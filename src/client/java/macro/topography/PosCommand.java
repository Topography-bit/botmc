package macro.topography;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class PosCommand {

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(literal("pos").executes(PosCommand::showPosition))
        );
    }

    private static int showPosition(CommandContext<FabricClientCommandSource> ctx) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null) {
            ctx.getSource().sendFeedback(Text.literal("[Topography] Player is not available."));
            return 0;
        }

        Vec3d pos = player.getEntityPos();
        BlockPos block = player.getBlockPos();
        String message = String.format(
            "[Topography] XYZ: %.2f %.2f %.2f | Block: %d %d %d",
            pos.x, pos.y, pos.z,
            block.getX(), block.getY(), block.getZ()
        );
        ctx.getSource().sendFeedback(Text.literal(message));
        return 1;
    }
}
