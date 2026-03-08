package macro.topography;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.chunk.WorldChunk;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lazy Theta* pathfinder for End island navigation + mob targeting.
 * Runs async with a thread-safe block cache snapshot, provides waypoint
 * direction as normalized [0,1] features.
 */
public class Pathfinder {

    /* ── destinations ─────────────────────────────────────────────── */
    private static final BlockPos DRAGONS_NEST = new BlockPos(-621, 15, 275);
    private static final BlockPos BRUISER_PORTAL_WAYPOINT = new BlockPos(-621, 15, -282);
    private static final BlockPos BRUISER_PORTAL_WAYPOINT_2 = new BlockPos(-619, 8, -292);
    private static final BlockPos BRUISER_PORTAL_APPROACH = new BlockPos(-616, 5, -280);
    private static final BlockPos BRUISER_HIDEOUT = new BlockPos(-616, 5, -281);

    // ╔══════════════════════════════════════════════════════════════╗
    // ║                      FARM ZONES                             ║
    // ║  Каждая строка: { minX, maxX, minY, maxY, minZ, maxZ }     ║
    // ║  Добавляй новые прямоугольники в нужный массив             ║
    // ╠══════════════════════════════════════════════════════════════╣
    // ║  ZEALOT ZONES  (Dragon's Nest)                              ║
    // ╠══════════════════════════════════════════════════════════════╣
    // ║  BRUISER ZONES  (Bruiser Hideout)                           ║
    // ║  [0]  X: -625 .. -542   Y: 48 .. 97   Z: -250 .. -185     ║
    // ║  [1]  X: -548 .. -502   Y: 38 .. 56   Z: -289 .. -214     ║
    // ╚══════════════════════════════════════════════════════════════╝

    // { minX, maxX, minY, maxY, minZ, maxZ }
    private static final double ZEALOT_MIN_X  = -675, ZEALOT_MAX_X  = -565;
    private static final double ZEALOT_MIN_Y  =    5, ZEALOT_MAX_Y  =   50;
    private static final double ZEALOT_MIN_Z  =  225, ZEALOT_MAX_Z  =  335;

    private static final double[][] BRUISER_ZONES = {
        // { minX, maxX, minY, maxY, minZ, maxZ }
        { -625, -542,  48,  97, -250, -185 },  // зона 1
        { -548, -502,  38,  56, -297, -214 },  // зона 2
        { -539, -502,  38,  52, -329, -277 },  // zone 3
    };

    // Исключения — дыры внутри BRUISER_ZONES (здесь зона НЕ считается фарм-зоной)
    // { minX, maxX, minY, maxY, minZ, maxZ }
    private static final double[][] BRUISER_EXCLUDE_ZONES = {
        { -549, -538,  38,  97, -280, -253 },  // исключение 1
    };

    /* ── search limits ─────────────────────────────────────────────── */
    private static final int MAX_NODES = 10_000;
    private static final int MAX_FALL = 40;
    private static final int CACHE_RADIUS = 80;
    private static final double MAX_PATHFIND_DIST = 50.0; // intermediate goal distance
    private static final int MAX_CACHE_AREA = 250 * 250;
    private static final double WALL_PROXIMITY_COST = 0.45;
    private static final int WALL_COST_RADIUS = 2;
    private static final float WALL_REPLAN_THRESH = 0.08f;
    private static final float VERTICAL_REPLAN_THRESH = 0.15f;
    private static final float POS_ANOMALY_RESET_THRESH = 0.15f;
    private static final long SNAPSHOT_REUSE_MS = 650L;
    private static final int SNAPSHOT_COVER_MARGIN = 8;
    private static final double HEURISTIC_WEIGHT = 1.22;

    /* ── waypoint management ──────────────────────────────────────── */
    private static final double ADVANCE_RADIUS_BASE = 1.5;
    private static final double ADVANCE_RADIUS_MAX = 3.0;
    private static final double FINAL_RADIUS = 0.3;
    private static final double WAYPOINT_PROGRESS_EPS_SQ = 0.05;
    private static final double GOAL_TIGHTEN_DIST = 5.0; // start tightening radius within this distance
    public static final int PATH_HORIZON_POINTS = 5;
    public static final int PATH_FEATURE_COUNT = PATH_HORIZON_POINTS * 3;

    /* ── async control ────────────────────────────────────────────── */
    private static final double DEVIATE_DIST = 5.0;
    private static final double GOAL_SHIFT_REPLAN_DIST = 3.0;
    private static final double GOAL_SHIFT_REPLAN_DIST_SQ = GOAL_SHIFT_REPLAN_DIST * GOAL_SHIFT_REPLAN_DIST;
    private static final int BLOCK_VALIDATION_POINTS = 4;
    private static final float KNOCKBACK_REPLAN_VEL_ANOMALY = 0.12f;
    private static final double LANDING_FALL_SPEED_THRESH = -0.12;

    /* ── mob targeting ────────────────────────────────────────────── */
    private static final double ATTACK_STANDOFF = 1.75;
    private static final double PATH_COST_MAX = 100.0; // normalize path costs to [0,1]
    private static final float TARGET_SWITCH_COST_RATIO_BASE = 1.3f;
    private static final float TARGET_SWITCH_COST_RATIO_MAX  = 2.5f;
    private static final long  TARGET_SWITCH_DELAY_MS_MIN = 200L;
    private static final long  TARGET_SWITCH_DELAY_MS_MAX = 600L;
    private static final float FOV_BIAS_WEIGHT = 0.5f;        // angular cost multiplier
    private static final float HIDDEN_MOB_PENALTY = 1.8f;     // cost multiplier for non-visible mobs
    private static final float SPECIAL_ZEALOT_DISCOUNT = 0.15f; // Special Zealots get cost * 0.15 (massive priority)
    private static final double COMMITMENT_RADIUS = 15.0;      // don't switch if closer than this
    private static final float SOFT_CHOICE_THRESHOLD = 0.15f;  // 15% cost margin for randomization
    public static final int MOB_PATH_COST_COUNT = 5;

    /* ── state ────────────────────────────────────────────────────── */
    private static List<BlockPos> currentPath = Collections.emptyList();
    private static int waypointIndex = 0;
    private static final AtomicBoolean computing = new AtomicBoolean(false);
    private static CompletableFuture<List<BlockPos>> pendingFuture = null;
    private static BlockPos lastGoal = null;
    private static BlockPos pendingGoal = null;
    private static String forcedRepathReason = null;
    private static LivingEntity targetMob = null;        // real mob for hitbox (can be null)
    private static ArmorStandEntity targetNameTag = null; // name tag for position
    private static final Set<UUID> seenNameTagIds = new HashSet<>();
    private static final Set<UUID> pendingSpawnTagIds = new HashSet<>();
    private static long lastNewNameTagMs = 0L;
    private static long targetAcquiredMs = 0L;          // when current target was picked
    private static long currentSwitchDelayMs = 200L;    // randomized per-spawn delay
    private static BlockPos roamingGoal = null;          // random point to walk to when no mobs
    private static int activeTargetMode = 0;
    // 0 = none reached, 1 = first waypoint reached, 2 = second waypoint reached
    private static int bruiserWaypointStage = 0;
    private static float lastDistToWall = 1f;
    private static float lastVerticalClearance = 1f;
    private static float lastStressLevel = 0f;
    private static float lastPosAnomaly = 0f;
    private static Vec3d lastPlayerPos = null;
    private static Vec3d lastPlayerVelocity = Vec3d.ZERO;
    private static boolean wasOnGround = true;

    /* ── per-mob path costs (cached, updated async) ── */
    private static final float[] mobPathCosts = new float[MOB_PATH_COST_COUNT]; // [0,1] normalized
    private static final Map<UUID, Float> mobPathCostByTag = new HashMap<>();
    private static List<BlockPos> mobGoals = Collections.emptyList(); // standable goals for up to 5 mobs
    private static List<LivingEntity> targetMobs = Collections.emptyList(); // real mobs for hitbox
    private static int mobCostTicks = 0;
    private static final int MOB_COST_INTERVAL = 20; // recalc every 1 sec
    private static CompletableFuture<MobCostResult> pendingMobCosts = null;
    private static final boolean EVENT_LOG_ENABLED = true;
    private static final long HEARTBEAT_LOG_INTERVAL_MS = 5000L;
    private static long lastHeartbeatLogMs = 0L;
    private static long pathTaskSeq = 0L;
    private static long pendingPathTaskId = 0L;
    private static long pendingPathTaskStartedMs = 0L;
    private static long mobCostTaskSeq = 0L;
    private static long pendingMobCostTaskId = 0L;
    private static long pendingMobCostTaskStartedMs = 0L;
    private static final Object snapshotLock = new Object();
    private static CachedSnapshot cachedSnapshot = null;

    private static class CachedSnapshot {
        final ClientWorld world;
        final BlockCache cache;
        final long createdAtMs;

        CachedSnapshot(ClientWorld world, BlockCache cache, long createdAtMs) {
            this.world = world;
            this.cache = cache;
            this.createdAtMs = createdAtMs;
        }
    }

    private static class MobCostResult {
        final float[] costs;
        final Map<UUID, Float> costsByTag;
        final List<LivingEntity> renderedMobs;

        MobCostResult(float[] costs, Map<UUID, Float> costsByTag, List<LivingEntity> renderedMobs) {
            this.costs = costs;
            this.costsByTag = costsByTag;
            this.renderedMobs = renderedMobs;
        }
    }

    /* ================================================================
     *  BLOCK CACHE — snapshot on main thread, read safely from async
     * ============================================================== */

    private static void eventLog(String format, Object... args) {
        if (!EVENT_LOG_ENABLED) return;
        long now = System.currentTimeMillis();
        String message = String.format(Locale.ROOT, format, args);
        System.out.printf(Locale.ROOT, "[Pathfinder][%d] %s%n", now, message);
    }

    private static String shortUuid(UUID id) {
        if (id == null) return "null";
        String s = id.toString();
        return s.length() <= 8 ? s : s.substring(0, 8);
    }

    private static String tagInfo(ArmorStandEntity tag) {
        if (tag == null) return "null";
        Vec3d pos = tag.getEntityPos();
        return String.format(
            Locale.ROOT,
            "%s/%s alive=%s removed=%s pos=%.1f,%.1f,%.1f",
            getMobName(tag),
            shortUuid(tag.getUuid()),
            tag.isAlive(),
            tag.isRemoved(),
            pos.x, pos.y, pos.z
        );
    }

    private static String mobInfo(LivingEntity mob) {
        if (mob == null) return "null";
        Vec3d pos = mob.getEntityPos();
        return String.format(
            Locale.ROOT,
            "%s/%s alive=%s removed=%s pos=%.1f,%.1f,%.1f",
            mob.getType().getTranslationKey(),
            shortUuid(mob.getUuid()),
            mob.isAlive(),
            mob.isRemoved(),
            pos.x, pos.y, pos.z
        );
    }

    private static void requestImmediateRepath(String reason) {
        if (reason == null || reason.isBlank()) return;
        if (forcedRepathReason == null || forcedRepathReason.isBlank()) {
            forcedRepathReason = reason;
            return;
        }
        if (!forcedRepathReason.contains(reason)) {
            forcedRepathReason = forcedRepathReason + "+" + reason;
        }
    }

