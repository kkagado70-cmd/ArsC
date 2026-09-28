package com.example;

/**
 * Mantido como ponto de compatibilidade para código que ainda referencia
 * ModuleBootstrapHandler.
 *
 * O antigo arquivo dependia de um sistema de eventos Arsenal que não existe
 * neste source set. O bootstrap agora é feito por HandlerManager.
 */
public final class ModuleBootstrapHandler {
    private ModuleBootstrapHandler() {}

    public static void initialize() {
        HandlerManager.initialize();
    }
}
