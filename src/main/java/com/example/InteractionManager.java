package com.example;

import net.minecraft.client.Minecraft;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

public class InteractionManager {

    public static final String FILE_NAME = "InteractionManager.java";

    public enum InteractionType {
        USE,
        ATTACK,
        USE_HOLD,
        ATTACK_HOLD
    }

    public enum InteractionPriority {
        LOW(0), NORMAL(1), HIGH(2), IMMEDIATE(3);
        public final int level;
        InteractionPriority(int l) { this.level = l; }
    }

    public static final class InteractionTask {
        public final InteractionType type;
        public final InteractionPriority priority;
        public final long executionTimestamp;
        public final int holdTicks;
        private boolean consumed;

        public InteractionTask(InteractionType type, InteractionPriority priority, long delayMs, int holdTicks) {
            this.type = type;
            this.priority = priority;
            this.executionTimestamp = System.currentTimeMillis() + delayMs;
            this.holdTicks = Math.max(1, holdTicks);
            this.consumed = false;
        }

        public boolean isReady() {
            return System.currentTimeMillis() >= executionTimestamp;
        }

        public boolean isConsumed() {
            return consumed;
        }

        public void markConsumed() {
            this.consumed = true;
        }
    }

    private static final SecureRandom secureRandom = new SecureRandom();
    private static final Map<String, Object> INTERACTION_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_ID = UUID.randomUUID();
    private static final ConcurrentLinkedQueue<InteractionTask> taskQueue = new ConcurrentLinkedQueue<>();
    private static final Deque<Long> EXECUTION_HISTORY = new ArrayDeque<>();
    private static final int HISTORY_CAP = 256;

    private static int attackHoldTicks = 0;
    private static int useHoldTicks = 0;
    private static long lastExecutionEpoch = 0L;
    private static long globalTaskCounter = 0L;
    private static int processedThisTick = 0;
    private static final int MAX_PER_TICK = 1;
    private static long minIntervalMs = 4L;
    private static boolean rateLimitActive = true;
    private static boolean interactionLocked = false;
    private static int consecutiveUseCount = 0;
    private static int consecutiveAttackCount = 0;
    private static long lastUseTimestamp = 0L;
    private static long lastAttackTimestamp = 0L;
    private static double gaussianMeanDelayMs = 6.0;
    private static double gaussianStdDevMs = 2.5;

    static {
        INTERACTION_REGISTRY.put("SubsessionUUID", SUBSESSION_ID);
        INTERACTION_REGISTRY.put("Profile", "Enterprise-InteractionManager-KeySim");
        INTERACTION_REGISTRY.put("KeySimMode", "HOTBAR_AND_USE");
        INTERACTION_REGISTRY.put("GCDAware", true);
    }

    public static void update(Minecraft client) {
        if (client == null || client.options == null) return;
        processedThisTick = 0;

        decayHoldStates(client);
        processQueue(client);
    }

    private static void decayHoldStates(Minecraft client) {
        if (attackHoldTicks > 0) {
            attackHoldTicks--;
            if (attackHoldTicks == 0) {
                client.options.keyAttack.setDown(false);
            }
        }
        if (useHoldTicks > 0) {
            useHoldTicks--;
            if (useHoldTicks == 0) {
                client.options.keyUse.setDown(false);
            }
        }
    }

    private static void processQueue(Minecraft client) {
        if (interactionLocked) return;
        long now = System.currentTimeMillis();

        if (rateLimitActive && now - lastExecutionEpoch < minIntervalMs) return;

        while (!taskQueue.isEmpty() && processedThisTick < MAX_PER_TICK) {
            InteractionTask task = taskQueue.peek();
            if (task == null) break;
            if (!task.isReady()) break;
            taskQueue.poll();
            if (task.isConsumed()) continue;

            task.markConsumed();
            globalTaskCounter++;
            processedThisTick++;

            executeTask(client, task);
            lastExecutionEpoch = now;
            pushExecutionHistory(now);
            break;
        }
    }

    private static void executeTask(Minecraft client, InteractionTask task) {
        switch (task.type) {
            case USE -> executeUse(client, task.holdTicks);
            case ATTACK -> executeAttack(client, task.holdTicks);
            case USE_HOLD -> executeUseHold(client, task.holdTicks);
            case ATTACK_HOLD -> executeAttackHold(client, task.holdTicks);
        }
    }

    private static void executeUse(Minecraft client, int holdTicks) {
        if (client.options == null) return;
        useHoldTicks = holdTicks;
        client.options.keyUse.setDown(true);
        lastUseTimestamp = System.currentTimeMillis();
        consecutiveUseCount++;
        INTERACTION_REGISTRY.put("LastUse", lastUseTimestamp);
        INTERACTION_REGISTRY.put("ConsecutiveUse", consecutiveUseCount);
    }

