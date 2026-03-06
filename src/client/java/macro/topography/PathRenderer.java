package macro.topography;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

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
        ClientWorld world = MinecraftClient.getInstance().world;
        if (world == null) return;

        List<BlockPos> path = Pathfinder.getCurrentPath();
        int wpIndex = Pathfinder.getWaypointIndex();

        MatrixStack matrices = context.matrices();
        VertexConsumerProvider consumers = context.consumers();
        Vec3d cam = context.worldState().cameraRenderState.pos;
        float pulse = (float) (0.6 + 0.4 * Math.sin(System.currentTimeMillis() / 200.0));

        matrices.push();
        matrices.translate(-cam.x, -cam.y, -cam.z);

        if (!path.isEmpty()) {
            renderPathBlocks(matrices, consumers, world, path, wpIndex, pulse);
            renderPathLines(matrices, consumers, world, path, wpIndex);
        }

        renderTargetMobHitbox(matrices, consumers, pulse);

        matrices.pop();
    }

    /* Highlight each block in the route (Taunahi-like). */
    private static void renderPathBlocks(MatrixStack matrices, VertexConsumerProvider consumers,
                                         ClientWorld world, List<BlockPos> path, int wpIndex, float pulse) {
        for (int i = 0; i < path.size(); i++) {
            BlockPos supportPos = path.get(i).down();
            VoxelShape shape = getSupportShape(world, supportPos);
            if (shape.isEmpty()) continue;

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
            float outlineAlpha = (i == wpIndex) ? (0.65f + 0.25f * pulse) : 0.45f;
            for (Box part : shape.getBoundingBoxes()) {
                float minX = (float) (supportPos.getX() + part.minX + 0.01);
                float minY = (float) (supportPos.getY() + part.minY + 0.01);
                float minZ = (float) (supportPos.getZ() + part.minZ + 0.01);
                float maxX = (float) (supportPos.getX() + part.maxX - 0.01);
                float maxY = (float) (supportPos.getY() + part.maxY + 0.015);
                float maxZ = (float) (supportPos.getZ() + part.maxZ - 0.01);

                if (maxX <= minX || maxY <= minY || maxZ <= minZ) continue;

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
    }

    /* Draw direct segments between centers of consecutive blocks. */
    private static void renderPathLines(MatrixStack matrices, VertexConsumerProvider consumers,
                                        ClientWorld world, List<BlockPos> path, int wpIndex) {
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
            float ay = getSupportTopY(world, a) + 0.08f;
            float az = a.getZ() + 0.5f;
            float bx = b.getX() + 0.5f;
            float by = getSupportTopY(world, b) + 0.08f;
            float bz = b.getZ() + 0.5f;
            drawThickLine(matrices, consumers, ax, ay, az, bx, by, bz, thickness, r, g, bl, alpha);
        }

        VertexConsumer lines = consumers.getBuffer(RenderLayer.getLines());
        for (int i = 0; i < path.size() - 1; i++) {
            BlockPos a = path.get(i);
            BlockPos b = path.get(i + 1);

            float ax = a.getX() + 0.5f;
            float ay = getSupportTopY(world, a) + 0.08f;
            float az = a.getZ() + 0.5f;
            float bx = b.getX() + 0.5f;
            float by = getSupportTopY(world, b) + 0.08f;
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

    private static VoxelShape getSupportShape(ClientWorld world, BlockPos supportPos) {
        BlockState state = world.getBlockState(supportPos);
        VoxelShape shape = state.getCollisionShape(world, supportPos);
        if (!shape.isEmpty()) return shape;
        return state.getOutlineShape(world, supportPos);
    }

    private static float getSupportTopY(ClientWorld world, BlockPos pathPos) {
        BlockPos supportPos = pathPos.down();
        VoxelShape shape = getSupportShape(world, supportPos);
        if (!shape.isEmpty()) {
            return (float) (supportPos.getY() + shape.getMax(Direction.Axis.Y));
        }
        return supportPos.getY() + 1.0f;
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

    private static UUID lastPrimaryTargetId = null;
    private static long primarySwitchFlashUntilMs = 0L;
    private static final long PRIMARY_SWITCH_FLASH_MS = 220L;

    private static void renderTargetMobHitbox(MatrixStack matrices, VertexConsumerProvider consumers,
                                               float pulse) {
        List<LivingEntity> mobs = Pathfinder.getTargetMobs();
        LivingEntity primary = Pathfinder.getTargetMob();

        UUID primaryId = (primary != null) ? primary.getUuid() : null;
        if (!Objects.equals(primaryId, lastPrimaryTargetId)) {
            lastPrimaryTargetId = primaryId;
            primarySwitchFlashUntilMs = System.currentTimeMillis() + PRIMARY_SWITCH_FLASH_MS;
        }
        boolean switchFlash = System.currentTimeMillis() < primarySwitchFlashUntilMs;

        for (LivingEntity mob : mobs) {
            if (mob == null || !mob.isAlive()) continue;
            if (mob instanceof ArmorStandEntity) continue;
            if (mob == primary) continue;

            Box box = mob.getBoundingBox();
            float minX = (float) box.minX;
            float minY = (float) box.minY;
            float minZ = (float) box.minZ;
            float maxX = (float) box.maxX;
            float maxY = (float) box.maxY;
            float maxZ = (float) box.maxZ;

            // Non-selected candidates: pale yellow.
            VertexRendering.drawBox(
                matrices.peek(), consumers.getBuffer(RenderLayer.getLines()),
                minX, minY, minZ, maxX, maxY, maxZ,
                1f, 0.95f, 0.55f, 0.55f
            );
            VertexRendering.drawFilledBox(
                matrices, consumers.getBuffer(RenderLayer.getDebugFilledBox()),
                minX, minY, minZ, maxX, maxY, maxZ,
                1f, 0.95f, 0.55f, 0.10f
            );
        }

        if (primary != null && primary.isAlive() && !(primary instanceof ArmorStandEntity)) {
            Box box = primary.getBoundingBox();
            float minX = (float) box.minX - 0.01f;
            float minY = (float) box.minY;
            float minZ = (float) box.minZ - 0.01f;
            float maxX = (float) box.maxX + 0.01f;
            float maxY = (float) box.maxY + 0.01f;
            float maxZ = (float) box.maxZ + 0.01f;

            float g = switchFlash ? 0.14f : 0.22f;
            float b = switchFlash ? 0.14f : 0.22f;
            float outlineAlpha = switchFlash ? 1.0f : (0.85f + 0.15f * pulse);
            float fillAlpha = switchFlash ? 0.36f : 0.24f;

            // Selected target: strong red, immediate switch feedback.
            VertexRendering.drawBox(
                matrices.peek(), consumers.getBuffer(RenderLayer.getLines()),
                minX, minY, minZ, maxX, maxY, maxZ,
                1f, g, b, outlineAlpha
            );
            VertexRendering.drawFilledBox(
                matrices, consumers.getBuffer(RenderLayer.getDebugFilledBox()),
                minX, minY, minZ, maxX, maxY, maxZ,
                1f, g, b, fillAlpha
            );
        }
    }
}
