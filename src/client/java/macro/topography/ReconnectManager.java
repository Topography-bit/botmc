package macro.topography;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.util.Random;

/**
 * Auto-reconnects to the last server when kicked while a macro is running.
 *
 * Full flow:
 *  1. Kick detected → ReconnectScreen countdown (5-20s)
 *  2. Connect to server
 *  3. Detect limbo (Y > 100, void world) → /lobby → wait
 *  4. /play sb → wait for errors or success
 *  5. On skyblock → /warp to correct location (e.g. /warp end)
 *  6. Wait for warp → check location → start macro
 *
 * Gives up after MAX_ATTEMPTS failed connections or MAX_SKYBLOCK_ATTEMPTS failed /play sb.
 */
public final class ReconnectManager {

    private static final int RECONNECT_MIN_TICKS = 100;  // 5s
    private static final int RECONNECT_MAX_TICKS = 400;  // 20s
    private static final int MAX_ATTEMPTS = 5;
    private static final Random RNG = new Random();

    // Phase timings (min/max in ticks, 20 ticks = 1s)
    private static final int INITIAL_WAIT_MIN   = 40;   // 2s
    private static final int INITIAL_WAIT_MAX   = 80;   // 4s
    private static final int LOBBY_CMD_WAIT_MIN = 40;   // 2s
    private static final int LOBBY_CMD_WAIT_MAX = 80;   // 4s
    private static final int SB_CMD_WAIT_MIN    = 40;   // 2s
    private static final int SB_CMD_WAIT_MAX    = 80;   // 4s
    private static final int SKYBLOCK_TIMEOUT   = 200;  // 10s — no error = success
    private static final int SKYBLOCK_RETRY_MIN = 600;  // 30s
    private static final int SKYBLOCK_RETRY_MAX = 1200; // 60s
    private static final int MAX_SB_ATTEMPTS    = 5;
    private static final int WARP_WAIT_MIN      = 80;   // 4s
    private static final int WARP_WAIT_MAX      = 140;  // 7s
    private static final int WARP_RETRY_MIN     = 200;  // 10s
    private static final int WARP_RETRY_MAX     = 400;  // 20s
    private static final int MAX_WARP_ATTEMPTS  = 5;
    private static final int MACRO_START_MIN    = 60;   // 3s
    private static final int MACRO_START_MAX    = 140;  // 7s
    private static final int LOCATION_CHECK_INTERVAL = 40; // 2s between location checks

    enum Phase {
        IDLE,
        RECONNECT_COUNTDOWN,
        WAITING_FOR_JOIN,
        INITIAL_DELAY,       // just joined, waiting before doing anything
        DETECT_LIMBO,        // checking if in limbo
        LOBBY_SENT,          // /lobby sent, waiting
        SKYBLOCK_CMD_WAIT,   // waiting before /play sb
        SKYBLOCK_SENT,       // /play sb sent, listening for errors
        SKYBLOCK_RETRY,      // error received, waiting to retry
        WARP_SENT,           // /warp sent, waiting
        WARP_RETRY,          // warp failed, waiting to retry
        LOCATION_CHECK,      // checking if at correct location
        MACRO_START_WAIT     // at correct location, starting macro soon
    }

    // Persistent
    private static String lastServerAddress = null;
    private static String lastServerName = null;

    // State
    private static Phase phase = Phase.IDLE;
    private static int pendingModeId = -1;
    private static int countdown = -1;
    private static int totalDelayTicks = -1;
    private static int serverAttempts = 0;
    private static int sbAttempts = 0;
    private static int warpAttempts = 0;
    private static boolean screenReplaced = false;
    private static String kickReason = null;
    private static String statusText = "";
    private static boolean gotSbError = false;
    private static boolean gotWarpError = false;

    private ReconnectManager() {}

    private static int rng(int min, int max) {
        return min + RNG.nextInt(max - min + 1);
    }

