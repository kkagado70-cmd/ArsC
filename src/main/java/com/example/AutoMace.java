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
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

public class AutoMace {

    public static final String FILE_NAME = "AutoMace.java";
    public static boolean enabled = false;

    private static final SecureRandom secureRandom = new SecureRandom();
    private static final UUID SUBSESSION_IDENTITY  = UUID.randomUUID();

    private static final Deque<Double> VELOCITY_VECTOR_QUEUE = new ArrayDeque<>();
    private static final Deque<Long>   SMASH_TIMING_QUEUE    = new ArrayDeque<>();
    private static final int HISTORY_MAX = 256;

    private static double  maxSwingRange        = 7.0D;
    private static double  maxAimDistance       = 30.0D;
    private static double  minFallDistance      = 1.5D;
    private static float   hyperSnapSpeed       = 0.99F;
    private static long    executionTicks       = 0L;
    private static LivingEntity lockedMaceTarget = null;
    private static int     smashCooldown        = 0;
    private static boolean windChargeDetection  = true;
    private static boolean elytraDiveCheck      = true;
    private static double  currentFatigueLevel  = 0.0D;
    private static final double fatigueScalar   = 0.001D;
    private static final double fatigueRecovery = 0.0003D;
    private static double  randomMissProbability = 0.01D;

    private static double peakY     = Double.NEGATIVE_INFINITY;
    private static boolean hasPeaked = false;
    private static int     aimTicks  = 0;
    private static int     trackTicks = 0;
    private static boolean isTracking = false;
    private static int     savedSlot  = -1;
    private static float   aimYawTolerance   = 4.5f;
    private static float   aimPitchTolerance = 5.0f;
    private static int     maxAimTicksVal    = 10;

    public static void toggle() {
        enabled = !enabled;
        if (!enabled) {
            lockedMaceTarget = null;
            isTracking       = false;
            aimTicks         = 0;
            trackTicks       = 0;
            executionTicks   = 0L;
            peakY            = Double.NEGATIVE_INFINITY;
            hasPeaked        = false;
            VELOCITY_VECTOR_QUEUE.clear();
            SMASH_TIMING_QUEUE.clear();
        }
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(AutoMace::onTick);
    }

    public static void onTick(Minecraft client) {
        if (!enabled || client.player == null || client.level == null || !client.player.isAlive()) return;

        executionTicks++;
        currentFatigueLevel = Math.max(0.0D, currentFatigueLevel - fatigueRecovery);

        if (smashCooldown > 0) smashCooldown--;

        RotationManager.samplePlayerGcd(client);

        double vY = client.player.getDeltaMovement().y;
        if (VELOCITY_VECTOR_QUEUE.size() >= HISTORY_MAX) VELOCITY_VECTOR_QUEUE.pollFirst();
        VELOCITY_VECTOR_QUEUE.offerLast(vY);

        trackPeak(client);

        lockedMaceTarget = null;
        double minDst = (maxAimDistance * maxAimDistance) + 1.0D;
        for (Player p : client.level.players()) {
            if (p == client.player || !p.isAlive() || p.isSpectator() || p.isCreative()) continue;
            double dst = client.player.distanceToSqr(p);
            if (dst > (maxAimDistance * maxAimDistance)) continue;
            if (!verifyLineOfSight(client, p)) continue;
            if (dst < minDst) { minDst = dst; lockedMaceTarget = p; }
        }

        if (lockedMaceTarget == null) { isTracking = false; return; }

        double fallDist    = client.player.fallDistance;
        boolean elytra     = client.player.isFallFlying();
        boolean windMoment = windChargeDetection && vY > 0.75D;
        boolean peaked     = hasPeaked || (peakY - client.player.getY() >= minFallDistance);
        boolean diving     = (!client.player.onGround() && peaked && vY < -0.05D)
                          || fallDist >= minFallDistance
                          || (elytraDiveCheck && elytra)
                          || windMoment;

        if (!diving) { isTracking = false; return; }

        int mSlot = -1;
        for (int i = 0; i < 9; i++) {
            if (client.player.getInventory().getItem(i).getItem() == Items.MACE) { mSlot = i; break; }
        }
        if (mSlot < 0) return;

        if (client.player.getInventory().getSelectedSlot() != mSlot) {
            if (savedSlot < 0) savedSlot = client.player.getInventory().getSelectedSlot();
            InventoryManager.selectSlot(client, mSlot);
            return;
        }

        Vec3 center = lockedMaceTarget.position().add(0.0D, lockedMaceTarget.getBbHeight() * 0.45D, 0.0D);

        if (!isTracking) {
            aimTicks   = 0;
            trackTicks = 0;
            isTracking = true;
        }

        aimTicks++;
        trackTicks++;

        double aimErr = Math.hypot(
            RotationManager.computeYawError(client, center),
            RotationManager.computePitchError(client, center)
        );

        float factor = aimErr > 20.0D ? hyperSnapSpeed
                     : aimErr > 8.0D  ? 0.78f
                     : Math.max(0.15f, 0.28f + 0.5f * (float)(aimErr / 8.0D));
        if (aimTicks < 4) factor *= (aimTicks / 4.0f);

        RotationManager.setEasingMode(aimErr > 20.0D
            ? RotationManager.EasingMode.SWIGHT_HIGH_SENS
            : RotationManager.EasingMode.KINEMATIC_SPRING);
        RotationManager.smoothTo(client, center, factor);

        double dist  = client.player.distanceTo(lockedMaceTarget);
        float  scale = client.player.getAttackStrengthScale(0.0F);

        if (dist <= maxSwingRange && scale >= (0.55D + currentFatigueLevel) && smashCooldown == 0) {
            if (dist > 5.0D && secureRandom.nextDouble() >= 0.75D) return;
            if (secureRandom.nextDouble() < randomMissProbability) {
                smashCooldown = 2 + secureRandom.nextInt(2);
                return;
            }
            InteractionManager.simulateClickAttack(client);
            smashCooldown = 2 + secureRandom.nextInt(2);
            currentFatigueLevel = Math.min(1.0D, currentFatigueLevel + fatigueScalar);
            if (SMASH_TIMING_QUEUE.size() >= HISTORY_MAX) SMASH_TIMING_QUEUE.pollFirst();
            SMASH_TIMING_QUEUE.offerLast(System.currentTimeMillis());
            GrimBypassCore.onHitLanded(38, 65, 48, 75);
            if (savedSlot >= 0) { InventoryManager.restoreSavedSlot(client); savedSlot = -1; }
            isTracking = false; aimTicks = 0; trackTicks = 0;
        }

        if (trackTicks > 200 || aimTicks > 200) {
            isTracking = false; aimTicks = 0; trackTicks = 0;
            if (savedSlot >= 0) { InventoryManager.restoreSavedSlot(client); savedSlot = -1; }
        }
    }

