package macro.topography;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

public final class TopographyController {

    private static boolean screenOpenScheduled;
    private static boolean zealotKeyWasPressed;
    private static boolean bruiserKeyWasPressed;

    // Zone transition tracking
    private static boolean wasInZealotZone = false;
    private static boolean wasInBruiserZone = false;

    // Runtime stats
    private static long startTimeMs = 0;
    private static int killCount = 0;
    private static int deathCount = 0;
    private static int activeMode = 0; // 0=none, 1=zealots, 2=bruisers

    private TopographyController() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(TopographyController::onClientTick);
    }

    public static void openScreen() {
        screenOpenScheduled = true;
    }

    // ── Toggle ────────────────────────────────────────────────────

    public static boolean toggleZealots() { return toggleAutopilot(1); }
    public static boolean toggleBruisers() { return toggleAutopilot(2); }

    private static boolean toggleAutopilot(int mode) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.world == null) {
            notify("Join a world first.");
            return false;
        }

        // Already running this mode → stop
        if (Autopilot.isEnabled() && Autopilot.getTargetMode() == mode) {
            Autopilot.stop();
            activeMode = 0;
            notify((mode == 1 ? "Zealots" : "Bruisers") + " stopped.");
            return true;
        }

        // Stop any running autopilot
        if (Autopilot.isEnabled()) {
            Autopilot.stop();
        }

        Autopilot.start(mode);
        activeMode = mode;
        startTimeMs = System.currentTimeMillis();
        killCount = 0;
        deathCount = 0;
        notify((mode == 1 ? "Zealots" : "Bruisers") + " started.");
        return true;
    }

    // ── Status queries ────────────────────────────────────────────

    public static boolean isZealotsActive()  { return Autopilot.isEnabled() && Autopilot.getTargetMode() == 1; }
    public static boolean isBruisersActive() { return Autopilot.isEnabled() && Autopilot.getTargetMode() == 2; }
    public static boolean isAnyActive()      { return Autopilot.isEnabled(); }

    public static String getUptime() {
        if (!Autopilot.isEnabled() || startTimeMs == 0) return "--:--";
        long elapsed = System.currentTimeMillis() - startTimeMs;
        long totalSec = elapsed / 1000;
        long min = totalSec / 60;
        long sec = totalSec % 60;
        return String.format("%02d:%02d", min, sec);
    }

    public static int getKillCount() { return killCount; }
    public static int getDeathCount() { return deathCount; }
    public static void incrementKills() { killCount++; }
    public static void incrementDeaths() { deathCount++; }

    // ── Tick ──────────────────────────────────────────────────────

    private static void onClientTick(MinecraftClient client) {
        if (client == null || client.getWindow() == null) return;

        if (screenOpenScheduled) {
            screenOpenScheduled = false;
            Screen parent = client.currentScreen;
            client.setScreen(new TopographyScreen(parent));
            return;
        }

        // Don't process keybinds while in our screen (capturing binds)
        if (client.currentScreen instanceof TopographyScreen) return;

        checkZoneTransitions(client);

        // Keybind polling
        long window = client.getWindow().getHandle();

        int zealotKey = TopographyUiConfig.getZealotsKeyCode();
        boolean zealotPressed = zealotKey != org.lwjgl.glfw.GLFW.GLFW_KEY_UNKNOWN
            && org.lwjgl.glfw.GLFW.glfwGetKey(window, zealotKey) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        if (zealotPressed && !zealotKeyWasPressed) toggleZealots();
        zealotKeyWasPressed = zealotPressed;

        int bruiserKey = TopographyUiConfig.getBruisersKeyCode();
        boolean bruiserPressed = bruiserKey != org.lwjgl.glfw.GLFW.GLFW_KEY_UNKNOWN
            && org.lwjgl.glfw.GLFW.glfwGetKey(window, bruiserKey) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        if (bruiserPressed && !bruiserKeyWasPressed) toggleBruisers();
        bruiserKeyWasPressed = bruiserPressed;

        // Sync render toggles
        PathRenderer.enabled = TopographyUiConfig.isPathRenderEnabled();
    }

    private static void checkZoneTransitions(MinecraftClient client) {
        if (client.player == null || client.inGameHud == null) return;
        net.minecraft.util.math.Vec3d pos = client.player.getEntityPos();
        int x = (int) pos.x, y = (int) pos.y, z = (int) pos.z;

        boolean inZealot  = Pathfinder.isInFarmZone(pos, 1);
        boolean inBruiser = Pathfinder.isInFarmZone(pos, 2);

        if (inZealot && !wasInZealotZone)
            client.inGameHud.getChatHud().addMessage(Text.literal("\u00a7a[IN] Zealot zone | " + x + " " + y + " " + z));
        else if (!inZealot && wasInZealotZone)
            client.inGameHud.getChatHud().addMessage(Text.literal("\u00a7c[OUT] Zealot zone | " + x + " " + y + " " + z));
        wasInZealotZone = inZealot;

        if (inBruiser && !wasInBruiserZone)
            client.inGameHud.getChatHud().addMessage(Text.literal("\u00a7a[IN] Bruiser zone | " + x + " " + y + " " + z));
        else if (!inBruiser && wasInBruiserZone)
            client.inGameHud.getChatHud().addMessage(Text.literal("\u00a7c[OUT] Bruiser zone | " + x + " " + y + " " + z));
        wasInBruiserZone = inBruiser;
    }

    private static void notify(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;
        client.execute(() -> {
            if (client.inGameHud != null)
                client.inGameHud.getChatHud().addMessage(Text.literal("[Topography] " + message));
        });
    }
}
