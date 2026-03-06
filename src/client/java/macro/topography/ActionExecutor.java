package macro.topography;

import ai.onnxruntime.*;
import macro.topography.mixin.client.MinecraftClientInvoker;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.io.File;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Applies ONNX neural network predictions to the player.
 *
 * Tick (20 Hz): run inference → set movement keys, CPS-limited clicks, hotbar slot,
 *               compute target yaw/pitch.
 * Frame (FPS):  smooth-interpolate yaw/pitch toward target with ease-out + micro-jitter.
 */
public class ActionExecutor {

    /* ── public state ─────────────────────────────────────────────── */
    public static boolean active = false;

    /* ── ONNX ─────────────────────────────────────────────────────── */
    private static OrtEnvironment ortEnv;
    private static OrtSession    session;
    private static float[][][]   hiddenState;          // [layers][1][hidden]

    private static final int GRU_LAYERS = 2;
    private static final int GRU_HIDDEN = 256;

    /* ── sub-tick smoothing ───────────────────────────────────────── */
    private static float startYaw,  startPitch;
    private static float targetYaw, targetPitch;
    private static long  tickStartNano;
    private static boolean hasTarget = false;
    private static final long TICK_NS = 50_000_000L;   // 50 ms

    /* ── CPS control ──────────────────────────────────────────────── */
    private static int attackTicks   = 0;
    private static int useTicks      = 0;
    private static int nextAttackGap = 2;              // ticks between attacks
    private static int nextUseGap    = 3;

    /* ── feature-builder state ────────────────────────────────────── */
    private static Vec3d prevVel   = Vec3d.ZERO;
    private static Vec3d prevPos   = null;
    private static float prevYaw   = 0f;
    private static float prevPitch = 0f;
    private static float stress    = 0f;
    private static int   debugTick = 0;

    // track what the bot applied last tick (for action features)
    private static final float[] lastKeys = new float[8];
    private static float lastSlotPress = 0f;
    private static int   lastTargetMode = 0;

    /* ── mouse EMA smoothing ───────────────────────────────────── */
    private static float smoothYaw   = 0.5f;
    private static float smoothPitch = 0.5f;
    private static final float MOUSE_ALPHA = 0.4f;  // 0=full smooth, 1=no smooth

    /* ── stuck detection ───────────────────────────────────────── */
    private static int   stuckTicks     = 0;
    private static int   stuckTurnTicks = 0;
    private static float stuckTurnDir   = 1f;

    /* ── warmup: skip first N ticks to stabilize GRU ───────────── */
    private static int   warmupTicks    = 0;
    private static final int WARMUP_COUNT = 5;
    private static final int BASE_FEATURE_COUNT = 86;
    private static final int MOB_COST_OFFSET = BASE_FEATURE_COUNT; // 86..90
    private static final int ANOMALY_OFFSET = MOB_COST_OFFSET + Pathfinder.MOB_PATH_COST_COUNT; // 91..95
    private static final int PATH_OFFSET = ANOMALY_OFFSET + 5; // 96..110
    private static final int FEATURE_COUNT = PATH_OFFSET + Pathfinder.PATH_FEATURE_COUNT; // 111

    /* ================================================================
     *  LIFECYCLE
     * ============================================================== */

    /** Models directory inside .minecraft */
    private static final String MODELS_DIR = "models";

    /**
     * Load ONNX model from .minecraft/models/ directory.
     * @param filename e.g. "bot_model.onnx" or "bruisers.onnx"
     */
    public static boolean loadModel(String filename) {
        try {
            File gameDir = MinecraftClient.getInstance().runDirectory;
            File modelsDir = new File(gameDir, MODELS_DIR);
            if (!modelsDir.exists()) modelsDir.mkdirs();

            File modelFile = new File(modelsDir, filename);
            if (!modelFile.exists()) {
                System.err.println("[ActionExecutor] Model not found: " + modelFile.getAbsolutePath());
                return false;
            }

            ortEnv  = OrtEnvironment.getEnvironment();
            session = ortEnv.createSession(modelFile.getAbsolutePath(), new OrtSession.SessionOptions());
            resetHidden();
            System.out.println("[ActionExecutor] Model loaded: " + modelFile.getAbsolutePath());
            return true;
        } catch (OrtException e) {
            System.err.println("[ActionExecutor] ONNX load failed: " + e.getMessage());
            return false;
        }
    }

