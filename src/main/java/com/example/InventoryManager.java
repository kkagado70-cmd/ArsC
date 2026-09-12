package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.MaceItem;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

public class InventoryManager {
    public static final String FILE_NAME = "InventoryManager.java";
    private static final SecureRandom secureRandom = new SecureRandom();
    private static int cachedSelectedSlot = -1;
    private static long lastSwapEpoch = 0L;
    private static final Map<Item, Integer> itemSlotCache = new HashMap<>();
    private static boolean inventoryLocked = false;

    public static void selectSlot(Minecraft client, int slot) {
        if (client.player == null || client.options == null || slot < 0 || slot > 8 || inventoryLocked) return;
        if (System.currentTimeMillis() - lastSwapEpoch < 12L) return;

        if (client.options.keyHotbarSlots[slot] != null) {
            client.options.keyHotbarSlots[slot].setDown(true);
            client.options.keyHotbarSlots[slot].setDown(false);
        }
        cachedSelectedSlot = slot;
        lastSwapEpoch = System.currentTimeMillis();
    }

    public static int findItem(Minecraft client, Item targetItem) {
        if (client.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == targetItem) {
                return i;
            }
        }
        return -1;
    }

    public static int findChargedCrossbow(Minecraft client) {
        if (client.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(stack)) {
                return i;
            }
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack.getItem() instanceof CrossbowItem) {
                return i;
            }
        }
        return -1;
    }

    public static void purgeInventoryState() {
        cachedSelectedSlot = -1;
        lastSwapEpoch = 0L;
        itemSlotCache.clear();
        inventoryLocked = false;
    }

    public static boolean verifySlotIntegrity(Minecraft client, int expectedSlot) {
        if (client.player == null) return false;
        return client.player.getInventory().selected == expectedSlot;
    }

    public static void emergencyRestore(Minecraft client, int fallbackSlot) {
        if (client.player != null && fallbackSlot >= 0 && fallbackSlot < 9) {
            selectSlot(client, fallbackSlot);
        }
    }

    public static int countItemTotal(Minecraft client, Item targetItem) {
        if (client.player == null) return 0;
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == targetItem) {
                count += stack.getCount();
            }
        }
        return count;
    }

    public static void stepCycle(Minecraft client) {
        if (client != null && client.player != null) {
            cachedSelectedSlot = client.player.getInventory().selected;
        }
    }

    public static void setInventoryLock(boolean lock) {
        inventoryLocked = lock;
    }

    public static boolean isInventoryLocked() {
        return inventoryLocked;
    }

    public static int findFirstAvailableWeapon(Minecraft client) {
        if (client.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!stack.isEmpty() && (stack.getItem() instanceof SwordItem || stack.getItem() instanceof AxeItem || stack.getItem() instanceof MaceItem)) {
                return i;
            }
        }
        return -1;
    }
}
