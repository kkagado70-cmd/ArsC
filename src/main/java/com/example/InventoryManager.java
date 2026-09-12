package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.CrossbowItem;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

public class InventoryManager {
    public static final String FILE_NAME = "InventoryManager.java";
    private static final SecureRandom secureRandom = new SecureRandom();
    private static int cachedSelectedSlot = 0;
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
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack.getItem() instanceof CrossbowItem) {
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
            if (!stack.isEmpty() && stack.getItem().getDescriptionId().toLowerCase().contains(query)) {
                return i;
            }
        }
        return -1;
    }

    public static void performHumanizedSwap(Minecraft client, int slot) {
        if (client.player == null || slot < 0 || slot > 8) return;
        selectSlot(client, slot);
    }

    public static int fetchCachedSlot() { return cachedSelectedSlot; }

    public static void purgeInventoryState() {
        cachedSelectedSlot = 0;
        lastSwapEpoch = 0L;
        itemSlotCache.clear();
        inventoryLocked = false;
    }

    public static boolean verifySlotIntegrity(Minecraft client, int expectedSlot) {
        if (client.player == null) return false;
        return cachedSelectedSlot == expectedSlot;
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

    public static boolean validateHotbarIndex(int index) {
        return index >= 0 && index < 9;
    }

    public static ItemStack fetchCurrentItemStack(Minecraft client) {
        if (client.player == null) return ItemStack.EMPTY;
        return client.player.getMainHandItem();
    }

    public static void clearSlotCache() {
        itemSlotCache.clear();
    }

    public static int findFirstAvailableWeapon(Minecraft client) {
        if (client.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                String id = stack.getItem().getDescriptionId().toLowerCase();
                if (id.contains("sword") || id.contains("axe") || id.contains("mace") || id.contains("trident")) {
                    return i;
                }
            }
        }
        return -1;
    }
}