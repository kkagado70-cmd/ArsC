package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class ClickGUI extends Screen {

    public ClickGUI() {
        super(Component.literal("Config"));
    }

    public static void open() {
        Minecraft.getInstance().setScreen(new ClickGUI());
    }

    public static void close() {
        if (isOpen()) {
            Minecraft.getInstance().setScreen(null);
        }
    }

    public static boolean isOpen() {
        return Minecraft.getInstance().screen instanceof ClickGUI;
    }

    @Override
    protected void init() {
        super.init();
        int cx = this.width / 2;
        int cy = this.height / 2;
        int bw = 140, bh = 20;

        this.addRenderableWidget(Button.builder(
                Component.literal("XbowCart: " + (XbowCart.enabled ? "§aON" : "§cOFF")),
                btn -> {
                    XbowCart.enabled = !XbowCart.enabled;
                    btn.setMessage(Component.literal("XbowCart: " + (XbowCart.enabled ? "§aON" : "§cOFF")));
                }
        ).bounds(cx - bw / 2, cy - 50, bw, bh).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("AimAssist: " + (AimAssist.enabled ? "§aON" : "§cOFF")),
                btn -> {
                    AimAssist.enabled = !AimAssist.enabled;
                    btn.setMessage(Component.literal("AimAssist: " + (AimAssist.enabled ? "§aON" : "§cOFF")));
                }
        ).bounds(cx - bw / 2, cy - 20, bw, bh).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("TriggerBot: " + (TriggerBot.enabled ? "§aON" : "§cOFF")),
                btn -> {
                    TriggerBot.enabled = !TriggerBot.enabled;
                    btn.setMessage(Component.literal("TriggerBot: " + (TriggerBot.enabled ? "§aON" : "§cOFF")));
                }
        ).bounds(cx - bw / 2, cy + 10, bw, bh).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("Fechar"),
                btn -> this.onClose()
        ).bounds(cx - 40, cy + 45, 80, 20).build());
    }

    @Override
    public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}