package me.lovkar.seasons.item;

import me.lovkar.seasons.decor.DecorLedger;
import me.lovkar.seasons.decor.DecorPlacer;
import me.lovkar.seasons.decor.StreetMarks;
import me.lovkar.seasons.network.StreetMarksPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The wand's view of the marks (0.2.0, Q11), the server's half: a player holding the Decorator's wand is sent the marks
 * of the colony he stands in, at most once a second ({@link #INTERVAL} ticks), and only when that colony or its marks
 * changed since the last list he got ({@link DecorLedger#marksRevision}). Outside any colony he gets an empty list once.
 * Nothing is drawn or kept on the server for the view but the last (level, colony, revision) per player, forgotten when
 * he logs out.
 */
public final class WandSync {
    public static final int INTERVAL = 20;

    private record Sent(String level, int colony, int revision) {
    }

    private static final Map<UUID, Sent> LAST = new HashMap<>();
    private static int sent;

    private WandSync() {
    }

    /** Hooks the player tick; called once from the mod constructor. */
    public static void register() {
        NeoForge.EVENT_BUS.addListener(WandSync::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(WandSync::onLogout);
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> LAST.clear());
    }

    /** Lists sent since the server started (the probe counts them). */
    public static int sent() {
        return sent;
    }

    private static void onPlayerTick(final PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player && player.tickCount % INTERVAL == 0) {
            update(player);
        }
    }

    private static void onLogout(final PlayerEvent.PlayerLoggedOutEvent event) {
        LAST.remove(event.getEntity().getUUID());
    }

    /** True when the player holds a wand in either hand. */
    public static boolean holding(final ServerPlayer player) {
        return DecoratorWandItem.is(player.getMainHandItem()) || DecoratorWandItem.is(player.getOffhandItem());
    }

    /** The list for a player now, or {@code null} when he has it already (or holds no wand). Public for the probe. */
    public static StreetMarksPayload update(final ServerPlayer player) {
        if (!holding(player)) {
            return null;
        }
        final ServerLevel level = player.serverLevel();
        final int colony = DecorPlacer.colonyAt(level, player.blockPosition());
        final DecorLedger ledger = DecorLedger.peek(level);
        final int revision = ledger == null || colony == DecorPlacer.NO_COLONY ? 0 : ledger.marksRevision(colony);
        final Sent now = new Sent(level.dimension().location().toString(), colony, revision);
        if (now.equals(LAST.get(player.getUUID()))) {
            return null;
        }
        LAST.put(player.getUUID(), now);
        final List<StreetMarksPayload.Entry> entries = new ArrayList<>();
        if (ledger != null && colony != DecorPlacer.NO_COLONY) {
            for (final StreetMarks.Mark mark : ledger.marks(colony)) {
                entries.add(new StreetMarksPayload.Entry(mark.pos(), mark.type().id(), mark.to()));
            }
        }
        final StreetMarksPayload payload = new StreetMarksPayload(colony, entries);
        sent++;
        if (player.connection != null && !(player instanceof net.neoforged.neoforge.common.util.FakePlayer)) {
            PacketDistributor.sendToPlayer(player, payload);
        }
        return payload;
    }
}
