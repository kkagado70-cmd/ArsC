package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Deque;

public class AutoMace extends ClientBase.Module {
    public static final String FILE_NAME = "AutoMace.java";
    public static boolean enabled = false;
    private static final SecureRandom secureRandom = new SecureRandom();

    private static final Map<String, Object> MACE_ENTERPRISE_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_IDENTITY = UUID.randomUUID();
    private static final Deque<Double> VERTICAL_VELOCITY_HISTORY = new ArrayDeque<>();
    private static final Deque<Long> SMASH_EXECUTION_TIMESTAMPS = new ArrayDeque<>();
    private static final Deque<Vec3> TARGET_MOMENTUM_BUFFER = new ArrayDeque<>();
    private static final int HISTORY_CAPACITY = 2048;

    private static double maxSwingRange = 4.75D;
    private static double maxAimDistance = 24.0D;
    private static double minimumFallDistance = 1.15D;
    private static float hyperSnapSpeed = 0.99F;
    private static long executionTickCounter = 0L;
    private static boolean windChargeBoostDetection = true;
    private static boolean elytraDiveCheck = true;
    private static LivingEntity lockedMaceTarget = null;
    private static int smashCooldownTracker = 0;
    private static boolean antiHeuristicBypassActive = true;
    private static int anomalyCounter = 0;
    private static boolean stealthProfileActive = true;
    private static double stochasticJitterFactor = 0.005D;
    private static boolean instantSlotSwitchActive = true;
    private static int sessionSmashCounter = 0;
    private static boolean packetOrderStrictSync = true;
    private static long lastSmashEpoch = 0L;
    private static boolean targetPredictionEngine = true;
    private static double predictionMultiplier = 1.25D;
    private static boolean hardwareTurnSimulation = true;
    private static boolean profileLockState = false;
    private static double stochasticVarianceScalar = 0.03D;
    private static int emergencyResetLimit = 100;
    private static boolean biologicalFatigueSimulation = true;
    private static double fatigueIncrementRate = 0.001D;
    private static double currentFatigueAccumulator = 0.0D;
    private static boolean errorInjectionActive = true;
    private static double randomMissProbabilityRate = 0.008D;
    private static boolean selfOptimizationLoop = true;
    private static int autoCalibrationTickTimer = 0;
    private static double attackStrengthScaleLock = 0.70D;
    private static boolean windChargeMomentumStacking = true;
    private static double verticalBoostThreshold = 0.70D;
    private static boolean hitregBypassEngine = true;
    private static double hitboxExpansionScalar = 0.15D;

    static {
        initializeMaceEnterpriseRegistry();
    }

    private static void initializeMaceEnterpriseRegistry() {
        MACE_ENTERPRISE_REGISTRY.put("SubsessionUUID", SUBSESSION_IDENTITY);
        MACE_ENTERPRISE_REGISTRY.put("ModuleState", "Pro-Tier1-AutoMace-400Lines");
        MACE_ENTERPRISE_REGISTRY.put("BypassEngine", "GrimAC-Motion-Sync-Pro");
        MACE_ENTERPRISE_REGISTRY.put("InitializationEpoch", System.currentTimeMillis());
        MACE_ENTERPRISE_REGISTRY.put("WindChargeDetection", windChargeBoostDetection);
        MACE_ENTERPRISE_REGISTRY.put("ElytraDiveCheck", elytraDiveCheck);
        MACE_ENTERPRISE_REGISTRY.put("MaxSwingRange", maxSwingRange);
        MACE_ENTERPRISE_REGISTRY.put("MinFallDistance", minimumFallDistance);
    }

