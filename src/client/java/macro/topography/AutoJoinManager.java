package macro.topography;

import macro.topography.mixin.client.MultiplayerScreenAccessor;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.ServerList;

/**
 * Auto-joins the configured server on game startup.
 *
 * Flow:
 *  1. Wait for TitleScreen → open MultiplayerScreen
 *  2. Wait for target server ping → status SUCCESSFUL
 *  3. If UNREACHABLE → reopen MultiplayerScreen to refresh (no limit)
 *  4. On SUCCESSFUL → connect via the screen's own connect()
 *  5. After join → hand off to ReconnectManager for skyblock/warp/macro
 */
public final class AutoJoinManager {

    private static final int TITLE_SCREEN_DELAY = 60;   // 3s after title screen
    private static final int MP_SETTLE_DELAY = 80;      // 4s let MP screen init + start pinging
    private static final int PING_CHECK_INTERVAL = 20;  // 1s between status checks
    private static final int PING_TIMEOUT = 600;        // 30s max wait for ping result
    private static final int REFRESH_COOLDOWN = 200;    // 10s before refresh on error
    private static final int CONNECT_TIMEOUT = 600;     // 30s timeout for connecting

    private enum Phase {
        WAITING_FOR_TITLE,
        TITLE_COUNTDOWN,
        OPENING_MULTIPLAYER,
        REOPEN_COOLDOWN,       // waiting before reopening MP screen
        MP_SETTLE,             // let MultiplayerScreen init + start pinging
        WAITING_FOR_PING,      // checking server status every second
        CONNECTING,
        DONE
    }

    private static Phase phase = Phase.WAITING_FOR_TITLE;
    private static int countdown = -1;
    private static int pingTimer = 0;
    private static boolean titleSeen = false;

    private AutoJoinManager() {}

