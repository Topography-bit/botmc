package macro.topography;

import java.util.Arrays;
import java.util.List;

public enum TopographyModuleDefinition {
    ZEALOT_BOT(
        "zealot_bot",
        TopographyTab.COMBAT,
        TopographyModuleType.COMBAT,
        "Zealot Bot",
        "Loads an ONNX profile and loops zealot combat without opening chat commands.",
        true,
        1,
        "bot_model.onnx"
    ),
    BRUISER_BOT(
        "bruiser_bot",
        TopographyTab.COMBAT,
        TopographyModuleType.COMBAT,
        "Bruiser Bot",
        "Runs the bruiser route with the same controller stack and hotkey workflow.",
        true,
        2,
        "bot_model.onnx"
    ),
    RECORD_SIMPLE_ZEALOTS(
        "record_simple_zealots",
        TopographyTab.PLAYER,
        TopographyModuleType.RECORDING,
        "Record Simple Zealots",
        "Captures training samples into datasets/ for lightweight zealot recordings.",
        false,
        1,
        ""
    ),
    RECORD_BRUISERS(
        "record_bruisers",
        TopographyTab.PLAYER,
        TopographyModuleType.RECORDING,
        "Record Bruisers",
        "Collects bruiser traces and writes a fresh CSV session on demand.",
        false,
        2,
        ""
    ),
    AUTOPILOT_ZEALOTS(
        "autopilot_zealots",
        TopographyTab.MOVEMENT,
        TopographyModuleType.AUTOPILOT,
        "Autopilot Zealots",
        "Rule-based autopilot that follows paths with human-like movement and combat.",
        false,
        1,
        ""
    ),
    AUTOPILOT_BRUISERS(
        "autopilot_bruisers",
        TopographyTab.MOVEMENT,
        TopographyModuleType.AUTOPILOT,
        "Autopilot Bruisers",
        "Rule-based autopilot for bruiser routes with natural movement patterns.",
        false,
        2,
        ""
    );

    private final String id;
    private final TopographyTab tab;
    private final TopographyModuleType type;
    private final String title;
    private final String description;
    private final boolean usesModelPath;
    private final int mode;
    private final String defaultModelPath;

    TopographyModuleDefinition(
        String id,
        TopographyTab tab,
        TopographyModuleType type,
        String title,
        String description,
        boolean usesModelPath,
        int mode,
        String defaultModelPath
    ) {
        this.id = id;
        this.tab = tab;
        this.type = type;
        this.title = title;
        this.description = description;
        this.usesModelPath = usesModelPath;
        this.mode = mode;
        this.defaultModelPath = defaultModelPath;
    }

    public String id() {
        return id;
    }

    public TopographyTab tab() {
        return tab;
    }

    public TopographyModuleType type() {
        return type;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public boolean usesModelPath() {
        return usesModelPath;
    }

    public int mode() {
        return mode;
    }

    public String defaultModelPath() {
        return defaultModelPath;
    }

    public String actionLabel() {
        return type.label();
    }

    public String activeBadge() {
        return type.activeBadge();
    }

    public static List<TopographyModuleDefinition> forTab(TopographyTab tab) {
        return Arrays.stream(values())
            .filter(module -> module.tab == tab)
            .toList();
    }
}
