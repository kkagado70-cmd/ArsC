package com.example;

public final class ModuleBootstrapHandler {

    private ModuleBootstrapHandler() {
    }

    public static void initialize() {
        HandlerManager.initialize();
    }
}
