package com.scale.preciseguiscale;

import com.scale.preciseguiscale.gui.ClickGUI;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

public class KeyBindings {
    private static KeyMapping openGuiKey;

    public static void register() {
        // Cria a categoria do keybind
        var category = KeyMapping.Category.register(
            net.minecraft.resources.Identifier.fromNamespaceAndPath("precise-gui-scale", "general")
        );

        // Registra o keybind para Right Shift (GLFW.GLFW_KEY_RIGHT_SHIFT)
        openGuiKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.preciseguiscale.open_gui",
            KeyMapping.Type.KEYSYM,
            GLFW.GLFW_KEY_RIGHT_SHIFT,
            category
        ));

        // Registra o evento de clique
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (openGuiKey.consumeClick()) {
                if (ClickGUI.isOpen()) {
                    ClickGUI.close();
                } else {
                    ClickGUI.open();
                }
            }
        });
    }
}