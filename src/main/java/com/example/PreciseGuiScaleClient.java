package com.scale.preciseguiscale;

import com.example.client.AutoMace;
import com.example.client.XbowCart;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public class PreciseGuiScaleClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Registra os keybinds
        KeyBindings.register();

        // Tick handler para os mods
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            if (AutoMace.enabled) {
                AutoMace.onTick(client);
            }
            if (XbowCart.enabled) {
                XbowCart.onTick(client);
            }
        });
    }
}