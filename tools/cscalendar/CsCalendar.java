package me.lovkar.seasons.cscalendar;

import io.netty.buffer.Unpooled;
import me.lovkar.seasons.SeasonsConfig;
import me.lovkar.seasons.calendar.CalendarData;
import me.lovkar.seasons.calendar.CalendarManager;
import me.lovkar.seasons.calendar.DateSource;
import me.lovkar.seasons.calendar.Festival;
import me.lovkar.seasons.calendar.FestivalCalendar;
import me.lovkar.seasons.calendar.Season;
import me.lovkar.seasons.calendar.SeasonCalendar;
import me.lovkar.seasons.calendar.SeasonState;
import me.lovkar.seasons.calendar.SereneBridge;
import me.lovkar.seasons.network.SeasonStatePayload;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Headless check of the calendar (chunk S1) on the dedicated test server. One mod, one mode word per run
 * (the rig writes it to probe-mode.txt):
 * <ul>
 *   <li>{@code dates}: the pure parts with a fake clock (festival windows, the year turn, 29 Feb, the season from day time
 *       and offset for year lengths 4, 48 and 96, startSeason auto, the time zone), the saved data, the packet codec,
 *       and the real commands run on the live server (status, season set, festival force and clear, permission 2);</li>
 *   <li>{@code bridge-absent}, {@code bridge-present}, {@code bridge-throws}, {@code bridge-nonseasonal}, {@code bridge-off}:
 *       the Serene Seasons bridge with no such mod, with the stand-in jar (tools/sereneshim), with a stand-in that throws,
 *       with one whose whitelist leaves the overworld out, and with the config switched off;</li>
 *   <li>{@code restart-save} then {@code restart-load} (tools/restart.sh): an offset and a forced festival set in one
 *       server run are read back in the next one on the same world.</li>
 * </ul>
 * Prints "[cscalendar] CHECK ..." lines and "[cscalendar] RESULT cscalendar: PASS|FAIL", then halts.
 * The words "error" and "exception" stay out of the details: the rig takes such a line for a failure.
 */
@Mod("colonyseasonscalendar")
public class CsCalendar {
    private static final Logger LOGGER = LoggerFactory.getLogger("cscalendar");
    private int ticks = 0;
    private boolean failed = false;

    public CsCalendar() {
        NeoForge.EVENT_BUS.addListener(this::onTick);
    }

    private void check(final String what, final boolean ok, final String detail) {
        LOGGER.info("[cscalendar] CHECK {}: {} {}", what, ok ? "OK" : "FAIL", detail);
        failed |= !ok;
    }

    private void checkEq(final String what, final Object actual, final Object expected) {
        check(what, Objects.equals(actual, expected), "got " + actual + (Objects.equals(actual, expected) ? "" : ", wanted " + expected));
    }

    private void onTick(final ServerTickEvent.Post event) {
        if (++ticks != 100) {
            return;
        }
        final MinecraftServer server = event.getServer();
        try {
            final String mode = readMode();
            LOGGER.info("[cscalendar] mode '{}'", mode);
            check("the server polled the calendar by itself", CalendarManager.polls() >= 4 && CalendarManager.current() != null,
                    CalendarManager.polls() + " polls by tick 100");
            switch (mode) {
                case "dates" -> runDates(server);
                case "bridge-absent" -> runBridgeAbsent(server);
                case "bridge-present" -> runBridgePresent(server);
                case "bridge-throws" -> runBridgeThrows(server);
                case "bridge-nonseasonal" -> runBridgeNonseasonal(server);
                case "bridge-off" -> runBridgeOff(server);
                case "restart-save" -> runRestartSave(server);
                case "restart-load" -> runRestartLoad(server);
                default -> check("known mode", false, "'" + mode + "'");
            }
        } catch (final Throwable t) {
            check("probe ran to the end", false, t.toString());
        }
        LOGGER.info("[cscalendar] RESULT cscalendar: {}", failed ? "FAIL" : "PASS");
        server.halt(false);
    }

    private static String readMode() throws Exception {
        final Path file = Path.of("probe-mode.txt");
        return Files.exists(file) ? Files.readString(file, StandardCharsets.US_ASCII).trim() : "";
    }

    // ================================================================ mode: dates

    private void runDates(final MinecraftServer server) throws Exception {
        checkDateSource();
        checkFestivalWindows();
        checkSeasonMath();
        checkStartSeason();
        checkSavedData(server);
        checkPayloadCodec(server);
        checkCommands(server);
    }

    // ---------------------------------------------------------------- the clock and the time zone

