package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Deque;

public class ShieldBreaker extends ClientBase.Module {
    public static final String FILE_NAME = "ShieldBreaker.java";
    public static boolean enabled = true;

    private enum ShieldState { IDLE, REACTING, SWINGING, COOLDOWN, FOLLOWUP }

    private static ShieldState currentState = ShieldState.IDLE;
    private static int reactionDelay = 0;
    private static int cooldownTicks = 0;
    private static int followUpAttacksRemaining = 0;
    private static LivingEntity lockedShieldTarget = null;
    private static final SecureRandom secureRandom = new SecureRandom();

    private static final Map<String, Object> SHIELD_ENTERPRISE_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_IDENTITY = UUID.randomUUID();
    private static final Deque<Long> STUN_HISTORY = new ArrayDeque<>();
    private static final Deque<Double> VELOCITY_HISTORY = new ArrayDeque<>();
    private static final Deque<Long> RECOVERY_MEMORY = new ArrayDeque<>();
    private static final int HISTORY_MAX_CAPACITY = 2048;

    private static long globalTicks = 0L;
    private static double maxReach = 4.5D;
    private static boolean shieldStunActiveSync = false;
    private static int successiveStuns = 0;
    private static long lastStunEpoch = 0L;
    private static int sessionStunCount = 0;
    private static double currentFatigueLevel = 0.0D;
    private static double fatigueScalar = 0.001D;
    private static boolean errorInjectionActive = true;
    private static double randomMissChance = 0.01D;
    private static int autoCalibrationCounter = 0;
    private static float overshootYawOffset = 0.0f;
    private static float overshootPitchOffset = 0.0f;
    private static int saccadeTimer = 0;

    private static double sessionMetricAlpha = 0.5D;
    private static double sessionMetricBeta = 0.5D;
    private static double sessionMetricGamma = 0.5D;
    private static double sessionMetricDelta = 0.5D;
    private static double sessionMetricEpsilon = 0.5D;
    private static double sessionMetricZeta = 0.5D;
    private static double sessionMetricEta = 0.5D;
    private static double sessionMetricTheta = 0.5D;
    private static double sessionMetricIota = 0.5D;
    private static double sessionMetricKappa = 0.5D;
    private static boolean deepTelemetryAuditActive = true;
    private static int telemetryFlushIntervalTicks = 300;
    private static long lastTelemetryFlushEpoch = 0L;
    private static boolean adaptiveFovScalingActive = true;
    private static double fovExpansionRate = 0.05D;
    private static boolean strictRaycastVerification = true;
    private static double raycastStepPrecision = 0.1D;
    private static boolean kineticInertiaModelActive = true;
    private static double massSimulatedDrag = 0.02D;
    private static boolean rotationalFrictionActive = true;
    private static double frictionCoefficient = 0.04D;

    static {
        initializeRegistry();
    }

    private static void initializeRegistry() {
        SHIELD_ENTERPRISE_REGISTRY.put("SubsessionUUID", SUBSESSION_IDENTITY);
        SHIELD_ENTERPRISE_REGISTRY.put("Profile", "Swight-Tier1-ShieldBreaker-Enterprise");
        SHIELD_ENTERPRISE_REGISTRY.put("State", currentState.name());
        SHIELD_ENTERPRISE_REGISTRY.put("SuccessiveStuns", successiveStuns);
        SHIELD_ENTERPRISE_REGISTRY.put("AlphaMetric", sessionMetricAlpha);
        SHIELD_ENTERPRISE_REGISTRY.put("BetaMetric", sessionMetricBeta);
        SHIELD_ENTERPRISE_REGISTRY.put("GammaMetric", sessionMetricGamma);
        SHIELD_ENTERPRISE_REGISTRY.put("DeltaMetric", sessionMetricDelta);
    }

