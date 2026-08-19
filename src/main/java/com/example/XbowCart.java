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
    public enum Mode { STEALTH, FAST, STREAMER }

    public static boolean enabled = false;
    public static Mode currentMode = Mode.STEALTH;
    public static boolean streamerMode = false;

    private static boolean triggered = false;
    private static Stage stage = Stage.IDLE;
    private static int tickTimer = 0;
    private static BlockHitResult targetBlockHit = null;
    private static float targetYaw = 0.0f, targetPitch = 0.0f;
    private static int originalSlot = -1;
    private static final Random RANDOM = new Random();

    private static int hesitationTicks = 0;
    private static int pingSimulationTicks = 0;
    private static boolean isHesitating = false;

    public static void onTick(Minecraft client) {
        if (!enabled || client.player == null || client.level == null || client.gameMode == null) {
            if (stage != Stage.IDLE) reset(client, true);
            return;
        }

        if (pingSimulationTicks > 0) { pingSimulationTicks--; return; }
        if (hesitationTicks > 0) { hesitationTicks--; return; }
        if (isHesitating && hesitationTicks <= 0) isHesitating = false;

        if (stage == Stage.IDLE) {
            ItemStack mainHand = client.player.getMainHandItem();
            boolean holdingRail = mainHand.is(Items.RAIL) || mainHand.is(Items.POWERED_RAIL)
                    || mainHand.is(Items.DETECTOR_RAIL) || mainHand.is(Items.ACTIVATOR_RAIL);
            if (holdingRail && client.hitResult instanceof BlockHitResult hit
                    && hit.getType() == HitResult.Type.BLOCK && hit.getDirection() == Direction.UP) {
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
        return s.is(Items.RAIL) || s.is(Items.POWERED_RAIL)
                || s.is(Items.DETECTOR_RAIL) || s.is(Items.ACTIVATOR_RAIL);
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
        int rail = findRailSlot(client);
        int cart = findItemSlot(client, Items.TNT_MINECART);
        int flint = findItemSlot(client, Items.FLINT_AND_STEEL);
        int xbow = findChargedCrossbow(client);
        if (rail == -1 || cart == -1 || flint == -1) return;
        if (xbow == -1) {
            int cb = findCrossbow(client);
            if (cb != -1) {
                client.player.getInventory().setSelectedSlot(cb);
                client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
                return;
            }
            return;
        }
        originalSlot = client.player.getInventory().getSelectedSlot();
        targetBlockHit = hit;
        stage = Stage.PLACE_RAIL;
        tickTimer = 1 + RANDOM.nextInt(2);

        if (currentMode == Mode.STEALTH || streamerMode) {
            if (RANDOM.nextInt(100) < 15) {
                pingSimulationTicks = 2 + RANDOM.nextInt(4);
            }
        }
    }

    private static void processStateTransition(Minecraft client) {
        if (targetBlockHit == null) { reset(client, true); return; }

        BlockPos ground = targetBlockHit.getBlockPos();
        Direction face = targetBlockHit.getDirection();
        BlockPos railPos = ground.relative(face);
        // O carrinho deve ser colocado no bloco acima do trilho
        BlockPos cartPos = railPos.above();

        BlockHitResult railHit = new BlockHitResult(
                new Vec3(railPos.getX() + 0.5, railPos.getY() + 0.5, railPos.getZ() + 0.5),
                Direction.UP, railPos, false);

        // Hit para colocar o carrinho no centro do bloco acima
        BlockHitResult cartHit = new BlockHitResult(
                new Vec3(cartPos.getX() + 0.5, cartPos.getY() + 0.5, cartPos.getZ() + 0.5),
                Direction.UP, cartPos, false);

        // Fogo no bloco acima do carrinho (opcional, pode ser no cart)
        BlockPos firePos = cartPos.above();
        BlockHitResult fireHit = new BlockHitResult(
                new Vec3(firePos.getX() + 0.5, firePos.getY() + 0.5, firePos.getZ() + 0.5),
                Direction.UP, firePos, false);

        int delay = 1 + RANDOM.nextInt(2);

        switch (stage) {
            case PLACE_RAIL -> {
                int r = findRailSlot(client);
                if (r != -1) {
                    client.player.getInventory().setSelectedSlot(r);
                    client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, railHit);
                    client.player.swing(InteractionHand.MAIN_HAND);
                }
                stage = Stage.PLACE_CART;
                tickTimer = delay + RANDOM.nextInt(2);
                if (streamerMode && RANDOM.nextInt(100) < 15) {
                    hesitationTicks = 2 + RANDOM.nextInt(4);
                    isHesitating = true;
                }
            }
            case PLACE_CART -> {
                int c = findItemSlot(client, Items.TNT_MINECART);
                if (c != -1) {
                    client.player.getInventory().setSelectedSlot(c);
                    client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, cartHit);
                    client.player.swing(InteractionHand.MAIN_HAND);
                }
                stage = Stage.LIGHT_FIRE;
                tickTimer = delay + RANDOM.nextInt(2);
            }
            case LIGHT_FIRE -> {
                int f = findItemSlot(client, Items.FLINT_AND_STEEL);
                if (f != -1) {
                    client.player.getInventory().setSelectedSlot(f);
                    client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, fireHit);
                    client.player.swing(InteractionHand.MAIN_HAND);
                } else {
                    reset(client, true);
                    return;
                }
                computeAim(client, cartPos);
                stage = Stage.AIM;
                tickTimer = 1;
            }
            case AIM -> {
                int x = findChargedCrossbow(client);
                if (x != -1) {
                    client.player.getInventory().setSelectedSlot(x);
                    applySafeAim(client, targetYaw, targetPitch);
                    stage = Stage.DISCHARGE;
                    tickTimer = 1;
                } else {
                    int cb = findCrossbow(client);
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
                pingSimulationTicks = 1 + RANDOM.nextInt(3);
            }
            case RESTORE -> reset(client, true);
            default -> reset(client, false);
        }
    }

    private static void computeAim(Minecraft client, BlockPos cartPos) {
        Vec3 eye = client.player.getEyePosition();
        Vec3 target = new Vec3(cartPos.getX() + 0.5, cartPos.getY() + 0.22, cartPos.getZ() + 0.5);
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        targetYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        targetPitch = (float) -Math.toDegrees(Math.atan2(dy, dist));
        float jitter = streamerMode ? 0.6f : 0.3f;
        targetYaw += (RANDOM.nextFloat() - 0.5f) * jitter;
        targetPitch += (RANDOM.nextFloat() - 0.5f) * (jitter * 0.7f);
    }

    private static void applySafeAim(Minecraft client, float yaw, float pitch) {
        float curYaw = client.player.getYRot();
        float curPitch = client.player.getXRot();
        float maxStep = 25.0f + RANDOM.nextFloat() * 10.0f;
        if (streamerMode) maxStep = 20.0f + RANDOM.nextFloat() * 8.0f;
        if (currentMode == Mode.FAST) maxStep = 35.0f + RANDOM.nextFloat() * 15.0f;

        float dYaw = wrapAngle(yaw - curYaw);
        float dPitch = pitch - curPitch;

        // Reduz overshoot ao mínimo
        float overshoot = 0.02f + RANDOM.nextFloat() * 0.03f;
        dYaw += dYaw * overshoot;
        dPitch += dPitch * overshoot;

        dYaw = Math.max(-maxStep, Math.min(maxStep, dYaw));
        dPitch = Math.max(-maxStep * 0.6f, Math.min(maxStep * 0.6f, dPitch));

        float steppedYaw = curYaw + dYaw;
        float steppedPitch = curPitch + dPitch;

        client.player.setYRot(steppedYaw);
        client.player.setXRot(Math.max(-90.0f, Math.min(90.0f, steppedPitch)));
        client.player.yRotO = steppedYaw - (RANDOM.nextFloat() - 0.5f) * 0.3f;
        client.player.xRotO = steppedPitch - (RANDOM.nextFloat() - 0.5f) * 0.2f;
        client.player.yHeadRot = steppedYaw;
        client.player.yHeadRotO = steppedYaw;
    }

    private static float wrapAngle(float a) {
        a %= 360f;
        if (a >= 180f) a -= 360f;
        if (a < -180f) a += 360f;
        return a;
    }

    private static void reset(Minecraft client, boolean restore) {
        if (restore && originalSlot != -1 && client.player != null) {
            client.player.getInventory().setSelectedSlot(originalSlot);
        }
        stage = Stage.IDLE;
        targetBlockHit = null;
        tickTimer = 0;
        originalSlot = -1;
        triggered = false;
        hesitationTicks = 0;
        isHesitating = false;
        pingSimulationTicks = 0;
    }

    public static void setMode(Mode mode) {
        currentMode = mode;
        streamerMode = (mode == Mode.STREAMER);
    }
    public static void toggleStreamer() {
        streamerMode = !streamerMode;
        if (streamerMode) currentMode = Mode.STREAMER;
        else currentMode = Mode.STEALTH;
    }
    public static void reset() { reset(Minecraft.getInstance(), true); }
    public static void toggle() { enabled = !enabled; if (!enabled) reset(); }
                    }