    private void checkDateSource() {
        // 2026-12-31 12:00 UTC is already 1 Jan in Kiritimati (UTC+14) and still 31 Dec in Pago Pago (UTC-11)
        final Instant noon = Instant.parse("2026-12-31T12:00:00Z");
        final Clock clock = Clock.fixed(noon, ZoneOffset.UTC);
        checkEq("zone Pacific/Kiritimati reads 1 Jan", DateSource.of(clock, () -> "Pacific/Kiritimati").today(), LocalDate.of(2027, 1, 1));
        checkEq("zone Pacific/Pago_Pago reads 31 Dec", DateSource.of(clock, () -> "Pacific/Pago_Pago").today(), LocalDate.of(2026, 12, 31));
        checkEq("zone UTC", DateSource.of(clock, () -> "UTC").today(), LocalDate.of(2026, 12, 31));
        checkEq("zone system is the server zone", DateSource.of(clock, () -> "system").today(),
                LocalDate.ofInstant(noon, ZoneId.systemDefault()));
        checkEq("an unknown zone falls back to the server zone", DateSource.of(clock, () -> "No/Such_Zone").today(),
                LocalDate.ofInstant(noon, ZoneId.systemDefault()));
        checkEq("fixed source", DateSource.fixed(LocalDate.of(2028, 2, 29)).today(), LocalDate.of(2028, 2, 29));
        // the zone is read again on every call: a config change applies at once
        final String[] setting = {"Pacific/Kiritimati"};
        final DateSource live = DateSource.of(clock, () -> setting[0]);
        final LocalDate first = live.today();
        setting[0] = "Pacific/Pago_Pago";
        check("the zone setting is read on every call", !first.equals(live.today()), first + " then " + live.today());
    }

    // ---------------------------------------------------------------- festival windows

    private void expectOn(final String what, final FestivalCalendar calendar, final LocalDate date,
                          final List<String> decor, final List<String> days) {
        final FestivalCalendar.Active active = calendar.at(date);
        check(what + " (" + date + ")", active.decor().equals(decor) && active.days().equals(days),
                "decor " + active.decor() + " days " + active.days() + (active.decor().equals(decor) && active.days().equals(days)
                        ? "" : ", wanted decor " + decor + " days " + days));
    }

    private static Festival festival(final String id, final boolean enabled, final String decorFrom, final String decorUntil,
                                     final String daysFrom, final String daysUntil) {
        return new Festival(id, enabled, Festival.parse(decorFrom), Festival.parse(decorUntil), Festival.parse(daysFrom), Festival.parse(daysUntil));
    }

