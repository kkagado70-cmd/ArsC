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
        private final CartConfiguration config = new CartConfiguration();
        private final HotbarAuditor auditor = new HotbarAuditor();
        private final PlacementGeometryEngine placement = new PlacementGeometryEngine();
        private final CrossbowSimulator crossbow = new CrossbowSimulator();
        private final CartStateMachine stateMachine = new CartStateMachine();

        public static HT1CartEngine getInstance() {
            return INSTANCE;
        }

        public void processTick(Minecraft client) {
            if (client.player == null || client.level == null) return;
            config.refresh();
            stateMachine.executeSequence(client, config, auditor, placement, crossbow);
        }

        public void hardReset() {
            stateMachine.abortSequence();
        }
    }

    public static class CartConfiguration {
        private final int executionDelayTicks = 1;
        private final double maxPlacementDistance = 6.0D;
        private final boolean towerPenetrationMode = true;

        public void refresh() {}

        public int getExecutionDelayTicks() { return executionDelayTicks; }
        public double getMaxPlacementDistance() { return maxPlacementDistance; }
        public boolean isTowerPenetrationMode() { return towerPenetrationMode; }
    }

    public static class HotbarAuditor {
        public boolean validateInventoryLoadout(Minecraft client) {
            boolean hasRail = false;
            boolean hasCart = false;
            boolean hasFireSource = false;
            boolean hasCrossbow = false;

            for (int slot = 0; slot < 9; slot++) {
                Item itemType = client.player.getInventory().getItem(slot).getItem();
                if (itemType == Items.RAIL) hasRail = true;
                if (itemType == Items.TNT_MINECART) hasCart = true;
                if (itemType == Items.FLINT_AND_STEEL || itemType == Items.FIRE_CHARGE) hasFireSource = true;
                if (itemType == Items.CROSSBOW) hasCrossbow = true;
            }
            return hasRail && hasCart && hasFireSource && hasCrossbow;
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

    public static class PlacementGeometryEngine {
        public BlockHitResult resolveTargetHit(Minecraft client, double maxRange) {
            if (client.hitResult instanceof BlockHitResult blockHit) {
                if (client.player.distanceToSqr(blockHit.getLocation()) <= maxRange * maxRange) {
                    return blockHit;
                }
            }
            BlockPos fallbackPos = client.player.blockPosition().below();
            return new BlockHitResult(Vec3.atCenterOf(fallbackPos), Direction.UP, fallbackPos, false);
        }
    }

    public static class CrossbowSimulator {
        private final Random stochasticJitter = new Random();
        private int pressTicks = 0;

        public void simulateButterflyFire(Minecraft client) {
            ItemStack activeStack = client.player.getMainHandItem();
            if (activeStack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(activeStack)) {
                client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
            } else {
                client.options.keyUse.setDown(true);
                pressTicks++;
                if (pressTicks > 6 + stochasticJitter.nextFloat() * 4) {
                    client.options.keyUse.setDown(false);
                    pressTicks = 0;
                }
            }
        }

        public void releaseTriggers() {
            if (mc.options != null) {
                mc.options.keyUse.setDown(false);
            }
            pressTicks = 0;
        }
    }

    public static class CartStateMachine {
        private enum SequencePhase { INACTIVE, STAGE_RAIL, STAGE_CART, STAGE_FIRE, STAGE_CROSSBOW, TERMINATE }
        private SequencePhase currentPhase = SequencePhase.INACTIVE;
        private int internalDelay = 0;
        private long safetyWatchdog = 0L;

        public void executeSequence(Minecraft client, CartConfiguration cfg, HotbarAuditor auditor, PlacementGeometryEngine geometry, CrossbowSimulator simulator) {
            if (!auditor.validateInventoryLoadout(client)) {
                abortSequence();
                return;
            }

            if (internalDelay > 0) {
                internalDelay--;
                return;
            }

            if (System.currentTimeMillis() > safetyWatchdog && currentPhase != SequencePhase.INACTIVE) {
                abortSequence();
                return;
            }

            BlockHitResult resolvedHit = geometry.resolveTargetHit(client, cfg.getMaxPlacementDistance());
            BlockPos targetPos = resolvedHit.getBlockPos();
            Direction hitFace = resolvedHit.getDirection();
            var connection = client.getConnection();

            switch (currentPhase) {
                case INACTIVE:
                    currentPhase = SequencePhase.STAGE_RAIL;
                    safetyWatchdog = System.currentTimeMillis() + 1500L;
                    break;

                case STAGE_RAIL:
                    if (auditor.selectAndSyncSlot(client, Items.RAIL)) {
                        if (connection != null) {
                            connection.send(new ServerboundUseItemOnPacket(
                                InteractionHand.MAIN_HAND,
                                new BlockHitResult(resolvedHit.getLocation(), hitFace, targetPos, false),
                                0
                            ));
                        }
                        internalDelay = cfg.getExecutionDelayTicks();
                        currentPhase = SequencePhase.STAGE_CART;
                    }
                    break;

                case STAGE_CART:
                    if (auditor.selectAndSyncSlot(client, Items.TNT_MINECART)) {
                        if (connection != null) {
                            connection.send(new ServerboundUseItemOnPacket(
                                InteractionHand.MAIN_HAND,
                                new BlockHitResult(resolvedHit.getLocation(), hitFace, targetPos, false),
                                0
                            ));
                        }
                        internalDelay = cfg.getExecutionDelayTicks();
                        currentPhase = SequencePhase.STAGE_FIRE;
                    }
                    break;

                case STAGE_FIRE:
                    if (auditor.selectAndSyncSlot(client, Items.FLINT_AND_STEEL) || auditor.selectAndSyncSlot(client, Items.FIRE_CHARGE)) {
                        if (connection != null) {
                            connection.send(new ServerboundUseItemOnPacket(
                                InteractionHand.MAIN_HAND,
                                new BlockHitResult(resolvedHit.getLocation(), hitFace, targetPos, false),
                                0
                            ));
                        }
                        internalDelay = cfg.getExecutionDelayTicks();
                        currentPhase = SequencePhase.STAGE_CROSSBOW;
                    }
                    break;

                case STAGE_CROSSBOW:
                    if (auditor.selectChargedOrAnyCrossbow(client)) {
                        simulator.simulateButterflyFire(client);
                        internalDelay = cfg.getExecutionDelayTicks();
                    }
                    break;

                case TERMINATE:
                    abortSequence();
                    break;
            }
        }

        public void abortSequence() {
            currentPhase = SequencePhase.INACTIVE;
            internalDelay = 0;
            if (mc.options != null) {
                mc.options.keyUse.setDown(false);
            }
            safetyWatchdog = 0L;
        }
    }
}
