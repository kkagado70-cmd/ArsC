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
                "key.xbowcart.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_X, KeyMapping.Category.MISC
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (mc.player == null || mc.level == null) return;
            while (toggleKey.consumeClick()) { enabled = !enabled; CartPipeline.getInstance().reset(); }
            boolean looking = mc.hitResult instanceof BlockHitResult;
            boolean rail = isRail(mc.player.getMainHandItem().getItem());
            if (enabled && looking && rail) onTick(client);
            else CartPipeline.getInstance().reset();
        });
    }

    public static void toggle() { enabled = !enabled; CartPipeline.getInstance().reset(); }
    public static void onTick() { onTick(Minecraft.getInstance()); }
    public static void onTick(Minecraft client) {
        if (client.player == null || client.level == null) return;
        CartPipeline.getInstance().process(client);
    }

    private static boolean isRail(Item i) {
        return i == Items.RAIL || i == Items.POWERED_RAIL || i == Items.DETECTOR_RAIL || i == Items.ACTIVATOR_RAIL;
    }

    public static class CartConfiguration {
        private final Random rnd = new Random();
        public int getDelay() { return 2 + rnd.nextInt(2); }
    }

    public static class CartInventoryAuditor {
        public boolean select(Minecraft client, Item target) {
            for (int i = 0; i < 9; i++) {
                if (client.player.getInventory().getItem(i).getItem() == target) {
                    client.player.getInventory().setSelectedSlot(i);
                    client.options.keyHotbarSlots[i].setDown(true);
                    client.options.keyHotbarSlots[i].setDown(false);
                    return true;
                }
            }
            return false;
        }

        public boolean selectRail(Minecraft client) {
            for (int i = 0; i < 9; i++) {
                if (isRail(client.player.getInventory().getItem(i).getItem())) {
                    client.player.getInventory().setSelectedSlot(i);
                    client.options.keyHotbarSlots[i].setDown(true);
                    client.options.keyHotbarSlots[i].setDown(false);
                    return true;
                }
            }
            return false;
        }

        public boolean selectCrossbow(Minecraft client) {
            for (int i = 0; i < 9; i++) {
                ItemStack s = client.player.getInventory().getItem(i);
                if (s.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(s)) {
                    client.player.getInventory().setSelectedSlot(i);
                    client.options.keyHotbarSlots[i].setDown(true);
                    client.options.keyHotbarSlots[i].setDown(false);
                    return true;
                }
            }
            return select(client, Items.CROSSBOW);
        }
    }

    public static class CartPipeline {
        private static final CartPipeline INSTANCE = new CartPipeline();
        private final CartConfiguration cfg = new CartConfiguration();
        private final CartInventoryAuditor auditor = new CartInventoryAuditor();
        private int stage = 0, delay = 0, releaseTimer = 0, globalCd = 0;

        public static CartPipeline getInstance() { return INSTANCE; }

        public void reset() {
            stage = 0; delay = 0; globalCd = 0; releaseTimer = 0;
            if (mc.options != null) mc.options.keyUse.setDown(false);
        }

        public void process(Minecraft client) {
            if (releaseTimer > 0) {
                releaseTimer--;
                if (releaseTimer == 0 && mc.options != null) mc.options.keyUse.setDown(false);
            }
            if (globalCd > 0) { globalCd--; return; }
            if (delay > 0) { delay--; return; }

            BlockHitResult hit = (BlockHitResult) client.hitResult;
            BlockPos pos = hit.getBlockPos();
            Direction face = hit.getDirection();

            switch (stage) {
                case 0:
                    if (auditor.selectRail(client)) {
                        interact(client, pos, face); delay = cfg.getDelay(); stage = 1;
                    }
                    break;
                case 1:
                    if (auditor.select(client, Items.TNT_MINECART)) {
                        interact(client, pos, face); delay = cfg.getDelay(); stage = 2;
                    }
                    break;
                case 2:
                    if (auditor.select(client, Items.FLINT_AND_STEEL) || auditor.select(client, Items.FIRE_CHARGE)) {
                        interact(client, pos, face); delay = cfg.getDelay(); stage = 3;
                    }
                    break;
                case 3:
                    if (auditor.selectCrossbow(client)) {
                        ItemStack stack = client.player.getMainHandItem();
                        if (stack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(stack)) {
                            client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
                        } else {
                            client.options.keyUse.setDown(true); releaseTimer = 4;
                        }
                        globalCd = 10; stage = 0; XbowCart.enabled = false;
                    }
                    break;
            }
        }

        private void interact(Minecraft client, BlockPos pos, Direction face) {
            if (client.gameMode != null && client.player != null) {
                BlockHitResult hr = new BlockHitResult(Vec3.atCenterOf(pos), face, pos, false);
                client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hr);
            }
        }
    }
        }
