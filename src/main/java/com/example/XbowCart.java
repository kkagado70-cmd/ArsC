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
import net.minecraft.util.Mth;

import java.util.Random;

/**
 * XbowCart - Top-level compatibility bridge and execution engine.
 * Fully satisfies ClickGUI and PreciseGuiScaleClient references while maintaining 
 * strict Mojang Mappings, ClickSim safety, and Anti-Cheat bypass execution.
 */
public class XbowCart {
    public static boolean enabled = false;

    private enum CartPhase {
        INACTIVE, 
        RAIL_SELECT, RAIL_DEPLOY,
        FIRE_SELECT, FIRE_DEPLOY,
        CART_SELECT, CART_DEPLOY,
        CROSSBOW_SELECT, CROSSBOW_FIRE
    }

    private static CartPhase phase = CartPhase.INACTIVE;
    private static int delayTicks = 0;
    private static int globalCooldownTicks = 0;
    private static int originalSlot = -1;
    private static int mouseButtonReleaseTracker = 0;
    private static BlockPos basePos = null;

    public static void toggle() {
        enabled = !enabled;
        if (!enabled) {
            resetSequence();
        }
    }

    public static boolean isAnyRail(Item item) {
        return item == Items.RAIL || 
               item == Items.POWERED_RAIL || 
               item == Items.DETECTOR_RAIL || 
               item == Items.ACTIVATOR_RAIL;
    }

    public static void onTick(Minecraft client) {
        if (!enabled || client.player == null || client.level == null) return;

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
            restoreSlot(client, originalSlot);
            resetSequence();
            return;
        }

        switch (phase) {
            case INACTIVE:
                if (!isActivationValid(client)) return;
                originalSlot = client.player.getInventory().getSelectedSlot();
                if (client.hitResult instanceof BlockHitResult hit) {
                    basePos = hit.getBlockPos();
                } else {
                    basePos = client.player.blockPosition().below();
                }
                phase = CartPhase.RAIL_SELECT;
                break;

            case RAIL_SELECT:
                if (selectRail(client)) {
                    delayTicks = 2; // Slot switch sync delay
                    phase = CartPhase.RAIL_DEPLOY;
                } else {
                    resetSequence();
                }
                break;

            case RAIL_DEPLOY:
                if (basePos != null) {
                    aimBlock(client, basePos);
                    mouseButtonReleaseTracker = 2;
                    client.options.keyUse.setDown(true);
                }
                delayTicks = 2 + new Random().nextInt(2); // 2-3 ticks randomized delay
                phase = CartPhase.FIRE_SELECT;
                break;

            case FIRE_SELECT:
                if (selectItem(client, Items.FLINT_AND_STEEL) || selectItem(client, Items.FIRE_CHARGE)) {
                    delayTicks = 2;
                    phase = CartPhase.FIRE_DEPLOY;
                } else {
                    resetSequence();
                }
                break;

            case FIRE_DEPLOY:
                if (basePos != null) {
                    BlockPos firePos = basePos.above(); // 1 block above rail
                    aimBlock(client, firePos);
                    mouseButtonReleaseTracker = 2;
                    client.options.keyUse.setDown(true);
                }
                delayTicks = 2 + new Random().nextInt(2);
                phase = CartPhase.CART_SELECT;
                break;

            case CART_SELECT:
                if (selectItem(client, Items.TNT_MINECART)) {
                    delayTicks = 2;
                    phase = CartPhase.CART_DEPLOY;
                } else {
                    resetSequence();
                }
                break;

            case CART_DEPLOY:
                if (basePos != null) {
                    BlockPos cartPos = basePos.above(2); // 2 blocks above rail (on top of fire)
                    aimBlock(client, cartPos);
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
                if (selectCrossbow(client)) {
                    delayTicks = 2;
                    phase = CartPhase.CROSSBOW_FIRE;
                } else {
                    resetSequence();
                }
                break;

            case CROSSBOW_FIRE:
                mouseButtonReleaseTracker = 2;
                client.options.keyUse.setDown(true);
                restoreSlot(client, originalSlot);
                globalCooldownTicks = 8;
                resetSequence();
                break;

            default:
                resetSequence();
                break;
        }
    }

    private static boolean isActivationValid(Minecraft client) {
        if (client.player == null || client.hitResult == null) return false;
        boolean lookingDown = client.hitResult instanceof BlockHitResult blockHit && blockHit.getDirection() == Direction.UP;
        boolean holdingRail = isHoldingRail(client);
        return lookingDown && holdingRail;
    }

    private static boolean isHoldingRail(Minecraft client) {
        if (client.player == null) return false;
        return isAnyRail(client.player.getMainHandItem().getItem());
    }

    private static void resetSequence() {
        phase = CartPhase.INACTIVE;
        delayTicks = 0;
        originalSlot = -1;
        basePos = null;
    }

    private static void aimBlock(Minecraft client, BlockPos pos) {
        if (client.player == null) return;
        Vec3 target = Vec3.atCenterOf(pos);
        double dx = target.x - client.player.getX();
        double dy = target.y - client.player.getEyeY();
        double dz = target.z - client.player.getZ();
        double hDist = Math.sqrt(dx * dx + dz * dz);

        float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, hDist)));
        targetPitch = Mth.clamp(targetPitch, -30.0F, 30.0F);

        float currentYaw = client.player.getYRot();
        float currentPitch = client.player.getXRot();

        float smoothedYaw = currentYaw + (targetYaw - currentYaw) * 0.65F + (new Random().nextFloat() - 0.5F) * 0.08F;
        float smoothedPitch = currentPitch + (targetPitch - currentPitch) * 0.65F + (new Random().nextFloat() - 0.5F) * 0.08F;

        client.player.setYRot(smoothedYaw);
        client.player.setXRot(smoothedPitch);
    }

    private static void selectSlot(Minecraft client, int slot) {
        if (client.player == null || slot < 0 || slot > 8) return;
        client.options.keyHotbarSlots[slot].setDown(true);
        client.options.keyHotbarSlots[slot].setDown(false);
    }

    private static void restoreSlot(Minecraft client, int slot) {
        if (slot >= 0 && slot < 9) {
            selectSlot(client, slot);
        }
    }

    private static boolean selectRail(Minecraft client) {
        for (int i = 0; i < 9; i++) {
            Item item = client.player.getInventory().getItem(i).getItem();
            if (isAnyRail(item)) {
                selectSlot(client, i);
                return true;
            }
        }
        return false;
    }

    private static boolean selectItem(Minecraft client, Item targetItem) {
        for (int i = 0; i < 9; i++) {
            if (client.player.getInventory().getItem(i).getItem() == targetItem) {
                selectSlot(client, i);
                return true;
            }
        }
        return false;
    }

    private static boolean selectCrossbow(Minecraft client) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(stack)) {
                selectSlot(client, i);
                return true;
            }
        }
        return selectItem(client, Items.CROSSBOW);
    }
}
