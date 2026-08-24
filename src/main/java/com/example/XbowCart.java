package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
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
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;
import java.util.Random;

public class XbowCart implements ClientModInitializer {
    private static final Minecraft mc = Minecraft.getInstance();
    private static KeyMapping toggleKey;
    public static boolean enabled = false;
    private static final Random random = new Random();
    private static int clickTimer = 0;
    private static int sequenceState = 0;
    private static int actionDelay = 0;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.xbowcart.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_X, "key.categories.misc"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (mc.player == null || mc.level == null) return;
            while (toggleKey.consumeClick()) {
                enabled = !enabled;
                resetState();
            }
            if (enabled) {
                onTick(client);
            }
        });
    }

    public static void toggle() {
        enabled = !enabled;
        resetState();
    }

    public static void onTick() {
        onTick(Minecraft.getInstance());
    }

    public static void onTick(Minecraft client) {
        if (client.player == null || client.level == null || client.gameMode == null) return;
        if (actionDelay > 0) {
            actionDelay--;
            return;
        }

        BlockPos targetPos = client.player.blockPosition().below();
        var connection = client.getConnection();

        switch (sequenceState) {
            case 0:
                if (selectItem(client, Items.RAIL)) {
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
                if (selectItem(client, Items.TNT_MINECART)) {
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
                if (selectItem(client, Items.FLINT_AND_STEEL)) {
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
                if (selectItem(client, Items.CROSSBOW)) {
                    ItemStack stack = client.player.getMainHandItem();
                    if (stack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(stack)) {
                        client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
                    } else {
                        client.options.keyUse.setDown(true);
                        clickTimer++;
                        if (clickTimer > 10 + random.nextInt(5)) {
                            client.options.keyUse.setDown(false);
                            clickTimer = 0;
                        }
                    }
                    actionDelay = 1;
                }
                break;
        }
    }

    private static boolean selectItem(Minecraft client, Item item) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack.getItem() == item) {
                client.player.getInventory().setSelectedSlot(i);
                if (client.getConnection() != null) {
                    client.getConnection().send(new ServerboundSetCarriedItemPacket(i));
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
