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
    public static boolean enabled = true;
    private static final SecureRandom secureRandom = new SecureRandom();
    private static Entity lockedTarget = null;
    private static int targetLockTicks = 0;
    private static int targetLostTicks = 0;

    private static final Map<String, Object> SWIGHT_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_IDENTITY = UUID.randomUUID();
    private static final Deque<Float> YAW_HISTORY = new ArrayDeque<>();
    private static final Deque<Float> PITCH_HISTORY = new ArrayDeque<>();
    private static final Deque<Vec3> VELOCITY_HISTORY = new ArrayDeque<>();
    private static final Deque<Long> TIMING_HISTORY = new ArrayDeque<>();
    private static final Deque<Double> ACCELERATION_HISTORY = new ArrayDeque<>();
    private static final Deque<Double> JERK_HISTORY = new ArrayDeque<>();
    private static final Deque<Float> OVERSHOOT_HISTORY = new ArrayDeque<>();
    private static final Deque<Long> SESSION_HISTORY = new ArrayDeque<>();
    private static final int HISTORY_MAX_CAPACITY = 8192;

    // Parâmetros de mira
    private static double kinematicSmoothingRate = 0.45D;
    private static double stochasticJitterScale = 0.00005D;
    private static float maximumFovAngle = 100.0F;
    private static double maximumReachBound = 4.5D;
    private static long globalExecutionCounter = 0L;
    private static boolean windMouseEngineActive = true;
    private static boolean horizontalAxisOnly = false;
    private static boolean gcdCorrectionActive = true;
    private static double cumulativeWindX = 0.0D;
    private static double cumulativeWindY = 0.0D;
    private static int targetSwitchThrottleTicks = 0;

    // Predição e contenção
    private static Vec3 previousTargetVelocity = Vec3.ZERO;
    private static Vec3 previousTargetAcceleration = Vec3.ZERO;
    private static final float PREDICTION_TICKS = 2.0f;
    private static float containmentStrength = 0.15f;
    private static float containmentRadius = 1.2f;
    private static float overshootYawOffset = 0.0f;
    private static float overshootPitchOffset = 0.0f;
    private static int saccadeTimer = 0;
    private static double targetPredictionScalar = 1.15D;
    private static double verticalSmoothingMultiplier = 1.1D;
    private static boolean errorInjectionActive = true;
    private static double randomMissProbability = 0.025D;

    // Melhorias extras
    private static Vec3 lastKnownTargetPos = null;
    private static int memoryTicks = 0;
    private static float recoilYaw = 0.0f;
    private static float recoilPitch = 0.0f;
    private static int recoilTicks = 0;
    private static int hitCount = 0;
    private static int totalAttacks = 0;

    static {
        initializeRegistry();
    }

    private static void initializeRegistry() {
        SWIGHT_REGISTRY.put("SubsessionUUID", SUBSESSION_IDENTITY);
        SWIGHT_REGISTRY.put("Profile", "Swight-Elite-AimAssist-800L");
        SWIGHT_REGISTRY.put("BypassEngine", "Human-Mime-Easing-Enterprise");
        SWIGHT_REGISTRY.put("InitializationEpoch", System.currentTimeMillis());
        SWIGHT_REGISTRY.put("SmoothingFactor", kinematicSmoothingRate);
        SWIGHT_REGISTRY.put("JitterScale", stochasticJitterScale);
        SWIGHT_REGISTRY.put("ContainmentRadius", containmentRadius);
    }

    public AimAssist() {
        super("AimAssist");
        AimAssist.enabled = true;
        initializeRegistry();
    }

    @Override
    public boolean isEnabled() { return enabled; }

    @Override
    public void toggle() {
        enabled = !enabled;
        super.enabled = enabled;
        hardReset();
    }

    private static void hardReset() {
        lockedTarget = null;
        targetLockTicks = 0;
        targetLostTicks = 0;
        targetSwitchThrottleTicks = 0;
        cumulativeWindX = 0.0D;
        cumulativeWindY = 0.0D;
        overshootYawOffset = 0.0f;
        overshootPitchOffset = 0.0f;
        saccadeTimer = 0;
        previousTargetVelocity = Vec3.ZERO;
        previousTargetAcceleration = Vec3.ZERO;
        lastKnownTargetPos = null;
        memoryTicks = 0;
        recoilYaw = 0.0f;
        recoilPitch = 0.0f;
        recoilTicks = 0;
        hitCount = 0;
        totalAttacks = 0;
        YAW_HISTORY.clear();
        PITCH_HISTORY.clear();
        VELOCITY_HISTORY.clear();
        TIMING_HISTORY.clear();
        ACCELERATION_HISTORY.clear();
        JERK_HISTORY.clear();
        OVERSHOOT_HISTORY.clear();
        SESSION_HISTORY.clear();
        purgeRegistry();
        initializeRegistry();
    }

    private static void purgeRegistry() { SWIGHT_REGISTRY.clear(); }

    @Override
    public void tick(Minecraft client) { onTick(client); }

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
        BlockHitResult hit = client.level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));
        return hit.getType() == HitResult.Type.MISS;
    }

    public static void onTick(Minecraft client) {
        if (!enabled || client.player == null || client.level == null) return;
        if (!client.player.isAlive()) return;
        if (!isHoldingWeapon(client)) {
            lockedTarget = null;
            targetLockTicks = 0;
            return;
        }
        if (ShieldBreaker.isShieldStunActive()) return; // CORRIGIDO

        globalExecutionCounter++;
        if (targetSwitchThrottleTicks > 0) targetSwitchThrottleTicks--;

        Entity target = evaluateSmartTarget(client);
        if (target != null) {
            lastKnownTargetPos = target.position();
            memoryTicks = 0;
            smoothAimToTarget(client, target);
        } else if (lastKnownTargetPos != null && memoryTicks < 5) {
            memoryTicks++;
            smoothAimToPosition(client, lastKnownTargetPos);
        } else {
            lockedTarget = null;
            targetLockTicks = 0;
            cumulativeWindX = 0.0D;
            cumulativeWindY = 0.0D;
            previousTargetVelocity = Vec3.ZERO;
            previousTargetAcceleration = Vec3.ZERO;
            lastKnownTargetPos = null;
            memoryTicks = 0;
        }

        // Recoil simulado
        if (recoilTicks > 0) {
            float yaw = client.player.getYRot();
            float pitch = client.player.getXRot();
            client.player.setYRot(yaw + recoilYaw / recoilTicks);
            client.player.setXRot(Mth.clamp(pitch + recoilPitch / recoilTicks, -89.0F, 89.0F));
            recoilTicks--;
        }

        executeAutoCalibrationLearning();
        refreshRegistryState();
    }

    private static Entity evaluateSmartTarget(Minecraft client) {
        if (lockedTarget != null) {
            double effectiveReach = maximumReachBound;
            if (client.player.distanceTo(lockedTarget) > 5.0D) effectiveReach = 7.0D;
            double distSqr = client.player.distanceToSqr(lockedTarget);
            double dist = Math.sqrt(distSqr);
            double effectiveFov = dist > 5.0D ? 60.0D : maximumFovAngle;
            if (lockedTarget.isAlive() && distSqr <= effectiveReach * effectiveReach
                    && computeFovCheck(client, lockedTarget, effectiveFov)
                    && verifyLineOfSight(client, lockedTarget)) {
                targetLockTicks++;
                targetLostTicks = 0;
                if (targetLockTicks < 5000) return lockedTarget;
            }
            targetLostTicks++;
            if (targetLostTicks < 5) return lockedTarget;
            lockedTarget = null;
            targetLockTicks = 0;
            targetLostTicks = 0;
            targetSwitchThrottleTicks = 1 + secureRandom.nextInt(2);
        }

        if (targetSwitchThrottleTicks > 0) return null;

        Entity best = null;
        double minDistSqr = (7.0D * 7.0D) + 1.0D;

        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || living == client.player || !living.isAlive()) continue;
            if (living instanceof Player p && (p.isSpectator() || p.isCreative())) continue;

            double distSqr = client.player.distanceToSqr(living);
            double currentReach = maximumReachBound;
            ItemStack hand = client.player.getMainHandItem();
            boolean isLongRange = hand.getItem() instanceof BowItem || hand.getItem() instanceof CrossbowItem;
            boolean targetStatic = living.getDeltaMovement().horizontalDistanceSqr() < 0.001D;
            if (isLongRange || targetStatic || distSqr > 4.5D * 4.5D) currentReach = 7.0D;
            else currentReach = 4.5D;
            if (distSqr > currentReach * currentReach) continue;

            double dist = Math.sqrt(distSqr);
            double effectiveFov = dist > 5.0D ? 60.0D : maximumFovAngle;
            if (!computeFovCheck(client, living, effectiveFov)) continue;
            if (!verifyLineOfSight(client, living)) continue;

            if (distSqr < minDistSqr) {
                minDistSqr = distSqr;
                best = living;
            }
        }

        if (best != null && best != lockedTarget) {
            lockedTarget = best;
            targetLockTicks = 0;
            targetLostTicks = 0;
        }
        return lockedTarget;
    }

    private static boolean computeFovCheck(Minecraft client, Entity entity, double maxAngle) {
        Vec3 pos = entity.position();
        double dx = pos.x - client.player.getX();
        double dz = pos.z - client.player.getZ();
        float targetYaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        float currentYaw = client.player.getYRot();
        return Math.abs(Mth.wrapDegrees(targetYaw - currentYaw)) <= maxAngle;
    }

    public static void smoothAimToTarget(Minecraft client, Entity target) {
        Vec3 resolved = computeResolvedTargetPosition(client, target);
        performAimInterpolation(client, resolved, target);
    }

    private static void smoothAimToPosition(Minecraft client, Vec3 pos) {
        performAimInterpolation(client, pos, null);
    }

    private static Vec3 computeResolvedTargetPosition(Minecraft client, Entity target) {
        double dist = client.player.distanceTo(target);

        // Smoothing adaptativo
        double baseSmooth = dist < 2.5D ? 0.55D : (dist > 5.0D ? 0.50D : 0.45D);
        if (client.player.getDeltaMovement().horizontalDistanceSqr() > 0.01D) baseSmooth = Math.min(0.70D, baseSmooth + 0.10D);

        double sens = client.options.sensitivity().get();
        double normalizedSens = (sens - 0.0) / (1.0 - 0.0);
        kinematicSmoothingRate = (0.35D + normalizedSens * 0.15D) + (baseSmooth - 0.45D);
        stochasticJitterScale = Math.max(0.00001D, 0.00008D - normalizedSens * 0.00005D);

        // Estresse (vida baixa, alvo rápido)
        if (client.player.getHealth() <= 6.0D || target.getDeltaMovement().horizontalDistance() > 1.5D) {
            kinematicSmoothingRate *= 0.85D;
        }

        // Overshoot com reflexo
        saccadeTimer++;
        float maxOvershootYaw = dist < 2.5D ? 0.05f : 0.15f;
        float maxOvershootPitch = dist < 2.5D ? 0.05f : 0.10f;
        if (saccadeTimer == 0) {
            overshootYawOffset = (float) ((secureRandom.nextDouble() - 0.5) * 1.2D);
            overshootPitchOffset = (float) ((secureRandom.nextDouble() - 0.5) * 0.8D);
        } else if (saccadeTimer == 1) {
            overshootYawOffset *= 0.2f;
            overshootPitchOffset *= 0.2f;
        } else if (saccadeTimer > 18 + secureRandom.nextInt(12)) {
            saccadeTimer = 0;
            overshootYawOffset = (float) ((secureRandom.nextDouble() - 0.5) * maxOvershootYaw * 2.0f);
            overshootPitchOffset = (float) ((secureRandom.nextDouble() - 0.5) * maxOvershootPitch * 2.0f);
        } else {
            overshootYawOffset *= 0.97f;
            overshootPitchOffset *= 0.97f;
            if (Math.abs(overshootYawOffset) < 0.01f) overshootYawOffset = 0.0f;
            if (Math.abs(overshootPitchOffset) < 0.01f) overshootPitchOffset = 0.0f;
        }

        // Predição com aceleração e jerk
        Vec3 vel = target.getDeltaMovement();
        Vec3 accel = vel.subtract(previousTargetVelocity);
        Vec3 jerk = accel.subtract(previousTargetAcceleration);

        long ping = 50L;
        if (client.getConnection() != null) {
            try { ping = client.getConnection().getLatency(); } catch (Exception ignored) {}
        }
        double pingComp = (ping / 50.0) * 0.02D;

        Vec3 predicted = target.position()
                .add(vel.scale(PREDICTION_TICKS * 0.05D + pingComp))
                .add(accel.scale(0.5D * PREDICTION_TICKS * PREDICTION_TICKS * 0.0025D))
                .add(jerk.scale((1.0/6.0) * PREDICTION_TICKS * PREDICTION_TICKS * PREDICTION_TICKS * 0.000125D));

        previousTargetVelocity = vel;
        previousTargetAcceleration = accel;

        // Variação do ponto de mira
        double roll = secureRandom.nextDouble();
        double chestOffsetY = target.getBbHeight() * 0.42D;
        if (roll < 0.15D && dist < 3.0D) chestOffsetY = target.getBbHeight() * 0.85D;
        else if (roll < 0.25D) chestOffsetY = target.getBbHeight() * 0.15D;
        else if (roll < 0.30D) chestOffsetY = target.getBbHeight() * secureRandom.nextDouble();

        return predicted.add(
                (secureRandom.nextDouble() - 0.5) * 0.06D,
                chestOffsetY,
                (secureRandom.nextDouble() - 0.5) * 0.06D
        );
    }

    private static void performAimInterpolation(Minecraft client, Vec3 targetPos, Entity target) {
        double dx = targetPos.x - client.player.getX();
        double dy = targetPos.y - client.player.getEyeY();
        double dz = targetPos.z - client.player.getZ();
        double hDist = Math.sqrt(dx*dx + dz*dz);

        float calcYaw = (float) (Math.atan2(dz, dx) * (180.0/Math.PI)) - 90.0F;
        float calcPitch = (float) (-Math.atan2(dy, hDist) * (180.0/Math.PI));
        calcPitch = Mth.clamp(calcPitch, -89.0F, 89.0F);

        float curYaw = client.player.getYRot();
        float curPitch = client.player.getXRot();
        float rawYawDiff = Mth.wrapDegrees(calcYaw - curYaw);
        float rawPitchDiff = calcPitch - curPitch;

        double dist = target != null ? client.player.distanceTo(target) : 3.0D;
        float deadzone = dist < 2.5D ? 0.3f : 0.5f;
        float distFromCenter = (float) Math.sqrt(rawYawDiff*rawYawDiff + rawPitchDiff*rawPitchDiff);
        if (distFromCenter < deadzone) return;

        // Modo foco/periférico
        if (Math.abs(rawYawDiff) < 10.0D) {
            stochasticJitterScale = 0.00003D;
            randomMissProbability = 0.01D;
        } else {
            stochasticJitterScale = 0.0001D;
            randomMissProbability = 0.04D;
        }

        // Modo espectador
        long spectators = client.level.players().stream().filter(p -> p != client.player && p.isSpectator()).count();
        if (spectators > 0) {
            stochasticJitterScale *= 1.5D;
            kinematicSmoothingRate *= 0.9D;
            randomMissProbability *= 1.5D;
        }

        // Zona de contenção
        if (distFromCenter > containmentRadius) {
            float pull = (distFromCenter - containmentRadius) * containmentStrength;
            float pullYaw = (rawYawDiff / distFromCenter) * pull;
            float pullPitch = (rawPitchDiff / distFromCenter) * pull;
            rawYawDiff -= pullYaw;
            rawPitchDiff -= pullPitch;
        }

        float finalYawDiff = rawYawDiff;
        float finalPitchDiff = rawPitchDiff;
        if (distFromCenter > 1.0f) {
            finalYawDiff += overshootYawOffset;
            finalPitchDiff += overshootPitchOffset;
        }

        // Easing (aceleração/desaceleração)
        float t = Math.min(1.0f, distFromCenter / 10.0f);
        float eased = 1.0f - (1.0f - t) * (1.0f - t);
        finalYawDiff *= eased;
        finalPitchDiff *= eased;

        // Erro proposital
        if (errorInjectionActive && secureRandom.nextDouble() < randomMissProbability) {
            finalYawDiff += (float) ((secureRandom.nextDouble() - 0.5) * 1.5D);
            finalPitchDiff += (float) ((secureRandom.nextDouble() - 0.5) * 1.0D);
        }

        // Aplicar rotação com WindMouse ou smoothing puro
        if (windMouseEngineActive) {
            cumulativeWindX = cumulativeWindX * 0.95D + secureRandom.nextGaussian() * 0.1D;
            if (!horizontalAxisOnly) cumulativeWindY = cumulativeWindY * 0.95D + secureRandom.nextGaussian() * 0.1D * verticalSmoothingMultiplier;

            float stepYaw = (float) (finalYawDiff / 14.0D + cumulativeWindX * 0.002D);
            float noiseYaw = (float) (secureRandom.nextGaussian() * stochasticJitterScale);
            float nextYaw = curYaw + stepYaw + noiseYaw;

            float nextPitch = curPitch;
            if (!horizontalAxisOnly) {
                float stepPitch = (float) (finalPitchDiff / 14.0D + cumulativeWindY * 0.002D);
                float noisePitch = (float) (secureRandom.nextGaussian() * stochasticJitterScale);
                nextPitch = Mth.clamp(curPitch + stepPitch + noisePitch, -89.0F, 89.0F);
            }

            if (gcdCorrectionActive) nextYaw = applyGcdGridSnap(client, curYaw, nextYaw);
            client.player.setYRot(nextYaw);
            if (!horizontalAxisOnly) client.player.setXRot(nextPitch);
            applyGcdHardwareTurnSimulation(client, curYaw, nextYaw, horizontalAxisOnly ? 0.0D : (nextPitch - curPitch));
        } else {
            float nextYaw = curYaw + finalYawDiff * (float) kinematicSmoothingRate;
            float nextPitch = curPitch;
            if (!horizontalAxisOnly) nextPitch = Mth.clamp(curPitch + finalPitchDiff * (float) kinematicSmoothingRate, -89.0F, 89.0F);
            if (gcdCorrectionActive) nextYaw = applyGcdGridSnap(client, curYaw, nextYaw);
            client.player.setYRot(nextYaw);
            if (!horizontalAxisOnly) client.player.setXRot(nextPitch);
            applyGcdHardwareTurnSimulation(client, curYaw, nextYaw, horizontalAxisOnly ? 0.0D : (nextPitch - curPitch));
        }
    }

        private static float applyGcdGridSnap(Minecraft client, float curYaw, float targetYaw) {
        if (client.options == null) return targetYaw;
        double sens = client.options.sensitivity().get() * 0.6D + 0.2D;
        double gcd = sens * sens * sens * 8.0D;
        if (gcd <= 0.0D) return targetYaw;
        double delta = targetYaw - curYaw;
        double clamped = Math.round(delta / (gcd * 0.15D)) * (gcd * 0.15D);
        return curYaw + (float) clamped;
    }

    private static void applyGcdHardwareTurnSimulation(Minecraft client, float curYaw, float nextYaw, double deltaPitch) {
        if (client.options == null) return;
        double sens = client.options.sensitivity().get() * 0.6D + 0.2D;
        double gcd = sens * sens * sens * 8.0D;
        if (gcd > 0.0D) {
            double dY = (nextYaw - curYaw);
            client.player.turn(dY / (gcd * 0.15D), deltaPitch / (gcd * 0.15D));
        }
    }

    private static void executeAutoCalibrationLearning() {
        totalAttacks++;
        if (totalAttacks >= 100) {
            double hitRate = (double) hitCount / totalAttacks;
            if (hitRate > 0.95D) {
                stochasticJitterScale += 0.00001D;
                randomMissProbability += 0.005D;
            } else if (hitRate < 0.70D) {
                stochasticJitterScale = Math.max(0.00001D, stochasticJitterScale - 0.00001D);
                randomMissProbability = Math.max(0.005D, randomMissProbability - 0.005D);
            }
            hitCount = 0;
            totalAttacks = 0;
        }
    }

    public static void registerAttackResult(boolean hit) {
        totalAttacks++;
        if (hit) hitCount++;
    }

    public static void triggerSimulatedRecoil() {
        recoilYaw = (float) ((secureRandom.nextDouble() - 0.5) * 0.4D);
        recoilPitch = (float) ((secureRandom.nextDouble() - 0.3) * 0.3D);
        recoilTicks = 3;
    }

    private static void refreshRegistryState() {
        SWIGHT_REGISTRY.put("ExecutionTicks", globalExecutionCounter);
        SWIGHT_REGISTRY.put("ActiveLockState", lockedTarget != null);
        SWIGHT_REGISTRY.put("WindOffset", cumulativeWindX);
        SWIGHT_REGISTRY.put("HistorySize", YAW_HISTORY.size());
    }

    public static UUID getSubsessionIdentity() { return SUBSESSION_IDENTITY; }
    public static long getGlobalExecutionCounter() { return globalExecutionCounter; }
    public static void setKinematicSmoothing(double v) { kinematicSmoothingRate = v; }
    public static double getKinematicSmoothing() { return kinematicSmoothingRate; }
    public static void toggleWindMouseEngine(boolean state) { windMouseEngineActive = state; }
    public static boolean isWindMouseEngineActive() { return windMouseEngineActive; }
    public static void toggleHorizontalAxisOnly(boolean state) { horizontalAxisOnly = state; }
    public static boolean isHorizontalAxisOnly() { return horizontalAxisOnly; }
    public static void toggleGcdCorrection(boolean state) { gcdCorrectionActive = state; }
    public static boolean isGcdCorrectionActive() { return gcdCorrectionActive; }
    public static int getYawHistorySize() { return YAW_HISTORY.size(); }
    public static int getPitchHistorySize() { return PITCH_HISTORY.size(); }
    
    public static void runBaselineCalibration() {
        kinematicSmoothingRate = 0.45D;
        stochasticJitterScale = 0.00005D;
        maximumFovAngle = 100.0F;
        maximumReachBound = 4.5D;
        windMouseEngineActive = true;
        horizontalAxisOnly = false;
        gcdCorrectionActive = true;
        cumulativeWindX = 0.0D;
        cumulativeWindY = 0.0D;
    }

    public static double getWindOffsetX() { return cumulativeWindX; }
    public static double getWindOffsetY() { return cumulativeWindY; }
}