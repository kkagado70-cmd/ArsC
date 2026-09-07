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
    private static long lastExecutionEpoch = 0L;

    private static final Map<String, Object> INTERACTION_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_ID = UUID.randomUUID();
    private static long globalTaskCounter = 0L;

    static {
        INTERACTION_REGISTRY.put("SubsessionUUID", SUBSESSION_ID);
        INTERACTION_REGISTRY.put("Profile", "Enterprise-InteractionManager");
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
            if (attackHoldTicks == 0) client.options.keyAttack.setDown(false);
        }

        if (useHoldTicks > 0) {
            useHoldTicks--;
            if (useHoldTicks == 0) client.options.keyUse.setDown(false);
        }
    }

    private static void processQueue(Minecraft client) {
        long now = System.currentTimeMillis();
        if (now - lastExecutionEpoch < 15L) return;

        while (!packetTaskQueue.isEmpty() && packetTaskQueue.peek().executionTimestamp <= now) {
            InteractionPacketTask task = packetTaskQueue.poll();
            if (task != null) {
                globalTaskCounter++;
                if (task.isAttack) executeRawAttack(client);
                else executeRawUse(client);
                lastExecutionEpoch = now;
                break;
            }
        }
    }

    public static void simulateClickUse(Minecraft client) {
        if (client == null || client.options == null) return;
        long delay = 5 + secureRandom.nextInt(10);
        packetTaskQueue.add(new InteractionPacketTask(false, delay));
    }

    public static void simulateClickAttack(Minecraft client) {
        if (client == null || client.options == null) return;
        long delay = 5 + secureRandom.nextInt(10);
        packetTaskQueue.add(new InteractionPacketTask(true, delay));
    }

    private static void executeRawUse(Minecraft client) {
        if (client.options == null) return;
        useHoldTicks = 1 + secureRandom.nextInt(3);
        client.options.keyUse.setDown(false);
        client.options.keyUse.setDown(true);
    }

    private static void executeRawAttack(Minecraft client) {
        if (client.options == null || client.player == null) return;
        attackHoldTicks = 1 + secureRandom.nextInt(3);
        client.options.keyAttack.setDown(false);
        client.options.keyAttack.setDown(true);
    }

    public static UUID getSubsessionIdentity() { return SUBSESSION_ID; }
}