    private static class BlockCache {
        private final int minX;
        private final int maxX;
        private final int minY;
        private final int maxY;
        private final int minZ;
        private final int maxZ;
        private final int sizeY;
        private final int sizeZ;
        private final BitSet solidBits;
        private final boolean empty;

        private BlockCache(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
            this.minZ = minZ;
            this.maxZ = maxZ;

            int sx = maxX - minX + 1;
            int sy = maxY - minY + 1;
            int sz = maxZ - minZ + 1;
            if (sx <= 0 || sy <= 0 || sz <= 0) {
                this.empty = true;
                this.sizeY = 0;
                this.sizeZ = 0;
                this.solidBits = new BitSet();
            } else {
                this.empty = false;
                this.sizeY = sy;
                this.sizeZ = sz;
                this.solidBits = new BitSet(sx * sy * sz);
            }
        }

        static BlockCache empty() {
            return new BlockCache(0, -1, 0, -1, 0, -1);
        }

        /** Build cache by scanning loaded chunks in target area. */
        static BlockCache snapshotBetweenChunks(ClientWorld world, BlockPos start, BlockPos goal) {
            int minX = Math.min(start.getX(), goal.getX()) - 10;
            int maxX = Math.max(start.getX(), goal.getX()) + 10;
            int minZ = Math.min(start.getZ(), goal.getZ()) - 10;
            int maxZ = Math.max(start.getZ(), goal.getZ()) + 10;
            int minY = Math.max(-64, Math.min(start.getY(), goal.getY()) - MAX_FALL - 5);
            int maxY = Math.max(start.getY(), goal.getY()) + 20;

            if ((maxX - minX) * (maxZ - minZ) > MAX_CACHE_AREA) {
                minX = start.getX() - CACHE_RADIUS;
                maxX = start.getX() + CACHE_RADIUS;
                minZ = start.getZ() - CACHE_RADIUS;
                maxZ = start.getZ() + CACHE_RADIUS;
                minY = Math.max(-64, start.getY() - MAX_FALL - 5);
                maxY = start.getY() + 20;
            }

            BlockCache cache = new BlockCache(minX, maxX, minY, maxY, minZ, maxZ);
            if (cache.empty) return cache;

            BlockPos.Mutable mutable = new BlockPos.Mutable();
            int minChunkX = Math.floorDiv(minX, 16);
            int maxChunkX = Math.floorDiv(maxX, 16);
            int minChunkZ = Math.floorDiv(minZ, 16);
            int maxChunkZ = Math.floorDiv(maxZ, 16);

            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    WorldChunk chunk;
                    try {
                        chunk = world.getChunk(chunkX, chunkZ);
                    } catch (Exception e) {
                        continue;
                    }
                    if (chunk == null || chunk.isEmpty()) continue;

                    int chunkMinX = Math.max(minX, chunkX << 4);
                    int chunkMaxX = Math.min(maxX, (chunkX << 4) + 15);
                    int chunkMinZ = Math.max(minZ, chunkZ << 4);
                    int chunkMaxZ = Math.min(maxZ, (chunkZ << 4) + 15);

                    for (int x = chunkMinX; x <= chunkMaxX; x++) {
                        for (int z = chunkMinZ; z <= chunkMaxZ; z++) {
                            for (int y = minY; y <= maxY; y++) {
                                mutable.set(x, y, z);
                                try {
                                    BlockState state = chunk.getBlockState(mutable);
                                    boolean solid = !state.getCollisionShape(world, mutable).isEmpty();
                                    cache.setSolid(x, y, z, solid);
                                } catch (Exception e) {
                                    // Unknown states are treated as passable.
                                }
                            }
                        }
                    }
                }
            }

