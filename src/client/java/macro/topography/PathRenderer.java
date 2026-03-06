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

import java.util.List;

/**
 * Renders pathfinder path as highlighted blocks + direct lines + mob hitbox highlight.
 */
public class PathRenderer {

    public static boolean enabled = true;

    public static void register() {
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(PathRenderer::onWorldRender);
    }

    private static void onWorldRender(WorldRenderContext context) {
        if (!enabled) return;
        if (context.matrices() == null || context.consumers() == null || context.worldState() == null) return;

        List<BlockPos> path = Pathfinder.getCurrentPath();
        int wpIndex = Pathfinder.getWaypointIndex();

        MatrixStack matrices = context.matrices();
        VertexConsumerProvider consumers = context.consumers();
        Vec3d cam = context.worldState().cameraRenderState.pos;
        float pulse = (float) (0.6 + 0.4 * Math.sin(System.currentTimeMillis() / 200.0));

        matrices.push();
        matrices.translate(-cam.x, -cam.y, -cam.z);

        if (!path.isEmpty()) {
            renderPathBlocks(matrices, consumers, path, wpIndex, pulse);
            renderPathLines(matrices, consumers, path, wpIndex);
        }

        renderTargetMobHitbox(matrices, consumers, pulse);

        matrices.pop();
    }

    /* Highlight each block in the route (Taunahi-like). */
    private static void renderPathBlocks(MatrixStack matrices, VertexConsumerProvider consumers,
                                         List<BlockPos> path, int wpIndex, float pulse) {
        for (int i = 0; i < path.size(); i++) {
            BlockPos p = path.get(i);

            float r;
            float g;
            float b;
            float alpha;
            if (i < wpIndex) {
                r = 0.12f;
                g = 0.40f;
                b = 0.45f;
                alpha = 0.16f;
            } else if (i == wpIndex) {
                r = 0.0f;
                g = 1.0f;
                b = 1.0f;
                alpha = 0.25f + 0.18f * pulse;
            } else {
                r = 0.0f;
                g = 0.92f;
                b = 1.0f;
                alpha = 0.24f;
            }

            float minX = p.getX() + 0.06f;
            float minY = p.getY() + 0.02f;
            float minZ = p.getZ() + 0.06f;
            float maxX = p.getX() + 0.94f;
            float maxY = p.getY() + 1.02f;
            float maxZ = p.getZ() + 0.94f;

            VertexRendering.drawFilledBox(
                matrices,
                consumers.getBuffer(RenderLayer.getDebugFilledBox()),
                minX,
                minY,
                minZ,
                maxX,
                maxY,
                maxZ,
                r,
                g,
                b,
                alpha
            );

            float outlineAlpha = (i == wpIndex) ? (0.65f + 0.25f * pulse) : 0.45f;
            VertexRendering.drawBox(
                matrices.peek(),
                consumers.getBuffer(RenderLayer.getLines()),
                minX,
                minY,
                minZ,
                maxX,
                maxY,
                maxZ,
                r,
                g,
                b,
                outlineAlpha
            );
        }
    }

    /* Draw direct segments between centers of consecutive blocks. */
    private static void renderPathLines(MatrixStack matrices, VertexConsumerProvider consumers,
                                        List<BlockPos> path, int wpIndex) {
        float thickness = 0.045f;

        for (int i = 0; i < path.size() - 1; i++) {
            BlockPos a = path.get(i);
            BlockPos b = path.get(i + 1);

            float r;
            float g;
            float bl;
            float alpha;
            if (i < wpIndex) {
                r = 0.15f;
                g = 0.45f;
                bl = 0.5f;
                alpha = 0.4f;
            } else {
                r = 0f;
                g = 1f;
                bl = 1f;
                alpha = 0.85f;
            }

            float ax = a.getX() + 0.5f;
            float ay = a.getY() + 0.5f;
            float az = a.getZ() + 0.5f;
            float bx = b.getX() + 0.5f;
            float by = b.getY() + 0.5f;
            float bz = b.getZ() + 0.5f;
            drawThickLine(matrices, consumers, ax, ay, az, bx, by, bz, thickness, r, g, bl, alpha);
        }

        VertexConsumer lines = consumers.getBuffer(RenderLayer.getLines());
        for (int i = 0; i < path.size() - 1; i++) {
            BlockPos a = path.get(i);
            BlockPos b = path.get(i + 1);

            float ax = a.getX() + 0.5f;
            float ay = a.getY() + 0.5f;
            float az = a.getZ() + 0.5f;
            float bx = b.getX() + 0.5f;
            float by = b.getY() + 0.5f;
            float bz = b.getZ() + 0.5f;

            int r;
            int g;
            int bl;
            int alpha;
            if (i < wpIndex) {
                r = 40;
                g = 120;
                bl = 130;
                alpha = 100;
            } else {
                r = 0;
                g = 255;
                bl = 255;
                alpha = 255;
            }

            drawLine(lines, matrices, ax, ay, az, bx, by, bz, r, g, bl, alpha);
        }
    }

