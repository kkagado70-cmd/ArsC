package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

import java.util.*;

/**
 * ClientBase & XbowCart - Fabric 1.21.11 (Mojang Mappings)
 * Full PvP Client Infrastructure, ClickSim Engine, Anti-Cheat Bypass Suite, and XbowCart Automation Module.
 */
public class ClientBase implements ClientModInitializer {
    private static ClientBase INSTANCE;
    private static KeyMapping toggleBaseKey;
    private ModuleManager moduleManager;

    @Override
    public void onInitializeClient() {
        INSTANCE = this;
        moduleManager = new ModuleManager();

        // Register modules
        moduleManager.register(new XbowCartModule());

        // Register global toggle UI/Keybind master
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

    // =========================================================================
    // MODULE SYSTEM ARCHITECTURE
    // =========================================================================

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
            if (enabled) {
                onEnable();
            } else {
                onDisable();
            }
        }

        public void onEnable() {}
        public void onDisable() {}
        public abstract void tick(Minecraft client);
    }

    public static class ModuleManager {
        private final List<Module> modules = new ArrayList<>();

        public void register(Module module) {
            modules.add(module);
        }

        public List<Module> getAll() {
            return modules;
        }

        public <T extends Module> T get(Class<T> clazz) {
            for (Module m : modules) {
                if (clazz.isInstance(m)) {
                    return clazz.cast(m);
                }
            }
            return null;
        }

        public void tick(Minecraft client) {
            for (Module m : modules) {
                if (m.isEnabled()) {
                    m.tick(client);
                }
            }
        }
    }

    // =========================================================================
    // UTILITY SUITE: ROTATION, INVENTORY, TARGETING
    // =========================================================================

    public static class RotationUtils {
        private static float lastYaw = 0.0f;
        private static float lastPitch = 0.0f;

        public static void aim(Minecraft client, Vec3 target, float speed) {
            if (client.player == null) return;

            double dx = target.x - client.player.getX();
            double dy = target.y - client.player.getEyeY();
            double dz = target.z - client.player.getZ();
            double hDist = Math.sqrt(dx * dx + dz * dz);

            float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
            float targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, hDist)));
            targetPitch = Mth.clamp(targetPitch, -30.0F, 30.0F); // Strict clamp for blocks/pvp to prevent flicking

            float currentYaw = client.player.getYRot();
            float currentPitch = client.player.getXRot();

            float yawDiff = Mth.wrapDegrees(targetYaw - currentYaw);
            float pitchDiff = targetPitch - currentPitch;

            float smoothedYaw = currentYaw + yawDiff * speed + (new Random().nextFloat() - 0.5F) * 0.08F;
            float smoothedPitch = currentPitch + pitchDiff * speed + (new Random().nextFloat() - 0.5F) * 0.08F;

            client.player.setYRot(smoothedYaw);
            client.player.setXRot(smoothedPitch);
            lastYaw = smoothedYaw;
            lastPitch = smoothedPitch;
        }

        public static void aimBlock(Minecraft client, BlockPos pos) {
            aim(client, Vec3.atCenterOf(pos), 0.70F);
        }

        public static void aimEntity(Minecraft client, Entity entity) {
            aim(client, entity.getEyePosition(), 0.70F);
        }

        public static void reset() {
            lastYaw = 0.0f;
            lastPitch = 0.0f;
        }
    }

    public static class InvUtils {
        public static int find(Minecraft client, Item targetItem) {
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
            return find(client, Items.CROSSBOW);
        }

        public static void select(Minecraft client, int slot) {
            if (client.player == null || slot < 0 || slot > 8) return;
            client.options.keyHotbarSlots[slot].setDown(true);
            client.options.keyHotbarSlots[slot].setDown(false);
        }

        public static boolean selectItem(Minecraft client, Item item) {
            int slot = find(client, item);
            if (slot != -1) {
                select(client, slot);
                return true;
            }
            return false;
        }

        public static boolean selectRail(Minecraft client) {
            int slot = findRail(client);
            if (slot != -1) {
                select(client, slot);
                return true;
            }
            return false;
        }

        public static boolean selectCrossbow(Minecraft client) {
            int slot = findChargedCrossbow(client);
            if (slot != -1) {
                select(client, slot);
                return true;
            }
            return false;
        }

        public static boolean isHolding(Minecraft client, Item item) {
            if (client.player == null) return false;
            return client.player.getMainHandItem().getItem() == item;
        }

        public static boolean isHoldingRail(Minecraft client) {
            if (client.player == null) return false;
            return XbowCartModule.isAnyRail(client.player.getMainHandItem().getItem());
        }

        public static int getSelected(Minecraft client) {
            if (client.player == null) return 0;
            return client.player.getInventory().getSelectedSlot();
        }

        public static void restore(Minecraft client, int slot) {
            if (slot >= 0 && slot < 9) {
                select(client, slot);
            }
        }
    }

    public static class TargetUtils {
        public static LivingEntity nearest(Minecraft client, double range) {
            if (client.player == null || client.level == null) return null;
            LivingEntity closest = null;
            double minDistSq = range * range;

            for (Entity entity : client.level.entitiesForRendering()) {
                if (entity instanceof LivingEntity living && living != client.player && living.isAlive()) {
                    double distSq = client.player.distanceToSqr(living);
                    if (distSq <= minDistSq) {
                        minDistSq = distSq;
                        closest = living;
                    }
                }
            }
            return closest;
        }

        public static Entity crosshair(Minecraft client, double range) {
            if (client.hitResult instanceof EntityHitResult entityHit) {
                if (client.player.distanceToSqr(entityHit.getLocation()) <= range * range) {
                    return entityHit.getEntity();
                }
            }
            return null;
        }
    }

    // =========================================================================
    // XBOWCART MODULE ADAPTATION
    // =========================================================================

    public static class XbowCartModule extends Module {
        private enum CartPhase {
            INACTIVE, RAIL_SELECT, RAIL_DEPLOY,
            FIRE_SELECT, FIRE_DEPLOY,
            CART_SELECT, CART_DEPLOY,
            CROSSBOW_SELECT, CROSSBOW_FIRE, COOLDOWN
        }

        private CartPhase phase = CartPhase.INACTIVE;
        private int delayTicks = 0;
        private int globalCooldownTicks = 0;
        private int originalSlot = -1;
        private int mouseButtonReleaseTracker = 0;
        private BlockPos basePos = null;

        public XbowCartModule() {
            super("XbowCart");
            this.enabled = true; // Permanent enabled state per instructions
        }

        public static boolean isAnyRail(Item item) {
            return item == Items.RAIL || item == Items.POWERED_RAIL || item == Items.DETECTOR_RAIL || item == Items.ACTIVATOR_RAIL;
        }

        @Override
        public void tick(Minecraft client) {
            if (client.player == null || client.level == null) return;

            // Handle mouse keyUse tracking safely for ClickSim
            if (mouseButtonReleaseTracker > 0) {
                mouseButtonReleaseTracker--;
                if (mouseButtonReleaseTracker == 0 && client.options != null) {
                    client.options.keyUse.setDown(false);
                }
            }

            if (globalCooldownTicks > 0) {
                globalCooldownTicks--;
                return;
            }

            if (delayTicks > 0) {
                delayTicks--;
                return;
            }

            // Continuous validation during sequence execution
            if (phase != CartPhase.INACTIVE && !isActivationValid(client)) {
                InvUtils.restore(client, originalSlot);
                resetSequence();
                return;
            }

            switch (phase) {
                case INACTIVE:
                    if (!isActivationValid(client)) return;
                    originalSlot = InvUtils.getSelected(client);
                    if (client.hitResult instanceof BlockHitResult hit) {
                        basePos = hit.getBlockPos();
                    } else {
                        basePos = client.player.blockPosition().below();
                    }
                    phase = CartPhase.RAIL_SELECT;
                    break;

                case RAIL_SELECT:
                    if (InvUtils.selectRail(client)) {
                        delayTicks = 2; // Slot switch sync delay
                        phase = CartPhase.RAIL_DEPLOY;
                    } else {
                        resetSequence();
                    }
                    break;

                case RAIL_DEPLOY:
                    if (basePos != null) {
                        RotationUtils.aimBlock(client, basePos);
                        mouseButtonReleaseTracker = 2;
                        client.options.keyUse.setDown(true);
                    }
                    delayTicks = 2 + new Random().nextInt(2); // 2-3 ticks randomized delay
                    phase = CartPhase.FIRE_SELECT;
                    break;

                case FIRE_SELECT:
                    if (InvUtils.selectItem(client, Items.FLINT_AND_STEEL) || InvUtils.selectItem(client, Items.FIRE_CHARGE)) {
                        delayTicks = 2;
                        phase = CartPhase.FIRE_DEPLOY;
                    } else {
                        resetSequence();
                    }
                    break;

                case FIRE_DEPLOY:
                    if (basePos != null) {
                        BlockPos firePos = basePos.above();
                        RotationUtils.aimBlock(client, firePos);
                        mouseButtonReleaseTracker = 2;
                        client.options.keyUse.setDown(true);
                    }
                    delayTicks = 2 + new Random().nextInt(2);
                    phase = CartPhase.CART_SELECT;
                    break;

                case CART_SELECT:
                    if (InvUtils.selectItem(client, Items.TNT_MINECART)) {
                        delayTicks = 2;
                        phase = CartPhase.CART_DEPLOY;
                    } else {
                        resetSequence();
                    }
                    break;

                case CART_DEPLOY:
                    if (basePos != null) {
                        BlockPos cartPos = basePos.above(2);
                        RotationUtils.aimBlock(client, cartPos);
                        mouseButtonReleaseTracker = 2;
                        client.options.keyUse.setDown(true);
                    }
                    delayTicks = 2 + new Random().nextInt(2);
                    phase = CartPhase.CROSSBOW_SELECT;
                    break;

                case CROSSBOW_SELECT:
                    if (client.player.getAttackStrengthScale(0.0F) < 0.9F) {
                        delayTicks = 1;
                        return;
                    }
                    if (InvUtils.selectCrossbow(client)) {
                        delayTicks = 2;
                        phase = CartPhase.CROSSBOW_FIRE;
                    } else {
                        resetSequence();
                    }
                    break;

                case CROSSBOW_FIRE:
                    mouseButtonReleaseTracker = 2;
                    client.options.keyUse.setDown(true);
                    InvUtils.restore(client, originalSlot);
                    globalCooldownTicks = 8;
                    resetSequence();
                    break;

                default:
                    resetSequence();
                    break;
            }
        }

        private boolean isActivationValid(Minecraft client) {
            if (client.player == null || client.hitResult == null) return false;
            boolean lookingDown = client.hitResult instanceof BlockHitResult blockHit && blockHit.getDirection() == Direction.UP;
            boolean holdingRail = InvUtils.isHoldingRail(client);
            return lookingDown && holdingRail;
        }

        private void resetSequence() {
            phase = CartPhase.INACTIVE;
            delayTicks = 0;
            originalSlot = -1;
            basePos = null;
        }
    }
                    }
