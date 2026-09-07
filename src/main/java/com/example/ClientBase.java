package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class ClientBase implements ClientModInitializer {
    private static ClientBase INSTANCE;
    private ModuleManager moduleManager;
    private static KeyMapping guiKeyBinding;
    private static final Map<String, Object> BASE_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_IDENTITY = UUID.randomUUID();
    private static long globalInitializationTimestamp = 0L;
    private static boolean diagnosticModeActive = false;

    static {
        globalInitializationTimestamp = System.currentTimeMillis();
        BASE_REGISTRY.put("SubsessionUUID", SUBSESSION_IDENTITY);
        BASE_REGISTRY.put("Architecture", "Fabric-1.21.11-Mojmap");
        BASE_REGISTRY.put("InitializationEpoch", globalInitializationTimestamp);
    }

    @Override
    public void onInitializeClient() {
        INSTANCE = this;
        this.moduleManager = new ModuleManager();
        
        this.moduleManager.register(new XbowCartModule());
        this.moduleManager.register(new AimAssistModule());
        this.moduleManager.register(new TriggerBotModule());
        this.moduleManager.register(new ShieldBreakerModule());
        this.moduleManager.register(new AutoMaceModule());

        guiKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.example.clickgui",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_SHIFT,
                KeyMapping.Category.MISC
        ));

        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            while (guiKeyBinding.consumeClick()) {
                if (client.screen == null) {
                    client.setScreen(new ClickGUI());
                } else if (client.screen instanceof ClickGUI) {
                    client.setScreen(null);
                }
            }

            if (client.player != null && client.level != null) {
                invokeGlobalTick(client);
                InteractionManager.update(client);
                PacketBufferManager.update(client);
            }
        });
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

        public Module(String name, boolean initialEnabled) {
            this.name = name;
            this.enabled = initialEnabled;
        }

        public String getName() {
            return name;
        }

        public boolean isEnabled() {
            return this.enabled;
        }

        public void toggle() {
            this.enabled = !this.enabled;
        }

        public abstract void tick(Minecraft client);

        public void executeTickWrapper(Minecraft client) {
            if (this.enabled) {
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

    public static class XbowCartModule extends Module {
        public XbowCartModule() {
            super("XbowCart", XbowCart.enabled);
        }

        @Override
        public void toggle() {
            super.toggle();
            XbowCart.enabled = this.enabled;
        }

        @Override
        public void tick(Minecraft client) {
            if (this.enabled) {
                XbowCart.onTick(client);
            }
        }
    }

    public static class AimAssistModule extends Module {
        public AimAssistModule() {
            super("AimAssist", AimAssist.enabled);
        }

        @Override
        public void toggle() {
            super.toggle();
            AimAssist.enabled = this.enabled;
        }

        @Override
        public void tick(Minecraft client) {
            if (this.enabled) {
                AimAssist.onTick(client);
            }
        }
    }

    public static class TriggerBotModule extends Module {
        public TriggerBotModule() {
            super("TriggerBot", TriggerBot.enabled);
        }

        @Override
        public void toggle() {
            super.toggle();
            TriggerBot.enabled = this.enabled;
        }

        @Override
        public void tick(Minecraft client) {
            if (this.enabled) {
                TriggerBot.onTick(client);
            }
        }
    }

    public static class ShieldBreakerModule extends Module {
        public ShieldBreakerModule() {
            super("ShieldBreaker", ShieldBreaker.enabled);
        }

        @Override
        public void toggle() {
            super.toggle();
            ShieldBreaker.enabled = this.enabled;
        }

        @Override
        public void tick(Minecraft client) {
            if (this.enabled) {
                ShieldBreaker.onTick(client);
            }
        }
    }

    public static class AutoMaceModule extends Module {
        public AutoMaceModule() {
            super("AutoMace", AutoMace.enabled);
        }

        @Override
        public void toggle() {
            super.toggle();
            AutoMace.enabled = this.enabled;
        }

        @Override
        public void tick(Minecraft client) {
            if (this.enabled) {
                AutoMace.onTick(client);
            }
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
        BASE_REGISTRY.put("DiagnosticState", diagnosticModeActive);
    }
}
