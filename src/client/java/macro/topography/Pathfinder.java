package macro.topography;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lazy Theta* pathfinder for End island navigation + mob targeting.
 * Runs async with a thread-safe block cache snapshot, provides waypoint
 * direction as normalized [0,1] features.
 */
public class Pathfinder {

    /* ── destinations ─────────────────────────────────────────────── */
    private static final BlockPos DRAGONS_NEST = new BlockPos(-621, 15, 275);
    private static final BlockPos BRUISER_HIDEOUT = new BlockPos(-616, 5, -281);

    /* ── search limits ─────────────────────────────────────────────── */
    private static final int MAX_NODES = 10_000;
    private static final int MAX_FALL = 40;
    private static final int CACHE_RADIUS = 80;

    /* ── waypoint management ──────────────────────────────────────── */
    private static final double ADVANCE_RADIUS = 1.5;
    private static final double FINAL_RADIUS = 0.3;

    /* ── async control ────────────────────────────────────────────── */
    private static final int RECALC_COOLDOWN = 20;
    private static final int SAFETY_RECALC = 100;
    private static final double DEVIATE_DIST = 5.0;

    /* ── mob targeting ────────────────────────────────────────────── */
    private static final double ATTACK_STANDOFF = 1.75;
    private static final double WALL_PADDING = 0.25;

    /* ── state ────────────────────────────────────────────────────── */
    private static List<BlockPos> currentPath = Collections.emptyList();
    private static int waypointIndex = 0;
    private static int ticksSinceRecalc = 0;
    private static int recalcCooldown = 0;
    private static final AtomicBoolean computing = new AtomicBoolean(false);
    private static CompletableFuture<List<BlockPos>> pendingFuture = null;
    private static BlockPos lastGoal = null;
    private static LivingEntity targetMob = null;
    private static int activeTargetMode = 0;

    /* ================================================================
     *  BLOCK CACHE — snapshot on main thread, read safely from async
     * ============================================================== */

    private static class BlockCache {
        private final Map<Long, Boolean> solidMap = new ConcurrentHashMap<>();

        /** Build cache on MAIN THREAD around a region. */
        static BlockCache snapshot(ClientWorld world, BlockPos center, int radius) {
            BlockCache cache = new BlockCache();
            int cx = center.getX(), cy = center.getY(), cz = center.getZ();
            for (int x = cx - radius; x <= cx + radius; x++) {
                for (int z = cz - radius; z <= cz + radius; z++) {
                    for (int y = Math.max(-64, cy - MAX_FALL - 5); y <= cy + 20; y++) {
                        BlockPos bp = new BlockPos(x, y, z);
                        try {
                            BlockState state = world.getBlockState(bp);
                            boolean solid = !state.getCollisionShape(world, bp).isEmpty();
                            cache.solidMap.put(packPos(x, y, z), solid);
                        } catch (Exception e) {
                            cache.solidMap.put(packPos(x, y, z), false);
                        }
                    }
                }
            }
            return cache;
        }

