package me.lovkar.seasons.csconfig;

import me.lovkar.seasons.ColonySeasons;
import me.lovkar.seasons.SeasonsConfig;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Headless check of Colony Seasons' server config on the dedicated test server.
 *
 * <p>Two runs. {@code -Mode seeded}: the rig seeds this probe's {@code rigconfig/colony_seasons-server.toml}
 * (a non-default value for every key) and every getter must return that value. {@code -Mode defaults}:
 * nothing is seeded ({@code rigconfig-defaults/} holds no .toml), NeoForge writes the file from the mod's
 * defaults, and every getter must return the defaults of design section 9, written out here by hand so
 * a wrong default in the mod cannot agree with itself. Both modes also check the validators.</p>
 *
 * <p>Prints "[csconfig] CHECK ..." lines and "[csconfig] RESULT csconfig: PASS|FAIL", then halts.</p>
 */
@Mod("colonyseasonsconfig")
public class CsConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("csconfig");
    private int ticks = 0;
    private boolean failed = false;

    /** The five keys of one festival. */
    private record Festival(boolean enabled, String decorFrom, String decorUntil, String daysFrom, String daysUntil) {
    }

    /** What the getters must return in one run. */
    private record Expected(int yearLengthDays, String startSeason, boolean useSereneSeasons, String timeZone,
                            Festival winter, Festival newYear,
                            boolean needsMaterials, int buildingsPerDayBase, int buildingsPerDayPerLevel, int maxPerSide,
                            int gatherFromTick, int gatherUntilTick, String whoComes, boolean midnightShow, int giftsPerPlayerPerDay,
                            boolean festivalHappiness, double festivalHappinessBonus,
                            boolean winterCropSlowdown, double winterCropSkipChance,
                            boolean winterFoodNeed, double winterFoodNeedFactor) {
    }

    /** Design section 9, by hand: the numbers the mod must start with. */
    private static final Expected DEFAULTS = new Expected(
            48, "auto", true, "system",
            new Festival(true, "12-01", "01-06", "12-24", "12-26"),
            new Festival(true, "12-31", "01-01", "12-31", "01-01"),
            true, 2, 2, 6,
            8500, 10500, "everyone_once_a_day", true, 1,
            false, 1.0, false, 0.5, false, 1.25);

    /** What rigconfig/colony_seasons-server.toml seeds: a non-default value for every key. */
    private static final Expected SEEDED = new Expected(
            96, "winter", false, "Europe/Paris",
            new Festival(false, "11-15", "01-10", "12-20", "12-28"),
            new Festival(false, "12-30", "01-02", "12-30", "01-02"),
            false, 5, 3, 9,
            7000, 9000, "off_duty", false, 3,
            true, 2.5, true, 0.25, true, 1.5);

    public CsConfig() {
        NeoForge.EVENT_BUS.addListener(this::onTick);
    }

    private void check(final String what, final boolean ok, final String detail) {
        LOGGER.info("[csconfig] CHECK {}: {} {}", what, ok ? "OK" : "FAIL", detail);
        failed |= !ok;
    }

    private void onTick(final ServerTickEvent.Post event) {
        if (++ticks != 100) {
            return;
        }
        final MinecraftServer server = event.getServer();
        try {
            final String mode = readMode();
            final boolean defaults = "defaults".equals(mode);
            check("mode is defaults or seeded", defaults || "seeded".equals(mode), "'" + mode + "'");
            LOGGER.info("[csconfig] mode '{}': expecting the {}", mode, defaults ? "mod defaults" : "seeded rigconfig values");
            checkLoaded();
            checkConfig(defaults ? DEFAULTS : SEEDED);
            if (defaults) {
                checkDefaultConstants();
            }
            checkValidators();
        } catch (final Throwable t) {
            check("probe ran to the end", false, t.toString());
        }
        LOGGER.info("[csconfig] RESULT csconfig: {}", failed ? "FAIL" : "PASS");
        server.halt(false);
    }

    /** The mode word the rig wrote next to the server (empty without -Mode). */
    private static String readMode() throws Exception {
        final Path file = Path.of("probe-mode.txt");
        return Files.exists(file) ? Files.readString(file, StandardCharsets.US_ASCII).trim() : "";
    }

    private void checkLoaded() {
        check("config loaded", SeasonsConfig.SPEC.isLoaded(), "colony_seasons-server.toml is the live SERVER config");
        final Path file = Path.of("config", ColonySeasons.SERVER_CONFIG_FILE);
        check("config file", Files.exists(file), file.toString());
    }

    // ---------------------------------------------------------------- every getter

    private void checkConfig(final Expected want) {
        checkEq("yearLengthDays", SeasonsConfig.yearLengthDays(), want.yearLengthDays());
        checkEq("startSeason", SeasonsConfig.startSeason(), want.startSeason());
        checkEq("useSereneSeasons", SeasonsConfig.useSereneSeasons(), want.useSereneSeasons());
        checkEq("timeZone", SeasonsConfig.timeZone(), want.timeZone());

        checkFestival(SeasonsConfig.WINTER_FESTIVAL, want.winter());
        checkFestival(SeasonsConfig.NEW_YEAR, want.newYear());
        checkEq("festival ids", SeasonsConfig.FESTIVAL_IDS, List.of("winter_festival", "new_year"));

        checkEq("needsMaterials", SeasonsConfig.needsMaterials(), want.needsMaterials());
        checkEq("buildingsPerDayBase", SeasonsConfig.buildingsPerDayBase(), want.buildingsPerDayBase());
        checkEq("buildingsPerDayPerLevel", SeasonsConfig.buildingsPerDayPerLevel(), want.buildingsPerDayPerLevel());
        checkEq("maxPerSide", SeasonsConfig.maxPerSide(), want.maxPerSide());

        checkEq("gatherFromTick", SeasonsConfig.gatherFromTick(), want.gatherFromTick());
        checkEq("gatherUntilTick", SeasonsConfig.gatherUntilTick(), want.gatherUntilTick());
        checkEq("whoComes", SeasonsConfig.whoComes(), want.whoComes());
        checkEq("midnightShow", SeasonsConfig.midnightShow(), want.midnightShow());
        checkEq("giftsPerPlayerPerDay", SeasonsConfig.giftsPerPlayerPerDay(), want.giftsPerPlayerPerDay());

        checkEq("festivalHappiness", SeasonsConfig.festivalHappiness(), want.festivalHappiness());
        checkNear("festivalHappinessBonus", SeasonsConfig.festivalHappinessBonus(), want.festivalHappinessBonus());
        checkEq("winterCropSlowdown", SeasonsConfig.winterCropSlowdown(), want.winterCropSlowdown());
        checkNear("winterCropSkipChance", SeasonsConfig.winterCropSkipChance(), want.winterCropSkipChance());
        checkEq("winterFoodNeed", SeasonsConfig.winterFoodNeed(), want.winterFoodNeed());
        checkNear("winterFoodNeedFactor", SeasonsConfig.winterFoodNeedFactor(), want.winterFoodNeedFactor());
    }

    private void checkFestival(final String id, final Festival want) {
        checkEq(id + ".enabled", SeasonsConfig.festivalEnabled(id), want.enabled());
        checkEq(id + ".decorFrom", SeasonsConfig.decorFrom(id), want.decorFrom());
        checkEq(id + ".decorUntil", SeasonsConfig.decorUntil(id), want.decorUntil());
        checkEq(id + ".daysFrom", SeasonsConfig.daysFrom(id), want.daysFrom());
        checkEq(id + ".daysUntil", SeasonsConfig.daysUntil(id), want.daysUntil());
    }

    /** The class constants the README documents must be the same numbers as the hand-written list. */
    private void checkDefaultConstants() {
        final Expected d = DEFAULTS;
        final boolean same = SeasonsConfig.DEFAULT_YEAR_LENGTH_DAYS == d.yearLengthDays()
                && SeasonsConfig.DEFAULT_START_SEASON.equals(d.startSeason())
                && SeasonsConfig.DEFAULT_USE_SERENE_SEASONS == d.useSereneSeasons()
                && SeasonsConfig.DEFAULT_TIME_ZONE.equals(d.timeZone())
                && SeasonsConfig.DEFAULT_FESTIVAL_ENABLED == d.winter().enabled()
                && SeasonsConfig.DEFAULT_WINTER_DECOR_FROM.equals(d.winter().decorFrom())
                && SeasonsConfig.DEFAULT_WINTER_DECOR_UNTIL.equals(d.winter().decorUntil())
                && SeasonsConfig.DEFAULT_WINTER_DAYS_FROM.equals(d.winter().daysFrom())
                && SeasonsConfig.DEFAULT_WINTER_DAYS_UNTIL.equals(d.winter().daysUntil())
                && SeasonsConfig.DEFAULT_NEW_YEAR_DECOR_FROM.equals(d.newYear().decorFrom())
                && SeasonsConfig.DEFAULT_NEW_YEAR_DECOR_UNTIL.equals(d.newYear().decorUntil())
                && SeasonsConfig.DEFAULT_NEW_YEAR_DAYS_FROM.equals(d.newYear().daysFrom())
                && SeasonsConfig.DEFAULT_NEW_YEAR_DAYS_UNTIL.equals(d.newYear().daysUntil())
                && SeasonsConfig.DEFAULT_NEEDS_MATERIALS == d.needsMaterials()
                && SeasonsConfig.DEFAULT_BUILDINGS_PER_DAY_BASE == d.buildingsPerDayBase()
                && SeasonsConfig.DEFAULT_BUILDINGS_PER_DAY_PER_LEVEL == d.buildingsPerDayPerLevel()
                && SeasonsConfig.DEFAULT_MAX_PER_SIDE == d.maxPerSide()
                && SeasonsConfig.DEFAULT_GATHER_FROM_TICK == d.gatherFromTick()
                && SeasonsConfig.DEFAULT_GATHER_UNTIL_TICK == d.gatherUntilTick()
                && SeasonsConfig.DEFAULT_WHO_COMES.equals(d.whoComes())
                && SeasonsConfig.DEFAULT_MIDNIGHT_SHOW == d.midnightShow()
                && SeasonsConfig.DEFAULT_GIFTS_PER_PLAYER_PER_DAY == d.giftsPerPlayerPerDay()
                && SeasonsConfig.DEFAULT_FESTIVAL_HAPPINESS == d.festivalHappiness()
                && SeasonsConfig.DEFAULT_FESTIVAL_HAPPINESS_BONUS == d.festivalHappinessBonus()
                && SeasonsConfig.DEFAULT_WINTER_CROP_SLOWDOWN == d.winterCropSlowdown()
                && SeasonsConfig.DEFAULT_WINTER_CROP_SKIP_CHANCE == d.winterCropSkipChance()
                && SeasonsConfig.DEFAULT_WINTER_FOOD_NEED == d.winterFoodNeed()
                && SeasonsConfig.DEFAULT_WINTER_FOOD_NEED_FACTOR == d.winterFoodNeedFactor();
        check("documented defaults", same, "SeasonsConfig.DEFAULT_*");
        check("all effects off by default", !SeasonsConfig.festivalHappiness() && !SeasonsConfig.winterCropSlowdown()
                && !SeasonsConfig.winterFoodNeed(), "design section 8");
    }

    // ---------------------------------------------------------------- the validators

    private void checkValidators() {
        check("month-day accepts 12-24", SeasonsConfig.isMonthDay("12-24"), "");
        check("month-day accepts 02-29", SeasonsConfig.isMonthDay("02-29"), "a day that exists in a leap year");
        check("month-day refuses 02-30", !SeasonsConfig.isMonthDay("02-30"), "");
        check("month-day refuses 13-01", !SeasonsConfig.isMonthDay("13-01"), "");
        check("month-day refuses 1-5", !SeasonsConfig.isMonthDay("1-5"), "");
        check("month-day refuses 2026-12-24", !SeasonsConfig.isMonthDay("2026-12-24"), "");
        check("month-day refuses a number", !SeasonsConfig.isMonthDay(1224), "");
        check("year length accepts 4, 48, 960", SeasonsConfig.isYearLength(4) && SeasonsConfig.isYearLength(48)
                && SeasonsConfig.isYearLength(960), "");
        check("year length refuses 3, 50, 964, 0", !SeasonsConfig.isYearLength(3) && !SeasonsConfig.isYearLength(50)
                && !SeasonsConfig.isYearLength(964) && !SeasonsConfig.isYearLength(0), "");
        check("time zone accepts system and a zone id", SeasonsConfig.isTimeZone("system")
                && SeasonsConfig.isTimeZone("Europe/Berlin"), "");
        check("time zone refuses a made-up id", !SeasonsConfig.isTimeZone("Mars/Olympus_Mons") && !SeasonsConfig.isTimeZone(""), "");
        check("start seasons", SeasonsConfig.START_SEASONS.equals(List.of("auto", "spring", "summer", "autumn", "winter")),
                SeasonsConfig.START_SEASONS.toString());
        check("who comes", SeasonsConfig.WHO_COMES.equals(List.of("everyone_once_a_day", "off_duty", "everyone")),
                SeasonsConfig.WHO_COMES.toString());
        boolean unknownRefused = false;
        try {
            SeasonsConfig.decorFrom("spring_bloom");
        } catch (final IllegalArgumentException expected) {
            unknownRefused = true;
        }
        check("unknown festival id is refused", unknownRefused, "spring_bloom is not in 0.1.0");
    }

    // ---------------------------------------------------------------- helpers

    private void checkEq(final String what, final Object got, final Object want) {
        check(what, Objects.equals(got, want), "got " + got + ", want " + want);
    }

    private void checkNear(final String what, final double got, final double want) {
        check(what, Math.abs(got - want) < 1e-9, "got " + got + ", want " + want);
    }
}
