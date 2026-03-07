package macro.topography;

public enum TopographyTab {
    COMBAT("Combat", "Combat automations and bot presets.", "C"),
    MOVEMENT("Movement", "Pathing, traversal and future movement tools.", "M"),
    VISUALS("Visuals", "Flat glass styling, blur and interface tuning.", "V"),
    PLAYER("Player", "Recording, keybinds and personal workflow modules.", "P");

    private final String title;
    private final String subtitle;
    private final String icon;

    TopographyTab(String title, String subtitle, String icon) {
        this.title = title;
        this.subtitle = subtitle;
        this.icon = icon;
    }

    public String title() {
        return title;
    }

    public String subtitle() {
        return subtitle;
    }

    public String icon() {
        return icon;
    }
}