    private void checkFestivalWindows() {
        final String w = "winter_festival";
        final String n = "new_year";
        final List<String> none = List.of();
        // the owner's default windows (design 2.1), written out by hand
        final FestivalCalendar byHand = new FestivalCalendar(List.of(
                festival(w, true, "12-01", "01-06", "12-24", "12-26"),
                festival(n, true, "12-31", "01-01", "12-31", "01-01")));
        final FestivalCalendar fromConfig = FestivalCalendar.fromConfig();
        final Object[][] table = {
                {LocalDate.of(2026, 11, 30), none, none},
                {LocalDate.of(2026, 12, 1), List.of(w), none},
                {LocalDate.of(2026, 12, 23), List.of(w), none},
                {LocalDate.of(2026, 12, 24), List.of(w), List.of(w)},
                {LocalDate.of(2026, 12, 26), List.of(w), List.of(w)},
                {LocalDate.of(2026, 12, 27), List.of(w), none},
                {LocalDate.of(2026, 12, 30), List.of(w), none},
                {LocalDate.of(2026, 12, 31), List.of(w, n), List.of(n)},
                {LocalDate.of(2027, 1, 1), List.of(w, n), List.of(n)},
                {LocalDate.of(2027, 1, 2), List.of(w), none},
                {LocalDate.of(2027, 1, 6), List.of(w), none},
                {LocalDate.of(2027, 1, 7), none, none},
                {LocalDate.of(2027, 6, 15), none, none},
                {LocalDate.of(2028, 2, 29), none, none},
        };
        for (final Object[] row : table) {
            @SuppressWarnings("unchecked") final List<String> decor = (List<String>) row[1];
            @SuppressWarnings("unchecked") final List<String> days = (List<String>) row[2];
            expectOn("default windows by hand", byHand, (LocalDate) row[0], decor, days);
            expectOn("windows from the default config", fromConfig, (LocalDate) row[0], decor, days);
        }

        // the year turn, 29 Feb, one-day windows and a disabled festival
        final FestivalCalendar odd = new FestivalCalendar(List.of(
                festival("turn", true, "12-30", "01-02", "01-01", "01-01"),
                festival("leap", true, "02-28", "03-01", "02-29", "02-29"),
                festival("single", true, "05-05", "05-05", "05-05", "05-05"),
                festival("off", false, "12-01", "12-31", "12-01", "12-31")));
        expectOn("year turn: before", odd, LocalDate.of(2026, 12, 29), none, none);
        expectOn("year turn: opens 30 Dec", odd, LocalDate.of(2026, 12, 30), List.of("turn"), none);
        expectOn("year turn: 31 Dec", odd, LocalDate.of(2026, 12, 31), List.of("turn"), none);
        expectOn("year turn: 1 Jan has its days", odd, LocalDate.of(2027, 1, 1), List.of("turn"), List.of("turn"));
        expectOn("year turn: closes 2 Jan", odd, LocalDate.of(2027, 1, 2), List.of("turn"), none);
        expectOn("year turn: closed 3 Jan", odd, LocalDate.of(2027, 1, 3), none, none);
        expectOn("leap year: 27 Feb is before", odd, LocalDate.of(2028, 2, 27), none, none);
        expectOn("leap year: 28 Feb opens", odd, LocalDate.of(2028, 2, 28), List.of("leap"), none);
        expectOn("leap year: 29 Feb has its day", odd, LocalDate.of(2028, 2, 29), List.of("leap"), List.of("leap"));
        expectOn("leap year: 1 Mar still open", odd, LocalDate.of(2028, 3, 1), List.of("leap"), none);
        expectOn("leap year: 2 Mar closed", odd, LocalDate.of(2028, 3, 2), none, none);
        expectOn("common year: 28 Feb opens", odd, LocalDate.of(2027, 2, 28), List.of("leap"), none);
        expectOn("common year: 1 Mar has no festival day", odd, LocalDate.of(2027, 3, 1), List.of("leap"), none);
        expectOn("one-day window: before", odd, LocalDate.of(2027, 5, 4), none, none);
        expectOn("one-day window: the day", odd, LocalDate.of(2027, 5, 5), List.of("single"), List.of("single"));
        expectOn("one-day window: after", odd, LocalDate.of(2027, 5, 6), none, none);
        expectOn("a disabled festival never opens", odd, LocalDate.of(2026, 12, 15), none, none);

        // a window that starts on 29 Feb works in a year that has none
        final FestivalCalendar fromLeapDay = new FestivalCalendar(List.of(festival("x", true, "02-29", "03-02", "02-29", "02-29")));
        expectOn("window from 29 Feb: 28 Feb of a common year", fromLeapDay, LocalDate.of(2027, 2, 28), none, none);
        expectOn("window from 29 Feb: 1 Mar of a common year", fromLeapDay, LocalDate.of(2027, 3, 1), List.of("x"), none);
        expectOn("window from 29 Feb: 29 Feb of a leap year", fromLeapDay, LocalDate.of(2028, 2, 29), List.of("x"), List.of("x"));

        // the ids a client is told about: decoration window or festival days, no duplicates
        checkEq("active ids on 31 Dec", byHand.at(LocalDate.of(2026, 12, 31)).ids(), List.of(w, n));
        checkEq("active ids on 24 Dec", byHand.at(LocalDate.of(2026, 12, 24)).ids(), List.of(w));
        checkEq("active ids in June", byHand.at(LocalDate.of(2027, 6, 1)).ids(), none);
        checkEq("a forced festival is added to both windows", FestivalCalendar.Active.NONE.with(n),
                new FestivalCalendar.Active(List.of(n), List.of(n)));
        checkEq("a forced festival is not listed twice", byHand.at(LocalDate.of(2026, 12, 31)).with(n).ids(), List.of(w, n));
        checkEq("MonthDay parse of the config form", Festival.parse("02-29"), MonthDay.of(2, 29));
    }

    // ---------------------------------------------------------------- season from day time and offset

