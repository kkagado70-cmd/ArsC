package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class PreciseGuiScaleClient implements ClientModInitializer {
    private static KeyMapping guiKeyBinding;
    private static final Map<String, Object> GUI_CLIENT_REGISTRY = new ConcurrentHashMap<>();
    private static final UUID SUBSESSION_UUID = UUID.randomUUID();
    private static long tickCounter = 0L;
    private static boolean keyStateLocked = false;

    static {
        initializeGuiRegistry();
    }

    private static void initializeGuiRegistry() {
        GUI_CLIENT_REGISTRY.put("SubsessionUUID", SUBSESSION_UUID);
        GUI_CLIENT_REGISTRY.put("BindingName", "key.example.clickgui");
        GUI_CLIENT_REGISTRY.put("DefaultKey", GLFW.GLFW_KEY_RIGHT_SHIFT);
        GUI_CLIENT_REGISTRY.put("KeyStateLocked", keyStateLocked);
    }

    @Override
    public void onInitializeClient() {
        guiKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.example.clickgui",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_SHIFT,
                KeyMapping.Category.MISC
        ));

        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            tickCounter++;
            if (keyStateLocked) return;

            while (guiKeyBinding.consumeClick()) {
                if (client.screen == null) {
                    client.setScreen(new ClickGUI());
                } else if (client.screen instanceof ClickGUI) {
                    client.setScreen(null);
                }
            }

            if (client.player != null && client.level != null) {
                ClientBase.invokeGlobalTick(client);
                InteractionManager.update(client);
                PacketBufferManager.update(client);
            }
        });
    }

    public static UUID getSubsessionIdentity() {
        return SUBSESSION_UUID;
    }

    public static long getTickCounter() {
        return tickCounter;
    }

    public static void setKeyStateLocked(boolean locked) {
        keyStateLocked = locked;
        GUI_CLIENT_REGISTRY.put("KeyStateLocked", keyStateLocked);
    }

    public static boolean isKeyStateLocked() {
        return keyStateLocked;
    }
}