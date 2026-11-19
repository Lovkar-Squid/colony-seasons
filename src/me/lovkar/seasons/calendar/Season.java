package me.lovkar.seasons.calendar;

import java.time.LocalDate;
import java.time.Month;

/**
 * The four seasons of the game calendar. The ordinal order (spring first) is also Serene Seasons'
 * {@code Season} order, so the bridge can map one to the other by ordinal.
 */
public enum Season {
    SPRING("spring"),
    SUMMER("summer"),
    AUTUMN("autumn"),
    WINTER("winter");

    private final String id;

    Season(final String id) {
        this.id = id;
    }

    /** The lower-case id used in commands, the config and the lang keys. */
    public String id() {
        return id;
    }

    /** The lang key of the season's name. */
    public String translationKey() {
        return "com.colony_seasons.season." + id;
    }

    /** The season with this id, or {@code null}. */
    public static Season fromId(final String id) {
        for (final Season season : values()) {
            if (season.id.equals(id)) {
                return season;
            }
        }
        return null;
    }

    /** The season with this ordinal, or {@code null} when it is out of range. */
    public static Season byOrdinal(final int ordinal) {
        final Season[] all = values();
        return ordinal >= 0 && ordinal < all.length ? all[ordinal] : null;
    }

    /**
     * The season of a real date on the northern hemisphere, by whole months: March to May is spring,
     * June to August summer, September to November autumn and December to February winter. A colony
     * founded on 1 December therefore starts in winter, on the first day of the Winter Festival's decorations.
     */
    public static Season ofDate(final LocalDate date) {
        final Month month = date.getMonth();
        return switch (month) {
            case MARCH, APRIL, MAY -> SPRING;
            case JUNE, JULY, AUGUST -> SUMMER;
            case SEPTEMBER, OCTOBER, NOVEMBER -> AUTUMN;
            case DECEMBER, JANUARY, FEBRUARY -> WINTER;
        };
    }
}
