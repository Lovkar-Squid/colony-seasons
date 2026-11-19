package me.lovkar.seasons.network;

import me.lovkar.seasons.ColonySeasons;
import me.lovkar.seasons.calendar.Season;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/**
 * What the server tells every client about the calendar: the season, the day of the year, how long the
 * year is and the ids of the active festivals. Sent at login and on every change. A client never works out a
 * date by itself, so every player sees the same thing and the force command works.
 *
 * <p>"Active" means a festival whose decoration window or festival days are open (see
 * {@code FestivalCalendar.Active#ids()}); the festival days alone stay with the server.</p>
 *
 * @param season     the season
 * @param dayOfYear  0 to yearLength - 1
 * @param yearLength days in the year
 * @param festivals  ids of the festivals that are on
 */
public record SeasonStatePayload(Season season, int dayOfYear, int yearLength, List<String> festivals)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SeasonStatePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ColonySeasons.MODID, "season_state"));

    private static final StreamCodec<io.netty.buffer.ByteBuf, Season> SEASON_CODEC =
            ByteBufCodecs.idMapper(Season::byOrdinal, Season::ordinal);

    public static final StreamCodec<RegistryFriendlyByteBuf, SeasonStatePayload> STREAM_CODEC = StreamCodec.composite(
            SEASON_CODEC, SeasonStatePayload::season,
            ByteBufCodecs.VAR_INT, SeasonStatePayload::dayOfYear,
            ByteBufCodecs.VAR_INT, SeasonStatePayload::yearLength,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), SeasonStatePayload::festivals,
            SeasonStatePayload::new);

    public SeasonStatePayload {
        festivals = List.copyOf(festivals);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * Runs on the client when the packet arrives. The client class is only named inside the lambda, so a dedicated
     * server never loads it (this packet is never delivered there).
     */
    public static void handle(final SeasonStatePayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> me.lovkar.seasons.client.ClientSeasonState.accept(payload));
    }
}
