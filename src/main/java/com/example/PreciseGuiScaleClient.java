package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

public class PreciseGuiScaleClient implements ClientModInitializer {
    private static KeyMapping guiKeyBinding;

    @Override
    public void onInitializeClient() {
        ClientBase.getInstance();

        guiKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.example.clickgui",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_SHIFT,
                KeyMapping.Category.MISC
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
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
}