    public ShieldBreaker() {
        super("ShieldBreaker");
        ShieldBreaker.enabled = true;
        initializeRegistry();
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void toggle() {
        enabled = !enabled;
        super.enabled = enabled;
        hardReset();
    }

    private static void hardReset() {
        currentState = ShieldState.IDLE;
        reactionDelay = 0;
        cooldownTicks = 0;
        followUpAttacksRemaining = 0;
        lockedShieldTarget = null;
        successiveStuns = 0;
        shieldStunActiveSync = false;
        autoCalibrationCounter = 0;
        sessionStunCount = 0;
        currentFatigueLevel = 0.0D;
        overshootYawOffset = 0.0f;
        overshootPitchOffset = 0.0f;
        saccadeTimer = 0;
        STUN_HISTORY.clear();
        VELOCITY_HISTORY.clear();
        RECOVERY_MEMORY.clear();
        SHIELD_ENTERPRISE_REGISTRY.clear();
        initializeRegistry();
    }

    @Override
    public void tick(Minecraft clientRef) {
        onTick(clientRef);
    }

    private static boolean validateWeaponContext(Minecraft clientRef) {
        if (clientRef.player == null) return false;
        ItemStack stack = clientRef.player.getMainHandItem();
        if (stack.isEmpty()) return false;
        String name = stack.getItem().getDescriptionId().toLowerCase();
        return name.contains("sword") || name.contains("axe") || name.contains("trident") || name.contains("mace");
    }

    private static boolean isBlocking(LivingEntity target) {
        if (target == null) return false;
        return target.isUsingItem() && target.getUseItem().getItem() == Items.SHIELD;
    }

    private static boolean verifyLos(Minecraft clientRef, Entity target) {
        if (clientRef.player == null || target == null) return false;
        Vec3 start = clientRef.player.getEyePosition();
        Vec3 end = target.getEyePosition();
        BlockHitResult hit = clientRef.level.clip(
            new ClipContext(
                start,
                end,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                clientRef.player
            )
        );
        return hit.getType() == HitResult.Type.MISS;
    }

    public static boolean isShieldStunActive() {
        return shieldStunActiveSync;
    }

    private static LivingEntity findShieldTarget(Minecraft clientRef) {
        LivingEntity bestTarget = null;
        double minDst = (maxReach * maxReach) + 1.0D;
        for (Entity entity : clientRef.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || living == clientRef.player || !living.isAlive()) continue;
            if (living instanceof Player player && (player.isSpectator() || player.isCreative())) continue;
            double dst = clientRef.player.distanceToSqr(living);
            if (dst > (maxReach * maxReach)) continue;
            if (!verifyLos(clientRef, living)) continue;
            if (isBlocking(living)) {
                if (dst < minDst) {
                    minDst = dst;
                    bestTarget = living;
                }
            }
        }
        return bestTarget;
    }

    private static int findBestAxe(Minecraft clientRef) {
        if (clientRef.player == null) return -1;
        int slot = findItem(clientRef, Items.NETHERITE_AXE);
        if (slot == -1) slot = findItem(clientRef, Items.DIAMOND_AXE);
        if (slot == -1) slot = findItem(clientRef, Items.IRON_AXE);
        if (slot == -1) slot = findItem(clientRef, Items.GOLDEN_AXE);
        if (slot == -1) slot = findItem(clientRef, Items.STONE_AXE);
        if (slot == -1) slot = findItem(clientRef, Items.WOODEN_AXE);
        return slot;
    }

    private static int findBestSword(Minecraft clientRef) {
        if (clientRef.player == null) return -1;
        int slot = findItem(clientRef, Items.NETHERITE_SWORD);
        if (slot == -1) slot = findItem(clientRef, Items.DIAMOND_SWORD);
        if (slot == -1) slot = findItem(clientRef, Items.IRON_SWORD);
        if (slot == -1) slot = findItem(clientRef, Items.GOLDEN_SWORD);
        if (slot == -1) slot = findItem(clientRef, Items.STONE_SWORD);
        if (slot == -1) slot = findItem(clientRef, Items.WOODEN_SWORD);
        return slot;
    }

    private static int findItem(Minecraft clientRef, Item item) {
        if (clientRef.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (clientRef.player.getInventory().getItem(i).getItem() == item) return i;
        }
        return -1;
    }

    private static void smoothAimToTarget(Minecraft clientRef, LivingEntity target) {
        saccadeTimer++;
        if (saccadeTimer > 20 + secureRandom.nextInt(15)) {
            saccadeTimer = 0;
            overshootYawOffset = (float) ((secureRandom.nextDouble() - 0.5) * 0.8D);
            overshootPitchOffset = (float) ((secureRandom.nextDouble() - 0.5) * 0.6D);
        } else {
            overshootYawOffset *= 0.92f;
            overshootPitchOffset *= 0.92f;
            if (Math.abs(overshootYawOffset) < 0.02f) overshootYawOffset = 0.0f;
            if (Math.abs(overshootPitchOffset) < 0.02f) overshootPitchOffset = 0.0f;
        }

        Vec3 center = target.position().add(0.0D, target.getBbHeight() * 0.45D, 0.0D);
        double dist = clientRef.player.distanceTo(target);
        float factor = dist < 2.5D ? 0.88F : 0.95F;
        RotationManager.smoothTo(clientRef, center.add(overshootYawOffset, overshootPitchOffset, 0.0D), factor);
    }

    private static void followUp(Minecraft clientRef, LivingEntity target) {
        int swordSlot = findBestSword(clientRef);
        if (swordSlot != -1) {
            InventoryManager.selectSlot(clientRef, swordSlot);
        }
        followUpAttacksRemaining = 2;
    }

