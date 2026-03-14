package macro.topography;

/**
 * Represents a farming mode (e.g., Zealots, Bruisers).
 * The {@code id} maps to the int-based mode system used by Pathfinder/Autopilot.
 */
public final class Mode {

    private final int id;
    private final String name;
    private final String subtitle;

    public Mode(int id, String name, String subtitle) {
        this.id = id;
        this.name = name;
        this.subtitle = subtitle;
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public String getSubtitle() { return subtitle; }

    public boolean isActive() {
        return Autopilot.isEnabled() && Autopilot.getTargetMode() == id;
    }
}
