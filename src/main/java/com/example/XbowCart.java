package com.example;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
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
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.Entity;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Deque;

public class XbowCart extends ClientBase.Module {
    public static final String FILE_NAME = "XbowCart.java";
    public static boolean enabled = true;
    private static final SecureRandom secureRandom = new SecureRandom();

    private enum PipelinePhase { 
        VOID, 
        RAIL_ACTION, 
        CART_ACTION, 
        FLINT_ACTION, 
        XBOW_ACTION, 
        CLEANUP 
    }

    private static PipelinePhase currentPhase = PipelinePhase.VOID;
    private static int actionTickCounter = 0;
    private static BlockPos vectorReferencePos = null;
    private static Direction vectorReferenceFace = Direction.UP;
    private static Vec3 vectorHitRegistry = null;
    private static BlockPos resolvedRailPos = null;
    private static BlockPos resolvedCartPos = null;
    private static BlockPos resolvedFirePos = null;
    private static final SafetyWatchdog safetyWatchdog = new SafetyWatchdog();

    private static final Map<String, Object> XBOW_FIXED_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_IDENTITY = UUID.randomUUID();
    private static final Deque<Long> EXECUTION_TIMESTAMP_QUEUE = new ArrayDeque<>();
    private static final Deque<Double> STOCHASTIC_LATENCY_DEQUE = new ArrayDeque<>();
    private static final Deque<Vec3> VECTOR_TRAJECTORY_HISTORY = new ArrayDeque<>();
    private static final Deque<Integer> PIPELINE_ERROR_DEQUE = new ArrayDeque<>();
    private static final Deque<Long> STAGE_DURATION_DEQUE = new ArrayDeque<>();
    private static final int HISTORY_MAX_CAPACITY = 8192;

    private static long pipelineExecutionCounter = 0L;
    private static boolean strictComplianceFlag = true;
    private static int maxPipelineRetries = 5;
    private static int currentRetryAttempt = 0;
    private static double stochasticDelayModifier = 1.0D;
    private static boolean towerCartingModeActive = true;
    private static boolean divebombBypassActive = true;
    private static long globalWatchdogTimeoutMs = 1200L;
    private static int internalSlotCacheIndex = -1;
    private static boolean emergencyHaltFlag = false;
    private static double humanMimeJitterFactor = 0.003D;
    private static int packetThrottlingCounter = 0;
    private static boolean adaptivePacingActive = true;
    private static long subsessionEpochTracker = System.currentTimeMillis();
    private static double spatialPrecisionTolerance = 0.01D;
    private static boolean antiReplayHeuristicShield = true;
    private static int pipelineAnomalyCounter = 0;
    private static boolean tacticalRetreatMode = false;
    private static double targetElevationOffset = 0.12D;
    private static boolean dynamicAngleCorrection = true;
    private static int successiveExecutionCount = 0;
    private static boolean stealthProfileActive = true;
    private static long lastPipelineInvocationEpoch = 0L;
    private static double mouseInertiaWeight = 0.98D;
    private static boolean packetOrderStrictSync = true;
    private static int uniformTickDelay = 2;
    private static int serverTickOffsetCalibration = 1;