    public static void resetHidden() {
        hiddenState = new float[GRU_LAYERS][1][GRU_HIDDEN];
    }

    /** Register tick event. Call once at mod init. Render hook is via GameRendererMixin. */
    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!active || client.player == null || session == null) return;
            onTick(client);
        });
    }

    /** Called every frame from GameRendererMixin. */
    public static void onFrameRender() {
        if (!active || !hasTarget) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) onFrame(mc.player);
    }

    /** Activate the executor. Resets GRU hidden state. */
    public static void start(int targetMode) {
        lastTargetMode = targetMode;
        resetHidden();
        hasTarget = false;
        stress = 0f;
        Arrays.fill(lastKeys, 0f);
        lastSlotPress = 0f;
        smoothYaw = 0.5f;
        smoothPitch = 0.5f;
        stuckTicks = 0;
        stuckTurnTicks = 0;
        warmupTicks = 0;

        Pathfinder.reset();

        // Initialize prev state from current player so first tick isn't garbage
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            prevYaw   = mc.player.getYaw();
            prevPitch = mc.player.getPitch();
            prevVel   = mc.player.getVelocity();
            prevPos   = mc.player.getEntityPos();
        }

        active = true;
        System.out.println("[ActionExecutor] Started (mode " + targetMode + ")");
    }

    /** Deactivate and release all keys. */
    public static void stop() {
        active = false;
        hasTarget = false;
        Pathfinder.stop();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null) releaseAllKeys(mc);
        System.out.println("[ActionExecutor] Stopped");
    }

    /* ================================================================
     *  TICK  (20 Hz)
     * ============================================================== */

    private static void onTick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) return;

        /* warmup: first N ticks just update prev state, no inference */
        if (warmupTicks < WARMUP_COUNT) {
            warmupTicks++;
            prevVel   = player.getVelocity();
            prevPos   = player.getEntityPos();
            prevYaw   = player.getYaw();
            prevPitch = player.getPitch();
            return;
        }

        /* 1. build feature vector */
        float[] feat = buildFeatures(client, player);

        /* 2. inference */
        float[] keys;
        float[] mouse;
        float[] slot;
        try {
            OnnxTensor inputT  = OnnxTensor.createTensor(ortEnv, new float[][]{ feat });
            OnnxTensor hiddenT = OnnxTensor.createTensor(ortEnv, hiddenState);

            OrtSession.Result res = session.run(Map.of(
                "input",     inputT,
                "hidden_in", hiddenT
            ));

            keys  = ((float[][]) res.get("keys").get().getValue())[0];       // [8]
            mouse = ((float[][]) res.get("mouse").get().getValue())[0];      // [2]
            slot  = ((float[][]) res.get("slot").get().getValue())[0];       // [10]
            hiddenState = (float[][][]) res.get("hidden_out").get().getValue();

            res.close();
            inputT.close();
            hiddenT.close();
        } catch (OrtException e) {
            System.err.println("[ActionExecutor] Inference error: " + e.getMessage());
            return;
        }

        /* DEBUG: log features + outputs every 20 ticks (1 sec) */
        debugTick++;
        if (debugTick % 20 == 0) {
            System.out.printf("[Bot] OUT mouse=[%.4f, %.4f] keys=[%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f]%n",
                mouse[0], mouse[1],
                keys[0], keys[1], keys[2], keys[3], keys[4], keys[5], keys[6], keys[7]);
            // Log first 40 features to compare with CSV
            StringBuilder sb = new StringBuilder("[Bot] FEAT ");
            for (int i = 0; i < 40; i++) {
                sb.append(String.format("%.3f,", feat[i]));
            }
            System.out.println(sb);
            // Log entity features (first mob)
            StringBuilder sb2 = new StringBuilder("[Bot] ENT0 ");
            for (int i = 40; i < 49; i++) {
                sb2.append(String.format("%.3f,", feat[i]));
            }
            System.out.println(sb2);
        }

        /* ── FIX 1: Mana guard — suppress RKM if not enough mana ── */
        float manaCurrent = feat[31]; // mana ratio [0,1]
        float manaCost    = feat[32]; // cost ratio [0,1]
        if (manaCurrent < manaCost + 0.02f) {
            keys[7] = 0f; // suppress RKM (ability use)
        }

        /* ── FIX 2: Stuck detection — force turn if position unchanged ── */
        if (prevPos != null) {
            double moved = player.getEntityPos().squaredDistanceTo(prevPos);
            boolean wantsMove = keys[0] > 0.5f || keys[1] > 0.5f || keys[2] > 0.5f || keys[3] > 0.5f;
            if (wantsMove && moved < 0.001) {
                stuckTicks++;
            } else {
                stuckTicks = 0;
                stuckTurnTicks = 0;
            }
            // stuck for 15+ ticks (~0.75s) → force turn for 10 ticks
            if (stuckTicks > 15) {
                if (stuckTurnTicks == 0) {
                    stuckTurnDir = ThreadLocalRandom.current().nextBoolean() ? 1f : -1f;
                }
                stuckTurnTicks++;
                // override mouse to force turn
                mouse[0] = 0.5f + stuckTurnDir * 0.3f; // ±18 degrees
                mouse[1] = 0.5f;
                keys[0] = 0f; // release W while turning
                if (stuckTurnTicks > 10) {
                    stuckTicks = 0;
                    stuckTurnTicks = 0;
                }
            }
        }

        /* ── FIX 3: Wall proximity — suppress forward if about to hit wall ── */
        float wallDist = feat[21]; // dist_to_wall_straight [0,1], 0=touching
        if (wallDist < 0.05f && keys[0] > 0.5f) {
            // very close to wall — don't walk into it
            keys[0] = 0f;
        }

        /* 3. apply movement keys  [W,S,A,D,Space,Ctrl] */
        applyMovement(client, keys);

        /* 4. apply clicks  [LKM, RKM] with CPS jitter */
        applyClicks(client, keys);

        /* 5. apply hotbar slot */
        applySlot(player, slot);

        /* 6. compute rotation target for sub-tick smoothing */
        // Denormalize: model output [0,1] maps to [-30,+30] degrees
        // Dead zone: ignore tiny deltas to prevent drift
        float deltaYaw   = Math.abs(mouse[0] - 0.5f) < 0.03f ? 0f : (mouse[0] - 0.5f) * 60f;
        float deltaPitch  = Math.abs(mouse[1] - 0.5f) < 0.03f ? 0f : (mouse[1] - 0.5f) * 60f;

        startYaw   = player.getYaw();
        startPitch = player.getPitch();
        targetYaw  = startYaw  + deltaYaw;
        targetPitch = MathHelper.clamp(startPitch + deltaPitch, -90f, 90f);
        tickStartNano = System.nanoTime();
        hasTarget = true;

        /* 7. remember applied actions for next feature vector */
        System.arraycopy(keys, 0, lastKeys, 0, 8);
        lastSlotPress = encodeSlotPress(slot);

        /* 8. update prev state */
        prevVel   = player.getVelocity();
        prevPos   = player.getEntityPos();
        prevYaw   = player.getYaw();
        prevPitch = player.getPitch();
    }

    /* ================================================================
     *  RENDER FRAME  (FPS)
     * ============================================================== */

    private static void onFrame(ClientPlayerEntity player) {
        long elapsed = System.nanoTime() - tickStartNano;
        float t = Math.min(1f, (float) elapsed / TICK_NS);

        // quadratic ease-out: fast start, smooth arrival
        float e = 1f - (1f - t) * (1f - t);

        // micro-jitter ±1 % so the curve is never mathematically perfect
        float jitter = (ThreadLocalRandom.current().nextFloat() - 0.5f) * 0.02f;
        e = MathHelper.clamp(e + jitter, 0f, 1f);

        float yaw   = startYaw  + (targetYaw  - startYaw)  * e;
        float pitch = MathHelper.clamp(
                startPitch + (targetPitch - startPitch) * e,
                -90f, 90f);

        player.setYaw(yaw);
        player.setPitch(pitch);
        // keep head yaw in sync so the server sees the same value
        player.headYaw = yaw;
    }

    /* ================================================================
     *  ACTION APPLICATION
     * ============================================================== */

    private static void applyMovement(MinecraftClient client, float[] keys) {
        GameOptions o = client.options;
        o.forwardKey.setPressed(keys[0] > 0.5f);
        o.backKey   .setPressed(keys[1] > 0.5f);
        o.leftKey   .setPressed(keys[2] > 0.5f);
        o.rightKey  .setPressed(keys[3] > 0.5f);
        o.jumpKey   .setPressed(keys[4] > 0.5f);
        o.sprintKey .setPressed(keys[5] > 0.5f);
    }

    private static void applyClicks(MinecraftClient client, float[] keys) {
        boolean wantAttack = keys[6] > 0.5f;
        boolean wantUse    = keys[7] > 0.5f;

        /* ── LKM (attack) ── */
        attackTicks++;
        if (wantAttack && attackTicks >= nextAttackGap) {
            ((MinecraftClientInvoker) client).invokeDoAttack();
            attackTicks = 0;
            nextAttackGap = 2 + ThreadLocalRandom.current().nextInt(2); // 2-3 ticks → 7-10 CPS
        }
        if (!wantAttack) {
            client.options.attackKey.setPressed(false);
            attackTicks = nextAttackGap;                               // ready for instant first hit
        }

        /* ── RKM (use) ── */
        useTicks++;
        if (wantUse && useTicks >= nextUseGap) {
            ((MinecraftClientInvoker) client).invokeDoItemUse();
            useTicks = 0;
            nextUseGap = 2 + ThreadLocalRandom.current().nextInt(3);   // 2-4 ticks → 5-10 CPS
        }
        if (!wantUse) {
            client.options.useKey.setPressed(false);
            useTicks = nextUseGap;
        }
    }

    private static void applySlot(ClientPlayerEntity player, float[] slot) {
        int argmax = 0;
        float maxVal = slot[0];
        for (int i = 1; i < slot.length; i++) {
            if (slot[i] > maxVal) { maxVal = slot[i]; argmax = i; }
        }
        if (argmax > 0) {
            player.getInventory().setSelectedSlot(argmax - 1);
        }
    }

    private static void releaseAllKeys(MinecraftClient client) {
        GameOptions o = client.options;
        o.forwardKey.setPressed(false);
        o.backKey   .setPressed(false);
        o.leftKey   .setPressed(false);
        o.rightKey  .setPressed(false);
        o.jumpKey   .setPressed(false);
        o.sprintKey .setPressed(false);
        o.attackKey .setPressed(false);
        o.useKey    .setPressed(false);
    }

    /* ================================================================
     *  FEATURE VECTOR  (base + path horizon, all [0,1])
     * ============================================================== */

    private static float[] buildFeatures(MinecraftClient client, ClientPlayerEntity player) {
        float[] f = new float[FEATURE_COUNT];

        Vec3d vel = player.getVelocity();
        Vec3d pos = player.getEntityPos();
        float yawRaw   = player.getYaw();
        float pitchRaw = player.getPitch();

        /* [0] active_slot */
        f[0] = player.getInventory().getSelectedSlot() / 8f;
        /* [1] on_ground */
        f[1] = player.isOnGround() ? 1f : 0f;
        /* [2] is_sprinting */
        f[2] = player.isSprinting() ? 1f : 0f;
        /* [3] is_jumping — use actual game state, not model output */
        GameOptions opts = client.options;
        f[3] = opts.jumpKey.isPressed() ? 1f : 0f;
        /* [4] yaw  [0,1] */
        f[4] = ((yawRaw % 360f + 360f) % 360f) / 360f;
        /* [5] pitch [0,1] */
        f[5] = (pitchRaw + 90f) / 180f;

        /* [6..9] W, S, A, D — use actual pressed state, not model feedback */
        f[6]  = opts.forwardKey.isPressed() ? 1f : 0f;
        f[7]  = opts.backKey.isPressed()    ? 1f : 0f;
        f[8]  = opts.leftKey.isPressed()    ? 1f : 0f;
        f[9]  = opts.rightKey.isPressed()   ? 1f : 0f;
        /* [10] slot_to_press */
        f[10] = lastSlotPress;
        /* [11] l_click */
        f[11] = opts.attackKey.isPressed() ? 1f : 0f;
        /* [12] r_click */
        f[12] = opts.useKey.isPressed()    ? 1f : 0f;

        /* [13] delta_yaw  [0,1] in [-30,30] */
        f[13] = clamp01((normalizeAngle(yawRaw - prevYaw) + 30f) / 60f);
        /* [14] delta_pitch [0,1] in [-30,30] */
        f[14] = clamp01((pitchRaw - prevPitch + 30f) / 60f);

        /* [15..17] velocity in [-1,1] */
        f[15] = clamp01((float) ((vel.x + 1) / 2.0));
        f[16] = clamp01((float) ((vel.y + 1) / 2.0));
        f[17] = clamp01((float) ((vel.z + 1) / 2.0));
        /* [18..20] acceleration in [-0.5,0.5] */
        f[18] = clamp01((float) ((vel.x - prevVel.x + 0.5) / 1.0));
        f[19] = clamp01((float) ((vel.y - prevVel.y + 0.5) / 1.0));
        f[20] = clamp01((float) ((vel.z - prevVel.z + 0.5) / 1.0));

        /* [21..23] wall distances (3 rays at 1.6 m) */
        f[21] = RaycastHelper.calcDistToWallOrBlock(client, player,   0f, 1.6f);
        f[22] = RaycastHelper.calcDistToWallOrBlock(client, player,  45f, 1.6f);
        f[23] = RaycastHelper.calcDistToWallOrBlock(client, player, -45f, 1.6f);

        /* [24..29] block distances (3 rays at 0.2 m) */
        float[] bS = RaycastHelper.calcDistToBlock(client, player,   0f);
        float[] bL = RaycastHelper.calcDistToBlock(client, player, -45f);
        float[] bR = RaycastHelper.calcDistToBlock(client, player,  45f);
        f[24] = bS[0]; f[25] = bS[1];
        f[26] = bL[0]; f[27] = bL[1];
        f[28] = bR[0]; f[29] = bR[1];

        /* [30] vertical_clearance */
        f[30] = RaycastHelper.calcVerticalClearance(client, player);

        /* [31] mana */
        f[31] = DataCollector.maxMana > 0
                ? (float) DataCollector.currentMana / DataCollector.maxMana : 0f;
        /* [32] mana_cost_aotev */
        f[32] = DataCollector.maxMana > 0
                ? clamp01(50f / DataCollector.maxMana) : 0.05f;
        /* [33] slot_aotev */
        f[33] = getAotevSlot(player);

        /* [34] time_to_collision */
        double velHz = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        f[34] = velHz > 0.01
                ? clamp01((float) ((f[21] * 10.0) / velHz / 20.0))
                : 1f;

        /* [35] current_mode */
        int cm = detectMode(client, player);
        f[35] = cm / 2f;
        /* [36] target_mode */
        f[36] = lastTargetMode / 2f;

        /* [37..39] ground probes */
        f[37] = RaycastHelper.calcGroundProbe(client, player, 1.5f);
        f[38] = RaycastHelper.calcGroundProbe(client, player, 3.0f);
        f[39] = RaycastHelper.calcGroundProbe(client, player, 4.5f);

        /* [40] block_below_height — slab=0.5, full=1.0, air=0.0 */
        if (client.world != null) {
            net.minecraft.util.math.BlockPos belowPos = player.getBlockPos().down();
            net.minecraft.block.BlockState belowState = client.world.getBlockState(belowPos);
            net.minecraft.util.shape.VoxelShape shape = belowState.getCollisionShape(client.world, belowPos);
            if (!shape.isEmpty()) {
                f[40] = clamp01((float) shape.getMax(net.minecraft.util.math.Direction.Axis.Y));
            }
        }

        /* [41..85] entity block (5 mobs × 9 features) */
        fillEntityBlock(client, player, pos, f);

        /* [86..90] per-mob path costs (same order as CSV) */
        float[] mobCosts = Pathfinder.getMobPathCosts();
        for (int i = 0; i < Pathfinder.MOB_PATH_COST_COUNT; i++) {
            f[MOB_COST_OFFSET + i] = mobCosts[i];
        }

        /* [91..95] anomalies + stress + ping */
        float yawDiffA  = Math.abs(normalizeAngle(yawRaw - prevYaw))  / 180f;
        float pitchDiffA = Math.abs(pitchRaw - prevPitch) / 90f;
        float rotAnomaly = clamp01((yawDiffA + pitchDiffA) / 2f);

        float posAnomaly = 0f;
        if (DataCollector.serverPosEvent && prevPos != null && DataCollector.aoteGraceTicks <= 0) {
            double actual   = pos.distanceTo(prevPos);
            double expected = vel.length();
            posAnomaly = clamp01((float) (Math.abs(actual - expected) / 25.0));
        }
        DataCollector.serverPosEvent = false;

        float velAnomaly = 0f;
        if (DataCollector.serverVelEvent) {
            velAnomaly = clamp01(Math.abs((float) vel.length() - (float) prevVel.length()) / 2f);
            DataCollector.serverVelEvent = false;
        }

        float tickStress = clamp01(rotAnomaly * 0.35f + posAnomaly * 0.35f + velAnomaly * 0.3f);
        stress = tickStress > stress ? tickStress : Math.max(0f, stress - 0.05f);
        float ping = clamp01(getPlayerPingMs(client, player) / 1000.0f);
        f[ANOMALY_OFFSET] = rotAnomaly;
        f[ANOMALY_OFFSET + 1] = posAnomaly;
        f[ANOMALY_OFFSET + 2] = velAnomaly;
        f[ANOMALY_OFFSET + 3] = stress;
        f[ANOMALY_OFFSET + 4] = ping;

        /* [96..110] path horizon from Pathfinder */
        Pathfinder.update(client, player, lastTargetMode, cm, f[21], f[30], stress, posAnomaly);
        float[] pathRel = Pathfinder.getPathRelative(pos);
        int pathCount = Math.min(pathRel.length, Pathfinder.PATH_FEATURE_COUNT);
        for (int i = 0; i < pathCount; i++) {
            f[PATH_OFFSET + i] = pathRel[i];
        }

        return f;
    }

    /* ── entity helpers ───────────────────────────────────────────── */

    private static void fillEntityBlock(MinecraftClient client, ClientPlayerEntity player,
                                        Vec3d pos, float[] f) {
        List<LivingEntity> mobs = getNearestMobs(client, player);
        for (int i = 0; i < 5; i++) {
            int base = 41 + i * 9;
            if (i < mobs.size()) {
                LivingEntity mob = mobs.get(i);
                Vec3d rel = mob.getEntityPos().subtract(pos);
                f[base]     = clamp01((float) ((rel.x / 50.0 + 1) / 2.0));
                f[base + 1] = clamp01((float) ((rel.y / 20.0 + 1) / 2.0));
                f[base + 2] = clamp01((float) ((rel.z / 50.0 + 1) / 2.0));
                f[base + 3] = getEntityType(mob) / 2f;
                f[base + 4] = isVisible(client, player, mob) ? 1f : 0f;
                f[base + 5] = getYawDiff(player, mob);
                float[] comp = getCompetitorData(client, player, mob);
                f[base + 6] = comp[0];
                f[base + 7] = comp[1];
                f[base + 8] = comp[2];
            } else {
                // sentinel — no mob
                f[base]     = 0f;
                f[base + 1] = 0f;
                f[base + 2] = 0f;
                f[base + 3] = 0f;
                f[base + 4] = 0f;
                f[base + 5] = 0.5f;
                f[base + 6] = 0f;
                f[base + 7] = 1f;
                f[base + 8] = 0.5f;
            }
        }
    }

    private static List<LivingEntity> getNearestMobs(MinecraftClient client,
                                                      ClientPlayerEntity player) {
        if (client.world == null) return Collections.emptyList();

        // Step 1: Find ArmorStand name tags with matching mob names
        List<ArmorStandEntity> nameTags = new ArrayList<>();
        for (LivingEntity e : client.world.getEntitiesByClass(
                LivingEntity.class, player.getBoundingBox().expand(50),
                ent -> ent instanceof ArmorStandEntity)) {
            String n = getMobName(e);
            if (n.isEmpty()) continue;
            boolean match = false;
            if (lastTargetMode == 1) match = n.contains("Zealot") && !n.contains("Bruiser");
            else if (lastTargetMode == 2) match = n.contains("Bruiser");
            else match = n.contains("Zealot") || n.contains("Bruiser");
            if (match) nameTags.add((ArmorStandEntity) e);
        }

        // Step 2: For each name tag, find the nearest real mob within 4 blocks
        Set<LivingEntity> found = new LinkedHashSet<>();
        for (ArmorStandEntity tag : nameTags) {
            LivingEntity mob = findRealMobNear(client, player, tag);
            if (mob != null) found.add(mob);
        }

        // Debug: log what we found every ~5 sec
        if (debugTick % 100 == 0) {
            System.out.println("[Bot] Name tags found: " + nameTags.size()
                + ", Real mobs matched: " + found.size());
        }

        List<LivingEntity> mobs = new ArrayList<>(found);
        Vec3d pp = player.getEntityPos();
        mobs.sort(Comparator.comparingDouble(e -> e.getEntityPos().distanceTo(pp)));
        return mobs.size() > 5 ? mobs.subList(0, 5) : mobs;
    }

    /** Find the nearest non-ArmorStand LivingEntity within 4 blocks of a name tag. */
    private static LivingEntity findRealMobNear(MinecraftClient client,
                                                  ClientPlayerEntity player,
                                                  ArmorStandEntity nameTag) {
        if (client.world == null) return null;
        LivingEntity closest = null;
        double closestDist = 4.0;
        Vec3d tagPos = nameTag.getEntityPos();
        for (LivingEntity e : client.world.getEntitiesByClass(
                LivingEntity.class, nameTag.getBoundingBox().expand(4),
                ent -> !(ent instanceof ArmorStandEntity)
                    && !(ent instanceof PlayerEntity)
                    && ent != player)) {
            double d = e.getEntityPos().distanceTo(tagPos);
            if (d < closestDist) {
                closestDist = d;
                closest = e;
            }
        }
        return closest;
    }

    private static int getEntityType(LivingEntity e) {
        // Real mob is an Enderman — check nearby ArmorStand name tags
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world != null) {
            for (LivingEntity nearby : client.world.getEntitiesByClass(
                    LivingEntity.class, e.getBoundingBox().expand(4),
                    ent -> ent instanceof ArmorStandEntity)) {
                String n = getMobName(nearby);
                if (n.contains("Bruiser")) return 1;
                if (n.contains("Special")) return 2;
            }
        }
        return 0; // Zealot or unknown
    }

    private static boolean isVisible(MinecraftClient client, ClientPlayerEntity player,
                                     LivingEntity mob) {
        if (client.world == null) return false;
        Vec3d start = player.getEyePos();
        Vec3d end   = mob.getEntityPos().add(0, mob.getHeight() / 2.0, 0);
        BlockHitResult hit = client.world.raycast(new RaycastContext(
            start, end,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            player
        ));
        return hit.getType() == HitResult.Type.MISS;
    }

    private static float getYawDiff(ClientPlayerEntity player, LivingEntity mob) {
        Vec3d toMob = mob.getEntityPos().subtract(player.getEntityPos());
        double len = Math.sqrt(toMob.x * toMob.x + toMob.z * toMob.z);
        if (len < 0.001) return 0.5f;
        Vec3d look = Vec3d.fromPolar(0, player.getYaw());
        double dot = (look.x * toMob.x + look.z * toMob.z) / len;
        double angle = Math.acos(MathHelper.clamp(dot, -1.0, 1.0));
        return clamp01((float) (angle / Math.PI));
    }

    private static float[] getCompetitorData(MinecraftClient client, ClientPlayerEntity player,
                                             LivingEntity mob) {
        if (client.world == null) return new float[]{0f, 1f, 0.5f};
        Vec3d mobPos = mob.getEntityPos();
        double myDist = player.getEntityPos().distanceTo(mobPos);
        int count = 0;
        double minDist = Double.MAX_VALUE;
        float intent = 0.5f;
        for (PlayerEntity other : client.world.getPlayers()) {
            if (other == player) continue;
            double d = other.getEntityPos().distanceTo(mobPos);
            if (d < myDist) {
                count++;
                if (d < minDist) {
                    minDist = d;
                    Vec3d toM = mobPos.subtract(other.getEntityPos()).normalize();
                    double dot = other.getVelocity().dotProduct(toM);
                    intent = clamp01((float) ((dot + 1) / 2.0));
                }
            }
        }
        return new float[]{
            clamp01(count / 10f),
            minDist == Double.MAX_VALUE ? 1f : clamp01((float) (minDist / 50f)),
            intent
        };
    }

    private static int detectMode(MinecraftClient client, ClientPlayerEntity player) {
        if (client.world == null || lastTargetMode == 0) return 0;
        // Check if any ArmorStand name tags with matching names exist nearby
        for (LivingEntity e : client.world.getEntitiesByClass(
                LivingEntity.class, player.getBoundingBox().expand(20),
                ent -> ent instanceof ArmorStandEntity)) {
            String n = getMobName(e);
            if (n.isEmpty()) continue;
            if (lastTargetMode == 1 && n.contains("Zealot") && !n.contains("Bruiser")) return lastTargetMode;
            if (lastTargetMode == 2 && n.contains("Bruiser")) return lastTargetMode;
        }
        return 0;
    }

    /** Get mob name from custom name or display name, stripping color codes. */
    private static String getMobName(LivingEntity e) {
        if (e.hasCustomName() && e.getCustomName() != null) {
            return e.getCustomName().getString().replaceAll("§.", "");
        }
        return e.getName().getString().replaceAll("§.", "");
    }

    /* ── misc helpers ─────────────────────────────────────────────── */

    private static float getAotevSlot(ClientPlayerEntity player) {
        for (int i = 0; i < 9; i++) {
            ItemStack item = player.getInventory().getStack(i);
            if (item.isEmpty()) continue;
            String name = item.getName().getString().replaceAll("§.", "");
            if (name.contains("Aspect of the End") || name.contains("Aspect of the Void"))
                return (i + 1) / 9f;
        }
        return 0f;
    }

    /** Encode selected slot the same way DataCollector does: argmax → (idx+1)/9 or 0 */
    private static float encodeSlotPress(float[] slot) {
        int argmax = 0;
        float max = slot[0];
        for (int i = 1; i < slot.length; i++) {
            if (slot[i] > max) { max = slot[i]; argmax = i; }
        }
        return argmax > 0 ? argmax / 9f : 0f;
    }

    private static float normalizeAngle(float a) {
        while (a >  180) a -= 360;
        while (a < -180) a += 360;
        return a;
    }

    private static int getPlayerPingMs(MinecraftClient client, ClientPlayerEntity player) {
        if (client.getNetworkHandler() == null || player == null) return 0;
        PlayerListEntry entry = client.getNetworkHandler().getPlayerListEntry(player.getUuid());
        if (entry == null) return 0;
        return Math.max(0, entry.getLatency());
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
