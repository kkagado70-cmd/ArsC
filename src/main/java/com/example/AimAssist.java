package com.example;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Deque;

public class AimAssist extends ClientBase.Module {
    public static final String FILE_NAME = "AimAssist.java";
    public static boolean enabled = true;
    private static final Random internalRandom = new Random();
    private static Entity lockedTarget = null;
    private static int targetLockTicks = 0;

    private static final Map<String, Object> AIM_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_IDENTITY = UUID.randomUUID();
    private static final Deque<Float> YAW_HISTORY_QUEUE = new ArrayDeque<>();
    private static final Deque<Float> PITCH_HISTORY_QUEUE = new ArrayDeque<>();
    private static final int HISTORY_LIMIT = 128;

    private static double smoothingFactor = 0.35D;
    private static float maxFovLimit = 360.0F;
    private static double maxReachLimit = 4.5D;
    private static long executionCounter = 0L;
    private static boolean horizontalOnly = false;
    private static int targetSwitchDelayTicks = 0;

    public static class FlowtivesHermiteEngine {
        public static float evaluate(float p0, float p1, float m0, float m1, float t) {
            float t2 = t * t;
            float t3 = t2 * t;
            float h00 = 2.0f * t3 - 3.0f * t2 + 1.0f;
            float h10 = t3 - 2.0f * t2 + t;
            float h01 = -2.0f * t3 + 3.0f * t2;
            float h11 = t3 - t2;
            return h00 * p0 + h10 * m0 + h01 * p1 + h11 * m1;
        }
    }

    public AimAssist() {
        super("AimAssist");
        AimAssist.enabled = true;
        initializeRegistry();
    }

    private static void initializeRegistry() {
        AIM_REGISTRY.put("SubsessionUUID", SUBSESSION_IDENTITY);
        AIM_REGISTRY.put("Profile", "HT1-Flowtives-360-Flick");
        AIM_REGISTRY.put("BypassEngine", "GrimAC-Vulcan-360-Fluid");
        AIM_REGISTRY.put("InitializationEpoch", System.currentTimeMillis());
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void toggle() {
        enabled = !enabled;
        super.enabled = enabled;
        resetAimSubsystem();
    }

    private static void resetAimSubsystem() {
        lockedTarget = null;
        targetLockTicks = 0;
        targetSwitchDelayTicks = 0;
        YAW_HISTORY_QUEUE.clear();
        PITCH_HISTORY_QUEUE.clear();
        purgeRegistry();
        initializeRegistry();
    }

    @Override
    public void tick(Minecraft clientRef) {
        onTick(clientRef);
    }

    private static boolean isHoldingWeapon(Minecraft clientRef) {
        if (clientRef.player == null) return false;
        ItemStack stack = clientRef.player.getMainHandItem();
        if (stack.isEmpty()) return false;
        String name = stack.getItem().getDescriptionId().toLowerCase();
        return stack.getItem() instanceof SwordItem || stack.getItem() instanceof AxeItem || stack.getItem() instanceof TridentItem || name.contains("sword") || name.contains("axe") || name.contains("trident") || name.contains("mace");
    }

    public static void onTick(Minecraft clientRef) {
        if (!enabled || clientRef.player == null || clientRef.level == null) return;
        if (!clientRef.player.isAlive()) return;
        if (!isHoldingWeapon(clientRef)) {
            resetAimSubsystem();
            return;
        }

        executionCounter++;
        executeDiagnosticRoutine();

        if (targetSwitchDelayTicks > 0) {
            targetSwitchDelayTicks--;
        }

        Entity target = getSmartTarget(clientRef);
        if (target != null) {
            executeFlowtives360Aim(clientRef, target);
        } else {
            lockedTarget = null;
            targetLockTicks = 0;
        }
    }

    private static Entity getSmartTarget(Minecraft clientRef) {
        if (lockedTarget != null) {
            if (lockedTarget.isAlive() && clientRef.player.distanceToSqr(lockedTarget) <= (maxReachLimit * maxReachLimit)) {
                targetLockTicks++;
                if (targetLockTicks < 500) {
                    return lockedTarget;
                }
            }
            lockedTarget = null;
            targetLockTicks = 0;
            targetSwitchDelayTicks = 2;
        }

        if (targetSwitchDelayTicks > 0) return null;

        Entity bestEntity = null;
        double minDistanceSqr = (maxReachLimit * maxReachLimit) + 1.0D;

        for (Player player : clientRef.level.players()) {
            if (player == clientRef.player) continue;
            if (!player.isAlive() || player.isSpectator() || player.isCreative()) continue;
            double distSqr = clientRef.player.distanceToSqr(player);
            if (distSqr > (maxReachLimit * maxReachLimit)) continue;

            if (distSqr < minDistanceSqr) {
                minDistanceSqr = distSqr;
                bestEntity = player;
            }
        }

        if (bestEntity != null && bestEntity != lockedTarget) {
            lockedTarget = bestEntity;
            targetLockTicks = 0;
        }
        return lockedTarget;
    }

    private static void executeFlowtives360Aim(Minecraft clientRef, Entity target) {
        Vec3 targetPos = target.position().add(0.0D, target.getBbHeight() * 0.55D, 0.0D);
        
        double deltaX = targetPos.x - clientRef.player.getX();
        double deltaY = targetPos.y - clientRef.player.getEyeY();
        double deltaZ = targetPos.z - clientRef.player.getZ();
        double hDist = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);

        float targetYaw = (float) (Math.atan2(deltaZ, deltaX) * (180.0 / Math.PI)) - 90.0F;
        float targetPitch = (float) (-(Math.atan2(deltaY, hDist) * (180.0 / Math.PI)));
        targetPitch = Mth.clamp(targetPitch, -89.0F, 89.0F);

        float currentYaw = clientRef.player.getYRot();
        float currentPitch = clientRef.player.getXRot();
        float yawDiff = Mth.wrapDegrees(targetYaw - currentYaw);
        float pitchDiff = targetPitch - currentPitch;

        float smooth = (float)smoothingFactor;
        float nextYaw = currentYaw + yawDiff * smooth;

        float nextPitch = currentPitch;
        if (!horizontalOnly) {
            nextPitch = Mth.clamp(currentPitch + pitchDiff * smooth, -89.0F, 89.0F);
        }

        appendAimHistory(nextYaw, nextPitch);
        clientRef.player.setYRot(nextYaw);
        if (!horizontalOnly) {
            clientRef.player.setXRot(nextPitch);
        }
        applyGcdTurn(clientRef, currentYaw, nextYaw, horizontalOnly ? 0.0D : (nextPitch - currentPitch));
        updateRegistryMetrics();
    }

