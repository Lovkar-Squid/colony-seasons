package me.lovkar.seasons.csclient;

import io.netty.buffer.Unpooled;
import me.lovkar.seasons.ColonySeasons;
import me.lovkar.seasons.SeasonsClientConfig;
import me.lovkar.seasons.calendar.Season;
import me.lovkar.seasons.network.SeasonStatePayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.config.ModConfigs;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Headless check of the client half of Colony Seasons on the dedicated test server (chunk S6): the server boots
 * with the clothes code in the jar, the client config {@code colony_seasons-client.toml} is registered as a CLIENT
 * config but, as on every dedicated server, never loaded, so its getters answer the hand-written defaults of design
 * section 9; and the season packet decodes a season id outside 0 to 3 to a real season instead of null.
 * Touches no class of the {@code client} package. Prints "[csclient] CHECK ..." lines and
 * "[csclient] RESULT csclient: PASS|FAIL", then halts.
 */
@Mod("colonyseasonsclient")
public class CsClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("csclient");
    private int ticks = 0;
    private boolean failed = false;

    public CsClient() {
        NeoForge.EVENT_BUS.addListener(this::onTick);
    }

    private void check(final String what, final boolean ok, final String detail) {
        LOGGER.info("[csclient] CHECK {}: {} {}", what, ok ? "OK" : "FAIL", detail);
        failed |= !ok;
    }

    private void onTick(final ServerTickEvent.Post event) {
        if (++ticks != 100) {
            return;
        }
        final MinecraftServer server = event.getServer();
        try {
            check("dedicated server", FMLEnvironment.dist.isDedicatedServer(), String.valueOf(FMLEnvironment.dist));

            final List<String> clientFiles = ModConfigs.getConfigFileNames(ColonySeasons.MODID, ModConfig.Type.CLIENT);
            check("client config registered", clientFiles.equals(List.of("colony_seasons-client.toml")), String.valueOf(clientFiles));
            check("client config file name constant", "colony_seasons-client.toml".equals(SeasonsClientConfig.FILE), SeasonsClientConfig.FILE);
            check("client config not loaded on a server", !SeasonsClientConfig.SPEC.isLoaded(), "a CLIENT config is read by the physical client only");
            // design section 9, by hand
            check("showClothes default", SeasonsClientConfig.showClothes(), String.valueOf(SeasonsClientConfig.showClothes()));
            // S10i: showParticles is gone; csconfig loads the client file itself and checks its keys
            check("default constant", SeasonsClientConfig.DEFAULT_SHOW_CLOTHES, "true");

            final SeasonStatePayload sent = new SeasonStatePayload(Season.WINTER, 40, 48, List.of("winter_festival"));
            final SeasonStatePayload back = decode(encoded(server, sent));
            check("packet round trip", sent.equals(back), String.valueOf(back));
            for (final int id : new int[]{4, 9, 1000, -1}) {
                final RegistryFriendlyByteBuf raw = buffer(server);
                raw.writeVarInt(id);
                raw.writeVarInt(5);
                raw.writeVarInt(48);
                raw.writeVarInt(0);
                final SeasonStatePayload odd = decode(raw);
                final Season want = id < 0 ? Season.SPRING : Season.WINTER;
                check("season id " + id + " decodes to a real season", odd.season() == want, "got " + odd.season() + ", want " + want);
            }
        } catch (final Throwable t) {
            check("probe ran to the end", false, t.toString());
        }
        LOGGER.info("[csclient] RESULT csclient: {}", failed ? "FAIL" : "PASS");
        server.halt(false);
    }

    private static RegistryFriendlyByteBuf buffer(final MinecraftServer server) {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
    }

    private static RegistryFriendlyByteBuf encoded(final MinecraftServer server, final SeasonStatePayload payload) {
        final RegistryFriendlyByteBuf buf = buffer(server);
        SeasonStatePayload.STREAM_CODEC.encode(buf, payload);
        return buf;
    }

    private static SeasonStatePayload decode(final RegistryFriendlyByteBuf buf) {
        return SeasonStatePayload.STREAM_CODEC.decode(buf);
    }
}
