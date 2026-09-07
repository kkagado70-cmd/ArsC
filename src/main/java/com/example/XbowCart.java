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

import java.util.Random;
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

    private static final Map<String, Object> XBOW_ENTERPRISE_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_IDENTITY = UUID.randomUUID();
    private static final Deque<Long> EXECUTION_TIMESTAMP_QUEUE = new ArrayDeque<>();
    private static final int HISTORY_MAX_LIMIT = 256;
    private static long pipelineExecutionCounter = 0L;
    private static boolean strictComplianceFlag = true;
    private static int maxPipelineRetries = 5;
    private static int currentRetryAttempt = 0;
    private static double stochasticDelayModifier = 1.0D;
    private static boolean towerCartingModeActive = true;
    private static boolean divebombBypassActive = true;
    private static long globalWatchdogTimeoutMs = 1500L;
    private static int internalSlotCacheIndex = -1;
    private static boolean emergencyHaltFlag = false;

    public XbowCart() {
        super("XbowCart");
        XbowCart.enabled = false;
        initializeXbowEnterpriseRegistry();
    }

    private static void initializeXbowEnterpriseRegistry() {
        XBOW_ENTERPRISE_REGISTRY.put("SubsessionUUID", SUBSESSION_IDENTITY);
        XBOW_ENTERPRISE_REGISTRY.put("ModuleState", "HT1-Enterprise-XbowCart-Engine-V4");
        XBOW_ENTERPRISE_REGISTRY.put("StrictCompliance", strictComplianceFlag);
        XBOW_ENTERPRISE_REGISTRY.put("InitializationEpoch", System.currentTimeMillis());
        XBOW_ENTERPRISE_REGISTRY.put("ExecutionHistorySize", 0);
        XBOW_ENTERPRISE_REGISTRY.put("MaxRetries", maxPipelineRetries);
        XBOW_ENTERPRISE_REGISTRY.put("CurrentRetryAttempt", currentRetryAttempt);
        XBOW_ENTERPRISE_REGISTRY.put("TowerMode", towerCartingModeActive);
        XBOW_ENTERPRISE_REGISTRY.put("DivebombMode", divebombBypassActive);
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
        EXECUTION_TIMESTAMP_QUEUE.clear();
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
        if (emergencyHaltFlag) {
            purgePipelineRegistry();
            return;
        }

        pipelineExecutionCounter++;
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
                BlockHitResult hit = RaycastManager.getValidHit(clientRef);
                if (hit == null || !validateRegistryItem(clientRef.player.getMainHandItem().getItem()) || InventoryManager.findChargedCrossbow(clientRef) == -1) return;
                vectorReferencePos = hit.getBlockPos();
                vectorReferenceFace = hit.getDirection();
                vectorHitRegistry = hit.getLocation();
                safetyWatchdog.arm();
                currentRetryAttempt = 0;
                currentPhase = PipelinePhase.RAIL_ACTION;
                break;
            case RAIL_ACTION:
                int r = locateRailSlot(clientRef);
                if (r == -1) {
                    handlePipelineFailure(clientRef);
                    return;
                }
                RotationManager.smoothTo(clientRef, vectorHitRegistry != null ? vectorHitRegistry : Vec3.atCenterOf(vectorReferencePos), 0.99F);
                InventoryManager.selectSlot(clientRef, r);
                InteractionManager.simulateClickUse(clientRef);
                actionTickCounter = 2 + new Random().nextInt(2);
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
                RotationManager.smoothTo(clientRef, Vec3.atCenterOf(cartPos), 0.99F);
                InventoryManager.selectSlot(clientRef, c);
                InteractionManager.simulateClickUse(clientRef);
                actionTickCounter = 2 + new Random().nextInt(2);
                break;
            case FLINT_ACTION:
                int f = InventoryManager.findItem(clientRef, Items.FLINT_AND_STEEL);
                if (f == -1) f = InventoryManager.findItem(clientRef, Items.FIRE_CHARGE);
                if (f == -1) {
                    handlePipelineFailure(clientRef);
                    return;
                }
                BlockPos firePos = vectorReferenceFace == Direction.UP ? vectorReferencePos.relative(clientRef.player.getDirection().getOpposite()) : vectorReferencePos;
                RotationManager.smoothTo(clientRef, Vec3.atCenterOf(firePos), 0.99F);
                InventoryManager.selectSlot(clientRef, f);
                InteractionManager.simulateClickUse(clientRef);
                actionTickCounter = 2 + new Random().nextInt(2);
                break;
            case XBOW_ACTION:
                int x = InventoryManager.findChargedCrossbow(clientRef);
                if (x == -1) {
                    handlePipelineFailure(clientRef);
                    return;
                }
                BlockPos shootPos = vectorReferenceFace == Direction.UP ? vectorReferencePos : vectorReferencePos.relative(vectorReferenceFace);
                RotationManager.smoothTo(clientRef, Vec3.atCenterOf(shootPos).add(0.0D, 0.2D, 0.0D), 0.99F);
                InventoryManager.selectSlot(clientRef, x);
                InteractionManager.simulateClickUse(clientRef);
                actionTickCounter = 2 + new Random().nextInt(2);
                break;
            case CLEANUP:
                purgePipelineRegistry();
                break;
        }
        updateRegistryState();
    }

    private static void handlePipelineFailure(Minecraft clientRef) {
        currentRetryAttempt++;
        if (currentRetryAttempt <= maxPipelineRetries) {
            actionTickCounter = 3;
        } else {
            emergencyHaltFlag = true;
            purgePipelineRegistry();
        }
    }

    private static void advancePipelinePhase(Minecraft clientRef) {
        if (clientRef != null && clientRef.options != null) {
            clientRef.options.keyUse.setDown(false);
        }
        switch (currentPhase) {
            case RAIL_ACTION: currentPhase = PipelinePhase.CART_ACTION; break;
            case CART_ACTION: currentPhase = PipelinePhase.FLINT_ACTION; break;
            case FLINT_ACTION: currentPhase = PipelinePhase.XBOW_ACTION; break;
            case XBOW_ACTION: currentPhase = PipelinePhase.CLEANUP; break;
            default: purgePipelineRegistry(); break;
        }

        if (EXECUTION_TIMESTAMP_QUEUE.size() >= HISTORY_MAX_LIMIT) {
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
    }

    private static void executeSubsystemSanitation() {
        if (pipelineExecutionCounter > 10000000L) {
            pipelineExecutionCounter = 0L;
        }
        if (XBOW_ENTERPRISE_REGISTRY.size() > 120) {
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
        maxPipelineRetries = 5;
        currentRetryAttempt = 0;
        pipelineExecutionCounter = 0L;
        emergencyHaltFlag = false;
        EXECUTION_TIMESTAMP_QUEUE.clear();
    }

    public static void executeExtendedDiagnosticFlush() {
        executeSubsystemSanitation();
        if (EXECUTION_TIMESTAMP_QUEUE.size() > HISTORY_MAX_LIMIT) {
            EXECUTION_TIMESTAMP_QUEUE.clear();
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
    }
}