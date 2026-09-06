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
        if (System.currentTimeMillis() - lastSwapEpoch < 12L) return;

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

    public static int findItemFuzzy(Minecraft client, String nameQuery) {
        if (client.player == null || nameQuery == null) return -1;
        String query = nameQuery.toLowerCase();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getDescriptionId().toLowerCase().contains(query)) {
                return i;
            }
        }
        return -1;
    }

    public static void performHumanizedSwap(Minecraft client, int slot) {
        if (client.player == null || slot < 0 || slot > 8) return;
        selectSlot(client, slot);
        try {
            Thread.sleep(1 + secureRandom.nextInt(4));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static int fetchCachedSlot() { return cachedSelectedSlot; }

    public static void purgeInventoryState() {
        cachedSelectedSlot = -1;
        lastSwapEpoch = 0L;
        itemSlotCache.clear();
        inventoryLocked = false;
    }

    public static boolean verifySlotIntegrity(Minecraft client, int expectedSlot) {
        if (client.player == null) return false;
        return client.player.getInventory().selectedSlot == expectedSlot;
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
            cachedSelectedSlot = client.player.getInventory().selectedSlot;
            if (secureRandom.nextInt(100) == 0) {
                itemSlotCache.clear();
            }
        }
    }

    public static void setInventoryLock(boolean lock) {
        inventoryLocked = lock;
    }

    public static boolean isInventoryLocked() {
        return inventoryLocked;
    }

    public static int findEmptyHotbarSlot(Minecraft client) {
        if (client.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (client.player.getInventory().getItem(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    public static boolean hasItemInOffhand(Minecraft client, Item targetItem) {
        if (client.player == null) return false;
        return client.player.getOffhandItem().getItem() == targetItem;
    }
}