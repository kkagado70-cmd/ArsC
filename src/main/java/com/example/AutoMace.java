package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.awt.Color;
import java.util.List;
import java.util.Random;

/**
 * AutoMace Combat Module
 * Simplified implementation for Fabric Mod 1.21.11
 */
public class AutoMace {
    public static boolean enabled = false;

    // ===== CONFIGURAÇÕES =====
    private static double aimRange = 15.0;
    private static double minFallDist = 1.5;
    private static boolean autoSwitch = true;
    private static boolean swapBack = true;
    private static boolean stunSlam = true;
    private static long cooldownMs = 500;
    private static double rotationSpeed = 24.0;
    private static double swingRange = 3.0;
    private static double aimInAir = 4.5;
    private static double maceSwapDelayMs = 1.0;
    private static boolean weaponOnly = false;
    private static boolean ignoreFriends = true;
    private static boolean renderPred = false;
    private static boolean targetMode = false;
    private static double hitboxAccuracy = 0.3;
    private static String aimMode = "Strict";
    private static String stopAim = "Hitbox Edge";

    // ===== ESTADO =====
    private static LivingEntity currentTarget = null;
    private static int originalSlot = -1;
    private static int preSequenceSlot = -1;
    private static long lastComboTime = 0;
    private static int resetTimer = 0;
    private static double highestY = 0.0;
    private static boolean shouldBreakShield = false;
    private static int targetSlotForAttack = -1;
    private static final Random RANDOM = new Random();
    private static int maceClicksLeft = 0;
    private static long axeHitTime = 0L;
    private static boolean wasOnGround = true;
    private static boolean shouldAttackThisTick = false;
    private static boolean shouldMaceSmash = false;
    private static boolean isSwappingArmor = false;
    private static int armorSwapTimer = 0;
    private static int armorSwapReturnSlot = -1;
    private static final double ATTACK_RANGE = 3.0;

    public AutoMace() {
        // Constructor for potential future expansion
    }

    /**
     * Main tick logic - runs every game tick
     */
    public static void onTick() {
        if (!enabled) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }

        // Handle armor swap if in progress
        if (isSwappingArmor) {
            manageArmorSwap();
            return;
        }

        // Execute pending actions
        if (shouldBreakShield) {
            executeShieldBreak(mc);
        } else if (shouldMaceSmash) {
            executeMaceSmash(mc);
        } else if (shouldAttackThisTick) {
            executeAttack(mc);
        }

