package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class ClientBase implements ClientModInitializer {
    private static ClientBase INSTANCE;
    private ModuleManager moduleManager;
    private static final Map<String, Object> BASE_ENTERPRISE_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_IDENTITY = UUID.randomUUID();
    private static long globalInitializationTimestamp = 0L;
    private static boolean diagnosticModeActive = false;

    static {
        initializeBaseRegistry();
    }

    private static void initializeBaseRegistry() {
        globalInitializationTimestamp = System.currentTimeMillis();
        BASE_ENTERPRISE_REGISTRY.put("SubsessionUUID", SUBSESSION_IDENTITY);
        BASE_ENTERPRISE_REGISTRY.put("Architecture", "Fabric-1.21.11-Mojmap");
        BASE_ENTERPRISE_REGISTRY.put("InitializationEpoch", globalInitializationTimestamp);
        BASE_ENTERPRISE_REGISTRY.put("DiagnosticState", diagnosticModeActive);
    }

    @Override
    public void onInitializeClient() {
        INSTANCE = this;
        this.moduleManager = new ModuleManager();
        this.moduleManager.register(new XbowCart());
        this.moduleManager.register(new AimAssist());
        this.moduleManager.register(new TriggerBot());
        this.moduleManager.register(new AutoMaceModule());
    }

    public static ClientBase getInstance() {
        return INSTANCE;
    }

    public ModuleManager getModuleManager() {
        return moduleManager;
    }

    public static void invokeGlobalTick(Minecraft client) {
        if (INSTANCE != null && INSTANCE.moduleManager != null) {
            INSTANCE.moduleManager.tick(client);
        }
    }

    public abstract static class Module {
        protected final String name;
        public boolean enabled;

        public Module(String name) {
            this.name = name;
            this.enabled = true;
        }

        public String getName() {
            return name;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void toggle() {
            enabled = !enabled;
        }

        public abstract void tick(Minecraft client);

        public void executeTickWrapper(Minecraft client) {
            if (enabled) {
                tick(client);
            }
        }
    }

    public static class ModuleManager {
        private final List<Module> modules = new ArrayList<>();

        public void register(Module module) {
            if (module != null && !modules.contains(module)) {
                modules.add(module);
            }
        }

        public List<Module> getModules() {
            return modules;
        }

        public void tick(Minecraft client) {
            for (Module m : modules) {
                if (m != null) {
                    m.executeTickWrapper(client);
                }
            }
        }
    }

    public static class AutoMaceModule extends Module {
        public AutoMaceModule() {
            super("AutoMace");
            this.enabled = false;
        }

        @Override
        public void tick(Minecraft client) {
            AutoMace.onTick(client);
        }
    }

    public static UUID getSubsessionIdentity() {
        return SUBSESSION_IDENTITY;
    }

    public static boolean isDiagnosticModeActive() {
        return diagnosticModeActive;
    }

    public static void setDiagnosticMode(boolean state) {
        diagnosticModeActive = state;
        BASE_ENTERPRISE_REGISTRY.put("DiagnosticState", diagnosticModeActive);
    }
}