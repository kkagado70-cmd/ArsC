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
    private static BlockHitResult targetBlock = null;
    private static float targetYaw = 0, targetPitch = 0;
    private static int originalSlot = -1;
    private static final Random RANDOM = new Random();
    private static int hesitate = 0, ping = 0;
    private static boolean hesitating = false;

    public static void onTick(Minecraft client) {
        if (!enabled || client.player == null || client.level == null || client.gameMode == null) {
            if (stage != Stage.IDLE) reset(client, true);
            return;
        }
        if (ping > 0) { ping--; return; }
        if (hesitate > 0) { hesitate--; return; }
        if (hesitating && hesitate <= 0) hesitating = false;

        if (stage == Stage.IDLE) {
            ItemStack hand = client.player.getMainHandItem();
            boolean holding = hand.is(Items.RAIL) || hand.is(Items.POWERED_RAIL)
                    || hand.is(Items.DETECTOR_RAIL) || hand.is(Items.ACTIVATOR_RAIL);
            if (holding && client.hitResult instanceof BlockHitResult hit
                    && hit.getType() == HitResult.Type.BLOCK && hit.getDirection() == Direction.UP) {
                if (client.player.getEyePosition().distanceTo(hit.getLocation()) <= 5.0) {
                    triggered = true;
                }
            }
            if (triggered) {
                triggered = false;
                if (client.hitResult instanceof BlockHitResult hit) {
                    int rail = findSlot(client, Items.RAIL, Items.POWERED_RAIL, Items.DETECTOR_RAIL, Items.ACTIVATOR_RAIL);
                    int cart = findSlot(client, Items.TNT_MINECART);
                    int flint = findSlot(client, Items.FLINT_AND_STEEL);
                    int xbow = findChargedCrossbow(client);
                    if (rail == -1 || cart == -1 || flint == -1) return;
                    if (xbow == -1) {
                        int cb = findSlot(client, Items.CROSSBOW);
                        if (cb != -1) {
                            client.player.getInventory().setSelectedSlot(cb);
                            client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
                            return;
                        }
                        return;
                    }
                    originalSlot = client.player.getInventory().getSelectedSlot();
                    targetBlock = hit;
                    stage = Stage.PLACE_RAIL;
                    tickTimer = 1 + RANDOM.nextInt(2);
                    if (RANDOM.nextInt(100) < 15) { ping = 2 + RANDOM.nextInt(4); }
                }
            }
            return;
        }
        if (tickTimer > 0) { tickTimer--; return; }
        processState(client);
    }

    private static boolean isRail(ItemStack s) {
        return s.is(Items.RAIL) || s.is(Items.POWERED_RAIL)
                || s.is(Items.DETECTOR_RAIL) || s.is(Items.ACTIVATOR_RAIL);
    }

    private static int findSlot(Minecraft client, net.minecraft.world.item.Item... items) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = client.player.getInventory().getItem(i);
            if (s.isEmpty()) continue;
            for (net.minecraft.world.item.Item item : items) {
                if (s.is(item)) return i;
            }
        }
        return -1;
    }

    private static int findChargedCrossbow(Minecraft client) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = client.player.getInventory().getItem(i);
            if (!s.isEmpty() && s.is(Items.CROSSBOW) && CrossbowItem.isCharged(s)) return i;
        }
        return -1;
    }

    private static void processState(Minecraft client) {
        if (targetBlock == null) { reset(client, true); return; }
        BlockPos gnd = targetBlock.getBlockPos();
        Direction face = targetBlock.getDirection();
        BlockPos railPos = gnd.relative(face);
        BlockPos cartPos = railPos.above();
        BlockPos firePos = cartPos.above();

        BlockHitResult railHit = new BlockHitResult(
                new Vec3(railPos.getX() + 0.5, railPos.getY() + 0.5, railPos.getZ() + 0.5),
                Direction.UP, railPos, false);
        BlockHitResult cartHit = new BlockHitResult(
                new Vec3(cartPos.getX() + 0.5, cartPos.getY() + 0.5, cartPos.getZ() + 0.5),
                Direction.UP, cartPos, false);
        BlockHitResult fireHit = new BlockHitResult(
                new Vec3(firePos.getX() + 0.5, firePos.getY() + 0.5, firePos.getZ() + 0.5),
                Direction.UP, firePos, false);

        int delay = 1 + RANDOM.nextInt(2);

        switch (stage) {
            case PLACE_RAIL -> {
                int r = findSlot(client, Items.RAIL, Items.POWERED_RAIL, Items.DETECTOR_RAIL, Items.ACTIVATOR_RAIL);
                if (r != -1) {
                    client.player.getInventory().setSelectedSlot(r);
                    client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, railHit);
                    client.player.swing(InteractionHand.MAIN_HAND);
                }
                stage = Stage.PLACE_CART;
                tickTimer = delay + RANDOM.nextInt(2);
                if (RANDOM.nextInt(100) < 15) { hesitate = 2 + RANDOM.nextInt(4); hesitating = true; }
            }
            case PLACE_CART -> {
                int c = findSlot(client, Items.TNT_MINECART);
                if (c != -1) {
                    client.player.getInventory().setSelectedSlot(c);
                    client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, cartHit);
                    client.player.swing(InteractionHand.MAIN_HAND);
                }
                stage = Stage.LIGHT_FIRE;
                tickTimer = delay + RANDOM.nextInt(2);
            }
            case LIGHT_FIRE -> {
                int f = findSlot(client, Items.FLINT_AND_STEEL);
                if (f != -1) {
                    client.player.getInventory().setSelectedSlot(f);
                    client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, fireHit);
                    client.player.swing(InteractionHand.MAIN_HAND);
                } else {
                    reset(client, true);
                    return;
                }
                Vec3 eye = client.player.getEyePosition();
                Vec3 t = new Vec3(cartPos.getX() + 0.5, cartPos.getY() + 0.22, cartPos.getZ() + 0.5);
                double dx = t.x - eye.x, dy = t.y - eye.y, dz = t.z - eye.z;
                double dist = Math.sqrt(dx * dx + dz * dz);
                targetYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
                targetPitch = (float) -Math.toDegrees(Math.atan2(dy, dist));
                targetYaw += (RANDOM.nextFloat() - 0.5f) * 0.4f;
                targetPitch += (RANDOM.nextFloat() - 0.5f) * 0.3f;
                stage = Stage.AIM;
                tickTimer = 1;
            }
            case AIM -> {
                int x = findChargedCrossbow(client);
                if (x != -1) {
                    client.player.getInventory().setSelectedSlot(x);
                    float curY = client.player.getYRot();
                    float curP = client.player.getXRot();
                    float maxStep = 30f + RANDOM.nextFloat() * 15f;
                    float dY = targetYaw - curY;
                    while (dY > 180) dY -= 360;
                    while (dY < -180) dY += 360;
                    float dP = targetPitch - curP;
                    dY = Math.max(-maxStep, Math.min(maxStep, dY));
                    dP = Math.max(-maxStep * 0.6f, Math.min(maxStep * 0.6f, dP));
                    client.player.setYRot(curY + dY);
                    client.player.setXRot(Math.max(-90, Math.min(90, curP + dP)));
                    stage = Stage.DISCHARGE;
                    tickTimer = 1;
                } else {
                    int cb = findSlot(client, Items.CROSSBOW);
                    if (cb != -1) {
                        client.player.getInventory().setSelectedSlot(cb);
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
                ping = 1 + RANDOM.nextInt(3);
            }
            case RESTORE -> reset(client, true);
            default -> reset(client, false);
        }
    }

    private static void reset(Minecraft client, boolean restore) {
        if (restore && originalSlot != -1 && client.player != null) {
            client.player.getInventory().setSelectedSlot(originalSlot);
        }
        stage = Stage.IDLE;
        targetBlock = null;
        tickTimer = 0;
        originalSlot = -1;
        triggered = false;
        hesitate = 0;
        hesitating = false;
        ping = 0;
    }
    public static void toggle() { enabled = !enabled; if (!enabled) reset(Minecraft.getInstance(), true); }
        }
