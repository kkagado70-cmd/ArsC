package com.example;

public final class HandlerManager {

    private static boolean initialized = false;

    private HandlerManager() {
    }

    public static void initialize() {
        if (initialized) {
            return;
        }

        initialized = true;

        InventoryManager.register();
        AimAssist.register();
        AutoMace.register();
        TriggerBot.register();
        ShieldBreaker.register();
        XbowCart.register();
    }

    public static void shutdown() {
        initialized = false;
    }
}
