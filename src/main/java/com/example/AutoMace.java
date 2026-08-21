package com.slither.cyemer.module.implementation.combat;

import com.slither.cyemer.event.EventBus;
import com.slither.cyemer.event.impl.AutoMaceSyncEvent;
import com.slither.cyemer.event.impl.MaceHitEvent;
import com.slither.cyemer.event.impl.ShieldDrainEvent;
import com.slither.cyemer.friend.FriendManager;
import com.slither.cyemer.manager.TargetManager;
import com.slither.cyemer.mixin.KeyBindingAccessor;
import com.slither.cyemer.module.BooleanSetting;
import com.slither.cyemer.module.Category;
import com.slither.cyemer.module.ModeSetting;
import com.slither.cyemer.module.Module;
import com.slither.cyemer.module.SliderSetting;
import com.slither.cyemer.util.AttackValidator;
import com.slither.cyemer.util.RotationManager;
import com.slither.cyemer.util.render.RenderUtils;
import java.awt.Color;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.class_1297;
import net.minecraft.class_1657;
import net.minecraft.class_1743;
import net.minecraft.class_1799;
import net.minecraft.class_1819;
import net.minecraft.class_1887;
import net.minecraft.class_2246;
import net.minecraft.class_2338;
import net.minecraft.class_238;
import net.minecraft.class_243;
import net.minecraft.class_2680;
import net.minecraft.class_332;
import net.minecraft.class_3486;
import net.minecraft.class_3489;
import net.minecraft.class_3532;
import net.minecraft.class_3966;
import net.minecraft.class_4587;
import net.minecraft.class_5321;
import net.minecraft.class_6880;
import net.minecraft.class_746;
import net.minecraft.class_9304;
import net.minecraft.class_9334;
import net.minecraft.class_9362;

/* JADX INFO: loaded from: Cyemer-1.21.11.jar:com/slither/cyemer/module/implementation/combat/AutoMace.class */
@Environment(EnvType.CLIENT)
public class AutoMace extends Module {
    private static final double ATTACK_RANGE = 2.95d;
    private final SliderSetting swingRange;
    private final SliderSetting aimRange;
    private final SliderSetting aimInAir;
    private final BooleanSetting autoSwitch;
    private final BooleanSetting swapBack;
    private final SliderSetting rotationSpeed;
    private final SliderSetting minFallDist;
    private final SliderSetting cooldown;
    private final SliderSetting maceSwapDelay;
    private final BooleanSetting stunSlam;
    private final BooleanSetting weaponOnly;
    private final ModeSetting aimMode;
    private final ModeSetting stopAim;
    private final SliderSetting hitboxAccuracy;
    private final BooleanSetting ignoreFriends;
    private final BooleanSetting renderPred;
    private final BooleanSetting targetMode;
    private class_1657 currentTarget;
    private int maceClicksLeft;
    private int originalSlot;
    private int preSequenceSlot;
    private long lastComboTime;
    private long axeHitTime;
    private int resetTimer;
    private double highestY;
    private boolean wasOnGround;
    private boolean shouldAttackThisTick;
    private boolean shouldBreakShield;
    private boolean shouldMaceSmash;
    private int targetSlotForAttack;
    private boolean isSwappingArmor;
    private int armorSwapTimer;
    private int armorSwapReturnSlot;

