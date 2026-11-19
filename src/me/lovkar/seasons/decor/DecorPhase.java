package me.lovkar.seasons.decor;

import me.lovkar.seasons.calendar.CalendarManager;

/**
 * Where the Decorator's year stands (design section 4). Worked out from the calendar's snapshot alone, never from
 * the clock or Serene Seasons, so whatever drives the calendar (the real date, a forced festival, a test's fixed
 * date) drives him too. Pure: the same snapshot gives the same phase.
 */
public enum DecorPhase {
    /** No window open and nothing up: he prepares stock (when the hut allows it), then idles. */
    OFF_SEASON,
    /** A decoration window is open: he puts the decorations up and tends them (S4), and keeps preparing. */
    DECORATING,
    /** Festival days: he hosts at the tree (S7). */
    HOSTING,
    /** After the window, while decorations are still up: he takes them down (S4). */
    TAKING_DOWN;

    /**
     * The phase for one calendar state.
     *
     * @param snapshot      the calendar's state, {@code null} before its first poll (gives {@link #OFF_SEASON})
     * @param decorationsUp whether the colony's decorations are up (the hut's {@code cs_decorations_up})
     */
    public static DecorPhase of(final CalendarManager.Snapshot snapshot, final boolean decorationsUp) {
        if (snapshot == null || snapshot.festivals() == null) {
            return OFF_SEASON;
        }
        if (!snapshot.festivals().days().isEmpty()) {
            return HOSTING;
        }
        if (!snapshot.festivals().decor().isEmpty()) {
            return DECORATING;
        }
        return decorationsUp ? TAKING_DOWN : OFF_SEASON;
    }
}
