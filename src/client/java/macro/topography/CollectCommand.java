package macro.topography;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class CollectCommand {

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(literal("collect")
                .then(literal("start")
                    .then(literal("zealots").executes(ctx -> start(ctx, 1)))
                    .then(literal("bruisers").executes(ctx -> start(ctx, 2))))
                .then(literal("stop").executes(ctx -> stop(ctx))))
        );
    }

    private static int start(CommandContext<FabricClientCommandSource> ctx, int mode) {
        if (DataCollector.isRecording) {
            ctx.getSource().sendFeedback(Text.literal("[Topography] Сначала нада остановить запись: /collect stop"));
            return 0;
        }
        DataCollector.startCollecting(mode);
        String name = mode == 1 ? "Zealots" : "Bruisers";
        ctx.getSource().sendFeedback(Text.literal("[Topography] Запись начата — режим: " + name));
        return 1;
    }

    private static int stop(CommandContext<FabricClientCommandSource> ctx) {
        if (!DataCollector.isRecording) {
            ctx.getSource().sendFeedback(Text.literal("[Topography] Запись и так не идёт."));
            return 0;
        }
        DataCollector.stopCollecting();
        ctx.getSource().sendFeedback(Text.literal("[Topography] Запись остановлена. Файл сохранён в datasets/"));
        return 1;
    }
}