        /** Expand cache toward goal if needed. */
        static BlockCache snapshotBetween(ClientWorld world, BlockPos start, BlockPos goal) {
            BlockCache cache = new BlockCache();
            int minX = Math.min(start.getX(), goal.getX()) - 10;
            int maxX = Math.max(start.getX(), goal.getX()) + 10;
            int minZ = Math.min(start.getZ(), goal.getZ()) - 10;
            int maxZ = Math.max(start.getZ(), goal.getZ()) + 10;
            int minY = Math.max(-64, Math.min(start.getY(), goal.getY()) - MAX_FALL - 5);
            int maxY = Math.max(start.getY(), goal.getY()) + 20;
            // Clamp to reasonable size
            if ((maxX - minX) * (maxZ - minZ) > 250 * 250) {
                // Too large, just cache around start
                return snapshot(world, start, CACHE_RADIUS);
            }
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    for (int y = minY; y <= maxY; y++) {
                        BlockPos bp = new BlockPos(x, y, z);
                        try {
                            BlockState state = world.getBlockState(bp);
                            boolean solid = !state.getCollisionShape(world, bp).isEmpty();
                            cache.solidMap.put(packPos(x, y, z), solid);
                        } catch (Exception e) {
                            cache.solidMap.put(packPos(x, y, z), false);
                        }
                    }
                }
            }
            return cache;
        }

        boolean isSolid(int x, int y, int z) {
            Boolean val = solidMap.get(packPos(x, y, z));
            return val != null && val; // unknown = not solid (passable)
        }

        boolean isPassable(int x, int y, int z) {
            return !isSolid(x, y, z);
        }

        boolean isStandable(int x, int y, int z) {
            return isSolid(x, y - 1, z)      // solid below feet
                && isPassable(x, y, z)        // feet clear
                && isPassable(x, y + 1, z);   // head clear
        }

        private static long packPos(int x, int y, int z) {
            return ((long)(x + 30000000) << 36)
                 | ((long)(y + 64) << 26)
                 | ((long)(z + 30000000));
        }
    }

    /* ================================================================
     *  PUBLIC API
     * ============================================================== */

    public static void update(MinecraftClient client, ClientPlayerEntity player,
                              int targetMode, int currentMode) {
        if (client.world == null || player == null) return;

        activeTargetMode = targetMode;
        Vec3d pos = player.getEntityPos();
        ticksSinceRecalc++;
        if (recalcCooldown > 0) recalcCooldown--;

        BlockPos goal = pickGoal(client, player, targetMode);

        // Check pending async result
        if (pendingFuture != null && pendingFuture.isDone()) {
            try {
                List<BlockPos> result = pendingFuture.get();
                if (result != null && !result.isEmpty()) {
                    currentPath = result;
                    waypointIndex = 0;
                }
            } catch (Exception e) {
                System.err.println("[Pathfinder] Async error: " + e.getMessage());
            }
            pendingFuture = null;
            computing.set(false);
        }

        // Advance waypoint
        if (!currentPath.isEmpty() && waypointIndex < currentPath.size()) {
            BlockPos wp = currentPath.get(waypointIndex);
            double dist = pos.distanceTo(Vec3d.ofCenter(wp));
            boolean isFinal = waypointIndex == currentPath.size() - 1;
            double radius = isFinal ? FINAL_RADIUS : ADVANCE_RADIUS;
            if (dist < radius) {
                waypointIndex++;
            }
        }

        // Check if recalc needed
        boolean needRecalc = false;
        if (goal != null && !goal.equals(lastGoal)) needRecalc = true;
        if (ticksSinceRecalc >= SAFETY_RECALC) needRecalc = true;
        if (waypointIndex >= currentPath.size()) needRecalc = true;
        if (!currentPath.isEmpty() && waypointIndex < currentPath.size()) {
            BlockPos wp = currentPath.get(waypointIndex);
            double distToPath = pos.distanceTo(Vec3d.ofCenter(wp));
            if (distToPath > DEVIATE_DIST) needRecalc = true;
        }

        if (needRecalc && recalcCooldown <= 0 && !computing.get() && goal != null) {
            BlockPos start = findStandableStart(client.world, player.getBlockPos());
            // Snapshot block data on MAIN THREAD before going async
            BlockCache cache = BlockCache.snapshotBetween(client.world, start, goal);
            startPathfinding(cache, start, goal);
            lastGoal = goal;
        }
    }

    public static float[] getPathRelative(Vec3d playerPos) {
        if (currentPath.isEmpty() || waypointIndex >= currentPath.size()) {
            return new float[]{0.5f, 0.5f, 0.5f};
        }

        BlockPos wp = currentPath.get(waypointIndex);
        Vec3d wpCenter = Vec3d.ofCenter(wp);

        float rx = clamp01((float) ((wpCenter.x - playerPos.x) / 50.0 + 1) / 2.0f);
        float ry = clamp01((float) ((wpCenter.y - playerPos.y) / 20.0 + 1) / 2.0f);
        float rz = clamp01((float) ((wpCenter.z - playerPos.z) / 50.0 + 1) / 2.0f);

        return new float[]{rx, ry, rz};
    }

    public static void reset() {
        currentPath = Collections.emptyList();
        waypointIndex = 0;
        ticksSinceRecalc = 0;
        recalcCooldown = 0;
        lastGoal = null;
        targetMob = null;
        activeTargetMode = 0;
        if (pendingFuture != null) pendingFuture.cancel(true);
        pendingFuture = null;
        computing.set(false);
    }

    public static List<BlockPos> getCurrentPath() { return currentPath; }
    public static int getWaypointIndex() { return waypointIndex; }
    public static LivingEntity getTargetMob() { return targetMob; }
    public static int getActiveTargetMode() { return activeTargetMode; }

    public static void stop() {
        if (pendingFuture != null) pendingFuture.cancel(true);
        pendingFuture = null;
        computing.set(false);
    }

    /* ================================================================
     *  GOAL SELECTION
     * ============================================================== */

    private static BlockPos pickGoal(MinecraftClient client, ClientPlayerEntity player,
                                     int targetMode) {
        LivingEntity nearest = getNearestTargetMob(client, player, targetMode);
        if (nearest != null) {
            targetMob = nearest;
            // Use bounding box center, not feet position
            Vec3d mobCenter = nearest.getBoundingBox().getCenter();
            // Offset goal toward player so bot stops at attack range, not inside the mob
            Vec3d playerPos = player.getEntityPos();
            Vec3d toPlayer = playerPos.subtract(mobCenter);
            double hDist = Math.sqrt(toPlayer.x * toPlayer.x + toPlayer.z * toPlayer.z);
            if (hDist > 0.1) {
                Vec3d offset = new Vec3d(toPlayer.x / hDist * ATTACK_STANDOFF, 0,
                                         toPlayer.z / hDist * ATTACK_STANDOFF);
                Vec3d goal = mobCenter.add(offset);
                return BlockPos.ofFloored(goal.x, mobCenter.y, goal.z);
            }
            return BlockPos.ofFloored(mobCenter.x, mobCenter.y, mobCenter.z);
        }
        targetMob = null;
        if (targetMode == 1) return DRAGONS_NEST;
        if (targetMode == 2) return BRUISER_HIDEOUT;
        return null;
    }

    /* ================================================================
     *  MOB TARGETING — strict mode filtering with § stripping
     * ============================================================== */

    private static LivingEntity getNearestTargetMob(MinecraftClient client,
                                                     ClientPlayerEntity player, int targetMode) {
        if (client.world == null || targetMode == 0) return null;
        Vec3d pPos = player.getEntityPos();
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;

        for (LivingEntity e : client.world.getEntitiesByClass(
                LivingEntity.class, player.getBoundingBox().expand(50),
                ent -> {
                    if (ent == player || ent instanceof PlayerEntity) return false;
                    String name = getMobName(ent);
                    if (name.isEmpty()) return false;
                    if (targetMode == 1) return name.contains("Zealot") && !name.contains("Bruiser");
                    if (targetMode == 2) return name.contains("Bruiser");
                    return false;
                })) {
            double d = e.getEntityPos().distanceTo(pPos);
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        return best;
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

    private static void startPathfinding(BlockCache cache, BlockPos start, BlockPos goal) {
        if (!computing.compareAndSet(false, true)) return;

        ticksSinceRecalc = 0;
        recalcCooldown = RECALC_COOLDOWN;

        pendingFuture = CompletableFuture.supplyAsync(() -> {
            try {
                List<BlockPos> raw = astar(cache, start, goal);
                return smoothPath(cache, raw);
            } catch (Exception e) {
                System.err.println("[Pathfinder] A* error: " + e.getMessage());
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

        open.add(new Node(start, 0, heuristic(start, goal)));
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
                    open.add(new Node(nb.pos, tentG, tentG + heuristic(nb.pos, goal)));
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
                neighbors.add(new Neighbor(new BlockPos(nx, y, nz), 1.414));
            } else {
                neighbors.add(new Neighbor(new BlockPos(nx, y, nz), 1.0));
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
                neighbors.add(new Neighbor(new BlockPos(nx, ny, nz), 2.414));
            } else {
                neighbors.add(new Neighbor(new BlockPos(nx, ny, nz), 2.0));
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
                neighbors.add(new Neighbor(new BlockPos(x, ny, z), dy * 0.1));
                break;
            }
            if (cache.isSolid(x, ny, z)) break;
        }

        return neighbors;
    }

    /* ── path smoothing (Theta*-style line-of-sight skip) ────────── */

    private static List<BlockPos> smoothPath(BlockCache cache, List<BlockPos> raw) {
        if (raw == null || raw.size() <= 2) return raw;

        List<BlockPos> smooth = new ArrayList<>();
        smooth.add(raw.get(0));

        int i = 0;
        while (i < raw.size() - 1) {
            int furthest = i + 1;
            for (int j = raw.size() - 1; j > i + 1; j--) {
                if (hasLineOfSight(cache, raw.get(i), raw.get(j))) {
                    furthest = j;
                    break;
                }
            }
            smooth.add(raw.get(furthest));
            i = furthest;
        }

        // Wall-hugging prevention: nudge corner waypoints away from adjacent walls
        for (int w = 1; w < smooth.size() - 1; w++) {
            BlockPos wp = smooth.get(w);
            int wx = wp.getX(), wy = wp.getY(), wz = wp.getZ();
            double nudgeX = 0, nudgeZ = 0;
            if (cache.isSolid(wx + 1, wy, wz)) nudgeX -= WALL_PADDING;
            if (cache.isSolid(wx - 1, wy, wz)) nudgeX += WALL_PADDING;
            if (cache.isSolid(wx, wy, wz + 1)) nudgeZ -= WALL_PADDING;
            if (cache.isSolid(wx, wy, wz - 1)) nudgeZ += WALL_PADDING;
            if (nudgeX != 0 || nudgeZ != 0) {
                BlockPos nudged = BlockPos.ofFloored(wx + 0.5 + nudgeX, wy, wz + 0.5 + nudgeZ);
                if (cache.isPassable(nudged.getX(), nudged.getY(), nudged.getZ())
                    && cache.isPassable(nudged.getX(), nudged.getY() + 1, nudged.getZ())) {
                    smooth.set(w, nudged);
                }
            }
        }

        return smooth;
    }

    /** Check if the full player hitbox (0.6 wide) can pass in a straight line. */
    private static boolean hasLineOfSight(BlockCache cache, BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        if (steps == 0) return true;
        if (Math.abs(to.getY() - from.getY()) > 1) return false;

        // Perpendicular offset direction (normalized)
        double len = Math.sqrt(dx * dx + dz * dz);
        double perpX = -dz / len;
        double perpZ =  dx / len;

        // 3 rays: center, left (-0.3), right (+0.3) — player half-width
        double[][] offsets = {{0, 0}, {perpX * 0.3, perpZ * 0.3}, {-perpX * 0.3, -perpZ * 0.3}};

        for (double[] off : offsets) {
            for (int s = 1; s < steps; s++) {
                float t = (float) s / steps;
                int cx = (int) Math.floor(from.getX() + 0.5 + dx * t + off[0]);
                int cz = (int) Math.floor(from.getZ() + 0.5 + dz * t + off[1]);
                int cy = from.getY() + Math.round((to.getY() - from.getY()) * t);
                if (!cache.isPassable(cx, cy, cz) || !cache.isPassable(cx, cy + 1, cz)) {
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
