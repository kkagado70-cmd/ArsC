package com.example;

/**
 * Bootstrap guard for the client subsystems.
 *
 * Module execution is owned by ClientBase.ModuleManager.  This class must not
 * register the modules again with Fabric tick events, otherwise every module
 * would execute twice per tick.
 */
public final class HandlerManager {
    private static boolean initialized = false;

    private HandlerManager() {}

    public static void initialize() {
        initialized = true;
    }

    public static void shutdown() {
        initialized = false;
    }

    public static boolean isInitialized() {
        return initialized;
    }
}
