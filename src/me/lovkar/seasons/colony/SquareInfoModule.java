package me.lovkar.seasons.colony;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.modules.AbstractBuildingModule;
import com.minecolonies.api.colony.buildings.modules.ITickingModule;
import me.lovkar.seasons.ColonySeasons;
import me.lovkar.seasons.SeasonsConfig;
import me.lovkar.seasons.calendar.CalendarManager;
import me.lovkar.seasons.calendar.Festival;
import me.lovkar.seasons.decor.DecorPhase;
import me.lovkar.seasons.decor.SquareSets;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Town Square's info page in the hut window (0.2.0, chunk Q5, docs/town-square-design.md 4.4): the square's level,
 * its order, the set it wants and the set that stands on it with its state (up, going up, coming down, waiting for
 * materials, no room), the next change of set, the festival, the Decorator, what is missing and what the next level
 * adds. It keeps nothing of its own and writes nothing: every number is read from the calendar, the ledger and the
 * colony when the window asks ({@link SquareStatus}); never {@code Squares.refresh}, which writes the ledger.
 *
 * <p>The packet {@link #serializeToView} writes is read by {@link SquareInfoModuleView#deserialize} field for field;
 * tools/check_packets.py compares the two. Strings are ids, lang keys and the Decorator's name; the window translates,
 * so each player reads it in their own language. Like the Workshop's Calendar page it is sent again only when it
 * changed: every colony tick the module reads the page and marks the hut when it differs from the last reading.</p>
 */
public class SquareInfoModule extends AbstractBuildingModule implements ITickingModule {

    /** The most missing-item rows and level-addition rows a packet carries. */
    public static final int MAX_ROWS = 12;

    /** Next-change kinds: none planned, a set's window opens on a date, the set's window ends on a date. */
    public static final int CHANGE_NONE = 0;
    public static final int CHANGE_FROM = 1;
    public static final int CHANGE_UNTIL = 2;

    private Info lastSeen;
    private boolean warned;

    /** One vanilla item the last visit was short of: an item id and how many. */
    public record MissingRow(String item, int count) {
    }

    /** What the next level adds: a tag (its slot) and how many more cells of it. */
    public record AddedRow(String tag, int count) {
    }

    /**
     * Everything one packet says. {@code wantedSet} and {@code upSet} are set ids or "" (none); {@code state} is a
     * {@link SquareStatus.State} id; {@code changeSet} and {@code changeDate} ("MM-DD") belong to {@code changeKind};
     * {@code festival} is the festival that is on (or the next, then {@code daysUntilNext} is 0 or more), as on the
     * Calendar page; {@code decorator} is his name or "", {@code decoratorHere} whether he is listed on this square (else
     * on another one, working the main square); {@code nextLevel} is 0 at the highest level this version has.
     */
    public record Info(int level, int maxLevel, String order, int standing, BlockPos mainPos, int mainLevel, String wantedSet,
                       String upSet, int state, int changeKind, String changeSet, String changeDate, String festival,
                       boolean festivalDays, int daysUntilNext, String decorator, boolean decoratorHere,
                       List<MissingRow> missing, int nextLevel, List<AddedRow> added) {
    }

    /** A page before its first packet: no level, no square. */
    public static Info empty() {
        return new Info(0, 0, SquareSets.Order.FOLLOW.key(), SquareStatus.Standing.NONE.ordinal(), BlockPos.ZERO, 0, "", "",
                SquareStatus.State.NOT_BUILT.ordinal(), CHANGE_NONE, "", "", "", false, -1, "", false, List.of(), 0, List.of());
    }

    @Override
    public void onColonyTick(final @NotNull IColony colony) {
        final Info now;
        try {
            now = read();
        } catch (final RuntimeException unread) {
            // MineColonies ticks every building of the colony in one loop: a throw here would stop the huts after this one
            if (!warned) {
                warned = true;
                ColonySeasons.LOGGER.warn("A Town Square's info page could not be read on a colony tick: {}", String.valueOf(unread));
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
        // head: the square, the order, its set and the state
        buf.writeVarInt(info.level());
        buf.writeVarInt(info.maxLevel());
        buf.writeUtf(info.order());
        buf.writeVarInt(info.standing());
        buf.writeBlockPos(info.mainPos());
        buf.writeVarInt(info.mainLevel());
        buf.writeUtf(info.wantedSet());
        buf.writeUtf(info.upSet());
        buf.writeVarInt(info.state());
        // the next change of set, the festival, the Decorator
        buf.writeVarInt(info.changeKind());
        buf.writeUtf(info.changeSet());
        buf.writeUtf(info.changeDate());
        buf.writeUtf(info.festival());
        buf.writeBoolean(info.festivalDays());
        buf.writeVarInt(info.daysUntilNext());
        buf.writeUtf(info.decorator());
        buf.writeBoolean(info.decoratorHere());
        // what is missing, one row an item
        buf.writeVarInt(info.missing().size());
        for (final MissingRow row : info.missing()) {
            buf.writeUtf(row.item());
            buf.writeVarInt(row.count());
        }
        // what the next level adds, one row a tag
        buf.writeVarInt(info.nextLevel());
        buf.writeVarInt(info.added().size());
        for (final AddedRow row : info.added()) {
            buf.writeUtf(row.tag());
            buf.writeVarInt(row.count());
        }
    }

    /** Reads everything from the colony, the ledger and the calendar. Safe before the first calendar poll and without a colony. */
    public Info read() {
        if (!(building instanceof BuildingTownSquare square)) {
            return empty();
        }
        final SquareStatus.Status status = SquareStatus.of(square);
        final CalendarManager.Snapshot snapshot = CalendarManager.current();

        String festival = "";
        boolean days = false;
        int until = -1;
        if (snapshot != null) {
            final List<String> ids = snapshot.festivals().ids();
            days = !snapshot.festivals().days().isEmpty();
            if (!ids.isEmpty()) {
                festival = days ? snapshot.festivals().days().get(0) : ids.get(0);
            } else {
                final String[] next = DecoratorInfoModule.nextFestival(snapshot.date());
                if (next != null) {
                    festival = next[0];
                    until = Integer.parseInt(next[1]);
                }
            }
        }

        final String[] change = nextChange(status.order(), snapshot);

        final List<MissingRow> missing = new ArrayList<>();
        for (final SquareStatus.Lack lack : status.lacks()) {
            if (missing.size() < MAX_ROWS) {
                missing.add(new MissingRow(lack.item(), lack.count()));
            }
        }

        final int max = square.getMaxBuildingLevel();
        final int nextLevel = status.level() < max ? status.level() + 1 : 0;
        final List<AddedRow> added = new ArrayList<>();
        if (nextLevel > 0) {
            for (final Map.Entry<String, Integer> e : addedBy(status.level(), nextLevel).entrySet()) {
                if (added.size() < MAX_ROWS) {
                    added.add(new AddedRow(e.getKey(), e.getValue()));
                }
            }
        }

        final SquareStatus.Worker worker = status.worker();
        return new Info(status.level(), max, status.order().key(), status.main().standing().ordinal(), status.main().pos(),
                status.main().level(), status.wanted() == null ? "" : status.wanted().id(),
                status.up() == null ? "" : status.up().id(), status.state().ordinal(), Integer.parseInt(change[0]), change[1],
                change[2], festival, days, until, worker == null ? "" : worker.name(), worker != null && worker.here(),
                List.copyOf(missing), nextLevel, List.copyOf(added));
    }

    /**
     * The slots a square of {@code to} has more than one of {@code from} (design 4.2, {@link SquareSets#slots}): tag and
     * how many more, in the table's order; a tag the lower level has not at all counts all its cells. Pure.
     */
    public static Map<String, Integer> addedBy(final int from, final int to) {
        final Map<String, Integer> had = SquareSets.slots(Math.max(1, from));
        final Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for (final Map.Entry<String, Integer> e : SquareSets.slots(to).entrySet()) {
            final int more = e.getValue() - (from < 1 ? 0 : had.getOrDefault(e.getKey(), 0));
            if (more > 0) {
                out.put(e.getKey(), more);
            }
        }
        return out;
    }

    /**
     * The next change of the square's set under {@code order} ([A] design 4.4): {@code {kind, set id, "MM-DD"}} as text.
     * Inside the window of a festival that has a set (or any festival, under an "Always" order) the set stays until that
     * window's last day; outside, the next window that has one opens on its first day. Bare, no calendar yet, and no
     * festival with a set in this version: none planned. Pure but for the config the festivals' windows are read from.
     */
    public static String[] nextChange(final SquareSets.Order order, final CalendarManager.Snapshot snapshot) {
        final String[] none = {Integer.toString(CHANGE_NONE), "", ""};
        if (order == SquareSets.Order.BARE || snapshot == null) {
            return none;
        }
        if (DecorPhase.windowOpen(snapshot)) {
            for (final String festival : snapshot.festivals().decor()) {
                final SquareSets.Set set = setFor(order, festival);
                if (set != null && set.shipped() && SeasonsConfig.FESTIVAL_IDS.contains(festival)) {
                    return new String[] {Integer.toString(CHANGE_UNTIL), set.id(), SeasonsConfig.decorUntil(festival)};
                }
            }
            return none;
        }
        final LocalDate today = snapshot.date();
        String bestId = null;
        SquareSets.Set bestSet = null;
        long bestDays = Long.MAX_VALUE;
        for (final String id : SeasonsConfig.FESTIVAL_IDS) {
            final SquareSets.Set set = setFor(order, id);
            if (!SeasonsConfig.festivalEnabled(id) || set == null || !set.shipped()) {
                continue;
            }
            LocalDate start = Festival.parse(SeasonsConfig.decorFrom(id)).atYear(today.getYear());
            if (start.isBefore(today)) {
                start = Festival.parse(SeasonsConfig.decorFrom(id)).atYear(today.getYear() + 1);
            }
            final long wait = ChronoUnit.DAYS.between(today, start);
            if (wait < bestDays) {
                bestDays = wait;
                bestId = id;
                bestSet = set;
            }
        }
        return bestId == null ? none : new String[] {Integer.toString(CHANGE_FROM), bestSet.id(), SeasonsConfig.decorFrom(bestId)};
    }

    /** The set the order puts up in a festival's window: the chosen one of an "Always" order, else the festival's own, or none. */
    private static @Nullable SquareSets.Set setFor(final SquareSets.Order order, final String festival) {
        return order.always() != null ? order.always() : SquareSets.forFestival(festival);
    }
}
