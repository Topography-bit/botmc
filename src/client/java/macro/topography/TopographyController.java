package macro.topography;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.Map;

public final class TopographyController {

    private static boolean screenOpenScheduled;

    // Per-mode keybind state: modeId -> wasPressed
    private static final Map<Integer, Boolean> keyWasPressed = new HashMap<>();
    private static boolean rshiftWasPressed;
    private static boolean f7WasPressed;
    private static boolean f8WasPressed;
    private static boolean f9WasPressed;

    // Zone transition tracking
    private static final Map<Integer, Boolean> wasInZone = new HashMap<>();

    // Runtime stats
    private static long startTimeMs = 0;
    private static int killCount = 0;
    private static int deathCount = 0;

    private TopographyController() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(TopographyController::onClientTick);
    }

    public static void openScreen() {
        screenOpenScheduled = true;
    }

    // ── Toggle ────────────────────────────────────────────────────

    public static boolean toggleMode(int modeId) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.world == null) {
            notify("Join a world first.");
            return false;
        }

        Mode mode = ModeRegistry.getById(modeId);
        String name = mode != null ? mode.getName() : "Mode " + modeId;

        // Already running this mode → stop
        if (Autopilot.isEnabled() && Autopilot.getTargetMode() == modeId) {
            Autopilot.stop();
            notify(name + " stopped.");
            return true;
        }

        // Stop any running autopilot
        if (Autopilot.isEnabled()) {
            Autopilot.stop();
        }

        Autopilot.start(modeId);
        startTimeMs = System.currentTimeMillis();
        killCount = 0;
        deathCount = 0;
        notify(name + " started.");
        return true;
    }

    // Backward-compat convenience
    public static boolean toggleZealots() { return toggleMode(1); }
    public static boolean toggleBruisers() { return toggleMode(2); }

    // ── Status queries ────────────────────────────────────────────

    public static boolean isModeActive(int modeId) {
        return Autopilot.isEnabled() && Autopilot.getTargetMode() == modeId;
    }

    public static boolean isZealotsActive()  { return isModeActive(1); }
    public static boolean isBruisersActive() { return isModeActive(2); }
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

        // Don't process keybinds while any screen is open (chat, inventory, our menu, etc.)
        if (client.currentScreen != null) return;

        checkZoneTransitions(client);

        // F7 = camera debug HUD, Shift+F7 = CSV toggle, Ctrl+F7 = human CSV toggle
        long window = client.getWindow().getHandle();
        {
            boolean f7Down = org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_F7) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
            boolean shiftDown = org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
            boolean ctrlDown = org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
            if (f7Down && !f7WasPressed) {
                if (ctrlDown) {
                    CameraDebugHud.toggleHumanCsv();
                    notify("Human CSV " + (CameraDebugHud.isHumanCsvEnabled() ? "started" : "stopped"));
                } else if (shiftDown) {
                    CameraDebugHud.toggleCsv();
                    notify("Camera CSV " + (CameraDebugHud.isCsvEnabled() ? "started" : "stopped"));
                } else {
                    CameraDebugHud.toggleHud();
                }
            }
            f7WasPressed = f7Down;
        }

        // Shift+F8 = human CSV toggle
        {
            boolean f8Down = org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_F8) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
            boolean shiftDown = org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
            if (f8Down && !f8WasPressed && shiftDown) {
                CameraDebugHud.toggleHumanCsv();
                notify("Human CSV " + (CameraDebugHud.isHumanCsvEnabled() ? "started" : "stopped"));
            }
            f8WasPressed = f8Down;
        }

        // F9 = autopilot debug HUD, Shift+F9 = autopilot CSV toggle
        {
            boolean f9Down = org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_F9) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
            boolean shiftDown = org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
            if (f9Down && !f9WasPressed) {
                if (shiftDown) {
                    AutopilotDebugHud.toggleCsv();
                    notify("Autopilot CSV " + (AutopilotDebugHud.isCsvEnabled() ? "started" : "stopped"));
                } else {
                    AutopilotDebugHud.toggleHud();
                }
            }
            f9WasPressed = f9Down;
        }

        // RSHIFT opens menu
        {
            boolean rshiftDown = org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
            if (rshiftDown && !rshiftWasPressed && client.currentScreen == null) {
                openScreen();
            }
            rshiftWasPressed = rshiftDown;
        }

        // Keybind polling — iterate all registered modes
        for (Mode mode : ModeRegistry.getModes()) {
            int modeId = mode.getId();
            int keyCode = TopographyUiConfig.getModeKeyCode(modeId);
            boolean pressed = keyCode != org.lwjgl.glfw.GLFW.GLFW_KEY_UNKNOWN
                && org.lwjgl.glfw.GLFW.glfwGetKey(window, keyCode) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
            boolean was = keyWasPressed.getOrDefault(modeId, false);
            if (pressed && !was) toggleMode(modeId);
            keyWasPressed.put(modeId, pressed);
        }

        // Sync render toggles
        PathRenderer.enabled = TopographyUiConfig.isPathRenderEnabled();
    }

    private static void checkZoneTransitions(MinecraftClient client) {
        if (client.player == null || client.inGameHud == null) return;
        net.minecraft.util.math.Vec3d pos = client.player.getEntityPos();
        int x = (int) pos.x, y = (int) pos.y, z = (int) pos.z;

        for (Mode mode : ModeRegistry.getModes()) {
            int id = mode.getId();
            boolean inZone = Pathfinder.isInFarmZone(pos, id);
            boolean was = wasInZone.getOrDefault(id, false);

            if (inZone && !was)
                client.inGameHud.getChatHud().addMessage(
                    Text.literal("\u00a7a[IN] " + mode.getName() + " zone | " + x + " " + y + " " + z));
            else if (!inZone && was)
                client.inGameHud.getChatHud().addMessage(
                    Text.literal("\u00a7c[OUT] " + mode.getName() + " zone | " + x + " " + y + " " + z));

            wasInZone.put(id, inZone);
        }
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