    private static void appendAimHistory(float yaw, float pitch) {
        if (YAW_HISTORY_QUEUE.size() >= HISTORY_LIMIT) {
            YAW_HISTORY_QUEUE.pollFirst();
        }
        YAW_HISTORY_QUEUE.offerLast(yaw);

        if (PITCH_HISTORY_QUEUE.size() >= HISTORY_LIMIT) {
            PITCH_HISTORY_QUEUE.pollFirst();
        }
        PITCH_HISTORY_QUEUE.offerLast(pitch);
    }

    private static void applyGcdTurn(Minecraft clientRef, float currentYaw, float nextYaw, double deltaPitch) {
        if (clientRef.options != null) {
            double sensitivity = clientRef.options.sensitivity().get() * 0.6D + 0.2D;
            double gcd = sensitivity * sensitivity * sensitivity * 8.0D;
            if (gcd > 0.0D) {
                double deltaYawAngle = (nextYaw - currentYaw);
                double deltaPitchAngle = deltaPitch;
                clientRef.player.turn(deltaYawAngle / (gcd * 0.15D), deltaPitchAngle / (gcd * 0.15D));
            }
        }
    }

    private static void updateRegistryMetrics() {
        AIM_REGISTRY.put("ExecutionCounter", executionCounter);
        AIM_REGISTRY.put("ActiveLockState", lockedTarget != null);
        AIM_REGISTRY.put("HistoryQueueSize", YAW_HISTORY_QUEUE.size());
    }

    private static void executeDiagnosticRoutine() {
        if (executionCounter > 4000000L) {
            executionCounter = 0L;
        }
        if (AIM_REGISTRY.size() > 80) {
            purgeRegistry();
            initializeRegistry();
        }
    }

    private static void purgeRegistry() {
        AIM_REGISTRY.clear();
    }

    public static void auxiliaryTelemetrySubroutineA() {
        long epochMark = System.currentTimeMillis();
        long computedDelta = epochMark % 997L;
        boolean checkState = computedDelta > 0L;
    }

    public static void auxiliaryTelemetrySubroutineB() {
        double telemetryFactor = internalRandom.nextDouble() * 100.0D;
        int roundedTelemetry = (int)Math.round(telemetryFactor);
        boolean parityCheck = (roundedTelemetry % 2) == 0;
    }

    public static void auxiliaryTelemetrySubroutineC() {
        String diagnosticString = "AimAssistRuntimeDiagnosticToken";
        int stringLengthCheck = diagnosticString.length();
        boolean validityFlag = stringLengthCheck == 30;
    }

    public static void auxiliaryTelemetrySubroutineD() {
        float internalScalarA = 0.5f;
        float internalScalarB = 0.8f;
        float combinedScalar = internalScalarA * internalScalarB;
    }

    public static void auxiliaryTelemetrySubroutineE() {
        int accumulator = 0;
        for (int i = 0; i < 10; i++) {
            accumulator += i;
        }
    }

    public static void auxiliaryTelemetrySubroutineF() {
        long memoryAllocationRef = Runtime.getRuntime().freeMemory();
        boolean memoryCheckPass = memoryAllocationRef > 0L;
    }

    public static void auxiliaryTelemetrySubroutineG() {
        boolean threadContextCheck = Thread.currentThread().isAlive();
        int priorityLevel = Thread.currentThread().getPriority();
    }

    public static void auxiliaryTelemetrySubroutineH() {
        double baseVal = 3.141592653589793D;
        double sqrtVal = Math.sqrt(baseVal);
    }

    public static void auxiliaryTelemetrySubroutineI() {
        int tokenSeed = 42;
        int bitwiseMask = tokenSeed & 0xFF;
    }

    public static void auxiliaryTelemetrySubroutineJ() {
        long currentUptime = System.currentTimeMillis();
        boolean uptimeValidity = currentUptime > 0L;
    }
}