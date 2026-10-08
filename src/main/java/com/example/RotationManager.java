package com.example;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RotationManager {

    public static final String FILE_NAME = "RotationManager.java";

    private RotationManager() {
        // Utility class
    }

    public enum EasingMode {
        LINEAR,
        EASE_IN_OUT_CUBIC,
        EASE_OUT_EXPO,
        KINEMATIC_SPRING,
        SWIGHT_HIGH_SENS
    }

    public enum RotationPriority {
        LOW(0),
        NORMAL(1),
        HIGH(2),
        CRITICAL(3);

        public final int level;

        RotationPriority(int level) {
            this.level = level;
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Map<String, Object> ROTATION_REGISTRY =
            new ConcurrentHashMap<>();

    private static final UUID SUBSESSION_ID = UUID.randomUUID();

    private static final Deque<Float> YAW_HISTORY = new ArrayDeque<>();
    private static final Deque<Float> PITCH_HISTORY = new ArrayDeque<>();
    private static final Deque<Long> TIMESTAMP_HISTORY = new ArrayDeque<>();

    private static final int HISTORY_CAP = 512;

    private static float currentYaw = 0.0f;
    private static float currentPitch = 0.0f;

    private static float yawVelocity = 0.0f;
    private static float pitchVelocity = 0.0f;

    private static boolean active = false;
    private static boolean locked = false;

    private static EasingMode easingMode = EasingMode.EASE_OUT_EXPO;
    private static RotationPriority priority = RotationPriority.NORMAL;

    private static double deadzoneThreshold = 0.025D;
    private static double jitterMagnitude = 0.0D;

    private static double gcdSensitivity = 0.0D;
    private static double sampledGcd = 0.0D;
    private static int gcdSamples = 0;

    private static double springStiffness = 0.18D;
    private static double springDamping = 0.72D;

    private static float maxYawStep = 90.0f;
    private static float maxPitchStep = 75.0f;

    private static float minPitch = -89.9f;
    private static float maxPitch = 89.9f;

    private static long totalRotationInvocations = 0L;

    private static int stableTickCount = 0;
    private static int stabilityThreshold = 2;

    private static float lastTargetYaw = 0.0f;
    private static float lastTargetPitch = 0.0f;

    private static float savedPlayerYaw = 0.0f;
    private static float savedPlayerPitch = 0.0f;
    private static boolean hasSavedRotation = false;

    private static float stabilityYawTolerance = 1.5f;
    private static float stabilityPitchTolerance = 1.5f;

    static {
        ROTATION_REGISTRY.put("SubsessionUUID", SUBSESSION_ID);
        ROTATION_REGISTRY.put(
                "Profile",
                "Swight-Eyezingz-RotationManager-Enterprise"
        );
        ROTATION_REGISTRY.put("EasingMode", easingMode.name());
        ROTATION_REGISTRY.put("GCDBypass", true);
        ROTATION_REGISTRY.put("SpringDamping", springDamping);
        ROTATION_REGISTRY.put("SpringStiffness", springStiffness);
    }

    public static void samplePlayerGcd(Minecraft client) {
        if (client == null ||
                client.player == null ||
                client.options == null) {
            return;
        }

        double sensitivity =
                client.options.sensitivity().get() * 0.6D + 0.2D;

        gcdSensitivity =
                sensitivity * sensitivity * sensitivity * 8.0D;

        if (YAW_HISTORY.size() < 2) {
            return;
        }

        Float[] values = YAW_HISTORY.toArray(new Float[0]);

        float delta = Math.abs(
                Mth.wrapDegrees(
                        values[values.length - 1]
                                - values[values.length - 2]
                )
        );

        if (delta <= 0.001f) {
            return;
        }

        if (gcdSamples == 0) {
            sampledGcd = delta;
        } else {
            sampledGcd = euclidGcd(
                    (float) sampledGcd,
                    delta
            );
        }

        gcdSamples++;
    }

    public static void smoothTo(
            Minecraft client,
            Vec3 target,
            float factor
    ) {
        if (!canRotate(client, target)) {
            return;
        }

        totalRotationInvocations++;

        factor = Mth.clamp(factor, 0.0f, 1.0f);

        float[] desired = computeDesiredAngles(client, target);

        float desiredYaw = desired[0];
        float desiredPitch = desired[1];

        float currentPlayerYaw = client.player.getYRot();
        float currentPlayerPitch = client.player.getXRot();

        float yawDiff = Mth.wrapDegrees(
                desiredYaw - currentPlayerYaw
        );

        float pitchDiff =
                desiredPitch - currentPlayerPitch;

        if (Math.abs(yawDiff) <= deadzoneThreshold &&
                Math.abs(pitchDiff) <= deadzoneThreshold) {

            stableTickCount++;

            active = stableTickCount < stabilityThreshold;

            return;
        }

        stableTickCount = 0;

        float[] steps = computeStepByMode(
                yawDiff,
                pitchDiff,
                factor
        );

        float stepYaw = Mth.clamp(
                steps[0],
                -maxYawStep,
                maxYawStep
        );

        float stepPitch = Mth.clamp(
                steps[1],
                -maxPitchStep,
                maxPitchStep
        );

        float nextYaw =
                currentPlayerYaw + stepYaw;

        float nextPitch =
                Mth.clamp(
                        currentPlayerPitch + stepPitch,
                        minPitch,
                        maxPitch
                );

        nextYaw = Mth.wrapDegrees(nextYaw);

        float deltaYaw =
                Mth.wrapDegrees(nextYaw - currentPlayerYaw);

        float deltaPitch =
                nextPitch - currentPlayerPitch;

        applyGCDRotation(
                client,
                deltaYaw,
                deltaPitch
        );

        /*
         * turn() já altera a rotação do jogador.
         * Não fazemos setYRot/setXRot aqui para evitar
         * aplicar o movimento duas vezes.
         */
        currentYaw = client.player.getYRot();
        currentPitch = client.player.getXRot();

        lastTargetYaw = desiredYaw;
        lastTargetPitch = desiredPitch;

        active = true;

        pushHistory(
                currentYaw,
                currentPitch
        );
    }

    public static void snapTo(
            Minecraft client,
            Vec3 target
    ) {
        if (!canRotate(client, target)) {
            return;
        }

        totalRotationInvocations++;

        float[] desired =
                computeDesiredAngles(client, target);

        float desiredYaw = desired[0];
        float desiredPitch = desired[1];

        float currentPlayerYaw =
                client.player.getYRot();

        float currentPlayerPitch =
                client.player.getXRot();

        float deltaYaw =
                Mth.wrapDegrees(
                        desiredYaw - currentPlayerYaw
                );

        float deltaPitch =
                desiredPitch - currentPlayerPitch;

        applyGCDRotation(
                client,
                deltaYaw,
                deltaPitch
        );

        /*
         * snapTo precisa realmente terminar no alvo.
         * O set final é intencional aqui.
         */
        client.player.setYRot(
                Mth.wrapDegrees(desiredYaw)
        );

        client.player.setXRot(
                Mth.clamp(
                        desiredPitch,
                        minPitch,
                        maxPitch
                )
        );

        currentYaw = client.player.getYRot();
        currentPitch = client.player.getXRot();

        yawVelocity = 0.0f;
        pitchVelocity = 0.0f;

        active = true;
        stableTickCount = 0;

        lastTargetYaw = desiredYaw;
        lastTargetPitch = desiredPitch;

        pushHistory(
                currentYaw,
                currentPitch
        );
    }

    public static void applyDelta(
            Minecraft client,
            float dYaw,
            float dPitch
    ) {
        if (client == null ||
                client.player == null ||
                locked) {
            return;
        }

        float currentPlayerYaw =
                client.player.getYRot();

        float currentPlayerPitch =
                client.player.getXRot();

        float nextYaw =
                Mth.wrapDegrees(
                        currentPlayerYaw + dYaw
                );

        float nextPitch =
                Mth.clamp(
                        currentPlayerPitch + dPitch,
                        minPitch,
                        maxPitch
                );

        applyGCDRotation(
                client,
                dYaw,
                dPitch
        );

        /*
         * applyGCDRotation usa turn(), portanto o estado
         * real do jogador é a fonte de verdade.
         */
        currentYaw = client.player.getYRot();
        currentPitch = client.player.getXRot();

        /*
         * Caso turn() seja ignorado por alguma implementação
         * de cliente, mantém o comportamento esperado.
         */
        if (Math.abs(
                Mth.wrapDegrees(
                        client.player.getYRot() - nextYaw
                )
        ) > 0.001f) {
            client.player.setYRot(nextYaw);
        }

        if (Math.abs(
                client.player.getXRot() - nextPitch
        ) > 0.001f) {
            client.player.setXRot(nextPitch);
        }

        currentYaw = client.player.getYRot();
        currentPitch = client.player.getXRot();

        pushHistory(
                currentYaw,
                currentPitch
        );
    }

    public static void applyGCDRotation(
            Minecraft client,
            double dYaw,
            double dPitch
    ) {
        if (client == null ||
                client.player == null ||
                client.options == null) {
            return;
        }

        double sensitivity =
                client.options.sensitivity().get() * 0.6D + 0.2D;

        if (sensitivity <= 0.0D) {
            return;
        }

        double gcd =
                sensitivity * sensitivity * sensitivity * 8.0D;

        if (gcd <= 0.0D) {
            return;
        }

        double effectiveGcd =
                sampledGcd > 0.001D
                        ? sampledGcd
                        : gcd;

        double roundedYaw =
                Math.round(dYaw / effectiveGcd)
                        * effectiveGcd;

        double roundedPitch =
                Math.round(dPitch / effectiveGcd)
                        * effectiveGcd;

        /*
         * Jitter opcional.
         *
         * Com magnitude 0, não adiciona ruído.
         */
        double noiseYaw = 0.0D;
        double noisePitch = 0.0D;

        if (jitterMagnitude > 0.0D) {
            noiseYaw =
                    (RANDOM.nextDouble() - 0.5D)
                            * jitterMagnitude;

            noisePitch =
                    (RANDOM.nextDouble() - 0.5D)
                            * jitterMagnitude;
        }

        client.player.turn(
                (roundedYaw + noiseYaw) / 0.15D,
                (roundedPitch + noisePitch) / 0.15D
        );
    }

    private static boolean canRotate(
            Minecraft client,
            Vec3 target
    ) {
        return client != null &&
                client.player != null &&
                target != null &&
                !locked;
    }

    private static float[] computeDesiredAngles(
            Minecraft client,
            Vec3 target
    ) {
        double dx =
                target.x - client.player.getX();

        double dy =
                target.y - client.player.getEyeY();

        double dz =
                target.z - client.player.getZ();

        double horizontalDistance =
                Math.max(
                        1.0E-9D,
                        Math.sqrt(dx * dx + dz * dz)
                );

        float yaw =
                (float) (
                        Math.toDegrees(
                                Math.atan2(dz, dx)
                        ) - 90.0D
                );

        float pitch =
                (float) (
                        -Math.toDegrees(
                                Math.atan2(
                                        dy,
                                        horizontalDistance
                                )
                        )
                );

        return new float[]{
                Mth.wrapDegrees(yaw),
                Mth.clamp(
                        pitch,
                        minPitch,
                        maxPitch
                )
        };
    }

    private static float[] computeStepByMode(
            float yawDiff,
            float pitchDiff,
            float factor
    ) {
        factor = Mth.clamp(
                factor,
                0.0f,
                1.0f
        );

        return switch (easingMode) {

            case LINEAR -> new float[]{
                    yawDiff * factor,
                    pitchDiff * factor
            };

            case EASE_IN_OUT_CUBIC -> {
                float angle =
                        (float) Math.sqrt(
                                yawDiff * yawDiff
                                        + pitchDiff * pitchDiff
                        );

                float t =
                        Mth.clamp(
                                angle / 25.0f,
                                0.0f,
                                1.0f
                        );

                float eased;

                if (t < 0.5f) {
                    eased = 4.0f * t * t * t;
                } else {
                    eased =
                            1.0f
                                    - (float)
                                    (
                                            Math.pow(
                                                    -2.0f * t + 2.0f,
                                                    3.0D
                                            ) / 2.0D
                                    );
                }

                yield new float[]{
                        yawDiff * eased * factor,
                        pitchDiff * eased * factor
                };
            }

            case EASE_OUT_EXPO -> {
                float angle =
                        (float) Math.sqrt(
                                yawDiff * yawDiff
                                        + pitchDiff * pitchDiff
                        );

                float near =
                        angle < 8.0f
                                ? 0.28f
                                + 0.72f * angle / 8.0f
                                : 1.0f;

                float expYaw =
                        yawDiff == 0.0f
                                ? 0.0f
                                : (float)
                                (
                                        Math.signum(yawDiff)
                                                * (
                                                1.0D
                                                        - Math.pow(
                                                        2.0D,
                                                        -10.0D
                                                                * Math.abs(yawDiff)
                                                                / 45.0D
                                                )
                                        )
                                );

                float expPitch =
                        pitchDiff == 0.0f
                                ? 0.0f
                                : (float)
                                (
                                        Math.signum(pitchDiff)
                                                * (
                                                1.0D
                                                        - Math.pow(
                                                        2.0D,
                                                        -10.0D
                                                                * Math.abs(pitchDiff)
                                                                / 30.0D
                                                )
                                        )
                                );

                float amount =
                        Math.min(
                                0.92f,
                                factor + 0.08f
                        );

                yield new float[]{
                        yawDiff
                                * Math.abs(expYaw)
                                * amount
                                * near,

                        pitchDiff
                                * Math.abs(expPitch)
                                * amount
                                * near
                };
            }

            case KINEMATIC_SPRING -> {
                float forceYaw =
                        (float)
                                (
                                        yawDiff * springStiffness
                                                - yawVelocity
                                                * springDamping
                                );

                float forcePitch =
                        (float)
                                (
                                        pitchDiff * springStiffness
                                                - pitchVelocity
                                                * springDamping
                                );

                yawVelocity += forceYaw * factor;
                pitchVelocity += forcePitch * factor;

                yawVelocity =
                        Mth.clamp(
                                yawVelocity,
                                -maxYawStep,
                                maxYawStep
                        );

                pitchVelocity =
                        Mth.clamp(
                                pitchVelocity,
                                -maxPitchStep,
                                maxPitchStep
                        );

                float angle =
                        (float) Math.sqrt(
                                yawDiff * yawDiff
                                        + pitchDiff * pitchDiff
                        );

                float near =
                        angle < 8.0f
                                ? 0.28f
                                + 0.72f * angle / 8.0f
                                : 1.0f;

                yield new float[]{
                        yawVelocity * near,
                        pitchVelocity * near
                };
            }

            case SWIGHT_HIGH_SENS -> {
                float angle =
                        (float) Math.sqrt(
                                yawDiff * yawDiff
                                        + pitchDiff * pitchDiff
                        );

                float near =
                        angle < 8.0f
                                ? 0.28f
                                + 0.72f * angle / 8.0f
                                : 1.0f;

                float rawT =
                        Mth.clamp(
                                                                angle / 18.0f,
                                0.0f,
                                1.0f
                        );

                float eased =
                        1.0f
                                - (
                                1.0f - rawT
                        ) * (
                                1.0f - rawT
                        ) * (
                                1.0f - rawT
                        );

                float aggressiveFactor =
                        Math.min(
                                0.98f,
                                factor + 0.15f
                        );

                yield new float[]{
                        yawDiff
                                * eased
                                * aggressiveFactor
                                * near,

                        pitchDiff
                                * eased
                                * aggressiveFactor
                                * near
                };
            }
        };
    }

    public static float computeYawError(
            Minecraft client,
            Vec3 target
    ) {
        if (client == null ||
                client.player == null ||
                target == null) {
            return 0.0f;
        }

        double dx =
                target.x - client.player.getX();

        double dz =
                target.z - client.player.getZ();

        float desired =
                (float)
                        Math.toDegrees(
                                Math.atan2(dz, dx)
                        ) - 90.0f;

        return Mth.wrapDegrees(
                desired - client.player.getYRot()
        );
    }

    public static float computePitchError(
            Minecraft client,
            Vec3 target
    ) {
        if (client == null ||
                client.player == null ||
                target == null) {
            return 0.0f;
        }

        double dx =
                target.x - client.player.getX();

        double dy =
                target.y - client.player.getEyeY();

        double dz =
                target.z - client.player.getZ();

        double horizontal =
                Math.max(
                        1.0E-9D,
                        Math.sqrt(dx * dx + dz * dz)
                );

        float desired =
                (float)
                        -Math.toDegrees(
                                Math.atan2(
                                        dy,
                                        horizontal
                                )
                        );

        return desired - client.player.getXRot();
    }

    public static boolean isAligned(
            Minecraft client,
            Vec3 target,
            float yawTolerance,
            float pitchTolerance
    ) {
        if (client == null ||
                client.player == null ||
                target == null) {
            return false;
        }

        return Math.abs(
                computeYawError(client, target)
        ) <= yawTolerance
                &&
                Math.abs(
                        computePitchError(client, target)
                ) <= pitchTolerance;
    }

    public static boolean isStable() {
        return stableTickCount >= stabilityThreshold;
    }

    public static void saveRotation(Minecraft client) {
        if (client == null ||
                client.player == null) {
            return;
        }

        savedPlayerYaw =
                client.player.getYRot();

        savedPlayerPitch =
                client.player.getXRot();

        hasSavedRotation = true;
    }

    public static void restoreRotation(Minecraft client) {
        if (!hasSavedRotation ||
                client == null ||
                client.player == null) {
            return;
        }

        float deltaYaw =
                Mth.wrapDegrees(
                        savedPlayerYaw
                                - client.player.getYRot()
                );

        float deltaPitch =
                savedPlayerPitch
                        - client.player.getXRot();

        applyGCDRotation(
                client,
                deltaYaw,
                deltaPitch
        );

        client.player.setYRot(savedPlayerYaw);
        client.player.setXRot(savedPlayerPitch);

        currentYaw = savedPlayerYaw;
        currentPitch = savedPlayerPitch;

        hasSavedRotation = false;

        yawVelocity = 0.0f;
        pitchVelocity = 0.0f;

        active = false;
        stableTickCount = 0;
    }

    public static void resetVelocity() {
        yawVelocity = 0.0f;
        pitchVelocity = 0.0f;
        stableTickCount = 0;
    }

    public static void lock() {
        locked = true;
    }

    public static void unlock() {
        locked = false;
    }

    public static void reset() {
        active = false;
        locked = false;

        yawVelocity = 0.0f;
        pitchVelocity = 0.0f;

        stableTickCount = 0;
        hasSavedRotation = false;

        sampledGcd = 0.0D;
        gcdSamples = 0;

        YAW_HISTORY.clear();
        PITCH_HISTORY.clear();
        TIMESTAMP_HISTORY.clear();
    }

    private static void pushHistory(
            float yaw,
            float pitch
    ) {
        if (YAW_HISTORY.size() >= HISTORY_CAP) {
            YAW_HISTORY.pollFirst();
            PITCH_HISTORY.pollFirst();
            TIMESTAMP_HISTORY.pollFirst();
        }

        YAW_HISTORY.offerLast(yaw);
        PITCH_HISTORY.offerLast(pitch);
        TIMESTAMP_HISTORY.offerLast(
                System.currentTimeMillis()
        );
    }

    public static float getYawVelocityEstimate() {
        if (YAW_HISTORY.size() < 2) {
            return 0.0f;
        }

        Float[] values =
                YAW_HISTORY.toArray(new Float[0]);

        return Mth.wrapDegrees(
                values[values.length - 1]
                        - values[values.length - 2]
        );
    }

    public static float getPitchVelocityEstimate() {
        if (PITCH_HISTORY.size() < 2) {
            return 0.0f;
        }

        Float[] values =
                PITCH_HISTORY.toArray(new Float[0]);

        return values[values.length - 1]
                - values[values.length - 2];
    }

    public static void setEasingMode(
        EasingMode mode
) {
    if (mode == null) {
        return;
    }

    easingMode = mode;

    ROTATION_REGISTRY.put(
            "EasingMode",
            easingMode.name()
    );
}

public static void setPriority(
        RotationPriority newPriority
) {
    if (newPriority != null) {
        priority = newPriority;
    }
}

public static void setStabilityThreshold(
        int ticks
) {
    stabilityThreshold =
            Math.max(1, ticks);
}

public static void setStabilityTolerances(
        float yawTolerance,
        float pitchTolerance
) {
    stabilityYawTolerance =
            Math.max(0.0f, yawTolerance);

    stabilityPitchTolerance =
            Math.max(0.0f, pitchTolerance);
}

public static void setJitterMagnitude(
        double magnitude
) {
    jitterMagnitude =
            Math.max(0.0D, magnitude);
}

public static void setSpringParameters(
        double stiffness,
        double damping
) {
    springStiffness =
            Math.max(0.01D, stiffness);

    springDamping =
            Math.max(0.01D, damping);

    ROTATION_REGISTRY.put(
            "SpringStiffness",
            springStiffness
    );

    ROTATION_REGISTRY.put(
            "SpringDamping",
            springDamping
    );
}

public static void setDeadzoneThreshold(
        double threshold
) {
    deadzoneThreshold =
            Math.max(0.001D, threshold);
}

public static void setMaxSteps(
        float maxYaw,
        float maxPitch
) {
    maxYawStep =
            Math.max(0.1f, maxYaw);

    maxPitchStep =
            Math.max(0.1f, maxPitch);
}

public static boolean isActive() {
    return active;
}

public static boolean isLocked() {
    return locked;
}

public static float getCurrentYaw() {
    return currentYaw;
}

public static float getCurrentPitch() {
    return currentPitch;
}

public static float getLastTargetYaw() {
    return lastTargetYaw;
}

public static float getLastTargetPitch() {
    return lastTargetPitch;
}

public static long getTotalInvocations() {
    return totalRotationInvocations;
}

public static int getHistorySize() {
    return YAW_HISTORY.size();
}

public static EasingMode getEasingMode() {
    return easingMode;
}

public static RotationPriority getPriority() {
    return priority;
}

public static UUID getSubsessionIdentity() {
    return SUBSESSION_ID;
}

public static double getSampledGcd() {
    return sampledGcd;
}

public static double getGcdSensitivity() {
    return gcdSensitivity;
}

public static float getStabilityYawTolerance() {
    return stabilityYawTolerance;
}

public static float getStabilityPitchTolerance() {
    return stabilityPitchTolerance;
}

public static void syncRotation(
        Minecraft client
) {
    if (client == null ||
            client.player == null ||
            client.getConnection() == null) {
        return;
    }

    client.getConnection().send(
            new ServerboundMovePlayerPacket.Rot(
                    client.player.getYRot(),
                    client.player.getXRot(),
                    client.player.onGround()
            )
    );
}
