package com.example;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.vehicle.MinecartTNT;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Blocks;

import java.util.Optional;
import java.util.Random;

public class XbowCart {

    public static boolean enabled = false;

    private enum Phase {
        IDLE,
        SELECT_RAIL, AIM_RAIL,  PLACE_RAIL,  WAIT_RAIL,
        SELECT_CART, AIM_CART,  PLACE_CART,  WAIT_CART,
        SELECT_FIRE, AIM_FIRE,  IGNITE,      WAIT_FIRE,
        SELECT_XBOW, AIM_XBOW,  SHOOT,       COOLDOWN
    }

    private static final Random RNG = new Random();

    private static Phase      phase     = Phase.IDLE;
    private static int        timer     = 0;
    private static int        aimTick   = 0;
    private static int        savedSlot = -1;

    private static BlockPos   railPos   = null;
    private static BlockPos   firePos   = null;
    private static Vec3       railAim   = null;
    private static Vec3       cartAim   = null;
    private static Vec3       fireAim   = null;
    private static Vec3       xbowAim   = null;
    private static MinecartTNT cart     = null;

    private static final float  PLACE_YT   = 3.5f;
    private static final float  PLACE_PT   = 4.0f;
    private static final float  SHOOT_YT   = 4.5f;
    private static final float  SHOOT_PT   = 5.0f;
    private static final int    AIM_MAX    = 6;
    private static final double CART_R     = 3.2;

    public static void onTick(Minecraft mc) {
        if (!enabled || mc.player == null || mc.level == null) return;
        if (mc.screen != null) { if (phase != Phase.IDLE) hardReset(mc); return; }

        RotationManager.samplePlayerGcd(mc);

        switch (phase) {
            case IDLE        -> tickIdle(mc);
            case SELECT_RAIL -> { selectSlot(mc, findRail(mc)); timer = 1; go(Phase.AIM_RAIL); }
            case AIM_RAIL    -> tickFlick(mc, railAim, PLACE_YT, PLACE_PT, Phase.PLACE_RAIL);
            case PLACE_RAIL  -> { if (--timer > 0) return; rightClick(mc); timer = 2; go(Phase.WAIT_RAIL); }
            case WAIT_RAIL   -> tickWait(mc, Phase.SELECT_CART, () -> isRailAt(mc, railPos));
            case SELECT_CART -> { selectSlot(mc, findItem(mc, Items.TNT_MINECART)); timer = 1; go(Phase.AIM_CART); }
            case AIM_CART    -> tickFlick(mc, cartAim, PLACE_YT, PLACE_PT, Phase.PLACE_CART);
            case PLACE_CART  -> { if (--timer > 0) return; rightClick(mc); timer = 2; go(Phase.WAIT_CART); }
            case WAIT_CART   -> tickWaitCart(mc);
            case SELECT_FIRE -> { selectSlot(mc, findFireSource(mc)); timer = 1; go(Phase.AIM_FIRE); }
            case AIM_FIRE    -> tickFlick(mc, fireAim, PLACE_YT, PLACE_PT, Phase.IGNITE);
            case IGNITE      -> { if (--timer > 0) return; rightClick(mc); timer = 1; go(Phase.WAIT_FIRE); }
            case WAIT_FIRE   -> tickWait(mc, Phase.SELECT_XBOW, () -> isFireAt(mc, firePos));
            case SELECT_XBOW -> { selectSlot(mc, findChargedXbow(mc)); timer = 1; go(Phase.AIM_XBOW); }
            case AIM_XBOW    -> tickFlick(mc, liveXbowAim(mc), SHOOT_YT, SHOOT_PT, Phase.SHOOT);
            case SHOOT       -> tickShoot(mc);
            case COOLDOWN    -> { if (--timer <= 0) hardReset(mc); }
        }
    }

    public static void purgePipelineRegistry() { hardReset(null); }

