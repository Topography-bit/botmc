package macro.topography;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders pathfinder path as visible lines + key waypoint markers + mob hitbox highlight.
 */
public class PathRenderer {

    public static boolean enabled = true;

    public static void register() {
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(PathRenderer::onWorldRender);
    }

    private static void onWorldRender(WorldRenderContext context) {
        if (!enabled) return;

        List<BlockPos> path = Pathfinder.getCurrentPath();
        int wpIndex = Pathfinder.getWaypointIndex();

        MatrixStack matrices = context.matrices();
        VertexConsumerProvider consumers = context.consumers();
        Vec3d cam = context.worldState().cameraRenderState.pos;
        float pulse = (float) (0.6 + 0.4 * Math.sin(System.currentTimeMillis() / 200.0));

        matrices.push();
        matrices.translate(-cam.x, -cam.y, -cam.z);

        if (!path.isEmpty()) {
            renderPathLines(matrices, consumers, path, wpIndex);
            renderKeyWaypoints(matrices, consumers, path, wpIndex, pulse);
            renderCurrentWaypointBeam(matrices, consumers, path, wpIndex, pulse);
        }

        renderTargetMobHitbox(matrices, consumers, pulse);

        matrices.pop();
    }

    /* ── Thick visible path lines using small filled box segments ── */
    private static void renderPathLines(MatrixStack matrices, VertexConsumerProvider consumers,
                                         List<BlockPos> path, int wpIndex) {
        float thickness = 0.06f;

        for (int i = 0; i < path.size() - 1; i++) {
            BlockPos a = path.get(i);
            BlockPos b = path.get(i + 1);

            float ax = a.getX() + 0.5f, ay = a.getY() + 0.15f, az = a.getZ() + 0.5f;
            float bx = b.getX() + 0.5f, by = b.getY() + 0.15f, bz = b.getZ() + 0.5f;

            float r, g, bl, alpha;
            if (i < wpIndex) {
                // Passed segments — dim teal
                r = 0.15f; g = 0.45f; bl = 0.5f; alpha = 0.4f;
            } else {
                // Active/upcoming — bright cyan
                r = 0f; g = 1f; bl = 1f; alpha = 0.85f;
            }

            // Interpolate small filled boxes along the segment for "thick line" effect
            float dx = bx - ax, dy = by - ay, dz = bz - az;
            float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 0.01f) continue;

            float step = 0.5f; // one box every 0.5 blocks
            int segments = Math.max(1, (int) (len / step));

            for (int s = 0; s <= segments; s++) {
                float t = (float) s / segments;
                float cx = ax + dx * t;
                float cy = ay + dy * t;
                float cz = az + dz * t;

                VertexRendering.drawFilledBox(
                    matrices, consumers.getBuffer(RenderLayer.getDebugFilledBox()),
                    cx - thickness, cy - thickness, cz - thickness,
                    cx + thickness, cy + thickness, cz + thickness,
                    r, g, bl, alpha
                );
            }
        }

        // Also draw thin RenderLayer lines for connectivity
        VertexConsumer lines = consumers.getBuffer(RenderLayer.getLines());
        for (int i = 0; i < path.size() - 1; i++) {
            BlockPos a = path.get(i);
            BlockPos b = path.get(i + 1);

            float ax = a.getX() + 0.5f, ay = a.getY() + 0.15f, az = a.getZ() + 0.5f;
            float bx = b.getX() + 0.5f, by = b.getY() + 0.15f, bz = b.getZ() + 0.5f;

            int r, g, bl, alpha;
            if (i < wpIndex) {
                r = 40; g = 120; bl = 130; alpha = 100;
            } else {
                r = 0; g = 255; bl = 255; alpha = 255;
            }

            float dx = bx - ax, dy = by - ay, dz = bz - az;
            float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 0.001f) continue;
            float nx = dx / len, ny = dy / len, nz = dz / len;

