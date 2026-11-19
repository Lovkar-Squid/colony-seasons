package me.lovkar.seasons.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** The packets of this mod. Protocol version "1": a change to a packet's layout bumps it. */
public final class SeasonsNetwork {
    public static final String PROTOCOL_VERSION = "1";

    private SeasonsNetwork() {
    }

    public static void registerPayloads(final RegisterPayloadHandlersEvent event) {
        event.registrar(PROTOCOL_VERSION).playToClient(
                SeasonStatePayload.TYPE, SeasonStatePayload.STREAM_CODEC, SeasonStatePayload::handle);
    }
}