    private void checkSeasonMath() {
        // hand-written spots
        checkEq("year 4: day 0", seasonAt(0, 0, 4), Season.SPRING);
        checkEq("year 4: day 1", seasonAt(24000, 0, 4), Season.SUMMER);
        checkEq("year 4: day 2", seasonAt(48000, 0, 4), Season.AUTUMN);
        checkEq("year 4: day 3", seasonAt(72000, 0, 4), Season.WINTER);
        checkEq("year 4: day 4 wraps to spring", seasonAt(96000, 0, 4), Season.SPRING);
        checkEq("year 48: day 11", seasonAt(11 * 24000L, 0, 48), Season.SPRING);
        checkEq("year 48: day 12", seasonAt(12 * 24000L, 0, 48), Season.SUMMER);
        checkEq("year 48: day 24", seasonAt(24 * 24000L, 0, 48), Season.AUTUMN);
        checkEq("year 48: day 36", seasonAt(36 * 24000L, 0, 48), Season.WINTER);
        checkEq("year 48: day 47", seasonAt(47 * 24000L, 0, 48), Season.WINTER);
        checkEq("year 48: day 48 wraps", seasonAt(48 * 24000L, 0, 48), Season.SPRING);
        checkEq("year 48 with offset 36: day 0 is winter", seasonAt(0, 36, 48), Season.WINTER);
        checkEq("year 48 with offset 36: day 12 wraps to spring", seasonAt(12 * 24000L, 36, 48), Season.SPRING);
        checkEq("year 96: day 23", seasonAt(23 * 24000L, 0, 96), Season.SPRING);
        checkEq("year 96: day 24", seasonAt(24 * 24000L, 0, 96), Season.SUMMER);
        checkEq("year 96: day 72", seasonAt(72 * 24000L, 0, 96), Season.WINTER);
        checkEq("year 96: day 95", seasonAt(95 * 24000L, 0, 96), Season.WINTER);
        checkEq("year 96: day 96 wraps", seasonAt(96 * 24000L, 0, 96), Season.SPRING);
        checkEq("the last tick of a day is still that day", SeasonCalendar.dayOfYear(11 * 24000L + 23999, 0, 48), 11);
        checkEq("the first tick of the next day", SeasonCalendar.dayOfYear(12 * 24000L, 0, 48), 12);
        checkEq("a negative day time wraps backwards", SeasonCalendar.dayOfYear(-1, 0, 48), 47);

        // every day of two years against a counter that counts up and wraps (a different way to get the same answer)
        final int[] lengths = {4, 48, 96, 960};
        final int[] offsets = {0, 1, 3, 47, 100, 1000};
        boolean allRight = true;
        String firstWrong = "";
        for (final int length : lengths) {
            for (final int offset : offsets) {
                int counter = offset % length;
                for (int day = 0; day < 2 * length + 3; day++) {
                    final long dayTime = day * 24000L + (day % 2 == 0 ? 0 : 23999);
                    final SeasonState state = SeasonCalendar.state(dayTime, offset, length);
                    final Season wanted = Season.byOrdinal(counter * 4 / length);
                    if (state.dayOfYear() != counter || state.season() != wanted || state.yearLength() != length
                            || state.source() != SeasonState.Source.OWN) {
                        if (allRight) {
                            firstWrong = "year " + length + " offset " + offset + " day " + day + ": got " + state + ", wanted day " + counter + " " + wanted;
                        }
                        allRight = false;
                    }
                    counter = (counter + 1) % length;
                }
            }
        }
        check("season and day of year for every day, year lengths 4, 48, 96 and 960", allRight, allRight ? "all days agree" : firstWrong);

        // the year length rule: 4 to 960, a multiple of 4
        for (final int bad : new int[]{0, 3, 5, 47, 964, 1000, -4}) {
            boolean threw = false;
            try {
                SeasonCalendar.dayOfYear(0, 0, bad);
            } catch (final IllegalArgumentException refused) {
                threw = true;
            }
            check("year length " + bad + " is refused", threw, "");
        }
        check("year lengths 4 and 960 are accepted", SeasonCalendar.dayOfYear(0, 0, 4) == 0 && SeasonCalendar.dayOfYear(0, 0, 960) == 0, "");

        // offsetFor: the offset that makes this moment the first day of a season
        boolean offsetsRight = true;
        String firstBad = "";
        for (final int length : new int[]{4, 48, 96}) {
            for (final Season season : Season.values()) {
                for (final long dayTime : new long[]{0, 5 * 24000L + 100, 123456789L}) {
                    final SeasonState state = SeasonCalendar.state(dayTime, SeasonCalendar.offsetFor(season, dayTime, length), length);
                    if (state.season() != season || state.dayInSeason() != 0) {
                        offsetsRight = false;
                        firstBad = "year " + length + ", " + season + ", day time " + dayTime + ": " + state;
                    }
                }
            }
        }
        check("offsetFor starts each season on its first day", offsetsRight, offsetsRight ? "4 seasons x 3 year lengths x 3 day times" : firstBad);
    }

    private static Season seasonAt(final long dayTime, final int offset, final int yearLength) {
        return SeasonCalendar.state(dayTime, offset, yearLength).season();
    }

    // ---------------------------------------------------------------- the season of a new world

    private void checkStartSeason() {
        final Object[][] table = {
                {LocalDate.of(2027, 1, 15), Season.WINTER}, {LocalDate.of(2027, 4, 20), Season.SPRING},
                {LocalDate.of(2027, 7, 4), Season.SUMMER}, {LocalDate.of(2027, 10, 10), Season.AUTUMN},
                {LocalDate.of(2027, 2, 28), Season.WINTER}, {LocalDate.of(2027, 3, 1), Season.SPRING},
                {LocalDate.of(2027, 5, 31), Season.SPRING}, {LocalDate.of(2027, 6, 1), Season.SUMMER},
                {LocalDate.of(2027, 8, 31), Season.SUMMER}, {LocalDate.of(2027, 9, 1), Season.AUTUMN},
                {LocalDate.of(2027, 11, 30), Season.AUTUMN}, {LocalDate.of(2027, 12, 1), Season.WINTER},
                {LocalDate.of(2028, 2, 29), Season.WINTER},
        };
        for (final Object[] row : table) {
            final LocalDate date = (LocalDate) row[0];
            for (final long dayTime : new long[]{0, 3 * 24000L + 500}) {
                final CalendarData data = CalendarData.fresh(dayTime, 48, "auto", date);
                final SeasonState state = SeasonCalendar.state(dayTime, data.offset(), 48);
                check("startSeason auto on " + date + " (day time " + dayTime + ")", state.season() == row[1] && state.dayInSeason() == 0,
                        state.season() + " day " + state.dayInSeason() + ", wanted " + row[1] + " day 0");
            }
        }
        final CalendarData summer = CalendarData.fresh(0, 48, "summer", LocalDate.of(2027, 1, 15));
        checkEq("an explicit startSeason beats the date", SeasonCalendar.state(0, summer.offset(), 48).season(), Season.SUMMER);
        final CalendarData odd = CalendarData.fresh(0, 48, "nonsense", LocalDate.of(2027, 7, 4));
        checkEq("an unknown startSeason is taken as auto", SeasonCalendar.state(0, odd.offset(), 96 / 2).season(), Season.SUMMER);
        check("a new world's data is marked for saving", summer.isDirty(), "isDirty");
        checkEq("a new world has no forced festival", summer.forcedFestival(), "");
    }

