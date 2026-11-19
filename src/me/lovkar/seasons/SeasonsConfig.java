package me.lovkar.seasons;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.time.DateTimeException;
import java.time.MonthDay;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The server config, {@code colony_seasons-server.toml}: what a server owner can tune about the
 * calendar, the festivals, the Decorator, the festival events and the (all off by default) effects
 * on the game.
 *
 * <p>It is a SERVER config on purpose: the numbers decide what happens in the world, so they belong
 * to the world's owner and NeoForge sends them to every client that joins.</p>
 *
 * <p>The getters are safe to call at any time. A SERVER config only exists while a world is
 * running; before that (the title screen, a client tooltip, a probe at the wrong moment) NeoForge
 * would throw, and a calendar rule is better served by its default than by a crash. The defaults are
 * named {@code DEFAULT_*} constants so the README, the spec and the config probe cannot drift apart.</p>
 */
public final class SeasonsConfig {

    // ---------------------------------------------------------------- the defaults, named once

    // calendar
    public static final int DEFAULT_YEAR_LENGTH_DAYS = 48;
    public static final String DEFAULT_START_SEASON = "auto";
    public static final boolean DEFAULT_USE_SERENE_SEASONS = true;
    public static final String DEFAULT_TIME_ZONE = "system";

    // festivals: every festival has the same five keys; the dates are month-day strings (MM-DD)
    public static final String WINTER_FESTIVAL = "winter_festival";
    public static final String NEW_YEAR = "new_year";
    /** The festivals of 0.1.0, in the order of the config file. */
    public static final List<String> FESTIVAL_IDS = List.of(WINTER_FESTIVAL, NEW_YEAR);
    public static final boolean DEFAULT_FESTIVAL_ENABLED = true;
    /** Winter Festival: decorations go up on 1 Dec and stay to 6 Jan (take-down starts on 7 Jan); the festival days are 24 to 26 Dec. */
    public static final String DEFAULT_WINTER_DECOR_FROM = "12-01";
    public static final String DEFAULT_WINTER_DECOR_UNTIL = "01-06";
    public static final String DEFAULT_WINTER_DAYS_FROM = "12-24";
    public static final String DEFAULT_WINTER_DAYS_UNTIL = "12-26";
    /** New Year: 31 Dec to 1 Jan. */
    public static final String DEFAULT_NEW_YEAR_DECOR_FROM = "12-31";
    public static final String DEFAULT_NEW_YEAR_DECOR_UNTIL = "01-01";
    public static final String DEFAULT_NEW_YEAR_DAYS_FROM = "12-31";
    public static final String DEFAULT_NEW_YEAR_DAYS_UNTIL = "01-01";

    // decorator
    public static final boolean DEFAULT_NEEDS_MATERIALS = true;
    public static final int DEFAULT_BUILDINGS_PER_DAY_BASE = 2;
    public static final int DEFAULT_BUILDINGS_PER_DAY_PER_LEVEL = 2;
    public static final int DEFAULT_MAX_PER_SIDE = 6;

    // events
    public static final int DEFAULT_GATHER_FROM_TICK = 8500;
    public static final int DEFAULT_GATHER_UNTIL_TICK = 10500;
    public static final String DEFAULT_WHO_COMES = "everyone_once_a_day";
    public static final boolean DEFAULT_MIDNIGHT_SHOW = true;
    public static final int DEFAULT_GIFTS_PER_PLAYER_PER_DAY = 1;

    // effects (every switch off)
    public static final boolean DEFAULT_FESTIVAL_HAPPINESS = false;
    /** Strength of the festival-day happiness bonus, in happiness points (MineColonies shows 0 to 10). */
    public static final double DEFAULT_FESTIVAL_HAPPINESS_BONUS = 1.0;
    public static final boolean DEFAULT_WINTER_CROP_SLOWDOWN = false;
    /** Chance that a crop growth step is skipped in winter: 0.5 is "half their growth steps". */
    public static final double DEFAULT_WINTER_CROP_SKIP_CHANCE = 0.5;
    public static final boolean DEFAULT_WINTER_FOOD_NEED = false;
    /** Food used in winter compared with usual: 1.25 is "about a quarter more". */
    public static final double DEFAULT_WINTER_FOOD_NEED_FACTOR = 1.25;

