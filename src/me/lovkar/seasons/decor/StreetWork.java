package me.lovkar.seasons.decor;

import com.ldtteam.structurize.api.RotationMirror;
import com.minecolonies.api.colony.IColony;
import me.lovkar.seasons.calendar.CalendarManager;
import me.lovkar.seasons.calendar.Season;
import me.lovkar.seasons.festival.Gathering;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Decorator at the spots the player marked with his wand (0.2.0, Q11): which marks are open, one visit that dresses
 * a mark and every other open one within {@link #STREET_REACH}, and the take-down. Every mark is a ledger group of its own
 * ({@link DecorLedger.Owner#street}, kind {@link DecorLedger.Kind#STREET}); all marks of a colony share one slot of the
 * day's budget ({@link DecorLedger.Owner#STREET_BUDGET}), and the pieces are free (no item taken, none given back, a
 * broken one drops nothing). Only {@link DecorPlacer} sets or clears a block.
 *
 * <p>A mark is done when its group was finished on some day for the piece it wants now ({@link StreetPieces#pieceKey}: a
 * new season swaps only the planters). Marks are open only while a decoration window is open; {@link DecorWork} offers
 * them after the town hall and the square, before the other buildings. A body standing in a cell keeps that cell (that
 * mark) open, and the other marks go up; a post goes up whole or not at all (both halves pass the cell rule first, and a
 * post found with one half listed has it taken down by the exact-state rule and goes up again later); a garland hangs
 * only between two whole posts, and comes down when one of them went.</p>
 */
public final class StreetWork {

    /** Blocks (on x and z) round the mark he walked to within which one visit also serves the other marks. */
    public static final int STREET_REACH = 16;

    private StreetWork() {
    }

    // ---------------------------------------------------------------- choosing

    /** The in-game season now ({@code null} before the calendar's first poll). */
    static @Nullable Season season() {
        final CalendarManager.Snapshot now = CalendarManager.current();
        return now == null || now.season() == null ? null : now.season().season();
    }

    /** True when the mark's group was finished for the piece it wants now. */
    public static boolean done(final DecorLedger ledger, final int colony, final StreetMarks.Mark mark, final @Nullable Season season) {
        final DecorLedger.GroupView group = ledger.group(colony, DecorLedger.Owner.street(mark.pos()));
        return group != null && group.decoratedDay() >= 0 && group.piece().equals(StreetPieces.pieceKey(mark, season));
    }

    /** True when a finished mark is due for tending today: finished on an earlier day, not looked at today, not being removed. */
    static boolean due(final DecorLedger ledger, final int colony, final StreetMarks.Mark mark, final long day) {
        final DecorLedger.GroupView group = ledger.group(colony, DecorLedger.Owner.street(mark.pos()));
        return group != null && group.decoratedDay() >= 0 && group.decoratedDay() < day && group.tendedDay() < day && !group.removing();
    }

    /**
     * The marks that are not done, as targets, nearest {@code from} first: only while a decoration window is open, only
     * marks in loaded chunks that keep their rules ({@link StreetMarks#placeRule}) and were not left for today
     * ({@link DecorWork#MAX_FRUITLESS_VISITS}); a planter only once the season is known.
     */
    public static List<DecorWork.Target> open(final ServerLevel level, final IColony colony, final DecorLedger ledger, final BlockPos from,
                                              final long day) {
        if (!DecorPhase.windowOpen(CalendarManager.current())) {
            return List.of();
        }
        final int id = colony.getID();
        final List<StreetMarks.Mark> marks = ledger.marks(id);
        if (marks.isEmpty()) {
            return List.of();
        }
        final Season season = season();
        final List<DoorFinder.Box> boxes = StreetMarks.buildingBoxes(colony);
        final List<DecorWork.Target> found = new ArrayList<>();
        for (final StreetMarks.Mark mark : marks) {
            if (done(ledger, id, mark, season) || ledger.fruitless(id, DecorLedger.Owner.street(mark.pos()), day) >= DecorWork.MAX_FRUITLESS_VISITS) {
                continue;
            }
            if (!level.hasChunkAt(mark.pos()) || mark.type() == StreetMarks.Type.PLANTER && season == null
                    || StreetMarks.placeRule(level, colony, ledger, mark, boxes) != null) {
                continue;
            }
            found.add(target(level, mark, from));
        }
        final BlockPos at = from == null ? colony.getCenter() : from;
        found.sort(Comparator.comparingDouble((DecorWork.Target t) -> t.owner().pos().distSqr(at)).thenComparing(t -> t.owner().pos()));
        return found;
    }

    /** A mark as a place to work at: its group's key, its cells' box, and a standing place beside it ({@link #stand}). */
    public static DecorWork.Target target(final ServerLevel level, final StreetMarks.Mark mark, final @Nullable BlockPos from) {
        final List<BlockPos> cells = StreetPieces.cells(mark);
        BlockPos min = cells.get(0);
        BlockPos max = cells.get(0);
        for (final BlockPos cell : cells) {
            min = new BlockPos(Math.min(min.getX(), cell.getX()), Math.min(min.getY(), cell.getY()), Math.min(min.getZ(), cell.getZ()));
            max = new BlockPos(Math.max(max.getX(), cell.getX()), Math.max(max.getY(), cell.getY()), Math.max(max.getZ(), cell.getZ()));
        }
        return new DecorWork.Target(DecorLedger.Owner.street(mark.pos()), new DoorFinder.Box(min, max, mark.pos()), null,
                stand(level, cells, from == null ? mark.pos() : from), "", RotationMirror.NONE);
    }

    /**
     * Where he stands: a standing place ({@link Gathering#standable}) beside one of the cells, nearest {@code from}, whose
     * feet and head are no cell of the mark; the mark's own cell when there is none (he works from where he is).
     */
    static BlockPos stand(final ServerLevel level, final List<BlockPos> cells, final BlockPos from) {
        final List<BlockPos> beside = new ArrayList<>();
        for (final BlockPos cell : cells) {
            for (final Direction side : Direction.Plane.HORIZONTAL) {
                beside.add(cell.relative(side));
            }
        }
        beside.sort(Comparator.comparingDouble((BlockPos p) -> p.distSqr(from)).thenComparing(p -> p));
        for (final BlockPos at : beside) {
            final BlockPos stand = Gathering.standable(level, at);
            if (stand != null && !cells.contains(stand) && !cells.contains(stand.above())) {
                return stand;
            }
        }
        return cells.get(0);
    }

    // ---------------------------------------------------------------- one visit

    /** What one visit did at one mark. */
    private record One(int placed, int open, boolean complete) {
    }

    /**
     * One visit for the mark at {@code target} (decorating, or tending once a game day): that mark and every other mark
     * within {@link #STREET_REACH} that is open (decorating) or due (tending: a mark that is not done waits for a
     * decorating visit, inside the day's budget), each into its own group, the garlands after the posts. A tending visit
     * notes the day on every mark it served, also on one its rule turned away (the ground under it gone, ...), so the
     * same mark is not offered again and again that day. The day's budget is counted once
     * ({@link DecorLedger.Owner#STREET_BUDGET}) when anything was placed and he was not tending; the ledger is written once.
     */
    public static DecorWork.Outcome visit(final ServerLevel level, final int colony, final DecorWork.Target target, final long day,
                                          final DecorLedger ledger, final boolean tending) {
        final StreetMarks.Mark first = ledger.markAt(colony, target.owner().pos());
        if (first == null) {
            return DecorWork.Outcome.of(0, 0, false, "the mark is gone");
        }
        final IColony owner = StreetMarks.colonyOf(level, colony);
        if (owner == null) {
            return DecorWork.Outcome.of(0, 0, false, "the colony is gone");
        }
        final Season season = season();
        final List<StreetMarks.Mark> serve = new ArrayList<>();
        serve.add(first);
        for (final StreetMarks.Mark mark : ledger.marks(colony)) {
            if (mark.equals(first) || Math.abs(mark.pos().getX() - first.pos().getX()) > STREET_REACH
                    || Math.abs(mark.pos().getZ() - first.pos().getZ()) > STREET_REACH) {
                continue;
            }
            if (tending ? due(ledger, colony, mark, day) : !done(ledger, colony, mark, season)) {
                serve.add(mark);
            }
        }
        // the garlands last: they hang between posts this same visit may put up
        serve.sort(Comparator.comparing((StreetMarks.Mark m) -> m.type() == StreetMarks.Type.GARLAND));
        // the posts that are whole before this visit: a garland on a post that went comes down first and waits a visit
        final Set<BlockPos> wholeBefore = new HashSet<>();
        for (final StreetMarks.Mark mark : serve) {
            if (mark.type() == StreetMarks.Type.GARLAND) {
                for (final BlockPos post : StreetMarks.posts(mark)) {
                    if (whole(ledger, colony, post)) {
                        wholeBefore.add(post);
                    }
                }
            }
        }
        final String festival = DecorWork.festival();
        int placed = 0;
        int open = 0;
        boolean complete = true;
        for (final StreetMarks.Mark mark : serve) {
            final One one = one(level, ledger, colony, owner, mark, season, festival, day, tending, wholeBefore);
            placed += one.placed();
            open += one.open();
            complete &= one.complete();
        }
        if (placed > 0 && !tending) {
            ledger.charge(colony, target.owner().budgetKey(), day);
        }
        ledger.flush(level);
        return DecorWork.Outcome.of(placed, open, complete, "street: " + serve.size() + " marks, " + placed + " placed, " + open + " still open");
    }

    /** True when the post whose lower half is at {@code post} stands whole: its group lists both halves. */
    static boolean whole(final DecorLedger ledger, final int colony, final BlockPos post) {
        final DecorLedger.GroupView group = ledger.group(colony, DecorLedger.Owner.street(post));
        return group != null && group.entries().containsKey(post) && group.entries().containsKey(post.above());
    }

    private static One one(final ServerLevel level, final DecorLedger ledger, final int colony, final IColony owner, final StreetMarks.Mark mark,
                           final @Nullable Season season, final String festival, final long day, final boolean tending,
                           final Set<BlockPos> wholeBefore) {
        final DecorLedger.Owner key = DecorLedger.Owner.street(mark.pos());
        final List<BlockPos> cells = StreetPieces.cells(mark);
        // a vanilla piece of ours broken by hand (a planter) never told the ledger: its entry is forgotten, the cell is open again
        DecorLedger.GroupView group = ledger.group(colony, key);
        if (group != null) {
            for (final BlockPos pos : group.entries().keySet()) {
                if (level.hasChunkAt(pos) && level.getBlockState(pos).isAir()) {
                    ledger.forget(pos);
                }
            }
        }
        DecorPlacer.followLeaves(level, ledger, colony, key);
        final String piece = StreetPieces.pieceKey(mark, season);
        group = ledger.group(colony, key);
        if (group != null && !group.entries().isEmpty() && !group.piece().isEmpty() && !group.piece().equals(piece)) {
            StreetMarks.takeDown(level, ledger, colony, key);   // another piece (a new season's planter): down first
            group = ledger.group(colony, key);
        }
        if (StreetMarks.placeRule(level, owner, ledger, mark) != null) {
            return fruit(ledger, colony, key, day, tending, 0, cells.size(), false);
        }
        final int listed = listed(group, cells);
        if (mark.type() == StreetMarks.Type.POST && listed == 1) {
            // half a post: that half comes down by the exact-state rule, the post goes up whole on a later visit
            StreetMarks.takeDown(level, ledger, colony, key);
            return fruit(ledger, colony, key, day, tending, 0, cells.size(), false);
        }
        if (mark.type() == StreetMarks.Type.GARLAND) {
            final BlockPos[] ends = StreetMarks.posts(mark);
            final boolean whole = whole(ledger, colony, ends[0]) && whole(ledger, colony, ends[1]);
            final boolean wasWhole = wholeBefore.contains(ends[0]) && wholeBefore.contains(ends[1]);
            if (!whole || !wasWhole && listed > 0) {
                if (listed > 0) {
                    StreetMarks.takeDown(level, ledger, colony, key);
                }
                return fruit(ledger, colony, key, day, tending, 0, cells.size(), false);
            }
        }
        final List<DecorPlacer.Slot> slots = StreetPieces.slots(mark, season);
        final List<DecorPlacer.Slot> todo = new ArrayList<>();
        for (final DecorPlacer.Slot slot : slots) {
            if (group == null || !group.entries().containsKey(slot.pos())) {
                todo.add(slot);
            }
        }
        // a body in a cell keeps that cell open, a thin garland's cell too (never into a cell a body stands in); a post
        // waits whole
        final List<Entity> near = DecorPlacer.blockers(level, todo);
        if (!near.isEmpty() && todo.removeIf(slot -> inBody(near, slot.pos())) && mark.type() == StreetMarks.Type.POST) {
            return fruit(ledger, colony, key, day, tending, 0, cells.size(), false);
        }
        if (mark.type() == StreetMarks.Type.POST && todo.size() == 2) {
            // both halves pass the cell rule before either goes up (nobody stands in the upper half's cell, ...)
            for (final DecorPlacer.Slot slot : todo) {
                if (DecorPlacer.refusal(level, ledger, colony, slot.pos(), DecorPlacer.normalise(slot.state(), slot.pos()), true, near) != null) {
                    return fruit(ledger, colony, key, day, tending, 0, cells.size(), false);
                }
            }
        }
        int placed = 0;
        if (!todo.isEmpty()) {
            ledger.plan(colony, key, festival, 0, piece);
            final DecorPlacer.PlaceResult result = DecorPlacer.place(level, ledger, colony, key, festival, 0, todo, DecorPlacer.Stock.FREE);
            placed = result.count();
            if (placed > 0 && todo.get(0).state().getBlock() instanceof LeavesBlock) {
                DecorPlacer.settleLeaves(level, ledger, colony, key);
            }
        } else if (!slots.isEmpty()) {
            ledger.plan(colony, key, festival, 0, piece);
        }
        group = ledger.group(colony, key);
        final int up = listed(group, cells);
        final int open = slots.isEmpty() ? 0 : cells.size() - up;
        final boolean complete = !slots.isEmpty() && open == 0;
        if (group != null && group.entries().isEmpty()) {
            ledger.dropGroup(colony, key);            // nothing up (a body in the cell, no season yet): no empty group left behind
        } else if (group != null && complete) {
            ledger.setDecorated(colony, key, day);
        }
        return fruit(ledger, colony, key, day, tending, placed, open, complete);
    }

    /**
     * The end of every visit at one mark: a tending visit notes the day on the mark's group (when it still has one),
     * whatever came of it, so a mark turned away is tended again only the next day; a decorating visit counts towards the
     * fruitless limit, and anything placed starts that count again.
     */
    private static One fruit(final DecorLedger ledger, final int colony, final DecorLedger.Owner key, final long day, final boolean tending,
                             final int placed, final int open, final boolean complete) {
        if (tending) {
            ledger.setTended(colony, key, day);
        } else {
            if (placed > 0) {
                ledger.clearFruitless(colony, key);
            } else if (open > 0) {
                ledger.addFruitless(colony, key, day);
            }
        }
        return new One(placed, open, complete);
    }

    /** True when one of {@code bodies} strictly overlaps the cell (one standing on top of it does not). */
    private static boolean inBody(final List<Entity> bodies, final BlockPos cell) {
        final AABB box = new AABB(cell);
        for (final Entity body : bodies) {
            if (body.getBoundingBox().intersects(box)) {
                return true;
            }
        }
        return false;
    }

    private static int listed(final @Nullable DecorLedger.GroupView group, final List<BlockPos> cells) {
        if (group == null) {
            return 0;
        }
        int n = 0;
        for (final BlockPos cell : cells) {
            if (group.entries().containsKey(cell)) {
                n++;
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- taking down

    /**
     * The Decorator's take-down at a mark: its group and the other marks' groups within {@link #STREET_REACH}, nearest
     * first, by the removal rule, at most {@link DecorWork#SQUARE_SLICE} cells in all; groups with cells in unloaded chunks
     * are marked for the sweep. The marks stay for the next window. The day's budget is the caller's.
     */
    public static DecorPlacer.Removed takeDown(final ServerLevel level, final int colony, final DecorLedger.Owner owner,
                                               final DecorPlacer.Stock sink, final DecorLedger ledger) {
        final List<DecorLedger.Owner> order = new ArrayList<>();
        order.add(owner);
        final List<DecorLedger.GroupView> others = new ArrayList<>();
        for (final DecorLedger.GroupView group : ledger.groups(colony)) {
            final BlockPos at = group.owner().pos();
            if (group.owner().kind() == DecorLedger.Kind.STREET && !group.owner().equals(owner) && !group.entries().isEmpty()
                    && Math.abs(at.getX() - owner.pos().getX()) <= STREET_REACH && Math.abs(at.getZ() - owner.pos().getZ()) <= STREET_REACH) {
                others.add(group);
            }
        }
        others.sort(Comparator.comparingDouble((DecorLedger.GroupView g) -> g.owner().pos().distSqr(owner.pos())).thenComparing(g -> g.owner().pos()));
        for (final DecorLedger.GroupView group : others) {
            order.add(group.owner());
        }
        DecorPlacer.Removed all = DecorPlacer.Removed.NONE;
        for (final DecorLedger.Owner one : order) {
            final int budget = DecorWork.SQUARE_SLICE - all.touched();
            if (budget <= 0) {
                break;
            }
            final DecorPlacer.Removed removed = DecorPlacer.remove(level, ledger, colony, one, sink, budget);
            if (removed.waiting() > 0) {
                ledger.markRemoving(colony, one);
            }
            all = all.plus(removed);
        }
        return all;
    }

    // ---------------------------------------------------------------- the sweep

    /**
     * The sweep's rule for a mark's group: it goes when the mark no longer exists, when a loaded cell of it lies outside
     * this colony's claim, or when a building's box covers one of its cells now. A cell in an unloaded chunk alone never
     * makes it go.
     */
    static boolean gone(final ServerLevel level, final IColony colony, final DecorLedger ledger, final BlockPos pos) {
        final StreetMarks.Mark mark = ledger.markAt(colony.getID(), pos);
        if (mark == null) {
            return true;
        }
        final List<DoorFinder.Box> boxes = StreetMarks.buildingBoxes(colony);
        for (final BlockPos cell : StreetPieces.cells(mark)) {
            if (level.hasChunkAt(cell) && DecorPlacer.colonyAt(level, cell) != colony.getID()) {
                return true;
            }
            if (StreetMarks.inside(boxes, cell)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when a loaded cell of the mark lies outside its colony's claim now (the claim shrank or moved under it; Q7,
     * H25). A cell in an unloaded chunk never counts. Such a mark can no longer be served, nor removed with the wand
     * (a click there belongs to the colony that claims the cell, or to none), so the sweep drops it
     * ({@link DecorEvents#sweep}).
     */
    static boolean outOfClaim(final ServerLevel level, final IColony colony, final StreetMarks.Mark mark) {
        for (final BlockPos cell : StreetPieces.cells(mark)) {
            if (level.hasChunkAt(cell) && DecorPlacer.colonyAt(level, cell) != colony.getID()) {
                return true;
            }
        }
        return false;
    }

    /** For the probe: every mark's group of a colony in one map (mark position, entries). */
    public static Map<BlockPos, Integer> sizes(final DecorLedger ledger, final int colony) {
        final Map<BlockPos, Integer> out = new java.util.LinkedHashMap<>();
        for (final StreetMarks.Mark mark : ledger.marks(colony)) {
            final DecorLedger.GroupView group = ledger.group(colony, DecorLedger.Owner.street(mark.pos()));
            out.put(mark.pos(), group == null ? 0 : group.size());
        }
        return out;
    }
}