    // ---------------------------------------------------------------- saved data and the packet

    private void checkSavedData(final MinecraftServer server) {
        final CalendarData data = new CalendarData(29, "new_year");
        final CompoundTag tag = data.save(new CompoundTag(), server.registryAccess());
        final CalendarData back = CalendarData.load(tag, server.registryAccess());
        checkEq("saved data: offset survives NBT", back.offset(), 29);
        checkEq("saved data: forced festival survives NBT", back.forcedFestival(), "new_year");
        final CalendarData empty = CalendarData.load(new CompoundTag(), server.registryAccess());
        check("saved data: an empty tag loads as offset 0, nothing forced", empty.offset() == 0 && empty.forcedFestival().isEmpty(), "");
        final CalendarData changed = new CalendarData(0, "");
        check("saved data: a clean object is not dirty", !changed.isDirty(), "");
        changed.setForcedFestival("new_year");
        check("saved data: forcing marks it dirty", changed.isDirty(), "");
        changed.setForcedFestival("");
        checkEq("saved data: clearing forgets the festival", changed.forcedFestival(), "");
    }

    private void checkPayloadCodec(final MinecraftServer server) {
        final List<SeasonStatePayload> samples = List.of(
                new SeasonStatePayload(Season.WINTER, 36, 48, List.of("new_year")),
                new SeasonStatePayload(Season.SPRING, 0, 4, List.of()),
                new SeasonStatePayload(Season.AUTUMN, 959, 960, List.of("winter_festival", "new_year")),
                new SeasonStatePayload(Season.SUMMER, 17, 96, List.of("a", "b", "c")));
        for (final SeasonStatePayload sample : samples) {
            final RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
            SeasonStatePayload.STREAM_CODEC.encode(buffer, sample);
            final SeasonStatePayload decoded = SeasonStatePayload.STREAM_CODEC.decode(buffer);
            check("payload decodes to an equal value: " + sample, sample.equals(decoded) && buffer.readableBytes() == 0,
                    "got " + decoded + ", " + buffer.readableBytes() + " bytes left");
        }
        // the layout on the wire: season id, day, year length, then the list (a golden sample; a change bumps the protocol version)
        final RegistryFriendlyByteBuf golden = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
        SeasonStatePayload.STREAM_CODEC.encode(golden, samples.get(0));
        final byte[] bytes = new byte[golden.readableBytes()];
        golden.readBytes(bytes);
        final byte[] wanted = {3, 36, 48, 1, 8, 'n', 'e', 'w', '_', 'y', 'e', 'a', 'r'};
        check("payload wire layout", java.util.Arrays.equals(bytes, wanted), java.util.HexFormat.of().formatHex(bytes));
        check("payload type id", "colony_seasons:season_state".equals(SeasonStatePayload.TYPE.id().toString()), SeasonStatePayload.TYPE.id().toString());
        final SeasonStatePayload copy = new SeasonStatePayload(Season.SPRING, 1, 48, new ArrayList<>(List.of("x")));
        check("payload keeps its own copy of the festival list", copy.festivals().equals(List.of("x")), "");
    }

    // ---------------------------------------------------------------- the commands, on the live server

    /** Collects what a command says, as a command source. */
    private static final class Collector implements CommandSource {
        final List<String> lines = new ArrayList<>();
        /** The translation keys of every message, the arguments' and siblings' included (the server may or may not translate them). */
        final List<String> keys = new ArrayList<>();

        @Override
        public void sendSystemMessage(final Component message) {
            lines.add(message.getString());
            collect(message);
        }

        private void collect(final Component component) {
            if (component.getContents() instanceof TranslatableContents translatable) {
                keys.add(translatable.getKey());
                for (final Object argument : translatable.getArgs()) {
                    if (argument instanceof Component inner) {
                        collect(inner);
                    }
                }
            }
            for (final Component sibling : component.getSiblings()) {
                collect(sibling);
            }
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }

