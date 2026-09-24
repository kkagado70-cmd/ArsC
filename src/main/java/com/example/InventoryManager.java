package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.security.SecureRandom;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InventoryManager {

    public static final String FILE_NAME = "InventoryManager.java";

    public enum SlotSelectionMode {
        DIRECT_ONLY,
        KEY_ONLY,
        DUAL
    }

    public enum InventoryRegion {
        HOTBAR(0, 8),
        MAIN(9, 35),
        ARMOR(36, 39),
        OFFHAND(40, 40);

        public final int start;
        public final int end;

        InventoryRegion(int s, int e) { this.start = s; this.end = e; }
    }

    private static final SecureRandom secureRandom = new SecureRandom();
    private static final Map<String, Object> INVENTORY_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_ID = UUID.randomUUID();
    private static final Map<Item, Integer> itemSlotCache = new HashMap<>();
    private static final Map<Item, Long> cacheTTL = new HashMap<>();
    private static final long CACHE_TTL_MS = 500L;

    private static SlotSelectionMode selectionMode = SlotSelectionMode.DUAL;
    private static int cachedSelectedSlot = -1;
    private static long lastSwapEpoch = 0L;
    private static long minSwapIntervalMs = 5L;
    private static boolean inventoryLocked = false;
    private static int savedSlotBeforeSequence = -1;
    private static long lastInventorySnapshot = 0L;
    private static long totalSwaps = 0L;
    private static int consecutiveSwapCount = 0;
    private static long lastSwapResetEpoch = 0L;
    private static double swapDelayMeanMs = 6.0;
    private static double swapDelayStdDevMs = 3.0;

    static {
        INVENTORY_REGISTRY.put("SubsessionUUID", SUBSESSION_ID);
        INVENTORY_REGISTRY.put("Profile", "Enterprise-InventoryManager-1.21.11");
        INVENTORY_REGISTRY.put("SelectionMode", selectionMode.name());
        INVENTORY_REGISTRY.put("MojmapCompliant", "setSelectedSlot");
    }

    public static boolean selectSlot(Minecraft client, int slot) {
        if (client == null || client.player == null) return false;
        if (inventoryLocked) return false;
        if (slot < 0 || slot > 8) return false;

        long now = System.currentTimeMillis();
        long dynamicDelay = computeSwapDelay();
        if (now - lastSwapEpoch < dynamicDelay) return false;

        applySlotSelection(client, slot);
        cachedSelectedSlot = slot;
        lastSwapEpoch = now;
        totalSwaps++;
        consecutiveSwapCount++;
        lastInventorySnapshot = now;

        INVENTORY_REGISTRY.put("LastSlot", slot);
        INVENTORY_REGISTRY.put("TotalSwaps", totalSwaps);

        return true;
    }

    public static boolean selectSlotImmediate(Minecraft client, int slot) {
        if (client == null || client.player == null) return false;
        if (slot < 0 || slot > 8) return false;

        applySlotSelection(client, slot);
        cachedSelectedSlot = slot;
        lastSwapEpoch = System.currentTimeMillis();
        totalSwaps++;

        return true;
    }

    private static void applySlotSelection(Minecraft client, int slot) {
        switch (selectionMode) {
            case DIRECT_ONLY -> {
                client.player.getInventory().setSelectedSlot(slot);
            }
            case KEY_ONLY -> {
                simulateHotbarKey(client, slot);
            }
            case DUAL -> {
                client.player.getInventory().setSelectedSlot(slot);
                simulateHotbarKey(client, slot);
            }
        }
    }

    private static void simulateHotbarKey(Minecraft client, int slot) {
        if (client.options == null) return;
        if (slot < 0 || slot >= client.options.keyHotbarSlots.length) return;
        client.options.keyHotbarSlots[slot].setDown(true);
        client.options.keyHotbarSlots[slot].setDown(false);
    }

    public static boolean verifySlotSelected(Minecraft client, int slot) {
        if (client == null || client.player == null) return false;
        return client.player.getInventory().getSelectedSlot() == slot;
    }

    public static boolean verifySlotItem(Minecraft client, int slot, Item expectedItem) {
        if (client == null || client.player == null) return false;
        if (slot < 0 || slot > 8) return false;
        ItemStack stack = client.player.getInventory().getItem(slot);
        return !stack.isEmpty() && stack.getItem() == expectedItem;
    }

    public static boolean verifyActiveItem(Minecraft client, Item expectedItem) {
        if (client == null || client.player == null) return false;
        ItemStack held = client.player.getMainHandItem();
        return !held.isEmpty() && held.getItem() == expectedItem;
    }

    public static int findItem(Minecraft client, Item targetItem) {
        if (client == null || client.player == null) return -1;

        long now = System.currentTimeMillis();
        if (itemSlotCache.containsKey(targetItem)) {
            Long ttl = cacheTTL.get(targetItem);
            if (ttl != null && now - ttl < CACHE_TTL_MS) {
                int cached = itemSlotCache.get(targetItem);
                if (cached >= 0 && cached < 9) {
                    ItemStack stack = client.player.getInventory().getItem(cached);
                    if (!stack.isEmpty() && stack.getItem() == targetItem) {
                        return cached;
                    }
                }
            }
        }

        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == targetItem) {
                itemSlotCache.put(targetItem, i);
                cacheTTL.put(targetItem, now);
                return i;
            }
        }

        itemSlotCache.remove(targetItem);
        cacheTTL.remove(targetItem);
        return -1;
    }

    public static int findAnyOf(Minecraft client, Item... items) {
        if (client == null || client.player == null) return -1;
        for (Item item : items) {
            int slot = findItem(client, item);
            if (slot >= 0) return slot;
        }
        return -1;
    }

    public static int findRail(Minecraft client) {
        if (client == null || client.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!stack.isEmpty() && isRailItem(stack.getItem())) {
                return i;
            }
        }
        return -1;
    }

    public static boolean isRailItem(Item item) {
        return item == Items.RAIL
                || item == Items.POWERED_RAIL
                || item == Items.DETECTOR_RAIL
                || item == Items.ACTIVATOR_RAIL;
    }

    public static int findChargedCrossbow(Minecraft client) {
        if (client == null || client.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!stack.isEmpty()
                    && stack.getItem() instanceof CrossbowItem
                    && CrossbowItem.isCharged(stack)) {
                return i;
            }
        }
        return -1;
    }

    public static boolean isChargedCrossbow(Minecraft client, int slot) {
        if (client == null || client.player == null) return false;
        if (slot < 0 || slot > 8) return false;
        ItemStack stack = client.player.getInventory().getItem(slot);
        return !stack.isEmpty()
                && stack.getItem() instanceof CrossbowItem
                && CrossbowItem.isCharged(stack);
    }

    public static int findFireSource(Minecraft client) {
        if (client == null || client.player == null) return -1;
        int flint = findItem(client, Items.FLINT_AND_STEEL);
        if (flint >= 0) return flint;
        return findItem(client, Items.FIRE_CHARGE);
    }

    public static boolean isFireSource(Minecraft client, int slot) {
        if (client == null || client.player == null) return false;
        if (slot < 0 || slot > 8) return false;
        ItemStack stack = client.player.getInventory().getItem(slot);
        if (stack.isEmpty()) return false;
        return stack.is(Items.FLINT_AND_STEEL) || stack.is(Items.FIRE_CHARGE);
    }

    public static int getRemainingDurability(Minecraft client, int slot) {
        if (client == null || client.player == null) return -1;
        if (slot < 0 || slot > 8) return -1;
        ItemStack stack = client.player.getInventory().getItem(slot);
        if (stack.isEmpty()) return -1;
        return stack.getMaxDamage() - stack.getDamageValue();
    }

    public static boolean hasMinDurability(Minecraft client, int slot, int minDurability) {
        int remaining = getRemainingDurability(client, slot);
        return remaining < 0 || remaining >= minDurability;
    }

    public static int getItemCount(Minecraft client, Item item) {
        if (client == null || client.player == null) return 0;
        int total = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        return total;
    }

    public static boolean hasItems(Minecraft client, Item item, int requiredCount) {
        return getItemCount(client, item) >= requiredCount;
    }

    public static boolean validateSequenceInventory(Minecraft client) {
        if (client == null || client.player == null) return false;
        return findRail(client) >= 0
                && findItem(client, Items.TNT_MINECART) >= 0
                && findFireSource(client) >= 0
                && findChargedCrossbow(client) >= 0;
    }

    public static void saveCurrentSlot(Minecraft client) {
        if (client == null || client.player == null) return;
        savedSlotBeforeSequence = client.player.getInventory().getSelectedSlot();
    }

    public static void restoreSavedSlot(Minecraft client) {
        if (savedSlotBeforeSequence < 0 || client == null || client.player == null) return;
        selectSlot(client, savedSlotBeforeSequence);
        savedSlotBeforeSequence = -1;
    }

    public static void invalidateCache() {
        itemSlotCache.clear();
        cacheTTL.clear();
    }

    public static void invalidateCacheEntry(Item item) {
        itemSlotCache.remove(item);
        cacheTTL.remove(item);
    }

    public static void lock() {
        inventoryLocked = true;
        INVENTORY_REGISTRY.put("Locked", true);
    }

    public static void unlock() {
        inventoryLocked = false;
        INVENTORY_REGISTRY.put("Locked", false);
    }

    public static boolean isLocked() {
        return inventoryLocked;
    }

    private static long computeSwapDelay() {
        double raw = swapDelayMeanMs + secureRandom.nextGaussian() * swapDelayStdDevMs;
        return Math.max(1L, (long) raw);
    }

    public static void setSwapDelayParams(double meanMs, double stdDevMs) {
        swapDelayMeanMs = Math.max(1.0, meanMs);
        swapDelayStdDevMs = Math.max(0.0, stdDevMs);
    }

    public static void setSelectionMode(SlotSelectionMode mode) {
        selectionMode = mode;
        INVENTORY_REGISTRY.put("SelectionMode", mode.name());
    }

    public static int getCurrentSlot(Minecraft client) {
        if (client == null || client.player == null) return -1;
        return client.player.getInventory().getSelectedSlot();
    }

    public static ItemStack getCurrentItem(Minecraft client) {
        if (client == null || client.player == null) return ItemStack.EMPTY;
        return client.player.getMainHandItem();
    }

    public static ItemStack getOffhandItem(Minecraft client) {
        if (client == null || client.player == null) return ItemStack.EMPTY;
        return client.player.getOffhandItem();
    }

    public static long getTotalSwaps() {
        return totalSwaps;
    }

    public static int getCachedSelectedSlot() {
        return cachedSelectedSlot;
    }

    public static long getLastSwapEpoch() {
        return lastSwapEpoch;
    }

    public static UUID getSubsessionIdentity() {
        return SUBSESSION_ID;
    }

    public static boolean hasCrossbow(Minecraft client) {
        return findItem(client, Items.CROSSBOW) >= 0;
    }

    public static boolean hasTntMinecart(Minecraft client) {
        if (client == null || client.player == null) return false;
        return findItem(client, Items.TNT_MINECART) >= 0;
    }

    public static int countFireSources(Minecraft client) {
        if (client == null || client.player == null) return 0;
        int count = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack s = client.player.getInventory().getItem(i);
            if (!s.isEmpty() && (s.is(Items.FLINT_AND_STEEL) || s.is(Items.FIRE_CHARGE))) count++;
        }
        return count;
    }

    public static int totalItemCount(Minecraft client, Item item, InventoryRegion region) {
        if (client == null || client.player == null) return 0;
        int count = 0;
        for (int i = region.start; i <= region.end; i++) {
            ItemStack s = client.player.getInventory().getItem(i);
            if (!s.isEmpty() && s.getItem() == item) count += s.getCount();
        }
        return count;
    }

    public static java.util.List<Integer> findAllSlots(Minecraft client, Item item) {
        java.util.List<Integer> slots = new java.util.ArrayList<>();
        if (client == null || client.player == null) return slots;
        for (int i = 0; i < 9; i++) {
            ItemStack s = client.player.getInventory().getItem(i);
            if (!s.isEmpty() && s.getItem() == item) slots.add(i);
        }
        return slots;
    }

    public static int getBestFireSource(Minecraft client) {
        int flintSlot = findItem(client, Items.FLINT_AND_STEEL);
        int chargeSlot = findItem(client, Items.FIRE_CHARGE);
        if (flintSlot >= 0 && hasMinDurability(client, flintSlot, MIN_FLINT_DURABILITY)) return flintSlot;
        if (chargeSlot >= 0) return chargeSlot;
        if (flintSlot >= 0) return flintSlot;
        return -1;
    }

    private static final int MIN_FLINT_DURABILITY = 2;

    public static java.util.Map<String, Integer> buildSlotMap(Minecraft client) {
        java.util.Map<String, Integer> map = new java.util.LinkedHashMap<>();
        map.put("rail",  findRail(client));
        map.put("cart",  findItem(client, Items.TNT_MINECART));
        map.put("fire",  findFireSource(client));
        map.put("xbow",  findChargedCrossbow(client));
        return map;
    }

    public static boolean selectIfNot(Minecraft client, int slot) {
        if (client == null || client.player == null) return false;
        if (client.player.getInventory().getSelectedSlot() == slot) return true;
        return selectSlot(client, slot);
    }

    public static boolean hasMinimumSet(Minecraft client) {
        return validateSequenceInventory(client);
    }

    public static void warmupCache(Minecraft client) {
        if (client == null || client.player == null) return;
        findRail(client);
        findItem(client, Items.TNT_MINECART);
        findFireSource(client);
        findChargedCrossbow(client);
    }

    public static String buildInventoryReport(Minecraft client) {
        if (client == null || client.player == null) return "[null]";
        java.util.Map<String, Integer> slots = buildSlotMap(client);
        return "[INV] rail=" + slots.get("rail")
                + " cart=" + slots.get("cart")
                + " fire=" + slots.get("fire")
                + " xbow=" + slots.get("xbow")
                + " swaps=" + totalSwaps;
    }
}

// Appended
EOF
    public static void update(Minecraft client) {
        if (client == null || client.player == null) return;
        long now = System.currentTimeMillis();
        if (now - lastInventorySnapshot > CACHE_TTL_MS) {
            invalidateCache();
            lastInventorySnapshot = now;
        }
        if (now - lastSwapResetEpoch > 2000L) {
            consecutiveSwapCount = 0;
            lastSwapResetEpoch = now;
        }
    }
}
