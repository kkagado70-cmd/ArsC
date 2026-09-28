package com.example;

/**
 * Limita retries das sequências automatizadas para impedir loops infinitos.
 */
public final class SafetyWatchdog {
    private static final int MAX_RETRIES = 12;
    private static int retries = 0;

    private SafetyWatchdog() {}

    public static void startGlobal() {
        retries = 0;
    }

    public static boolean onRetry(String phase) {
        return ++retries <= MAX_RETRIES;
    }

    public static void reset() {
        retries = 0;
    }
}
