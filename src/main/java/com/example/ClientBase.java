package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

/**
 * ClientBase — entrypoint principal do mod.
 *
 * Registra todos os módulos no Fabric tick e keybinds de toggle.
 *
 * Keybinds padrão (configuráveis nas Opções → Controles):
 *   V → AimAssist
 *   G → TriggerBot
 *   X → ShieldBreaker
 *   Z → AutoMace
 *   N → XbowCart
 *   R → GUI
 */
public class ClientBase implements ClientModInitializer {

    private static ClientBase INSTANCE;

    // Keybinds
    public static KeyMapping keyGui;
    public static KeyMapping keyAimAssist;
    public static KeyMapping keyTriggerBot;
    public static KeyMapping keyShieldBreaker;
    public static KeyMapping keyAutoMace;
    public static KeyMapping keyXbowCart;

    @Override
    public void onInitializeClient() {
        INSTANCE = this;

        // Register keybinds
        keyGui = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.arsenalmod.gui",         InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, "category.arsenalmod.modules"));
        keyAimAssist = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.arsenalmod.aimassist",   InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, "category.arsenalmod.modules"));
        keyTriggerBot = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.arsenalmod.triggerbot",  InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, "category.arsenalmod.modules"));
        keyShieldBreaker = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.arsenalmod.shieldbreaker", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_X, "category.arsenalmod.modules"));
        keyAutoMace = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.arsenalmod.automace",    InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Z, "category.arsenalmod.modules"));
        keyXbowCart = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.arsenalmod.xbowcart",   InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N, "category.arsenalmod.modules"));

        // Register module ticks via Fabric
        AimAssist.register();
        TriggerBot.register();
        ShieldBreaker.register();
        AutoMace.register();
        XbowCart.register();

        // GrimBypass session reach cap — initialized once at startup
        GrimBypassCore.refreshSessionCap(2.95f, 3.15f);

        // Keybind polling
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);

        HandlerManager.initialize();
    }

    private void onClientTick(Minecraft mc) {
        // GUI open
        while (keyGui.consumeClick()) {
            if (mc.screen == null) {
                mc.setScreen(new ClickGUI(mc.screen));
            }
        }

        // Module toggles
        while (keyAimAssist.consumeClick())     AimAssist.toggle();
        while (keyTriggerBot.consumeClick())    TriggerBot.toggle();
        while (keyShieldBreaker.consumeClick()) ShieldBreaker.toggle();
        while (keyAutoMace.consumeClick())      AutoMace.toggle();
        while (keyXbowCart.consumeClick())      XbowCart.enabled = !XbowCart.enabled;
    }

    public static ClientBase getInstance() { return INSTANCE; }
}
