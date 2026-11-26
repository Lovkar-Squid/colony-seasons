package me.lovkar.seasons.decor;

import me.lovkar.seasons.ColonySeasons;
import me.lovkar.seasons.block.Lights;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Every decoration the Decorator (or the server on his behalf) has put into one level, keyed by colony id (design
 * 3.3). It is NOT stored in the hut: hut module data goes when the hut is destroyed, and the decorations must not
 * become orphans.
 *
 * <p>An entry is a position, the exact block state put there, and whether an item was paid for it. Entries live in
 * groups: one per building or festival marker ({@link Owner}), with the festival it went up for, the Decorator hut
 * level it was planned for, the piece path (the square), the game days it was decorated and last tended, and a
 * "removing" mark for the server sweep. Per colony the record also keeps the day's building budget (how many and
 * which buildings or squares were counted on that game day: the count belongs to the colony, so a group that is
 * dropped and made again the same day, as when a work order takes a building's decorations off, is not counted
 * twice), the festival marker, the main Town Square's mark (0.2.0, {@link SquareMark}), and when the colony's managers
 * were last told the square has no room.</p>
 *
 * <p>Rules this class keeps: a position is listed at most once, in all colonies of the level together ({@link #record}
 * refuses a second one), and {@link #record} is called BEFORE the block is set, so a crash can at worst leave an entry
 * whose block is missing (forgotten at take-down), never a block nobody knows about. A position index makes
 * {@link #at} and {@link #blockChanged} constant time.</p>
 *
 * <p>Saved per level as {@code data/colony_seasons_decor.dat}, with a version number that {@link #load} reads,
 * written at once on the server thread (see {@link #save(File, HolderLookup.Provider)}). A group stores its states as
 * a palette plus one index per position. A state that reads back as air (its block no longer exists) drops its
 * entries with one warning: such a position can never equal the record again.</p>
 */
public final class DecorLedger extends SavedData {
    public static final String FILE = "colony_seasons_decor";
    /**
     * The file format: 1 (S4 to S10g1), 2 (S10g2: the {@link Kind#STOREROOM} kind), 3 (0.2.0, Q2: per colony the main
     * Town Square's mark, {@code squareBuilding} and {@code squareAnchor}, see {@link SquareMark}). The layout is the
     * same in all three, and each version only adds: a file with no version, or with version 1, has no storeroom groups
     * and no mark and loads exactly as before; a version 2 file has no mark. A build that knows only version 1 reads a
     * version 2 file with its one warning and skips the storeroom groups (their blocks do not exist in that build
     * either); a build that knows version 2 (0.1.0) reads a version 3 file with its one warning and ignores the two mark
     * keys, and its own rule (a square's group whose position is not the marker goes) then takes the square's group down.
     *
     * <p>0.2.0, Q10 adds two more keys to a colony's record without a new version number (0.2.0 is not released, and each
     * version only adds): {@code marketDay} and {@code marketPaid}, what the festival market's stalls paid on one colony
     * day ({@link MarketMath.PaidDay}); both are written only once something was paid, and a file without them reads as
     * "never paid".</p>
     */
    static final int VERSION = 3;

    /**
     * What a group belongs to: a building (its hut position), the square (its festival marker's position), or the
     * Decorator's storeroom inside his Workshop (the Workshop's hut position; S10g2, storage-look 3.3). The storeroom is
     * a group of its own next to the Workshop's outside group at the same position: it is never taken down, never
     * counted against the day's budget, and its {@code festival} is the set that is up ({@code ""} packed).
     */
    public enum Kind { BUILDING, SQUARE, STOREROOM }

    public record Owner(Kind kind, BlockPos pos) {
        public Owner {
            pos = pos.immutable();
        }

        public static Owner building(final BlockPos pos) {
            return new Owner(Kind.BUILDING, pos);
        }

        public static Owner square(final BlockPos marker) {
            return new Owner(Kind.SQUARE, marker);
        }

        /** The storeroom of the Decorator's Workshop whose hut block stands at {@code hut} (S10g2). */
        public static Owner storeroom(final BlockPos hut) {
            return new Owner(Kind.STOREROOM, hut);
        }

        /** A building's or the square's: the festival decorations outside (not the storeroom). */
        public boolean outdoor() {
            return kind != Kind.STOREROOM;
        }

        @Override
        public String toString() {
            return kind.name().toLowerCase(java.util.Locale.ROOT) + " " + pos.toShortString();
        }
    }

    /** One placed block: its exact state and whether an item was taken for it. */
    public record Entry(BlockState state, boolean paid) {
    }

    /** Where a listed position belongs. */
    public record Located(int colony, Owner owner, Entry entry) {
    }

    /**
     * A copy of one group; {@code chargedDay} is the colony's budget day when this owner was counted on it, else -1.
     * {@code treeDay} (the square only): the game day the cells of the tree itself were all up, else -1 (S10c: the
     * tree is ready to light then, whatever else of the square is still open).
     */
    public record GroupView(Owner owner, String festival, int level, String piece, long decoratedDay, long tendedDay,
                            long chargedDay, boolean removing, Map<BlockPos, Entry> entries, long treeDay) {
        public int size() {
            return entries.size();
        }
    }

    private static final class Group {
        final Owner owner;
        String festival = "";
        int level;
        String piece = "";
        long decoratedDay = -1;
        long tendedDay = -1;
        long treeDay = -1;
        boolean removing;
        final LinkedHashMap<BlockPos, Entry> entries = new LinkedHashMap<>();

        Group(final Owner owner) {
            this.owner = owner;
        }

        GroupView view(final ColonyRecord record) {
            final long chargedDay = record.charged.contains(owner) ? record.budgetDay : -1;
            return new GroupView(owner, festival, level, piece, decoratedDay, tendedDay, chargedDay, removing,
                    Collections.unmodifiableMap(new LinkedHashMap<>(entries)), treeDay);
        }
    }

    /** One target's fruitless visits on one game day (S10c); kept in memory only, never saved. */
    private record Fruit(int colony, Owner owner) {
    }

    private final Map<Fruit, long[]> fruitless = new HashMap<>();

    private static final class ColonyRecord {
        long budgetDay = -1;
        int budgetUsed;
        /** The owners counted on {@code budgetDay}; kept when their group is dropped. */
        final Set<Owner> charged = new LinkedHashSet<>();
        BlockPos marker;
        /** The main Town Square's hut and its centrepiece cell (version 3): both set or both null. */
        BlockPos squareBuilding;
        BlockPos squareAnchor;
        /** What the festival market paid on one colony day (Q10): {@code marketDay} -1 for never. */
        MarketMath.PaidDay market = MarketMath.PaidDay.NONE;
        long noticeDay = -1;
        boolean squareProblem;
        final LinkedHashMap<Owner, Group> groups = new LinkedHashMap<>();
    }

    private record Slot(int colony, Owner owner) {
    }

    private final Map<Integer, ColonyRecord> colonies = new TreeMap<>();
    /** pos.asLong() -> where the entry lives. */
    private final Map<Long, Slot> index = new HashMap<>();

    public static final SavedData.Factory<DecorLedger> FACTORY = new SavedData.Factory<>(DecorLedger::new, DecorLedger::load);

    public DecorLedger() {
    }

    /** The ledger of one level (made on first use). */
    public static DecorLedger get(final ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, FILE);
    }

    /** The ledger of one level if it exists (in memory or on disk), else {@code null}; never makes one. */
    public static DecorLedger peek(final ServerLevel level) {
        return level.getDataStorage().get(FACTORY, FILE);
    }

    // ---------------------------------------------------------------- entries

    /**
     * Lists one placed block. Refuses (false) a position that is already listed, in any group of any colony.
     * Call it before the block is set.
     */
    public boolean record(final int colony, final Owner owner, final String festival, final int level, final BlockPos pos,
                          final BlockState state, final boolean paid) {
        final long key = pos.asLong();
        if (index.containsKey(key) || state == null || state.isAir()) {
            return false;
        }
        final Group group = group(colony, owner, true);
        if (group.entries.isEmpty() && group.festival.isEmpty()) {
            group.festival = festival == null ? "" : festival;
        }
        if (group.level == 0) {
            group.level = level;
        }
        group.entries.put(pos.immutable(), new Entry(state, paid));
        index.put(key, new Slot(colony, owner));
        setDirty();
        return true;
    }

    /** Forgets one position (whatever is in the world there); returns its entry, or {@code null} if none. */
    public Entry forget(final BlockPos pos) {
        final Slot slot = index.remove(pos.asLong());
        if (slot == null) {
            return null;
        }
        final ColonyRecord record = colonies.get(slot.colony());
        final Group group = record == null ? null : record.groups.get(slot.owner());
        final Entry entry = group == null ? null : group.entries.remove(pos);
        setDirty();
        return entry;
    }

    /** A listed block changed its state in a way that is still ours (a light switched): the entry follows it. */
    public boolean litChanged(final BlockPos pos, final BlockState state) {
        return restate(pos, state);
    }

    /** Replaces the recorded state of a listed position, keeping whether it was paid for. */
    public boolean restate(final BlockPos pos, final BlockState state) {
        final Slot slot = index.get(pos.asLong());
        if (slot == null || state == null || state.isAir()) {
            return false;
        }
        final Group group = colonies.get(slot.colony()).groups.get(slot.owner());
        final Entry old = group.entries.get(pos);
        if (old == null || old.state() == state) {
            return false;
        }
        group.entries.put(pos.immutable(), new Entry(state, old.paid()));
        setDirty();
        return true;
    }

    /** Where a position is listed, or {@code null}. */
    public Located at(final BlockPos pos) {
        final Slot slot = index.get(pos.asLong());
        if (slot == null) {
            return null;
        }
        final Group group = colonies.get(slot.colony()).groups.get(slot.owner());
        final Entry entry = group == null ? null : group.entries.get(pos);
        return entry == null ? null : new Located(slot.colony(), slot.owner(), entry);
    }

    /** True when {@code pos} is listed in exactly this group with exactly {@code state}. */
    public boolean isOurs(final int colony, final Owner owner, final BlockPos pos, final BlockState state) {
        final Located at = at(pos);
        return at != null && at.colony() == colony && at.owner().equals(owner) && at.entry().state() == state;
    }

    // ---------------------------------------------------------------- groups

    private Group group(final int colony, final Owner owner, final boolean make) {
        ColonyRecord record = colonies.get(colony);
        if (record == null) {
            if (!make) {
                return null;
            }
            record = new ColonyRecord();
            colonies.put(colony, record);
        }
        Group group = record.groups.get(owner);
        if (group == null && make) {
            group = new Group(owner);
            record.groups.put(owner, group);
            setDirty();
        }
        return group;
    }

    /** Makes the group if it is missing (an empty group means: planned, nothing fits) and sets what it was planned for. */
    public void plan(final int colony, final Owner owner, final String festival, final int level, final String piece) {
        final Group group = group(colony, owner, true);
        final String newFestival = festival == null ? "" : festival;
        final String newPiece = piece == null ? "" : piece;
        boolean changed = false;
        if (group.entries.isEmpty() && !group.festival.equals(newFestival)) {
            group.festival = newFestival;
            changed = true;
        }
        if (group.level != level) {
            group.level = level;
            changed = true;
        }
        if (!group.piece.equals(newPiece)) {
            group.piece = newPiece;
            group.treeDay = -1;                       // a new piece: its tree is not up yet
            changed = true;
        }
        if (changed) {
            setDirty();                               // a visit that plans the same again writes nothing
        }
    }

    public GroupView group(final int colony, final Owner owner) {
        final Group group = group(colony, owner, false);
        return group == null ? null : group.view(colonies.get(colony));
    }

    /** Copies of every group of a colony, in the order they were made. */
    public List<GroupView> groups(final int colony) {
        final ColonyRecord record = colonies.get(colony);
        if (record == null) {
            return List.of();
        }
        final List<GroupView> all = new ArrayList<>();
        for (final Group group : record.groups.values()) {
            all.add(group.view(record));
        }
        return all;
    }

    /** The colony ids with a record in this level. */
    public List<Integer> colonies() {
        return List.copyOf(colonies.keySet());
    }

    public void setDecorated(final int colony, final Owner owner, final long day) {
        final Group group = group(colony, owner, false);
        if (group != null && group.decoratedDay != day) {
            group.decoratedDay = day;
            setDirty();
        }
    }

    /**
     * The cells of the square's tree are all up on game day {@code day}: noted once (only while it is -1), so a leaf
     * broken later never makes a lit tree unready; a new piece clears it ({@link #plan}).
     */
    public void setTreeReady(final int colony, final Owner owner, final long day) {
        final Group group = group(colony, owner, false);
        if (group != null && group.treeDay < 0) {
            group.treeDay = day;
            setDirty();
        }
    }

    // ---------------------------------------------------------------- fruitless visits (S10c), in memory only

    /** Fruitless visits to this target on game day {@code day} (0 on any other day). */
    public int fruitless(final int colony, final Owner owner, final long day) {
        final long[] at = fruitless.get(new Fruit(colony, owner));
        return at == null || at[0] != day ? 0 : (int) at[1];
    }

    /** One more fruitless visit on game day {@code day} (a new day starts again at 1); returns the count now. */
    public int addFruitless(final int colony, final Owner owner, final long day) {
        final long[] at = fruitless.computeIfAbsent(new Fruit(colony, owner), k -> new long[] {day, 0});
        if (at[0] != day) {
            at[0] = day;
            at[1] = 0;
        }
        return (int) ++at[1];
    }

    /** A visit placed something: the count starts again. */
    public void clearFruitless(final int colony, final Owner owner) {
        fruitless.remove(new Fruit(colony, owner));
    }

    /**
     * A storeroom group's state after a complete check (S10g2, storage-look 3.2): the set that is up ({@code ""} packed),
     * the Workshop level it was laid out for and the game day of the check, written as they are (unlike
     * {@link #record} and {@link #plan}, which set the festival only on an empty group). Only an existing group; the
     * file is marked to be written only when a value changes, so a check that changes nothing writes nothing.
     */
    public void setRoom(final int colony, final Owner owner, final String festival, final int level, final long day) {
        final Group group = group(colony, owner, false);
        if (group == null) {
            return;
        }
        final String newFestival = festival == null ? "" : festival;
        boolean changed = false;
        if (!group.festival.equals(newFestival)) {
            group.festival = newFestival;
            changed = true;
        }
        if (group.level != level) {
            group.level = level;
            changed = true;
        }
        if (group.tendedDay != day) {
            group.tendedDay = day;
            changed = true;
        }
        if (changed) {
            setDirty();
        }
    }

    /** What a storeroom check needs to know of a group, read in place without copying its entries (S10g2). */
    public record RoomMark(String festival, int level, long tendedDay, boolean removing, int size) {
    }

    /** The group's {@link RoomMark}, or {@code null} when there is no such group. */
    public RoomMark roomMark(final int colony, final Owner owner) {
        final Group group = group(colony, owner, false);
        return group == null ? null : new RoomMark(group.festival, group.level, group.tendedDay, group.removing, group.entries.size());
    }

    public void setTended(final int colony, final Owner owner, final long day) {
        final Group group = group(colony, owner, false);
        if (group != null && group.tendedDay != day) {
            group.tendedDay = day;
            setDirty();
        }
    }

    /** Marks a group for removal by the server sweep (its entries stay listed until they are removed). */
    public void markRemoving(final int colony, final Owner owner) {
        final Group group = group(colony, owner, false);
        if (group != null && !group.removing) {
            group.removing = true;
            setDirty();
        }
    }

    /** Drops a group that has no entries left; a group that still lists entries is kept (no orphans). */
    public boolean dropGroup(final int colony, final Owner owner) {
        final ColonyRecord record = colonies.get(colony);
        final Group group = record == null ? null : record.groups.get(owner);
        if (group == null || !group.entries.isEmpty()) {
            return false;
        }
        record.groups.remove(owner);
        setDirty();
        return true;
    }

    /**
     * Forgets a colony's whole record (its marker, its day's budget, its empty groups) when it lists no entry any more:
     * the colony is gone (S9, H12). A record that still lists entries is never dropped (no orphans). True when dropped.
     */
    public boolean dropColony(final int colony) {
        final ColonyRecord record = colonies.get(colony);
        if (record == null || hasEntries(colony)) {
            return false;
        }
        colonies.remove(colony);
        setDirty();
        return true;
    }

    /** Drops every group of a colony that lists nothing. */
    public int dropEmptyGroups(final int colony) {
        final ColonyRecord record = colonies.get(colony);
        if (record == null) {
            return 0;
        }
        final int before = record.groups.size();
        record.groups.values().removeIf(group -> group.entries.isEmpty());
        final int dropped = before - record.groups.size();
        if (dropped > 0) {
            setDirty();
        }
        return dropped;
    }

    /** True when any group of the colony lists an entry, the storeroom included ("anything listed at all"). */
    public boolean hasEntries(final int colony) {
        return has(colony, false);
    }

    /**
     * True when a building's or the square's group lists an entry: the festival decorations are up (storage-look 3.3,
     * point 2). The storeroom stays listed all year (packed outside the windows), so the Decorator's phase, his
     * take-down and the sweep's reset of "decorations up" read this one.
     */
    public boolean hasOutdoorEntries(final int colony) {
        return has(colony, true);
    }

    private boolean has(final int colony, final boolean outdoorOnly) {
        final ColonyRecord record = colonies.get(colony);
        if (record == null) {
            return false;
        }
        for (final Group group : record.groups.values()) {
            if (!group.entries.isEmpty() && (!outdoorOnly || group.owner.outdoor())) {
                return true;
            }
        }
        return false;
    }

    /** Entries listed for one colony, all kinds. */
    public int count(final int colony) {
        return count(colony, false);
    }

    /** Entries of the colony's buildings and square (the storeroom left out): the decorations that are up outside. */
    public int countOutdoor(final int colony) {
        return count(colony, true);
    }

    private int count(final int colony, final boolean outdoorOnly) {
        final ColonyRecord record = colonies.get(colony);
        int total = 0;
        if (record != null) {
            for (final Group group : record.groups.values()) {
                if (!outdoorOnly || group.owner.outdoor()) {
                    total += group.entries.size();
                }
            }
        }
        return total;
    }

    /**
     * Buildings of one colony that are decorated now: a building's group that was finished on some day and still lists
     * entries (the Workshop's page). Counts in place, without the copies {@link #groups} makes.
     */
    public int buildingsDone(final int colony) {
        final ColonyRecord record = colonies.get(colony);
        int done = 0;
        if (record != null) {
            for (final Group group : record.groups.values()) {
                if (group.owner.kind() == Kind.BUILDING && group.decoratedDay >= 0 && !group.entries.isEmpty()) {
                    done++;
                }
            }
        }
        return done;
    }

    /** Entries listed in this level, all colonies. */
    public int count() {
        return index.size();
    }

    // ---------------------------------------------------------------- the day's budget

    /** Buildings started on game day {@code day} (0 when the budget is of another day). */
    public int budgetUsed(final int colony, final long day) {
        final ColonyRecord record = colonies.get(colony);
        return record == null || record.budgetDay != day ? 0 : record.budgetUsed;
    }

    /**
     * Counts a building or square against the colony's budget of game day {@code day}, once a day: true when it was
     * counted now, false when it had been counted already today. The mark is the colony's, so it holds even when the
     * owner's group was dropped in between (its decorations taken off for a work order, then put up again).
     */
    public boolean charge(final int colony, final Owner owner, final long day) {
        final ColonyRecord record = budgetOf(colony, day);
        if (!record.charged.add(owner)) {
            return false;
        }
        record.budgetUsed++;
        setDirty();
        return true;
    }

    /** True when the owner was counted against the colony's budget of game day {@code day}. */
    public boolean chargedOn(final int colony, final Owner owner, final long day) {
        final ColonyRecord record = colonies.get(colony);
        return record != null && record.budgetDay == day && record.charged.contains(owner);
    }

    /** One more building started on game day {@code day}, with no owner named (the probe fills the day with it). */
    public void spendBudget(final int colony, final long day) {
        budgetOf(colony, day).budgetUsed++;
        setDirty();
    }

    /** The colony's record with its budget turned to game day {@code day} (a new day starts at 0, nobody counted). */
    private ColonyRecord budgetOf(final int colony, final long day) {
        ColonyRecord record = colonies.get(colony);
        if (record == null) {
            record = new ColonyRecord();
            colonies.put(colony, record);
        }
        if (record.budgetDay != day) {
            record.budgetDay = day;
            record.budgetUsed = 0;
            record.charged.clear();
            setDirty();
        }
        return record;
    }

    // ---------------------------------------------------------------- the festival marker and the square

    /** The colony's festival marker, or {@code null}. */
    public BlockPos marker(final int colony) {
        final ColonyRecord record = colonies.get(colony);
        return record == null ? null : record.marker;
    }

    /** Sets the colony's marker; false (and nothing changes) when it already has another one. */
    public boolean registerMarker(final int colony, final BlockPos pos) {
        ColonyRecord record = colonies.get(colony);
        if (record == null) {
            record = new ColonyRecord();
            colonies.put(colony, record);
        }
        if (record.marker != null && !record.marker.equals(pos)) {
            return false;
        }
        record.marker = pos.immutable();
        setDirty();
        return true;
    }

    /** Takes the marker off its colony (only when {@code pos} is the registered one); true when it was. */
    public boolean unregisterMarker(final int colony, final BlockPos pos) {
        final ColonyRecord record = colonies.get(colony);
        if (record == null || record.marker == null || !record.marker.equals(pos)) {
            return false;
        }
        record.marker = null;
        record.squareProblem = false;
        setDirty();
        return true;
    }

    /** The colony whose marker stands at {@code pos}, or -1. */
    public int markerColony(final BlockPos pos) {
        for (final Map.Entry<Integer, ColonyRecord> e : colonies.entrySet()) {
            if (pos.equals(e.getValue().marker)) {
                return e.getKey();
            }
        }
        return -1;
    }

    /**
     * The colony's main Town Square as the resolver last stored it (0.2.0, Q2, design 4.6): the hut's position and the
     * centrepiece cell (the world position of its {@code tree} tag). While a mark is stored the festival marker is only
     * the fallback ({@link Squares}).
     */
    public record SquareMark(BlockPos building, BlockPos anchor) {
        public SquareMark {
            building = building.immutable();
            anchor = anchor.immutable();
        }
    }

    /** The colony's stored {@link SquareMark}, or {@code null}. */
    public SquareMark squareMark(final int colony) {
        final ColonyRecord record = colonies.get(colony);
        return record == null || record.squareBuilding == null || record.squareAnchor == null
                ? null : new SquareMark(record.squareBuilding, record.squareAnchor);
    }

    /** Stores the colony's main square (makes the record if there is none); true, and marked dirty, only when it changed. */
    public boolean setSquareMark(final int colony, final BlockPos building, final BlockPos anchor) {
        ColonyRecord record = colonies.get(colony);
        if (record != null && building.equals(record.squareBuilding) && anchor.equals(record.squareAnchor)) {
            return false;
        }
        if (record == null) {
            record = new ColonyRecord();
            colonies.put(colony, record);
        }
        record.squareBuilding = building.immutable();
        record.squareAnchor = anchor.immutable();
        setDirty();
        return true;
    }

    /** Forgets the colony's main square (never makes a record); true, and marked dirty, only when one was stored. */
    public boolean clearSquareMark(final int colony) {
        final ColonyRecord record = colonies.get(colony);
        if (record == null || record.squareBuilding == null && record.squareAnchor == null) {
            return false;
        }
        record.squareBuilding = null;
        record.squareAnchor = null;
        setDirty();
        return true;
    }

    /** True while the square's piece does not fit at the marker (S8 shows it in the hut window). */
    public boolean squareProblem(final int colony) {
        final ColonyRecord record = colonies.get(colony);
        return record != null && record.squareProblem;
    }

    public void setSquareProblem(final int colony, final boolean problem) {
        final ColonyRecord record = colonies.get(colony);
        if (record != null && record.squareProblem != problem) {
            record.squareProblem = problem;
            setDirty();
        }
    }

    // ---------------------------------------------------------------- the festival market's paid day (Q10)

    /** What the festival market paid on one colony day: {@link MarketMath.PaidDay#NONE} when it never paid. */
    public MarketMath.PaidDay market(final int colony) {
        final ColonyRecord record = colonies.get(colony);
        return record == null ? MarketMath.PaidDay.NONE : record.market;
    }

    /** What the stalls already paid on colony day {@code day}: 0 on a later day, "everything" on an earlier one than the stored day ({@link MarketMath.PaidDay#paidOn}). */
    public int marketPaid(final int colony, final long day) {
        return market(colony).paidOn(day);
    }

    /**
     * Adds {@code amount} (a refund is negative) to what the stalls paid on colony day {@code day}, and marks the ledger
     * for saving. A day earlier than the stored one changes nothing. The caller flushes the ledger before it pays, so a
     * restart can never pay a sale twice.
     */
    public void addMarketPaid(final int colony, final long day, final int amount) {
        ColonyRecord record = colonies.get(colony);
        if (record == null) {
            record = new ColonyRecord();
            colonies.put(colony, record);
        }
        final MarketMath.PaidDay next = record.market.plus(day, amount);
        if (!next.equals(record.market)) {
            record.market = next;
            setDirty();
        }
    }

    /** Forgets what the festival market paid (a colony that is gone): a new colony with the same id starts with nothing paid. */
    public void clearMarket(final int colony) {
        final ColonyRecord record = colonies.get(colony);
        if (record != null && !record.market.equals(MarketMath.PaidDay.NONE)) {
            record.market = MarketMath.PaidDay.NONE;
            setDirty();
        }
    }

    /** True (and remembered) when the managers have not been told about the square yet on game day {@code day}. */
    public boolean noticeDue(final int colony, final long day) {
        final ColonyRecord record = colonies.get(colony);
        if (record == null || record.noticeDay == day) {
            return false;
        }
        record.noticeDay = day;
        setDirty();
        return true;
    }

    // ---------------------------------------------------------------- the blocks tell the ledger

    /**
     * Called by our decoration blocks from {@code onRemove}, for every state change on the server (MineColonies'
     * Builder, a player, a piston, an explosion, a light switched). Only LIT changed: the entry follows (the
     * exact-state rule still removes it at take-down). Anything else: the entry is forgotten at once and the world is
     * left alone from then on; the forgotten entry is kept for the rest of this game tick ({@link #freedNow}), because
     * a player's break computes the drops after the block is gone and the drop rule needs to know whether it was paid
     * for (S10d).
     */
    public static void blockChanged(final Level level, final BlockPos pos, final BlockState old, final BlockState now) {
        if (!(level instanceof ServerLevel server) || old == now) {
            return;
        }
        final DecorLedger ledger = peek(server);
        if (ledger == null) {
            return;
        }
        final Located at = ledger.at(pos);
        if (at == null) {
            return;
        }
        if (at.entry().state() == old && onlyLitDiffers(old, now)) {
            ledger.litChanged(pos, now);
        } else {
            final Entry gone = ledger.forget(pos);
            if (gone != null) {
                ledger.stampFreed(pos, gone, server.getGameTime());
            }
        }
    }

    // ---------------------------------------------------------------- entries forgotten this tick (S10d, never saved)

    /** Entries {@link #blockChanged} forgot in game tick {@link #freedTick}, by position; cleared when a new tick stamps. */
    private final Map<Long, Entry> freed = new HashMap<>();
    private long freedTick = Long.MIN_VALUE;

    private void stampFreed(final BlockPos pos, final Entry entry, final long gameTime) {
        if (freedTick != gameTime) {
            freed.clear();
            freedTick = gameTime;
        }
        freed.put(pos.asLong(), entry);
    }

    /**
     * The entry {@link #blockChanged} forgot at {@code pos} in game tick {@code gameTime}, or {@code null}; it is
     * handed out once (and removed). Only a block of ours that changed by itself is stamped: the Decorator's own
     * removal and every direct {@link #forget} forget first and never stamp.
     */
    public Entry freedNow(final BlockPos pos, final long gameTime) {
        if (freedTick != gameTime) {
            return null;
        }
        return freed.remove(pos.asLong());
    }

    /** Same block, and the two states are equal once LIT is set the same. */
    static boolean onlyLitDiffers(final BlockState a, final BlockState b) {
        if (a.getBlock() != b.getBlock() || !a.hasProperty(Lights.LIT)) {
            return false;
        }
        return b.setValue(Lights.LIT, a.getValue(Lights.LIT)) == a;
    }

    // ---------------------------------------------------------------- saving

    /**
     * Writes this ledger's file now if anything changed since the last write, so a crash between autosaves cannot
     * lose what was placed. The file is the same one the level's own save writes.
     */
    public void flush(final ServerLevel level) {
        if (!isDirty()) {
            return;
        }
        final Path root = level.getServer().getWorldPath(LevelResource.ROOT);
        final Path data = DimensionType.getStorageFolder(level.dimension(), root).resolve("data");
        save(data.resolve(FILE + ".dat").toFile(), level.registryAccess());
    }

    /**
     * The level's autosave and {@link #flush} both come here. Written at once on the calling (server) thread, to a
     * temporary file that then replaces the old one, and marked clean only once that worked. Vanilla hands saved data
     * to NeoForge's IO workers instead, where two quick writes race for the same file and a failed one is logged and
     * forgotten with the data marked clean; for a ledger that must never lose an entry the few kilobytes are written
     * here and now, and a failed write stays dirty for the next try.
     */
    @Override
    public void save(final File file, final HolderLookup.Provider provider) {
        if (!isDirty()) {
            return;
        }
        final Path target = file.toPath();
        final Path temp = target.resolveSibling(target.getFileName() + ".cs-tmp");
        try {
            Files.createDirectories(target.getParent());
            final CompoundTag root = new CompoundTag();
            root.put("data", save(new CompoundTag(), provider));
            NbtUtils.addCurrentDataVersion(root);
            NbtIo.writeCompressed(root, temp);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (final AtomicMoveNotSupportedException noAtomic) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            setDirty(false);
            failedWrites = 0;
            writes++;
        } catch (final IOException unwritable) {
            if (failedWrites++ == 0) {
                ColonySeasons.LOGGER.warn("The decoration ledger could not be written now ({}); it is tried again on the next change "
                        + "and with the level's next save", unwritable.getClass().getSimpleName().replace("Exception", ""));
            }
        }
    }

    private int failedWrites;
    private int writes;

    /** How often this ledger was written to its file (the probe checks that a visit that changes nothing writes nothing). */
    public int writes() {
        return writes;
    }

    @Override
    public CompoundTag save(final CompoundTag tag, final HolderLookup.Provider provider) {
        tag.putInt("version", VERSION);
        final ListTag all = new ListTag();
        for (final Map.Entry<Integer, ColonyRecord> e : colonies.entrySet()) {
            final ColonyRecord record = e.getValue();
            final CompoundTag one = new CompoundTag();
            one.putInt("id", e.getKey());
            one.putLong("budgetDay", record.budgetDay);
            one.putInt("budgetUsed", record.budgetUsed);
            final ListTag charged = new ListTag();
            for (final Owner owner : record.charged) {
                final CompoundTag o = new CompoundTag();
                o.putString("kind", owner.kind().name());
                o.putLong("pos", owner.pos().asLong());
                charged.add(o);
            }
            one.put("charged", charged);
            if (record.marker != null) {
                one.putLong("marker", record.marker.asLong());   // also while a square is main: 0.1.0 reads it as the square
            }
            if (record.squareBuilding != null && record.squareAnchor != null) {
                one.putLong("squareBuilding", record.squareBuilding.asLong());
                one.putLong("squareAnchor", record.squareAnchor.asLong());
            }
            if (record.market.day() >= 0) {
                one.putLong("marketDay", record.market.day());
                one.putInt("marketPaid", record.market.paid());
            }
            one.putLong("noticeDay", record.noticeDay);
            one.putBoolean("squareProblem", record.squareProblem);
            final ListTag groups = new ListTag();
            for (final Group group : record.groups.values()) {
                groups.add(saveGroup(group));
            }
            one.put("groups", groups);
            all.add(one);
        }
        tag.put("colonies", all);
        return tag;
    }

    private static CompoundTag saveGroup(final Group group) {
        final CompoundTag tag = new CompoundTag();
        tag.putString("kind", group.owner.kind().name());
        tag.putLong("pos", group.owner.pos().asLong());
        tag.putString("festival", group.festival);
        tag.putInt("level", group.level);
        tag.putString("piece", group.piece);
        tag.putLong("decoratedDay", group.decoratedDay);
        tag.putLong("tendedDay", group.tendedDay);
        tag.putLong("treeDay", group.treeDay);
        tag.putBoolean("removing", group.removing);
        final List<BlockState> palette = new ArrayList<>();
        final Map<BlockState, Integer> indexOf = new HashMap<>();
        final long[] positions = new long[group.entries.size()];
        final int[] states = new int[group.entries.size()];
        final byte[] paid = new byte[group.entries.size()];
        int i = 0;
        for (final Map.Entry<BlockPos, Entry> e : group.entries.entrySet()) {
            final BlockState state = e.getValue().state();
            Integer at = indexOf.get(state);
            if (at == null) {
                at = palette.size();
                palette.add(state);
                indexOf.put(state, at);
            }
            positions[i] = e.getKey().asLong();
            states[i] = at;
            paid[i] = (byte) (e.getValue().paid() ? 1 : 0);
            i++;
        }
        final ListTag paletteTag = new ListTag();
        for (final BlockState state : palette) {
            paletteTag.add(NbtUtils.writeBlockState(state));
        }
        tag.put("palette", paletteTag);
        tag.putLongArray("positions", positions);
        tag.putIntArray("states", states);
        tag.putByteArray("paid", paid);
        return tag;
    }

    /** Files of a newer version read since the server started (each one warned about once; the probe counts them). */
    private static int newerVersionWarnings;

    public static int newerVersionWarnings() {
        return newerVersionWarnings;
    }

    /**
     * Reads a ledger. The file's {@code version} is read first (a file without one is version 1; versions 1, 2 and 3
     * share one layout, so all read the same way, a version 3 file with its square marks): a newer one than
     * {@link #VERSION} is read for what this version knows, with one warning naming both versions; group kinds this
     * version does not know are skipped and counted in that warning.
     */
    public static DecorLedger load(final CompoundTag tag, final HolderLookup.Provider provider) {
        final DecorLedger ledger = new DecorLedger();
        final HolderGetter<Block> blocks = provider.lookupOrThrow(Registries.BLOCK);
        final int version = tag.contains("version") ? tag.getInt("version") : 1;
        int dropped = 0;
        int unknown = 0;
        for (final Tag element : tag.getList("colonies", Tag.TAG_COMPOUND)) {
            final CompoundTag one = (CompoundTag) element;
            final int id = one.getInt("id");
            final ColonyRecord record = new ColonyRecord();
            record.budgetDay = one.getLong("budgetDay");
            record.budgetUsed = one.getInt("budgetUsed");
            for (final Tag c : one.getList("charged", Tag.TAG_COMPOUND)) {
                final CompoundTag o = (CompoundTag) c;
                try {
                    record.charged.add(new Owner(Kind.valueOf(o.getString("kind")), BlockPos.of(o.getLong("pos"))));
                } catch (final IllegalArgumentException unknownKind) {
                    // an owner kind this version does not know: it is not counted
                }
            }
            final boolean oldFile = !one.contains("charged");
            record.marker = one.contains("marker") ? BlockPos.of(one.getLong("marker")) : null;
            // version 3: the main square's mark; one key without the other reads as no mark
            if (one.contains("squareBuilding", Tag.TAG_LONG) && one.contains("squareAnchor", Tag.TAG_LONG)) {
                record.squareBuilding = BlockPos.of(one.getLong("squareBuilding"));
                record.squareAnchor = BlockPos.of(one.getLong("squareAnchor"));
            }
            // 0.2.0 Q10: what the festival market paid on one colony day; one key without the other reads as never paid
            if (one.contains("marketDay", Tag.TAG_LONG) && one.contains("marketPaid", Tag.TAG_INT)) {
                record.market = MarketMath.PaidDay.of(one.getLong("marketDay"), one.getInt("marketPaid"));
            }
            record.noticeDay = one.contains("noticeDay") ? one.getLong("noticeDay") : -1;
            record.squareProblem = one.getBoolean("squareProblem");
            ledger.colonies.put(id, record);
            for (final Tag g : one.getList("groups", Tag.TAG_COMPOUND)) {
                final CompoundTag group = (CompoundTag) g;
                if (!knownKind(group.getString("kind"))) {
                    unknown += group.getLongArray("positions").length;
                    continue;
                }
                dropped += ledger.loadGroup(id, record, group, blocks, oldFile);
            }
        }
        if (version > VERSION) {
            newerVersionWarnings++;
            ColonySeasons.LOGGER.warn("Decoration ledger: the file was written by a newer version of the mod (file version {}, this version reads {}); "
                    + "what this version knows is loaded{}", version, VERSION, unknown > 0 ? ", " + unknown + " entries of kinds it does not know are skipped" : "");
        } else if (unknown > 0) {
            ColonySeasons.LOGGER.warn("Decoration ledger: {} entries of an unknown kind skipped on load", unknown);
        }
        if (dropped > 0) {
            ColonySeasons.LOGGER.warn("Decoration ledger: {} entries dropped on load (their block no longer exists)", dropped);
        }
        return ledger;
    }

    private static boolean knownKind(final String kind) {
        for (final Kind k : Kind.values()) {
            if (k.name().equals(kind)) {
                return true;
            }
        }
        return false;
    }

    private int loadGroup(final int colony, final ColonyRecord record, final CompoundTag tag, final HolderGetter<Block> blocks,
                          final boolean oldFile) {
        final Owner owner = new Owner(Kind.valueOf(tag.getString("kind")), BlockPos.of(tag.getLong("pos")));   // a known kind (load checks)
        final Group group = new Group(owner);
        group.festival = tag.getString("festival");
        group.level = tag.getInt("level");
        group.piece = tag.getString("piece");
        group.decoratedDay = tag.contains("decoratedDay") ? tag.getLong("decoratedDay") : -1;
        group.tendedDay = tag.contains("tendedDay") ? tag.getLong("tendedDay") : -1;
        // a file of an earlier build knew no treeDay: a square it marked decorated had its whole tree up
        group.treeDay = tag.contains("treeDay") ? tag.getLong("treeDay") : group.decoratedDay;
        if (oldFile && tag.contains("chargedDay") && tag.getLong("chargedDay") == record.budgetDay) {
            record.charged.add(owner);                // a file of an earlier build kept the day's count per group
        }
        group.removing = tag.getBoolean("removing");
        final ListTag paletteTag = tag.getList("palette", Tag.TAG_COMPOUND);
        final List<BlockState> palette = new ArrayList<>();
        for (int i = 0; i < paletteTag.size(); i++) {
            palette.add(NbtUtils.readBlockState(blocks, paletteTag.getCompound(i)));
        }
        final long[] positions = tag.getLongArray("positions");
        final int[] states = tag.getIntArray("states");
        final byte[] paid = tag.getByteArray("paid");
        int dropped = 0;
        for (int i = 0; i < positions.length; i++) {
            final int at = i < states.length ? states[i] : -1;
            final BlockState state = at >= 0 && at < palette.size() ? palette.get(at) : null;
            if (state == null || state.isAir() || index.containsKey(positions[i])) {
                dropped++;
                continue;
            }
            final BlockPos pos = BlockPos.of(positions[i]);
            group.entries.put(pos, new Entry(state, i < paid.length && paid[i] != 0));
            index.put(positions[i], new Slot(colony, owner));
        }
        record.groups.put(owner, group);
        return dropped;
    }

    /** For the probe: every listed position of one colony with its state. */
    public Map<BlockPos, Entry> entries(final int colony) {
        final Map<BlockPos, Entry> all = new LinkedHashMap<>();
        final ColonyRecord record = colonies.get(colony);
        if (record != null) {
            for (final Group group : record.groups.values()) {
                all.putAll(group.entries);
            }
        }
        return all;
    }

    /** Every listed position of the level. */
    public Set<Long> positions() {
        return Set.copyOf(index.keySet());
    }
}
