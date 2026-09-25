package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

public class ShieldBreaker {

    public static boolean enabled = true;

    private enum State { IDLE, REACT, SWAP, AIM, ATTACK, RESTORE, COOLDOWN }

    private static final Random RNG = new Random();

    private static State        state       = State.IDLE;
    private static int          timer       = 0;
    private static int          savedSlot   = -1;
    private static LivingEntity target      = null;
    private static int          aimTicks    = 0;

    private static final int    REACT_MIN   = 1;
    private static final int    REACT_MAX   = 3;
    private static final int    COOLDOWN_T  = 14;
    private static final double REACH       = 4.5;
    private static final float  AIM_YT      = 3.5f;
    private static final float  AIM_PT      = 4.0f;
    private static final int    AIM_MAX     = 8;

    public static void onTick(Minecraft mc) {
        if (!enabled || mc.player == null || mc.level == null) return;
        if (mc.screen != null) { hardReset(mc); return; }

        switch (state) {
            case IDLE    -> tickIdle(mc);
            case REACT   -> tickReact(mc);
            case SWAP    -> tickSwap(mc);
            case AIM     -> tickAim(mc);
            case ATTACK  -> tickAttack(mc);
            case RESTORE -> tickRestore(mc);
            case COOLDOWN -> { if (--timer <= 0) hardReset(mc); }
        }
    }

    private static void tickIdle(Minecraft mc) {
        LivingEntity t = getCrosshairTarget(mc);
        if (t == null || !t.isBlocking()) return;
        if (mc.player.distanceTo(t) > REACH) return;
        if (findAxe(mc) < 0) return;

        target    = t;
        savedSlot = mc.player.getInventory().getSelectedSlot();
        timer     = REACT_MIN + RNG.nextInt(REACT_MAX - REACT_MIN + 1);
        if (RNG.nextFloat() < 0.1f) timer++;
        state     = State.REACT;
    }

    private static void tickReact(Minecraft mc) {
        if (!validateTarget(mc)) { hardReset(mc); return; }
        if (--timer > 0) return;

        int axe = findAxe(mc);
        if (axe < 0) { hardReset(mc); return; }

        InventoryManager.saveCurrentSlot(mc);
        InventoryManager.selectSlot(mc, axe);
        timer = 1;
        state = State.SWAP;
    }

    private static void tickSwap(Minecraft mc) {
        if (--timer > 0) return;
        if (!validateTarget(mc)) { hardReset(mc); return; }
        aimTicks = 0;
        state    = State.AIM;
    }

    private static void tickAim(Minecraft mc) {
        if (!validateTarget(mc)) { hardReset(mc); return; }
        aimTicks++;

        Vec3 aim = target.getEyePosition(1.0f);
        float ang = (float) Math.sqrt(
            Math.pow(RotationManager.computeYawError(mc, aim), 2) +
            Math.pow(RotationManager.computePitchError(mc, aim), 2)
        );
        float spd = ang > 15.0f ? 0.82f : (0.3f + 0.52f * ang / 15.0f);
        RotationManager.setEasingMode(RotationManager.EasingMode.SWIGHT_HIGH_SENS);
        RotationManager.smoothTo(mc, aim, spd);

        boolean ok = RotationManager.isAligned(mc, aim, AIM_YT, AIM_PT);
        if (ok || aimTicks >= AIM_MAX) {
            if (!ok) RotationManager.snapTo(mc, aim);
            aimTicks = 0;
            timer    = 1;
            state    = State.ATTACK;
        }
    }

    private static void tickAttack(Minecraft mc) {
        if (--timer > 0) return;
        if (!validateTarget(mc)) { doRestore(mc); return; }

        float strength = mc.player.getAttackStrengthScale(0.5f);
        if (strength < 0.82f && timer < 3) { timer = 1; return; }

        InteractionManager.simulateClickAttack(mc, InteractionManager.InteractionPriority.HIGH);
        mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        timer = 1;
        state = State.RESTORE;
    }

    private static void tickRestore(Minecraft mc) {
        if (--timer > 0) return;
        doRestore(mc);
    }

    private static void doRestore(Minecraft mc) {
        InventoryManager.restoreSavedSlot(mc);
        savedSlot = -1;
        timer     = COOLDOWN_T + RNG.nextInt(6);
        state     = State.COOLDOWN;
    }

    private static boolean validateTarget(Minecraft mc) {
        if (target == null || !target.isAlive()) return false;
        if (!target.isBlocking()) return false;
        if (mc.player.distanceTo(target) > REACH + 1.0) return false;
        return true;
    }

    private static void hardReset(Minecraft mc) {
        if (mc != null && mc.player != null && savedSlot >= 0 && savedSlot < 9) {
            mc.player.getInventory().setSelectedSlot(savedSlot);
        }
        InventoryManager.restoreSavedSlot(mc);
        RotationManager.reset();
        state    = State.IDLE;
        timer    = 0;
        aimTicks = 0;
        target   = null;
        savedSlot = -1;
    }

    private static int findAxe(Minecraft mc) {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).getItem() instanceof AxeItem) return i;
        }
        return -1;
    }

    private static LivingEntity getCrosshairTarget(Minecraft mc) {
        if (mc.hitResult == null || mc.hitResult.getType() != HitResult.Type.ENTITY) return null;
        if (!(mc.hitResult instanceof EntityHitResult ehr)) return null;
        if (!(ehr.getEntity() instanceof LivingEntity le)) return null;
        if (le == mc.player || (le instanceof Player p && p.isCreative())) return null;
        if (!le.isAlive()) return null;
        return le;
    }
}
