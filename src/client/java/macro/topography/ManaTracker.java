package macro.topography;

/** Simple mana state holder, updated by ActionBarMixin. */
public final class ManaTracker {
    public static volatile int currentMana = 0;
    public static volatile int maxMana = 0;

    private ManaTracker() {}
}
