package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
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
        this.moduleManager = new ModuleManager();
        this.moduleManager.register(new XbowCartModule());
        this.moduleManager.register(new AimAssist());
        this.moduleManager.register(new TriggerBot());
        this.moduleManager.register(new AutoMaceModule());

        ClientTickEvents.START_CLIENT_TICK.register(ClientBase::invokeGlobalTick);
    }

    public static ClientBase getInstance() { return INSTANCE; }
    public ModuleManager getModuleManager() { return moduleManager; }

    public static void invokeGlobalTick(Minecraft client) {
        if (INSTANCE != null && INSTANCE.moduleManager != null) {
            INSTANCE.moduleManager.tick(client);
        }
    }

    public abstract static class Module {
        protected final String name;
        public boolean enabled;

        public Module(String name) {
            this.name = name;
            this.enabled = true;
        }

        public String getName() { return name; }
        public boolean isEnabled() { return enabled; }
        public void toggle() { enabled = !enabled; }
        public abstract void tick(Minecraft client);

        public void executeTickWrapper(Minecraft client) {
            if (enabled) {
                tick(client);
            }
        }
    }

    public static class ModuleManager {
        private final List<Module> modules = new ArrayList<>();

        public void register(Module module) {
            if (module != null && !modules.contains(module)) {
                modules.add(module);
            }
        }

        public List<Module> getModules() { return modules; }

        public void tick(Minecraft client) {
            for (Module m : modules) {
                if (m != null) {
                    m.executeTickWrapper(client);
                }
            }
        }
    }

    public static class XbowCartModule extends Module {
        private enum Phase { VOID, RAIL_ACTION, CART_ACTION, FLINT_ACTION, XBOW_ACTION, CLEANUP }
        private Phase phase = Phase.VOID;
        private int tickTimer = 0;
        private net.minecraft.core.BlockPos targetPos = null;
        private Direction targetFace = Direction.UP;
        private final SafetyWatchdog watchdog = new SafetyWatchdog();

        public XbowCartModule() { super("XbowCart"); this.enabled = true; }

        @Override
        public void tick(Minecraft client) {
            if (client.player == null || client.level == null) return;
            if (watchdog.isTimedOut()) { reset(); return; }
            if (tickTimer > 0) { tickTimer--; return; }

            switch (phase) {
                case VOID:
                    BlockHitResult hit = RaycastManager.getValidHit(client);
                    if (hit == null || !XbowCart.validateRegistryItem(client.player.getMainHandItem().getItem()) || InventoryManager.findChargedCrossbow(client) == -1) return;
                    targetPos = hit.getBlockPos();
                    targetFace = hit.getDirection();
                    watchdog.arm();
                    phase = Phase.RAIL_ACTION;
                    break;
                case RAIL_ACTION:
                    int r = locateRail(client);
                    if (r == -1) { reset(); return; }
                    RotationManager.smoothTo(client, Vec3.atCenterOf(targetPos), 0.99F);
                    InventoryManager.selectSlot(client, r);
                    InteractionManager.simulateClickUse(client);
                    tickTimer = 2;
                    phase = Phase.CART_ACTION;
                    break;
                case CART_ACTION:
                    int c = InventoryManager.findItem(client, net.minecraft.world.item.Items.TNT_MINECART);
                    if (c == -1) { reset(); return; }
                    BlockPos cartBlock = targetFace == Direction.UP ? targetPos : targetPos.relative(targetFace);
                    RotationManager.smoothTo(client, Vec3.atCenterOf(cartBlock), 0.99F);
                    InventoryManager.selectSlot(client, c);
                    InteractionManager.simulateClickUse(client);
                    tickTimer = 2;
                    phase = Phase.FLINT_ACTION;
                    break;
                case FLINT_ACTION:
                    int f = InventoryManager.findItem(client, net.minecraft.world.item.Items.FLINT_AND_STEEL);
                    if (f == -1) f = InventoryManager.findItem(client, net.minecraft.world.item.Items.FIRE_CHARGE);
                    if (f == -1) { reset(); return; }
                    BlockPos fireBlock = targetFace == Direction.UP ? targetPos.relative(client.player.getDirection().getOpposite()) : targetPos;
                    RotationManager.smoothTo(client, Vec3.atCenterOf(fireBlock), 0.99F);
                    InventoryManager.selectSlot(client, f);
                    InteractionManager.simulateClickUse(client);
                    tickTimer = 2;
                    phase = Phase.XBOW_ACTION;
                    break;
                case XBOW_ACTION:
                    int x = InventoryManager.findChargedCrossbow(client);
                    if (x == -1) { reset(); return; }
                    BlockPos shootBlock = targetFace == Direction.UP ? targetPos : targetPos.relative(targetFace);
                    RotationManager.smoothTo(client, Vec3.atCenterOf(shootBlock).add(0.0D, 0.2D, 0.0D), 0.99F);
                    InventoryManager.selectSlot(client, x);
                    InteractionManager.simulateClickUse(client);
                    tickTimer = 2;
                    phase = Phase.CLEANUP;
                    break;
                case CLEANUP:
                    reset();
                    break;
            }
        }

        private int locateRail(Minecraft client) {
            for (int i = 0; i < 9; i++) {
                if (XbowCart.validateRegistryItem(client.player.getInventory().getItem(i).getItem())) return i;
            }
            return -1;
        }

        public void reset() {
            phase = Phase.INACTIVE;
            tickTimer = 0;
            targetPos = null;
            targetFace = Direction.UP;
            watchdog.disarm();
        }
    }

    public static class AutoMaceModule extends Module {
        public AutoMaceModule() { super("AutoMace"); this.enabled = true; }
        @Override
        public void tick(Minecraft client) {
            AutoMace.HT1CombatController.onTick(client);
        }
    }
}