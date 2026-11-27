package me.lovkar.seasons.compat;

import com.minecolonies.api.colony.IColony;
import me.lovkar.seasons.ColonySeasons;
import me.lovkar.seasons.SeasonsConfig;
import me.lovkar.seasons.decor.MarketMath;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * The bridge to Trade Post for MineColonies (0.2.0, chunk Q10, docs/town-square-design.md 7.1): the festival market's
 * stalls pay the colony's Trade Post balance, which is one MineColonies statistic, {@value #STAT}, on the colony's own
 * statistics manager (its Resort and its Marketplace write the same number). Nothing here is compiled against Trade Post
 * and no class of it is imported: the one thing read from it is the value of a Trade Coin, by reflection, and the name of
 * that class is put together at run time so that no built class of this mod holds it (tools/check_nodeps.py).
 *
 * <p>The rules ([B]): the bridge is {@link #active} only with the switch {@code compat.tradePostBridge} on, Trade Post
 * installed and no failure this session; otherwise nothing is looked up and nothing is logged above debug. Every call
 * into Trade Post's world sits in a try/catch that logs one WARN line and turns the bridge off until the next server
 * start ({@link #fellBack}). Payments run on the server thread only and are marked for saving ({@code markDirty}), and
 * each one is read back: the statistic must have moved by exactly the amount, else the bridge turns itself off (Trade
 * Post renamed the statistic, say). The bridge never keeps money, never mints or hands out coin items and never writes
 * Trade Post's per-building numbers.</p>
 */
public class TradePostBridge {
    public static final String MOD_ID = "mctradepost";
    /** Trade Post's own statistic for the colony's balance ("Tudarii", the value points on the Town Hall's Impact line). */
    public static final String STAT = "current_balance";
    /** The Trade Post version this bridge was proved on; another one runs, with one "not tested" line. */
    public static final String TESTED_VERSION = "1.06.012";
    /** What a Trade Coin is worth in points when Trade Post cannot be asked. */
    public static final int DEFAULT_COIN_VALUE = 1000;
    /** Every INFO or WARN line of the bridge starts with this, so a test can count them. */
    public static final String LOG_PREFIX = "Trade Post bridge: ";
    /** The largest coin value taken as sensible: a player's value outside 1 to this is taken as nonsense and the default is used. */
    public static final int MAX_COIN_VALUE = 1_000_000;

    private static final Logger LOGGER = ColonySeasons.LOGGER;
    private static final AtomicInteger LOG_LINES = new AtomicInteger();

    private final BooleanSupplier wanted;
    private final BooleanSupplier installed;
    private volatile boolean fellBack;
    private volatile boolean tested;
    private volatile int coinValue = -1;

    /** The real bridge: the config switch and the mod list. */
    public TradePostBridge() {
        this(SeasonsConfig::tradePostBridge, () -> ModList.get() != null && ModList.get().isLoaded(MOD_ID));
    }

    /** For tests: what the two answers are is theirs to decide. */
    public TradePostBridge(final BooleanSupplier wanted, final BooleanSupplier installed) {
        this.wanted = wanted;
        this.installed = installed;
    }

    /** Lines at INFO or above this class logged since the JVM started (the probe wants none without Trade Post). */
    public static int logLines() {
        return LOG_LINES.get();
    }

    /** True while the bridge may be used: the switch is on, Trade Post is installed and nothing failed this session. Field reads only: no log, no class loaded. */
    public boolean active() {
        return !fellBack && wanted.getAsBoolean() && installed.getAsBoolean();
    }

    /** True once the bridge gave up for this session. */
    public boolean fellBack() {
        return fellBack;
    }

    /** At server start: one INFO line naming the Trade Post version, and whether this bridge was proved on it. Nothing when the bridge is not active. */
    public void announce() {
        if (!active()) {
            return;
        }
        final String version = versionOf();
        if (version.startsWith(TESTED_VERSION)) {
            info("Trade Post " + version + " found: the festival market of a Town Square pays the colony's balance");
        } else {
            info("Trade Post " + version + " found, but this bridge was tested on " + TESTED_VERSION + " only (not tested): "
                    + "the festival market runs and turns itself off at the first sign that the balance does not move");
        }
    }

    private static String versionOf() {
        try {
            final Object info = ModList.get().getModContainerById(MOD_ID).map(container -> (Object) container.getModInfo()).orElse(null);
            return info == null ? "?" : String.valueOf(info.getClass().getMethod("getVersion").invoke(info));
        } catch (final ReflectiveOperationException | RuntimeException unreadable) {
            return "?";
        }
    }

    /** Once a session: reads the balance. A throw turns the bridge off. True when the bridge is active and the read worked. */
    public boolean selfTest(final IColony colony) {
        if (!active() || colony == null) {
            return false;
        }
        if (tested) {
            return true;
        }
        try {
            read(colony);
            tested = true;
            return true;
        } catch (final Throwable failed) {
            fallBack("reading the colony's balance failed (" + describe(failed) + ")", failed);
            return false;
        }
    }

    /** What a Trade Coin is worth in points: Trade Post's own setting (by reflection), else {@link #DEFAULT_COIN_VALUE}; asked once a session. */
    public int coinValue() {
        final int known = coinValue;
        if (known > 0) {
            return known;
        }
        int value = DEFAULT_COIN_VALUE;
        if (!active()) {
            return value;                                 // asked again once the bridge is on: nothing of Trade Post is looked at now
        }
        try {
            final Class<?> config = Class.forName(String.join(".", "com", "deathfrog", "mctradepost", "MCTPConfig"), true,
                    TradePostBridge.class.getClassLoader());
            final Object holder = config.getField("tradeCoinValue").get(null);
            final Object got = holder.getClass().getMethod("get").invoke(holder);
            if (got instanceof Integer i && i >= 1 && i <= MAX_COIN_VALUE) {
                value = i;
            }
        } catch (final Throwable unreadable) {
            // not worth turning the bridge off: the default is what Trade Post ships
            LOGGER.debug("{}the coin value could not be read ({}), using {}", LOG_PREFIX, describe(unreadable), DEFAULT_COIN_VALUE);
        }
        coinValue = value;
        return value;
    }

    /** The colony's balance in points (a read; 0 when the bridge is not active; a throw turns the bridge off and answers 0). */
    public int balance(final IColony colony) {
        if (!active() || colony == null) {
            return 0;
        }
        try {
            return read(colony);
        } catch (final Throwable failed) {
            fallBack("reading the colony's balance failed (" + describe(failed) + ")", failed);
            return 0;
        }
    }

    /**
     * Pays the colony {@code amount} points. False, with nothing written, when the bridge is not active, the self-test
     * has not passed, the call is not on the server thread (no fall-back for that), the amount is 0 or less or more than
     * {@link MarketMath#clamp} allows on the colony's balance (the bridge stays on). A write that throws, or a balance that
     * did not rise, writes one WARN line, turns the bridge off for the session and answers false: nothing was paid.
     *
     * <p>Once the write went through and the balance rose, the colony has been paid, so the answer is true (the caller
     * keeps the sale: the good stays sold and the paid record stays), also when the read-back shows another amount than
     * asked or cannot be read at all; those still write the one WARN line and turn the bridge off (Q7, H26: an answer of
     * false there gave the good back after the money was in, money from nothing).</p>
     */
    public boolean credit(final IColony colony, final int amount) {
        if (!active() || colony == null || !selfTest(colony)) {
            return false;
        }
        if (!(colony.getWorld() instanceof ServerLevel level) || !level.getServer().isSameThread()) {
            return false;
        }
        final int before;
        try {
            before = read(colony);
        } catch (final Throwable failed) {
            fallBack("reading the colony's balance failed (" + describe(failed) + ")", failed);
            return false;
        }
        if (amount <= 0 || amount != MarketMath.clamp(amount, before)) {
            return false;
        }
        try {
            write(colony, amount);
        } catch (final Throwable failed) {
            fallBack("paying the colony failed (" + describe(failed) + ")", failed);
            return false;
        }
        colony.markDirty();
        final long moved;
        try {
            moved = (long) read(colony) - before;
        } catch (final Throwable failed) {
            // the write went through: the colony is paid, so the sale stands; the bridge stops here
            fallBack("reading the colony's balance back after a payment failed (" + describe(failed) + ")", failed);
            return true;
        }
        if (moved != amount) {
            fallBack("the balance moved by " + moved + " instead of " + amount + ", so " + STAT + " no longer means what it did", null);
            return moved > 0;                         // paid (another amount): the sale stands; nothing moved: no sale
        }
        return true;
    }

    /** The one write: MineColonies' own statistic, on the colony's day. The probe overrides it to make it throw. */
    protected void write(final IColony colony, final int amount) {
        colony.getStatisticsManager().incrementBy(STAT, amount, colony.getDay());
    }

    /** The one read: the statistic's total (the self-test, the read-back). The probe overrides it to make it throw. */
    protected int read(final IColony colony) {
        return colony.getStatisticsManager().getStatTotal(STAT);
    }

    /** A failure somewhere else in the market (a sale that threw): one WARN line, and the bridge is off until the next server start. */
    public void fail(final String what, final Throwable cause) {
        fallBack(what + " (" + describe(cause) + ")", cause);
    }

    private void fallBack(final String reason, final Throwable cause) {
        if (fellBack) {
            return;
        }
        fellBack = true;
        LOG_LINES.incrementAndGet();
        LOGGER.warn("{}{}; the festival market stops until the next server start", LOG_PREFIX, reason);
        if (cause != null) {
            LOGGER.debug("{}details", LOG_PREFIX, cause);
        }
    }

    private static void info(final String text) {
        LOG_LINES.incrementAndGet();
        LOGGER.info("{}{}", LOG_PREFIX, text);
    }

    private static String describe(final Throwable failed) {
        if (failed == null) {
            return "no details";
        }
        final String name = failed.getClass().getSimpleName().replace("Exception", "").replace("Error", "");
        final String message = failed.getMessage();
        return message == null || message.isBlank() ? name : name + ": " + message;
    }
}