    static {
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (enabled) {
                onTick(client);
            }
        });
        initializeXbowFixedRegistry();
    }

    private static void initializeXbowFixedRegistry() {
        XBOW_FIXED_REGISTRY.put("SubsessionUUID", SUBSESSION_IDENTITY);
        XBOW_FIXED_REGISTRY.put("ModuleState", "HT1-Fixed-XbowCart-800Lines");
        XBOW_FIXED_REGISTRY.put("StrictCompliance", strictComplianceFlag);
        XBOW_FIXED_REGISTRY.put("InitializationEpoch", subsessionEpochTracker);
        XBOW_FIXED_REGISTRY.put("ExecutionHistorySize", 0);
        XBOW_FIXED_REGISTRY.put("MaxRetries", maxPipelineRetries);
        XBOW_FIXED_REGISTRY.put("CurrentRetryAttempt", currentRetryAttempt);
        XBOW_FIXED_REGISTRY.put("TowerMode", towerCartingModeActive);
        XBOW_FIXED_REGISTRY.put("DivebombMode", divebombBypassActive);
        XBOW_FIXED_REGISTRY.put("JitterFactor", humanMimeJitterFactor);
        XBOW_FIXED_REGISTRY.put("AdaptivePacing", adaptivePacingActive);
        XBOW_FIXED_REGISTRY.put("AntiReplayShield", antiReplayHeuristicShield);
        XBOW_FIXED_REGISTRY.put("StealthProfile", stealthProfileActive);
        XBOW_FIXED_REGISTRY.put("MouseInertia", mouseInertiaWeight);
        XBOW_FIXED_REGISTRY.put("PacketOrderSync", packetOrderStrictSync);
    }

    public XbowCart() {
        super("XbowCart");
        XbowCart.enabled = true;
        initializeXbowFixedRegistry();
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
        resolvedRailPos = null;
        resolvedCartPos = null;
        resolvedFirePos = null;
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
        initializeXbowFixedRegistry();
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

    private static boolean isRailBlock(BlockState state) {
        return state.is(Blocks.RAIL) || state.is(Blocks.POWERED_RAIL) || state.is(Blocks.DETECTOR_RAIL) || state.is(Blocks.ACTIVATOR_RAIL);
    }

    public static void onTick(Minecraft clientRef) {
        if (!enabled || clientRef.player == null || clientRef.level == null) return;
        if (emergencyHaltFlag || safetyWatchdog.isWatchdogLockout()) {
            purgePipelineRegistry();
            return;
        }

        pipelineExecutionCounter++;
        successiveExecutionCount++;

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
                if (rawHit == null || rawHit.getType() != HitResult.Type.BLOCK) {
                    HitResult fallbackHit = clientRef.player.pick(4.5D, 0.0F, false);
                    if (fallbackHit.getType() == HitResult.Type.BLOCK) {
                        rawHit = fallbackHit;
                    } else {
                        return;
                    }
                }

                if (!(rawHit instanceof BlockHitResult blockHit)) return;
                
                ItemStack mainHand = clientRef.player.getMainHandItem();
                if (!validateRegistryItem(mainHand.getItem())) return;
                if (InventoryManager.findChargedCrossbow(clientRef) == -1) return;
                if (InventoryManager.findItem(clientRef, Items.TNT_MINECART) == -1) return;

                vectorReferencePos = blockHit.getBlockPos();
                vectorReferenceFace = blockHit.getDirection();
                vectorHitRegistry = blockHit.getLocation();

                BlockState hitState = clientRef.level.getBlockState(vectorReferencePos);
                if (isRailBlock(hitState)) {
                    resolvedRailPos = vectorReferencePos;
                } else if (vectorReferenceFace == Direction.UP) {
                    resolvedRailPos = vectorReferencePos.above();
                } else {
                    resolvedRailPos = vectorReferencePos.relative(vectorReferenceFace);
                    if (!clientRef.level.getBlockState(resolvedRailPos).isAir() && clientRef.level.getBlockState(resolvedRailPos.above()).isAir()) {
                        resolvedRailPos = resolvedRailPos.above();
                    }
                }

                resolvedCartPos = resolvedRailPos;
                Direction playerFacing = clientRef.player.getDirection();
                resolvedFirePos = resolvedRailPos.relative(playerFacing.getOpposite());
                if (!clientRef.level.getBlockState(resolvedFirePos.below()).isSolid()) {
                    resolvedFirePos = resolvedRailPos.relative(playerFacing);
                }

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
                Vec3 railTarget = Vec3.atCenterOf(resolvedRailPos);
                RotationManager.smoothTo(clientRef, railTarget, 0.99F);

                InventoryManager.selectSlot(clientRef, r);
                InteractionManager.simulateClickUse(clientRef);
                actionTickCounter = uniformTickDelay;
                break;

            case CART_ACTION:
                int c = InventoryManager.findItem(clientRef, Items.TNT_MINECART);
                if (c == -1) {
                    handlePipelineFailure(clientRef);
                    return;
                }
                Vec3 cartTarget = Vec3.atCenterOf(resolvedCartPos);
                RotationManager.smoothTo(clientRef, cartTarget, 0.99F);

                InventoryManager.selectSlot(clientRef, c);
                InteractionManager.simulateClickUse(clientRef);
                actionTickCounter = uniformTickDelay;
                break;

            case FLINT_ACTION:
                int f = InventoryManager.findItem(clientRef, Items.FLINT_AND_STEEL);
                if (f == -1) f = InventoryManager.findItem(clientRef, Items.FIRE_CHARGE);
                if (f == -1) {
                    handlePipelineFailure(clientRef);
                    return;
                }
                Vec3 fireTarget = Vec3.atCenterOf(resolvedFirePos);
                RotationManager.smoothTo(clientRef, fireTarget, 0.99F);

                InventoryManager.selectSlot(clientRef, f);
                InteractionManager.simulateClickUse(clientRef);
                actionTickCounter = uniformTickDelay;
                break;

            case XBOW_ACTION:
                int x = InventoryManager.findChargedCrossbow(clientRef);
                if (x == -1) {
                    handlePipelineFailure(clientRef);
                    return;
                }
                Vec3 cartCenter = Vec3.atCenterOf(resolvedCartPos);
                Vec3 fireCenter = Vec3.atCenterOf(resolvedFirePos);
                Vec3 trajectoryMidpoint = cartCenter.add(fireCenter).scale(0.5D);
                double distanceToCart = clientRef.player.position().distanceTo(cartCenter);
                double dynamicElevation = targetElevationOffset + (distanceToCart * 0.025D);
                Vec3 shootTarget = trajectoryMidpoint.add(0.0D, dynamicElevation, 0.0D);

                RotationManager.smoothTo(clientRef, shootTarget, 0.99F);

                InventoryManager.selectSlot(clientRef, x);
                InteractionManager.simulateClickUse(clientRef);
                actionTickCounter = 2;
                break;

            case CLEANUP:
                purgePipelineRegistry();
                break;
        }
    }

    private static void handlePipelineFailure(Minecraft clientRef) {
        currentRetryAttempt++;
        pipelineAnomalyCounter++;
        PIPELINE_ERROR_DEQUE.offerLast(currentPhase.ordinal());
        if (PIPELINE_ERROR_DEQUE.size() > HISTORY_MAX_CAPACITY) {
            PIPELINE_ERROR_DEQUE.pollFirst();
        }
        if (currentRetryAttempt <= maxPipelineRetries) {
            actionTickCounter = 1;
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
            case RAIL_ACTION:
                currentPhase = PipelinePhase.CART_ACTION;
                break;
            case CART_ACTION:
                currentPhase = PipelinePhase.FLINT_ACTION;
                break;
            case FLINT_ACTION:
                currentPhase = PipelinePhase.XBOW_ACTION;
                break;
            case XBOW_ACTION:
                currentPhase = PipelinePhase.CLEANUP;
                break;
            default:
                purgePipelineRegistry();
                break;
        }

        if (EXECUTION_TIMESTAMP_QUEUE.size() >= HISTORY_MAX_CAPACITY) {
            EXECUTION_TIMESTAMP_QUEUE.pollFirst();
        }
        EXECUTION_TIMESTAMP_QUEUE.offerLast(System.currentTimeMillis());
    }

    private static int locateRailSlot(Minecraft clientRef) {
        if (clientRef.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            Item itemNode = clientRef.player.getInventory().getItem(i).getItem();
            if (validateRegistryItem(itemNode)) return i;
        }
        return -1;
    }

    private static void purgeRegistry() {
        XBOW_FIXED_REGISTRY.clear();
    }

    public static void purgePipelineRegistry() {
        currentPhase = PipelinePhase.VOID;
        vectorReferencePos = null;
        vectorReferenceFace = Direction.UP;
        vectorHitRegistry = null;
        resolvedRailPos = null;
        resolvedCartPos = null;
        resolvedFirePos = null;
        actionTickCounter = 0;
        currentRetryAttempt = 0;
        safetyWatchdog.disarm();
    }
                    }