    private static void tickIdle(Minecraft mc) {
        if (!mc.options.keyAttack.isDown()) return;

        if (mc.hitResult == null || mc.hitResult.getType() != HitResult.Type.BLOCK) return;
        BlockHitResult bhr = (BlockHitResult) mc.hitResult;
        if (bhr.getDirection() == Direction.DOWN) return;

        BlockPos hit = bhr.getBlockPos();
        railPos = computeRailPos(mc, hit);
        if (railPos == null || blockDist(mc, railPos) > 4.9) return;

        firePos = pickFirePos(mc, railPos);
        if (firePos == null) return;

        if (findRail(mc) < 0 || findItem(mc, Items.TNT_MINECART) < 0
         || findFireSource(mc) < 0 || findChargedXbow(mc) < 0) return;

        railAim = Vec3.atCenterOf(railPos).add(0, 0.55, 0);
        cartAim = Vec3.atCenterOf(railPos).add(0, 0.62, 0);
        fireAim = Vec3.atCenterOf(firePos).add(0, 0.50, 0);
        xbowAim = Vec3.atCenterOf(railPos).add(0, 0.85, 0);

        savedSlot = mc.player.getInventory().getSelectedSlot();
        aimTick   = 0;
        cart      = null;
        go(Phase.SELECT_RAIL);
    }

    private static void tickFlick(Minecraft mc, Vec3 target, float yt, float pt, Phase next) {
        if (target == null) { hardReset(mc); return; }
        if (--timer > 0) return;

        aimTick++;
        float ang = angDist(mc, target);
        float spd = flickSpeed(aimTick, ang);
        RotationManager.smoothTo(mc, target, spd);

        boolean ok = RotationManager.isAligned(mc, target, yt, pt);
        if (ok || aimTick >= AIM_MAX) {
            if (!ok) RotationManager.snapTo(mc, target);
            aimTick = 0;
            timer   = 1;
            go(next);
        }
    }

    private static void tickWait(Minecraft mc, Phase next, java.util.function.BooleanSupplier check) {
        if (timer > 0) { timer--; return; }
        if (!check.getAsBoolean()) {
            if (timer++ > 6) { hardReset(mc); return; }
            return;
        }
        go(next);
    }

    private static void tickWaitCart(Minecraft mc) {
        if (timer > 0) { timer--; return; }
        cart = findCart(mc, railPos, CART_R);
        if (cart == null) {
            if (timer++ > 6) { hardReset(mc); return; }
            rightClick(mc);
            return;
        }
        xbowAim = predictCart(cart);
        go(Phase.SELECT_FIRE);
    }

    private static void tickShoot(Minecraft mc) {
        if (--timer > 0) return;
        int slot = findChargedXbow(mc);
        if (slot < 0) { hardReset(mc); return; }
        selectSlot(mc, slot);

        ItemStack held = mc.player.getMainHandItem();
        if (held.isEmpty() || !(held.getItem() instanceof CrossbowItem) || !CrossbowItem.isCharged(held)) {
            hardReset(mc); return;
        }
        if (cart != null && !cart.isAlive()) { hardReset(mc); return; }

        Vec3 live = liveXbowAim(mc);
        if (live != null && !RotationManager.isAligned(mc, live, SHOOT_YT, SHOOT_PT)) {
            RotationManager.snapTo(mc, live);
        }

        rightClick(mc);
        timer = 20 + RNG.nextInt(8);
        go(Phase.COOLDOWN);
    }

    private static void rightClick(Minecraft mc) {
        if (mc.player == null || mc.gameMode == null) return;
        mc.options.keyUse.setDown(true);
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        mc.options.keyUse.setDown(false);
    }

    private static float flickSpeed(int tick, float ang) {
        float t    = Math.min(1.0f, (float) tick / AIM_MAX);
        float ease = 1.0f - (1.0f - t) * (1.0f - t) * (1.0f - t);
        float near = ang < 10.0f ? (0.28f + 0.72f * ang / 10.0f) : 1.0f;
        return (0.70f + 0.30f * ease) * near;
    }

    private static float angDist(Minecraft mc, Vec3 aim) {
        double dx = aim.x - mc.player.getX();
        double dy = aim.y - mc.player.getEyeY();
        double dz = aim.z - mc.player.getZ();
        double h  = Math.max(1e-9, Math.sqrt(dx * dx + dz * dz));
        float ty  = (float)(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float tp  = (float)(-Math.toDegrees(Math.atan2(dy, h)));
        float dy2 = Math.abs(Mth.wrapDegrees(mc.player.getYRot() - ty));
        float dp2 = Math.abs(mc.player.getXRot() - tp);
        return (float) Math.sqrt(dy2 * dy2 + dp2 * dp2);
    }

    private static Vec3 liveXbowAim(Minecraft mc) {
        if (cart != null && cart.isAlive()) return predictCart(cart);
        MinecartTNT found = railPos != null ? findCart(mc, railPos, CART_R) : null;
        if (found != null) { cart = found; return predictCart(found); }
        return xbowAim;
    }

    private static Vec3 predictCart(MinecartTNT c) {
        Vec3 p = c.position();
        Vec3 v = c.getDeltaMovement();
        return p.add(v.x * 1.9, 0.85, v.z * 1.9);
    }

    private static BlockPos computeRailPos(Minecraft mc, BlockPos hit) {
        BlockPos up = hit.above();
        if (mc.level.getBlockState(up).isAir()) return up;
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos adj = hit.relative(d);
            if (mc.level.getBlockState(adj).isAir() && mc.level.getBlockState(adj.below()).isSolidRender(mc.level, adj.below())) return adj;
        }
        return null;
    }

