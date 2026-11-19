package me.lovkar.seasons.client;

import me.lovkar.seasons.ColonySeasons;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/**
 * Client-only hooks, registered by NeoForge on the physical client only (the {@code value = Dist.CLIENT} of the
 * annotation), so this class and the Minecraft client classes it names are never loaded on a dedicated server.
 */
@EventBusSubscriber(modid = ColonySeasons.MODID, value = Dist.CLIENT)
public final class SeasonsClient {
    private SeasonsClient() {
    }

    /** The state belongs to the world that sent it: forget it when the player leaves. */
    @SubscribeEvent
    public static void onLoggingOut(final ClientPlayerNetworkEvent.LoggingOut event) {
        ClientSeasonState.clear();
    }
}
