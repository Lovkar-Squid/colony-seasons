package me.lovkar.seasons.calendar;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.Map;

/**
 * One festival and its two windows: the decoration window (decorations go up on the first day and
 * stay to the last) and the festival days. Windows are month-day pairs, both ends included, and a window
 * whose end comes before its start runs over the new year (12-24 to 01-06). A window never needs the
 * date to exist in the year being asked about: 02-29 compares as a month-day, so it works in every year.
 */
public record Festival(String id, boolean enabled, MonthDay decorFrom, MonthDay decorUntil,
                       MonthDay daysFrom, MonthDay daysUntil) {

    /** The lang keys of the festival names (literal, so the lang check sees them). */
    private static final Map<String, String> NAME_KEYS = Map.of(
            "winter_festival", "com.colony_seasons.festival.winter_festival",
            "new_year", "com.colony_seasons.festival.new_year");

    /** The lang key of this festival's name; an unknown id is shown as it is. */
    public static String nameKey(final String id) {
        return NAME_KEYS.getOrDefault(id, id);
    }

    /** A month-day from the config form {@code MM-DD}. */
    public static MonthDay parse(final String monthDay) {
        return MonthDay.parse("--" + monthDay);
    }

    /** True while the decorations of this festival are up (a disabled festival never has any). */
    public boolean decorOpen(final LocalDate date) {
        return enabled && inWindow(decorFrom, decorUntil, date);
    }

    /** True on the festival days themselves (a disabled festival never has any). */
    public boolean daysOpen(final LocalDate date) {
        return enabled && inWindow(daysFrom, daysUntil, date);
    }

    /** Is {@code date} inside the window, both ends included, with the wrap over the new year. */
    static boolean inWindow(final MonthDay from, final MonthDay until, final LocalDate date) {
        final MonthDay day = MonthDay.from(date);
        if (from.compareTo(until) <= 0) {
            return day.compareTo(from) >= 0 && day.compareTo(until) <= 0;
        }
        return day.compareTo(from) >= 0 || day.compareTo(until) <= 0;
    }
}