    private static BlockPos pickFirePos(Minecraft mc, BlockPos rail) {
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos p = rail.relative(d);
            if (mc.level.getBlockState(p).isAir() && blockDist(mc, p) <= 4.9) return p;
        }
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos p = rail.relative(d).below();
            if (mc.level.getBlockState(p).isAir() && blockDist(mc, p) <= 4.9) return p;
        }
        return null;
    }

    private static boolean isRailAt(Minecraft mc, BlockPos pos) {
        if (mc.level == null || pos == null) return false;
        var b = mc.level.getBlockState(pos).getBlock();
        return b == Blocks.RAIL || b == Blocks.POWERED_RAIL || b == Blocks.DETECTOR_RAIL || b == Blocks.ACTIVATOR_RAIL;
    }

    private static boolean isFireAt(Minecraft mc, BlockPos pos) {
        if (mc.level == null || pos == null) return false;
        var b = mc.level.getBlockState(pos).getBlock();
        return b == Blocks.FIRE || b == Blocks.SOUL_FIRE || b == Blocks.CAMPFIRE || b == Blocks.SOUL_CAMPFIRE;
    }

    private static MinecartTNT findCart(Minecraft mc, BlockPos near, double r) {
        if (mc.level == null || near == null) return null;
        Vec3 c = Vec3.atCenterOf(near);
        return mc.level.getEntitiesOfClass(MinecartTNT.class,
            new net.minecraft.world.phys.AABB(c.x-r, c.y-r, c.z-r, c.x+r, c.y+r, c.z+r),
            e -> e.isAlive()).stream()
            .min(Comparator.comparingDouble(e -> e.position().distanceTo(c)))
            .orElse(null);
    }

    private static double blockDist(Minecraft mc, BlockPos pos) {
        if (mc.player == null) return 999;
        Vec3 eye = mc.player.getEyePosition(1.0f);
        return Math.sqrt(pos.distToCenterSqr(eye.x, eye.y, eye.z));
    }

    private static int findRail(Minecraft mc) {
        for (int i = 0; i < 9; i++) {
            var b = mc.player.getInventory().getItem(i).getItem();
            if (b == Items.RAIL || b == Items.POWERED_RAIL || b == Items.DETECTOR_RAIL || b == Items.ACTIVATOR_RAIL) return i;
        }
        return -1;
    }

    private static int findItem(Minecraft mc, net.minecraft.world.item.Item item) {
        for (int i = 0; i < 9; i++) { if (mc.player.getInventory().getItem(i).getItem() == item) return i; }
        return -1;
    }

    private static int findFireSource(Minecraft mc) {
        for (int i = 0; i < 9; i++) {
            var b = mc.player.getInventory().getItem(i).getItem();
            if (b == Items.FLINT_AND_STEEL || b == Items.FIRE_CHARGE || b == Items.CAMPFIRE) return i;
        }
        return -1;
    }

    private static int findChargedXbow(Minecraft mc) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (!s.isEmpty() && s.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(s)) return i;
        }
        return -1;
    }

    private static void selectSlot(Minecraft mc, int slot) {
        if (mc.player != null && slot >= 0 && slot < 9) mc.player.getInventory().setSelectedSlot(slot);
    }

    private static void go(Phase next) { phase = next; timer = 0; aimTick = 0; }

    private static void hardReset(Minecraft mc) {
        if (mc != null && mc.player != null && savedSlot >= 0 && savedSlot < 9) {
            mc.player.getInventory().setSelectedSlot(savedSlot);
        }
        RotationManager.reset();
        phase = Phase.IDLE; timer = 0; aimTick = 0; savedSlot = -1;
        railPos = null; firePos = null;
        railAim = cartAim = fireAim = xbowAim = null;
        cart = null;
    }

}
