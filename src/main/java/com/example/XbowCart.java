package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Deque;

public class XbowCart extends ClientBase.Module {
    public static final String FILE_NAME = "XbowCart.java";
    public static boolean enabled = false;

    private enum PipelinePhase { VOID, RAIL_ACTION, CART_ACTION, FLINT_ACTION, XBOW_ACTION, CLEANUP }

    private static PipelinePhase currentPhase = PipelinePhase.VOID;
    private static int actionTickCounter = 0;
    private static BlockPos vectorReferencePos = null;
    private static Direction vectorReferenceFace = Direction.UP;
    private static Vec3 vectorHitRegistry = null;
    private static final SafetyWatchdog safetyWatchdog = new SafetyWatchdog();
    private static final SecureRandom secureRandom = new SecureRandom();

    private static final Map<String, Object> XBOW_ENTERPRISE_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_IDENTITY = UUID.randomUUID();
    private static final Deque<Long> EXECUTION_TIMESTAMP_QUEUE = new ArrayDeque<>();
    private static final Deque<Double> STOCHASTIC_LATENCY_DEQUE = new ArrayDeque<>();
    private static final Deque<Vec3> VECTOR_TRAJECTORY_HISTORY = new ArrayDeque<>();
    private static final Deque<Integer> PIPELINE_ERROR_DEQUE = new ArrayDeque<>(); // <-- Deque<Integer>
    private static final Deque<Long> STAGE_DURATION_DEQUE = new ArrayDeque<>();
    private static final int HISTORY_MAX_CAPACITY = 4096;

