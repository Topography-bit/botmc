package macro.topography;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Central registry of all farming modes.
 * New mode = one call to {@code register()}.
 */
public final class ModeRegistry {

    private static final List<Mode> modes = new ArrayList<>();

    private ModeRegistry() {}

    public static void register(Mode mode) {
        modes.add(mode);
    }

    public static List<Mode> getModes() {
        return Collections.unmodifiableList(modes);
    }

    public static Mode getById(int id) {
        for (Mode m : modes) {
            if (m.getId() == id) return m;
        }
        return null;
    }

    public static void init() {
        register(new Mode(1, "ZEALOTS", "Dragon's Nest"));
        register(new Mode(2, "BRUISERS", "Bruiser Hideout"));
    }
}
