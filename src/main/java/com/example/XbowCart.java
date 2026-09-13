package com.example;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
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
import net.minecraft.world.entity.Entity;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class XbowCart extends ClientBase.Module {
    public static final String FILE_NAME = "XbowCart.java";
    public static boolean enabled = false;

    private enum PipelinePhase { VOID, RAIL, CART, FLINT, SHOOT, DONE }

    private static PipelinePhase currentPhase = PipelinePhase.VOID;
    private static int tickWait = 0;
    private static BlockPos targetPos = null;
    private static Direction targetFace = Direction.UP;
    private static BlockPos railPos = null;
    private static BlockPos cartPos = null;
    private static BlockPos firePos = null;
    private static final SecureRandom secureRandom = new SecureRandom();
    private static final Map<String, Object> REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_ID = UUID.randomUUID();

    static {
        REGISTRY.put("SubsessionUUID", SUBSESSION_ID);
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (enabled) {
                onTick(client);
            }
        });
    }

    public XbowCart() {
        super("XbowCart");
        XbowCart.enabled = false;
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
    public void tick(Minecraft client) {
        onTick(client);
    }

    private static boolean isRail(Item item) {
        return item == Items.RAIL || item == Items.POWERED_RAIL || item == Items.DETECTOR_RAIL || item == Items.ACTIVATOR_RAIL;
    }

    private static boolean isRailState(BlockState state) {
        return state.is(Blocks.RAIL) || state.is(Blocks.POWERED_RAIL) || state.is(Blocks.DETECTOR_RAIL) || state.is(Blocks.ACTIVATOR_RAIL);
    }

    public static void resetPipeline() {
        currentPhase = PipelinePhase.VOID;
        tickWait = 0;
        targetPos = null;
        targetFace = Direction.UP;
        railPos = null;
        cartPos = null;
        firePos = null;
    }

    private static int findItemSlot(Minecraft client, Item targetItem) {
        if (client.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (client.player.getInventory().getItem(i).getItem() == targetItem) return i;
        }
        return -1;
    }

    private static int findRailSlot(Minecraft client) {
        if (client.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (isRail(client.player.getInventory().getItem(i).getItem())) return i;
        }
        return -1;
    }

    private static int findCrossbowSlot(Minecraft client) {
        if (client.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(stack)) return i;
        }
        return -1;
    }

    private static void selectAndUse(Minecraft client, int slot) {
        if (client.player == null || client.options == null) return;
        client.player.getInventory().setSelectedSlot(slot);
        if (slot >= 0 && slot < 9 && client.options.keyHotbarSlots[slot] != null) {
            client.options.keyHotbarSlots[slot].setDown(true);
            client.options.keyHotbarSlots[slot].setDown(false);
        }
        client.options.keyUse.setDown(true);
        client.options.keyUse.setDown(false);
    }

    public static void onTick(Minecraft client) {
        if (!enabled || client.player == null || client.level == null) {
            return;
        }

        if (tickWait > 0) {
            tickWait--;
            return;
        }

        switch (currentPhase) {
            case VOID:
                HitResult hit = client.hitResult;
                if (hit == null || hit.getType() != HitResult.Type.BLOCK) return;
                BlockHitResult blockHit = (BlockHitResult) hit;

                if (!isRail(client.player.getMainHandItem().getItem())) return;
                if (findCrossbowSlot(client) == -1) return;
                if (findItemSlot(client, Items.TNT_MINECART) == -1) return;

                targetPos = blockHit.getBlockPos();
                targetFace = blockHit.getDirection();

                BlockState state = client.level.getBlockState(targetPos);
                if (isRailState(state)) {
                    railPos = targetPos;
                } else if (targetFace == Direction.UP) {
                    railPos = targetPos.above();
                } else {
                    railPos = targetPos.relative(targetFace);
                    if (!client.level.getBlockState(railPos).isAir() && client.level.getBlockState(railPos.above()).isAir()) {
                        railPos = railPos.above();
                    }
                }

                cartPos = railPos;
                Direction facing = client.player.getDirection();
                firePos = railPos.relative(facing.getOpposite());
                if (!client.level.getBlockState(firePos.below()).isSolid()) {
                    firePos = railPos.relative(facing);
                }
                if (!client.level.getBlockState(firePos.below()).isSolid()) {
                    firePos = railPos.above();
                }

                currentPhase = PipelinePhase.RAIL;
                break;

            case RAIL:
                int rSlot = findRailSlot(client);
                if (rSlot == -1) {
                    resetPipeline();
                    return;
                }
                RotationManager.smoothTo(client, Vec3.atCenterOf(railPos), 1.0F);
                selectAndUse(client, rSlot);
                tickWait = 1;
                currentPhase = PipelinePhase.CART;
                break;

            case CART:
                int cSlot = findItemSlot(client, Items.TNT_MINECART);
                if (cSlot == -1) {
                    resetPipeline();
                    return;
                }
                RotationManager.smoothTo(client, Vec3.atCenterOf(cartPos), 1.0F);
                selectAndUse(client, cSlot);
                tickWait = 1;
                currentPhase = PipelinePhase.FLINT;
                break;

            case FLINT:
                int fSlot = findItemSlot(client, Items.FLINT_AND_STEEL);
                if (fSlot == -1) fSlot = findItemSlot(client, Items.FIRE_CHARGE);
                if (fSlot == -1) {
                    resetPipeline();
                    return;
                }
                RotationManager.smoothTo(client, Vec3.atCenterOf(firePos), 1.0F);
                selectAndUse(client, fSlot);
                tickWait = 1;
                currentPhase = PipelinePhase.SHOOT;
                break;

            case SHOOT:
                int xSlot = findCrossbowSlot(client);
                if (xSlot == -1) {
                    resetPipeline();
                    return;
                }
                Vec3 targetShoot = Vec3.atCenterOf(cartPos).add(0.0D, 0.2D, 0.0D);
                RotationManager.smoothTo(client, targetShoot, 1.0F);
                selectAndUse(client, xSlot);
                tickWait = 2;
                currentPhase = PipelinePhase.DONE;
                break;

            case DONE:
                resetPipeline();
                break;
        }
    }

    public static UUID getSubsessionIdentity() {
        return SUBSESSION_ID;
    }
}