        // Main combat logic
        performCombatLogic(mc);
    }

    /**
     * Perform main combat logic each tick
     */
    private static void performCombatLogic(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            return;
        }

        // Check cooldown
        if (System.currentTimeMillis() - lastComboTime < cooldownMs) {
            return;
        }

        // Find target
        currentTarget = findTarget(mc);
        if (currentTarget == null) {
            stopAiming();
            return;
        }

        // Check if falling
        float fallDistance = mc.player.fallDistance;
        if (fallDistance < minFallDist && minFallDist > 0.1) {
            stopAiming();
            return;
        }

        // Check if target is blocking
        boolean isBlocking = isTargetBlocking(currentTarget);

        if (stunSlam && isBlocking) {
            performStunSlam(mc);
        } else {
            performDirectAttack(mc);
        }
    }

    /**
     * Perform stun slam sequence (axe to break shield, then mace)
     */
    private static void performStunSlam(Minecraft mc) {
        if (mc.player == null) {
            return;
        }

        int maceSlot = findBestMace(mc);
        int axeSlot = findAxe(mc);

        if (axeSlot != -1 && maceSlot != -1) {
            if (preSequenceSlot == -1) {
                preSequenceSlot = mc.player.getInventory().selected;
            }
            shouldBreakShield = true;
            targetSlotForAttack = axeSlot;
            originalSlot = maceSlot;
        }
    }

    /**
     * Perform direct mace attack
     */
    private static void performDirectAttack(Minecraft mc) {
        if (mc.player == null) {
            return;
        }

        int maceSlot = findBestMace(mc);
        if (maceSlot != -1) {
            if (preSequenceSlot == -1) {
                preSequenceSlot = mc.player.getInventory().selected;
            }
            shouldAttackThisTick = true;
            targetSlotForAttack = maceSlot;
        }
    }

    /**
     * Execute shield break with axe
     */
    private static void executeShieldBreak(Minecraft mc) {
        if (mc.player == null || currentTarget == null) {
            return;
        }

        if (!canAttackTarget(mc)) {
            return;
        }

        syncToAttackSlot(mc);
        mc.gameMode.attack(currentTarget);

        maceClicksLeft = 1;
        axeHitTime = System.currentTimeMillis();
    }

    /**
     * Execute mace smash
     */
    private static void executeMaceSmash(Minecraft mc) {
        if (mc.player == null || currentTarget == null) {
            return;
        }

        if (!canAttackTarget(mc)) {
            return;
        }

        syncToAttackSlot(mc);
        mc.gameMode.attack(currentTarget);

        maceClicksLeft = 0;
        resetTimer = 8;
        lastComboTime = System.currentTimeMillis();
    }

    /**
     * Execute normal attack
     */
    private static void executeAttack(Minecraft mc) {
        if (mc.player == null || currentTarget == null) {
            return;
        }

        if (!canAttackTarget(mc)) {
            return;
        }

        syncToAttackSlot(mc);
        mc.gameMode.attack(currentTarget);

        lastComboTime = System.currentTimeMillis();
        resetTimer = 5;
    }

    /**
     * Check if can attack target
     */
    private static boolean canAttackTarget(Minecraft mc) {
        if (mc.player == null || currentTarget == null) {
            return false;
        }

        // Check distance
        double distance = mc.player.distanceTo(currentTarget);
        if (distance > ATTACK_RANGE) {
            return false;
        }

        // Check line of sight
        return mc.player.canSee(currentTarget);
    }

    /**
     * Sync to attack slot in inventory
     */
    private static void syncToAttackSlot(Minecraft mc) {
        if (mc.player == null) {
            return;
        }

        if (!autoSwitch || targetSlotForAttack < 0 || targetSlotForAttack > 8) {
            return;
        }

        mc.player.getInventory().selected = targetSlotForAttack;
    }

    /**
     * Manage armor swap
     */
    private static void manageArmorSwap() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            isSwappingArmor = false;
            return;
        }

        armorSwapTimer--;
        if (armorSwapTimer <= 0) {
            if (armorSwapReturnSlot != -1) {
                mc.player.getInventory().selected = armorSwapReturnSlot;
            }
            isSwappingArmor = false;
            armorSwapReturnSlot = -1;
        }
    }

    /**
     * Find best mace in inventory
     */
    private static int findBestMace(Minecraft mc) {
        if (mc.player == null) {
            return -1;
        }

        // Looking for heavy weighted tools (mace)
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                // Check if it's a mace-like item (weighted heavy tool)
                // This is simplified - adjust based on your mod's mace item ID
                if (stack.getItem().toString().contains("mace")) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Find axe in inventory
     */
    private static int findAxe(Minecraft mc) {
        if (mc.player == null) {
            return -1;
        }

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.getItem() instanceof AxeItem) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Check if target is blocking with shield
     */
    private static boolean isTargetBlocking(LivingEntity target) {
        if (target == null) {
            return false;
        }

        if (target.isBlocking()) {
            return true;
        }

        ItemStack offhand = target.getOffhandItem();
        return offhand.getItem() instanceof ShieldItem;
    }

    /**
     * Find nearest valid target
     */
    private static LivingEntity findTarget(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            return null;
        }

        LivingEntity bestTarget = null;
        double bestDistance = aimRange * aimRange;

        for (Entity entity : mc.level.getEntities()) {
            if (entity instanceof LivingEntity && entity != mc.player) {
                LivingEntity living = (LivingEntity) entity;

                // Check if alive
                if (!living.isAlive()) {
                    continue;
                }

                // Check distance
                double distSq = mc.player.distanceToSqr(living);
                if (distSq < bestDistance) {
                    bestDistance = distSq;
                    bestTarget = living;
                }
            }
        }

        return bestTarget;
    }

    /**
     * Stop aiming and reset state
     */
    private static void stopAiming() {
        currentTarget = null;
        maceClicksLeft = 0;
        shouldAttackThisTick = false;
        shouldBreakShield = false;
        shouldMaceSmash = false;
        targetSlotForAttack = -1;
        originalSlot = -1;
    }

    /**
     * Swap back to pre-sequence slot
     */
    private static void swapBackToPreSequence(Minecraft mc) {
        if (swapBack && autoSwitch && preSequenceSlot >= 0 && preSequenceSlot < 9) {
            if (mc.player != null) {
                mc.player.getInventory().selected = preSequenceSlot;
            }
        }
        preSequenceSlot = -1;
    }

    /**
     * Toggle AutoMace on/off
     */
    public static void toggle() {
        enabled = !enabled;
        if (!enabled) {
            stopAiming();
        }
    }

    /**
     * Get enabled status
     */
    public static boolean isEnabled() {
        return enabled;
    }
}
