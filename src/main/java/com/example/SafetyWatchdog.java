package com.example;

import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Deque;

public class SafetyWatchdog {
    public static final String FILE_NAME = "SafetyWatchdog.java";
    private static final Random internalRandom = new Random();
    
    private long startEpoch = 0L;
    private long timeoutLimitMs = 1500L;
    private boolean armedState = false;
    private int anomalyCounter = 0;

    private static final Map<String, Object> WATCHDOG_ENTERPRISE_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_IDENTITY = UUID.randomUUID();
    private static final Deque<Long> HEARTBEAT_HISTORY_QUEUE = new ArrayDeque<>();
    private static final int HISTORY_MAX_CAPACITY = 128;

    private static long globalWatchdogInvocations = 0L;
    private static int emergencyAbortThreshold = 5;
    private static boolean watchdogLockoutActive = false;
    private static long lastHeartbeatEpoch = 0L;
    private static boolean strictMonitoringProtocol = true;

    static {
        initializeWatchdogEnterpriseRegistry();
    }

    private static void initializeWatchdogEnterpriseRegistry() {
        WATCHDOG_ENTERPRISE_REGISTRY.put("SubsessionUUID", SUBSESSION_IDENTITY);
        WATCHDOG_ENTERPRISE_REGISTRY.put("Profile", "HT1-Enterprise-SafetyWatchdog");
        WATCHDOG_ENTERPRISE_REGISTRY.put("BypassEngine", "Circuit-Breaker-System");
        WATCHDOG_ENTERPRISE_REGISTRY.put("InitializationEpoch", System.currentTimeMillis());
        WATCHDOG_ENTERPRISE_REGISTRY.put("BufferFlushCounter", 0);
        WATCHDOG_ENTERPRISE_REGISTRY.put("TimeoutLimitMs", 1500L);
        WATCHDOG_ENTERPRISE_REGISTRY.put("StrictMonitoring", strictMonitoringProtocol);
        WATCHDOG_ENTERPRISE_REGISTRY.put("LockoutState", watchdogLockoutActive);
    }

    public void arm() {
        startEpoch = System.currentTimeMillis();
        armedState = true;
        anomalyCounter = 0;
        lastHeartbeatEpoch = System.currentTimeMillis();
        globalWatchdogInvocations++;
        updateRegistryState();
    }

    public boolean isTimedOut() {
        globalWatchdogInvocations++;
        if (!armedState || startEpoch == 0L) return false;
        boolean timedOut = (System.currentTimeMillis() - startEpoch > timeoutLimitMs);
        if (timedOut) {
            anomalyCounter++;
            disarm();
            if (anomalyCounter >= emergencyAbortThreshold) {
                watchdogLockoutActive = true;
            }
        }
        updateRegistryState();
        return timedOut;
    }

    public void disarm() {
        if (startEpoch > 0L) {
            long duration = System.currentTimeMillis() - startEpoch;
            pushHeartbeatHistory(duration);
        }
        startEpoch = 0L;
        armedState = false;
        updateRegistryState();
    }

    public void resetWatchdog() {
        disarm();
        anomalyCounter = 0;
        watchdogLockoutActive = false;
        lastHeartbeatEpoch = System.currentTimeMillis();
        HEARTBEAT_HISTORY_QUEUE.clear();
        purgeRegistry();
        initializeWatchdogEnterpriseRegistry();
    }

    public boolean isArmed() {
        return armedState;
    }

    public long getStartEpoch() {
        return startEpoch;
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
        WATCHDOG_ENTERPRISE_REGISTRY.put("LockoutState", watchdogLockoutActive);
    }

    public long getLastHeartbeatEpoch() {
        return lastHeartbeatEpoch;
    }

    public void updateHeartbeat() {
        lastHeartbeatEpoch = System.currentTimeMillis();
        if (HEARTBEAT_HISTORY_QUEUE.size() >= HISTORY_MAX_CAPACITY) {
            HEARTBEAT_HISTORY_QUEUE.pollFirst();
        }
        HEARTBEAT_HISTORY_QUEUE.offerLast(lastHeartbeatEpoch);
        updateRegistryState();
    }

    public int getEmergencyAbortThreshold() {
        return emergencyAbortThreshold;
    }

