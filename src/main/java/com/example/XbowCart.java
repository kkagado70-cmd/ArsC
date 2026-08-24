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

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.xbowcart.toggle",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_X,
            KeyMapping.Category.MISC
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (mc.player == null || mc.level == null) return;
            while (toggleKey.consumeClick()) {
                enabled = !enabled;
                HT1CartEngine.getInstance().hardReset();
            }
            if (enabled) {
                onTick(client);
            }
        });
    }

    public static void toggle() {
        enabled = !enabled;
        HT1CartEngine.getInstance().hardReset();
    }

    public static void onTick() {
        onTick(Minecraft.getInstance());
    }

    public static void onTick(Minecraft client) {
        if (client.player == null || client.level == null) return;
        HT1CartEngine.getInstance().processTick(client);
    }

    public static class HT1CartEngine {
        private static final HT1CartEngine INSTANCE = new HT1CartEngine();
        private final CartConfig config = new CartConfig();
        private final PlacementOptimizer placement = new PlacementOptimizer();
        private final CrossbowSimulator crossbow = new CrossbowSimulator();
        private final CartStateMachine stateMachine = new CartStateMachine();

        public static HT1CartEngine getInstance() {
            return INSTANCE;
        }

        public void processTick(Minecraft client) {
            if (client.player == null || client.level == null) return;
            config.refresh();
            stateMachine.executeSequence(client, config, placement, crossbow);
        }

        public void hardReset() {
            stateMachine.abortSequence();
        }
    }

    public static class CartConfig {
        private final int executionDelay = 1;
        private final double maxPlacementRange = 5.0D;

        public void refresh() {}
        public int getExecutionDelay() { return executionDelay; }
        public double getMaxPlacementRange() { return maxPlacementRange; }
    }

    public static class PlacementOptimizer {
        public boolean verifyHotbarRequirements(Minecraft client) {
            boolean railFound = false;
            boolean cartFound = false;
            boolean fireFound = false;
            boolean crossbowFound = false;

            for (int i = 0; i < 9; i++) {
                Item item = client.player.getInventory().getItem(i).getItem();
                if (item == Items.RAIL) railFound = true;
                if (item == Items.TNT_MINECART) cartFound = true;
                if (item == Items.FLINT_AND_STEEL || item == Items.FIRE_CHARGE) fireFound = true;
                if (item == Items.CROSSBOW) crossbowFound = true;
            }
            return railFound && cartFound && fireFound && crossbowFound;
        }

        public boolean selectAndSyncSlot(Minecraft client, Item targetItem) {
            for (int i = 0; i < 9; i++) {
                ItemStack stack = client.player.getInventory().getItem(i);
                if (stack.getItem() == targetItem) {
                    client.player.getInventory().setSelectedSlot(i);
                    if (client.getConnection() != null) {
                        client.getConnection().send(new ServerboundSetCarriedItemPacket(i));
                    }
                    return true;
                }
            }
            return false;
        }

        public boolean selectChargedOrAnyCrossbow(Minecraft client) {
            for (int i = 0; i < 9; i++) {
                ItemStack stack = client.player.getInventory().getItem(i);
                if (stack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(stack)) {
                    client.player.getInventory().setSelectedSlot(i);
                    if (client.getConnection() != null) {
                        client.getConnection().send(new ServerboundSetCarriedItemPacket(i));
                    }
                    return true;
                }
            }
            return selectAndSyncSlot(client, Items.CROSSBOW);
        }
    }

    public static class CrossbowSimulator {
        private final Random jitter = new Random();
        private int pressCounter = 0;

        public void simulateButterflyClick(Minecraft client) {
            ItemStack activeStack = client.player.getMainHandItem();
            if (activeStack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(activeStack)) {
                client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
            } else {
                client.options.keyUse.setDown(true);
                pressCounter++;
                if (pressCounter > 5 + jitter.nextInt(4)) {
                    client.options.keyUse.setDown(false);
                    pressCounter = 0;
                }
            }
        }

        public void releaseTriggers() {
            if (mc.options != null) {
                mc.options.keyUse.setDown(false);
            }
            pressCounter = 0;
        }
    }

    public static class CartStateMachine {
        private enum CartPhase { DORMANT, DEPLOY_RAIL, DEPLOY_CART, IGNITE_FIRE, FIRE_CROSSBOW, FINALIZE }
        private CartPhase phase = CartPhase.DORMANT;
        private int delayTicks = 0;

        public void executeSequence(Minecraft client, CartConfig cfg, PlacementOptimizer placer, CrossbowSimulator shooter) {
            if (!placer.verifyHotbarRequirements(client)) {
                abortSequence();
                return;
            }

            if (delayTicks > 0) {
                delayTicks--;
                return;
            }

            BlockHitResult hit = client.hitResult instanceof BlockHitResult ? (BlockHitResult) client.hitResult : null;
            if (hit == null) {
                hit = new BlockHitResult(client.player.position(), Direction.UP, client.player.blockPosition().below(), false);
            }

            BlockPos targetBlock = hit.getBlockPos();
            Direction face = hit.getDirection();
            var connection = client.getConnection();

            switch (phase) {
                case DORMANT:
                    phase = CartPhase.DEPLOY_RAIL;
                    break;

                case DEPLOY_RAIL:
                    if (placer.selectAndSyncSlot(client, Items.RAIL)) {
                        if (connection != null) {
                            connection.send(new ServerboundUseItemOnPacket(
                                InteractionHand.MAIN_HAND,
                                new BlockHitResult(hit.getLocation(), face, targetBlock, false),
                                0
                            ));
                        }
                        delayTicks = cfg.getExecutionDelay();
                        phase = CartPhase.DEPLOY_CART;
                    }
                    break;

                case DEPLOY_CART:
                    if (placer.selectAndSyncSlot(client, Items.TNT_MINECART)) {
                        if (connection != null) {
                            connection.send(new ServerboundUseItemOnPacket(
                                InteractionHand.MAIN_HAND,
                                new BlockHitResult(hit.getLocation(), face, targetBlock, false),
                                0
                            ));
                        }
                        delayTicks = cfg.getExecutionDelay();
                        phase = CartPhase.IGNITE_FIRE;
                    }
                    break;

                case IGNITE_FIRE:
                    if (placer.selectAndSyncSlot(client, Items.FLINT_AND_STEEL) || placer.selectAndSyncSlot(client, Items.FIRE_CHARGE)) {
                        if (connection != null) {
                            connection.send(new ServerboundUseItemOnPacket(
                                InteractionHand.MAIN_HAND,
                                new BlockHitResult(hit.getLocation(), face, targetBlock, false),
                                0
                            ));
                        }
                        delayTicks = cfg.getExecutionDelay();
                        phase = CartPhase.FIRE_CROSSBOW;
                    }
                    break;

                case FIRE_CROSSBOW:
                    if (placer.selectChargedOrAnyCrossbow(client)) {
                        shooter.simulateButterflyClick(client);
                        delayTicks = cfg.getExecutionDelay();
                    }
                    break;

                case FINALIZE:
                    abortSequence();
                    break;
            }
        }

        public void abortSequence() {
            phase = CartPhase.DORMANT;
            delayTicks = 0;
            if (mc.options != null) {
                mc.options.keyUse.setDown(false);
            }
        }
    }
        }
