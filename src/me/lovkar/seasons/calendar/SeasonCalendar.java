package me.lovkar.seasons.calendar;

import me.lovkar.seasons.SeasonsConfig;

/**
 * This mod's own season calendar, as pure functions: {@code day of year = (dayTime / 24000 + offset) mod yearLength}.
 * There is no counter that ticks; sleeping, {@code /time set} and a paused day cycle therefore move the
 * season exactly as they move the day. The offset lives in {@link CalendarData}. A quarter of the year is one season.
 */
public final class SeasonCalendar {
    public static final long TICKS_PER_DAY = 24000L;

    private SeasonCalendar() {
    }

    /** Throws unless the length is 4 to 960 and a multiple of 4 (the rule of {@code calendar.yearLengthDays}). */
    public static int requireYearLength(final int yearLength) {
        if (!SeasonsConfig.isYearLength(yearLength)) {
            throw new IllegalArgumentException("year length must be 4 to 960 and a multiple of 4: " + yearLength);
        }
        return yearLength;
    }

    /** Days in one season. */
    public static int seasonLength(final int yearLength) {
        return requireYearLength(yearLength) / 4;
    }

    /** The day of the year, 0 to yearLength - 1, for the overworld's day time. */
    public static int dayOfYear(final long dayTime, final int offset, final int yearLength) {
        requireYearLength(yearLength);
        final long day = Math.floorDiv(dayTime, TICKS_PER_DAY);
        return (int) Math.floorMod(day + offset, (long) yearLength);
    }

    /** The season a day of the year falls in. */
    public static Season seasonOf(final int dayOfYear, final int yearLength) {
        return Season.byOrdinal(dayOfYear / seasonLength(yearLength));
    }

    /** The state of this mod's calendar for a day time and an offset. */
    public static SeasonState state(final long dayTime, final int offset, final int yearLength) {
        final int day = dayOfYear(dayTime, offset, yearLength);
        return new SeasonState(seasonOf(day, yearLength), day, yearLength, SeasonState.Source.OWN);
    }

    /**
     * The offset that makes {@code dayTime} the first day of {@code season}. Used for a new world
     * ({@code calendar.startSeason}) and by {@code /colonyseasons season set}.
     */
    public static int offsetFor(final Season season, final long dayTime, final int yearLength) {
        final int firstDay = season.ordinal() * seasonLength(yearLength);
        final long day = Math.floorDiv(dayTime, TICKS_PER_DAY);
        return (int) Math.floorMod(firstDay - day, (long) yearLength);
    }
}
