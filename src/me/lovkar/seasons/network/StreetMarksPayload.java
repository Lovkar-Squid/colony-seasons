package me.lovkar.seasons.network;

import me.lovkar.seasons.ColonySeasons;
import me.lovkar.seasons.decor.StreetMarks;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The marks of the colony a wand's holder stands in (0.2.0, Q11), server to client, for the outlines the wand shows
 * ({@code client/WandMarks}); sent by {@link me.lovkar.seasons.item.WandSync}. Each entry is a mark's first cell, its
 * type id, and a garland's last cell. A list longer than {@link StreetMarks#MAX_MARKS} is cut to that length when it is
 * read (the rest is read and dropped), so a client never holds more than a colony can have.
 *
 * @param colony  the colony's id (-1: none)
 * @param entries its marks
 */
public record StreetMarksPayload(int colony, List<Entry> entries) implements CustomPacketPayload {

    /** One mark as the client sees it. */
    public record Entry(BlockPos pos, String type, @Nullable BlockPos to) {
        public Entry {
            pos = pos.immutable();
            to = to == null ? null : to.immutable();
        }
    }

    public static final CustomPacketPayload.Type<StreetMarksPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ColonySeasons.MODID, "street_marks"));

    /** The longest type id a client reads. */
    public static final int MAX_TYPE = 32;

    public static final StreamCodec<RegistryFriendlyByteBuf, StreetMarksPayload> STREAM_CODEC =
            StreamCodec.of(StreetMarksPayload::write, StreetMarksPayload::read);

    public StreetMarksPayload {
        entries = List.copyOf(entries);
    }

    static void write(final RegistryFriendlyByteBuf buf, final StreetMarksPayload payload) {
        buf.writeVarInt(payload.colony());
        final int n = Math.min(StreetMarks.MAX_MARKS, payload.entries().size());
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            final Entry entry = payload.entries().get(i);
            buf.writeBlockPos(entry.pos());
            buf.writeUtf(entry.type(), MAX_TYPE);
            buf.writeBoolean(entry.to() != null);
            if (entry.to() != null) {
                buf.writeBlockPos(entry.to());
            }
        }
    }

    static StreetMarksPayload read(final RegistryFriendlyByteBuf buf) {
        final int colony = buf.readVarInt();
        final int n = Math.max(0, buf.readVarInt());
        final List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final BlockPos pos = buf.readBlockPos();
            final String type = buf.readUtf(MAX_TYPE);
            final BlockPos to = buf.readBoolean() ? buf.readBlockPos() : null;
            if (entries.size() < StreetMarks.MAX_MARKS) {
                entries.add(new Entry(pos, type, to));
            }
        }
        return new StreetMarksPayload(colony, entries);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * Runs on the client when the packet arrives. The client class is only named inside the lambda, so a dedicated server
     * never loads it (this packet is never delivered there).
     */
    public static void handle(final StreetMarksPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> me.lovkar.seasons.client.WandMarks.accept(payload));
    }
}