    /** The values the enumerated keys accept. */
    public static final List<String> START_SEASONS = List.of("auto", "spring", "summer", "autumn", "winter");
    public static final List<String> WHO_COMES = List.of("everyone_once_a_day", "off_duty", "everyone");

    // ---------------------------------------------------------------- the spec

    /** Declared before the spec: the validators run while the spec is being built. */
    private static final Pattern MONTH_DAY = Pattern.compile("\\d{2}-\\d{2}");

    public static final ModConfigSpec SPEC;

    private static final ModConfigSpec.ConfigValue<Integer> YEAR_LENGTH_DAYS;
    private static final ModConfigSpec.ConfigValue<String> START_SEASON;
    private static final ModConfigSpec.BooleanValue USE_SERENE_SEASONS;
    private static final ModConfigSpec.ConfigValue<String> TIME_ZONE;

    /** The five keys of one festival. */
    private record FestivalKeys(ModConfigSpec.BooleanValue enabled, ModConfigSpec.ConfigValue<String> decorFrom,
                                ModConfigSpec.ConfigValue<String> decorUntil, ModConfigSpec.ConfigValue<String> daysFrom,
                                ModConfigSpec.ConfigValue<String> daysUntil) {
    }

    private static final Map<String, FestivalKeys> FESTIVALS = new LinkedHashMap<>();

    private static final ModConfigSpec.BooleanValue NEEDS_MATERIALS;
    private static final ModConfigSpec.IntValue BUILDINGS_PER_DAY_BASE;
    private static final ModConfigSpec.IntValue BUILDINGS_PER_DAY_PER_LEVEL;
    private static final ModConfigSpec.IntValue MAX_PER_SIDE;

    private static final ModConfigSpec.IntValue GATHER_FROM_TICK;
    private static final ModConfigSpec.IntValue GATHER_UNTIL_TICK;
    private static final ModConfigSpec.ConfigValue<String> WHO_COMES_VALUE;
    private static final ModConfigSpec.BooleanValue MIDNIGHT_SHOW;
    private static final ModConfigSpec.IntValue GIFTS_PER_PLAYER_PER_DAY;

    private static final ModConfigSpec.BooleanValue FESTIVAL_HAPPINESS;
    private static final ModConfigSpec.DoubleValue FESTIVAL_HAPPINESS_BONUS;
    private static final ModConfigSpec.BooleanValue WINTER_CROP_SLOWDOWN;
    private static final ModConfigSpec.DoubleValue WINTER_CROP_SKIP_CHANCE;
    private static final ModConfigSpec.BooleanValue WINTER_FOOD_NEED;
    private static final ModConfigSpec.DoubleValue WINTER_FOOD_NEED_FACTOR;

