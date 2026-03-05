package macro.topography;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.RaycastContext;

public class RaycastHelper {

    public static float calcDistToWallOrBlock(MinecraftClient client, ClientPlayerEntity player, float angleDegrees, float height){
        if (client.world == null) return 1.0f;

        float currentPitch = player.getPitch();
        float currentYaw = player.getYaw();

        float targetYaw = currentYaw + angleDegrees;
        Vec3d start = player.getEntityPos().add(0, height, 0);

        Vec3d direction = Vec3d.fromPolar(0, targetYaw);

        Vec3d end = start.add(direction.multiply(10));

        RaycastContext context = new RaycastContext(
                start, end,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                player
        );

        BlockHitResult hit = client.world.raycast(context);

        if (hit.getType() == HitResult.Type.BLOCK){
            double realDistance = hit.getPos().distanceTo(start);
            return (float) realDistance / 10;
        }

        return 1.0f;
    }

    public static float[] calcDistToBlock(MinecraftClient client, ClientPlayerEntity player, float angleDegrees) {
        if (client.world == null) return new float[]{1.0f, 0f};

        Vec3d start = player.getEntityPos().add(0, 0.2, 0);
        float currentYaw = player.getYaw();

        float targetYaw = currentYaw + angleDegrees;
        Vec3d direction = Vec3d.fromPolar(0, targetYaw);
        Vec3d end = start.add(direction.multiply(10));

        RaycastContext context = new RaycastContext(
                start, end,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                player
        );

        BlockHitResult hit = client.world.raycast(context);

        if (hit.getType() == HitResult.Type.BLOCK) {
            float dist = (float) hit.getPos().distanceTo(start) / 10;

            BlockState state = client.world.getBlockState(hit.getBlockPos());
            VoxelShape shape = state.getCollisionShape(client.world, hit.getBlockPos());
            float height = (float) shape.getMax(Direction.Axis.Y);


            return new float[]{ dist, height };
        }

        return new float[]{ 1.0f, 0f };
    }

    /**
     * Вертикальный луч вниз из точки на forwardDist блоков впереди игрока.
     * Возвращает расстояние до земли / 10, capped at 1.0.
     */
    public static float calcGroundProbe(MinecraftClient client, ClientPlayerEntity player, float forwardDist) {
        if (client.world == null) return 1.0f;

        Vec3d forward = Vec3d.fromPolar(0, player.getYaw());
        Vec3d start = player.getEntityPos().add(0, 1.6, 0).add(forward.multiply(forwardDist));
        Vec3d end = start.add(0, -10, 0);

        RaycastContext context = new RaycastContext(
                start, end,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                player
        );

        BlockHitResult hit = client.world.raycast(context);

        if (hit.getType() == HitResult.Type.BLOCK) {
            return (float) hit.getPos().distanceTo(start) / 10f;
        }

        return 1.0f;
    }

    public static float calcVerticalClearance(MinecraftClient client, ClientPlayerEntity player){
        if (client.world == null) return 0f;

        Vec3d direction = Vec3d.fromPolar(0, player.getYaw());
        Vec3d start = player.getEntityPos().add(0, 1.6f, 0);
        Vec3d end = start.add(direction.multiply(10));
        RaycastContext context = new RaycastContext(
                start, end,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                player
        );
        BlockHitResult hit = client.world.raycast(context);

        if (hit.getType() != HitResult.Type.BLOCK) return 1.0f;

        BlockPos wallBase = hit.getBlockPos();

        int clearance = 0;
        for (int i = 1; i < 30; i++){
            BlockPos aboveBlock = wallBase.up(i);
            if (clearance == 3){
                break;
            } else if (client.world.getBlockState(aboveBlock).isAir()) {
                clearance++;
            } else if (clearance != 0){
                return 0f;
            }
        }
        return clearance / 3f;

    }
}
