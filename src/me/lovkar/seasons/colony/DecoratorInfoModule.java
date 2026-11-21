package me.lovkar.seasons.colony;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.modules.AbstractBuildingModule;
import com.minecolonies.api.colony.buildings.modules.ITickingModule;
import me.lovkar.seasons.ColonySeasons;
import me.lovkar.seasons.SeasonsConfig;
import me.lovkar.seasons.calendar.CalendarManager;
import me.lovkar.seasons.calendar.Festival;
import me.lovkar.seasons.calendar.Season;
import me.lovkar.seasons.decor.DecorLedger;
import me.lovkar.seasons.decor.DecorPhase;
import me.lovkar.seasons.decor.DecorWork;
import me.lovkar.seasons.decor.Kits;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * The Decorator's page in the hut window (chunk S8): today's season and day of the year, the festival that is on (or
 * the next one), what he is doing right now, how many decorations are up and buildings done, the stock of each kit
 * against its cap, what the next kit still lacks, and what the three effect switches do. It keeps nothing of its own:
 * every number is read from the calendar, the ledger and the hut when the window asks. Since S10i (the owner's answer
 * of 21 Nov 2026) it also says when the festival square's tree has no room at the marker ({@link Info#squareProblem}).
 *
 * <p>The packet {@link #serializeToView} writes is read by {@link DecoratorInfoModuleView#deserialize} field for field;
 * tools/check_packets.py compares the two. Strings are lang keys, ids and plain numbers only: the window does the
 * translating, so each player reads it in their own language.</p>
 *
 * <p>MineColonies sends a hut's page again only when the hut is marked dirty. Every colony tick (about every 25
 * seconds) this module reads the page and marks the hut when anything on it differs from the last reading: a new day,
 * a new season, a festival, the stock. Nothing changed, nothing is sent.</p>
 */
public class DecoratorInfoModule extends AbstractBuildingModule implements ITickingModule {

    /** The most kit rows and missing-ingredient rows a packet carries (a datapack can add kits). */
    public static final int MAX_ROWS = 24;

    /** The page as the last colony tick read it (never saved: after a restart the first tick sends it once). */
    private Info lastSeen;

    /** Set after the first failed reading, so a broken page warns once and not every colony tick. */
    private boolean warned;

    @Override
    public void onColonyTick(final @NotNull IColony colony) {
        final Info now;
        try {
            now = read();
        } catch (final RuntimeException unread) {
            // MineColonies ticks every building of the colony in one loop: a throw here would stop the huts after this one
            if (!warned) {
                warned = true;
                ColonySeasons.LOGGER.warn("Decorator's Workshop page could not be read on a colony tick: {}", String.valueOf(unread));
            }
            return;
        }
        if (!now.equals(lastSeen)) {
            lastSeen = now;
            markDirty();
        }
    }

    @Override
    public void serializeToView(final @NotNull RegistryFriendlyByteBuf buf) {
        final Info info = read();
        // head: the calendar, the festival, his status line
        buf.writeVarInt(info.season());
        buf.writeVarInt(info.dayOfYear());
        buf.writeVarInt(info.yearLength());
        buf.writeUtf(info.festival());
        buf.writeBoolean(info.festivalDays());
        buf.writeVarInt(info.daysUntilNext());
        buf.writeUtf(info.decorWindow());
        buf.writeUtf(info.daysWindow());
        buf.writeUtf(info.statusKey());
        buf.writeVarInt(info.statusArgs().size());
        for (final String arg : info.statusArgs()) {
            buf.writeUtf(arg);
        }
        // the tree has no room at the festival marker (S10i)
        buf.writeBoolean(info.squareProblem());
        // progress
        buf.writeBoolean(info.decorateOn());
        buf.writeVarInt(info.decorationsUp());
        buf.writeVarInt(info.buildingsDone());
        buf.writeVarInt(info.usedToday());
        buf.writeVarInt(info.quota());
        buf.writeBoolean(info.needsMaterials());
        // stock against the cap, one row a kit
        buf.writeVarInt(info.stockCap());
        buf.writeVarInt(info.stock().size());
        for (final StockRow row : info.stock()) {
            buf.writeUtf(row.item());
            buf.writeVarInt(row.have());
        }
        // what the next kit still lacks
        buf.writeUtf(info.nextKit());
        buf.writeVarInt(info.missing().size());
        for (final MissingRow row : info.missing()) {
            buf.writeUtf(row.ingredient());
            buf.writeVarInt(row.count());
        }
        // the effects
        buf.writeBoolean(info.happinessOn());
        buf.writeDouble(info.happinessBonus());
        buf.writeBoolean(info.cropsOn());
        buf.writeDouble(info.cropSkip());
        buf.writeBoolean(info.foodOn());
        buf.writeDouble(info.foodFactor());
        buf.writeBoolean(info.winter());
    }

