package macro.topography;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class ZoneCommand {

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(literal("iszone").executes(ZoneCommand::execute))
        );
    }

    private static int execute(CommandContext<FabricClientCommandSource> ctx) {
        Vec3d pos = ctx.getSource().getPlayer().getEntityPos();
        int x = (int) pos.x, y = (int) pos.y, z = (int) pos.z;

        boolean inZealot  = Pathfinder.isInFarmZone(pos, 1);
        boolean inBruiser = Pathfinder.isInFarmZone(pos, 2);

        ctx.getSource().sendFeedback(Text.literal("§eПозиция: " + x + " " + y + " " + z));

        if (inZealot) {
            ctx.getSource().sendFeedback(Text.literal("§a[В ЗОНЕ] Zealot zone"));
        } else {
            ctx.getSource().sendFeedback(Text.literal("§c[НЕ В ЗОНЕ] Zealot zone"));
        }

        if (inBruiser) {
            ctx.getSource().sendFeedback(Text.literal("§a[В ЗОНЕ] Bruiser zone"));
        } else {
            ctx.getSource().sendFeedback(Text.literal("§c[НЕ В ЗОНЕ] Bruiser zone"));
        }

        return 1;
    }
}
