package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
            IntManager.update(client);
            InventoryManager.stepCycle(client);
            RaycastManager.stepCycle(client);
            moduleManager.tick(client);
        });
    }

    public static ClientBase getInstance() { return INSTANCE; }
    public ModuleManager getModuleManager() { return moduleManager; }

    public abstract static class Module {
        protected final String name;
        protected boolean enabled;
        public Module(String name) { this.name = name; this.enabled = false; }
        public String getName() { return name; }
        public boolean isEnabled() { return enabled; }
        public void toggle() { enabled = !enabled; }
        public abstract void tick(Minecraft client);
    }

    public static class ModuleManager {
        private final List<Module> modules = new ArrayList<>();
        public void register(Module m) { modules.add(m); }
        public List<Module> getModules() { return modules; }
        public void tick(Minecraft client) {
            for (Module m : modules) { if (m.isEnabled()) m.tick(client); }
        }
        public Module getModuleByName(String name) {
            for (Module m : modules) {
                if (m.getName().equalsIgnoreCase(name)) return m;
            }
            return null;
        }
    }

    public static class ClientTelemetryEngine {
        private static final Map<String, Long> performanceMetrics = new ConcurrentHashMap<>();
        
        public static void recordMetric(String key, long duration) {
            performanceMetrics.put(key, duration);
        }
        
        public static long getMetric(String key) {
            return performanceMetrics.getOrDefault(key, 0L);
        }
        
        public static void clearMetrics() {
            performanceMetrics.clear();
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
        private net.minecraft.core.BlockPos targetBlockPos = null;
        private Direction targetFace = Direction.UP;
        private final SafetyWatchdog watchdog = new SafetyWatchdog();

        public XbowCartModule() { super("XbowCart"); this.enabled = true; }

        public static boolean isRail(Item item) {
            return item == Items.RAIL || item == Items.POWERED_RAIL ||
                   item == Items.DETECTOR_RAIL || item == Items.ACTIVATOR_RAIL;
        }

        @Override
        public void tick(Minecraft client) {
            if (client.player == null || client.level == null) return;
            if (globalCooldown > 0) { globalCooldown--; return; }
            if (watchdog.isTimedOut()) { reset(); return; }
            if (delayTicks > 0) { delayTicks--; return; }

            long startTime = System.currentTimeMillis();

            switch (phase) {
                case INACTIVE:
                    BlockHitResult hit = RaycastManager.getValidHit(client);
                    if (hit == null || !isRail(client.player.getMainHandItem().getItem()) || InventoryManager.findChargedCrossbow(client) == -1) return;
                    targetBlockPos = hit.getBlockPos();
                    targetFace = hit.getDirection();
                    watchdog.arm();
                    phase = Phase.RAIL_SELECT;
                    break;
                case RAIL_SELECT:
                    int r = findRail(client);
                    if (r != -1) { InventoryManager.selectSlot(client, r); delayTicks = 1; phase = Phase.RAIL_DEPLOY; } else reset();
                    break;
                case RAIL_DEPLOY:
                    if (targetBlockPos != null) {
                        RotationManager.smoothTo(client, Vec3.atCenterOf(targetBlockPos), 0.85F);
                        IntManager.simulateClickUse(client);
                    }
                    delayTicks = 1; phase = Phase.CART_SELECT;
                    break;
                case CART_SELECT:
                    int c = InventoryManager.findItem(client, Items.TNT_MINECART);
                    if (c != -1) { InventoryManager.selectSlot(client, c); delayTicks = 1; phase = Phase.CART_DEPLOY; } else reset();
                    break;
                case CART_DEPLOY:
                    if (targetBlockPos != null) {
                        net.minecraft.core.BlockPos pos = targetFace == Direction.UP ? targetBlockPos : targetBlockPos.relative(targetFace);
                        RotationManager.smoothTo(client, Vec3.atCenterOf(pos), 0.85F);
                        IntManager.simulateClickUse(client);
                    }
                    delayTicks = 1; phase = Phase.FIRE_SELECT;
                    break;
                case FIRE_SELECT:
                    int f = InventoryManager.findItem(client, Items.FLINT_AND_STEEL);
                    if (f == -1) f = InventoryManager.findItem(client, Items.FIRE_CHARGE);
                    if (f != -1) { InventoryManager.selectSlot(client, f); delayTicks = 1; phase = Phase.FIRE_DEPLOY; } else reset();
                    break;
                case FIRE_DEPLOY:
                    if (targetBlockPos != null) {
                        net.minecraft.core.BlockPos pos = targetFace == Direction.UP ? targetBlockPos.relative(client.player.getDirection().getOpposite()) : targetBlockPos;
                        RotationManager.smoothTo(client, Vec3.atCenterOf(pos), 0.85F);
                        IntManager.simulateClickUse(client);
                    }
                    delayTicks = 1; phase = Phase.CROSSBOW_SELECT;
                    break;
                case CROSSBOW_SELECT:
                    int x = InventoryManager.findChargedCrossbow(client);
                    if (x != -1) { InventoryManager.selectSlot(client, x); delayTicks = 1; phase = Phase.CROSSBOW_FIRE; } else reset();
                    break;
                case CROSSBOW_FIRE:
                    if (targetBlockPos != null) {
                        net.minecraft.core.BlockPos pos = targetFace == Direction.UP ? targetBlockPos : targetBlockPos.relative(targetFace);
                        RotationManager.smoothTo(client, Vec3.atCenterOf(pos).add(0.0D, 0.2D, 0.0D), 0.85F);
                    }
                    IntManager.simulateClickUse(client);
                    globalCooldown = 4;
                    reset();
                    break;
                default:
                    reset();
                    break;
            }

            ClientTelemetryEngine.recordMetric("XbowCart.TickTime", System.currentTimeMillis() - startTime);
        }

        private int findRail(Minecraft client) {
            for (int i = 0; i < 9; i++) {
                if (isRail(client.player.getInventory().getItem(i).getItem())) return i;
            }
            return -1;
        }

        public void reset() {
            phase = Phase.INACTIVE;
            delayTicks = 0;
            targetBlockPos = null;
            targetFace = Direction.UP;
            watchdog.disarm();
        }
    }
}