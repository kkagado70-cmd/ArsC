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
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

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
                HT1CartDirector.getInstance().hardResetSequence();
            }

            boolean lookingAtBlock = mc.hitResult instanceof BlockHitResult;
            boolean holdingAnyRail = isAnyRail(mc.player.getMainHandItem().getItem());

            if (enabled && lookingAtBlock && holdingAnyRail) {
                onTick(client);
            } else {
                HT1CartDirector.getInstance().hardResetSequence();
            }
        });
    }

    private static boolean isAnyRail(Item item) {
        return item == Items.RAIL || item == Items.POWERED_RAIL || item == Items.DETECTOR_RAIL || item == Items.ACTIVATOR_RAIL;
    }

    public static void toggle() {
        enabled = !enabled;
        HT1CartDirector.getInstance().hardResetSequence();
    }

    public static void onTick() {
        onTick(Minecraft.getInstance());
    }

    public static void onTick(Minecraft client) {
        if (client.player == null || client.level == null) return;
        HT1CartDirector.getInstance().processTick(client);
    }

    public static class HT1CartDirector {
        private static final HT1CartDirector INSTANCE = new HT1CartDirector();
        private final CartConfiguration configuration = new CartConfiguration();
        private final HotbarSlotAuditor auditor = new HotbarSlotAuditor();
        private final TowerGeometryCalculator geometry = new TowerGeometryCalculator();
        private final LegitimateInteractionSimulator simulator = new LegitimateInteractionSimulator();
        private final CartExecutionStateMachine pipeline = new CartExecutionStateMachine();

        public static HT1CartDirector getInstance() {
            return INSTANCE;
        }

        public void processTick(Minecraft client) {
            if (client.player == null || client.level == null) return;
            configuration.refresh();
            pipeline.executeSequence(client, configuration, auditor, geometry, simulator);
        }

        public void hardResetSequence() {
            pipeline.abortSequence();
        }
    }

    public static class CartConfiguration {
        private final int actionDelayTicks = 2;
        private final double maxPlacementDistance = 6.0D;
        private final boolean towerMode = true;

        public void refresh() {}

        public int getActionDelayTicks() { return actionDelayTicks; }
        public double getMaxPlacementDistance() { return maxPlacementDistance; }
        public boolean isTowerMode() { return towerMode; }
    }

    public static class HotbarSlotAuditor {
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

        public boolean selectAnyRail(Minecraft client) {
            for (int i = 0; i < 9; i++) {
                Item item = client.player.getInventory().getItem(i).getItem();
                if (isAnyRail(item)) {
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

    public static class TowerData {
        private final BlockPos cartPosition;
        private final BlockPos firePosition;
        private final Direction hitFace;

        public TowerData(BlockPos cartPosition, BlockPos firePosition, Direction hitFace) {
            this.cartPosition = cartPosition;
            this.firePosition = firePosition;
            this.hitFace = hitFace;
        }

        public BlockPos getCartPosition() { return cartPosition; }
        public BlockPos getFirePosition() { return firePosition; }
        public Direction getHitFace() { return hitFace; }
    }

    public static class TowerGeometryCalculator {
        public TowerData resolveTowerStructure(Minecraft client, double maxRange) {
            if (client.hitResult instanceof BlockHitResult blockHit) {
                if (client.player.distanceToSqr(blockHit.getLocation()) <= maxRange * maxRange) {
                    BlockPos basePos = blockHit.getBlockPos();
                    BlockPos topPos = basePos;
                    
                    for (int yOffset = 1; yOffset <= 4; yOffset++) {
                        BlockPos upper = basePos.above(yOffset);
                        if (!client.level.getBlockState(upper).isAir()) {
                            topPos = upper;
                        } else {
                            break;
                        }
                    }

                    BlockPos cartPlacementTarget = topPos.above();
                    BlockPos firePlacementTarget = basePos;
                    return new TowerData(cartPlacementTarget, firePlacementTarget, blockHit.getDirection());
                }
            }

            BlockPos fallback = client.player.blockPosition().below();
            return new TowerData(fallback.above(), fallback, Direction.UP);
        }
    }

    public static class LegitimateInteractionSimulator {
        private boolean hasFired = false;
        private boolean railPlaced = false;
        private boolean cartPlaced = false;
        private boolean firePlaced = false;

        public void placeRailOnce(Minecraft client, BlockPos pos, Direction face) {
            if (railPlaced) return;
            performClientInteraction(client, pos, face);
            railPlaced = true;
        }

        public void placeCartOnce(Minecraft client, BlockPos pos, Direction face) {
            if (cartPlaced) return;
            performClientInteraction(client, pos, face);
            cartPlaced = true;
        }

        public void placeFireOnce(Minecraft client, BlockPos pos, Direction face) {
            if (firePlaced) return;
            performClientInteraction(client, pos, face);
            firePlaced = true;
        }

        public void fireCrossbowOnce(Minecraft client) {
            if (hasFired) return;
            ItemStack activeStack = client.player.getMainHandItem();
            if (activeStack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(activeStack)) {
                client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
                hasFired = true;
            }
        }

        private void performClientInteraction(Minecraft client, BlockPos pos, Direction face) {
            if (client.gameMode != null && client.player != null) {
                BlockHitResult hitResult = new BlockHitResult(Vec3.atCenterOf(pos), face, pos, false);
                client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hitResult);
            }
        }

        public boolean hasFired() {
            return hasFired;
        }

        public boolean hasCompleted() {
            return railPlaced && cartPlaced && firePlaced && hasFired;
        }

        public void reset() {
            railPlaced = false;
            cartPlaced = false;
            firePlaced = false;
            hasFired = false;
        }
    }

    public static class CartExecutionStateMachine {
        private enum CartPhase { INACTIVE, STAGE_RAIL_DEPLOY, STAGE_CART_DEPLOY, STAGE_FIRE_IGNITE, STAGE_CROSSBOW_BURST, COMPLETE_LOCK }
        private CartPhase activePhase = CartPhase.INACTIVE;
        private int sequenceDelay = 0;
        private long safetyWatchdogEpoch = 0L;

        public void executeSequence(Minecraft client, CartConfiguration cfg, HotbarSlotAuditor auditor, TowerGeometryCalculator geometry, LegitimateInteractionSimulator simulator) {
            if (activePhase == CartPhase.COMPLETE_LOCK) {
                return;
            }

            if (sequenceDelay > 0) {
                sequenceDelay--;
                return;
            }

            if (System.currentTimeMillis() > safetyWatchdogEpoch && activePhase != CartPhase.INACTIVE) {
                abortSequence();
                return;
            }

            TowerData tower = geometry.resolveTowerStructure(client, cfg.getMaxPlacementDistance());

            switch (activePhase) {
                case INACTIVE:
                    simulator.reset();
                    activePhase = CartPhase.STAGE_RAIL_DEPLOY;
                    safetyWatchdogEpoch = System.currentTimeMillis() + 1500L;
                    break;

                case STAGE_RAIL_DEPLOY:
                    if (auditor.selectAnyRail(client)) {
                        simulator.placeRailOnce(client, tower.getCartPosition(), tower.getHitFace());
                        sequenceDelay = cfg.getActionDelayTicks();
                        activePhase = CartPhase.STAGE_CART_DEPLOY;
                    }
                    break;

                case STAGE_CART_DEPLOY:
                    if (auditor.selectAndSyncSlot(client, Items.TNT_MINECART)) {
                        simulator.placeCartOnce(client, tower.getCartPosition(), tower.getHitFace());
                        sequenceDelay = cfg.getActionDelayTicks();
                        activePhase = CartPhase.STAGE_FIRE_IGNITE;
                    }
                    break;

                case STAGE_FIRE_IGNITE:
                    if (auditor.selectAndSyncSlot(client, Items.FLINT_AND_STEEL) || auditor.selectAndSyncSlot(client, Items.FIRE_CHARGE)) {
                        simulator.placeFireOnce(client, tower.getFirePosition(), tower.getHitFace());
                        sequenceDelay = cfg.getActionDelayTicks();
                        activePhase = CartPhase.STAGE_CROSSBOW_BURST;
                    }
                    break;

                case STAGE_CROSSBOW_BURST:
                    if (auditor.selectChargedOrAnyCrossbow(client)) {
                        simulator.fireCrossbowOnce(client);
                        if (simulator.hasFired()) {
                            activePhase = CartPhase.COMPLETE_LOCK;
                            XbowCart.enabled = false;
                        }
                        sequenceDelay = cfg.getActionDelayTicks();
                    }
                    break;

                case COMPLETE_LOCK:
                    break;
            }
        }

        public void abortSequence() {
            activePhase = CartPhase.INACTIVE;
            sequenceDelay = 0;
            safetyWatchdogEpoch = 0L;
        }
    }
                                  }
