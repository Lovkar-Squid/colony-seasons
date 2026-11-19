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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 *   <li>{@link #nextToTakeDown} / {@link #takeDown}: one group at a time, by the removal rule.</li>
 * </ul>
 */
public final class DecorWork {

    /** Cells of the square placed per visit at most. */
    public static final int SQUARE_SLICE = 256;

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
            return new Target(DecorLedger.Owner.square(marker), new DoorFinder.Box(marker, marker, marker), null, marker, pack, facing);
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
        final Target square = squareTarget(level, ledger, id);
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
                    return target;
                }
                continue;
            }
            final DecorPlanner.Plan plan = DecorPlanner.plan(level, target.box(), hutLevel, SeasonsConfig.maxPerSide(), ledger, id,
                    target.owner(), costs);
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
        return !plan.slots().isEmpty() && anyInStock(plan.slots(), stock);
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
        return found.isEmpty() ? null : found.get(0);
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
            return square(level, colony, target, hutLevel, stock, day, ledger, true);
        }
        return building(level, colony, target, hutLevel, stock, day, ledger, true);
    }

    /** One tending visit: the same plan again, what went missing put back; the day is noted even when nothing was. */
    public static Outcome tend(final ServerLevel level, final int colony, final Target target, final int hutLevel,
                               final DecorPlacer.Stock stock, final long day, final DecorLedger ledger) {
        final Outcome outcome = target.owner().kind() == DecorLedger.Kind.SQUARE
                ? square(level, colony, target, hutLevel, stock, day, ledger, false)
                : building(level, colony, target, hutLevel, stock, day, ledger, false);
        ledger.setTended(colony, target.owner(), day);
        return outcome;
    }

    private static Outcome building(final ServerLevel level, final int colony, final Target target, final int hutLevel,
                                    final DecorPlacer.Stock stock, final long day, final DecorLedger ledger, final boolean charge) {
        // checked again at the building itself: an order may have opened (or the building gone) while he walked here
        final IBuilding building = target.building();
        if (building != null && (building.getColony().getServerBuildingManager().getBuilding(building.getPosition()) != building
                || busy(building.getColony(), building))) {
            return Outcome.of(0, 0, false, "the building is gone or under construction: skipped");
        }
        final DecorPlanner.Plan plan = DecorPlanner.plan(level, target.box(), hutLevel, SeasonsConfig.maxPerSide(), ledger, colony,
                target.owner(), SeasonsConfig.needsMaterials());
        if (!plan.loaded()) {
            return Outcome.of(0, 0, false, "waiting for the building's chunks to load");
        }
        final String festival = festival();
        ledger.plan(colony, target.owner(), festival, hutLevel, "");
        final int placed = DecorPlacer.place(level, ledger, colony, target.owner(), festival, hutLevel, plan.slots(), stock);
        final int open = plan.slots().size() - placed;
        if (placed > 0 && charge) {
            ledger.charge(colony, target.owner(), day);
        }
        final boolean complete = open == 0;
        if (complete) {
            ledger.setDecorated(colony, target.owner(), day);
        } else if (ledger.group(colony, target.owner()).entries().isEmpty() && placed == 0) {
            // nothing could be placed (no stock): no empty group left behind for this visit
            ledger.dropGroup(colony, target.owner());
        }
        ledger.flush(level);
        return Outcome.of(placed, open, complete, placed + " placed, " + open + " still open (" + plan.doors() + " doors)");
    }

    private static Outcome square(final ServerLevel level, final int colony, final Target target, final int hutLevel,
                                  final DecorPlacer.Stock stock, final long day, final DecorLedger ledger, final boolean charge) {
        final String path = PieceLoader.pathFor(hutLevel);
        final DecorLedger.GroupView group = ledger.group(colony, target.owner());
        if (group != null && !group.entries().isEmpty() && !group.piece().isEmpty() && !group.piece().equals(path)) {
            // the hut changed level: the old piece comes down, the new one goes up on a later visit
            final DecorPlacer.Removed removed = DecorPlacer.remove(level, ledger, colony, target.owner(), stock);
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
        final PieceLoader.Piece piece = PieceLoader.request(level, target.pack(), path, target.facing());
        if (piece == null) {
            return Outcome.of(0, 0, false, PieceLoader.missing(target.pack(), path, target.facing())
                    ? "no tree piece " + path + " in pack " + target.pack() : "loading the tree piece");
        }
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
        final List<DecorPlacer.Slot> slice = plan.slots().subList(0, Math.min(SQUARE_SLICE, plan.slots().size()));
        final Map<Item, Integer> missing = new LinkedHashMap<>();
        for (final DecorPlacer.Slot slot : slice) {
            final Item item = slot.costs() ? DecorPlacer.itemOf(slot.state()) : null;
            if (item != null && !stock.has(item)) {
                missing.merge(item, 1, Integer::sum);
            }
        }
        final int placed = DecorPlacer.place(level, ledger, colony, target.owner(), festival, hutLevel, slice, stock);
        if (placed > 0) {
            DecorPlacer.settleLeaves(level, ledger, colony, target.owner());
            if (charge) {
                ledger.charge(colony, target.owner(), day);
            }
        }
        final int open = plan.slots().size() - placed;
        final boolean complete = open == 0;
        if (complete) {
            ledger.setDecorated(colony, target.owner(), day);
        }
        ledger.flush(level);
        return new Outcome(placed, open, complete, "square: " + placed + " placed, " + open + " still open", Map.copyOf(missing));
    }

    /** Takes one group down by the removal rule; a group with cells in unloaded chunks is left to the sweep. */
    public static DecorPlacer.Removed takeDown(final ServerLevel level, final int colony, final DecorLedger.Owner owner,
                                               final DecorPlacer.Stock sink, final long day, final DecorLedger ledger) {
        final DecorLedger.GroupView group = ledger.group(colony, owner);
        if (group == null) {
            return DecorPlacer.Removed.NONE;
        }
        if (!group.entries().isEmpty()) {
            ledger.charge(colony, owner, day);
        }
        final DecorPlacer.Removed removed = DecorPlacer.remove(level, ledger, colony, owner, sink);
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
        return handlers(level, at, pack, store);
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
