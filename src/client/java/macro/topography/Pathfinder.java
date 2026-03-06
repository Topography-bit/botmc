package macro.topography;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
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
    private static final double MAX_PATHFIND_DIST = 50.0; // intermediate goal distance

    /* ── waypoint management ──────────────────────────────────────── */
    private static final double ADVANCE_RADIUS = 1.5;
    private static final double FINAL_RADIUS = 0.3;

    /* ── async control ────────────────────────────────────────────── */
    private static final int RECALC_COOLDOWN = 20;
    private static final int SAFETY_RECALC = 100;
    private static final double DEVIATE_DIST = 5.0;

    /* ── mob targeting ────────────────────────────────────────────── */
    private static final double ATTACK_STANDOFF = 1.75;

    /* ── state ────────────────────────────────────────────────────── */
    private static List<BlockPos> currentPath = Collections.emptyList();
    private static int waypointIndex = 0;
    private static int ticksSinceRecalc = 0;
    private static int recalcCooldown = 0;
    private static final AtomicBoolean computing = new AtomicBoolean(false);
    private static CompletableFuture<List<BlockPos>> pendingFuture = null;
    private static BlockPos lastGoal = null;
    private static LivingEntity targetMob = null;        // real mob for hitbox (can be null)
    private static ArmorStandEntity targetNameTag = null; // name tag for position
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

        // Debug logging every 5 sec
        if (ticksSinceRecalc % 100 == 0) {
            System.out.printf("[Pathfinder] mode=%d goal=%s targetMob=%s nameTag=%s path=%d wp=%d%n",
                targetMode,
                goal != null ? goal.toShortString() : "null",
                targetMob != null ? targetMob.getType().getTranslationKey() : "null",
                targetNameTag != null ? getMobName(targetNameTag) : "null",
                currentPath.size(), waypointIndex);
        }

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

            // For distant goals, create intermediate goal in the right direction
            BlockPos pathGoal = goal;
            double distToGoal = Math.sqrt(
                Math.pow(goal.getX() - start.getX(), 2) +
                Math.pow(goal.getY() - start.getY(), 2) +
                Math.pow(goal.getZ() - start.getZ(), 2));
            if (distToGoal > MAX_PATHFIND_DIST) {
                double ratio = MAX_PATHFIND_DIST / distToGoal;
                int ix = start.getX() + (int) ((goal.getX() - start.getX()) * ratio);
                int iy = start.getY() + (int) ((goal.getY() - start.getY()) * ratio);
                int iz = start.getZ() + (int) ((goal.getZ() - start.getZ()) * ratio);
                pathGoal = findNearestStandable(client.world, new BlockPos(ix, iy, iz));
            }

            BlockCache cache = BlockCache.snapshotBetween(client.world, start, pathGoal);
            startPathfinding(cache, start, pathGoal);
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
        targetNameTag = null;
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
        if (client.world == null) return null;
        Vec3d pPos = player.getEntityPos();

        // Step 1: Find nearest ArmorStand name tag with matching name
        ArmorStandEntity bestTag = findNearestNameTag(client, player, targetMode);

        if (bestTag != null) {
            targetNameTag = bestTag;
            Vec3d tagPos = bestTag.getEntityPos();

            // Try to find real mob near the name tag for hitbox rendering
            targetMob = findRealMobNear(client, player, bestTag);

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

        targetMob = null;
        targetNameTag = null;
        if (targetMode == 1) return DRAGONS_NEST;
        if (targetMode == 2) return BRUISER_HIDEOUT;
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

    /* ================================================================
     *  MOB TARGETING — find ArmorStand name tags, then pair with real mob
     * ============================================================== */

    /** Find nearest ArmorStand name tag with matching mob name. */
    private static ArmorStandEntity findNearestNameTag(MinecraftClient client,
                                                        ClientPlayerEntity player, int targetMode) {
        if (client.world == null || targetMode == 0) return null;
        Vec3d pPos = player.getEntityPos();
        ArmorStandEntity best = null;
        double bestDist = Double.MAX_VALUE;

        for (LivingEntity e : client.world.getEntitiesByClass(
                LivingEntity.class, player.getBoundingBox().expand(50),
                ent -> ent instanceof ArmorStandEntity)) {
            String name = getMobName(e);
            if (name.isEmpty()) continue;
            boolean match = false;
            if (targetMode == 1) match = name.contains("Zealot") && !name.contains("Bruiser");
            if (targetMode == 2) match = name.contains("Bruiser");
            if (match) {
                double d = e.getEntityPos().distanceTo(pPos);
                if (d < bestDist) {
                    bestDist = d;
                    best = (ArmorStandEntity) e;
                }
            }
        }
        return best;
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
                    && ent != player)) {
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

    /* ── path smoothing — conservative, max 6 node skip ────────── */

    private static final int MAX_SMOOTH_SKIP = 6;

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
        int checks = Math.max(dist * 2, 4); // 2 checks per block for precision

        for (double[] off : offsets) {
            for (int s = 1; s <= checks; s++) {
                float t = (float) s / checks;
                int cx = (int) Math.floor(from.getX() + 0.5 + dx * t + off[0]);
                int cz = (int) Math.floor(from.getZ() + 0.5 + dz * t + off[1]);

                // Feet + head must be passable
                if (!cache.isPassable(cx, y, cz) || !cache.isPassable(cx, y + 1, cz)) {
                    return false;
                }
                // Must have ground below (within 2 blocks)
                if (!cache.isSolid(cx, y - 1, cz) && !cache.isSolid(cx, y - 2, cz)) {
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
