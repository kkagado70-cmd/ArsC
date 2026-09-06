package com.example;

public class SafetyWatchdog {
    public static final String FILE_NAME = "SafetyWatchdog.java";
    private long startEpoch = 0L;
    private long timeoutLimitMs = 1500L;
    private boolean armedState = false;

    public void arm() {
        startEpoch = System.currentTimeMillis();
        armedState = true;
    }

    public boolean isTimedOut() {
        if (!armedState || startEpoch == 0L) return false;
        return System.currentTimeMillis() - startEpoch > timeoutLimitMs;
    }

    public void disarm() {
        startEpoch = 0L;
        armedState = false;
    }
}