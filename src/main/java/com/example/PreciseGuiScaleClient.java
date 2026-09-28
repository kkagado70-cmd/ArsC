package com.example;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import com.mojang.blaze3d.platform.InputConstants;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class PreciseGuiScaleClient {

    public static final String FILE_NAME = "PreciseGuiScaleClient.java";

    public enum GuiScaleMode {
        AUTO,
        FIXED_1,
        FIXED_2,
        FIXED_3,
        FIXED_4,
        PRECISE_OVERRIDE
    }

    public enum BindingCategory {
        MODULE_TOGGLE,
        GUI_CONTROL,
        COMBAT_ASSIST,
        UTILITY,
        DEBUG
    }

    public static final class RegisteredBinding {
        public final String id;
        public final KeyMapping keyMapping;
        public final BindingCategory category;
        public final Runnable onPress;
        public boolean enabled;
        private boolean wasDown;
        private long lastPressEpoch;
        private int pressCooldownMs;

        public RegisteredBinding(String id, KeyMapping km, BindingCategory cat, Runnable onPress, int cooldownMs) {
            this.id = id;
            this.keyMapping = km;
            this.category = cat;
            this.onPress = onPress;
            this.enabled = true;
            this.wasDown = false;
            this.lastPressEpoch = 0L;
            this.pressCooldownMs = Math.max(0, cooldownMs);
        }

        public boolean tryFire() {
            if (!enabled || onPress == null) return false;
            long now = System.currentTimeMillis();
            if (now - lastPressEpoch < pressCooldownMs) return false;
            boolean down = keyMapping.isDown();
            if (down && !wasDown) {
                try {
                    onPress.run();
                } catch (Exception ignored) {}
                lastPressEpoch = now;
                wasDown = true;
                return true;
            }
            if (!down) wasDown = false;
            return false;
        }
    }

    public static final class ScreenHistoryEntry {
        public final String screenClass;
        public final long enterEpoch;
        public final long exitEpoch;

        public ScreenHistoryEntry(String screenClass, long enter, long exit) {
            this.screenClass = screenClass;
            this.enterEpoch = enter;
            this.exitEpoch = exit;
        }

        public long getDurationMs() { return exitEpoch - enterEpoch; }
    }

    private static final SecureRandom secureRandom = new SecureRandom();
    private static final Map<String, Object> GUI_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_ID = UUID.randomUUID();
    private static final Map<String, RegisteredBinding> BINDINGS = new LinkedHashMap<>();
    private static final CopyOnWriteArrayList<Runnable> SCALE_CHANGE_LISTENERS = new CopyOnWriteArrayList<>();
    private static final Deque<ScreenHistoryEntry> SCREEN_HISTORY = new ArrayDeque<>();
    private static final int SCREEN_HISTORY_CAP = 64;

    private static GuiScaleMode guiScaleMode = GuiScaleMode.AUTO;
    private static int fixedGuiScale = 2;
    private static int preciseOverrideScale = 2;
    private static boolean guiScaleOverrideActive = false;
    private static boolean initialized = false;
    private static int previousGuiScale = 2;
    private static String currentScreenClass = "null";
    private static long currentScreenEnterEpoch = 0L;
    private static boolean screenOpen = false;
    private static long totalKeyPresses = 0L;
    private static long totalScaleChanges = 0L;
    private static boolean bindingsEnabled = true;
    private static int globalCooldownMs = 200;
    private static boolean conflictDetectionActive = true;

    static {
        GUI_REGISTRY.put("SubsessionUUID", SUBSESSION_ID);
        GUI_REGISTRY.put("Profile", "Enterprise-PreciseGuiScaleClient");
        GUI_REGISTRY.put("GuiScaleMode", guiScaleMode.name());
        GUI_REGISTRY.put("OverrideActive", false);
    }

    public static void initialize() {
        if (initialized) return;

        KeyMapping toggleXbowCart = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.xbowcart.toggle",
                InputConstants.Type.KEYSYM,
                InputConstants.GLFW_KEY_V,
                "category.xbowcart"
        ));

        KeyMapping openGui = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.xbowcart.gui",
                InputConstants.Type.KEYSYM,
                InputConstants.GLFW_KEY_RIGHT_SHIFT,
                "category.xbowcart"
        ));

        KeyMapping scaleUp = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.xbowcart.scale_up",
                InputConstants.Type.KEYSYM,
                InputConstants.GLFW_KEY_EQUAL,
                "category.xbowcart"
        ));

        KeyMapping scaleDown = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.xbowcart.scale_down",
                InputConstants.Type.KEYSYM,
                InputConstants.GLFW_KEY_MINUS,
                "category.xbowcart"
        ));

        KeyMapping panicKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.xbowcart.panic",
                InputConstants.Type.KEYSYM,
                InputConstants.GLFW_KEY_END,
                "category.xbowcart"
        ));

        KeyMapping profileCycleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.xbowcart.profile_cycle",
                InputConstants.Type.KEYSYM,
                InputConstants.GLFW_KEY_F6,
                "category.xbowcart"
        ));

        registerBinding("toggle_xbowcart", toggleXbowCart, BindingCategory.MODULE_TOGGLE, () -> {
            Minecraft client = Minecraft.getInstance();
            if (client != null) ClientBase.toggleModule("XbowCart");
        }, globalCooldownMs);

        registerBinding("open_gui", openGui, BindingCategory.GUI_CONTROL, () -> {
            Minecraft client = Minecraft.getInstance();
            if (client != null && client.screen == null) {
                client.setScreen(new ClickGUI());
            }
        }, globalCooldownMs);

        registerBinding("scale_up", scaleUp, BindingCategory.GUI_CONTROL, () -> {
            adjustGuiScale(Minecraft.getInstance(), 1);
        }, 150);

        registerBinding("scale_down", scaleDown, BindingCategory.GUI_CONTROL, () -> {
            adjustGuiScale(Minecraft.getInstance(), -1);
        }, 150);

        registerBinding("panic", panicKey, BindingCategory.UTILITY, () -> {
            Minecraft client = Minecraft.getInstance();
            if (client == null) return;
            ClientBase.suspendAll();
            InteractionManager.flushAndRelease(client);
            RotationManager.reset();
            SafetyWatchdog.trip(
                    SafetyWatchdog.TripReason.EXTERNAL_FORCE_TRIP,
                    SafetyWatchdog.SeverityLevel.WARN,
                    "Panic key triggered"
            );
        }, 500);

        registerBinding("profile_cycle", profileCycleKey, BindingCategory.UTILITY, () -> {
            cycleNetworkProfile();
        }, 800);

        ClientTickEvents.START_CLIENT_TICK.register(PreciseGuiScaleClient::onTick);

        initialized = true;
        GUI_REGISTRY.put("Initialized", true);
        GUI_REGISTRY.put("BindingsRegistered", BINDINGS.size());
    }

    private static void onTick(Minecraft client) {
        if (client == null) return;

        updateScreenTracking(client);

        if (!bindingsEnabled) return;

        for (RegisteredBinding binding : BINDINGS.values()) {
            if (binding.tryFire()) {
                totalKeyPresses++;
                GUI_REGISTRY.put("TotalKeyPresses", totalKeyPresses);
            }
        }
    }

    private static void updateScreenTracking(Minecraft client) {
        Screen current = client.screen;
        String currentClass = current != null ? current.getClass().getSimpleName() : "null";

        if (!currentClass.equals(currentScreenClass)) {
            long now = System.currentTimeMillis();

            if (screenOpen && !currentScreenClass.equals("null")) {
                pushScreenHistory(currentScreenClass, currentScreenEnterEpoch, now);
            }

            currentScreenClass = currentClass;
            currentScreenEnterEpoch = now;
            screenOpen = !currentClass.equals("null");

            GUI_REGISTRY.put("CurrentScreen", currentScreenClass);
            GUI_REGISTRY.put("ScreenOpen", screenOpen);
        }
    }

    public static void applyGuiScaleOverride(Minecraft client) {
        if (client == null || client.options == null) return;
        if (!guiScaleOverrideActive) return;

        int targetScale = switch (guiScaleMode) {
            case FIXED_1 -> 1;
            case FIXED_2 -> 2;
            case FIXED_3 -> 3;
            case FIXED_4 -> 4;
            case PRECISE_OVERRIDE -> preciseOverrideScale;
            default -> client.options.guiScale().get();
        };

        int current = client.options.guiScale().get();
        if (current != targetScale) {
            previousGuiScale = current;
            client.options.guiScale().set(targetScale);
            client.resizeDisplay();
            totalScaleChanges++;
            notifyScaleChangeListeners();
            GUI_REGISTRY.put("CurrentScale", targetScale);
            GUI_REGISTRY.put("TotalScaleChanges", totalScaleChanges);
        }
    }

    public static void restoreGuiScale(Minecraft client) {
        if (client == null || client.options == null || previousGuiScale < 1) return;
        int current = client.options.guiScale().get();
        if (current != previousGuiScale) {
            client.options.guiScale().set(previousGuiScale);
            client.resizeDisplay();
            totalScaleChanges++;
            notifyScaleChangeListeners();
            GUI_REGISTRY.put("CurrentScale", previousGuiScale);
        }
    }

    private static void adjustGuiScale(Minecraft client, int delta) {
        if (client == null || client.options == null) return;
        int current = client.options.guiScale().get();
        int next = Math.max(1, Math.min(4, current + delta));
        if (next != current) {
            client.options.guiScale().set(next);
            client.resizeDisplay();
            totalScaleChanges++;
            notifyScaleChangeListeners();
            GUI_REGISTRY.put("CurrentScale", next);
        }
    }

    private static void cycleNetworkProfile() {
        PacketBufferManager.NetworkProfile[] profiles = PacketBufferManager.NetworkProfile.values();
        PacketBufferManager.NetworkProfile current = PacketBufferManager.getActiveProfile();
        int next = (current.ordinal() + 1) % profiles.length;
        PacketBufferManager.setNetworkProfile(profiles[next]);
        GUI_REGISTRY.put("ActiveNetworkProfile", profiles[next].name());
    }

    private static void notifyScaleChangeListeners() {
        for (Runnable listener : SCALE_CHANGE_LISTENERS) {
            try { listener.run(); } catch (Exception ignored) {}
        }
    }

    private static void pushScreenHistory(String screenClass, long enter, long exit) {
        if (SCREEN_HISTORY.size() >= SCREEN_HISTORY_CAP) SCREEN_HISTORY.pollFirst();
        SCREEN_HISTORY.offerLast(new ScreenHistoryEntry(screenClass, enter, exit));
    }

    public static void registerBinding(String id, KeyMapping km, BindingCategory cat, Runnable onPress, int cooldownMs) {
        if (id == null || km == null) return;
        if (conflictDetectionActive && BINDINGS.containsKey(id)) return;
        BINDINGS.put(id, new RegisteredBinding(id, km, cat, onPress, cooldownMs));
        GUI_REGISTRY.put("BindingsRegistered", BINDINGS.size());
    }

    public static void unregisterBinding(String id) {
        BINDINGS.remove(id);
        GUI_REGISTRY.put("BindingsRegistered", BINDINGS.size());
    }

    public static void registerScaleChangeListener(Runnable listener) {
        if (listener != null) SCALE_CHANGE_LISTENERS.add(listener);
    }

    public static void enableBinding(String id) {
        RegisteredBinding b = BINDINGS.get(id);
        if (b != null) b.enabled = true;
    }

    public static void disableBinding(String id) {
        RegisteredBinding b = BINDINGS.get(id);
        if (b != null) b.enabled = false;
    }

    public static void setBindingsEnabled(boolean enabled) {
        bindingsEnabled = enabled;
        GUI_REGISTRY.put("BindingsEnabled", enabled);
    }

    public static void setGuiScaleMode(GuiScaleMode mode) {
        guiScaleMode = mode;
        GUI_REGISTRY.put("GuiScaleMode", mode.name());
    }

    public static void setPreciseOverrideScale(int scale) {
        preciseOverrideScale = Math.max(1, Math.min(8, scale));
    }

    public static void setGuiScaleOverrideActive(boolean active) {
        guiScaleOverrideActive = active;
        GUI_REGISTRY.put("OverrideActive", active);
    }

    public static void setConflictDetectionActive(boolean active) {
        conflictDetectionActive = active;
    }

    public static void setGlobalCooldownMs(int ms) {
        globalCooldownMs = Math.max(0, ms);
    }

    public static boolean isScreenOpen() { return screenOpen; }
    public static String getCurrentScreenClass() { return currentScreenClass; }
    public static long getTotalKeyPresses() { return totalKeyPresses; }
    public static long getTotalScaleChanges() { return totalScaleChanges; }
    public static GuiScaleMode getGuiScaleMode() { return guiScaleMode; }
    public static boolean isInitialized() { return initialized; }
    public static int getBindingCount() { return BINDINGS.size(); }
    public static RegisteredBinding getBinding(String id) { return BINDINGS.get(id); }
    public static Collection<RegisteredBinding> getAllBindings() { return Collections.unmodifiableCollection(BINDINGS.values()); }
    public static List<ScreenHistoryEntry> getScreenHistory() { return new ArrayList<>(SCREEN_HISTORY); }
    public static UUID getSubsessionIdentity() { return SUBSESSION_ID; }
}
