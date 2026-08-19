package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
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
import net.minecraft.world.phys.Vec3;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;

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
        if (delayTicks > 0) { delayTicks--; return; }

        if (targetLockTicks <= 0 || activeTarget == null || !activeTarget.isAlive()) {
            activeTarget = findTarget(client);
            targetLockTicks = 5 + RANDOM.nextInt(8);
        } else {
            targetLockTicks--;
        }
        if (activeTarget == null) { resetState(client); return; }
        if (client.player.getY() <= activeTarget.getY() + 0.5) {
            activeTarget = null;
            resetState(client);
            return;
        }

        boolean isFalling = client.player.fallDistance >= (2.8f + RANDOM.nextFloat() * 0.4f)
                && !client.player.onGround()
                && !client.player.isInWater();

        if (isFalling) {
            applyAim(client, activeTarget);
            double hitDist = 2.9 + RANDOM.nextDouble() * 0.2;
            if (client.player.distanceTo(activeTarget) <= hitDist) {
                float strength = client.player.getAttackStrengthScale(0.0f);
                if (strength < 0.85f) return;

                // anti-flag: miss aleatório
                if (RANDOM.nextInt(100) < (3 + (state == State.SMASH_ATTACK ? 2 : 0))) {
                    missChance = 2;
                }
                if (missChance > 0) {
                    missChance--;
                    client.player.setYRot(client.player.getYRot() + (RANDOM.nextFloat() - 0.5f) * 20f);
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
                    boolean preferDensity = client.player.fallDistance > (6.5 + RANDOM.nextFloat() * 1.5f);
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
        Vec3 eyePos = client.player.getEyePosition();
        double heightOffset = 0.3 + RANDOM.nextDouble() * 0.5;
        double jitterX = (RANDOM.nextDouble() - 0.5) * 0.06;
        double jitterY = (RANDOM.nextDouble() - 0.5) * 0.05;
        double jitterZ = (RANDOM.nextDouble() - 0.5) * 0.06;
        Vec3 targetPoint = new Vec3(
                target.getX() + jitterX,
                target.getY() + (target.getBbHeight() * heightOffset) + jitterY,
                target.getZ() + jitterZ
        );
        double dx = targetPoint.x - eyePos.x;
        double dy = targetPoint.y - eyePos.y;
        double dz = targetPoint.z - eyePos.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        float targetYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        float targetPitch = (float) -Math.toDegrees(Math.atan2(dy, dist));
        if (Float.isNaN(smoothYaw) || Float.isNaN(smoothPitch)) {
            smoothYaw = client.player.getYRot();
            smoothPitch = client.player.getXRot();
        }
        float attention = 0.6f + (float)(1.0 / (dist + 0.5)) * 0.3f;
        float yawDiff = wrapAngle(targetYaw - smoothYaw);
        float pitchDiff = targetPitch - smoothPitch;
        float maxTurn = 5.0f + RANDOM.nextFloat() * 3.0f;
        yawDiff = Math.max(-maxTurn, Math.min(maxTurn, yawDiff * attention));
        pitchDiff = Math.max(-maxTurn * 0.6f, Math.min(maxTurn * 0.6f, pitchDiff * attention));
        float noiseYaw = (RANDOM.nextFloat() - 0.5f) * 0.08f;
        float noisePitch = (RANDOM.nextFloat() - 0.5f) * 0.06f;
        float finalYaw = smoothYaw + yawDiff + noiseYaw;
        float finalPitch = Math.max(-90.0f, Math.min(90.0f, smoothPitch + pitchDiff + noisePitch));
        double sens = client.options.sensitivity().get() * 0.6 + 0.2;
        double gcd = Math.pow(sens, 1.2);
        gcd = Math.max(0.05, Math.min(0.4, gcd));
        float deltaYaw = finalYaw - client.player.getYRot();
        float deltaPitch = finalPitch - client.player.getXRot();
        if (Math.abs(deltaYaw) > 0.01 || Math.abs(deltaPitch) > 0.01) {
            deltaYaw = (float) (Math.round(deltaYaw / gcd) * gcd);
            deltaPitch = (float) (Math.round(deltaPitch / gcd) * gcd);
        }
        smoothYaw = client.player.getYRot() + deltaYaw;
        smoothPitch = client.player.getXRot() + deltaPitch;
        client.player.setYRot(smoothYaw);
        client.player.setXRot(smoothPitch);
        client.player.yRotO = smoothYaw - (RANDOM.nextFloat() - 0.5f) * 0.5f;
        client.player.xRotO = smoothPitch - (RANDOM.nextFloat() - 0.5f) * 0.3f;
        client.player.yHeadRot = smoothYaw;
    }

    private static float wrapAngle(float angle) {
        float wrapped = angle % 360.0f;
        if (wrapped >= 180.0f) wrapped -= 360.0f;
        if (wrapped < -180.0f) wrapped += 360.0f;
        return wrapped;
    }

    private static int findAxeSlot(Minecraft client) {
        int bestSlot = -1, bestLevel = -1;
        RegistryAccess registryAccess = client.level.registryAccess();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack.getItem() instanceof AxeItem) {
                int level = getEnchantmentLevel(registryAccess, Enchantments.SHARPNESS, stack);
                if (level > bestLevel) { bestLevel = level; bestSlot = i; }
            }
        }
        return bestSlot;
    }

    private static int findBestMaceSlot(Minecraft client, boolean preferDensity) {
        int bestSlot = -1, maxLevel = -1;
        RegistryAccess registryAccess = client.level.registryAccess();
        var enchantKey = preferDensity ? Enchantments.DENSITY : Enchantments.BREACH;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack.isEmpty() || !stack.is(Items.MACE)) continue;
            int level = getEnchantmentLevel(registryAccess, enchantKey, stack);
            if (level > maxLevel) { maxLevel = level; bestSlot = i; }
        }
        return bestSlot;
    }

    private static int getEnchantmentLevel(RegistryAccess registryAccess, net.minecraft.resources.ResourceKey<Enchantment> enchantmentKey, ItemStack stack) {
        var registryLookup = registryAccess.lookupOrThrow(Registries.ENCHANTMENT);
        Optional<Holder.Reference<Enchantment>> enchHolder = registryLookup.get(enchantmentKey);
        return enchHolder.map(holder -> EnchantmentHelper.getItemEnchantmentLevel(holder, stack)).orElse(0);
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
