package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

public class XbowCart implements ClientModInitializer {
    private static ClientBaseInfrastructure infrastructure;

    @Override
    public void onInitializeClient() {
        infrastructure = new ClientBaseInfrastructure();
        infrastructure.init();
    }

    public static boolean enabled = false;
    public static void toggle() {
        enabled = !enabled;
        if (!enabled) {
            XbowEngine.resetSequence();
        }
    }

    public static void onTick(Minecraft client) {
        if (!enabled) return;
        XbowEngine.tick(client);
    }

    public static class ClientBaseInfrastructure {
        private static KeyMapping toggleKey;

        public void init() {
            toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                    "key.xbowcart.toggle",
                    InputConstants.Type.KEYSYM,
                    GLFW.GLFW_KEY_X,
                    KeyMapping.Category.MISC
            ));

            ClientTickEvents.END_CLIENT_TICK.register(client -> {
                if (client.player == null || client.level == null) return;
                while (toggleKey.consumeClick()) {
                    toggle();
                }
                if (enabled) {
                    onTick(client);
                }
            });
        }
    }

    public static class RotationManager {
        public static Vec3 getRailTarget(BlockPos pos, Direction face) {
            if (face == Direction.UP) {
                return Vec3.atCenterOf(pos);
            }
            return Vec3.atCenterOf(pos.relative(face));
        }

        public static Vec3 getCartTarget(BlockPos pos, Direction face) {
            if (face == Direction.UP) {
                return Vec3.atCenterOf(pos);
            }
            return Vec3.atCenterOf(pos.relative(face));
        }

        public static Vec3 getFireTarget(Minecraft client, BlockPos pos, Direction face) {
            if (face == Direction.UP) {
                return Vec3.atCenterOf(pos.relative(client.player.getDirection().getOpposite()));
            }
            return Vec3.atCenterOf(pos);
        }

        public static Vec3 getShootTarget(BlockPos pos, Direction face) {
            BlockPos target = face == Direction.UP ? pos : pos.relative(face);
            return Vec3.atCenterOf(target).add(0.0D, 0.2D, 0.0D);
        }

        public static void smoothTo(Minecraft client, Vec3 target) {
            if (client.player == null) return;
            double dx = target.x - client.player.getX();
            double dy = target.y - client.player.getEyeY();
            double dz = target.z - client.player.getZ();
            double hDist = Math.sqrt(dx * dx + dz * dz);

            float targetYaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI) - 90.0D);
            float targetPitch = (float) (-(Mth.atan2(dy, hDist) * (180.0 / Math.PI)));
            targetPitch = Mth.clamp(targetPitch, -85.0F, 85.0F);

            float currentYaw = client.player.getYRot();
            float currentPitch = client.player.getXRot();

            float yawDiff = Mth.wrapDegrees(targetYaw - currentYaw);
            float pitchDiff = targetPitch - currentPitch;

            client.player.setYRot(currentYaw + yawDiff * 0.85F);
            client.player.setXRot(currentPitch + pitchDiff * 0.85F);
        }
    }

    public static class InventoryManager {
        public static void selectSlot(Minecraft client, int slot) {
            if (client.player == null || slot < 0 || slot > 8) return;
            client.player.getInventory().setSelectedSlot(slot);
        }

        public static int findItem(Minecraft client, Item targetItem) {
            if (client.player == null) return -1;
            for (int i = 0; i < 9; i++) {
                if (client.player.getInventory().getItem(i).getItem() == targetItem) {
                    return i;
                }
            }
            return -1;
        }

        public static int findRail(Minecraft client) {
            if (client.player == null) return -1;
            for (int i = 0; i < 9; i++) {
                Item item = client.player.getInventory().getItem(i).getItem();
                if (XbowEngine.isAnyRail(item)) {
                    return i;
                }
            }
            return -1;
        }

        public static int findChargedCrossbow(Minecraft client) {
            if (client.player == null) return -1;
            for (int i = 0; i < 9; i++) {
                ItemStack stack = client.player.getInventory().getItem(i);
                if (stack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(stack)) {
                    return i;
                }
            }
            return -1;
        }
    }

    public static class RaycastManager {
        public static BlockHitResult getValidHit(Minecraft client) {
            if (client.hitResult != null && client.hitResult.getType() == HitResult.Type.BLOCK) {
                if (client.hitResult instanceof BlockHitResult blockHit) {
                    if (blockHit.getDirection() != Direction.DOWN) {
                        return blockHit;
                    }
                }
            }
            return null;
        }
    }

    public static class InteractionManager {
        private static int releaseTracker = 0;

        public static void update(Minecraft client) {
            if (releaseTracker > 0) {
                releaseTracker--;
                if (releaseTracker == 0 && client.options != null) {
                    client.options.keyUse.setDown(false);
                }
            }
        }

        public static void clickUse(Minecraft client) {
            releaseTracker = 2;
            client.options.keyUse.setDown(false);
            client.options.keyUse.setDown(true);
        }
    }

    public static class SafetyWatchdog {
        private long startEpoch = 0L;
        private final long timeoutMs = 1500L;

        public void arm() {
            startEpoch = System.currentTimeMillis();
        }

        public boolean isTimedOut() {
            return startEpoch > 0 && (System.currentTimeMillis() - startEpoch > timeoutMs);
        }

        public void disarm() {
            startEpoch = 0L;
        }
    }

    public static class XbowEngine {
        private enum Phase {
            INACTIVE, 
            RAIL_SELECT, RAIL_DEPLOY,
            CART_SELECT, CART_DEPLOY,
            FIRE_SELECT, FIRE_DEPLOY,
            CROSSBOW_SELECT, CROSSBOW_FIRE
        }

        private static Phase phase = Phase.INACTIVE;
        private static int delayTicks = 0;
        private static int globalCooldown = 0;
        private static BlockPos targetBlockPos = null;
        private static Direction targetFace = Direction.UP;
        private static final SafetyWatchdog watchdog = new SafetyWatchdog();

        public static boolean isAnyRail(Item item) {
            return item == Items.RAIL || 
                   item == Items.POWERED_RAIL || 
                   item == Items.DETECTOR_RAIL || 
                   item == Items.ACTIVATOR_RAIL;
        }

        public static void tick(Minecraft client) {
            InteractionManager.update(client);

            if (client.player == null || client.level == null) return;

            if (globalCooldown > 0) {
                globalCooldown--;
                return;
            }

            if (watchdog.isTimedOut()) {
                resetSequence();
                return;
            }

            if (delayTicks > 0) {
                delayTicks--;
                return;
            }

            if (phase != Phase.INACTIVE && (!enabled || client.player == null || client.level == null)) {
                resetSequence();
                return;
            }

            switch (phase) {
                case INACTIVE:
                    BlockHitResult hit = RaycastManager.getValidHit(client);
                    if (hit == null || !isHoldingRail(client) || InventoryManager.findChargedCrossbow(client) == -1) {
                        return;
                    }

                    targetBlockPos = hit.getBlockPos();
                    targetFace = hit.getDirection();

                    watchdog.arm();
                    phase = Phase.RAIL_SELECT;
                    break;

                case RAIL_SELECT:
                    int railSlot = InventoryManager.findRail(client);
                    if (railSlot != -1) {
                        InventoryManager.selectSlot(client, railSlot);
                        delayTicks = 1;
                        phase = Phase.RAIL_DEPLOY;
                    } else {
                        resetSequence();
                    }
                    break;

                case RAIL_DEPLOY:
                    if (targetBlockPos != null) {
                        RotationManager.smoothTo(client, RotationManager.getRailTarget(targetBlockPos, targetFace));
                        InteractionManager.clickUse(client);
                    }
                    delayTicks = 1;
                    phase = Phase.CART_SELECT;
                    break;

                case CART_SELECT:
                    int cartSlot = InventoryManager.findItem(client, Items.TNT_MINECART);
                    if (cartSlot != -1) {
                        InventoryManager.selectSlot(client, cartSlot);
                        delayTicks = 1;
                        phase = Phase.CART_DEPLOY;
                    } else {
                        resetSequence();
                    }
                    break;

                case CART_DEPLOY:
                    if (targetBlockPos != null) {
                        RotationManager.smoothTo(client, RotationManager.getCartTarget(targetBlockPos, targetFace));
                        InteractionManager.clickUse(client);
                    }
                    delayTicks = 1;
                    phase = Phase.FIRE_SELECT;
                    break;

                case FIRE_SELECT:
                    int fireSlot = InventoryManager.findItem(client, Items.FLINT_AND_STEEL);
                    if (fireSlot == -1) {
                        fireSlot = InventoryManager.findItem(client, Items.FIRE_CHARGE);
                    }
                    if (fireSlot != -1) {
                        InventoryManager.selectSlot(client, fireSlot);
                        delayTicks = 1;
                        phase = Phase.FIRE_DEPLOY;
                    } else {
                        resetSequence();
                    }
                    break;

                case FIRE_DEPLOY:
                    if (targetBlockPos != null) {
                        RotationManager.smoothTo(client, RotationManager.getFireTarget(client, targetBlockPos, targetFace));
                        InteractionManager.clickUse(client);
                    }
                    delayTicks = 1;
                    phase = Phase.CROSSBOW_SELECT;
                    break;

                case CROSSBOW_SELECT:
                    int crossbowSlot = InventoryManager.findChargedCrossbow(client);
                    if (crossbowSlot != -1) {
                        InventoryManager.selectSlot(client, crossbowSlot);
                        delayTicks = 1;
                        phase = Phase.CROSSBOW_FIRE;
                    } else {
                        resetSequence();
                    }
                    break;

                case CROSSBOW_FIRE:
                    if (targetBlockPos != null) {
                        RotationManager.smoothTo(client, RotationManager.getShootTarget(targetBlockPos, targetFace));
                    }
                    InteractionManager.clickUse(client);
                    globalCooldown = 4;
                    resetSequence();
                    break;

                default:
                    resetSequence();
                    break;
            }
        }

        private static boolean isHoldingRail(Minecraft client) {
            if (client.player == null) return false;
            return XbowCart.isAnyRail(client.player.getMainHandItem().getItem());
        }

        public static void resetSequence() {
            phase = Phase.INACTIVE;
            delayTicks = 0;
            targetBlockPos = null;
            targetFace = Direction.UP;
            watchdog.disarm();
        }
    }
            }
