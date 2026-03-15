package macro.topography;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class TopographyCommand {

    private TopographyCommand() {
    }

    private static void msg(String text) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.sendMessage(Text.literal("§a[Topography] §r" + text), false);
        }
    }

    public static void register() {
        ClientSendMessageEvents.ALLOW_COMMAND.register(command -> {
            String normalized = command == null ? "" : command.trim().toLowerCase();
            if (normalized.equals("topography") || normalized.equals("topography open")) {
                TopographyController.openScreen();
                return false;
            }

            return true;
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(literal("topography")
                .executes(context -> {
                    TopographyController.openScreen();
                    return 1;
                })
                .then(literal("open")
                    .executes(context -> {
                        TopographyController.openScreen();
                        return 1;
                    }))
                .then(literal("fakekick")
                    .executes(context -> {
                        MinecraftClient client = MinecraftClient.getInstance();
                        if (client != null && client.player != null) {
                            client.player.networkHandler.getConnection()
                                    .disconnect(Text.literal("Fake kick for reconnect test"));
                        }
                        return 1;
                    }))
                .then(literal("path")
                    .then(literal("zealots")
                        .executes(context -> {
                            if (Autopilot.isEnabled()) {
                                Autopilot.stop();
                                msg("Path-only stopped.");
                            } else {
                                Autopilot.startPathOnly(1);
                                msg("Path-only started (zealots).");
                            }
                            return 1;
                        }))
                    .then(literal("bruisers")
                        .executes(context -> {
                            if (Autopilot.isEnabled()) {
                                Autopilot.stop();
                                msg("Path-only stopped.");
                            } else {
                                Autopilot.startPathOnly(2);
                                msg("Path-only started (bruisers).");
                            }
                            return 1;
                        }))
                    .then(literal("stop")
                        .executes(context -> {
                            Autopilot.stop();
                            msg("Path-only stopped.");
                            return 1;
                        }))))
        );
    }
}
