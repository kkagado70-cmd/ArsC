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
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public class XbowCart extends ClientBase.Module {
    public static final String FILE_NAME = "XbowCart.java";
    public static boolean enabled = true;

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
        vectorReferencePos = null;
        vectorReferenceFace = Direction.UP;
        vectorHitRegistry = null;
        resolvedRailPos = null;
        resolvedCartPos = null;
        resolvedFirePos = null;
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

    private static boolean isRailBlock(BlockState state) {
        return state.is(Blocks.RAIL) || state.is(Blocks.POWERED_RAIL) || state.is(Blocks.DETECTOR_RAIL) || state.is(Blocks.ACTIVATOR_RAIL);
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
                currentPhase = PipelinePhase.RAIL_ACTION;
                break;

            case RAIL_ACTION:
                int r = locateRailSlot(clientRef);
                if (r == -1) { purgePipelineRegistry(); return; }
                Vec3 railTarget = Vec3.atCenterOf(resolvedRailPos);
                RotationManager.smoothTo(clientRef, railTarget, 0.95F);

                InventoryManager.selectSlot(clientRef, r);
                InteractionManager.simulateClickUse(clientRef);
                actionTickCounter = 2;
                break;

            case CART_ACTION:
                int c = InventoryManager.findItem(clientRef, Items.TNT_MINECART);
                if (c == -1) { purgePipelineRegistry(); return; }
                Vec3 cartTarget = Vec3.atCenterOf(resolvedCartPos);
                RotationManager.smoothTo(clientRef, cartTarget, 0.95F);

                InventoryManager.selectSlot(clientRef, c);
                InteractionManager.simulateClickUse(clientRef);
                actionTickCounter = 2;
                break;

            case FLINT_ACTION:
                int f = InventoryManager.findItem(clientRef, Items.FLINT_AND_STEEL);
                if (f == -1) f = InventoryManager.findItem(clientRef, Items.FIRE_CHARGE);
                if (f == -1) { purgePipelineRegistry(); return; }
                Vec3 fireTarget = Vec3.atCenterOf(resolvedFirePos);
                RotationManager.smoothTo(clientRef, fireTarget, 0.95F);

                InventoryManager.selectSlot(clientRef, f);
                InteractionManager.simulateClickUse(clientRef);
                actionTickCounter = 2;
                break;

            case XBOW_ACTION:
                int x = InventoryManager.findChargedCrossbow(clientRef);
                if (x == -1) { purgePipelineRegistry(); return; }
                Vec3 cartCenter = Vec3.atCenterOf(resolvedCartPos);
                Vec3 fireCenter = Vec3.atCenterOf(resolvedFirePos);
                Vec3 trajectoryMidpoint = cartCenter.add(fireCenter).scale(0.5D);
                Vec3 shootTarget = trajectoryMidpoint.add(0.0D, 0.25D, 0.0D);

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
        resolvedRailPos = null;
        resolvedCartPos = null;
        resolvedFirePos = null;
        actionTickCounter = 0;
        safetyWatchdog.disarm();
    }
}