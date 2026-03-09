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

/**
 * Rule-based autopilot that follows Pathfinder waypoints with human-like behavior.
 * Uses a layer stack architecture:
 *   L1  Pure Pursuit steering
 *   L2  Combat aim blending
 *   L3  Ornstein-Uhlenbeck yaw noise
 *   L4  Head scanning
 *   L5  Sprint control
 *   L6  Micro-pauses
 *   L7  Jump scheduler (random + bhop)
 *   L8  Combat actions (attack + fidget)
 *   L9  Strafe correction
 *   L10 Stuck detection
 */
public final class Autopilot {

    private Autopilot() {}

    /* ══════════════════════════════════════════════════════════════
     *  TUNING CONSTANTS
     * ══════════════════════════════════════════════════════════════ */

    // ── Pure Pursuit ────────────────────────────────────────────
    private static final double LOOKAHEAD_MIN = 5.0;
    private static final double LOOKAHEAD_MAX = 20.0;          // speed 500 covers ~28 bl/s
    private static final double LOOKAHEAD_SPEED_K = 0.6;       // scale by hSpeed: 28*0.6=16.8

    // ── Ornstein-Uhlenbeck yaw noise ────────────────────────────
    private static final double OU_THETA = 2.5;    // mean-reversion rate
    private static final double OU_SIGMA = 1.5;    // noise amplitude (lowered — no vibration)
    private static final double OU_DT    = 0.05;   // tick = 50 ms

    // ── Sprint control ──────────────────────────────────────────
    private static final double SPRINT_ANGLE_THRESH = 35.0;      // deg — release sprint above this
    private static final double SPRINT_LOOKAHEAD_DIST = 12.0;    // blocks ahead to check turn (speed 500)
    private static final double SPRINT_DELTA_YAW_MAX = 45.0;     // deg — too much turning → no sprint

    // ── Jumps ───────────────────────────────────────────────────
    private static final double RANDOM_JUMP_CHANCE = 0.0015;     // per tick → ~3 %/s
    private static final double BHOP_CHANCE         = 0.004;     // per tick on flat sprint
    private static final int    BHOP_SERIES_MIN     = 2;
    private static final int    BHOP_SERIES_MAX     = 5;

    // ── Combat ──────────────────────────────────────────────────
    private static final double ATTACK_RANGE       = 3.8;
    private static final double PRE_AIM_RANGE      = 16.0;       // start aiming earlier at high speed
    private static final double BRAKE_RANGE        = 6.0;        // stop sprint to not overshoot mob
    private static final double COMBAT_CHASE_RANGE = 2.5;        // closer than this → stop W, just strafe
    private static final double AIM_BLEND_FAR      = 0.3;        // blend factor at pre-aim edge
    private static final int    ATTACK_CD_MIN      = 0;          // ticks — speed 500: every tick counts
    private static final int    ATTACK_CD_MAX      = 1;
    private static final double ATTACK_AIM_TOLERANCE = 12.0;     // deg — tight for raycast hits
    private static final double FIDGET_SWING_CHANCE = 0.0005;    // per tick → ~1 %/s
    private static final int    COMBAT_STRAFE_MIN  = 10;         // ticks min in one direction
    private static final int    COMBAT_STRAFE_MAX  = 30;         // ticks max
    private static final double COMBAT_PITCH_OMEGA = 14.0;       // faster pitch in combat, but not twitchy

    // ── Fall: look at landing point ─────────────────────────────
    private static final double FALL_SPEED_THRESH  = -0.3;       // vel.y to trigger fall aim
    private static final double FALL_AIM_BLEND     = 0.8;        // how much to override yaw/pitch toward landing

    // ── Head scanning ───────────────────────────────────────────
    private static final int    SCAN_INTERVAL_MIN = 100;         // 5 s
    private static final int    SCAN_INTERVAL_MAX = 300;         // 15 s
    private static final double SCAN_YAW_AMP      = 35.0;       // max degrees
    private static final int    SCAN_DURATION     = 25;          // ticks (~1.25 s)

