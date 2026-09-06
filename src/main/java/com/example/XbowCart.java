package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

public class XbowCart {
    public static final String FILE_NAME = "XbowCart.java";
    public static boolean enabled = false;

    private enum PipelinePhase { VOID, RAIL_ACTION, CART_ACTION, FLINT_ACTION, XBOW_ACTION, CLEANUP }

    private static PipelinePhase currentPhase = PipelinePhase.VOID;
    private static int actionTickCounter = 0;
    private static BlockPos vectorReferencePos = null;
    private static Direction vectorReferenceFace = Direction.UP;
    private static Vec3 vectorHitRegistry = null;
    private static final SafetyWatchdog safetyWatchdog = new SafetyWatchdog();

    public static void toggle() {
        enabled = !enabled;
        if (!enabled) purgePipelineRegistry();
    }

    public static void onTick(Minecraft clientRef) {
        if (!enabled || clientRef.player == null || clientRef.level == null) return;

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
                if (hit == null || !ClientBase.XbowCartModule.isRail(clientRef.player.getMainHandItem().getItem()) || InventoryManager.findChargedCrossbow(clientRef) == -1) return;
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
    }

    private static int locateRailSlot(Minecraft clientRef) {
        if (clientRef.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            Item itemNode = clientRef.player.getInventory().getItem(i).getItem();
            if (ClientBase.XbowCartModule.isRail(itemNode)) return i;
        }
        return -1;
    }

    public static void purgePipelineRegistry() {
        currentPhase = PipelinePhase.VOID;
        vectorReferencePos = null;
        vectorReferenceFace = Direction.UP;
        vectorHitRegistry = null;
        actionTickCounter = 0;
        safetyWatchdog.disarm();
    }
}