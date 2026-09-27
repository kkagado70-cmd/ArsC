package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.security.SecureRandom;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class XbowCart extends ClientBase.Module {

    public static final String FILE_NAME = "XbowCart.java";
    public static final String MOD_VER   = "3.0.0-1.21.11";

    public enum Phase {
        IDLE,
        VALIDATE,
        SELECT_RAIL, CHECK_RAIL,   AIM_RAIL,   PLACE_RAIL,  VERIFY_RAIL,
        SELECT_CART, CHECK_CART,   AIM_CART,   PLACE_CART,  VERIFY_CART,
        SELECT_FIRE, CHECK_FIRE,   AIM_FIRE,   IGNITE,      VERIFY_FIRE,
        SELECT_XBOW, CHECK_XBOW,   AIM_SMOOTH, VERIFY_AIM,
        SHOOT, VERIFY_SHOT, COOLDOWN
    }

    public enum CartMode { STANDARD, TOWER, MULTI_ANGLE }

    public static final class SequenceSnapshot {
        public final BlockPos hitTarget;
        public final Direction hitFace;
        public final BlockPos railPos;
        public final BlockPos firePos;
        public final Vec3 railAimTarget;
        public final Vec3 cartAimTarget;
        public final Vec3 fireAimTarget;
        public final Vec3 xbowAimTarget;
        public final int railSlot;
        public final int cartSlot;
        public final int fireSlot;
        public final int xbowSlot;
        public final CartMode mode;
        public final long capturedAtEpoch;
        public final UUID snapshotId;

        public SequenceSnapshot(BlockPos hitTarget, Direction hitFace, BlockPos railPos, BlockPos firePos,
                                Vec3 railAim, Vec3 cartAim, Vec3 fireAim, Vec3 xbowAim,
                                int railSlot, int cartSlot, int fireSlot, int xbowSlot, CartMode mode) {
            this.hitTarget      = hitTarget;
            this.hitFace        = hitFace;
            this.railPos        = railPos;
            this.firePos        = firePos;
            this.railAimTarget  = railAim;
            this.cartAimTarget  = cartAim;
            this.fireAimTarget  = fireAim;
            this.xbowAimTarget  = xbowAim;
            this.railSlot       = railSlot;
            this.cartSlot       = cartSlot;
            this.fireSlot       = fireSlot;
            this.xbowSlot       = xbowSlot;
            this.mode           = mode;
            this.capturedAtEpoch = System.currentTimeMillis();
            this.snapshotId     = UUID.randomUUID();
        }
    }

    private static final SecureRandom secureRandom = new SecureRandom();
    private static final Map<String, Object> XBOW_REGISTRY = new ConcurrentHashMap<>();

    private static final double RAIL_AIM_Y_OFFSET    = 0.55;
    private static final double CART_AIM_Y_OFFSET    = 0.60;
    private static final double FIRE_AIM_Y_OFFSET    = 0.50;
    private static final double XBOW_AIM_Y_OFFSET    = 0.85;
    private static final double ENTITY_SCAN_RADIUS   = 2.5;
    private static final double RAIL_REACH           = 4.5;
    private static final float  AIM_YAW_TOLERANCE    = 2.0f;
    private static final float  AIM_PITCH_TOLERANCE  = 2.5f;
    private static final float  SHOOT_YAW_TOLERANCE  = 3.5f;
    private static final float  SHOOT_PITCH_TOLERANCE = 3.5f;
    private static final int    COOLDOWN_BASE_TICKS  = 25;
    private static final int    TICK_WAIT_AFTER_AIM   = 2;
    private static final int    TICK_WAIT_AFTER_PLACE  = 3;
    private static final int    TICK_WAIT_AFTER_IGNITE = 2;
    private static final int    MIN_FLINT_DURABILITY  = 2;

    private Phase phase            = Phase.IDLE;
    private SequenceSnapshot snapshot = null;
    private int   tickTimer        = 0;
    private int   retryCount       = 0;
    private int   cooldownTicks    = 0;
    private long  lastSeqStartEpoch = 0L;
    private long  totalAttempted   = 0L;
    private long  totalCompleted   = 0L;
    private long  totalFailed      = 0L;
    private String statusDetail    = "idle";
    private MinecartTNT trackedCart = null;
    private boolean cartWasTracked = false;
    private float smoothFactor     = 0.35f;
    private int   maxAimTicks      = 12;
    private int   aimTicksElapsed  = 0;
    private boolean autoRestart    = false;
    private boolean verifyWorldState = true;
    private CartMode defaultCartMode = CartMode.MULTI_ANGLE;

    public XbowCart() {
        super("XbowCart");
        XBOW_REGISTRY.put("ModuleVersion", MOD_VER);
        XBOW_REGISTRY.put("Profile", "CartPvP-MultiAngle-Enterprise");
        XBOW_REGISTRY.put("AimYawTolerance", AIM_YAW_TOLERANCE);
        XBOW_REGISTRY.put("AimPitchTolerance", AIM_PITCH_TOLERANCE);
    }

    @Override
    public void toggle() {
        super.toggle();
        if (!enabled) {
            XBOW_REGISTRY.put("Enabled", false);
            XBOW_REGISTRY.put("TotalAttempted", totalAttempted);
            XBOW_REGISTRY.put("TotalCompleted", totalCompleted);
            XBOW_REGISTRY.put("TotalFailed", totalFailed);
        } else {
            phase = Phase.IDLE;
            snapshot = null;
            tickTimer = 0;
            retryCount = 0;
            cooldownTicks = 0;
            trackedCart = null;
            cartWasTracked = false;
            aimTicksElapsed = 0;
            statusDetail = "idle";
            SafetyWatchdog.reset();
            RotationManager.reset();
            InventoryManager.unlock();
            InteractionManager.unlock();
            XBOW_REGISTRY.put("Enabled", true);
        }
    }

    @Override
    public void tick(Minecraft client) {
        if (client.player == null || client.level == null) return;
        if (client.screen != null && !(client.screen instanceof ClickGUI)) return;

        RotationManager.samplePlayerGcd(client);
        SafetyWatchdog.update(client);

        if (SafetyWatchdog.isTripped()) {
            if (phase != Phase.IDLE) { totalFailed++; hardReset(client); }
            return;
        }
        if (SafetyWatchdog.isCoolingDown() && phase == Phase.IDLE) return;

        switch (phase) {
            case IDLE          -> tickIdle(client);
            case VALIDATE      -> tickValidate(client);
            case SELECT_RAIL   -> tickSelectRail(client);
            case CHECK_RAIL    -> tickCheckRail(client);
            case AIM_RAIL      -> tickAimRail(client);
            case PLACE_RAIL    -> tickPlaceRail(client);
            case VERIFY_RAIL   -> tickVerifyRail(client);
            case SELECT_CART   -> tickSelectCart(client);
            case CHECK_CART    -> tickCheckCart(client);
            case AIM_CART      -> tickAimCart(client);
            case PLACE_CART    -> tickPlaceCart(client);
            case VERIFY_CART   -> tickVerifyCart(client);
            case SELECT_FIRE   -> tickSelectFire(client);
            case CHECK_FIRE    -> tickCheckFire(client);
            case AIM_FIRE      -> tickAimFire(client);
            case IGNITE        -> tickIgnite(client);
            case VERIFY_FIRE   -> tickVerifyFire(client);
            case SELECT_XBOW   -> tickSelectXbow(client);
            case CHECK_XBOW    -> tickCheckXbow(client);
            case AIM_SMOOTH    -> tickAimSmooth(client);
            case VERIFY_AIM    -> tickVerifyAim(client);
            case SHOOT         -> tickShoot(client);
            case VERIFY_SHOT   -> tickVerifyShot(client);
            case COOLDOWN      -> tickCooldown(client);
        }

        XBOW_REGISTRY.put("Phase", phase.name());
        XBOW_REGISTRY.put("Status", statusDetail);
        statusLabel = "[" + phase.name() + "] " + statusDetail;
    }

    private void tickIdle(Minecraft client) {
        if (!client.options.keyAttack.isDown()) return;
        if (!InventoryManager.validateSequenceInventory(client)) { statusDetail = "missing items"; return; }
        beginSequence(client);
    }

    private void beginSequence(Minecraft client) {
        net.minecraft.world.phys.BlockHitResult bhr = RaycastManager.getValidHit(client);
        if (bhr == null) { statusDetail = "no target"; return; }

        BlockPos hitTarget = bhr.getBlockPos();
        Direction hitFace  = bhr.getDirection();
        if (hitFace == Direction.DOWN) { statusDetail = "invalid face"; return; }

        BlockPos railPos = computeRailPos(client, hitTarget, hitFace);
        if (railPos == null) { statusDetail = "no rail pos"; return; }
        if (!RaycastManager.isWithinReach(client, railPos, RAIL_REACH + 1.0)) { statusDetail = "out of reach"; return; }

        BlockPos firePos = RaycastManager.computeFirePosition(client, railPos, hitFace);
        if (firePos == null) { statusDetail = "no fire pos"; return; }

        int railSlot = InventoryManager.findRail(client);
        int cartSlot = InventoryManager.findItem(client, Items.TNT_MINECART);
        int fireSlot = InventoryManager.findFireSource(client);
        int xbowSlot = InventoryManager.findChargedCrossbow(client);
        if (railSlot < 0 || cartSlot < 0 || fireSlot < 0 || xbowSlot < 0) { statusDetail = "slot missing"; return; }
        if (!InventoryManager.hasMinDurability(client, fireSlot, MIN_FLINT_DURABILITY)) { statusDetail = "fire low dur"; return; }

        Vec3 railAim = Vec3.atCenterOf(railPos).add(0, RAIL_AIM_Y_OFFSET, 0);
        Vec3 cartAim = Vec3.atCenterOf(railPos).add(0, CART_AIM_Y_OFFSET, 0);
        Vec3 fireAim = Vec3.atCenterOf(firePos).add(0, FIRE_AIM_Y_OFFSET, 0);
        Vec3 xbowAim = Vec3.atCenterOf(railPos).add(0, XBOW_AIM_Y_OFFSET, 0);

        snapshot = new SequenceSnapshot(hitTarget, hitFace, railPos, firePos, railAim, cartAim, fireAim, xbowAim,
                                        railSlot, cartSlot, fireSlot, xbowSlot, resolveCartMode(hitFace));

        InventoryManager.saveCurrentSlot(client);
        SafetyWatchdog.startGlobal();
        totalAttempted++;
        lastSeqStartEpoch = System.currentTimeMillis();
        aimTicksElapsed   = 0;
        retryCount        = 0;
        trackedCart       = null;
        cartWasTracked    = false;
        transition(Phase.VALIDATE);
    }

    private void tickValidate(Minecraft client) {
        SafetyWatchdog.enterPhase("VALIDATE");
        if (SafetyWatchdog.checkPhaseTimeout("VALIDATE")) return;
        if (snapshot == null) { abortSequence(client, "null snapshot"); return; }
        if (!InventoryManager.validateSequenceInventory(client)) { abortSequence(client, "inventory changed"); return; }
        SafetyWatchdog.onPhaseSuccess("VALIDATE");
        transition(Phase.SELECT_RAIL);
    }

    private void tickSelectRail(Minecraft client) {
        SafetyWatchdog.enterPhase("SELECT_RAIL");
        if (SafetyWatchdog.checkPhaseTimeout("SELECT_RAIL")) return;
        if (InventoryManager.selectSlot(client, snapshot.railSlot)) {
            tickTimer = 1;
            SafetyWatchdog.onPhaseSuccess("SELECT_RAIL");
            transition(Phase.CHECK_RAIL);
        }
    }

    private void tickCheckRail(Minecraft client) {
        SafetyWatchdog.enterPhase("CHECK_RAIL");
        if (SafetyWatchdog.checkPhaseTimeout("CHECK_RAIL")) return;
        if (tickTimer > 0) { tickTimer--; return; }
        if (!InventoryManager.verifySlotSelected(client, snapshot.railSlot)) {
            if (!SafetyWatchdog.onRetry("CHECK_RAIL")) return;
            InventoryManager.selectSlotImmediate(client, snapshot.railSlot);
            tickTimer = 1; return;
        }
        SafetyWatchdog.onPhaseSuccess("CHECK_RAIL");
        aimTicksElapsed = 0;
        transition(Phase.AIM_RAIL);
    }

    private void tickAimRail(Minecraft client) {
        SafetyWatchdog.enterPhase("AIM_RAIL");
        if (SafetyWatchdog.checkPhaseTimeout("AIM_RAIL")) return;
        aimTicksElapsed++;
        Vec3 aim = adjustedAimForFace(snapshot.railAimTarget, snapshot.hitFace);
        RotationManager.setEasingMode(RotationManager.EasingMode.SWIGHT_HIGH_SENS);
        RotationManager.smoothTo(client, aim, computeDynamicSmooth(aimTicksElapsed));
        if (RotationManager.isAligned(client, aim, AIM_YAW_TOLERANCE, AIM_PITCH_TOLERANCE) || aimTicksElapsed >= maxAimTicks) {
            if (!RotationManager.isAligned(client, aim, AIM_YAW_TOLERANCE, AIM_PITCH_TOLERANCE)) RotationManager.snapTo(client, aim);
            tickTimer = TICK_WAIT_AFTER_AIM;
            SafetyWatchdog.onPhaseSuccess("AIM_RAIL");
            transition(Phase.PLACE_RAIL);
        }
    }

    private void tickPlaceRail(Minecraft client) {
        SafetyWatchdog.enterPhase("PLACE_RAIL");
        if (SafetyWatchdog.checkPhaseTimeout("PLACE_RAIL")) return;
        if (tickTimer > 0) { tickTimer--; return; }
        if (!RaycastManager.isLookingAtBlock(client, snapshot.railPos.below())) {
            RotationManager.snapTo(client, adjustedAimForFace(snapshot.railAimTarget, snapshot.hitFace));
            tickTimer = 1;
            if (!SafetyWatchdog.onRetry("PLACE_RAIL")) return;
            return;
        }
        InteractionManager.simulateClickUse(client, InteractionManager.InteractionPriority.HIGH);
        tickTimer = TICK_WAIT_AFTER_PLACE;
        SafetyWatchdog.onPhaseSuccess("PLACE_RAIL");
        transition(Phase.VERIFY_RAIL);
    }

    private void tickVerifyRail(Minecraft client) {
        SafetyWatchdog.enterPhase("VERIFY_RAIL");
        if (SafetyWatchdog.checkPhaseTimeout("VERIFY_RAIL")) return;
        if (tickTimer > 0) { tickTimer--; return; }
        if (!verifyWorldState) { SafetyWatchdog.onPhaseSuccess("VERIFY_RAIL"); transition(Phase.SELECT_CART); return; }
        BlockState state = client.level.getBlockState(snapshot.railPos);
        boolean hasRail  = state.is(Blocks.RAIL) || state.is(Blocks.POWERED_RAIL) || state.is(Blocks.ACTIVATOR_RAIL) || state.is(Blocks.DETECTOR_RAIL);
        if (!hasRail) {
            if (!SafetyWatchdog.onRetry("VERIFY_RAIL")) return;
            InteractionManager.simulateClickUse(client, InteractionManager.InteractionPriority.HIGH);
            tickTimer = TICK_WAIT_AFTER_PLACE; return;
        }
        SafetyWatchdog.onPhaseSuccess("VERIFY_RAIL");
        transition(Phase.SELECT_CART);
    }

    private void tickSelectCart(Minecraft client) {
        SafetyWatchdog.enterPhase("SELECT_CART");
        if (SafetyWatchdog.checkPhaseTimeout("SELECT_CART")) return;
        if (InventoryManager.selectSlot(client, snapshot.cartSlot)) {
            tickTimer = 1;
            SafetyWatchdog.onPhaseSuccess("SELECT_CART");
            transition(Phase.CHECK_CART);
        }
    }

    private void tickCheckCart(Minecraft client) {
        SafetyWatchdog.enterPhase("CHECK_CART");
        if (SafetyWatchdog.checkPhaseTimeout("CHECK_CART")) return;
        if (tickTimer > 0) { tickTimer--; return; }
        if (!InventoryManager.verifySlotSelected(client, snapshot.cartSlot)) {
            if (!SafetyWatchdog.onRetry("CHECK_CART")) return;
            InventoryManager.selectSlotImmediate(client, snapshot.cartSlot);
            tickTimer = 1; return;
        }
        SafetyWatchdog.onPhaseSuccess("CHECK_CART");
        aimTicksElapsed = 0;
        transition(Phase.AIM_CART);
    }

    private void tickAimCart(Minecraft client) {
        SafetyWatchdog.enterPhase("AIM_CART");
        if (SafetyWatchdog.checkPhaseTimeout("AIM_CART")) return;
        aimTicksElapsed++;
        RotationManager.setEasingMode(RotationManager.EasingMode.SWIGHT_HIGH_SENS);
        RotationManager.smoothTo(client, snapshot.cartAimTarget, computeDynamicSmooth(aimTicksElapsed));
        if (RotationManager.isAligned(client, snapshot.cartAimTarget, AIM_YAW_TOLERANCE, AIM_PITCH_TOLERANCE) || aimTicksElapsed >= maxAimTicks) {
            if (!RotationManager.isAligned(client, snapshot.cartAimTarget, AIM_YAW_TOLERANCE, AIM_PITCH_TOLERANCE)) RotationManager.snapTo(client, snapshot.cartAimTarget);
            tickTimer = TICK_WAIT_AFTER_AIM;
            SafetyWatchdog.onPhaseSuccess("AIM_CART");
            transition(Phase.PLACE_CART);
        }
    }

    private void tickPlaceCart(Minecraft client) {
        SafetyWatchdog.enterPhase("PLACE_CART");
        if (SafetyWatchdog.checkPhaseTimeout("PLACE_CART")) return;
        if (tickTimer > 0) { tickTimer--; return; }
        BlockState railState = client.level.getBlockState(snapshot.railPos);
        if (!railState.is(Blocks.RAIL) && !railState.is(Blocks.POWERED_RAIL) && !railState.is(Blocks.ACTIVATOR_RAIL) && !railState.is(Blocks.DETECTOR_RAIL)) {
            abortSequence(client, "rail gone before cart place"); return;
        }
        if (!RaycastManager.isLookingAtBlock(client, snapshot.railPos)) {
            RotationManager.snapTo(client, snapshot.cartAimTarget);
            tickTimer = 1;
            if (!SafetyWatchdog.onRetry("PLACE_CART")) return;
            return;
        }
        InteractionManager.simulateClickUse(client, InteractionManager.InteractionPriority.HIGH);
        tickTimer = TICK_WAIT_AFTER_PLACE;
        SafetyWatchdog.onPhaseSuccess("PLACE_CART");
        transition(Phase.VERIFY_CART);
    }

    private void tickVerifyCart(Minecraft client) {
        SafetyWatchdog.enterPhase("VERIFY_CART");
        if (SafetyWatchdog.checkPhaseTimeout("VERIFY_CART")) return;
        if (tickTimer > 0) { tickTimer--; return; }
        if (!verifyWorldState) { SafetyWatchdog.onPhaseSuccess("VERIFY_CART"); transition(Phase.SELECT_FIRE); return; }
        Optional<MinecartTNT> cartOpt = RaycastManager.findNearestEntity(client, MinecartTNT.class, snapshot.railPos, ENTITY_SCAN_RADIUS);
        if (cartOpt.isEmpty()) {
            if (!SafetyWatchdog.onRetry("VERIFY_CART")) return;
            InteractionManager.simulateClickUse(client, InteractionManager.InteractionPriority.HIGH);
            tickTimer = TICK_WAIT_AFTER_PLACE; return;
        }
        trackedCart = cartOpt.get();
        cartWasTracked = true;
        Vec3 xbowTarget = liveXbowTarget(trackedCart);
        snapshot = new SequenceSnapshot(snapshot.hitTarget, snapshot.hitFace, snapshot.railPos, snapshot.firePos,
            snapshot.railAimTarget, snapshot.cartAimTarget, snapshot.fireAimTarget, xbowTarget,
            snapshot.railSlot, snapshot.cartSlot, snapshot.fireSlot, snapshot.xbowSlot, snapshot.mode);
        SafetyWatchdog.onPhaseSuccess("VERIFY_CART");
        transition(Phase.SELECT_FIRE);
    }

    private void tickSelectFire(Minecraft client) {
        SafetyWatchdog.enterPhase("SELECT_FIRE");
        if (SafetyWatchdog.checkPhaseTimeout("SELECT_FIRE")) return;
        int slot = InventoryManager.findFireSource(client);
        if (slot < 0) { abortSequence(client, "fire source gone"); return; }
        if (InventoryManager.selectSlot(client, slot)) {
            tickTimer = 1;
            SafetyWatchdog.onPhaseSuccess("SELECT_FIRE");
            transition(Phase.CHECK_FIRE);
        }
    }

    private void tickCheckFire(Minecraft client) {
        SafetyWatchdog.enterPhase("CHECK_FIRE");
        if (SafetyWatchdog.checkPhaseTimeout("CHECK_FIRE")) return;
        if (tickTimer > 0) { tickTimer--; return; }
        int slot = InventoryManager.findFireSource(client);
        if (slot < 0) { abortSequence(client, "fire gone at CHECK_FIRE"); return; }
        if (!InventoryManager.verifySlotSelected(client, slot)) {
            if (!SafetyWatchdog.onRetry("CHECK_FIRE")) return;
            InventoryManager.selectSlotImmediate(client, slot);
            tickTimer = 1; return;
        }
        SafetyWatchdog.onPhaseSuccess("CHECK_FIRE");
        aimTicksElapsed = 0;
        transition(Phase.AIM_FIRE);
    }

    private void tickAimFire(Minecraft client) {
        SafetyWatchdog.enterPhase("AIM_FIRE");
        if (SafetyWatchdog.checkPhaseTimeout("AIM_FIRE")) return;
        if (cartWasTracked && trackedCart != null && !trackedCart.isAlive()) { abortSequence(client, "cart died before fire"); return; }
        aimTicksElapsed++;
        RotationManager.setEasingMode(RotationManager.EasingMode.SWIGHT_HIGH_SENS);
        RotationManager.smoothTo(client, snapshot.fireAimTarget, computeDynamicSmooth(aimTicksElapsed));
        if (RotationManager.isAligned(client, snapshot.fireAimTarget, AIM_YAW_TOLERANCE, AIM_PITCH_TOLERANCE) || aimTicksElapsed >= maxAimTicks) {
            if (!RotationManager.isAligned(client, snapshot.fireAimTarget, AIM_YAW_TOLERANCE, AIM_PITCH_TOLERANCE)) RotationManager.snapTo(client, snapshot.fireAimTarget);
            tickTimer = TICK_WAIT_AFTER_AIM;
            SafetyWatchdog.onPhaseSuccess("AIM_FIRE");
            transition(Phase.IGNITE);
        }
    }

    private void tickIgnite(Minecraft client) {
        SafetyWatchdog.enterPhase("IGNITE");
        if (SafetyWatchdog.checkPhaseTimeout("IGNITE")) return;
        if (tickTimer > 0) { tickTimer--; return; }
        if (cartWasTracked && trackedCart != null && !trackedCart.isAlive()) { abortSequence(client, "cart dead before ignite"); return; }
        BlockPos fp = snapshot.firePos;
        if (!RaycastManager.isValidFirePosition(client, fp)) {
            BlockPos alt = RaycastManager.computeFirePosition(client, snapshot.railPos, snapshot.hitFace);
            if (alt == null) { if (!SafetyWatchdog.onRetry("IGNITE fire pos")) return; return; }
            Vec3 altAim = Vec3.atCenterOf(alt).add(0, FIRE_AIM_Y_OFFSET, 0);
            snapshot = new SequenceSnapshot(snapshot.hitTarget, snapshot.hitFace, snapshot.railPos, alt,
                snapshot.railAimTarget, snapshot.cartAimTarget, altAim, snapshot.xbowAimTarget,
                snapshot.railSlot, snapshot.cartSlot, snapshot.fireSlot, snapshot.xbowSlot, snapshot.mode);
            RotationManager.snapTo(client, altAim); tickTimer = 1; return;
        }
        if (!client.level.getBlockState(fp).isAir()) { if (!SafetyWatchdog.onRetry("IGNITE occupied")) return; return; }
        InteractionManager.simulateClickUse(client, InteractionManager.InteractionPriority.HIGH);
        tickTimer = TICK_WAIT_AFTER_IGNITE;
        SafetyWatchdog.onPhaseSuccess("IGNITE");
        transition(Phase.VERIFY_FIRE);
    }

    private void tickVerifyFire(Minecraft client) {
        SafetyWatchdog.enterPhase("VERIFY_FIRE");
        if (SafetyWatchdog.checkPhaseTimeout("VERIFY_FIRE")) return;
        if (tickTimer > 0) { tickTimer--; return; }
        if (!verifyWorldState) { SafetyWatchdog.onPhaseSuccess("VERIFY_FIRE"); transition(Phase.SELECT_XBOW); return; }
        BlockState fs = client.level.getBlockState(snapshot.firePos);
        boolean hasFire = fs.getBlock() instanceof FireBlock || fs.is(Blocks.FIRE) || fs.is(Blocks.SOUL_FIRE);
        if (!hasFire) {
            if (!SafetyWatchdog.onRetry("VERIFY_FIRE")) return;
            int slot = InventoryManager.findFireSource(client);
            if (slot >= 0) {
                InventoryManager.selectSlotImmediate(client, slot);
                RotationManager.snapTo(client, snapshot.fireAimTarget);
                InteractionManager.simulateClickUse(client, InteractionManager.InteractionPriority.HIGH);
                tickTimer = TICK_WAIT_AFTER_IGNITE;
            }
            return;
        }
        if (cartWasTracked && trackedCart != null && !trackedCart.isAlive()) { abortSequence(client, "cart detonated before xbow"); return; }
        SafetyWatchdog.onPhaseSuccess("VERIFY_FIRE");
        transition(Phase.SELECT_XBOW);
    }

    private void tickSelectXbow(Minecraft client) {
        SafetyWatchdog.enterPhase("SELECT_XBOW");
        if (SafetyWatchdog.checkPhaseTimeout("SELECT_XBOW")) return;
        if (cartWasTracked && trackedCart != null && !trackedCart.isAlive()) { abortSequence(client, "cart dead at SELECT_XBOW"); return; }
        int slot = InventoryManager.findChargedCrossbow(client);
        if (slot < 0) { abortSequence(client, "no charged xbow"); return; }
        if (InventoryManager.selectSlot(client, slot)) {
            tickTimer = 1;
            SafetyWatchdog.onPhaseSuccess("SELECT_XBOW");
            transition(Phase.CHECK_XBOW);
        }
    }

    private void tickCheckXbow(Minecraft client) {
        SafetyWatchdog.enterPhase("CHECK_XBOW");
        if (SafetyWatchdog.checkPhaseTimeout("CHECK_XBOW")) return;
        if (tickTimer > 0) { tickTimer--; return; }
        int slot = InventoryManager.findChargedCrossbow(client);
        if (slot < 0) { abortSequence(client, "xbow gone at CHECK"); return; }
        if (!InventoryManager.verifySlotSelected(client, slot)) {
            if (!SafetyWatchdog.onRetry("CHECK_XBOW")) return;
            InventoryManager.selectSlotImmediate(client, slot);
            tickTimer = 1; return;
        }
        ItemStack held = client.player.getMainHandItem();
        if (held.isEmpty() || !(held.getItem() instanceof CrossbowItem) || !CrossbowItem.isCharged(held)) {
            if (!SafetyWatchdog.onRetry("CHECK_XBOW charged")) return; return;
        }
        SafetyWatchdog.onPhaseSuccess("CHECK_XBOW");
        aimTicksElapsed = 0;
        transition(Phase.AIM_SMOOTH);
    }

    private void tickAimSmooth(Minecraft client) {
        SafetyWatchdog.enterPhase("AIM_SMOOTH");
        if (SafetyWatchdog.checkPhaseTimeout("AIM_SMOOTH")) return;
        if (cartWasTracked && trackedCart != null && !trackedCart.isAlive()) { abortSequence(client, "cart dead during aim"); return; }
        Vec3 aim = resolveXbowTarget(client);
        if (aim == null) { abortSequence(client, "no xbow aim target"); return; }
        aimTicksElapsed++;
        RotationManager.setEasingMode(RotationManager.EasingMode.KINEMATIC_SPRING);
        RotationManager.smoothTo(client, aim, computeDynamicSmooth(aimTicksElapsed));
        boolean aligned = RotationManager.isAligned(client, aim, SHOOT_YAW_TOLERANCE, SHOOT_PITCH_TOLERANCE) && RotationManager.isStable();
        if (aligned || aimTicksElapsed >= maxAimTicks + 5) {
            if (!RotationManager.isAligned(client, aim, SHOOT_YAW_TOLERANCE, SHOOT_PITCH_TOLERANCE)) RotationManager.snapTo(client, aim);
            tickTimer = 1;
            SafetyWatchdog.onPhaseSuccess("AIM_SMOOTH");
            transition(Phase.VERIFY_AIM);
        }
    }

    private void tickVerifyAim(Minecraft client) {
        SafetyWatchdog.enterPhase("VERIFY_AIM");
        if (SafetyWatchdog.checkPhaseTimeout("VERIFY_AIM")) return;
        if (tickTimer > 0) { tickTimer--; return; }
        if (cartWasTracked && trackedCart != null && !trackedCart.isAlive()) { abortSequence(client, "cart dead at VERIFY_AIM"); return; }
        Vec3 aim = resolveXbowTarget(client);
        if (aim == null) { abortSequence(client, "lost xbow target"); return; }
        if (!RotationManager.isAligned(client, aim, SHOOT_YAW_TOLERANCE, SHOOT_PITCH_TOLERANCE)) {
            if (!SafetyWatchdog.onRetry("VERIFY_AIM")) return;
            RotationManager.snapTo(client, aim);
            tickTimer = 1;
            transition(Phase.AIM_SMOOTH); return;
        }
        ItemStack held = client.player.getMainHandItem();
        if (held.isEmpty() || !(held.getItem() instanceof CrossbowItem) || !CrossbowItem.isCharged(held)) {
            abortSequence(client, "xbow uncharged at VERIFY_AIM"); return;
        }
        SafetyWatchdog.onPhaseSuccess("VERIFY_AIM");
        transition(Phase.SHOOT);
    }

    private void tickShoot(Minecraft client) {
        SafetyWatchdog.enterPhase("SHOOT");
        if (SafetyWatchdog.checkPhaseTimeout("SHOOT")) return;
        if (cartWasTracked && trackedCart != null && !trackedCart.isAlive()) { abortSequence(client, "cart detonated before shoot"); return; }
        ItemStack held = client.player.getMainHandItem();
        if (held.isEmpty() || !(held.getItem() instanceof CrossbowItem) || !CrossbowItem.isCharged(held)) {
            abortSequence(client, "xbow not charged at SHOOT"); return;
        }
        InteractionManager.simulateClickUse(client, InteractionManager.InteractionPriority.IMMEDIATE);
        tickTimer = 2;
        SafetyWatchdog.onPhaseSuccess("SHOOT");
        transition(Phase.VERIFY_SHOT);
    }

    private void tickVerifyShot(Minecraft client) {
        SafetyWatchdog.enterPhase("VERIFY_SHOT");
        if (SafetyWatchdog.checkPhaseTimeout("VERIFY_SHOT")) return;
        if (tickTimer > 0) { tickTimer--; return; }
        ItemStack held = client.player.getMainHandItem();
        boolean fired = held.isEmpty() || !(held.getItem() instanceof CrossbowItem) || !CrossbowItem.isCharged(held);
        if (!fired) {
            if (!SafetyWatchdog.onRetry("VERIFY_SHOT")) return;
            InteractionManager.simulateClickUse(client, InteractionManager.InteractionPriority.IMMEDIATE);
            tickTimer = 2; return;
        }
        SafetyWatchdog.onPhaseSuccess("VERIFY_SHOT");
        totalCompleted++;
        long elapsed = System.currentTimeMillis() - lastSeqStartEpoch;
        XBOW_REGISTRY.put("LastSeqDurationMs", elapsed);
        XBOW_REGISTRY.put("TotalCompleted", totalCompleted);
        statusDetail = "shot fired (" + elapsed + "ms)";
        cooldownTicks = computeDynamicCooldown();
        transition(Phase.COOLDOWN);
    }

    private void tickCooldown(Minecraft client) {
        SafetyWatchdog.enterPhase("COOLDOWN");
        if (cooldownTicks > 0) { cooldownTicks--; statusDetail = "cooldown " + cooldownTicks; return; }
        InventoryManager.restoreSavedSlot(client);
        RotationManager.reset();
        InventoryManager.invalidateCache();
        SafetyWatchdog.onPhaseSuccess("COOLDOWN");
        SafetyWatchdog.reset();
        snapshot       = null;
        trackedCart    = null;
        cartWasTracked = false;
        aimTicksElapsed = 0;
        retryCount     = 0;
        statusDetail   = "idle";
        transition(Phase.IDLE);
    }

    public static void purgePipelineRegistry() {}

    private void abortSequence(Minecraft client, String reason) {
        totalFailed++;
        statusDetail = "ABORT: " + reason;
        XBOW_REGISTRY.put("AbortReason", reason);
        XBOW_REGISTRY.put("TotalFailed", totalFailed);
        SafetyWatchdog.trip(SafetyWatchdog.TripReason.EXTERNAL_FORCE_TRIP, SafetyWatchdog.SeverityLevel.WARN, reason);
        hardReset(client);
    }

    private void hardReset(Minecraft client) {
        InventoryManager.restoreSavedSlot(client);
        InteractionManager.flushAndRelease(client);
        RotationManager.reset();
        InventoryManager.invalidateCache();
        SafetyWatchdog.reset();
        snapshot       = null;
        trackedCart    = null;
        cartWasTracked = false;
        phase          = Phase.IDLE;
        tickTimer      = 0;
        retryCount     = 0;
        cooldownTicks  = 0;
        aimTicksElapsed = 0;
    }

    private Vec3 resolveXbowTarget(Minecraft client) {
        if (cartWasTracked && trackedCart != null && trackedCart.isAlive()) return liveXbowTarget(trackedCart);
        if (snapshot != null) {
            Optional<MinecartTNT> fresh = RaycastManager.findNearestEntity(client, MinecartTNT.class, snapshot.railPos, ENTITY_SCAN_RADIUS);
            if (fresh.isPresent()) { trackedCart = fresh.get(); return liveXbowTarget(trackedCart); }
        }
        return snapshot != null ? snapshot.xbowAimTarget : null;
    }

    private Vec3 liveXbowTarget(MinecartTNT cart) {
        Vec3 pos = cart.position();
        Vec3 vel = cart.getDeltaMovement();
        return pos.add(vel.x * 1.9, XBOW_AIM_Y_OFFSET, vel.z * 1.9);
    }

    private BlockPos computeRailPos(Minecraft client, BlockPos hit, Direction face) {
        BlockPos up = hit.above();
        if (client.level.getBlockState(up).isAir()) return up;
        if (defaultCartMode == CartMode.MULTI_ANGLE || defaultCartMode == CartMode.TOWER) {
            for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                BlockPos adj = hit.relative(d);
                if (client.level.getBlockState(adj).isAir() && client.level.getBlockState(adj.below()).isSolidRender()) return adj;
            }
        }
        return null;
    }

    private Vec3 adjustedAimForFace(Vec3 base, Direction face) {
        return switch (face) {
            case NORTH -> base.add(0, 0, -0.1);
            case SOUTH -> base.add(0, 0, 0.1);
            case EAST  -> base.add(0.1, 0, 0);
            case WEST  -> base.add(-0.1, 0, 0);
            default    -> base;
        };
    }

    private CartMode resolveCartMode(Direction face) {
        return switch (face) {
            case UP -> CartMode.STANDARD;
            case NORTH, SOUTH, EAST, WEST -> CartMode.TOWER;
            default -> defaultCartMode;
        };
    }

    private float computeDynamicSmooth(int ticks) {
        float t = Math.min(1.0f, (float) ticks / Math.max(1, maxAimTicks));
        return smoothFactor + (1.0f - smoothFactor) * t * t;
    }

    private int computeDynamicCooldown() {
        return COOLDOWN_BASE_TICKS + secureRandom.nextInt(6);
    }

    private void transition(Phase next) {
        phase     = next;
        tickTimer = 0;
        XBOW_REGISTRY.put("Phase", next.name());
        XBOW_REGISTRY.put("PhaseTransitionEpoch", System.currentTimeMillis());
    }

    public void setSmoothFactor(float f)     { smoothFactor    = Math.max(0.05f, Math.min(1.0f, f)); }
    public void setMaxAimTicks(int t)        { maxAimTicks     = Math.max(1, t); }
    public void setAutoRestart(boolean b)    { autoRestart     = b; }
    public void setVerifyWorldState(boolean b) { verifyWorldState = b; }
    public void setDefaultCartMode(CartMode m) { defaultCartMode  = m; }
    public Phase getPhase()                  { return phase; }
    public SequenceSnapshot getSnapshot()    { return snapshot; }
    public long getTotalAttempted()          { return totalAttempted; }
    public long getTotalCompleted()          { return totalCompleted; }
    public long getTotalFailed()             { return totalFailed; }
    public double getSuccessRate()           { return totalAttempted == 0 ? 0.0 : (double) totalCompleted / totalAttempted * 100.0; }
    public MinecartTNT getTrackedCart()      { return trackedCart; }
    public boolean isCartTracked()           { return cartWasTracked && trackedCart != null && trackedCart.isAlive(); }
}
