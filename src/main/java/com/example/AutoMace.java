package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public class AutoMace {
    public enum State { IDLE, PRE_SMASH, SMASH_ATTACK, POST_SMASH }
    public static boolean enabled = false;
    public static boolean streamerMode = true; // liga o modo mais natural

    private static State state = State.IDLE;
    private static long lastActionTime = 0;
    private static int originalSlot = -1;
    private static boolean isSwapped = false;
    private static int delayTicks = 0;
    private static LivingEntity activeTarget = null;
    private static int targetLockTicks = 0;
    private static final Random RANDOM = new Random();

    // ===== MIRA SUAVE (igual ao Echo) =====
    private static float smoothYaw = Float.NaN;
    private static float smoothPitch = Float.NaN;
    private static final Queue<Float> yawHistory = new ConcurrentLinkedQueue<>();
    private static final int HIST_SIZE = 15;
    private static float targetYaw = 0, targetPitch = 0;
    private static boolean rotating = false;

    // ===== DELAYS E ERROS =====
    private static int missChance = 0;
    private static int hesitationTicks = 0;
    private static int pingTicks = 0;
    private static int cooldownTicks = 0;
    private static boolean hesitating = false;
    private static int clickDelay = 0;
    private static long lastClick = 0;

    // ===== KALMAN FILTER (predição) =====
    private static class KalmanFilter {
        double x, y, z, vx, vy, vz, px = 1, py = 1, pz = 1;
        final double q = 0.05, r = 0.1;
        void update(double mx, double my, double mz) {
            x += vx; y += vy; z += vz;
            px += q; py += q; pz += q;
            double kx = px / (px + r), ky = py / (py + r), kz = pz / (pz + r);
            x += kx * (mx - x); y += ky * (my - y); z += kz * (mz - z);
            vx += kx * (mx - x); vy += ky * (my - y); vz += kz * (mz - z);
            px *= (1 - kx); py *= (1 - ky); pz *= (1 - kz);
        }
        Vec3 predict(double dt) { return new Vec3(x + vx * dt, y + vy * dt, z + vz * dt); }
    }
    private static final Map<LivingEntity, KalmanFilter> kalmanMap = new HashMap<>();

    // ===== CÁLCULO DE DANO =====
    private static double calculateMaceDamage(RegistryAccess reg, ItemStack mace, double fallDist) {
        if (mace.isEmpty() || !mace.is(Items.MACE)) return 0;
        var lookup = reg.lookupOrThrow(Registries.ENCHANTMENT);
        Holder<Enchantment> density = lookup.getOrThrow(Enchantments.DENSITY);
        Holder<Enchantment> breach = lookup.getOrThrow(Enchantments.BREACH);
        double base = 6.0, fallDmg = 0;
        double remaining = fallDist;
        if (remaining > 0) {
            double first = Math.min(remaining, 3);
            fallDmg += first * 4;
            remaining -= first;
            if (remaining > 0) {
                double second = Math.min(remaining, 5);
                fallDmg += second * 2;
                remaining -= second;
            }
            if (remaining > 0) fallDmg += remaining;
        }
        int densityLvl = EnchantmentHelper.getItemEnchantmentLevel(density, mace);
        if (densityLvl > 0) fallDmg += fallDist * 0.5 * densityLvl;
        int breachLvl = EnchantmentHelper.getItemEnchantmentLevel(breach, mace);
        double total = base + fallDmg;
        if (breachLvl > 0) total *= (1 + breachLvl * 0.07);
        return total;
    }

    private static int findBestMaceByDamage(Minecraft client) {
        RegistryAccess reg = client.level.registryAccess();
        int bestSlot = -1;
        double bestDmg = -1;
        double fallDist = client.player.fallDistance;
        for (int i = 0; i < 9; i++) {
            ItemStack s = client.player.getInventory().getItem(i);
            if (s.isEmpty() || !s.is(Items.MACE)) continue;
            double dmg = calculateMaceDamage(reg, s, fallDist);
            if (dmg > bestDmg) { bestDmg = dmg; bestSlot = i; }
        }
        return bestSlot;
    }

    private static int totalTicks = 0, smashCount = 0;

    public static void onTick(Minecraft client) {
        if (!enabled || client.player == null || client.level == null) {
            if (isSwapped) resetState(client);
            return;
        }
        RegistryAccess reg = client.level.registryAccess();
        totalTicks++;
        if (cooldownTicks > 0) { cooldownTicks--; return; }
        if (pingTicks > 0) { pingTicks--; return; }
        if (delayTicks > 0) { delayTicks--; return; }
        if (hesitationTicks > 0) { hesitationTicks--; return; }
        if (clickDelay > 0) { clickDelay--; return; }
        if (hesitating && hesitationTicks <= 0) hesitating = false;

        // ===== ALVO (range 7.0) =====
        if (targetLockTicks <= 0 || activeTarget == null || !activeTarget.isAlive()) {
            activeTarget = findTarget(client, 7.0);
            targetLockTicks = 4 + RANDOM.nextInt(6);
        } else {
            targetLockTicks--;
        }
        if (activeTarget == null) { resetState(client); return; }
        if (client.player.getY() <= activeTarget.getY() + 0.3) {
            activeTarget = null; resetState(client); return;
        }

        // Kalman
        KalmanFilter kf = kalmanMap.computeIfAbsent(activeTarget, k -> new KalmanFilter());
        kf.update(activeTarget.getX(), activeTarget.getY(), activeTarget.getZ());

        // Hesitação leve (modo streamer)
        if (streamerMode && RANDOM.nextInt(100) < 2 && !hesitating && state == State.IDLE) {
            hesitating = true;
            hesitationTicks = 1 + RANDOM.nextInt(3);
            return;
        }

        // ===== VERIFICA QUEDA =====
        float fallThr = 2.0f + (streamerMode ? 0.3f : 0f) + RANDOM.nextFloat() * 0.4f;
        if (pingTicks > 0) fallThr += 0.2f;
        boolean falling = client.player.fallDistance >= fallThr
                && !client.player.onGround() && !client.player.isInWater();

        if (falling) {
            state = State.PRE_SMASH;
            // ===== MIRA SUAVE (estilo Echo) =====
            applySmoothAim(client, activeTarget, kf);

            // ===== DISTÂNCIA DE ATAQUE =====
            double hitDist = 2.85 + 0.05 + RANDOM.nextDouble() * 0.1;
            if (isMoving(activeTarget)) hitDist += 0.05;

            if (client.player.distanceTo(activeTarget) <= hitDist) {
                if (!canClick()) return;
                float strength = client.player.getAttackStrengthScale(0.0f);
                if (strength < 0.85f) return;
                if (!activeTarget.isAlive()) { resetState(client); return; }

                // ===== ERRO HUMANO =====
                int missRate = streamerMode ? 2 + RANDOM.nextInt(3) : 1 + RANDOM.nextInt(2);
                if (isMovingFast(activeTarget)) missRate += 1;
                if (state == State.SMASH_ATTACK) missRate -= 1;
                missRate = Math.max(0, Math.min(missRate, 6));
                if (RANDOM.nextInt(100) < missRate) {
                    missChance = 1 + RANDOM.nextInt(2);
                }
                if (missChance > 0) {
                    missChance--;
                    client.player.setYRot(client.player.getYRot() + (RANDOM.nextFloat() - 0.5f) * 15f);
                    return;
                }

                // ===== SHIELD BREAK =====
                boolean shielding = activeTarget instanceof Player p && p.isUsingItem()
                        && p.getUseItem().getItem() instanceof ShieldItem;
                if (shielding && state == State.PRE_SMASH) {
                    int axe = findAxeSlot(client);
                    if (axe != -1) {
                        if (originalSlot == -1) originalSlot = client.player.getInventory().getSelectedSlot();
                        if (delayTicks == 0) { delayTicks = 1 + RANDOM.nextInt(2); return; }
                        client.player.getInventory().setSelectedSlot(axe);
                        delayTicks = 1;
                        doAttack(client);
                        isSwapped = true;
                        state = State.SMASH_ATTACK;
                        lastActionTime = System.currentTimeMillis();
                        smashCount++;
                        return;
                    }
                }

                // ===== ATAQUE COM MAÇA =====
                if (state == State.SMASH_ATTACK || state == State.PRE_SMASH) {
                    int mace = findBestMaceByDamage(client);
                    if (mace == -1) {
                        mace = findBestMace(client, client.player.fallDistance > 4.0);
                    }
                    if (mace != -1) {
                        if (originalSlot == -1) originalSlot = client.player.getInventory().getSelectedSlot();
                        if (delayTicks == 0) { delayTicks = 1 + RANDOM.nextInt(2); return; }
                        client.player.getInventory().setSelectedSlot(mace);
                        isSwapped = true;
                    }
                    if (delayTicks == 0) { delayTicks = 1; return; }
                    doAttack(client);
                    lastActionTime = System.currentTimeMillis();
                    state = State.POST_SMASH;
                    cooldownTicks = 1 + RANDOM.nextInt(2);
                    pingTicks = 1 + RANDOM.nextInt(2);
                    delayTicks = 3 + RANDOM.nextInt(5);
                    hesitationTicks = 1 + RANDOM.nextInt(3);
                    resetState(client);
                    smashCount++;
                }
            }
        } else if (client.player.onGround()) {
            if (state != State.IDLE) { delayTicks = 1 + RANDOM.nextInt(2); resetState(client); }
            hesitating = false; hesitationTicks = 0;
        }
        if (totalTicks % 100 == 0 && smashCount > 50) { smashCount = 0; }
    }

    // ===== MIRA SUAVE (estilo Echo) =====
    private static void applySmoothAim(Minecraft client, LivingEntity target, KalmanFilter kf) {
        Vec3 eye = client.player.getEyePosition();
        Vec3 predicted = kf.predict(1.0);

        double heightOffset = 0.45 + RANDOM.nextDouble() * 0.1;
        double jX = RANDOM.nextGaussian() * 0.03;
        double jY = RANDOM.nextGaussian() * 0.025;
        double jZ = RANDOM.nextGaussian() * 0.03;
        if (streamerMode) { jX *= 1.3; jY *= 1.3; jZ *= 1.3; }

        Vec3 targetPoint = new Vec3(
            predicted.x + jX,
            predicted.y + (target.getBbHeight() * heightOffset) + jY,
            predicted.z + jZ
        );

        double dx = targetPoint.x - eye.x;
        double dy = targetPoint.y - eye.y;
        double dz = targetPoint.z - eye.z;
        double dist = Math.sqrt(dx * dx + dz * dz);

        float rawYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float rawPitch = (float) -Math.toDegrees(Math.atan2(dy, dist));

        if (Float.isNaN(smoothYaw) || Float.isNaN(smoothPitch)) {
            smoothYaw = client.player.getYRot();
            smoothPitch = client.player.getXRot();
        }

        float yawDiff = rawYaw - smoothYaw;
        while (yawDiff > 180) yawDiff -= 360;
        while (yawDiff < -180) yawDiff += 360;

        float pitchDiff = rawPitch - smoothPitch;
        while (pitchDiff > 180) pitchDiff -= 360;
        while (pitchDiff < -180) pitchDiff += 360;

        // ===== SPEED E ATTENTION (igual ao Echo) =====
        float maxTurn = 6.0f; // velocidade máxima por tick
        if (streamerMode) maxTurn = 5.0f;
        if (pingTicks > 0) maxTurn *= 0.8f;

        // Atenção: mais perto = mais rápido, mas com limite
        float attention = 0.85f;
        if (dist < 3.0) attention = 0.95f;
        else if (dist > 6.0) attention = 0.75f;
        if (state == State.SMASH_ATTACK) attention += 0.05f;
        attention = Math.min(attention, 0.95f);

        float stepY = Math.max(-maxTurn, Math.min(maxTurn, yawDiff * attention));
        float stepP = Math.max(-maxTurn * 0.6f, Math.min(maxTurn * 0.6f, pitchDiff * attention));

        // ===== OVERSHOOT NATURAL (igual ao Echo) =====
        if (RANDOM.nextInt(100) < 5 && Math.abs(yawDiff) > 2f) {
            float ov = 1.0f + RANDOM.nextFloat() * 1.5f;
            stepY += (yawDiff > 0 ? ov : -ov) * 0.2f;
        }

        // ===== JITTER E RUÍDO =====
        float noiseY = (RANDOM.nextFloat() - 0.5f) * 0.08f;
        float noiseP = (RANDOM.nextFloat() - 0.5f) * 0.05f;
        float finalY = smoothYaw + stepY + noiseY;
        float finalP = Math.max(-90f, Math.min(90f, smoothPitch + stepP + noiseP));

        // ===== GCD (respeita o snapping do jogo) =====
        Options opt = client.options;
        double sens = opt.sensitivity().get() * 0.6 + 0.2;
        double gcd = Math.pow(sens, 1.2);
        gcd = Math.max(0.04, Math.min(0.4, gcd));
        float dY = finalY - client.player.getYRot();
        float dP = finalP - client.player.getXRot();
        if (Math.abs(dY) > 0.02 || Math.abs(dP) > 0.02) {
            dY = (float) (Math.round(dY / gcd) * gcd);
            dP = (float) (Math.round(dP / gcd) * gcd);
        }

        smoothYaw = client.player.getYRot() + dY;
        smoothPitch = client.player.getXRot() + dP;

        // ===== APLICA ROTAÇÃO =====
        client.player.setYRot(smoothYaw);
        client.player.setXRot(smoothPitch);
        client.player.yRotO = smoothYaw - (RANDOM.nextFloat() - 0.5f) * 0.3f;
        client.player.xRotO = smoothPitch - (RANDOM.nextFloat() - 0.5f) * 0.2f;
        client.player.yHeadRot = smoothYaw;
        client.player.yHeadRotO = smoothYaw;
        if (yawHistory.size() >= HIST_SIZE) yawHistory.poll();
        yawHistory.add(smoothYaw);
    }

    // ===== CLICK SIM =====
    private static boolean canClick() {
        long now = System.currentTimeMillis();
        if (lastClick == 0) { lastClick = now; return true; }
        long elapsed = now - lastClick;
        int cps = streamerMode ? 9 + RANDOM.nextInt(6) : 12 + RANDOM.nextInt(5);
        int delay = (int)((1000.0 / cps) * (0.85 + RANDOM.nextDouble() * 0.20));
        if (elapsed >= delay) { lastClick = now; return true; }
        return false;
    }

    private static void doAttack(Minecraft client) {
        if (activeTarget == null || !activeTarget.isAlive()) return;
        client.gameMode.attack(client.player, activeTarget);
        client.player.swing(InteractionHand.MAIN_HAND);
        int cps = streamerMode ? 10 + RANDOM.nextInt(5) : 13 + RANDOM.nextInt(4);
        int delay = (int)((1000.0 / cps) * (0.85 + RANDOM.nextDouble() * 0.20));
        clickDelay = delay / 50;
        if (clickDelay < 1) clickDelay = 1;
    }

    // ===== PREDIÇÃO DE MOVIMENTO =====
    private static boolean isMoving(LivingEntity target) {
        if (target == null) return false;
        Vec3 vel = kalmanMap.getOrDefault(target, new KalmanFilter()).predict(1).subtract(target.position());
        return vel.length() > 0.05;
    }

    private static boolean isMovingFast(LivingEntity target) {
        if (target == null) return false;
        Vec3 vel = kalmanMap.getOrDefault(target, new KalmanFilter()).predict(1).subtract(target.position());
        return vel.length() > 0.3;
    }

    // ===== SELEÇÃO DE ALVO (range 7.0) =====
    private static LivingEntity findTarget(Minecraft client, double range) {
        AABB box = client.player.getBoundingBox().inflate(range, 350, range);
        List<LivingEntity> list = client.level.getEntitiesOfClass(LivingEntity.class, box, e ->
            e != client.player && e.isAlive() && !e.isDeadOrDying()
            && client.player.getY() > e.getY() + 0.3 && !e.isInvisible() && e.getHealth() > 0
        );
        if (list.isEmpty()) return null;
        RegistryAccess reg = client.level.registryAccess();
        return list.stream().max((a, b) -> {
            double fallDist = client.player.fallDistance;
            double da = calculateMaceDamage(reg, new ItemStack(Items.MACE), fallDist);
            double db = calculateMaceDamage(reg, new ItemStack(Items.MACE), fallDist);
            double sa = (100 + da) - client.player.distanceToSqr(a) * 0.03;
            double sb = (100 + db) - client.player.distanceToSqr(b) * 0.03;
            if (a instanceof Player) sa += 15;
            if (b instanceof Player) sb += 15;
            sa += (20 - a.getHealth()) * 1.5;
            sb += (20 - b.getHealth()) * 1.5;
            return Double.compare(sa, sb);
        }).orElse(null);
    }

    // ===== SLOTS E ENCANTAMENTOS =====
    private static int findAxeSlot(Minecraft client) {
        RegistryAccess reg = client.level.registryAccess();
        var lookup = reg.lookupOrThrow(Registries.ENCHANTMENT);
        Holder<Enchantment> sharp = lookup.getOrThrow(Enchantments.SHARPNESS);
        for (int i = 0; i < 9; i++) {
            ItemStack s = client.player.getInventory().getItem(i);
            if (s.getItem() instanceof AxeItem && EnchantmentHelper.getItemEnchantmentLevel(sharp, s) >= 0) return i;
        }
        return -1;
    }

    private static int findBestMace(Minecraft client, boolean preferDensity) {
        RegistryAccess reg = client.level.registryAccess();
        var lookup = reg.lookupOrThrow(Registries.ENCHANTMENT);
        Holder<Enchantment> enchant = preferDensity ? lookup.getOrThrow(Enchantments.DENSITY) : lookup.getOrThrow(Enchantments.BREACH);
        int best = -1, bestLvl = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack s = client.player.getInventory().getItem(i);
            if (s.isEmpty() || !s.is(Items.MACE)) continue;
            int lvl = EnchantmentHelper.getItemEnchantmentLevel(enchant, s);
            if (lvl > bestLvl) { bestLvl = lvl; best = i; }
        }
        return best;
    }

    // ===== RESET =====
    private static void resetState(Minecraft client) {
        if (isSwapped && originalSlot != -1 && client.player != null) {
            if (System.currentTimeMillis() - lastActionTime > 150) {
                client.player.getInventory().setSelectedSlot(originalSlot);
            }
        }
        originalSlot = -1; isSwapped = false; state = State.IDLE;
        if (Float.isNaN(smoothYaw)) {
            smoothYaw = client.player != null ? client.player.getYRot() : 0;
            smoothPitch = client.player != null ? client.player.getXRot() : 0;
        }
        cooldownTicks = 0; pingTicks = 0; hesitating = false; hesitationTicks = 0; clickDelay = 0;
    }

    public static void toggle() {
        enabled = !enabled;
        if (enabled) {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                smoothYaw = client.player.getYRot();
                smoothPitch = client.player.getXRot();
            }
            kalmanMap.clear();
        } else {
            resetState(Minecraft.getInstance());
        }
    }
    public static void reset() { resetState(Minecraft.getInstance()); }
    }
