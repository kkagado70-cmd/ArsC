package com.example;

/**
 * Bootstrap central dos módulos.
 *
 * O código anterior dependia de com.arsenal.client.event.* e de vários
 * handlers que não estão presentes neste source set. Como os módulos já
 * expõem register() baseado no Fabric ClientTickEvents, eles são registrados
 * diretamente aqui.
 */
public final class HandlerManager {
    private static boolean initialized = false;

    public HandlerManager() {}

    public static void initialize() {
    if (initialized) return;
    initialized = true;

    InventoryManager.register();
    AimAssist.register();
    AutoMace.register();
    TriggerBot.register();
    ShieldBreaker.register();
    XbowCart.register();
    }

    public void shutdown() {
        // Não há API pública simples para remover os callbacks do Fabric
        // depois de registrados. A flag impede registros duplicados.
        initialized = false;
    }
}
