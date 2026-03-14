package macro.topography;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * /nav zealots  — запустить Pathfinder в режиме Zealots (без автопилота)
 * /nav bruisers — запустить Pathfinder в режиме Bruisers (без автопилота)
 * /nav stop     — остановить
 *
 * Отображает путь и хайлайтит мобов через PathRenderer,
 * но не управляет движением/атакой игрока.
 */
public class NavCommand {

    private static boolean active = false;
    private static int mode = 0; // 1=zealots, 2=bruisers

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(literal("nav")
                .then(literal("zealots").executes(ctx -> {
                    start(1);
                    ctx.getSource().sendFeedback(Text.literal("§a[Nav] Zealots pathfinding started. /nav stop to stop."));
                    return 1;
                }))
                .then(literal("bruisers").executes(ctx -> {
                    start(2);
                    ctx.getSource().sendFeedback(Text.literal("§a[Nav] Bruisers pathfinding started. /nav stop to stop."));
                    return 1;
                }))
                .then(literal("stop").executes(ctx -> {
                    stop();
                    ctx.getSource().sendFeedback(Text.literal("§c[Nav] Stopped."));
                    return 1;
                }))
            )
        );

        ClientTickEvents.END_CLIENT_TICK.register(NavCommand::onTick);
    }

    private static void start(int targetMode) {
        if (active) stop();
        mode = targetMode;
        Pathfinder.reset();
        active = true;
    }

    private static void stop() {
        active = false;
        Pathfinder.stop();
        mode = 0;
    }

    private static void onTick(MinecraftClient client) {
        if (!active) return;
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) return;

        Pathfinder.update(
            client, player,
            mode, mode,
            1.0f,  // distToWall
            1.0f,  // verticalClearance
            0.0f,  // stressLevel
            0.0f,  // posAnomaly
            0.0f   // velAnomaly
        );
    }

    public static boolean isActive() { return active; }
    public static int getMode()      { return mode; }
}
