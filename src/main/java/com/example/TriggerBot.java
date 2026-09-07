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
import net.minecraft.util.Mth; // <-- IMPORT ADICIONADA

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

    private static int reactionCountdownTicks = 0;
    private static int comboBufferTicks = 0;
    private static double attackReach = 4.5D;

    private static final Map<String, Object> TRIGGER_ENTERPRISE_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_UUID = UUID.randomUUID();
    private static final Deque<Long> ATTACK_INTERVAL_HISTORY = new ArrayDeque<>();
    private static final Deque<Integer> CLICK_DURATION_MEMORY = new ArrayDeque<>();
    private static final Deque<Double> ERROR_VECTOR_MEMORY = new ArrayDeque<>();
    private static final Deque<Float> ATTACK_STRENGTH_SAMPLE_DEQUE = new ArrayDeque<>();
    private static final Deque<Long> SESSION_TIMESTAMP_DEQUE = new ArrayDeque<>();
    private static final Deque<Double> FATIGUE_SAMPLE_DEQUE = new ArrayDeque<>();
    private static final Deque<Integer> REACTION_DELAY_SAMPLE_DEQUE = new ArrayDeque<>();
    private static final int HISTORY_MAX_CAPACITY = 4096;

    private static long totalTriggersFired = 0L;
    private static boolean adaptiveCritSyncActive = true;
    private static double attackStrengthThresholdNormal = 0.85D;
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

    private static double sessionMetricAlpha = 0.5D;
    private static double sessionMetricBeta = 0.5D;
    private static double sessionMetricGamma = 0.5D;
    private static double sessionMetricDelta = 0.5D;
    private static double sessionMetricEpsilon = 0.5D;
    private static double sessionMetricZeta = 0.5D;
    private static double sessionMetricEta = 0.5D;
    private static double sessionMetricTheta = 0.5D;
    private static double sessionMetricIota = 0.5D;
    private static double sessionMetricKappa = 0.5D;
    private static boolean deepTelemetryAuditActive = true;
    private static int telemetryFlushIntervalTicks = 300;
    private static long lastTelemetryFlushEpoch = 0L;
    private static boolean adaptiveFovScalingActive = true;
    private static double fovExpansionRate = 0.05D;
    private static boolean strictRaycastVerification = true;
    private static double raycastStepPrecision = 0.1D;
    private static boolean kineticInertiaModelActive = true;
    private static double massSimulatedDrag = 0.02D;
    private static boolean rotationalFrictionActive = true;
    private static double frictionCoefficient = 0.04D;

    static {
        initializeTriggerRegistry();
    }

    private static void initializeTriggerRegistry() {
        TRIGGER_ENTERPRISE_REGISTRY.put("SubsessionUUID", SUBSESSION_UUID);
        TRIGGER_ENTERPRISE_REGISTRY.put("Profile", "Swight-Ultimate-TriggerBot-800Lines");
        TRIGGER_ENTERPRISE_REGISTRY.put("BypassEngine", "Instant-Click-Enterprise-Shield");
        TRIGGER_ENTERPRISE_REGISTRY.put("InitializationEpoch", subsessionEpochTracker);
        TRIGGER_ENTERPRISE_REGISTRY.put("TotalFires", totalTriggersFired);
        TRIGGER_ENTERPRISE_REGISTRY.put("AdaptiveCritSync", adaptiveCritSyncActive);
        TRIGGER_ENTERPRISE_REGISTRY.put("CombatSync", combatSyncEnabled);
        TRIGGER_ENTERPRISE_REGISTRY.put("AlphaMetric", sessionMetricAlpha);
        TRIGGER_ENTERPRISE_REGISTRY.put("BetaMetric", sessionMetricBeta);
        TRIGGER_ENTERPRISE_REGISTRY.put("GammaMetric", sessionMetricGamma);
        TRIGGER_ENTERPRISE_REGISTRY.put("DeltaMetric", sessionMetricDelta);
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
        hardResetTriggerSubsystem();
    }

    private static void hardResetTriggerSubsystem() {
        reactionCountdownTicks = 0;
        comboBufferTicks = 0;
        triggerAnomalyCounter = 0;
        sessionAttackCounter = 0;
        currentFatigueLevel = 0.0D;
        autoCalibrationCounter = 0;
        ATTACK_INTERVAL_HISTORY.clear();
        CLICK_DURATION_MEMORY.clear();
        ERROR_VECTOR_MEMORY.clear();
        ATTACK_STRENGTH_SAMPLE_DEQUE.clear();
        SESSION_TIMESTAMP_DEQUE.clear();
        FATIGUE_SAMPLE_DEQUE.clear();
        REACTION_DELAY_SAMPLE_DEQUE.clear();
        purgeTriggerRegistry();
        initializeTriggerRegistry();
    }

    private static void purgeTriggerRegistry() {
        TRIGGER_ENTERPRISE_REGISTRY.clear();
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
        BlockHitResult hit = clientRef.level.clip(
            new ClipContext(
                start,
                end,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                clientRef.player
            )
        );
        return hit.getType() == HitResult.Type.MISS;
    }

    public static void onTick(Minecraft clientRef) {
        if (!enabled || clientRef.player == null || clientRef.level == null) return;
        if (!clientRef.player.isAlive()) return;
        if (!isHoldingWeapon(clientRef)) {
            comboBufferTicks = 0;
            return;
        }
        if (ShieldBreaker.isShieldStunStatic() || ShieldBreaker.isShieldStunActive()) {
            return;
        }

        autoCalibrationCounter++;
        if (autoCalibrationCounter >= 250) {
            autoCalibrationCounter = 0;
            executeAutoCalibrationRoutine();
        }

        if (clientRef.player.hurtTime > 0) {
            comboBufferTicks = 15;
        } else if (comboBufferTicks > 0) {
            comboBufferTicks--;
        }

        HitResult hit = clientRef.hitResult;
        if (hit == null || hit.getType() != HitResult.Type.ENTITY) {
            return;
        }

        Entity target = ((EntityHitResult) hit).getEntity();
        if (!(target instanceof LivingEntity living) || !living.isAlive() || living == clientRef.player) {
            return;
        }
        if (living instanceof Player player && (player.isSpectator() || player.isCreative())) {
            return;
        }

        double reachSqr = attackReach * attackReach;
        if (clientRef.player.distanceToSqr(living) > reachSqr) {
            return;
        }

        if (lineOfSightValidation && !hasLineOfSight(clientRef, living)) {
            return;
        }

        double distance = Math.sqrt(clientRef.player.distanceToSqr(living));
        int requiredReactionDelay = distance > 5.0D ? (2 + secureRandom.nextInt(3)) : (minReactionDelayTicks + secureRandom.nextInt(maxReactionDelayTicks));

        if (reactionCountdownTicks < requiredReactionDelay && comboBufferTicks == 0) {
            reactionCountdownTicks++;
            return;
        }
        reactionCountdownTicks = 0;

        if (consistentCritsEnabled && !clientRef.player.onGround()) {
            boolean isFalling = clientRef.player.getDeltaMovement().y < verticalFallingTolerance;
            if (!isFalling && comboBufferTicks == 0) {
                return;
            }
            if (secureRandom.nextDouble() < 0.05D) {
                return;
            }
        }

        double activeThreshold = (comboBufferTicks > 0 ? attackStrengthThresholdCombo : attackStrengthThresholdNormal) + (currentFatigueLevel * 0.05D);
        float currentAttackScale = clientRef.player.getAttackStrengthScale(0.0F);

        pushAttackStrengthSample(currentAttackScale);

        if (currentAttackScale >= activeThreshold) {
            if (errorInjectionActive && secureRandom.nextDouble() < randomMissChance) {
                return;
            }

            totalTriggersFired++;
            sessionAttackCounter++;
            currentFatigueLevel = Math.min(1.0D, currentFatigueLevel + fatigueScalar);
            long timestamp = System.currentTimeMillis();
            pushAttackInterval(timestamp);
            pushSessionTimestamp(timestamp);
            pushFatigueSample(currentFatigueLevel);
            pushReactionDelaySample(requiredReactionDelay);

            InteractionManager.simulateClickAttack(clientRef);

            float recoilYaw = (float) ((secureRandom.nextDouble() - 0.5) * 0.3D);
            float recoilPitch = (float) ((secureRandom.nextDouble() - 0.5) * 0.2D);
            clientRef.player.setYRot(clientRef.player.getYRot() + recoilYaw);
            clientRef.player.setXRot(Mth.clamp(clientRef.player.getXRot() + recoilPitch, -89.0F, 89.0F));
        } else {
            if (comboBufferTicks == 0) {
                reactionCountdownTicks = 0;
            }
        }
        updateRegistryState();
        executeSubsystemSanitation();
    }

    private static void pushAttackInterval(long timestamp) {
        if (ATTACK_INTERVAL_HISTORY.size() >= HISTORY_MAX_CAPACITY) {
            ATTACK_INTERVAL_HISTORY.pollFirst();
        }
        ATTACK_INTERVAL_HISTORY.offerLast(timestamp);
    }

    private static void pushAttackStrengthSample(float scale) {
        if (ATTACK_STRENGTH_SAMPLE_DEQUE.size() >= HISTORY_MAX_CAPACITY) {
            ATTACK_STRENGTH_SAMPLE_DEQUE.pollFirst();
        }
        ATTACK_STRENGTH_SAMPLE_DEQUE.offerLast(scale);
    }

    private static void pushSessionTimestamp(long timestamp) {
        if (SESSION_TIMESTAMP_DEQUE.size() >= HISTORY_MAX_CAPACITY) {
            SESSION_TIMESTAMP_DEQUE.pollFirst();
        }
        SESSION_TIMESTAMP_DEQUE.offerLast(timestamp);
    }

    private static void pushFatigueSample(double fatigue) {
        if (FATIGUE_SAMPLE_DEQUE.size() >= HISTORY_MAX_CAPACITY) {
            FATIGUE_SAMPLE_DEQUE.pollFirst();
        }
        FATIGUE_SAMPLE_DEQUE.offerLast(fatigue);
    }

    private static void pushReactionDelaySample(int delay) {
        if (REACTION_DELAY_SAMPLE_DEQUE.size() >= HISTORY_MAX_CAPACITY) {
            REACTION_DELAY_SAMPLE_DEQUE.pollFirst();
        }
        REACTION_DELAY_SAMPLE_DEQUE.offerLast(delay);
    }

    private static void executeAutoCalibrationRoutine() {
        attackStrengthThresholdNormal = 0.85D + (secureRandom.nextDouble() - 0.5) * 0.04D;
        currentFatigueLevel = Math.max(0.0D, currentFatigueLevel - 0.1D);
    }

    private static void updateRegistryState() {
        TRIGGER_ENTERPRISE_REGISTRY.put("TotalFires", totalTriggersFired);
        TRIGGER_ENTERPRISE_REGISTRY.put("SessionFires", sessionAttackCounter);
        TRIGGER_ENTERPRISE_REGISTRY.put("FatigueLevel", currentFatigueLevel);
        TRIGGER_ENTERPRISE_REGISTRY.put("HistoryQueueSize", ATTACK_INTERVAL_HISTORY.size());
        TRIGGER_ENTERPRISE_REGISTRY.put("StrengthQueueSize", ATTACK_STRENGTH_SAMPLE_DEQUE.size());
    }

    private static void executeSubsystemSanitation() {
        if (totalTriggersFired > 50000000L) {
            totalTriggersFired = 0L;
        }
        if (TRIGGER_ENTERPRISE_REGISTRY.size() > 200) {
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
        reactionCountdownTicks = 0;
        comboBufferTicks = 0;
        triggerAnomalyCounter = 0;
        currentFatigueLevel = 0.0D;
        ATTACK_INTERVAL_HISTORY.clear();
        CLICK_DURATION_MEMORY.clear();
        ERROR_VECTOR_MEMORY.clear();
        ATTACK_STRENGTH_SAMPLE_DEQUE.clear();
        SESSION_TIMESTAMP_DEQUE.clear();
        FATIGUE_SAMPLE_DEQUE.clear();
        REACTION_DELAY_SAMPLE_DEQUE.clear();
    }

    public static void executeExtendedDiagnosticFlush() {
        executeSubsystemSanitation();
        if (ATTACK_INTERVAL_HISTORY.size() > HISTORY_MAX_CAPACITY) {
            ATTACK_INTERVAL_HISTORY.clear();
        }
        if (CLICK_DURATION_MEMORY.size() > HISTORY_MAX_CAPACITY) {
            CLICK_DURATION_MEMORY.clear();
        }
        if (ERROR_VECTOR_MEMORY.size() > HISTORY_MAX_CAPACITY) {
            ERROR_VECTOR_MEMORY.clear();
        }
        if (ATTACK_STRENGTH_SAMPLE_DEQUE.size() > HISTORY_MAX_CAPACITY) {
            ATTACK_STRENGTH_SAMPLE_DEQUE.clear();
        }
        if (SESSION_TIMESTAMP_DEQUE.size() > HISTORY_MAX_CAPACITY) {
            SESSION_TIMESTAMP_DEQUE.clear();
        }
        if (FATIGUE_SAMPLE_DEQUE.size() > HISTORY_MAX_CAPACITY) {
            FATIGUE_SAMPLE_DEQUE.clear();
        }
        if (REACTION_DELAY_SAMPLE_DEQUE.size() > HISTORY_MAX_CAPACITY) {
            REACTION_DELAY_SAMPLE_DEQUE.clear();
        }
    }

    public static UUID getSubsessionIdentity() {
        return SUBSESSION_UUID;
    }

    public static void setAttackReach(double reach) {
        attackReach = reach;
        TRIGGER_ENTERPRISE_REGISTRY.put("AttackReach", attackReach);
    }

    public static double getAttackReach() {
        return attackReach;
    }

    public static void setConsistentCritsEnabled(boolean state) {
        consistentCritsEnabled = state;
        TRIGGER_ENTERPRISE_REGISTRY.put("ConsistentCrits", consistentCritsEnabled);
    }

    public static boolean isConsistentCritsEnabled() {
        return consistentCritsEnabled;
    }

    public static void setRandomMissChance(double chance) {
        randomMissChance = chance;
        TRIGGER_ENTERPRISE_REGISTRY.put("MissChance", randomMissChance);
    }

    public static double getRandomMissChance() {
        return randomMissChance;
    }

    public static int getMinReactionDelayTicks() {
        return minReactionDelayTicks;
    }

    public static void setMinReactionDelayTicks(int ticks) {
        minReactionDelayTicks = Math.max(0, ticks);
        TRIGGER_ENTERPRISE_REGISTRY.put("MinReactionDelay", minReactionDelayTicks);
    }

    public static int getMaxReactionDelayTicks() {
        return maxReactionDelayTicks;
    }

    public static void setMaxReactionDelayTicks(int ticks) {
        maxReactionDelayTicks = Math.max(minReactionDelayTicks, ticks);
        TRIGGER_ENTERPRISE_REGISTRY.put("MaxReactionDelay", maxReactionDelayTicks);
    }

    public static double getAttackStrengthThresholdNormal() {
        return attackStrengthThresholdNormal;
    }

    public static void setAttackStrengthThresholdNormal(double threshold) {
        attackStrengthThresholdNormal = threshold;
        TRIGGER_ENTERPRISE_REGISTRY.put("ThresholdNormal", attackStrengthThresholdNormal);
    }

    public static double getAttackStrengthThresholdCombo() {
        return attackStrengthThresholdCombo;
    }

    public static void setAttackStrengthThresholdCombo(double threshold) {
        attackStrengthThresholdCombo = threshold;
        TRIGGER_ENTERPRISE_REGISTRY.put("ThresholdCombo", attackStrengthThresholdCombo);
    }

    public static double getCurrentFatigueLevel() {
        return currentFatigueLevel;
    }

    public static void setCurrentFatigueLevel(double fatigue) {
        currentFatigueLevel = fatigue;
        TRIGGER_ENTERPRISE_REGISTRY.put("FatigueLevel", currentFatigueLevel);
    }

    public static int getSessionAttackCounter() {
        return sessionAttackCounter;
    }

    public static void resetSessionAttackCounter() {
        sessionAttackCounter = 0;
        TRIGGER_ENTERPRISE_REGISTRY.put("SessionFires", sessionAttackCounter);
    }

    public static boolean isAdaptiveCritSyncActive() {
        return adaptiveCritSyncActive;
    }

    public static void setAdaptiveCritSyncActive(boolean active) {
        adaptiveCritSyncActive = active;
        TRIGGER_ENTERPRISE_REGISTRY.put("AdaptiveCritSync", adaptiveCritSyncActive);
    }

    public static boolean isLineOfSightValidation() {
        return lineOfSightValidation;
    }

    public static void setLineOfSightValidation(boolean validation) {
        lineOfSightValidation = validation;
        TRIGGER_ENTERPRISE_REGISTRY.put("LineOfSightValidation", lineOfSightValidation);
    }

    public static double getFatigueScalar() {
        return fatigueScalar;
    }

    public static void setFatigueScalar(double scalar) {
        fatigueScalar = scalar;
        TRIGGER_ENTERPRISE_REGISTRY.put("FatigueScalar", fatigueScalar);
    }

    public static boolean isErrorInjectionActive() {
        return errorInjectionActive;
    }

    public static void setErrorInjectionActive(boolean active) {
        errorInjectionActive = active;
        TRIGGER_ENTERPRISE_REGISTRY.put("ErrorInjectionActive", errorInjectionActive);
    }

    public static int getAutoCalibrationCounter() {
        return autoCalibrationCounter;
    }

    public static int getHistoryCapacity() {
        return HISTORY_MAX_CAPACITY;
    }

    public static int getAttackIntervalHistorySize() {
        return ATTACK_INTERVAL_HISTORY.size();
    }

    public static int getClickDurationMemorySize() {
        return CLICK_DURATION_MEMORY.size();
    }

    public static int getErrorVectorMemorySize() {
        return ERROR_VECTOR_MEMORY.size();
    }

        public static int getAttackStrengthSampleSize() {
        return ATTACK_STRENGTH_SAMPLE_DEQUE.size();
    }

    public static int getSessionTimestampSize() {
        return SESSION_TIMESTAMP_DEQUE.size();
    }

    public static int getFatigueSampleSize() {
        return FATIGUE_SAMPLE_DEQUE.size();
    }

    public static int getReactionDelaySampleSize() {
        return REACTION_DELAY_SAMPLE_DEQUE.size();
    }

    public static void clearAllHistoryQueues() {
        ATTACK_INTERVAL_HISTORY.clear();
        CLICK_DURATION_MEMORY.clear();
        ERROR_VECTOR_MEMORY.clear();
        ATTACK_STRENGTH_SAMPLE_DEQUE.clear();
        SESSION_TIMESTAMP_DEQUE.clear();
        FATIGUE_SAMPLE_DEQUE.clear();
        REACTION_DELAY_SAMPLE_DEQUE.clear();
    }

    public static void forceTriggerReset() {
        hardResetTriggerSubsystem();
    }
} // <-- FECHA A CLASSE TRIGGERBOT