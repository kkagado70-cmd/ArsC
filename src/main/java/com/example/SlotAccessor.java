package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;

import java.lang.reflect.Field;

/**
 * Acessa Inventory.selected mesmo quando privado (1.21.11+).
 * READ  → getSelectedSlot()  se existir, senão reflection.
 * WRITE → reflection + ServerboundSetCarriedItemPacket para sync com servidor.
 */
final class SlotAccessor {
    private static final Field SEL;

    static {
        Field f = null;
        for (String name : new String[]{"selected", "f_35977_"}) {
            try { f = Inventory.class.getDeclaredField(name); f.setAccessible(true); break; }
            catch (NoSuchFieldException ignored) {}
        }
        SEL = f;
    }

    private SlotAccessor() {}

    static int get(Minecraft mc) {
        if (mc == null || mc.player == null) return -1;
        try { return mc.player.getInventory().getSelectedSlot(); }
        catch (NoSuchMethodError ignored) {}
        if (SEL != null) {
            try { return (int) SEL.get(mc.player.getInventory()); }
            catch (IllegalAccessException ignored) {}
        }
        return mc.player.getInventory().selected;
    }

    static void set(Minecraft mc, int slot) {
        if (mc == null || mc.player == null || slot < 0 || slot > 8) return;
        if (SEL != null) {
            try { SEL.set(mc.player.getInventory(), slot); }
            catch (IllegalAccessException ignored) {}
        }
        if (mc.getConnection() != null) {
            mc.getConnection().send(
                new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(slot));
        }
    }
}
