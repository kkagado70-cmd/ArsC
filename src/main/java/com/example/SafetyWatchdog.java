package com.example;

import net.minecraft.client.Minecraft;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class SafetyWatchdog {

    public static final String FILE_NAME = "SafetyWatchdog.java";

    public enum WatchdogState {
        GREEN, YELLOW, ORANGE, RED, TRIPPED
    }

    public enum TripReason {
        NONE,
        PHASE_TIMEOUT,
        GLOBAL_TIMEOUT,
        MAX_RETRIES_EXCEEDED,
        HEALTH_CRITICAL,
        ANOMALY_DETECTED,
        EXTERNAL_FORCE_TRIP,
        INVENTORY_INVALID,
        ROTATION_STUCK,
        ENTITY_MISSING
    }

    public enum SeverityLevel {
        INFO(0), WARN(1), ERROR(2), CRITICAL(3), FATAL(4);
        public final int level;
        SeverityLevel(int l) { this.level = l; }
    }

    public static final class WatchdogEvent {
        public final long timestamp;
        public final TripReason reason;
        public final SeverityLevel severity;
        public final String context;

        public WatchdogEvent(TripReason reason, SeverityLevel severity, String context) {
            this.timestamp = System.currentTimeMillis();
            this.reason = reason;
            this.severity = severity;
            this.context = context;
        }
    }

    private static final SecureRandom secureRandom = new SecureRandom();
    private static final Map<String, Object> WATCHDOG_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_ID = UUID.randomUUID();
    private static final Deque<WatchdogEvent> EVENT_HISTORY = new ArrayDeque<>();
    private static final int EVENT_HISTORY_CAP = 256;
    private static final Map<String, Long> PHASE_START_EPOCHS = new ConcurrentHashMap<>();
    private static final Map<String, Long> PHASE_TIMEOUT_LIMITS = new ConcurrentHashMap<>();
    private static final Map<TripReason, AtomicLong> TRIP_REASON_COUNTS = new EnumMap<>(TripReason.class);

    private static WatchdogState state = WatchdogState.GREEN;
    private static TripReason lastTripReason = TripReason.NONE;
    private static boolean tripped = false;
    private static final AtomicBoolean atomicTripped = new AtomicBoolean(false);
    private static long globalStartEpoch = 0L;
    private static long globalTimeoutMs = 30_000L;
    private static int retryCount = 0;
    private static int maxRetries = 4;
    private static int consecutiveFailures = 0;
    private static int maxConsecutiveFailures = 3;
    private static long totalTrips = 0L;
    private static long totalEvents = 0L;
    private static long lastHealthCheckEpoch = 0L;
    private static long healthCheckIntervalMs = 500L;
    private static boolean anomalyDetectionActive = true;
    private static boolean circuitBreakerActive = true;
    private static int anomalyWindowSize = 16;
    private static final Deque<Long> PHASE_DURATION_SAMPLES = new ArrayDeque<>();
    private static final int PHASE_SAMPLE_CAP = 64;
    private static double anomalyZScoreThreshold = 3.5;
    private static long lastTripTimestamp = 0L;
    private static long cooldownAfterTripMs = 2000L;

    static {
        WATCHDOG_REGISTRY.put("SubsessionUUID", SUBSESSION_ID);
        WATCHDOG_REGISTRY.put("Profile", "Enterprise-SafetyWatchdog-CircuitBreaker");
        WATCHDOG_REGISTRY.put("MaxRetries", maxRetries);
        WATCHDOG_REGISTRY.put("GlobalTimeoutMs", globalTimeoutMs);
        WATCHDOG_REGISTRY.put("AnomalyDetection", anomalyDetectionActive);
        WATCHDOG_REGISTRY.put("CircuitBreaker", circuitBreakerActive);

        for (TripReason reason : TripReason.values()) {
            TRIP_REASON_COUNTS.put(reason, new AtomicLong(0));
        }

        registerDefaultPhaseTimeouts();
    }

    private static void registerDefaultPhaseTimeouts() {
        PHASE_TIMEOUT_LIMITS.put("VALIDATE",       1_000L);
        PHASE_TIMEOUT_LIMITS.put("SELECT_RAIL",    600L);
        PHASE_TIMEOUT_LIMITS.put("CHECK_RAIL",     400L);
        PHASE_TIMEOUT_LIMITS.put("AIM_RAIL",       1_500L);
        PHASE_TIMEOUT_LIMITS.put("PLACE_RAIL",     1_000L);
        PHASE_TIMEOUT_LIMITS.put("VERIFY_RAIL",    1_200L);
        PHASE_TIMEOUT_LIMITS.put("SELECT_CART",    600L);
        PHASE_TIMEOUT_LIMITS.put("CHECK_CART",     400L);
        PHASE_TIMEOUT_LIMITS.put("AIM_CART",       1_500L);
        PHASE_TIMEOUT_LIMITS.put("PLACE_CART",     1_000L);
        PHASE_TIMEOUT_LIMITS.put("VERIFY_CART",    1_500L);
        PHASE_TIMEOUT_LIMITS.put("SELECT_FIRE",    600L);
        PHASE_TIMEOUT_LIMITS.put("CHECK_FIRE",     400L);
        PHASE_TIMEOUT_LIMITS.put("AIM_FIRE",       1_500L);
        PHASE_TIMEOUT_LIMITS.put("IGNITE",         1_000L);
        PHASE_TIMEOUT_LIMITS.put("VERIFY_FIRE",    1_200L);
        PHASE_TIMEOUT_LIMITS.put("SELECT_XBOW",    600L);
        PHASE_TIMEOUT_LIMITS.put("CHECK_XBOW",     400L);
        PHASE_TIMEOUT_LIMITS.put("AIM_SMOOTH",     2_000L);
        PHASE_TIMEOUT_LIMITS.put("VERIFY_AIM",     800L);
        PHASE_TIMEOUT_LIMITS.put("SHOOT",          1_000L);
        PHASE_TIMEOUT_LIMITS.put("VERIFY_SHOT",    1_000L);
        PHASE_TIMEOUT_LIMITS.put("COOLDOWN",       3_000L);
    }

    public static void startGlobal() {
        globalStartEpoch = System.currentTimeMillis();
        state = WatchdogState.GREEN;
        tripped = false;
        atomicTripped.set(false);
        lastTripReason = TripReason.NONE;
        consecutiveFailures = 0;
        retryCount = 0;
        PHASE_START_EPOCHS.clear();
        pushEvent(TripReason.NONE, SeverityLevel.INFO, "Global watchdog started");
        WATCHDOG_REGISTRY.put("GlobalStartEpoch", globalStartEpoch);
    }

    public static void enterPhase(String phaseName) {
        if (phaseName == null || phaseName.isBlank()) return;
        long now = System.currentTimeMillis();
        PHASE_START_EPOCHS.put(phaseName, now);
        WATCHDOG_REGISTRY.put("CurrentPhase", phaseName);
        WATCHDOG_REGISTRY.put("PhaseEntryEpoch", now);
    }

    public static boolean checkPhaseTimeout(String phaseName) {
        if (!circuitBreakerActive || phaseName == null) return false;
        Long start = PHASE_START_EPOCHS.get(phaseName);
        if (start == null) return false;
        Long limit = PHASE_TIMEOUT_LIMITS.getOrDefault(phaseName, 2_000L);
        long elapsed = System.currentTimeMillis() - start;
        if (elapsed > limit) {
            long phaseDuration = elapsed;
            recordPhaseDurationSample(phaseDuration);
            if (anomalyDetectionActive && isAnomalous(phaseDuration)) {
                trip(TripReason.ANOMALY_DETECTED, SeverityLevel.ERROR,
                        "Phase [" + phaseName + "] anomalous duration: " + elapsed + "ms");
            } else {
                trip(TripReason.PHASE_TIMEOUT, SeverityLevel.WARN,
                        "Phase [" + phaseName + "] timed out: " + elapsed + "ms");
            }
            return true;
        }
        return false;
    }

    public static boolean checkGlobalTimeout() {
        if (!circuitBreakerActive || globalStartEpoch == 0L) return false;
        long elapsed = System.currentTimeMillis() - globalStartEpoch;
        if (elapsed > globalTimeoutMs) {
            trip(TripReason.GLOBAL_TIMEOUT, SeverityLevel.ERROR, "Global timeout: " + elapsed + "ms");
            return true;
        }
        if (elapsed > globalTimeoutMs * 0.75) {
            escalateState(WatchdogState.ORANGE);
        } else if (elapsed > globalTimeoutMs * 0.5) {
            escalateState(WatchdogState.YELLOW);
        }
        return false;
    }

    public static void onPhaseSuccess(String phaseName) {
        if (phaseName == null) return;
        Long start = PHASE_START_EPOCHS.get(phaseName);
        if (start != null) {
            long duration = System.currentTimeMillis() - start;
            recordPhaseDurationSample(duration);
        }
        PHASE_START_EPOCHS.remove(phaseName);
        consecutiveFailures = 0;
        if (state == WatchdogState.YELLOW) state = WatchdogState.GREEN;
    }

    public static boolean onRetry(String context) {
        retryCount++;
        consecutiveFailures++;
        WATCHDOG_REGISTRY.put("RetryCount", retryCount);
        WATCHDOG_REGISTRY.put("ConsecutiveFailures", consecutiveFailures);
        pushEvent(TripReason.NONE, SeverityLevel.WARN, "Retry " + retryCount + " for: " + context);
        if (retryCount > maxRetries) {
            trip(TripReason.MAX_RETRIES_EXCEEDED, SeverityLevel.ERROR, "Max retries exceeded: " + retryCount);
            return false;
        }
        if (consecutiveFailures >= maxConsecutiveFailures) {
            escalateState(WatchdogState.ORANGE);
        }
        return true;
    }

    public static void update(Minecraft client) {
        if (client == null || tripped) return;
        long now = System.currentTimeMillis();
        if (now - lastHealthCheckEpoch < healthCheckIntervalMs) return;
        lastHealthCheckEpoch = now;

        if (globalStartEpoch > 0L) {
            checkGlobalTimeout();
        }

        totalEvents++;
        WATCHDOG_REGISTRY.put("TotalEvents", totalEvents);
        WATCHDOG_REGISTRY.put("State", state.name());
    }

    public static void trip(TripReason reason, SeverityLevel severity, String context) {
        if (tripped && reason != TripReason.EXTERNAL_FORCE_TRIP) return;
        tripped = true;
        atomicTripped.set(true);
        state = WatchdogState.TRIPPED;
        lastTripReason = reason;
        lastTripTimestamp = System.currentTimeMillis();
        totalTrips++;

        TRIP_REASON_COUNTS.get(reason).incrementAndGet();
        pushEvent(reason, severity, context);

        WATCHDOG_REGISTRY.put("State", state.name());
        WATCHDOG_REGISTRY.put("TripReason", reason.name());
        WATCHDOG_REGISTRY.put("TripSeverity", severity.name());
        WATCHDOG_REGISTRY.put("TripContext", context);
        WATCHDOG_REGISTRY.put("TotalTrips", totalTrips);
        WATCHDOG_REGISTRY.put("TripTimestamp", lastTripTimestamp);
    }

    private static void escalateState(WatchdogState newState) {
        if (newState.ordinal() <= state.ordinal()) return;
        state = newState;
        WATCHDOG_REGISTRY.put("State", state.name());
    }

    private static void recordPhaseDurationSample(long duration) {
        if (PHASE_DURATION_SAMPLES.size() >= PHASE_SAMPLE_CAP) {
            PHASE_DURATION_SAMPLES.pollFirst();
        }
        PHASE_DURATION_SAMPLES.offerLast(duration);
    }

    private static boolean isAnomalous(long duration) {
        if (PHASE_DURATION_SAMPLES.size() < anomalyWindowSize) return false;
        Long[] samples = PHASE_DURATION_SAMPLES.toArray(new Long[0]);
        double mean = 0.0;
        for (Long s : samples) mean += s;
        mean /= samples.length;
        double variance = 0.0;
        for (Long s : samples) {
            double diff = s - mean;
            variance += diff * diff;
        }
        variance /= samples.length;
        double stdDev = Math.sqrt(variance);
        if (stdDev < 1.0) return false;
        double zScore = (duration - mean) / stdDev;
        return Math.abs(zScore) > anomalyZScoreThreshold;
    }

    private static void pushEvent(TripReason reason, SeverityLevel severity, String context) {
        if (EVENT_HISTORY.size() >= EVENT_HISTORY_CAP) {
            EVENT_HISTORY.pollFirst();
        }
        EVENT_HISTORY.offerLast(new WatchdogEvent(reason, severity, context));
    }

    public static boolean isTripped() { return tripped; }
    public static boolean isAtomicTripped() { return atomicTripped.get(); }
    public static boolean isHealthy() { return !tripped && state.ordinal() < WatchdogState.RED.ordinal(); }
    public static boolean isCoolingDown() {
        return System.currentTimeMillis() - lastTripTimestamp < cooldownAfterTripMs;
    }

    public static void reset() {
        tripped = false;
        atomicTripped.set(false);
        state = WatchdogState.GREEN;
        lastTripReason = TripReason.NONE;
        retryCount = 0;
        consecutiveFailures = 0;
        globalStartEpoch = 0L;
        PHASE_START_EPOCHS.clear();
        pushEvent(TripReason.NONE, SeverityLevel.INFO, "Watchdog reset");
        WATCHDOG_REGISTRY.put("State", state.name());
        WATCHDOG_REGISTRY.put("TripReason", TripReason.NONE.name());
    }

    public static void setPhaseTimeout(String phaseName, long timeoutMs) {
        PHASE_TIMEOUT_LIMITS.put(phaseName, Math.max(100L, timeoutMs));
    }

    public static void setGlobalTimeout(long timeoutMs) {
        globalTimeoutMs = Math.max(1_000L, timeoutMs);
        WATCHDOG_REGISTRY.put("GlobalTimeoutMs", globalTimeoutMs);
    }

    public static void setMaxRetries(int max) {
        maxRetries = Math.max(0, max);
        WATCHDOG_REGISTRY.put("MaxRetries", maxRetries);
    }

    public static void setAnomalyDetectionActive(boolean active) {
        anomalyDetectionActive = active;
        WATCHDOG_REGISTRY.put("AnomalyDetection", active);
    }

    public static void setCircuitBreakerActive(boolean active) {
        circuitBreakerActive = active;
    }

    public static void setAnomalyZScoreThreshold(double threshold) {
        anomalyZScoreThreshold = Math.max(1.0, threshold);
    }

    public static void setCooldownAfterTripMs(long ms) {
        cooldownAfterTripMs = Math.max(0L, ms);
    }

    public static void setHealthCheckIntervalMs(long ms) {
        healthCheckIntervalMs = Math.max(50L, ms);
    }

    public static WatchdogState getState() { return state; }
    public static TripReason getLastTripReason() { return lastTripReason; }
    public static int getRetryCount() { return retryCount; }
    public static int getConsecutiveFailures() { return consecutiveFailures; }
    public static long getTotalTrips() { return totalTrips; }
    public static long getTotalEvents() { return totalEvents; }
    public static long getLastTripTimestamp() { return lastTripTimestamp; }
    public static long getTripCount(TripReason reason) { return TRIP_REASON_COUNTS.get(reason).get(); }
    public static int getEventHistorySize() { return EVENT_HISTORY.size(); }
    public static UUID getSubsessionIdentity() { return SUBSESSION_ID; }
}