    static {
        final ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("The calendar: how the seasons run in the game.").push("calendar");
        YEAR_LENGTH_DAYS = builder
                .comment("Game days in a year: 4 to 960, a multiple of 4 (a quarter of it is one season).",
                        "48 means twelve days a season, about four hours of play for the whole year.")
                .define("yearLengthDays", DEFAULT_YEAR_LENGTH_DAYS, SeasonsConfig::isYearLength);
        START_SEASON = builder
                .comment("The season a new world starts in: auto, spring, summer, autumn or winter.",
                        "auto picks the season of the real date (northern hemisphere), so a colony founded in",
                        "December starts in winter. Only used until the calendar of a world has been created.")
                .define("startSeason", DEFAULT_START_SEASON, value -> isOneOf(START_SEASONS, value));
        USE_SERENE_SEASONS = builder
                .comment("true: follow the Serene Seasons calendar when that mod is installed.",
                        "false: always use this mod's own calendar.")
                .define("useSereneSeasons", DEFAULT_USE_SERENE_SEASONS);
        TIME_ZONE = builder
                .comment("The time zone the festival dates are read in: system (the server's own clock zone)",
                        "or a zone id such as Europe/Ljubljana or America/New_York. For hosted servers whose",
                        "clock is in another zone than their players.")
                .define("timeZone", DEFAULT_TIME_ZONE, SeasonsConfig::isTimeZone);
        builder.pop();

        builder.comment("The festivals. Dates are month-day strings, MM-DD, read in calendar.timeZone.",
                "decorFrom to decorUntil: the Decorator's window, decorations go up on the first day and stay",
                "to the last; the take-down starts the day after decorUntil.",
                "daysFrom to daysUntil: the festival days themselves (the gathering, the better gift, the show).",
                "A window may cross the new year (12-24 to 01-06).").push("festivals");
        FESTIVALS.put(WINTER_FESTIVAL, festival(builder, WINTER_FESTIVAL, "Winter Festival: decorations from December, the festival days around the 25th.",
                DEFAULT_WINTER_DECOR_FROM, DEFAULT_WINTER_DECOR_UNTIL, DEFAULT_WINTER_DAYS_FROM, DEFAULT_WINTER_DAYS_UNTIL));
        FESTIVALS.put(NEW_YEAR, festival(builder, NEW_YEAR, "New Year: fireworks at dusk, the midnight show.",
                DEFAULT_NEW_YEAR_DECOR_FROM, DEFAULT_NEW_YEAR_DECOR_UNTIL, DEFAULT_NEW_YEAR_DAYS_FROM, DEFAULT_NEW_YEAR_DAYS_UNTIL));
        builder.pop();

        builder.comment("The Decorator, the colony worker who decorates the buildings.").push("decorator");
        NEEDS_MATERIALS = builder
                .comment("true: the Decorator asks the colony for the materials (wool, lanterns, ...) before he decorates.",
                        "false: decorations are free.")
                .define("needsMaterials", DEFAULT_NEEDS_MATERIALS);
        BUILDINGS_PER_DAY_BASE = builder
                .comment("Buildings decorated (or cleared) per game day by a Decorator hut of level 0.")
                .defineInRange("buildingsPerDayBase", DEFAULT_BUILDINGS_PER_DAY_BASE, 1, 100);
        BUILDINGS_PER_DAY_PER_LEVEL = builder
                .comment("Buildings per game day added for each level of the Decorator's hut.")
                .defineInRange("buildingsPerDayPerLevel", DEFAULT_BUILDINGS_PER_DAY_PER_LEVEL, 0, 100);
        MAX_PER_SIDE = builder
                .comment("The most decorations on one side of one building.")
                .defineInRange("maxPerSide", DEFAULT_MAX_PER_SIDE, 1, 64);
        builder.pop();

        builder.comment("Festival events: the gathering at the tree, gifts and fireworks.").push("events");
        GATHER_FROM_TICK = builder
                .comment("Game tick of the day (0 to 23999) at which the colonists start gathering on festival days.",
                        "8500 is 14:30 on the game clock.")
                .defineInRange("gatherFromTick", DEFAULT_GATHER_FROM_TICK, 0, 23999);
        GATHER_UNTIL_TICK = builder
                .comment("Game tick of the day (0 to 23999) at which the gathering ends. Colonists go to bed at 10600,",
                        "so keep it below that. It must be later than gatherFromTick.")
                .defineInRange("gatherUntilTick", DEFAULT_GATHER_UNTIL_TICK, 0, 23999);
        WHO_COMES_VALUE = builder
                .comment("Who comes to the gathering: everyone_once_a_day (all colonists, work stops for the festival hours of one",
                        "game day per real day, off-duty colonists on the other days), off_duty (only colonists who are",
                        "off duty) or everyone (all colonists on every festival game day).")
                .define("whoComes", DEFAULT_WHO_COMES, value -> isOneOf(WHO_COMES, value));
        MIDNIGHT_SHOW = builder
                .comment("true: if the server runs through real midnight on New Year's Eve, a countdown and the big fireworks show.")
                .define("midnightShow", DEFAULT_MIDNIGHT_SHOW);
        GIFTS_PER_PLAYER_PER_DAY = builder
                .comment("Gifts a player can pick up per real day in the festival season. 0 turns the gifts off.")
                .defineInRange("giftsPerPlayerPerDay", DEFAULT_GIFTS_PER_PLAYER_PER_DAY, 0, 10);
        builder.pop();

        builder.comment("Effects on the game. All off by default: with every switch off the mod changes nothing",
                "but looks, sounds, the Decorator's use of materials and the gifts.").push("effects");
        FESTIVAL_HAPPINESS = builder
                .comment("true: on festival days every colonist gets a happiness bonus.")
                .define("festivalHappiness", DEFAULT_FESTIVAL_HAPPINESS);
        FESTIVAL_HAPPINESS_BONUS = builder
                .comment("How big that bonus is, in happiness points. Only used while festivalHappiness is on.")
                .defineInRange("festivalHappinessBonus", DEFAULT_FESTIVAL_HAPPINESS_BONUS, 0.0, 5.0);
        WINTER_CROP_SLOWDOWN = builder
                .comment("true: in in-game winter, crops inside colony claims skip some of their growth steps.")
                .define("winterCropSlowdown", DEFAULT_WINTER_CROP_SLOWDOWN);
        WINTER_CROP_SKIP_CHANCE = builder
                .comment("The chance that a growth step is skipped in winter (0.5 is half of them). Only used while",
                        "winterCropSlowdown is on.")
                .defineInRange("winterCropSkipChance", DEFAULT_WINTER_CROP_SKIP_CHANCE, 0.0, 1.0);
        WINTER_FOOD_NEED = builder
                .comment("true: in in-game winter, colonists use more food.")
                .define("winterFoodNeed", DEFAULT_WINTER_FOOD_NEED);
        WINTER_FOOD_NEED_FACTOR = builder
                .comment("How much food colonists use in winter compared with usual (1.25 is about a quarter more).",
                        "Only used while winterFoodNeed is on.")
                .defineInRange("winterFoodNeedFactor", DEFAULT_WINTER_FOOD_NEED_FACTOR, 1.0, 3.0);
        builder.pop();

        SPEC = builder.build();
    }

