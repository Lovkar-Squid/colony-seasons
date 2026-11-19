package me.lovkar.seasons.calendar;

import me.lovkar.seasons.ColonySeasons;
import me.lovkar.seasons.SeasonsConfig;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Reads the season from Serene Seasons when that mod is installed, by reflection only: nothing here is compiled
 * against it, and a different or broken Serene Seasons must never crash this mod.
 *
 * <p>The calls are {@code SeasonHelper.getSeasonState(Level)} and then {@code getSeason()}, {@code getDay()} and
 * {@code getCycleDuration() / getDayDuration()}, looked up on the API interface, not the implementation. The caller
 * polls (every 20 ticks): Serene Seasons' change event lives on GlitchCore's bus, which reflection cannot join.</p>
 *
 * <p>Any failure, an API that is missing, throws or answers nonsense, and a dimension Serene Seasons does not run
 * seasons in (it then returns a frozen default and no failure) end the bridge for the rest of the session: {@link #read}
 * returns {@code null} from then on, which means "use the calendar of this mod", and exactly one line is logged, at INFO
 * or WARN, never at error level. {@code calendar.useSereneSeasons = false} is not a failure and logs nothing.</p>
 */
public final class SereneBridge {
    public static final String MOD_ID = "sereneseasons";
    /** Every log line of the bridge starts with this, so a test can count them. */
    public static final String LOG_PREFIX = "Serene bridge: ";

    private static final Logger LOGGER = ColonySeasons.LOGGER;

    private final BooleanSupplier wanted;
    private final BooleanSupplier installed;
    private boolean fellBack;
    private Api api;

    /** The API as found by reflection; the whitelist parts are optional (an API stub may not have a config). */
    private record Api(Method getSeasonState, Method getSeason, Method getDay, Method getCycleDuration, Method getDayDuration,
                       Field configField, Field whitelistField) {
    }

    public SereneBridge() {
        this(SeasonsConfig::useSereneSeasons, () -> ModList.get().isLoaded(MOD_ID));
    }

    /** For tests: what the two answers are is theirs to decide. */
    public SereneBridge(final BooleanSupplier wanted, final BooleanSupplier installed) {
        this.wanted = wanted;
        this.installed = installed;
    }

    /** True once the bridge gave up for this session. */
    public boolean fellBack() {
        return fellBack;
    }

    /**
     * The season Serene Seasons reports for this level, or {@code null} for "use the calendar of this mod"
     * (the config says so, Serene Seasons is not installed, or the bridge has fallen back).
     */
    public SeasonState read(final Level level) {
        if (fellBack || !wanted.getAsBoolean()) {
            return null;
        }
        if (!installed.getAsBoolean()) {
            fallBack("Serene Seasons is not installed, using the calendar of Colony Seasons", null, false);
            return null;
        }
        try {
            if (api == null) {
                api = lookUp();
            }
            if (!isSeasonal(level)) {
                fallBack("Serene Seasons runs no seasons in " + level.dimension().location()
                        + ", using the calendar of Colony Seasons from now on", null, false);
                return null;
            }
            final Object state = api.getSeasonState().invoke(null, level);
            if (state == null) {
                fallBack("Serene Seasons gave no season state, using the calendar of Colony Seasons from now on", null, true);
                return null;
            }
            final int dayDuration = Math.max(1, (Integer) api.getDayDuration().invoke(state));
            final int yearLength = (Integer) api.getCycleDuration().invoke(state) / dayDuration;
            final Season season = Season.byOrdinal(((Enum<?>) api.getSeason().invoke(state)).ordinal());
            final int day = (Integer) api.getDay().invoke(state);
            if (season == null || yearLength <= 0) {
                fallBack("Serene Seasons gave a season state this mod cannot use (year of " + yearLength
                        + " days), using the calendar of Colony Seasons from now on", null, true);
                return null;
            }
            return new SeasonState(season, Math.floorMod(day, yearLength), yearLength, SeasonState.Source.SERENE);
        } catch (final InvocationTargetException failed) {
            fallBack("reading Serene Seasons failed (" + describe(failed.getCause() != null ? failed.getCause() : failed)
                    + "), using the calendar of Colony Seasons from now on", failed, true);
            return null;
        } catch (final Throwable failed) {
            // a missing class or method, a LinkageError, a NullPointer from an API that is not ready yet
            fallBack("reading Serene Seasons failed (" + describe(failed)
                    + "), using the calendar of Colony Seasons from now on", failed, true);
            return null;
        }
    }

    private static Api lookUp() throws ReflectiveOperationException {
        final ClassLoader loader = SereneBridge.class.getClassLoader();
        final Class<?> helper = Class.forName("sereneseasons.api.season.SeasonHelper", false, loader);
        final Class<?> state = Class.forName("sereneseasons.api.season.ISeasonState", false, loader);
        Field config = null;
        Field whitelist = null;
        try {
            config = Class.forName("sereneseasons.init.ModConfig", false, loader).getField("seasons");
            whitelist = Class.forName("sereneseasons.config.SeasonsConfig", false, loader).getField("whitelistedDimensions");
        } catch (final ReflectiveOperationException | LinkageError noConfig) {
            // optional: an API without the config class is taken as seasonal everywhere
            config = null;
            whitelist = null;
        }
        return new Api(helper.getMethod("getSeasonState", Level.class), state.getMethod("getSeason"),
                state.getMethod("getDay"), state.getMethod("getCycleDuration"), state.getMethod("getDayDuration"),
                config, whitelist);
    }

    /** Serene Seasons only moves the seasons of the dimensions on its whitelist; any other one is frozen. */
    private boolean isSeasonal(final Level level) throws ReflectiveOperationException {
        if (api.configField() == null || api.whitelistField() == null) {
            return true;
        }
        final Object config = api.configField().get(null);
        if (config == null) {
            return false;
        }
        final Object list = api.whitelistField().get(config);
        return list instanceof List<?> dimensions && dimensions.contains(level.dimension().location().toString());
    }

    /** Ends the bridge for this session with its one log line: INFO for a normal case, WARN for a failure. */
    private void fallBack(final String reason, final Throwable cause, final boolean warn) {
        fellBack = true;
        if (warn) {
            LOGGER.warn("{}{}", LOG_PREFIX, reason);
        } else {
            LOGGER.info("{}{}", LOG_PREFIX, reason);
        }
        if (cause != null) {
            LOGGER.debug("{}details", LOG_PREFIX, cause);
        }
    }

    /** A short name of a throwable for the log line. */
    private static String describe(final Throwable failed) {
        final String name = failed.getClass().getSimpleName().replace("Exception", "").replace("Error", "");
        final String message = failed.getMessage();
        return message == null || message.isBlank() ? name : name + ": " + message;
    }
}
