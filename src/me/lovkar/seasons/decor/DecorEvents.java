package me.lovkar.seasons.decor;

import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.eventbus.events.ColonyManagerLoadedModEvent;
import com.minecolonies.api.eventbus.events.ColonyManagerUnloadedModEvent;
import com.minecolonies.api.eventbus.events.colony.buildings.BuildingRemovedModEvent;
import me.lovkar.seasons.ColonySeasons;
import me.lovkar.seasons.calendar.CalendarManager;
import me.lovkar.seasons.colony.BuildingDecorator;
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

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The server's side of the decorations, with no Decorator needed (design 3.3 and 3.2):
 *
 * <ul>
 *   <li>MineColonies' {@code BuildingRemovedModEvent} (its own event bus): that building's decorations go at the end
 *       of the server tick, if the colony really has no building there any more;</li>
 *   <li>a festival marker broken: its square goes the same way;</li>
 *   <li>the sweep, every {@link #SWEEP_INTERVAL} ticks per level, at most {@link #SWEEP_BUDGET} cells a pass, only in
 *       loaded chunks and never before MineColonies' colony manager has loaded: groups marked for removal; a
 *       building's group while the building is under construction (an upgrade or repair would clear our blocks in
 *       its CLEAR_NON_SOLIDS stage) or once the building is gone; and after the window, everything of a colony with
 *       no staffed Decorator hut (or no colony at all). Empty groups are dropped once the window is over. The storeroom
 *       of a Workshop (S10g2, storage-look 3.3 point 4) has rules of its own ({@link #roomGoes}): it stays packed all
 *       year with nobody's help, so only a festival set is cleared after the window, and only when that Workshop has
 *       no worker;</li>
 *   <li>a colony MineColonies no longer has (deleted): its marker is taken off at once, and its record here and its
 *       festival state are dropped once it lists no entry (S9, H12: a new colony may get its id).</li>
 * </ul>
 * Events only enqueue; the queue is drained with {@code poll()} and never iterated.
 */
public final class DecorEvents {

    public static final int SWEEP_INTERVAL = 100;
    public static final int SWEEP_BUDGET = 256;

    /** A group to remove at the end of the tick; {@code ifGone}: only if the colony has no building at the owner's position. */
    private record Pending(ResourceKey<Level> dimension, int colony, DecorLedger.Owner owner, boolean ifGone) {
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
            QUEUE.add(new Pending(colony.getDimension(), colony.getID(), DecorLedger.Owner.building(building.getPosition()), true));
            // a Workshop's storeroom goes with it (storage-look 3.3 point 5); any other building has no such group
            QUEUE.add(new Pending(colony.getDimension(), colony.getID(), DecorLedger.Owner.storeroom(building.getPosition()), true));
        }
    }

    /** A festival marker went: its square's decorations go at the end of the tick. */
    public static void squareGone(final ServerLevel level, final int colony, final BlockPos marker) {
        QUEUE.add(new Pending(level.dimension(), colony, DecorLedger.Owner.square(marker), false));
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
     * for this placement; the registered marker placed again at its own position sends nothing.
     */
    public static boolean markerPlaced(final ServerLevel level, final int colony, final BlockPos pos) {
        final DecorLedger ledger = DecorLedger.get(level);
        final BlockPos old = ledger.marker(colony);
        if (old != null && !old.equals(pos) && level.hasChunkAt(old)
                && !(level.getBlockState(old).getBlock() instanceof me.lovkar.seasons.block.FestivalMarkerBlock)) {
            ledger.unregisterMarker(colony, old);
            squareGone(level, colony, old);
            me.lovkar.seasons.festival.Gathering.markerGone(level, colony, old);
        }
        final boolean registered = ledger.registerMarker(colony, pos);
        ledger.flush(level);
        if (!registered) {
            secondMarkerNotices++;
            DecorWork.toManagers(level, colony, "com.colony_seasons.decor.second_marker");
        }
        return registered;
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

    /** Works off everything queued so far (public for the probe, which may want it done before it looks). */
    public static void drain(final MinecraftServer server) {
        Pending pending;
        while ((pending = QUEUE.poll()) != null) {
            final ServerLevel level = server.getLevel(pending.dimension());
            if (level == null) {
                continue;
            }
            final DecorLedger ledger = DecorLedger.peek(level);
            if (ledger == null || ledger.group(pending.colony(), pending.owner()) == null) {
                continue;
            }
            final IColony colony = IColonyManager.getInstance().getColonyByWorld(pending.colony(), level);
            if (pending.ifGone() && colony != null && colony.getServerBuildingManager().getBuilding(pending.owner().pos()) != null) {
                continue;                              // a building is there again (or never went): nothing to do
            }
            removeGroup(level, ledger, colony, pending.colony(), pending.owner(), Integer.MAX_VALUE);
            ledger.flush(level);
        }
    }

    private static void onServerStopped(final ServerStoppedEvent event) {
        QUEUE.clear();
        MANAGER_LOADS.set(0);
        PieceLoader.clear();
    }

    private static void onLevelTick(final LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level && level.getGameTime() % SWEEP_INTERVAL == 0) {
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
                    if (roomGoes(level, colony, ledger, id, group, windowOver, busy)) {
                        budget -= removeGroup(level, ledger, colony, id, group.owner(), budget);
                    }
                    continue;
                }
                if (group.removing() || windowOver && noDecorator || ownerGoneOrBusy(level, colony, ledger, id, group, busy)) {
                    budget -= removeGroup(level, ledger, colony, id, group.owner(), budget);
                }
            }
            // the outside decorations are down (the storeroom stays listed, packed, all year)
            if (colony != null && windowOver && !ledger.hasOutdoorEntries(id)) {
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
     * A colony MineColonies does not have any more (the manager is loaded, so it was deleted): its marker comes off at
     * once, so a new colony that gets the same id inherits no square; once nothing of it is listed its record and its
     * festival state are dropped (true then). Its entries go by the rules above (after the window, as with no Decorator).
     */
    private static boolean gone(final ServerLevel level, final DecorLedger ledger, final int id) {
        final BlockPos marker = ledger.marker(id);
        if (marker != null) {
            ledger.unregisterMarker(id, marker);
        }
        if (ledger.hasEntries(id)) {
            return false;
        }
        ledger.dropColony(id);
        me.lovkar.seasons.festival.FestivalData.of(level).dropColony(id);
        return true;
    }

    /**
     * A building's group whose building is gone or under construction (a storeroom's Workshop included), or a square
     * whose marker is gone. {@code busy} keeps the work-order test of each position for this pass.
     */
    private static boolean ownerGoneOrBusy(final ServerLevel level, final IColony colony, final DecorLedger ledger, final int id,
                                           final DecorLedger.GroupView group, final java.util.Map<BlockPos, Boolean> busy) {
        if (colony == null) {
            return false;                             // no colony: only the window's end clears it (windowOver && noDecorator)
        }
        final BlockPos pos = group.owner().pos();
        if (group.owner().kind() == DecorLedger.Kind.SQUARE) {
            return !pos.equals(ledger.marker(id));
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
                                    final DecorLedger.GroupView group, final boolean windowOver, final java.util.Map<BlockPos, Boolean> busy) {
        if (group.removing() || colony == null || !me.lovkar.seasons.SeasonsConfig.storeroomDressing()) {
            return true;
        }
        if (ownerGoneOrBusy(level, colony, ledger, id, group, busy)) {
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
