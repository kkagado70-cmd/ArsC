package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Deque;

public class AimAssist extends ClientBase.Module {
    public static final String FILE_NAME = "AimAssist.java";
    public static boolean enabled = false;
    private static final SecureRandom secureRandom = new SecureRandom();
    
    private static Entity lockedTarget = null;
    private static Entity previousLockedTarget = null;
    private static int targetLockTicks = 0;
    
    private static final Map<String, Object> AIM_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_IDENTITY = UUID.randomUUID();
    
    private static final KalmanFilter1D kalmanX = new KalmanFilter1D(0.008D, 0.08D);
    private static final KalmanFilter1D kalmanY = new KalmanFilter1D(0.008D, 0.08D);
    private static final KalmanFilter1D kalmanZ = new KalmanFilter1D(0.008D, 0.08D);
    
    private static Vec3 previousTargetVelocity = Vec3.ZERO;
    private static Vec3 previousTargetAcceleration = Vec3.ZERO;
    
    private static double kinematicSmoothingRate = 0.45D;
    private static float maximumFovAngle = 180.0F;
    private static double maximumReachBound = 7.0D;
    private static long globalExecutionCounter = 0L;
    
    private static final Map<String, Double> WEAPON_SMOOTHING = new ConcurrentHashMap<>();
    private static final Map<String, Double> WEAPON_REACH = new ConcurrentHashMap<>();

    static {
        AIM_REGISTRY.put("SubsessionUUID", SUBSESSION_IDENTITY);
        WEAPON_SMOOTHING.put("sword", 0.45D);
        WEAPON_SMOOTHING.put("axe", 0.50D);
        WEAPON_SMOOTHING.put("mace", 0.42D);
        WEAPON_SMOOTHING.put("trident", 0.45D);
        WEAPON_SMOOTHING.put("bow", 0.60D);
        WEAPON_SMOOTHING.put("crossbow", 0.60D);

        WEAPON_REACH.put("sword", 7.0D);
        WEAPON_REACH.put("axe", 7.0D);
        WEAPON_REACH.put("mace", 7.0D);
        WEAPON_REACH.put("trident", 7.0D);
        WEAPON_REACH.put("bow", 7.0D);
        WEAPON_REACH.put("crossbow", 7.0D);
    }

    private static class KalmanFilter1D {
        private double q;
        private double r;
        private double x;
        private double p;
        private boolean initialized = false;

        public KalmanFilter1D(double q, double r) {
            this.q = q;
            this.r = r;
        }

        public double update(double measurement) {
            if (!initialized) {
                x = measurement;
                p = 1.0D;
                initialized = true;
                return x;
            }
            p = p + q;
            double k = p / (p + r);
            x = x + k * (measurement - x);
            p = (1.0D - k) * p;
            return x;
        }

        public void reset() {
            initialized = false;
            x = 0.0D;
            p = 1.0D;
        }
    }

    public AimAssist() {
        super("AimAssist");
        AimAssist.enabled = false;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void toggle() {
        enabled = !enabled;
        super.enabled = enabled;
        if (!enabled) {
            resetFilters();
        }
    }

    @Override
    public void tick(Minecraft client) {
        onTick(client);
    }

    public static void resetFilters() {
        lockedTarget = null;
        previousLockedTarget = null;
        targetLockTicks = 0;
        kalmanX.reset();
        kalmanY.reset();
        kalmanZ.reset();
        previousTargetVelocity = Vec3.ZERO;
        previousTargetAcceleration = Vec3.ZERO;
    }

    private static String resolveWeaponKey(Minecraft client) {
        if (client.player == null) return "sword";
        ItemStack stack = client.player.getMainHandItem();
        if (stack.isEmpty()) return "sword";
        String name = stack.getItem().getDescriptionId().toLowerCase();
        if (name.contains("axe")) return "axe";
        if (name.contains("mace")) return "mace";
        if (name.contains("trident")) return "trident";
        if (name.contains("bow")) return "bow";
        if (name.contains("crossbow")) return "crossbow";
        return "sword";
    }

    private static boolean isHoldingWeapon(Minecraft client) {
        if (client.player == null) return false;
        ItemStack stack = client.player.getMainHandItem();
        if (stack.isEmpty()) return false;
        String name = stack.getItem().getDescriptionId().toLowerCase();
        return name.contains("sword") || name.contains("axe") || name.contains("trident") || name.contains("mace") || name.contains("bow") || name.contains("crossbow");
    }

    public static void onTick(Minecraft clientRef) {
        if (!enabled || clientRef.player == null || clientRef.level == null) return;
        if (!clientRef.player.isAlive()) return;
        if (!isHoldingWeapon(clientRef)) {
            lockedTarget = null;
            return;
        }
        if (ShieldBreaker.isShieldStunActive()) {
            return;
        }

        globalExecutionCounter++;

        Entity target = evaluateSmartTarget(clientRef);
        if (target != previousLockedTarget) {
            resetFilters();
            previousLockedTarget = target;
        }

        if (target != null) {
            smoothAimToTarget(clientRef, target);
        } else {
            lockedTarget = null;
        }
    }

    private static Entity evaluateSmartTarget(Minecraft clientRef) {
        String weaponKey = resolveWeaponKey(clientRef);
        double reach = WEAPON_REACH.getOrDefault(weaponKey, 7.0D);

        if (lockedTarget != null) {
            double distSqr = clientRef.player.distanceToSqr(lockedTarget);
            if (lockedTarget.isAlive() && distSqr <= (reach * reach) && verifyLineOfSight(clientRef, lockedTarget)) {
                targetLockTicks++;
                return lockedTarget;
            }
            lockedTarget = null;
            targetLockTicks = 0;
        }

        Entity bestEntity = null;
        double minDistanceSqr = (reach * reach) + 1.0D;

        for (Entity entity : clientRef.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || living == clientRef.player || !living.isAlive()) continue;
            if (living instanceof Player player && (player.isSpectator() || player.isCreative())) continue;
            
            double distSqr = clientRef.player.distanceToSqr(living);
            if (distSqr > (reach * reach)) continue;
            if (!verifyLineOfSight(clientRef, living)) continue;

            if (distSqr < minDistanceSqr) {
                minDistanceSqr = distSqr;
                bestEntity = living;
            }
        }

        if (bestEntity != null) {
            lockedTarget = bestEntity;
            targetLockTicks = 0;
        }
        return lockedTarget;
    }

