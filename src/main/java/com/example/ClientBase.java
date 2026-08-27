package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
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
    private ModuleManager moduleManager;

    @Override
    public void onInitializeClient() {
        INSTANCE = this;
        moduleManager = new ModuleManager();

        moduleManager.register(new XbowCartModule());

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
            this.enabled = false;
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

        public List<Module> getModules() {
            return modules;
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
        public static void smoothTo(Minecraft client, Vec3 target, float factor) {
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

            client.player.setYRot(currentYaw + yawDiff * factor);
            client.player.setXRot(currentPitch + pitchDiff * factor);
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

    public static class TargetUtils {
        public static LivingEntity nearest(Minecraft client, double range) {
            if (client.player == null || client.level == null) return null;
            LivingEntity closest = null;
            double minDistSq = range * range;

            for (Entity entity : client.level.entitiesForRendering()) {
                if (entity instanceof LivingEntity living && living != client.player && living.isAlive() && !living.isSpectator()) {
                    double distSq = client.player.distanceToSqr(living);
                    if (distSq <= minDistSq) {
                        minDistSq = distSq;
                        closest = living;
                    }
                }
            }
            return closest;
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

    public static class XbowCartModule extends Module {
        private enum Phase {
            INACTIVE, 
            RAIL_SELECT, RAIL_DEPLOY,
            CART_SELECT, CART_DEPLOY,
            FIRE_SELECT, FIRE_DEPLOY,
            CROSSBOW_SELECT, CROSSBOW_FIRE
        }

        private Phase phase = Phase.INACTIVE;
        private int delayTicks = 0;
        private int globalCooldown = 0;
        private BlockPos targetBlockPos = null;
        private Direction targetFace = Direction.UP;
        private final SafetyWatchdog watchdog = new SafetyWatchdog();

        public XbowCartModule() {
            super("XbowCart");
            this.enabled = true;
        }

        public static boolean isAnyRail(Item item) {
            return item == Items.RAIL || 
                   item == Items.POWERED_RAIL || 
                   item == Items.DETECTOR_RAIL || 
                   item == Items.ACTIVATOR_RAIL;
        }

        @Override
        public void tick(Minecraft client) {
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
                    int railSlot = findRail(client);
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
                        RotationManager.smoothTo(client, Vec3.atCenterOf(targetBlockPos), 0.85F);
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
                        BlockPos cartTarget = targetFace == Direction.UP ? targetBlockPos : targetBlockPos.relative(targetFace);
                        RotationManager.smoothTo(client, Vec3.atCenterOf(cartTarget), 0.85F);
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
                        BlockPos fireTarget = targetFace == Direction.UP ? targetBlockPos.relative(client.player.getDirection().getOpposite()) : targetBlockPos;
                        RotationManager.smoothTo(client, Vec3.atCenterOf(fireTarget), 0.85F);
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
                        BlockPos shootTarget = targetFace == Direction.UP ? targetBlockPos : targetBlockPos.relative(targetFace);
                        RotationManager.smoothTo(client, Vec3.atCenterOf(shootTarget).add(0.0D, 0.2D, 0.0D), 0.85F);
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

        private boolean isHoldingRail(Minecraft client) {
            if (client.player == null) return false;
            return isAnyRail(client.player.getMainHandItem().getItem());
        }

        private int findRail(Minecraft client) {
            for (int i = 0; i < 9; i++) {
                Item item = client.player.getInventory().getItem(i).getItem();
                if (isAnyRail(item)) {
                    return i;
                }
            }
            return -1;
        }

        public void resetSequence() {
            phase = Phase.INACTIVE;
            delayTicks = 0;
            targetBlockPos = null;
            targetFace = Direction.UP;
            watchdog.disarm();
        }
    }
                            }