    private static void executeAttack(Minecraft client, int holdTicks) {
        if (client.options == null || client.player == null) return;
        attackHoldTicks = holdTicks;
        client.options.keyAttack.setDown(true);
        lastAttackTimestamp = System.currentTimeMillis();
        consecutiveAttackCount++;
        INTERACTION_REGISTRY.put("LastAttack", lastAttackTimestamp);
        INTERACTION_REGISTRY.put("ConsecutiveAttack", consecutiveAttackCount);
    }

    private static void executeUseHold(Minecraft client, int holdTicks) {
        if (client.options == null) return;
        useHoldTicks = Math.max(useHoldTicks, holdTicks);
        client.options.keyUse.setDown(true);
        lastUseTimestamp = System.currentTimeMillis();
    }

    private static void executeAttackHold(Minecraft client, int holdTicks) {
        if (client.options == null || client.player == null) return;
        attackHoldTicks = Math.max(attackHoldTicks, holdTicks);
        client.options.keyAttack.setDown(true);
        lastAttackTimestamp = System.currentTimeMillis();
    }

    public static void simulateClickUse(Minecraft client) {
        if (client == null || client.options == null || interactionLocked) return;
        long delay = computeGaussianDelay();
        taskQueue.add(new InteractionTask(InteractionType.USE, InteractionPriority.NORMAL, delay, 1));
    }

    public static void simulateClickUse(Minecraft client, InteractionPriority priority) {
        if (client == null || client.options == null || interactionLocked) return;
        long delay = priority == InteractionPriority.IMMEDIATE ? 0L : computeGaussianDelay();
        taskQueue.add(new InteractionTask(InteractionType.USE, priority, delay, 1));
    }

    public static void simulateClickAttack(Minecraft client) {
        if (client == null || client.options == null || interactionLocked) return;
        long delay = computeGaussianDelay();
        taskQueue.add(new InteractionTask(InteractionType.ATTACK, InteractionPriority.NORMAL, delay, 1));
    }

    public static void simulateClickAttack(Minecraft client, InteractionPriority priority) {
        if (client == null || client.options == null || interactionLocked) return;
        long delay = priority == InteractionPriority.IMMEDIATE ? 0L : computeGaussianDelay();
        taskQueue.add(new InteractionTask(InteractionType.ATTACK, priority, delay, 1));
    }

    public static void simulateUseHold(Minecraft client, int ticks) {
        if (client == null || client.options == null || interactionLocked) return;
        taskQueue.add(new InteractionTask(InteractionType.USE_HOLD, InteractionPriority.HIGH, 0L, ticks));
    }

    public static void simulateAttackHold(Minecraft client, int ticks) {
        if (client == null || client.options == null || interactionLocked) return;
        taskQueue.add(new InteractionTask(InteractionType.ATTACK_HOLD, InteractionPriority.HIGH, 0L, ticks));
    }

    public static void executeImmediateUse(Minecraft client) {
        if (client == null || client.options == null) return;
        executeUse(client, 1);
        lastExecutionEpoch = System.currentTimeMillis();
    }

    public static void releaseAll(Minecraft client) {
        if (client == null || client.options == null) return;
        attackHoldTicks = 0;
        useHoldTicks = 0;
        client.options.keyAttack.setDown(false);
        client.options.keyUse.setDown(false);
        consecutiveUseCount = 0;
        consecutiveAttackCount = 0;
    }

    public static void flushQueue() {
        taskQueue.clear();
    }

    public static void flushAndRelease(Minecraft client) {
        flushQueue();
        if (client != null) releaseAll(client);
    }

    private static long computeGaussianDelay() {
        double raw = gaussianMeanDelayMs + secureRandom.nextGaussian() * gaussianStdDevMs;
        return Math.max(0L, (long) raw);
    }

    private static void pushExecutionHistory(long timestamp) {
        if (EXECUTION_HISTORY.size() >= HISTORY_CAP) EXECUTION_HISTORY.pollFirst();
        EXECUTION_HISTORY.offerLast(timestamp);
    }

    public static boolean isUseActive() {
        return useHoldTicks > 0;
    }

    public static boolean isAttackActive() {
        return attackHoldTicks > 0;
    }

    public static boolean isQueueEmpty() {
        return taskQueue.isEmpty();
    }

    public static int getQueueSize() {
        return taskQueue.size();
    }

    public static long getLastExecutionEpoch() {
        return lastExecutionEpoch;
    }

