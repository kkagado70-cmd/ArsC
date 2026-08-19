package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

public class PreciseGuiScaleClient implements ClientModInitializer {
    private static KeyMapping openGuiKey;

    @Override
    public void onInitializeClient() {
        // Usando KeyMapping.Category.MISC (categoria padrão para miscelânea)
        openGuiKey = new KeyMapping(
                "key.preciseguiscale.open_gui",
                KeyMapping.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_SHIFT,
                KeyMapping.Category.MISC
        );

        Thread tickThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(50);
                    Minecraft client = Minecraft.getInstance();
                    if (client.player == null) continue;

                    if (openGuiKey.consumeClick()) {
                        if (ClickGUI.isOpen()) ClickGUI.close();
                        else ClickGUI.open();
                    }

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
