package com.example;

/**
 * Bootstrap de compatibilidade.
 *
 * Os módulos são registrados pelo ModuleManager em ClientBase e executados
 * pelo tick central. Este ponto permanece para código legado que ainda chama
 * HandlerManager.initialize(), mas não registra callbacks Fabric duplicados.
 */
public final class HandlerManager {
    private static boolean initialized = false;

    private HandlerManager() {}

    public static void initialize() {
        initialized = true;
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static void shutdown() {
        initialized = false;
    }
}
