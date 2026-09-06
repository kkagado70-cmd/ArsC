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

    private static final Map<String, Object> XBOW_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_IDENTITY = UUID.randomUUID();
    private static final Deque<Long> EXECUTION_TIMESTAMP_QUEUE = new ArrayDeque<>();
    private static final int HISTORY_MAX_LIMIT = 64;
    private static long pipelineExecutionCounter = 0L;
    private static boolean strictComplianceFlag = true;

    public XbowCart() {
        super("XbowCart");
        XbowCart.enabled = false;
        initializeXbowRegistry();
    }

    private static void initializeXbowRegistry() {
        XBOW_REGISTRY.put("SubsessionUUID", SUBSESSION_IDENTITY);
        XBOW_REGISTRY.put("ModuleState", "HT1-XbowCart-Pipeline-Engine");
        XBOW_REGISTRY.put("StrictCompliance", strictComplianceFlag);
        XBOW_REGISTRY.put("InitializationEpoch", System.currentTimeMillis());
        XBOW_REGISTRY.put("ExecutionHistorySize", 0);
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
        EXECUTION_TIMESTAMP_QUEUE.clear();
        purgeRegistry();
        initializeXbowRegistry();
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
            purgePipelineRegistry();
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
                currentPhase = PipelinePhase.RAIL_ACTION;
                break;
            case RAIL_ACTION:
                int r = locateRailSlot(clientRef);
                if (r == -1) { purgePipelineRegistry(); return; }
                RotationManager.smoothTo(clientRef, vectorHitRegistry != null ? vectorHitRegistry : Vec3.atCenterOf(vectorReferencePos), 0.99F);
                InventoryManager.selectSlot(clientRef, r);
                InteractionManager.simulateClickUse(clientRef);
                actionTickCounter = 2;
                break;
            case CART_ACTION:
                int c = InventoryManager.findItem(clientRef, Items.TNT_MINECART);
                if (c == -1) { purgePipelineRegistry(); return; }
                BlockPos cartPos = vectorReferenceFace == Direction.UP ? vectorReferencePos : vectorReferencePos.relative(vectorReferenceFace);
                RotationManager.smoothTo(clientRef, Vec3.atCenterOf(cartPos), 0.99F);
                InventoryManager.selectSlot(clientRef, c);
                InteractionManager.simulateClickUse(clientRef);
                actionTickCounter = 2;
                break;
            case FLINT_ACTION:
                int f = InventoryManager.findItem(clientRef, Items.FLINT_AND_STEEL);
                if (f == -1) f = InventoryManager.findItem(clientRef, Items.FIRE_CHARGE);
                if (f == -1) { purgePipelineRegistry(); return; }
                BlockPos firePos = vectorReferenceFace == Direction.UP ? vectorReferencePos.relative(clientRef.player.getDirection().getOpposite()) : vectorReferencePos;
                RotationManager.smoothTo(clientRef, Vec3.atCenterOf(firePos), 0.99F);
                InventoryManager.selectSlot(clientRef, f);
                InteractionManager.simulateClickUse(clientRef);
                actionTickCounter = 2;
                break;
            case XBOW_ACTION:
                int x = InventoryManager.findChargedCrossbow(clientRef);
                if (x == -1) { purgePipelineRegistry(); return; }
                BlockPos shootPos = vectorReferenceFace == Direction.UP ? vectorReferencePos : vectorReferencePos.relative(vectorReferenceFace);
                RotationManager.smoothTo(clientRef, Vec3.atCenterOf(shootPos).add(0.0D, 0.2D, 0.0D), 0.99F);
                InventoryManager.selectSlot(clientRef, x);
                InteractionManager.simulateClickUse(clientRef);
                actionTickCounter = 2;
                break;
            case CLEANUP:
                purgePipelineRegistry();
                break;
        }
    }

    private static void advancePipelinePhase(Minecraft clientRef) {
        clientRef.options.keyUse.setDown(false);
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
        XBOW_REGISTRY.put("ExecutionCounter", pipelineExecutionCounter);
        XBOW_REGISTRY.put("PipelineStage", currentPhase.name());
        XBOW_REGISTRY.put("HistorySize", EXECUTION_TIMESTAMP_QUEUE.size());
    }

    private static void executeSubsystemSanitation() {
        if (pipelineExecutionCounter > 5000000L) {
            pipelineExecutionCounter = 0L;
        }
        if (XBOW_REGISTRY.size() > 80) {
            purgeRegistry();
            initializeXbowRegistry();
        }
    }

    private static void purgeRegistry() {
        XBOW_REGISTRY.clear();
    }

    public static void purgePipelineRegistry() {
        currentPhase = PipelinePhase.VOID;
        vectorReferencePos = null;
        vectorReferenceFace = Direction.UP;
        vectorHitRegistry = null;
        actionTickCounter = 0;
        safetyWatchdog.disarm();
    }

    public static boolean verifyXbowSubsystem() {
        return enabled && SUBSESSION_IDENTITY != null;
    }

    public static long getPipelineExecutionCounter() {
        return pipelineExecutionCounter;
    }

    public static PipelinePhase getPipelineStage() {
        return currentPhase;
    }
}