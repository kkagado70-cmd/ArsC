package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.Random;

public class XbowCart {
    public enum Stage { IDLE, PLACE_RAIL, PLACE_CART, LIGHT_FIRE, AIM, DISCHARGE, RESTORE }
    public static boolean enabled = false;
    private static boolean triggered = false;
    private static Stage stage = Stage.IDLE;
    private static int tickTimer = 0;
    private static BlockHitResult targetBlockHit = null;
    private static float targetYaw = 0.0f, targetPitch = 0.0f;
    private static int originalSlot = -1;
    private static final Random RANDOM = new Random();
    private static float originalYaw = 0.0f, originalPitch = 0.0f;

    public static void onTick(Minecraft client) {
        if (!enabled || client.player == null || client.level == null || client.gameMode == null) {
            if (stage != Stage.IDLE) reset(client, true);
            return;
        }

        if (stage == Stage.IDLE) {
            ItemStack mainHand = client.player.getMainHandItem();
            boolean holdingRail = mainHand.is(Items.RAIL) || mainHand.is(Items.POWERED_RAIL) ||
                    mainHand.is(Items.DETECTOR_RAIL) || mainHand.is(Items.ACTIVATOR_RAIL);
            if (holdingRail && client.hitResult instanceof BlockHitResult hit &&
                    hit.getType() == HitResult.Type.BLOCK && hit.getDirection() == Direction.UP) {
                if (client.player.getEyePosition().distanceTo(hit.getLocation()) <= 5.0) {
                    triggered = true;
                }
            }
            if (triggered) {
                triggered = false;
                if (client.hitResult instanceof BlockHitResult hit) {
                    initiateCombo(client, hit);
                }
            }
            return;
        }

        if (tickTimer > 0) { tickTimer--; return; }
        processStateTransition(client);
    }

    private static boolean isRail(ItemStack s) {
        return s.is(Items.RAIL) || s.is(Items.POWERED_RAIL) ||
                s.is(Items.DETECTOR_RAIL) || s.is(Items.ACTIVATOR_RAIL);
    }

