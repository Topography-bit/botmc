package macro.topography;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class TopographyCommand {

    private TopographyCommand() {
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
                    })))
        );
    }
}