    /** One kit: the item it makes (an id) and how many are in the hut and the worker's pack. */
    public record StockRow(String item, int have) {
    }

    /** One ingredient the next kit lacks: {@code item:<id>} or {@code tag:<id>} and how many. */
    public record MissingRow(String ingredient, int count) {
    }

    /**
     * Everything one packet says. {@code daysUntilNext} is -1 unless no festival is on and one is coming;
     * {@code decorWindow} and {@code daysWindow} are "MM-DD MM-DD" (from, until) or "" when there is no festival.
     * {@code squareProblem}: the ledger's note that the tree did not fit at the festival marker on his last look
     * ({@link DecorLedger#squareProblem}), shown only while a decoration window is open (outside it there is no tree
     * to put up, and nobody looks at the square until the next window).
     */
    public record Info(int season, int dayOfYear, int yearLength, String festival, boolean festivalDays, int daysUntilNext,
                       String decorWindow, String daysWindow, String statusKey, List<String> statusArgs, boolean squareProblem,
                       boolean decorateOn, int decorationsUp, int buildingsDone, int usedToday, int quota, boolean needsMaterials,
                       int stockCap, List<StockRow> stock, String nextKit, List<MissingRow> missing,
                       boolean happinessOn, double happinessBonus, boolean cropsOn, double cropSkip, boolean foodOn,
                       double foodFactor, boolean winter) {
    }