    private static int findItemSlot(Minecraft client, net.minecraft.world.item.Item item) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(item)) return i;
        }
        return -1;
    }

    private static int findChargedCrossbow(Minecraft client) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(Items.CROSSBOW) && CrossbowItem.isCharged(stack)) return i;
        }
        return -1;
    }

    private static int findCrossbow(Minecraft client) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(Items.CROSSBOW)) return i;
        }
        return -1;
    }

    private static int findRailSlot(Minecraft client) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!stack.isEmpty() && isRail(stack)) return i;
        }
        return -1;
    }

    private static void initiateCombo(Minecraft client, BlockHitResult hit) {
        int railSlot = findRailSlot(client);
        int cartSlot = findItemSlot(client, Items.TNT_MINECART);
        int flintSlot = findItemSlot(client, Items.FLINT_AND_STEEL);
        int xbowSlot = findChargedCrossbow(client);
        if (railSlot == -1 || cartSlot == -1 || flintSlot == -1) return;
        if (xbowSlot == -1) {
            int crossbowSlot = findCrossbow(client);
            if (crossbowSlot != -1) {
                client.player.getInventory().setSelectedSlot(crossbowSlot);
                client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
                return;
            }
            return;
        }
        originalSlot = client.player.getInventory().getSelectedSlot();
        originalYaw = client.player.getYRot();
        originalPitch = client.player.getXRot();
        targetBlockHit = hit;
        stage = Stage.PLACE_RAIL;
        tickTimer = 1 + RANDOM.nextInt(2);
    }

    private static void processStateTransition(Minecraft client) {
        if (targetBlockHit == null) { reset(client, true); return; }

        BlockPos groundPos = targetBlockHit.getBlockPos();
        Direction clickedFace = targetBlockHit.getDirection();
        BlockPos railPos = groundPos.relative(clickedFace);
        BlockPos firePos = railPos.above();

        BlockHitResult railHit = new BlockHitResult(
                new Vec3(railPos.getX() + 0.5, railPos.getY() + 0.5, railPos.getZ() + 0.5),
                Direction.UP, railPos, false
        );
        BlockHitResult cartHit = new BlockHitResult(
                new Vec3(railPos.getX() + 0.5, railPos.getY() + 0.05, railPos.getZ() + 0.5),
                Direction.UP, railPos, false
        );
        BlockHitResult fireHit = new BlockHitResult(
                new Vec3(firePos.getX() + 0.5, firePos.getY() + 0.5, firePos.getZ() + 0.5),
                Direction.UP, firePos, false
        );

        int delay = 1 + RANDOM.nextInt(2);

        switch (stage) {
            case PLACE_RAIL -> {
                int rail = findRailSlot(client);
                if (rail != -1) {
                    client.player.getInventory().setSelectedSlot(rail);
                    client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, railHit);
                    client.player.swing(InteractionHand.MAIN_HAND);
                }
                stage = Stage.PLACE_CART;
                tickTimer = delay + RANDOM.nextInt(2);
            }
            case PLACE_CART -> {
                int cart = findItemSlot(client, Items.TNT_MINECART);
                if (cart != -1) {
                    client.player.getInventory().setSelectedSlot(cart);
                    client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, cartHit);
                    client.player.swing(InteractionHand.MAIN_HAND);
                }
                stage = Stage.LIGHT_FIRE;
                tickTimer = delay + RANDOM.nextInt(2);
            }
            case LIGHT_FIRE -> {
                int flint = findItemSlot(client, Items.FLINT_AND_STEEL);
                if (flint != -1) {
                    client.player.getInventory().setSelectedSlot(flint);
                    client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, fireHit);
                    client.player.swing(InteractionHand.MAIN_HAND);
                }
                computeAim(client, railPos);
                stage = Stage.AIM;
                tickTimer = 1;
            }
            case AIM -> {
                int xbow = findChargedCrossbow(client);
                if (xbow != -1) {
                    client.player.getInventory().setSelectedSlot(xbow);
                    applySmoothAim(client, targetYaw, targetPitch);
                    stage = Stage.DISCHARGE;
                    tickTimer = 1;
                } else {
                    int crossbowSlot = findCrossbow(client);
                    if (crossbowSlot != -1) {
                        client.player.getInventory().setSelectedSlot(crossbowSlot);
                        client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
                        reset(client, true);
                    } else reset(client, true);
                }
            }
            case DISCHARGE -> {
                client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
                client.player.swing(InteractionHand.MAIN_HAND);
                stage = Stage.RESTORE;
                tickTimer = 1 + RANDOM.nextInt(2);
            }
            case RESTORE -> reset(client, true);
            default -> reset(client, false);
        }
    }

    private static void computeAim(Minecraft client, BlockPos railPos) {
        Vec3 eyePos = client.player.getEyePosition();
        Vec3 target = new Vec3(railPos.getX() + 0.5, railPos.getY() + 0.22, railPos.getZ() + 0.5);
        double dx = target.x - eyePos.x;
        double dy = target.y - eyePos.y;
        double dz = target.z - eyePos.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        targetYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        targetPitch = (float) -Math.toDegrees(Math.atan2(dy, dist));
        targetYaw += (RANDOM.nextFloat() - 0.5f) * 0.5f;
        targetPitch += (RANDOM.nextFloat() - 0.5f) * 0.3f;
    }

    private static void applySmoothAim(Minecraft client, float yaw, float pitch) {
        float curYaw = client.player.getYRot();
        float curPitch = client.player.getXRot();
        float maxStep = 45.0f + RANDOM.nextFloat() * 15.0f;
        float steppedYaw = curYaw + Math.max(-maxStep, Math.min(maxStep, wrapAngle(yaw - curYaw)));
        float steppedPitch = curPitch + Math.max(-maxStep * 0.7f, Math.min(maxStep * 0.7f, pitch - curPitch));
        client.player.setYRot(steppedYaw);
        client.player.setXRot(Math.max(-90.0f, Math.min(90.0f, steppedPitch)));
        client.player.yRotO = steppedYaw;
        client.player.xRotO = steppedPitch;
        client.player.yHeadRot = steppedYaw;
        client.player.yHeadRotO = steppedYaw;
    }

    private static float wrapAngle(float angle) {
        float wrapped = angle % 360.0f;
        if (wrapped >= 180.0f) wrapped -= 360.0f;
        if (wrapped < -180.0f) wrapped += 360.0f;
        return wrapped;
    }

    private static void reset(Minecraft client, boolean restore) {
        if (restore && originalSlot != -1 && client.player != null) {
            client.player.getInventory().setSelectedSlot(originalSlot);
            client.player.setYRot(originalYaw);
            client.player.setXRot(originalPitch);
            client.player.yRotO = originalYaw;
            client.player.xRotO = originalPitch;
            client.player.yHeadRot = originalYaw;
            client.player.yHeadRotO = originalYaw;
        }
        stage = Stage.IDLE;
        targetBlockHit = null;
        tickTimer = 0;
        originalSlot = -1;
        triggered = false;
    }
                }
