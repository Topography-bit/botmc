package macro.topography;

import macro.topography.mixin.client.MinecraftClientInvoker;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Clean autopilot: Pure Pursuit + combat aim + frame-rate critically damped spring.
 *
 * Architecture:
 *   TICK (20 Hz) — compute goalYaw/goalPitch from path+mob, handle keys/combat.
 *   FRAME (60-144 Hz) — step spring toward goal, apply rotation directly.
 *
 * The spring runs every frame with real dt, producing genuinely smooth
 * continuous rotation. No tick-boundary artifacts, no interpolation hacks.
 */
public final class Autopilot {

    private Autopilot() {}

    /* ══════════════════════════════════════════════════════════════
     *  TUNING CONSTANTS
     * ══════════════════════════════════════════════════════════════ */

    // ── Pure Pursuit ────────────────────────────────────────────
    // hSpeed = blocks/tick. Speed 500 ≈ 1.4 bl/tick = 28 bl/s.
    private static final double LOOKAHEAD_MIN = 3.0;
    private static final double LOOKAHEAD_MAX = 12.0;
    private static final double LOOKAHEAD_SPEED_K = 5.0;        // 1.4×5=7, total≈10

    // ── Sprint control ──────────────────────────────────────────
    private static final double SPRINT_ANGLE_THRESH = 25.0;
    private static final double SPRINT_LOOKAHEAD_DIST = 15.0;
    private static final double SPRINT_DELTA_YAW_MAX = 30.0;

    // ── Combat ──────────────────────────────────────────────────
    private static final double ATTACK_RANGE       = 3.8;
    private static final double PRE_AIM_RANGE      = 16.0;
    private static final double BRAKE_RANGE        = 6.0;
    private static final double COMBAT_CHASE_RANGE = 2.5;
    private static final double AIM_BLEND_FAR      = 0.3;
    private static final int    ATTACK_CD_MIN      = 0;
    private static final int    ATTACK_CD_MAX      = 1;
    private static final double ATTACK_AIM_TOLERANCE = 12.0;
    private static final int    COMBAT_STRAFE_MIN  = 10;
    private static final int    COMBAT_STRAFE_MAX  = 30;

    // ── Spring with human-like dynamics ─────────────────────────
    //    Runs at FRAME RATE (60-144 Hz).
    //    Dynamic ω: fast flick for large errors, gentle hold for small.
    //    Slight under-damping on big moves → natural overshoot.
    //    Slow-varying speed modulation → not always same pace.
    //    Rare micro-impulses → occasional tiny hand-tremor jerks.
    private static final double NAV_YAW_OMEGA      = 5.0;       // base ω for navigation
    private static final double COMBAT_YAW_OMEGA   = 8.0;       // base ω for combat
    private static final double NAV_PITCH_OMEGA     = 4.0;
    private static final double COMBAT_PITCH_OMEGA  = 8.0;
    private static final double MAX_TURN_DEG_PER_SEC = 500.0;

    // Dynamic ω scaling based on error size
    private static final double FLICK_THRESHOLD  = 25.0;   // deg — above this = quick flick
    private static final double HOLD_THRESHOLD   = 5.0;    // deg — below this = gentle hold
    private static final double FLICK_OMEGA_MULT = 1.6;    // ω multiplier for flicks
    private static final double HOLD_OMEGA_MULT  = 0.5;    // ω multiplier for holding

    // Under-damping for large moves (ζ < 1 → slight overshoot then correct)
    private static final double UNDERDAMP_THRESHOLD = 15.0;  // deg — above this = under-damp
    private static final double UNDERDAMP_ZETA      = 0.82;  // damping ratio (1.0=critical, 0.82→~5% overshoot)

    // Slow-varying speed modulation (aperiodic via two incommensurate sines)
    private static final double SPEED_MOD_AMP1 = 0.12;     // ±12% variation
    private static final double SPEED_MOD_FREQ1 = 1.7;     // rad/s (period ~3.7s)
    private static final double SPEED_MOD_AMP2 = 0.08;     // ±8% variation
    private static final double SPEED_MOD_FREQ2 = 0.6;     // rad/s (period ~10.5s)

