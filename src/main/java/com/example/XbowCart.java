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
import net.minecraft.util.Mth;
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

    private static int state = 0;
    private static int delay = 0;
    private static int globalCooldown = 0;
    private static int keyReleaseTimer = 0;

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
                resetState();
            }

            if (enabled) {
                onTick();
            }
        });
    }

    public static void resetState() {
        state = 0;
        delay = 0;
        globalCooldown = 0;
        releaseInputs();
    }

    private static void releaseInputs() {
        if (mc.options != null) {
            mc.options.keyUse.setDown(false);
        }
    }

    public static void onTick() {
        if (mc.player == null || mc.level == null) return;

        if (keyReleaseTimer > 0) {
            keyReleaseTimer--;
            if (keyReleaseTimer == 0) {
                releaseInputs();
            }
        }

        if (globalCooldown > 0) {
            globalCooldown--;
            return;
        }

        boolean lookingAtGround = mc.hitResult instanceof BlockHitResult blockHit && blockHit.getDirection() == Direction.UP;
        boolean holdingRail = isAnyRail(mc.player.getMainHandItem().getItem());

        if (!lookingAtGround || !holdingRail) {
            if (state != 0) {
                resetState();
            }
            return;
        }

        if (delay > 0) {
            delay--;
            return;
        }

        switch (state) {
            case 0:
                if (selectItemSlot(Items.RAIL)) {
                    delay = 2 + new Random().nextInt(2);
                    state = 1;
                }
                break;
            case 1:
                simulateRightClick();
                if (selectItemSlot(Items.TNT_MINECART)) {
                    delay = 2 + new Random().nextInt(2);
                    state = 2;
                }
                break;
            case 2:
                simulateRightClick();
                if (selectItemSlot(Items.FLINT_AND_STEEL) || selectItemSlot(Items.FIRE_CHARGE)) {
                    delay = 2 + new Random().nextInt(2);
                    state = 3;
                }
                break;
            case 3:
                simulateRightClick();
                if (selectCrossbowSlot()) {
                    delay = 2;
                    state = 4;
                }
                break;
            case 4:
                ItemStack stack = mc.player.getMainHandItem();
                if (stack.getItem() instanceof CrossbowItem) {
                    mc.options.keyUse.setDown(true);
                    keyReleaseTimer = 4 + new Random().nextInt(3);
                }
                globalCooldown = 6 + new Random().nextInt(5);
                state = 0;
                break;
        }
    }

    private static void simulateRightClick() {
        mc.options.keyUse.setDown(true);
        keyReleaseTimer = 2;
    }

    private static boolean isAnyRail(Item item) {
        return item == Items.RAIL || item == Items.POWERED_RAIL || item == Items.DETECTOR_RAIL || item == Items.ACTIVATOR_RAIL;
    }

    private static boolean selectItemSlot(Item target) {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).getItem() == target) {
                mc.player.getInventory().setSelectedSlot(i);
                simulateNumberKey(i + 1);
                return true;
            }
        }
        return false;
    }

    private static boolean selectCrossbowSlot() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.getItem() instanceof CrossbowItem) {
                mc.player.getInventory().setSelectedSlot(i);
                simulateNumberKey(i + 1);
                return true;
            }
        }
        return false;
    }

    private static void simulateNumberKey(int slotNum) {
        if (slotNum >= 1 && slotNum <= 9) {
            mc.options.keyHotbarSlots[slotNum - 1].setDown(true);
            mc.options.keyHotbarSlots[slotNum - 1].setDown(false);
        }
    }
}
