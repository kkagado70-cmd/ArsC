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
    public enum State { IDLE, TARGETING, APPROACHING, PRE_SMASH, SMASH_ATTACK, POST_SMASH }
    public enum Mode { STEALTH, FAST, STREAMER }

    public static boolean enabled = false;
    public static Mode currentMode = Mode.FAST;
    public static boolean streamerMode = false;

    private static State state = State.IDLE;
    private static long lastActionTime = 0;
    private static int originalSlot = -1;
    private static boolean isSwapped = false;
    private static int delayTicks = 0;
    private static LivingEntity activeTarget = null;
    private static int targetLockTicks = 0;
    private static final Random RANDOM = new Random();

    // Mira
    private static float smoothYaw = Float.NaN;
    private static float smoothPitch = Float.NaN;
    private static final Queue<Float> yawHistory = new ConcurrentLinkedQueue<>();
    private static final Queue<Float> pitchHistory = new ConcurrentLinkedQueue<>();
    private static final int HISTORY_SIZE = 15;

    // Erro humano
    private static int missChance = 0;
    private static int hesitationTicks = 0;
    private static int pingSimulationTicks = 0;
    private static int attackCooldownTicks = 0;
    private static boolean isHesitating = false;

    // ClickSim
    private static int clickDelayTicks = 0;
    private static long lastClickTime = 0;

    // Predição
    private static final Map<LivingEntity, Vec3> targetVelocities = new HashMap<>();
    private static final Map<LivingEntity, Long> lastVelocityUpdate = new HashMap<>();

    private static int totalTicks = 0;
    private static int successfulSmash = 0;
    private static int missedSmash = 0;

    public static void onTick(Minecraft client) {
        if (!enabled || client.player == null || client.level == null) {
            if (isSwapped) resetState(client);
            return;
        }

        totalTicks++;
        if (attackCooldownTicks > 0) { attackCooldownTicks--; return; }
        if (pingSimulationTicks > 0) { pingSimulationTicks--; return; }
        if (delayTicks > 0) { delayTicks--; return; }
        if (hesitationTicks > 0) { hesitationTicks--; return; }
        if (clickDelayTicks > 0) { clickDelayTicks--; return; }
        if (isHesitating && hesitationTicks <= 0) isHesitating = false;

        if (targetLockTicks <= 0 || activeTarget == null || !activeTarget.isAlive()) {
            activeTarget = findOptimalTarget(client);
            targetLockTicks = 5 + RANDOM.nextInt(10);
        } else {
            targetLockTicks--;
        }
        if (activeTarget == null) { resetState(client); return; }
        if (client.player.getY() <= activeTarget.getY() + 0.3) {
            activeTarget = null; resetState(client); return;
        }

        updateTargetVelocity(client, activeTarget);

        // Hesitação (menos em modo FAST)
        int hesitChance = (currentMode == Mode.FAST) ? 1 : (streamerMode ? 5 : 2);
        if (RANDOM.nextInt(100) < hesitChance && !isHesitating && state == State.IDLE) {
            isHesitating = true;
            hesitationTicks = 2 + RANDOM.nextInt(4);
            return;
        }

        float fallThreshold = calculateFallThreshold(client);
        boolean isFalling = client.player.fallDistance >= fallThreshold
                && !client.player.onGround() && !client.player.isInWater();

        if (isFalling) {
            state = State.PRE_SMASH;
            applyFastAim(client, activeTarget);

            double hitDist = calculateHitDistance(client);
            if (client.player.distanceTo(activeTarget) <= hitDist) {
                if (!canClick()) return;
                float strength = client.player.getAttackStrengthScale(0.0f);
                if (strength < 0.85f) return;

                int missRate = calculateMissRate(client);
                if (RANDOM.nextInt(100) < missRate) {
                    missChance = 1 + RANDOM.nextInt(2);
                }
                if (missChance > 0) {
                    missChance--;
                    applyMissAim(client);
                    missedSmash++;
                    return;
                }

                boolean isShielding = activeTarget instanceof Player p
                        && p.isUsingItem()
                        && p.getUseItem().getItem() instanceof ShieldItem;

                if (isShielding && state == State.PRE_SMASH) {
                    int axeSlot = findAxeSlot(client);
                    if (axeSlot != -1) {
                        if (originalSlot == -1) originalSlot = client.player.getInventory().getSelectedSlot();
                        client.player.getInventory().setSelectedSlot(axeSlot);
                        performAttack(client);
                        isSwapped = true;
                        state = State.SMASH_ATTACK;
                        delayTicks = 2 + RANDOM.nextInt(4);
                        lastActionTime = System.currentTimeMillis();
                        successfulSmash++;
                        return;
                    }
                }

                if (state == State.SMASH_ATTACK || state == State.PRE_SMASH) {
                    boolean preferDensity = client.player.fallDistance > (6.0 + RANDOM.nextDouble() * 2.0);
                    int maceSlot = findBestMaceSlot(client, preferDensity);
                    if (maceSlot != -1) {
                        if (originalSlot == -1) originalSlot = client.player.getInventory().getSelectedSlot();
                        client.player.getInventory().setSelectedSlot(maceSlot);
                        isSwapped = true;
                    }
                    performAttack(client);
                    lastActionTime = System.currentTimeMillis();
                    state = State.POST_SMASH;
                    attackCooldownTicks = 2 + RANDOM.nextInt(3);
                    pingSimulationTicks = 1 + RANDOM.nextInt(2);
                    delayTicks = 4 + RANDOM.nextInt(8);
                    hesitationTicks = 2 + RANDOM.nextInt(4);
                    resetState(client);
                    successfulSmash++;
                }
            } else {
                state = State.APPROACHING;
            }
        } else if (client.player.onGround()) {
            if (state != State.IDLE && state != State.TARGETING) {
                delayTicks = 2 + RANDOM.nextInt(3);
                resetState(client);
            }
            hesitationTicks = 0;
            isHesitating = false;
        }
        updateHeuristics(client);
    }

    // ============================================================
    // MIRA RÁPIDA (mas com curvas humanas)
    // ============================================================
    private static void applyFastAim(Minecraft client, LivingEntity target) {
        Vec3 eyePos = client.player.getEyePosition();
        Vec3 predictedPos = predictTargetPosition(target);

        double heightOffset = 0.3 + RANDOM.nextDouble() * 0.4;
        double jX = (RANDOM.nextGaussian()) * 0.04;
        double jY = (RANDOM.nextGaussian()) * 0.035;
        double jZ = (RANDOM.nextGaussian()) * 0.04;

        if (streamerMode) {
            jX *= 1.5; jY *= 1.5; jZ *= 1.5;
        }

        Vec3 targetPoint = new Vec3(
            predictedPos.x + jX,
            predictedPos.y + (target.getBbHeight() * heightOffset) + jY,
            predictedPos.z + jZ
        );

        double dx = targetPoint.x - eyePos.x;
        double dy = targetPoint.y - eyePos.y;
        double dz = targetPoint.z - eyePos.z;
        double dist = Math.sqrt(dx * dx + dz * dz);

        float rawYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        float rawPitch = (float) -Math.toDegrees(Math.atan2(dy, dist));

        if (Float.isNaN(smoothYaw) || Float.isNaN(smoothPitch)) {
            smoothYaw = client.player.getYRot();
            smoothPitch = client.player.getXRot();
        }

        float speedFactor = (currentMode == Mode.FAST) ? 1.0f : 0.6f;
        if (streamerMode) speedFactor = 0.5f;

        float overshootAmount = 0.12f + RANDOM.nextFloat() * 0.08f;
        float overshootYaw = (rawYaw - smoothYaw) * overshootAmount * speedFactor;
        float overshootPitch = (rawPitch - smoothPitch) * overshootAmount * speedFactor;

        float attention = 0.7f + (float)(1.0 / (dist + 0.5)) * 0.25f;
        if (state == State.SMASH_ATTACK) attention += 0.1f;

        float yawDiff = wrapAngle(rawYaw - smoothYaw);
        float pitchDiff = rawPitch - smoothPitch;

        float maxTurn = (currentMode == Mode.FAST) ? 8.0f + RANDOM.nextFloat() * 4.0f : 5.0f + RANDOM.nextFloat() * 2.0f;
        if (streamerMode) maxTurn *= 0.7f;
        if (pingSimulationTicks > 0) maxTurn *= 0.8f;

        yawDiff = Math.max(-maxTurn, Math.min(maxTurn, yawDiff * attention));
        pitchDiff = Math.max(-maxTurn * 0.65f, Math.min(maxTurn * 0.65f, pitchDiff * attention));

        yawDiff += overshootYaw * 0.5f;
        pitchDiff += overshootPitch * 0.5f;

        float noiseYaw = (RANDOM.nextFloat() - 0.5f) * 0.12f;
        float noisePitch = (RANDOM.nextFloat() - 0.5f) * 0.08f;

        float finalYaw = smoothYaw + yawDiff + noiseYaw;
        float finalPitch = Math.max(-90.0f, Math.min(90.0f, smoothPitch + pitchDiff + noisePitch));

        Options opt = client.options;
        double sens = opt.sensitivity().get() * 0.6 + 0.2;
        double gcd = Math.pow(sens, 1.2);
        gcd = Math.max(0.04, Math.min(0.4, gcd));

        float dYaw = finalYaw - client.player.getYRot();
        float dPitch = finalPitch - client.player.getXRot();

        if (Math.abs(dYaw) > 0.015 || Math.abs(dPitch) > 0.015) {
            dYaw = (float) (Math.round(dYaw / gcd) * gcd);
            dPitch = (float) (Math.round(dPitch / gcd) * gcd);
        }

        smoothYaw = client.player.getYRot() + dYaw;
        smoothPitch = client.player.getXRot() + dPitch;

        client.player.setYRot(smoothYaw);
        client.player.setXRot(smoothPitch);
        client.player.yRotO = smoothYaw - (RANDOM.nextFloat() - 0.5f) * 0.5f;
        client.player.xRotO = smoothPitch - (RANDOM.nextFloat() - 0.5f) * 0.35f;
        client.player.yHeadRot = smoothYaw;
        client.player.yHeadRotO = smoothYaw;

        updateHistory(smoothYaw, smoothPitch);
    }

    // ============================================================
    // CLICKSIM
    // ============================================================
    private static boolean canClick() {
        long now = System.currentTimeMillis();
        if (lastClickTime == 0) { lastClickTime = now; return true; }
        long elapsed = now - lastClickTime;
        int cps;
        if (currentMode == Mode.FAST) {
            cps = 10 + RANDOM.nextInt(6);
        } else if (streamerMode) {
            cps = 8 + RANDOM.nextInt(4);
        } else {
            cps = 9 + RANDOM.nextInt(5);
        }
        int delayMs = 1000 / cps;
        double jitter = 0.85 + RANDOM.nextDouble() * 0.3;
        int finalDelay = (int)(delayMs * jitter);
        if (elapsed >= finalDelay) {
            lastClickTime = now;
            return true;
        }
        return false;
    }

    private static void performAttack(Minecraft client) {
        client.gameMode.attack(client.player, activeTarget);
        client.player.swing(InteractionHand.MAIN_HAND);
        int cps = (currentMode == Mode.FAST) ? 12 + RANDOM.nextInt(6) : 9 + RANDOM.nextInt(5);
        int delayMs = 1000 / cps;
        double jitter = 0.85 + RANDOM.nextDouble() * 0.3;
        clickDelayTicks = (int)((delayMs * jitter) / 50);
        if (clickDelayTicks < 1) clickDelayTicks = 1;
    }

    // ============================================================
    // HEURÍSTICAS
    // ============================================================
    private static float calculateFallThreshold(Minecraft client) {
        float base = 2.8f;
        float var = 0f;
        if (currentMode == Mode.FAST) var = 0.1f + RANDOM.nextFloat() * 0.2f;
        else if (streamerMode) var = 0.5f + RANDOM.nextFloat() * 0.5f;
        else var = 0.3f + RANDOM.nextFloat() * 0.4f;
        if (pingSimulationTicks > 0) var += 0.2f;
        return base + var;
    }

    private static double calculateHitDistance(Minecraft client) {
        double base = 2.85;
        double var = 0f;
        if (currentMode == Mode.FAST) var = 0.05 + RANDOM.nextDouble() * 0.1;
        else if (streamerMode) var = 0.2 + RANDOM.nextDouble() * 0.3;
        else var = 0.1 + RANDOM.nextDouble() * 0.2;
        if (activeTarget != null && isTargetMoving()) var += 0.1;
        return base + var;
    }

    private static int calculateMissRate(Minecraft client) {
        int base = (currentMode == Mode.FAST) ? 1 : 2;
        int add = 0;
        if (streamerMode) add += 4;
        if (activeTarget != null && isTargetMovingFast()) add += 2;
        if (state == State.SMASH_ATTACK) add -= 1;
        if (successfulSmash > 10) add += (successfulSmash / 15);
        add = Math.min(add, 12);
        return base + add;
    }

    private static boolean isTargetMoving() {
        if (activeTarget == null) return false;
        Vec3 vel = targetVelocities.getOrDefault(activeTarget, Vec3.ZERO);
        return vel.length() > 0.1;
    }

    private static boolean isTargetMovingFast() {
        if (activeTarget == null) return false;
        Vec3 vel = targetVelocities.getOrDefault(activeTarget, Vec3.ZERO);
        return vel.length() > 0.5;
    }

    // ============================================================
    // PREDIÇÃO E VELOCIDADE
    // ============================================================
    private static Vec3 predictTargetPosition(LivingEntity target) {
        Vec3 pos = target.position();
        Vec3 vel = targetVelocities.getOrDefault(target, Vec3.ZERO);
        double predFactor = 0.08 + RANDOM.nextDouble() * 0.12;
        if (vel.length() > 0.5) predFactor += 0.05;
        return pos.add(vel.scale(predFactor));
    }

    private static void updateTargetVelocity(Minecraft client, LivingEntity target) {
        long now = System.currentTimeMillis();
        Vec3 currentPos = target.position();
        Vec3 previousPos = targetVelocities.getOrDefault(target, currentPos);
        long dt = lastVelocityUpdate.containsKey(target) ? now - lastVelocityUpdate.get(target) : 50;
        if (dt > 0) {
            Vec3 vel = currentPos.subtract(previousPos).scale(1000.0 / dt);
            targetVelocities.put(target, vel);
        }
        lastVelocityUpdate.put(target, now);
    }

    // ============================================================
    // ERRO E RESET
    // ============================================================
    private static void applyMissAim(Minecraft client) {
        float missYaw = (RANDOM.nextFloat() - 0.5f) * 25f;
        float missPitch = (RANDOM.nextFloat() - 0.5f) * 12f;
        if (streamerMode) { missYaw *= 1.3f; missPitch *= 1.3f; }
        client.player.setYRot(client.player.getYRot() + missYaw);
        client.player.setXRot(client.player.getXRot() + missPitch);
    }

    private static LivingEntity findOptimalTarget(Minecraft client) {
        AABB box = client.player.getBoundingBox().inflate(7.0, 400, 7.0);
        List<LivingEntity> entities = client.level.getEntitiesOfClass(LivingEntity.class, box, e ->
            e != client.player && e.isAlive() && !e.isDeadOrDying()
            && client.player.getY() > e.getY() + 0.3 && !e.isInvisible() && e.getHealth() > 0
        );
        if (entities.isEmpty()) return null;
        return entities.stream()
            .max((a, b) -> Double.compare(calculateTargetScore(client, a), calculateTargetScore(client, b)))
            .orElse(null);
    }

    private static double calculateTargetScore(Minecraft client, LivingEntity target) {
        double score = 100.0;
        score += (20 - target.getHealth()) * 2;
        score -= client.player.distanceToSqr(target) * 0.05;
        Vec3 vel = targetVelocities.getOrDefault(target, Vec3.ZERO);
        if (vel.length() > 0.2) score += 5;
        if (target instanceof Player) score += 20;
        if (target instanceof Player && ((Player) target).isUsingItem()
            && ((Player) target).getUseItem().getItem() instanceof ShieldItem) score += 15;
        if (activeTarget == target && state != State.IDLE) score -= 5;
        return score;
    }

    private static int findAxeSlot(Minecraft client) {
        int bestSlot = -1, bestLevel = -1;
        RegistryAccess reg = client.level.registryAccess();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack.getItem() instanceof AxeItem) {
                int level = getEnchantmentLevel(reg, Enchantments.SHARPNESS, stack);
                if (level > bestLevel) { bestLevel = level; bestSlot = i; }
            }
        }
        return bestSlot;
    }

    private static int findBestMaceSlot(Minecraft client, boolean preferDensity) {
        int bestSlot = -1, maxLevel = -1;
        RegistryAccess reg = client.level.registryAccess();
        var key = preferDensity ? Enchantments.DENSITY : Enchantments.BREACH;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack.isEmpty() || !stack.is(Items.MACE)) continue;
            int level = getEnchantmentLevel(reg, key, stack);
            if (level > maxLevel) { maxLevel = level; bestSlot = i; }
        }
        return bestSlot;
    }

    private static int getEnchantmentLevel(RegistryAccess reg, net.minecraft.resources.ResourceKey<Enchantment> key, ItemStack stack) {
        var lookup = reg.lookupOrThrow(Registries.ENCHANTMENT);
        Optional<Holder.Reference<Enchantment>> holder = lookup.get(key);
        return holder.map(h -> EnchantmentHelper.getItemEnchantmentLevel(h, stack)).orElse(0);
    }

    private static float wrapAngle(float a) {
        a %= 360f;
        if (a >= 180f) a -= 360f;
        if (a < -180f) a += 360f;
        return a;
    }

    private static void updateHistory(float yaw, float pitch) {
        if (yawHistory.size() >= HISTORY_SIZE) yawHistory.poll();
        if (pitchHistory.size() >= HISTORY_SIZE) pitchHistory.poll();
        yawHistory.add(yaw); pitchHistory.add(pitch);
    }

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
        attackCooldownTicks = 0; pingSimulationTicks = 0;
        isHesitating = false; hesitationTicks = 0;
        clickDelayTicks = 0;
    }

    private static void updateHeuristics(Minecraft client) {
        if (totalTicks % 100 == 0 && successfulSmash > 0) {
      
