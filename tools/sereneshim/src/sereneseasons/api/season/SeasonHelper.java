package sereneseasons.api.season;

import net.minecraft.world.level.Level;

/**
 * Test stand-in for the Serene Seasons API, written for this repo (nothing of Serene Seasons is copied or
 * downloaded): only the method shapes Colony Seasons' bridge calls. What it answers depends on the rig's
 * probe mode (the word in probe-mode.txt next to the server):
 * bridge-throws throws, anything else answers autumn, day 50 of a 96-day year, like a Serene Seasons with its defaults.
 */
public final class SeasonHelper {
    private SeasonHelper() {
    }

    public static ISeasonState getSeasonState(final Level level) {
        if ("bridge-throws".equals(SereneShim.mode())) {
            throw new IllegalStateException("stand-in Serene Seasons is not ready");
        }
        return new ISeasonState() {
            public int getDayDuration() { return 24000; }
            public int getCycleDuration() { return 24000 * 96; }
            public int getDay() { return 50; }
            public Season getSeason() { return Season.AUTUMN; }
        };
    }
}