        boolean said(final String key) {
            return keys.stream().anyMatch(known -> known.contains(key));
        }
    }

    private static CommandSourceStack source(final MinecraftServer server, final Collector out, final int permission) {
        return server.createCommandSourceStack().withSource(out).withPermission(permission);
    }

    private static Collector run(final MinecraftServer server, final int permission, final String command) {
        final Collector out = new Collector();
        server.getCommands().performPrefixedCommand(source(server, out, permission), command);
        return out;
    }

    private void checkCommands(final MinecraftServer server) throws Exception {
        CalendarManager.setDateSource(DateSource.fixed(LocalDate.of(2027, 6, 15)));
        try {
            final Collector status = run(server, 0, "colonyseasons status");
            check("status works for everyone and names the season and the festivals",
                    status.said("com.colony_seasons.command.status.season") && status.said("com.colony_seasons.command.status.festivals.none"),
                    String.join(" | ", status.keys));
            checkEq("on 15 June no festival is on", CalendarManager.current().festivals().ids(), List.of());
            final long dayTime = server.overworld().getDayTime();

            // season set
            run(server, 2, "colonyseasons season set winter");
            SeasonState state = CalendarManager.current().season();
            check("season set winter starts winter on its first day", state.season() == Season.WINTER && state.dayInSeason() == 0 && state.dayOfYear() == 36,
                    state.toString());
            checkEq("season set changes the saved offset", CalendarManager.data(server).offset(),
                    SeasonCalendar.offsetFor(Season.WINTER, dayTime, SeasonsConfig.yearLengthDays()));
            run(server, 2, "colonyseasons season set spring");
            checkEq("season set spring", CalendarManager.current().season().season(), Season.SPRING);
            run(server, 2, "colonyseasons season set autumn");
            checkEq("season set autumn", CalendarManager.current().season().season(), Season.AUTUMN);
            run(server, 2, "colonyseasons season set summer");
            checkEq("season set summer", CalendarManager.current().season().season(), Season.SUMMER);

            // permission 2
            final int offsetBefore = CalendarManager.data(server).offset();
            final Collector denied = run(server, 1, "colonyseasons season set winter");
            checkEq("season set needs permission 2: offset unchanged", CalendarManager.data(server).offset(), offsetBefore);
            check("season set at permission 1 is refused with a message", !denied.lines.isEmpty(), String.join(" | ", denied.lines));
            run(server, 1, "colonyseasons festival force winter_festival");
            checkEq("festival force needs permission 2", CalendarManager.data(server).forcedFestival(), "");
            run(server, 1, "colonyseasons festival clear");
            check("status still works at permission 0 after the refusals", run(server, 0, "colonyseasons status").said("status.season"), "");

            // festival force and clear
            final Collector force = run(server, 2, "colonyseasons festival force winter_festival");
            check("force says so", force.said("com.colony_seasons.command.festival.force"), String.join(" | ", force.keys));
            final CalendarManager.Snapshot forced = CalendarManager.current();
            checkEq("forced festival is active in June", forced.festivals().ids(), List.of("winter_festival"));
            checkEq("forced festival has its festival days too", forced.festivals().days(), List.of("winter_festival"));
            checkEq("forced festival is in the saved data", CalendarManager.data(server).forcedFestival(), "winter_festival");
            checkEq("forced festival is in the payload", forced.payload().festivals(), List.of("winter_festival"));
            check("status shows the forced festival", run(server, 0, "colonyseasons status").said("com.colony_seasons.command.status.forced"), "");
            run(server, 2, "colonyseasons festival force new_year");
            checkEq("force replaces the earlier forced festival", CalendarManager.current().festivals().ids(), List.of("new_year"));
            final Collector unknown = run(server, 2, "colonyseasons festival force nonsense");
            check("force of an unknown festival is refused", unknown.said("force.unknown"), String.join(" | ", unknown.keys));
            checkEq("... and changes nothing", CalendarManager.data(server).forcedFestival(), "new_year");
            final Collector cleared = run(server, 2, "colonyseasons festival clear");
            check("clear says so", cleared.said("com.colony_seasons.command.festival.clear"), String.join(" | ", cleared.keys));
            checkEq("clear ends the festival", CalendarManager.current().festivals().ids(), List.of());
            checkEq("clear empties the saved data", CalendarManager.data(server).forcedFestival(), "");
            check("clear with nothing forced is refused", run(server, 2, "colonyseasons festival clear").said("clear.none"), "");

            // a natural window with the fake clock: 25 Dec is festival day, and a forced festival does not hide it
            CalendarManager.setDateSource(DateSource.fixed(LocalDate.of(2026, 12, 25)));
            final CalendarManager.Snapshot christmas = CalendarManager.refresh(server);
            checkEq("on 25 Dec the Winter Festival is on by the calendar", christmas.festivals().days(), List.of("winter_festival"));
            checkEq("... and nothing is forced", christmas.forced(), "");
            CalendarManager.setDateSource(DateSource.fixed(LocalDate.of(2027, 1, 7)));
            checkEq("on 7 Jan (take-down) no festival is on", CalendarManager.refresh(server).festivals().ids(), List.of());

            // saved with the world
            CalendarManager.setForcedFestival(server, "new_year");
            server.saveEverything(true, true, true);
            final Path file = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(CalendarData.NAME + ".dat");
            check("the calendar data file is written with the world", Files.exists(file), file.getFileName().toString());
            CalendarManager.setForcedFestival(server, "");
        } finally {
            CalendarManager.setDateSource(null);
        }
    }