    public static void register() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ServerInfo entry = client.getCurrentServerEntry();
            if (entry != null) {
                lastServerAddress = entry.address;
                lastServerName = entry.name;
            }

            if (phase == Phase.WAITING_FOR_JOIN && pendingModeId > 0) {
                serverAttempts = 0;
                sbAttempts = 0;
                warpAttempts = 0;
                setPhase(Phase.INITIAL_DELAY, rng(INITIAL_WAIT_MIN, INITIAL_WAIT_MAX), "Connected. Detecting environment...");
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (phase == Phase.WAITING_FOR_JOIN) {
                onServerConnectionFailed();
                return;
            }

            // Kicked during any active phase → restart
            if (phase != Phase.IDLE && phase != Phase.RECONNECT_COUNTDOWN) {
                serverAttempts++;
                if (serverAttempts >= MAX_ATTEMPTS) {
                    giveUp("Lost connection " + serverAttempts + " times");
                    return;
                }
                screenReplaced = false;
                kickReason = null;
                scheduleReconnect();
                return;
            }

            if (Autopilot.isEnabled()) {
                int mode = Autopilot.getTargetMode();
                Autopilot.stop();

                if (lastServerAddress != null && mode > 0) {
                    pendingModeId = mode;
                    serverAttempts = 0;
                    sbAttempts = 0;
                    warpAttempts = 0;
                    screenReplaced = false;
                    kickReason = null;
                    scheduleReconnect();
                }
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(ReconnectManager::onTick);
    }

    /** Called from ClientPlayNetworkHandlerMixin on incoming game messages. */
    public static void onChatMessage(String message) {
        String lower = message.toLowerCase();

        if (phase == Phase.SKYBLOCK_SENT) {
            if (isSkyblockError(lower)) {
                gotSbError = true;
            }
        }

        if (phase == Phase.WARP_SENT) {
            if (isWarpError(lower)) {
                gotWarpError = true;
            }
        }

        // Detect limbo hints in chat
        if (phase == Phase.INITIAL_DELAY || phase == Phase.DETECT_LIMBO) {
            if (lower.contains("limbo") || lower.contains("you were spawned in limbo")
                    || lower.contains("afk")) {
                // Immediately go to lobby
                sendCommand("/lobby");
                setPhase(Phase.LOBBY_SENT, rng(LOBBY_CMD_WAIT_MIN, LOBBY_CMD_WAIT_MAX), "In limbo. Sending /lobby...");
            }
        }
    }

    // ── Error detection ───────────────────────────────────────────

    private static boolean isSkyblockError(String lower) {
        return lower.contains("please wait") || lower.contains("couldn't warp")
                || lower.contains("cannot join") || lower.contains("try again")
                || lower.contains("already connected") || lower.contains("you are already")
                || lower.contains("failed to join") || lower.contains("cannot connect")
                || lower.contains("you cannot") || lower.contains("kicked while joining")
                || lower.contains("game is full");
    }

    private static boolean isWarpError(String lower) {
        return lower.contains("couldn't warp") || lower.contains("unknown warp")
                || lower.contains("please wait") || lower.contains("try again")
                || lower.contains("you cannot") || lower.contains("cannot warp");
    }

    // ── Warp command for mode ─────────────────────────────────────

    private static String getWarpCommand(int modeId) {
        // Both zealots (1) and bruisers (2) are in The End
        if (modeId == 1 || modeId == 2) return "warp end";
        return null;
    }

    private static boolean isInCorrectLocation(MinecraftClient client, int modeId) {
        if (client.player == null) return false;
        Vec3d pos = client.player.getEntityPos();
        // For zealots/bruisers: check if in The End dimension or near the farm zones
        // Simple heuristic: if Y is reasonable and coordinates are in expected range
        if (modeId == 1 || modeId == 2) {
            // The End island coords — broad check, Pathfinder handles the rest
            return pos.x < -400 && pos.x > -800 && pos.z < -100 && pos.z > -400;
        }
        return true; // unknown mode, assume correct
    }

    private static boolean isInLimbo(MinecraftClient client) {
        if (client.player == null) return false;
        Vec3d pos = client.player.getEntityPos();
        // Limbo: not in expected game areas — check if clearly outside The End zone
        // Also trust chat-based detection (onChatMessage handles "limbo"/"afk" keywords)
        // Simple heuristic: Y > 100 and not in known farm zones
        if (pos.y > 100) return true;
        return false;
    }

    // ── Phase management ──────────────────────────────────────────

    private static void scheduleReconnect() {
        int delayTicks = RECONNECT_MIN_TICKS
                + RNG.nextInt(RECONNECT_MAX_TICKS - RECONNECT_MIN_TICKS + 1);
        setPhase(Phase.RECONNECT_COUNTDOWN, delayTicks, "Reconnecting...");
        totalDelayTicks = delayTicks;
    }

    private static void setPhase(Phase p, int ticks, String status) {
        phase = p;
        countdown = ticks;
        totalDelayTicks = ticks;
        statusText = status;
        gotSbError = false;
        gotWarpError = false;
    }

    private static void onServerConnectionFailed() {
        serverAttempts++;
        if (serverAttempts >= MAX_ATTEMPTS) {
            giveUp("Failed to connect after " + serverAttempts + " attempts");
            return;
        }
        screenReplaced = false;
        scheduleReconnect();
    }

    private static void giveUp(String reason) {
        MinecraftClient client = MinecraftClient.getInstance();
        int att = serverAttempts + sbAttempts + warpAttempts;
        if (client != null) {
            client.execute(() -> client.setScreen(
                    new ReconnectScreen(lastServerAddress, reason,
                            0, 0, att, MAX_ATTEMPTS, true)));
        }
        cancel();
    }

    // ── Tick ───────────────────────────────────────────────────────

    private static void onTick(MinecraftClient client) {
        if (phase == Phase.IDLE) return;

        // ── Replace DisconnectedScreen ───────────────────────────
        if (phase == Phase.RECONNECT_COUNTDOWN && !screenReplaced
                && client.currentScreen instanceof DisconnectedScreen) {
            screenReplaced = true;
            if (kickReason == null) {
                kickReason = client.currentScreen.getTitle().getString();
            }
            client.setScreen(new ReconnectScreen(
                    lastServerAddress, kickReason,
                    countdown, totalDelayTicks,
                    serverAttempts + 1, MAX_ATTEMPTS, false));
        }

        // ── Update ReconnectScreen ───────────────────────────────
        if (client.currentScreen instanceof ReconnectScreen rs) {
            rs.updateCountdown(countdown, totalDelayTicks);
            rs.updateStatus(statusText);
        }

        // ── Process errors that came in via chat ─────────────────
        if (phase == Phase.SKYBLOCK_SENT && gotSbError) {
            sbAttempts++;
            if (sbAttempts >= MAX_SB_ATTEMPTS) {
                giveUp("SkyBlock join failed " + sbAttempts + " times");
                return;
            }
            int retryTicks = SKYBLOCK_RETRY_MIN
                    + RNG.nextInt(SKYBLOCK_RETRY_MAX - SKYBLOCK_RETRY_MIN + 1);
            setPhase(Phase.SKYBLOCK_RETRY, retryTicks,
                    "SkyBlock error. Retry in " + (retryTicks / 20) + "s ("
                    + sbAttempts + "/" + MAX_SB_ATTEMPTS + ")");
        }

        if (phase == Phase.WARP_SENT && gotWarpError) {
            warpAttempts++;
            if (warpAttempts >= MAX_WARP_ATTEMPTS) {
                giveUp("Warp failed " + warpAttempts + " times");
                return;
            }
            int retryTicks = WARP_RETRY_MIN
                    + RNG.nextInt(WARP_RETRY_MAX - WARP_RETRY_MIN + 1);
            setPhase(Phase.WARP_RETRY, retryTicks,
                    "Warp error. Retry in " + (retryTicks / 20) + "s ("
                    + warpAttempts + "/" + MAX_WARP_ATTEMPTS + ")");
        }

        // ── Countdown tick ───────────────────────────────────────
        if (countdown > 0) {
            countdown--;
            if (countdown == 0) {
                onCountdownFinished(client);
            }
        }
    }

    private static void onCountdownFinished(MinecraftClient client) {
        switch (phase) {
            case RECONNECT_COUNTDOWN -> {
                serverAttempts++;
                attemptReconnect(client);
            }
            case INITIAL_DELAY -> {
                // Check if in limbo
                setPhase(Phase.DETECT_LIMBO, 20, "Checking environment...");
            }
            case DETECT_LIMBO -> {
                if (isInLimbo(client)) {
                    sendCommand("/lobby");
                    setPhase(Phase.LOBBY_SENT, rng(LOBBY_CMD_WAIT_MIN, LOBBY_CMD_WAIT_MAX), "In limbo. Sending /lobby...");
                } else {
                    // Not in limbo — check if already on skyblock
                    if (isInCorrectLocation(client, pendingModeId)) {
                        setPhase(Phase.MACRO_START_WAIT, rng(MACRO_START_MIN, MACRO_START_MAX),
                                "Already at location. Starting macro...");
                    } else {
                        // Might be in lobby or hub, try /play sb
                        setPhase(Phase.SKYBLOCK_CMD_WAIT, rng(SB_CMD_WAIT_MIN, SB_CMD_WAIT_MAX),
                                "Joining SkyBlock...");
                    }
                }
            }
            case LOBBY_SENT -> {
                // After /lobby, proceed to /play sb
                setPhase(Phase.SKYBLOCK_CMD_WAIT, rng(SB_CMD_WAIT_MIN, SB_CMD_WAIT_MAX),
                        "In lobby. Joining SkyBlock...");
            }
            case SKYBLOCK_CMD_WAIT -> {
                sendCommand("play sb");
                setPhase(Phase.SKYBLOCK_SENT, SKYBLOCK_TIMEOUT,
                        "Joining SkyBlock... (" + (sbAttempts + 1) + "/" + MAX_SB_ATTEMPTS + ")");
            }
            case SKYBLOCK_SENT -> {
                // Timeout with no error → assume on skyblock
                // Now check location and warp if needed
                startWarpPhase(client);
            }
            case SKYBLOCK_RETRY -> {
                sendCommand("play sb");
                setPhase(Phase.SKYBLOCK_SENT, SKYBLOCK_TIMEOUT,
                        "Joining SkyBlock... (" + (sbAttempts + 1) + "/" + MAX_SB_ATTEMPTS + ")");
            }
            case WARP_SENT -> {
                // Timeout with no error → assume warp succeeded
                setPhase(Phase.LOCATION_CHECK, LOCATION_CHECK_INTERVAL,
                        "Verifying location...");
            }
            case WARP_RETRY -> {
                String warpCmd = getWarpCommand(pendingModeId);
                if (warpCmd != null) {
                    sendCommand(warpCmd);
                    setPhase(Phase.WARP_SENT, rng(WARP_WAIT_MIN, WARP_WAIT_MAX),
                            "Warping... (" + (warpAttempts + 1) + "/" + MAX_WARP_ATTEMPTS + ")");
                } else {
                    setPhase(Phase.MACRO_START_WAIT, rng(MACRO_START_MIN, MACRO_START_MAX),
                            "Starting macro...");
                }
            }
            case LOCATION_CHECK -> {
                if (isInCorrectLocation(client, pendingModeId)) {
                    setPhase(Phase.MACRO_START_WAIT, rng(MACRO_START_MIN, MACRO_START_MAX),
                            "At location. Starting macro...");
                } else {
                    // Not there yet, retry warp
                    warpAttempts++;
                    if (warpAttempts >= MAX_WARP_ATTEMPTS) {
                        giveUp("Could not reach target location");
                        return;
                    }
                    String warpCmd = getWarpCommand(pendingModeId);
                    if (warpCmd != null) {
                        sendCommand(warpCmd);
                        setPhase(Phase.WARP_SENT, rng(WARP_WAIT_MIN, WARP_WAIT_MAX),
                                "Re-warping... (" + (warpAttempts + 1) + "/" + MAX_WARP_ATTEMPTS + ")");
                    }
                }
            }
            case MACRO_START_WAIT -> {
                if (pendingModeId > 0 && client.player != null && client.world != null) {
                    TopographyController.toggleMode(pendingModeId);
                }
                cancel();
            }
            default -> {}
        }
    }

    private static void startWarpPhase(MinecraftClient client) {
        String warpCmd = getWarpCommand(pendingModeId);
        if (warpCmd == null || isInCorrectLocation(client, pendingModeId)) {
            setPhase(Phase.MACRO_START_WAIT, rng(MACRO_START_MIN, MACRO_START_MAX),
                    "On SkyBlock. Starting macro...");
            return;
        }
        sendCommand(warpCmd);
        setPhase(Phase.WARP_SENT, rng(WARP_WAIT_MIN, WARP_WAIT_MAX),
                "Warping to location...");
    }

    private static void sendCommand(String command) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player != null && client.player.networkHandler != null) {
            // Remove leading slash if present — sendChatCommand doesn't want it
            String cmd = command.startsWith("/") ? command.substring(1) : command;
            client.player.networkHandler.sendChatCommand(cmd);
        }
    }

    private static void attemptReconnect(MinecraftClient client) {
        if (lastServerAddress == null) {
            cancel();
            return;
        }

        ServerAddress address = ServerAddress.parse(lastServerAddress);
        ServerInfo info = new ServerInfo(
                lastServerName != null ? lastServerName : "Server",
                lastServerAddress,
                ServerInfo.ServerType.OTHER);

        phase = Phase.WAITING_FOR_JOIN;
        statusText = "Connecting to " + lastServerAddress + "...";

        client.execute(() ->
            ConnectScreen.connect(
                    new MultiplayerScreen(new TitleScreen()),
                    client,
                    address,
                    info,
                    false,
                    null)
        );
    }

    public static void cancel() {
        phase = Phase.IDLE;
        countdown = -1;
        totalDelayTicks = -1;
        pendingModeId = -1;
        serverAttempts = 0;
        sbAttempts = 0;
        warpAttempts = 0;
        screenReplaced = false;
        kickReason = null;
        statusText = "";
        gotSbError = false;
        gotWarpError = false;
    }

    public static boolean isActive() {
        return phase != Phase.IDLE;
    }

    public static String getStatusText() {
        return statusText;
    }

    /**
     * Called by AutoJoinManager after it successfully connects to the server.
     * Starts the post-join flow: limbo check → lobby → skyblock → warp → macro.
     */
    public static void startPostJoinFlow(int modeId) {
        startPostJoinFlow(modeId, false);
    }

    /**
     * Called by Autopilot when limbo is detected during macro execution.
     * Skips limbo detection and goes straight to /lobby.
     */
    public static void startPostJoinFlow(int modeId, boolean alreadyInLimbo) {
        pendingModeId = modeId;
        serverAttempts = 0;
        sbAttempts = 0;
        warpAttempts = 0;
        screenReplaced = false;
        kickReason = null;
        if (alreadyInLimbo) {
            sendCommand("lobby");
            setPhase(Phase.LOBBY_SENT, rng(LOBBY_CMD_WAIT_MIN, LOBBY_CMD_WAIT_MAX), "In limbo. Sending /lobby...");
        } else {
            setPhase(Phase.INITIAL_DELAY, rng(INITIAL_WAIT_MIN, INITIAL_WAIT_MAX), "Connected. Detecting environment...");
        }
    }
}
