package com.example;

/**
 * Bootstrap central.
 *
 * ClientBase.ModuleManager é responsável por executar os módulos a cada tick.
 * HandlerManager registra apenas subsistemas de suporte (InventoryManager).
 * Os módulos NÃO se registram via ClientTickEvents para evitar execução dupla.
 *
 * Cadeia única:
 *   Fabric Tick → ClientBase → ModuleManager → Module.tick() → módulo real
 */
public final class HandlerManager {
    private static boolean initialized = false;

    private HandlerManager() {}

    public static void initialize() {
        if (initialized) return;
        initialized = true;

        // Apenas o subsistema de inventário/suporte se registra aqui.
        // Os módulos (AimAssist, AutoMace, etc.) são executados pelo
        // ClientBase.ModuleManager — não registrar novamente para evitar execução dupla.
        InventoryManager.register();
    }

    public static void shutdown() {
        initialized = false;
    }

    public static boolean isInitialized() {
        return initialized;
    }
}
