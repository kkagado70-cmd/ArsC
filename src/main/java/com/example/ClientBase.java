package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ClientBase implements ClientModInitializer {
    private static ClientBase INSTANCE;
    private ModuleManager moduleManager;

    @Override
    public void onInitializeClient() {
        INSTANCE = this;
        this.moduleManager = new ModuleManager();
        this.moduleManager.register(new XbowCart());
        this.moduleManager.register(new AimAssist());
        this.moduleManager.register(new TriggerBot());

        ClientTickEvents.START_CLIENT_TICK.register(ClientBase::invokeGlobalTick);
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
}