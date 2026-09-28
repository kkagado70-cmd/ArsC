package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ShieldBreaker {

    public static final String FILE_NAME = "ShieldBreaker.java";
    public static boolean enabled = true;

    private enum ShieldState { IDLE, REACTING, SWAPPING, AIMING, SWINGING, COOLDOWN, FOLLOWUP }

    private static ShieldState currentState = ShieldState.IDLE;
    private static int   reactionDelay      = 0;
    private static int   cooldownTicks      = 0;
    private static int   followUpRemaining  = 0;
    private static int   aimTicks           = 0;
    private static int   savedSlot          = -1;
    private static LivingEntity lockedShieldTarget = null;

    private static final SecureRandom secureRandom = new SecureRandom();
    private static final Map<String, Object> SHIELD_ENTERPRISE_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_IDENTITY = UUID.randomUUID();

    private static final Deque<Long>    STUN_HISTORY      = new ArrayDeque<>();
    private static final Deque<Double>  VELOCITY_HISTORY  = new ArrayDeque<>();
    private static final Deque<Long>    RECOVERY_MEMORY   = new ArrayDeque<>();
    private static final Deque<Double>  AIM_ERROR_YAW_LOG = new ArrayDeque<>();
    private static final Deque<Double>  AIM_ERROR_PCT_LOG = new ArrayDeque<>();
    private static final Deque<Integer> REACTION_LOG      = new ArrayDeque<>();
    private static final Deque<Long>    COOLDOWN_LOG      = new ArrayDeque<>();
    private static final Deque<Double>  FATIGUE_LOG       = new ArrayDeque<>();
    private static final int HISTORY_MAX_CAPACITY = 2048;

    private static long    globalTicks             = 0L;
    private static double  maxReach                = 4.5D;
    private static boolean shieldStunActiveSync    = false;
    private static int     successiveStuns         = 0;
    private static long    lastStunEpoch           = 0L;
    private static int     sessionStunCount        = 0;
    private static double  currentFatigueLevel     = 0.0D;
    private static double  fatigueScalar           = 0.001D;
    private static double  fatigueRecovery         = 0.0005D;
    private static boolean errorInjectionActive    = true;
    private static double  randomMissChance        = 0.01D;
    private static int     autoCalibrationCounter  = 0;
    private static float   overshootYawOffset      = 0.0f;
    private static float   overshootPitchOffset    = 0.0f;
    private static int     saccadeTimer            = 0;
    private static int     maxAimTicks             = 8;
    private static float   aimYawTolerance         = 3.5f;
    private static float   aimPitchTolerance       = 4.0f;
    private static boolean followUpEnabled         = true;
    private static int     followUpCount           = 2;
    private static boolean axePriority             = true;
    private static double  sessionMetricAlpha      = 0.5D;
    private static double  sessionMetricBeta       = 0.5D;
    private static double  sessionMetricGamma      = 0.5D;

    static {
        SHIELD_ENTERPRISE_REGISTRY.put("SubsessionUUID", SUBSESSION_IDENTITY);
        SHIELD_ENTERPRISE_REGISTRY.put("Profile", "ShieldBreaker-SaccadeAim-Enterprise");
        SHIELD_ENTERPRISE_REGISTRY.put("MaxReach", maxReach);
        SHIELD_ENTERPRISE_REGISTRY.put("StunSync", shieldStunActiveSync);
    }

    public static void toggle() {
        enabled = !enabled;
        hardReset();
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
        currentFatigueLevel = Math.max(0.0D, currentFatigueLevel - fatigueRecovery);

        if (autoCalibrationCounter >= 250) {
            autoCalibrationCounter = 0;
            executeAutoCalibrationRoutine();
        }

        pushVelocityHistory(clientRef.player.getDeltaMovement().horizontalDistance());
        RotationManager.samplePlayerGcd(clientRef);

        switch (currentState) {
            case IDLE       -> tickIdle(clientRef);
            case REACTING   -> tickReacting(clientRef);
            case SWAPPING   -> tickSwapping(clientRef);
            case AIMING     -> tickAiming(clientRef);
            case SWINGING   -> tickSwinging(clientRef);
            case COOLDOWN   -> tickCooldown(clientRef);
            case FOLLOWUP   -> tickFollowUp(clientRef);
        }

        updateRegistryState();
    }

    private static void tickIdle(Minecraft clientRef) {
        LivingEntity target = findShieldTarget(clientRef);
        if (target == null) return;
        lockedShieldTarget = target;
        reactionDelay = computeReactionDelay();
        pushReactionLog(reactionDelay);
        currentState = ShieldState.REACTING;
        shieldStunActiveSync = true;
        SHIELD_ENTERPRISE_REGISTRY.put("StunSync", true);
    }

    private static void tickReacting(Minecraft clientRef) {
        if (!validateTarget(clientRef)) { resetToIdle(); return; }
        if (--reactionDelay > 0) return;
        int axeSlot = findBestAxe(clientRef);
        if (axeSlot < 0) { resetToIdle(); return; }
        savedSlot = clientRef.player.getInventory().selected;
        InventoryManager.saveCurrentSlot(clientRef);
        InventoryManager.selectSlot(clientRef, axeSlot);
        currentState = ShieldState.SWAPPING;
    }

    private static void tickSwapping(Minecraft clientRef) {
        if (!validateTarget(clientRef)) { hardReset(); return; }
        aimTicks = 0;
        saccadeTimer = 0;
        overshootYawOffset   = (float)((secureRandom.nextDouble() - 0.5) * 0.9D);
        overshootPitchOffset = (float)((secureRandom.nextDouble() - 0.5) * 0.7D);
        currentState = ShieldState.AIMING;
    }

    private static void tickAiming(Minecraft clientRef) {
        if (!validateTarget(clientRef)) { hardReset(); return; }
        aimTicks++;

        updateSaccade();
        Vec3 center = lockedShieldTarget.position().add(0.0D, lockedShieldTarget.getBbHeight() * 0.45D, 0.0D)
            .add(overshootYawOffset * 0.01, overshootPitchOffset * 0.01, 0);

        double dist   = clientRef.player.distanceTo(lockedShieldTarget);
        float  factor = dist < 2.5D ? 0.88F : 0.95F;

        RotationManager.setEasingMode(RotationManager.EasingMode.SWIGHT_HIGH_SENS);
        RotationManager.smoothTo(clientRef, center, factor);

        double ye = Math.abs(RotationManager.computeYawError(clientRef, center));
        double pe = Math.abs(RotationManager.computePitchError(clientRef, center));
        pushAimErrorLog(ye, pe);

        boolean aligned = RotationManager.isAligned(clientRef, center, aimYawTolerance, aimPitchTolerance);
        if (aligned || aimTicks >= maxAimTicks) {
            if (!aligned) RotationManager.snapTo(clientRef, center);
            aimTicks = 0;
            currentState = ShieldState.SWINGING;
        }
    }

    private static void tickSwinging(Minecraft clientRef) {
        if (!validateTarget(clientRef)) { hardReset(); return; }

        float strength = clientRef.player.getAttackStrengthScale(0.5f);
        if (strength < 0.80f) return;

        if (errorInjectionActive && secureRandom.nextDouble() < randomMissChance) {
            cooldownTicks = 6 + secureRandom.nextInt(5);
            currentState  = ShieldState.COOLDOWN;
            hardResetInventory(clientRef);
            return;
        }

        InteractionManager.simulateClickAttack(clientRef);
        clientRef.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);

        long now = System.currentTimeMillis();
        pushStunHistory(now);
        lastStunEpoch = now;
        successiveStuns++;
        sessionStunCount++;
        currentFatigueLevel = Math.min(1.0D, currentFatigueLevel + fatigueScalar);

        if (followUpEnabled && followUpCount > 0) {
            followUpRemaining = followUpCount;
            int swordSlot = findBestSword(clientRef);
            if (swordSlot >= 0) InventoryManager.selectSlot(clientRef, swordSlot);
            currentState = ShieldState.FOLLOWUP;
        } else {
            int baseCd = 14 + secureRandom.nextInt(6);
            cooldownTicks = (int)(baseCd + currentFatigueLevel * 4.0D);
            pushCooldownLog(cooldownTicks);
            currentState = ShieldState.COOLDOWN;
            hardResetInventory(clientRef);
        }
    }

    private static void tickFollowUp(Minecraft clientRef) {
        if (followUpRemaining <= 0) {
            int baseCd = 14 + secureRandom.nextInt(6);
            cooldownTicks = (int)(baseCd + currentFatigueLevel * 4.0D);
            pushCooldownLog(cooldownTicks);
            currentState = ShieldState.COOLDOWN;
            hardResetInventory(clientRef);
            return;
        }
        if (!clientRef.player.isAlive()) { hardReset(); return; }
        LivingEntity t = lockedShieldTarget;
        if (t != null && t.isAlive() && clientRef.player.distanceTo(t) <= maxReach) {
            float strength = clientRef.player.getAttackStrengthScale(0.5f);
            if (strength >= 0.75f) {
                InteractionManager.simulateClickAttack(clientRef);
                clientRef.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                followUpRemaining--;
            }
        } else {
            followUpRemaining = 0;
        }
    }

    private static void tickCooldown(Minecraft clientRef) {
        if (--cooldownTicks <= 0) resetToIdle();
    }

    private static void updateSaccade() {
        saccadeTimer++;
        if (saccadeTimer > 18 + secureRandom.nextInt(12)) {
            saccadeTimer = 0;
            overshootYawOffset   = (float)((secureRandom.nextDouble() - 0.5) * 0.8D);
            overshootPitchOffset = (float)((secureRandom.nextDouble() - 0.5) * 0.6D);
        } else {
            overshootYawOffset   *= 0.92f;
            overshootPitchOffset *= 0.92f;
            if (Math.abs(overshootYawOffset)   < 0.02f) overshootYawOffset   = 0.0f;
            if (Math.abs(overshootPitchOffset) < 0.02f) overshootPitchOffset = 0.0f;
        }
    }

    private static boolean validateTarget(Minecraft clientRef) {
        if (lockedShieldTarget == null || !lockedShieldTarget.isAlive()) return false;
        if (!isBlocking(lockedShieldTarget)) return false;
        if (clientRef.player.distanceTo(lockedShieldTarget) > maxReach + 1.0D) return false;
        return true;
    }

    private static LivingEntity findShieldTarget(Minecraft clientRef) {
        LivingEntity best   = null;
        double minDst       = (maxReach * maxReach) + 1.0D;
        for (Entity e : clientRef.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity living) || living == clientRef.player || !living.isAlive()) continue;
            if (living instanceof Player p && (p.isSpectator() || p.isCreative())) continue;
            double dst = clientRef.player.distanceToSqr(living);
            if (dst > (maxReach * maxReach)) continue;
            if (!verifyLos(clientRef, living)) continue;
            if (!isBlocking(living)) continue;
            if (dst < minDst) { minDst = dst; best = living; }
        }
        return best;
    }

    private static boolean isBlocking(LivingEntity target) {
        if (target == null) return false;
        return target.isUsingItem() && target.getUseItem().getItem() == Items.SHIELD;
    }

    private static boolean verifyLos(Minecraft clientRef, Entity target) {
        if (clientRef.player == null || target == null || clientRef.level == null) return false;
        Vec3 start = clientRef.player.getEyePosition();
        Vec3 end   = target.getEyePosition();
        BlockHitResult hit = clientRef.level.clip(
            new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, clientRef.player));
        return hit.getType() == HitResult.Type.MISS;
    }

    private static boolean validateWeaponContext(Minecraft clientRef) {
        if (clientRef.player == null) return false;
        ItemStack s = clientRef.player.getMainHandItem();
        if (s.isEmpty()) return true;
        String name = s.getItem().getDescriptionId().toLowerCase();
        return name.contains("sword") || name.contains("axe") || name.contains("trident") || name.contains("mace") || s.isEmpty();
    }

    private static int findBestAxe(Minecraft clientRef) {
        Item[] axes = { Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE, Items.GOLDEN_AXE, Items.STONE_AXE, Items.WOODEN_AXE };
        for (Item ax : axes) { int s = findItemSlot(clientRef, ax); if (s >= 0) return s; }
        return -1;
    }

    private static int findBestSword(Minecraft clientRef) {
        Item[] swords = { Items.NETHERITE_SWORD, Items.DIAMOND_SWORD, Items.IRON_SWORD, Items.GOLDEN_SWORD, Items.STONE_SWORD, Items.WOODEN_SWORD };
        for (Item sw : swords) { int s = findItemSlot(clientRef, sw); if (s >= 0) return s; }
        return -1;
    }

    private static int findItemSlot(Minecraft clientRef, Item item) {
        if (clientRef.player == null) return -1;
        for (int i = 0; i < 9; i++) { if (clientRef.player.getInventory().getItem(i).getItem() == item) return i; }
        return -1;
    }

    private static int computeReactionDelay() {
        int base = 1 + secureRandom.nextInt(3);
        if (secureRandom.nextFloat() < 0.10f) base++;
        return Math.max(1, base);
    }

    private static void executeAutoCalibrationRoutine() {
        randomMissChance  = Math.max(0.005D, randomMissChance + (secureRandom.nextDouble() - 0.5) * 0.002D);
        currentFatigueLevel = Math.max(0.0D, currentFatigueLevel - 0.05D);
        sessionMetricAlpha  = 0.48D + secureRandom.nextDouble() * 0.04D;
        SHIELD_ENTERPRISE_REGISTRY.put("AutoCalibrated", System.currentTimeMillis());
    }

    private static void updateRegistryState() {
        SHIELD_ENTERPRISE_REGISTRY.put("State",         currentState.name());
        SHIELD_ENTERPRISE_REGISTRY.put("StunSync",      shieldStunActiveSync);
        SHIELD_ENTERPRISE_REGISTRY.put("SessionStuns",  sessionStunCount);
        SHIELD_ENTERPRISE_REGISTRY.put("FatigueLevel",  currentFatigueLevel);
        SHIELD_ENTERPRISE_REGISTRY.put("GlobalTicks",   globalTicks);
    }

    private static void pushVelocityHistory(double v) {
        if (VELOCITY_HISTORY.size() >= HISTORY_MAX_CAPACITY) VELOCITY_HISTORY.pollFirst();
        VELOCITY_HISTORY.offerLast(v);
    }

    private static void pushStunHistory(long t) {
        if (STUN_HISTORY.size() >= HISTORY_MAX_CAPACITY) STUN_HISTORY.pollFirst();
        STUN_HISTORY.offerLast(t);
    }

    private static void pushAimErrorLog(double ye, double pe) {
        if (AIM_ERROR_YAW_LOG.size() >= HISTORY_MAX_CAPACITY) AIM_ERROR_YAW_LOG.pollFirst();
        if (AIM_ERROR_PCT_LOG.size() >= HISTORY_MAX_CAPACITY) AIM_ERROR_PCT_LOG.pollFirst();
        AIM_ERROR_YAW_LOG.offerLast(ye);
        AIM_ERROR_PCT_LOG.offerLast(pe);
    }

    private static void pushReactionLog(int r) {
        if (REACTION_LOG.size() >= HISTORY_MAX_CAPACITY) REACTION_LOG.pollFirst();
        REACTION_LOG.offerLast(r);
    }

    private static void pushCooldownLog(int c) {
        if (COOLDOWN_LOG.size() >= HISTORY_MAX_CAPACITY) COOLDOWN_LOG.pollFirst();
        COOLDOWN_LOG.offerLast((long) c);
    }

    private static void hardResetInventory(Minecraft clientRef) {
        if (savedSlot >= 0 && savedSlot < 9 && clientRef != null && clientRef.player != null) {
            InventoryManager.restoreSavedSlot(clientRef);
        }
        savedSlot = -1;
    }

    private static void resetToIdle() {
        currentState         = ShieldState.IDLE;
        reactionDelay        = 0;
        cooldownTicks        = 0;
        followUpRemaining    = 0;
        aimTicks             = 0;
        lockedShieldTarget   = null;
        shieldStunActiveSync = false;
        savedSlot            = -1;
        SHIELD_ENTERPRISE_REGISTRY.put("StunSync", false);
    }

    public static void hardReset() {
        resetToIdle();
        currentFatigueLevel = 0.0D;
        successiveStuns     = 0;
        autoCalibrationCounter = 0;
        sessionStunCount    = 0;
        overshootYawOffset  = 0.0f;
        overshootPitchOffset = 0.0f;
        saccadeTimer        = 0;
        STUN_HISTORY.clear();
        VELOCITY_HISTORY.clear();
        RECOVERY_MEMORY.clear();
        AIM_ERROR_YAW_LOG.clear();
        AIM_ERROR_PCT_LOG.clear();
        SHIELD_ENTERPRISE_REGISTRY.clear();
        SHIELD_ENTERPRISE_REGISTRY.put("SubsessionUUID", SUBSESSION_IDENTITY);
        SHIELD_ENTERPRISE_REGISTRY.put("Profile", "ShieldBreaker-SaccadeAim-Enterprise");
    }

    private static void initializeRegistry() {
        SHIELD_ENTERPRISE_REGISTRY.put("MaxReach", maxReach);
        SHIELD_ENTERPRISE_REGISTRY.put("StunSync", shieldStunActiveSync);
    }

    public static boolean isShieldStunActive()     { return shieldStunActiveSync; }
    public static int     getSessionStunCount()    { return sessionStunCount; }
    public static double  getFatigueLevel()        { return currentFatigueLevel; }
    public static int     getSuccessiveStuns()     { return successiveStuns; }
    public static long    getLastStunEpoch()       { return lastStunEpoch; }
    public static String  getCurrentStateName()    { return currentState.name(); }
    public static void    setMaxReach(double r)    { maxReach = r; }
    public static void    setFollowUp(boolean b, int count) { followUpEnabled = b; followUpCount = count; }
    public static void    setAimTolerances(float yt, float pt) { aimYawTolerance = yt; aimPitchTolerance = pt; }
    public static UUID    getSubsessionIdentity()  { return SUBSESSION_IDENTITY; }

    /**
     * Registra este módulo no ClientTickEvents.END_CLIENT_TICK do Fabric.
     * Chamar uma vez durante a inicialização do mod (ex: ClientModInitializer.onInitializeClient()).
     *
     * Exemplo:
     *   ShieldBreaker.register();
     */
    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(ShieldBreaker::onTick);
    }

}