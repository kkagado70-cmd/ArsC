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
import java.util.ArrayDeque;
import java.util.Deque;

public class InventoryManager {
    public static final String FILE_NAME = "InventoryManager.java";
    private static final SecureRandom secureRandom = new SecureRandom();
    private static int cachedSelectedSlot = -1;
    private static long lastSwapEpoch = 0L;
    private static final Map<Item, Integer> itemSlotCache = new HashMap<>();
    private static boolean inventoryLocked = false;

    private static final Map<String, Object> INVENTORY_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_UUID = UUID.randomUUID();
    private static final Deque<Integer> SLOT_HISTORY_DEQUE = new ArrayDeque<>();
    private static final int HISTORY_CAPACITY = 128;
    private static long operationCounter = 0L;
    private static int slotSearchAttempts = 0;
    private static int inventoryScoreRegistry = 0;
    private static long lastCacheUpdateEpoch = 0L;
    private static final long cacheValidityDurationMs = 50L;

    static {
        initializeInventoryRegistry();
    }

    private static void initializeInventoryRegistry() {
        INVENTORY_REGISTRY.put("SubsessionUUID", SUBSESSION_UUID);
        INVENTORY_REGISTRY.put("Profile", "HT1-Enterprise-InventoryManager");
        INVENTORY_REGISTRY.put("BypassEngine", "GrimAC-Hotbar-Order-Strict");
        INVENTORY_REGISTRY.put("InitializationEpoch", System.currentTimeMillis());
        INVENTORY_REGISTRY.put("OperationCounter", 0L);
        INVENTORY_REGISTRY.put("InventoryLockState", inventoryLocked);
    }

    public static void selectSlot(Minecraft client, int slot) {
        if (client.player == null || slot < 0 || slot > 8 || inventoryLocked) return;
        if (System.currentTimeMillis() - lastSwapEpoch < 15L) return;

        operationCounter++;
        client.player.getInventory().setSelectedSlot(slot);
        if (client.options != null && client.options.keyHotbarSlots[slot] != null) {
            client.options.keyHotbarSlots[slot].setDown(true);
            client.options.keyHotbarSlots[slot].setDown(false);
        }
        cachedSelectedSlot = slot;
        lastSwapEpoch = System.currentTimeMillis();

        if (SLOT_HISTORY_DEQUE.size() >= HISTORY_CAPACITY) {
            SLOT_HISTORY_DEQUE.pollFirst();
        }
        SLOT_HISTORY_DEQUE.offerLast(slot);
        updateRegistryMetrics();
    }

    public static int findItem(Minecraft client, Item targetItem) {
        if (client.player == null) return -1;
        slotSearchAttempts++;
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
        slotSearchAttempts++;
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
        SLOT_HISTORY_DEQUE.clear();
        operationCounter = 0L;
        slotSearchAttempts = 0;
        inventoryScoreRegistry = 0;
        purgeRegistry();
        initializeInventoryRegistry();
    }

    public static boolean verifySlotIntegrity(Minecraft client, int expectedSlot) {
        if (client.player == null) return false;
        return client.player.getInventory().getSelectedSlot() == expectedSlot;
    }

    public static void setInventoryLock(boolean lock) {
        inventoryLocked = lock;
        INVENTORY_REGISTRY.put("InventoryLockState", inventoryLocked);
    }

    public static boolean isInventoryLocked() {
        return inventoryLocked;
    }

    public static int scoreAndFindOptimalSlot(Minecraft client, Item targetItem) {
        if (client.player == null || targetItem == null) return -1;
        slotSearchAttempts++;
        int bestSlot = -1;
        int maxScore = -999999;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack.getItem() == targetItem) {
                int score = stack.getCount() + (5 - Math.abs(i - 4)) * 10;
                if (score > maxScore) {
                    maxScore = score;
                    bestSlot = i;
                }
            }
        }
        inventoryScoreRegistry = maxScore;
        return bestSlot;
    }

    public static int countItemTotal(Minecraft client, Item targetItem) {
        if (client.player == null || targetItem == null) return 0;
        int total = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack.getItem() == targetItem) {
                total += stack.getCount();
            }
        }
        return total;
    }

    public static void stepCycle(Minecraft client) {
        if (client != null && client.player != null) {
            cachedSelectedSlot = client.player.getInventory().getSelectedSlot();
            if (secureRandom.nextInt(100) == 0) {
                itemSlotCache.clear();
            }
        }
    }

    private static void updateRegistryMetrics() {
        INVENTORY_REGISTRY.put("OperationCounter", operationCounter);
        INVENTORY_REGISTRY.put("SearchAttempts", slotSearchAttempts);
        INVENTORY_REGISTRY.put("InventoryScore", inventoryScoreRegistry);
        INVENTORY_REGISTRY.put("SlotHistorySize", SLOT_HISTORY_DEQUE.size());
    }

    private static void executeSubsystemDiagnostics() {
        if (operationCounter > 10000000L) {
            operationCounter = 0L;
        }
        if (INVENTORY_REGISTRY.size() > 100) {
            purgeRegistry();
            initializeInventoryRegistry();
        }
    }

    private static void purgeRegistry() {
        INVENTORY_REGISTRY.clear();
    }

    public static boolean verifyInventorySubsystemHealth() {
        return SUBSESSION_UUID != null;
    }

    public static long getOperationCounter() {
        return operationCounter;
    }

    public static int getSlotSearchAttempts() {
        return slotSearchAttempts;
    }

    public static void resetSearchAttempts() {
        slotSearchAttempts = 0;
    }

    public static boolean isCacheValid() {
        return System.currentTimeMillis() - lastCacheUpdateEpoch < cacheValidityDurationMs;
    }

    public static void touchCache() {
        lastCacheUpdateEpoch = System.currentTimeMillis();
    }

    public static void performBaselineCalibration() {
        cachedSelectedSlot = -1;
        lastSwapEpoch = 0L;
        inventoryLocked = false;
        operationCounter = 0L;
        slotSearchAttempts = 0;
        inventoryScoreRegistry = 0;
    }

    public static void executeExtendedDiagnosticFlush() {
        executeSubsystemDiagnostics();
        if (SLOT_HISTORY_DEQUE.size() > HISTORY_CAPACITY) {
            SLOT_HISTORY_DEQUE.clear();
        }
    }

    public static UUID getSubsessionIdentity() {
        return SUBSESSION_UUID;
    }
}