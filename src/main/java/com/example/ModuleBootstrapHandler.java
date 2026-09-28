package com.example;

/**
 * Ponto de compatibilidade para código que ainda referencia ModuleBootstrapHandler.
 */
public final class ModuleBootstrapHandler {
    private ModuleBootstrapHandler() {}

    public static void initialize() {
        HandlerManager.initialize();
    }
}
