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

    private static int attackReleaseTracker = 0;
    private static int reactionCountdownTicks = 0;
    private static int comboBufferTicks = 0;
    private static final double MAX_MELEE_REACH_SQR = 16.0D;

    private static final Map<String, Object> TRIGGER_ENTERPRISE_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_UUID = UUID.randomUUID();
    private static final Deque<Long> ATTACK_INTERVAL_HISTORY = new ArrayDeque<>();
    private static final Deque<Integer> CLICK_DURATION_MEMORY = new ArrayDeque<>();
    private static final Deque<Double> ERROR_VECTOR_MEMORY = new ArrayDeque<>();
    private static final int HISTORY_MAX_CAPACITY = 2048;

    private static long totalTriggersFired = 0L;
    private static boolean adaptiveCritSyncActive = true;
    private static double attackStrengthThresholdNormal = 0.82D;
    private static double attackStrengthThresholdCombo = 0.52D;
    private static boolean humanReactionStochasticity = true;
    private static long subsessionEpochTracker = System.currentTimeMillis();
    private static boolean antiReplayShieldActive = true;
    private static int triggerAnomalyCounter = 0;
    private static boolean stealthProfileMode = true;
    private static int minReactionDelayTicks = 1;
    private static int maxReactionDelayTicks = 3;
    private static boolean packetOrderStrictSync = true;
    private static double verticalFallingTolerance = -0.04D;
    private static boolean lineOfSightValidation = true;
    private static int sessionAttackCounter = 0;
    private static boolean dynamicThresholdAdjustment = true;
    private static double stochasticVariance = 0.04D;
    private static int emergencyResetThreshold = 100;
    private static boolean combatSyncEnabled = true;
    private static boolean biologicalFatigueSim = true;
    private static double fatigueScalar = 0.001D;
    private static double currentFatigueLevel = 0.0D;
    private static boolean errorInjectionActive = true;
    private static double randomMissChance = 0.015D;
    private static boolean selfOptimizationActive = true;
    private static int autoCalibrationCounter = 0;

    static {
        initializeTriggerRegistry();
    }

    private static void initializeTriggerRegistry() {
        TRIGGER_ENTERPRISE_REGISTRY.put("SubsessionUUID", SUBSESSION_UUID);
        TRIGGER_ENTERPRISE_REGISTRY.put("Profile", "Swight-Tier1-TriggerBot-FullEnterprise");
        TRIGGER_ENTERPRISE_REGISTRY.put("BypassEngine", "Crit-Sync-Attack-Interval-Stochastic");
        TRIGGER_ENTERPRISE_REGISTRY.put("InitializationEpoch", subsessionEpochTracker);
        TRIGGER_ENTERPRISE_REGISTRY.put("TotalFires", totalTriggersFired);
        TRIGGER_ENTERPRISE_REGISTRY.put("AdaptiveCritSync", adaptiveCritSyncActive);
        TRIGGER_ENTERPRISE_REGISTRY.put("CombatSync", combatSyncEnabled);
    }

    public TriggerBot() {
        super("TriggerBot");
        TriggerBot.enabled = true;
        initializeTriggerRegistry();
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void toggle() {
        enabled = !enabled;
        super.enabled = enabled;
    }

    @Override
    public void tick(Minecraft clientRef) {
        onTick(clientRef);
    }

    private static boolean isHoldingWeapon(Minecraft clientRef) {
        if (clientRef.player == null) return false;
        ItemStack stack = clientRef.player.getMainHandItem();
        if (stack.isEmpty()) return false;
        String name = stack.getItem().getDescriptionId().toLowerCase();
        return name.contains("sword") || name.contains("axe") || name.contains("trident") || name.contains("mace");
    }

    private static boolean hasLineOfSight(Minecraft clientRef, Entity target) {
        if (clientRef.player == null || target == null) return false;
        Vec3 start = clientRef.player.getEyePosition();
        Vec3 end = target.getEyePosition();
        BlockHitResult hit = clientRef.level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, clientRef.player));
        return hit.getType() == HitResult.Type.MISS;
    }

    public static void onTick(Minecraft clientRef) {
        if (!enabled || clientRef.player == null || clientRef.level == null) return;
        if (!clientRef.player.isAlive()) return;
        if (!isHoldingWeapon(clientRef)) {
            comboBufferTicks = 0;
            return;
        }
        if (ShieldBreaker.isShieldStunActive()) return;

        autoCalibrationCounter++;
        if (autoCalibrationCounter >= 250) {
            autoCalibrationCounter = 0;
            executeAutoCalibrationRoutine();
        }

        if (attackReleaseTracker > 0) {
            attackReleaseTracker--;
            if (attackReleaseTracker == 0) {
                clientRef.options.keyAttack.setDown(false);
            }
        }

        if (clientRef.player.hurtTime > 0) {
            comboBufferTicks = 15;
        } else if (comboBufferTicks > 0) {
            comboBufferTicks--;
        }

        boolean shouldAttack = false;
        HitResult hit = clientRef.hitResult;
        if (hit != null && hit.getType() == HitResult.Type.ENTITY) {
            if (hit instanceof EntityHitResult entityHit && entityHit.getEntity() instanceof LivingEntity living) {
                if (living.isAlive() && living != clientRef.player) {
                    if (!(living instanceof Player player && (player.isSpectator() || player.isCreative()))) {
                        if (clientRef.player.distanceToSqr(living) <= MAX_MELEE_REACH_SQR && (!lineOfSightValidation || hasLineOfSight(clientRef, living))) {
                            shouldAttack = true;
                        }
                    }
                }
            }
        }

        if (!shouldAttack) {
            for (Entity entity : clientRef.level.entitiesForRendering()) {
                if (!(entity instanceof LivingEntity living) || living == clientRef.player || !living.isAlive()) continue;
                if (living instanceof Player player && (player.isSpectator() || player.isCreative())) continue;
                if (clientRef.player.distanceToSqr(living) <= MAX_MELEE_REACH_SQR && (!lineOfSightValidation || hasLineOfSight(clientRef, living))) {
                    shouldAttack = true;
                    break;
                }
            }
        }

        if (shouldAttack) {
            if (reactionCountdownTicks > 0 && comboBufferTicks == 0) {
                reactionCountdownTicks--;
                return;
            }

            if (consistentCritsEnabled && !clientRef.player.onGround()) {
                boolean isFalling = clientRef.player.getDeltaMovement().y < verticalFallingTolerance;
                if (!isFalling && comboBufferTicks == 0) {
                    return;
                }
            }

            double activeThreshold = (comboBufferTicks > 0 ? attackStrengthThresholdCombo : attackStrengthThresholdNormal) + (currentFatigueLevel * 0.05D);
            if (clientRef.player.getAttackStrengthScale(0.0F) >= activeThreshold) {
                if (attackReleaseTracker == 0) {
                    if (errorInjectionActive && secureRandom.nextDouble() < randomMissChance) {
                        attackReleaseTracker = minReactionDelayTicks + secureRandom.nextInt(maxReactionDelayTicks) + 2;
                        return;
                    }

                    totalTriggersFired++;
                    sessionAttackCounter++;
                    currentFatigueLevel = Math.min(1.0D, currentFatigueLevel + fatigueScalar);
                    long timestamp = System.currentTimeMillis();
                    pushAttackInterval(timestamp);
                    InteractionManager.simulateClickAttack(clientRef);
                    attackReleaseTracker = minReactionDelayTicks + secureRandom.nextInt(maxReactionDelayTicks);
                    reactionCountdownTicks = minReactionDelayTicks + secureRandom.nextInt(maxReactionDelayTicks);
                }
            }
        } else {
            if (comboBufferTicks == 0) {
                reactionCountdownTicks = 0;
            }
        }
        updateRegistryState();
    }

    private static void pushAttackInterval(long timestamp) {
        if (ATTACK_INTERVAL_HISTORY.size() >= HISTORY_MAX_CAPACITY) {
            ATTACK_INTERVAL_HISTORY.pollFirst();
        }
        ATTACK_INTERVAL_HISTORY.offerLast(timestamp);
    }

    private static void executeAutoCalibrationRoutine() {
        attackStrengthThresholdNormal = 0.82D + (secureRandom.nextDouble() - 0.5) * 0.04D;
        currentFatigueLevel = Math.max(0.0D, currentFatigueLevel - 0.1D);
    }

    private static void updateRegistryState() {
        TRIGGER_ENTERPRISE_REGISTRY.put("TotalFires", totalTriggersFired);
        TRIGGER_ENTERPRISE_REGISTRY.put("SessionFires", sessionAttackCounter);
        TRIGGER_ENTERPRISE_REGISTRY.put("FatigueLevel", currentFatigueLevel);
    }

    private static void executeSubsystemSanitation() {
        if (totalTriggersFired > 50000000L) {
            totalTriggersFired = 0L;
        }
        if (TRIGGER_ENTERPRISE_REGISTRY.size() > 150) {
            TRIGGER_ENTERPRISE_REGISTRY.clear();
            initializeTriggerRegistry();
        }
    }

    public static boolean verifyTriggerSubsystemHealth() {
        return enabled && SUBSESSION_UUID != null;
    }

    public static long getTotalTriggersFired() {
        return totalTriggersFired;
    }

    public static void performBaselineCalibration() {
        totalTriggersFired = 0L;
        sessionAttackCounter = 0;
        attackReleaseTracker = 0;
        reactionCountdownTicks = 0;
        comboBufferTicks = 0;
        triggerAnomalyCounter = 0;
        currentFatigueLevel = 0.0D;
        ATTACK_INTERVAL_HISTORY.clear();
    }

    public static void executeExtendedDiagnosticFlush() {
        executeSubsystemSanitation();
        if (ATTACK_INTERVAL_HISTORY.size() > HISTORY_MAX_CAPACITY) {
            ATTACK_INTERVAL_HISTORY.clear();
        }
    }

    public static UUID getSubsessionIdentity() {
        return SUBSESSION_UUID;
    }
}