    // ================================================================ modes: the Serene Seasons bridge

    /** The log lines the bridge wrote in this run. */
    private static List<String> bridgeLines() throws Exception {
        final Path log = Path.of("logs", "latest.log");
        final List<String> found = new ArrayList<>();
        if (Files.exists(log)) {
            for (final String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
                if (line.contains(SereneBridge.LOG_PREFIX) && !line.contains("[cscalendar]")) {
                    found.add(line);
                }
            }
        }
        return found;
    }

    /** Poll a few more times: a bridge that gave up must not try again or log again. */
    private static void pollAgain(final MinecraftServer server) {
        for (int i = 0; i < 3; i++) {
            CalendarManager.refresh(server);
        }
    }

    private void checkOwnCalendarFollowed(final MinecraftServer server, final Season startSeason) {
        final CalendarManager.Snapshot snapshot = CalendarManager.current();
        final SeasonState state = snapshot.season();
        check("the own calendar decides", state.source() == SeasonState.Source.OWN && state.season() == startSeason
                        && state.dayOfYear() == SeasonCalendar.dayOfYear(server.overworld().getDayTime(), CalendarManager.data(server).offset(), 48)
                        && state.yearLength() == 48,
                state + ", wanted " + startSeason + " of a 48-day year");
        checkEq("the payload carries the own state", snapshot.payload(),
                new SeasonStatePayload(state.season(), state.dayOfYear(), 48, snapshot.festivals().ids()));
    }

    private void runBridgeAbsent(final MinecraftServer server) throws Exception {
        check("Serene Seasons is not loaded", !ModList.get().isLoaded("sereneseasons"), "");
        pollAgain(server);
        checkOwnCalendarFollowed(server, Season.SPRING);
        check("the bridge gave up", CalendarManager.bridge().fellBack(), "");
        final List<String> lines = bridgeLines();
        check("exactly one bridge log line", lines.size() == 1, lines.size() + " line(s): " + String.join(" | ", lines));
        check("that line is INFO or WARN", lines.stream().allMatch(l -> l.contains("/INFO]") || l.contains("/WARN]")), String.join(" | ", lines));

        // a festival that the seeded config switched off cannot be forced; one that is on can
        final Collector off = run(server, 2, "colonyseasons festival force new_year");
        check("a festival switched off in the config cannot be forced", off.said("force.disabled")
                && CalendarManager.data(server).forcedFestival().isEmpty(), String.join(" | ", off.keys));
        run(server, 2, "colonyseasons festival force winter_festival");
        checkEq("a festival that is on can", CalendarManager.data(server).forcedFestival(), "winter_festival");
        run(server, 2, "colonyseasons festival clear");
    }

    private void runBridgePresent(final MinecraftServer server) throws Exception {
        check("the stand-in is loaded as sereneseasons", ModList.get().isLoaded("sereneseasons"), "");
        pollAgain(server);
        final CalendarManager.Snapshot snapshot = CalendarManager.current();
        final SeasonState state = snapshot.season();
        check("the season follows the stand-in (autumn, day 50 of 96)",
                state.source() == SeasonState.Source.SERENE && state.season() == Season.AUTUMN && state.dayOfYear() == 50 && state.yearLength() == 96,
                state.toString());
        checkEq("the payload carries the stand-in's state", snapshot.payload().yearLength() + "/" + snapshot.payload().dayOfYear() + "/" + snapshot.payload().season(),
                "96/50/AUTUMN");
        check("the bridge did not give up", !CalendarManager.bridge().fellBack(), "");
        checkEq("no bridge log line", bridgeLines().size(), 0);
        final Collector status = run(server, 0, "colonyseasons status");
        check("status names Serene Seasons as the source", status.said("status.source.serene"), String.join(" | ", status.keys));

        // season set only changes this mod's own offset; the game keeps following Serene Seasons
        final Collector set = run(server, 2, "colonyseasons season set winter");
        check("season set says Serene Seasons decides and does not claim a change", set.keys.contains("com.colony_seasons.command.season.set.serene")
                && !set.keys.contains("com.colony_seasons.command.season.set"), String.join(" | ", set.keys));
        checkEq("the season still follows the stand-in", CalendarManager.current().season().season(), Season.AUTUMN);
        checkEq("the own offset was set all the same", CalendarManager.data(server).offset(),
                SeasonCalendar.offsetFor(Season.WINTER, server.overworld().getDayTime(), 48));
        checkEq("still no bridge log line", bridgeLines().size(), 0);
    }

