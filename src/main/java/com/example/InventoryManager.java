package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.CrossbowItem;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InventoryManager {
    public static final String FILE_NAME = "InventoryManager.java";
    private static final SecureRandom secureRandom = new SecureRandom();
    private static int cachedSelectedSlot = -1;
    private static long lastSwapEpoch = 0L;
    private static final Map<Item, Integer> itemSlotCache = new HashMap<>();
    private static boolean inventoryLocked = false;

    private static final Map<String, Object> INVENTORY_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_ID = UUID.randomUUID();

    static {
        INVENTORY_REGISTRY.put("SubsessionUUID", SUBSESSION_ID);
        INVENTORY_REGISTRY.put("Profile", "Enterprise-InventoryManager");
    }

    public static void selectSlot(Minecraft client, int slot) {
        if (client.player == null || slot < 0 || slot > 8 || inventoryLocked) return;
        long now = System.currentTimeMillis();
        long dynamicSwapDelay = 15L + secureRandom.nextInt(20);
        if (now - lastSwapEpoch < dynamicSwapDelay) return;

        client.player.getInventory().setSelectedSlot(slot);
        if (client.options != null && client.options.keyHotbarSlots[slot] != null) {
            client.options.keyHotbarSlots[slot].setDown(true);
            client.options.keyHotbarSlots[slot].setDown(false);
        }
        cachedSelectedSlot = slot;
        lastSwapEpoch = now;
    }

    public static int findItem(Minecraft client, Item targetItem) {
        if (client.player == null) return -1;
        if (itemSlotCache.containsKey(targetItem)) {
            int cachedSlot = itemSlotCache.get(targetItem);
            if (cachedSlot >= 0 && cachedSlot < 9) {
                ItemStack stack = client.player.getInventory().getItem(cachedSlot);
                if (!stack.isEmpty() && stack.getItem() == targetItem) return cachedSlot;
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
            if (stack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(stack)) return i;
        }
        return -1;
    }

    public static UUID getSubsessionIdentity() { return SUBSESSION_ID; }
}