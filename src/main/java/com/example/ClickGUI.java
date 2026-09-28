package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.UUID;

public class ClickGUI extends Screen {
    private static final UUID SUBSESSION_IDENTITY = UUID.randomUUID();
    private static boolean renderBackgroundFlag = true;

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
        int bw = 160, bh = 20;

        this.addRenderableWidget(Button.builder(
                moduleLabel("XbowCart"),
                btn -> {
                    ClientBase.toggleModule("XbowCart");
                    btn.setMessage(moduleLabel("XbowCart"));
                }
        ).bounds(cx - bw / 2, cy - 70, bw, bh).build());

        this.addRenderableWidget(Button.builder(
                moduleLabel("AimAssist"),
                btn -> {
                    ClientBase.toggleModule("AimAssist");
                    btn.setMessage(moduleLabel("AimAssist"));
                }
        ).bounds(cx - bw / 2, cy - 45, bw, bh).build());

        this.addRenderableWidget(Button.builder(
                moduleLabel("TriggerBot"),
                btn -> {
                    ClientBase.toggleModule("TriggerBot");
                    btn.setMessage(moduleLabel("TriggerBot"));
                }
        ).bounds(cx - bw / 2, cy - 20, bw, bh).build());

        this.addRenderableWidget(Button.builder(
                moduleLabel("ShieldBreaker"),
                btn -> {
                    ClientBase.toggleModule("ShieldBreaker");
                    btn.setMessage(moduleLabel("ShieldBreaker"));
                }
        ).bounds(cx - bw / 2, cy + 5, bw, bh).build());

        this.addRenderableWidget(Button.builder(
                moduleLabel("AutoMace"),
                btn -> {
                    ClientBase.toggleModule("AutoMace");
                    btn.setMessage(moduleLabel("AutoMace"));
                }
        ).bounds(cx - bw / 2, cy + 30, bw, bh).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("Fechar"),
                btn -> this.onClose()
        ).bounds(cx - 40, cy + 60, 80, 20).build());
    }

    private static Component moduleLabel(String name) {
        boolean enabled = ClientBase.isModuleEnabled(name);
        return Component.literal(name + ": " + (enabled ? "§aON" : "§cOFF"));
    }

    @Override
    public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
        if (renderBackgroundFlag) {
            super.render(context, mouseX, mouseY, delta);
        } else {
            super.renderBackground(context, mouseX, mouseY, delta);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    public static UUID getSubsessionIdentity() {
        return SUBSESSION_IDENTITY;
    }
}