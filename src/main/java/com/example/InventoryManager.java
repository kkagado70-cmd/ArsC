package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.CrossbowItem;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

public class InventoryManager {
    private static final SecureRandom secureRandom = new SecureRandom();
    private static int cachedSelectedSlot = -1;
    private static long lastSwapEpoch = 0L;
    private static final Map<Item, Integer> itemSlotCache = new HashMap<>();
    private static boolean inventoryLocked = false;

    public static void selectSlot(Minecraft client, int slot) {
        if (client.player == null || slot < 0 || slot > 8 || inventoryLocked) return;
        if (System.currentTimeMillis() - lastSwapEpoch < 15L) return;

        client.player.getInventory().setSelectedSlot(slot);
        if (client.options != null && client.options.keyHotbarSlots[slot] != null) {
            client.options.keyHotbarSlots[slot].setDown(true);
            client.options.keyHotbarSlots[slot].setDown(false);
        }
        cachedSelectedSlot = slot;
        lastSwapEpoch = System.currentTimeMillis();
    }

    public static int findItem(Minecraft client, Item targetItem) {
        if (client.player == null) return -1;
        if (itemSlotCache.containsKey(targetItem)) {
            int cachedSlot = itemSlotCache.get(targetItem);
            if (cachedSlot >= 0 && cachedSlot < 9) {
                ItemStack stack = client.player.getInventory().getItem(cachedSlot);
                if (!stack.isEmpty() && stack.getItem() == targetItem) {
                    return cachedSlot;
                }
            }
        }

        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == targetItem) {
                itemSlotCache.put(targetItem, i);
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
        return client.player.getInventory().getSelectedSlot() == expectedSlot;
    }

    public static void setInventoryLock(boolean lock) {
        inventoryLocked = lock;
    }

    public static boolean isInventoryLocked() {
        return inventoryLocked;
    }
}