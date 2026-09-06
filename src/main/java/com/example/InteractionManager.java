package com.example;

import net.minecraft.client.Minecraft;
import java.security.SecureRandom;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class InteractionManager {
    private static final SecureRandom secureRandom = new SecureRandom();
    private static final Queue<InteractionPacketTask> packetTaskQueue = new ConcurrentLinkedQueue<>();
    
    private static int attackHoldTicks = 0;
    private static int useHoldTicks = 0;
    private static boolean attackSimulated = false;
    private static boolean useSimulated = false;
    private static long interactionCooldownTracker = 0L;
    private static boolean burstModeActive = false;
    private static int burstCounter = 0;

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
        while (!packetTaskQueue.isEmpty() && packetTaskQueue.peek().executionTimestamp <= now) {
            InteractionPacketTask task = packetTaskQueue.poll();
            if (task != null) {
                if (task.isAttack) {
                    executeRawAttack(client);
                } else {
                    executeRawUse(client);
                }
            }
        }
    }

    public static void simulateClickUse(Minecraft client) {
        if (client == null || client.options == null) return;
        if (System.currentTimeMillis() < interactionCooldownTracker) return;

        long jitterDelay = secureRandom.nextInt(15);
        packetTaskQueue.add(new InteractionPacketTask(false, jitterDelay));
        interactionCooldownTracker = System.currentTimeMillis() + 30 + secureRandom.nextInt(25);
    }

    public static void simulateClickAttack(Minecraft client) {
        if (client == null || client.options == null) return;
        if (System.currentTimeMillis() < interactionCooldownTracker) return;

        long jitterDelay = secureRandom.nextInt(20);
        packetTaskQueue.add(new InteractionPacketTask(true, jitterDelay));
        interactionCooldownTracker = System.currentTimeMillis() + 40 + secureRandom.nextInt(30);
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

        if (client.gameMode != null && client.crosshairPickEntity != null) {
            client.gameMode.attack(client.player, client.crosshairPickEntity);
            client.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        }
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

    public static boolean isAttackSimulated() { return attackSimulated; }
    public static boolean isUseSimulated() { return useSimulated; }

    public static void injectArtificialDelay(long ms) {
        interactionCooldownTracker = System.currentTimeMillis() + ms;
    }

    public static boolean verifyInteractionReady() {
        return System.currentTimeMillis() >= interactionCooldownTracker;
    }

    public static void resetManager() {
        attackHoldTicks = 0;
        useHoldTicks = 0;
        attackSimulated = false;
        useSimulated = false;
        interactionCooldownTracker = 0L;
        packetTaskQueue.clear();
    }

    public static void auditInteractionState(Minecraft client) {
        if (client == null) {
            resetManager();
        }
    }

    public static void setCustomCooldown(long cooldown) {
        interactionCooldownTracker = System.currentTimeMillis() + cooldown;
    }

    public static long fetchRemainingCooldown() {
        return Math.max(0L, interactionCooldownTracker - System.currentTimeMillis());
    }

    public static void triggerRapidBurst(Minecraft client, int count) {
        for (int i = 0; i < count; i++) {
            simulateClickAttack(client);
        }
    }

    public static void executeSilentInteraction(Minecraft client, boolean isAttack) {
        if (isAttack) {
            simulateClickAttack(client);
        } else {
            simulateClickUse(client);
        }
    }

    public static void stepCycle(Minecraft client) {
        update(client);
    }

    public static int getQueueSize() {
        return packetTaskQueue.size();
    }

    public static void setBurstMode(boolean active, int count) {
        burstModeActive = active;
        burstCount = count;
    }

    public static boolean isBurstModeActive() {
        return burstModeActive;
    }
}