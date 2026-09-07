package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Deque;

public class TriggerBot extends ClientBase.Module {
    public static final String FILE_NAME = "TriggerBot.java";
    public static boolean enabled = true;
    public static boolean consistentCritsEnabled = true;

    private static final SecureRandom secureRandom = new SecureRandom();

    // ===== ESTADO =====
    private static int reactionCountdownTicks = 0;
    private static int comboBufferTicks = 0;
    private static double attackReach = 4.5D;

    // ===== REGISTROS =====
    private static final Map<String, Object> TRIGGER_ENTERPRISE_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_UUID = UUID.randomUUID();
    private static final Deque<Long> ATTACK_INTERVAL_HISTORY = new ArrayDeque<>();
    private static final Deque<Integer> CLICK_DURATION_MEMORY = new ArrayDeque<>();
    private static final Deque<Double> ERROR_VECTOR_MEMORY = new ArrayDeque<>();
    private static final int HISTORY_MAX_CAPACITY = 2048;

    // ===== PARÂMETROS =====
    private static long totalTriggersFired = 0L;
    private static double attackStrengthThresholdNormal = 0.85D;
    private static double attackStrengthThresholdCombo = 0.52D;
    private static int minReactionDelayTicks = 1;
    private static int maxReactionDelayTicks = 3;
    private static double verticalFallingTolerance = -0.04D;
    private static boolean lineOfSightValidation = true;
    private static double fatigueScalar = 0.001D;
    private static double currentFatigueLevel = 0.0D;
    private static boolean errorInjectionActive = true;
    private static double randomMissChance = 0.015D;
    private static int autoCalibrationCounter = 0;
    private static int sessionAttackCounter = 0;

    static {
        initializeTriggerRegistry();
    }

    private static void initializeTriggerRegistry() {
        TRIGGER_ENTERPRISE_REGISTRY.put("SubsessionUUID", SUBSESSION_UUID);
        TRIGGER_ENTERPRISE_REGISTRY.put("Profile", "Swight-InstantClick-TriggerBot");
        TRIGGER_ENTERPRISE_REGISTRY.put("TotalFires", totalTriggersFired);
        TRIGGER_ENTERPRISE_REGISTRY.put("FatigueLevel", currentFatigueLevel);
    }

