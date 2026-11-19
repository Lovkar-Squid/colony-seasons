package me.lovkar.seasons.calendar;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** The game-bus events that keep {@link CalendarManager} current. */
public final class CalendarEvents {
    /** The calendar is polled once a second (20 ticks), as the Serene Seasons bridge needs. */
    public static final int POLL_TICKS = 20;

    private CalendarEvents() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(CalendarEvents::onServerStarting);
        NeoForge.EVENT_BUS.addListener(CalendarEvents::onServerTick);
        NeoForge.EVENT_BUS.addListener(CalendarEvents::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(CalendarEvents::onServerStopped);
    }

    private static void onServerStarting(final ServerStartingEvent event) {
        CalendarManager.reset();
    }

    private static void onServerTick(final ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % POLL_TICKS == 0) {
            CalendarManager.refresh(event.getServer());
        }
    }

    private static void onPlayerLoggedIn(final PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && player.getServer() != null) {
            CalendarManager.sendTo(player.getServer(), player);
        }
    }

    private static void onServerStopped(final ServerStoppedEvent event) {
        CalendarManager.reset();
    }
}
