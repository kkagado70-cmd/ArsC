package com.example;

/**
 * Watchdog de segurança para operações automatizadas.
 * Limita retries e fornece ponto central de "trip" (parada de emergência).
 */
public final class SafetyWatchdog {
    private static final int MAX_RETRIES = 12;
    private static int retries = 0;
    private static boolean tripped = false;

    public enum TripReason {
        RETRY_LIMIT_EXCEEDED,
        EXTERNAL_FORCE_TRIP,
        EXCEPTION_CAUGHT,
        TIMEOUT,
        INVENTORY_ERROR,
        ROTATION_ERROR
    }

    public enum SeverityLevel {
        INFO,
        WARN,
        ERROR,
        CRITICAL
    }

    private SafetyWatchdog() {}

    public static void startGlobal() {
        retries = 0;
        tripped = false;
    }

    public static boolean onRetry(String phase) {
        if (++retries <= MAX_RETRIES) return true;
        trip(TripReason.RETRY_LIMIT_EXCEEDED, SeverityLevel.WARN, "Max retries at: " + phase);
        return false;
    }

    public static void trip(TripReason reason, SeverityLevel severity, String message) {
        tripped = true;
        // Log para depuração — não impede execução de outros módulos
        if (severity == SeverityLevel.ERROR || severity == SeverityLevel.CRITICAL) {
            System.err.println("[SafetyWatchdog][" + severity + "] " + reason + ": " + message);
        }
    }

    public static void reset() {
        retries = 0;
        tripped = false;
    }

    public static boolean isTripped() { return tripped; }
    public static int getRetries()    { return retries; }
}
