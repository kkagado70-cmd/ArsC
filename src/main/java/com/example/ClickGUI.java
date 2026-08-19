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
                Component.literal("AutoMace: " + (AutoMace.enabled ? "§aON" : "§cOFF")),
                btn -> {
                    AutoMace.enabled = !AutoMace.enabled;
                    btn.setMessage(Component.literal("AutoMace: " + (AutoMace.enabled ? "§aON" : "§cOFF")));
                }
        ).bounds(cx - bw / 2, cy - 30, bw, bh).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("XbowCart: " + (XbowCart.enabled ? "§aON" : "§cOFF")),
                btn -> {
                    XbowCart.enabled = !XbowCart.enabled;
                    btn.setMessage(Component.literal("XbowCart: " + (XbowCart.enabled ? "§aON" : "§cOFF")));
                }
        ).bounds(cx - bw / 2, cy + 10, bw, bh).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("Fechar"),
                btn -> this.onClose()
        ).bounds(cx - 40, cy + 50, 80, 20).build());
    }

    @Override
    public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