            lines.vertex(matrices.peek(), ax, ay, az)
                 .color(r, g, bl, alpha)
                 .normal(matrices.peek(), nx, ny, nz);
            lines.vertex(matrices.peek(), bx, by, bz)
                 .color(r, g, bl, alpha)
                 .normal(matrices.peek(), nx, ny, nz);
        }
    }

    /* ── Key waypoint markers every 3-8 blocks based on direction changes ── */
    private static void renderKeyWaypoints(MatrixStack matrices, VertexConsumerProvider consumers,
                                            List<BlockPos> path, int wpIndex, float pulse) {
        // Build list of key waypoints: first, last, direction changes, and spaced markers
        List<Integer> keyIndices = new ArrayList<>();
        keyIndices.add(0); // start

        float accumDist = 0;
        for (int i = 1; i < path.size(); i++) {
            BlockPos prev = path.get(i - 1);
            BlockPos cur = path.get(i);
            float dx = cur.getX() - prev.getX();
            float dy = cur.getY() - prev.getY();
            float dz = cur.getZ() - prev.getZ();
            float segLen = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            accumDist += segLen;

            boolean isLast = (i == path.size() - 1);
            boolean dirChange = false;

            // Check direction change at this point
            if (i < path.size() - 1) {
                BlockPos next = path.get(i + 1);
                float dx2 = next.getX() - cur.getX();
                float dy2 = next.getY() - cur.getY();
                float dz2 = next.getZ() - cur.getZ();
                float len1 = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                float len2 = (float) Math.sqrt(dx2 * dx2 + dy2 * dy2 + dz2 * dz2);
                if (len1 > 0.01f && len2 > 0.01f) {
                    float dot = (dx * dx2 + dy * dy2 + dz * dz2) / (len1 * len2);
                    // dot < 0.85 means > ~30 degree turn
                    if (dot < 0.85f) dirChange = true;
                }
            }

            // At direction changes: mark if at least 3 blocks from last key
            // On straight paths: mark every 8 blocks
            float minDist = dirChange ? 3.0f : 8.0f;

            if (isLast || (accumDist >= minDist)) {
                keyIndices.add(i);
                accumDist = 0;
            }
        }

        // Render each key waypoint
        for (int idx : keyIndices) {
            BlockPos wp = path.get(idx);
            float cx = wp.getX() + 0.5f;
            float cy = wp.getY() + 0.01f;
            float cz = wp.getZ() + 0.5f;

            if (idx == wpIndex) {
                // Current waypoint — larger pulsing cyan marker
                float size = 0.25f + 0.1f * pulse;
                VertexRendering.drawFilledBox(
                    matrices, consumers.getBuffer(RenderLayer.getDebugFilledBox()),
                    cx - size, cy, cz - size,
                    cx + size, cy + size * 2.5f, cz + size,
                    0f, 1f, 1f, pulse * 0.9f
                );
            } else if (idx == path.size() - 1) {
                // Final destination — golden marker (only if no mob target)
                if (Pathfinder.getTargetMob() == null) {
                    VertexRendering.drawFilledBox(
                        matrices, consumers.getBuffer(RenderLayer.getDebugFilledBox()),
                        cx - 0.35f, cy, cz - 0.35f,
                        cx + 0.35f, cy + 0.9f, cz + 0.35f,
                        1f, 0.85f, 0f, 0.9f
                    );
                }
            } else if (idx >= wpIndex) {
                // Upcoming key waypoint — medium cyan diamond
                float s = 0.18f;
                VertexRendering.drawFilledBox(
                    matrices, consumers.getBuffer(RenderLayer.getDebugFilledBox()),
                    cx - s, cy, cz - s,
                    cx + s, cy + s * 3f, cz + s,
                    0.05f, 0.9f, 1f, 0.75f
                );
            }
            // Skip passed waypoints — no marker
        }
    }

    /* ── Vertical beam at current waypoint ── */
    private static void renderCurrentWaypointBeam(MatrixStack matrices, VertexConsumerProvider consumers,
                                                    List<BlockPos> path, int wpIndex, float pulse) {
        if (wpIndex >= path.size()) return;

        BlockPos wp = path.get(wpIndex);
        float cx = wp.getX() + 0.5f;
        float cz = wp.getZ() + 0.5f;
        float baseY = wp.getY();

        VertexRendering.drawFilledBox(
            matrices, consumers.getBuffer(RenderLayer.getDebugFilledBox()),
            cx - 0.04f, baseY, cz - 0.04f,
            cx + 0.04f, baseY + 5f, cz + 0.04f,
            0f, 1f, 1f, pulse * 0.4f
        );
    }

    /* ── Target mob hitbox highlight — excludes ArmorStands ── */
    private static void renderTargetMobHitbox(MatrixStack matrices, VertexConsumerProvider consumers,
                                                float pulse) {
        LivingEntity targetMob = Pathfinder.getTargetMob();
        if (targetMob == null || !targetMob.isAlive()) return;

        // Skip ArmorStands (holograms, portal labels, etc.)
        if (targetMob instanceof ArmorStandEntity) return;

        net.minecraft.util.math.Box box = targetMob.getBoundingBox();
        float minX = (float) box.minX;
        float minY = (float) box.minY;
        float minZ = (float) box.minZ;
        float maxX = (float) box.maxX;
        float maxY = (float) box.maxY;
        float maxZ = (float) box.maxZ;

        // Bright pulsing red-magenta outline
        VertexRendering.drawBox(
            matrices.peek(), consumers.getBuffer(RenderLayer.getLines()),
            minX, minY, minZ, maxX, maxY, maxZ,
            1f, 0.2f, 0.6f, pulse
        );
        // Semi-transparent fill
        VertexRendering.drawFilledBox(
            matrices, consumers.getBuffer(RenderLayer.getDebugFilledBox()),
            minX, minY, minZ, maxX, maxY, maxZ,
            1f, 0.1f, 0.4f, pulse * 0.3f
        );
    }
}
