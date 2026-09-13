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

import java.util.Random;

public class XbowCart extends ClientBase.Module {
    public static final String FILE_NAME = "XbowCart.java";
    public static boolean enabled = true;
    private static final Random internalRandom = new Random();

    private enum PipelinePhase {
        INACTIVE,
        RAIL_SLOT,
        RAIL_USE,
        CART_SLOT,
        CART_USE,
        FLINT_SLOT,
        FLINT_USE,
        XBOW_SLOT,
        XBOW_USE,
        CLEANUP
    }

    private static PipelinePhase currentPhase = PipelinePhase.INACTIVE;
    private static int tickIntervalCounter = 0;
    private static int globalCooldownTicks = 0;
    private static BlockPos targetBasePos = null;
    private static Direction targetFace = Direction.UP;
    private static int originalSelectedSlot = 0;
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
        if (!enabled) resetPipeline();
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

    private static BlockPos resolvePlacementPos(BlockPos basePos, Direction faceDir) {
        if (faceDir == Direction.UP) {
            return basePos;
        }
        return basePos.above();
    }

    public static void onTick(Minecraft clientRef) {
        if (!enabled || clientRef.player == null || clientRef.level == null) return;

        if (globalCooldownTicks > 0) {
            globalCooldownTicks--;
            return;
        }

        if (safetyWatchdog.isTimedOut()) {
            resetPipeline();
            return;
        }

        if (currentPhase != PipelinePhase.INACTIVE && !clientRef.player.isAlive()) {
            resetPipeline();
            return;
        }

        if (tickIntervalCounter > 0) {
            tickIntervalCounter--;
            return;
        }

        executePipelineCycle(clientRef);
    }

    private static void executePipelineCycle(Minecraft clientRef) {
        switch (currentPhase) {
            case INACTIVE:
                checkForActivation(clientRef);
                break;
            case RAIL_SLOT:
                int railSlot = locateRailSlot(clientRef);
                if (railSlot == -1) { resetPipeline(); return; }
                selectSlotDirect(clientRef, railSlot);
                currentPhase = PipelinePhase.RAIL_USE;
                tickIntervalCounter = 1;
                break;
            case RAIL_USE:
                BlockPos railTarget = resolvePlacementPos(targetBasePos, targetFace);
                aimAndForceHit(clientRef, Vec3.atCenterOf(railTarget), targetFace, railTarget);
                clientRef.options.keyUse.setDown(true);
                currentPhase = PipelinePhase.CART_SLOT;
                tickIntervalCounter = 1;
                break;
            case CART_SLOT:
                clientRef.options.keyUse.setDown(false);
                int cartSlot = locateItemInInventory(clientRef, Items.TNT_MINECART);
                if (cartSlot == -1) { resetPipeline(); return; }
                selectSlotDirect(clientRef, cartSlot);
                currentPhase = PipelinePhase.CART_USE;
                tickIntervalCounter = 1;
                break;
            case CART_USE:
                BlockPos cartTarget = resolvePlacementPos(targetBasePos, targetFace);
                aimAndForceHit(clientRef, Vec3.atCenterOf(cartTarget), Direction.UP, cartTarget);
                clientRef.options.keyUse.setDown(true);
                currentPhase = PipelinePhase.FLINT_SLOT;
                tickIntervalCounter = 1;
                break;
            case FLINT_SLOT:
                clientRef.options.keyUse.setDown(false);
                int flintSlot = locateItemInInventory(clientRef, Items.FLINT_AND_STEEL);
                if (flintSlot == -1) flintSlot = locateItemInInventory(clientRef, Items.FIRE_CHARGE);
                if (flintSlot == -1) { resetPipeline(); return; }
                selectSlotDirect(clientRef, flintSlot);
                currentPhase = PipelinePhase.FLINT_USE;
                tickIntervalCounter = 1;
                break;
            case FLINT_USE:
                BlockPos fireTarget = resolvePlacementPos(targetBasePos, targetFace);
                aimAndForceHit(clientRef, Vec3.atCenterOf(fireTarget), targetFace, fireTarget);
                clientRef.options.keyUse.setDown(true);
                currentPhase = PipelinePhase.XBOW_SLOT;
                tickIntervalCounter = 1;
                break;
            case XBOW_SLOT:
                clientRef.options.keyUse.setDown(false);
                int xbowSlot = locateCrossbowSlot(clientRef);
                if (xbowSlot == -1) { resetPipeline(); return; }
                selectSlotDirect(clientRef, xbowSlot);
                currentPhase = PipelinePhase.XBOW_USE;
                tickIntervalCounter = 1;
                break;
            case XBOW_USE:
                BlockPos shootTarget = resolvePlacementPos(targetBasePos, targetFace);
                Vec3 shootVec = Vec3.atCenterOf(shootTarget).add(0.0D, 0.25D, 0.0D);
                aimAndForceHit(clientRef, shootVec, Direction.UP, shootTarget);
                clientRef.options.keyUse.setDown(true);
                currentPhase = PipelinePhase.CLEANUP;
                tickIntervalCounter = 2;
                break;
            case CLEANUP:
                clientRef.options.keyUse.setDown(false);
                globalCooldownTicks = 10;
                resetPipeline();
                break;
            default:
                resetPipeline();
                break;
        }
    }

