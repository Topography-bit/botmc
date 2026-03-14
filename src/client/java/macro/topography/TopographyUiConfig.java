package macro.topography;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class TopographyUiConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_FILE = FabricLoader.getInstance()
        .getConfigDir()
        .resolve("topography_ui.json");

    private static Data data = createDefaultData();

    private TopographyUiConfig() {}

    public static void load() {
        if (!CONFIG_FILE.toFile().exists()) {
            save();
            return;
        }
        try (Reader reader = new FileReader(CONFIG_FILE.toFile())) {
            Data loaded = GSON.fromJson(reader, Data.class);
            data = loaded != null ? loaded : createDefaultData();
        } catch (IOException e) {
            System.err.println("[TopographyUI] Failed to load config: " + e.getMessage());
            data = createDefaultData();
        }
        ensureDefaults();
        save();
    }

    public static void save() {
        ensureDefaults();
        try (Writer writer = new FileWriter(CONFIG_FILE.toFile())) {
            GSON.toJson(data, writer);
        } catch (IOException e) {
            System.err.println("[TopographyUI] Failed to save config: " + e.getMessage());
        }
    }

    // ── Keybinds (by mode id) ──────────────────────────────────────

    public static int getModeKeyCode(int modeId) {
        return data.modeKeybinds.getOrDefault(modeId, GLFW.GLFW_KEY_UNKNOWN);
    }

    public static void setModeKeyCode(int modeId, int code) {
        data.modeKeybinds.put(modeId, code);
        save();
    }

    // Backward-compat convenience
    public static int getZealotsKeyCode()  { return getModeKeyCode(1); }
    public static int getBruisersKeyCode() { return getModeKeyCode(2); }
    public static void setZealotsKeyCode(int code) { setModeKeyCode(1, code); }
    public static void setBruisersKeyCode(int code) { setModeKeyCode(2, code); }

    public static String getKeyLabel(int keyCode) {
        if (keyCode == GLFW.GLFW_KEY_UNKNOWN) return "None";
        return InputUtil.fromKeyCode(new KeyInput(keyCode, 0, 0)).getLocalizedText().getString();
    }

    // ── Render toggles ───────────────────────────────────────────

    public static boolean isPathRenderEnabled() { return data.pathRender; }
    public static void setPathRenderEnabled(boolean v) { data.pathRender = v; save(); }

    public static boolean isMobEspEnabled() { return data.mobEsp; }
    public static void setMobEspEnabled(boolean v) { data.mobEsp = v; save(); }

    // ── Internals ────────────────────────────────────────────────

    private static void ensureDefaults() {
        if (data == null) data = createDefaultData();
        if (data.modeKeybinds == null) data.modeKeybinds = new HashMap<>();
    }

    private static Data createDefaultData() {
        Data d = new Data();
        d.modeKeybinds = new HashMap<>();
        d.pathRender = true;
        d.mobEsp = true;
        return d;
    }

    private static final class Data {
        Map<Integer, Integer> modeKeybinds = new HashMap<>();
        boolean pathRender = true;
        boolean mobEsp = true;

        // Legacy fields — Gson will populate these if present in old config
        transient int zealotsKeyCode = GLFW.GLFW_KEY_UNKNOWN;
        transient int bruisersKeyCode = GLFW.GLFW_KEY_UNKNOWN;
    }
}
