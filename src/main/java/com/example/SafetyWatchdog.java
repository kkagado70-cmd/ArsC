package com.example;

public class SafetyWatchdog {
    private long startEpoch = 0L;
    private long timeoutLimitMs = 1500L;
    private boolean armedState = false;
    private int exceptionCounter = 0;
    private static final int MAX_EXCEPTIONS = 5;
    private long lastHeartbeat = 0L;

    public void arm() {
        startEpoch = System.currentTimeMillis();
        lastHeartbeat = startEpoch;
        armedState = true;
        exceptionCounter = 0;
    }

    public void arm(long customTimeoutMs) {
        timeoutLimitMs = customTimeoutMs;
        startEpoch = System.currentTimeMillis();
        lastHeartbeat = startEpoch;
        armedState = true;
        exceptionCounter = 0;
    }

    public boolean isTimedOut() {
        if (!armedState || startEpoch == 0L) return false;
        return System.currentTimeMillis() - startEpoch > timeoutLimitMs;
    }

    public void disarm() {
        startEpoch = 0L;
        lastHeartbeat = 0L;
        armedState = false;
        exceptionCounter = 0;
    }

    public void registerException() {
        exceptionCounter++;
        if (exceptionCounter >= MAX_EXCEPTIONS) {
            disarm();
        }
    }

    public boolean isArmed() {
        return armedState;
    }

    public long fetchElapsedTime() {
        if (!armedState || startEpoch == 0L) return 0L;
        return System.currentTimeMillis() - startEpoch;
    }

    public void resetWatchdog() {
        disarm();
    }

    public static void performSystemSanityCheck() {
        Runtime runtime = Runtime.getRuntime();
        long allocatedMemory = runtime.totalMemory() - runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        if ((double) allocatedMemory / maxMemory > 0.92D) {
            System.gc();
        }
    }

    public void extendTimeout(long additionalMs) {
        if (armedState) {
            timeoutLimitMs += additionalMs;
        }
    }

    public boolean validateExecutionSafety() {
        return !isTimedOut() && exceptionCounter < MAX_EXCEPTIONS;
    }

    public void stepCycle() {
        if (isTimedOut()) {
            disarm();
        }
        if (armedState) {
            long now = System.currentTimeMillis();
            if (now - lastHeartbeat > 2000L) {
                disarm();
            }
        }
    }

    public void heartbeat() {
        lastHeartbeat = System.currentTimeMillis();
    }

    public int getExceptionCount() {
        return exceptionCounter;
    }

    public void setTimeoutLimit(long limitMs) {
        this.timeoutLimitMs = limitMs;
    }

    public long getTimeoutLimit() {
        return timeoutLimitMs;
    }

    public boolean isStalled(long stallThresholdMs) {
        if (!armedState) return false;
        return System.currentTimeMillis() - lastHeartbeat > stallThresholdMs;
    }

    public void forceTriggerTimeout() {
        if (armedState) {
            startEpoch = System.currentTimeMillis() - timeoutLimitMs - 1L;
        }
    }
}