    private void runBridgeThrows(final MinecraftServer server) throws Exception {
        check("the stand-in is loaded as sereneseasons", ModList.get().isLoaded("sereneseasons"), "");
        pollAgain(server);
        checkOwnCalendarFollowed(server, Season.SPRING);
        check("the bridge gave up", CalendarManager.bridge().fellBack(), "");
        final List<String> lines = bridgeLines();
        check("exactly one bridge log line", lines.size() == 1, lines.size() + " line(s): " + String.join(" | ", lines));
        check("that line is WARN", lines.stream().allMatch(l -> l.contains("/WARN]") && !l.contains("/ERROR]")), String.join(" | ", lines));
        check("that line says why", lines.stream().allMatch(l -> l.contains("not ready")), String.join(" | ", lines));
        run(server, 2, "colonyseasons season set summer");
        checkEq("the own calendar stays in charge", CalendarManager.current().season().season(), Season.SUMMER);
        checkEq("and still only one line", bridgeLines().size(), 1);
    }

    private void runBridgeNonseasonal(final MinecraftServer server) throws Exception {
        check("the stand-in is loaded as sereneseasons", ModList.get().isLoaded("sereneseasons"), "");
        pollAgain(server);
        checkOwnCalendarFollowed(server, Season.SPRING);
        check("the bridge gave up", CalendarManager.bridge().fellBack(), "");
        final List<String> lines = bridgeLines();
        check("exactly one bridge log line", lines.size() == 1, lines.size() + " line(s): " + String.join(" | ", lines));
        check("it names the dimension and is INFO", lines.stream().allMatch(l -> l.contains("minecraft:overworld") && l.contains("/INFO]")),
                String.join(" | ", lines));
    }

    private void runBridgeOff(final MinecraftServer server) throws Exception {
        check("the stand-in is loaded as sereneseasons", ModList.get().isLoaded("sereneseasons"), "");
        check("calendar.useSereneSeasons is off", !SeasonsConfig.useSereneSeasons(), "");
        pollAgain(server);
        checkOwnCalendarFollowed(server, Season.SPRING);
        check("switched off is not a failure", !CalendarManager.bridge().fellBack(), "");
        checkEq("no bridge log line", bridgeLines().size(), 0);
    }

    // ================================================================ modes: restart (tools/restart.sh)

    /** The state the first run leaves behind and the second one must find: winter set on day 7, New Year forced. */
    private static final long SAVED_DAY_TIME = 7 * 24000L;
    private static final int SAVED_OFFSET = 29;

    private void runRestartSave(final MinecraftServer server) throws Exception {
        // the seeded config says the world starts in summer
        checkEq("a new world starts in the seeded startSeason", CalendarManager.current().season().season(), Season.SUMMER);
        run(server, 2, "time set " + SAVED_DAY_TIME);
        run(server, 2, "colonyseasons season set winter");
        run(server, 2, "colonyseasons festival force new_year");
        final CalendarManager.Snapshot snapshot = CalendarManager.current();
        checkEq("winter was set", snapshot.season().season(), Season.WINTER);
        checkEq("the offset is the one for day 7", CalendarManager.data(server).offset(), SAVED_OFFSET);
        checkEq("New Year is forced", CalendarManager.data(server).forcedFestival(), "new_year");
        checkEq("the forced festival is active", snapshot.festivals().ids(), List.of("new_year"));
        server.saveEverything(false, true, true);
        final Path file = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(CalendarData.NAME + ".dat");
        check("the calendar data file is on disk", Files.exists(file) && Files.size(file) > 0, file.getFileName().toString());
    }

    private void runRestartLoad(final MinecraftServer server) throws Exception {
        final CalendarData data = CalendarManager.data(server);
        checkEq("the offset came back from the world", data.offset(), SAVED_OFFSET);
        checkEq("the forced festival came back from the world", data.forcedFestival(), "new_year");
        final CalendarManager.Snapshot snapshot = CalendarManager.refresh(server);
        final long dayTime = server.overworld().getDayTime();
        final int wantedDay = (int) Math.floorMod(dayTime / 24000L + SAVED_OFFSET, 48L);
        check("the season is the saved one, not a new start", snapshot.season().season() == Season.WINTER && snapshot.season().dayOfYear() == wantedDay,
                snapshot.season() + " at day time " + dayTime + ", wanted winter day " + wantedDay);
        checkEq("the forced festival is active again", snapshot.festivals().ids().contains("new_year"), true);
        checkEq("the clients would be told the same", snapshot.payload().festivals().contains("new_year"), true);
    }
}
