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
    public enum Mode { STEALTH, FAST, STREAMER }

    public static boolean enabled = false;
    public static Mode currentMode = Mode.STEALTH;
    public static boolean streamerMode = false;

    private static State state = State.IDLE;
    private static long lastActionTime = 0;
    private static int originalSlot = -1;
    private static boolean isSwapped = false;
    private static int delayTicks = 0;
    private static LivingEntity activeTarget = null;
    private static int targetLockTicks = 0;
    private static final Random RANDOM = new Random();

    private static float smoothYaw = Float.NaN;
    private static float smoothPitch = Float.NaN;
    private static final Queue<Float> yawHist = new ConcurrentLinkedQueue<>();
    private static final int HIST_SIZE = 10;

    private static int missChance = 0;
    private static int hesitationTicks = 0;
    private static int pingTicks = 0;
    private static int cooldownTicks = 0;
    private static boolean hesitating = false;
    private static int clickDelay = 0;
    private static long lastClick = 0;

    private static final Map<LivingEntity, Vec3> velMap = new HashMap<>();
    private static final Map<LivingEntity, Long> velTime = new HashMap<>();
    private static int totalTicks = 0, smashCount = 0;

    public static void onTick(Minecraft client) {
        if (!enabled || client.player == null || client.level == null) {
            if (isSwapped) resetState(client);
            return;
        }
        totalTicks++;
        if (cooldownTicks > 0) { cooldownTicks--; return; }
        if (pingTicks > 0) { pingTicks--; return; }
        if (delayTicks > 0) { delayTicks--; return; }
        if (hesitationTicks > 0) { hesitationTicks--; return; }
        if (clickDelay > 0) { clickDelay--; return; }
        if (hesitating && hesitationTicks <= 0) hesitating = false;

        if (targetLockTicks <= 0 || activeTarget == null || !activeTarget.isAlive()) {
            activeTarget = findTarget(client);
            targetLockTicks = 6 + RANDOM.nextInt(10);
        } else {
            targetLockTicks--;
        }
        if (activeTarget == null) { resetState(client); return; }
        if (client.player.getY() <= activeTarget.getY() + 0.3) {
            activeTarget = null; resetState(client); return;
        }

        updateVelocity(activeTarget);

        int hChance = (currentMode == Mode.FAST) ? 2 : (streamerMode ? 7 : 4);
        if (RANDOM.nextInt(100) < hChance && !hesitating && state == State.IDLE) {
            hesitating = true;
            hesitationTicks = 2 + RANDOM.nextInt(6);
            return;
        }

        float fallThr = 2.8f + 0.2f + RANDOM.nextFloat() * 0.4f;
        if (streamerMode) fallThr += 0.3f;
        if (pingTicks > 0) fallThr += 0.2f;
        boolean falling = client.player.fallDistance >= fallThr
                && !client.player.onGround() && !client.player.isInWater();

        if (falling) {
            state = State.PRE_SMASH;
            applyAim(client, activeTarget);
            double hitDist = 2.85 + 0.1 + RANDOM.nextDouble() * 0.2;
            if (activeTarget != null && isMoving()) hitDist += 0.1;
            if (client.player.distanceTo(activeTarget) <= hitDist) {
                if (!canClick()) return;
                float strength = client.player.getAttackStrengthScale(0.0f);
                if (strength < 0.85f) return;
                if (!activeTarget.isAlive()) { resetState(client); return; }

                int missRate = (currentMode == Mode.FAST) ? 2 : 3;
                if (streamerMode) missRate += 5;
                if (activeTarget != null && isMovingFast()) missRate += 2;
                if (state == State.SMASH_ATTACK) missRate -= 1;
                missRate = Math.min(missRate, 10);
                if (RANDOM.nextInt(100) < missRate) {
                    missChance = 1 + RANDOM.nextInt(2);
                }
                if (missChance > 0) {
                    missChance--;
                    client.player.setYRot(client.player.getYRot() + (RANDOM.nextFloat() - 0.5f) * 20f);
                    return;
                }

                boolean shielding = activeTarget instanceof Player p && p.isUsingItem()
                        && p.getUseItem().getItem() instanceof ShieldItem;

                if (shielding && state == State.PRE_SMASH) {
                    int axe = findAxeSlot(client);
                    if (axe != -1) {
                        if (originalSlot == -1) originalSlot = client.player.getInventory().getSelectedSlot();
                        if (delayTicks == 0) { delayTicks = 2 + RANDOM.nextInt(3); return; }
                        client.player.getInventory().setSelectedSlot(axe);
                        delayTicks = 1 + RANDOM.nextInt(2);
                        doAttack(client);
                        isSwapped = true;
                        state = State.SMASH_ATTACK;
                        lastActionTime = System.currentTimeMillis();
                        smashCount++;
                        return;
                    }
                }

                if (state == State.SMASH_ATTACK || state == State.PRE_SMASH) {
                    boolean preferDens = client.player.fallDistance > (6.0 + RANDOM.nextDouble() * 2.0);
                    int mace = findBestMace(client, preferDens);
                    if (mace != -1) {
                        if (originalSlot == -1) originalSlot = client.player.getInventory().getSelectedSlot();
                        if (delayTicks == 0) { delayTicks = 2 + RANDOM.nextInt(3); return; }
                        client.player.getInventory().setSelectedSlot(mace);
                        isSwapped = true;
                    }
                    if (delayTicks == 0) { delayTicks = 1 + RANDOM.nextInt(2); return; }
                    doAttack(client);
                    lastActionTime = System.currentTimeMillis();
                    state = State.POST_SMASH;
                    cooldownTicks = 2 + RANDOM.nextInt(3);
                    pingTicks = 1 + RANDOM.nextInt(3);
                    delayTicks = 4 + RANDOM.nextInt(8);
                    hesitationTicks = 2 + RANDOM.nextInt(5);
                    resetState(client);
                    smashCount++;
                }
            }
        } else if (client.player.onGround()) {
            if (state != State.IDLE) { delayTicks = 2 + RANDOM.nextInt(3); resetState(client); }
            hesitating = false; hesitationTicks = 0;
        }
        if (totalTicks % 100 == 0 && smashCount > 50) { smashCount = 0; }
    }

    private static void applyAim(Minecraft client, LivingEntity target) {
        Vec3 eye = client.player.getEyePosition();
        Vec3 pred = predictPos(target);
        double hOff = 0.3 + RANDOM.nextDouble() * 0.4;
        double jX = RANDOM.nextGaussian() * 0.06;
        double jY = RANDOM.nextGaussian() * 0.055;
        double jZ = RANDOM.nextGaussian() * 0.06;
        if (streamerMode) { jX *= 1.3; jY *= 1.3; jZ *= 1.3; }
        Vec3 pt = new Vec3(pred.x + jX, pred.y + (target.getBbHeight() * hOff) + jY, pred.z + jZ);
        double dx = pt.x - eye.x, dy = pt.y - eye.y, dz = pt.z - eye.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        float rawYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
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

        float attn = 0.55f + (float)(1.0 / (dist + 0.5)) * 0.25f;
        if (attn > 0.85f) attn = 0.85f;
        if (state == State.SMASH_ATTACK) attn += 0.05f;
        float maxTurn = (currentMode == Mode.FAST) ? 4f : 3f;
        if (streamerMode) maxTurn = 2.5f;
        if (pingTicks > 0) maxTurn *= 0.8f;

        float stepY = Math.max(-maxTurn, Math.min(maxTurn, yawDiff * attn));
        float stepP = Math.max(-maxTurn * 0.6f, Math.min(maxTurn * 0.6f, pitchDiff * attn));
        if (RANDOM.nextInt(100) < 5 && Math.abs(yawDiff) > 2f) {
            float ov = 1.5f + RANDOM.nextFloat() * 2f;
            stepY += (yawDiff > 0 ? ov : -ov) * 0.3f;
        }
        float noiseY = (RANDOM.nextFloat() - 0.5f) * 0.12f;
        float noiseP = (RANDOM.nextFloat() - 0.5f) * 0.08f;
        float finalY = smoothYaw + stepY + noiseY;
        float finalP = Math.max(-90f, Math.min(90f, smoothPitch + stepP + noiseP));

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
        client.player.setYRot(smoothYaw);
        client.player.setXRot(smoothPitch);
        client.player.yRotO = smoothYaw - (RANDOM.nextFloat() - 0.5f) * 0.4f;
        client.player.xRotO = smoothPitch - (RANDOM.nextFloat() - 0.5f) * 0.3f;
        client.player.yHeadRot = smoothYaw;
        client.player.yHeadRotO = smoothYaw;
        if (yawHist.size() >= HIST_SIZE) yawHist.poll();
        yawHist.add(smoothYaw);
    }

    private static boolean canClick() {
        long now = System.currentTimeMillis();
        if (lastClick == 0) { lastClick = now; return true; }
        long elapsed = now - lastClick;
        int cps;
        if (currentMode == Mode.FAST) {
            cps = (int) (10 + RANDOM.nextGaussian() * 3);
            cps = Math.max(7, Math.min(16, cps));
        } else if (streamerMode) {
            cps = (int) (8 + RANDOM.nextGaussian() * 2.5);
            cps = Math.max(6, Math.min(13, cps));
        } else {
            cps = (int) (9 + RANDOM.nextGaussian() * 2.8);
            cps = Math.max(7, Math.min(14, cps));
        }
        int delay = (int)((1000.0 / cps) * (0.80 + RANDOM.nextDouble() * 0.35));
        if (elapsed >= delay) { lastClick = now; return true; }
        return false;
    }

    private static void doAttack(Minecraft client) {
        if (activeTarget == null || !activeTarget.isAlive()) return;
        client.gameMode.attack(client.player, activeTarget);
        client.player.swing(InteractionHand.MAIN_HAND);
        int cps = (currentMode == Mode.FAST) ? 10 + RANDOM.nextInt(6) : 8 + RANDOM.nextInt(5);
        int delay = (int)((1000.0 / cps) * (0.80 + RANDOM.nextDouble() * 0.35));
        clickDelay = delay / 50;
        if (clickDelay < 1) clickDelay = 1;
    }

    private static Vec3 predictPos(LivingEntity target) {
        Vec3 pos = target.position();
        Vec3 vel = velMap.getOrDefault(target, Vec3.ZERO);
        double factor = 0.06 + RANDOM.nextDouble() * 0.1;
        if (vel.length() > 0.5) factor += 0.03;
        return pos.add(vel.scale(factor));
    }

    private static void updateVelocity(LivingEntity target) {
        long now = System.currentTimeMillis();
        Vec3 cur = target.position();
        Vec3 prev = velMap.getOrDefault(target, cur);
        long dt = velTime.containsKey(target) ? now - velTime.get(target) : 50;
        if (dt > 0) {
            Vec3 vel = cur.subtract(prev).scale(1000.0 / dt);
            velMap.put(target, vel);
        }
        velTime.put(target, now);
    }

    private static boolean isMoving() {
        if (activeTarget == null) return false;
        Vec3 v = velMap.getOrDefault(activeTarget, Vec3.ZERO);
        return v.length() > 0.1;
    }

    private static boolean isMovingFast() {
        if (activeTarget == null) return false;
        Vec3 v = velMap.getOrDefault(activeTarget, Vec3.ZERO);
        return v.length() > 0.5;
    }

    private static LivingEntity findTarget(Minecraft client) {
        AABB box = client.player.getBoundingBox().inflate(6.5, 350, 6.5);
        List<LivingEntity> list = client.level.getEntitiesOfClass(LivingEntity.class, box, e ->
            e != client.player && e.isAlive() && !e.isDeadOrDying()
            && client.player.getY() > e.getY() + 0.3 && !e.isInvisible() && e.getHealth() > 0
        );
        if (list.isEmpty()) return null;
        return list.stream().max((a, b) -> {
            double sa = 100 + (20 - a.getHealth()) * 2 - client.player.distanceToSqr(a) * 0.05;
            double sb = 100 + (20 - b.getHealth()) * 2 - client.player.distanceToSqr(b) * 0.05;
            if (a instanceof Player) sa += 15;
            if (b instanceof Player) sb += 15;
            return Double.compare(sa, sb);
        }).orElse(null);
    }

    private static int findAxeSlot(Minecraft client) {
        RegistryAccess reg = client.level.registryAccess();
        for (int i = 0; i < 9; i++) {
            ItemStack s = client.player.getInventory().getItem(i);
            if (s.getItem() instanceof AxeItem) {
                int lvl = getEnchantLevel(reg, Enchantments.SHARPNESS, s);
                if (lvl >= 0) return i;
            }
        }
        return -1;
    }

    private static int findBestMace(Minecraft client, boolean preferDensity) {
        RegistryAccess reg = client.level.registryAccess();
        var key = preferDensity ? Enchantments.DENSITY : Enchantments.BREACH;
        int best = -1, bestLvl = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack s = client.player.getInventory().getItem(i);
            if (s.isEmpty() || !s.is(Items.MACE)) continue;
            int lvl = getEnchantLevel(reg, key, s);
            if (lvl > bestLvl) { bestLvl = lvl; best = i; }
        }
        return best;
    }

    private static int getEnchantLevel(RegistryAccess reg, net.minecraft.resources.ResourceKey<Enchantment> key, ItemStack stack) {
        var lookup = reg.lookupOrThrow(Registries.ENCHANTMENT);
        Optional<Holder.Reference<Enchantment>> holder = lookup.get(key);
        return holder.map(h -> EnchantmentHelper.getItemEnchantmentLevel(h, stack)).orElse(0);
    }

    private static void resetState(Minecraft client) {
        if (isSwapped && originalSlot != -1 && client.player != null) {
            if (System.currentTimeMillis() - lastActionTime > 200) {
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
        } else {
            resetState(Minecraft.getInstance());
        }
    }
    public static void reset() { resetState(Minecraft.getInstance()); }
            }
