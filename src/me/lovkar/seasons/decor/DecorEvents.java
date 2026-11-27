package me.lovkar.seasons.decor;

import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.eventbus.events.ColonyManagerLoadedModEvent;
import com.minecolonies.api.eventbus.events.ColonyManagerUnloadedModEvent;
import com.minecolonies.api.eventbus.events.colony.buildings.BuildingConstructionModEvent;
import com.minecolonies.api.eventbus.events.colony.buildings.BuildingRemovedModEvent;
import me.lovkar.seasons.ColonySeasons;
import me.lovkar.seasons.calendar.CalendarManager;
import me.lovkar.seasons.colony.BuildingDecorator;
import me.lovkar.seasons.colony.BuildingTownSquare;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The server's side of the decorations, with no Decorator needed (design 3.3 and 3.2):
 *
 * <ul>
 *   <li>MineColonies' {@code BuildingRemovedModEvent} (its own event bus): that building's decorations go at the end
 *       of the server tick, if the colony really has no building there any more;</li>
 *   <li>a festival marker broken: its square goes the same way;</li>
 *   <li>the Town Square (0.2.0, Q2, design 4.6 to 4.8): {@link Squares} names the colony's one centrepiece, the sweep
 *       takes every square group elsewhere down, a work order on the main square takes its group down from the end of
 *       that tick on, in slices ({@link #squareOrder}, and {@link #squareWatch} for an order that came another way), and a
 *       removed square's group goes at once and the marker takes over again; the square's seasonal touches (Q12, their
 *       own group at the same anchor) go by the same rules, in the same slices;</li>
 *   <li>the sweep, every {@link #SWEEP_INTERVAL} ticks per level, at most {@link #SWEEP_BUDGET} cells a pass, only in
 *       loaded chunks and never before MineColonies' colony manager has loaded: groups marked for removal; a
 *       building's group while the building is under construction (an upgrade or repair would clear our blocks in
 *       its CLEAR_NON_SOLIDS stage) or once the building is gone; and after the window, everything of a colony with
 *       no staffed Decorator hut (or no colony at all). Empty groups are dropped once the window is over. The storeroom
 *       of a Workshop (S10g2, storage-look 3.3 point 4) has rules of its own ({@link #roomGoes}): it stays packed all
 *       year with nobody's help, so only a festival set is cleared after the window, and only when that Workshop has
 *       no worker;</li>
 *   <li>a colony MineColonies no longer has (deleted): its marker and its wand's marks (Q11) are taken off at once, and
 *       its record here and its festival state are dropped once it lists no entry (S9, H12: a new colony may get its id);</li>
 *   <li>the street (0.2.0, Q11): a marked spot's group goes when its mark was removed, when its cell left the claim or
 *       a building's box covers it now; the street lights' group goes when {@code decorator.streetLights} is off. A mark
 *       whose loaded cell left its colony's claim goes itself too (Q7, H25), as the wand's removal takes it: its group
 *       down by the exact-state rule, a post's garlands with it, and the colony's count of marks one lower.</li>
 * </ul>
 * Events only enqueue; the queue is drained with {@code poll()} and never iterated.
 */
public final class DecorEvents {

    public static final int SWEEP_INTERVAL = 100;
    public static final int SWEEP_BUDGET = 256;
    /**
     * Ticks between two looks for a busy main Town Square whose group is still up (0.2.0, Q2): a work order that did not
     * come through the square's own request (MineColonies' {@code requestWorkOrder}) is seen within this many ticks.
     */
    public static final int SQUARE_WATCH = 20;

    /** What a queued item asks for at the end of the tick. */
    private enum What {
        /** Remove the owner's group, unless the colony has a building at {@code guard} again (guard null: always). */
        GROUP,
        /** The Town Square whose hut stood at {@code guard} was removed: its group goes whole, the resolver runs again. */
        SQUARE_REMOVED,
        /** The Town Square at {@code guard} has a work order: its group comes down, {@link DecorWork#SQUARE_SLICE} cells a tick. */
        SQUARE_ORDER,
        /** The Town Square at {@code guard} was built, upgraded or removed by the Builder: the resolver runs again. */
        SQUARE_REFRESH
    }

    /**
     * One item of work for the end of the tick. {@code owner} is the group (only for {@link What#GROUP}); {@code guard} is
     * a building position that is not the group's own (a square's group sits at its centrepiece, not at its hut).
     */
    private record Pending(ResourceKey<Level> dimension, int colony, What what, DecorLedger.Owner owner, BlockPos guard) {
    }

    private static final Queue<Pending> QUEUE = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger MANAGER_LOADS = new AtomicInteger();
    private static volatile boolean subscribed;
    /** Cells removed by the sweep and the queue since the server started (the probe reads it). */
    private static volatile int swept;
    private static volatile int lostItems;

    private DecorEvents() {
    }

    /** Hooks everything up; called once from the mod constructor. */
    public static void init(final IEventBus modBus) {
        modBus.addListener(DecorEvents::onCommonSetup);
        NeoForge.EVENT_BUS.addListener(DecorEvents::onLevelTick);
        NeoForge.EVENT_BUS.addListener(DecorEvents::onServerTick);
        NeoForge.EVENT_BUS.addListener(DecorEvents::onServerStopped);
    }

    private static void onCommonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(DecorEvents::subscribe);
    }

    /** MineColonies' own event bus (not NeoForge's). */
    static void subscribe() {
        if (subscribed) {
            return;
        }
        try {
            final var bus = IMinecoloniesAPI.getInstance().getEventBus();
            bus.subscribe(BuildingRemovedModEvent.class, DecorEvents::onBuildingRemoved);
            bus.subscribe(BuildingConstructionModEvent.class, DecorEvents::onBuildingConstructed);
            bus.subscribe(ColonyManagerLoadedModEvent.class, e -> MANAGER_LOADS.incrementAndGet());
            bus.subscribe(ColonyManagerUnloadedModEvent.class, e -> MANAGER_LOADS.updateAndGet(n -> Math.max(0, n - 1)));
            subscribed = true;
        } catch (final RuntimeException unavailable) {
            // the sweep waits for ColonyManagerLoadedModEvent from this same bus, so it does not run either
            ColonySeasons.LOGGER.warn("MineColonies' event bus is not available, so the server sweep does not run: decorations of removed "
                    + "buildings stay up until the Decorator takes them down after the window, and a colony with no Decorator keeps its "
                    + "decorations until /colonyseasons decor remove. The Decorator's take-down, a broken marker's square and decor remove "
                    + "still work");
        }
    }

    /** True once the subscriptions are in (the boot probe checks it). */
    public static boolean subscribed() {
        return subscribed;
    }

    /** True while MineColonies' colony manager has data loaded. */
    public static boolean managerLoaded() {
        return MANAGER_LOADS.get() > 0;
    }

    public static int swept() {
        return swept;
    }

    public static int lostItems() {
        return lostItems;
    }

    private static void onBuildingRemoved(final BuildingRemovedModEvent event) {
        final IBuilding building = event.getBuilding();
        final IColony colony = building == null ? null : building.getColony();
        if (colony != null) {
            final BlockPos at = building.getPosition().immutable();
            QUEUE.add(new Pending(colony.getDimension(), colony.getID(), What.GROUP, DecorLedger.Owner.building(at), at));
            // a Workshop's storeroom goes with it (storage-look 3.3 point 5); any other building has no such group
            QUEUE.add(new Pending(colony.getDimension(), colony.getID(), What.GROUP, DecorLedger.Owner.storeroom(at), at));
            if (building instanceof BuildingTownSquare) {
                // the square's group sits at its centrepiece, which the stored mark names (read when the queue is worked off)
                QUEUE.add(new Pending(colony.getDimension(), colony.getID(), What.SQUARE_REMOVED, null, at));
            }
        }
    }

    /** MineColonies' Builder finished a work order (build, upgrade, repair, removal): a Town Square's resolver runs again. */
    private static void onBuildingConstructed(final BuildingConstructionModEvent event) {
        if (event.getBuilding() instanceof BuildingTownSquare square) {
            squareChanged(square);
        }
    }

    /**
     * A Town Square's level or state changed (its upgrade completed, a removal order finished): the resolver runs at
     * the end of the tick. Not at once, because MineColonies' Builder calls {@code onUpgradeComplete} before it sets the
     * new level (bytecode of {@code AbstractEntityAIStructureWithWorkOrder}).
     */
    public static void squareChanged(final BuildingTownSquare square) {
        final IColony colony = square.getColony();
        if (colony != null) {
            QUEUE.add(new Pending(colony.getDimension(), colony.getID(), What.SQUARE_REFRESH, null, square.getPosition().immutable()));
        }
    }

    /**
     * [B] design 4.6: a work order on a Town Square (upgrade, repair, removal) takes its group down on the server at
     * once, before the Builder reaches CLEAR_NON_SOLIDS. Called by the square's {@code requestWorkOrder}; the group goes
     * at the end of this tick, {@link DecorWork#SQUARE_SLICE} cells a tick. Work orders that come another way are seen
     * by {@link #squareWatch} within {@link #SQUARE_WATCH} ticks.
     */
    public static void squareOrder(final BuildingTownSquare square) {
        final IColony colony = square.getColony();
        if (colony != null) {
            QUEUE.add(new Pending(colony.getDimension(), colony.getID(), What.SQUARE_ORDER, null, square.getPosition().immutable()));
        }
    }

    /** A festival marker went: its square's decorations go at the end of the tick. */
    public static void squareGone(final ServerLevel level, final int colony, final BlockPos marker) {
        QUEUE.add(new Pending(level.dimension(), colony, What.GROUP, DecorLedger.Owner.square(marker), null));
    }

    /** Lines "only the first marker counts" sent since the server started (the probe counts them). */
    private static int secondMarkerNotices;

    public static int secondMarkerNotices() {
        return secondMarkerNotices;
    }

    /**
     * A marker was put down in a colony: it becomes the colony's square unless the colony has a live one already
     * (one marker per colony in 0.1.0). A registered marker that no longer stands (its chunk loaded) is replaced. A
     * marker that is not registered because the colony has another one (standing, or in a chunk that is not loaded)
     * sends the colony's managers one line that only the first one counts (the owner's answer of 20 Nov 2026), once
     * for this placement; the registered marker placed again at its own position sends nothing. 0.2.0: while a Town
     * Square is the colony's main square, a marker that registers is the fallback only, and the managers get one line
     * that the festival marker is not used while the Town Square stands (design 4.8).
     */
    public static boolean markerPlaced(final ServerLevel level, final int colony, final BlockPos pos) {
        final DecorLedger ledger = DecorLedger.get(level);
        final IColony owner = colonyOf(level, colony);
        // the resolver tells the managers itself when it stores the mark while a (perhaps stale) marker is registered
        final int toldBefore = Squares.markerUnusedNotices();
        if (owner != null) {
            Squares.refresh(level, owner, ledger);
        }
        final BlockPos old = ledger.marker(colony);
        if (old != null && !old.equals(pos) && level.hasChunkAt(old)
                && !(level.getBlockState(old).getBlock() instanceof me.lovkar.seasons.block.FestivalMarkerBlock)) {
            ledger.unregisterMarker(colony, old);
            squareGone(level, colony, old);
            me.lovkar.seasons.festival.Gathering.markerGone(level, colony, old);
        }
        final boolean fresh = !pos.equals(ledger.marker(colony));
        final boolean registered = ledger.registerMarker(colony, pos);
        ledger.flush(level);
        if (!registered) {
            secondMarkerNotices++;
            DecorWork.toManagers(level, colony, "com.colony_seasons.decor.second_marker");
        } else if (fresh && ledger.squareMark(colony) != null && Squares.markerUnusedNotices() == toldBefore) {
            Squares.markerUnused(level, colony);
        }
        return registered;
    }

    /** The colony MineColonies has loaded with this id in this level, or {@code null} (also when MineColonies cannot say). */
    private static IColony colonyOf(final ServerLevel level, final int colony) {
        try {
            return IColonyManager.getInstance().getColonyByWorld(colony, level);
        } catch (final RuntimeException unavailable) {
            return null;
        }
    }

    /** A marker block went: if it was a colony's square, the colony has none now and the square's decorations go. */
    public static void markerRemoved(final ServerLevel level, final BlockPos pos) {
        final DecorLedger ledger = DecorLedger.peek(level);
        if (ledger == null) {
            return;
        }
        final int colony = ledger.markerColony(pos);
        if (colony >= 0) {
            ledger.unregisterMarker(colony, pos);
            squareGone(level, colony, pos);
            me.lovkar.seasons.festival.Gathering.markerGone(level, colony, pos);
        }
    }

    private static void onServerTick(final ServerTickEvent.Post event) {
        drain(event.getServer());
    }

    /**
     * Works off everything queued so far (public for the probe, which may want it done before it looks). A square's
     * work-order take-down that has cells left is queued again for the next call (the next tick), never in this one.
     */
    public static void drain(final MinecraftServer server) {
        List<Pending> again = null;
        final Set<Pending> sliced = new HashSet<>();
        Pending pending;
        while ((pending = QUEUE.poll()) != null) {
            final ServerLevel level = server.getLevel(pending.dimension());
            if (level == null) {
                continue;
            }
            final DecorLedger ledger = DecorLedger.peek(level);
            if (ledger == null) {
                continue;
            }
            switch (pending.what()) {
                case GROUP -> removeQueued(level, ledger, pending);
                case SQUARE_REMOVED -> squareRemoved(level, ledger, pending);
                case SQUARE_REFRESH -> {
                    final IColony colony = colonyOf(level, pending.colony());
                    if (colony != null) {
                        Squares.refresh(level, colony, ledger);
                    }
                }
                case SQUARE_ORDER -> {
                    // the watch and the square's own request may both have asked: one slice a tick
                    if (sliced.add(pending) && orderSlice(level, ledger, pending)) {
                        if (again == null) {
                            again = new ArrayList<>();
                        }
                        again.add(pending);
                    }
                }
            }
        }
        if (again != null) {
            QUEUE.addAll(again);
        }
    }

    private static void removeQueued(final ServerLevel level, final DecorLedger ledger, final Pending pending) {
        if (ledger.roomMark(pending.colony(), pending.owner()) == null) {
            return;
        }
        final IColony colony = colonyOf(level, pending.colony());
        if (pending.guard() != null && colony != null && colony.getServerBuildingManager().getBuilding(pending.guard()) != null) {
            return;                                    // a building is there again (or never went): nothing to do
        }
        removeGroup(level, ledger, colony, pending.colony(), pending.owner(), Integer.MAX_VALUE);
        ledger.flush(level);
    }

    /**
     * The main square's hut is gone (broken, destroyed with its colony): its group comes down whole at once and the
     * resolver falls back in the same tick (to a second square, the marker, the town hall). A square that was not the
     * main one has no group, and a hut that stands again changes nothing.
     */
    private static void squareRemoved(final ServerLevel level, final DecorLedger ledger, final Pending pending) {
        final int id = pending.colony();
        final DecorLedger.SquareMark mark = ledger.squareMark(id);
        if (mark == null || !mark.building().equals(pending.guard())) {
            return;
        }
        final IColony colony = colonyOf(level, id);
        if (colony != null && colony.getServerBuildingManager().getBuilding(pending.guard()) != null) {
            return;
        }
        removeGroup(level, ledger, colony, id, DecorLedger.Owner.square(mark.anchor()), Integer.MAX_VALUE);
        removeGroup(level, ledger, colony, id, DecorLedger.Owner.touches(mark.anchor()), Integer.MAX_VALUE);   // Q12: its planters' touches too
        if (colony != null) {
            Squares.refresh(level, colony, ledger);
        } else {
            ledger.clearSquareMark(id);
        }
        ledger.flush(level);
    }

    /**
     * One slice of a busy main square's take-down: while the stored mark names this hut, the groups at its centrepiece
     * (its set, and its planters' touches, Q12) list entries and the square is busy (a work order on it or inside its
     * box), at most {@link DecorWork#SQUARE_SLICE} cells come down by the exact-state rule, the touches first, items to
     * the Workshop. True when entries are left and this slice took some (it is queued again); a group stuck on unloaded
     * cells stays marked for the sweep.
     */
    private static boolean orderSlice(final ServerLevel level, final DecorLedger ledger, final Pending pending) {
        final int id = pending.colony();
        final DecorLedger.SquareMark mark = ledger.squareMark(id);
        if (mark == null || !mark.building().equals(pending.guard())) {
            return false;
        }
        final DecorLedger.Owner owner = DecorLedger.Owner.square(mark.anchor());
        final DecorLedger.Owner touches = DecorLedger.Owner.touches(mark.anchor());
        if (size(ledger, id, owner) + size(ledger, id, touches) == 0) {
            return false;
        }
        final IColony colony = colonyOf(level, id);
        final IBuilding square = colony == null ? null : colony.getServerBuildingManager().getBuilding(pending.guard());
        if (!(square instanceof BuildingTownSquare) || !DecorWork.busy(colony, square)) {
            return false;
        }
        int touched = 0;
        if (size(ledger, id, touches) > 0) {
            touched += removeGroup(level, ledger, colony, id, touches, DecorWork.SQUARE_SLICE);
        }
        if (touched < DecorWork.SQUARE_SLICE && size(ledger, id, owner) > 0) {
            touched += removeGroup(level, ledger, colony, id, owner, DecorWork.SQUARE_SLICE - touched);
        }
        squareOrderCells += touched;
        ledger.flush(level);
        return touched > 0 && size(ledger, id, owner) + size(ledger, id, touches) > 0;
    }

    /** The entries a group lists (0 for none), read in place. */
    private static int size(final DecorLedger ledger, final int id, final DecorLedger.Owner owner) {
        final DecorLedger.RoomMark group = ledger.roomMark(id, owner);
        return group == null ? 0 : group.size();
    }

    /** Cells a busy square's take-down touched since the server started (the probe reads it). */
    private static int squareOrderCells;

    public static int squareOrderCells() {
        return squareOrderCells;
    }

    /**
     * Every {@link #SQUARE_WATCH} ticks: each colony of the level whose stored main square has a group up at its
     * centrepiece (its set, or its planters' touches, Q12) gets the work-order check of {@link #squareOrder} (it does
     * nothing while the square is free). With no mark stored this is one map read per colony. Returns how many checks it
     * queued.
     */
    public static int squareWatch(final ServerLevel level) {
        if (!managerLoaded()) {
            return 0;
        }
        final DecorLedger ledger = DecorLedger.peek(level);
        if (ledger == null) {
            return 0;
        }
        int queued = 0;
        for (final int id : ledger.colonies()) {
            final DecorLedger.SquareMark mark = ledger.squareMark(id);
            if (mark == null) {
                continue;
            }
            if (size(ledger, id, DecorLedger.Owner.square(mark.anchor())) + size(ledger, id, DecorLedger.Owner.touches(mark.anchor())) > 0) {
                QUEUE.add(new Pending(level.dimension(), id, What.SQUARE_ORDER, null, mark.building()));
                queued++;
            }
        }
        return queued;
    }

    private static void onServerStopped(final ServerStoppedEvent event) {
        QUEUE.clear();
        MANAGER_LOADS.set(0);
        PieceLoader.clear();
        StreetLights.clear();
    }

    private static void onLevelTick(final LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        final long time = level.getGameTime();
        if (time % SQUARE_WATCH == 0) {
            squareWatch(level);
        }
        if (time % SWEEP_INTERVAL == 0) {
            sweep(level);
        }
    }

    /** One pass of the sweep over one level; returns the cells it touched. */
    public static int sweep(final ServerLevel level) {
        if (!managerLoaded()) {
            return 0;
        }
        final DecorLedger ledger = DecorLedger.peek(level);
        if (ledger == null) {
            return 0;
        }
        final CalendarManager.Snapshot snapshot = CalendarManager.current();
        final boolean windowOver = snapshot != null && !DecorPhase.windowOpen(snapshot);
        int budget = SWEEP_BUDGET;
        final int before = budget;
        // a Workshop has two groups at one position now (outside and storeroom): its work orders are read once a pass
        final java.util.Map<BlockPos, Boolean> busy = new java.util.HashMap<>();
        for (final int id : ledger.colonies()) {
            if (budget <= 0) {
                break;
            }
            final IColony colony = IColonyManager.getInstance().getColonyByWorld(id, level);
            if (colony == null && gone(level, ledger, id)) {
                continue;                             // a deleted colony with nothing listed: its record is gone now
            }
            final boolean noDecorator = colony == null || !DecorWork.staffed(colony);
            // the colony's one centrepiece now (0.2.0): every square group anywhere else goes; the main square's mark follows
            final Squares.Square square = colony == null ? null : Squares.refresh(level, colony, ledger);
            if (colony != null) {
                budget -= dropMarksOutOfClaim(level, ledger, colony);
            }
            for (final DecorLedger.GroupView group : ledger.groups(id)) {
                if (budget <= 0) {
                    break;
                }
                if (group.entries().isEmpty()) {
                    if (windowOver) {
                        ledger.dropGroup(id, group.owner());
                    }
                    continue;
                }
                if (group.owner().kind() == DecorLedger.Kind.STOREROOM) {
                    if (roomGoes(level, colony, ledger, id, group, windowOver, busy, square)) {
                        budget -= removeGroup(level, ledger, colony, id, group.owner(), budget);
                    }
                    continue;
                }
                if (group.removing() || windowOver && noDecorator || ownerGoneOrBusy(level, colony, ledger, id, group, busy, square)) {
                    budget -= removeGroup(level, ledger, colony, id, group.owner(), budget);
                }
            }
            // the outside decorations are down (the storeroom stays listed, packed, all year)
            if (colony != null && windowOver && !ledger.hasOutdoorEntries(id)) {
                // the "no room for the tree" note belongs to the window that set it (S10i review)
                ledger.setSquareProblem(id, false);
                for (final IBuilding b : colony.getServerBuildingManager().getBuildings().values()) {
                    if (b instanceof BuildingDecorator hut && hut.decorationsUp() && hut.getAllAssignedCitizen().isEmpty()) {
                        hut.setDecorationsUp(false);
                    }
                }
            }
        }
        if (budget != before || ledger.isDirty()) {
            ledger.flush(level);
        }
        return before - budget;
    }

    /**
     * Q7, H25: every mark of the colony with a loaded cell outside its claim is removed as the wand's shift-click removes
     * one ({@link StreetMarks#remove}): the mark dropped, its pieces down by the exact-state rule (items to the Workshop),
     * a post's garlands with it. Without this such a mark stayed in the colony's record for good, counted against its cap
     * of {@link StreetMarks#MAX_MARKS}, and no click could reach it. Returns the marks removed.
     */
    private static int dropMarksOutOfClaim(final ServerLevel level, final DecorLedger ledger, final IColony colony) {
        final int id = colony.getID();
        int removed = 0;
        for (final StreetMarks.Mark mark : ledger.marks(id)) {
            if (ledger.markAt(id, mark.pos()) != null && StreetWork.outOfClaim(level, colony, mark) && StreetMarks.remove(level, id, mark.pos())) {
                removed++;
                ColonySeasons.LOGGER.info("A street mark of colony {} at {} lies outside the colony's claim now: removed", id, mark.pos().toShortString());
            }
        }
        return removed;
    }

    /**
     * A colony MineColonies does not have any more (the manager is loaded, so it was deleted): its marker and its main
     * square's mark come off at once, so a new colony that gets the same id inherits no square; once nothing of it is
     * listed its record and its festival state are dropped (true then). Its entries go by the rules above (after the
     * window, as with no Decorator).
     */
    private static boolean gone(final ServerLevel level, final DecorLedger ledger, final int id) {
        final BlockPos marker = ledger.marker(id);
        if (marker != null) {
            ledger.unregisterMarker(id, marker);
        }
        ledger.clearSquareMark(id);
        ledger.clearMarket(id);                       // Q10: nor the festival market's paid day (a later colony day of the old one would block it)
        ledger.clearMarks(id);                        // Q11: nor the wand's marks (a new colony with the same id starts with none)
        if (ledger.hasEntries(id)) {
            return false;
        }
        ledger.dropColony(id);
        me.lovkar.seasons.festival.FestivalData.of(level).dropColony(id);
        return true;
    }

    /**
     * A building's group whose building is gone or under construction (a storeroom's Workshop included), or a square
     * group that is not the colony's centrepiece now ({@link Squares.Square#hosts}: the marker when it is the square,
     * the main Town Square's anchor while that square is free; nothing while it is busy). A main square that cannot be
     * located now keeps every square group as it is. The seasonal touches (Q12) go by the same rule, and also when the
     * centrepiece is not a Town Square (they belong to a Town Square only). {@code busy} keeps the work-order test of each
     * position for this pass.
     */
    private static boolean ownerGoneOrBusy(final ServerLevel level, final IColony colony, final DecorLedger ledger, final int id,
                                           final DecorLedger.GroupView group, final java.util.Map<BlockPos, Boolean> busy,
                                           final Squares.Square square) {
        if (colony == null) {
            return false;                             // no colony: only the window's end clears it (windowOver && noDecorator)
        }
        final BlockPos pos = group.owner().pos();
        if (group.owner().kind() == DecorLedger.Kind.SQUARE) {
            return square != null && square.located() && !square.hosts(pos);
        }
        if (group.owner().kind() == DecorLedger.Kind.TOUCHES) {
            return square != null && square.located() && !(square.source() == Squares.Source.BUILDING && square.hosts(pos));
        }
        if (group.owner().kind() == DecorLedger.Kind.STREET) {
            return StreetWork.gone(level, colony, ledger, pos);     // Q11: the mark gone, its cell out of the claim or in a building
        }
        if (group.owner().kind() == DecorLedger.Kind.LIGHTS) {
            return !me.lovkar.seasons.SeasonsConfig.streetLights();   // Q11: the switch turned off
        }
        final IBuilding building = colony.getServerBuildingManager().getBuilding(pos);
        return building == null || busy.computeIfAbsent(pos, p -> DecorWork.busy(colony, building));
    }

    /**
     * The sweep's rules for a Workshop's storeroom (storage-look 3.3 point 4 and 6): it goes when it is marked for
     * removal, when the colony is gone, when the Workshop is gone or under construction (an upgrade or repair: the next
     * check puts the new level's set up), when the room's switch is off, and when the window is over while that
     * Workshop has no worker and the room still shows a festival set. Never otherwise: the packed set is right outside
     * the windows and needs nobody. The per-Workshop worker rule covers "the colony has no Decorator" and also a second,
     * unstaffed Workshop in a colony whose other Workshop has one (its festive room would stay all year).
     */
    private static boolean roomGoes(final ServerLevel level, final IColony colony, final DecorLedger ledger, final int id,
                                    final DecorLedger.GroupView group, final boolean windowOver, final java.util.Map<BlockPos, Boolean> busy,
                                    final Squares.Square square) {
        if (group.removing() || colony == null || !me.lovkar.seasons.SeasonsConfig.storeroomDressing()) {
            return true;
        }
        if (ownerGoneOrBusy(level, colony, ledger, id, group, busy, square)) {
            return true;
        }
        return windowOver && !StoreroomWork.staffed(colony, group.owner().pos()) && StoreroomWork.holdsFestival(group);
    }

    /** Removes one group by the rule, items to the colony's Decorator hut (or lost when there is none); returns cells touched. */
    static int removeGroup(final ServerLevel level, final DecorLedger ledger, final IColony colony, final int id,
                           final DecorLedger.Owner owner, final int budget) {
        final int[] lost = new int[1];
        final DecorPlacer.Removed removed = DecorPlacer.remove(level, ledger, id, owner, DecorWork.sinkOf(colony, lost), budget);
        if (removed.waiting() > 0 || removed.left() > 0) {
            ledger.markRemoving(id, owner);
        }
        if (lost[0] > 0) {
            lostItems += lost[0];
            ColonySeasons.LOGGER.info("Decorations of colony {} taken down with no Decorator's hut to keep them: {} items lost", id, lost[0]);
        }
        swept += removed.touched();
        return removed.touched();
    }
}
