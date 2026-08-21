package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.InteractionHand;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

public class AutoMace implements ClientModInitializer {
    private static final Minecraft mc = Minecraft.getInstance();
    private static KeyMapping toggleKey;
    public static boolean enabled = false;
    
    // Comprehensive combat parameters
    private static double swingRange = 2.95D;
    private static double aimRange = 15.0D;
    private static boolean autoSwitch = true;
    private static boolean swapBack = true;
    private static double rotationSpeed = 24.0D;
    private static double minFallDist = 1.5D;
    private static long cooldownMs = 500L;
    private static long maceSwapDelayMs = 1L;
    private static boolean stunSlam = true;
    private static boolean weaponOnly = false;
    private static boolean ignoreFriends = true;

    private static Player currentTarget = null;
    private static int preSequenceSlot = -1;
    private static long lastComboTime = 0L;
    private static long axeHitTime = 0L;
    private static int resetTimer = 0;
    private static double highestY = 0.0D;
    private static boolean shouldBreakShield = false;
    private static boolean shouldMaceSmash = false;
    private static int targetSlotForAttack = -1;
    private static int delayTicks = 0;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.automace.toggle",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_M,
            "category.automace"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (mc.player == null || mc.level == null) return;

            while (toggleKey.consumeClick()) {
                enabled = !enabled;
                mc.player.displayClientMessage(Component.literal("§6[AutoMace] " + (enabled ? "§aEnabled" : "§cDisabled")), true);
            }

            if (enabled) {
                onTick(client);
            }
        });
    }

    public static void onTick(Minecraft client) {
        if (mc.player == null || mc.level == null) return;

        if (delayTicks > 0) {
            delayTicks--;
            return;
        }

        if (mc.player.onGround()) {
            highestY = mc.player.getY();
        } else {
            highestY = Math.max(highestY, mc.player.getY());
        }
        double manualFallDist = Math.max(0.0D, highestY - mc.player.getY());

        int bestMaceSlot = findBestMace();
        boolean isHoldingMace = mc.player.getMainHandItem().getItem() instanceof MaceItem;
        boolean canUseMace = isHoldingMace || (autoSwitch && bestMaceSlot != -1);

        if (!canUseMace) return;
        if (weaponOnly && !isHoldingWeapon()) return;

        if (resetTimer > 0) {
            resetTimer--;
            return;
        }

        if (System.currentTimeMillis() - lastComboTime < cooldownMs) return;

        currentTarget = findTarget();
        if (currentTarget == null) return;

        boolean gameSaysFalling = mc.player.fallDistance >= minFallDist;
        boolean manualSaysFalling = manualFallDist >= minFallDist;
        if (!gameSaysFalling && !manualSaysFalling && minFallDist > 0.1D) return;

        boolean isBlocking = isTargetBlocking(currentTarget);
        if (stunSlam && isBlocking) {
            calculateStunSlam();
        } else {
            calculateDirectMaceLogic();
        }
    }

    private static void calculateStunSlam() {
        if (mc.player.distanceTo(currentTarget) > aimRange) {
            currentTarget = null;
            return;
        }
        int axeSlot = findAxe();
        int maceSlot = findBestMace();
        if (axeSlot != -1 && maceSlot != -1) {
            if (preSequenceSlot == -1) {
                preSequenceSlot = mc.player.getInventory().selected;
            }
            shouldBreakShield = true;
            targetSlotForAttack = axeSlot;
            executeShieldBreak();
        }
    }

    private static void executeShieldBreak() {
        if (currentTarget == null || !syncToAttackSlot()) return;
        mc.gameMode.attack(mc.player, currentTarget);
        mc.player.swing(InteractionHand.MAIN_HAND);
        axeHitTime = System.currentTimeMillis();
        calculateMaceLogic();
    }

    private static void calculateMaceLogic() {
        long timeSinceAxe = System.currentTimeMillis() - axeHitTime;
        if (timeSinceAxe < maceSwapDelayMs) return;
        
        int maceSlot = findBestMace();
        if (maceSlot != -1) {
            targetSlotForAttack = maceSlot;
            executeMaceSmash();
        }
    }

    private static void executeMaceSmash() {
        if (!syncToAttackSlot()) return;
        mc.gameMode.attack(mc.player, currentTarget);
        mc.player.swing(InteractionHand.MAIN_HAND);
        lastComboTime = System.currentTimeMillis();
        resetTimer = 8;
        swapBackToPreSequence();
    }

    private static void calculateDirectMaceLogic() {
        int maceSlot = findBestMace();
        if (currentTarget == null || !currentTarget.isAlive()) return;
        if (maceSlot != -1) {
            if (preSequenceSlot == -1) {
                preSequenceSlot = mc.player.getInventory().selected;
            }
            targetSlotForAttack = maceSlot;
            executeAttack();
        }
    }

    private static void executeAttack() {
        if (!syncToAttackSlot()) return;
        mc.gameMode.attack(mc.player, currentTarget);
        mc.player.swing(InteractionHand.MAIN_HAND);
        lastComboTime = System.currentTimeMillis();
        resetTimer = 5;
        swapBackToPreSequence();
    }

    private static void swapBackToPreSequence() {
        if (swapBack && autoSwitch && preSequenceSlot >= 0 && preSequenceSlot < 9) {
            mc.player.getInventory().selected = preSequenceSlot;
        }
        preSequenceSlot = -1;
    }

    private static boolean isHoldingWeapon() {
        if (mc.player == null) return false;
        return mc.player.getMainHandItem().getItem() instanceof MaceItem || 
               mc.player.getMainHandItem().getItem() instanceof AxeItem;
    }

    private static boolean isTargetBlocking(Player target) {
        if (target == null) return false;
        if (target.isUsingItem()) {
            return !target.getUseItem().isEmpty() && (target.getUseItem().getItem() instanceof ShieldItem);
        }
        return false;
    }

    private static int findBestMace() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).getItem() instanceof MaceItem) {
                return i;
            }
        }
        return -1;
    }

    private static int findAxe() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).getItem() instanceof AxeItem) {
                return i;
            }
        }
        return -1;
    }

    private static boolean syncToAttackSlot() {
        if (mc.player == null) return false;
        if (!autoSwitch || targetSlotForAttack < 0 || targetSlotForAttack > 8) return true;
        mc.player.getInventory().selected = targetSlotForAttack;
        return true;
    }

    private static Player findTarget() {
        if (mc.level == null || mc.player == null) return null;
        Player bestTarget = null;
        double bestDistSq = Double.MAX_VALUE;
        for (Player player : mc.level.players()) {
            if (player != mc.player && player.isAlive() && !player.isSpectator()) {
                double distSq = mc.player.distanceToSqr(player);
                if (distSq <= aimRange * aimRange && distSq < bestDistSq) {
                    bestDistSq = distSq;
                    bestTarget = player;
                }
            }
        }
        return bestTarget;
    }
}
