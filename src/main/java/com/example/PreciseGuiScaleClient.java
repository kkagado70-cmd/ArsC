package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

public class PreciseGuiScaleClient implements ClientModInitializer {
    private static KeyMapping openGuiKey;

    @Override
    public void onInitializeClient() {
        // Usa KeyMapping.Category.MISC em vez de String
        openGuiKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.preciseguiscale.open_gui",
                GLFW.GLFW_KEY_RIGHT_SHIFT,
                KeyMapping.Category.MISC
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (openGuiKey.consumeClick()) {
                if (ClickGUI.isOpen()) ClickGUI.close();
                else ClickGUI.open();
            }

            if (client.player != null) {
                if (AutoMace.enabled) AutoMace.onTick(client);
                if (XbowCart.enabled) XbowCart.onTick(client);
            }
        });
    }
}
