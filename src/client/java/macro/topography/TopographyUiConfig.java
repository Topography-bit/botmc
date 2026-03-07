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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TopographyUiConfig {

    public static final List<String> MODULE_PROFILES = List.of("Balanced", "Aggressive", "Steady");
    public static final List<String> ACCENT_PRESETS = List.of("Violet Bloom", "Flat Glass");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_FILE = FabricLoader.getInstance()
        .getConfigDir()
        .resolve("topography_ui.json");

    private static Data data = createDefaultData();

    private TopographyUiConfig() {
    }

    public static void load() {
        if (!CONFIG_FILE.toFile().exists()) {
            ensureDefaults();
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

    public static UiSettings ui() {
        ensureDefaults();
        return data.ui;
    }

    public static ModuleSettings getSettings(TopographyModuleDefinition module) {
        ensureDefaults();
        return data.modules.get(module.id());
    }

    public static int getKeyCode(TopographyModuleDefinition module) {
        return getSettings(module).keyCode;
    }

    public static void setKeyCode(TopographyModuleDefinition module, int keyCode) {
        getSettings(module).keyCode = keyCode;
        save();
    }

    public static boolean isBindEnabled(TopographyModuleDefinition module) {
        return getSettings(module).bindEnabled;
    }

    public static void setBindEnabled(TopographyModuleDefinition module, boolean enabled) {
        getSettings(module).bindEnabled = enabled;
        save();
    }

    public static float getResponseCurve(TopographyModuleDefinition module) {
        return getSettings(module).responseCurve;
    }

    public static void setResponseCurve(TopographyModuleDefinition module, float value) {
        getSettings(module).responseCurve = clamp(value, 0.15f, 1.0f);
        save();
    }

    public static String getProfile(TopographyModuleDefinition module) {
        ModuleSettings settings = getSettings(module);
        if (!MODULE_PROFILES.contains(settings.profile)) {
            settings.profile = MODULE_PROFILES.getFirst();
        }
        return settings.profile;
    }

    public static void setProfile(TopographyModuleDefinition module, String profile) {
        getSettings(module).profile = MODULE_PROFILES.contains(profile) ? profile : MODULE_PROFILES.getFirst();
        save();
    }

    public static String getBindLabel(TopographyModuleDefinition module) {
        int keyCode = getKeyCode(module);
        if (keyCode == GLFW.GLFW_KEY_UNKNOWN) {
            return "Unbound";
        }

        return InputUtil.fromKeyCode(new KeyInput(keyCode, 0, 0)).getLocalizedText().getString();
    }

    public static String getModelPath(TopographyModuleDefinition module) {
        ModuleSettings settings = getSettings(module);
        if (!module.usesModelPath()) {
            return "";
        }
        if (settings.modelPath == null || settings.modelPath.isBlank()) {
            settings.modelPath = module.defaultModelPath();
        }
        return settings.modelPath;
    }

    public static void setModelPath(TopographyModuleDefinition module, String modelPath) {
        if (!module.usesModelPath()) {
            return;
        }

        String trimmed = modelPath == null ? "" : modelPath.trim();
        getSettings(module).modelPath = trimmed.isEmpty() ? module.defaultModelPath() : trimmed;
        save();
    }

    public static boolean isBlurEnabled() {
        return ui().blurBackground;
    }

    public static void setBlurEnabled(boolean value) {
        ui().blurBackground = value;
        save();
    }

    public static boolean isMotionEnabled() {
        return ui().animationsEnabled;
    }

    public static void setMotionEnabled(boolean value) {
        ui().animationsEnabled = value;
        save();
    }

    public static float getInterfaceScale() {
        return ui().interfaceScale;
    }

    public static void setInterfaceScale(float value) {
        ui().interfaceScale = clamp(value, 0.9f, 1.12f);
        save();
    }

    public static String getAccentPreset() {
        if (!ACCENT_PRESETS.contains(ui().accentPreset)) {
            ui().accentPreset = ACCENT_PRESETS.getFirst();
        }
        return ui().accentPreset;
    }

    public static void setAccentPreset(String preset) {
        ui().accentPreset = ACCENT_PRESETS.contains(preset) ? preset : ACCENT_PRESETS.getFirst();
        save();
    }

    private static void ensureDefaults() {
        if (data == null) {
            data = createDefaultData();
        }
        if (data.ui == null) {
            data.ui = createDefaultUiSettings();
        }
        if (data.modules == null) {
            data.modules = new LinkedHashMap<>();
        }

        if (!ACCENT_PRESETS.contains(data.ui.accentPreset)) {
            data.ui.accentPreset = ACCENT_PRESETS.getFirst();
        }
        data.ui.interfaceScale = clamp(data.ui.interfaceScale, 0.9f, 1.12f);

        for (TopographyModuleDefinition module : TopographyModuleDefinition.values()) {
            ModuleSettings settings = data.modules.computeIfAbsent(module.id(), ignored -> createDefaultSettings(module));
            if (!module.usesModelPath()) {
                settings.modelPath = "";
            } else if (settings.modelPath == null || settings.modelPath.isBlank()) {
                settings.modelPath = module.defaultModelPath();
            }

            settings.responseCurve = clamp(settings.responseCurve, 0.15f, 1.0f);
            if (!MODULE_PROFILES.contains(settings.profile)) {
                settings.profile = MODULE_PROFILES.getFirst();
            }
        }
    }

    private static Data createDefaultData() {
        Data defaultData = new Data();
        defaultData.ui = createDefaultUiSettings();
        for (TopographyModuleDefinition module : TopographyModuleDefinition.values()) {
            defaultData.modules.put(module.id(), createDefaultSettings(module));
        }
        return defaultData;
    }

    private static UiSettings createDefaultUiSettings() {
        UiSettings settings = new UiSettings();
        settings.blurBackground = true;
        settings.animationsEnabled = true;
        settings.interfaceScale = 1.0f;
        settings.accentPreset = ACCENT_PRESETS.getFirst();
        return settings;
    }

    private static ModuleSettings createDefaultSettings(TopographyModuleDefinition module) {
        ModuleSettings settings = new ModuleSettings();
        settings.keyCode = GLFW.GLFW_KEY_UNKNOWN;
        settings.bindEnabled = true;
        settings.responseCurve = 0.65f;
        settings.profile = MODULE_PROFILES.getFirst();
        settings.modelPath = module.usesModelPath() ? module.defaultModelPath() : "";
        return settings;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class Data {
        private UiSettings ui = createDefaultUiSettings();
        private Map<String, ModuleSettings> modules = new LinkedHashMap<>();
    }

    public static final class UiSettings {
        public boolean blurBackground = true;
        public boolean animationsEnabled = true;
        public float interfaceScale = 1.0f;
        public String accentPreset = ACCENT_PRESETS.getFirst();
    }

    public static final class ModuleSettings {
        public int keyCode = GLFW.GLFW_KEY_UNKNOWN;
        public boolean bindEnabled = true;
        public float responseCurve = 0.65f;
        public String profile = MODULE_PROFILES.getFirst();
        public String modelPath = "";
    }
}
