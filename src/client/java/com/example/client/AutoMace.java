package com.example.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.AABB;
import java.util.*;

public class AutoMace {
    public enum State { IDLE, SMASH_ATTACK }

    public static boolean enabled = false;

    private static State state = State.IDLE;
    private static long lastActionTime = 0;
    private static int originalSlot = -1;
    private static boolean isSwapped = false;
    private static int delayTicks = 0;
    private static LivingEntity activeTarget = null;
    private static int targetLockTicks = 0;
    private static final Random RANDOM = new Random();

    private static float smoothYaw = Float.NaN;
    private static float smoothPitch = Float.NaN;

    private static int missChance = 0;

    public static void onTick(Minecraft client) {
        if (!enabled || client.player == null || client.level == null) {
            if (isSwapped) resetState(client);
            return;
        }

        if (delayTicks > 0) {
            delayTicks--;
            return;
        }

        if (targetLockTicks <= 0 || activeTarget == null || !activeTarget.isAlive()) {
            activeTarget = findTarget(client);
            targetLockTicks = 5 + RANDOM.nextInt(8);
        } else {
            targetLockTicks--;
        }

        if (activeTarget == null) {
            resetState(client);
            return;
        }

        if (client.player.getY() <= activeTarget.getY() + 0.5) {
            activeTarget = null;
            resetState(client);
            return;
        }

        boolean isFalling = client.player.fallDistance >= 3.0f
                && !client.player.onGround()
                && !client.player.isInWater();

        if (isFalling) {
            applyAim(client, activeTarget);

            if (client.player.distanceTo(activeTarget) <= 3.05) {
                float strength = client.player.getAttackStrengthScale(0.0f);
                if (strength < 0.85f) return;

                if (RANDOM.nextInt(100) < 3) {
                    missChance = 2;
                }

                if (missChance > 0) {
                    missChance--;
                    client.player.setYRot(client.player.getYRot() + (RANDOM.nextFloat() - 0.5f) * 15f);
                    return;
                }

                boolean isShielding = activeTarget instanceof Player p
                        && p.isUsingItem()
                        && p.getUseItem().getItem() instanceof ShieldItem;

                if (isShielding && state == State.IDLE) {
                    int axeSlot = findAxeSlot(client);
                    if (axeSlot != -1) {
                        if (originalSlot == -1) originalSlot = client.player.getInventory().getSelectedSlot();
                        client.player.getInventory().setSelectedSlot(axeSlot);
                        client.gameMode.attack(client.player, activeTarget);
                        client.player.swing(InteractionHand.MAIN_HAND);
                        isSwapped = true;
                        state = State.SMASH_ATTACK;
                        delayTicks = 3 + RANDOM.nextInt(4);
                        lastActionTime = System.currentTimeMillis();
                        return;
                    }
                }

                if (state == State.SMASH_ATTACK || state == State.IDLE) {
                    boolean preferDensity = client.player.fallDistance > 6.5;
                    int maceSlot = findBestMaceSlot(client, preferDensity);
                    if (maceSlot != -1) {
                        if (originalSlot == -1) originalSlot = client.player.getInventory().getSelectedSlot();
                        client.player.getInventory().setSelectedSlot(maceSlot);
                        isSwapped = true;
                    }

                    client.gameMode.attack(client.player, activeTarget);
                    client.player.swing(InteractionHand.MAIN_HAND);
                    lastActionTime = System.currentTimeMillis();

                    delayTicks = 4 + RANDOM.nextInt(8);
                    resetState(client);
                }
            }
        } else if (client.player.onGround()) {
            if (state != State.IDLE) {
                delayTicks = 2 + RANDOM.nextInt(5);
                resetState(client);
            }
        }
    }

    private static LivingEntity findTarget(Minecraft client) {
        AABB box = client.player.getBoundingBox().inflate(6.5, 400, 6.5);
        List<LivingEntity> entities = client.level.getEntitiesOfClass(LivingEntity.class, box, e ->
                e != client.player && e.isAlive() && !e.isDeadOrDying()
                        && client.player.getY() > e.getY() + 0.5
        );

        return entities.stream()
                .min(Comparator.comparingDouble(e -> client.player.distanceToSqr(e)))
                .orElse(null);
    }

    private static void applyAim(Minecraft client, LivingEntity target) {
        // Aim logic with smooth GCD-based turning
        // ... (código de aim, igual ao anterior, sem alterações conceituais)
    }

    private static float wrapAngle(float angle) {
        float wrapped = angle % 360.0f;
        if (wrapped >= 180.0f) wrapped -= 360.0f;
        if (wrapped < -180.0f) wrapped += 360.0f;
        return wrapped;
    }

    private static int findAxeSlot(Minecraft client) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack.getItem() instanceof AxeItem) {
                return i;
            }
        }
        return -1;
    }

    private static int findBestMaceSlot(Minecraft client, boolean preferDensity) {
        int bestSlot = -1;
        int maxLevel = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack.isEmpty() || !stack.is(Items.MACE)) continue;

            var enchantKey = preferDensity ? Enchantments.DENSITY : Enchantments.BREACH;
            Holder<Enchantment> holder = BuiltInRegistries.ENCHANTMENT.getHolder(enchantKey).orElse(null);
            int level = 0;
            if (holder != null) {
                level = EnchantmentHelper.getItemEnchantmentLevel(holder, stack);
            }
            if (level > maxLevel) {
                maxLevel = level;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    private static void resetState(Minecraft client) {
        if (isSwapped && originalSlot != -1 && client.player != null) {
            if (System.currentTimeMillis() - lastActionTime > 150) {
                client.player.getInventory().setSelectedSlot(originalSlot);
            }
        }
        originalSlot = -1;
        isSwapped = false;
        state = State.IDLE;
        if (Float.isNaN(smoothYaw)) {
            smoothYaw = client.player != null ? client.player.getYRot() : 0;
            smoothPitch = client.player != null ? client.player.getXRot() : 0;
        }
    }
}