            return cache;
        }

        boolean covers(BlockPos start, BlockPos goal, int margin) {
            if (empty) return false;
            int reqMinX = Math.min(start.getX(), goal.getX()) - margin;
            int reqMaxX = Math.max(start.getX(), goal.getX()) + margin;
            int reqMinZ = Math.min(start.getZ(), goal.getZ()) - margin;
            int reqMaxZ = Math.max(start.getZ(), goal.getZ()) + margin;
            int reqMinY = Math.max(-64, Math.min(start.getY(), goal.getY()) - MAX_FALL - 3);
            int reqMaxY = Math.max(start.getY(), goal.getY()) + 8;

            return reqMinX >= minX && reqMaxX <= maxX
                && reqMinY >= minY && reqMaxY <= maxY
                && reqMinZ >= minZ && reqMaxZ <= maxZ;
        }

        boolean isSolid(int x, int y, int z) {
            if (!inBounds(x, y, z)) return false;
            return solidBits.get(index(x, y, z));
        }

        boolean isPassable(int x, int y, int z) {
            return !isSolid(x, y, z);
        }

        boolean isStandable(int x, int y, int z) {
            return isSolid(x, y - 1, z)
                && isPassable(x, y, z)
                && isPassable(x, y + 1, z);
        }

        private boolean inBounds(int x, int y, int z) {
            return !empty
                && x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
        }

        private int index(int x, int y, int z) {
            int ox = x - minX;
            int oy = y - minY;
            int oz = z - minZ;
            return ((ox * sizeY) + oy) * sizeZ + oz;
        }

        private void setSolid(int x, int y, int z, boolean solid) {
            if (!solid || !inBounds(x, y, z)) return;
            solidBits.set(index(x, y, z));
        }
    }

    /* ================================================================
     *  PUBLIC API
     * ============================================================== */

    public static void update(MinecraftClient client, ClientPlayerEntity player,
                              int targetMode, int currentMode,
                              float distToWall, float verticalClearance,
                              float stressLevel, float posAnomaly, float velAnomaly) {
        if (client.world == null || player == null) return;

        activeTargetMode = targetMode;
        Vec3d pos = player.getEntityPos();
        Vec3d velocity = player.getVelocity();
        boolean onGround = player.isOnGround();
        lastDistToWall = distToWall;
        lastVerticalClearance = verticalClearance;
        lastStressLevel = stressLevel;
        lastPosAnomaly = posAnomaly;

        if (posAnomaly >= POS_ANOMALY_RESET_THRESH) {
            forceResetForTeleport(stressLevel);
        }

        boolean farmMode = (targetMode != 0 && isInFarmZone(pos, targetMode));
        if (farmMode && isCurrentTargetInvalid()) {
            eventLog("target_invalid tag=%s mob=%s", tagInfo(targetNameTag), mobInfo(targetMob));
            onTargetLost("invalid_current_target");
        }
        BlockPos goal = pickGoal(client, player, targetMode, currentMode);

        long nowMs = System.currentTimeMillis();
        if (EVENT_LOG_ENABLED && nowMs - lastHeartbeatLogMs >= HEARTBEAT_LOG_INTERVAL_MS) {
            lastHeartbeatLogMs = nowMs;
            eventLog("heartbeat mode=%d goal=%s targetMob=%s nameTag=%s path=%d wp=%d wall=%.3f vclear=%.3f stress=%.3f posA=%.3f",
                targetMode,
                goal != null ? goal.toShortString() : "null",
                targetMob != null ? targetMob.getType().getTranslationKey() : "null",
                targetNameTag != null ? getMobName(targetNameTag) : "null",
                currentPath.size(), waypointIndex,
                distToWall, verticalClearance, stressLevel, posAnomaly
            );
        }

        // Check pending async result
        if (pendingFuture != null && pendingFuture.isDone()) {
            try {
                List<BlockPos> result = pendingFuture.get();
                currentPath = (result != null) ? result : Collections.emptyList();
                waypointIndex = chooseWaypointIndexForPlayer(currentPath, pos);
                if (currentPath.isEmpty()) {
                    // Do not keep stale path when replan fails; it can point backward.
                    lastGoal = null;
                } else if (pendingGoal != null) {
                    lastGoal = pendingGoal;
                }
                long totalMs = pendingPathTaskStartedMs > 0L
                    ? (System.currentTimeMillis() - pendingPathTaskStartedMs)
                    : -1L;
                eventLog(
                    "path[%d] apply len=%d wp=%d goal=%s totalMs=%d",
                    pendingPathTaskId,
                    currentPath.size(),
                    waypointIndex,
                    pendingGoal != null ? pendingGoal.toShortString() : "null",
                    totalMs
                );
            } catch (Exception e) {
                System.err.println("[Pathfinder] Async error: " + e.getMessage());
                eventLog("path[%d] apply_error %s", pendingPathTaskId, e.getMessage());
                currentPath = Collections.emptyList();
                waypointIndex = 0;
                lastGoal = null;
            }
            pendingFuture = null;
            pendingGoal = null;
            computing.set(false);
            pendingPathTaskId = 0L;
            pendingPathTaskStartedMs = 0L;
        }

        // Advance waypoint (dynamic radius + corner cutting)
        if (!currentPath.isEmpty() && waypointIndex < currentPath.size()) {
            BlockPos wp = currentPath.get(waypointIndex);
            double dist = pos.distanceTo(Vec3d.ofCenter(wp));
            boolean isFinal = waypointIndex == currentPath.size() - 1;
            double radius = isFinal ? FINAL_RADIUS
                : computeAdvanceRadius(currentPath, waypointIndex, pos, velocity.horizontalLength());
            if (dist < radius) {
                waypointIndex++;
            }
            waypointIndex = advanceWaypointIfBehind(currentPath, waypointIndex, pos);
        }

        if (velAnomaly >= KNOCKBACK_REPLAN_VEL_ANOMALY) {
            requestImmediateRepath("player_knockback");
        }

        boolean justLanded = lastPlayerPos != null
            && !wasOnGround
            && onGround
            && lastPlayerVelocity.y <= LANDING_FALL_SPEED_THRESH;
        if (justLanded) {
            String landingCheck = computePathStaleReason(client.world, pos, goal, distToWall, verticalClearance, true);
            if (landingCheck != null) {
                requestImmediateRepath("landed_" + landingCheck);
            }
        }

        boolean forceRepath = forcedRepathReason != null;
        String repathReason = forceRepath
            ? forcedRepathReason
            : computePathStaleReason(client.world, pos, goal, distToWall, verticalClearance, false);

        if (repathReason != null && goal != null) {
            if (forceRepath && computing.get()) {
                eventLog("path_preempt reason=%s pendingTask=%d", repathReason, pendingPathTaskId);
                if (pendingFuture != null) pendingFuture.cancel(true);
                pendingFuture = null;
                pendingGoal = null;
                pendingPathTaskId = 0L;
                pendingPathTaskStartedMs = 0L;
                computing.set(false);
            }

            if (!computing.get()) {
            BlockPos start = findStandableStart(client.world, player.getBlockPos());

            // For distant goals, create intermediate goal in the right direction
            BlockPos pathGoal = goal;
            double distToGoal = Math.sqrt(
                Math.pow(goal.getX() - start.getX(), 2) +
                Math.pow(goal.getY() - start.getY(), 2) +
                Math.pow(goal.getZ() - start.getZ(), 2));
            if (distToGoal > MAX_PATHFIND_DIST) {
                // Try intermediate goals at decreasing distances to avoid placing them inside walls
                double dx = goal.getX() - start.getX();
                double dy = goal.getY() - start.getY();
                double dz = goal.getZ() - start.getZ();
                BlockPos bestIntermediate = null;
                for (double tryDist = MAX_PATHFIND_DIST; tryDist >= 15.0; tryDist -= 10.0) {
                    double r = tryDist / distToGoal;
                    int ix = start.getX() + (int) (dx * r);
                    int iy = start.getY() + (int) (dy * r);
                    int iz = start.getZ() + (int) (dz * r);
                    BlockPos candidate = findNearestStandable(client.world, new BlockPos(ix, iy, iz));
                    // Check the candidate is actually in open space (not buried in a wall)
                    if (isStandableWorld(client.world, candidate.getX(), candidate.getY(), candidate.getZ())
                        && isAirWorld(client.world, candidate.getX(), candidate.getY() + 2, candidate.getZ())) {
                        bestIntermediate = candidate;
                        break;
                    }
                }
                if (bestIntermediate != null) {
                    pathGoal = bestIntermediate;
                } else {
                    // All tried distances hit walls — use nearest standable to original calc
                    double r = MAX_PATHFIND_DIST / distToGoal;
                    pathGoal = findNearestStandable(client.world, new BlockPos(
                        start.getX() + (int) (dx * r),
                        start.getY() + (int) (dy * r),
                        start.getZ() + (int) (dz * r)));
                }
            }

            pendingGoal = goal;
            startPathfinding(client.world, start, pathGoal, stressLevel);
                eventLog(
                    "repath reason=%s goal=%s pathGoal=%s",
                    repathReason,
                    goal.toShortString(),
                    pathGoal.toShortString()
                );
                forcedRepathReason = null;
            }
        }

        lastPlayerPos = pos;
        lastPlayerVelocity = velocity;
        wasOnGround = onGround;

        if (!farmMode) {
            mobCostTicks = 0;
            Arrays.fill(mobPathCosts, 1.0f);
            synchronized (mobPathCostByTag) {
                mobPathCostByTag.clear();
            }
            seenNameTagIds.clear();
            pendingSpawnTagIds.clear();
            lastNewNameTagMs = 0L;
            mobGoals = Collections.emptyList();
            targetMobs = Collections.emptyList();
            if (pendingMobCosts != null) {
                pendingMobCosts.cancel(true);
                pendingMobCosts = null;
            }
            pendingMobCostTaskId = 0L;
            pendingMobCostTaskStartedMs = 0L;
            return;
        }

        // === MOB PATH COSTS (async, every MOB_COST_INTERVAL ticks) ===
        mobCostTicks++;
        // Check pending mob cost result
        if (pendingMobCosts != null && pendingMobCosts.isDone()) {
            try {
                MobCostResult result = pendingMobCosts.get();
                if (result != null) {
                    System.arraycopy(result.costs, 0, mobPathCosts, 0, MOB_PATH_COST_COUNT);
                    synchronized (mobPathCostByTag) {
                        mobPathCostByTag.clear();
                        mobPathCostByTag.putAll(result.costsByTag);
                    }
                    targetMobs = result.renderedMobs;
                    long totalMs = pendingMobCostTaskStartedMs > 0L
                        ? (System.currentTimeMillis() - pendingMobCostTaskStartedMs)
                        : -1L;
                    eventLog(
                        "mobCost[%d] apply tags=%d rendered=%d totalMs=%d",
                        pendingMobCostTaskId,
                        result.costsByTag.size(),
                        result.renderedMobs != null ? result.renderedMobs.size() : 0,
                        totalMs
                    );
                }
            } catch (Exception e) {
                eventLog("mobCost[%d] apply_error %s", pendingMobCostTaskId, e.getMessage());
            }
            pendingMobCosts = null;
            pendingMobCostTaskId = 0L;
            pendingMobCostTaskStartedMs = 0L;
        }
        // Start new mob cost computation
        if (mobCostTicks >= MOB_COST_INTERVAL && pendingMobCosts == null && client.world != null) {
            mobCostTicks = 0;
            List<ArmorStandEntity> tags = findAllNameTags(client, player, targetMode);
            List<BlockPos> goals = new ArrayList<>();
            List<LivingEntity> realMobs = new ArrayList<>();
            List<UUID> tagIds = new ArrayList<>();
            BlockPos playerBlock = findStandableStart(client.world, player.getBlockPos());

            for (int i = 0; i < Math.min(tags.size(), MOB_PATH_COST_COUNT); i++) {
                ArmorStandEntity tag = tags.get(i);
                Vec3d tagPos = tag.getEntityPos();
                BlockPos mobBlock = BlockPos.ofFloored(tagPos.x, tagPos.y, tagPos.z);
                goals.add(findNearestStandable(client.world, mobBlock));
                LivingEntity real = findRealMobNear(client, player, tag);
                realMobs.add(real); // can be null
                tagIds.add(tag.getUuid());
            }

            mobGoals = goals;

            // Async: compute A* cost from player to each mob goal
            final BlockPos startPos = playerBlock;
            final List<BlockPos> goalsCopy = new ArrayList<>(goals);
            final List<UUID> idsCopy = new ArrayList<>(tagIds);
            final List<LivingEntity> renderedMobs = new ArrayList<>(realMobs);
            final ClientWorld world = client.world;
            final long mobTaskId = ++mobCostTaskSeq;
            final long mobTaskStartMs = System.currentTimeMillis();
            // Build one cache covering all mob goals on client thread before async work.
            int minX = startPos.getX(), maxX = startPos.getX();
            int minZ = startPos.getZ(), maxZ = startPos.getZ();
            int minY = startPos.getY(), maxY = startPos.getY();
            for (BlockPos g : goalsCopy) {
                minX = Math.min(minX, g.getX());
                maxX = Math.max(maxX, g.getX());
                minZ = Math.min(minZ, g.getZ());
                maxZ = Math.max(maxZ, g.getZ());
                minY = Math.min(minY, g.getY());
                maxY = Math.max(maxY, g.getY());
            }
            BlockPos cacheMin = new BlockPos(minX - 5, minY - MAX_FALL, minZ - 5);
            BlockPos cacheMax = new BlockPos(maxX + 5, maxY + 10, maxZ + 5);
            final BlockCache mobCache = snapshotOnClientThread(world, cacheMin, cacheMax);
            long snapshotMs = System.currentTimeMillis() - mobTaskStartMs;
            pendingMobCostTaskId = mobTaskId;
            pendingMobCostTaskStartedMs = mobTaskStartMs;
            eventLog(
                "mobCost[%d] start tags=%d goals=%d snapshotMs=%d",
                mobTaskId,
                tags.size(),
                goalsCopy.size(),
                snapshotMs
            );

            pendingMobCosts = CompletableFuture.supplyAsync(() -> {
                long solveStartMs = System.currentTimeMillis();
                float[] costs = new float[MOB_PATH_COST_COUNT];
                Arrays.fill(costs, 1.0f); // default = max cost (unreachable)
                Map<UUID, Float> byTag = new HashMap<>();
                try {
                    // Run single A* from player and extract gCosts for all mob goals
                    Map<Long, Double> gCosts = astarMultiGoal(mobCache, startPos, goalsCopy);

                    for (int i = 0; i < goalsCopy.size(); i++) {
                        long key = posKey(goalsCopy.get(i));
                        Double cost = gCosts.get(key);
                        float norm = 1.0f;
                        if (cost != null) {
                            norm = clamp01((float) (cost / PATH_COST_MAX));
                            costs[i] = norm;
                        }
                        // else stays 1.0 (unreachable)
                        if (i < idsCopy.size()) {
                            byTag.put(idsCopy.get(i), norm);
                        }
                    }
                    eventLog(
                        "mobCost[%d] solved goals=%d solvedTags=%d solveMs=%d",
                        mobTaskId,
                        goalsCopy.size(),
                        byTag.size(),
                        System.currentTimeMillis() - solveStartMs
                    );
                } catch (Exception e) {
                    System.err.println("[Pathfinder] Mob cost error: " + e.getMessage());
                    eventLog("mobCost[%d] solve_error %s", mobTaskId, e.getMessage());
                }
                return new MobCostResult(costs, byTag, renderedMobs);
            });
        }
    }

    private static String computePathStaleReason(ClientWorld world, Vec3d playerPos, BlockPos goal,
                                                 float distToWall, float verticalClearance,
                                                 boolean landingValidationOnly) {
        if (goal == null) return null;
        if (currentPath.isEmpty() || waypointIndex >= currentPath.size()) return "path_empty";
        if (isPathBlockedAhead(world)) return "path_blocked";
        if (!landingValidationOnly && isGoalShiftedSignificantly(goal)) return "goal_shifted";
        if (!landingValidationOnly && hasPlayerDeviatedFromPath(playerPos)) return "player_deviated";
        if (!landingValidationOnly && distToWall <= WALL_REPLAN_THRESH) return "wall_close";
        if (!landingValidationOnly && verticalClearance <= VERTICAL_REPLAN_THRESH) return "low_clearance";
        return null;
    }

    private static boolean isGoalShiftedSignificantly(BlockPos goal) {
        if (lastGoal == null) return true;
        Vec3d currentGoal = Vec3d.ofCenter(goal);
        Vec3d prevGoal = Vec3d.ofCenter(lastGoal);
        if (currentGoal.squaredDistanceTo(prevGoal) > GOAL_SHIFT_REPLAN_DIST_SQ) {
            return true;
        }
        if (currentPath.isEmpty()) return false;
        Vec3d pathEnd = Vec3d.ofCenter(currentPath.get(currentPath.size() - 1));
        double endToNewGoalSq = pathEnd.squaredDistanceTo(currentGoal);
        if (endToNewGoalSq <= GOAL_SHIFT_REPLAN_DIST_SQ) return false;
        double endToPrevGoalSq = pathEnd.squaredDistanceTo(prevGoal);
        return endToPrevGoalSq <= GOAL_SHIFT_REPLAN_DIST_SQ;
    }

    private static boolean hasPlayerDeviatedFromPath(Vec3d playerPos) {
        if (currentPath.isEmpty() || waypointIndex >= currentPath.size()) return true;
        double bestSq = Vec3d.ofCenter(currentPath.get(waypointIndex)).squaredDistanceTo(playerPos);
        if (waypointIndex + 1 < currentPath.size()) {
            double nextSq = Vec3d.ofCenter(currentPath.get(waypointIndex + 1)).squaredDistanceTo(playerPos);
            if (nextSq < bestSq) bestSq = nextSq;
        }
        return bestSq > DEVIATE_DIST * DEVIATE_DIST;
    }

    private static boolean isPathBlockedAhead(ClientWorld world) {
        if (world == null || currentPath.isEmpty() || waypointIndex >= currentPath.size()) return true;
        int start = Math.max(0, waypointIndex);
        int end = Math.min(currentPath.size(), start + BLOCK_VALIDATION_POINTS);
        for (int i = start; i < end; i++) {
            BlockPos node = currentPath.get(i);
            if (!isStandableWorld(world, node.getX(), node.getY(), node.getZ())) {
                return true;
            }
        }
        return false;
    }

    public static float[] getPathRelative(Vec3d playerPos) {
        float[] out = new float[PATH_FEATURE_COUNT];
        if (currentPath.isEmpty() || waypointIndex >= currentPath.size()) return out;

        // Build control points for Catmull-Rom: player + upcoming waypoints
        int remaining = currentPath.size() - waypointIndex;
        int nControl = Math.min(remaining, PATH_HORIZON_POINTS + 2); // extra for spline tangents
        Vec3d[] controls = new Vec3d[nControl + 1]; // +1 for player pos as first point
        controls[0] = playerPos;
        for (int i = 0; i < nControl; i++) {
            controls[i + 1] = Vec3d.ofCenter(currentPath.get(waypointIndex + i));
        }

        // Total arc length estimate for parameterization
        double totalLen = 0;
        double[] segLens = new double[controls.length - 1];
        for (int i = 0; i < segLens.length; i++) {
            segLens[i] = controls[i].distanceTo(controls[i + 1]);
            totalLen += segLens[i];
        }
        if (totalLen < 0.01) return out;

        // Sample PATH_HORIZON_POINTS evenly along Catmull-Rom curve
        int outIndex = 0;
        for (int h = 0; h < PATH_HORIZON_POINTS; h++) {
            // t in [0,1] — skip t=0 (player pos), distribute from ~0.15 onwards
            double t = (h + 1.0) / (PATH_HORIZON_POINTS + 0.5);
            Vec3d pt = sampleCatmullRom(controls, segLens, totalLen, t);
            out[outIndex++] = normalizePathDelta(pt.x - playerPos.x);
            out[outIndex++] = normalizePathDelta(pt.y - playerPos.y);
            out[outIndex++] = normalizePathDelta(pt.z - playerPos.z);
        }
        return out;
    }

    /** Sample a point on a Catmull-Rom spline through control points at parameter t∈[0,1]. */
    private static Vec3d sampleCatmullRom(Vec3d[] pts, double[] segLens, double totalLen, double t) {
        // Map t to arc length position
        double targetLen = t * totalLen;
        double accum = 0;
        int seg = 0;
        for (; seg < segLens.length - 1; seg++) {
            if (accum + segLens[seg] >= targetLen) break;
            accum += segLens[seg];
        }
        double localT = (segLens[seg] > 0.001) ? (targetLen - accum) / segLens[seg] : 0;

        // Catmull-Rom needs 4 points: p0, p1, p2, p3
        int i1 = seg;
        int i2 = seg + 1;
        int i0 = Math.max(0, i1 - 1);
        int i3 = Math.min(pts.length - 1, i2 + 1);

        return catmullRomInterp(pts[i0], pts[i1], pts[i2], pts[i3], localT);
    }

    /** Catmull-Rom interpolation between p1 and p2, using p0 and p3 as tangent guides. */
    private static Vec3d catmullRomInterp(Vec3d p0, Vec3d p1, Vec3d p2, Vec3d p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        double x = 0.5 * ((2 * p1.x)
            + (-p0.x + p2.x) * t
            + (2 * p0.x - 5 * p1.x + 4 * p2.x - p3.x) * t2
            + (-p0.x + 3 * p1.x - 3 * p2.x + p3.x) * t3);
        double y = 0.5 * ((2 * p1.y)
            + (-p0.y + p2.y) * t
            + (2 * p0.y - 5 * p1.y + 4 * p2.y - p3.y) * t2
            + (-p0.y + 3 * p1.y - 3 * p2.y + p3.y) * t3);
        double z = 0.5 * ((2 * p1.z)
            + (-p0.z + p2.z) * t
            + (2 * p0.z - 5 * p1.z + 4 * p2.z - p3.z) * t2
            + (-p0.z + 3 * p1.z - 3 * p2.z + p3.z) * t3);
        return new Vec3d(x, y, z);
    }

    public static void reset() {
        clearCachedSnapshot();
        currentPath = Collections.emptyList();
        waypointIndex = 0;
        lastGoal = null;
        pendingGoal = null;
        forcedRepathReason = null;
        targetMob = null;
        targetNameTag = null;
        activeTargetMode = 0;
        bruiserWaypointStage = 0;
        lastDistToWall = 1f;
        lastVerticalClearance = 1f;
        lastStressLevel = 0f;
        lastPosAnomaly = 0f;
        lastPlayerPos = null;
        lastPlayerVelocity = Vec3d.ZERO;
        wasOnGround = true;
        Arrays.fill(mobPathCosts, 1.0f);
        synchronized (mobPathCostByTag) {
            mobPathCostByTag.clear();
        }
        seenNameTagIds.clear();
        pendingSpawnTagIds.clear();
        lastNewNameTagMs = 0L;
        targetAcquiredMs = 0L;
        mobGoals = Collections.emptyList();
        targetMobs = Collections.emptyList();
        mobCostTicks = 0;
        if (pendingMobCosts != null) pendingMobCosts.cancel(true);
        pendingMobCosts = null;
        pendingMobCostTaskId = 0L;
        pendingMobCostTaskStartedMs = 0L;
        if (pendingFuture != null) pendingFuture.cancel(true);
        pendingFuture = null;
        pendingPathTaskId = 0L;
        pendingPathTaskStartedMs = 0L;
        computing.set(false);
    }

    public static List<BlockPos> getCurrentPath() { return currentPath; }
    public static int getWaypointIndex() { return waypointIndex; }

    /**
     * Get smooth Catmull-Rom curve points for rendering.
     * Returns ~samplesPerSeg points per path segment from waypointIndex onward.
     */
    public static List<Vec3d> getSmoothedCurve(Vec3d playerPos, int samplesPerSeg) {
        List<Vec3d> curve = new ArrayList<>();
        if (currentPath.isEmpty() || waypointIndex >= currentPath.size()) return curve;

        int remaining = currentPath.size() - waypointIndex;
        Vec3d[] controls = new Vec3d[remaining + 1];
        controls[0] = playerPos;
        for (int i = 0; i < remaining; i++) {
            controls[i + 1] = Vec3d.ofCenter(currentPath.get(waypointIndex + i));
        }
        if (controls.length < 2) return curve;

        for (int seg = 0; seg < controls.length - 1; seg++) {
            int i0 = Math.max(0, seg - 1);
            int i1 = seg;
            int i2 = seg + 1;
            int i3 = Math.min(controls.length - 1, seg + 2);
            for (int s = 0; s <= samplesPerSeg; s++) {
                double t = (double) s / samplesPerSeg;
                // Skip first point of non-first segments to avoid duplicates
                if (seg > 0 && s == 0) continue;
                curve.add(catmullRomInterp(controls[i0], controls[i1], controls[i2], controls[i3], t));
            }
        }
        return curve;
    }
    public static LivingEntity getTargetMob() { return targetMob; }
    public static int getActiveTargetMode() { return activeTargetMode; }
    public static float[] getMobPathCosts() { return mobPathCosts; }
    public static List<LivingEntity> getTargetMobs() { return targetMobs; }

    /** Returns farm zone bounds as [minX, minY, minZ, maxX, maxY, maxZ] or null. */
    public static double[][] getExcludeZones(int targetMode) {
        if (targetMode == 2) return BRUISER_EXCLUDE_ZONES;
        return new double[0][];
    }

    public static double[][] getBruiserZones() {
        return BRUISER_ZONES;
    }

    public static double[] getFarmZoneBounds(int targetMode) {
        if (targetMode == 1) {
            return new double[]{ ZEALOT_MIN_X, ZEALOT_MIN_Y, ZEALOT_MIN_Z,
                                 ZEALOT_MAX_X, ZEALOT_MAX_Y, ZEALOT_MAX_Z };
        }
        if (targetMode == 2) {
            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double minZ = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            double maxZ = Double.NEGATIVE_INFINITY;
            for (double[] z : BRUISER_ZONES) {
                minX = Math.min(minX, z[0]);
                maxX = Math.max(maxX, z[1]);
                minY = Math.min(minY, z[2]);
                maxY = Math.max(maxY, z[3]);
                minZ = Math.min(minZ, z[4]);
                maxZ = Math.max(maxZ, z[5]);
            }
            if (Double.isInfinite(minX)) return null;
            return new double[]{ minX, minY, minZ, maxX, maxY, maxZ };
        }
        return null;
    }

    /** Check if position is inside the farm zone for the given target mode. */
    public static boolean isInFarmZone(Vec3d pos, int targetMode) {
        if (targetMode == 1) {
            return pos.x >= ZEALOT_MIN_X && pos.x <= ZEALOT_MAX_X
                && pos.y >= ZEALOT_MIN_Y && pos.y <= ZEALOT_MAX_Y
                && pos.z >= ZEALOT_MIN_Z && pos.z <= ZEALOT_MAX_Z;
        }
        if (targetMode == 2) {
            for (double[] e : BRUISER_EXCLUDE_ZONES) {
                if (pos.x >= e[0] && pos.x <= e[1]
                 && pos.y >= e[2] && pos.y <= e[3]
                 && pos.z >= e[4] && pos.z <= e[5]) return false;
            }
            for (double[] z : BRUISER_ZONES) {
                if (pos.x >= z[0] && pos.x <= z[1]
                 && pos.y >= z[2] && pos.y <= z[3]
                 && pos.z >= z[4] && pos.z <= z[5]) return true;
            }
            return false;
        }
        return false;
    }

    public static void stop() {
        clearCachedSnapshot();
        if (pendingFuture != null) pendingFuture.cancel(true);
        pendingFuture = null;
        pendingPathTaskId = 0L;
        pendingPathTaskStartedMs = 0L;
        forcedRepathReason = null;
        if (pendingMobCosts != null) pendingMobCosts.cancel(true);
        pendingMobCosts = null;
        pendingMobCostTaskId = 0L;
        pendingMobCostTaskStartedMs = 0L;
        pendingGoal = null;
        lastDistToWall = 1f;
        lastVerticalClearance = 1f;
        lastStressLevel = 0f;
        lastPosAnomaly = 0f;
        lastPlayerPos = null;
        lastPlayerVelocity = Vec3d.ZERO;
        wasOnGround = true;
        Arrays.fill(mobPathCosts, 1.0f);
        synchronized (mobPathCostByTag) {
            mobPathCostByTag.clear();
        }
        seenNameTagIds.clear();
        pendingSpawnTagIds.clear();
        lastNewNameTagMs = 0L;
        targetAcquiredMs = 0L;
        mobGoals = Collections.emptyList();
        targetMobs = Collections.emptyList();
        mobCostTicks = 0;
        computing.set(false);
    }

    private static boolean isCurrentTargetInvalid() {
        if (targetNameTag == null && targetMob == null) return false;
        if (targetNameTag != null && (!targetNameTag.isAlive() || targetNameTag.isRemoved())) return true;
        if (targetMob != null && (!targetMob.isAlive() || targetMob.isRemoved())) return true;
        // ArmorStand tag can linger after mob death; tag alive + mob missing is invalid target.
        if (targetNameTag != null && targetMob == null) return true;
        return false;
    }

    private static void onTargetLost(String reason) {
        eventLog(
            "target_lost reason=%s tag=%s mob=%s pendingPath=%s pendingMobCost=%s",
            reason,
            tagInfo(targetNameTag),
            mobInfo(targetMob),
            pendingFuture != null,
            pendingMobCosts != null
        );
        targetMob = null;
        targetNameTag = null;
        lastGoal = null;
        pendingGoal = null;
        if (pendingFuture != null) pendingFuture.cancel(true);
        pendingFuture = null;
        pendingPathTaskId = 0L;
        pendingPathTaskStartedMs = 0L;
        computing.set(false);
        requestImmediateRepath("target_lost_" + reason);
        if (pendingMobCosts != null) pendingMobCosts.cancel(true);
        pendingMobCosts = null;
        pendingMobCostTaskId = 0L;
        pendingMobCostTaskStartedMs = 0L;
        mobCostTicks = 0;
        pendingSpawnTagIds.clear();
        lastNewNameTagMs = 0L;
        targetAcquiredMs = 0L;
    }

    /* ================================================================
     *  GOAL SELECTION
     * ============================================================== */

    private static BlockPos pickGoal(MinecraftClient client, ClientPlayerEntity player,
                                     int targetMode, int currentMode) {
        if (client.world == null) return null;
        Vec3d pPos = player.getEntityPos();
        boolean farmMode = (targetMode != 0 && isInFarmZone(pPos, targetMode));

        if (farmMode) {
            // Pick mob with lowest path_cost (easiest to reach), fallback to nearest
            ArmorStandEntity bestTag = pickBestMobByPathCost(client, player, targetMode);

            if (bestTag != null) {
                UUID prevTargetId = targetNameTag != null ? targetNameTag.getUuid() : null;
                boolean targetSwitched = prevTargetId == null || !prevTargetId.equals(bestTag.getUuid());
                targetNameTag = bestTag;
                Vec3d tagPos = bestTag.getEntityPos();

                // Try to find real mob near the name tag for hitbox rendering
                targetMob = findRealMobNear(client, player, bestTag);

                if (targetSwitched) {
                    float newCost = getTagPathCost(bestTag.getUuid());
                    eventLog(
                        "target_switch prev=%s next=%s cost=%.3f mob=%s",
                        shortUuid(prevTargetId),
                        shortUuid(bestTag.getUuid()),
                        newCost,
                        mobInfo(targetMob)
                    );
                    requestImmediateRepath("target_switch");
                }

                // Use tag position for navigation (it's directly above the mob)
                BlockPos mobBlock = BlockPos.ofFloored(tagPos.x, tagPos.y, tagPos.z);

                // Find standable goal near the mob, offset toward player
                Vec3d toPlayer = pPos.subtract(tagPos);
                double hDist = Math.sqrt(toPlayer.x * toPlayer.x + toPlayer.z * toPlayer.z);

                if (hDist > 0.1) {
                    for (double dist = ATTACK_STANDOFF; dist >= 0; dist -= 0.5) {
                        Vec3d offset = new Vec3d(toPlayer.x / hDist * dist, 0,
                                                 toPlayer.z / hDist * dist);
                        Vec3d goal = tagPos.add(offset);
                        BlockPos gb = BlockPos.ofFloored(goal.x, goal.y, goal.z);
                        // Try current Y, Y-1, Y-2 (name tag floats above mob)
                        for (int dy = 0; dy >= -3; dy--) {
                            if (isStandableWorld(client.world, gb.getX(), gb.getY() + dy, gb.getZ())) {
                                return new BlockPos(gb.getX(), gb.getY() + dy, gb.getZ());
                            }
                        }
                    }
                }
                // Fallback: find any standable position near the tag
                return findNearestStandable(client.world, mobBlock);
            }

            // No mobs found in farm zone — roam to a random point within the zone
            return pickRoamingGoal(client, player, targetMode);
        }

        targetMob = null;
        targetNameTag = null;
        if (targetMode == 1) {
            bruiserWaypointStage = 0;
            return DRAGONS_NEST;
        }
        if (targetMode == 2) {
            Vec3d playerPos = player.getEntityPos();
            if (bruiserWaypointStage == 0
                && playerPos.distanceTo(Vec3d.ofCenter(BRUISER_PORTAL_WAYPOINT)) <= 3.0) {
                bruiserWaypointStage = 1;
            }
            if (bruiserWaypointStage == 1
                && playerPos.distanceTo(Vec3d.ofCenter(BRUISER_PORTAL_WAYPOINT_2)) <= 3.0) {
                bruiserWaypointStage = 2;
            }

            if (bruiserWaypointStage < 1) {
                return BRUISER_PORTAL_WAYPOINT;
            }
            if (bruiserWaypointStage < 2) {
                return BRUISER_PORTAL_WAYPOINT_2;
            }
            if (currentMode != 2) {
                return BRUISER_PORTAL_APPROACH;
            }
            return BRUISER_HIDEOUT;
        }
        bruiserWaypointStage = 0;
        return null;
    }

    /** Find the nearest standable block within a small radius. */
    private static BlockPos findNearestStandable(ClientWorld world, BlockPos center) {
        // Search downward first (name tags float above mobs)
        for (int dy = 0; dy >= -5; dy--) {
            if (isStandableWorld(world, center.getX(), center.getY() + dy, center.getZ())) {
                return new BlockPos(center.getX(), center.getY() + dy, center.getZ());
            }
        }
        double bestDist = Double.MAX_VALUE;
        BlockPos best = center;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -5; dy <= 2; dy++) {
                    int x = center.getX() + dx, y = center.getY() + dy, z = center.getZ() + dz;
                    if (isStandableWorld(world, x, y, z)) {
                        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                        if (d < bestDist) {
                            bestDist = d;
                            best = new BlockPos(x, y, z);
                        }
                    }
                }
            }
        }
        return best;
    }

    /**
     * Pick a random roaming goal within the farm zone when no mobs are available.
     * Reuses the same goal until the player gets close, then picks a new one.
     */
    private static BlockPos pickRoamingGoal(MinecraftClient client, ClientPlayerEntity player, int targetMode) {
        targetMob = null;
        targetNameTag = null;
        Vec3d pPos = player.getEntityPos();

        // Reuse existing roaming goal if still far away
        if (roamingGoal != null) {
            double dist = pPos.distanceTo(Vec3d.ofCenter(roamingGoal));
            if (dist > 5.0 && isInFarmZone(Vec3d.ofCenter(roamingGoal), targetMode)) {
                return roamingGoal;
            }
        }

        // Pick a new random point within the farm zone
        double[] bounds = getFarmZoneBounds(targetMode);
        if (bounds == null) return null;

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 20; attempt++) {
            int x = (int)(bounds[0] + rng.nextDouble() * (bounds[3] - bounds[0]));
            int z = (int)(bounds[2] + rng.nextDouble() * (bounds[5] - bounds[2]));
            // Must be far enough from current position to be worth walking
            double hDist = Math.sqrt((x - pPos.x) * (x - pPos.x) + (z - pPos.z) * (z - pPos.z));
            if (hDist < 15) continue;

            // Search for standable Y within zone bounds
            int minY = (int) bounds[1];
            int maxY = (int) bounds[4];
            for (int y = maxY; y >= minY; y--) {
                Vec3d candidatePos = new Vec3d(x + 0.5, y, z + 0.5);
                if (!isInFarmZone(candidatePos, targetMode)) continue;
                if (client.world != null && isStandableWorld(client.world, x, y, z)) {
                    roamingGoal = new BlockPos(x, y, z);
                    eventLog("roaming_goal pos=%s", roamingGoal.toShortString());
                    return roamingGoal;
                }
            }
        }
        return roamingGoal; // keep old goal if no new one found
    }

    /* ================================================================
     *  MOB TARGETING — find ArmorStand name tags, then pair with real mob
     * ============================================================== */

    /** Find up to 5 ArmorStand name tags, sorted by distance. */
    private static List<ArmorStandEntity> findAllNameTags(MinecraftClient client,
                                                           ClientPlayerEntity player, int targetMode) {
        if (client.world == null || targetMode == 0) return Collections.emptyList();
        Vec3d pPos = player.getEntityPos();
        List<ArmorStandEntity> tags = new ArrayList<>();
        Box searchBox = getNameTagSearchBox(player, targetMode);

        for (LivingEntity e : client.world.getEntitiesByClass(
                LivingEntity.class, searchBox,
                ent -> ent instanceof ArmorStandEntity)) {
            if (targetMode != 0 && !isInFarmZone(e.getEntityPos(), targetMode)) continue;
            String name = getMobName(e);
            if (name.isEmpty()) continue;
            boolean match = false;
            if (targetMode == 1) match = name.contains("Zealot") && !name.contains("Bruiser");
            if (targetMode == 2) match = name.contains("Bruiser") || name.contains("Special Zealot");
            if (match) tags.add((ArmorStandEntity) e);
        }
        // Drop stale name tags that no longer have a real mob nearby.
        // Also collect real mobs for rendering (updated every tick, not tied to async A*).
        List<LivingEntity> realMobs = new ArrayList<>();
        tags.removeIf(tag -> {
            LivingEntity real = findRealMobNear(client, player, tag);
            if (real == null) return true;
            realMobs.add(real);
            return false;
        });
        tags.sort(Comparator.comparingDouble(t -> t.getEntityPos().distanceTo(pPos)));
        if (tags.size() > MOB_PATH_COST_COUNT) {
            targetMobs = realMobs.subList(0, MOB_PATH_COST_COUNT);
            return tags.subList(0, MOB_PATH_COST_COUNT);
        }
        targetMobs = realMobs;
        return tags;
    }

    private static Box getNameTagSearchBox(ClientPlayerEntity player, int targetMode) {
        if (targetMode != 0) {
            double[] bounds = getFarmZoneBounds(targetMode);
            if (bounds != null) {
                return new Box(
                    bounds[0], bounds[1], bounds[2],
                    bounds[3] + 1.0, bounds[4] + 2.0, bounds[5] + 1.0
                );
            }
        }
        return player.getBoundingBox().expand(50);
    }

    /** Pick the mob with the lowest cached path_cost. Falls back to nearest by distance. */
    private static ArmorStandEntity pickBestMobByPathCost(MinecraftClient client,
                                                             ClientPlayerEntity player, int targetMode) {
        List<ArmorStandEntity> tags = findAllNameTags(client, player, targetMode);
        if (tags.isEmpty()) {
            seenNameTagIds.clear();
            pendingSpawnTagIds.clear();
            lastNewNameTagMs = 0L;
            targetAcquiredMs = 0L;
            return null;
        }

        long now = System.currentTimeMillis();
        Set<UUID> currentIds = new HashSet<>();
        boolean hasNewTag = false;
        Set<UUID> newTagIds = new HashSet<>();
        for (ArmorStandEntity tag : tags) {
            UUID id = tag.getUuid();
            currentIds.add(id);
            if (!seenNameTagIds.contains(id)) {
                hasNewTag = true;
                newTagIds.add(id);
            }
        }
        seenNameTagIds.retainAll(currentIds);
        seenNameTagIds.addAll(currentIds);
        pendingSpawnTagIds.retainAll(currentIds);
        if (hasNewTag) {
            lastNewNameTagMs = now;
            pendingSpawnTagIds.addAll(newTagIds);
            // Randomize reaction delay per spawn event (humans react in 200-600ms)
            currentSwitchDelayMs = TARGET_SWITCH_DELAY_MS_MIN
                    + ThreadLocalRandom.current().nextLong(TARGET_SWITCH_DELAY_MS_MAX - TARGET_SWITCH_DELAY_MS_MIN);
        }

        ArmorStandEntity currentTag = null;
        if (targetNameTag != null) {
            UUID currentId = targetNameTag.getUuid();
            for (ArmorStandEntity tag : tags) {
                if (tag.getUuid().equals(currentId)) {
                    currentTag = tag;
                    break;
                }
            }
        }

        // If ANY visible Special Zealot exists, always switch to it immediately.
        if (!isSpecialZealot(currentTag != null ? currentTag : tags.get(0))) {
            for (ArmorStandEntity t : tags) {
                if (isSpecialZealot(t)) {
                    pendingSpawnTagIds.clear();
                    lastNewNameTagMs = 0L;
                    targetAcquiredMs = now;
                    eventLog("special_zealot_override tag=%s", shortUuid(t.getUuid()));
                    return t;
                }
            }
        }

        // Sticky target policy with human-like attention momentum, commitment, and reaction delay.
        if (currentTag != null) {
            // Commitment: if we're close to the current target, don't switch.
            double distToTarget = player.getEntityPos().distanceTo(currentTag.getEntityPos());
            if (distToTarget < COMMITMENT_RADIUS) {
                pendingSpawnTagIds.clear();
                lastNewNameTagMs = 0L;
                return currentTag;
            }

            if (lastNewNameTagMs <= 0L) {
                return currentTag;
            }
            long elapsed = now - lastNewNameTagMs;
            if (elapsed < currentSwitchDelayMs) {
                return currentTag;
            }

            ArmorStandEntity spawnedBest = pickBestByPathCostAndVisibility(client, player, tags, pendingSpawnTagIds);
            if (spawnedBest == null) {
                pendingSpawnTagIds.clear();
                lastNewNameTagMs = 0L;
                return currentTag;
            }

            double playerY = player.getEntityPos().y;
            float currentCost = effectiveCost(getTagPathCost(currentTag.getUuid()), playerY, currentTag);
            float spawnedCost = effectiveCost(getTagPathCost(spawnedBest.getUuid()), playerY, spawnedBest);

            // Unknown costs: do not stick forever to current target.
            if (currentCost >= 1.0f && spawnedCost >= 1.0f) {
                pendingSpawnTagIds.clear();
                lastNewNameTagMs = 0L;
                targetAcquiredMs = now;
                return spawnedBest;
            }
            if (spawnedCost >= 1.0f) {
                return currentTag;
            }
            if (currentCost >= 1.0f) {
                pendingSpawnTagIds.clear();
                lastNewNameTagMs = 0L;
                targetAcquiredMs = now;
                return spawnedBest;
            }

            // Attention momentum: the longer we've been on this target, the harder to switch.
            float timeOnTargetSec = (now - targetAcquiredMs) / 1000f;
            float switchRatio = TARGET_SWITCH_COST_RATIO_BASE
                    + 0.3f * Math.min(timeOnTargetSec, 4.0f); // grows from 1.3 to 2.5 over 4 sec
            switchRatio = Math.min(switchRatio, TARGET_SWITCH_COST_RATIO_MAX);

            if (currentCost >= spawnedCost * switchRatio) {
                pendingSpawnTagIds.clear();
                lastNewNameTagMs = 0L;
                targetAcquiredMs = now;
                return spawnedBest;
            }

            pendingSpawnTagIds.clear();
            lastNewNameTagMs = 0L;
            return currentTag;
        }

        // No current target — pick best with soft randomization among close candidates.
        ArmorStandEntity best = pickBestWithSoftChoice(client, player, tags);
        targetAcquiredMs = now;
        return best != null ? best : tags.get(0);
    }

    private static float getTagPathCost(UUID tagId) {
        synchronized (mobPathCostByTag) {
            return mobPathCostByTag.getOrDefault(tagId, 1.0f);
        }
    }

    /** Height penalty per block of Y difference (climbing is expensive). */
    private static final float HEIGHT_COST_UP   = 0.02f;  // per block above player
    private static final float HEIGHT_COST_DOWN  = 0.005f; // per block below player (falling is cheap)

    /** Compute effective cost = pathCost * fovBias + height penalty. */
    private static float effectiveCost(float pathCost, double playerY, ArmorStandEntity tag) {
        double dy = tag.getEntityPos().y - playerY;
        float heightPenalty;
        if (dy > 0) {
            heightPenalty = (float) dy * HEIGHT_COST_UP;
        } else {
            heightPenalty = (float) (-dy) * HEIGHT_COST_DOWN;
        }

        // FOV bias: mobs behind the player cost more (humans don't see behind them)
        float fovFactor = 1.0f;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            Vec3d toTag = tag.getEntityPos().subtract(mc.player.getEntityPos());
            double yawToTag = Math.toDegrees(Math.atan2(-toTag.x, toTag.z));
            double playerYaw = mc.player.getYaw();
            double diff = Math.abs(((yawToTag - playerYaw) % 360 + 540) % 360 - 180);
            // diff is 0..180; mobs at 180° behind get max penalty
            fovFactor = 1.0f + FOV_BIAS_WEIGHT * (float)(diff / 180.0);
        }

        return pathCost * fovFactor + heightPenalty;
    }

    /** Check if a name tag belongs to a Special Zealot (highest priority target). */
    private static boolean isSpecialZealot(ArmorStandEntity tag) {
        return getMobName(tag).contains("Special");
    }

    /** Compute final selection cost with visibility and Special Zealot modifiers. */
    private static float selectionCost(float rawCost, double playerY, ArmorStandEntity tag,
                                       MinecraftClient client, ClientPlayerEntity player) {
        float cost = effectiveCost(rawCost, playerY, tag);
        if (!isTagVisible(client, player, tag)) {
            cost *= HIDDEN_MOB_PENALTY;
        }
        if (isSpecialZealot(tag)) {
            cost *= SPECIAL_ZEALOT_DISCOUNT;
        }
        return cost;
    }

    /** Pick mob with lowest selection cost. */
    private static ArmorStandEntity pickBestByPathCostAndVisibility(MinecraftClient client,
                                                                     ClientPlayerEntity player,
                                                                     List<ArmorStandEntity> tags,
                                                                     Set<UUID> restrictToIds) {
        ArmorStandEntity best = null;
        float bestCost = Float.MAX_VALUE;
        double playerY = player.getEntityPos().y;

        for (ArmorStandEntity tag : tags) {
            if (restrictToIds != null && !restrictToIds.isEmpty() && !restrictToIds.contains(tag.getUuid())) {
                continue;
            }

            float cost = selectionCost(getTagPathCost(tag.getUuid()), playerY, tag, client, player);
            if (cost < bestCost) {
                bestCost = cost;
                best = tag;
            }
        }

        return best;
    }

    /**
     * Soft-choice selection: when no current target exists, pick among all candidates
     * with randomization if top candidates are within SOFT_CHOICE_THRESHOLD of each other.
     * This prevents always picking the exact same mob deterministically.
     */
    private static ArmorStandEntity pickBestWithSoftChoice(MinecraftClient client,
                                                            ClientPlayerEntity player,
                                                            List<ArmorStandEntity> tags) {
        if (tags.size() == 1) return tags.get(0);

        double playerY = player.getEntityPos().y;
        float[] costs = new float[tags.size()];
        float bestCost = Float.MAX_VALUE;

        for (int i = 0; i < tags.size(); i++) {
            costs[i] = selectionCost(getTagPathCost(tags.get(i).getUuid()), playerY, tags.get(i), client, player);
            if (costs[i] < bestCost) bestCost = costs[i];
        }

        // Special Zealot always wins — no randomization
        for (int i = 0; i < tags.size(); i++) {
            if (isSpecialZealot(tags.get(i))) return tags.get(i);
        }

        // Collect candidates within threshold of the best
        float threshold = bestCost * (1.0f + SOFT_CHOICE_THRESHOLD);
        List<ArmorStandEntity> candidates = new ArrayList<>();
        List<Float> candidateCosts = new ArrayList<>();
        for (int i = 0; i < tags.size(); i++) {
            if (costs[i] <= threshold) {
                candidates.add(tags.get(i));
                candidateCosts.add(costs[i]);
            }
        }

        if (candidates.size() == 1) return candidates.get(0);

        // Weighted random: lower cost = higher weight (softmax-like with temperature)
        float temperature = 3.0f;
        float[] weights = new float[candidates.size()];
        float sumWeights = 0f;
        for (int i = 0; i < candidates.size(); i++) {
            weights[i] = (float) Math.exp(-candidateCosts.get(i) * temperature);
            sumWeights += weights[i];
        }
        sumWeights = 0f;
        for (float w : weights) sumWeights += w;

        float roll = ThreadLocalRandom.current().nextFloat() * sumWeights;
        float cumulative = 0f;
        for (int i = 0; i < candidates.size(); i++) {
            cumulative += weights[i];
            if (roll <= cumulative) return candidates.get(i);
        }
        return candidates.get(candidates.size() - 1);
    }

    private static boolean isTagVisible(MinecraftClient client, ClientPlayerEntity player, ArmorStandEntity tag) {
        if (client.world == null) return false;
        Vec3d start = player.getEyePos();
        Vec3d end = tag.getEntityPos().add(0, 0.35, 0);
        BlockHitResult hit = client.world.raycast(new RaycastContext(
            start,
            end,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            player
        ));
        return hit.getType() == HitResult.Type.MISS;
    }

    /** Find the nearest non-ArmorStand LivingEntity within 5 blocks of a name tag. */
    private static LivingEntity findRealMobNear(MinecraftClient client,
                                                  ClientPlayerEntity player,
                                                  ArmorStandEntity nameTag) {
        if (client.world == null) return null;
        LivingEntity closest = null;
        double closestDist = 5.0;
        Vec3d tagPos = nameTag.getEntityPos();

        for (LivingEntity e : client.world.getEntitiesByClass(
                LivingEntity.class, nameTag.getBoundingBox().expand(5),
                ent -> !(ent instanceof ArmorStandEntity)
                    && !(ent instanceof PlayerEntity)
                    && ent != player
                    && ent.isAlive()
                    && !ent.isRemoved())) {
            double d = e.getEntityPos().distanceTo(tagPos);
            if (d < closestDist) {
                closestDist = d;
                closest = e;
            }
        }
        return closest;
    }

    /** Get mob name, checking custom name first then display name, stripping color codes. */
    private static String getMobName(LivingEntity e) {
        if (e.hasCustomName() && e.getCustomName() != null) {
            return stripFormatting(e.getCustomName().getString());
        }
        return stripFormatting(e.getName().getString());
    }

    /** Strip Minecraft § color codes from string. */
    private static String stripFormatting(String s) {
        if (s == null) return "";
        return s.replaceAll("§.", "");
    }

    /* ================================================================
     *  FIND VALID START (handles mid-air / falling)
     * ============================================================== */

    private static BlockPos findStandableStart(ClientWorld world, BlockPos playerPos) {
        int x = playerPos.getX(), z = playerPos.getZ();
        if (isStandableWorld(world, x, playerPos.getY(), z)) {
            return playerPos;
        }
        for (int dy = 1; dy <= MAX_FALL; dy++) {
            int y = playerPos.getY() - dy;
            if (y < -64) break;
            if (isStandableWorld(world, x, y, z)) {
                return new BlockPos(x, y, z);
            }
            if (!isAirWorld(world, x, y, z)) break;
        }
        return playerPos;
    }

    /** Direct world read — only used on main thread for findStandableStart. */
    private static boolean isStandableWorld(ClientWorld world, int x, int y, int z) {
        try {
            BlockPos below = new BlockPos(x, y - 1, z);
            BlockPos feet  = new BlockPos(x, y, z);
            BlockPos head  = new BlockPos(x, y + 1, z);
            return !world.getBlockState(below).getCollisionShape(world, below).isEmpty()
                && world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()
                && world.getBlockState(head).getCollisionShape(world, head).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isAirWorld(ClientWorld world, int x, int y, int z) {
        try {
            BlockPos pos = new BlockPos(x, y, z);
            return world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /* ================================================================
     *  ASYNC PATHFINDING — uses BlockCache, not ClientWorld
     * ============================================================== */

    private static void startPathfinding(ClientWorld world, BlockPos start, BlockPos goal, float stressLevel) {
        if (!computing.compareAndSet(false, true)) return;

        final long taskId = ++pathTaskSeq;
        final long taskStartMs = System.currentTimeMillis();
        BlockCache cache = snapshotOnClientThread(world, start, goal);
        long snapshotMs = System.currentTimeMillis() - taskStartMs;
        pendingPathTaskId = taskId;
        pendingPathTaskStartedMs = taskStartMs;
        eventLog(
            "path[%d] start from=%s goal=%s snapshotMs=%d stress=%.3f",
            taskId,
            start.toShortString(),
            goal.toShortString(),
            snapshotMs,
            stressLevel
        );

        pendingFuture = CompletableFuture.supplyAsync(() -> {
            long solveStartMs = System.currentTimeMillis();
            try {
                // Run A* off-thread against already prepared world snapshot.
                List<BlockPos> raw = astar(cache, start, goal);
                List<BlockPos> smooth = smoothPath(cache, raw);
                eventLog(
                    "path[%d] solved raw=%d smooth=%d solveMs=%d",
                    taskId,
                    raw.size(),
                    smooth.size(),
                    System.currentTimeMillis() - solveStartMs
                );
                return smooth;
            } catch (Exception e) {
                System.err.println("[Pathfinder] A* error: " + e.getMessage());
                eventLog("path[%d] solve_error %s", taskId, e.getMessage());
                return Collections.emptyList();
            }
        });
    }

    /* ================================================================
     *  A* ALGORITHM — reads from BlockCache only
     * ============================================================== */

    private static List<BlockPos> astar(BlockCache cache, BlockPos start, BlockPos goal) {
        if (start.equals(goal)) return Collections.singletonList(goal);

        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        Map<Long, Double> gCosts = new HashMap<>();
        Map<Long, Long> cameFrom = new HashMap<>();

        long startKey = posKey(start);
        long goalKey = posKey(goal);

        open.add(new Node(start, 0, heuristic(start, goal) * HEURISTIC_WEIGHT));
        gCosts.put(startKey, 0.0);

        int expanded = 0;

        while (!open.isEmpty() && expanded < MAX_NODES) {
            Node current = open.poll();
            long curKey = posKey(current.pos);

            if (curKey == goalKey) {
                return reconstructPath(cameFrom, curKey, start);
            }

            double curG = gCosts.getOrDefault(curKey, Double.MAX_VALUE);
            if (current.g > curG + 0.001) continue;

            expanded++;

            for (Neighbor nb : getNeighbors(cache, current.pos)) {
                long nbKey = posKey(nb.pos);
                double tentG = curG + nb.cost;
                if (tentG < gCosts.getOrDefault(nbKey, Double.MAX_VALUE)) {
                    gCosts.put(nbKey, tentG);
                    cameFrom.put(nbKey, curKey);
                    open.add(new Node(nb.pos, tentG, tentG + heuristic(nb.pos, goal) * HEURISTIC_WEIGHT));
                }
            }
        }

        // No path found — return partial path to closest expanded node
        if (!cameFrom.isEmpty()) {
            long bestKey = startKey;
            double bestH = heuristic(start, goal);
            for (long key : gCosts.keySet()) {
                BlockPos p = keyToPos(key);
                double h = heuristic(p, goal);
                if (h < bestH) { bestH = h; bestKey = key; }
            }
            if (bestKey != startKey) {
                return reconstructPath(cameFrom, bestKey, start);
            }
        }

        return Collections.emptyList();
    }

    /** Dijkstra from start — expands until all goals are found or MAX_NODES hit.
     *  Returns gCosts map (contains cost to any reached position). */
    private static Map<Long, Double> astarMultiGoal(BlockCache cache, BlockPos start,
                                                      List<BlockPos> goals) {
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        Map<Long, Double> gCosts = new HashMap<>();

        long startKey = posKey(start);
        Set<Long> goalKeys = new HashSet<>();
        for (BlockPos g : goals) goalKeys.add(posKey(g));

        open.add(new Node(start, 0, 0));
        gCosts.put(startKey, 0.0);

        int expanded = 0;
        int goalsFound = 0;

        while (!open.isEmpty() && expanded < MAX_NODES && goalsFound < goalKeys.size()) {
            Node current = open.poll();
            long curKey = posKey(current.pos);

            double curG = gCosts.getOrDefault(curKey, Double.MAX_VALUE);
            if (current.g > curG + 0.001) continue;

            expanded++;

            if (goalKeys.contains(curKey)) goalsFound++;

            for (Neighbor nb : getNeighbors(cache, current.pos)) {
                long nbKey = posKey(nb.pos);
                double tentG = curG + nb.cost;
                if (tentG < gCosts.getOrDefault(nbKey, Double.MAX_VALUE)) {
                    gCosts.put(nbKey, tentG);
                    open.add(new Node(nb.pos, tentG, tentG)); // no heuristic = Dijkstra
                }
            }
        }

        return gCosts;
    }

    /* ── neighbor generation ─────────────────────────────────────── */

    private static final int[][] DIRS_8 = {
        {1,0}, {-1,0}, {0,1}, {0,-1}, {1,1}, {1,-1}, {-1,1}, {-1,-1}
    };

    private static List<Neighbor> getNeighbors(BlockCache cache, BlockPos pos) {
        List<Neighbor> neighbors = new ArrayList<>(24);
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();

        // 1. Walk (8 directions, same Y)
        for (int[] d : DIRS_8) {
            int nx = x + d[0], nz = z + d[1];
            if (!cache.isStandable(nx, y, nz)) continue;
            // Diagonal: check both intermediate axis-aligned blocks are passable
            // to prevent corner-cutting through walls
            if (d[0] != 0 && d[1] != 0) {
                if (!cache.isPassable(x + d[0], y, z) || !cache.isPassable(x + d[0], y + 1, z)
                 || !cache.isPassable(x, y, z + d[1]) || !cache.isPassable(x, y + 1, z + d[1])) {
                    continue;
                }
                double cost = 1.414 + wallProximityPenalty(cache, nx, y, nz);
                neighbors.add(new Neighbor(new BlockPos(nx, y, nz), cost));
            } else {
                double cost = 1.0 + wallProximityPenalty(cache, nx, y, nz);
                neighbors.add(new Neighbor(new BlockPos(nx, y, nz), cost));
            }
        }

        // 2. Jump up (8 directions, +1 Y) — need clearance at y+2 (above head)
        for (int[] d : DIRS_8) {
            int nx = x + d[0], nz = z + d[1], ny = y + 1;
            if (!cache.isPassable(x, y + 2, z)) continue;        // clearance above head for jump
            if (!cache.isStandable(nx, ny, nz)) continue;
            if (!cache.isPassable(nx, ny + 2, nz)) continue;     // head clearance at destination+1
            // Diagonal corner check
            if (d[0] != 0 && d[1] != 0) {
                if (!cache.isPassable(x + d[0], ny, z) || !cache.isPassable(x + d[0], ny + 1, z)
                 || !cache.isPassable(x, ny, z + d[1]) || !cache.isPassable(x, ny + 1, z + d[1])) {
                    continue;
                }
                double cost = 2.414 + wallProximityPenalty(cache, nx, ny, nz);
                neighbors.add(new Neighbor(new BlockPos(nx, ny, nz), cost));
            } else {
                double cost = 2.0 + wallProximityPenalty(cache, nx, ny, nz);
                neighbors.add(new Neighbor(new BlockPos(nx, ny, nz), cost));
            }
        }

        // 3. Fall (8 directions)
        for (int[] d : DIRS_8) {
            int nx = x + d[0], nz = z + d[1];
            // Diagonal corner check at current Y
            if (d[0] != 0 && d[1] != 0) {
                if (!cache.isPassable(x + d[0], y, z) || !cache.isPassable(x + d[0], y + 1, z)
                 || !cache.isPassable(x, y, z + d[1]) || !cache.isPassable(x, y + 1, z + d[1])) {
                    continue;
                }
            }
            for (int dy = 1; dy <= MAX_FALL; dy++) {
                int ny = y - dy;
                if (ny < -64) break;
                if (cache.isStandable(nx, ny, nz)) {
                    double cost = (d[0] != 0 && d[1] != 0) ? 1.414 : 1.0;
                    cost += dy * 0.1;
                    cost += wallProximityPenalty(cache, nx, ny, nz);
                    neighbors.add(new Neighbor(new BlockPos(nx, ny, nz), cost));
                    break;
                }
                if (cache.isSolid(nx, ny, nz)) break;
            }
        }

        // 4. Drop straight down
        for (int dy = 1; dy <= MAX_FALL; dy++) {
            int ny = y - dy;
            if (ny < -64) break;
            if (cache.isStandable(x, ny, z)) {
                double cost = dy * 0.1 + wallProximityPenalty(cache, x, ny, z);
                neighbors.add(new Neighbor(new BlockPos(x, ny, z), cost));
                break;
            }
            if (cache.isSolid(x, ny, z)) break;
        }

        return neighbors;
    }

    /* ── path smoothing — conservative, max 6 node skip ────────── */

    private static final int MAX_SMOOTH_SKIP = 3;

    private static List<BlockPos> smoothPath(BlockCache cache, List<BlockPos> raw) {
        if (raw == null || raw.size() <= 2) return raw;

        List<BlockPos> smooth = new ArrayList<>();
        smooth.add(raw.get(0));

        int i = 0;
        while (i < raw.size() - 1) {
            int furthest = i + 1;
            // Only try skipping up to MAX_SMOOTH_SKIP nodes ahead
            int maxJ = Math.min(raw.size() - 1, i + MAX_SMOOTH_SKIP);
            for (int j = maxJ; j > i + 1; j--) {
                if (hasLineOfSight(cache, raw.get(i), raw.get(j))) {
                    furthest = j;
                    break;
                }
            }
            smooth.add(raw.get(furthest));
            i = furthest;
        }

        return smooth;
    }

    /** Check if the full player hitbox can WALK in a straight line.
     *  Checks: passability, wall clearance (5 rays), ground below, same Y level. */
    private static boolean hasLineOfSight(BlockCache cache, BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        int dist = Math.max(Math.abs(dx), Math.abs(dz));
        if (dist == 0) return true;
        // No smoothing across Y changes — let A* handle elevation
        if (from.getY() != to.getY()) return false;

        double len = Math.sqrt(dx * dx + dz * dz);
        double perpX = -dz / len;
        double perpZ =  dx / len;

        // 5 rays: center + 4 offsets (player hitbox ~0.6 wide)
        double[][] offsets = {
            {0, 0},
            { perpX * 0.35,  perpZ * 0.35},
            {-perpX * 0.35, -perpZ * 0.35},
            { dx / len * 0.35,  dz / len * 0.35},  // forward
            {-dx / len * 0.35, -dz / len * 0.35},  // backward
        };

        int y = from.getY();
        // Check every block along the line, not just every step
        int checks = Math.max(dist * 3, 6); // denser sampling to avoid corner clipping

        for (double[] off : offsets) {
            for (int s = 1; s <= checks; s++) {
                float t = (float) s / checks;
                int cx = (int) Math.floor(from.getX() + 0.5 + dx * t + off[0]);
                int cz = (int) Math.floor(from.getZ() + 0.5 + dz * t + off[1]);

                // Keep smoothing only on truly walkable cells, not just passable rays.
                if (!cache.isStandable(cx, y, cz)) {
                    return false;
                }
            }
        }
        return true;
    }

    /* ── helpers ──────────────────────────────────────────────────── */

    private static double heuristic(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static BlockCache snapshotOnClientThread(ClientWorld world, BlockPos start, BlockPos goal) {
        long now = System.currentTimeMillis();
        synchronized (snapshotLock) {
            if (cachedSnapshot != null
                && cachedSnapshot.world == world
                && now - cachedSnapshot.createdAtMs <= SNAPSHOT_REUSE_MS
                && cachedSnapshot.cache.covers(start, goal, SNAPSHOT_COVER_MARGIN)) {
                return cachedSnapshot.cache;
            }
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        CompletableFuture<BlockCache> snapshotFuture = new CompletableFuture<>();
        Runnable buildTask = () -> {
            try {
                snapshotFuture.complete(BlockCache.snapshotBetweenChunks(world, start, goal));
            } catch (Exception e) {
                snapshotFuture.completeExceptionally(e);
            }
        };

        if (mc.isOnThread()) {
            buildTask.run();
        } else {
            mc.execute(buildTask);
        }

        try {
            BlockCache cache = snapshotFuture.get();
            synchronized (snapshotLock) {
                cachedSnapshot = new CachedSnapshot(world, cache, System.currentTimeMillis());
            }
            return cache;
        } catch (Exception e) {
            System.err.println("[Pathfinder] Snapshot error: " + e.getMessage());
            return BlockCache.empty();
        }
    }

    private static void clearCachedSnapshot() {
        synchronized (snapshotLock) {
            cachedSnapshot = null;
        }
    }

    private static void forceResetForTeleport(float stressLevel) {
        eventLog("teleport_reset stress=%.3f", stressLevel);
        clearCachedSnapshot();
        currentPath = Collections.emptyList();
        waypointIndex = 0;
        lastGoal = null;
        pendingGoal = null;
        forcedRepathReason = null;
        bruiserWaypointStage = 0;
        lastPlayerPos = null;
        lastPlayerVelocity = Vec3d.ZERO;
        wasOnGround = true;
        if (pendingFuture != null) pendingFuture.cancel(true);
        pendingFuture = null;
        pendingPathTaskId = 0L;
        pendingPathTaskStartedMs = 0L;
        if (pendingMobCosts != null) pendingMobCosts.cancel(true);
        pendingMobCosts = null;
        pendingMobCostTaskId = 0L;
        pendingMobCostTaskStartedMs = 0L;
        Arrays.fill(mobPathCosts, 1.0f);
        synchronized (mobPathCostByTag) {
            mobPathCostByTag.clear();
        }
        seenNameTagIds.clear();
        pendingSpawnTagIds.clear();
        lastNewNameTagMs = 0L;
        targetAcquiredMs = 0L;
        mobGoals = Collections.emptyList();
        targetMobs = Collections.emptyList();
        mobCostTicks = 0;
        computing.set(false);
    }

    private static float normalizePathDelta(double delta) {
        return clamp01((float) (((delta / 50.0) + 1.0) / 2.0));
    }

    private static double wallProximityPenalty(BlockCache cache, int x, int y, int z) {
        double minDistSq = Double.MAX_VALUE;
        for (int dx = -WALL_COST_RADIUS; dx <= WALL_COST_RADIUS; dx++) {
            for (int dz = -WALL_COST_RADIUS; dz <= WALL_COST_RADIUS; dz++) {
                if (dx == 0 && dz == 0) continue;
                if (!cache.isSolid(x + dx, y, z + dz) && !cache.isSolid(x + dx, y + 1, z + dz)) {
                    continue;
                }
                double dSq = dx * dx + dz * dz;
                if (dSq < minDistSq) minDistSq = dSq;
            }
        }
        if (minDistSq == Double.MAX_VALUE) return 0.0;
        double minDist = Math.sqrt(minDistSq);
        double norm = clamp01((float) ((WALL_COST_RADIUS + 1.0 - minDist) / (WALL_COST_RADIUS + 1.0)));
        return WALL_PROXIMITY_COST * norm * norm;
    }

    /**
     * Dynamic advance radius based on speed, turn angle, and distance to goal.
     * Straight segments → larger radius (corner cutting), tight turns → smaller.
     */
    private static double computeAdvanceRadius(List<BlockPos> path, int wpIdx, Vec3d playerPos, double hSpeed) {
        double radius = ADVANCE_RADIUS_BASE;

        // Speed factor: walking ~0.22 blocks/tick, sprinting ~0.28
        // Scale radius up when moving fast
        double speedFactor = clamp01((float)(hSpeed / 0.22)) * 0.5 + 0.75; // 0.75..1.25
        radius *= speedFactor;

        // Turn angle factor: dot product of incoming vs outgoing direction
        if (wpIdx + 1 < path.size()) {
            Vec3d cur = Vec3d.ofCenter(path.get(wpIdx));
            Vec3d next = Vec3d.ofCenter(path.get(wpIdx + 1));
            Vec3d dirIn = cur.subtract(playerPos);
            Vec3d dirOut = next.subtract(cur);
            double lenIn = dirIn.horizontalLength();
            double lenOut = dirOut.horizontalLength();
            if (lenIn > 0.1 && lenOut > 0.1) {
                double dot = (dirIn.x * dirOut.x + dirIn.z * dirOut.z) / (lenIn * lenOut);
                // dot=1 straight, dot=0 90° turn, dot=-1 U-turn
                // angleFactor: straight=1.4, 90°=0.8, U-turn=0.5
                double angleFactor = 0.5 + 0.9 * Math.max(0, dot);
                radius *= angleFactor;
            }
        }

        // Distance to goal: tighten radius when close to final target
        Vec3d goalPos = Vec3d.ofCenter(path.get(path.size() - 1));
        double distToGoal = playerPos.distanceTo(goalPos);
        if (distToGoal < GOAL_TIGHTEN_DIST) {
            radius *= Math.max(0.2, distToGoal / GOAL_TIGHTEN_DIST);
        }

        return Math.max(FINAL_RADIUS, Math.min(radius, ADVANCE_RADIUS_MAX));
    }

    /**
     * If the next waypoint is already closer than the current one, skip ahead.
     * Prevents "pull back" behavior when the bot passes a waypoint from the side.
     */
    private static int advanceWaypointIfBehind(List<BlockPos> path, int index, Vec3d playerPos) {
        int idx = index;
        while (idx >= 0 && idx + 1 < path.size()) {
            Vec3d cur = Vec3d.ofCenter(path.get(idx));
            Vec3d next = Vec3d.ofCenter(path.get(idx + 1));
            double curDistSq = cur.squaredDistanceTo(playerPos);
            double nextDistSq = next.squaredDistanceTo(playerPos);
            if (nextDistSq + WAYPOINT_PROGRESS_EPS_SQ < curDistSq) {
                idx++;
            } else {
                break;
            }
        }
        return idx;
    }

    /**
     * Async path is built from an older start position.
     * Pick the waypoint nearest to the CURRENT player position
     * so we do not backtrack to path[0] when the player has already moved.
     */
    private static int chooseWaypointIndexForPlayer(List<BlockPos> path, Vec3d playerPos) {
        if (path == null || path.isEmpty()) return 0;

        int bestIndex = 0;
        double bestDistSq = Double.MAX_VALUE;
        for (int i = 0; i < path.size(); i++) {
            double dSq = Vec3d.ofCenter(path.get(i)).squaredDistanceTo(playerPos);
            if (dSq < bestDistSq) {
                bestDistSq = dSq;
                bestIndex = i;
            }
        }

        // If player is already progressing along best->next segment, advance one step.
        if (bestIndex < path.size() - 1) {
            Vec3d a = Vec3d.ofCenter(path.get(bestIndex));
            Vec3d b = Vec3d.ofCenter(path.get(bestIndex + 1));
            Vec3d ab = b.subtract(a);
            double lenSq = ab.lengthSquared();
            if (lenSq > 1.0e-6) {
                double t = playerPos.subtract(a).dotProduct(ab) / lenSq;
                if (t > 0.35) {
                    bestIndex++;
                }
            }
        }

        return bestIndex;
    }

    private static long posKey(BlockPos p) {
        return ((long)(p.getX() + 30000000) << 36)
             | ((long)(p.getY() + 64) << 26)
             | ((long)(p.getZ() + 30000000));
    }

    private static BlockPos keyToPos(long key) {
        int z = (int)(key & 0x3FFFFFF) - 30000000;
        int y = (int)((key >> 26) & 0x3FF) - 64;
        int x = (int)((key >> 36)) - 30000000;
        return new BlockPos(x, y, z);
    }

    private static List<BlockPos> reconstructPath(Map<Long, Long> cameFrom, long endKey, BlockPos start) {
        LinkedList<BlockPos> path = new LinkedList<>();
        long current = endKey;
        long startKey = posKey(start);
        int safety = 0;
        while (current != startKey && safety < MAX_NODES) {
            path.addFirst(keyToPos(current));
            Long parent = cameFrom.get(current);
            if (parent == null) break;
            current = parent;
            safety++;
        }
        return path;
    }

    /* ── inner types ─────────────────────────────────────────────── */

    private static class Node {
        final BlockPos pos;
        final double g, f;
        Node(BlockPos pos, double g, double f) {
            this.pos = pos; this.g = g; this.f = f;
        }
    }

    private static class Neighbor {
        final BlockPos pos;
        final double cost;
        Neighbor(BlockPos pos, double cost) {
            this.pos = pos; this.cost = cost;
        }
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}


