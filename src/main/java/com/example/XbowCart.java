package com.example.modules;

import com.example.ClientBase;
import com.example.ClientBase.Module;
import com.example.ClientBase.InvUtils;
import com.example.ClientBase.RotationUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.Random;

public class XbowCart extends Module {
    private enum CartPhase {
        INACTIVE, 
        RAIL_SELECT, RAIL_DEPLOY,
        FIRE_SELECT, FIRE_DEPLOY,
        CART_SELECT, CART_DEPLOY,
        CROSSBOW_SELECT, CROSSBOW_FIRE
    }

    private CartPhase phase = CartPhase.INACTIVE;
    private int delayTicks = 0;
    private int globalCooldownTicks = 0;
    private int originalSlot = -1;
    private int mouseButtonReleaseTracker = 0;
    private BlockPos basePos = null;

    public XbowCart() {
        super("XbowCart");
        this.enabled = true; // Stays enabled per core instructions
    }

    public static boolean isAnyRail(Item item) {
        return item == Items.RAIL || 
               item == Items.POWERED_RAIL || 
               item == Items.DETECTOR_RAIL || 
               item == Items.ACTIVATOR_RAIL;
    }

    @Override
    public void tick(Minecraft client) {
        if (client.player == null || client.level == null) return;

        // Handle ClickSim mouse use key release safety
        if (mouseButtonReleaseTracker > 0) {
            mouseButtonReleaseTracker--;
            if (mouseButtonReleaseTracker == 0 && client.options != null) {
                client.options.keyUse.setDown(false);
            }
        }

        if (globalCooldownTicks > 0) {
            globalCooldownTicks--;
            return;
        }

        if (delayTicks > 0) {
            delayTicks--;
            return;
        }

        // Abort and restore if activation conditions are broken mid-sequence
        if (phase != CartPhase.INACTIVE && !isActivationValid(client)) {
            InvUtils.restore(client, originalSlot);
            resetSequence();
            return;
        }

        switch (phase) {
            case INACTIVE:
                if (!isActivationValid(client)) return;
                originalSlot = InvUtils.getSelected(client);
                if (client.hitResult instanceof BlockHitResult hit) {
                    basePos = hit.getBlockPos();
                } else {
                    basePos = client.player.blockPosition().below();
                }
                phase = CartPhase.RAIL_SELECT;
                break;

            case RAIL_SELECT:
                if (InvUtils.selectRail(client)) {
                    delayTicks = 2; // Slot switch sync delay
                    phase = CartPhase.RAIL_DEPLOY;
                } else {
                    resetSequence();
                }
                break;

            case RAIL_DEPLOY:
                if (basePos != null) {
                    RotationUtils.aimBlock(client, basePos);
                    mouseButtonReleaseTracker = 2;
                    client.options.keyUse.setDown(true);
                }
                delayTicks = 2 + new Random().nextInt(2); // 2-3 ticks randomized delay
                phase = CartPhase.FIRE_SELECT;
                break;

            case FIRE_SELECT:
                if (InvUtils.selectItem(client, Items.FLINT_AND_STEEL) || InvUtils.selectItem(client, Items.FIRE_CHARGE)) {
                    delayTicks = 2;
                    phase = CartPhase.FIRE_DEPLOY;
                } else {
                    resetSequence();
                }
                break;

            case FIRE_DEPLOY:
                if (basePos != null) {
                    BlockPos firePos = basePos.above(); // 1 block above rail
                    RotationUtils.aimBlock(client, firePos);
                    mouseButtonReleaseTracker = 2;
                    client.options.keyUse.setDown(true);
                }
                delayTicks = 2 + new Random().nextInt(2);
                phase = CartPhase.CART_SELECT;
                break;

            case CART_SELECT:
                if (InvUtils.selectItem(client, Items.TNT_MINECART)) {
                    delayTicks = 2;
                    phase = CartPhase.CART_DEPLOY;
                } else {
                    resetSequence();
                }
                break;

            case CART_DEPLOY:
                if (basePos != null) {
                    BlockPos cartPos = basePos.above(2); // 2 blocks above rail (on top of fire)
                    RotationUtils.aimBlock(client, cartPos);
                    mouseButtonReleaseTracker = 2;
                    client.options.keyUse.setDown(true);
                }
                delayTicks = 2 + new Random().nextInt(2);
                phase = CartPhase.CROSSBOW_SELECT;
                break;

            case CROSSBOW_SELECT:
                if (client.player.getAttackStrengthScale(0.0F) < 0.9F) {
                    delayTicks = 1;
                    return;
                }
                if (InvUtils.selectCrossbow(client)) {
                    delayTicks = 2;
                    phase = CartPhase.CROSSBOW_FIRE;
                } else {
                    resetSequence();
                }
                break;

            case CROSSBOW_FIRE:
                mouseButtonReleaseTracker = 2;
                client.options.keyUse.setDown(true);
                InvUtils.restore(client, originalSlot);
                globalCooldownTicks = 8;
                resetSequence();
                break;

            default:
                resetSequence();
                break;
        }
    }

    private boolean isActivationValid(Minecraft client) {
        if (client.player == null || client.hitResult == null) return false;
        boolean lookingDown = client.hitResult instanceof BlockHitResult blockHit && blockHit.getDirection() == Direction.UP;
        boolean holdingRail = InvUtils.isHoldingRail(client);
        return lookingDown && holdingRail;
    }

    private void resetSequence() {
        phase = CartPhase.INACTIVE;
        delayTicks = 0;
        originalSlot = -1;
        basePos = null;
    }
}
