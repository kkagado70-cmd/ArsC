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

import java.util.ArrayList;
import java.util.List;

public class ClientBase implements ClientModInitializer {
    private static ClientBase INSTANCE;
    private static KeyMapping toggleBaseKey;
    private ModuleManager moduleManager;

    @Override
    public void onInitializeClient() {
        INSTANCE = this;
        moduleManager = new ModuleManager();
        moduleManager.register(new XbowCartModule());

        toggleBaseKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.clientbase.toggle",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_X,
                KeyMapping.Category.MISC
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.level == null) return;
            moduleManager.tick(client);
        });
    }

    public static ClientBase getInstance() {
        return INSTANCE;
    }

    public ModuleManager getModuleManager() {
        return moduleManager;
    }

    public abstract static class Module {
        protected final String name;
        protected boolean enabled;

        public Module(String name) {
            this.name = name;
            this.enabled = true;
        }

        public String getName() {
            return name;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void toggle() {
            enabled = !enabled;
        }

        public abstract void tick(Minecraft client);
    }

    public static class ModuleManager {
        private final List<Module> modules = new ArrayList<>();

        public void register(Module module) {
            modules.add(module);
        }

        public void tick(Minecraft client) {
            for (Module m : modules) {
                if (m.isEnabled()) {
                    m.tick(client);
                }
            }
        }
    }

    public static class RotationUtils {
        public static void aimAt(Minecraft client, Vec3 target) {
            if (client.player == null) return;
            double dx = target.x - client.player.getX();
            double dy = target.y - client.player.getEyeY();
            double dz = target.z - client.player.getZ();
            double hDist = Math.sqrt(dx * dx + dz * dz);

            float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
            float targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, hDist)));
            targetPitch = Mth.clamp(targetPitch, -85.0F, 85.0F);

            client.player.setYRot(targetYaw);
            client.player.setXRot(targetPitch);
        }
    }

    public static class InvUtils {
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
                if (XbowCartModule.isAnyRail(item)) {
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
            return findItem(client, Items.CROSSBOW);
        }

        public static void selectSlot(Minecraft client, int slot) {
            if (client.player == null || slot < 0 || slot > 8) return;
            client.player.getInventory().setSelectedSlot(slot);
        }
    }

    public static class RaycastUtils {
        public static BlockHitResult raycastBlock(Minecraft client, double range) {
            if (client.hitResult instanceof BlockHitResult blockHit) {
                if (client.player != null && client.player.distanceToSqr(blockHit.getLocation()) <= range * range) {
                    return blockHit;
                }
            }
            return null;
        }
    }

    public static class XbowCartModule extends Module {
        private enum CartPhase {
            INACTIVE, 
            RAIL_SELECT, RAIL_WAIT, RAIL_DEPLOY,
            CART_SELECT, CART_WAIT, CART_DEPLOY,
            FIRE_SELECT, FIRE_WAIT, FIRE_DEPLOY,
            CROSSBOW_SELECT, CROSSBOW_WAIT, CROSSBOW_FIRE
        }

        private CartPhase phase = CartPhase.INACTIVE;
        private int delayTicks = 0;
        private int globalCooldownTicks = 0;
        private int targetSlot = -1;
        private Vec3 targetVec = null;
        private Vec3 fireVec = null;

        public XbowCartModule() {
            super("XbowCart");
        }

        public static boolean isAnyRail(Item item) {
            return item == Items.RAIL || 
                   item == Items.POWERED_RAIL || 
                   item == Items.DETECTOR_RAIL || 
                   item == Items.ACTIVATOR_RAIL;
        }

        @Override
        public void tick(Minecraft client) {
            if (client.player == null || client.level == null) return;

            if (globalCooldownTicks > 0) {
                globalCooldownTicks--;
                return;
            }

            if (delayTicks > 0) {
                delayTicks--;
                return;
            }

            if (phase != CartPhase.INACTIVE && !isSequenceIntegrityValid(client)) {
                resetSequence();
                return;
            }

            switch (phase) {
                case INACTIVE:
                    if (!isInitialActivationValid(client)) return;
                    BlockHitResult hit = RaycastUtils.raycastBlock(client, 6.0D);
                    if (hit != null) {
                        targetVec = hit.getLocation();
                        BlockPos basePos = hit.getBlockPos();
                        Direction facing = client.player.getDirection();
                        BlockPos fireBlockPos = basePos.relative(facing.getOpposite());
                        fireVec = Vec3.atCenterOf(fireBlockPos);
                    } else {
                        return;
                    }
                    phase = CartPhase.RAIL_SELECT;
                    break;

                case RAIL_SELECT:
                    targetSlot = InvUtils.findRail(client);
                    if (targetSlot != -1) {
                        InvUtils.selectSlot(client, targetSlot);
                        delayTicks = 1;
                        phase = CartPhase.RAIL_WAIT;
                    } else {
                        resetSequence();
                    }
                    break;

                case RAIL_WAIT:
                    delayTicks = 1;
                    phase = CartPhase.RAIL_DEPLOY;
                    break;

                case RAIL_DEPLOY:
                    if (targetVec != null) {
                        RotationUtils.aimAt(client, targetVec);
                        client.options.keyUse.setDown(false);
                        client.options.keyUse.setDown(true);
                    }
                    delayTicks = 1;
                    phase = CartPhase.CART_SELECT;
                    break;

                case CART_SELECT:
                    targetSlot = InvUtils.findItem(client, Items.TNT_MINECART);
                    if (targetSlot != -1) {
                        InvUtils.selectSlot(client, targetSlot);
                        delayTicks = 1;
                        phase = CartPhase.CART_WAIT;
                    } else {
                        resetSequence();
                    }
                    break;

                case CART_WAIT:
                    delayTicks = 1;
                    phase = CartPhase.CART_DEPLOY;
                    break;

                case CART_DEPLOY:
                    if (targetVec != null) {
                        RotationUtils.aimAt(client, targetVec);
                        client.options.keyUse.setDown(false);
                        client.options.keyUse.setDown(true);
                    }
                    delayTicks = 1;
                    phase = CartPhase.FIRE_SELECT;
                    break;

                case FIRE_SELECT:
                    targetSlot = InvUtils.findItem(client, Items.FLINT_AND_STEEL);
                    if (targetSlot == -1) {
                        targetSlot = InvUtils.findItem(client, Items.FIRE_CHARGE);
                    }
                    if (targetSlot != -1) {
                        InvUtils.selectSlot(client, targetSlot);
                        delayTicks = 1;
                        phase = CartPhase.FIRE_WAIT;
                    } else {
                        resetSequence();
                    }
                    break;

                case FIRE_WAIT:
                    delayTicks = 1;
                    phase = CartPhase.FIRE_DEPLOY;
                    break;

                case FIRE_DEPLOY:
                    if (fireVec != null) {
                        RotationUtils.aimAt(client, fireVec);
                        client.options.keyUse.setDown(false);
                        client.options.keyUse.setDown(true);
                    }
                    delayTicks = 1;
                    phase = CartPhase.CROSSBOW_SELECT;
                    break;

                case CROSSBOW_SELECT:
                    targetSlot = InvUtils.findChargedCrossbow(client);
                    if (targetSlot != -1) {
                        InvUtils.selectSlot(client, targetSlot);
                        delayTicks = 1;
                        phase = CartPhase.CROSSBOW_WAIT;
                    } else {
                        resetSequence();
                    }
                    break;

                case CROSSBOW_WAIT:
                    delayTicks = 1;
                    phase = CartPhase.CROSSBOW_FIRE;
                    break;

                case CROSSBOW_FIRE:
                    if (targetVec != null) {
                        RotationUtils.aimAt(client, targetVec);
                    }
                    client.options.keyUse.setDown(false);
                    client.options.keyUse.setDown(true);
                    globalCooldownTicks = 4;
                    resetSequence();
                    break;

                default:
                    resetSequence();
                    break;
            }
        }

        private boolean isInitialActivationValid(Minecraft client) {
            if (client.player == null || client.hitResult == null) return false;
            boolean lookingAtBlock = client.hitResult instanceof BlockHitResult;
            boolean holdingRail = isHoldingRail(client);
            return lookingAtBlock && holdingRail;
        }

        private boolean isSequenceIntegrityValid(Minecraft client) {
            return enabled && client.player != null && client.level != null;
        }

        private boolean isHoldingRail(Minecraft client) {
            if (client.player == null) return false;
            return isAnyRail(client.player.getMainHandItem().getItem());
        }

        private void resetSequence() {
            phase = CartPhase.INACTIVE;
            delayTicks = 0;
            targetSlot = -1;
            targetVec = null;
            fireVec = null;
        }
    }
                        }
