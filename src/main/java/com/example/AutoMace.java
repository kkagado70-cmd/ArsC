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
import net.minecraft.world.item.ItemStack;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

import java.util.Optional;
import java.util.Random;

public class AutoMace implements ClientModInitializer {
    private static final Minecraft mc = Minecraft.getInstance();
    private static KeyMapping toggleKey;
    public static boolean enabled = false;

    private static final double MAX_AIM_RANGE = 7.0D;
    private static final double SPEAR_RANGE = 4.5D;
    private static final double SWING_RANGE = 3.0D;
    private static final double MIN_FALL_DIST = 1.3D;

    private static int state = 0;
    private static int delayTimer = 0;
    private static int keyReleaseTimer = 0;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.automace.toggle",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_M,
                KeyMapping.Category.MISC
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (mc.player == null || mc.level == null) return;

            while (toggleKey.consumeClick()) {
                enabled = !enabled;
                resetState();
            }

            if (enabled) {
                onTick();
            }
        });
    }

    public static void resetState() {
        state = 0;
        delayTimer = 0;
        releaseAttackKey();
    }

    private static void releaseAttackKey() {
        if (mc.options != null) {
            mc.options.keyAttack.setDown(false);
        }
    }

    public static void onTick() {
        if (mc.player == null || mc.level == null) return;

        if (keyReleaseTimer > 0) {
            keyReleaseTimer--;
            if (keyReleaseTimer == 0) {
                releaseAttackKey();
            }
        }

        if (delayTimer > 0) {
            delayTimer--;
            return;
        }

        Player target = acquireTarget(MAX_AIM_RANGE);
        if (target == null) {
            if (state != 0) resetState();
            return;
        }

        double dist = mc.player.distanceTo(target);
        boolean isFalling = mc.player.fallDistance >= MIN_FALL_DIST && mc.player.getDeltaMovement().y < -0.1D;
        boolean useSpear = dist > SWING_RANGE && dist <= SPEAR_RANGE && hasSpear();

        // STRICT GATE: Only aim when actually falling or using spear. Zero camera movement on flat ground!
        if (isFalling || useSpear) {
            applyButterSmoothAim(target);
        }

        switch (state) {
            case 0:
                if (useSpear) {
                    state = 1;
                } else if (isFalling && hasMace()) {
                    state = 4;
                }
                break;
            case 1:
                if (selectSpearSlot()) {
                    delayTimer = 2 + new Random().nextInt(2);
                    state = 2;
                } else {
                    state = 0;
                }
                break;
            case 2:
                if (dist <= SPEAR_RANGE && mc.player.getAttackStrengthScale(0.0F) >= 0.9F) {
                    if (hasLineOfSight(target) && validateFOV(target)) {
                        mc.options.keyAttack.setDown(true);
                        keyReleaseTimer = 2;
                        delayTimer = 2 + new Random().nextInt(2);
                        if (isFalling && hasMace()) {
                            state = 4;
                        } else {
                            state = 7;
                        }
                    }
                }
                break;
            case 4:
                if (selectMaceSlot()) {
                    delayTimer = 2 + new Random().nextInt(2);
                    state = 5;
                } else {
                    state = 7;
                }
                break;
            case 5:
                if (dist <= SWING_RANGE && isFalling && mc.player.getAttackStrengthScale(0.0F) >= 0.9F) {
                    if (hasLineOfSight(target) && validateFOV(target)) {
                        mc.options.keyAttack.setDown(true);
                        keyReleaseTimer = 2;
                        delayTimer = 2 + new Random().nextInt(2);
                        state = 7;
                    }
                }
                break;
            case 7:
                resetState();
                break;
        }
    }

    private static void applyButterSmoothAim(Player target) {
        Vec3 center = target.getBoundingBox().getCenter();
        double dx = center.x - mc.player.getX();
        double dy = center.y - mc.player.getEyeY();
        double dz = center.z - mc.player.getZ();
        double hDist = Math.sqrt(dx * dx + dz * dz);

        float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, hDist)));

        float yawDelta = Mth.wrapDegrees(targetYaw - mc.player.getYRot());
        float pitchDelta = Mth.wrapDegrees(targetPitch - mc.player.getXRot());

        // Butter-smooth interpolation factor with zero violent shaking
        float smoothness = 0.40F;
        float finalYaw = mc.player.getYRot() + yawDelta * smoothness;
        float finalPitch = mc.player.getXRot() + pitchDelta * smoothness;

        mc.player.setYRot(finalYaw);
        mc.player.setXRot(Mth.clamp(finalPitch, -90.0F, 90.0F));
    }

    private static Player acquireTarget(double range) {
        if (mc.level == null || mc.player == null) return null;
        Player best = null;
        double minDist = Double.MAX_VALUE;
        for (Player p : mc.level.players()) {
            if (p != mc.player && p.isAlive() && !p.isSpectator()) {
                double dist = mc.player.distanceToSqr(p);
                if (dist <= range * range && dist < minDist) {
                    minDist = dist;
                    best = p;
                }
            }
        }
        return best;
    }

    private static boolean hasLineOfSight(Player target) {
        return mc.player != null && target != null && mc.player.hasLineOfSight(target);
    }

    private static boolean validateFOV(Player target) {
        Vec3 toTarget = target.position().subtract(mc.player.position()).normalize();
        Vec3 look = mc.player.getViewVector(1.0F);
        return look.dot(toTarget) >= 0.2D;
    }

    private static boolean hasSpear() {
        return findItemSlotByName("spear") != -1;
    }

    private static boolean hasMace() {
        return findItemSlotByClass(MaceItem.class) != -1;
    }

    private static boolean selectSpearSlot() {
        int slot = findItemSlotByName("spear");
        if (slot != -1) {
            mc.player.getInventory().setSelectedSlot(slot);
            simulateNumberKey(slot + 1);
            return true;
        }
        return false;
    }

    private static boolean selectMaceSlot() {
        int slot = findItemSlotByClass(MaceItem.class);
        if (slot != -1) {
            mc.player.getInventory().setSelectedSlot(slot);
            simulateNumberKey(slot + 1);
            return true;
        }
        return false;
    }

    private static int findItemSlotByName(String name) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            String id = stack.getItem().getDescriptionId().toLowerCase();
            if (id.contains(name) || stack.getHoverName().getString().toLowerCase().contains(name)) {
                return i;
            }
        }
        return -1;
    }

    private static int findItemSlotByClass(Class<?> clazz) {
        for (int i = 0; i < 9; i++) {
            if (clazz.isInstance(mc.player.getInventory().getItem(i).getItem())) {
                return i;
            }
        }
        return -1;
    }

    private static void simulateNumberKey(int slotNum) {
        if (slotNum >= 1 && slotNum <= 9) {
            mc.options.keyHotbarSlots[slotNum - 1].setDown(true);
            mc.options.keyHotbarSlots[slotNum - 1].setDown(false);
        }
    }
            }