    public static boolean isRecentUse(long windowMs) {
        return System.currentTimeMillis() - lastUseTimestamp < windowMs;
    }

    public static boolean isRecentAttack(long windowMs) {
        return System.currentTimeMillis() - lastAttackTimestamp < windowMs;
    }

    public static void lock() {
        interactionLocked = true;
    }

    public static void unlock() {
        interactionLocked = false;
    }

    public static boolean isLocked() {
        return interactionLocked;
    }

    public static void setGaussianDelay(double meanMs, double stdDevMs) {
        gaussianMeanDelayMs = Math.max(0.0, meanMs);
        gaussianStdDevMs = Math.max(0.0, stdDevMs);
    }

    public static void setMinIntervalMs(long intervalMs) {
        minIntervalMs = Math.max(1L, intervalMs);
    }

    public static void setRateLimitActive(boolean active) {
        rateLimitActive = active;
    }

    public static long getTotalTasksProcessed() {
        return globalTaskCounter;
    }

    public static int getConsecutiveUseCount() {
        return consecutiveUseCount;
    }

    public static int getConsecutiveAttackCount() {
        return consecutiveAttackCount;
    }

    public static void resetCounters() {
        consecutiveUseCount = 0;
        consecutiveAttackCount = 0;
        INTERACTION_REGISTRY.put("ConsecutiveUse", 0);
        INTERACTION_REGISTRY.put("ConsecutiveAttack", 0);
    }

    public static UUID getSubsessionIdentity() {
        return SUBSESSION_ID;
    }
}

    public static final class InteractionWindow {
        public final long openEpoch;
        public final long durationMs;
        private boolean used;

        public InteractionWindow(long durationMs) {
            this.openEpoch = System.currentTimeMillis();
            this.durationMs = durationMs;
            this.used = false;
        }

        public boolean isOpen() { return !used && System.currentTimeMillis() - openEpoch < durationMs; }
        public void consume() { this.used = true; }
    }

    private static final java.util.Deque<InteractionWindow> WINDOW_STACK = new java.util.ArrayDeque<>();

    public static InteractionWindow openWindow(long durationMs) {
        InteractionWindow w = new InteractionWindow(durationMs);
        WINDOW_STACK.offerLast(w);
        return w;
    }

    public static boolean isWindowOpen() {
        while (!WINDOW_STACK.isEmpty()) {
            InteractionWindow w = WINDOW_STACK.peekFirst();
            if (w.isOpen()) return true;
            WINDOW_STACK.pollFirst();
        }
        return false;
    }

    public static void drainStaleWindows() {
        WINDOW_STACK.removeIf(w -> !w.isOpen());
    }

    public static void enqueueDelayed(Minecraft client, InteractionType type, long delayMs) {
        if (client == null || interactionLocked) return;
        taskQueue.add(new InteractionTask(type, InteractionPriority.NORMAL, delayMs, 1));
    }

    public static void enqueueImmediate(Minecraft client, InteractionType type) {
        if (client == null || interactionLocked) return;
        taskQueue.add(new InteractionTask(type, InteractionPriority.IMMEDIATE, 0L, 1));
    }

    public static boolean drainNextTask(Minecraft client) {
        if (client == null || taskQueue.isEmpty()) return false;
        InteractionTask task = taskQueue.poll();
        if (task == null || task.isConsumed()) return false;
        task.markConsumed();
        executeTask(client, task);
        return true;
    }

    public static long peekNextReadyDelay() {
        InteractionTask t = taskQueue.peek();
        if (t == null) return Long.MAX_VALUE;
        return Math.max(0L, t.executionTimestamp - System.currentTimeMillis());
    }

    public static Map<InteractionType, Integer> getQueueCountByType() {
        Map<InteractionType, Integer> counts = new java.util.EnumMap<>(InteractionType.class);
        for (InteractionType t : InteractionType.values()) counts.put(t, 0);
        for (InteractionTask task : taskQueue) {
            counts.merge(task.type, 1, Integer::sum);
        }
        return counts;
    }

    public static boolean isAnyHoldActive() {
        return attackHoldTicks > 0 || useHoldTicks > 0;
    }

    public static int getTotalHoldTicksRemaining() {
        return attackHoldTicks + useHoldTicks;
    }

    public static String buildStateString() {
        return "[IM]"
                + " queue=" + taskQueue.size()
                + " useHold=" + useHoldTicks
                + " attackHold=" + attackHoldTicks
                + " locked=" + interactionLocked
                + " totalProcessed=" + globalTaskCounter;
    }

    public static UUID getSubsessionId() { return SUBSESSION_ID; }