    private SeasonsConfig() {
    }

    /** Defines the five keys of one festival under {@code festivals.<id>}. */
    private static FestivalKeys festival(final ModConfigSpec.Builder builder, final String id, final String about,
                                         final String decorFrom, final String decorUntil, final String daysFrom, final String daysUntil) {
        builder.comment(about).push(id);
        final ModConfigSpec.BooleanValue enabled = builder
                .comment("false: this festival does not happen at all.")
                .define("enabled", DEFAULT_FESTIVAL_ENABLED);
        final ModConfigSpec.ConfigValue<String> from = builder
                .comment("First day of decorations, MM-DD.")
                .define("decorFrom", decorFrom, SeasonsConfig::isMonthDay);
        final ModConfigSpec.ConfigValue<String> until = builder
                .comment("Last day of decorations, MM-DD. The take-down starts the day after.")
                .define("decorUntil", decorUntil, SeasonsConfig::isMonthDay);
        final ModConfigSpec.ConfigValue<String> dFrom = builder
                .comment("First festival day, MM-DD.")
                .define("daysFrom", daysFrom, SeasonsConfig::isMonthDay);
        final ModConfigSpec.ConfigValue<String> dUntil = builder
                .comment("Last festival day, MM-DD.")
                .define("daysUntil", daysUntil, SeasonsConfig::isMonthDay);
        builder.pop();
        return new FestivalKeys(enabled, from, until, dFrom, dUntil);
    }

    // ---------------------------------------------------------------- validators

    /** A month-day string the file may hold: MM-DD naming a real day (02-29 counts). */
    public static boolean isMonthDay(final Object value) {
        if (!(value instanceof String text) || !MONTH_DAY.matcher(text).matches()) {
            return false;
        }
        try {
            MonthDay.of(Integer.parseInt(text.substring(0, 2)), Integer.parseInt(text.substring(3, 5)));
            return true;
        } catch (final DateTimeException invalid) {
            return false;
        }
    }

    /**
     * A value of an enumerated key. Not defineInList: it asks an immutable list whether it contains
     * null when the key is missing from the file, and List.of throws on that instead of answering no.
     */
    private static boolean isOneOf(final List<String> allowed, final Object value) {
        return value instanceof String text && allowed.contains(text);
    }