    public AutoMace() {
        super("AutoMace");
        AutoMace.enabled = false;
        initializeMaceEnterpriseRegistry();
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void toggle() {
        enabled = !enabled;
        super.enabled = enabled;
        resetMaceEnterpriseState();
    }

    private static void resetMaceEnterpriseState() {
        lockedMaceTarget = null;
        smashCooldownTracker = 0;
        anomalyCounter = 0;
        sessionSmashCounter = 0;
        autoCalibrationTickTimer = 0;
        currentFatigueAccumulator = 0.0D;
        VERTICAL_VELOCITY_HISTORY.clear();
        SMASH_EXECUTION_TIMESTAMPS.clear();
        TARGET_MOMENTUM_BUFFER.clear();
        MACE_ENTERPRISE_REGISTRY.clear();
        initializeMaceEnterpriseRegistry();
    }

    @Override
    public void tick(Minecraft clientRef) {
        onTick(clientRef);
    }

    public static void onTick(Minecraft clientRef) {
        if (!enabled || clientRef.player == null || clientRef.level == null) return;
        if (!clientRef.player.isAlive()) return;

        executionTickCounter++;
        autoCalibrationTickTimer++;

        if (autoCalibrationTickTimer >= 200) {
            autoCalibrationTickTimer = 0;
            executeAutoCalibrationRoutine();
        }

        executeSubsystemSanitation();

        if (smashCooldownTracker > 0) {
            smashCooldownTracker--;
        }

        double currentVerticalVelocity = clientRef.player.getDeltaMovement().y;
        pushFallVelocityHistory(currentVerticalVelocity);

        LivingEntity target = resolveOptimalMaceTarget(clientRef);
        if (target != null) {
            evaluateSmashConditions(clientRef, target);
        } else {
            lockedMaceTarget = null;
        }

        updateRegistryState();
    }

    private static LivingEntity resolveOptimalMaceTarget(Minecraft clientRef) {
        if (lockedMaceTarget != null) {
            if (lockedMaceTarget.isAlive() && clientRef.player.distanceToSqr(lockedMaceTarget) <= (maxAimDistance * maxAimDistance)) {
                return lockedMaceTarget;
            }
            lockedMaceTarget = null;
        }

        LivingEntity bestTarget = null;
        double minDistanceSqr = (maxAimDistance * maxAimDistance) + 1.0D;

        for (Player player : clientRef.level.players()) {
            if (player == clientRef.player) continue;
            if (!player.isAlive() || player.isSpectator() || player.isCreative()) continue;
            double distSqr = clientRef.player.distanceToSqr(player);
            if (distSqr > (maxAimDistance * maxAimDistance)) continue;

            if (distSqr < minDistanceSqr) {
                minDistanceSqr = distSqr;
                bestTarget = player;
            }
        }

        if (bestTarget != null) {
            lockedMaceTarget = bestTarget;
        }
        return lockedMaceTarget;
    }

    private static void evaluateSmashConditions(Minecraft clientRef, LivingEntity target) {
        double playerFallDistance = clientRef.player.fallDistance;
        boolean isElytraActive = clientRef.player.isFallFlying();
        double verticalVelocityY = clientRef.player.getDeltaMovement().y;
        
        boolean windChargeMomentum = windChargeBoostDetection && verticalVelocityY > verticalBoostThreshold;
        boolean diveTriggerCondition = playerFallDistance >= minimumFallDistance || (elytraDiveCheck && isElytraActive) || verticalVelocityY < -0.25D || windChargeMomentum;

        if (diveTriggerCondition) {
            int maceSlot = findItem(clientRef, Items.MACE);
            if (maceSlot != -1) {
                selectSlot(clientRef, maceSlot);
                
                Vec3 targetCenter = target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D);
                Vec3 predictedTargetPos = targetCenter.add(target.getDeltaMovement().scale(predictionMultiplier));
                
                RotationManager.smoothTo(clientRef, predictedTargetPos.add(secureRandom.nextDouble() * stochasticJitterFactor, secureRandom.nextDouble() * stochasticJitterFactor, secureRandom.nextDouble() * stochasticJitterFactor), hyperSnapSpeed);

                double distanceToTarget = clientRef.player.distanceTo(target);
                float attackScale = clientRef.player.getAttackStrengthScale(0.0F);

                double dynamicThreshold = attackStrengthScaleLock + currentFatigueAccumulator;
                if (distanceToTarget <= maxSwingRange && attackScale >= dynamicThreshold && smashCooldownTracker == 0) {
                    if (errorInjectionActive && secureRandom.nextDouble() < randomMissProbabilityRate) {
                        return;
                    }

                    sessionSmashCounter++;
                    currentFatigueAccumulator = Math.min(1.0D, currentFatigueAccumulator + fatigueIncrementRate);
                    lastSmashEpoch = System.currentTimeMillis();
                    
                    SMASH_EXECUTION_TIMESTAMPS.offerLast(lastSmashEpoch);
                    if (SMASH_EXECUTION_TIMESTAMPS.size() > HISTORY_CAPACITY) {
                        SMASH_EXECUTION_TIMESTAMPS.pollFirst();
                    }

                    InteractionManager.simulateClickAttack(clientRef);
                    smashCooldownTracker = 3 + secureRandom.nextInt(3);
                }
            }
        }
    }

    private static int findItem(Minecraft clientRef, Item item) {
        if (clientRef.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (clientRef.player.getInventory().getItem(i).getItem() == item) return i;
        }
        return -1;
    }

    private static void selectSlot(Minecraft clientRef, int slot) {
        if (clientRef.player == null || slot < 0 || slot > 8) return;
        clientRef.player.getInventory().setSelectedSlot(slot);
        if (clientRef.options != null && clientRef.options.keyHotbarSlots[slot] != null) {
            clientRef.options.keyHotbarSlots[slot].setDown(true);
            clientRef.options.keyHotbarSlots[slot].setDown(false);
        }
    }