    public AutoMace() {
        super("AutoMace", "boing boing smash boing", Category.COMBAT);
        this.swingRange = new SliderSetting("Swing Range", 3.0d, 2.5d, 3.0d, 1);
        this.aimRange = new SliderSetting("Aim Range", 15.0d, 0.0d, 10.0d, 1);
        this.aimInAir = new SliderSetting("Aim In Air", 4.5d, 0.0d, 15.0d, 1);
        this.autoSwitch = new BooleanSetting("Auto Switch", true);
        this.swapBack = new BooleanSetting("Swap Back", true);
        this.rotationSpeed = new SliderSetting("Aim Speed", 24.0d, 0.0d, 35.0d, 1);
        this.minFallDist = new SliderSetting("Min Fall Dist", 1.5d, 0.0d, 5.0d, 1);
        this.cooldown = new SliderSetting("Cooldown (ms)", 500.0d, 100.0d, 2000.0d, 0);
        this.maceSwapDelay = new SliderSetting("Mace Swap Delay (ms)", 1.0d, 0.0d, 100.0d, 0);
        this.stunSlam = new BooleanSetting("Stun Slam", true);
        this.weaponOnly = new BooleanSetting("Weapon Only", false);
        this.aimMode = new ModeSetting("Aim Mode", "Strict", "Loose", "Horizontal");
        this.stopAim = new ModeSetting("Stop Aim", "Hitbox Edge", "Exact Center");
        this.hitboxAccuracy = new SliderSetting("Hitbox Accuracy", 0.3d, 0.0d, 1.0d, 2);
        this.ignoreFriends = new BooleanSetting("Ignore Friends", true);
        this.renderPred = new BooleanSetting("Render Pred", false);
        this.targetMode = new BooleanSetting("Target Mode", false);
        this.currentTarget = null;
        this.maceClicksLeft = 0;
        this.originalSlot = -1;
        this.preSequenceSlot = -1;
        this.lastComboTime = 0L;
        this.axeHitTime = 0L;
        this.resetTimer = 0;
        this.highestY = 0.0d;
        this.wasOnGround = true;
        this.shouldAttackThisTick = false;
        this.shouldBreakShield = false;
        this.shouldMaceSmash = false;
        this.targetSlotForAttack = -1;
        this.isSwappingArmor = false;
        this.armorSwapTimer = 0;
        this.armorSwapReturnSlot = -1;
        addSetting(this.swingRange);
        addSetting(this.aimRange);
        addSetting(this.aimInAir);
        addSetting(this.autoSwitch);
        addSetting(this.swapBack);
        addSetting(this.rotationSpeed);
        addSetting(this.minFallDist);
        addSetting(this.cooldown);
        addSetting(this.maceSwapDelay);
        addSetting(this.stunSlam);
        addSetting(this.weaponOnly);
        addSetting(this.aimMode);
        addSetting(this.stopAim);
        addSetting(this.hitboxAccuracy);
        addSetting(this.ignoreFriends);
        addSetting(this.renderPred);
        addSetting(this.targetMode);
    }

    @Override // com.slither.cyemer.module.Module
    public void onRender(class_332 context, float tickDelta) {
        if (!isEnabled()) {
            return;
        }
        ShieldDrainEvent drainEvent = new ShieldDrainEvent();
        EventBus.post(drainEvent);
        if (drainEvent.isActive()) {
            return;
        }
        this.shouldAttackThisTick = false;
        this.shouldBreakShield = false;
        this.shouldMaceSmash = false;
        this.targetSlotForAttack = -1;
        runRenderLogic();
    }

    @Override // com.slither.cyemer.module.Module
    public void onWorldRender(class_4587 matrices, float tickDelta) {
        if (isEnabled() && this.renderPred.isEnabled() && this.currentTarget != null && this.f23mc.field_1724 != null) {
            renderPredictions(matrices, tickDelta);
        }
    }

    @Override // com.slither.cyemer.module.Module
    public void onTick() {
        if (!isEnabled()) {
            return;
        }
        ShieldDrainEvent drainEvent = new ShieldDrainEvent();
        EventBus.post(drainEvent);
        if (drainEvent.isActive()) {
            return;
        }
        if (this.isSwappingArmor) {
            manageArmorSwap();
            return;
        }
        if (this.shouldBreakShield) {
            executeShieldBreak();
        } else if (this.shouldMaceSmash) {
            executeMaceSmash();
        } else if (this.shouldAttackThisTick) {
            executeAttack();
        }
    }

