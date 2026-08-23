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
import net.minecraft.world.InteractionHand;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

import java.util.Random;

public class AutoMace implements ClientModInitializer {
    private static final Minecraft mc = Minecraft.getInstance();
    private static KeyMapping toggleKey;
    public static boolean enabled = false;

    private static final double MAX_SWING_RANGE = 2.84D;
    private static final double MAX_AIM_RANGE = 4.5D;
    private static final double MIN_FALL_DIST = 1.3D;

    private static final Random random = new Random();
    private static int reactionDelayTicks = 0;
    private static final float ROTATION_SMOOTHNESS = 0.8F;

    private static Player currentTarget = null;
    private static State state = State.IDLE;
    private static int delayTimer = 0;
    private static int preSequenceSlot = -1;
    private static double highestY = 0.0D;

    public enum State {
        IDLE,
        AXE_SWAP,
        AXE_STRIKE,
        MACE_SWAP,
        MACE_SLAM,
        RESTORE_SLOT
    }

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.automace.toggle",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_M,
            "key.categories.misc"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (mc.player == null || mc.level == null) return;

            while (toggleKey.consumeClick()) {
                toggle();
            }

            if (enabled) {
                onTick(client);
            }
        });
    }

    public static void toggle() {
        enabled = !enabled;
        resetState();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("§6[AutoMace] " + (enabled ? "§aON" : "§cOFF")), true);
        }
    }

    public static void onTick(Minecraft client) {
        if (mc.player == null || mc.level == null) return;

        if (delayTimer > 0) {
            delayTimer--;
            return;
        }

        updateFallTracker();

        double currentFallDistance = Math.max(0.0D, highestY - mc.player.getY());

        boolean isFalling = mc.player.fallDistance >= MIN_FALL_DIST || currentFallDistance >= MIN_FALL_DIST;
        if (!isFalling) {
            if (state != State.IDLE) {
                restoreSlotAndReset();
            }
            return;
        }

        if (state == State.IDLE) {
            currentTarget = locateTarget(MAX_AIM_RANGE);
            if (currentTarget != null) {
                reactionDelayTicks = 1 + random.nextInt(2);
            }
        }

        if (currentTarget == null || !currentTarget.isAlive()) {
            restoreSlotAndReset();
            return;
        }

        if (reactionDelayTicks > 0) {
            reactionDelayTicks--;
            return;
        }

        boolean isBlocking = currentTarget.isUsingItem() && currentTarget.getUseItem().getItem() instanceof ShieldItem;

        switch (state) {
            case IDLE:
                if (preSequenceSlot == -1) {
                    preSequenceSlot = mc.player.getInventory().getSelectedSlot();
                }
                
                if (isBlocking) {
                    state = State.AXE_SWAP;
                } else {
                    state = State.MACE_SWAP;
                }
                break;

            case AXE_SWAP:
                int axeSlot = findAxeSlot();
                if (axeSlot != -1) {
                    setInventorySlot(axeSlot);
                    delayTimer = 2;
                    state = State.AXE_STRIKE;
                } else {
                    state = State.MACE_SWAP;
                }
                break;

            case AXE_STRIKE:
                if (canValidlyAttack(currentTarget)) {
                    applyProfessionalRotation(currentTarget);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                    mc.gameMode.attack(mc.player, currentTarget);
                    delayTimer = 2;
                    state = State.MACE_SWAP;
                }
                break;

            case MACE_SWAP:
                int maceSlot = selectOptimalMaceSlot(currentTarget, currentFallDistance);
                if (maceSlot != -1) {
                    setInventorySlot(maceSlot);
                    delayTimer = 2;
                    state = State.MACE_SLAM;
                } else {
                    restoreSlotAndReset();
                }
                break;

            case MACE_SLAM:
                if (canValidlyAttack(currentTarget)) {
                    applyProfessionalRotation(currentTarget);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                    mc.gameMode.attack(mc.player, currentTarget);
                    delayTimer = 2;
                    state = State.RESTORE_SLOT;
                }
                break;

            case RESTORE_SLOT:
                restoreSlotAndReset();
                break;
        }
    }

    private static void setInventorySlot(int slot) {
        if (mc.player == null) return;
        mc.player.getInventory().setSelectedSlot(slot);
        if (mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
        }
    }

    private static boolean canValidlyAttack(Player target) {
        if (mc.player == null || target == null) return false;
        if (!mc.player.hasLineOfSight(target)) return false;
        return mc.player.distanceTo(target) <= MAX_SWING_RANGE;
    }

    private static void updateFallTracker() {
        if (mc.player == null) return;
        if (mc.player.onGround()) {
            highestY = mc.player.getY();
        } else {
            highestY = Math.max(highestY, mc.player.getY());
        }
    }

    private static int selectOptimalMaceSlot(Player target, double fallDist) {
        if (mc.player == null || target == null) return -1;

        int bestSlot = -1;
        int maxDensityScore = -1;
        int maxBreachScore = -1;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.getItem() instanceof MaceItem) {
                int densityLevel = getEnchantmentLevel(stack, "density");
                int breachLevel = getEnchantmentLevel(stack, "breach");

                if (fallDist >= 7.0D) {
                    if (densityLevel > maxDensityScore) {
                        maxDensityScore = densityLevel;
                        bestSlot = i;
                    }
                } else {
                    if (breachLevel > maxBreachScore) {
                        maxBreachScore = breachLevel;
                        bestSlot = i;
                    }
                }

                if (bestSlot == -1) bestSlot = i;
            }
        }
        return bestSlot;
    }

    private static int getEnchantmentLevel(ItemStack stack, String enchantmentIdentifier) {
        if (stack.isEmpty()) return 0;
        ItemEnchantments enchantments = stack.get(DataComponents.ENCHANTMENTS);
        if (enchantments == null) return 0;

        for (var entry : enchantments.entrySet()) {
            String registeredName = entry.getKey().toString();
            if (registeredName.contains(enchantmentIdentifier)) {
                return entry.getIntValue();
            }
        }
        return 0;
    }

    private static void applyProfessionalRotation(Player target) {
        if (mc.player == null || target == null) return;

        AABB box = target.getBoundingBox();
        Vec3 center = box.getCenter();
        double aimY = box.minY + (target.getBbHeight() * 0.45D);
        Vec3 targetEyePos = new Vec3(center.x, aimY, center.z);

        double dx = targetEyePos.x - mc.player.getX();
        double dy = targetEyePos.y - mc.player.getEyeY();
        double dz = targetEyePos.z - mc.player.getZ();
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);

        float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontalDistance)));

        float yawDelta = Mth.wrapDegrees(targetYaw - mc.player.getYRot());
        float pitchDelta = Mth.wrapDegrees(targetPitch - mc.player.getXRot());

        float interpolatedYaw = mc.player.getYRot() + (yawDelta * ROTATION_SMOOTHNESS);
        float interpolatedPitch = mc.player.getXRot() + (pitchDelta * ROTATION_SMOOTHNESS);

        double sensitivity = mc.options.sensitivity().get();
        double f = sensitivity * 0.6D + 0.2D;
        double dg = f * f * f * 8.0D;
        double gcd = dg * 0.15D;

        float finalYaw = (float) (mc.player.getYRot() + Math.round((interpolatedYaw - mc.player.getYRot()) / gcd) * gcd);
        float finalPitch = (float) (mc.player.getXRot() + Math.round((interpolatedPitch - mc.player.getXRot()) / gcd) * gcd);

        mc.player.setYRot(finalYaw);
        mc.player.setXRot(Mth.clamp(finalPitch, -90.0F, 90.0F));
    }

    private static int findAxeSlot() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).getItem() instanceof AxeItem) {
                return i;
            }
        }
        return -1;
    }

    private static Player locateTarget(double range) {
        if (mc.level == null || mc.player == null) return null;
        Player bestTarget = null;
        double bestDistSq = Double.MAX_VALUE;

        for (Player player : mc.level.players()) {
            if (player != mc.player && player.isAlive() && !player.isSpectator()) {
                double distSq = mc.player.distanceToSqr(player);
                if (distSq <= range * range && distSq < bestDistSq) {
                    bestDistSq = distSq;
                    bestTarget = player;
                }
            }
        }
        return bestTarget;
    }

    private static void restoreSlotAndReset() {
        if (mc.player != null && preSequenceSlot >= 0 && preSequenceSlot < 9) {
            setInventorySlot(preSequenceSlot);
        }
        resetState();
    }

    private static void resetState() {
        currentTarget = null;
        state = State.IDLE;
        delayTimer = 0;
        preSequenceSlot = -1;
        reactionDelayTicks = 0;
    }
        }
