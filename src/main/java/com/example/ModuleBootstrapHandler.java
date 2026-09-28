package com.arsenal.client.handlers.impl;

import com.arsenal.client.event.EventSubscribe;
import com.arsenal.client.event.impl.EventClientPlayerTick;
import com.arsenal.client.handlers.Handler;
import com.example.AimAssist;
import com.example.AutoMace;
import com.example.InventoryManager;
import com.example.ShieldBreaker;
import com.example.TriggerBot;
import com.example.XbowCart;
import net.minecraft.client.Minecraft;

/**
 * Conecta os módulos standalone (com.example.*) ao sistema de eventos do Arsenal.
 * Registrado via HandlerManager.initialize() — não precisa tocar em ClientTickEvents.
 *
 * Adicionar no HandlerManager.initialize():
 *   handlers.add(new ModuleBootstrapHandler());
 */
public class ModuleBootstrapHandler extends Handler {

    private final Minecraft mc = Minecraft.getInstance();

    @EventSubscribe
    public void onClientTick(EventClientPlayerTick event) {
        if (mc == null || mc.player == null || mc.level == null) return;

        InventoryManager.update(mc);
        AimAssist.onTick(mc);
        AutoMace.onTick(mc);
        TriggerBot.onTick(mc);
        ShieldBreaker.onTick(mc);
        XbowCart.onTick(mc);
    }
}
