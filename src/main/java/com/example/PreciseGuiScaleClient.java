package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

public class PreciseGuiScaleClient implements ClientModInitializer {
    private static KeyMapping openGuiKey;

    @Override
    public void onInitializeClient() {
        // Registra o keybind
        openGuiKey = new KeyMapping(
                "key.preciseguiscale.open_gui",
                GLFW.GLFW_KEY_RIGHT_SHIFT,
                "key.categories.misc"
        );

        // Thread de tick (20 ticks por segundo)
        Thread tickThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(50); // 20 TPS
                    Minecraft client = Minecraft.getInstance();
                    if (client.player == null) continue;

                    // Processa keybind
                    if (openGuiKey.consumeClick()) {
                        if (ClickGUI.isOpen()) ClickGUI.close();
                        else ClickGUI.open();
                    }

                    // Processa os mods
                    if (AutoMace.enabled) AutoMace.onTick(client);
                    if (XbowCart.enabled) XbowCart.onTick(client);

                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        tickThread.setDaemon(true);
        tickThread.start();
    }
}