    /** Reads everything from the calendar, the ledger and the hut. Safe before the first calendar poll and with no worker. */
    public Info read() {
        final CalendarManager.Snapshot snapshot = CalendarManager.current();
        int season = -1;
        int day = 0;
        int year = 0;
        String festival = "";
        boolean days = false;
        int until = -1;
        String decorWindow = "";
        String daysWindow = "";
        if (snapshot != null) {
            season = snapshot.season().season().ordinal();
            day = snapshot.season().dayOfYear();
            year = snapshot.season().yearLength();
            final List<String> ids = snapshot.festivals().ids();
            days = !snapshot.festivals().days().isEmpty();
            if (!ids.isEmpty()) {
                festival = days ? snapshot.festivals().days().get(0) : ids.get(0);
            } else {
                final String[] next = nextFestival(snapshot.date());
                if (next != null) {
                    festival = next[0];
                    until = Integer.parseInt(next[1]);
                }
            }
            if (!festival.isEmpty() && SeasonsConfig.FESTIVAL_IDS.contains(festival)) {
                decorWindow = SeasonsConfig.decorFrom(festival) + " " + SeasonsConfig.decorUntil(festival);
                daysWindow = SeasonsConfig.daysFrom(festival) + " " + SeasonsConfig.daysUntil(festival);
            }
        }

        final BuildingDecorator hut = building instanceof BuildingDecorator decorator ? decorator : null;
        final int level = hut == null ? 0 : hut.getBuildingLevel();

        String statusKey = DecoratorTexts.NO_WORKER;
        final List<String> statusArgs = new ArrayList<>();
        if (hut != null) {
            for (final ICitizenData citizen : hut.getAllAssignedCitizen()) {
                if (citizen != null && citizen.getJob() instanceof JobDecorator job) {
                    statusKey = job.statusKey().isEmpty() ? DecoratorTexts.WAITING_MATERIALS : job.statusKey();
                    statusArgs.addAll(List.of(job.statusArgs()));
                    break;
                }
            }
        }

        int up = 0;
        int done = 0;
        int used = 0;
        boolean square = false;
        final IColony colony = building == null ? null : building.getColony();
        if (colony != null && colony.getWorld() instanceof ServerLevel serverLevel) {
            final DecorLedger ledger = DecorLedger.peek(serverLevel);
            if (ledger != null) {
                up = ledger.countOutdoor(colony.getID());     // the storeroom is no decoration that is "up"
                done = ledger.buildingsDone(colony.getID());
                used = ledger.budgetUsed(colony.getID(), DecorWork.day(serverLevel));
                square = ledger.squareProblem(colony.getID()) && DecorPhase.windowOpen(snapshot);
            }
        }

        final List<StockRow> stock = new ArrayList<>();
        String nextKit = "";
        final List<MissingRow> missing = new ArrayList<>();
        if (hut != null) {
            final int cap = hut.stockCap();
            Kits.Kit pick = null;
            List<MissingRow> pickMissing = List.of();
            for (final Kits.Kit kit : Kits.all()) {
                final int have = hut.stock(kit.output());
                if (stock.size() < MAX_ROWS) {
                    stock.add(new StockRow(BuiltInRegistries.ITEM.getKey(kit.output()).toString(), have));
                }
                if (!Kits.wanted(have, kit.count(), cap)) {
                    continue;
                }
                final List<MissingRow> lacks = new ArrayList<>();
                for (final Kits.Ingredient ingredient : kit.ingredients()) {
                    final int lack = ingredient.count() - hut.count(ingredient::matches);
                    if (lack > 0) {
                        lacks.add(new MissingRow(ingredient.key(), lack));
                    }
                }
                // the first kit that lacks something; else the first one that can be made now
                if (pick == null || pickMissing.isEmpty() && !lacks.isEmpty()) {
                    pick = kit;
                    pickMissing = lacks;
                }
            }
            if (pick != null) {
                nextKit = BuiltInRegistries.ITEM.getKey(pick.output()).toString();
                missing.addAll(pickMissing.subList(0, Math.min(MAX_ROWS, pickMissing.size())));
            }
        }

        final boolean winter = snapshot != null && snapshot.season().season() == Season.WINTER;
        return new Info(season, day, year, festival, days, until, decorWindow, daysWindow, statusKey, List.copyOf(statusArgs), square,
                hut == null || hut.decorateEnabled(), up, done, used, DecorWork.quota(level), SeasonsConfig.needsMaterials(),
                hut == null ? 0 : hut.stockCap(), List.copyOf(stock), nextKit, List.copyOf(missing),
                SeasonsConfig.festivalHappiness(), SeasonsConfig.festivalHappinessBonus(), SeasonsConfig.winterCropSlowdown(),
                SeasonsConfig.winterCropSkipChance(), SeasonsConfig.winterFoodNeed(), SeasonsConfig.winterFoodNeedFactor(), winter);
    }

    /**
     * The next festival whose decoration window has not opened yet: {@code {id, days until}} or {@code null}. A window
     * that opens next year counts too (the date wraps).
     */
    private static String[] nextFestival(final LocalDate today) {
        String best = null;
        long bestDays = Long.MAX_VALUE;
        for (final String id : SeasonsConfig.FESTIVAL_IDS) {
            if (!SeasonsConfig.festivalEnabled(id)) {
                continue;
            }
            LocalDate start = Festival.parse(SeasonsConfig.decorFrom(id)).atYear(today.getYear());
            if (start.isBefore(today)) {
                start = Festival.parse(SeasonsConfig.decorFrom(id)).atYear(today.getYear() + 1);
            }
            final long wait = ChronoUnit.DAYS.between(today, start);
            if (wait < bestDays) {
                bestDays = wait;
                best = id;
            }
        }
        return best == null ? null : new String[] {best, Long.toString(bestDays)};
    }
}