    private static long pipelineExecutionCounter = 0L;
    private static boolean strictComplianceFlag = true;
    private static int maxPipelineRetries = 7;
    private static int currentRetryAttempt = 0;
    private static double stochasticDelayModifier = 1.25D;
    private static boolean towerCartingModeActive = true;
    private static boolean divebombBypassActive = true;
    private static long globalWatchdogTimeoutMs = 1800L;
    private static int internalSlotCacheIndex = -1;
    private static boolean emergencyHaltFlag = false;
    private static double humanMimeJitterFactor = 0.015D;
    private static int packetThrottlingCounter = 0;
    private static boolean adaptivePacingActive = true;
    private static long subsessionEpochTracker = System.currentTimeMillis();
    private static double spatialPrecisionTolerance = 0.05D;
    private static boolean antiReplayHeuristicShield = true;
    private static int pipelineAnomalyCounter = 0;
    private static boolean tacticalRetreatMode = false;
    private static double targetElevationOffset = 0.2D;
    private static boolean dynamicAngleCorrection = true;
    private static int successiveExecutionCount = 0;
    private static boolean stealthProfileActive = true;
    private static long lastPipelineInvocationEpoch = 0L;
    private static double mouseInertiaWeight = 0.85D;
    private static boolean packetOrderStrictSync = true;
    private static int serverTickOffsetCalibration = 2;

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
        initializeXbowEnterpriseRegistry();
    }

    private static void initializeXbowEnterpriseRegistry() {
        XBOW_ENTERPRISE_REGISTRY.put("SubsessionUUID", SUBSESSION_IDENTITY);
        XBOW_ENTERPRISE_REGISTRY.put("ModuleState", "HT1-Enterprise-XbowCart-Engine-V12");
        XBOW_ENTERPRISE_REGISTRY.put("StrictCompliance", strictComplianceFlag);
        XBOW_ENTERPRISE_REGISTRY.put("InitializationEpoch", subsessionEpochTracker);
        XBOW_ENTERPRISE_REGISTRY.put("ExecutionHistorySize", 0);
        XBOW_ENTERPRISE_REGISTRY.put("MaxRetries", maxPipelineRetries);
        XBOW_ENTERPRISE_REGISTRY.put("CurrentRetryAttempt", currentRetryAttempt);
        XBOW_ENTERPRISE_REGISTRY.put("TowerMode", towerCartingModeActive);
        XBOW_ENTERPRISE_REGISTRY.put("DivebombMode", divebombBypassActive);
        XBOW_ENTERPRISE_REGISTRY.put("JitterFactor", humanMimeJitterFactor);
        XBOW_ENTERPRISE_REGISTRY.put("AdaptivePacing", adaptivePacingActive);
        XBOW_ENTERPRISE_REGISTRY.put("AntiReplayShield", antiReplayHeuristicShield);
        XBOW_ENTERPRISE_REGISTRY.put("StealthProfile", stealthProfileActive);
        XBOW_ENTERPRISE_REGISTRY.put("MouseInertia", mouseInertiaWeight);
        XBOW_ENTERPRISE_REGISTRY.put("PacketOrderSync", packetOrderStrictSync);
        XBOW_ENTERPRISE_REGISTRY.put("AlphaMetric", sessionMetricAlpha);
        XBOW_ENTERPRISE_REGISTRY.put("BetaMetric", sessionMetricBeta);
        XBOW_ENTERPRISE_REGISTRY.put("GammaMetric", sessionMetricGamma);
        XBOW_ENTERPRISE_REGISTRY.put("DeltaMetric", sessionMetricDelta);
    }

    public XbowCart() {
        super("XbowCart");
        XbowCart.enabled = false;
        initializeXbowEnterpriseRegistry();
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void toggle() {
        enabled = !enabled;
        super.enabled = enabled;
        if (!enabled) {
            purgePipelineRegistry();
        } else {
            resetXbowInternalState();
        }
    }

    private static void resetXbowInternalState() {
        currentPhase = PipelinePhase.VOID;
        actionTickCounter = 0;
        vectorReferencePos = null;
        vectorReferenceFace = Direction.UP;
        vectorHitRegistry = null;
        currentRetryAttempt = 0;
        emergencyHaltFlag = false;
        pipelineAnomalyCounter = 0;
        successiveExecutionCount = 0;
        EXECUTION_TIMESTAMP_QUEUE.clear();
        STOCHASTIC_LATENCY_DEQUE.clear();
        VECTOR_TRAJECTORY_HISTORY.clear();
        PIPELINE_ERROR_DEQUE.clear();
        STAGE_DURATION_DEQUE.clear();
        purgeRegistry();
        initializeXbowEnterpriseRegistry();
    }

    @Override
    public void tick(Minecraft clientRef) {
        onTick(clientRef);
    }

    public static boolean validateRegistryItem(Item candidateItem) {
        return candidateItem == Items.RAIL ||
               candidateItem == Items.POWERED_RAIL ||
               candidateItem == Items.DETECTOR_RAIL ||
               candidateItem == Items.ACTIVATOR_RAIL;
    }

    public static void onTick(Minecraft clientRef) {
        if (!enabled || clientRef.player == null || clientRef.level == null) return;
        if (emergencyHaltFlag || safetyWatchdog.isWatchdogLockout()) {
            purgePipelineRegistry();
            return;
        }

        pipelineExecutionCounter++;
        successiveExecutionCount++;
        executeSubsystemSanitation();

        if (actionTickCounter > 0) {
            actionTickCounter--;
            if (actionTickCounter == 0) {
                advancePipelinePhase(clientRef);
            }
            return;
        }

        if (safetyWatchdog.isTimedOut()) {
            handlePipelineFailure(clientRef);
            return;
        }

        switch (currentPhase) {
            case VOID:
                HitResult rawHit = clientRef.hitResult;
                if (rawHit == null || rawHit.getType() != HitResult.Type.BLOCK) return;
                if (!(rawHit instanceof BlockHitResult blockHit)) return;
                
                ItemStack mainHand = clientRef.player.getMainHandItem();
                if (!validateRegistryItem(mainHand.getItem())) return;
                if (InventoryManager.findChargedCrossbow(clientRef) == -1) return;
                
                vectorReferencePos = blockHit.getBlockPos();
                vectorReferenceFace = blockHit.getDirection();
                vectorHitRegistry = blockHit.getLocation();
                
                if (vectorReferenceFace != Direction.UP) return;

                safetyWatchdog.arm();
                currentRetryAttempt = 0;
                lastPipelineInvocationEpoch = System.currentTimeMillis();
                currentPhase = PipelinePhase.RAIL_ACTION;
                break;
            case RAIL_ACTION:
                int r = locateRailSlot(clientRef);
                if (r == -1) {
                    handlePipelineFailure(clientRef);
                    return;
                }
                Vec3 railTarget = vectorHitRegistry != null ? vectorHitRegistry : Vec3.atCenterOf(vectorReferencePos);
                RotationManager.smoothTo(clientRef, railTarget.add(secureRandom.nextDouble() * humanMimeJitterFactor, secureRandom.nextDouble() * humanMimeJitterFactor, secureRandom.nextDouble() * humanMimeJitterFactor), 0.98F);
                InventoryManager.selectSlot(clientRef, r);
                InteractionManager.simulateClickUse(clientRef);
                actionTickCounter = serverTickOffsetCalibration + secureRandom.nextInt(2);
                break;
            case CART_ACTION:
                int c = InventoryManager.findItem(clientRef, Items.TNT_MINECART);
                if (c == -1) {
                    handlePipelineFailure(clientRef);
                    return;
                }
                BlockPos cartPos = vectorReferenceFace == Direction.UP ? vectorReferencePos : vectorReferencePos.relative(vectorReferenceFace);
                if (towerCartingModeActive && vectorReferenceFace != Direction.UP) {
                    cartPos = vectorReferencePos.above();
                }
                Vec3 cartTarget = Vec3.atCenterOf(cartPos);
                RotationManager.smoothTo(clientRef, cartTarget.add(secureRandom.nextDouble() * humanMimeJitterFactor, secureRandom.nextDouble() * humanMimeJitterFactor, secureRandom.nextDouble() * humanMimeJitterFactor), 0.98F);
                InventoryManager.selectSlot(clientRef, c);
                InteractionManager.simulateClickUse(clientRef);
                actionTickCounter = serverTickOffsetCalibration + secureRandom.nextInt(2);
                break;
            case FLINT_ACTION:
                int f = InventoryManager.findItem(clientRef, Items.FLINT_AND_STEEL);
                if (f == -1) f = InventoryManager.findItem(clientRef, Items.FIRE_CHARGE);
                if (f == -1) {
                    handlePipelineFailure(clientRef);
                    return;
                }
                BlockPos firePos = vectorReferenceFace == Direction.UP ? vectorReferencePos.relative(clientRef.player.getDirection().getOpposite()) : vectorReferencePos;
                Vec3 fireTarget = Vec3.atCenterOf(firePos);
                RotationManager.smoothTo(clientRef, fireTarget.add(secureRandom.nextDouble() * humanMimeJitterFactor, secureRandom.nextDouble() * humanMimeJitterFactor, secureRandom.nextDouble() * humanMimeJitterFactor), 0.98F);
                InventoryManager.selectSlot(clientRef, f);
                InteractionManager.simulateClickUse(clientRef);
                actionTickCounter = serverTickOffsetCalibration + secureRandom.nextInt(2);
                break;
            case XBOW_ACTION:
                int x = InventoryManager.findChargedCrossbow(clientRef);
                if (x == -1) {
                    handlePipelineFailure(clientRef);
                    return;
                }
                BlockPos shootPos = vectorReferenceFace == Direction.UP ? vectorReferencePos : vectorReferencePos.relative(vectorReferenceFace);
                Vec3 shootTarget = Vec3.atCenterOf(shootPos).add(0.0D, targetElevationOffset, 0.0D);
                RotationManager.smoothTo(clientRef, shootTarget.add(secureRandom.nextDouble() * humanMimeJitterFactor, secureRandom.nextDouble() * humanMimeJitterFactor, secureRandom.nextDouble() * humanMimeJitterFactor), 0.98F);
                InventoryManager.selectSlot(clientRef, x);
                InteractionManager.simulateClickUse(clientRef);
                actionTickCounter = serverTickOffsetCalibration + secureRandom.nextInt(2);
                break;
            case CLEANUP:
                purgePipelineRegistry();
                break;
        }
        updateRegistryState();
    }

    private static void handlePipelineFailure(Minecraft clientRef) {
        currentRetryAttempt++;
        pipelineAnomalyCounter++;
        PIPELINE_ERROR_DEQUE.offerLast(currentPhase.ordinal()); // CORRIGIDO: sem cast
        if (PIPELINE_ERROR_DEQUE.size() > HISTORY_MAX_CAPACITY) {
            PIPELINE_ERROR_DEQUE.pollFirst();
        }
        if (currentRetryAttempt <= maxPipelineRetries) {
            actionTickCounter = 3 + secureRandom.nextInt(3);
        } else {
            emergencyHaltFlag = true;
            purgePipelineRegistry();
        }
    }

    private static void advancePipelinePhase(Minecraft clientRef) {
        if (clientRef != null && clientRef.options != null) {
            clientRef.options.keyUse.setDown(false);
        }
        long stageDuration = System.currentTimeMillis() - lastPipelineInvocationEpoch;
        STAGE_DURATION_DEQUE.offerLast(stageDuration);
        if (STAGE_DURATION_DEQUE.size() > HISTORY_MAX_CAPACITY) {
            STAGE_DURATION_DEQUE.pollFirst();
        }

        switch (currentPhase) {
            case RAIL_ACTION: currentPhase = PipelinePhase.CART_ACTION; break;
            case CART_ACTION: currentPhase = PipelinePhase.FLINT_ACTION; break;
            case FLINT_ACTION: currentPhase = PipelinePhase.XBOW_ACTION; break;
            case XBOW_ACTION: currentPhase = PipelinePhase.CLEANUP; break;
            default: purgePipelineRegistry(); break;
        }

        if (EXECUTION_TIMESTAMP_QUEUE.size() >= HISTORY_MAX_CAPACITY) {
            EXECUTION_TIMESTAMP_QUEUE.pollFirst();
        }
        EXECUTION_TIMESTAMP_QUEUE.offerLast(System.currentTimeMillis());
        updateRegistryState();
    }

    private static int locateRailSlot(Minecraft clientRef) {
        if (clientRef.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            Item itemNode = clientRef.player.getInventory().getItem(i).getItem();
            if (validateRegistryItem(itemNode)) return i;
        }
        return -1;
    }

    private static void updateRegistryState() {
        XBOW_ENTERPRISE_REGISTRY.put("ExecutionCounter", pipelineExecutionCounter);
        XBOW_ENTERPRISE_REGISTRY.put("PipelineStage", currentPhase.name());
        XBOW_ENTERPRISE_REGISTRY.put("HistorySize", EXECUTION_TIMESTAMP_QUEUE.size());
        XBOW_ENTERPRISE_REGISTRY.put("CurrentRetryAttempt", currentRetryAttempt);
        XBOW_ENTERPRISE_REGISTRY.put("EmergencyHalt", emergencyHaltFlag);
        XBOW_ENTERPRISE_REGISTRY.put("AnomalyCount", pipelineAnomalyCounter);
        XBOW_ENTERPRISE_REGISTRY.put("SuccessiveExecutions", successiveExecutionCount);
    }

    private static void executeSubsystemSanitation() {
        if (pipelineExecutionCounter > 100000000L) {
            pipelineExecutionCounter = 0L;
        }
        if (XBOW_ENTERPRISE_REGISTRY.size() > 250) {
            purgeRegistry();
            initializeXbowEnterpriseRegistry();
        }
    }

    private static void purgeRegistry() {
        XBOW_ENTERPRISE_REGISTRY.clear();
    }

    public static void purgePipelineRegistry() {
        currentPhase = PipelinePhase.VOID;
        vectorReferencePos = null;
        vectorReferenceFace = Direction.UP;
        vectorHitRegistry = null;
        actionTickCounter = 0;
        currentRetryAttempt = 0;
        safetyWatchdog.disarm();
    }

    public static boolean verifyXbowSubsystemHealth() {
        return enabled && SUBSESSION_IDENTITY != null;
    }

    public static long getPipelineExecutionCounter() {
        return pipelineExecutionCounter;
    }

    public static PipelinePhase getPipelineStage() {
        return currentPhase;
    }

    public static void setStrictCompliance(boolean state) {
        strictComplianceFlag = state;
        XBOW_ENTERPRISE_REGISTRY.put("StrictCompliance", strictComplianceFlag);
    }

    public static boolean isStrictComplianceActive() {
        return strictComplianceFlag;
    }

    public static void setMaxRetries(int retries) {
        maxPipelineRetries = Math.max(0, retries);
        XBOW_ENTERPRISE_REGISTRY.put("MaxRetries", maxPipelineRetries);
    }

    public static int getMaxRetries() {
        return maxPipelineRetries;
    }

    public static void performBaselineCalibration() {
        strictComplianceFlag = true;
        maxPipelineRetries = 7;
        currentRetryAttempt = 0;
        pipelineExecutionCounter = 0L;
        emergencyHaltFlag = false;
        pipelineAnomalyCounter = 0;
        successiveExecutionCount = 0;
        EXECUTION_TIMESTAMP_QUEUE.clear();
        STOCHASTIC_LATENCY_DEQUE.clear();
        VECTOR_TRAJECTORY_HISTORY.clear();
        PIPELINE_ERROR_DEQUE.clear();
        STAGE_DURATION_DEQUE.clear();
    }

    public static void executeExtendedDiagnosticFlush() {
        executeSubsystemSanitation();
        if (EXECUTION_TIMESTAMP_QUEUE.size() > HISTORY_MAX_CAPACITY) {
            EXECUTION_TIMESTAMP_QUEUE.clear();
        }
        if (STOCHASTIC_LATENCY_DEQUE.size() > HISTORY_MAX_CAPACITY) {
            STOCHASTIC_LATENCY_DEQUE.clear();
        }
        if (VECTOR_TRAJECTORY_HISTORY.size() > HISTORY_MAX_CAPACITY) {
            VECTOR_TRAJECTORY_HISTORY.clear();
        }
        if (PIPELINE_ERROR_DEQUE.size() > HISTORY_MAX_CAPACITY) {
            PIPELINE_ERROR_DEQUE.clear();
        }
        if (STAGE_DURATION_DEQUE.size() > HISTORY_MAX_CAPACITY) {
            STAGE_DURATION_DEQUE.clear();
        }
    }

    public static UUID getSubsessionIdentity() {
        return SUBSESSION_IDENTITY;
    }

    public static void setTowerCartingMode(boolean state) {
        towerCartingModeActive = state;
        XBOW_ENTERPRISE_REGISTRY.put("TowerMode", towerCartingModeActive);
    }

    public static boolean isTowerCartingModeActive() {
        return towerCartingModeActive;
    }

    public static void setDivebombBypass(boolean state) {
        divebombBypassActive = state;
        XBOW_ENTERPRISE_REGISTRY.put("DivebombMode", divebombBypassActive);
    }

    public static boolean isDivebombBypassActive() {
        return divebombBypassActive;
    }

    public static int getInternalSlotCacheIndex() {
        return internalSlotCacheIndex;
    }

    public static void setInternalSlotCacheIndex(int idx) {
        internalSlotCacheIndex = idx;
    }

    public static double getStochasticDelayModifier() {
        return stochasticDelayModifier;
    }

    public static void setStochasticDelayModifier(double modifier) {
        stochasticDelayModifier = modifier;
    }

    public static long getGlobalWatchdogTimeoutMs() {
    return globalWatchdogTimeoutMs;
}

public static void setGlobalWatchdogTimeoutMs(long timeout) {
    globalWatchdogTimeoutMs = timeout;
    XBOW_ENTERPRISE_REGISTRY.put("WatchdogTimeout", globalWatchdogTimeoutMs);
}