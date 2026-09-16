package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Deque;

public class ClientBase implements ClientModInitializer {
    private static ClientBase INSTANCE;
    private ModuleManager moduleManager;
    private static KeyMapping guiKeyBinding;
    
    private static final Map<String, Object> BASE_ENTERPRISE_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_IDENTITY = UUID.randomUUID();
    private static final Deque<Long> GLOBAL_TICK_LATENCY_DEQUE = new ArrayDeque<>();
    private static final Deque<String> INITIALIZATION_LOG_DEQUE = new ArrayDeque<>();
    private static final int HISTORY_MAX_CAPACITY = 2048;
    
    private static long globalInitializationTimestamp = 0L;
    private static long totalGlobalTicksProcessed = 0L;
    private static boolean diagnosticModeActive = false;
    private static boolean enterpriseSecurityAuditActive = true;
    private static int subsessionHealthScore = 100;

    static {
        globalInitializationTimestamp = System.currentTimeMillis();
        BASE_ENTERPRISE_REGISTRY.put("SubsessionUUID", SUBSESSION_IDENTITY);
        BASE_ENTERPRISE_REGISTRY.put("Architecture", "Fabric-1.21.11-Mojmap-Monolith");
        BASE_ENTERPRISE_REGISTRY.put("InitializationEpoch", globalInitializationTimestamp);
        INITIALIZATION_LOG_DEQUE.offerLast(globalInitializationTimestamp + ": Core Monolith Subsystem Initialized");
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
            totalGlobalTicksProcessed++;
            executeSubsystemSanitation();

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
                InventoryManager.update(client);
            }
        });
        
        INITIALIZATION_LOG_DEQUE.offerLast(System.currentTimeMillis() + ": ClientModInitializer fully bound to Fabric lifecycle");
    }

    public static ClientBase getInstance() {
        return INSTANCE;
    }

    public ModuleManager getModuleManager() {
        return moduleManager;
    }

    public static void invokeGlobalTick(Minecraft client) {
        long startTickNano = System.nanoTime();
        if (INSTANCE != null && INSTANCE.moduleManager != null) {
            INSTANCE.moduleManager.tick(client);
        }
        long elapsedNano = System.nanoTime() - startTickNano;
        pushTickLatency(elapsedNano / 1000000L);
    }

    private static void pushTickLatency(long ms) {
        if (GLOBAL_TICK_LATENCY_DEQUE.size() >= HISTORY_MAX_CAPACITY) {
            GLOBAL_TICK_LATENCY_DEQUE.pollFirst();
        }
        GLOBAL_TICK_LATENCY_DEQUE.offerLast(ms);
    }

    private static void executeSubsystemSanitation() {
        if (totalGlobalTicksProcessed > 100000000L) {
            totalGlobalTicksProcessed = 0L;
        }
        if (BASE_ENTERPRISE_REGISTRY.size() > 250) {
            BASE_ENTERPRISE_REGISTRY.clear();
            BASE_ENTERPRISE_REGISTRY.put("SubsessionUUID", SUBSESSION_IDENTITY);
        }
    }

    public abstract static class Module {
        protected final String name;
        public boolean enabled;

        public Module(String name) {
            this.name = name;
            this.enabled = false;
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
            return Collections.unmodifiableList(modules);
        }

        public Module getModule(String name) {
            if (name == null) return null;
            for (Module m : modules) {
                if (m != null && m.getName().equalsIgnoreCase(name)) {
                    return m;
                }
            }
            return null;
        }

        public void setEnabled(String name, boolean state) {
            Module m = getModule(name);
            if (m != null && m.enabled != state) {
                m.toggle();
            }
        }

        public void tick(Minecraft client) {
            List<Module> snapshot = new ArrayList<>(modules);
            for (Module m : snapshot) {
                if (m != null) {
                    m.executeTickWrapper(client);
                }
            }
        }
    }

    public static class XbowCartModule extends Module {
        public XbowCartModule() {
            super("XbowCart");
            this.enabled = false;
        }

        @Override
        public void toggle() {
            super.toggle();
            XbowCart.enabled = this.enabled;
            if (!this.enabled) {
                XbowCart.purgePipelineRegistry();
            }
        }

        @Override
        public void tick(Minecraft client) {
            XbowCart.onTick(client);
        }
    }

    public static class AimAssistModule extends Module {
        public AimAssistModule() {
            super("AimAssist");
            this.enabled = false;
        }

        @Override
        public void toggle() {
            super.toggle();
            AimAssist.enabled = this.enabled;
        }

        @Override
        public void tick(Minecraft client) {
            AimAssist.onTick(client);
        }
    }

    public static class TriggerBotModule extends Module {
        public TriggerBotModule() {
            super("TriggerBot");
            this.enabled = false;
        }

        @Override
        public void toggle() {
            super.toggle();
            TriggerBot.enabled = this.enabled;
        }

        @Override
        public void tick(Minecraft client) {
            TriggerBot.onTick(client);
        }
    }

    public static class ShieldBreakerModule extends Module {
        public ShieldBreakerModule() {
            super("ShieldBreaker");
            this.enabled = false;
        }

        @Override
        public void toggle() {
            super.toggle();
            ShieldBreaker.enabled = this.enabled;
        }

        @Override
        public void tick(Minecraft client) {
            ShieldBreaker.onTick(client);
        }
    }

    public static class AutoMaceModule extends Module {
        public AutoMaceModule() {
            super("AutoMace");
            this.enabled = false;
        }

        @Override
        public void toggle() {
            super.toggle();
            AutoMace.enabled = this.enabled;
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

    public static long getTotalGlobalTicksProcessed() {
        return totalGlobalTicksProcessed;
    }

    public static int getSubsessionHealthScore() {
        return subsessionHealthScore;
    }

    public static void setSubsessionHealthScore(int score) {
        subsessionHealthScore = Math.max(0, Math.min(100, score));
        BASE_ENTERPRISE_REGISTRY.put("HealthScore", subsessionHealthScore);
    }
}