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
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AutoMace implements ClientModInitializer {
    private static final Minecraft mc = Minecraft.getInstance();
    private static KeyMapping toggleKey;
    public static boolean enabled = false;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.automace.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, KeyMapping.Category.MISC
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (mc.player == null || mc.level == null) return;
            while (toggleKey.consumeClick()) {
                enabled = !enabled;
                HT1CombatController.getInstance().hardReset();
            }
            if (enabled) {
                HT1CombatController.getInstance().onTick(client);
            }
        });
    }

    public static void toggle() {
        enabled = !enabled;
        HT1CombatController.getInstance().hardReset();
    }

    public static void onTick() {
        onTick(Minecraft.getInstance());
    }

    public static void onTick(Minecraft client) {
        if (client.player == null || client.level == null || !enabled) return;
        HT1CombatController.getInstance().onTick(client);
    }

    public static class HT1CombatController {
        private static final HT1CombatController INSTANCE = new HT1CombatController();
        private final HT1Config config = new HT1Config();
        private final EliteTargetAuditor auditor = new EliteTargetAuditor();
        private final HyperRotationEngine rotator = new HyperRotationEngine();
        private final InventoryOptimizer inventory = new InventoryOptimizer();
        private final MomentumStunEngine momentum = new MomentumStunEngine();
        private final AggressivePipeline pipeline = new AggressivePipeline();

        public static HT1CombatController getInstance() {
            return INSTANCE;
        }

        public void onTick(Minecraft client) {
            if (client.player == null || client.level == null) return;
            config.refresh();
            pipeline.processFrame(client, config, auditor, rotator, inventory, momentum);
        }

        public void hardReset() {
            pipeline.abortPipeline();
        }
    }

    public static class HT1Config {
        private final double maxSwingRange = 3.0D;
        private final double maxAimRange = 7.0D;
        private final double minFallDist = 0.5D;
        private final float hyperSnapSpeed = 0.95F;
        private final int zeroLatencyDelay = 0;

        public void refresh() {}

        public double getMaxSwingRange() { return maxSwingRange; }
        public double getMaxAimRange() { return maxAimRange; }
        public double getMinFallDist() { return minFallDist; }
        public float getHyperSnapSpeed() { return hyperSnapSpeed; }
        public int getZeroLatencyDelay() { return zeroLatencyDelay; }
    }

    public static class EliteTargetAuditor {
        private final ConcurrentHashMap<UUID, Vec3> posHistory = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<UUID, Vec3> velocityHistory = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<UUID, Long> timeHistory = new ConcurrentHashMap<>();

        public Player selectPrimaryTarget(Minecraft client, double radius) {
            if (client.level == null || client.player == null) return null;
            Player topTarget = null;
            double topScore = Double.MAX_VALUE;

            for (Player p : client.level.players()) {
                if (p == client.player || !p.isAlive() || p.isSpectator()) continue;
                double distSq = client.player.distanceToSqr(p);
                if (distSq > radius * radius) continue;

                calculateVelocityVector(p);
                double score = distSq + calculateAggressionFactor(p);
                if (score < topScore) {
                    topScore = score;
                    topTarget = p;
                }
            }
            return topTarget;
        }

        private void calculateVelocityVector(Player player) {
            long now = System.currentTimeMillis();
            Vec3 current = player.position();
            Vec3 prev = posHistory.getOrDefault(player.getUUID(), current);
            long oldTime = timeHistory.getOrDefault(player.getUUID(), now);

            long elapsed = Math.max(1L, now - oldTime);
            Vec3 diff = current.subtract(prev);
            Vec3 velocity = new Vec3(
                diff.x / (elapsed / 50.0D),
                diff.y / (elapsed / 50.0D),
                diff.z / (elapsed / 50.0D)
            );

            velocityHistory.put(player.getUUID(), velocity);
            posHistory.put(player.getUUID(), current);
            timeHistory.put(player.getUUID(), now);
        }

        private double calculateAggressionFactor(Player player) {
            double factor = 0.0D;
            if (player.getMainHandItem().getItem() instanceof MaceItem) factor -= 20.0D;
            if (player.isUsingItem()) factor -= 10.0D;
            return factor;
        }

        public Vec3 extrapolatePosition(Player player, double scale) {
            Vec3 vel = velocityHistory.getOrDefault(player.getUUID(), Vec3.ZERO);
            return player.position().add(vel.scale(scale));
        }
    }

    public static class HyperRotationEngine {
        private final Random jitter = new Random();

        public void snapToCoordinates(Vec3 target, float speed, boolean isDive) {
            if (mc.player == null) return;

            double dx = target.x - mc.player.getX();
            double dy = target.y - mc.player.getEyeY();
            double dz = target.z - mc.player.getZ();
            double distPlane = Math.sqrt(dx * dx + dz * dz);

            float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
            float targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, distPlane)));

            float yawErr = Mth.wrapDegrees(targetYaw - mc.player.getYRot());
            float pitchErr = Mth.wrapDegrees(targetPitch - mc.player.getXRot());

            float velocityFactor = isDive ? 0.98F : speed;
            float stepYaw = yawErr * (velocityFactor + (jitter.nextFloat() * 0.02F));
            float stepPitch = pitchErr * (velocityFactor + (jitter.nextFloat() * 0.02F));

            float rawYaw = mc.player.getYRot() + stepYaw;
            float rawPitch = mc.player.getXRot() + stepPitch;

            double sens = mc.options.sensitivity().get();
            double m = sens * 0.6D + 0.2D;
            double gcd = m * m * m * 8.0D * 0.15D;

            float finalYaw = (float) (mc.player.getYRot() + Math.round((rawYaw - mc.player.getYRot()) / gcd) * gcd);
            float finalPitch = (float) (mc.player.getXRot() + Math.round((rawPitch - mc.player.getXRot()) / gcd) * gcd);

            mc.player.setYRot(finalYaw);
            mc.player.setXRot(Mth.clamp(finalPitch, -90.0F, 90.0F));
        }
    }

    public static class InventoryOptimizer {
        private int cachedAxe = -1;
        private int cachedMace = -1;

        public void scanHotbar(Player player, double fallHeight) {
            cachedAxe = -1;
            cachedMace = -1;
            int bestDensity = -1;
            int bestBreach = -1;

            for (int i = 0; i < 9; i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (stack.isEmpty()) continue;

                if (stack.getItem() instanceof AxeItem && cachedAxe == -1) {
                    if (stack.getDamageValue() < stack.getMaxDamage() - 3) {
                        cachedAxe = i;
                    }
                } else if (stack.getItem() instanceof MaceItem) {
                    int density = getEncLvl(stack, "density");
                    int breach = getEncLvl(stack, "breach");

                    if (fallHeight >= 5.0D) {
                        if (density > bestDensity) {
                            bestDensity = density;
                            cachedMace = i;
                        }
                    } else {
                        if (breach > bestBreach) {
                            bestBreach = breach;
                            cachedMace = i;
                        }
                    }
                    if (cachedMace == -1) cachedMace = i;
                }
            }
        }

        private int getEncLvl(ItemStack stack, String key) {
            if (stack.isEmpty()) return 0;
            ItemEnchantments map = stack.get(DataComponents.ENCHANTMENTS);
            if (map == null) return 0;
            for (var entry : map.entrySet()) {
                if (entry.getKey().toString().contains(key)) return entry.getIntValue();
            }
            return 0;
        }

        public int getAxeSlot() { return cachedAxe; }
        public int getMaceSlot() { return cachedMace; }

        public void swapSlot(int slot) {
            if (mc.player == null) return;
            mc.player.getInventory().setSelectedSlot(slot);
            if (slot >= 0 && slot < 9) {
                mc.options.keyHotbarSlots[slot].setDown(true);
                mc.options.keyHotbarSlots[slot].setDown(false);
            }
        }
    }

    public static class MomentumStunEngine {
        private double peakY = 0.0D;
        private int airTicks = 0;

        public void updatePhysics(Player player) {
            if (player == null) return;
            if (player.onGround()) {
                peakY = player.getY();
                airTicks = 0;
            } else {
                peakY = Math.max(peakY, player.getY());
                airTicks++;
            }
        }

        public double getFall(Player player) {
            if (player == null) return 0.0D;
            return Math.max(0.0D, peakY - player.getY());
        }

        public boolean isDiving(Player player) {
            return player != null && player.getDeltaMovement().y < -0.3D;
        }

        public boolean isStunned(Player target) {
            return target != null && (target.hurtTime > 0 || airTicks > 2);
        }
    }

    public static class AggressivePipeline {
        private enum State { INACTIVE, AXE_PREP, AXE_HIT, MACE_PREP, MACE_HIT, COMPLETE }
        private State currentStage = State.INACTIVE;
        private int ticksLeft = 0;
        private int startingSlotIndex = -1;
        private long watchdogTimer = 0L;
        private int attackReleaseTimer = 0;

        public void processFrame(Minecraft client, HT1Config cfg, EliteTargetAuditor auditor, HyperRotationEngine rotator, InventoryOptimizer inv, MomentumStunEngine momentum) {
            if (attackReleaseTimer > 0) {
                attackReleaseTimer--;
                if (attackReleaseTimer == 0 && mc.options != null) {
                    mc.options.keyAttack.setDown(false);
                }
            }

            if (ticksLeft > 0) {
                ticksLeft--;
                return;
            }

            if (System.currentTimeMillis() > watchdogTimer && currentStage != State.INACTIVE) {
                abortPipeline();
                return;
            }

            momentum.updatePhysics(client.player);
            double fall = momentum.getFall(client.player);
            boolean diving = momentum.isDiving(client.player);

            Player target = auditor.selectPrimaryTarget(client, cfg.getMaxAimRange());
            if (target == null) {
                if (currentStage != State.INACTIVE) abortPipeline();
                return;
            }

            inv.scanHotbar(client.player, fall);
            boolean shield = target.isUsingItem() && target.getUseItem().getItem() instanceof ShieldItem;

            switch (currentStage) {
                case INACTIVE:
                    startingSlotIndex = client.player.getInventory().getSelectedSlot();
                    currentStage = shield ? State.AXE_PREP : State.MACE_PREP;
                    watchdogTimer = System.currentTimeMillis() + 1000L;
                    break;

                case AXE_PREP:
                    int axe = inv.getAxeSlot();
                    if (axe != -1) {
                        inv.swapSlot(axe);
                        ticksLeft = cfg.getZeroLatencyDelay();
                        currentStage = State.AXE_HIT;
                    } else {
                        currentStage = State.MACE_PREP;
                    }
                    break;

                case AXE_HIT:
                    if (client.player.distanceTo(target) <= cfg.getMaxSwingRange() && client.player.getAttackStrengthScale(0.0F) >= 0.9F) {
                        rotator.snapToCoordinates(auditor.extrapolatePosition(target, 0.2D), cfg.getHyperSnapSpeed(), diving);
                        mc.options.keyAttack.setDown(true);
                        attackReleaseTimer = 2;
                        ticksLeft = cfg.getZeroLatencyDelay();
                        currentStage = State.MACE_PREP;
                    }
                    break;

                case MACE_PREP:
                    int mace = inv.getMaceSlot();
                    if (mace != -1) {
                        inv.swapSlot(mace);
                        ticksLeft = cfg.getZeroLatencyDelay();
                        currentStage = State.MACE_HIT;
                    } else {
                        currentStage = State.COMPLETE;
                    }
                    break;

                case MACE_HIT:
                    boolean ready = fall >= cfg.getMinFallDist() || diving || momentum.isStunned(target);
                    if (client.player.distanceTo(target) <= cfg.getMaxSwingRange() && ready && client.player.getAttackStrengthScale(0.0F) >= 0.9F) {
                        rotator.snapToCoordinates(auditor.extrapolatePosition(target, 0.2D), cfg.getHyperSnapSpeed(), diving);
                        mc.options.keyAttack.setDown(true);
                        attackReleaseTimer = 2;
                        ticksLeft = cfg.getZeroLatencyDelay();
                        currentStage = State.COMPLETE;
                    }
                    break;

                case COMPLETE:
                    if (startingSlotIndex >= 0 && startingSlotIndex < 9) {
                        inv.swapSlot(startingSlotIndex);
                    }
                    abortPipeline();
                    break;
            }
        }

        public void abortPipeline() {
            currentStage = State.INACTIVE;
            ticksLeft = 0;
            if (startingSlotIndex >= 0 && startingSlotIndex < 9 && mc.player != null) {
                mc.player.getInventory().setSelectedSlot(startingSlotIndex);
                mc.options.keyHotbarSlots[startingSlotIndex].setDown(true);
                mc.options.keyHotbarSlots[startingSlotIndex].setDown(false);
            }
            startingSlotIndex = -1;
            watchdogTimer = 0L;
        }
    }
                                       }
