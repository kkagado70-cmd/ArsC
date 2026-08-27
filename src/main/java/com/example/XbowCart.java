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

    public static class RotationManager {
        public static void snapTo(Minecraft client, Vec3 target) {
            if (client.player == null) return;
            double dx = target.x - client.player.getX();
            double dy = target.y - client.player.getEyeY();
            double dz = target.z - client.player.getZ();
            double hDist = Math.sqrt(dx * dx + dz * dz);

            float targetYaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI) - 90.0D);
            float targetPitch = (float) (-(Mth.atan2(dy, hDist) * (180.0 / Math.PI)));
            targetPitch = Mth.clamp(targetPitch, -85.0F, 85.0F);

            client.player.setYRot(targetYaw);
            client.player.setXRot(targetPitch);
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
            return -1;
        }
    }

    public static class SafetyWatchdog {
        private long watchdogStart = 0L;
        private final long timeoutMs = 1500L;

        public void arm() {
            watchdogStart = System.currentTimeMillis();
        }

        public boolean isTimedOut() {
            return watchdogStart > 0 && (System.currentTimeMillis() - watchdogStart > timeoutMs);
        }

        public void disarm() {
            watchdogStart = 0L;
        }
    }

    public static class XbowCartModule extends Module {
        private enum CartPhase {
            INACTIVE, 
            RAIL_SELECT, RAIL_DEPLOY,
            CART_SELECT, CART_DEPLOY,
            FIRE_SELECT, FIRE_DEPLOY,
            CROSSBOW_SELECT, CROSSBOW_FIRE
        }

        private CartPhase phase = CartPhase.INACTIVE;
        private int delayTicks = 0;
        private int globalCooldownTicks = 0;
        private int targetSlot = -1;
        private Vec3 targetVec = null;
        private Vec3 fireVec = null;
        private final SafetyWatchdog watchdog = new SafetyWatchdog();

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

            if (watchdog.isTimedOut()) {
                resetSequence();
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
                    if (client.hitResult instanceof BlockHitResult hit) {
                        targetVec = hit.getLocation();
                        BlockPos basePos = hit.getBlockPos();
                        Direction facing = client.player.getDirection();
                        BlockPos fireBlockPos = basePos.relative(facing.getOpposite());
                        fireVec = Vec3.atCenterOf(fireBlockPos);
                    } else {
                        return;
                    }
                    watchdog.arm();
                    phase = CartPhase.RAIL_SELECT;
                    break;

                case RAIL_SELECT:
                    targetSlot = InventoryManager.findRail(client);
                    if (targetSlot != -1) {
                        InventoryManager.selectSlot(client, targetSlot);
                        delayTicks = 1;
                        phase = CartPhase.RAIL_DEPLOY;
                    } else {
                        resetSequence();
                    }
                    break;

                case RAIL_DEPLOY:
                    if (targetVec != null) {
                        RotationManager.snapTo(client, targetVec);
                        client.options.keyUse.setDown(false);
                        client.options.keyUse.setDown(true);
                    }
                    delayTicks = 1;
                    phase = CartPhase.CART_SELECT;
                    break;

                case CART_SELECT:
                    targetSlot = InventoryManager.findItem(client, Items.TNT_MINECART);
                    if (targetSlot != -1) {
                        InventoryManager.selectSlot(client, targetSlot);
                        delayTicks = 1;
                        phase = CartPhase.CART_DEPLOY;
                    } else {
                        resetSequence();
                    }
                    break;

                case CART_DEPLOY:
                    if (targetVec != null) {
                        RotationManager.snapTo(client, targetVec);
                        client.options.keyUse.setDown(false);
                        client.options.keyUse.setDown(true);
                    }
                    delayTicks = 1;
                    phase = CartPhase.FIRE_SELECT;
                    break;

                case FIRE_SELECT:
                    targetSlot = InventoryManager.findItem(client, Items.FLINT_AND_STEEL);
                    if (targetSlot == -1) {
                        targetSlot = InventoryManager.findItem(client, Items.FIRE_CHARGE);
                    }
                    if (targetSlot != -1) {
                        InventoryManager.selectSlot(client, targetSlot);
                        delayTicks = 1;
                        phase = CartPhase.FIRE_DEPLOY;
                    } else {
                        resetSequence();
                    }
                    break;

                case FIRE_DEPLOY:
                    if (fireVec != null) {
                        RotationManager.snapTo(client, fireVec);
                        client.options.keyUse.setDown(false);
                        client.options.keyUse.setDown(true);
                    }
                    delayTicks = 1;
                    phase = CartPhase.CROSSBOW_SELECT;
                    break;

                case CROSSBOW_SELECT:
                    targetSlot = InventoryManager.findChargedCrossbow(client);
                    if (targetSlot != -1) {
                        InventoryManager.selectSlot(client, targetSlot);
                        delayTicks = 1;
                        phase = CartPhase.CROSSBOW_FIRE;
                    } else {
                        resetSequence();
                    }
                    break;

                case CROSSBOW_FIRE:
                    if (targetVec != null) {
                        RotationManager.snapTo(client, targetVec);
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
            if (client.hitResult.getType() != HitResult.Type.BLOCK) return false;
            if (!(client.hitResult instanceof BlockHitResult blockHit)) return false;
            if (blockHit.getDirection() != Direction.UP) return false;
            if (!isHoldingRail(client)) return false;
            return InventoryManager.findChargedCrossbow(client) != -1;
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
            watchdog.disarm();
        }
    }
        }
