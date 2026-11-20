package me.lovkar.seasons.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * The packets of this mod. Protocol version "2" since S10e (the Workshop's storage upgrade: a packet to the server and
 * a new module on the hut's page): a change to a packet's layout bumps it.
 */
public final class SeasonsNetwork {
    public static final String PROTOCOL_VERSION = "2";

    private SeasonsNetwork() {
    }

    public static void registerPayloads(final RegisterPayloadHandlersEvent event) {
        event.registrar(PROTOCOL_VERSION)
                .playToClient(SeasonStatePayload.TYPE, SeasonStatePayload.STREAM_CODEC, SeasonStatePayload::handle)
                .playToServer(UpgradeStoragePayload.TYPE, UpgradeStoragePayload.STREAM_CODEC, UpgradeStoragePayload::handle);
    }
}
