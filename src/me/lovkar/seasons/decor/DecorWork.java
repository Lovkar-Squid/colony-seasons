package me.lovkar.seasons.decor;

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
import me.lovkar.seasons.calendar.CalendarManager;
import me.lovkar.seasons.colony.BuildingDecorator;
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
     * One place to work on. {@code building} is null for the square and for a probe's pasted house; {@code stand}
     * is where he walks to; {@code pack} and {@code facing} are the square's.
     */
    public record Target(DecorLedger.Owner owner, DoorFinder.Box box, IBuilding building, BlockPos stand, String pack, Direction facing) {

        public static Target building(final IBuilding building) {
            final DoorFinder.Box box = DoorFinder.Box.of(building);
            return new Target(DecorLedger.Owner.building(building.getPosition()), box, building, building.getPosition(), "", Direction.NORTH);
        }

        /** A house MineColonies does not know (the placing probe pastes real hut blueprints). */
        public static Target box(final DoorFinder.Box box) {
            return new Target(DecorLedger.Owner.building(box.hut()), box, null, box.hut(), "", Direction.NORTH);
        }

        public static Target square(final BlockPos marker, final Direction facing, final String pack) {
            return square(marker, facing, pack, marker);
        }

        /** The square with the place he stands at while he works on it (outside the piece's box, {@link #squareStand}). */
        public static Target square(final BlockPos marker, final Direction facing, final String pack, final BlockPos stand) {
            return new Target(DecorLedger.Owner.square(marker), new DoorFinder.Box(marker, marker, marker), null,
                    stand == null ? marker : stand.immutable(), pack, facing);
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

    /** The colony's buildings in the Decorator's order: town hall, then by distance to {@code from}, ties by position. */
    public static List<IBuilding> buildingsInOrder(final IColony colony, final BlockPos from) {
        final List<IBuilding> all = new ArrayList<>(colony.getServerBuildingManager().getBuildings().values());
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
        final int id = colony.getID();
        final boolean costs = SeasonsConfig.needsMaterials();
        final boolean budgetLeft = ledger.budgetUsed(id, day) < quota(hutLevel);
        final List<IBuilding> buildings = buildingsInOrder(colony, from);
        final IBuilding hall = colony.getServerBuildingManager().getTownHall();
        final List<Target> order = new ArrayList<>();
        if (hall != null) {
            order.add(Target.building(hall));
        }
        final Target square = stock.kinds().tree() ? squareTarget(level, ledger, id) : null;
        if (square != null) {
            order.add(square);
        }
        for (final IBuilding b : buildings) {
            if (b != hall) {
                order.add(Target.building(b));
            }
        }
        for (final Target target : order) {
            final DecorLedger.GroupView group = ledger.group(id, target.owner());
            final boolean done = group != null && group.decoratedDay() >= 0 && group.level() == hutLevel
                    && (target.owner().kind() == DecorLedger.Kind.BUILDING || group.piece().equals(PieceLoader.pathFor(hutLevel)));
            if (done) {
                continue;
            }
            if (ledger.fruitless(id, target.owner(), day) >= MAX_FRUITLESS_VISITS) {
                continue;                             // left for the rest of today (S10c): the next one, back tomorrow
            }
            // counted today already (also when its group was dropped since, as a work order does): no second slot
            final boolean chargedToday = ledger.chargedOn(id, target.owner(), day);
            if (!chargedToday && !budgetLeft) {
                continue;
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
        final AABB box = squareBox(level, ledger, colony, marker, square.facing(), square.pack(), squarePath(ledger, colony, marker, hutLevel));
        final BlockPos stand = squareStand(level, box, marker, from);
        return Target.square(marker, square.facing(), square.pack(), stand);
    }

    /** The piece path of the colony's square: the one its group was planned for, else the one of this hut level (0: none). */
    public static String squarePath(final DecorLedger ledger, final int colony, final BlockPos marker, final int hutLevel) {
        final DecorLedger.GroupView group = ledger.group(colony, DecorLedger.Owner.square(marker));
        if (group != null && !group.piece().isEmpty()) {
            return group.piece();
        }
        return hutLevel > 0 ? PieceLoader.pathFor(hutLevel) : null;
    }

    /**
     * The box the square takes: the cells of its piece at the marker ({@code path}, when that piece is loaded) and the
     * cells its group lists; {@code null} when neither is known yet.
     */
    public static AABB squareBox(final ServerLevel level, final DecorLedger ledger, final int colony, final BlockPos marker,
                                 final Direction facing, final String pack, final String path) {
        final PieceLoader.Piece piece = path == null ? null : PieceLoader.request(level, pack, path, facing);
        return squareBox(ledger, colony, marker, piece);
    }

    /** The box of a known piece at the marker, joined with the group's entries. */
    static AABB squareBox(final DecorLedger ledger, final int colony, final BlockPos marker, final PieceLoader.Piece piece) {
        AABB box = null;
        if (piece != null) {
            box = new AABB(Vec3.atLowerCornerOf(marker.offset(piece.min())), Vec3.atLowerCornerOf(marker.offset(piece.max()).offset(1, 1, 1)));
        }
        final DecorLedger.GroupView group = ledger.group(colony, DecorLedger.Owner.square(marker));
        if (group != null) {
            for (final BlockPos pos : group.entries().keySet()) {
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

    /** The colony's square, if a marker is registered and still stands (or its chunk is not loaded). */
    public static Target squareTarget(final ServerLevel level, final DecorLedger ledger, final int colony) {
        final BlockPos marker = ledger.marker(colony);
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
        final String path = PieceLoader.pathFor(hutLevel);
        if (group != null && !group.entries().isEmpty() && !group.piece().isEmpty() && !group.piece().equals(path)) {
            return true;                              // the old piece comes down first
        }
        final PieceLoader.Piece piece = PieceLoader.request(level, target.pack(), path, target.facing());
        if (piece == null) {
            return false;
        }
        final PieceLoader.SquarePlan plan = PieceLoader.plan(level, piece, target.owner().pos(), hutLevel, ledger, colony,
                target.owner(), SeasonsConfig.needsMaterials());
        if (plan.waiting()) {
            return false;
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
        final int id = colony.getID();
        final List<Target> found = new ArrayList<>();
        for (final DecorLedger.GroupView group : ledger.groups(id)) {
            if (group.decoratedDay() < 0 || group.decoratedDay() >= day || group.tendedDay() >= day || group.removing()) {
                continue;
            }
            final Target target = targetOf(level, colony, ledger, group.owner());
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

    /** The next group with entries to take down (nearest first, loaded ones only), or {@code null}. */
    public static Target nextToTakeDown(final ServerLevel level, final IColony colony, final BlockPos from, final DecorLedger ledger) {
        final List<Target> found = new ArrayList<>();
        for (final DecorLedger.GroupView group : ledger.groups(colony.getID())) {
            if (group.entries().isEmpty()) {
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
            found.add(new Target(group.owner(), new DoorFinder.Box(first, first, group.owner().pos()), null, first, "", Direction.NORTH));
        }
        found.sort(Comparator.comparingDouble((Target t) -> t.owner().pos().distSqr(from)).thenComparing(t -> t.owner().pos()));
        return found.isEmpty() ? null : found.get(0);
    }

    /** The target for a group's owner as it is now, or {@code null} when the building or the marker is gone. */
    public static Target targetOf(final ServerLevel level, final IColony colony, final DecorLedger ledger, final DecorLedger.Owner owner) {
        if (owner.kind() == DecorLedger.Kind.SQUARE) {
            final Target square = squareTarget(level, ledger, colony.getID());
            return square != null && square.owner().equals(owner) ? square : null;
        }
        final IBuilding building = colony.getServerBuildingManager().getBuilding(owner.pos());
        return building == null ? null : Target.building(building);
    }

    // ---------------------------------------------------------------- doing

    /** One decorating visit. */
    public static Outcome decorate(final ServerLevel level, final int colony, final Target target, final int hutLevel,
                                   final DecorPlacer.Stock stock, final long day, final DecorLedger ledger) {
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
        final String path = PieceLoader.pathFor(hutLevel);
        final DecorLedger.GroupView group = ledger.group(colony, target.owner());
        if (group != null && !group.entries().isEmpty() && !group.piece().isEmpty() && !group.piece().equals(path)) {
            // the hut changed level: the old piece comes down (in slices), the new one goes up on a later visit
            final DecorPlacer.Removed removed = DecorPlacer.remove(level, ledger, colony, target.owner(), stock, SQUARE_SLICE);
            tended(ledger, colony, target, day, tending);
            ledger.flush(level);
            return Outcome.of(0, 1, false, "took the old tree down (" + removed.removed() + " blocks) for a new one");
        }
        // design 3.2: collision only at a marker the player put down; it may have been broken while he walked here
        final BlockPos marker = target.owner().pos();
        if (!level.hasChunkAt(marker)) {
            return Outcome.of(0, 0, false, "waiting for the square's chunks to load");
        }
        if (!(level.getBlockState(marker).getBlock() instanceof FestivalMarkerBlock)) {
            return Outcome.of(0, 0, false, "the festival marker is gone: no tree");
        }
        // leaves of the tree that re-read their distance (a player broke a log) are still ours: the entries follow first
        DecorPlacer.followLeaves(level, ledger, colony, target.owner());
        final PieceLoader.Piece piece = PieceLoader.request(level, target.pack(), path, target.facing());
        if (piece == null) {
            if (!PieceLoader.missing(target.pack(), path, target.facing())) {
                return Outcome.of(0, 0, false, "loading the tree piece");
            }
            // missing for good: he looked, there is nothing to put up today
            tended(ledger, colony, target, day, tending);
            ledger.flush(level);
            return Outcome.of(0, 0, false, "no tree piece " + path + " in pack " + target.pack());
        }
        // nobody stands in the tree while it goes up (S10c): every colonist in its box, the Decorator too, steps out first
        evict(level, squareBox(ledger, colony, marker, piece), marker);
        final PieceLoader.SquarePlan plan = PieceLoader.plan(level, piece, target.owner().pos(), hutLevel, ledger, colony,
                target.owner(), SeasonsConfig.needsMaterials());
        if (plan.waiting()) {
            return Outcome.of(0, 0, false, "waiting for the square's chunks to load");
        }
        final String festival = festival();
        final boolean first = group == null || group.entries().isEmpty();
        if (first && !plan.fits()) {
            ledger.plan(colony, target.owner(), festival, hutLevel, path);
            ledger.setDecorated(colony, target.owner(), day);
            ledger.setSquareProblem(colony, true);
            if (ledger.noticeDue(colony, day)) {
                tellManagers(level, colony);
            }
            tended(ledger, colony, target, day, tending);
            ledger.flush(level);
            return Outcome.of(0, 0, false, "not enough room at the festival marker (" + plan.blocked() + " of " + plan.cells()
                    + " cells blocked" + (plan.trunkBlocked() ? ", the trunk among them" : "") + ")");
        }
        ledger.setSquareProblem(colony, false);
        ledger.plan(colony, target.owner(), festival, hutLevel, path);
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
        DecorPlacer.PlaceResult result = DecorPlacer.place(level, ledger, colony, target.owner(), festival, hutLevel, slice, stock);
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
                result = result.plus(DecorPlacer.place(level, ledger, colony, target.owner(), festival, hutLevel,
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
        if (new HashSet<>(result.placed()).containsAll(plan.treeSlots())) {
            ledger.setTreeReady(colony, target.owner(), day);
        }
        final String fruit = fruitless(level, ledger, colony, target, day, tending, placed, open, result);
        tended(ledger, colony, target, day, tending);
        ledger.flush(level);
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
     * for the sweep.
     */
    public static DecorPlacer.Removed takeDown(final ServerLevel level, final int colony, final DecorLedger.Owner owner,
                                               final DecorPlacer.Stock sink, final long day, final DecorLedger ledger) {
        final DecorLedger.GroupView group = ledger.group(colony, owner);
        if (group == null) {
            return DecorPlacer.Removed.NONE;
        }
        if (!group.entries().isEmpty()) {
            ledger.charge(colony, owner, day);
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
        notices++;
        try {
            final IColony colony = com.minecolonies.api.colony.IColonyManager.getInstance().getColonyByWorld(colonyId, level);
            if (colony != null) {
                MessageUtils.format("com.colony_seasons.decor.no_room").sendTo(colony).forManagers();
            }
        } catch (final RuntimeException unavailable) {
            ColonySeasons.LOGGER.debug("No room message not sent: {}", unavailable.toString());
        }
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
