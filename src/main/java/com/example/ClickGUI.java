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

        ClientBase base = ClientBase.getInstance();
        if (base == null || base.getModuleManager() == null) return;

        for (ClientBase.Module module : base.getModuleManager().getModules()) {
            int offset = 0;
            if (module.getName().equals("XbowCart")) offset = -70;
            else if (module.getName().equals("AimAssist")) offset = -45;
            else if (module.getName().equals("TriggerBot")) offset = -20;
            else if (module.getName().equals("ShieldBreaker")) offset = 5;
            else if (module.getName().equals("AutoMace")) offset = 30;

            this.addRenderableWidget(Button.builder(
                    Component.literal(module.getName() + ": " + (module.isEnabled() ? "§aON" : "§cOFF")),
                    btn -> {
                        module.toggle();
                        btn.setMessage(Component.literal(module.getName() + ": " + (module.isEnabled() ? "§aON" : "§cOFF")));
                    }
            ).bounds(cx - bw / 2, cy + offset, bw, bh).build());
        }

        this.addRenderableWidget(Button.builder(
                Component.literal("Fechar"),
                btn -> this.onClose()
        ).bounds(cx - 40, cy + 60, 80, 20).build());
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
