package me.lovkar.seasons.festival;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.util.MessageUtils;
import me.lovkar.seasons.ColonySeasons;
import me.lovkar.seasons.calendar.CalendarEvents;
import me.lovkar.seasons.calendar.CalendarManager;
import me.lovkar.seasons.decor.DecorEvents;
import me.lovkar.seasons.decor.DecorWork;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The festival events of every colony (design 5), run from the server tick: every {@link CalendarEvents#POLL_TICKS}
 * ticks, right after the calendar's own poll, it looks at each colony (tree lighting, the gathering, the dusk
 * fireworks) and at the real clock (the New Year countdown and midnight show), and tells each colony's players once
 * when the season turns (design 2.2). A right-click on a block of a colony's finished tree lights it
 * ({@link TreeLighting}); the gift pile hands out gifts itself ({@link Gifts}).
 *
 * <p>Everything that must survive a restart is in {@link FestivalData}; the fields here are counters for the probe
 * and the set the mixin reads.</p>
 */
public final class FestivalDirector {
    /** Every this many polls, the lit tree's upkeep switches on lights put up since. */
    public static final int UPKEEP_POLLS = 5;

    private static int polls;
    private static int seasonLines;

    private FestivalDirector() {
    }

    /** Hooks everything up; called once from the mod constructor, after {@link CalendarEvents#register()}. */
    public static void init(final IEventBus modBus) {
        FestivalSounds.load();
        Gifts.init(modBus);
        NeoForge.EVENT_BUS.addListener(FestivalDirector::onServerTick);
        // LOW: MineColonies' colony protection (NORMAL) answers first, so only a player who may use blocks in the colony
        // lights its tree, and a click it refused never reaches us
        NeoForge.EVENT_BUS.addListener(EventPriority.LOW, FestivalDirector::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(FestivalDirector::onServerStopped);
    }

    /** Season lines sent since the server started (one per colony and season turn). */
    public static int seasonLines() {
        return seasonLines;
    }

    public static int polls() {
        return polls;
    }

    private static void onServerTick(final ServerTickEvent.Post event) {
        final MinecraftServer server = event.getServer();
        Fireworks.tickQueue(server);
        if (server.getTickCount() % CalendarEvents.POLL_TICKS == 0) {
            poll(server);
        }
    }

    /** One look at the calendar and every colony (public for the probe). */
    public static void poll(final MinecraftServer server) {
        final CalendarManager.Snapshot snapshot = CalendarManager.current();
        if (snapshot == null || !DecorEvents.managerLoaded()) {
            return;
        }
        polls++;
        final FestivalData data = FestivalData.get(server);
        seasonLine(data, snapshot);
        Fireworks.midnight(server, data, snapshot);
        final Set<Integer> everyone = new HashSet<>();
        final boolean upkeep = polls % UPKEEP_POLLS == 0;
        for (final IColony colony : List.copyOf(IColonyManager.getInstance().getAllColonies())) {
            if (!(colony.getWorld() instanceof ServerLevel level)) {
                continue;
            }
            try {
                final long gameDay = DecorWork.day(level);
                TreeLighting.tick(level, colony, data, snapshot, gameDay, upkeep);
                if (Gathering.tick(level, colony, data, snapshot, gameDay, server.getTickCount())) {
                    everyone.add(colony.getID());
                }
                Fireworks.dusk(level, colony, data, snapshot, gameDay);
            } catch (final RuntimeException broken) {
                // one colony in a state we did not expect must not stop the others; the next poll tries again
                ColonySeasons.LOGGER.warn("Festival events skipped colony {} this time: {}", colony.getID(), broken.toString());
            }
        }
        Gathering.publish(everyone);
    }

    /** One chat line to each colony's players when the season turns; the season told is saved, so a restart says nothing. */
    private static void seasonLine(final FestivalData data, final CalendarManager.Snapshot snapshot) {
        final String season = snapshot.season().season().id();
        final String before = data.lastSeason();
        if (season.equals(before)) {
            return;
        }
        data.setLastSeason(season);
        if (before.isEmpty()) {
            return;                                   // the first season this world knows: nothing turned
        }
        for (final IColony colony : List.copyOf(IColonyManager.getInstance().getAllColonies())) {
            seasonLines++;
            try {
                MessageUtils.format("com.colony_seasons.festival.season_turned",
                        Component.translatable(snapshot.season().season().translationKey()), colony.getName()).sendTo(colony).forAllPlayers();
            } catch (final RuntimeException unavailable) {
                ColonySeasons.LOGGER.debug("Season line not sent: {}", unavailable.toString());
            }
        }
    }

    private static void onRightClickBlock(final PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel() instanceof ServerLevel level && TreeLighting.onRightClick(level, event.getEntity(), event.getPos())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    private static void onServerStopped(final ServerStoppedEvent event) {
        Fireworks.reset();
        Gathering.reset();
        polls = 0;
    }
}
