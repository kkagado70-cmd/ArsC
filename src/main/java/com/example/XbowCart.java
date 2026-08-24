package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.InteractionHand;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import java.util.Random;

public class XbowCart implements ClientModInitializer {
    private static final Minecraft mc = Minecraft.getInstance();
    private static final Random random = new Random();
    private static int clickTimer = 0;
    private static int sequenceState = 0;
    private static int actionDelay = 0;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (mc.player == null || mc.level == null) return;
            
            boolean lookingAtBlock = mc.hitResult instanceof BlockHitResult;
            boolean holdingRail = mc.player.getMainHandItem().getItem() == Items.RAIL;

            if (lookingAtBlock && holdingRail) {
                onTick();
            } else {
                resetState();
            }
        });
    }

    public static void onTick() {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (actionDelay > 0) {
            actionDelay--;
            return;
        }

        BlockPos targetPos = mc.player.blockPosition().below();
        var connection = mc.getConnection();

        switch (sequenceState) {
            case 0:
                if (selectItem(Items.RAIL)) {
                    if (connection != null) {
                        connection.send(new ServerboundUseItemOnPacket(
                            InteractionHand.MAIN_HAND,
                            new BlockHitResult(Vec3.atCenterOf(targetPos), Direction.UP, targetPos, false),
                            0
                        ));
                    }
                    actionDelay = 1 + random.nextInt(2);
                    sequenceState = 1;
                }
                break;

            case 1:
                if (selectItem(Items.TNT_MINECART)) {
                    if (connection != null) {
                        connection.send(new ServerboundUseItemOnPacket(
                            InteractionHand.MAIN_HAND,
                            new BlockHitResult(Vec3.atCenterOf(targetPos), Direction.UP, targetPos, false),
                            0
                        ));
                    }
                    actionDelay = 1 + random.nextInt(2);
                    sequenceState = 2;
                }
                break;

            case 2:
                if (selectItem(Items.FLINT_AND_STEEL)) {
                    if (connection != null) {
                        connection.send(new ServerboundUseItemOnPacket(
                            InteractionHand.MAIN_HAND,
                            new BlockHitResult(Vec3.atCenterOf(targetPos), Direction.UP, targetPos, false),
                            0
                        ));
                    }
                    actionDelay = 1;
                    sequenceState = 3;
                }
                break;

            case 3:
                if (selectItem(Items.CROSSBOW)) {
                    ItemStack stack = mc.player.getMainHandItem();
                    if (stack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(stack)) {
                        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                    } else {
                        mc.options.keyUse.setDown(true);
                        clickTimer++;
                        if (clickTimer > 10 + random.nextInt(5)) {
                            mc.options.keyUse.setDown(false);
                            clickTimer = 0;
                        }
                    }
                    actionDelay = 1;
                }
                break;
        }
    }

    private static boolean selectItem(Item item) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.getItem() == item) {
                mc.player.getInventory().setSelectedSlot(i);
                if (mc.getConnection() != null) {
                    mc.getConnection().send(new ServerboundSetCarriedItemPacket(i));
                }
                return true;
            }
        }
        return false;
    }

    private static void resetState() {
        sequenceState = 0;
        actionDelay = 0;
        clickTimer = 0;
        if (mc.options != null) {
            mc.options.keyUse.setDown(false);
        }
    }
}