    // Micro-impulses (hand tremor)
    private static final double IMPULSE_CHANCE_PER_SEC = 0.5;  // ~once every 2 seconds
    private static final double IMPULSE_YAW_STRENGTH   = 12.0; // deg/s velocity kick
    private static final double IMPULSE_PITCH_STRENGTH = 5.0;  // deg/s (pitch tremor is smaller)

    // ── Stuck detection ─────────────────────────────────────────
    private static final int    STUCK_THRESHOLD = 25;
    private static final double STUCK_MOVE_SQ   = 0.01;

    // ── Human-like behavioral constants ───────────────────────────
    // Sprint timing: humans don't sprint the instant they start moving
    private static final int    SPRINT_DELAY_MIN     = 4;   // ticks after W press
    private static final int    SPRINT_DELAY_MAX     = 9;
    private static final int    SPRINT_GAP_MIN_TICKS = 2;   // brief sprint release
    private static final int    SPRINT_GAP_MAX_TICKS = 4;
    private static final int    SPRINT_RUN_MIN       = 80;  // ticks between gaps
    private static final int    SPRINT_RUN_MAX       = 240;

    // Turn slowdown: humans release W at sharp corners
    private static final double TURN_SLOW_ANGLE      = 45.0;  // degrees
    private static final double TURN_SLOW_CHANCE      = 0.15;  // per-tick probability
    private static final int    TURN_SLOW_MIN_TICKS   = 1;
    private static final int    TURN_SLOW_MAX_TICKS   = 3;

    // Navigation strafe: humans press A/D for small course corrections
    private static final double NAV_STRAFE_MIN_ERR   = 3.0;   // degrees — below this, no strafe
    private static final double NAV_STRAFE_MAX_ERR   = 15.0;  // degrees — above this, turn camera instead
    private static final double NAV_STRAFE_CHANCE    = 0.06;  // per-tick probability
    private static final int    NAV_STRAFE_MIN_TICKS = 3;
    private static final int    NAV_STRAFE_MAX_TICKS = 8;

    // Idle head scanning: humans look around when running straight
    private static final int    IDLE_SCAN_AFTER      = 30;    // ticks of straight running
    private static final double IDLE_SCAN_AMP        = 3.5;   // degrees max drift

    // Combat reaction: humans don't attack the frame a mob enters range
    private static final int    REACTION_MIN_TICKS   = 3;     // 150ms
    private static final int    REACTION_MAX_TICKS   = 7;     // 350ms

    // W micro-releases: humans' fingers aren't perfectly still
    private static final double W_MICRORELEASE_CHANCE = 0.004; // per-tick (~once per 12.5 sec)

    /* ══════════════════════════════════════════════════════════════
     *  STATE
     * ══════════════════════════════════════════════════════════════ */

    private static boolean enabled = false;
    private static int     targetMode = 0;
    private static volatile boolean limboDetected = false;
    private static final Random rng = new Random();

    // Goals (set per tick, consumed per frame)
    private static double goalYaw = 0, goalPitch = 0;
    private static boolean inCombat = false;

    // Frame-rate spring state
    private static double springYawPos = 0, springYawVel = 0;
    private static double springPitchPos = 0, springPitchVel = 0;
    private static long   lastFrameNano = 0;

    // Combat
    private static int attackCooldown = 0;
    private static int combatStrafeDir = 1;
    private static int combatStrafeTicks = 0;

    // Stuck
    private static Vec3d lastPos = null;
    private static int   stuckTicks = 0;

    // ── Human-like behavioral state ──────────────────────────────
    // Sprint timing
    private static boolean wasMovingForward = false;
    private static int sprintDelayRemaining = 0;
    private static int continuousSprintTicks = 0;
    private static int sprintGapRemaining = 0;
    private static int nextSprintGapAt = 0;