    private static void trackPeak(Minecraft client) {
        double y  = client.player.getY();
        double vy = client.player.getDeltaMovement().y;
        if (client.player.onGround()) { peakY = y; hasPeaked = false; return; }
        if (vy > 0) peakY = Math.max(peakY == Double.NEGATIVE_INFINITY ? y : peakY, y);
        if (vy < 0 && peakY != Double.NEGATIVE_INFINITY) hasPeaked = true;
    }

    private static boolean verifyLineOfSight(Minecraft client, Entity target) {
        if (client.player == null || target == null || client.level == null) return false;
        Vec3 start = client.player.getEyePosition();
        Vec3 end   = target.getEyePosition();
        BlockHitResult hit = client.level.clip(
            new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));
        return hit.getType() == HitResult.Type.MISS;
    }

    public static boolean verifyMaceSubsystemHealth() { return enabled && SUBSESSION_IDENTITY != null; }
    public static long    getExecutionTicks()          { return executionTicks; }
    public static void    setMaxSwingRange(double r)   { maxSwingRange = r; }
    public static double  getMaxSwingRange()           { return maxSwingRange; }
    public static void    setMaxAimDistance(double d)  { maxAimDistance = d; }
    public static double  getMaxAimDistance()          { return maxAimDistance; }
    public static void    setMinFallDistance(double d) { minFallDistance = d; }
    public static double  getMinFallDistance()         { return minFallDistance; }
    public static void    setHyperSnapSpeed(float s)   { hyperSnapSpeed = s; }
    public static float   getHyperSnapSpeed()          { return hyperSnapSpeed; }
    public static void    setWindChargeDetection(boolean b) { windChargeDetection = b; }
    public static boolean isWindChargeDetectionActive() { return windChargeDetection; }
    public static void    setElytraDiveCheck(boolean b) { elytraDiveCheck = b; }
    public static boolean isElytraDiveCheckActive()    { return elytraDiveCheck; }
    public static double  getFatigueLevel()            { return currentFatigueLevel; }
    public static LivingEntity getLockedTarget()       { return lockedMaceTarget; }
    public static UUID    getSubsessionIdentity()      { return SUBSESSION_IDENTITY; }
    public static void    clearAllMaceQueues()         { VELOCITY_VECTOR_QUEUE.clear(); SMASH_TIMING_QUEUE.clear(); }
}
