package com.example;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SafetyWatchdog {

    public static final String FILE_NAME = "SafetyWatchdog.java";

    private static final int HISTORY_MAX_CAPACITY = 128;
    private static final long DEFAULT_TIMEOUT_MS = 1500L;
    private static final long HEARTBEAT_TIMEOUT_MS = 5000L;

    private final Map<String, Object> watchdogRegistry = new ConcurrentHashMap<>();
    private final Deque<Long> heartbeatHistoryQueue = new ArrayDeque<>();
    private final UUID subsessionIdentity = UUID.randomUUID();

    private long startNanoTime = 0L;
    private long lastHeartbeatNanoTime = 0L;

    private long timeoutLimitMs = DEFAULT_TIMEOUT_MS;

    private boolean armedState = false;
    private boolean watchdogLockoutActive = false;
    private boolean strictMonitoringProtocol = true;

    private int anomalyCounter = 0;
    private int emergencyAbortThreshold = 5;

    private long watchdogInvocations = 0L;

    public SafetyWatchdog() {
        initializeRegistry();
    }

    private void initializeRegistry() {
        watchdogRegistry.clear();

        watchdogRegistry.put("SubsessionUUID", subsessionIdentity);
        watchdogRegistry.put("TimeoutLimitMs", timeoutLimitMs);
        watchdogRegistry.put("AnomalyCounter", anomalyCounter);
        watchdogRegistry.put("LockoutState", watchdogLockoutActive);
        watchdogRegistry.put("StrictMonitoring", strictMonitoringProtocol);
        watchdogRegistry.put("InvocationCount", watchdogInvocations);
        watchdogRegistry.put("HeartbeatQueueSize", heartbeatHistoryQueue.size());
    }

    /**
     * Arms the watchdog and starts a fresh timeout window.
     */
    public void arm() {
        startNanoTime = System.nanoTime();
        lastHeartbeatNanoTime = startNanoTime;
        armedState = true;

        watchdogInvocations++;

        updateRegistryState();
    }

    /**
     * Returns true when the current watchdog window has expired.
     */
    public boolean isTimedOut() {
        watchdogInvocations++;

        if (!armedState || startNanoTime == 0L) {
            updateRegistryState();
            return false;
        }

        long elapsedMs = elapsedSince(startNanoTime);

        if (elapsedMs >= timeoutLimitMs) {
            anomalyCounter++;

            recordHeartbeatDuration(elapsedMs);
            disarmInternal();

            if (anomalyCounter >= emergencyAbortThreshold) {
                watchdogLockoutActive = true;
            }

            updateRegistryState();
            return true;
        }

        updateRegistryState();
        return false;
    }

    /**
     * Disarms the watchdog normally.
     */
    public void disarm() {
        if (armedState && startNanoTime != 0L) {
            long duration = elapsedSince(startNanoTime);
            recordHeartbeatDuration(duration);
        }

        disarmInternal();
        updateRegistryState();
    }

    private void disarmInternal() {
        armedState = false;
        startNanoTime = 0L;
    }

    /**
     * Completely resets watchdog state.
     */
    public void resetWatchdog() {
        armedState = false;
        startNanoTime = 0L;
        lastHeartbeatNanoTime = 0L;

        anomalyCounter = 0;
        watchdogLockoutActive = false;
        watchdogInvocations = 0L;

        heartbeatHistoryQueue.clear();

        initializeRegistry();
    }

    public boolean isArmed() {
        return armedState;
    }

    /**
     * Kept for API compatibility.
     *
     * This returns epoch milliseconds approximately corresponding
     * to when the watchdog was armed.
     */
    public long getStartEpoch() {
        if (!armedState || startNanoTime == 0L) {
            return 0L;
        }

        long elapsedMs = elapsedSince(startNanoTime);
        return System.currentTimeMillis() - elapsedMs;
    }

    public long getTimeoutMs() {
        return timeoutLimitMs;
    }

    public int getAnomalyCount() {
        return anomalyCounter;
    }

    public boolean isWatchdogLockout() {
        return watchdogLockoutActive;
    }

    public void setWatchdogLockout(boolean lockout) {
        watchdogLockoutActive = lockout;
        updateRegistryState();
    }

    public long getLastHeartbeatEpoch() {
        if (lastHeartbeatNanoTime == 0L) {
            return 0L;
        }

        long elapsedMs = elapsedSince(lastHeartbeatNanoTime);
        return System.currentTimeMillis() - elapsedMs;
    }

    /**
     * Records that the watchdog is still alive.
     *
     * IMPORTANT:
     * This does not reset the watchdog timeout.
     * It only updates heartbeat information.
     */
    public void updateHeartbeat() {
        lastHeartbeatNanoTime = System.nanoTime();

        heartbeatHistoryQueue.offerLast(
                System.currentTimeMillis()
        );

        while (heartbeatHistoryQueue.size() > HISTORY_MAX_CAPACITY) {
            heartbeatHistoryQueue.pollFirst();
        }

        updateRegistryState();
    }

    public int getEmergencyAbortThreshold() {
        return emergencyAbortThreshold;
    }

    public void setEmergencyAbortThreshold(int threshold) {
        emergencyAbortThreshold = Math.max(1, threshold);
        updateRegistryState();
    }

    public boolean isStrictMonitoringProtocol() {
        return strictMonitoringProtocol;
    }

    public void setStrictMonitoringProtocol(boolean flag) {
        strictMonitoringProtocol = flag;
        updateRegistryState();
    }

    /**
     * Checks whether the last heartbeat is still recent.
     */
    public boolean evaluateHeartbeatHealth() {
        if (lastHeartbeatNanoTime == 0L) {
            return !armedState;
        }

        return elapsedSince(lastHeartbeatNanoTime) < HEARTBEAT_TIMEOUT_MS;
    }

    /**
     * Performs lightweight health checks.
     */
    public void performWatchdogSanitation() {
        if (anomalyCounter >= emergencyAbortThreshold) {
            watchdogLockoutActive = true;
        }

        if (armedState && !evaluateHeartbeatHealth()) {
            anomalyCounter++;
            disarmInternal();

            if (anomalyCounter >= emergencyAbortThreshold) {
                watchdogLockoutActive = true;
            }
        }

        updateRegistryState();
    }

    /**
     * Performs a complete watchdog diagnostic pass.
     */
    public void executeWatchdogDiagnostic() {
        performWatchdogSanitation();

        if (armedState) {
            isTimedOut();
        }
    }

    /**
     * Immediately aborts the current operation.
     */
    public void forceEmergencyAbort() {
        anomalyCounter++;
        watchdogLockoutActive = true;

        disarmInternal();
        updateRegistryState();
    }

    public boolean validateWatchdogIntegrity() {
        return timeoutLimitMs > 0L
                && emergencyAbortThreshold > 0
                && anomalyCounter >= 0
                && startNanoTime >= 0L
                && lastHeartbeatNanoTime >= 0L;
    }

    public static SafetyWatchdog createDefaultWatchdog() {
        return new SafetyWatchdog();
    }

    /**
     * Updates the heartbeat timestamp without extending the timeout window.
     */
    public void touchWatchdog() {
        if (!armedState) {
            return;
        }

        updateHeartbeat();
    }

    /**
     * Returns the remaining timeout time.
     */
    public long fetchRemainingTimeMs() {
        if (!armedState || startNanoTime == 0L) {
            return 0L;
        }

        long elapsed = elapsedSince(startNanoTime);
        return Math.max(0L, timeoutLimitMs - elapsed);
    }

    private void recordHeartbeatDuration(long durationMs) {
        heartbeatHistoryQueue.offerLast(durationMs);

        while (heartbeatHistoryQueue.size() > HISTORY_MAX_CAPACITY) {
            heartbeatHistoryQueue.pollFirst();
        }
    }

    private long elapsedSince(long nanoStart) {
        long elapsedNanos = System.nanoTime() - nanoStart;

        if (elapsedNanos <= 0L) {
            return 0L;
        }

        return elapsedNanos / 1_000_000L;
    }

    private void updateRegistryState() {
        watchdogRegistry.put("TimeoutLimitMs", timeoutLimitMs);
        watchdogRegistry.put("AnomalyCounter", anomalyCounter);
        watchdogRegistry.put("HeartbeatQueueSize", heartbeatHistoryQueue.size());
        watchdogRegistry.put("InvocationCount", watchdogInvocations);
        watchdogRegistry.put("LockoutState", watchdogLockoutActive);
        watchdogRegistry.put("StrictMonitoring", strictMonitoringProtocol);
    }

    public boolean verifyWatchdogSubsystemHealth() {
        return subsessionIdentity != null && validateWatchdogIntegrity();
    }

    public long getGlobalWatchdogInvocations() {
        return watchdogInvocations;
    }

    /**
     * Restores safe default configuration.
     */
    public void performBaselineCalibration() {
        timeoutLimitMs = DEFAULT_TIMEOUT_MS;
        emergencyAbortThreshold = 5;
        watchdogLockoutActive = false;
        strictMonitoringProtocol = true;

        anomalyCounter = 0;
        watchdogInvocations = 0L;

        armedState = false;
        startNanoTime = 0L;
        lastHeartbeatNanoTime = 0L;

        heartbeatHistoryQueue.clear();

        initializeRegistry();
    }

    /**
     * Cleans history/diagnostic state without changing the current armed state.
     */
    public void executeExtendedDiagnosticFlush() {
        heartbeatHistoryQueue.clear();
        updateRegistryState();
    }

    public UUID getSubsessionIdentity() {
        return subsessionIdentity;
    }
}