    private void manageArmorSwap() {
        if (this.f23mc.field_1724 == null) {
            this.isSwappingArmor = false;
            return;
        }
        this.armorSwapTimer--;
        if (this.armorSwapTimer == 1) {
            KeyBindingAccessor useKey = this.f23mc.field_1690.field_1904;
            useKey.setTimesPressed(useKey.getTimesPressed() + 1);
        }
        if (this.armorSwapTimer <= 0) {
            if (this.armorSwapReturnSlot != -1) {
                this.f23mc.field_1724.method_31548().method_61496(this.armorSwapReturnSlot);
            }
            this.isSwappingArmor = false;
            this.armorSwapReturnSlot = -1;
        }
    }

    private void triggerArmorSwap(int targetSlot) {
        if (this.f23mc.field_1724 == null || targetSlot == -1 || this.isSwappingArmor) {
            return;
        }
        this.armorSwapReturnSlot = this.f23mc.field_1724.method_31548().method_67532();
        this.f23mc.field_1724.method_31548().method_61496(targetSlot);
        this.isSwappingArmor = true;
        this.armorSwapTimer = 3;
    }

    private class_243 getAimPos(class_1297 target) {
        if (target == null || this.f23mc.field_1724 == null) {
            return class_243.field_1353;
        }
        class_238 box = target.method_5829();
        class_243 center = box.method_1005();
        double aimY = box.field_1322 + (((double) target.method_17682()) * 0.65d);
        return new class_243(center.field_1352, aimY, center.field_1350);
    }

    private boolean canExecuteAttack() {
        if (this.f23mc.field_1724 == null || this.currentTarget == null || !AttackValidator.canAttack(this.f23mc)) {
            return false;
        }
        double effectiveRange = getEffectiveAttackRange();
        if (!this.f23mc.field_1724.method_6057(this.currentTarget) || !isWithinLegitReach(this.currentTarget, effectiveRange)) {
            return false;
        }
        class_3966 class_3966Var = this.f23mc.field_1765;
        if (class_3966Var instanceof class_3966) {
            class_3966 ehr = class_3966Var;
            if (ehr.method_17782() == this.currentTarget) {
                return true;
            }
        }
        return false;
    }

    private boolean isHorizontalMode() {
        return this.aimMode.getCurrentMode().equals("Horizontal");
    }

    private boolean shouldRotate() {
        return isHorizontalMode() ? this.aimRange.getValue() > 0.0d || this.aimInAir.getValue() > 0.0d : this.aimRange.getValue() > 0.0d;
    }

    private void runRenderLogic() {
        if (this.f23mc.field_1724 == null || this.f23mc.field_1687 == null || this.isSwappingArmor) {
            return;
        }
        if (isInLiquidOrWeb()) {
            stopAiming();
            return;
        }
        boolean isOnGroundNow = this.f23mc.field_1724.method_24828();
        if (isOnGroundNow) {
            this.highestY = this.f23mc.field_1724.method_23318();
        } else {
            this.highestY = Math.max(this.highestY, this.f23mc.field_1724.method_23318());
        }
        double manualFallDist = Math.max(0.0d, this.highestY - this.f23mc.field_1724.method_23318());
        this.wasOnGround = isOnGroundNow;
        int bestMaceSlot = findBestMace();
        boolean isHoldingMace = this.f23mc.field_1724.method_6047().method_7909() instanceof class_9362;
        boolean canUseMace = isHoldingMace || (this.autoSwitch.isEnabled() && bestMaceSlot != -1);
        boolean canHorizontalWeaponAim = isHorizontalMode() && this.weaponOnly.isEnabled() && isInAirForHorizontalAssist() && isHoldingWeapon();
        if (!canUseMace && !canHorizontalWeaponAim) {
            stopAiming();
            return;
        }
        if (this.weaponOnly.isEnabled() && !isHoldingWeapon()) {
            stopAiming();
            return;
        }
        if (this.resetTimer > 0) {
            handleResetSequence();
            return;
        }
        if (this.maceClicksLeft > 0) {
            calculateMaceLogic();
            return;
        }
        if (System.currentTimeMillis() - this.lastComboTime < this.cooldown.getValue()) {
            return;
        }
        this.currentTarget = findTarget();
        if (this.currentTarget == null) {
            stopAiming();
            return;
        }
        if (isHorizontalMode() && !isTargetInHorizontalFov(this.currentTarget)) {
            stopAiming();
            return;
        }
        boolean gameSaysFalling = this.f23mc.field_1724.field_6017 >= this.minFallDist.getValue();
        boolean manualSaysFalling = manualFallDist >= this.minFallDist.getValue();
        boolean isFalling = gameSaysFalling || manualSaysFalling;
        if (!isFalling && this.minFallDist.getValue() > 0.1d) {
            stopAiming();
            return;
        }
        boolean isBlocking = isTargetBlocking(this.currentTarget);
        boolean canStunSlam = this.stunSlam.isEnabled() && isBlocking;
        EventBus.post(new AutoMaceSyncEvent());
        if (canStunSlam) {
            calculateStunSlam();
        } else {
            calculateDirectMaceLogic();
        }
    }

