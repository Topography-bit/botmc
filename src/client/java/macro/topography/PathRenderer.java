package macro.topography;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * Renders pathfinder path as neon lines on block surfaces + mob hitbox highlight.
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

        // ── Path lines — on block surface ──
        if (!path.isEmpty()) {
            VertexConsumer lines = consumers.getBuffer(RenderLayer.getLines());
            for (int i = 0; i < path.size() - 1; i++) {
                BlockPos a = path.get(i);
                BlockPos b = path.get(i + 1);

                // Surface of the block = feet Y + tiny offset to avoid z-fighting
                float ax = a.getX() + 0.5f, ay = a.getY() + 0.01f, az = a.getZ() + 0.5f;
                float bx = b.getX() + 0.5f, by = b.getY() + 0.01f, bz = b.getZ() + 0.5f;

                int r, g, bl, alpha;
                if (i < wpIndex) {
                    // Passed — dimmer but still visible
                    r = 40; g = 120; bl = 130; alpha = 120;
                } else if (i == wpIndex) {
                    // Current segment — full bright pulsing cyan
                    r = 0; g = 255; bl = 255; alpha = 255;
                } else {
                    // Upcoming — bright cyan, full opacity
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

            // ── Waypoint dots ──
            for (int i = wpIndex; i < path.size(); i++) {
                BlockPos wp = path.get(i);
                float cx = wp.getX() + 0.5f;
                float cy = wp.getY() + 0.01f;
                float cz = wp.getZ() + 0.5f;

                if (i == wpIndex) {
                    // Current waypoint — pulsing cyan box
                    float size = 0.22f + 0.08f * pulse;
                    VertexRendering.drawFilledBox(
                        matrices, consumers.getBuffer(RenderLayer.getDebugFilledBox()),
                        cx - size, cy, cz - size,
                        cx + size, cy + size * 2, cz + size,
                        0f, 1f, 1f, pulse * 0.9f
                    );
                } else if (i == path.size() - 1) {
                    // Final destination — golden marker (only if no mob target)
                    if (Pathfinder.getTargetMob() == null) {
                        VertexRendering.drawFilledBox(
                            matrices, consumers.getBuffer(RenderLayer.getDebugFilledBox()),
                            cx - 0.35f, cy, cz - 0.35f,
                            cx + 0.35f, cy + 0.9f, cz + 0.35f,
                            1f, 0.85f, 0f, 0.9f
                        );
                    }
                } else {
                    // Other waypoints — cyan dots
                    float s = 0.12f;
                    VertexRendering.drawFilledBox(
                        matrices, consumers.getBuffer(RenderLayer.getDebugFilledBox()),
                        cx - s, cy, cz - s,
                        cx + s, cy + s * 2, cz + s,
                        0.1f, 1f, 1f, 0.7f
                    );
                }
            }

            // ── Vertical beam at current waypoint ──
            if (wpIndex < path.size()) {
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
        }

        // ── Target mob hitbox highlight ──
        LivingEntity targetMob = Pathfinder.getTargetMob();
        if (targetMob != null && targetMob.isAlive()) {
            Box box = targetMob.getBoundingBox();
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

        matrices.pop();
    }
}