    /** A year length the file may hold: 4 to 960 and a multiple of 4. */
    public static boolean isYearLength(final Object value) {
        return value instanceof Integer days && days >= 4 && days <= 960 && days % 4 == 0;
    }

    /** A time zone the file may hold: {@code system} or an id the JVM knows. */
    public static boolean isTimeZone(final Object value) {
        if (!(value instanceof String id)) {
            return false;
        }
        if (DEFAULT_TIME_ZONE.equals(id)) {
            return true;
        }
        try {
            ZoneId.of(id);
            return true;
        } catch (final DateTimeException unknown) {
            return false;
        }
    }

    // ---------------------------------------------------------------- the getters

    /** The value while a world's config is loaded, the default otherwise (see the class comment). */
    private static <T> T read(final ModConfigSpec.ConfigValue<T> value) {
        if (!SPEC.isLoaded()) {
            return value.getDefault();
        }
        try {
            return value.get();
        } catch (final IllegalStateException notLoaded) {
            // unloaded between the check and the read (a world closing): the default will do
            return value.getDefault();
        }
    }

    private static FestivalKeys festival(final String id) {
        final FestivalKeys keys = FESTIVALS.get(id);
        if (keys == null) {
            throw new IllegalArgumentException("unknown festival '" + id + "', known: " + FESTIVAL_IDS);
        }
        return keys;
    }

    // calendar

    /** Game days in a year (4 to 960, a multiple of 4). */
    public static int yearLengthDays() {
        return read(YEAR_LENGTH_DAYS);
    }

    /** One of {@link #START_SEASONS}. */
    public static String startSeason() {
        return read(START_SEASON);
    }

    public static boolean useSereneSeasons() {
        return read(USE_SERENE_SEASONS);
    }

    /** {@code system} or a zone id. */
    public static String timeZone() {
        return read(TIME_ZONE);
    }

    // festivals (id is one of FESTIVAL_IDS)

    public static boolean festivalEnabled(final String id) {
        return read(festival(id).enabled());
    }

    /** First day of decorations, MM-DD. */
    public static String decorFrom(final String id) {
        return read(festival(id).decorFrom());
    }

    /** Last day of decorations, MM-DD. */
    public static String decorUntil(final String id) {
        return read(festival(id).decorUntil());
    }

    /** First festival day, MM-DD. */
    public static String daysFrom(final String id) {
        return read(festival(id).daysFrom());
    }

    /** Last festival day, MM-DD. */
    public static String daysUntil(final String id) {
        return read(festival(id).daysUntil());
    }

    // decorator

    public static boolean needsMaterials() {
        return read(NEEDS_MATERIALS);
    }

    public static int buildingsPerDayBase() {
        return read(BUILDINGS_PER_DAY_BASE);
    }

    public static int buildingsPerDayPerLevel() {
        return read(BUILDINGS_PER_DAY_PER_LEVEL);
    }

    public static int maxPerSide() {
        return read(MAX_PER_SIDE);
    }

    // events

    public static int gatherFromTick() {
        return read(GATHER_FROM_TICK);
    }

    public static int gatherUntilTick() {
        return read(GATHER_UNTIL_TICK);
    }

    /** One of {@link #WHO_COMES}. */
    public static String whoComes() {
        return read(WHO_COMES_VALUE);
    }

    public static boolean midnightShow() {
        return read(MIDNIGHT_SHOW);
    }

    public static int giftsPerPlayerPerDay() {
        return read(GIFTS_PER_PLAYER_PER_DAY);
    }

    // effects

    public static boolean festivalHappiness() {
        return read(FESTIVAL_HAPPINESS);
    }

    public static double festivalHappinessBonus() {
        return read(FESTIVAL_HAPPINESS_BONUS);
    }

    public static boolean winterCropSlowdown() {
        return read(WINTER_CROP_SLOWDOWN);
    }

    public static double winterCropSkipChance() {
        return read(WINTER_CROP_SKIP_CHANCE);
    }

    public static boolean winterFoodNeed() {
        return read(WINTER_FOOD_NEED);
    }

    public static double winterFoodNeedFactor() {
        return read(WINTER_FOOD_NEED_FACTOR);
    }
}