    private boolean isInLiquidOrWeb() {
        if (this.f23mc.field_1724 == null || this.f23mc.field_1687 == null) {
            return false;
        }
        class_238 box = this.f23mc.field_1724.method_5829();
        class_2338 min = class_2338.method_49637(box.field_1323, box.field_1322, box.field_1321);
        class_2338 max = class_2338.method_49637(box.field_1320, box.field_1325, box.field_1324);
        class_2338.class_2339 mutable = new class_2338.class_2339();
        for (int x = min.method_10263(); x <= max.method_10263(); x++) {
            for (int y = min.method_10264(); y <= max.method_10264(); y++) {
                for (int z = min.method_10260(); z <= max.method_10260(); z++) {
                    mutable.method_10103(x, y, z);
                    class_2680 state = this.f23mc.field_1687.method_8320(mutable);
                    if (state.method_26227().method_15767(class_3486.field_15517) || state.method_26204() == class_2246.field_10343) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void calculateStunSlam() {
        double maxRange = getTrackingRange(this.currentTarget);
        if (this.f23mc.field_1724.method_5739(this.currentTarget) > maxRange) {
            stopAiming();
            this.currentTarget = null;
            this.maceClicksLeft = 0;
            this.originalSlot = -1;
            return;
        }
        class_243 aimPos = getAimPos(this.currentTarget);
        if (shouldRotate()) {
            applyAimRotation(aimPos, RotationManager.Priority.HIGH);
        }
        if (canExecuteAttack()) {
            int axeSlot = findAxe();
            int maceSlot = findBestMace();
            if (axeSlot != -1 && maceSlot != -1) {
                if (this.preSequenceSlot == -1) {
                    this.preSequenceSlot = this.f23mc.field_1724.method_31548().method_67532();
                }
                this.shouldBreakShield = true;
                this.targetSlotForAttack = axeSlot;
                this.originalSlot = maceSlot;
            }
        }
    }

    private void executeShieldBreak() {
        if (this.currentTarget == null || !syncToAttackSlot() || !canExecuteAttack()) {
            return;
        }
        boolean success = AttackValidator.tryAttack(this.f23mc, "combat.attack.automace");
        if (success) {
            EventBus.post(new MaceHitEvent());
            this.maceClicksLeft = 1;
            this.axeHitTime = System.currentTimeMillis();
        } else {
            swapBackToPreSequence();
            this.originalSlot = -1;
        }
    }

    private void calculateMaceLogic() {
        double maxRange = getTrackingRange(this.currentTarget);
        if (this.currentTarget == null || !this.currentTarget.method_5805() || this.f23mc.field_1724.method_5739(this.currentTarget) > maxRange) {
            swapBackToPreSequence();
            this.maceClicksLeft = 0;
            this.originalSlot = -1;
            stopAiming();
            return;
        }
        class_243 aimPos = getAimPos(this.currentTarget);
        if (shouldRotate()) {
            applyAimRotation(aimPos, RotationManager.Priority.HIGHEST);
        }
        long timeSinceAxe = System.currentTimeMillis() - this.axeHitTime;
        if (timeSinceAxe < this.maceSwapDelay.getValue()) {
            return;
        }
        if (timeSinceAxe > 1500) {
            swapBackToPreSequence();
            this.maceClicksLeft = 0;
            this.originalSlot = -1;
            stopAiming();
            return;
        }
        if (canExecuteAttack()) {
            this.shouldMaceSmash = true;
            this.targetSlotForAttack = this.originalSlot;
        }
    }

    private void executeMaceSmash() {
        if (!syncToAttackSlot() || !canExecuteAttack()) {
            return;
        }
        boolean success = AttackValidator.tryAttack(this.f23mc, "combat.attack.automace");
        if (success) {
            EventBus.post(new MaceHitEvent());
            this.maceClicksLeft = 0;
            this.resetTimer = 8;
            this.lastComboTime = System.currentTimeMillis();
            return;
        }
        swapBackToPreSequence();
        this.maceClicksLeft = 0;
        this.originalSlot = -1;
    }

    private void calculateDirectMaceLogic() {
        int maceSlot;
        double maxRange = getTrackingRange(this.currentTarget);
        if (this.currentTarget == null || !this.currentTarget.method_5805() || this.f23mc.field_1724.method_5739(this.currentTarget) > maxRange) {
            stopAiming();
            return;
        }
        class_243 aimPos = getAimPos(this.currentTarget);
        if (shouldRotate()) {
            applyAimRotation(aimPos, RotationManager.Priority.HIGH);
        }
        if (canExecuteAttack() && (maceSlot = findBestMace()) != -1) {
            if (this.preSequenceSlot == -1) {
                this.preSequenceSlot = this.f23mc.field_1724.method_31548().method_67532();
            }
            this.shouldAttackThisTick = true;
            this.targetSlotForAttack = maceSlot;
        }
    }

    private void executeAttack() {
        if (!syncToAttackSlot() || !canExecuteAttack()) {
            return;
        }
        boolean success = AttackValidator.tryAttack(this.f23mc, "combat.attack.automace");
        if (success) {
            EventBus.post(new MaceHitEvent());
            this.lastComboTime = System.currentTimeMillis();
            this.resetTimer = 5;
            return;
        }
        swapBackToPreSequence();
    }

    private void swapBackToPreSequence() {
        if (this.swapBack.isEnabled() && this.autoSwitch.isEnabled() && this.preSequenceSlot >= 0 && this.preSequenceSlot < 9) {
            this.f23mc.field_1724.method_31548().method_61496(this.preSequenceSlot);
        }
        this.preSequenceSlot = -1;
    }

    private void resetSlot() {
        if (this.autoSwitch.isEnabled() && this.originalSlot >= 0 && this.originalSlot < 9) {
            this.f23mc.field_1724.method_31548().method_61496(this.originalSlot);
        }
    }

    private void handleResetSequence() {
        this.resetTimer--;
        double maxRange = getTrackingRange(this.currentTarget);
        if (this.currentTarget != null && this.currentTarget.method_5805() && this.f23mc.field_1724.method_5739(this.currentTarget) <= maxRange && shouldRotate()) {
            class_243 aimPos = getAimPos(this.currentTarget);
            applyAimRotation(aimPos, RotationManager.Priority.HIGH);
        }
        if (this.resetTimer <= 0) {
            swapBackToPreSequence();
            stopAiming();
        }
    }

    private boolean isHoldingWeapon() {
        if (this.f23mc.field_1724 == null) {
            return false;
        }
        class_1799 stack = this.f23mc.field_1724.method_6047();
        return (stack.method_7909() instanceof class_9362) || stack.method_31573(class_3489.field_42612) || stack.method_31573(class_3489.field_42611);
    }

    private boolean isTargetBlocking(class_1657 target) {
        if (target == null) {
            return false;
        }
        if (target.method_6039()) {
            return true;
        }
        if (!target.method_6115()) {
            return false;
        }
        class_1799 active = target.method_6030();
        return !active.method_7960() && (active.method_7909() instanceof class_1819);
    }

    private boolean isTargetInHorizontalFov(class_1657 target) {
        if (this.f23mc.field_1724 == null || target == null || !this.f23mc.field_1724.method_6057(target)) {
            return false;
        }
        class_243 eyePos = this.f23mc.field_1724.method_33571();
        class_243 center = target.method_5829().method_1005();
        double dx = center.field_1352 - eyePos.field_1352;
        double dz = center.field_1350 - eyePos.field_1350;
        if ((dx * dx) + (dz * dz) <= 1.0E-6d) {
            return true;
        }
        float targetYaw = ((float) Math.toDegrees(Math.atan2(dz, dx))) - 90.0f;
        float yawDiff = Math.abs(class_3532.method_15393(this.f23mc.field_1724.method_36454() - targetYaw));
        double fov = class_3532.method_15350(((Integer) this.f23mc.field_1690.method_41808().method_41753()).intValue(), 30.0d, 170.0d);
        return ((double) yawDiff) <= fov * 0.5d;
    }

    private int findBestMace() {
        int densityLevel;
        int bestSlot = -1;
        int maxDensity = -1;
        for (int i = 0; i < 9; i++) {
            class_1799 stack = this.f23mc.field_1724.method_31548().method_5438(i);
            if ((stack.method_7909() instanceof class_9362) && (densityLevel = getDensityLevel(stack)) > maxDensity) {
                maxDensity = densityLevel;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    private int getDensityLevel(class_1799 stack) {
        class_9304 enchantments;
        if (stack.method_7960() || (enchantments = (class_9304) stack.method_58694(class_9334.field_49633)) == null) {
            return 0;
        }
        for (class_6880<class_1887> entry : enchantments.method_57534()) {
            if (entry.method_40230().isPresent()) {
                String id = ((class_5321) entry.method_40230().get()).method_29177().method_12832();
                if (id.contains("density")) {
                    return enchantments.method_57536(entry);
                }
            }
        }
        return 0;
    }

    private int findAxe() {
        for (int i = 0; i < 9; i++) {
            if (this.f23mc.field_1724.method_31548().method_5438(i).method_7909() instanceof class_1743) {
                return i;
            }
        }
        return -1;
    }

    private void stopAiming() {
        RotationManager.stop(this);
        this.currentTarget = null;
        this.maceClicksLeft = 0;
        this.shouldAttackThisTick = false;
        this.shouldBreakShield = false;
        this.shouldMaceSmash = false;
        this.targetSlotForAttack = -1;
        this.originalSlot = -1;
    }

    private boolean syncToAttackSlot() {
        if (this.f23mc.field_1724 == null) {
            return false;
        }
        if (!this.autoSwitch.isEnabled() || this.targetSlotForAttack < 0 || this.targetSlotForAttack > 8) {
            return true;
        }
        int selected = this.f23mc.field_1724.method_31548().method_67532();
        if (selected != this.targetSlotForAttack) {
            this.f23mc.field_1724.method_31548().method_61496(this.targetSlotForAttack);
            return true;
        }
        return true;
    }

    private boolean isWithinLegitReach(class_1297 target, double range) {
        if (this.f23mc.field_1724 == null || target == null) {
            return false;
        }
        class_243 eyePos = this.f23mc.field_1724.method_33571();
        class_238 box = target.method_5829();
        double clampedX = class_3532.method_15350(eyePos.field_1352, box.field_1323, box.field_1320);
        double clampedY = class_3532.method_15350(eyePos.field_1351, box.field_1322, box.field_1325);
        double clampedZ = class_3532.method_15350(eyePos.field_1350, box.field_1321, box.field_1324);
        double maxRange = Math.max(0.0d, range);
        return eyePos.method_1028(clampedX, clampedY, clampedZ) <= maxRange * maxRange;
    }

    private void applyAimRotation(class_243 aimPos, RotationManager.Priority priority) {
        if (this.f23mc.field_1724 == null) {
            return;
        }
        if (this.currentTarget != null && shouldStopAim(this.currentTarget, getEffectiveAttackRange())) {
            RotationManager.clearTarget(this);
        } else if (isHorizontalMode()) {
            RotationManager.setRotationSupplier(this, priority, () -> {
                if (this.f23mc.field_1724 == null || this.currentTarget == null) {
                    return null;
                }
                class_243 liveAim = getAimPos(this.currentTarget);
                return new class_243(liveAim.field_1352, this.f23mc.field_1724.method_23320(), liveAim.field_1350);
            }, this.rotationSpeed.getValue(), RotationManager.RotationMode.SMOOTH, 0.0d, false, true);
        } else {
            RotationManager.setRotationSupplier(this, priority, () -> {
                if (this.currentTarget != null) {
                    return getAimPos(this.currentTarget);
                }
                return null;
            }, this.rotationSpeed.getValue(), RotationManager.RotationMode.SMOOTH, 0.0d, false, false);
        }
    }

    private boolean shouldStopAim(class_1297 target, double reachDistance) {
        if (this.f23mc.field_1724 == null || target == null || this.f23mc.field_1724.method_5739(target) > reachDistance) {
            return false;
        }
        String mode = this.stopAim.getCurrentMode();
        if (mode.equals("Exact Center")) {
            if (isHorizontalMode() && !isCrosshairOnTarget(target)) {
                return false;
            }
            return isAimingAtCenter(target);
        }
        return isOnHitboxWithAccuracy(target);
    }

    private boolean isCrosshairOnTarget(class_1297 target) {
        class_3966 class_3966Var = this.f23mc.field_1765;
        if (class_3966Var instanceof class_3966) {
            class_3966 ehr = class_3966Var;
            if (ehr.method_17782() == target) {
                return true;
            }
        }
        return false;
    }

    private boolean isAimingAtCenter(class_1297 target) {
        if (this.f23mc.field_1724 == null || target == null) {
            return false;
        }
        class_243 center = target.method_5829().method_1005();
        float[] required = RotationManager.calculateRotationsToPos(center, RotationManager.getFinalYaw());
        float yawDiff = Math.abs(class_3532.method_15393(RotationManager.getFinalYaw() - required[0]));
        if (isHorizontalMode()) {
            return yawDiff <= 1.0f;
        }
        float pitchDiff = Math.abs(class_3532.method_15393(RotationManager.getFinalPitch() - required[1]));
        return yawDiff <= 1.0f && pitchDiff <= 1.0f;
    }

    private boolean isOnHitboxWithAccuracy(class_1297 target) {
        if (this.f23mc.field_1724 == null || target == null || !isCrosshairOnTarget(target)) {
            return false;
        }
        class_243 eyePos = this.f23mc.field_1724.method_33571();
        class_238 box = target.method_5829();
        class_243 center = box.method_1005();
        float[] centerRot = RotationManager.calculateRotationsToPos(center, RotationManager.getFinalYaw());
        double yawDiff = Math.abs(class_3532.method_15393(RotationManager.getFinalYaw() - centerRot[0]));
        double dx = center.field_1352 - eyePos.field_1352;
        double dz = center.field_1350 - eyePos.field_1350;
        double horizontalDist = Math.sqrt((dx * dx) + (dz * dz));
        double safeDist = Math.max(horizontalDist, 0.001d);
        double halfWidth = Math.max(box.method_17939(), box.method_17941()) * 0.5d;
        double yawHalfSpan = Math.toDegrees(Math.atan2(Math.max(halfWidth, 0.001d), safeDist));
        double yawInside = 1.0d - Math.min(1.0d, yawDiff / Math.max(yawHalfSpan, 0.001d));
        double requiredInside = class_3532.method_15350(this.hitboxAccuracy.getValue(), 0.0d, 1.0d);
        if (isHorizontalMode()) {
            return yawInside >= requiredInside;
        }
        double verticalDist = Math.sqrt((dx * dx) + (dz * dz));
        double safeVertical = Math.max(verticalDist, 0.001d);
        double halfHeight = box.method_17940() * 0.5d;
        double pitchHalfSpan = Math.toDegrees(Math.atan2(Math.max(halfHeight, 0.001d), safeVertical));
        double pitchDiff = Math.abs(class_3532.method_15393(RotationManager.getFinalPitch() - centerRot[1]));
        double pitchInside = 1.0d - Math.min(1.0d, pitchDiff / Math.max(pitchHalfSpan, 0.001d));
        return Math.min(yawInside, pitchInside) >= requiredInside;
    }

    private double getBaseRange() {
        return this.aimRange.getValue() > 0.0d ? this.aimRange.getValue() : this.swingRange.getValue();
    }

    private double getEffectiveAttackRange() {
        return Math.min(this.swingRange.getValue(), ATTACK_RANGE);
    }

    private double getTrackingRange(class_1657 target) {
        double maxRange = getBaseRange();
        if (!isHorizontalMode() || target == null || this.f23mc.field_1724 == null || this.aimInAir.getValue() <= 0.0d) {
            return maxRange;
        }
        boolean inAir = isInAirForHorizontalAssist();
        boolean belowTarget = this.f23mc.field_1724.method_23318() < target.method_23318();
        if (inAir && belowTarget) {
            return maxRange;
        }
        return maxRange;
    }

    private boolean isInAirForHorizontalAssist() {
        return (this.f23mc.field_1724 == null || this.f23mc.field_1724.method_24828() || this.f23mc.field_1724.field_6017 <= 0.0d) ? false : true;
    }

    private class_1657 findTarget() {
        if (this.f23mc.field_1687 == null || this.f23mc.field_1724 == null) {
            return null;
        }
        class_746 class_746Var = null;
        double bestDistSq = Double.MAX_VALUE;
        double range = getBaseRange();
        for (class_746 class_746Var2 : this.f23mc.field_1687.method_18456()) {
            if (class_746Var2 != this.f23mc.field_1724 && (!FriendManager.getInstance().isFriend(class_746Var2.method_5477().getString()) || !this.ignoreFriends.isEnabled())) {
                if (!this.targetMode.isEnabled() || TargetManager.isLocked(class_746Var2)) {
                    if (class_746Var2.method_5805()) {
                        double distSq = this.f23mc.field_1724.method_5858(class_746Var2);
                        if (distSq <= range * range && distSq < bestDistSq) {
                            bestDistSq = distSq;
                            class_746Var = class_746Var2;
                        }
                    }
                }
            }
        }
        return class_746Var;
    }

    private void renderPredictions(class_4587 matrices, float tickDelta) {
        if (this.currentTarget == null) {
            return;
        }
        class_243 pos = this.currentTarget.method_30950(tickDelta);
        class_238 box = this.currentTarget.method_5829().method_997(pos.method_1020(new class_243(this.currentTarget.method_23317(), this.currentTarget.method_23318(), this.currentTarget.method_23321())));
        RenderUtils.drawBox(matrices, this.f23mc.method_22940().method_23000(), box, new Color(255, 0, 0), 0.4f, false);
    }
}
