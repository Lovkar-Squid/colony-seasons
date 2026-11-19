package me.lovkar.seasons.calendar;

/**
 * Where the game calendar stands: the season, the day of the year (0-based) and how long the year is.
 *
 * @param source who decided it: this mod's own calendar or Serene Seasons (through the bridge)
 */
public record SeasonState(Season season, int dayOfYear, int yearLength, Source source) {

    /** Whose calendar a state comes from. */
    public enum Source {
        OWN,
        SERENE
    }

    /** The day inside the season, 0-based. */
    public int dayInSeason() {
        return dayOfYear % Math.max(1, yearLength / 4);
    }
}
