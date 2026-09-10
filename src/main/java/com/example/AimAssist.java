package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
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
    public static boolean enabled = true;
    private static final SecureRandom secureRandom = new SecureRandom();
    private static Entity lockedTarget = null;

    private static final Map<String, Object> MATH_AIM_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_IDENTITY = UUID.randomUUID();

    private static final KalmanFilter1D kalmanX = new KalmanFilter1D(0.02D, 0.5D, 1.0D, 0.0D);
    private static final KalmanFilter1D kalmanY = new KalmanFilter1D(0.02D, 0.5D, 1.0D, 0.0D);
    private static final KalmanFilter1D kalmanZ = new KalmanFilter1D(0.02D, 0.5D, 1.0D, 0.0D);
    private static final DampedSpring dampedSpringYaw = new DampedSpring(1.0D, 18.0D);
    private static final DampedSpring dampedSpringPitch = new DampedSpring(1.0D, 18.0D);
    private static final ButterworthFilter2nd butterworthYaw = new ButterworthFilter2nd(8.0D, 50.0D);
    private static final ButterworthFilter2nd butterworthPitch = new ButterworthFilter2nd(8.0D, 50.0D);
    private static final PinkNoiseGenerator pinkNoise = new PinkNoiseGenerator();

    private static long executionCounter = 0L;

    static {
        MATH_AIM_REGISTRY.put("SubsessionUUID", SUBSESSION_IDENTITY);
        MATH_AIM_REGISTRY.put("Profile", "Swight-Mathematical-HT1");
    }

    public static class KalmanFilter1D {
        private double q;
        private double r;
        private double x;
        private double p;
        private double k;

        public KalmanFilter1D(double processNoise, double measurementNoise, double estimationError, double initialValue) {
            this.q = processNoise;
            this.r = measurementNoise;
            this.p = estimationError;
            this.x = initialValue;
        }

        public double update(double measurement) {
            p = p + q;
            k = p / (p + r);
            x = x + k * (measurement - x);
            p = (1.0D - k) * p;
            return x;
        }
    }

    public static class FittsCalculator {
        public static double calculateMovementTime(double distance, double tolerance) {
            if (tolerance <= 0.001D) tolerance = 0.001D;
            double a = 0.05D;
            double b = 0.12D;
            return a + b * Math.log(2.0D * distance / tolerance) / Math.log(2.0D);
        }
    }

    public static class MinJerkTrajectory {
        public static double compute(double t) {
            double clamped = Mth.clamp(t, 0.0D, 1.0D);
            return 10.0D * Math.pow(clamped, 3.0D) - 15.0D * Math.pow(clamped, 4.0D) + 6.0D * Math.pow(clamped, 5.0D);
        }
    }

    public static class BezierCubic {
        public static double evaluate(double t, double p0, double p1, double p2, double p3) {
            double u = 1.0D - t;
            double tt = t * t;
            double uu = u * u;
            double uuu = uu * u;
            double ttt = tt * t;
            double p = uuu * p0;
            p += 3.0D * uu * t * p1;
            p += 3.0D * u * tt * p2;
            p += ttt * p3;
            return p;
        }
    }

    public static class DampedSpring {
        private double dampingRatio;
        private double angularFrequency;
        private double velocity = 0.0D;

        public DampedSpring(double zeta, double omega) {
            this.dampingRatio = zeta;
            this.angularFrequency = omega;
        }

        public double update(double current, double target, double dt) {
            double displacement = current - target;
            double acceleration = -2.0D * dampingRatio * angularFrequency * velocity - angularFrequency * angularFrequency * displacement;
            velocity += acceleration * dt;
            return current + velocity * dt;
        }
    }

    public static class ButterworthFilter2nd {
        private double cutoffFrequency;
        private double sampleRate;
        private double v0 = 0.0D;
        private double v1 = 0.0D;

        public ButterworthFilter2nd(double cutoff, double rate) {
            this.cutoffFrequency = cutoff;
            this.sampleRate = rate;
        }

        public double filter(double input) {
            double c = 1.0D / Math.tan(Math.PI * cutoffFrequency / sampleRate);
            double a1 = 1.0D / (1.0D + Math.sqrt(2.0D) * c + c * c);
            double a2 = 2.0D * a1;
            double a3 = a1;
            double b1 = 2.0D * (1.0D - c * c) * a1;
            double b2 = (1.0D - Math.sqrt(2.0D) * c + c * c) * a1;

            double output = a1 * input + a2 * v0 + a3 * v1 - b1 * v0 - b2 * v1;
            v1 = v0;
            v0 = input;
            return output;
        }
    }

    public static class PinkNoiseGenerator {
        private int counter = 0;
        private final double[] rows = new double[16];
        private double runningSum = 0.0D;

        public double nextNoise() {
            int lastKey = counter;
            counter = (counter + 1) & 32767;
            int diff = lastKey ^ counter;
            for (int i = 0; i < 16; i++) {
                if (((diff >> i) & 1) != 0) {
                    double rand = (secureRandom.nextDouble() - 0.5D) * 2.0D;
                    runningSum -= rows[i];
                    rows[i] = rand;
                    runningSum += rows[i];
                }
            }
            return runningSum / 16.0D;
        }
    }

    public static class TwoThirdsLaw {
        public static double calculateCurvatureVelocity(double radius, double curvature) {
            double k = 0.85D;
            if (radius < 0.001D) radius = 0.001D;
            if (curvature < 0.001D) curvature = 0.001D;
            return k * Math.pow(radius, 1.0D / 3.0D) * Math.pow(curvature, 2.0D / 3.0D);
        }
    }

    public AimAssist() {
        super("AimAssist");
        AimAssist.enabled = true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void toggle() {
        enabled = !enabled;
        super.enabled = enabled;
        lockedTarget = null;
    }

    @Override
    public void tick(Minecraft client) {
        onTick(client);
    }

    private static boolean isHoldingWeapon(Minecraft client) {
        if (client.player == null) return false;
        ItemStack stack = client.player.getMainHandItem();
        if (stack.isEmpty()) return false;
        String name = stack.getItem().getDescriptionId().toLowerCase();
        return name.contains("sword") || name.contains("axe") || name.contains("trident") || name.contains("mace") || name.contains("bow") || name.contains("crossbow");
    }

    private static boolean verifyLineOfSight(Minecraft client, Entity target) {
        if (client.player == null || target == null) return false;
        Vec3 start = client.player.getEyePosition();
        Vec3 end = target.getEyePosition();
        BlockHitResult hit = client.level.clip(
            new ClipContext(
                start, 
                end, 
                ClipContext.Block.COLLIDER, 
                ClipContext.Fluid.NONE, 
                client.player
            )
        );
        return hit.getType() == HitResult.Type.MISS;
    }

    public static void onTick(Minecraft client) {
        if (!enabled || client.player == null || client.level == null || !client.player.isAlive()) return;
        if (!isHoldingWeapon(client)) {
            lockedTarget = null;
            return;
        }
        if (ShieldBreaker.isShieldStunActive()) return;

        executionCounter++;
        Entity target = evaluateSmartTarget(client);
        if (target != null) {
            executeMathematicalPipeline(client, target);
        } else {
            lockedTarget = null;
        }
    }

    private static Entity evaluateSmartTarget(Minecraft client) {
        if (lockedTarget != null) {
            if (lockedTarget.isAlive() && client.player.distanceToSqr(lockedTarget) <= 49.0D && verifyLineOfSight(client, lockedTarget)) {
                return lockedTarget;
            }
            lockedTarget = null;
        }

        Entity bestEntity = null;
        double minDst = 50.0D;

        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || living == client.player || !living.isAlive()) continue;
            if (living instanceof Player player && (player.isSpectator() || player.isCreative())) continue;
            
            double dstSqr = client.player.distanceToSqr(living);
            if (dstSqr > 49.0D) continue;
            if (!verifyLineOfSight(client, living)) continue;

            if (dstSqr < minDst) {
                minDst = dstSqr;
                bestEntity = living;
            }
        }

        if (bestEntity != null) {
            lockedTarget = bestEntity;
        }
        return lockedTarget;
    }

    private static void executeMathematicalPipeline(Minecraft client, Entity target) {
        Vec3 rawPos = target.position().add(0.0D, target.getBbHeight() * 0.42D, 0.0D);
        
        double filteredX = kalmanX.update(rawPos.x);
        double filteredY = kalmanY.update(rawPos.y);
        double filteredZ = kalmanZ.update(rawPos.z);
        Vec3 filteredPos = new Vec3(filteredX, filteredY, filteredZ);

        Vec3 targetVel = target.getDeltamovement != null ? target.getDeltaMovement().scale(PREDICTION_TICKS * 0.05D) : Vec3.ZERO;
        Vec3 resolvedPos = filteredPos.add(targetVel);

        double dx = resolvedPos.x - client.player.getX();
        double dy = resolvedPos.y - client.player.getEyeY();
        double dz = resolvedPos.z - client.player.getZ();
        double hDist = Math.sqrt(dx * dx + dz * dz);
        if (hDist < 0.001D) hDist = 0.001D;

        float targetYaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        float targetPitch = (float) (-(Math.atan2(dy, hDist) * (180.0 / Math.PI)));
        targetPitch = Mth.clamp(targetPitch, -89.0F, 89.0F);

        float curYaw = client.player.getYRot();
        float curPitch = client.player.getXRot();
        float yawDiff = Mth.wrapDegrees(targetYaw - curYaw);
        float pitchDiff = targetPitch - curPitch;

        double distance = client.player.distanceTo(target);
        double tolerance = 0.1D;
        double movementTime = FittsCalculator.calculateMovementTime(distance, tolerance);
        double normalizedProgress = Math.min(1.0D, 1.0D / (movementTime * 20.0D));

        double jerkProgress = MinJerkTrajectory.compute(normalizedProgress);
        double bezierVal = BezierCubic.evaluate(normalizedProgress, 0.0D, 0.2D, 0.8D, 1.0D);
        double blendedCurve = (jerkProgress + bezierVal) * 0.5D;

        double curvatureVelocity = TwoThirdsLaw.calculateCurvatureVelocity(distance, Math.abs(yawDiff));
        double adjustedYawDiff = yawDiff * blendedCurve * (1.0D + curvatureVelocity * 0.01D);
        double adjustedPitchDiff = pitchDiff * blendedCurve;

        double springYaw = dampedSpringYaw.update(curYaw, curYaw + (float)adjustedYawDiff, 0.05D);
        double springPitch = dampedSpringPitch.update(curPitch, curPitch + (float)adjustedPitchDiff, 0.05D);

        double filteredFinalYaw = butterworthYaw.filter(springYaw);
        double filteredFinalPitch = butterworthPitch.filter(springPitch);

        double noise = pinkNoise.nextNoise() * 0.0005D;
        double finalYaw = filteredFinalYaw + noise;
        double finalPitch = Mth.clamp(filteredFinalPitch + noise, -89.0F, 89.0F);

        if (client.options != null) {
            double sens = client.options.sensitivity().get() * 0.6D + 0.2D;
            double gcd = sens * sens * sens * 8.0D;
            if (gcd > 0.0D) {
                double dY = (finalYaw - curYaw);
                double dP = (finalPitch - curPitch);
                client.player.turn(dY / (gcd * 0.15D), dP / (gcd * 0.15D));
            }
        }

        client.player.setYRot((float)finalYaw);
        client.player.setXRot((float)finalPitch);
    }

    public static UUID getSubsessionIdentity() {
        return SUBSESSION_IDENTITY;
    }
}