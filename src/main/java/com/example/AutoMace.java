package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Deque;

public class AutoMace extends ClientBase.Module {
    public static final String FILE_NAME = "AutoMace.java";
    public static boolean enabled = false;
    private static final SecureRandom secureRandom = new SecureRandom();

    private static final Map<String, Object> MACE_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_ID = UUID.randomUUID();
    private static final Deque<Double> VELOCITY_QUEUE = new ArrayDeque<>();
    private static final int HISTORY_CAP = 512;

    private static double maxSwingRange = 4.75D;
    private static double maxAimDistance = 22.0D;
    private static double minFallDistance = 1.2D;
    private static float hyperSnapSpeed = 0.98F;
    private static long executionTicks = 0L;
    private static boolean windChargeDetection = true;
    private static boolean elytraDiveCheck = true;
    private static LivingEntity lockedMaceTarget = null;
    private static int smashCooldown = 0;
    private static boolean antiHeuristicActive = true;
    private static int anomalyCount = 0;
    private static boolean stealthMode = true;
    private static double jitterFactor = 0.008D;
    private static boolean instantSlotSwitch = true;
    private static int sessionSmashCount = 0;
    private static boolean packetOrderSync = true;
    private static long lastSmashEpoch = 0L;
    private static boolean targetPredictionActive = true;
    private static double predictionScalar = 1.2D;
    private static boolean hardwareBypass = true;
    private static boolean profileLocked = false;
    private static double stochasticVariance = 0.04D;
    private static int emergencyResetThreshold = 100;

    static {
        initializeRegistry();
    }

    private static void initializeRegistry() {
        MACE_REGISTRY.put("SubsessionUUID", SUBSESSION_ID);
        MACE_REGISTRY.put("Profile", "Swight-Tier1-AutoMace-FullEnterprise");
        MACE_REGISTRY.put("WindChargeDetection", windChargeDetection);
        MACE_REGISTRY.put("ElytraDiveCheck", elytraDiveCheck);
        MACE_REGISTRY.put("ExecutionTicks", executionTicks);
    }

    public AutoMace() {
        super("AutoMace");
        AutoMace.enabled = false;
    }

    @Override
    public boolean isEnabled() { return enabled; }

    @Override
    public void toggle() {
        enabled = !enabled;
        super.enabled = enabled;
        resetState();
    }

    private static void resetState() {
        lockedMaceTarget = null;
        smashCooldown = 0;
        anomalyCount = 0;
        sessionSmashCount = 0;
        VELOCITY_QUEUE.clear();
        MACE_REGISTRY.clear();
        initializeRegistry();
    }

    @Override
    public void tick(Minecraft client) { onTick(client); }

    public static void onTick(Minecraft client) {
        if (!enabled || client.player == null || client.level == null || !client.player.isAlive()) return;

        executionTicks++;
        if (smashCooldown > 0) smashCooldown--;

        double vY = client.player.getDeltaMovement().y;
        VELOCITY_QUEUE.offerLast(vY);
        if (VELOCITY_QUEUE.size() > HISTORY_CAP) VELOCITY_QUEUE.pollFirst();

        LivingEntity target = evaluateTarget(client);
        if (target != null) {
            evaluateSmash(client, target);
        } else {
            lockedMaceTarget = null;
        }
        updateRegistry();
    }

    private static LivingEntity evaluateTarget(Minecraft client) {
        if (lockedMaceTarget != null) {
            if (lockedMaceTarget.isAlive() && client.player.distanceToSqr(lockedMaceTarget) <= (maxAimDistance * maxAimDistance)) {
                return lockedMaceTarget;
            }
            lockedMaceTarget = null;
        }

        LivingEntity best = null;
        double minDst = (maxAimDistance * maxAimDistance) + 1.0D;
        for (Player p : client.level.players()) {
            if (p == client.player || !p.isAlive() || p.isSpectator() || p.isCreative()) continue;
            double dst = client.player.distanceToSqr(p);
            if (dst > (maxAimDistance * maxAimDistance)) continue;
            if (dst < minDst) { minDst = dst; best = p; }
        }
        if (best != null) lockedMaceTarget = best;
        return lockedMaceTarget;
    }

    private static void evaluateSmash(Minecraft client, LivingEntity target) {
        double fallDist = client.player.fallDistance;
        boolean elytra = client.player.isFallFlying();
        double vY = client.player.getDeltaMovement().y;
        
        boolean windMomentum = windChargeDetection && vY > 0.75D;
        boolean trigger = fallDist >= minFallDistance || (elytraDiveCheck && elytra) || vY < -0.25D || windMomentum;

        if (trigger) {
            int mSlot = findItem(client, Items.MACE);
            if (mSlot != -1) {
                selectSlot(client, mSlot);
                Vec3 center = target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D);
                RotationManager.smoothTo(client, center.add(secureRandom.nextDouble() * jitterFactor, secureRandom.nextDouble() * jitterFactor, secureRandom.nextDouble() * jitterFactor), hyperSnapSpeed);

                double dist = client.player.distanceTo(target);
                float scale = client.player.getAttackStrengthScale(0.0F);

                if (dist <= maxSwingRange && scale >= 0.65F && smashCooldown == 0) {
                    sessionSmashCount++;
                    lastSmashEpoch = System.currentTimeMillis();
                    InteractionManager.simulateClickAttack(client);
                    smashCooldown = 3 + secureRandom.nextInt(3);
                }
            }
        }
    }

    private static int findItem(Minecraft client, Item item) {
        if (client.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (client.player.getInventory().getItem(i).getItem() == item) return i;
        }
        return -1;
    }

    private static void selectSlot(Minecraft client, int slot) {
        if (client.player == null || slot < 0 || slot > 8) return;
        client.player.getInventory().setSelectedSlot(slot);
        if (client.options.keyHotbarSlots[slot] != null) {
            client.options.keyHotbarSlots[slot].setDown(true);
            client.options.keyHotbarSlots[slot].setDown(false);
        }
    }

    private static void updateRegistry() {
        MACE_REGISTRY.put("ExecutionTicks", executionTicks);
        MACE_REGISTRY.put("Locked", lockedMaceTarget != null);
        MACE_REGISTRY.put("SessionSmashes", sessionSmashCount);
    }

    public static boolean verifySubsystemHealth() { return enabled && SUBSESSION_ID != null; }
    public static long getExecutionTicks() { return executionTicks; }
    public static UUID getSubsessionIdentity() { return SUBSESSION_ID; }
}