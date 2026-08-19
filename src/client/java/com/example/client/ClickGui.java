package com.scale.preciseguiscale.gui;

import com.example.client.AutoMace;
import com.example.client.XbowCart;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class ClickGUI extends Screen {
    private static ClickGUI instance;

    public ClickGUI() {
        super(Component.literal("Precise GUI Scale - Config"));
        instance = this;
    }

    public static void open() {
        Minecraft.getInstance().setScreen(new ClickGUI());
    }

    public static void close() {
        if (Minecraft.getInstance().screen instanceof ClickGUI) {
            Minecraft.getInstance().screen.onClose();
        }
    }

    public static boolean isOpen() {
        return Minecraft.getInstance().screen instanceof ClickGUI;
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;
        int centerY = this.height / 2;
        int buttonWidth = 150;
        int buttonHeight = 20;

        // Título
        this.addRenderableWidget(Button.builder(
                Component.literal("§6§lPrecise GUI Scale - Config"),
                button -> {}
        ).bounds(centerX - 100, centerY - 80, 200, 20).build());

        // AutoMace Toggle
        this.addRenderableWidget(CycleButton.onOffBuilder(AutoMace.enabled)
                .displayOnlyValue()
                .create(centerX - 75, centerY - 40, buttonWidth, buttonHeight,
                        Component.literal("AutoMace: "),
                        (button, value) -> {
                            AutoMace.enabled = value;
                            if (value) {
                                Minecraft.getInstance().player.displayClientMessage(
                                        Component.literal("§aAutoMace ON"), true);
                            } else {
                                Minecraft.getInstance().player.displayClientMessage(
                                        Component.literal("§cAutoMace OFF"), true);
                            }
                        }));

        // XbowCart Toggle
        this.addRenderableWidget(CycleButton.onOffBuilder(XbowCart.enabled)
                .displayOnlyValue()
                .create(centerX - 75, centerY - 10, buttonWidth, buttonHeight,
                        Component.literal("XbowCart: "),
                        (button, value) -> {
                            XbowCart.enabled = value;
                            if (value) {
                                Minecraft.getInstance().player.displayClientMessage(
                                        Component.literal("§aXbowCart ON"), true);
                            } else {
                                Minecraft.getInstance().player.displayClientMessage(
                                        Component.literal("§cXbowCart OFF"), true);
                            }
                        }));

        // Botão Fechar
        this.addRenderableWidget(Button.builder(
                Component.literal("§cFechar"),
                button -> this.onClose()
        ).bounds(centerX - 50, centerY + 30, 100, 20).build());
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

    @Override
    public void onClose() {
        super.onClose();
        // Salva as configurações se necessário
    }
}