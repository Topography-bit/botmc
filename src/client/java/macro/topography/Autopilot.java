package macro.topography;

import macro.topography.mixin.client.MinecraftClientInvoker;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.hit.HitResult;
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
    private static final double COMBAT_CHASE_RANGE = 2.0;  // walk forward until this close
    private static final double DIRECT_PURSUIT_RANGE = 8.0; // within this: ignore path, walk straight at mob
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
    private static final double NAV_YAW_OMEGA      = 7.0;       // base ω for navigation
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

    // ── Saccade: minimum-jerk trajectory for large moves ──────
    //    Replaces spring for >18° moves. Bell-shaped velocity profile
    //    matching real human motor control (Flash & Hogan 1985).
    //    Spring is fundamentally wrong for large moves — exponential
    //    approach vs. the real S-curve with peak velocity at ~40%.
    private static final double SACCADE_THRESHOLD = 18.0;   // deg — above this, use min-jerk
    private static final double SACCADE_FITTS_A   = 0.12;   // base movement time (s)
    private static final double SACCADE_FITTS_B   = 0.008;  // seconds per degree of rotation
    private static final double SACCADE_TIME_VAR  = 0.20;   // ±20% movement time variability

    // ── Physiological tremor (narrow-band ~10Hz) ──────────────
    //    Continuous oscillation matching real hand tremor spectrum.
    //    NOT random impulses — real tremor is autocorrelated 8-12Hz
    //    with slowly drifting amplitude and frequency.
    private static final double TREMOR_YAW_AMP    = 0.08;   // degrees base amplitude
    private static final double TREMOR_PITCH_AMP  = 0.05;   // degrees (pitch tremor smaller)
    private static final double TREMOR_YAW_FREQ   = 10.0;   // Hz center frequency
    private static final double TREMOR_PITCH_FREQ = 9.3;    // Hz (slightly different — decorrelated)

    // ── Goal smoothing (visual processing delay ~100ms) ───────
    //    Simulates the ~100ms visual-cortex latency before the hand
    //    reacts to a change in the target position.
    private static final double GOAL_LAG_TAU      = 0.10;   // seconds (100ms time constant)

    // ── Speed modulation (1/f noise, not periodic sines) ──────
    //    Value noise at two octaves → aperiodic biological variation.
    //    No detectable frequency peaks under Fourier analysis.
    private static final double SPEED_NOISE_AMP1  = 0.12;   // ±12% slow drift
    private static final double SPEED_NOISE_FREQ1 = 0.4;    // Hz
    private static final double SPEED_NOISE_AMP2  = 0.06;   // ±6% faster variation
    private static final double SPEED_NOISE_FREQ2 = 0.9;    // Hz

    // ── Post-saccade corrections (discrete sub-movements) ───────
    //    Humans don't smoothly converge after a saccade. They pause
    //    50-150ms (visual evaluation), then make 1-2 small discrete
    //    corrections — each followed by another brief pause.
    private static final double CORRECTION_PAUSE_MIN  = 0.06;  // first pause after saccade (s)
    private static final double CORRECTION_PAUSE_MAX  = 0.16;
    private static final double CORRECTION_INTER_MIN  = 0.04;  // inter-correction pauses (s)
    private static final double CORRECTION_INTER_MAX  = 0.10;
    private static final double CORRECTION_THRESH     = 3.0;   // deg — error below this → pause
    private static final double CORRECTION_DAMP_RATE  = 25.0;  // velocity decay during pause (1/s)

    // ── Post-saccadic oscillation (mechanical wrist resonance) ─
    //    After a fast flick, hand/wrist oscillates ~3Hz for ~250ms.
    //    Distinct from tremor — transient response to deceleration.
    private static final double POST_SACC_OSC_FREQ    = 3.2;   // Hz
    private static final double POST_SACC_OSC_DECAY   = 12.0;  // decay rate (1/s)
    private static final double POST_SACC_OSC_GAIN    = 0.0008; // amplitude = peak_vel × gain (max ~0.4°)

    // ── Attention model (slow-varying focus/alertness) ────────
    //    Modulates reaction time, omega, tremor, accuracy.
    //    Simulates natural focus fluctuations over 3-13s cycles.
    private static final double ATTENTION_MIN          = 0.78;
    private static final double ATTENTION_MAX          = 1.22;
    private static final double ATTENTION_DRIFT_RATE   = 0.02;  // convergence per tick
    private static final int    ATTENTION_CHANGE_MIN   = 60;    // ticks between re-rolls (3s)
    private static final int    ATTENTION_CHANGE_MAX   = 260;   // (13s)

    // ── Endpoint scatter (per-aim-attempt imprecision) ────────
    //    Even aiming at the same point, humans hit different angles.
    //    Re-rolled each time a new mob target is acquired.
    private static final double AIM_SCATTER_YAW_SD     = 1.2;   // degrees σ
    private static final double AIM_SCATTER_PITCH_SD   = 0.8;   // degrees σ

    // ── Mouse lift (physical mouse repositioning) ──────────────
    //    For very large rotations (>100°), humans physically lift the
    //    mouse and reposition it. Brief ~80-120ms pause mid-saccade.
    private static final double MOUSE_LIFT_THRESHOLD  = 100.0;  // deg — saccade size to trigger
    private static final double MOUSE_LIFT_TAU_START  = 0.35;   // pause starts at ~35% through saccade
    private static final double MOUSE_LIFT_TAU_END    = 0.42;   // pause ends at ~42%
    private static final double MOUSE_LIFT_PAUSE_MIN  = 0.07;   // seconds
    private static final double MOUSE_LIFT_PAUSE_MAX  = 0.12;

    // ── Correlated tremor (diagonal hand tendency) ────────────
    //    Real hand movement has off-axis coupling: when yaw trembles,
    //    pitch moves too (and vice versa). Correlation ~0.3-0.5.
    private static final double TREMOR_CORRELATION    = 0.35;   // cross-axis coupling factor

    // ── Yaw-pitch wrist coupling (diagonal mouse swipe tendency) ──
    //    When swiping mouse horizontally, wrist arc creates slight
    //    vertical component. Human data shows correlation ~ -0.6.
    //    Applied as velocity-proportional offset to pitch GOAL so it
    //    goes through the spring (smooth) and doesn't trigger hysteresis.
    //    Units: degrees of pitch goal shift per deg/s of yaw velocity.
    private static final double YAW_PITCH_COUPLING_VEL = -0.004;

    // ── Breathing rhythm (chest movement → pitch) ─────────────
    //    Ultra-subtle ~0.25Hz pitch oscillation. Always present.
    //    Amplitude varies with breathing depth (slow noise modulation).
    private static final double BREATH_FREQ           = 0.25;   // Hz (~15 breaths/min)
    private static final double BREATH_AMP            = 0.6;    // degrees base amplitude (needs >1 pixel at most sensitivities)
    private static final double BREATH_FREQ_DRIFT     = 0.04;   // Hz drift range

    // ── Fatigue model (session degradation) ───────────────────
    //    Over 10+ minutes: tremor increases, reaction slows,
    //    attention range narrows. Resets on start().
    private static final double FATIGUE_ONSET_SEC     = 600.0;  // 10 min before noticeable
    private static final double FATIGUE_MAX           = 0.35;   // max fatigue factor (0-1)
    private static final double FATIGUE_TREMOR_MULT   = 1.6;    // tremor × at max fatigue
    private static final double FATIGUE_LAG_MULT      = 1.3;    // goal lag × at max fatigue
    private static final double FATIGUE_OMEGA_MULT    = 0.85;   // omega × at max fatigue

    // ── Asymmetric overshoot (humans overshoot > undershoot) ──
    //    After saccade, add a small overshoot bias. Saccade target
    //    is nudged slightly past the real goal.
    private static final double OVERSHOOT_BIAS        = 0.04;   // 4% overshoot bias
    private static final double OVERSHOOT_VARIANCE    = 0.03;   // ±3% random variation

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
    private static final int    IDLE_SCAN_AFTER      = 15;    // ticks of straight running (0.75s)
    private static final double IDLE_SCAN_AMP        = 3.5;   // degrees max drift

    // Walking head-bob: subtle pitch oscillation from walking cadence.
    // Real humans have slight vertical camera movement when running.
    // Low frequencies prevent ±1px alternation (dithering jitter) —
    // each half-cycle spans many frames, producing smooth multi-pixel sweeps.
    private static final double HEAD_BOB_FREQ        = 1.7;   // Hz (relaxed stride rhythm)
    private static final double HEAD_BOB_AMP         = 0.55;  // degrees (~6.5 pixels per half-cycle)
    private static final double HEAD_BOB_FREQ2       = 0.6;   // Hz (slow body sway)
    private static final double HEAD_BOB_AMP2        = 0.4;   // degrees

    // Combat reaction: humans don't attack the frame a mob enters range
    private static final int    REACTION_MIN_TICKS   = 3;     // 150ms
    private static final int    REACTION_MAX_TICKS   = 7;     // 350ms

    // W micro-releases: humans' fingers aren't perfectly still
    private static final double W_MICRORELEASE_CHANCE = 0.004; // per-tick (~once per 12.5 sec)

    // ── Fall camera: human-like look-down during falls ────────────
    //    Humans instinctively look toward landing zone when falling.
    //    5 phases: pre-edge peek → reaction delay → active tracking →
    //    pre-landing brace → post-landing bounce.
    private static final double FALL_VELOCITY_THRESH  = -0.18;  // y vel to detect falling (bl/tick) — ignore stairs/slopes (~0.08)
    private static final int    FALL_REACTION_MIN     = 1;      // ticks before camera reacts (50ms)
    private static final int    FALL_REACTION_MAX     = 3;      // max reaction delay (150ms)
    private static final double FALL_MAX_PITCH        = 62.0;   // max downward pitch (humans don't look 90° down)
    private static final double FALL_RAMP_TICKS       = 3.0;    // ticks to smoothly ramp in fall-look
    private static final double FALL_BRACE_SECONDS    = 0.35;   // seconds before landing to start recovering
    private static final double FALL_BRACE_RECOVERY   = 0.20;   // how much pitch recovers before landing (0-1)
    private static final double FALL_PITCH_OMEGA      = 5.5;    // pitch spring ω during fall (fast tracking)
    private static final double FALL_PITCH_ZETA       = 0.92;   // slight underdamp for natural fall tracking
    private static final double FALL_TREMOR_MULT      = 2.2;    // hand tremor multiplier during falls (anxiety)
    private static final double FALL_BOUNCE_STRENGTH  = 2.0;    // pitch velocity kick on landing (reduced — camera stays down longer)
    private static final double FALL_LOOKAHEAD_MULT   = 1.5;    // yaw lookahead boost during long falls
    // ── Pre-edge peek: look at the EDGE, not through the floor ──
    private static final int    PRE_PEEK_SCAN_WPS     = 20;     // waypoints ahead to scan for edges
    private static final double PRE_PEEK_MIN_DROP     = 3.0;    // minimum Y drop to consider it an edge (ignore stairs)
    private static final double PRE_PEEK_START_DIST   = 12.0;   // blocks — start subtle glance-down
    private static final double PRE_PEEK_FULL_DIST    = 3.0;    // blocks — full peek at edge point
    private static final double PRE_PEEK_EARLY_PITCH  = 5.0;    // degrees — subtle initial glance (far from edge)
    private static final double PRE_PEEK_SKIP_CHANCE  = 0.15;   // 15% chance to not pre-peek (distracted)
    private static final double PRE_PEEK_DIST_VAR     = 0.20;   // ±20% variation in start distance

    /* ══════════════════════════════════════════════════════════════
     *  STATE
     * ══════════════════════════════════════════════════════════════ */

    private static boolean enabled = false;
    private static boolean pathfinderOnly = false;
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

    // Smoothed goals (visual processing delay)
    private static double smoothGoalYaw = 0, smoothGoalPitch = 0;

    // Saccade (minimum-jerk trajectory for large moves)
    private static boolean inSaccade = false;
    private static double saccadeStartYaw, saccadeStartPitch;
    private static double saccadeTargetYaw, saccadeTargetPitch;
    private static double saccadeDuration, saccadeElapsed;

    // Physiological tremor phase accumulators
    private static double tremorYawPhase = 0, tremorPitchPhase = 0;

    // Movement-to-movement variability (re-rolled per saccade/movement)
    private static double moveOmegaScale = 1.0;
    private static boolean wasInHoldZone = false;

    // Post-saccade corrections (discrete sub-movements with pauses)
    private static double postSaccadeDwell = 0;       // seconds of pause remaining
    private static int    postSaccadeCorrections = 0;  // pauses left to inject

    // Post-saccadic oscillation (transient wrist resonance after flick)
    private static double postSaccOscPhase = 0;
    private static double postSaccOscAmp = 0;
    private static double postSaccOscStartTime = 0;

    // Attention model (slow-varying focus)
    private static double attention = 1.0;
    private static double attentionTarget = 1.0;
    private static int    nextAttentionChangeTick = 0;
    private static int    tickCounter = 0;

    // Endpoint scatter (per aim attempt)
    private static double aimScatterYaw = 0;
    private static double aimScatterPitch = 0;

    // Mouse lift state (mid-saccade pause for large rotations)
    private static double mouseLiftPause = 0;
    private static boolean mouseLiftDone = false;

    // Breathing phase
    private static double breathPhase = 0;

    // Head-bob phase (walking cadence)
    private static double headBobPhase = 0;

    // Fatigue
    private static long sessionStartNano = 0;
    private static double fatigue = 0; // 0..FATIGUE_MAX

    // Mouse quantization: error diffusion carries sub-pixel remainder
    // between frames so micro-movements (tremor, breathing) accumulate
    // and eventually produce a real pixel step — like a DAC with dithering.
    private static double quantErrorYaw   = 0;
    private static double quantErrorPitch = 0;

    // Double-precision angle tracking — avoids float precision loss from
    // player.getYaw() (float). Without this, ~20% of deltas land off the
    // mouse sensitivity grid because float→double roundtrip corrupts values.
    private static double trackedYaw   = 0;
    private static double trackedPitch = 0;

    // Pitch hysteresis: suppress ±1px alternation (dithering jitter).
    // When tremor/bob oscillates near a pixel boundary, error diffusion
    // creates rapid +1/-1 alternation that looks robotic. This tracks the
    // last non-zero pitch direction to add a small hysteresis band.
    private static long lastPitchDir = 0; // +1 or -1, direction of last pitch step
    private static long lastYawDir   = 0; // +1 or -1, direction of last yaw step

    // Combat
    private static int attackCooldown = 0;
    private static int combatStrafeDir = 1;
    private static int combatStrafeTicks = 0;
    private static int consecutiveMisses = 0;         // attacks that didn't hit entity
    private static final int MISS_SCATTER_REROLL = 4;  // re-roll aimScatter after this many misses
    private static int combatStuckTicks = 0;           // ticks in combat without landing a hit
    private static final int COMBAT_STUCK_THRESHOLD = 30; // 1.5 sec → reposition
    private static final double COMBAT_REPOSITION_ANGLE = 45.0; // degrees to sidestep

    // Stuck
    private static Vec3d lastPos = null;
    private static int   stuckTicks = 0;
    private static int   stuckKicks = 0;          // how many times stuck-kick fired at same area
    private static int   verticalStuckTicks = 0;  // ticks near path end but mob far away
    private static final int STUCK_KICKS_REPATH = 2; // after 2 kicks → force repath

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

    // Fall camera
    private static boolean isFallingCamera = false;
    private static int fallingCameraTicks = 0;
    private static int fallReactionDelay = 0;
    private static Vec3d fallLandingPos = null;
    private static double fallBasePitch = 0;       // frozen base pitch at fall start
    private static double fallSmoothedPitch = 0;   // smoothed fall target (prevents jumps)

    // Post-fall recovery: after landing, camera stays somewhat down and gradually returns
    private static int postFallRecoveryTicks = 0;
    private static double postFallPitch = 0;        // pitch at moment of landing
    private static final int POST_FALL_RECOVERY_TICKS = 25; // ~1.25s to fully straighten

    // Pre-edge peek state
    private static boolean preEdgeActive = false;   // currently peeking toward edge
    private static Vec3d   preEdgePos = null;        // position of the edge waypoint
    private static double  preEdgeStartDist = 0;     // personalized start distance (with variance)
    private static boolean preEdgeSkip = false;       // this approach: skip pre-peek (distracted)
    private static double  preEdgePeekStrength = 0;   // current blend 0..1

    /* ══════════════════════════════════════════════════════════════
     *  PUBLIC API
     * ══════════════════════════════════════════════════════════════ */

    public static boolean isEnabled()      { return enabled; }
    public static boolean isPathOnly()     { return pathfinderOnly; }
    public static int     getTargetMode()  { return targetMode; }

    // ── Debug data (read by CameraDebugHud) ───────────────────
    static boolean dbg_saccadeActive;
    static double  dbg_saccadeTau;
    static double  dbg_yawVel, dbg_pitchVel;
    static double  dbg_goalYawRaw, dbg_goalPitchRaw;
    static double  dbg_goalYawSmooth, dbg_goalPitchSmooth;
    static double  dbg_springYaw, dbg_springPitch;
    static double  dbg_tremorYaw, dbg_tremorPitch;
    static double  dbg_omegaScale;
    static boolean dbg_falling;
    static double  dbg_totalError;
    static double  dbg_attention;
    static boolean dbg_inDwell;
    static int     dbg_correctionsLeft;
    static double  dbg_fatigue;
    static double  dbg_breathOffset;
    static boolean dbg_mouseLift;
    static boolean dbg_preEdge;
    static double  dbg_preEdgeStrength;
    static double  dbg_preEdgeDist;

    // ── Autopilot/Pathfinder debug (tick-rate) ──────────────────
    static boolean dbg_inCombat;
    static boolean dbg_directPursuit;
    static double  dbg_mobDist;
    static double  dbg_aimError;
    static boolean dbg_crosshairOnEntity;
    static boolean dbg_attackFired;
    static int     dbg_consecutiveMisses;
    static int     dbg_combatStuckTicks;
    static boolean dbg_forward;
    static boolean dbg_sprint;
    static boolean dbg_jump;
    static int     dbg_strafeDir;     // -1=left, 0=none, 1=right
    static int     dbg_pathLen;
    static int     dbg_wpIndex;
    static double  dbg_distToWp;
    static double  dbg_distToGoal;
    static String  dbg_repathReason;
    static int     dbg_stuckTicks;
    static double  dbg_playerX, dbg_playerY, dbg_playerZ;
    static double  dbg_mobX, dbg_mobY, dbg_mobZ;
    static String  dbg_targetName;
    static double  dbg_wpX, dbg_wpY, dbg_wpZ;          // current waypoint pos
    static double  dbg_goalYaw, dbg_currentYaw;          // where bot wants to face vs where it faces
    static double  dbg_goalPitchTick, dbg_currentPitch;   // same for pitch
    static boolean dbg_pathBlocked;                       // pathfinder thinks path is blocked ahead
    static String  dbg_lastRepathReason;                  // last reason pathfinder repathed
    static long    dbg_lastRepathMs;                      // when last repath happened

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
        pathfinderOnly = false;
        reset();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.player != null) {
            springYawPos   = mc.player.getYaw();
            springPitchPos = mc.player.getPitch();
            goalYaw   = springYawPos;
            goalPitch = springPitchPos;
            smoothGoalYaw   = springYawPos;
            smoothGoalPitch = springPitchPos;
            trackedYaw   = mc.player.getYaw();
            trackedPitch = mc.player.getPitch();
        }
        springYawVel   = 0;
        springPitchVel = 0;
        inSaccade = false;
        lastFrameNano  = System.nanoTime();
        sessionStartNano = lastFrameNano;
        Pathfinder.reset();
        enabled = true;
        System.out.println("[Autopilot] Started (mode " + mode + ")");
    }

    /** Start pathfinder only — path is computed & rendered, but bot doesn't control player. */
    public static void startPathOnly(int mode) {
        targetMode = mode;
        pathfinderOnly = true;
        Pathfinder.reset();
        enabled = true;
        System.out.println("[Autopilot] Path-only started (mode " + mode + ")");
    }

    public static void stop() {
        enabled = false;
        pathfinderOnly = false;
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
        if (!enabled || pathfinderOnly) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) onFrame(mc.player);
    }

    /* ══════════════════════════════════════════════════════════════
     *  TICK  (20 Hz) — compute goals & keys, NO rotation applied
     * ══════════════════════════════════════════════════════════════ */

    private static void onTick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) return;

        // Path-only mode: just update pathfinder, don't control player
        if (pathfinderOnly) {
            Pathfinder.update(client, player, targetMode, targetMode,
                1.0f, 1.0f, 0.0f, 0.0f, 0.0f);
            return;
        }

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

        // ── Attention model update ────────────────────────────────
        //    Slow-varying focus level that modulates all motor control.
        tickCounter++;
        if (tickCounter >= nextAttentionChangeTick) {
            attentionTarget = ATTENTION_MIN
                + rng.nextDouble() * (ATTENTION_MAX - ATTENTION_MIN);
            nextAttentionChangeTick = tickCounter + ATTENTION_CHANGE_MIN
                + rng.nextInt(ATTENTION_CHANGE_MAX - ATTENTION_CHANGE_MIN + 1);
        }
        attention += (attentionTarget - attention) * ATTENTION_DRIFT_RATE;

        // ── Fatigue model ─────────────────────────────────────────
        //    Gradual degradation over long sessions. Sigmoid curve:
        //    negligible for first 10 min, then ramps toward max.
        double sessionSec = (System.nanoTime() - sessionStartNano) / 1_000_000_000.0;
        double fatigueRaw = sessionSec / FATIGUE_ONSET_SEC - 1.0; // negative before onset
        fatigue = FATIGUE_MAX / (1.0 + Math.exp(-2.0 * fatigueRaw)); // sigmoid 0→MAX

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
            // During long falls, look further ahead (where to go after landing)
            if (isFallingCamera && fallingCameraTicks > 10) L *= FALL_LOOKAHEAD_MULT;
            Vec3d pursuitTarget = findPursuitPoint(path, wpIdx, pos, L);
            newGoalYaw = yawTo(pos, pursuitTarget);
        }

        /* ── Combat aim blending ───────────────────────────────────
         *  Run-through mode: bot sprints through mob, attacks on crosshair.
         *  No stopping, no strafing — path goes past the mob.
         *  Camera follows path (Pure Pursuit), with increasing mob aim blend. */
        inCombat = false;
        boolean directPursuit = false;
        double  mobDist  = Double.MAX_VALUE;
        Vec3d   mobPos   = null;
        double  combatPitchBlend = 0;
        double  combatMobPitch   = 0;

        if (mob != null && mob.isAlive()) {
            mobPos  = mob.getEntityPos().add(0, mob.getHeight() * 0.5, 0);
            mobDist = pos.distanceTo(mob.getEntityPos());

            if (mobDist < PRE_AIM_RANGE) {
                double mobYaw = yawTo(pos, mobPos) + aimScatterYaw;

                // Pitch to mob center
                double eyeY = pos.y + player.getStandingEyeHeight();
                double dym = mobPos.y - eyeY;
                double dhm = Math.sqrt(sq(mobPos.x - pos.x) + sq(mobPos.z - pos.z));
                combatMobPitch = (dhm > 0.1)
                    ? -Math.toDegrees(Math.atan2(dym, dhm)) + aimScatterPitch
                    : 0;

                if (mobDist < ATTACK_RANGE) {
                    inCombat = true;
                    newGoalYaw = mobYaw;
                    combatPitchBlend = 1.0;
                } else if (mobDist < DIRECT_PURSUIT_RANGE) {
                    directPursuit = true;
                    double pursuitBlend = 1.0 - clamp01((mobDist - ATTACK_RANGE) / (DIRECT_PURSUIT_RANGE - ATTACK_RANGE));
                    double blend = 0.3 + 0.7 * pursuitBlend;
                    newGoalYaw = lerpAngle(newGoalYaw, mobYaw, blend);
                    combatPitchBlend = blend;
                } else {
                    double t = 1.0 - clamp01((mobDist - ATTACK_RANGE) / (PRE_AIM_RANGE - ATTACK_RANGE));
                    newGoalYaw = lerpAngle(newGoalYaw, mobYaw, t * AIM_BLEND_FAR);
                    combatPitchBlend = t * AIM_BLEND_FAR;
                }
            }
        }

        goalYaw = newGoalYaw;
        // Don't freeze waypoint in combat — let pathfinder update as mob moves
        Pathfinder.setFreezeWaypoint(false);

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

        // Base pitch from path elevation (same as before)
        if (hasPath && wpIdx + 1 < path.size()) {
            BlockPos wpCur   = path.get(wpIdx);
            BlockPos wpAhead = path.get(Math.min(wpIdx + 2, path.size() - 1));
            double dy = wpAhead.getY() - wpCur.getY();
            double dh = horizontalDist(wpCur, wpAhead);
            if (dh > 0.5) {
                newGoalPitch = -Math.toDegrees(Math.atan2(dy, dh)) * 0.3;
            }
        }

        // Combat: blend pitch toward mob body (before fall camera overrides)
        if (combatPitchBlend > 0) {
            newGoalPitch = newGoalPitch + (combatMobPitch - newGoalPitch) * combatPitchBlend;
        }

        /* ── Fall camera system ────────────────────────────────────
         *  5-phase human-like camera during vertical drops:
         *  1. Pre-edge peek: subtle downward glance approaching an edge
         *  2. Reaction delay: 150-350ms before camera starts moving
         *  3. Active tracking: smooth pitch toward landing zone
         *  4. Pre-landing brace: pitch recovers toward horizontal
         *  5. Post-landing bounce: impact feel via spring velocity kick */
        Vec3d velocity = player.getVelocity();
        boolean playerFalling = !player.isOnGround() && velocity.y < FALL_VELOCITY_THRESH;

        if (playerFalling && !isFallingCamera) {
            // Phase 2 start: just went airborne — roll reaction delay
            isFallingCamera = true;
            fallingCameraTicks = 0;
            postFallRecoveryTicks = 0; // cancel any post-fall recovery — we're falling again
            fallBasePitch = newGoalPitch; // freeze base pitch — don't let pathfinder changes jerk camera
            fallSmoothedPitch = springPitchPos; // start smoothed target at where camera actually IS
            // If pre-edge was active, player expected this drop → normal reaction
            // If no pre-edge, it's a surprise → shorter startle reaction (1-3 ticks)
            if (preEdgeActive && preEdgePeekStrength > 0.3) {
                fallReactionDelay = FALL_REACTION_MIN
                    + rng.nextInt(FALL_REACTION_MAX - FALL_REACTION_MIN + 1);
            } else {
                // Surprise fall — startle reflex is faster
                fallReactionDelay = 1 + rng.nextInt(3); // 1-3 ticks (50-150ms)
            }
            fallLandingPos = findLandingFromPath(path, wpIdx, pos);
        }

        if (playerFalling && isFallingCamera) {
            fallingCameraTicks++;

            // Update landing estimate periodically (path may change)
            if (fallingCameraTicks % 10 == 0) {
                Vec3d updated = findLandingFromPath(path, wpIdx, pos);
                if (updated != null) fallLandingPos = updated;
            }

            if (fallingCameraTicks > fallReactionDelay) {
                // Phase 3: active fall tracking
                double fallPitch;

                if (fallLandingPos != null) {
                    // Known landing — look at it
                    Vec3d eyePos = pos.add(0, player.getStandingEyeHeight(), 0);
                    double dyToLand = fallLandingPos.y - eyePos.y;
                    double dhToLand = Math.sqrt(
                        sq(fallLandingPos.x - eyePos.x) + sq(fallLandingPos.z - eyePos.z));

                    if (dhToLand > 0.5) {
                        fallPitch = -Math.toDegrees(Math.atan2(dyToLand, dhToLand));
                    } else {
                        fallPitch = -Math.toDegrees(Math.atan2(dyToLand, Math.max(dhToLand, 1.5)));
                    }
                } else {
                    // Unknown landing (accidental fall) — look down based on
                    // velocity direction. Humans instinctively look toward
                    // where they're going. More downward velocity = more pitch.
                    double vy = velocity.y * 20.0; // blocks per second (negative = down)
                    double vhSq = sq(velocity.x) + sq(velocity.z);
                    double vh = Math.sqrt(vhSq) * 20.0;
                    fallPitch = -Math.toDegrees(Math.atan2(vy, Math.max(vh, 2.0)));
                }
                fallPitch = Math.min(fallPitch, FALL_MAX_PITCH);

                // Smooth the fall target to prevent jerks from landing pos updates.
                // Exponential smoothing with ~200ms time constant — fast enough to
                // track real falls but absorbs pathfinder recalculation jumps.
                double fallSmoothAlpha = 1.0 - Math.exp(-0.05 / 0.12); // ~0.05s per tick / 0.12s tau
                fallSmoothedPitch += (fallPitch - fallSmoothedPitch) * fallSmoothAlpha;

                // Smooth ramp-in (smoothstep curve, not linear)
                int activeTicks = fallingCameraTicks - fallReactionDelay;
                double rampIn = Math.min(1.0, activeTicks / FALL_RAMP_TICKS);
                rampIn = rampIn * rampIn * (3.0 - 2.0 * rampIn); // smoothstep

                // Phase 4: pre-landing brace — recover pitch toward horizontal
                double recovery = 0;
                if (fallLandingPos != null) {
                    double fallSpeed = -velocity.y * 20.0; // blocks per second
                    double remainingDy = Math.abs(fallLandingPos.y - pos.y);
                    double estSecsToLand = (fallSpeed > 1.0) ? remainingDy / fallSpeed : 999;

                    if (estSecsToLand < FALL_BRACE_SECONDS) {
                        recovery = 1.0 - (estSecsToLand / FALL_BRACE_SECONDS);
                        recovery = recovery * recovery; // ease-in: gentle at first, faster near end
                    }
                }

                double effectiveFallPitch = fallSmoothedPitch * (1.0 - recovery * FALL_BRACE_RECOVERY);

                // Blend over FROZEN base pitch — not live pathfinder pitch.
                // This prevents path recalculations from jerking camera mid-fall.
                newGoalPitch = fallBasePitch * (1.0 - rampIn) + effectiveFallPitch * rampIn;
            } else {
                // During reaction delay: hold frozen pitch
                newGoalPitch = fallBasePitch;
            }
        } else {
            if (isFallingCamera) {
                // Phase 5: just landed — start post-fall recovery
                // Camera stays somewhat down and gradually straightens,
                // so consecutive falls don't snap up between drops.
                springPitchVel += FALL_BOUNCE_STRENGTH;
                postFallRecoveryTicks = POST_FALL_RECOVERY_TICKS;
                postFallPitch = springPitchPos; // remember how far down we were looking
            }
            isFallingCamera = false;
            fallingCameraTicks = 0;
            fallLandingPos = null;
        }

        // Phase 1: pre-edge peek — look toward the EDGE point, not through floor
        //   Scan path ahead for a significant Y drop. If found, identify the
        //   last ground-level waypoint (the "edge"). Target gaze at that edge
        //   point — since it's at roughly player Y, pitch stays moderate (5-20°)
        //   and we never look through blocks. Fall camera takes over once airborne.
        if (!isFallingCamera && player.isOnGround() && hasPath && !inCombat) {
            // Scan path forward to find edge: last WP before Y drops ≥ PRE_PEEK_MIN_DROP
            Vec3d foundEdge = null;
            int scanEnd = Math.min(wpIdx + PRE_PEEK_SCAN_WPS, path.size());
            int edgeIdx = -1;
            for (int i = wpIdx; i < scanEnd - 1; i++) {
                int curY = path.get(i).getY();
                // Look ahead from this point for a significant drop
                for (int j = i + 1; j < scanEnd; j++) {
                    int drop = curY - path.get(j).getY();
                    if (drop >= PRE_PEEK_MIN_DROP) {
                        // Edge = waypoint i (last ground before drop)
                        // But only if path doesn't go UP between player and edge
                        boolean pathRises = false;
                        int playerY = path.get(wpIdx).getY();
                        for (int k = wpIdx + 1; k <= i; k++) {
                            if (path.get(k).getY() > playerY + 1) {
                                pathRises = true;
                                break;
                            }
                        }
                        if (!pathRises) {
                            edgeIdx = i;
                            foundEdge = Vec3d.ofCenter(path.get(i));
                        }
                        break; // found first significant drop from point i
                    }
                }
                if (foundEdge != null) break;
            }

            if (foundEdge != null) {
                double distToEdge = Math.sqrt(
                    sq(foundEdge.x - pos.x) + sq(foundEdge.z - pos.z));

                // New edge detected — initialize per-approach state
                if (!preEdgeActive || preEdgePos == null
                    || foundEdge.squaredDistanceTo(preEdgePos) > 25.0) {
                    preEdgeActive = true;
                    preEdgePos = foundEdge;
                    // Human variability: randomize start distance ±20%
                    preEdgeStartDist = PRE_PEEK_START_DIST
                        * (1.0 + (rng.nextDouble() * 2.0 - 1.0) * PRE_PEEK_DIST_VAR);
                    // Attention modulates: focused → earlier notice
                    preEdgeStartDist *= (0.5 + 0.5 * attention);
                    // 15% chance player is "distracted" and doesn't pre-peek
                    preEdgeSkip = rng.nextDouble() < PRE_PEEK_SKIP_CHANCE;
                }

                if (!preEdgeSkip && distToEdge < preEdgeStartDist) {
                    // Compute pitch angle to the edge point
                    double eyeY = pos.y + player.getStandingEyeHeight();
                    double edgeDy = foundEdge.y - eyeY;
                    double edgeDh = Math.max(distToEdge, 0.5);
                    double pitchToEdge = -Math.toDegrees(Math.atan2(edgeDy, edgeDh));
                    // Clamp — don't look more than 25° down for pre-peek
                    pitchToEdge = Math.max(0, Math.min(pitchToEdge, 25.0));

                    // Strength ramp: smoothstep from start distance to full distance
                    double t;
                    if (distToEdge > preEdgeStartDist) {
                        t = 0;
                    } else if (distToEdge < PRE_PEEK_FULL_DIST) {
                        t = 1.0;
                    } else {
                        t = 1.0 - (distToEdge - PRE_PEEK_FULL_DIST)
                            / (preEdgeStartDist - PRE_PEEK_FULL_DIST);
                    }
                    t = t * t * (3.0 - 2.0 * t); // smoothstep

                    // Early phase (far): just a subtle +5° glance
                    // Late phase (close): full pitch toward edge point
                    double peekPitch = PRE_PEEK_EARLY_PITCH * (1.0 - t)
                                     + pitchToEdge * t;

                    preEdgePeekStrength = t;
                    newGoalPitch += peekPitch * t + PRE_PEEK_EARLY_PITCH * (1.0 - t) * Math.min(t * 3.0, 1.0);
                } else {
                    preEdgePeekStrength = 0;
                }
            } else {
                // No edge ahead — reset
                if (preEdgeActive) {
                    preEdgeActive = false;
                    preEdgePos = null;
                    preEdgePeekStrength = 0;
                }
            }
        } else if (isFallingCamera) {
            // Hand off to fall camera — clear pre-edge state
            preEdgeActive = false;
            preEdgePos = null;
            preEdgePeekStrength = 0;
        }

        // Combat/pursuit pitch blend — look at mob when close
        if (mob != null && mob.isAlive() && mobPos != null && mobDist < PRE_AIM_RANGE) {
            double dy = mobPos.y - (pos.y + player.getStandingEyeHeight());
            double dh = Math.sqrt(sq(mobPos.x - pos.x) + sq(mobPos.z - pos.z));
            if (dh > 0.1) {
                double mobPitch = -Math.toDegrees(Math.atan2(dy, dh)) + aimScatterPitch;
                double blend;
                if (inCombat || directPursuit) {
                    // Close to mob: full aim at mob
                    blend = 1.0;
                } else {
                    blend = 1.0 - clamp01((mobDist - ATTACK_RANGE) / (PRE_AIM_RANGE - ATTACK_RANGE));
                }
                newGoalPitch = newGoalPitch * (1.0 - blend) + mobPitch * blend;
            }
        }

        goalPitch = newGoalPitch;

        // Post-fall recovery: after landing, gradually blend from fall pitch
        // back to normal. Prevents camera from snapping upward between
        // consecutive falls (e.g. staircase drops).
        if (postFallRecoveryTicks > 0 && !isFallingCamera) {
            postFallRecoveryTicks--;
            double recoveryT = 1.0 - (double) postFallRecoveryTicks / POST_FALL_RECOVERY_TICKS;
            recoveryT = recoveryT * recoveryT; // ease-in: slow at first, faster at end
            // Blend between remembered fall pitch and current goal
            goalPitch = postFallPitch * (1.0 - recoveryT) + goalPitch * recoveryT;
        }

        // Subtle pitch wander while running straight (humans glance up/down)
        if (lowErrorTicks > IDLE_SCAN_AFTER && !inCombat) {
            double t = lowErrorTicks / 20.0;
            double pitchDrift = IDLE_SCAN_AMP * 0.35 * Math.sin(t * 0.8 + 1.3)
                              + IDLE_SCAN_AMP * 0.2  * Math.sin(t * 2.1 + 0.7);
            goalPitch += pitchDrift;
        }

        // Slow body sway (0.6Hz) applied to goal — spring tracks it with
        // momentum, producing smooth multi-pixel sweeps. The fast stride
        // component (1.7Hz) is applied post-spring in onFrame as overlay.
        if (!isFallingCamera && !inCombat && player.isOnGround() && hSpeed > 0.05) {
            double bobScale = Math.min(1.0, hSpeed / 0.2);
            goalPitch += HEAD_BOB_AMP2 * bobScale * Math.sin(headBobPhase * 0.5 + 0.8);
        }

        /* ── Sprint control (uses spring state, not frame interpolation) ── */
        // How far the spring still needs to turn
        double springError = Math.abs(MathHelper.wrapDegrees((float)(goalYaw - springYawPos)));

        boolean shouldSprint = true;
        if (hasPath) {
            double turnAhead = computeTurnAngleAhead(path, wpIdx, pos, SPRINT_LOOKAHEAD_DIST);
            if (turnAhead > SPRINT_ANGLE_THRESH) shouldSprint = false;
        }
        if (springError > SPRINT_DELTA_YAW_MAX) shouldSprint = false;
        // Walk near mob — gives camera time to aim for attack
        if (mob != null && mobDist < BRAKE_RANGE) shouldSprint = false;

        /* ── Sprint timing (human-like) ────────────────────────────
         *  1. Delay sprint 4-9 ticks after starting forward movement
         *  2. Occasional 2-4 tick gaps every 80-240 ticks of sprinting */
        boolean forwardNow = hasPath || inCombat;
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

        // Full 3D aim error: yaw + pitch, not just yaw.
        // Without pitch check, bot "attacks" when crosshair is above/below hitbox.
        double aimErrorToMob = 999;
        if (inCombat && mobPos != null) {
            double yawErr = Math.abs(MathHelper.wrapDegrees(
                (float)(yawTo(pos, mobPos) - springYawPos)));
            double eyeY = pos.y + player.getStandingEyeHeight();
            double dy = mobPos.y - eyeY;
            double dh = Math.sqrt(sq(mobPos.x - pos.x) + sq(mobPos.z - pos.z));
            double targetPitch = (dh > 0.1)
                ? -Math.toDegrees(Math.atan2(dy, dh))
                : springPitchPos;
            double pitchErr = Math.abs(targetPitch - springPitchPos);
            aimErrorToMob = Math.sqrt(yawErr * yawErr + pitchErr * pitchErr);
        }

        // Only attack when vanilla crosshair raycast actually sees an entity.
        // doAttack() hits whatever crosshairTarget is — if it's a block, we miss.
        boolean crosshairOnEntity = client.crosshairTarget != null
            && client.crosshairTarget.getType() == HitResult.Type.ENTITY;

        // Crosshair is vanilla raycast — if it sees an entity, attack.
        // This is more accurate than our aim_error math (accounts for hitbox).
        if (inCombat && attackCooldown <= 0 && crosshairOnEntity) {
            attack = true;
            attackCooldown = ATTACK_CD_MIN + rng.nextInt(ATTACK_CD_MAX - ATTACK_CD_MIN + 1);
            consecutiveMisses = 0;
            combatStuckTicks = 0;
        } else if (inCombat && attackCooldown <= 0 && aimErrorToMob < ATTACK_AIM_TOLERANCE
                && !crosshairOnEntity) {
            // Aim says close enough but crosshair misses → count as miss
            attackCooldown = ATTACK_CD_MIN + rng.nextInt(ATTACK_CD_MAX - ATTACK_CD_MIN + 1);
            consecutiveMisses++;
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
                // Endpoint scatter: each new aim attempt has slightly different offset
                aimScatterYaw   = rng.nextGaussian() * AIM_SCATTER_YAW_SD;
                aimScatterPitch = rng.nextGaussian() * AIM_SCATTER_PITCH_SD;
                consecutiveMisses = 0;
                combatStuckTicks = 0;
            }
        } else {
            lastCombatTargetId = null;
            consecutiveMisses = 0;
            combatStuckTicks = 0;
        }
        if (combatReactionRemaining > 0) {
            combatReactionRemaining--;
            attack = false; // suppress attack during reaction window
        }

        // Re-roll aimScatter after consecutive misses — human adjusts aim
        if (consecutiveMisses >= MISS_SCATTER_REROLL) {
            aimScatterYaw   = rng.nextGaussian() * AIM_SCATTER_YAW_SD * 0.5; // tighter after miss
            aimScatterPitch = rng.nextGaussian() * AIM_SCATTER_PITCH_SD * 0.5;
            consecutiveMisses = 0;
        }

        /* ── Combat strafe ─────────────────────────────────────────
         *  Run-through mode: no strafing. Bot sprints through mob.  */
        boolean strafeLeft = false, strafeRight = false;

        /* ── Path-following jump ────────────────────────────────────
         *  Jump only for reachable step-ups (≤1.3 blocks above).
         *  Waypoints far above (falls, cliffs) should NOT trigger jump. */
        boolean jump = false;
        if (hasPath && !inCombat && player.isOnGround()) {
            for (int look = 0; look < 3 && wpIdx + look < path.size(); look++) {
                BlockPos wp = path.get(wpIdx + look);
                double wpY = wp.getY();
                double playerY = player.getBlockPos().getY();
                double dy = wpY - playerY;
                if (dy > 0.6 && dy <= 1.3) {
                    jump = true;
                    break;
                }
            }
        }

        /* ── Stuck detection ─────────────────────────────────────── */
        // Vertically unreachable: path ended (near goal) but mob is far away.
        // pickGoal resolved to cliff base → A* pathed there → bot arrived → mob still above.
        // Blacklist this mob and move on instead of staring at a wall for 16 seconds.
        if (hasPath && !inCombat && mob != null && mob.isAlive()) {
            double distGoalNow = Pathfinder.getDistToGoal();
            if (distGoalNow >= 0 && distGoalNow < 4.0 && mobDist > 8.0) {
                verticalStuckTicks++;
                if (verticalStuckTicks > 40) { // 2 seconds at path end, mob still far
                    Pathfinder.blacklistCurrentTarget("vertical_unreachable");
                    verticalStuckTicks = 0;
                }
            } else {
                verticalStuckTicks = 0;
            }
        } else {
            verticalStuckTicks = 0;
        }

        // Use horizontal distance only — jumping in place shouldn't reset stuck
        double hdxSq = lastPos != null ? (sq(pos.x - lastPos.x) + sq(pos.z - lastPos.z)) : 999;
        if (lastPos != null && hdxSq < STUCK_MOVE_SQ && !inCombat) {
            stuckTicks++;
            // Fast repath: should be walking but not moving = hitting a wall
            // Don't waste time with yaw kicks, just repath immediately
            boolean shouldBeWalking = directPursuit || (hasPath && !inCombat);
            if (stuckTicks > 12 && shouldBeWalking) {
                Pathfinder.requestImmediateRepath("wall_stuck");
                stuckTicks = 0;
                stuckKicks = 0;
            } else if (stuckTicks > STUCK_THRESHOLD) {
                stuckKicks++;
                if (stuckKicks >= STUCK_KICKS_REPATH) {
                    Pathfinder.requestImmediateRepath("stuck_repeated");
                    stuckKicks = 0;
                } else {
                    double kickDeg = (rng.nextBoolean() ? 1 : -1) * (30 + rng.nextDouble() * 30);
                    goalYaw = springYawPos + kickDeg;
                    jump = true;
                }
                stuckTicks = 0;
            }
        } else {
            if (hdxSq > 4.0) stuckKicks = 0;
            stuckTicks = 0;
        }
        lastPos = pos;

        /* ── Combat stuck: circling mob without hitting ────────── */
        if (inCombat) {
            combatStuckTicks++;
            if (combatStuckTicks > COMBAT_STUCK_THRESHOLD) {
                // Reposition: step back + sidestep, re-approach from different angle
                double kickDeg = (rng.nextBoolean() ? 1 : -1) * COMBAT_REPOSITION_ANGLE;
                goalYaw = springYawPos + kickDeg;
                aimScatterYaw   = rng.nextGaussian() * AIM_SCATTER_YAW_SD * 0.3;
                aimScatterPitch = rng.nextGaussian() * AIM_SCATTER_PITCH_SD * 0.3;
                combatStuckTicks = 0;
                consecutiveMisses = 0;
            }
        } else {
            combatStuckTicks = 0;
        }

        /* ── Apply keys (rotation is applied in onFrame) ─────────── */
        boolean forward = hasPath || (inCombat && mobDist < ATTACK_RANGE);

        /* ── Graduated turn behavior ──────────────────────────────
         *  Humans turn and walk simultaneously for moderate angles.
         *  Only stop for very sharp turns (>90°, walking backwards).
         *  cos(70°)=0.34 → still forward progress. cos(90°)=0 → none. */
        double navYawErr = 0;
        if (forward && !inCombat) {
            navYawErr = Math.abs(MathHelper.wrapDegrees(
                (float)(goalYaw - springYawPos)));
            // Near mob: tighter aim threshold — let camera lock on before entering combat
            double maxAimErr = (mob != null && mobDist < BRAKE_RANGE) ? 30.0 : 90.0;
            if (navYawErr > maxAimErr) {
                forward = false;
            }
            // 30-90°: sprint already disabled via SPRINT_DELTA_YAW_MAX
        }

        // Combat/pursuit: distance-aware forward control.
        // Close range (< 2 blocks): strict aim required — prevents sprinting
        //   through mob then standing behind it for 1-2 seconds rotating 180°.
        // Far range (> 2 blocks): looser — can approach while camera adjusts.
        if (inCombat || directPursuit) {
            double combatAimErr = Math.abs(MathHelper.wrapDegrees(
                (float)(goalYaw - springYawPos)));
            double maxCombatAim = (mobDist < 2.0) ? 25.0 : 60.0;
            if (combatAimErr > maxCombatAim) forward = false;
        }

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
        // Cancel nav strafe when entering combat — prevents circling
        if (inCombat) {
            navStrafeTicks = 0;
            navStrafeDir = 0;
        }
        if (navStrafeTicks > 0 && !inCombat) {
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

        // ── Normalize yaw accumulators ──────────────────────────────
        // goalYaw is near [-180,180]. Spring/tracked accumulate unbounded.
        // Re-center spring group near goalYaw each tick.
        // smoothGoalYaw is NOT normalized here — it is re-centered near
        // springYawPos every frame in onFrame (single source of truth).
        // Having two normalizers (tick + frame) caused catastrophic conflicts
        // where they pulled smoothGoalYaw in opposite directions.
        {
            double base = goalYaw;
            double c1 = Math.round((springYawPos - base) / 360.0) * 360.0;
            if (c1 != 0) {
                springYawPos -= c1;
                trackedYaw   -= c1;
                if (inSaccade) {
                    saccadeStartYaw  -= c1;
                    saccadeTargetYaw -= c1;
                }
            }
        }

        // ── Fill debug fields for AutopilotDebugHud ──
        dbg_inCombat        = inCombat;
        dbg_directPursuit   = directPursuit;
        dbg_mobDist         = mobDist;
        dbg_aimError        = aimErrorToMob;
        dbg_crosshairOnEntity = crosshairOnEntity;
        dbg_attackFired     = attack;
        dbg_consecutiveMisses = consecutiveMisses;
        dbg_combatStuckTicks = combatStuckTicks;
        dbg_forward         = forward;
        dbg_sprint          = shouldSprint && forward;
        dbg_jump            = jump;
        dbg_strafeDir       = (strafeLeft || navLeft) ? -1 : (strafeRight || navRight) ? 1 : 0;
        dbg_pathLen         = hasPath ? path.size() : 0;
        dbg_wpIndex         = wpIdx;
        dbg_distToWp        = (hasPath && wpIdx < path.size())
            ? pos.distanceTo(Vec3d.ofCenter(path.get(wpIdx))) : -1;
        dbg_distToGoal      = (hasPath)
            ? pos.distanceTo(Vec3d.ofCenter(path.get(path.size() - 1))) : -1;
        dbg_stuckTicks      = stuckTicks;
        dbg_playerX = pos.x; dbg_playerY = pos.y; dbg_playerZ = pos.z;
        if (hasPath && wpIdx < path.size()) {
            BlockPos wp = path.get(wpIdx);
            dbg_wpX = wp.getX() + 0.5; dbg_wpY = wp.getY(); dbg_wpZ = wp.getZ() + 0.5;
        } else {
            dbg_wpX = 0; dbg_wpY = 0; dbg_wpZ = 0;
        }
        dbg_goalYaw     = goalYaw;
        dbg_currentYaw  = springYawPos;
        dbg_goalPitchTick = goalPitch;
        dbg_currentPitch  = springPitchPos;
        dbg_pathBlocked = hasPath && client.world != null
            && isPathBlockedForDebug(client.world, path, wpIdx);
        if (mob != null && mob.isAlive()) {
            Vec3d mp = mob.getEntityPos();
            dbg_mobX = mp.x; dbg_mobY = mp.y; dbg_mobZ = mp.z;
            dbg_targetName = mob.getType().getTranslationKey();
        } else {
            dbg_mobX = 0; dbg_mobY = 0; dbg_mobZ = 0;
            dbg_targetName = "none";
        }
        dbg_repathReason = null;
        AutopilotDebugHud.tickLog();
    }

    /* ══════════════════════════════════════════════════════════════
     *  FRAME  (60-144 Hz) — motor-control-accurate human dynamics
     *
     *  Three-layer architecture matching real neuromuscular control:
     *
     *  Layer 1: Goal smoothing (visual processing delay ~100ms)
     *    Raw goal from tick → low-pass → smoothed goal.
     *    Simulates the visual cortex latency before motor response.
     *
     *  Layer 2: Movement execution (dual-mode)
     *    SACCADE MODE (>18° error): Minimum-jerk trajectory
     *      (Flash & Hogan 1985). Bell-shaped velocity profile with
     *      Fitts' Law timing + per-movement variability.
     *    SPRING MODE (<18° error): Critically-damped spring for
     *      continuous tracking with dynamic ω and damping.
     *
     *  Layer 3: Physiological tremor overlay
     *    Continuous narrow-band ~10Hz oscillation (NOT white noise).
     *    Added to output, not fed back into spring.
     * ══════════════════════════════════════════════════════════════ */

    private static void onFrame(ClientPlayerEntity player) {
        long now = System.nanoTime();
        double dt = (now - lastFrameNano) / 1_000_000_000.0;
        lastFrameNano = now;

        if (dt <= 0 || dt > 0.1) dt = 0.016;

        double timeS = now / 1_000_000_000.0;

        // ══ Layer 1: Goal smoothing ══════════════════════════════
        //    Low-pass filter: goalYaw changes at 20Hz tick rate.
        //    Combat: 100ms delay simulates visual processing before aiming.
        //    Navigation: near-instant tracking — player anticipates the path,
        //    no reaction delay needed. The spring itself provides all smoothing.
        // Fatigue increases visual processing delay over long sessions
        double fatigueLag = 1.0 + fatigue * (FATIGUE_LAG_MULT - 1.0);
        boolean isNavigating = !dbg_inCombat && !dbg_directPursuit;
        double baseTau = isNavigating ? 0.015 : GOAL_LAG_TAU; // 15ms nav vs 100ms combat
        // Melee: snap to goal fast — no time for 100ms lag when mob is at arm's length
        // and camera needs a 180° flip after overshooting
        if (dbg_inCombat && dbg_mobDist < ATTACK_RANGE) baseTau = 0.02;
        double effectiveTau = baseTau * fatigueLag / (0.5 + 0.5 * attention);
        double alpha = 1.0 - Math.exp(-dt / effectiveTau);
        smoothGoalYaw   += MathHelper.wrapDegrees((float)(goalYaw   - smoothGoalYaw))   * alpha;
        smoothGoalPitch += (goalPitch - smoothGoalPitch) * alpha;
        // Re-center smoothGoalYaw near springYawPos — sole normalization
        // point for smooth (tick normalization was removed to prevent conflicts).
        {
            double drift = smoothGoalYaw - springYawPos;
            if (Math.abs(drift) > 180.0) {
                smoothGoalYaw -= Math.round(drift / 360.0) * 360.0;
            }
        }
        // Also keep saccade target in the same domain as spring
        if (inSaccade) {
            double tgtDrift = saccadeTargetYaw - springYawPos;
            if (Math.abs(tgtDrift) > 180.0) {
                double c = Math.round(tgtDrift / 360.0) * 360.0;
                saccadeTargetYaw -= c;
                saccadeStartYaw  -= c;
            }
        }

        // ══ Layer 2: Movement execution ══════════════════════════

        // Measure current error to smoothed goal
        double yawToGo   = MathHelper.wrapDegrees((float)(smoothGoalYaw - springYawPos));
        double pitchToGo = smoothGoalPitch - springPitchPos;
        double totalError = Math.sqrt(yawToGo * yawToGo + pitchToGo * pitchToGo);

        // ── Saccade detection ─────────────────────────────────────
        //    When error exceeds threshold, switch to minimum-jerk
        //    trajectory. This produces the characteristic bell-shaped
        //    velocity profile of real human reaching movements,
        //    instead of the exponential decay of a spring.
        // Suppress saccades during ALL fall phases (including reaction delay)
        // AND during post-fall recovery. Without this, a saccade triggers during
        // the 1-3 tick reaction delay when goalPitch suddenly freezes to fallBasePitch
        // — producing 8°+ per-frame jumps that are physically impossible for humans.
        // The spring alone maxes at ~0.3°/frame which matches human data (max 1.44°).
        boolean suppressSaccade = isFallingCamera || postFallRecoveryTicks > 0;
        // Cancel running saccade during fall/recovery — spring handles it smoother
        if (suppressSaccade && inSaccade) {
            inSaccade = false;
            // Keep current springYawVel/springPitchVel for smooth hand-off
        }
        if (!inSaccade && !suppressSaccade && totalError > SACCADE_THRESHOLD) {
            inSaccade         = true;
            saccadeStartYaw   = springYawPos;
            saccadeStartPitch = springPitchPos;
            // Clear dwell from previous saccade — without this, dwell freezes
            // indefinitely (it only decrements in spring mode, never during saccade)
            postSaccadeDwell = 0;
            postSaccadeCorrections = 0;

            // Asymmetric overshoot: humans tend to overshoot slightly
            // Nudge target 4% ±3% past the goal along movement direction
            double overshoot = 1.0 + OVERSHOOT_BIAS
                + (rng.nextDouble() * 2.0 - 1.0) * OVERSHOOT_VARIANCE;
            double rawDeltaYaw = MathHelper.wrapDegrees(
                (float)(smoothGoalYaw - springYawPos));
            double rawDeltaPitch = smoothGoalPitch - springPitchPos;
            saccadeTargetYaw   = springYawPos + rawDeltaYaw * overshoot;
            saccadeTargetPitch = springPitchPos + rawDeltaPitch * overshoot;

            // Fitts' Law: T = a + b × D, with ±20% variability
            // Navigation turns are faster (running player whips camera)
            // Combat aims are more deliberate
            double navSpeedup = (!dbg_inCombat && !dbg_directPursuit) ? 0.65 : 1.0;
            double baseTime = (SACCADE_FITTS_A + SACCADE_FITTS_B * totalError) * navSpeedup;
            double timeVar = 1.0 - SACCADE_TIME_VAR
                + rng.nextDouble() * 2.0 * SACCADE_TIME_VAR;
            double fatigueMul = 1.0 + fatigue * (1.0 / FATIGUE_OMEGA_MULT - 1.0);
            saccadeDuration = baseTime * timeVar * fatigueMul
                / (0.5 + 0.5 * attention);
            saccadeElapsed = 0;

            // Mouse lift: for very large rotations, plan a mid-saccade pause
            mouseLiftDone = totalError < MOUSE_LIFT_THRESHOLD;
            mouseLiftPause = MOUSE_LIFT_PAUSE_MIN
                + rng.nextDouble() * (MOUSE_LIFT_PAUSE_MAX - MOUSE_LIFT_PAUSE_MIN);

            // Pre-roll omega scale for post-saccade spring phase
            moveOmegaScale = 0.82 + rng.nextDouble() * 0.36;
        }

        if (inSaccade) {
            // ── Saccade execution (minimum-jerk) ──────────────────

            // Mouse lift: pause mid-saccade for physical mouse repositioning
            double tau = Math.min(1.0, saccadeElapsed / saccadeDuration);
            if (!mouseLiftDone && tau >= MOUSE_LIFT_TAU_START && tau < MOUSE_LIFT_TAU_END) {
                mouseLiftPause -= dt;
                if (mouseLiftPause > 0) {
                    // Freeze saccade — don't advance elapsed time
                    // (hand is lifting the mouse, position doesn't change)
                } else {
                    mouseLiftDone = true;
                    saccadeElapsed += dt;
                }
            } else {
                saccadeElapsed += dt;
            }

            // If goal shifted significantly mid-saccade, cancel and hand off
            // to spring. Saccades are ballistic (fixed trajectory) — they can't
            // track moving targets. The spring is adaptive and actually faster
            // (84°/s avg vs 54°/s for restart-plagued saccades).
            // Grace period: smoothGoalYaw takes ~100ms to converge after a goal
            // change (tau=20ms melee). Cancelling during convergence kills velocity
            // because the saccade barely started (vel≈0 at tau<0.1).
            // Wait 200ms so the saccade builds speed before checking for shifts.
            if (saccadeElapsed > 0.2) {
                double shiftYaw   = MathHelper.wrapDegrees((float)(smoothGoalYaw - saccadeTargetYaw));
                double shiftPitch = smoothGoalPitch - saccadeTargetPitch;
                if (Math.abs(shiftYaw) > 30 || Math.abs(shiftPitch) > 30) {
                    inSaccade = false;
                    // Keep current velocity for smooth hand-off to spring
                }
            }

            if (inSaccade) {
                // ── Continue saccade trajectory ─────────────────────
                tau = Math.min(1.0, saccadeElapsed / saccadeDuration);
                double blend = minJerk(tau);

                double deltaYaw   = MathHelper.wrapDegrees(
                    (float)(saccadeTargetYaw - saccadeStartYaw));
                double deltaPitch = saccadeTargetPitch - saccadeStartPitch;

                springYawPos   = saccadeStartYaw   + deltaYaw   * blend;
                springPitchPos = saccadeStartPitch + deltaPitch * blend;

                // Track velocity for smooth hand-off to spring
                // minJerkDeriv gives deg/normalized-time, divide by T for deg/sec
                double dBlend  = minJerkDeriv(tau) / saccadeDuration;
                springYawVel   = deltaYaw   * dBlend;
                springPitchVel = deltaPitch * dBlend;

                if (tau >= 1.0) {
                    // Saccade complete — velocity is zero (minJerk deriv = 0 at τ=1)

                    // Post-saccade corrections: discrete pauses before smooth tracking.
                    // Only in combat/pursuit — humans evaluate aim visually.
                    // During navigation, mouse moves continuously (no "evaluate aim" pause).
                    // Only dwell if we actually landed close to target — no point
                    // pausing to "evaluate" when aim is still way off.
                    double postErr = Math.sqrt(
                        sq(MathHelper.wrapDegrees((float)(smoothGoalYaw - springYawPos)))
                        + sq(smoothGoalPitch - springPitchPos));
                    boolean needsAimEval = (dbg_inCombat || dbg_directPursuit) && postErr < 15;
                    if (needsAimEval) {
                        postSaccadeDwell = CORRECTION_PAUSE_MIN
                            + rng.nextDouble() * (CORRECTION_PAUSE_MAX - CORRECTION_PAUSE_MIN);
                        postSaccadeCorrections = 1 + rng.nextInt(2); // 1-2 more pauses
                    } else {
                        postSaccadeDwell = 0;
                        postSaccadeCorrections = 0;
                    }

                    // Post-saccadic oscillation: wrist resonance after fast flick
                    // Amplitude proportional to how fast the saccade peaked
                    double peakVel = Math.abs(deltaYaw) * 1.875 / saccadeDuration; // minJerk peak
                    postSaccOscAmp = peakVel * POST_SACC_OSC_GAIN;
                    postSaccOscPhase = 0;
                    postSaccOscStartTime = timeS;

                    inSaccade      = false;
                    springYawVel   = 0;
                    springPitchVel = 0;
                }
            }
        } else {
            // ── Spring mode (continuous tracking, <18° corrections) ─

            // Post-saccade dwell: discrete pauses simulating visual
            // evaluation between corrective sub-movements
            if (postSaccadeDwell > 0) {
                postSaccadeDwell -= dt;
                // Heavy damping during dwell (hand is still, eyes evaluate error)
                springYawVel   *= Math.exp(-CORRECTION_DAMP_RATE * dt);
                springPitchVel *= Math.exp(-CORRECTION_DAMP_RATE * dt);
                // Skip spring step — no active correction during pause
            } else {
                double baseYawOmega   = inCombat ? COMBAT_YAW_OMEGA : NAV_YAW_OMEGA;
                double basePitchOmega = inCombat ? COMBAT_PITCH_OMEGA
                                      : isFallingCamera ? FALL_PITCH_OMEGA
                                      : NAV_PITCH_OMEGA;

                // Movement-to-movement variability (±18%)
                baseYawOmega   *= moveOmegaScale;
                basePitchOmega *= moveOmegaScale;

                // Attention + fatigue modulate spring speed
                double fatigueOmega = 1.0 + fatigue * (FATIGUE_OMEGA_MULT - 1.0);
                double attentionOmegaMul = (0.5 + 0.5 * attention) * fatigueOmega;
                baseYawOmega   *= attentionOmegaMul;
                basePitchOmega *= attentionOmegaMul;

                // Dynamic omega based on error magnitude
                double yawError    = MathHelper.wrapDegrees((float)(smoothGoalYaw - springYawPos));
                double absYawError = Math.abs(yawError);

                double yawOmega = baseYawOmega;
                if (absYawError > FLICK_THRESHOLD) {
                    yawOmega *= FLICK_OMEGA_MULT;
                    if (!dbg_inCombat && absYawError > 45.0) {
                        yawOmega *= 1.3; // nav: total ~2.08× for large turns
                    }
                    if (dbg_inCombat && absYawError > 30.0) {
                        yawOmega *= 1.4; // combat: snap to mob faster when aim is way off
                    }
                    // Combat with massive error: floor omega at 14 so camera
                    // never stalls at 16°/s — ensures ~200°/s peak for 150° turns.
                    // This catches edge cases where attention/fatigue/scale
                    // stack unfavorably and drag omega below useful levels.
                    if ((dbg_inCombat || dbg_directPursuit) && absYawError > 60.0) {
                        yawOmega = Math.max(yawOmega, 14.0);
                    }
                } else if (absYawError < HOLD_THRESHOLD) {
                    // Hold zone: slow down for precise aiming (combat only).
                    // Navigation needs continuous smooth tracking, not careful aim.
                    if (dbg_inCombat || dbg_directPursuit) {
                        yawOmega *= HOLD_OMEGA_MULT;
                    }
                }

                // Re-roll omega scale when starting a new correction
                boolean nowInHoldZone = absYawError < HOLD_THRESHOLD;
                if (wasInHoldZone && !nowInHoldZone) {
                    moveOmegaScale = 0.82 + rng.nextDouble() * 0.36;
                }
                wasInHoldZone = nowInHoldZone;

                // Trigger next post-saccade pause when error drops below threshold
                if (postSaccadeCorrections > 0 && absYawError < CORRECTION_THRESH
                        && (dbg_inCombat || dbg_directPursuit)) {
                    postSaccadeCorrections--;
                    postSaccadeDwell = CORRECTION_INTER_MIN
                        + rng.nextDouble() * (CORRECTION_INTER_MAX - CORRECTION_INTER_MIN);
                }

                // Speed modulation: value noise (aperiodic 1/f spectrum)
                double speedMod = 1.0
                    + SPEED_NOISE_AMP1 * valueNoise(timeS * SPEED_NOISE_FREQ1)
                    + SPEED_NOISE_AMP2 * valueNoise(timeS * SPEED_NOISE_FREQ2 + 137.0);
                yawOmega *= speedMod;
                double pitchOmega = basePitchOmega * speedMod;
                // Combat pitch boost — aim was freezing because pitch omega too slow
                if (dbg_inCombat) {
                    double pitchError = Math.abs(smoothGoalPitch - springPitchPos);
                    if (pitchError > 15.0) pitchOmega *= 1.5;
                }

                // Damping ratios
                double yawZeta   = (absYawError > UNDERDAMP_THRESHOLD) ? UNDERDAMP_ZETA : 1.0;
                double pitchZeta = isFallingCamera ? FALL_PITCH_ZETA : 1.0;

                // Step yaw spring — with acceleration limiter to prevent jerks
                double yawAccel = yawOmega * yawOmega * yawError
                                - 2.0 * yawZeta * yawOmega * springYawVel;
                // Soft-limit acceleration: human arms have max force
                // Navigation: higher limit — player whips mouse for big turns
                double maxAccel = isNavigating ? 5000.0 : 2500.0;
                if (Math.abs(yawAccel) > maxAccel) {
                    yawAccel = Math.signum(yawAccel) * maxAccel
                        * Math.tanh(Math.abs(yawAccel) / maxAccel);
                }
                springYawVel += yawAccel * dt;
                springYawPos += springYawVel * dt;

                // Step pitch spring
                // Yaw-pitch wrist coupling: when turning, wrist arc creates
                // a slight vertical component. Applied as velocity-proportional
                // offset to pitch goal — goes through spring for smooth output.
                double coupledGoalPitch = smoothGoalPitch
                    + springYawVel * YAW_PITCH_COUPLING_VEL;
                double pitchError = coupledGoalPitch - springPitchPos;
                double pitchAccel = pitchOmega * pitchOmega * pitchError
                                  - 2.0 * pitchZeta * pitchOmega * springPitchVel;
                if (Math.abs(pitchAccel) > maxAccel) {
                    pitchAccel = Math.signum(pitchAccel) * maxAccel
                        * Math.tanh(Math.abs(pitchAccel) / maxAccel);
                }
                springPitchVel += pitchAccel * dt;
                springPitchPos += springPitchVel * dt;
            }
        }

        springPitchPos = Math.max(-90, Math.min(90, springPitchPos));

        // ══ Layer 3: Physiological tremor ════════════════════════
        //    Continuous narrow-band ~10Hz oscillation overlaid on output.
        //    NOT random impulses — real hand tremor is autocorrelated
        //    with slowly drifting frequency and amplitude.
        //
        //    Frequency drift: ±1.5Hz slow sine modulation prevents
        //    exact periodicity that spectral analysis would detect.
        //    Amplitude modulation: value noise produces natural
        //    1/f-like variation in tremor intensity.
        tremorYawPhase   += dt * 2.0 * Math.PI
            * (TREMOR_YAW_FREQ   + 1.5 * Math.sin(timeS * 0.7));
        tremorPitchPhase += dt * 2.0 * Math.PI
            * (TREMOR_PITCH_FREQ + 1.2 * Math.sin(timeS * 0.5 + 2.0));

        // Attention + fatigue modulate tremor
        double tremorScale = isFallingCamera ? FALL_TREMOR_MULT : 1.0;
        tremorScale *= (2.0 - attention); // low focus → more tremor
        tremorScale *= (1.0 + fatigue * (FATIGUE_TREMOR_MULT - 1.0)); // fatigue → more tremor
        double tyAmp = TREMOR_YAW_AMP   * tremorScale
            * (1.0 + 0.35 * valueNoise(timeS * 0.2));
        double tpAmp = TREMOR_PITCH_AMP * tremorScale
            * (1.0 + 0.35 * valueNoise(timeS * 0.15 + 50.0));

        double rawTremorYaw   = tyAmp * Math.sin(tremorYawPhase);
        double rawTremorPitch = tpAmp * Math.sin(tremorPitchPhase);

        // Correlated tremor: real hand movement has diagonal coupling.
        // When yaw trembles, pitch moves too (~35% cross-axis).
        double pureTremorYaw   = rawTremorYaw   + rawTremorPitch * TREMOR_CORRELATION;
        double pureTremorPitch = rawTremorPitch + rawTremorYaw   * TREMOR_CORRELATION;

        // Start composite overlay with pure tremor, then add layers
        double tremorYaw   = pureTremorYaw;
        double tremorPitch = pureTremorPitch;

        // ══ Layer 4: Breathing rhythm ════════════════════════════
        //    Ultra-subtle ~0.25Hz pitch oscillation from chest movement.
        //    Always present. Amplitude varies with breathing depth.
        breathPhase += dt * 2.0 * Math.PI
            * (BREATH_FREQ + BREATH_FREQ_DRIFT * Math.sin(timeS * 0.08));
        double breathAmp = BREATH_AMP * (1.0 + 0.2 * valueNoise(timeS * 0.1 + 200.0));
        tremorPitch += breathAmp * Math.sin(breathPhase);

        // Head-bob: fast component (1.7Hz) applied here as overlay — bypasses
        // the spring which would filter it (spring cutoff ~0.6Hz). Slow component
        // (0.6Hz) is in goalPitch so the spring tracks it with momentum.
        double hSpeedNow = player.getVelocity().horizontalLength();
        if (player.isOnGround() && hSpeedNow > 0.05) {
            headBobPhase += dt * 2.0 * Math.PI * HEAD_BOB_FREQ;
            double bobScale = Math.min(1.0, hSpeedNow / 0.2);
            // Fast stride rhythm — applied post-spring so it's not filtered out
            tremorPitch += HEAD_BOB_AMP  * bobScale * Math.sin(headBobPhase);
            // Tiny yaw bob (body sway)
            tremorYaw   += HEAD_BOB_AMP2 * 0.3 * bobScale * Math.sin(headBobPhase * 0.5 + 2.0);
        }

        // ══ Layer 5: Post-saccadic oscillation ═══════════════════
        //    Transient damped ~3Hz wrist resonance after fast flicks.
        //    Decays exponentially over ~250ms. Distinct from tremor.
        double oscAge = timeS - postSaccOscStartTime;
        if (postSaccOscAmp > 0.01 && oscAge < 0.4) {
            postSaccOscPhase += dt * 2.0 * Math.PI * POST_SACC_OSC_FREQ;
            double oscDecay = Math.exp(-oscAge * POST_SACC_OSC_DECAY);
            double osc = postSaccOscAmp * oscDecay * Math.sin(postSaccOscPhase);
            tremorYaw += osc; // rides on top of tremor overlay
        }

        // ══ Rest-state overlay attenuation ═══════════════════════
        //    When the spring is settled (low velocity, low error), fade
        //    out yaw overlays. This creates natural dead zones where the
        //    camera is truly still — matching human behavior where the
        //    mouse is physically stationary between discrete swipes.
        //    Human data: yaw static runs avg 7.6 frames vs bot 2.9.
        //    Without this, tremor keeps yaw permanently moving.
        {
            double yawErr = Math.abs(MathHelper.wrapDegrees(
                (float)(smoothGoalYaw - springYawPos)));
            double yawRestFactor = clamp01(Math.max(
                Math.abs(springYawVel) / 15.0,  // >15 deg/s: full overlay
                yawErr / 2.5                      // >2.5° error: full overlay
            ));
            tremorYaw *= yawRestFactor;
            // Pitch: leave at full — pitch static % already matches human (62% vs 60%).
            // Pitch issues (reversals, autocorr) are fixed by hysteresis below.
        }

        // ══ Soft cap turn rate ══════════════════════════════════
        //    Tanh soft-clamp only near limit — passes through small/medium
        //    velocities unchanged to prevent systematic drag and jitter.
        double finalYaw = springYawPos + tremorYaw;
        // Use trackedYaw (double) instead of player.getYaw() (float) to avoid
        // precision loss that puts ~20% of deltas off the mouse sensitivity grid.
        double yawDelta = MathHelper.wrapDegrees((float)(finalYaw - trackedYaw));
        double maxDeg   = MAX_TURN_DEG_PER_SEC * dt;
        if (maxDeg > 0) {
            double absRatio = Math.abs(yawDelta) / maxDeg;
            if (absRatio > 0.7) {
                yawDelta = Math.signum(yawDelta) * maxDeg * Math.tanh(absRatio);
            }
            // Update spring position to reflect clamped delta (double precision)
            // This keeps spring velocity consistent with actual movement.
            springYawPos = trackedYaw + yawDelta - tremorYaw;
            // Soft-clamp velocity only when near limit
            double absVelRatio = Math.abs(springYawVel) / MAX_TURN_DEG_PER_SEC;
            if (absVelRatio > 0.7) {
                springYawVel = Math.signum(springYawVel)
                    * MAX_TURN_DEG_PER_SEC * Math.tanh(absVelRatio);
            }
        }

        // ══ Mouse sensitivity quantization ═════════════════════════
        //    Real mouse input is discrete: each pixel = step degrees.
        //    Step = (sens*0.6+0.2)³ × 8.0 × 0.15
        //    We quantize deltas to this grid + carry sub-pixel error
        //    to the next frame (error diffusion / 1D dithering).
        //    This prevents anticheat detecting non-grid angle deltas.
        MinecraftClient mc = MinecraftClient.getInstance();
        double sens = mc.options.getMouseSensitivity().getValue(); // 0..1 (0.5 = 100%)
        double f = sens * 0.6 + 0.2;
        double mouseStep = f * f * f * 8.0 * 0.15; // deg per pixel

        double desiredPitch = MathHelper.clamp(
            (float)(springPitchPos + tremorPitch), -90f, 90f);

        // Add accumulated sub-pixel error from previous frames
        double rawDeltaYaw   = yawDelta + quantErrorYaw;   // yawDelta already computed above
        double rawDeltaPitch = (desiredPitch - trackedPitch) + quantErrorPitch;

        // Quantize to mouse pixel grid
        long pixelsYaw   = Math.round(rawDeltaYaw   / mouseStep);
        long pixelsPitch = Math.round(rawDeltaPitch / mouseStep);

        // ── Reversal hysteresis (BOTH axes) ─────────────────────
        //    Human data shows 0% frame-to-frame reversals at 300+ FPS.
        //    Real mouse can't physically reverse direction in 3ms.
        //    Error diffusion + tremor + breathing create artificial
        //    reversals that are a dead giveaway for bot detection.
        //
        //    Fix: suppress ANY direction reversal unless the raw delta
        //    exceeds a generous threshold — meaning genuine intent.
        //    Yaw: 3.0 mouseSteps (~0.25°). Pitch: 4.0 mouseSteps (~0.34°).
        //    Suppressed energy accumulates in quantError and fires later.
        if (pixelsYaw != 0 && lastYawDir != 0
            && Long.signum(pixelsYaw) != lastYawDir) {
            if (Math.abs(rawDeltaYaw) < mouseStep * 3.0) {
                pixelsYaw = 0;
            }
        }
        if (pixelsYaw != 0) {
            lastYawDir = Long.signum(pixelsYaw);
        }

        double quantizedDeltaYaw = pixelsYaw * mouseStep;

        // Pitch hysteresis: stronger than yaw — pitch has more overlays
        // (tremor + breathing + head-bob) that all create reversal pressure.
        if (pixelsPitch != 0 && lastPitchDir != 0
            && Long.signum(pixelsPitch) != lastPitchDir) {
            if (Math.abs(rawDeltaPitch) < mouseStep * 5.0) {
                pixelsPitch = 0;
            }
        }
        if (pixelsPitch != 0) {
            lastPitchDir = Long.signum(pixelsPitch);
        }

        double quantizedDeltaPitch = pixelsPitch * mouseStep;

        // Store remainder for next frame (sub-pixel error diffusion).
        // CRITICAL: clamp to ±1 mouseStep to prevent accumulation bomb.
        // When reversal hysteresis blocks deltas, quantError keeps growing
        // each frame. After 50+ blocked frames, it fires as a 5-8° spike
        // in a single frame — physically impossible and a detection giveaway.
        // Clamping to ±1 pixel means max accumulated energy is ~0.085° —
        // the worst-case spike on release is 2 pixels, matching human data.
        quantErrorYaw   = rawDeltaYaw   - quantizedDeltaYaw;
        quantErrorPitch = rawDeltaPitch - quantizedDeltaPitch;
        quantErrorYaw   = Math.max(-mouseStep, Math.min(mouseStep, quantErrorYaw));
        quantErrorPitch = Math.max(-mouseStep, Math.min(mouseStep, quantErrorPitch));

        // Update double-precision tracked angles (never loses precision)
        trackedYaw   += quantizedDeltaYaw;
        trackedPitch  = Math.max(-90, Math.min(90, trackedPitch + quantizedDeltaPitch));

        // Apply to player (float cast here is unavoidable but doesn't feed back)
        player.setYaw((float) trackedYaw);
        player.headYaw = (float) trackedYaw;
        player.setPitch((float) trackedPitch);

        // Sync spring position from double-precision tracked values
        springYawPos   = trackedYaw   - tremorYaw;
        springPitchPos = trackedPitch - tremorPitch;

        // ══ Update debug telemetry ═══════════════════════════════
        dbg_saccadeActive  = inSaccade;
        dbg_saccadeTau     = inSaccade ? Math.min(1.0, saccadeElapsed / saccadeDuration) : 0;
        dbg_yawVel         = springYawVel;
        dbg_pitchVel       = springPitchVel;
        dbg_goalYawRaw     = goalYaw;
        dbg_goalPitchRaw   = goalPitch;
        dbg_goalYawSmooth  = smoothGoalYaw;
        dbg_goalPitchSmooth = smoothGoalPitch;
        dbg_springYaw      = trackedYaw;
        dbg_springPitch    = trackedPitch;
        dbg_tremorYaw      = pureTremorYaw;
        dbg_tremorPitch    = pureTremorPitch;
        dbg_omegaScale     = moveOmegaScale;
        dbg_falling        = isFallingCamera;
        dbg_totalError     = totalError;
        dbg_attention      = attention;
        dbg_inDwell        = postSaccadeDwell > 0;
        dbg_correctionsLeft = postSaccadeCorrections;
        dbg_fatigue        = fatigue;
        dbg_breathOffset   = BREATH_AMP * Math.sin(breathPhase);
        dbg_mouseLift      = mouseLiftPause > 0 && !mouseLiftDone;
        dbg_preEdge        = preEdgeActive && !preEdgeSkip && preEdgePeekStrength > 0;
        dbg_preEdgeStrength = preEdgePeekStrength;
        dbg_preEdgeDist    = (preEdgeActive && preEdgePos != null)
            ? Math.sqrt(sq(preEdgePos.x - player.getX()) + sq(preEdgePos.z - player.getZ()))
            : -1;
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

    // ── Minimum-jerk trajectory (Flash & Hogan 1985) ──────────
    //    Position: 10τ³ − 15τ⁴ + 6τ⁵   (S-curve, smooth 0→1)
    //    Velocity: 30τ² − 60τ³ + 30τ⁴   (bell-shape, peak at τ=0.5)
    //    This is the mathematically optimal trajectory that minimizes
    //    the integral of squared jerk — which is what the human motor
    //    cortex actually optimizes for reaching movements.
    private static double minJerk(double tau) {
        double t2 = tau * tau;
        return t2 * tau * (10.0 - 15.0 * tau + 6.0 * t2);
    }

    private static double minJerkDeriv(double tau) {
        double t2 = tau * tau;
        return 30.0 * t2 * (1.0 - 2.0 * tau + t2);
    }

    // ── Value noise (aperiodic 1/f-like variation) ─────────────
    //    Hash-based interpolated noise. Two octaves give biological
    //    variation without the frequency peaks of periodic sines.
    private static double valueNoise(double x) {
        long ix = (long) Math.floor(x);
        double fx = x - Math.floor(x);
        fx = fx * fx * (3.0 - 2.0 * fx); // smoothstep
        return hash1D(ix) * (1.0 - fx) + hash1D(ix + 1) * fx;
    }

    private static double hash1D(long n) {
        n = ((n >> 16) ^ n) * 0x45d9f3bL;
        n = ((n >> 16) ^ n) * 0x45d9f3bL;
        n = (n >> 16) ^ n;
        return ((n & 0x7fffffffL) / (double) 0x7fffffffL) * 2.0 - 1.0;
    }

    /**
     * Scan path ahead to find landing position after a fall.
     * Looks for where Y stops decreasing (= landed). Returns a point
     * slightly past landing (human anticipation — looking where to go next).
     */
    private static Vec3d findLandingFromPath(List<BlockPos> path, int wpIdx, Vec3d playerPos) {
        if (path == null || wpIdx >= path.size()) return null;

        boolean foundDrop = false;
        int landingIdx = -1;

        for (int i = wpIdx; i + 1 < path.size() && i < wpIdx + 50; i++) {
            int curY  = path.get(i).getY();
            int nextY = path.get(i + 1).getY();

            if (nextY < curY) {
                foundDrop = true;
            } else if (foundDrop && nextY >= curY) {
                landingIdx = i + 1;
                break;
            }
        }

        if (landingIdx < 0) {
            // No clear landing in path — fall may be ongoing, use deepest point
            if (foundDrop) {
                int deepIdx = wpIdx;
                int minY = Integer.MAX_VALUE;
                for (int i = wpIdx; i < Math.min(wpIdx + 50, path.size()); i++) {
                    if (path.get(i).getY() < minY) {
                        minY = path.get(i).getY();
                        deepIdx = i;
                    }
                }
                landingIdx = deepIdx;
            } else {
                return null;
            }
        }

        Vec3d landing = Vec3d.ofCenter(path.get(landingIdx));

        // Human anticipation: look slightly PAST landing along path direction
        int aheadIdx = Math.min(landingIdx + 2, path.size() - 1);
        if (aheadIdx > landingIdx) {
            Vec3d ahead = Vec3d.ofCenter(path.get(aheadIdx));
            // Blend: 70% landing pos, 30% ahead pos (look where you'll go next)
            landing = landing.add(ahead.subtract(landing).multiply(0.3));
        }

        return landing;
    }

    /** Quick check if any of the next 3 waypoints are inside a solid block (for debug HUD). */
    private static boolean isPathBlockedForDebug(net.minecraft.client.world.ClientWorld world,
                                                  List<BlockPos> path, int wpIdx) {
        for (int i = wpIdx; i < Math.min(path.size(), wpIdx + 3); i++) {
            BlockPos node = path.get(i);
            try {
                BlockPos feet = new BlockPos(node.getX(), node.getY(), node.getZ());
                if (!world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()) {
                    return true;
                }
            } catch (Exception e) { /* ignore */ }
        }
        return false;
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
        consecutiveMisses  = 0;
        combatStuckTicks   = 0;
        stuckTicks         = 0;
        stuckKicks         = 0;
        verticalStuckTicks = 0;
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
        // Fall camera
        isFallingCamera     = false;
        fallingCameraTicks  = 0;
        fallReactionDelay   = 0;
        fallLandingPos      = null;
        fallBasePitch       = 0;
        fallSmoothedPitch   = 0;
        postFallRecoveryTicks = 0;
        postFallPitch       = 0;
        // Pre-edge peek
        preEdgeActive       = false;
        preEdgePos          = null;
        preEdgeStartDist    = 0;
        preEdgeSkip         = false;
        preEdgePeekStrength = 0;
        // Motor control state
        inSaccade        = false;
        saccadeElapsed   = 0;
        smoothGoalYaw    = 0;
        smoothGoalPitch  = 0;
        tremorYawPhase   = rng.nextDouble() * 2 * Math.PI;
        tremorPitchPhase = rng.nextDouble() * 2 * Math.PI;
        moveOmegaScale   = 1.0;
        wasInHoldZone    = false;
        // Tier 2 state
        postSaccadeDwell       = 0;
        postSaccadeCorrections = 0;
        postSaccOscPhase       = 0;
        postSaccOscAmp         = 0;
        postSaccOscStartTime   = 0;
        attention              = 1.0;
        attentionTarget        = 1.0;
        nextAttentionChangeTick = 0;
        tickCounter            = 0;
        aimScatterYaw          = 0;
        aimScatterPitch        = 0;
        // Tier 3 state
        mouseLiftPause         = 0;
        mouseLiftDone          = false;
        breathPhase            = rng.nextDouble() * 2 * Math.PI;
        sessionStartNano       = System.nanoTime();
        fatigue                = 0;
        // Quantization
        quantErrorYaw          = 0;
        quantErrorPitch        = 0;
        trackedYaw             = 0;
        trackedPitch           = 0;
        headBobPhase           = 0;
        lastPitchDir           = 0;
        lastYawDir             = 0;
    }
}
