package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.vehicle.MinecartTNT;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class XbowCart extends ClientBase.Module {

    public static final String FILE_NAME  = "XbowCart.java";
    public static final String MOD_VER    = "2.0.0-1.21.11";

    public enum Phase {
        IDLE,
        VALIDATE,
        SELECT_RAIL,
        CHECK_RAIL,
        AIM_RAIL,
        PLACE_RAIL,
        VERIFY_RAIL,
        SELECT_CART,
        CHECK_CART,
        AIM_CART,
        PLACE_CART,
        VERIFY_CART,
        SELECT_FIRE,
        CHECK_FIRE,
        AIM_FIRE,
        IGNITE,
        VERIFY_FIRE,
        SELECT_XBOW,
        CHECK_XBOW,
        AIM_SMOOTH,
        VERIFY_AIM,
        SHOOT,
        VERIFY_SHOT,
        COOLDOWN
    }

    public enum CartMode {
        STANDARD,
        TOWER,
        MULTI_ANGLE
    }

    public static final class SequenceSnapshot {
        public final BlockPos hitTarget;
        public final Direction hitFace;
        public final BlockPos railPos;
        public final BlockPos cartPos;
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

        public SequenceSnapshot(BlockPos hitTarget, Direction hitFace, BlockPos railPos, BlockPos cartPos,
                                BlockPos firePos, Vec3 railAim, Vec3 cartAim, Vec3 fireAim, Vec3 xbowAim,
                                int railSlot, int cartSlot, int fireSlot, int xbowSlot, CartMode mode) {
            this.hitTarget    = hitTarget;
            this.hitFace      = hitFace;
            this.railPos      = railPos;
            this.cartPos      = cartPos;
            this.firePos      = firePos;
            this.railAimTarget = railAim;
            this.cartAimTarget = cartAim;
            this.fireAimTarget = fireAim;
            this.xbowAimTarget = xbowAim;
            this.railSlot     = railSlot;
            this.cartSlot     = cartSlot;
            this.fireSlot     = fireSlot;
            this.xbowSlot     = xbowSlot;
            this.mode         = mode;
            this.capturedAtEpoch = System.currentTimeMillis();
            this.snapshotId   = UUID.randomUUID();
        }
    }

    private static final SecureRandom secureRandom = new SecureRandom();
    private static final Map<String, Object> XBOW_REGISTRY = new ConcurrentHashMap<>();

    private static final double RAIL_AIM_Y_OFFSET   = 0.55;
    private static final double CART_AIM_Y_OFFSET   = 0.6;
    private static final double FIRE_AIM_Y_OFFSET   = 0.5;
    private static final double XBOW_AIM_Y_OFFSET   = 0.85;
    private static final double ENTITY_SCAN_RADIUS  = 2.5;
    private static final double RAIL_REACH           = 4.5;
    private static final float  AIM_YAW_TOLERANCE   = 2.0f;
    private static final float  AIM_PITCH_TOLERANCE = 2.5f;
    private static final float  SHOOT_YAW_TOLERANCE = 3.5f;
    private static final float  SHOOT_PITCH_TOLERANCE = 3.5f;
    private static final int    MAX_RETRIES          = 3;
    private static final int    COOLDOWN_BASE_TICKS  = 25;
    private static final int    TICK_WAIT_AFTER_AIM  = 2;
    private static final int    TICK_WAIT_AFTER_PLACE = 3;
    private static final int    TICK_WAIT_AFTER_IGNITE = 2;
    private static final int    MIN_FLINT_DURABILITY = 2;

    private Phase phase = Phase.IDLE;
    private SequenceSnapshot snapshot = null;
    private int tickTimer = 0;
    private int retryCount = 0;
    private int cooldownTicks = 0;
    private long lastSequenceStartEpoch = 0L;
    private long totalSequencesAttempted = 0L;
    private long totalSequencesCompleted = 0L;
    private long totalSequencesFailed = 0L;
    private String statusDetail = "idle";
    private MinecartTNT trackedCart = null;
    private boolean cartWasTracked = false;

    private float smoothFactor        = 0.35f;
    private int   maxAimTicks         = 18;
    private int   aimTicksElapsed     = 0;
    private boolean autoRestart       = false;
    private boolean verifyWorldState  = true;
    private CartMode defaultCartMode  = CartMode.MULTI_ANGLE;

    public XbowCart() {
        super("XbowCart", ClientBase.ModuleCategory.COMBAT);
        XBOW_REGISTRY.put("ModuleVersion", MOD_VER);
        XBOW_REGISTRY.put("Profile", "CartPvP-MultiAngle-Enterprise");
        XBOW_REGISTRY.put("AimYawTolerance", AIM_YAW_TOLERANCE);
        XBOW_REGISTRY.put("AimPitchTolerance", AIM_PITCH_TOLERANCE);
        XBOW_REGISTRY.put("MaxRetries", MAX_RETRIES);
    }

    @Override
    public void onEnable(Minecraft client) {
        super.onEnable(client);
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
        XBOW_REGISTRY.put("Phase", phase.name());
        XBOW_REGISTRY.put("Enabled", true);
    }

    @Override
    public void onDisable(Minecraft client) {
        super.onDisable(client);
        hardReset(client);
        XBOW_REGISTRY.put("Phase", phase.name());
        XBOW_REGISTRY.put("Enabled", false);
        XBOW_REGISTRY.put("TotalAttempted", totalSequencesAttempted);
        XBOW_REGISTRY.put("TotalCompleted", totalSequencesCompleted);
        XBOW_REGISTRY.put("TotalFailed", totalSequencesFailed);
    }

    @Override
    public void onTick(Minecraft client) {
        super.onTick(client);

        if (client.player == null || client.level == null) return;
        if (client.screen != null && !(client.screen instanceof ClickGUI)) return;

        SafetyWatchdog.update(client);
        if (SafetyWatchdog.isTripped()) {
            if (phase != Phase.IDLE) {
                totalSequencesFailed++;
                hardReset(client);
            }
            return;
        }

        if (SafetyWatchdog.isCoolingDown() && phase == Phase.IDLE) return;

        switch (phase) {
            case IDLE         -> tickIdle(client);
            case VALIDATE     -> tickValidate(client);
            case SELECT_RAIL  -> tickSelectRail(client);
            case CHECK_RAIL   -> tickCheckRail(client);
            case AIM_RAIL     -> tickAimRail(client);
            case PLACE_RAIL   -> tickPlaceRail(client);
            case VERIFY_RAIL  -> tickVerifyRail(client);
            case SELECT_CART  -> tickSelectCart(client);
            case CHECK_CART   -> tickCheckCart(client);
            case AIM_CART     -> tickAimCart(client);
            case PLACE_CART   -> tickPlaceCart(client);
            case VERIFY_CART  -> tickVerifyCart(client);
            case SELECT_FIRE  -> tickSelectFire(client);
            case CHECK_FIRE   -> tickCheckFire(client);
            case AIM_FIRE     -> tickAimFire(client);
            case IGNITE       -> tickIgnite(client);
            case VERIFY_FIRE  -> tickVerifyFire(client);
            case SELECT_XBOW  -> tickSelectXbow(client);
            case CHECK_XBOW   -> tickCheckXbow(client);
            case AIM_SMOOTH   -> tickAimSmooth(client);
            case VERIFY_AIM   -> tickVerifyAim(client);
            case SHOOT        -> tickShoot(client);
            case VERIFY_SHOT  -> tickVerifyShot(client);
            case COOLDOWN     -> tickCooldown(client);
        }

        XBOW_REGISTRY.put("Phase", phase.name());
        XBOW_REGISTRY.put("Status", statusDetail);
        statusLabel = "[" + phase.name() + "] " + statusDetail;
    }

    private void tickIdle(Minecraft client) {
        if (!client.options.keyAttack.isDown()) return;
        if (!InventoryManager.validateSequenceInventory(client)) {
            statusDetail = "missing items";
            return;
        }
        beginSequence(client);
    }

    private void beginSequence(Minecraft client) {
        net.minecraft.world.phys.BlockHitResult bhr = RaycastManager.getValidHit(client);
        if (bhr == null) {
            statusDetail = "no target";
            return;
        }

        BlockPos hitTarget = bhr.getBlockPos();
        Direction hitFace  = bhr.getDirection();

        if (hitFace == Direction.DOWN) {
            statusDetail = "invalid face (DOWN)";
            return;
        }

        BlockPos railPos = RaycastManager.computeRailPosition(client, hitTarget, hitFace);
        if (railPos == null) {
            statusDetail = "cannot compute rail pos";
            return;
        }

        if (!RaycastManager.isWithinReach(client, railPos, RAIL_REACH + 1.0)) {
            statusDetail = "out of reach";
            return;
        }

        BlockPos firePos = RaycastManager.computeFirePosition(client, railPos, hitFace);
        if (firePos == null) {
            statusDetail = "no fire position";
            return;
        }

        int railSlot = InventoryManager.findRail(client);
        int cartSlot = InventoryManager.findItem(client, Items.TNT_MINECART);
        int fireSlot = InventoryManager.findFireSource(client);
        int xbowSlot = InventoryManager.findChargedCrossbow(client);

        if (railSlot < 0 || cartSlot < 0 || fireSlot < 0 || xbowSlot < 0) {
            statusDetail = "slot not found";
            return;
        }

        if (!InventoryManager.hasMinDurability(client, fireSlot, MIN_FLINT_DURABILITY)) {
            statusDetail = "fire source low dur";
            return;
        }

        CartMode mode = resolveCartMode(hitFace);

        Vec3 railAim  = Vec3.atCenterOf(railPos).add(0, RAIL_AIM_Y_OFFSET, 0);
        Vec3 cartAim  = Vec3.atCenterOf(railPos).add(0, CART_AIM_Y_OFFSET, 0);
        Vec3 fireAim  = Vec3.atCenterOf(firePos).add(0, FIRE_AIM_Y_OFFSET, 0);
        Vec3 xbowAim  = Vec3.atCenterOf(railPos).add(0, XBOW_AIM_Y_OFFSET, 0);

        snapshot = new SequenceSnapshot(
                hitTarget, hitFace, railPos, railPos, firePos,
                railAim, cartAim, fireAim, xbowAim,
                railSlot, cartSlot, fireSlot, xbowSlot, mode
        );

        InventoryManager.saveCurrentSlot(client);
        SafetyWatchdog.startGlobal();
        totalSequencesAttempted++;
        lastSequenceStartEpoch = System.currentTimeMillis();
        aimTicksElapsed = 0;
        retryCount = 0;
        trackedCart = null;
        cartWasTracked = false;

        transition(Phase.VALIDATE);
    }

    private void tickValidate(Minecraft client) {
        SafetyWatchdog.enterPhase("VALIDATE");
        if (SafetyWatchdog.checkPhaseTimeout("VALIDATE")) return;

        if (snapshot == null) { abortSequence(client, "null snapshot"); return; }
        if (!InventoryManager.validateSequenceInventory(client)) {
            abortSequence(client, "inventory changed during validate");
            return;
        }

        BlockState surface = client.level.getBlockState(snapshot.railPos.below());
        if (surface.isAir() && snapshot.railPos.below().equals(snapshot.hitTarget)) {
            abortSequence(client, "no surface for rail");
            return;
        }

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
            if (!SafetyWatchdog.onRetry("CHECK_RAIL verify")) return;
            InventoryManager.selectSlotImmediate(client, snapshot.railSlot);
            tickTimer = 1;
            return;
        }

        if (!InventoryManager.verifyActiveItem(client, getRailItem(client, snapshot.railSlot))) {
            if (!SafetyWatchdog.onRetry("CHECK_RAIL item")) return;
            return;
        }

        SafetyWatchdog.onPhaseSuccess("CHECK_RAIL");
        aimTicksElapsed = 0;
        transition(Phase.AIM_RAIL);
    }

    private void tickAimRail(Minecraft client) {
        SafetyWatchdog.enterPhase("AIM_RAIL");
        if (SafetyWatchdog.checkPhaseTimeout("AIM_RAIL")) return;

        aimTicksElapsed++;

        Vec3 aimTarget = adjustedAimForFace(snapshot.railAimTarget, snapshot.hitFace);
        RotationManager.smoothTo(client, aimTarget, computeDynamicSmooth(aimTicksElapsed));

        boolean aligned = RotationManager.isAligned(client, aimTarget, AIM_YAW_TOLERANCE, AIM_PITCH_TOLERANCE);
        if (aligned || aimTicksElapsed >= maxAimTicks) {
            if (!aligned) {
                RotationManager.snapTo(client, aimTarget);
            }
            tickTimer = TICK_WAIT_AFTER_AIM;
            SafetyWatchdog.onPhaseSuccess("AIM_RAIL");
            transition(Phase.PLACE_RAIL);
        }
    }

    private void tickPlaceRail(Minecraft client) {
        SafetyWatchdog.enterPhase("PLACE_RAIL");
        if (SafetyWatchdog.checkPhaseTimeout("PLACE_RAIL")) return;

        if (tickTimer > 0) { tickTimer--; return; }

        if (!RaycastManager.isLookingAtBlock(client, computePlaceSurface(snapshot.railPos))) {
            Vec3 aimTarget = adjustedAimForFace(snapshot.railAimTarget, snapshot.hitFace);
            RotationManager.snapTo(client, aimTarget);
            tickTimer = 1;
            if (!SafetyWatchdog.onRetry("PLACE_RAIL hitresult")) return;
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

        if (!verifyWorldState) {
            SafetyWatchdog.onPhaseSuccess("VERIFY_RAIL");
            transition(Phase.SELECT_CART);
            return;
        }

        BlockState state = client.level.getBlockState(snapshot.railPos);
        boolean hasRail = state.getBlock() instanceof RailBlock
                || state.is(Blocks.RAIL)
                || state.is(Blocks.POWERED_RAIL)
                || state.is(Blocks.ACTIVATOR_RAIL)
                || state.is(Blocks.DETECTOR_RAIL);

        if (!hasRail) {
            if (!SafetyWatchdog.onRetry("VERIFY_RAIL no rail placed")) return;
            InteractionManager.simulateClickUse(client, InteractionManager.InteractionPriority.HIGH);
            tickTimer = TICK_WAIT_AFTER_PLACE;
            return;
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
            if (!SafetyWatchdog.onRetry("CHECK_CART verify")) return;
            InventoryManager.selectSlotImmediate(client, snapshot.cartSlot);
            tickTimer = 1;
            return;
        }

        SafetyWatchdog.onPhaseSuccess("CHECK_CART");
        aimTicksElapsed = 0;
        transition(Phase.AIM_CART);
    }

    private void tickAimCart(Minecraft client) {
        SafetyWatchdog.enterPhase("AIM_CART");
        if (SafetyWatchdog.checkPhaseTimeout("AIM_CART")) return;

        aimTicksElapsed++;

        Vec3 aimTarget = snapshot.cartAimTarget;
        RotationManager.smoothTo(client, aimTarget, computeDynamicSmooth(aimTicksElapsed));

        boolean aligned = RotationManager.isAligned(client, aimTarget, AIM_YAW_TOLERANCE, AIM_PITCH_TOLERANCE);
        if (aligned || aimTicksElapsed >= maxAimTicks) {
            if (!aligned) RotationManager.snapTo(client, aimTarget);
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
        boolean railPresent = railState.is(Blocks.RAIL) || railState.is(Blocks.POWERED_RAIL)
                || railState.is(Blocks.ACTIVATOR_RAIL) || railState.is(Blocks.DETECTOR_RAIL);

        if (!railPresent) {
            abortSequence(client, "rail gone before cart place");
            return;
        }

        if (!RaycastManager.isLookingAtBlock(client, snapshot.railPos)) {
            RotationManager.snapTo(client, snapshot.cartAimTarget);
            tickTimer = 1;
            if (!SafetyWatchdog.onRetry("PLACE_CART hitresult")) return;
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

        if (!verifyWorldState) {
            SafetyWatchdog.onPhaseSuccess("VERIFY_CART");
            transition(Phase.SELECT_FIRE);
            return;
        }

        Optional<MinecartTNT> cartOpt = RaycastManager.findNearestEntity(client, MinecartTNT.class, snapshot.railPos, ENTITY_SCAN_RADIUS);

        if (cartOpt.isEmpty()) {
            if (!SafetyWatchdog.onRetry("VERIFY_CART no cart entity")) return;
            InteractionManager.simulateClickUse(client, InteractionManager.InteractionPriority.HIGH);
            tickTimer = TICK_WAIT_AFTER_PLACE;
            return;
        }

        trackedCart = cartOpt.get();
        cartWasTracked = true;

        Vec3 cartCenter = trackedCart.position().add(0, XBOW_AIM_Y_OFFSET, 0);
        snapshot = new SequenceSnapshot(
                snapshot.hitTarget, snapshot.hitFace, snapshot.railPos, snapshot.railPos,
                snapshot.firePos, snapshot.railAimTarget, snapshot.cartAimTarget,
                snapshot.fireAimTarget, cartCenter,
                snapshot.railSlot, snapshot.cartSlot, snapshot.fireSlot, snapshot.xbowSlot,
                snapshot.mode
        );

        SafetyWatchdog.onPhaseSuccess("VERIFY_CART");
        transition(Phase.SELECT_FIRE);
    }

    private void tickSelectFire(Minecraft client) {
        SafetyWatchdog.enterPhase("SELECT_FIRE");
        if (SafetyWatchdog.checkPhaseTimeout("SELECT_FIRE")) return;

        int freshFireSlot = InventoryManager.findFireSource(client);
        if (freshFireSlot < 0) {
            abortSequence(client, "fire source missing at SELECT_FIRE");
            return;
        }

        if (InventoryManager.selectSlot(client, freshFireSlot)) {
            tickTimer = 1;
            SafetyWatchdog.onPhaseSuccess("SELECT_FIRE");
            transition(Phase.CHECK_FIRE);
        }
    }

    private void tickCheckFire(Minecraft client) {
        SafetyWatchdog.enterPhase("CHECK_FIRE");
        if (SafetyWatchdog.checkPhaseTimeout("CHECK_FIRE")) return;

        if (tickTimer > 0) { tickTimer--; return; }

        int freshFireSlot = InventoryManager.findFireSource(client);
        if (freshFireSlot < 0) {
            abortSequence(client, "fire source gone at CHECK_FIRE");
            return;
        }

        if (!InventoryManager.verifySlotSelected(client, freshFireSlot)) {
            if (!SafetyWatchdog.onRetry("CHECK_FIRE slot")) return;
            InventoryManager.selectSlotImmediate(client, freshFireSlot);
            tickTimer = 1;
            return;
        }

        SafetyWatchdog.onPhaseSuccess("CHECK_FIRE");
        aimTicksElapsed = 0;
        transition(Phase.AIM_FIRE);
    }

    private void tickAimFire(Minecraft client) {
        SafetyWatchdog.enterPhase("AIM_FIRE");
        if (SafetyWatchdog.checkPhaseTimeout("AIM_FIRE")) return;

        if (cartWasTracked && trackedCart != null && !trackedCart.isAlive()) {
            abortSequence(client, "cart died before fire");
            return;
        }

        aimTicksElapsed++;

        Vec3 aimTarget = snapshot.fireAimTarget;
        RotationManager.smoothTo(client, aimTarget, computeDynamicSmooth(aimTicksElapsed));

        boolean aligned = RotationManager.isAligned(client, aimTarget, AIM_YAW_TOLERANCE, AIM_PITCH_TOLERANCE);
        if (aligned || aimTicksElapsed >= maxAimTicks) {
            if (!aligned) RotationManager.snapTo(client, aimTarget);
            tickTimer = TICK_WAIT_AFTER_AIM;
            SafetyWatchdog.onPhaseSuccess("AIM_FIRE");
            transition(Phase.IGNITE);
        }
    }

    private void tickIgnite(Minecraft client) {
        SafetyWatchdog.enterPhase("IGNITE");
        if (SafetyWatchdog.checkPhaseTimeout("IGNITE")) return;

        if (tickTimer > 0) { tickTimer--; return; }

        if (cartWasTracked && trackedCart != null && !trackedCart.isAlive()) {
            abortSequence(client, "cart dead before ignite");
            return;
        }

        if (!RaycastManager.isValidFirePosition(client, snapshot.firePos)) {
            BlockPos altFire = recomputeFirePos(client);
            if (altFire == null) {
                if (!SafetyWatchdog.onRetry("IGNITE fire pos")) return;
                return;
            }
            Vec3 altAim = Vec3.atCenterOf(altFire).add(0, FIRE_AIM_Y_OFFSET, 0);
            snapshot = new SequenceSnapshot(
                    snapshot.hitTarget, snapshot.hitFace, snapshot.railPos, snapshot.railPos,
                    altFire, snapshot.railAimTarget, snapshot.cartAimTarget,
                    altAim, snapshot.xbowAimTarget,
                    snapshot.railSlot, snapshot.cartSlot, snapshot.fireSlot, snapshot.xbowSlot,
                    snapshot.mode
            );
            RotationManager.snapTo(client, altAim);
            tickTimer = 1;
            return;
        }

        BlockState atFire = client.level.getBlockState(snapshot.firePos);
        if (!atFire.isAir()) {
            if (!SafetyWatchdog.onRetry("IGNITE fire pos occupied")) return;
            return;
        }

        InteractionManager.simulateClickUse(client, InteractionManager.InteractionPriority.HIGH);
        tickTimer = TICK_WAIT_AFTER_IGNITE;
        SafetyWatchdog.onPhaseSuccess("IGNITE");
        transition(Phase.VERIFY_FIRE);
    }

    private void tickVerifyFire(Minecraft client) {
        SafetyWatchdog.enterPhase("VERIFY_FIRE");
        if (SafetyWatchdog.checkPhaseTimeout("VERIFY_FIRE")) return;

        if (tickTimer > 0) { tickTimer--; return; }

        if (!verifyWorldState) {
            SafetyWatchdog.onPhaseSuccess("VERIFY_FIRE");
            transition(Phase.SELECT_XBOW);
            return;
        }

        BlockState fireState = client.level.getBlockState(snapshot.firePos);
        boolean hasFire = fireState.getBlock() instanceof FireBlock || fireState.is(Blocks.FIRE) || fireState.is(Blocks.SOUL_FIRE);

        if (!hasFire) {
            if (!SafetyWatchdog.onRetry("VERIFY_FIRE no fire")) return;

            int freshFireSlot = InventoryManager.findFireSource(client);
            if (freshFireSlot >= 0) {
                InventoryManager.selectSlotImmediate(client, freshFireSlot);
                RotationManager.snapTo(client, snapshot.fireAimTarget);
                InteractionManager.simulateClickUse(client, InteractionManager.InteractionPriority.HIGH);
                tickTimer = TICK_WAIT_AFTER_IGNITE;
            }
            return;
        }

        if (cartWasTracked && trackedCart != null && !trackedCart.isAlive()) {
            abortSequence(client, "cart detonated before xbow");
            return;
        }

        SafetyWatchdog.onPhaseSuccess("VERIFY_FIRE");
        transition(Phase.SELECT_XBOW);
    }

    private void tickSelectXbow(Minecraft client) {
        SafetyWatchdog.enterPhase("SELECT_XBOW");
        if (SafetyWatchdog.checkPhaseTimeout("SELECT_XBOW")) return;

        if (cartWasTracked && trackedCart != null && !trackedCart.isAlive()) {
            abortSequence(client, "cart dead at SELECT_XBOW");
            return;
        }

        int freshXbow = InventoryManager.findChargedCrossbow(client);
        if (freshXbow < 0) {
            abortSequence(client, "charged crossbow gone");
            return;
        }

        if (InventoryManager.selectSlot(client, freshXbow)) {
            tickTimer = 1;
            SafetyWatchdog.onPhaseSuccess("SELECT_XBOW");
            transition(Phase.CHECK_XBOW);
        }
    }

    private void tickCheckXbow(Minecraft client) {
        SafetyWatchdog.enterPhase("CHECK_XBOW");
        if (SafetyWatchdog.checkPhaseTimeout("CHECK_XBOW")) return;

        if (tickTimer > 0) { tickTimer--; return; }

        int freshXbow = InventoryManager.findChargedCrossbow(client);
        if (freshXbow < 0) {
            abortSequence(client, "xbow missing at CHECK");
            return;
        }

        if (!InventoryManager.verifySlotSelected(client, freshXbow)) {
            if (!SafetyWatchdog.onRetry("CHECK_XBOW slot")) return;
            InventoryManager.selectSlotImmediate(client, freshXbow);
            tickTimer = 1;
            return;
        }

        ItemStack held = client.player.getMainHandItem();
        if (held.isEmpty() || !(held.getItem() instanceof CrossbowItem) || !CrossbowItem.isCharged(held)) {
            if (!SafetyWatchdog.onRetry("CHECK_XBOW not charged")) return;
            return;
        }

        SafetyWatchdog.onPhaseSuccess("CHECK_XBOW");
        aimTicksElapsed = 0;
        transition(Phase.AIM_SMOOTH);
    }

    private void tickAimSmooth(Minecraft client) {
        SafetyWatchdog.enterPhase("AIM_SMOOTH");
        if (SafetyWatchdog.checkPhaseTimeout("AIM_SMOOTH")) return;

        if (cartWasTracked && trackedCart != null && !trackedCart.isAlive()) {
            abortSequence(client, "cart dead during AIM_SMOOTH");
            return;
        }

        Vec3 aimTarget = resolveXbowTarget(client);
        if (aimTarget == null) {
            abortSequence(client, "no xbow aim target");
            return;
        }

        aimTicksElapsed++;
        float dynamicFactor = computeDynamicSmooth(aimTicksElapsed);
        RotationManager.smoothTo(client, aimTarget, dynamicFactor);

        boolean aligned = RotationManager.isAligned(client, aimTarget, SHOOT_YAW_TOLERANCE, SHOOT_PITCH_TOLERANCE)
                && RotationManager.isStable();

        if (aligned || aimTicksElapsed >= maxAimTicks + 5) {
            if (!RotationManager.isAligned(client, aimTarget, SHOOT_YAW_TOLERANCE, SHOOT_PITCH_TOLERANCE)) {
                RotationManager.snapTo(client, aimTarget);
            }
            tickTimer = 1;
            SafetyWatchdog.onPhaseSuccess("AIM_SMOOTH");
            transition(Phase.VERIFY_AIM);
        }
    }

    private void tickVerifyAim(Minecraft client) {
        SafetyWatchdog.enterPhase("VERIFY_AIM");
        if (SafetyWatchdog.checkPhaseTimeout("VERIFY_AIM")) return;

        if (tickTimer > 0) { tickTimer--; return; }

        if (cartWasTracked && trackedCart != null && !trackedCart.isAlive()) {
            abortSequence(client, "cart dead at VERIFY_AIM");
            return;
        }

        Vec3 aimTarget = resolveXbowTarget(client);
        if (aimTarget == null) {
            abortSequence(client, "lost xbow target at VERIFY_AIM");
            return;
        }

        boolean aligned = RotationManager.isAligned(client, aimTarget, SHOOT_YAW_TOLERANCE, SHOOT_PITCH_TOLERANCE);
        if (!aligned) {
            if (!SafetyWatchdog.onRetry("VERIFY_AIM not aligned")) return;
            RotationManager.snapTo(client, aimTarget);
            tickTimer = 1;
            transition(Phase.AIM_SMOOTH);
            return;
        }

        ItemStack held = client.player.getMainHandItem();
        if (held.isEmpty() || !(held.getItem() instanceof CrossbowItem) || !CrossbowItem.isCharged(held)) {
            abortSequence(client, "xbow uncharged at VERIFY_AIM");
            return;
        }

        SafetyWatchdog.onPhaseSuccess("VERIFY_AIM");
        transition(Phase.SHOOT);
    }

    private void tickShoot(Minecraft client) {
        SafetyWatchdog.enterPhase("SHOOT");
        if (SafetyWatchdog.checkPhaseTimeout("SHOOT")) return;

        if (cartWasTracked && trackedCart != null && !trackedCart.isAlive()) {
            abortSequence(client, "cart detonated before shoot");
            return;
        }

        ItemStack held = client.player.getMainHandItem();
        if (held.isEmpty() || !(held.getItem() instanceof CrossbowItem) || !CrossbowItem.isCharged(held)) {
            abortSequence(client, "xbow not charged at SHOOT");
            return;
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
        boolean shotFired = held.isEmpty() || !(held.getItem() instanceof CrossbowItem) || !CrossbowItem.isCharged(held);

        if (!shotFired) {
            if (!SafetyWatchdog.onRetry("VERIFY_SHOT still charged")) return;
            InteractionManager.simulateClickUse(client, InteractionManager.InteractionPriority.IMMEDIATE);
            tickTimer = 2;
            return;
        }

        SafetyWatchdog.onPhaseSuccess("VERIFY_SHOT");
        totalSequencesCompleted++;
        long elapsed = System.currentTimeMillis() - lastSequenceStartEpoch;
        XBOW_REGISTRY.put("LastSequenceDurationMs", elapsed);
        XBOW_REGISTRY.put("TotalCompleted", totalSequencesCompleted);
        statusDetail = "shot fired (" + elapsed + "ms)";

        cooldownTicks = computeDynamicCooldown();
        transition(Phase.COOLDOWN);
    }

    private void tickCooldown(Minecraft client) {
        SafetyWatchdog.enterPhase("COOLDOWN");

        if (cooldownTicks > 0) {
            cooldownTicks--;
            statusDetail = "cooldown " + cooldownTicks;
            return;
        }

        InventoryManager.restoreSavedSlot(client);
        RotationManager.reset();
        InventoryManager.invalidateCache();
        SafetyWatchdog.onPhaseSuccess("COOLDOWN");
        SafetyWatchdog.reset();
        snapshot = null;
        trackedCart = null;
        cartWasTracked = false;
        aimTicksElapsed = 0;
        retryCount = 0;
        statusDetail = "idle";

        if (autoRestart) {
            transition(Phase.IDLE);
        } else {
            transition(Phase.IDLE);
        }
    }

    private void abortSequence(Minecraft client, String reason) {
        totalSequencesFailed++;
        statusDetail = "ABORT: " + reason;
        XBOW_REGISTRY.put("AbortReason", reason);
        XBOW_REGISTRY.put("TotalFailed", totalSequencesFailed);
        SafetyWatchdog.trip(SafetyWatchdog.TripReason.EXTERNAL_FORCE_TRIP, SafetyWatchdog.SeverityLevel.WARN, reason);
        hardReset(client);
    }

    private void hardReset(Minecraft client) {
        InventoryManager.restoreSavedSlot(client);
        InteractionManager.flushAndRelease(client);
        RotationManager.reset();
        InventoryManager.invalidateCache();
        SafetyWatchdog.reset();
        snapshot = null;
        trackedCart = null;
        cartWasTracked = false;
        phase = Phase.IDLE;
        tickTimer = 0;
        retryCount = 0;
        cooldownTicks = 0;
        aimTicksElapsed = 0;
    }

    private void transition(Phase next) {
        phase = next;
        tickTimer = 0;
        XBOW_REGISTRY.put("Phase", next.name());
        XBOW_REGISTRY.put("PhaseTransitionEpoch", System.currentTimeMillis());
    }

    private Vec3 resolveXbowTarget(Minecraft client) {
        if (cartWasTracked && trackedCart != null && trackedCart.isAlive()) {
            return trackedCart.position().add(0, XBOW_AIM_Y_OFFSET, 0);
        }
        if (snapshot != null) {
            Optional<MinecartTNT> fresh = RaycastManager.findNearestEntity(client, MinecartTNT.class, snapshot.railPos, ENTITY_SCAN_RADIUS);
            if (fresh.isPresent()) {
                trackedCart = fresh.get();
                return trackedCart.position().add(0, XBOW_AIM_Y_OFFSET, 0);
            }
        }
        return snapshot != null ? snapshot.xbowAimTarget : null;
    }

    private BlockPos recomputeFirePos(Minecraft client) {
        if (snapshot == null) return null;
        return RaycastManager.computeFirePosition(client, snapshot.railPos, snapshot.hitFace);
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

    private BlockPos computePlaceSurface(BlockPos railPos) {
        return railPos.below();
    }

    private CartMode resolveCartMode(Direction hitFace) {
        return switch (hitFace) {
            case UP -> CartMode.STANDARD;
            case NORTH, SOUTH, EAST, WEST -> CartMode.TOWER;
            default -> defaultCartMode;
        };
    }

    private float computeDynamicSmooth(int ticksElapsed) {
        float t = Math.min(1.0f, (float) ticksElapsed / Math.max(1, maxAimTicks));
        return smoothFactor + (1.0f - smoothFactor) * t * t;
    }

    private int computeDynamicCooldown() {
        long pingMs = PacketBufferManager.getSmoothedPingMs();
        int pingBonus = (int) (pingMs / 50L);
        return COOLDOWN_BASE_TICKS + Math.min(15, pingBonus) + secureRandom.nextInt(6);
    }

    private net.minecraft.world.item.Item getRailItem(Minecraft client, int slot) {
        if (client.player == null) return Items.RAIL;
        ItemStack stack = client.player.getInventory().getItem(slot);
        if (stack.isEmpty()) return Items.RAIL;
        return stack.getItem();
    }

    public void setSmoothFactor(float f) { smoothFactor = Math.max(0.05f, Math.min(1.0f, f)); }
    public void setMaxAimTicks(int ticks) { maxAimTicks = Math.max(1, ticks); }
    public void setAutoRestart(boolean enabled) { autoRestart = enabled; }
    public void setVerifyWorldState(boolean enabled) { verifyWorldState = enabled; }
    public void setDefaultCartMode(CartMode mode) { defaultCartMode = mode; }

    public Phase getPhase() { return phase; }
    public SequenceSnapshot getSnapshot() { return snapshot; }
    public long getTotalSequencesAttempted() { return totalSequencesAttempted; }
    public long getTotalSequencesCompleted() { return totalSequencesCompleted; }
    public long getTotalSequencesFailed() { return totalSequencesFailed; }
    public double getSuccessRate() {
        if (totalSequencesAttempted == 0) return 0.0;
        return (double) totalSequencesCompleted / totalSequencesAttempted * 100.0;
    }
    public MinecartTNT getTrackedCart() { return trackedCart; }
    public boolean isCartTracked() { return cartWasTracked && trackedCart != null && trackedCart.isAlive(); }
}