    private static void drawThickLine(MatrixStack matrices, VertexConsumerProvider consumers,
                                      float ax, float ay, float az,
                                      float bx, float by, float bz,
                                      float thickness, float r, float g, float bl, float alpha) {
        float dx = bx - ax;
        float dy = by - ay;
        float dz = bz - az;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 0.01f) return;

        int segments = Math.max(1, (int) (len / 0.45f));
        for (int s = 0; s <= segments; s++) {
            float t = (float) s / segments;
            float cx = ax + dx * t;
            float cy = ay + dy * t;
            float cz = az + dz * t;
            VertexRendering.drawFilledBox(
                matrices,
                consumers.getBuffer(RenderLayer.getDebugFilledBox()),
                cx - thickness,
                cy - thickness,
                cz - thickness,
                cx + thickness,
                cy + thickness,
                cz + thickness,
                r,
                g,
                bl,
                alpha
            );
        }
    }

    private static void drawLine(VertexConsumer lines, MatrixStack matrices,
                                 float ax, float ay, float az,
                                 float bx, float by, float bz,
                                 int r, int g, int bl, int alpha) {
        float dx = bx - ax;
        float dy = by - ay;
        float dz = bz - az;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 0.001f) return;
        float nx = dx / len;
        float ny = dy / len;
        float nz = dz / len;
        lines.vertex(matrices.peek(), ax, ay, az)
            .color(r, g, bl, alpha)
            .normal(matrices.peek(), nx, ny, nz);
        lines.vertex(matrices.peek(), bx, by, bz)
            .color(r, g, bl, alpha)
            .normal(matrices.peek(), nx, ny, nz);
    }

    /* All target mob hitboxes, color-coded by path cost. */
    private static final float[][] MOB_COLORS = {
        {1f, 0.2f, 0.2f},
        {1f, 0.6f, 0.1f},
        {1f, 1f, 0.1f},
        {0.4f, 1f, 0.2f},
        {0.2f, 0.6f, 1f}
    };

    private static void renderTargetMobHitbox(MatrixStack matrices, VertexConsumerProvider consumers,
                                               float pulse) {
        List<LivingEntity> mobs = Pathfinder.getTargetMobs();
        float[] costs = Pathfinder.getMobPathCosts();

        for (int i = 0; i < mobs.size(); i++) {
            LivingEntity mob = mobs.get(i);
            if (mob == null || !mob.isAlive()) continue;
            if (mob instanceof ArmorStandEntity) continue;

            net.minecraft.util.math.Box box = mob.getBoundingBox();
            float minX = (float) box.minX;
            float minY = (float) box.minY;
            float minZ = (float) box.minZ;
            float maxX = (float) box.maxX;
            float maxY = (float) box.maxY;
            float maxZ = (float) box.maxZ;

            float[] c = (i < MOB_COLORS.length) ? MOB_COLORS[i] : MOB_COLORS[MOB_COLORS.length - 1];
            float cost = (i < costs.length) ? costs[i] : 1f;
            float brightness = 1f - cost * 0.5f;

            VertexRendering.drawBox(
                matrices.peek(), consumers.getBuffer(RenderLayer.getLines()),
                minX, minY, minZ, maxX, maxY, maxZ,
                c[0] * brightness, c[1] * brightness, c[2] * brightness, pulse
            );
            VertexRendering.drawFilledBox(
                matrices, consumers.getBuffer(RenderLayer.getDebugFilledBox()),
                minX, minY, minZ, maxX, maxY, maxZ,
                c[0], c[1], c[2], pulse * 0.2f
            );
        }

        LivingEntity primary = Pathfinder.getTargetMob();
        if (primary != null && primary.isAlive() && !(primary instanceof ArmorStandEntity)) {
            boolean alreadyRendered = false;
            for (LivingEntity m : mobs) {
                if (m == primary) {
                    alreadyRendered = true;
                    break;
                }
            }
            if (!alreadyRendered) {
                net.minecraft.util.math.Box box = primary.getBoundingBox();
                VertexRendering.drawBox(
                    matrices.peek(), consumers.getBuffer(RenderLayer.getLines()),
                    (float) box.minX, (float) box.minY, (float) box.minZ,
                    (float) box.maxX, (float) box.maxY, (float) box.maxZ,
                    1f, 0.2f, 0.6f, pulse
                );
            }
        }
    }
}