    // Turn slowdown
    private static int wReleaseRemaining = 0;

    // Navigation strafe
    private static int navStrafeDir = 0;    // -1=left, 0=none, 1=right
    private static int navStrafeTicks = 0;

    // Idle head scanning
    private static int lowErrorTicks = 0;

    // Combat reaction delay
    private static UUID lastCombatTargetId = null;
    private static int combatReactionRemaining = 0;

    /* ══════════════════════════════════════════════════════════════
     *  PUBLIC API
     * ══════════════════════════════════════════════════════════════ */

    public static boolean isEnabled()      { return enabled; }
    public static int     getTargetMode()  { return targetMode; }

    /** Called from ClientPlayNetworkHandlerMixin when a chat message arrives. */
    public static void onChatMessage(String message) {
        if (!enabled) return;
        String lower = message.toLowerCase();
        if (lower.contains("limbo") || lower.contains("you were spawned in limbo")
                || lower.contains("afk") || lower.contains("you are afk")) {
            limboDetected = true;
        }
    }

    public static void start(int mode) {
        targetMode = mode;
        reset();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.player != null) {
            springYawPos   = mc.player.getYaw();
            springPitchPos = mc.player.getPitch();
            goalYaw   = springYawPos;
            goalPitch = springPitchPos;
        }
        springYawVel   = 0;
        springPitchVel = 0;
        lastFrameNano  = System.nanoTime();
        Pathfinder.reset();
        enabled = true;
        System.out.println("[Autopilot] Started (mode " + mode + ")");
    }

    public static void stop() {
        enabled = false;
        limboDetected = false;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null) releaseAllKeys(mc);
        Pathfinder.stop();
        System.out.println("[Autopilot] Stopped");
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!enabled || client.player == null || client.world == null) return;
            onTick(client);
        });
    }

    /** Called every frame from GameRendererMixin — this is where rotation happens. */
    public static void onFrameRender() {
        if (!enabled) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) onFrame(mc.player);
    }

    /* ══════════════════════════════════════════════════════════════
     *  TICK  (20 Hz) — compute goals & keys, NO rotation applied
     * ══════════════════════════════════════════════════════════════ */

    private static void onTick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) return;

        // Limbo detection: chat message or sudden location change
        if (limboDetected) {
            limboDetected = false;
            System.out.println("[Autopilot] Limbo detected via chat, stopping macro → /lobby");
            int mode = targetMode;
            stop();
            ReconnectManager.startPostJoinFlow(mode, true);
            return;
        }

        Vec3d  pos    = player.getEntityPos();
        double hSpeed = player.getVelocity().horizontalLength();

        // Update pathfinder
        Pathfinder.update(client, player, targetMode, targetMode,
            1.0f, 1.0f, 0.0f, 0.0f, 0.0f);

        List<BlockPos> path  = Pathfinder.getCurrentPath();
        int            wpIdx = Pathfinder.getWaypointIndex();
        LivingEntity   mob   = Pathfinder.getTargetMob();
        boolean hasPath = path != null && !path.isEmpty() && wpIdx < path.size();

        /* ── Pure Pursuit steering → goalYaw ─────────────────────── */
        double newGoalYaw = springYawPos; // default: hold current direction

        if (hasPath) {
            double L = Math.min(LOOKAHEAD_MAX, LOOKAHEAD_MIN + hSpeed * LOOKAHEAD_SPEED_K);
            Vec3d pursuitTarget = findPursuitPoint(path, wpIdx, pos, L);
            newGoalYaw = yawTo(pos, pursuitTarget);
        }

        /* ── Combat aim blending ─────────────────────────────────── */
        inCombat = false;
        double  mobDist  = Double.MAX_VALUE;
        Vec3d   mobPos   = null;

        if (mob != null && mob.isAlive()) {
            mobPos  = mob.getEntityPos().add(0, mob.getHeight() * 0.5, 0);
            mobDist = pos.distanceTo(mob.getEntityPos());

            if (mobDist < PRE_AIM_RANGE) {
                double mobYaw = yawTo(pos, mobPos);
                if (mobDist < ATTACK_RANGE) {
                    inCombat = true;
                    newGoalYaw = mobYaw;
                } else {
                    double t = 1.0 - clamp01((mobDist - ATTACK_RANGE) / (PRE_AIM_RANGE - ATTACK_RANGE));
                    newGoalYaw = lerpAngle(newGoalYaw, mobYaw, t * AIM_BLEND_FAR);
                }
            }
        }

        goalYaw = newGoalYaw;
        Pathfinder.setFreezeWaypoint(inCombat);

        /* ── Idle head scanning ────────────────────────────────────
         *  When running straight for 1.5s+, add subtle head drift.
         *  Simulates human looking around while running.
         *  Applied to GOAL (not output) so spring still smooths it. */
        double springErrorNow = Math.abs(MathHelper.wrapDegrees((float)(goalYaw - springYawPos)));
        if (springErrorNow < 8.0 && !inCombat && hasPath) {
            lowErrorTicks++;
        } else {
            lowErrorTicks = 0;
        }
        if (lowErrorTicks > IDLE_SCAN_AFTER && !inCombat) {
            double t = lowErrorTicks / 20.0; // seconds
            // Two incommensurate frequencies → non-repeating pattern
            double drift = IDLE_SCAN_AMP * Math.sin(t * 1.9)
                         + IDLE_SCAN_AMP * 0.4 * Math.sin(t * 0.7);
            goalYaw += drift;
        }

        /* ── Pitch goal ──────────────────────────────────────────── */
        double newGoalPitch = 0;

        if (hasPath && wpIdx + 1 < path.size()) {
            BlockPos wpCur   = path.get(wpIdx);
            BlockPos wpAhead = path.get(Math.min(wpIdx + 2, path.size() - 1));
            double dy = wpAhead.getY() - wpCur.getY();
            double dh = horizontalDist(wpCur, wpAhead);
            if (dh > 0.5) {
                newGoalPitch = -Math.toDegrees(Math.atan2(dy, dh)) * 0.3;
            }
        }

        if (mob != null && mob.isAlive() && mobPos != null && mobDist < PRE_AIM_RANGE) {
            double dy = mobPos.y - (pos.y + player.getStandingEyeHeight());
            double dh = Math.sqrt(sq(mobPos.x - pos.x) + sq(mobPos.z - pos.z));
            if (dh > 0.1) {
                double mobPitch = -Math.toDegrees(Math.atan2(dy, dh));
                double blend = 1.0 - clamp01((mobDist - ATTACK_RANGE) / (PRE_AIM_RANGE - ATTACK_RANGE));
                newGoalPitch = newGoalPitch * (1.0 - blend) + mobPitch * blend;
            }
        }

        goalPitch = newGoalPitch;

        /* ── Sprint control (uses spring state, not frame interpolation) ── */
        // How far the spring still needs to turn
        double springError = Math.abs(MathHelper.wrapDegrees((float)(goalYaw - springYawPos)));

        boolean shouldSprint = true;
        if (hasPath) {
            double turnAhead = computeTurnAngleAhead(path, wpIdx, pos, SPRINT_LOOKAHEAD_DIST);
            if (turnAhead > SPRINT_ANGLE_THRESH) shouldSprint = false;
        }
        if (springError > SPRINT_DELTA_YAW_MAX) shouldSprint = false;
        if (inCombat) shouldSprint = false;
        if (mob != null && mob.isAlive() && mobDist < BRAKE_RANGE) shouldSprint = false;

        /* ── Sprint timing (human-like) ────────────────────────────
         *  1. Delay sprint 4-9 ticks after starting forward movement
         *  2. Occasional 2-4 tick gaps every 80-240 ticks of sprinting */
        boolean forwardNow = hasPath && (!inCombat || (inCombat && mobDist > COMBAT_CHASE_RANGE));
        if (forwardNow && !wasMovingForward) {
            // Just started moving — delay sprint like a human pressing shift after W
            sprintDelayRemaining = SPRINT_DELAY_MIN
                + rng.nextInt(SPRINT_DELAY_MAX - SPRINT_DELAY_MIN + 1);
            continuousSprintTicks = 0;
            nextSprintGapAt = SPRINT_RUN_MIN
                + rng.nextInt(SPRINT_RUN_MAX - SPRINT_RUN_MIN + 1);
        }
        wasMovingForward = forwardNow;

        if (sprintDelayRemaining > 0) {
            sprintDelayRemaining--;
            shouldSprint = false;
        }

        if (shouldSprint && forwardNow) {
            if (sprintGapRemaining > 0) {
                // In a brief sprint gap (finger slipped off shift)
                sprintGapRemaining--;
                shouldSprint = false;
            } else {
                continuousSprintTicks++;
                if (continuousSprintTicks >= nextSprintGapAt) {
                    sprintGapRemaining = SPRINT_GAP_MIN_TICKS
                        + rng.nextInt(SPRINT_GAP_MAX_TICKS - SPRINT_GAP_MIN_TICKS + 1);
                    nextSprintGapAt = SPRINT_RUN_MIN
                        + rng.nextInt(SPRINT_RUN_MAX - SPRINT_RUN_MIN + 1);
                    continuousSprintTicks = 0;
                }
            }
        } else if (!forwardNow) {
            continuousSprintTicks = 0;
        }

        /* ── Combat actions ──────────────────────────────────────── */
        boolean attack = false;
        if (attackCooldown > 0) attackCooldown--;

        double aimErrorToMob = (inCombat && mobPos != null)
            ? Math.abs(MathHelper.wrapDegrees((float)(yawTo(pos, mobPos) - springYawPos)))
            : 999;

        if (inCombat && attackCooldown <= 0 && aimErrorToMob < ATTACK_AIM_TOLERANCE) {
            attack = true;
            attackCooldown = ATTACK_CD_MIN + rng.nextInt(ATTACK_CD_MAX - ATTACK_CD_MIN + 1);
        }

        /* ── Combat reaction delay ─────────────────────────────────
         *  Humans need 150-350ms to react to a new target entering range.
         *  First attack on a new mob is delayed. */
        if (mob != null && mob.isAlive()) {
            UUID mobId = mob.getUuid();
            if (lastCombatTargetId == null || !lastCombatTargetId.equals(mobId)) {
                lastCombatTargetId = mobId;
                combatReactionRemaining = REACTION_MIN_TICKS
                    + rng.nextInt(REACTION_MAX_TICKS - REACTION_MIN_TICKS + 1);
            }
        } else {
            lastCombatTargetId = null;
        }
        if (combatReactionRemaining > 0) {
            combatReactionRemaining--;
            attack = false; // suppress attack during reaction window
        }

        /* ── Combat strafe ───────────────────────────────────────── */
        boolean strafeLeft = false, strafeRight = false;

        if (inCombat && mobDist < COMBAT_CHASE_RANGE) {
            combatStrafeTicks--;
            if (combatStrafeTicks <= 0) {
                combatStrafeDir = -combatStrafeDir;
                combatStrafeTicks = COMBAT_STRAFE_MIN
                    + rng.nextInt(COMBAT_STRAFE_MAX - COMBAT_STRAFE_MIN + 1);
            }
            if (combatStrafeDir > 0) strafeRight = true;
            else                     strafeLeft  = true;
        }

        /* ── Path-following jump ────────────────────────────────────
         *  If the next waypoint is above us, we need to jump.
         *  Also jump if there's a 1-block step-up in the next 2 waypoints. */
        boolean jump = false;
        if (hasPath && !inCombat && player.isOnGround()) {
            for (int look = 0; look < 3 && wpIdx + look < path.size(); look++) {
                BlockPos wp = path.get(wpIdx + look);
                double wpY = wp.getY();
                double playerY = player.getBlockPos().getY();
                if (wpY > playerY + 0.4) {
                    jump = true;
                    break;
                }
            }
        }

        /* ── Stuck detection ─────────────────────────────────────── */
        if (lastPos != null && pos.squaredDistanceTo(lastPos) < STUCK_MOVE_SQ && !inCombat) {
            stuckTicks++;
            if (stuckTicks > STUCK_THRESHOLD) {
                double kickDeg = (rng.nextBoolean() ? 1 : -1) * (30 + rng.nextDouble() * 30);
                goalYaw = springYawPos + kickDeg;
                jump = true;
                stuckTicks = 0;
            }
        } else {
            stuckTicks = 0;
        }
        lastPos = pos;

        /* ── Apply keys (rotation is applied in onFrame) ─────────── */
        boolean combatChase = inCombat && mobDist > COMBAT_CHASE_RANGE;
        boolean forward = hasPath && (!inCombat || combatChase);

        /* ── Turn slowdown ─────────────────────────────────────────
         *  Humans slow down at sharp corners. Brief W release. */
        if (wReleaseRemaining > 0) {
            wReleaseRemaining--;
            forward = false;
        } else if (hasPath && forward && !inCombat) {
            double upcomingTurn = computeTurnAngleAhead(path, wpIdx, pos, 4.0);
            if (upcomingTurn > TURN_SLOW_ANGLE && rng.nextDouble() < TURN_SLOW_CHANCE) {
                wReleaseRemaining = TURN_SLOW_MIN_TICKS
                    + rng.nextInt(TURN_SLOW_MAX_TICKS - TURN_SLOW_MIN_TICKS + 1);
                forward = false;
            }
        }

        /* ── W micro-release ───────────────────────────────────────
         *  Very rare 1-tick W release. Human fingers aren't perfectly still. */
        if (forward && !inCombat && rng.nextDouble() < W_MICRORELEASE_CHANCE) {
            forward = false;
        }

        /* ── Navigation strafe ─────────────────────────────────────
         *  For small yaw errors (3-15°), occasionally strafe instead of
         *  turning the camera. This is how humans make minor corrections. */
        boolean navLeft = false, navRight = false;
        if (navStrafeTicks > 0) {
            navStrafeTicks--;
            if (navStrafeDir < 0) navLeft = true;
            else if (navStrafeDir > 0) navRight = true;
        } else if (forward && !inCombat) {
            double yawErr = MathHelper.wrapDegrees((float)(goalYaw - springYawPos));
            double absErr = Math.abs(yawErr);
            if (absErr > NAV_STRAFE_MIN_ERR && absErr < NAV_STRAFE_MAX_ERR
                && rng.nextDouble() < NAV_STRAFE_CHANCE) {
                // Strafe toward the goal: error>0 = goal is right → strafe right
                navStrafeDir = yawErr > 0 ? 1 : -1;
                navStrafeTicks = NAV_STRAFE_MIN_TICKS
                    + rng.nextInt(NAV_STRAFE_MAX_TICKS - NAV_STRAFE_MIN_TICKS + 1);
                if (navStrafeDir < 0) navLeft = true;
                else navRight = true;
            }
        }

        GameOptions o = client.options;
        o.forwardKey .setPressed(forward);
        o.backKey    .setPressed(false);
        o.leftKey    .setPressed(strafeLeft || navLeft);
        o.rightKey   .setPressed(strafeRight || navRight);
        o.jumpKey    .setPressed(jump);
        o.sprintKey  .setPressed(shouldSprint && forward);

        if (attack) {
            ((MinecraftClientInvoker) client).invokeDoAttack();
        }
    }

    /* ══════════════════════════════════════════════════════════════
     *  FRAME  (60-144 Hz) — human-like spring dynamics
     *
     *  Instead of constant critically-damped spring (robotic):
     *  - Large error → high ω, slight under-damping (quick flick + overshoot)
     *  - Small error → low ω (gentle hold, no rush)
     *  - Slow speed modulation (variable pace, not always same speed)
     *  - Rare micro-impulses (hand tremor moments)
     * ══════════════════════════════════════════════════════════════ */

    private static void onFrame(ClientPlayerEntity player) {
        long now = System.nanoTime();
        double dt = (now - lastFrameNano) / 1_000_000_000.0;
        lastFrameNano = now;

        if (dt <= 0 || dt > 0.1) dt = 0.016;

        double timeS = now / 1_000_000_000.0;

        // ── Base omega from combat state ────────────────────────
        double baseYawOmega   = inCombat ? COMBAT_YAW_OMEGA   : NAV_YAW_OMEGA;
        double basePitchOmega = inCombat ? COMBAT_PITCH_OMEGA  : NAV_PITCH_OMEGA;

        // ── YAW: dynamic omega based on error magnitude ─────────
        double yawError = MathHelper.wrapDegrees((float)(goalYaw - springYawPos));
        double absYawError = Math.abs(yawError);

        double yawOmega = baseYawOmega;
        if (absYawError > FLICK_THRESHOLD) {
            // Large error: quick ballistic flick
            yawOmega *= FLICK_OMEGA_MULT;
        } else if (absYawError < HOLD_THRESHOLD) {
            // Small error: gentle corrective hold
            yawOmega *= HOLD_OMEGA_MULT;
        }
        // Smooth transition between zones (no hard boundary)
        // Already handled: flick/hold only at extremes, middle = base omega

        // ── Slow speed modulation (quasi-random pace variation) ──
        double speedMod = 1.0
            + SPEED_MOD_AMP1 * Math.sin(timeS * SPEED_MOD_FREQ1)
            + SPEED_MOD_AMP2 * Math.sin(timeS * SPEED_MOD_FREQ2);
        yawOmega *= speedMod;
        double pitchOmega = basePitchOmega * speedMod;

        // ── Damping: under-damp large moves for natural overshoot ─
        //    Standard: accel = ω²·error − 2ζω·vel  (ζ=1 → critical)
        //    Under-damp: ζ=0.82 → ~5% overshoot on flick, then settle
        double yawZeta  = (absYawError > UNDERDAMP_THRESHOLD) ? UNDERDAMP_ZETA : 1.0;
        double pitchZeta = 1.0; // pitch always critically damped

        // ── Step yaw spring ─────────────────────────────────────
        double yawAccel = yawOmega * yawOmega * yawError
                        - 2.0 * yawZeta * yawOmega * springYawVel;
        springYawVel += yawAccel * dt;
        springYawPos += springYawVel * dt;

        // ── Step pitch spring ───────────────────────────────────
        double pitchError = goalPitch - springPitchPos;
        double pitchAccel = pitchOmega * pitchOmega * pitchError
                          - 2.0 * pitchZeta * pitchOmega * springPitchVel;
        springPitchVel += pitchAccel * dt;
        springPitchPos += springPitchVel * dt;
        springPitchPos = Math.max(-90, Math.min(90, springPitchPos));

        // ── Micro-impulses (rare hand tremor) ───────────────────
        //    Probability per frame = chance_per_sec × dt
        //    Spring damping absorbs the kick within ~0.3s
        if (rng.nextDouble() < IMPULSE_CHANCE_PER_SEC * dt) {
            springYawVel   += rng.nextGaussian() * IMPULSE_YAW_STRENGTH;
            springPitchVel += rng.nextGaussian() * IMPULSE_PITCH_STRENGTH;
        }

        // ── Hard cap turn rate ──────────────────────────────────
        double maxDeg = MAX_TURN_DEG_PER_SEC * dt;
        double yawDelta = MathHelper.wrapDegrees((float)(springYawPos - player.getYaw()));
        if (Math.abs(yawDelta) > maxDeg) {
            yawDelta = Math.signum(yawDelta) * maxDeg;
            springYawPos = player.getYaw() + yawDelta;
            springYawVel = Math.signum(springYawVel)
                * Math.min(Math.abs(springYawVel), MAX_TURN_DEG_PER_SEC);
        }

        // ── Apply ───────────────────────────────────────────────
        player.setYaw((float) springYawPos);
        player.headYaw = (float) springYawPos;
        player.setPitch(MathHelper.clamp((float) springPitchPos, -90f, 90f));
    }

    /* ══════════════════════════════════════════════════════════════
     *  PURE PURSUIT
     * ══════════════════════════════════════════════════════════════ */

    private static Vec3d findPursuitPoint(List<BlockPos> path, int wpIdx, Vec3d playerPos, double L) {
        double remaining = L;
        Vec3d prev = playerPos;

        for (int i = wpIdx; i < path.size(); i++) {
            Vec3d wp = Vec3d.ofCenter(path.get(i));
            double seg = prev.distanceTo(wp);
            if (seg >= remaining && seg > 0.001) {
                double ratio = remaining / seg;
                return prev.add(wp.subtract(prev).multiply(ratio));
            }
            remaining -= seg;
            prev = wp;
        }

        return Vec3d.ofCenter(path.get(path.size() - 1));
    }

    /* ══════════════════════════════════════════════════════════════
     *  HELPERS
     * ══════════════════════════════════════════════════════════════ */

    private static double computeTurnAngleAhead(List<BlockPos> path, int wpIdx,
                                                 Vec3d playerPos, double dist) {
        if (wpIdx + 2 >= path.size()) return 0;

        Vec3d wpCur = Vec3d.ofCenter(path.get(wpIdx));
        double yaw1 = yawTo(playerPos, wpCur);

        double accumulated = 0;
        Vec3d prev = wpCur;
        Vec3d far = null;
        for (int i = wpIdx + 1; i < path.size(); i++) {
            Vec3d next = Vec3d.ofCenter(path.get(i));
            accumulated += prev.distanceTo(next);
            if (accumulated >= dist) {
                far = next;
                break;
            }
            prev = next;
        }
        if (far == null) return 0;

        double yaw2 = yawTo(playerPos, far);
        return Math.abs(MathHelper.wrapDegrees((float)(yaw2 - yaw1)));
    }

    private static double yawTo(Vec3d from, Vec3d to) {
        return -Math.toDegrees(Math.atan2(to.x - from.x, to.z - from.z));
    }

    private static double lerpAngle(double from, double to, double t) {
        return from + MathHelper.wrapDegrees((float)(to - from)) * t;
    }

    private static double horizontalDist(BlockPos a, BlockPos b) {
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static double sq(double x) { return x * x; }

    private static double clamp01(double v) { return Math.max(0, Math.min(1, v)); }

    private static void releaseAllKeys(MinecraftClient client) {
        GameOptions o = client.options;
        o.forwardKey .setPressed(false);
        o.backKey    .setPressed(false);
        o.leftKey    .setPressed(false);
        o.rightKey   .setPressed(false);
        o.jumpKey    .setPressed(false);
        o.sprintKey  .setPressed(false);
        o.attackKey  .setPressed(false);
        o.useKey     .setPressed(false);
    }

    private static void reset() {
        attackCooldown     = 0;
        combatStrafeDir    = rng.nextBoolean() ? 1 : -1;
        combatStrafeTicks  = COMBAT_STRAFE_MIN + rng.nextInt(COMBAT_STRAFE_MAX - COMBAT_STRAFE_MIN + 1);
        stuckTicks         = 0;
        lastPos            = null;
        inCombat           = false;
        // Human-like behavioral state
        wasMovingForward       = false;
        sprintDelayRemaining   = 0;
        continuousSprintTicks  = 0;
        sprintGapRemaining     = 0;
        nextSprintGapAt        = SPRINT_RUN_MIN + rng.nextInt(SPRINT_RUN_MAX - SPRINT_RUN_MIN + 1);
        wReleaseRemaining      = 0;
        navStrafeDir           = 0;
        navStrafeTicks         = 0;
        lowErrorTicks          = 0;
        lastCombatTargetId     = null;
        combatReactionRemaining = 0;
    }
}