    // ── Micro-pauses ────────────────────────────────────────────
    private static final double PAUSE_CHANCE = 0.001;            // per tick → ~2 %/s
    private static final int    PAUSE_MIN    = 3;                // ticks
    private static final int    PAUSE_MAX    = 12;

    // ── Pitch noise ─────────────────────────────────────────────
    private static final double PITCH_WAVE1_FREQ = 0.030;
    private static final double PITCH_WAVE1_AMP  = 2.0;
    private static final double PITCH_WAVE2_FREQ = 0.007;
    private static final double PITCH_WAVE2_AMP  = 3.5;
    private static final double PITCH_SLOPE_K    = 0.3;         // pitch follows path slope

    // ── Critically damped spring (mouse smoothing) ──────────────
    //    ω = natural frequency: higher → snappier, lower → lazier
    //    Critical damping: ζ = 1 → fastest convergence without oscillation
    //    Semi-implicit Euler: stable for ω·dt < 2 (max ω = 40 at 20Hz)
    private static final double YAW_OMEGA        = 10.0;        // snappy yaw for speed 500
    private static final double PITCH_OMEGA       = 7.0;       // faster pitch tracking
    private static final double SPRING_DT         = 0.05;       // tick = 50 ms
    private static final double MAX_TURN_RATE     = 55.0;       // deg/tick cap (~1100°/s) for speed 500

    // ── Strafe correction ───────────────────────────────────────
    private static final double STRAFE_ANGLE_MAX  = 20.0;       // degrees — wider for speed 500
    private static final double STRAFE_ANGLE_MIN  = 3.0;

    // ── Stuck detection ─────────────────────────────────────────
    private static final int    STUCK_THRESHOLD = 20;            // ticks stationary
    private static final double STUCK_MOVE_SQ   = 0.01;         // squared dist threshold

    // ── Frame interpolation ─────────────────────────────────────
    private static final float  TICK_NS = 50_000_000f;           // 50 ms in nanos
    private static final float  MICRO_JITTER = 0.3f;             // degrees

    /* ══════════════════════════════════════════════════════════════
     *  STATE
     * ══════════════════════════════════════════════════════════════ */

    private static boolean enabled = false;
    private static int     targetMode = 0;
    private static final Random rng = new Random();
    private static long tickCount = 0;

    // Rotation (sub-tick interpolation)
    private static float  targetYaw, targetPitch;
    private static float  startYaw,  startPitch;
    private static long   tickStartNano;
    private static boolean hasTarget = false;

    // OU process
    private static double ouYaw = 0.0;

    // Critically damped spring
    private static double springYawPos = 0, springYawVel = 0;
    private static double springPitchPos = 0, springPitchVel = 0;

    // Scan
    private static int    scanCooldown = 150;
    private static int    scanTicksLeft = 0;
    private static double scanYawTarget = 0.0;

    // Pause
    private static int pauseTicksLeft = 0;

    // Bhop
    private static int bhopTicksLeft = 0;

    // Combat
    private static int attackCooldown = 0;
    private static int combatStrafeDir = 1;     // +1 = right, -1 = left
    private static int combatStrafeTicks = 0;   // ticks until direction switch

    // Stuck
    private static Vec3d lastPos = null;
    private static int   stuckTicks = 0;

    /* ══════════════════════════════════════════════════════════════
     *  PUBLIC API
     * ══════════════════════════════════════════════════════════════ */

    public static boolean isEnabled()      { return enabled; }
    public static int     getTargetMode()  { return targetMode; }

