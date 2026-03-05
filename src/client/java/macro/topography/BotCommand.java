package macro.topography;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.text.Text;

/**
 * /bot start zealots              — load bot_model.onnx & start in zealot mode
 * /bot start bruisers mymodel     — load mymodel.onnx & start in bruiser mode
 * /bot stop                       — stop executor, release all keys
 */
public class BotCommand {

    private static final String DEFAULT_MODEL_PATH = "bot_model.onnx";

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> {
            dispatcher.register(
                ClientCommandManager.literal("bot")
                    .then(ClientCommandManager.literal("start")
                        .then(ClientCommandManager.argument("mode", StringArgumentType.word())
                            .suggests((ctx, builder) -> {
                                builder.suggest("zealots");
                                builder.suggest("bruisers");
                                return builder.buildFuture();
                            })
                            // /bot start <mode>  — uses default model
                            .executes(ctx -> startBot(ctx, DEFAULT_MODEL_PATH))
                            // /bot start <mode> <model>  — uses custom model
                            .then(ClientCommandManager.argument("model", StringArgumentType.word())
                                .executes(ctx -> {
                                    String modelName = StringArgumentType.getString(ctx, "model");
                                    String modelPath = modelName.endsWith(".onnx") ? modelName : modelName + ".onnx";
                                    return startBot(ctx, modelPath);
                                })
                            )
                        )
                    )
                    .then(ClientCommandManager.literal("stop")
                        .executes(ctx -> {
                            ActionExecutor.stop();
                            ctx.getSource().sendFeedback(Text.literal("§eBot stopped."));
                            return 1;
                        })
                    )
            );
        });
    }

    private static int startBot(com.mojang.brigadier.context.CommandContext<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> ctx, String modelPath) {
        String mode = StringArgumentType.getString(ctx, "mode");
        int modeInt;
        switch (mode.toLowerCase()) {
            case "zealots"  -> modeInt = 1;
            case "bruisers" -> modeInt = 2;
            default -> {
                ctx.getSource().sendFeedback(
                    Text.literal("§cUnknown mode: " + mode + ". Use zealots or bruisers."));
                return 0;
            }
        }

        if (!ActionExecutor.loadModel(modelPath)) {
            ctx.getSource().sendFeedback(
                Text.literal("§cFailed to load model: " + modelPath));
            return 0;
        }

        ActionExecutor.start(modeInt);
        ctx.getSource().sendFeedback(
            Text.literal("§aBot started (" + mode + ") model: " + modelPath));
        return 1;
    }
}
