package com.example.mixin;

import com.example.XbowCart;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.Random;

@Mixin(Minecraft.class)
public class MixinXbowCart {
    private static final Random R = new Random();
    private static int stage = 0;
    private static BlockPos targetPos = null;
    private static Direction playerDir = null;
    private static int origSlot = -1;
    private static int delayTicks = 0;

    @Inject(method = "tick", at = @At("HEAD"))
    private void onTick(CallbackInfo ci) {
        Minecraft c = Minecraft.getInstance();
        if (c.player == null || c.level == null) return;
        if (!XbowCart.enabled) return;

        if (stage != 0) {
            if (delayTicks > 0) { delayTicks--; return; }
            executeStage(c);
            return;
        }

        ItemStack hand = c.player.getMainHandItem();
        boolean holdingRail = hand.is(Items.RAIL) || hand.is(Items.POWERED_RAIL)
                || hand.is(Items.DETECTOR_RAIL) || hand.is(Items.ACTIVATOR_RAIL);
        if (!holdingRail) return;
        if (!(c.hitResult instanceof BlockHitResult hit)) return;
        if (hit.getDirection() != Direction.UP) return;
        if (c.player.distanceToSqr(hit.getBlockPos().getCenter()) > 36.0) return;

        int rail = findSlot(c, Items.RAIL, Items.POWERED_RAIL, Items.DETECTOR_RAIL, Items.ACTIVATOR_RAIL);
        int cart = findSlot(c, Items.TNT_MINECART);
        int flint = findSlot(c, Items.FLINT_AND_STEEL);
        int xbow = findChargedXbow(c);
        if (rail == -1 || cart == -1 || flint == -1 || xbow == -1) return;

        origSlot = c.player.getInventory().getSelectedSlot();
        targetPos = hit.getBlockPos().relative(hit.getDirection());
        playerDir = c.player.getDirection();
        stage = 1;
        delayTicks = 0;
        executeStage(c);
    }

