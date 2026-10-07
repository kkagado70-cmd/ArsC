package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;

import java.lang.reflect.Field;

/**
 * Acessa Inventory.selected (privado em 1.21.11) via reflection.
 * Nunca acessa o campo diretamente — compila sem erros de acesso.
 * WRITE também envia ServerboundSetCarriedItemPacket para sync com servidor.
 */
final class SlotAccessor {
    private static final Field SEL_FIELD;

    static {
        Field f = null;
        // Tenta nomes conhecidos (Mojmap: "selected", intermediary: "f_35977_")
        for (String name : new String[]{"selected", "f_35977_", "selectedSlot"}) {
            try {
                f = Inventory.class.getDeclaredField(name);
                f.setAccessible(true);
                break;
            } catch (NoSuchFieldException ignored) {}
        }
        SEL_FIELD = f;
    }

    private SlotAccessor() {}

    static int get(Minecraft mc) {
        if (mc == null || mc.player == null) return -1;
        // Tenta método público primeiro (pode existir em alguns mappings)
        try {
            var method = Inventory.class.getMethod("getSelectedSlot");
            return (int) method.invoke(mc.player.getInventory());
        } catch (Exception ignored) {}
        // Fallback: reflection sobre campo privado
        if (SEL_FIELD != null) {
            try { return (int) SEL_FIELD.get(mc.player.getInventory()); }
            catch (IllegalAccessException ignored) {}
        }
        return 0; // safe default
    }

    static void set(Minecraft mc, int slot) {
        if (mc == null || mc.player == null || slot < 0 || slot > 8) return;
        // Write via reflection
        if (SEL_FIELD != null) {
            try { SEL_FIELD.set(mc.player.getInventory(), slot); }
            catch (IllegalAccessException ignored) {}
        }
        // Sync with server
        if (mc.getConnection() != null) {
            mc.getConnection().send(
                new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(slot));
        }
    }
}
