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

public class XbowCart extends ClientBase.Module {
    public static final String FILE_NAME = "XbowCart.java";
    public static boolean enabled = true;

    private enum PipelinePhase {
        VOID,
        RAIL_SELECT,
        RAIL_DEPLOY,
        CART_SELECT,
        CART_DEPLOY,
        FLINT_SELECT,
        FLINT_DEPLOY,
        XBOW_SELECT,
        XBOW_DEPLOY,
        CLEANUP,
        DONE
    }

    private static PipelinePhase currentPhase = PipelinePhase.VOID;
    private static int actionTickCounter = 0;
    private static int globalCooldownTicks = 0;
    private static BlockPos vectorReferencePos = null;
    private static Direction vectorReferenceFace = Direction.UP;
    private static Vec3 vectorHitRegistry = null;
    private static final SafetyWatchdog safetyWatchdog = new SafetyWatchdog();

    private static int activeTargetSlot = 0;

    public XbowCart() {
        super("XbowCart");
        XbowCart.enabled = true;
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
        globalCooldownTicks = 0;
        vectorReferencePos = null;
        vectorReferenceFace = Direction.UP;
        vectorHitRegistry = null;
        activeTargetSlot = 0;
        safetyWatchdog.disarm();
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

        if (globalCooldownTicks > 0) {
            globalCooldownTicks--;
            if (globalCooldownTicks == 0 && currentPhase == PipelinePhase.DONE) {
                currentPhase = PipelinePhase.VOID;
            }
            return;
        }

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
                currentPhase = PipelinePhase.RAIL_SELECT;
                break;
            case RAIL_SELECT:
                activeTargetSlot = locateRailSlot(clientRef);
                if (activeTargetSlot == -1) { purgePipelineRegistry(); return; }
                pressHotbarSlotDown(clientRef, activeTargetSlot);
                actionTickCounter = 1;
                break;
            case RAIL_DEPLOY:
                releaseHotbarSlot(clientRef, activeTargetSlot);
                RotationManager.smoothTo(clientRef, vectorHitRegistry != null ? vectorHitRegistry : Vec3.atCenterOf(vectorReferencePos), 0.99F);
                InteractionManager.simulateClickUse(clientRef);
                actionTickCounter = 2;
                break;
            case CART_SELECT:
                activeTargetSlot = InventoryManager.findItem(clientRef, Items.TNT_MINECART);
                if (activeTargetSlot == -1) { purgePipelineRegistry(); return; }
                pressHotbarSlotDown(clientRef, activeTargetSlot);
                actionTickCounter = 1;
                break;
            case CART_DEPLOY:
                releaseHotbarSlot(clientRef, activeTargetSlot);
                BlockPos cartPos = vectorReferenceFace == Direction.UP ? vectorReferencePos : vectorReferencePos.relative(vectorReferenceFace);
                RotationManager.smoothTo(clientRef, Vec3.atCenterOf(cartPos), 0.99F);
                InteractionManager.simulateClickUse(clientRef);
                actionTickCounter = 2;
                break;
            case FLINT_SELECT:
                int f = InventoryManager.findItem(clientRef, Items.FLINT_AND_STEEL);
                if (f == -1) f = InventoryManager.findItem(clientRef, Items.FIRE_CHARGE);
                if (f == -1) { purgePipelineRegistry(); return; }
                activeTargetSlot = f;
                pressHotbarSlotDown(clientRef, activeTargetSlot);
                actionTickCounter = 1;
                break;
            case FLINT_DEPLOY:
                releaseHotbarSlot(clientRef, activeTargetSlot);
                BlockPos firePos = vectorReferenceFace == Direction.UP ? vectorReferencePos.relative(clientRef.player.getDirection().getOpposite()) : vectorReferencePos;
                RotationManager.smoothTo(clientRef, Vec3.atCenterOf(firePos), 0.99F);
                InteractionManager.simulateClickUse(clientRef);
                actionTickCounter = 2;
                break;
            case XBOW_SELECT:
                activeTargetSlot = InventoryManager.findChargedCrossbow(clientRef);
                if (activeTargetSlot == -1) { purgePipelineRegistry(); return; }
                pressHotbarSlotDown(clientRef, activeTargetSlot);
                actionTickCounter = 1;
                break;
            case XBOW_DEPLOY:
                releaseHotbarSlot(clientRef, activeTargetSlot);
                BlockPos shootPos = vectorReferenceFace == Direction.UP ? vectorReferencePos : vectorReferencePos.relative(vectorReferenceFace);
                RotationManager.smoothTo(clientRef, Vec3.atCenterOf(shootPos).add(0.0D, 0.2D, 0.0D), 0.99F);
                InteractionManager.simulateClickUse(clientRef);
                actionTickCounter = 2;
                break;
            case CLEANUP:
                currentPhase = PipelinePhase.DONE;
                globalCooldownTicks = 15;
                purgePipelineRegistry();
                break;
            case DONE:
                break;
        }
    }

    private static void advancePipelinePhase(Minecraft clientRef) {
        switch (currentPhase) {
            case RAIL_SELECT: currentPhase = PipelinePhase.RAIL_DEPLOY; break;
            case RAIL_DEPLOY: currentPhase = PipelinePhase.CART_SELECT; break;
            case CART_SELECT: currentPhase = PipelinePhase.CART_DEPLOY; break;
            case CART_DEPLOY: currentPhase = PipelinePhase.FLINT_SELECT; break;
            case FLINT_SELECT: currentPhase = PipelinePhase.FLINT_DEPLOY; break;
            case FLINT_DEPLOY: currentPhase = PipelinePhase.XBOW_SELECT; break;
            case XBOW_SELECT: currentPhase = PipelinePhase.XBOW_DEPLOY; break;
            case XBOW_DEPLOY: currentPhase = PipelinePhase.CLEANUP; break;
            default: purgePipelineRegistry(); break;
        }
    }

    private static void pressHotbarSlotDown(Minecraft clientRef, int slot) {
        if (clientRef.player == null || clientRef.options == null) return;
        if (slot >= 0 && slot < 9) {
            clientRef.player.getInventory().setSelectedSlot(slot);
            if (clientRef.options.keyHotbarSlots[slot] != null) {
                clientRef.options.keyHotbarSlots[slot].setDown(true);
            }
        }
    }

    private static void releaseHotbarSlot(Minecraft clientRef, int slot) {
        if (clientRef.options == null) return;
        if (slot >= 0 && slot < 9 && clientRef.options.keyHotbarSlots[slot] != null) {
            clientRef.options.keyHotbarSlots[slot].setDown(false);
        }
    }

    private static int locateRailSlot(Minecraft clientRef) {
        if (clientRef.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            Item itemNode = clientRef.player.getInventory().getItem(i).getItem();
            if (validateRegistryItem(itemNode)) return i;
        }
        return -1;
    }

    public static void purgePipelineRegistry() {
        currentPhase = PipelinePhase.VOID;
        vectorReferencePos = null;
        vectorReferenceFace = Direction.UP;
        vectorHitRegistry = null;
        actionTickCounter = 0;
        activeTargetSlot = 0;
        safetyWatchdog.disarm();
    }
}