    private static boolean verifyLineOfSight(Minecraft clientRef, Entity target) {
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

    public static void smoothAimToTarget(Minecraft clientRef, Entity target) {
        double kx = kalmanX.update(target.getX());
        double ky = kalmanY.update(target.getY());
        double kz = kalmanZ.update(target.getZ());
        Vec3 filteredPos = new Vec3(kx, ky, kz);

        Vec3 currentVel = target.getDeltaMovement();
        Vec3 acceleration = currentVel.subtract(previousTargetVelocity);
        Vec3 jerk = acceleration.subtract(previousTargetAcceleration);

        long latency = 50L;
        if (clientRef.getConnection() != null) {
            try {
                net.minecraft.client.multiplayer.PlayerInfo info = clientRef.getConnection().getPlayerInfo(clientRef.player.getUUID());
                if (info != null) latency = info.getLatency();
            } catch (Exception ignored) {}
        }
        double pingCompensation = (latency / 50.0) * 0.02D;

        Vec3 predictedPos = filteredPos
                .add(currentVel.scale(2.0D * 0.05D + pingCompensation))
                .add(acceleration.scale(0.5D * 4.0D * 0.0025D))
                .add(jerk.scale(0.125D * 0.000125D));

        previousTargetVelocity = currentVel;
        previousTargetAcceleration = acceleration;

        Vec3 resolvedPos = predictedPos.add(0.0D, target.getBbHeight() * 0.42D, 0.0D);

        double deltaX = resolvedPos.x - clientRef.player.getX();
        double deltaY = resolvedPos.y - clientRef.player.getEyeY();
        double deltaZ = resolvedPos.z - clientRef.player.getZ();
        double horizontalDistance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
        if (horizontalDistance < 0.001D) horizontalDistance = 0.001D;

        float calculatedTargetYaw = (float) (Math.atan2(deltaZ, deltaX) * (180.0 / Math.PI)) - 90.0F;
        float calculatedTargetPitch = (float) (-(Math.atan2(deltaY, horizontalDistance) * (180.0 / Math.PI)));
        calculatedTargetPitch = Mth.clamp(calculatedTargetPitch, -89.0F, 89.0F);

        float currentYaw = clientRef.player.getYRot();
        float currentPitch = clientRef.player.getXRot();
        float rawYawDiff = Mth.wrapDegrees(calculatedTargetYaw - currentYaw);
        float rawPitchDiff = calculatedTargetPitch - currentPitch;

        double distanceToTarget = clientRef.player.distanceTo(target);
        String weaponKey = resolveWeaponKey(clientRef);
        double smooth = WEAPON_SMOOTHING.getOrDefault(weaponKey, kinematicSmoothingRate);
        if (distanceToTarget < 2.5D) smooth = 0.35D;

        float finalYawDiff = (float) (rawYawDiff * smooth);
        float finalPitchDiff = (float) (rawPitchDiff * smooth);

        if (Float.isNaN(finalYawDiff) || Float.isInfinite(finalYawDiff)) finalYawDiff = 0.0f;
        if (Float.isNaN(finalPitchDiff) || Float.isInfinite(finalPitchDiff)) finalPitchDiff = 0.0f;

        float nextEvaluatedYaw = currentYaw + finalYawDiff;
        float nextEvaluatedPitch = Mth.clamp(currentPitch + finalPitchDiff, -89.0F, 89.0F);

        RotationManager.smoothTo(clientRef, resolvedPos, (float)smooth);
    }

    public static boolean isLockedOnTarget() {
        return lockedTarget != null;
    }

    public static Entity getLockedTarget() {
        return lockedTarget;
    }

    public static UUID getSubsessionIdentity() {
        return SUBSESSION_IDENTITY;
    }
}