    public static void register() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (phase == Phase.CONNECTING) {
                int modeId = TopographyUiConfig.getAutoJoinModeId();
                phase = Phase.DONE;
                ReconnectManager.startPostJoinFlow(modeId);
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (phase == Phase.CONNECTING) {
                // Connection failed — reopen multiplayer screen
                phase = Phase.REOPEN_COOLDOWN;
                countdown = REFRESH_COOLDOWN;
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(AutoJoinManager::onTick);
    }

    private static void onTick(MinecraftClient client) {
        if (phase == Phase.DONE) return;
        if (!TopographyUiConfig.isAutoJoinEnabled()) {
            phase = Phase.DONE;
            return;
        }

        switch (phase) {
            case WAITING_FOR_TITLE -> {
                if (client.currentScreen instanceof TitleScreen && !titleSeen) {
                    titleSeen = true;
                    phase = Phase.TITLE_COUNTDOWN;
                    countdown = TITLE_SCREEN_DELAY;
                }
            }
            case TITLE_COUNTDOWN -> {
                if (--countdown <= 0) {
                    phase = Phase.OPENING_MULTIPLAYER;
                }
            }
            case OPENING_MULTIPLAYER -> {
                client.execute(() ->
                    client.setScreen(new MultiplayerScreen(new TitleScreen()))
                );
                phase = Phase.MP_SETTLE;
                countdown = MP_SETTLE_DELAY;
            }
            case REOPEN_COOLDOWN -> {
                if (--countdown <= 0) {
                    phase = Phase.OPENING_MULTIPLAYER;
                }
            }
            case MP_SETTLE -> {
                if (--countdown <= 0) {
                    phase = Phase.WAITING_FOR_PING;
                    countdown = PING_CHECK_INTERVAL;
                    pingTimer = PING_TIMEOUT;
                }
            }
            case WAITING_FOR_PING -> {
                pingTimer--;
                if (pingTimer <= 0) {
                    // Ping timeout — reopen MP screen to retry
                    System.out.println("[AutoJoin] Ping timeout, reopening multiplayer screen...");
                    cachedTarget = null;
                    phase = Phase.REOPEN_COOLDOWN;
                    countdown = REFRESH_COOLDOWN;
                } else if (--countdown <= 0) {
                    countdown = PING_CHECK_INTERVAL;
                    checkServerStatus(client);
                }
            }
            case CONNECTING -> {
                if (--countdown <= 0) {
                    System.out.println("[AutoJoin] Connect timeout, retrying...");
                    phase = Phase.REOPEN_COOLDOWN;
                    countdown = REFRESH_COOLDOWN;
                }
            }
            default -> {}
        }
    }

    private static ServerInfo cachedTarget = null;

    private static void checkServerStatus(MinecraftClient client) {
        String targetAddr = TopographyUiConfig.getAutoJoinServer();
        if (targetAddr == null || targetAddr.isEmpty()) {
            phase = Phase.DONE;
            return;
        }

        // Try to find server from screen's list (only if screen is still open)
        if (cachedTarget == null && client.currentScreen instanceof MultiplayerScreen mpScreen) {
            MultiplayerScreenAccessor accessor = (MultiplayerScreenAccessor) mpScreen;
            ServerList serverList = accessor.getServerList();
            if (serverList != null) {
                String targetDomain = extractBaseDomain(targetAddr.toLowerCase());
                for (int i = 0; i < serverList.size(); i++) {
                    ServerInfo info = serverList.get(i);
                    if (info.address == null) continue;
                    String addrDomain = extractBaseDomain(info.address.toLowerCase());
                    // Match on base domain (e.g. "hypixel.net" matches mc/c/stuck.hypixel.net)
                    if (addrDomain.equals(targetDomain)) {
                        cachedTarget = info;
                        System.out.println("[AutoJoin] Found server: '" + info.address + "' (domain: " + addrDomain + ")");
                        break;
                    }
                }
                if (cachedTarget == null) {
                    System.out.println("[AutoJoin] Server not found in list (" + serverList.size() + " entries). Target domain: " + targetDomain);
                    for (int i = 0; i < serverList.size(); i++) {
                        ServerInfo info = serverList.get(i);
                        System.out.println("[AutoJoin]   [" + i + "] '" + info.address + "'");
                    }
                }
            }
        }

        if (cachedTarget == null) {
            // Server not in list — connect directly
            System.out.println("[AutoJoin] Server not in list, connecting directly to: " + targetAddr);
            connectDirectly(client, targetAddr);
            return;
        }

        ServerInfo.Status status = cachedTarget.getStatus();
        System.out.println("[AutoJoin] Server '" + cachedTarget.address + "' status: " + status);

        if (status == ServerInfo.Status.SUCCESSFUL) {
            // Server is online — connect!
            phase = Phase.CONNECTING;
            countdown = CONNECT_TIMEOUT;
            ServerAddress address = ServerAddress.parse(cachedTarget.address);
            final ServerInfo finalTarget = cachedTarget;
            cachedTarget = null;
            client.execute(() ->
                ConnectScreen.connect(
                        new MultiplayerScreen(new TitleScreen()),
                        client,
                        address,
                        finalTarget,
                        false,
                        null)
            );
        } else if (status == ServerInfo.Status.UNREACHABLE
                || status == ServerInfo.Status.INCOMPATIBLE) {
            // Server down — reopen MP screen to refresh
            cachedTarget = null;
            phase = Phase.REOPEN_COOLDOWN;
            countdown = REFRESH_COOLDOWN;
        }
        // INITIAL or PINGING → keep waiting
    }

    /** Extracts base domain: "mc.hypixel.net" → "hypixel.net", "hypixel.net" → "hypixel.net" */
    private static String extractBaseDomain(String addr) {
        // Strip port if present
        int colonIdx = addr.indexOf(':');
        if (colonIdx >= 0) addr = addr.substring(0, colonIdx);
        String[] parts = addr.split("\\.");
        if (parts.length >= 2) {
            return parts[parts.length - 2] + "." + parts[parts.length - 1];
        }
        return addr;
    }

    private static void connectDirectly(MinecraftClient client, String addr) {
        phase = Phase.CONNECTING;
        countdown = CONNECT_TIMEOUT;
        ServerAddress address = ServerAddress.parse(addr);
        ServerInfo info = new ServerInfo("AutoJoin", addr, ServerInfo.ServerType.OTHER);
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
}
