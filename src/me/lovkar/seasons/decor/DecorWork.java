package me.lovkar.seasons.decor;

import com.ldtteam.structurize.api.RotationMirror;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.workorders.IServerWorkOrder;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.MessageUtils;
import me.lovkar.seasons.ColonySeasons;
import me.lovkar.seasons.SeasonsConfig;
import me.lovkar.seasons.block.FestivalMarkerBlock;
import me.lovkar.seasons.block.GiftPileBlock;
import me.lovkar.seasons.block.SeasonsBlocks;
import me.lovkar.seasons.calendar.CalendarManager;
import me.lovkar.seasons.colony.BuildingDecorator;
import me.lovkar.seasons.colony.BuildingTownSquare;
import me.lovkar.seasons.festival.Gathering;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the Decorator does, as calls the AI, the commands, the events and the placing probe share (design 4,
 * "Decorating", "Tending", "Taking down"). Every call takes its target explicitly, so the probe can drive pasted
 * buildings that MineColonies does not know, and nothing here keeps state between calls: progress is the ledger.
 *
 * <ul>
 *   <li>{@link #nextToDecorate}: the town hall first, then the square, then the nearest building to the hut; only a
 *       target that still has a placeable slot (in stock, or free), that is not under construction, and while the
 *       colony's buildings-per-day budget lasts;</li>
 *   <li>{@link #decorate}: places what the plan and the stock allow; a building whose plan is complete (or empty) is
 *       marked decorated for its hut level, so it is planned again only when the Decorator's hut level changes;</li>
 *   <li>{@link #nextToTend} / {@link #tend}: once a game day per decorated group, the plan again, what went missing
 *       put back;</li>
 *   <li>{@link #nextToTakeDown} / {@link #takeDown}: one group at a time, by the removal rule, at most
 *       {@link #SQUARE_SLICE} cells a visit (the grand tree comes down over a few visits).</li>
 * </ul>
 *
 * <p>The square (S10c, from the third in-game test): he stands outside the piece's box while he builds or tends it
 * ({@link #squareStand}), and a visit first moves every colonist out of the box ({@link #evict}); no block with
 * collision goes into a cell an entity stands in. A target whose visits place nothing {@link #MAX_FRUITLESS_VISITS}
 * times in a game day (a cell that cannot be filled now, such as a lantern on a planter cell the grass keeps) is left
 * for the rest of that day, and he moves on to the next one; it is back in the order the next day. The tree is ready
 * to light once the cells of the tree itself are up ({@link DecorLedger#setTreeReady}), whatever else of the square is
 * still open.</p>
 *
 * <p>The seasonal touches (0.2.0, Q12, [B] owner answer of 20 Nov 2026: small seasonal touches in the planters between
 * the festivals, the full sets only in festival windows): {@link #nextTouches} / {@link #touch} put the in-game season's
 * touch ({@link SquareSets#touchPath}) on every {@code bed} cell of the main Town Square, swap it when the season
 * changes, and take it down when a festival set wants the square or the order is Bare. They are a ledger group of their
 * own ({@link DecorLedger.Kind#TOUCHES}), never "decorations up": no phase, no take-down, no budget, no notice; they
 * cost nothing and come back only once the square's own group lists nothing (after the window's take-down).</p>
 *
 * <p>The street (0.2.0, Q11): the spots the player marked with the Decorator's wand are offered right after the square,
 * before the other buildings, all of them on one slot of the day's budget, and the street lights on fences
 * ({@code decorator.streetLights}) after the buildings; the work itself is {@link StreetWork}'s and {@link StreetLights}',
 * this class only hands them their targets, visits and take-downs.</p>
 */
public final class DecorWork {

    /** Cells of the square placed per visit at most, and cells of any group taken down per visit at most. */
    public static final int SQUARE_SLICE = 256;
    /**
     * Visits in one game day that place nothing and leave cells open, before the target is left for the rest of that
     * day (S10c: one cell that could not be filled kept him at the square a whole game day).
     */
    public static final int MAX_FRUITLESS_VISITS = 3;
    /** He stands on a ring this many blocks outside the square's box at most (the nearest ring that has room). */
    public static final int STAND_RING = 3;

    /**
     * One place to work on. {@code building} is the building for a building, the Town Square for the square on a
     * Town Square (0.2.0, Q3), and null for the square at a festival marker and for a probe's pasted house;
     * {@code stand} is where he walks to; {@code pack} and {@code rotation} are the square's (the marker's facing as a
     * rotation, or the Town Square's own with its mirror stripped, {@link PieceLoader#forSquare}).
     */
    public record Target(DecorLedger.Owner owner, DoorFinder.Box box, IBuilding building, BlockPos stand, String pack, RotationMirror rotation) {

        public static Target building(final IBuilding building) {
            final DoorFinder.Box box = DoorFinder.Box.of(building);
            return new Target(DecorLedger.Owner.building(building.getPosition()), box, building, building.getPosition(), "", RotationMirror.NONE);
        }

        /** A house MineColonies does not know (the placing probe pastes real hut blueprints). */
        public static Target box(final DoorFinder.Box box) {
            return new Target(DecorLedger.Owner.building(box.hut()), box, null, box.hut(), "", RotationMirror.NONE);
        }

        public static Target square(final BlockPos marker, final Direction facing, final String pack) {
            return square(marker, facing, pack, marker);
        }

        /** The square with the place he stands at while he works on it (outside the piece's box, {@link #squareStand}). */
        public static Target square(final BlockPos marker, final Direction facing, final String pack, final BlockPos stand) {
            return new Target(DecorLedger.Owner.square(marker), new DoorFinder.Box(marker, marker, marker), null,
                    stand == null ? marker : stand.immutable(), pack, PieceLoader.rotation(facing));
        }

        /**
         * The centrepiece of a Town Square at its {@code tree} cell ({@code anchor}, the resolver's): the same group key
         * as the marker's square ({@link DecorLedger.Owner#square}), turned by the square's rotation without its mirror.
         */
        public static Target townSquare(final BuildingTownSquare square, final BlockPos anchor) {
            return new Target(DecorLedger.Owner.square(anchor), new DoorFinder.Box(anchor, anchor, anchor), square, anchor.immutable(),
                    PieceLoader.PACK, PieceLoader.forSquare(square.getRotationMirror()));
        }

        /** The marker's facing this target's rotation stands for (a Town Square's: the facing of its rotation). */
        public Direction facing() {
            return PieceLoader.facing(rotation);
        }

        /**
         * The seasonal touches of a Town Square's target {@code square} (Q12): the same building, pack and rotation, the
         * touches' own group at the same anchor, and the place he stands at ({@code null}: the anchor).
         */
        public static Target touches(final Target square, final BlockPos stand) {
            final BlockPos anchor = square.owner().pos();
            return new Target(DecorLedger.Owner.touches(anchor), square.box(), square.building(), stand == null ? anchor : stand.immutable(),
                    square.pack(), square.rotation());
        }

        /** The same target with the place he stands at ({@code null}: the anchor). */
        public Target withStand(final BlockPos at) {
            return new Target(owner, box, building, at == null ? owner.pos() : at.immutable(), pack, rotation);
        }

        /** True for the centrepiece of a Town Square (not the festival marker's). */
        public boolean onTownSquare() {
            return building instanceof BuildingTownSquare;
        }
    }

    /**
     * What one visit did: blocks placed, slots that are still open after it (no stock, or a slice used up), whether
     * the group is complete now, a status line, and vanilla items the square is short of (the AI asks for them).
     */
    public record Outcome(int placed, int open, boolean complete, String status, Map<Item, Integer> missing) {
        static Outcome of(final int placed, final int open, final boolean complete, final String status) {
            return new Outcome(placed, open, complete, status, Map.of());
        }
    }

    /** What one visit to the square's planters did (Q12): blocks taken down, blocks placed, cells still open, a status line. */
    public record TouchOutcome(int removed, int placed, int open, String status) {
    }

    private DecorWork() {
    }

    // ---------------------------------------------------------------- small facts

    /** The game day ({@code day time / 24000}). */
    public static long day(final Level level) {
        return level.getDayTime() / 24000L;
    }

    /** The festival a new group goes up for: the first open decoration window, or "". */
    public static String festival() {
        final CalendarManager.Snapshot snapshot = CalendarManager.current();
        if (snapshot == null || snapshot.festivals() == null || snapshot.festivals().decor().isEmpty()) {
            return "";
        }
        return snapshot.festivals().decor().get(0);
    }

    /** Buildings a hut of this level decorates (or clears) per game day: base + per level x level. */
    public static int quota(final int hutLevel) {
        return SeasonsConfig.buildingsPerDayBase() + SeasonsConfig.buildingsPerDayPerLevel() * Math.max(0, hutLevel);
    }

    /** Under construction: an upgrade or repair is pending, or any work order stands inside its box (design 3.2). */
    public static boolean busy(final IColony colony, final IBuilding building) {
        if (building == null) {
            return false;
        }
        try {
            if (building.isPendingConstruction()) {
                return true;
            }
        } catch (final RuntimeException unknown) {
            return true;
        }
        return orderInside(colony, DoorFinder.Box.of(building));
    }

    /** True when an open work order of the colony stands inside the box. */
    public static boolean orderInside(final IColony colony, final DoorFinder.Box box) {
        if (colony == null) {
            return false;
        }
        final List<IServerWorkOrder> orders = new ArrayList<>(colony.getWorkManager().getWorkOrders().values());
        for (final IServerWorkOrder order : orders) {
            final BlockPos at = order.getLocation();
            if (at != null && box.contains(at)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The colony's buildings in the Decorator's order: town hall, then by distance to {@code from}, ties by position. A
     * Town Square is not one of them (0.2.0: it has no doors, and its decorations are the square's own, design 4.1).
     */
    public static List<IBuilding> buildingsInOrder(final IColony colony, final BlockPos from) {
        final List<IBuilding> all = new ArrayList<>(colony.getServerBuildingManager().getBuildings().values());
        all.removeIf(b -> b instanceof BuildingTownSquare);
        final IBuilding hall = colony.getServerBuildingManager().getTownHall();
        all.sort(Comparator.comparing((IBuilding b) -> b != hall)
                .thenComparingDouble(b -> b.getPosition().distSqr(from))
                .thenComparing(IBuilding::getPosition));
        return all;
    }

    // ---------------------------------------------------------------- choosing a target

    /**
     * The next place to decorate, or {@code null} when there is nothing (left for today). A building whose plan
     * turns out empty (no door, no anchor) is marked done for this hut level on the way.
     */
    public static Target nextToDecorate(final ServerLevel level, final IColony colony, final int hutLevel, final BlockPos from,
                                        final DecorLedger ledger, final DecorPlacer.Stock stock, final long day) {
        return nextToDecorate(level, colony, hutLevel, from, ledger, stock, day, null);
    }

    /**
     * {@link #nextToDecorate(ServerLevel, IColony, int, BlockPos, DecorLedger, DecorPlacer.Stock, long)} for a Decorator
     * who works {@code assigned} (the Town Square whose Decorator module lists him, or {@code null}): the colony's main
     * Town Square is his only then ([B] design 4.3); the festival marker is open to any Decorator, as in 0.1.0.
     */
    public static Target nextToDecorate(final ServerLevel level, final IColony colony, final int hutLevel, final BlockPos from,
                                        final DecorLedger ledger, final DecorPlacer.Stock stock, final long day,
                                        final BuildingTownSquare assigned) {
        final int id = colony.getID();
        final boolean costs = SeasonsConfig.needsMaterials();
        final boolean budgetLeft = ledger.budgetUsed(id, day) < quota(hutLevel);
        final List<IBuilding> buildings = buildingsInOrder(colony, from);
        final IBuilding hall = colony.getServerBuildingManager().getTownHall();
        final List<Target> order = new ArrayList<>();
        if (hall != null) {
            order.add(Target.building(hall));
        }
        final Target square = stock.kinds().tree() ? centrepiece(level, colony, ledger, assigned) : null;
        if (square != null) {
            order.add(square);
        }
        // Q11: the spots marked with the wand that are not done, nearest first, one slot of the day's budget for all of them
        order.addAll(StreetWork.open(level, colony, ledger, from, day));
        for (final IBuilding b : buildings) {
            if (b != hall) {
                order.add(Target.building(b));
            }
        }
        for (final Target target : order) {
            final DecorLedger.GroupView group = ledger.group(id, target.owner());
            final boolean done = target.owner().kind() == DecorLedger.Kind.SQUARE ? squareDone(group, target, hutLevel)
                    : group != null && group.decoratedDay() >= 0 && group.level() == hutLevel;
            if (done) {
                continue;
            }
            if (ledger.fruitless(id, target.owner(), day) >= MAX_FRUITLESS_VISITS) {
                continue;                             // left for the rest of today (S10c): the next one, back tomorrow
            }
            // counted today already (also when its group was dropped since, as a work order does): no second slot
            final boolean chargedToday = ledger.chargedOn(id, target.owner().budgetKey(), day);
            if (!chargedToday && !budgetLeft) {
                continue;
            }
            if (target.owner().kind() == DecorLedger.Kind.STREET) {
                return target;                        // StreetWork.open offers only marks with work
            }
            if (target.building() != null && (target.building().getBuildingLevel() < 1 || busy(colony, target.building()))) {
                continue;
            }
            if (target.owner().kind() == DecorLedger.Kind.SQUARE) {
                if (squareReady(level, ledger, id, target, hutLevel, stock)) {
                    return withStand(level, ledger, id, target, hutLevel, from);
                }
                continue;
            }
            final DecorPlanner.Plan plan = DecorPlanner.plan(level, target.box(), hutLevel, SeasonsConfig.maxPerSide(), ledger, id,
                    target.owner(), costs, stock.kinds());
            if (!plan.loaded()) {
                continue;
            }
            if (plan.slots().isEmpty()) {
                ledger.plan(id, target.owner(), festival(), hutLevel, "");
                ledger.setDecorated(id, target.owner(), day);
                continue;
            }
            if (anyInStock(plan.slots(), stock)) {
                return target;
            }
        }
        // Q11: the street lights on fences, last (the switch, the lights kind and the window are StreetLights.next's to check)
        final Target lights = StreetLights.next(level, colony, ledger, stock.kinds(), day);
        if (lights != null && (budgetLeft || ledger.chargedOn(id, lights.owner(), day))) {
            return lights;
        }
        return null;
    }

    /**
     * The square target with its stand: the standing place nearest {@code from} on the nearest ring outside the box of
     * the piece (the group's piece, else the one of this hut level) and of the group's entries; the marker itself only
     * when there is no room round the tree.
     */
    public static Target withStand(final ServerLevel level, final DecorLedger ledger, final int colony, final Target square,
                                   final int hutLevel, final BlockPos from) {
        final BlockPos marker = square.owner().pos();
        final String path = squarePath(ledger, colony, marker, hutLevel > 0 ? piecePath(square, hutLevel) : null);
        final AABB box = squareBox(level, ledger, colony, marker, square.rotation(), square.pack(), path, fixtureCells(square));
        return square.withStand(squareStand(level, box, marker, from));
    }

    /**
     * The box the centrepiece of a Town Square takes at its {@code tree} cell ({@code anchor}): the cells of the piece
     * its group was planned for (else the one its order wants at {@code hutLevel}) and the cells the group lists;
     * {@code null} when neither is known yet. The square's fixtures at its own {@code gift} and {@code launch} cells are
     * not the tree's: they stand on the square's paving, not in this box. The gathering and the Decorator stand outside it.
     */
    public static AABB townSquareBox(final ServerLevel level, final DecorLedger ledger, final int colony, final BuildingTownSquare square,
                                     final BlockPos anchor, final int hutLevel) {
        final Target target = Target.townSquare(square, anchor);
        final String path = squarePath(ledger, colony, anchor, hutLevel > 0 ? piecePath(target, hutLevel) : null);
        return squareBox(level, ledger, colony, anchor, target.rotation(), target.pack(), path, fixtureCells(target));
    }

    /** The level from which a Town Square's own gift pile stands at its {@code gift} cell (design 4.2: from square level 1). */
    public static final int SQUARE_GIFT_LEVEL = 1;

    /**
     * The festival's fixtures of a Town Square for a piece level (design 4.3 and 4.4, Q4), relative to its anchor: the
     * gift pile at the square's {@code gift} cell (facing away from the tree) from {@link #SQUARE_GIFT_LEVEL}, and the
     * firework crate at its {@code launch} cell from {@link PieceLoader#FIREWORK_CRATE_LEVEL}, while the square holds
     * the Winter Festival's fixtures ({@link SquareSets#winterFixtures}); the lowest cell should a tag have several. None
     * at the festival marker, before the calendar's first poll, or while the hut's tags read empty. With the Winter
     * Festival's set itself (not under Bare) also the tree's foot (Q12, {@link #foot}), after the gift pile and the crate.
     */
    public static List<PieceLoader.PieceCell> fixtures(final Target target, final int pieceLevel) {
        if (!(target.building() instanceof BuildingTownSquare square)) {
            return List.of();
        }
        final CalendarManager.Snapshot now = CalendarManager.current();
        if (now == null) {
            return List.of();
        }
        final List<PieceLoader.PieceCell> out = new ArrayList<>();
        final SquareSets.Set wanted = SquareSets.wanted(square.order(), now);
        if (SquareSets.winterFixtures(wanted, now)) {
            winterFixtures(target, square, pieceLevel, out);
        }
        if (wanted == SquareSets.WINTER) {
            out.add(foot());
        }
        // Q10: the festival market's stalls at the stall tags, only while the Trade Post bridge is on (none otherwise)
        out.addAll(MarketStalls.fixtures(target));
        return out;
    }

    private static void winterFixtures(final Target target, final BuildingTownSquare square, final int pieceLevel,
                                       final List<PieceLoader.PieceCell> out) {
        final BlockPos anchor = target.owner().pos();
        final BlockPos gift = lowest(square.getLocationsFromTag(BuildingTownSquare.TAG_GIFT));
        if (gift != null && pieceLevel >= SQUARE_GIFT_LEVEL) {
            final BlockPos rel = gift.subtract(anchor);
            final Direction away = rel.getX() == 0 && rel.getZ() == 0 ? Direction.NORTH
                    : Direction.getNearest(rel.getX(), 0, rel.getZ());
            out.add(new PieceLoader.PieceCell(rel, SeasonsBlocks.GIFT_PILE.get().defaultBlockState()
                    .setValue(GiftPileBlock.FACING, away), false, true, false, false, false));
        }
        final BlockPos launch = lowest(square.getLocationsFromTag(BuildingTownSquare.TAG_LAUNCH));
        if (launch != null && pieceLevel >= PieceLoader.FIREWORK_CRATE_LEVEL) {
            out.add(new PieceLoader.PieceCell(launch.subtract(anchor), SeasonsBlocks.FIREWORK_CRATE.get().defaultBlockState(),
                    false, false, true, false, false));
        }
    }

    /**
     * The tree's foot on a Town Square (Q12): one festival trunk in the centrepiece cell itself. The square's {@code tree}
     * cell is the one the 0.1.0 marker stood in, so the tree's own trunk starts a block above the paving; the foot fills
     * the cell, so the tree never floats. A tree cell of the square's group, planned first and soft
     * ({@link PieceLoader#plan}): an occupied centre never refuses the tree.
     */
    public static PieceLoader.PieceCell foot() {
        return new PieceLoader.PieceCell(BlockPos.ZERO, SeasonsBlocks.FESTIVAL_TRUNK.get().defaultBlockState(), true, false, false, false, true);
    }

    /** The world cells of a Town Square's {@code gift} and {@code launch} tags (its fixtures' cells); empty at the festival marker. */
    static Set<BlockPos> fixtureCells(final Target target) {
        if (!(target.building() instanceof BuildingTownSquare square)) {
            return Set.of();
        }
        final Set<BlockPos> out = new HashSet<>();
        for (final String tag : List.of(BuildingTownSquare.TAG_GIFT, BuildingTownSquare.TAG_LAUNCH)) {
            final List<BlockPos> cells = square.getLocationsFromTag(tag);
            if (cells != null) {
                for (final BlockPos cell : cells) {
                    out.add(cell.immutable());
                }
            }
        }
        out.addAll(MarketStalls.cells(square, target.owner().pos()));   // Q10: the stalls' cells too, whether they stand or not
        return out;
    }

    private static BlockPos lowest(final List<BlockPos> cells) {
        if (cells == null || cells.isEmpty()) {
            return null;
        }
        BlockPos lowest = cells.get(0);
        for (final BlockPos cell : cells) {
            if (cell.compareTo(lowest) < 0) {
                lowest = cell;
            }
        }
        return lowest.immutable();
    }

    /** The piece path of the colony's square: the one its group was planned for, else the one of this hut level (0: none). */
    public static String squarePath(final DecorLedger ledger, final int colony, final BlockPos marker, final int hutLevel) {
        return squarePath(ledger, colony, marker, hutLevel > 0 ? PieceLoader.pathFor(hutLevel) : null);
    }

    /** The piece path of a square group: the one it was planned for, else {@code fallback}. */
    public static String squarePath(final DecorLedger ledger, final int colony, final BlockPos anchor, final String fallback) {
        final DecorLedger.GroupView group = ledger.group(colony, DecorLedger.Owner.square(anchor));
        if (group != null && !group.piece().isEmpty()) {
            return group.piece();
        }
        return fallback;
    }

    /**
     * The level the square's centrepiece is sized by: on a Town Square the lower of its level and the Workshop's
     * ([B] owner answer of 20 Nov 2026), at the festival marker the Workshop's (0.1.0).
     */
    public static int pieceLevel(final Target target, final int hutLevel) {
        return target.building() instanceof BuildingTownSquare square ? SquareSets.pieceLevel(square.getBuildingLevel(), hutLevel) : hutLevel;
    }

    /**
     * The centrepiece the square wants now: on a Town Square the one of the set its order asks for
     * ({@link SquareSets#wanted}, {@code null}: no set now), at the festival marker the Winter Festival's tree of the
     * Workshop's level (0.1.0).
     */
    public static String piecePath(final Target target, final int hutLevel) {
        if (target.building() instanceof BuildingTownSquare square) {
            final CalendarManager.Snapshot now = CalendarManager.current();
            final SquareSets.Set set = SquareSets.wanted(square.order(), now);
            final String path = PieceLoader.pathFor(set, pieceLevel(target, hutLevel));
            // no centrepiece wanted (Bare) while the festival is on: its fixtures still go up (design 4.4)
            return path == null && SquareSets.winterFixtures(set, now) ? PieceLoader.FIXTURES_ONLY : path;
        }
        return PieceLoader.pathFor(hutLevel);
    }

    /** A square group needs no visit: no set wanted and nothing up, or the wanted set of this level decorated. */
    private static boolean squareDone(final DecorLedger.GroupView group, final Target target, final int hutLevel) {
        final String path = piecePath(target, hutLevel);
        if (path == null) {
            return group == null || group.entries().isEmpty();
        }
        return group != null && group.decoratedDay() >= 0 && group.level() == pieceLevel(target, hutLevel) && group.piece().equals(path)
                && !MarketStalls.open(group, target);   // Q10: a market wanted since the group was done is not done
    }

    /**
     * The box the square takes: the cells of its piece at the marker ({@code path}, when that piece is loaded) and the
     * cells its group lists; {@code null} when neither is known yet.
     */
    public static AABB squareBox(final ServerLevel level, final DecorLedger ledger, final int colony, final BlockPos marker,
                                 final Direction facing, final String pack, final String path) {
        return squareBox(level, ledger, colony, marker, PieceLoader.rotation(facing), pack, path);
    }

    public static AABB squareBox(final ServerLevel level, final DecorLedger ledger, final int colony, final BlockPos marker,
                                 final RotationMirror rotation, final String pack, final String path) {
        return squareBox(level, ledger, colony, marker, rotation, pack, path, Set.of());
    }

    /** {@link #squareBox(ServerLevel, DecorLedger, int, BlockPos, RotationMirror, String, String)} without the entries at {@code skip}. */
    static AABB squareBox(final ServerLevel level, final DecorLedger ledger, final int colony, final BlockPos marker,
                          final RotationMirror rotation, final String pack, final String path, final Set<BlockPos> skip) {
        final PieceLoader.Piece piece = path == null ? null : PieceLoader.request(level, pack, path, rotation);
        return squareBox(ledger, colony, marker, piece, skip);
    }

    /** The box of a known piece at the marker, joined with the group's entries. */
    static AABB squareBox(final DecorLedger ledger, final int colony, final BlockPos marker, final PieceLoader.Piece piece) {
        return squareBox(ledger, colony, marker, piece, Set.of());
    }

    /** The same without the group's entries at {@code skip} (a Town Square's fixtures, which stand on its paving). */
    static AABB squareBox(final DecorLedger ledger, final int colony, final BlockPos marker, final PieceLoader.Piece piece,
                          final Set<BlockPos> skip) {
        AABB box = null;
        if (piece != null) {
            box = new AABB(Vec3.atLowerCornerOf(marker.offset(piece.min())), Vec3.atLowerCornerOf(marker.offset(piece.max()).offset(1, 1, 1)));
        }
        final DecorLedger.GroupView group = ledger.group(colony, DecorLedger.Owner.square(marker));
        if (group != null) {
            for (final BlockPos pos : group.entries().keySet()) {
                if (skip.contains(pos)) {
                    continue;
                }
                box = box == null ? new AABB(pos) : box.minmax(new AABB(pos));
            }
        }
        return box;
    }

    /**
     * Where he stands while he works at the square: a standing place ({@link Gathering#standable}) on the nearest ring
     * 1 to {@link #STAND_RING} blocks outside the box's footprint, at the marker's height, the one nearest
     * {@code from}; {@code null} without a box or room. Never loads a chunk.
     */
    public static BlockPos squareStand(final ServerLevel level, final AABB box, final BlockPos marker, final BlockPos from) {
        if (box == null) {
            return null;
        }
        final int x0 = (int) Math.floor(box.minX);
        final int x1 = (int) Math.ceil(box.maxX) - 1;
        final int z0 = (int) Math.floor(box.minZ);
        final int z1 = (int) Math.ceil(box.maxZ) - 1;
        for (int r = 1; r <= STAND_RING; r++) {
            BlockPos best = null;
            double bestD = Double.MAX_VALUE;
            for (int x = x0 - r; x <= x1 + r; x++) {
                for (int z = z0 - r; z <= z1 + r; z++) {
                    if (x != x0 - r && x != x1 + r && z != z0 - r && z != z1 + r) {
                        continue;                     // the ring only
                    }
                    final BlockPos stand = Gathering.standable(level, new BlockPos(x, marker.getY(), z));
                    if (stand == null) {
                        continue;
                    }
                    final double d = stand.distSqr(from);
                    if (d < bestD || d == bestD && stand.compareTo(best) < 0) {
                        best = stand;
                        bestD = d;
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    /** Colonists moved out of a square's box since the server started (the probe counts them). */
    private static int evictions;

    public static int evictions() {
        return evictions;
    }

    /**
     * Every colonist (the Decorator among them) whose body is in the square's box is moved to the nearest standing
     * place outside it and his walk stopped, so the tree never goes up round him (S10c). One asleep in his bed, or
     * riding something, is left; players and other entities are never moved (the cell rule keeps their cells open).
     * Returns how many were moved.
     */
    public static int evict(final ServerLevel level, final AABB box, final BlockPos marker) {
        if (box == null) {
            return 0;
        }
        int moved = 0;
        for (final AbstractEntityCitizen citizen : level.getEntitiesOfClass(AbstractEntityCitizen.class, box, Entity::isAlive)) {
            if (citizen.isPassenger() || citizen.getCitizenSleepHandler() != null && citizen.getCitizenSleepHandler().isAsleep()) {
                continue;
            }
            final BlockPos to = squareStand(level, box, marker, citizen.blockPosition());
            if (to == null) {
                continue;
            }
            citizen.teleportTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
            citizen.getNavigation().stop();
            moved++;
            evictions++;
        }
        return moved;
    }

    /**
     * The colony's centrepiece as a place to work on now ({@link Squares#of}): the main Town Square while it is located
     * and free and {@code assigned} is a Town Square whose Decorator module lists the Decorator ([B] design 4.3: it is
     * worked only by a Decorator its modules list; a busy square gets nothing, and the marker is not used meanwhile),
     * else the festival marker's square ({@link #squareTarget}, open to any Decorator), else {@code null}. A Decorator
     * listed on a square that is not the colony's main one works the main square (the owner item of Q2, taken in Q5: the
     * square's Decorator page says which square is the main one).
     */
    public static Target centrepiece(final ServerLevel level, final IColony colony, final DecorLedger ledger, final BuildingTownSquare assigned) {
        final Squares.Square square = Squares.of(level, colony, ledger);
        if (square.source() == Squares.Source.BUILDING) {
            if (!square.located() || square.busy() || assigned == null) {
                return null;
            }
            final BuildingTownSquare main = assigned.getPosition().equals(square.building()) ? assigned
                    : colony.getServerBuildingManager().getBuilding(square.building()) instanceof BuildingTownSquare found ? found : null;
            return main == null ? null : Target.townSquare(main, square.anchor());
        }
        return squareTarget(level, ledger, colony.getID());
    }

    /**
     * The festival marker's square, if a marker is registered and still stands (or its chunk is not loaded) and no Town
     * Square is the colony's main square ({@link Squares#marker}: while one is, the marker is only the fallback). The
     * Town Square's own target comes from {@link #centrepiece}.
     */
    public static Target squareTarget(final ServerLevel level, final DecorLedger ledger, final int colony) {
        final BlockPos marker = Squares.marker(ledger, colony);
        if (marker == null || !level.hasChunkAt(marker)) {
            return null;
        }
        final BlockState state = level.getBlockState(marker);
        if (!(state.getBlock() instanceof FestivalMarkerBlock)) {
            return null;
        }
        return Target.square(marker, state.getValue(FestivalMarkerBlock.FACING), PieceLoader.PACK);
    }

    /**
     * The square is worth walking to: the old piece has to come down, or the new one is loaded (else it is asked for
     * now) and either does not fit (he goes and says so, once a day) or has a cell he can place.
     */
    private static boolean squareReady(final ServerLevel level, final DecorLedger ledger, final int colony, final Target target,
                                       final int hutLevel, final DecorPlacer.Stock stock) {
        final DecorLedger.GroupView group = ledger.group(colony, target.owner());
        final String path = piecePath(target, hutLevel);
        if (group != null && !group.entries().isEmpty() && !group.piece().isEmpty() && !group.piece().equals(path)) {
            return true;                              // the old piece comes down first (also when no set is wanted now)
        }
        if (path == null) {
            return false;                             // no set wanted now and nothing up
        }
        final PieceLoader.Piece piece = PieceLoader.request(level, target.pack(), path, target.rotation());
        if (piece == null) {
            return false;
        }
        final PieceLoader.SquarePlan plan = PieceLoader.plan(level, piece, target.owner().pos(), pieceLevel(target, hutLevel), ledger, colony,
                target.owner(), SeasonsConfig.needsMaterials(), fixtures(target, pieceLevel(target, hutLevel)));
        if (plan.waiting() || plan.cells() == 0 && piece.cells().isEmpty()) {
            return false;                             // (fixtures only, and their cells cannot be read now: nothing to do)
        }
        final boolean first = group == null || group.entries().isEmpty();
        if (first && !plan.fits()) {
            return true;
        }
        return !plan.slots().isEmpty() && anyInStock(placeable(level, ledger, colony, plan.slots()), stock);
    }

    /**
     * The square's slots in the order they may go up: with {@code decorator.needsMaterials} on, a free cell (the gift
     * pile, the firework crate, the large star, the tinsel leaves: {@link PieceLoader#costsItem} false) waits while a
     * cell that costs an item is open for its item or held by an entity, so the tree is built from its materials first
     * and nothing free stands on its own (S9, H8); a visit that puts up the last of them goes on with the free cells. A
     * costed cell the cell rule refuses for another reason (a lantern that cannot stand where the grass keeps its
     * planter cell, a wall, the claim) holds nothing back (S10c). With decorations free every slot may go up.
     */
    static List<DecorPlacer.Slot> placeable(final ServerLevel level, final DecorLedger ledger, final int colony, final List<DecorPlacer.Slot> slots) {
        if (!SeasonsConfig.needsMaterials()) {
            return slots;
        }
        final List<DecorPlacer.Slot> costed = new ArrayList<>();
        for (final DecorPlacer.Slot slot : slots) {
            if (slot.costs()) {
                costed.add(slot);
            }
        }
        return costed.isEmpty() || holding(level, ledger, colony, costed).isEmpty() ? slots : costed;
    }

    /** The costed slots that hold the free cells back: the cell takes the block now (it waits for its item) or an entity stands in it. */
    static List<DecorPlacer.Slot> holding(final ServerLevel level, final DecorLedger ledger, final int colony, final List<DecorPlacer.Slot> costed) {
        final List<Entity> near = DecorPlacer.blockers(level, costed);
        final List<DecorPlacer.Slot> out = new ArrayList<>();
        for (final DecorPlacer.Slot slot : costed) {
            final String why = DecorPlacer.refusal(level, ledger, colony, slot.pos(), DecorPlacer.normalise(slot.state(), slot.pos()), true, near);
            if (why == null || "entity".equals(why)) {
                out.add(slot);
            }
        }
        return out;
    }

    private static boolean anyInStock(final List<DecorPlacer.Slot> slots, final DecorPlacer.Stock stock) {
        for (final DecorPlacer.Slot slot : slots) {
            if (!slot.costs()) {
                return true;
            }
            final Item item = DecorPlacer.itemOf(slot.state());
            if (item == null || stock.has(item)) {
                return true;
            }
        }
        return false;
    }

    /** The next decorated group to tend today (nearest first), or {@code null}. */
    public static Target nextToTend(final ServerLevel level, final IColony colony, final BlockPos from, final DecorLedger ledger, final long day) {
        return nextToTend(level, colony, from, ledger, day, null);
    }

    /** {@link #nextToTend(ServerLevel, IColony, BlockPos, DecorLedger, long)} for a Decorator who works {@code assigned} (or none). */
    public static Target nextToTend(final ServerLevel level, final IColony colony, final BlockPos from, final DecorLedger ledger, final long day,
                                    final BuildingTownSquare assigned) {
        final int id = colony.getID();
        final List<Target> found = new ArrayList<>();
        for (final DecorLedger.GroupView group : ledger.groups(id)) {
            if (!group.owner().outdoor()) {
                continue;                             // the storeroom follows its target at the hut (StoreroomWork), the touches the season (nextTouches)
            }
            if (group.decoratedDay() < 0 || group.decoratedDay() >= day || group.tendedDay() >= day || group.removing()) {
                continue;
            }
            final Target target = targetOf(level, colony, ledger, group.owner(), assigned);
            if (target == null || target.building() != null && busy(colony, target.building())) {
                continue;
            }
            found.add(target);
        }
        found.sort(Comparator.comparingDouble((Target t) -> t.owner().pos().distSqr(from)).thenComparing(t -> t.owner().pos()));
        if (found.isEmpty()) {
            return null;
        }
        final Target next = found.get(0);
        return next.owner().kind() == DecorLedger.Kind.SQUARE ? withStand(level, ledger, id, next, 0, from) : next;
    }

    /**
     * The next group with entries to take down (nearest first, loaded ones only), or {@code null}. The storeroom is never
     * one (storage-look 3.3, point 3): it changes by its target at the hut ({@link StoreroomWork}), packed after the window;
     * nor are the seasonal touches (Q12): they stand between the festivals ({@link #nextTouches}).
     */
    public static Target nextToTakeDown(final ServerLevel level, final IColony colony, final BlockPos from, final DecorLedger ledger) {
        final List<Target> found = new ArrayList<>();
        for (final DecorLedger.GroupView group : ledger.groups(colony.getID())) {
            if (group.entries().isEmpty() || !group.owner().outdoor()) {
                continue;
            }
            boolean anyLoaded = false;
            for (final BlockPos pos : group.entries().keySet()) {
                if (level.hasChunkAt(pos)) {
                    anyLoaded = true;
                    break;
                }
            }
            if (!anyLoaded) {
                continue;
            }
            final BlockPos first = group.entries().keySet().iterator().next();
            found.add(new Target(group.owner(), new DoorFinder.Box(first, first, group.owner().pos()), null, first, "", RotationMirror.NONE));
        }
        found.sort(Comparator.comparingDouble((Target t) -> t.owner().pos().distSqr(from)).thenComparing(t -> t.owner().pos()));
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * The target for a group's owner as it is now, or {@code null} when the building or the marker is gone, and for the
     * storeroom (it is no place he walks to: {@link StoreroomWork} checks it at the hut) and for the seasonal touches
     * ({@link #nextTouches} names their target).
     */
    public static Target targetOf(final ServerLevel level, final IColony colony, final DecorLedger ledger, final DecorLedger.Owner owner) {
        return targetOf(level, colony, ledger, owner, null);
    }

    /** {@link #targetOf(ServerLevel, IColony, DecorLedger, DecorLedger.Owner)}; a Town Square's group only for its Decorator ({@code assigned}). */
    public static Target targetOf(final ServerLevel level, final IColony colony, final DecorLedger ledger, final DecorLedger.Owner owner,
                                  final BuildingTownSquare assigned) {
        if (owner.kind() == DecorLedger.Kind.STOREROOM || owner.kind() == DecorLedger.Kind.TOUCHES) {
            return null;
        }
        if (owner.kind() == DecorLedger.Kind.STREET) {
            // Q11: the mark's target while the mark exists
            final StreetMarks.Mark mark = ledger.markAt(colony.getID(), owner.pos());
            return mark == null ? null : StreetWork.target(level, mark, null);
        }
        if (owner.kind() == DecorLedger.Kind.LIGHTS) {
            return StreetLights.target(level, colony, ledger);   // null while the switch is off
        }
        if (owner.kind() == DecorLedger.Kind.SQUARE) {
            final Target square = centrepiece(level, colony, ledger, assigned);
            return square != null && square.owner().equals(owner) ? square : null;
        }
        final IBuilding building = colony.getServerBuildingManager().getBuilding(owner.pos());
        return building == null ? null : Target.building(building);
    }

    // ---------------------------------------------------------------- doing

    /** One decorating visit. */
    public static Outcome decorate(final ServerLevel level, final int colony, final Target target, final int hutLevel,
                                   final DecorPlacer.Stock stock, final long day, final DecorLedger ledger) {
        if (target.owner().kind() == DecorLedger.Kind.STREET) {
            return StreetWork.visit(level, colony, target, day, ledger, false);
        }
        if (target.owner().kind() == DecorLedger.Kind.LIGHTS) {
            return StreetLights.visit(level, colony, target, day, ledger, false, stock.kinds().lights());
        }
        if (target.owner().kind() == DecorLedger.Kind.SQUARE) {
            return square(level, colony, target, hutLevel, stock, day, ledger, false);
        }
        return building(level, colony, target, hutLevel, stock, day, ledger, false);
    }

    /**
     * One tending visit: the same plan again, what went missing put back. It is not counted against the day's budget.
     * The day is noted once the visit really looked at the target, also when nothing was missing; a visit that was
     * turned away (the building gone or under construction, chunks not loaded, the marker gone, the piece still
     * loading) notes nothing, so the group is tended later the same day.
     */
    public static Outcome tend(final ServerLevel level, final int colony, final Target target, final int hutLevel,
                               final DecorPlacer.Stock stock, final long day, final DecorLedger ledger) {
        if (target.owner().kind() == DecorLedger.Kind.STREET) {
            return StreetWork.visit(level, colony, target, day, ledger, true);
        }
        if (target.owner().kind() == DecorLedger.Kind.LIGHTS) {
            return StreetLights.visit(level, colony, target, day, ledger, true, stock.kinds().lights());
        }
        return target.owner().kind() == DecorLedger.Kind.SQUARE
                ? square(level, colony, target, hutLevel, stock, day, ledger, true)
                : building(level, colony, target, hutLevel, stock, day, ledger, true);
    }

    private static Outcome building(final ServerLevel level, final int colony, final Target target, final int hutLevel,
                                    final DecorPlacer.Stock stock, final long day, final DecorLedger ledger, final boolean tending) {
        // checked again at the building itself: an order may have opened (or the building gone) while he walked here
        final IBuilding building = target.building();
        if (building != null && (building.getColony().getServerBuildingManager().getBuilding(building.getPosition()) != building
                || busy(building.getColony(), building))) {
            return Outcome.of(0, 0, false, "the building is gone or under construction: skipped");
        }
        final DecorPlanner.Plan plan = DecorPlanner.plan(level, target.box(), hutLevel, SeasonsConfig.maxPerSide(), ledger, colony,
                target.owner(), SeasonsConfig.needsMaterials(), stock.kinds());
        if (!plan.loaded()) {
            return Outcome.of(0, 0, false, "waiting for the building's chunks to load");
        }
        final String festival = festival();
        ledger.plan(colony, target.owner(), festival, hutLevel, "");
        final DecorPlacer.PlaceResult result = DecorPlacer.place(level, ledger, colony, target.owner(), festival, hutLevel, plan.slots(), stock);
        final int placed = result.count();
        final int open = plan.slots().size() - placed;
        final String fruit = fruitless(level, ledger, colony, target, day, tending, placed, open, result);
        if (placed > 0 && !tending) {
            ledger.charge(colony, target.owner(), day);
        }
        final boolean complete = open == 0;
        if (complete) {
            ledger.setDecorated(colony, target.owner(), day);
        } else if (ledger.group(colony, target.owner()).entries().isEmpty() && placed == 0) {
            // nothing could be placed (no stock): no empty group left behind for this visit
            ledger.dropGroup(colony, target.owner());
        }
        tended(ledger, colony, target, day, tending);
        ledger.flush(level);
        return Outcome.of(placed, open, complete, placed + " placed, " + open + " still open (" + plan.doors() + " doors)" + fruit);
    }

    /**
     * Counts a decorating visit that ran its placing step, placed nothing and left cells open; any placing starts the
     * count again. At {@link #MAX_FRUITLESS_VISITS} in one game day the target is left for the rest of that day
     * ({@link #nextToDecorate} skips it), with one INFO line naming it and why its cells stayed open. Tending visits
     * come once a day and are not counted. Returns a note for the status line.
     */
    private static String fruitless(final ServerLevel level, final DecorLedger ledger, final int colony, final Target target, final long day,
                                    final boolean tending, final int placed, final int open, final DecorPlacer.PlaceResult result) {
        if (tending) {
            return "";
        }
        if (placed > 0) {
            ledger.clearFruitless(colony, target.owner());
            return "";
        }
        if (open <= 0) {
            return "";
        }
        final int n = ledger.addFruitless(colony, target.owner(), day);
        if (n == MAX_FRUITLESS_VISITS) {
            ColonySeasons.LOGGER.info("Decorator of colony {}: {} left for today (game day {}) after {} visits that placed nothing; "
                    + "{} cells cannot be filled now: {}", colony, target.owner(), day, n, open, result.reasons());
            return ", left for today";
        }
        return "";
    }

    private static Outcome square(final ServerLevel level, final int colony, final Target target, final int hutLevel,
                                  final DecorPlacer.Stock stock, final long day, final DecorLedger ledger, final boolean tending) {
        final boolean onSquare = target.onTownSquare();
        if (onSquare && CalendarManager.current() == null) {
            return Outcome.of(0, 0, false, "waiting for the calendar");   // the order's set is not known yet: nothing swapped
        }
        final String path = piecePath(target, hutLevel);
        final int pieceLevel = pieceLevel(target, hutLevel);
        final DecorLedger.GroupView group = ledger.group(colony, target.owner());
        if (group != null && !group.entries().isEmpty() && !group.piece().isEmpty() && !group.piece().equals(path)) {
            // the level changed (the hut's, or the square's) or, on a Town Square, the set its order asks for (the order,
            // a window, the season): the old piece comes down first (in slices, by the exact-state rule, its items to his
            // stock), the new one goes up on a later visit (design 4.3, Tending)
            final DecorPlacer.Removed removed = DecorPlacer.remove(level, ledger, colony, target.owner(), stock, SQUARE_SLICE);
            tended(ledger, colony, target, day, tending);
            ledger.flush(level);
            return Outcome.of(0, 1, false, path == null ? "took the square's set down (" + removed.removed() + " blocks): its order wants none now"
                    : "took the old tree down (" + removed.removed() + " blocks) for a new one");
        }
        // design 3.2 with the owner's answer of 20 Nov 2026: blocks with collision only at a marker the player put down
        // or on a Town Square the player built (both are the square's group, DecorPlacer's square rule); the marker may
        // have been broken, or the square taken by a work order, while he walked here
        final BlockPos marker = target.owner().pos();
        if (onSquare) {
            final String why = notFree(level, (BuildingTownSquare) target.building(), marker, ledger);
            if (why != null) {
                return Outcome.of(0, 0, false, why);
            }
        } else {
            // [B] design 4.8, one centrepiece: a target chosen before a Town Square took over (he was walking here) gets
            // nothing; while a square is the main one the marker is only the fallback (Squares.marker)
            final DecorLedger.SquareMark main = ledger.squareMark(colony);
            if (main != null && !main.anchor().equals(marker)) {
                return Outcome.of(0, 0, false, "a Town Square is the colony's centrepiece now: nothing at the festival marker");
            }
            if (!level.hasChunkAt(marker)) {
                return Outcome.of(0, 0, false, "waiting for the square's chunks to load");
            }
            if (!(level.getBlockState(marker).getBlock() instanceof FestivalMarkerBlock)) {
                return Outcome.of(0, 0, false, "the festival marker is gone: no tree");
            }
        }
        if (path == null) {
            // Bare, or between the windows: no set (the seasonal touches are a group of their own, nextTouches)
            tended(ledger, colony, target, day, tending);
            ledger.flush(level);
            return Outcome.of(0, 0, false, "the square's order wants no set now");
        }
        // leaves of the tree that re-read their distance (a player broke a log) are still ours: the entries follow first
        DecorPlacer.followLeaves(level, ledger, colony, target.owner());
        final PieceLoader.Piece piece = PieceLoader.request(level, target.pack(), path, target.rotation());
        if (piece == null) {
            if (!PieceLoader.missing(target.pack(), path, target.rotation())) {
                return Outcome.of(0, 0, false, "loading the tree piece");
            }
            // missing for good: he looked, there is nothing to put up today
            tended(ledger, colony, target, day, tending);
            ledger.flush(level);
            return Outcome.of(0, 0, false, "no tree piece " + path + " in pack " + target.pack());
        }
        // nobody stands in the tree while it goes up (S10c): every colonist in its box, the Decorator too, steps out first
        evict(level, squareBox(ledger, colony, marker, piece, fixtureCells(target)), marker);
        final PieceLoader.SquarePlan plan = PieceLoader.plan(level, piece, target.owner().pos(), pieceLevel, ledger, colony,
                target.owner(), SeasonsConfig.needsMaterials(), fixtures(target, pieceLevel));
        if (plan.waiting()) {
            return Outcome.of(0, 0, false, "waiting for the square's chunks to load");
        }
        if (plan.cells() == 0 && piece.cells().isEmpty()) {
            return Outcome.of(0, 0, false, "the square's gift and launch cells cannot be read now");   // fixtures only
        }
        final String festival = festival();
        final boolean first = group == null || group.entries().isEmpty();
        if (first && !plan.fits()) {
            ledger.plan(colony, target.owner(), festival, pieceLevel, path);
            ledger.setDecorated(colony, target.owner(), day);
            ledger.setSquareProblem(colony, true);
            if (ledger.noticeDue(colony, day)) {
                tellManagers(level, colony, onSquare);
            }
            tended(ledger, colony, target, day, tending);
            ledger.flush(level);
            return Outcome.of(0, 0, false, "not enough room " + (onSquare ? "on the Town Square" : "at the festival marker") + " ("
                    + plan.blocked() + " of " + plan.cells() + " cells blocked" + (plan.trunkBlocked() ? ", the trunk among them" : "") + ")");
        }
        ledger.setSquareProblem(colony, false);
        ledger.plan(colony, target.owner(), festival, pieceLevel, path);
        // a log or leaf of the piece that went missing never told the ledger (only our own blocks do, from onRemove):
        // its stale entry is forgotten here, so the cell is filled again like any free one (design 4, tending)
        for (final BlockPos pos : plan.gone()) {
            ledger.forget(pos);
        }
        final List<DecorPlacer.Slot> order = placeable(level, ledger, colony, plan.slots());
        final List<DecorPlacer.Slot> slice = order.subList(0, Math.min(SQUARE_SLICE, order.size()));
        final Map<Item, Integer> missing = new LinkedHashMap<>();
        for (final DecorPlacer.Slot slot : slice) {
            final Item item = slot.costs() ? DecorPlacer.itemOf(slot.state()) : null;
            if (item != null && !stock.has(item)) {
                missing.merge(item, 1, Integer::sum);
            }
        }
        DecorPlacer.PlaceResult result = DecorPlacer.place(level, ledger, colony, target.owner(), festival, pieceLevel, slice, stock);
        if (order.size() < plan.slots().size() && result.count() < SQUARE_SLICE) {
            // no cell that costs an item waits for it or for an entity any more: the free ones follow at once, in what
            // is left of the slice (a costed cell the cell rule refuses for good holds nothing back, S10c)
            final Set<BlockPos> up = new HashSet<>(result.placed());
            final List<DecorPlacer.Slot> costedLeft = new ArrayList<>();
            for (final DecorPlacer.Slot slot : order) {
                if (!up.contains(slot.pos())) {
                    costedLeft.add(slot);
                }
            }
            if (holding(level, ledger, colony, costedLeft).isEmpty()) {
                final List<DecorPlacer.Slot> free = new ArrayList<>(plan.slots());
                free.removeAll(order);
                result = result.plus(DecorPlacer.place(level, ledger, colony, target.owner(), festival, pieceLevel,
                        free.subList(0, Math.min(SQUARE_SLICE - result.count(), free.size())), stock));
            }
        }
        final int placed = result.count();
        if (placed > 0) {
            DecorPlacer.settleLeaves(level, ledger, colony, target.owner());
            if (!tending) {
                ledger.charge(colony, target.owner(), day);
            }
        }
        final int open = plan.slots().size() - placed;
        final boolean complete = open == 0;
        if (complete) {
            ledger.setDecorated(colony, target.owner(), day);
        }
        // the tree is ready once no cell of the tree itself is still open (cells blocked when it was planned do not count)
        if (!piece.cells().isEmpty() && new HashSet<>(result.placed()).containsAll(plan.treeSlots())) {
            ledger.setTreeReady(colony, target.owner(), day);   // (fixtures only: there is no tree to light)
        }
        final String fruit = fruitless(level, ledger, colony, target, day, tending, placed, open, result);
        tended(ledger, colony, target, day, tending);
        ledger.flush(level);
        if (target.building() instanceof BuildingTownSquare square) {
            square.noteMissing(missing);              // the square's info page says what he is short of (Q5)
        }
        return new Outcome(placed, open, complete, "square: " + placed + " placed, " + open + " still open" + fruit, Map.copyOf(missing));
    }

    /** A tending visit that really looked at its target notes the day (a dropped group notes nothing). */
    private static void tended(final DecorLedger ledger, final int colony, final Target target, final long day, final boolean tending) {
        if (tending) {
            ledger.setTended(colony, target.owner(), day);
        }
    }

    /**
     * One take-down visit to a group by the removal rule: at most {@link #SQUARE_SLICE} cells (the S4 hand-off: the grand
     * tree is taken down in slices like it is put up), the rest stays listed for his next visits, and the group counts
     * once against the day's budget however many visits it takes. A group with cells in unloaded chunks is also marked
     * for the sweep. A mark's group (Q11) counts on the one slot all marks share, and its visit takes down the other marks'
     * groups within {@link StreetWork#STREET_REACH} too, in the same slice ({@link StreetWork#takeDown}).
     */
    public static DecorPlacer.Removed takeDown(final ServerLevel level, final int colony, final DecorLedger.Owner owner,
                                               final DecorPlacer.Stock sink, final long day, final DecorLedger ledger) {
        final DecorLedger.GroupView group = ledger.group(colony, owner);
        if (group == null) {
            return DecorPlacer.Removed.NONE;
        }
        if (!group.entries().isEmpty()) {
            ledger.charge(colony, owner.budgetKey(), day);
        }
        if (owner.kind() == DecorLedger.Kind.STREET) {
            final DecorPlacer.Removed street = StreetWork.takeDown(level, colony, owner, sink, ledger);
            ledger.flush(level);
            return street;
        }
        final DecorPlacer.Removed removed = DecorPlacer.remove(level, ledger, colony, owner, sink, SQUARE_SLICE);
        if (removed.waiting() > 0) {
            ledger.markRemoving(colony, owner);
        }
        ledger.flush(level);
        return removed;
    }

    /** How many "not enough room" notices went out since the server started (the probe counts them). */
    private static int notices;

    public static int notices() {
        return notices;
    }

    /** "Not enough room at the festival marker", to the colony's managers. */
    public static void tellManagers(final ServerLevel level, final int colonyId) {
        tellManagers(level, colonyId, false);
    }

    /** The same for a Town Square: the line names the square ([A] design 4.6; its volume is reserved, so it should never fire). */
    public static void tellManagers(final ServerLevel level, final int colonyId, final boolean townSquare) {
        notices++;
        toManagers(level, colonyId, townSquare ? "com.colony_seasons.decor.no_room_square" : "com.colony_seasons.decor.no_room");
    }

    /**
     * Why a visit to a Town Square's centrepiece places nothing now ({@code null}: it may): it must still be the colony's
     * main square, located at the same tree cell and free of work orders (an order may have opened while he walked
     * here; the sweep takes its group down then), and the cell's chunk loaded.
     */
    static String notFree(final ServerLevel level, final BuildingTownSquare square, final BlockPos anchor, final DecorLedger ledger) {
        final IColony colony = square.getColony();
        if (colony == null) {
            return "the Town Square's colony is gone";
        }
        final Squares.Square now = Squares.of(level, colony, ledger);
        if (now.source() != Squares.Source.BUILDING || !square.getPosition().equals(now.building()) || !now.located()
                || !anchor.equals(now.anchor())) {
            return "this Town Square is not the colony's centrepiece now: nothing placed";
        }
        if (now.busy()) {
            return "a work order is open on the Town Square: nothing placed";
        }
        if (!level.hasChunkAt(anchor)) {
            return "waiting for the square's chunks to load";
        }
        return null;
    }

    /** One chat line (a lang key) to the managers of a colony of this level; nothing when MineColonies cannot send it. */
    public static void toManagers(final ServerLevel level, final int colonyId, final String key) {
        try {
            final IColony colony = com.minecolonies.api.colony.IColonyManager.getInstance().getColonyByWorld(colonyId, level);
            if (colony != null) {
                MessageUtils.format(key).sendTo(colony).forManagers();
            }
        } catch (final RuntimeException unavailable) {
            ColonySeasons.LOGGER.debug("Message {} not sent: {}", key, unavailable.toString());
        }
    }

    // ---------------------------------------------------------------- the seasonal touches (Q12)

    /** The distinct {@code bed} cells of a Town Square in position order (the planters, the touches' cells); empty while its tags read empty. */
    public static List<BlockPos> bedCells(final BuildingTownSquare square) {
        final List<BlockPos> cells = square.getLocationsFromTag(BuildingTownSquare.TAG_BED);
        if (cells == null || cells.isEmpty()) {
            return List.of();
        }
        final java.util.TreeSet<BlockPos> sorted = new java.util.TreeSet<>();
        for (final BlockPos cell : cells) {
            sorted.add(cell.immutable());
        }
        return List.copyOf(sorted);
    }

    /**
     * The next visit to the main Town Square's planters for a Decorator who works {@code assigned} (the Town Square whose
     * Decorator module lists him), or {@code null}. Reads only. Never at a festival marker, never on a busy or unlocated
     * square, never before the calendar's first poll. With {@code W} the touch the planters want now
     * ({@link SquareSets#touchPath}):
     * <ol>
     *   <li>touches stand and {@code W} is none or another piece (a festival set wants the square, Bare, the in-game
     *       season changed): the target, to take them down or swap them, also when {@code placing} is false (the hut's
     *       "decorate" setting off); a swap waits while the new piece is still loading (it is asked for now), so the
     *       planters are never left bare between two seasons;</li>
     *   <li>otherwise nothing when there is no {@code W}, when {@code placing} is false, while the square's own group lists
     *       anything (the touches come back only after the take-down), when today's fruitless visits are used up
     *       ({@link #MAX_FRUITLESS_VISITS}), or when the touches of {@code W} are up and were looked at today (a touch that
     *       went missing comes back at most once a game day);</li>
     *   <li>else the target when the piece is loaded (otherwise it is asked for now) and a planter has room for it.</li>
     * </ol>
     * He stands at the square's standing spot ({@code gather}) nearest the first planter with work (ties: nearer
     * {@code from}), else at the square's hut block: never in a planter.
     */
    public static Target nextTouches(final ServerLevel level, final IColony colony, final DecorLedger ledger, final BuildingTownSquare assigned,
                                     final long day, final boolean placing, final BlockPos from) {
        if (assigned == null) {
            return null;
        }
        final CalendarManager.Snapshot now = CalendarManager.current();
        if (now == null) {
            return null;
        }
        final Target square = centrepiece(level, colony, ledger, assigned);
        if (square == null || !square.onTownSquare()) {
            return null;
        }
        final BuildingTownSquare building = (BuildingTownSquare) square.building();
        final int id = colony.getID();
        final String want = SquareSets.touchPath(building.order(), now);
        final DecorLedger.Owner owner = DecorLedger.Owner.touches(square.owner().pos());
        final DecorLedger.GroupView group = ledger.group(id, owner);
        if (group != null && !group.entries().isEmpty() && (want == null || group.removing() || !group.piece().equals(want))) {
            if (want != null && placing && !group.removing() && PieceLoader.request(level, square.pack(), want, square.rotation()) == null
                    && !PieceLoader.missing(square.pack(), want, square.rotation())) {
                return null;                          // the new season's piece is loading: the old touches stay until one visit can swap them
            }
            return touchTarget(level, square, group.entries().keySet().iterator().next(), from);
        }
        if (want == null || !placing) {
            return null;
        }
        final DecorLedger.GroupView set = ledger.group(id, square.owner());
        if (set != null && !set.entries().isEmpty()) {
            return null;                              // the festival's set (or what is left of it) first: touches after the take-down
        }
        if (ledger.fruitless(id, owner, day) >= MAX_FRUITLESS_VISITS) {
            return null;
        }
        if (group != null && want.equals(group.piece()) && group.decoratedDay() >= 0 && group.tendedDay() >= day) {
            return null;
        }
        final TouchPlan plan = touchPlan(level, square, building, ledger, id, owner, want);
        if (plan == null || plan.waiting() || plan.slots().isEmpty()) {
            return null;
        }
        return touchTarget(level, square, plan.slots().get(0).pos(), from);
    }

    /**
     * One visit to the main Town Square's planters, in one tick ({@code target} from {@link #nextTouches}): touches that
     * are not the wanted ones come down by the exact-state rule (at most {@link #SQUARE_SLICE}; cells that wait are
     * marked for the sweep), then, while {@code placing} and the square's own group lists nothing, the wanted touch goes
     * on every free {@code bed} cell by the cell rule (the piece's anchor on the planter's soil cell, the bed cell minus
     * one in y, the contract of tools/square_hut.py). A bed that is not air, is listed by another group or lies outside
     * the claim is skipped without a word; a cell an entity stands in stays open (the fruitless count leaves it for the
     * day). The touches are free (no item taken, none given back, a broken one drops nothing); the visit never counts
     * against the day's budget, never sets "decorations up", and sends no notice.
     */
    public static TouchOutcome touch(final ServerLevel level, final int colony, final Target target, final long day, final DecorLedger ledger,
                                     final boolean placing) {
        final CalendarManager.Snapshot now = CalendarManager.current();
        if (now == null) {
            return new TouchOutcome(0, 0, 0, "waiting for the calendar");
        }
        if (!(target.building() instanceof BuildingTownSquare square) || target.owner().kind() != DecorLedger.Kind.TOUCHES) {
            return new TouchOutcome(0, 0, 0, "not the planters of a Town Square");
        }
        final BlockPos anchor = target.owner().pos();
        final String why = notFree(level, square, anchor, ledger);
        if (why != null) {
            return new TouchOutcome(0, 0, 0, why);    // the sweep takes care of a square that is busy or no longer the main one
        }
        final DecorLedger.Owner owner = target.owner();
        final String want = SquareSets.touchPath(square.order(), now);
        int removed = 0;
        final DecorLedger.GroupView old = ledger.group(colony, owner);
        if (old != null && !old.entries().isEmpty() && (want == null || old.removing() || !old.piece().equals(want))) {
            DecorPlacer.followLeaves(level, ledger, colony, owner);
            final DecorPlacer.Removed down = DecorPlacer.remove(level, ledger, colony, owner, DecorPlacer.Stock.FREE, SQUARE_SLICE);
            removed = down.removed();
            if (down.waiting() > 0 || down.left() > 0) {
                ledger.markRemoving(colony, owner);
                ledger.flush(level);
                return new TouchOutcome(removed, 0, down.waiting() + down.left(), "touches: " + removed + " taken down, the rest waits");
            }
        }
        final DecorLedger.GroupView set = ledger.group(colony, DecorLedger.Owner.square(anchor));
        if (want == null || !placing || set != null && !set.entries().isEmpty()) {
            ledger.flush(level);
            return new TouchOutcome(removed, 0, 0, "touches: " + removed + " taken down, none wanted now");
        }
        DecorPlacer.followLeaves(level, ledger, colony, owner);
        final TouchPlan plan = touchPlan(level, target, square, ledger, colony, owner, want);
        if (plan == null || plan.waiting()) {
            ledger.flush(level);
            return new TouchOutcome(removed, 0, 0, plan == null ? "loading the touch piece " + want : "waiting for the square's chunks to load");
        }
        final DecorLedger.GroupView before = ledger.group(colony, owner);
        if (plan.slots().isEmpty() && (before == null || before.entries().isEmpty())) {
            ledger.dropGroup(colony, owner);          // nothing up, no room: no group left behind
            ledger.flush(level);
            return new TouchOutcome(removed, 0, 0, "touches: no planter has room");
        }
        final int squareLevel = square.getBuildingLevel();
        ledger.plan(colony, owner, "", squareLevel, want);
        for (final BlockPos pos : plan.gone()) {
            ledger.forget(pos);                       // a vanilla touch broken by hand never told the ledger
        }
        final DecorPlacer.PlaceResult result = DecorPlacer.place(level, ledger, colony, owner, "", squareLevel, plan.slots(), DecorPlacer.Stock.FREE);
        final int placed = result.count();
        final int open = plan.slots().size() - placed;
        if (placed > 0) {
            DecorPlacer.settleLeaves(level, ledger, colony, owner);
            ledger.clearFruitless(colony, owner);
        } else if (open > 0) {
            ledger.addFruitless(colony, owner, day);
        }
        final DecorLedger.GroupView after = ledger.group(colony, owner);
        if (after == null || after.entries().isEmpty()) {
            ledger.dropGroup(colony, owner);
        } else {
            if (open == 0) {
                ledger.setDecorated(colony, owner, day);
            }
            ledger.setTended(colony, owner, day);
        }
        ledger.flush(level);
        return new TouchOutcome(removed, placed, open, "touches: " + placed + " placed, " + open + " still open, " + removed + " taken down");
    }

    /** The touch's slots over every bed of the square ({@code waiting}: a bed's chunk is not loaded); {@code null} while the piece loads. */
    private record TouchPlan(List<DecorPlacer.Slot> slots, List<BlockPos> gone, boolean waiting) {
    }

    private static TouchPlan touchPlan(final ServerLevel level, final Target square, final BuildingTownSquare building, final DecorLedger ledger,
                                       final int colony, final DecorLedger.Owner owner, final String path) {
        final PieceLoader.Piece piece = PieceLoader.request(level, square.pack(), path, square.rotation());
        if (piece == null) {
            return null;
        }
        final List<DecorPlacer.Slot> slots = new ArrayList<>();
        final List<BlockPos> gone = new ArrayList<>();
        for (final BlockPos bed : bedCells(building)) {
            // the Q6 contract: the piece's anchor on the planter's soil cell, so its block stands in the bed cell
            final PieceLoader.SquarePlan plan = PieceLoader.plan(level, piece, bed.below(), building.getBuildingLevel(), ledger, colony, owner, false);
            if (plan.waiting()) {
                return new TouchPlan(List.of(), List.of(), true);
            }
            slots.addAll(plan.slots());
            gone.addAll(plan.gone());
        }
        return new TouchPlan(List.copyOf(slots), List.copyOf(gone), false);
    }

    /** The touches' target with its stand: the standable {@code gather} cell nearest {@code work} (ties: nearer {@code from}), else the hut. */
    private static Target touchTarget(final ServerLevel level, final Target square, final BlockPos work, final BlockPos from) {
        final BuildingTownSquare building = (BuildingTownSquare) square.building();
        final List<BlockPos> spots = new ArrayList<>();
        final List<BlockPos> gather = building.getLocationsFromTag(BuildingTownSquare.TAG_GATHER);
        if (gather != null) {
            spots.addAll(gather);
        }
        spots.sort(Comparator.comparingDouble((BlockPos p) -> p.distSqr(work)).thenComparingDouble(p -> from == null ? 0 : p.distSqr(from))
                .thenComparing(p -> p));
        for (final BlockPos spot : spots) {
            final BlockPos stand = Gathering.standable(level, spot);
            if (stand != null) {
                return Target.touches(square, stand);
            }
        }
        return Target.touches(square, building.getPosition());
    }

    // ---------------------------------------------------------------- stock

    /** The Decorator's stock: his pack first, then the hut; items come back to the hut, then his pack, then the ground at him. */
    public static DecorPlacer.Stock stockOf(final BuildingDecorator hut, final AbstractEntityCitizen worker) {
        final IItemHandler pack = worker == null ? null : worker.getInventoryCitizen();
        final IItemHandler store = hut.getItemHandlerCap();
        final Level level = hut.getColony().getWorld();
        final BlockPos at = worker == null ? hut.getPosition() : worker.blockPosition();
        return DecorPlacer.Stock.withKinds(handlers(level, at, pack, store), hut.kinds());
    }

    /** Where the sweep puts what it takes down: the colony's first Decorator hut, staffed or not; none: lost (counted). */
    public static DecorPlacer.Stock sinkOf(final IColony colony, final int[] lost) {
        if (colony != null) {
            for (final IBuilding b : colony.getServerBuildingManager().getBuildings().values()) {
                if (b instanceof BuildingDecorator hut && hut.getItemHandlerCap() != null) {
                    return handlers(colony.getWorld(), hut.getPosition(), null, hut.getItemHandlerCap());
                }
            }
        }
        return new DecorPlacer.Stock() {
            @Override
            public boolean take(final Item item) {
                return false;
            }

            @Override
            public void give(final ItemStack stack) {
                lost[0] += stack.getCount();
            }

            @Override
            public boolean has(final Item item) {
                return false;
            }
        };
    }

    /** True when some Decorator hut of the colony has a worker. */
    public static boolean staffed(final IColony colony) {
        if (colony == null) {
            return false;
        }
        for (final IBuilding b : colony.getServerBuildingManager().getBuildings().values()) {
            if (b instanceof BuildingDecorator && !b.getAllAssignedCitizen().isEmpty()) {
                for (final ICitizenData citizen : b.getAllAssignedCitizen()) {
                    if (citizen != null) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static DecorPlacer.Stock handlers(final Level level, final BlockPos dropAt, final IItemHandler first, final IItemHandler second) {
        return new DecorPlacer.Stock() {
            @Override
            public boolean take(final Item item) {
                return extractOne(first, item) || extractOne(second, item);
            }

            @Override
            public boolean has(final Item item) {
                return BuildingDecorator.countIn(first, s -> s.is(item)) + BuildingDecorator.countIn(second, s -> s.is(item)) > 0;
            }

            @Override
            public void give(final ItemStack stack) {
                ItemStack rest = second == null ? stack : ItemHandlerHelper.insertItemStacked(second, stack, false);
                if (!rest.isEmpty() && first != null) {
                    rest = ItemHandlerHelper.insertItemStacked(first, rest, false);
                }
                if (!rest.isEmpty() && level != null) {
                    InventoryUtils.spawnItemStack(level, dropAt.getX() + 0.5, dropAt.getY() + 0.5, dropAt.getZ() + 0.5, rest);
                }
            }
        };
    }

    private static boolean extractOne(final IItemHandler handler, final Item item) {
        if (handler == null) {
            return false;
        }
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            final ItemStack stack = handler.getStackInSlot(slot);
            if (!stack.isEmpty() && stack.is(item) && !handler.extractItem(slot, 1, false).isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