    public static void onTick(Minecraft clientRef) {
        if (!enabled || clientRef.player == null || clientRef.level == null) return;
        if (!clientRef.player.isAlive()) return;

        if (clientRef.player.isFallFlying() || clientRef.player.fallDistance > 1.5F) {
            currentState = ShieldState.IDLE;
            shieldStunActiveSync = false;
            return;
        }

        if (!validateWeaponContext(clientRef)) {
            shieldStunActiveSync = false;
            currentState = ShieldState.IDLE;
            return;
        }

        globalTicks++;
        autoCalibrationCounter++;

        if (autoCalibrationCounter >= 250) {
            autoCalibrationCounter = 0;
            executeAutoCalibrationRoutine();
        }

        VELOCITY_HISTORY.offerLast(clientRef.player.getDeltaMovement().horizontalDistance());
        if (VELOCITY_HISTORY.size() > HISTORY_MAX_CAPACITY) {
            VELOCITY_HISTORY.pollFirst();
        }

        LivingEntity target = findShieldTarget(clientRef);

        if (followUpAttacksRemaining > 0 && target != null) {
            smoothAimToTarget(clientRef, target);
            if (clientRef.player.getAttackStrengthScale(0.0F) >= 0.85F) {
                InteractionManager.simulateClickAttack(clientRef);
                followUpAttacksRemaining--;
            }
            updateRegistryState();
            return;
        } else if (followUpAttacksRemaining > 0 && target == null) {
            followUpAttacksRemaining = 0;
        }

        if (target == null) {
            shieldStunActiveSync = false;
            currentState = ShieldState.IDLE;
            updateRegistryState();
            return;
        }

        switch (currentState) {
            case IDLE:
                if (isBlocking(target)) {
                    reactionDelay = 2 + secureRandom.nextInt(3);
                    shieldStunActiveSync = true;
                    currentState = ShieldState.REACTING;
                    smoothAimToTarget(clientRef, target);
                }
                break;
            case REACTING:
                smoothAimToTarget(clientRef, target);
                if (!isBlocking(target)) {
                    shieldStunActiveSync = false;
                    currentState = ShieldState.IDLE;
                    break;
                }
                reactionDelay--;
                if (reactionDelay <= 0) {
                    int axeSlot = findBestAxe(clientRef);
                    if (axeSlot != -1) {
                        InventoryManager.selectSlot(clientRef, axeSlot);
                        currentState = ShieldState.SWINGING;
                    } else {
                        shieldStunActiveSync = false;
                        currentState = ShieldState.IDLE;
                    }
                }
                break;
            case SWINGING:
                smoothAimToTarget(clientRef, target);
                if (!isBlocking(target)) {
                    shieldStunActiveSync = false;
                    currentState = ShieldState.IDLE;
                    break;
                }
                if (clientRef.player.getAttackStrengthScale(0.0F) >= 0.80F) {
                    if (errorInjectionActive && secureRandom.nextDouble() < randomMissChance) {
                        cooldownTicks = 5;
                        currentState = ShieldState.COOLDOWN;
                        break;
                    }
                    InteractionManager.simulateClickAttack(clientRef);
                    successiveStuns++;
                    sessionStunCount++;
                    currentFatigueLevel = Math.min(1.0D, currentFatigueLevel + fatigueScalar);
                    lastStunEpoch = System.currentTimeMillis();
                    STUN_HISTORY.offerLast(lastStunEpoch);
                    cooldownTicks = 20;
                    currentState = ShieldState.COOLDOWN;
                }
                break;
            case COOLDOWN:
                smoothAimToTarget(clientRef, target);
                cooldownTicks--;
                if (cooldownTicks <= 0) {
                    if (isBlocking(target)) {
                        currentState = ShieldState.SWINGING;
                    } else {
                        followUp(clientRef, target);
                        shieldStunActiveSync = false;
                        currentState = ShieldState.IDLE;
                    }
                }
                break;
            case FOLLOWUP:
                break;
        }
        updateRegistryState();
    }

    private static void executeAutoCalibrationRoutine() {
        maxReach = 4.5D + (secureRandom.nextDouble() - 0.5) * 0.05D;
        currentFatigueLevel = Math.max(0.0D, currentFatigueLevel - 0.1D);
    }

    private static void updateRegistryState() {
        SHIELD_ENTERPRISE_REGISTRY.put("Ticks", globalTicks);
        SHIELD_ENTERPRISE_REGISTRY.put("State", currentState.name());
        SHIELD_ENTERPRISE_REGISTRY.put("SuccessiveStuns", successiveStuns);
        SHIELD_ENTERPRISE_REGISTRY.put("SessionStuns", sessionStunCount);
        SHIELD_ENTERPRISE_REGISTRY.put("FatigueLevel", currentFatigueLevel);
    }

    public static boolean verifySubsystemHealth() {
        return enabled && SUBSESSION_IDENTITY != null;
    }

    public static UUID getSubsessionIdentity() {
        return SUBSESSION_IDENTITY;
    }
}