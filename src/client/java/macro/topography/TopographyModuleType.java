package macro.topography;

public enum TopographyModuleType {
    RECORDING("Recording", "Recording"),
    COMBAT("Combat", "Active"),
    AUTOPILOT("Autopilot", "Driving");

    private final String label;
    private final String activeBadge;

    TopographyModuleType(String label, String activeBadge) {
        this.label = label;
        this.activeBadge = activeBadge;
    }

    public String label() {
        return label;
    }

    public String activeBadge() {
        return activeBadge;
    }
}