    public static void start(int mode) {
        targetMode = mode;
        reset();
        // Initialize spring positions from current player facing
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.player != null) {
            springYawPos   = mc.player.getYaw();
            springPitchPos = mc.player.getPitch();
        }
        springYawVel   = 0;
        springPitchVel = 0;
        Pathfinder.reset();
        enabled = true;
        System.out.println("[Autopilot] Started (mode " + mode + ")");
    }

    public static void stop() {
        enabled = false;
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

    /** Called every frame from GameRendererMixin for smooth rotation. */
    public static void onFrameRender() {
        if (!enabled || !hasTarget) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) onFrame(mc.player);
    }

    /* ══════════════════════════════════════════════════════════════
     *  TICK  (20 Hz)
     * ══════════════════════════════════════════════════════════════ */

    private static void onTick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) return;

        tickCount++;
        tickStartNano = System.nanoTime();
        startYaw   = player.getYaw();
        startPitch = player.getPitch();

        Vec3d  pos    = player.getEntityPos();
        double hSpeed = player.getVelocity().horizontalLength();

        // Update pathfinder (drives waypoint advance, mob targeting, replanning)
        // Autopilot doesn't compute anomaly features — pass safe defaults
        Pathfinder.update(client, player, targetMode, targetMode,
            1.0f,   // distToWall — pretend far from walls
            1.0f,   // verticalClearance — pretend lots of headroom
            0.0f,   // stressLevel
            0.0f,   // posAnomaly
            0.0f    // velAnomaly
        );

        // Read path state
        List<BlockPos> path  = Pathfinder.getCurrentPath();
        int            wpIdx = Pathfinder.getWaypointIndex();
        LivingEntity   mob   = Pathfinder.getTargetMob();
        boolean hasPath = path != null && !path.isEmpty() && wpIdx < path.size();

        /* ── L1: Pure Pursuit steering ─────────────────────────── */
        double goalYaw = player.getYaw();    // raw desired direction (before spring)
        Vec3d pursuitTarget = null;

        if (hasPath) {
            double L = Math.min(LOOKAHEAD_MAX, LOOKAHEAD_MIN + hSpeed * LOOKAHEAD_SPEED_K);
            pursuitTarget = findPursuitPoint(path, wpIdx, pos, L);
            goalYaw = yawTo(pos, pursuitTarget);
        }

        /* ── L2: Combat aim blending ───────────────────────────── */
        boolean inCombat = false;
        double  mobDist  = Double.MAX_VALUE;
        Vec3d   mobPos   = null;

        if (mob != null && mob.isAlive()) {
            mobPos  = mob.getEntityPos().add(0, mob.getHeight() * 0.5, 0);
            mobDist = pos.distanceTo(mob.getEntityPos());

            if (mobDist < PRE_AIM_RANGE) {
                double mobYaw = yawTo(pos, mobPos);
                if (mobDist < ATTACK_RANGE) {
                    // In melee range: aim 100% at mob, stop running, strafe
                    inCombat = true;
                    goalYaw = mobYaw;
                } else {
                    // Approaching: gradually blend toward mob
                    double t = 1.0 - clamp01((mobDist - ATTACK_RANGE) / (PRE_AIM_RANGE - ATTACK_RANGE));
                    goalYaw = lerpAngle(goalYaw, mobYaw, t * AIM_BLEND_FAR);
                }
            }
        }

        // Freeze waypoint advance while fighting — don't let path pull past mob
        Pathfinder.setFreezeWaypoint(inCombat);

        /* ── L3: OU-process yaw noise ──────────────────────────── */
        ouYaw += OU_THETA * (0 - ouYaw) * OU_DT
               + OU_SIGMA * Math.sqrt(OU_DT) * rng.nextGaussian();
        goalYaw += ouYaw * (inCombat ? 0.0 : 1.0);

        /* ── L4: Head scanning ─────────────────────────────────── */
        if (scanTicksLeft > 0) {
            double progress = 1.0 - (double) scanTicksLeft / SCAN_DURATION;
            goalYaw += Math.sin(progress * Math.PI) * scanYawTarget;
            scanTicksLeft--;
        } else {
            scanCooldown--;
            if (scanCooldown <= 0 && !inCombat) {
                scanTicksLeft = SCAN_DURATION;
                scanYawTarget = (rng.nextBoolean() ? 1 : -1)
                    * (SCAN_YAW_AMP * 0.5 + rng.nextDouble() * SCAN_YAW_AMP * 0.5);
                scanCooldown = SCAN_INTERVAL_MIN
                    + rng.nextInt(SCAN_INTERVAL_MAX - SCAN_INTERVAL_MIN);
            }
        }

        /* ── Pitch goal ────────────────────────────────────────── */
        double goalPitch = 0;

        // Follow path slope
        if (hasPath && wpIdx + 1 < path.size()) {
            BlockPos wpCur   = path.get(wpIdx);
            BlockPos wpAhead = path.get(Math.min(wpIdx + 2, path.size() - 1));
            double dy = wpAhead.getY() - wpCur.getY();
            double dh = horizontalDist(wpCur, wpAhead);
            if (dh > 0.5) {
                goalPitch = -Math.toDegrees(Math.atan2(dy, dh)) * PITCH_SLOPE_K;
            }
        }

        // Aim at mob center — blend gradually across pre-aim range (no sudden snap)
        if (mob != null && mob.isAlive() && mobPos != null && mobDist < PRE_AIM_RANGE) {
            double dy = mobPos.y - (pos.y + player.getStandingEyeHeight());
            double dh = Math.sqrt(sq(mobPos.x - pos.x) + sq(mobPos.z - pos.z));
            if (dh > 0.1) {
                double mobPitch = -Math.toDegrees(Math.atan2(dy, dh));
                // Smooth blend: 0 at PRE_AIM edge → 1.0 at ATTACK_RANGE
                double blend = 1.0 - clamp01((mobDist - ATTACK_RANGE) / (PRE_AIM_RANGE - ATTACK_RANGE));
                goalPitch = goalPitch * (1.0 - blend) + mobPitch * blend;
            }
        }

        // When falling: aim at landing waypoint (both yaw + pitch)
        double velY = player.getVelocity().y;
        boolean falling = !player.isOnGround() && velY < FALL_SPEED_THRESH;
        if (falling && hasPath) {
            // Find the next waypoint that's at or below the player (landing target)
            Vec3d landingTarget = findLandingTarget(path, wpIdx, pos);
            if (landingTarget != null) {
                double landYaw = yawTo(pos, landingTarget);
                Vec3d eyePos = pos.add(0, player.getStandingEyeHeight(), 0);
                double ddy = landingTarget.y - eyePos.y;
                double ddh = Math.sqrt(sq(landingTarget.x - eyePos.x) + sq(landingTarget.z - eyePos.z));
                double landPitch = (ddh > 0.3)
                    ? -Math.toDegrees(Math.atan2(ddy, ddh))
                    : 45.0; // straight down if directly below

                // Override both yaw and pitch toward landing point
                goalYaw   = lerpAngle(goalYaw, landYaw, FALL_AIM_BLEND);
                goalPitch = goalPitch * (1.0 - FALL_AIM_BLEND) + landPitch * FALL_AIM_BLEND;
            }
        }

        // Layered sine noise (off in combat, reduced during fall)
        double noiseScale = inCombat ? 0.0 : (falling ? 0.3 : 1.0);
        goalPitch += (Math.sin(tickCount * PITCH_WAVE1_FREQ) * PITCH_WAVE1_AMP
                    + Math.sin(tickCount * PITCH_WAVE2_FREQ) * PITCH_WAVE2_AMP) * noiseScale;

        /* ══════════════════════════════════════════════════════════
         *  CRITICALLY DAMPED SPRING — smooths raw goals into output
         *  Eliminates tick-to-tick jitter, gives natural acceleration
         *  and deceleration like a human hand on a mouse.
         * ══════════════════════════════════════════════════════════ */
        stepYawSpring(goalYaw);
        stepPitchSpring(goalPitch, inCombat ? COMBAT_PITCH_OMEGA : PITCH_OMEGA);

        // Safety: cap turn rate (handles teleports, extreme stuck turns)
        double yawDelta = MathHelper.wrapDegrees((float)(springYawPos - startYaw));
        if (Math.abs(yawDelta) > MAX_TURN_RATE) {
            yawDelta = Math.signum(yawDelta) * MAX_TURN_RATE;
            // Also cap spring velocity so it doesn't accumulate
            springYawVel = Math.signum(springYawVel)
                * Math.min(Math.abs(springYawVel), MAX_TURN_RATE / SPRING_DT);
        }

        /* ── Delta yaw (from spring output, not raw goal) ──────── */
        double deltaYaw = yawDelta;

        /* ── L5: Sprint control ────────────────────────────────── */
        boolean shouldSprint = true;
        if (hasPath) {
            double turnAhead = computeTurnAngleAhead(path, wpIdx, pos, SPRINT_LOOKAHEAD_DIST);
            if (turnAhead > SPRINT_ANGLE_THRESH) shouldSprint = false;
        }
        if (Math.abs(deltaYaw) > SPRINT_DELTA_YAW_MAX) shouldSprint = false;
        if (inCombat)                                    shouldSprint = false;
        if (mob != null && mob.isAlive() && mobDist < BRAKE_RANGE) shouldSprint = false; // brake before mob
        if (pauseTicksLeft > 0)                          shouldSprint = false;

        /* ── L6: Micro-pauses ──────────────────────────────────── */
        boolean paused = false;
        if (pauseTicksLeft > 0) {
            pauseTicksLeft--;
            paused = true;
        } else if (!inCombat && rng.nextDouble() < PAUSE_CHANCE) {
            pauseTicksLeft = PAUSE_MIN + rng.nextInt(PAUSE_MAX - PAUSE_MIN + 1);
            paused = true;
        }

        /* ── L7: Jump scheduler ────────────────────────────────── */
        boolean jump = false;

        // Bhop series
        if (bhopTicksLeft > 0) {
            jump = player.isOnGround();
            bhopTicksLeft--;
        } else if (hasPath && shouldSprint && player.isOnGround() && !paused) {
            if (isPathFlat(path, wpIdx, 4) && rng.nextDouble() < BHOP_CHANCE) {
                bhopTicksLeft = BHOP_SERIES_MIN + rng.nextInt(BHOP_SERIES_MAX - BHOP_SERIES_MIN + 1);
                jump = true;
            }
        }

        // Random single jump
        if (!jump && !paused && player.isOnGround() && rng.nextDouble() < RANDOM_JUMP_CHANCE) {
            jump = true;
        }

        /* ── L8: Combat actions ────────────────────────────────── */
        boolean attack = false;
        if (attackCooldown > 0) attackCooldown--;

        // Check actual angle between player facing and mob — NOT the spring delta
        double aimErrorToMob = (inCombat && mobPos != null)
            ? Math.abs(MathHelper.wrapDegrees((float)(yawTo(pos, mobPos) - player.getYaw())))
            : 999;

        if (inCombat && attackCooldown <= 0 && aimErrorToMob < ATTACK_AIM_TOLERANCE) {
            attack = true;
            attackCooldown = ATTACK_CD_MIN + rng.nextInt(ATTACK_CD_MAX - ATTACK_CD_MIN + 1);
        }

        // Fidget swing
        if (!attack && !inCombat && rng.nextDouble() < FIDGET_SWING_CHANCE) {
            attack = true;
        }

        /* ── L9: Strafe correction / combat strafe ─────────────── */
        boolean strafeLeft = false, strafeRight = false;

        if (inCombat && mobDist < COMBAT_CHASE_RANGE) {
            // Only strafe when very close — at speed 500, strafe from far = orbit
            combatStrafeTicks--;
            if (combatStrafeTicks <= 0) {
                combatStrafeDir = -combatStrafeDir;
                combatStrafeTicks = COMBAT_STRAFE_MIN
                    + rng.nextInt(COMBAT_STRAFE_MAX - COMBAT_STRAFE_MIN + 1);
            }
            if (combatStrafeDir > 0) strafeRight = true;
            else                     strafeLeft  = true;
        } else if (hasPath
                && Math.abs(deltaYaw) > STRAFE_ANGLE_MIN
                && Math.abs(deltaYaw) < STRAFE_ANGLE_MAX) {
            if (deltaYaw > 0) strafeRight = true;
            else              strafeLeft  = true;
        }

        /* ── L10: Stuck detection ──────────────────────────────── */
        if (lastPos != null && pos.squaredDistanceTo(lastPos) < STUCK_MOVE_SQ && !paused) {
            stuckTicks++;
            if (stuckTicks > STUCK_THRESHOLD) {
                // Impulse the spring — immediate velocity kick in random direction
                double kickDeg = (rng.nextBoolean() ? 1 : -1) * (45 + rng.nextDouble() * 45);
                springYawPos = player.getYaw() + kickDeg;
                springYawVel = kickDeg / SPRING_DT;
                jump = true;
                stuckTicks = 0;
            }
        } else {
            stuckTicks = 0;
        }
        lastPos = pos;

        /* ── Apply rotation ────────────────────────────────────── */
        targetYaw   = (float)(startYaw + yawDelta);
        targetPitch = MathHelper.clamp((float) springPitchPos, -90f, 90f);
        hasTarget   = true;

        /* ── Apply keys ────────────────────────────────────────── */
        // In combat: walk forward to close gap if mob is far, stop when close enough
        boolean combatChase = inCombat && mobDist > COMBAT_CHASE_RANGE;
        boolean forward = hasPath && !paused && (!inCombat || combatChase);
        GameOptions o = client.options;
        o.forwardKey .setPressed(forward);
        o.backKey    .setPressed(false);
        o.leftKey    .setPressed(strafeLeft);
        o.rightKey   .setPressed(strafeRight);
        o.jumpKey    .setPressed(jump);
        o.sprintKey  .setPressed(shouldSprint && forward);

        /* ── Apply attack ──────────────────────────────────────── */
        if (attack) {
            ((MinecraftClientInvoker) client).invokeDoAttack();
        }
    }

    /* ══════════════════════════════════════════════════════════════
     *  FRAME  (FPS rate — smooth rotation)
     * ══════════════════════════════════════════════════════════════ */

    private static void onFrame(ClientPlayerEntity player) {
        long elapsed = System.nanoTime() - tickStartNano;
        float t = Math.min(1f, elapsed / TICK_NS);

        // Quadratic ease-out
        float e = 1f - (1f - t) * (1f - t);

        // Micro-jitter
        float jitter = (rng.nextFloat() - 0.5f) * MICRO_JITTER;

        float yaw   = lerpAngle(startYaw, targetYaw, e) + jitter;
        float pitch = MathHelper.clamp(
            startPitch + MathHelper.wrapDegrees(targetPitch - startPitch) * e + jitter * 0.5f,
            -90f, 90f
        );

        player.setYaw(yaw);
        player.headYaw = yaw;
        player.setPitch(pitch);
    }

    /* ══════════════════════════════════════════════════════════════
     *  PURE PURSUIT
     * ══════════════════════════════════════════════════════════════ */

    /**
     * Walk along the path from the current waypoint, accumulating distance,
     * and return the interpolated point at the lookahead distance L.
     */
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
     *  CRITICALLY DAMPED SPRING
     *
     *  Second-order system with ζ = 1 (critical damping).
     *  Semi-implicit Euler integration (velocity first, then position).
     *  Stable for ω·dt < 2   (ω=12, dt=0.05 → 0.6 < 2 ✓).
     *
     *  Equations:
     *    accel = ω² · error − 2ω · vel     (spring force − damping)
     *    vel  += accel · dt                  (semi-implicit: vel updated first)
     *    pos  += vel · dt                    (then pos uses new vel)
     * ══════════════════════════════════════════════════════════════ */

    private static void stepYawSpring(double target) {
        double error = MathHelper.wrapDegrees((float)(target - springYawPos));
        double accel = YAW_OMEGA * YAW_OMEGA * error - 2.0 * YAW_OMEGA * springYawVel;
        springYawVel += accel * SPRING_DT;
        springYawPos += springYawVel * SPRING_DT;
    }

    private static void stepPitchSpring(double target, double omega) {
        double error = target - springPitchPos;
        double accel = omega * omega * error - 2.0 * omega * springPitchVel;
        springPitchVel += accel * SPRING_DT;
        springPitchPos += springPitchVel * SPRING_DT;
        springPitchPos = Math.max(-90, Math.min(90, springPitchPos));
    }

    /* ══════════════════════════════════════════════════════════════
     *  HELPERS
     * ══════════════════════════════════════════════════════════════ */

    /**
     * Find the best waypoint to aim at during a fall.
     * Searches forward from current waypoint for a point that's below the player
     * (the expected landing area). Returns the closest such waypoint center.
     */
    private static Vec3d findLandingTarget(List<BlockPos> path, int wpIdx, Vec3d playerPos) {
        // Look for the first waypoint that's at or below the player
        Vec3d best = null;
        double bestDistSq = Double.MAX_VALUE;

        int searchEnd = Math.min(path.size(), wpIdx + 15); // don't search too far ahead
        for (int i = wpIdx; i < searchEnd; i++) {
            BlockPos wp = path.get(i);
            if (wp.getY() < playerPos.y - 1.0) {
                // Below player — candidate landing point
                Vec3d wpCenter = Vec3d.ofCenter(wp);
                double dSq = playerPos.squaredDistanceTo(wpCenter);
                if (dSq < bestDistSq) {
                    bestDistSq = dSq;
                    best = wpCenter;
                }
            }
        }

        // If no waypoint below, use the next waypoint anyway (shallow fall)
        if (best == null && wpIdx < path.size()) {
            best = Vec3d.ofCenter(path.get(Math.min(wpIdx + 1, path.size() - 1)));
        }

        return best;
    }

    /** Compute the total turn angle at a lookahead distance along the path. */
    private static double computeTurnAngleAhead(List<BlockPos> path, int wpIdx,
                                                 Vec3d playerPos, double dist) {
        if (wpIdx + 2 >= path.size()) return 0;

        // Direction from player to current waypoint
        Vec3d wpCur = Vec3d.ofCenter(path.get(wpIdx));
        double yaw1 = yawTo(playerPos, wpCur);

        // Walk along path to find a point 'dist' blocks ahead
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

    /** Check if next 'count' waypoints share the same Y level. */
    private static boolean isPathFlat(List<BlockPos> path, int wpIdx, int count) {
        if (wpIdx >= path.size()) return false;
        int y   = path.get(wpIdx).getY();
        int end = Math.min(path.size(), wpIdx + count);
        for (int i = wpIdx + 1; i < end; i++) {
            if (path.get(i).getY() != y) return false;
        }
        return true;
    }

    /** Minecraft yaw from 'from' to 'to' (degrees, south = 0, west = 90). */
    private static double yawTo(Vec3d from, Vec3d to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        return -Math.toDegrees(Math.atan2(dx, dz));
    }

    /** Lerp between two angles, handling wrapping. */
    private static float lerpAngle(float from, float to, float t) {
        return from + MathHelper.wrapDegrees(to - from) * t;
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
        tickCount      = 0;
        ouYaw          = 0;
        scanCooldown   = 100 + rng.nextInt(100);
        scanTicksLeft  = 0;
        pauseTicksLeft = 0;
        bhopTicksLeft  = 0;
        attackCooldown     = 0;
        combatStrafeDir    = rng.nextBoolean() ? 1 : -1;
        combatStrafeTicks  = COMBAT_STRAFE_MIN + rng.nextInt(COMBAT_STRAFE_MAX - COMBAT_STRAFE_MIN + 1);
        stuckTicks         = 0;
        lastPos        = null;
        hasTarget      = false;
    }
}
