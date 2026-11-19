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
 *       no staffed Decorator hut (or no colony at all). Empty groups are dropped once the window is over.</li>
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
            ColonySeasons.LOGGER.warn("MineColonies' event bus is not available; decorations of removed buildings go with the sweep");
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
        }
    }

    /** A festival marker went: its square's decorations go at the end of the tick. */
    public static void squareGone(final ServerLevel level, final int colony, final BlockPos marker) {
        QUEUE.add(new Pending(level.dimension(), colony, DecorLedger.Owner.square(marker), false));
    }

    /**
     * A marker was put down in a colony: it becomes the colony's square unless the colony has a live one already
     * (one marker per colony in 0.1.0). A registered marker that no longer stands (its chunk loaded) is replaced.
     */
    public static boolean markerPlaced(final ServerLevel level, final int colony, final BlockPos pos) {
        final DecorLedger ledger = DecorLedger.get(level);
        final BlockPos old = ledger.marker(colony);
        if (old != null && !old.equals(pos) && level.hasChunkAt(old)
                && !(level.getBlockState(old).getBlock() instanceof me.lovkar.seasons.block.FestivalMarkerBlock)) {
            ledger.unregisterMarker(colony, old);
            squareGone(level, colony, old);
        }
        final boolean registered = ledger.registerMarker(colony, pos);
        ledger.flush(level);
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
        for (final int id : ledger.colonies()) {
            if (budget <= 0) {
                break;
            }
            final IColony colony = IColonyManager.getInstance().getColonyByWorld(id, level);
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
                if (group.removing() || windowOver && noDecorator || ownerGoneOrBusy(level, colony, ledger, id, group)) {
                    budget -= removeGroup(level, ledger, colony, id, group.owner(), budget);
                }
            }
            if (colony != null && windowOver && !ledger.hasEntries(id)) {
                for (final IBuilding b : colony.getServerBuildingManager().getBuildings().values()) {
                    if (b instanceof BuildingDecorator hut && hut.decorationsUp() && hut.getAllAssignedCitizen().isEmpty()) {
                        hut.setDecorationsUp(false);
                    }
                }
            }
        }
        if (budget != before) {
            ledger.flush(level);
        }
        return before - budget;
    }

    /** A building's group whose building is gone or under construction, or a square whose marker is gone. */
    private static boolean ownerGoneOrBusy(final ServerLevel level, final IColony colony, final DecorLedger ledger, final int id,
                                           final DecorLedger.GroupView group) {
        if (colony == null) {
            return false;                             // no colony: only the window's end clears it (windowOver && noDecorator)
        }
        final BlockPos pos = group.owner().pos();
        if (group.owner().kind() == DecorLedger.Kind.SQUARE) {
            return !pos.equals(ledger.marker(id));
        }
        final IBuilding building = colony.getServerBuildingManager().getBuilding(pos);
        return building == null || DecorWork.busy(colony, building);
    }

    /** Removes one group by the rule, items to the colony's Decorator hut (or lost when there is none); returns cells touched. */
    static int removeGroup(final ServerLevel level, final DecorLedger ledger, final IColony colony, final int id,
                           final DecorLedger.Owner owner, final int budget) {
        final int[] lost = new int[1];
        final DecorPlacer.Removed removed = DecorPlacer.remove(level, ledger, id, owner, DecorWork.sinkOf(colony, lost), budget);
        if (removed.waiting() > 0) {
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