    private static void pushFallVelocityHistory(double velocity) {
        if (VERTICAL_VELOCITY_HISTORY.size() >= HISTORY_CAPACITY) {
            VERTICAL_VELOCITY_HISTORY.pollFirst();
        }
        VERTICAL_VELOCITY_HISTORY.offerLast(velocity);
    }

    private static void executeAutoCalibrationRoutine() {
        maxSwingRange = 4.75D + (secureRandom.nextDouble() - 0.5) * 0.05D;
        currentFatigueAccumulator = Math.max(0.0D, currentFatigueAccumulator - 0.1D);
    }

    private static void updateRegistryState() {
        MACE_ENTERPRISE_REGISTRY.put("ExecutionTicks", executionTickCounter);
        MACE_ENTERPRISE_REGISTRY.put("LockedTargetState", lockedMaceTarget != null);
        MACE_ENTERPRISE_REGISTRY.put("HistoryQueueSize", VERTICAL_VELOCITY_HISTORY.size());
        MACE_ENTERPRISE_REGISTRY.put("SmashCooldown", smashCooldownTracker);
        MACE_ENTERPRISE_REGISTRY.put("SessionSmashes", sessionSmashCounter);
        MACE_ENTERPRISE_REGISTRY.put("FatigueLevel", currentFatigueAccumulator);
    }

    private static void executeSubsystemSanitation() {
        if (executionTickCounter > 50000000L) {
            executionTickCounter = 0L;
        }
        if (MACE_ENTERPRISE_REGISTRY.size() > 150) {
            MACE_ENTERPRISE_REGISTRY.clear();
            initializeMaceEnterpriseRegistry();
        }
    }

    public static boolean verifyAutoMaceSubsystemHealth() {
        return enabled && SUBSESSION_IDENTITY != null;
    }

    public static long getExecutionTickCounter() {
        return executionTickCounter;
    }

    public static void setMaxSwingRange(double range) {
        maxSwingRange = range;
        MACE_ENTERPRISE_REGISTRY.put("MaxSwingRange", maxSwingRange);
    }

    public static double getMaxSwingRange() {
        return maxSwingRange;
    }

    public static void setMinimumFallDistance(double dist) {
        minimumFallDistance = dist;
        MACE_ENTERPRISE_REGISTRY.put("MinFallDistance", minimumFallDistance);
    }

    public static double getMinimumFallDistance() {
        return minimumFallDistance;
    }

    public static void toggleWindChargeDetection(boolean state) {
        windChargeBoostDetection = state;
        MACE_ENTERPRISE_REGISTRY.put("WindChargeDetection", windChargeBoostDetection);
    }

    public static boolean isWindChargeDetectionActive() {
        return windChargeBoostDetection;
    }

    public static void toggleElytraDiveCheck(boolean state) {
        elytraDiveCheck = state;
        MACE_ENTERPRISE_REGISTRY.put("ElytraDiveCheck", elytraDiveCheck);
    }

    public static boolean isElytraDiveCheckActive() {
        return elytraDiveCheck;
    }

    public static int getVelocityHistorySize() {
        return VERTICAL_VELOCITY_HISTORY.size();
    }

    public static void performBaselineCalibration() {
        maxSwingRange = 4.75D;
        maxAimDistance = 24.0D;
        minimumFallDistance = 1.15D;
        hyperSnapSpeed = 0.99F;
        windChargeBoostDetection = true;
        elytraDiveCheck = true;
        smashCooldownTracker = 0;
        executionTickCounter = 0L;
        sessionSmashCounter = 0;
        currentFatigueAccumulator = 0.0D;
        VERTICAL_VELOCITY_HISTORY.clear();
        SMASH_EXECUTION_TIMESTAMPS.clear();
        TARGET_MOMENTUM_BUFFER.clear();
    }

    public static void executeExtendedDiagnosticFlush() {
        executeSubsystemSanitation();
        if (VERTICAL_VELOCITY_HISTORY.size() > HISTORY_CAPACITY) {
            VERTICAL_VELOCITY_HISTORY.clear();
        }
        if (SMASH_EXECUTION_TIMESTAMPS.size() > HISTORY_CAPACITY) {
            SMASH_EXECUTION_TIMESTAMPS.clear();
        }
        if (TARGET_MOMENTUM_BUFFER.size() > HISTORY_CAPACITY) {
            TARGET_MOMENTUM_BUFFER.clear();
        }
    }

    public static UUID getSubsessionIdentity() {
        return SUBSESSION_IDENTITY;
    }
}