    public TriggerBot() {
        super("TriggerBot");
        TriggerBot.enabled = true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void toggle() {
        enabled = !enabled;
        super.enabled = enabled;
        hardReset();
    }

    private static void hardReset() {
        reactionCountdownTicks = 0;
        comboBufferTicks = 0;
        sessionAttackCounter = 0;
        currentFatigueLevel = 0.0D;
        autoCalibrationCounter = 0;
        ATTACK_INTERVAL_HISTORY.clear();
        CLICK_DURATION_MEMORY.clear();
        ERROR_VECTOR_MEMORY.clear();
        TRIGGER_ENTERPRISE_REGISTRY.clear();
        initializeTriggerRegistry();
    }

    @Override
    public void tick(Minecraft clientRef) {
        onTick(clientRef);
    }

    private static boolean isHoldingWeapon(Minecraft client) {
        if (client.player == null) return false;
        ItemStack stack = client.player.getMainHandItem();
        if (stack.isEmpty()) return false;
        String name = stack.getItem().getDescriptionId().toLowerCase();
        return name.contains("sword") || name.contains("axe") || name.contains("trident") || name.contains("mace");
    }

    private static boolean hasLineOfSight(Minecraft client, Entity target) {
        if (client.player == null || target == null) return false;
        Vec3 start = client.player.getEyePosition();
        Vec3 end = target.getEyePosition();
        BlockHitResult hit = client.level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));
        return hit.getType() == HitResult.Type.MISS;
    }

    public static void onTick(Minecraft client) {
        if (!enabled || client.player == null || client.level == null) return;
        if (!client.player.isAlive()) return;
        if (!isHoldingWeapon(client)) {
            comboBufferTicks = 0;
            return;
        }
        if (ShieldBreaker.isShieldStunActive()) return;

        autoCalibrationCounter++;
        if (autoCalibrationCounter >= 250) {
            autoCalibrationCounter = 0;
            attackStrengthThresholdNormal = 0.85D + (secureRandom.nextDouble() - 0.5) * 0.04D;
            currentFatigueLevel = Math.max(0.0D, currentFatigueLevel - 0.1D);
        }

        if (client.player.hurtTime > 0) {
            comboBufferTicks = 15;
        } else if (comboBufferTicks > 0) {
            comboBufferTicks--;
        }

        HitResult hit = client.hitResult;
        // NUNCA atacar se for BLOCK ou MISS – só ENTITY
        if (hit == null || hit.getType() != HitResult.Type.ENTITY) {
            return;
        }

        Entity target = ((EntityHitResult) hit).getEntity();
        if (!(target instanceof LivingEntity living) || !living.isAlive() || living == client.player) {
            return;
        }
        if (living instanceof Player player && (player.isSpectator() || player.isCreative())) {
            return;
        }

        double distSqr = client.player.distanceToSqr(living);
        if (distSqr > attackReach * attackReach) {
            return;
        }

        if (lineOfSightValidation && !hasLineOfSight(client, living)) {
            return;
        }

        double distance = Math.sqrt(distSqr);
        int requiredDelay = distance > 5.0 ? (2 + secureRandom.nextInt(3)) : (minReactionDelayTicks + secureRandom.nextInt(maxReactionDelayTicks));

        if (reactionCountdownTicks < requiredDelay && comboBufferTicks == 0) {
            reactionCountdownTicks++;
            return;
        }
        reactionCountdownTicks = 0;

        if (consistentCritsEnabled && !client.player.onGround()) {
            boolean isFalling = client.player.getDeltaMovement().y < verticalFallingTolerance;
            if (!isFalling && comboBufferTicks == 0) {
                return;
            }
            if (secureRandom.nextDouble() < 0.05D) { // hesitação humana
                return;
            }
        }

        double threshold = (comboBufferTicks > 0 ? attackStrengthThresholdCombo : attackStrengthThresholdNormal)
                + (currentFatigueLevel * 0.05D);

        if (client.player.getAttackStrengthScale(0.0F) >= threshold) {
            if (errorInjectionActive && secureRandom.nextDouble() < randomMissChance) {
                return; // erro proposital
            }

            totalTriggersFired++;
            sessionAttackCounter++;
            currentFatigueLevel = Math.min(1.0D, currentFatigueLevel + fatigueScalar);
            pushAttackInterval(System.currentTimeMillis());

            // ===== CLIQUE INSTANTÂNEO VIA InteractionManager =====
            InteractionManager.simulateClickAttack(client);

            // Pequeno recuo na mira (natural)
            float recoilYaw = (float) ((secureRandom.nextDouble() - 0.5) * 0.3D);
            float recoilPitch = (float) ((secureRandom.nextDouble() - 0.5) * 0.2D);
            client.player.setYRot(client.player.getYRot() + recoilYaw);
            client.player.setXRot(Mth.clamp(client.player.getXRot() + recoilPitch, -89.0F, 89.0F));
        } else {
            if (comboBufferTicks == 0) {
                reactionCountdownTicks = 0;
            }
        }

        updateRegistry();
    }

    private static void pushAttackInterval(long timestamp) {
        if (ATTACK_INTERVAL_HISTORY.size() >= HISTORY_MAX_CAPACITY) {
            ATTACK_INTERVAL_HISTORY.pollFirst();
        }
        ATTACK_INTERVAL_HISTORY.offerLast(timestamp);
    }

    private static void updateRegistry() {
        TRIGGER_ENTERPRISE_REGISTRY.put("TotalFires", totalTriggersFired);
        TRIGGER_ENTERPRISE_REGISTRY.put("SessionFires", sessionAttackCounter);
        TRIGGER_ENTERPRISE_REGISTRY.put("FatigueLevel", currentFatigueLevel);
    }

    // ===== MÉTODOS PÚBLICOS =====
    public static UUID getSubsessionIdentity() {
        return SUBSESSION_UUID;
    }

    public static long getTotalTriggersFired() {
        return totalTriggersFired;
    }

    public static void setAttackReach(double reach) {
        attackReach = reach;
    }

    public static double getAttackReach() {
        return attackReach;
    }

    public static void setConsistentCritsEnabled(boolean state) {
        consistentCritsEnabled = state;
    }

    public static boolean isConsistentCritsEnabled() {
        return consistentCritsEnabled;
    }
}