    private static void checkForActivation(Minecraft clientRef) {
        ItemStack mainHandItem = clientRef.player.getMainHandItem();
        if (!validateRegistryItem(mainHandItem.getItem())) return;

        HitResult rawHit = clientRef.hitResult;
        if (rawHit == null || rawHit.getType() != HitResult.Type.BLOCK) {
            rawHit = clientRef.player.pick(4.5D, 1.0F, false);
            if (rawHit == null || rawHit.getType() != HitResult.Type.BLOCK) return;
        }

        if (rawHit instanceof BlockHitResult blockHit) {
            double distSqr = clientRef.player.getEyePosition().distanceToSqr(blockHit.getLocation());
            if (distSqr > 20.25D) return;
            if (!clientRef.level.getBlockState(blockHit.getBlockPos()).isSolid()) return;

            int railSlot = locateRailSlot(clientRef);
            int cartSlot = locateItemInInventory(clientRef, Items.TNT_MINECART);
            int flintSlot = locateItemInInventory(clientRef, Items.FLINT_AND_STEEL);
            if (flintSlot == -1) flintSlot = locateItemInInventory(clientRef, Items.FIRE_CHARGE);
            int xbowSlot = locateCrossbowSlot(clientRef);

            if (railSlot == -1 || cartSlot == -1 || flintSlot == -1 || xbowSlot == -1) return;

            originalSelectedSlot = clientRef.player.getInventory().getSelectedSlot();
            targetBasePos = blockHit.getBlockPos();
            targetFace = blockHit.getDirection();

            safetyWatchdog.arm();
            currentPhase = PipelinePhase.RAIL_SLOT;
            tickIntervalCounter = 0;
        }
    }

    private static void selectSlotDirect(Minecraft clientRef, int slot) {
        if (clientRef.player == null || slot < 0 || slot > 8) return;
        clientRef.player.getInventory().setSelectedSlot(slot);
        if (clientRef.options.keyHotbarSlots[slot] != null) {
            clientRef.options.keyHotbarSlots[slot].setDown(true);
            clientRef.options.keyHotbarSlots[slot].setDown(false);
        }
    }

    private static void aimAndForceHit(Minecraft clientRef, Vec3 targetVec, Direction face, BlockPos blockPos) {
        if (clientRef.player == null || targetVec == null || blockPos == null) return;
        clientRef.hitResult = new BlockHitResult(targetVec, face, blockPos, false);

        double deltaX = targetVec.x - clientRef.player.getX();
        double deltaY = targetVec.y - clientRef.player.getEyeY();
        double deltaZ = targetVec.z - clientRef.player.getZ();
        double hDist = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);

        float targetYaw = (float) (Math.atan2(deltaZ, deltaX) * (180.0 / Math.PI)) - 90.0F;
        float targetPitch = (float) (-(Math.atan2(deltaY, hDist) * (180.0 / Math.PI)));
        targetPitch = Mth.clamp(targetPitch, -89.0F, 89.0F);

        clientRef.player.setYRot(targetYaw);
        clientRef.player.setXRot(targetPitch);

        if (clientRef.options != null) {
            double sensitivity = clientRef.options.sensitivity().get() * 0.6D + 0.2D;
            double gcd = sensitivity * sensitivity * sensitivity * 8.0D;
            if (gcd > 0.0D) {
                double deltaYawAngle = (targetYaw - clientRef.player.getYRot());
                double deltaPitchAngle = (targetPitch - clientRef.player.getXRot());
                clientRef.player.turn(deltaYawAngle / (gcd * 0.15D), deltaPitchAngle / (gcd * 0.15D));
            }
        }
    }

    private static int locateRailSlot(Minecraft clientRef) {
        if (clientRef.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (validateRegistryItem(clientRef.player.getInventory().getItem(i).getItem())) return i;
        }
        return -1;
    }

    private static int locateItemInInventory(Minecraft clientRef, Item targetItem) {
        if (clientRef.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = clientRef.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == targetItem) return i;
        }
        return -1;
    }

    private static int locateCrossbowSlot(Minecraft clientRef) {
        if (clientRef.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = clientRef.player.getInventory().getItem(i);
            if (stack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(stack)) return i;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = clientRef.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == Items.CROSSBOW) return i;
        }
        return -1;
    }

    public static void resetPipeline() {
        currentPhase = PipelinePhase.INACTIVE;
        tickIntervalCounter = 0;
        targetBasePos = null;
        targetFace = Direction.UP;
        if (Minecraft.getInstance() != null && Minecraft.getInstance().options != null) {
            Minecraft.getInstance().options.keyUse.setDown(false);
        }
        safetyWatchdog.disarm();
    }
}
