package com.example;

import net.minecraft.client.Minecraft;
import java.security.SecureRandom;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

public class InteractionManager {
    public static final String FILE_NAME = "InteractionManager.java";
    private static final SecureRandom secureRandom = new SecureRandom();
    private static final Queue<InteractionPacketTask> packetTaskQueue = new ConcurrentLinkedQueue<>();
    
    private static int attackHoldTicks = 0;
    private static int useHoldTicks = 0;
    private static boolean attackSimulated = false;
    private static boolean useSimulated = false;
    private static long lastPacketExecutionEpoch = 0L;

    private static final Map<String, Object> INTERACTION_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_IDENTITY = UUID.randomUUID();
    private static long globalTaskCounter = 0L;

    static {
        initializeInteractionRegistry();
    }

    private static void initializeInteractionRegistry() {
        INTERACTION_REGISTRY.put("SubsessionUUID", SUBSESSION_IDENTITY);
        INTERACTION_REGISTRY.put("Profile", "HT1-Enterprise-InteractionManager");
        INTERACTION_REGISTRY.put("BypassEngine", "Zero-Direct-Packets-KeySim");
        INTERACTION_REGISTRY.put("InitializationEpoch", System.currentTimeMillis());
        INTERACTION_REGISTRY.put("TotalTasksProcessed", globalTaskCounter);
    }

    public static class InteractionPacketTask {
        public final boolean isAttack;
        public final long executionTimestamp;

        public InteractionPacketTask(boolean isAttack, long delay) {
            this.isAttack = isAttack;
            this.executionTimestamp = System.currentTimeMillis() + delay;
        }
    }

    public static void update(Minecraft client) {
        if (client == null || client.options == null) return;
        processQueue(client);

        if (attackHoldTicks > 0) {
            attackHoldTicks--;
            if (attackHoldTicks == 0) {
                client.options.keyAttack.setDown(false);
                attackSimulated = false;
            }
        }

        if (useHoldTicks > 0) {
            useHoldTicks--;
            if (useHoldTicks == 0) {
                client.options.keyUse.setDown(false);
                useSimulated = false;
            }
        }
    }

    private static void processQueue(Minecraft client) {
        long now = System.currentTimeMillis();
        if (now - lastPacketExecutionEpoch < 25L) return;

        while (!packetTaskQueue.isEmpty() && packetTaskQueue.peek().executionTimestamp <= now) {
            InteractionPacketTask task = packetTaskQueue.poll();
            if (task != null) {
                globalTaskCounter++;
                if (task.isAttack) {
                    executeRawAttack(client);
                } else {
                    executeRawUse(client);
                }
                lastPacketExecutionEpoch = now;
                break;
            }
        }
        updateRegistryState();
    }

    public static void simulateClickUse(Minecraft client) {
        if (client == null || client.options == null) return;
        long jitterDelay = 15 + secureRandom.nextInt(20);
        packetTaskQueue.add(new InteractionPacketTask(false, jitterDelay));
    }

    public static void simulateClickAttack(Minecraft client) {
        if (client == null || client.options == null) return;
        long jitterDelay = 15 + secureRandom.nextInt(20);
        packetTaskQueue.add(new InteractionPacketTask(true, jitterDelay));
    }

    private static void executeRawUse(Minecraft client) {
        if (client.options == null) return;
        useHoldTicks = 1 + secureRandom.nextInt(2);
        client.options.keyUse.setDown(false);
        client.options.keyUse.setDown(true);
        useSimulated = true;
    }

    private static void executeRawAttack(Minecraft client) {
        if (client.options == null || client.player == null) return;
        attackHoldTicks = 1 + secureRandom.nextInt(2);
        client.options.keyAttack.setDown(false);
        client.options.keyAttack.setDown(true);
        attackSimulated = true;
    }

    public static void forceReleaseAll(Minecraft client) {
        if (client == null || client.options == null) return;
        client.options.keyUse.setDown(false);
        client.options.keyAttack.setDown(false);
        attackHoldTicks = 0;
        useHoldTicks = 0;
        attackSimulated = false;
        useSimulated = false;
        packetTaskQueue.clear();
    }

    private static void updateRegistryState() {
        INTERACTION_REGISTRY.put("TotalTasksProcessed", globalTaskCounter);
        INTERACTION_REGISTRY.put("QueueSize", packetTaskQueue.size());
    }

    public static UUID getSubsessionIdentity() {
        return SUBSESSION_IDENTITY;
    }

    public static long getGlobalTaskCounter() {
        return globalTaskCounter;
    }
}