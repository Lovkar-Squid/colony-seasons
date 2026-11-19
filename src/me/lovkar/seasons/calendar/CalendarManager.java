package me.lovkar.seasons.calendar;

import me.lovkar.seasons.SeasonsConfig;
import me.lovkar.seasons.network.SeasonStatePayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.time.LocalDate;

/**
 * The server's calendar: puts together the season (Serene Seasons through the bridge, else the own calendar), the
 * festivals that are on, and the data saved with the world, and tells the clients when something changed.
 * Everything runs on the server thread. {@link CalendarEvents} polls it every 20 ticks; commands refresh it at once.
 *
 * <p>Later chunks read {@link #current()}; they never ask the clock or Serene Seasons themselves.</p>
 */
public final class CalendarManager {

    /**
     * Everything the calendar knows at one moment.
     *
     * @param season    the season and the day of the year
     * @param festivals what is on, forced festival included
     * @param forced    the festival on by force, "" for none
     * @param date      the real date the festival windows were read for
     */
    public record Snapshot(SeasonState season, FestivalCalendar.Active festivals, String forced, LocalDate date) {

        /** What the clients are told. */
        public SeasonStatePayload payload() {
            return new SeasonStatePayload(season.season(), season.dayOfYear(), season.yearLength(), festivals.ids());
        }
    }

    private static DateSource dates = DateSource.system();
    private static SereneBridge bridge = new SereneBridge();
    private static CalendarData data;
    private static Snapshot current;
    private static int polls;

    private CalendarManager() {
    }

    /** Replace the clock (tests); {@code null} puts the real one back. */
    public static void setDateSource(final DateSource source) {
        dates = source == null ? DateSource.system() : source;
    }

    /** Replace the bridge (tests); {@code null} puts a new real one in. */
    public static void setBridge(final SereneBridge replacement) {
        bridge = replacement == null ? new SereneBridge() : replacement;
    }

    /**
     * The real date and time of day from the same source the festival windows are read from (the New Year countdown
     * and the midnight show need the hour). Never the clock itself: {@link DateSource} is the only reader.
     */
    public static java.time.LocalDateTime now() {
        return dates.now();
    }

    /** A new server session: the bridge may try again and the world data is read again. */
    public static void reset() {
        bridge = new SereneBridge();
        data = null;
        current = null;
        polls = 0;
    }

    /** The last state worked out, or {@code null} before the first poll of a session. */
    public static Snapshot current() {
        return current;
    }

    /** How many times the state was worked out in this session (a poll, a command or a login). */
    public static int polls() {
        return polls;
    }

    /** The bridge of this session (its {@code fellBack()} tells whether Serene Seasons was given up). */
    public static SereneBridge bridge() {
        return bridge;
    }

    /** The data saved with the world. */
    public static CalendarData data(final MinecraftServer server) {
        if (data == null) {
            data = CalendarData.get(server.overworld(), dates);
        }
        return data;
    }

    /** Works the state out again; tells every player when it differs from the last one. */
    public static Snapshot refresh(final MinecraftServer server) {
        final ServerLevel overworld = server.overworld();
        final CalendarData saved = data(server);
        SeasonState season = bridge.read(overworld);
        if (season == null) {
            season = SeasonCalendar.state(overworld.getDayTime(), saved.offset(), SeasonsConfig.yearLengthDays());
        }
        final LocalDate today = dates.today();
        final FestivalCalendar.Active festivals = FestivalCalendar.fromConfig().at(today).with(saved.forcedFestival());
        final Snapshot next = new Snapshot(season, festivals, saved.forcedFestival(), today);
        final Snapshot before = current;
        current = next;
        polls++;
        if (before == null || !before.payload().equals(next.payload())) {
            PacketDistributor.sendToAllPlayers(next.payload());
        }
        return next;
    }

    /** Sends the current state to one player (at login). */
    public static void sendTo(final MinecraftServer server, final ServerPlayer player) {
        final Snapshot snapshot = current != null ? current : refresh(server);
        PacketDistributor.sendToPlayer(player, snapshot.payload());
    }

    /** {@code /colonyseasons season set}: the offset that makes today the first day of {@code season}. */
    public static Snapshot setSeason(final MinecraftServer server, final Season season) {
        data(server).setOffset(SeasonCalendar.offsetFor(season, server.overworld().getDayTime(), SeasonsConfig.yearLengthDays()));
        return refresh(server);
    }

    /** {@code /colonyseasons festival force}; {@code ""} clears. */
    public static Snapshot setForcedFestival(final MinecraftServer server, final String id) {
        data(server).setForcedFestival(id);
        return refresh(server);
    }
}
