package me.lovkar.seasons.client;

import me.lovkar.seasons.network.SeasonStatePayload;

import java.util.List;

/**
 * The client's copy of the calendar: the last {@link SeasonStatePayload} the server sent, and nothing else. The
 * client never computes a season or a date (design 2.2), so before the first packet there is no state at all
 * rather than a guess. Nothing outside the {@code client} package and the packet handler names this class, so a
 * dedicated server never loads it.
 */
public final class ClientSeasonState {
    private static volatile SeasonStatePayload latest;

    private ClientSeasonState() {
    }

    /** Remember the state the server just sent. */
    public static void accept(final SeasonStatePayload payload) {
        latest = payload;
    }

    /** Forget it (on leaving a world). */
    public static void clear() {
        latest = null;
    }

    /** The last state the server sent, or {@code null} before the first one. */
    public static SeasonStatePayload get() {
        return latest;
    }

    /** True when the server says this festival is on. */
    public static boolean festivalActive(final String id) {
        final SeasonStatePayload state = latest;
        return state != null && state.festivals().contains(id);
    }

    /** The ids of the festivals that are on, empty before the first packet. */
    public static List<String> festivals() {
        final SeasonStatePayload state = latest;
        return state == null ? List.of() : state.festivals();
    }
}