    private void executeStage(Minecraft c) {
        int delay = 2 + R.nextInt(2);
        switch (stage) {
            case 1 -> {
                int slot = findSlot(c, Items.RAIL, Items.POWERED_RAIL, Items.DETECTOR_RAIL, Items.ACTIVATOR_RAIL);
                if (slot == -1) { reset(); return; }
                BlockPos pos = targetPos.above();
                BlockHitResult hit = new BlockHitResult(
                        new Vec3(pos.getX()+0.5, pos.getY()+0.5, pos.getZ()+0.5),
                        Direction.UP, pos, false);
                c.player.getInventory().setSelectedSlot(slot);
                c.gameMode.useItemOn(c.player, InteractionHand.MAIN_HAND, hit);
                c.player.swing(InteractionHand.MAIN_HAND);
                stage = 2;
                delayTicks = delay;
            }
            case 2 -> {
                int slot = findSlot(c, Items.TNT_MINECART);
                if (slot == -1) { reset(); return; }
                BlockPos pos = targetPos.above();
                BlockHitResult hit = new BlockHitResult(
                        new Vec3(pos.getX()+0.5, pos.getY()+0.05, pos.getZ()+0.5),
                        Direction.UP, pos, false);
                c.player.getInventory().setSelectedSlot(slot);
                c.gameMode.useItemOn(c.player, InteractionHand.MAIN_HAND, hit);
                c.player.swing(InteractionHand.MAIN_HAND);
                stage = 3;
                delayTicks = delay;
            }
            case 3 -> {
                int flint = findSlot(c, Items.FLINT_AND_STEEL);
                if (flint == -1) { reset(); return; }
                int slab = findSlab(c);
                if (slab != -1) {
                    BlockPos slabPos = targetPos.relative(playerDir.getOpposite());
                    BlockHitResult slabHit = new BlockHitResult(
                            new Vec3(slabPos.getX()+0.5, slabPos.getY()+0.5, slabPos.getZ()+0.5),
                            Direction.UP, slabPos, false);
                    c.player.getInventory().setSelectedSlot(slab);
                    c.gameMode.useItemOn(c.player, InteractionHand.MAIN_HAND, slabHit);
                    c.player.swing(InteractionHand.MAIN_HAND);
                    BlockPos firePos = slabPos.relative(playerDir);
                    BlockHitResult fireHit = new BlockHitResult(
                            new Vec3(firePos.getX()+0.5, firePos.getY()+0.5, firePos.getZ()+0.5),
                            Direction.UP, firePos, false);
                    c.player.getInventory().setSelectedSlot(flint);
                    c.gameMode.useItemOn(c.player, InteractionHand.MAIN_HAND, fireHit);
                    c.player.swing(InteractionHand.MAIN_HAND);
                } else {
                    BlockPos firePos = targetPos.relative(playerDir.getOpposite());
                    BlockHitResult fireHit = new BlockHitResult(
                            new Vec3(firePos.getX()+0.5, firePos.getY()+0.5, firePos.getZ()+0.5),
                            Direction.UP, firePos, false);
                    c.player.getInventory().setSelectedSlot(flint);
                    c.gameMode.useItemOn(c.player, InteractionHand.MAIN_HAND, fireHit);
                    c.player.swing(InteractionHand.MAIN_HAND);
                }
                stage = 4;
                delayTicks = delay;
            }
            case 4 -> {
                int xbow = findChargedXbow(c);
                if (xbow == -1) { reset(); return; }
                c.player.getInventory().setSelectedSlot(xbow);
                BlockPos cartPos = targetPos.above();
                Vec3 eye = c.player.getEyePosition();
                Vec3 target = new Vec3(cartPos.getX()+0.5, cartPos.getY()+0.22, cartPos.getZ()+0.5);
                double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
                float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx*dx+dz*dz)));
                applyAim(c, yaw, pitch, 40f);
                stage = 5;
                delayTicks = 1;
            }
            case 5 -> {
                int xbow = findChargedXbow(c);
                if (xbow != -1) {
                    c.player.getInventory().setSelectedSlot(xbow);
                    c.gameMode.useItem(c.player, InteractionHand.MAIN_HAND);
                    c.player.swing(InteractionHand.MAIN_HAND);
                }
                if (origSlot != -1) c.player.getInventory().setSelectedSlot(origSlot);
                reset();
            }
            default -> reset();
        }
    }

    private void applyAim(Minecraft c, float yaw, float pitch, float maxStep) {
        float curY = c.player.getYRot(), curP = c.player.getXRot();
        float dY = yaw - curY; while(dY>180)dY-=360; while(dY<-180)dY+=360;
        float dP = pitch - curP;
        dY = Math.max(-maxStep, Math.min(maxStep, dY));
        dP = Math.max(-maxStep*0.6f, Math.min(maxStep*0.6f, dP));
        c.player.setYRot(curY + dY);
        c.player.setXRot(Math.max(-90, Math.min(90, curP + dP)));
    }

    private int findSlot(Minecraft c, net.minecraft.world.item.Item... items) {
        for(int i=0;i<9;i++){ ItemStack s=c.player.getInventory().getItem(i); if(s.isEmpty()) continue; for(net.minecraft.world.item.Item it:items){ if(s.is(it)) return i; } } return -1;
    }

    private int findSlab(Minecraft c) {
        for(int i=0;i<9;i++){ ItemStack s=c.player.getInventory().getItem(i); if(!s.isEmpty() && s.getItem() instanceof BlockItem){ BlockItem bi=(BlockItem)s.getItem(); if(bi.getBlock() instanceof SlabBlock) return i; } } return -1;
    }

    private int findChargedXbow(Minecraft c) {
        for(int i=0;i<9;i++){ ItemStack s=c.player.getInventory().getItem(i); if(!s.isEmpty() && s.is(Items.CROSSBOW) && CrossbowItem.isCharged(s)) return i; } return -1;
    }

    private void reset() {
        stage = 0; targetPos = null; playerDir = null; origSlot = -1; delayTicks = 0;
    }
        }