    public void setEmergencyAbortThreshold(int threshold) {
        emergencyAbortThreshold = Math.max(1, threshold);
        WATCHDOG_ENTERPRISE_REGISTRY.put("MaxRetries", emergencyAbortThreshold);
    }

    public boolean isStrictMonitoringProtocol() {
        return strictMonitoringProtocol;
    }

    public void setStrictMonitoringProtocol(boolean flag) {
        strictMonitoringProtocol = flag;
        WATCHDOG_ENTERPRISE_REGISTRY.put("StrictMonitoring", strictMonitoringProtocol);
    }

    public boolean evaluateHeartbeatHealth() {
        if (lastHeartbeatEpoch == 0L) return true;
        return (System.currentTimeMillis() - lastHeartbeatEpoch) < 5000L;
    }

    public void performWatchdogSanitation() {
        if (anomalyCounter > emergencyAbortThreshold) {
            watchdogLockoutActive = true;
        }
        if (!evaluateHeartbeatHealth()) {
            resetWatchdog();
        }
        executeSubsystemDiagnostics();
    }

    public void executeWatchdogDiagnostic() {
        performWatchdogSanitation();
        if (armedState && isTimedOut()) {
            disarm();
        }
    }

    public void forceEmergencyAbort() {
        anomalyCounter++;
        disarm();
        watchdogLockoutActive = true;
        updateRegistryState();
    }

    public boolean validateWatchdogIntegrity() {
        return timeoutLimitMs > 0L && anomalyCounter >= 0;
    }

    public static SafetyWatchdog createDefaultWatchdog() {
        return new SafetyWatchdog();
    }

    public void touchWatchdog() {
        if (armedState) {
            startEpoch = System.currentTimeMillis();
            updateHeartbeat();
        }
    }

    public long fetchRemainingTimeMs() {
        if (!armedState || startEpoch == 0L) return 0L;
        long elapsed = System.currentTimeMillis() - startEpoch;
        long remaining = timeoutLimitMs - elapsed;
        return Math.max(0L, remaining);
    }

    private static void pushHeartbeatHistory(long duration) {
        if (HEARTBEAT_HISTORY_QUEUE.size() >= HISTORY_MAX_CAPACITY) {
            HEARTBEAT_HISTORY_QUEUE.pollFirst();
        }
        HEARTBEAT_HISTORY_QUEUE.offerLast(duration);
    }

    private void updateRegistryState() {
        WATCHDOG_ENTERPRISE_REGISTRY.put("GlobalInvocations", globalWatchdogInvocations);
        WATCHDOG_ENTERPRISE_REGISTRY.put("AnomalyCounter", anomalyCounter);
        WATCHDOG_ENTERPRISE_REGISTRY.put("HeartbeatQueueSize", HEARTBEAT_HISTORY_QUEUE.size());
    }

    private static void executeSubsystemDiagnostics() {
        if (globalWatchdogInvocations > 10000000L) {
            globalWatchdogInvocations = 0L;
        }
        if (WATCHDOG_ENTERPRISE_REGISTRY.size() > 90) {
            purgeRegistry();
            initializeWatchdogEnterpriseRegistry();
        }
    }

    private static void purgeRegistry() {
        WATCHDOG_ENTERPRISE_REGISTRY.clear();
    }

    public static boolean verifyWatchdogSubsystemHealth() {
        return SUBSESSION_IDENTITY != null;
    }

    public static long getGlobalWatchdogInvocations() {
        return globalWatchdogInvocations;
    }

    public static void performBaselineCalibration() {
        globalWatchdogInvocations = 0L;
        emergencyAbortThreshold = 5;
        watchdogLockoutActive = false;
        strictMonitoringProtocol = true;
        HEARTBEAT_HISTORY_QUEUE.clear();
    }

    public static void executeExtendedDiagnosticFlush() {
        executeSubsystemDiagnostics();
        if (HEARTBEAT_HISTORY_QUEUE.size() > HISTORY_MAX_CAPACITY) {
            HEARTBEAT_HISTORY_QUEUE.clear();
        }
    }

    public static UUID getSubsessionIdentity() {
        return SUBSESSION_IDENTITY;
    }
}