package me.lovkar.seasons.links;

import me.lovkar.seasons.SeasonsConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * The one chat line with the Discord and Patreon links (the owner's decision of 20 Nov 2026): sent by the server the
 * first time a player joins a world with the mod, word for word as he approved it, with both links clickable. Once
 * per player and never again, also not by his other mods later: the marker is kept in the player's persistent data
 * under a neutral key that names no single mod, so each of his mods that shows the line reads and sets the same one:
 *
 * <pre>getPersistentData() / "PlayerPersisted" / "lovkar_mods" / "welcome_shown" (boolean)</pre>
 *
 * <p>{@code PlayerPersisted} ({@link Player#PERSISTED_NBT_TAG}) is the compound NeoForge copies to the new player
 * object on death and on the way back from the End, so the marker is never lost there. The server config switch
 * {@code links.showWelcome} (default true) turns the line off; with it off nothing is sent and no marker is written,
 * so switching it on later still greets a player once.</p>
 */
public final class Welcome {
    public static final String DISCORD_URL = "https://discord.gg/BVztrTtXFu";
    public static final String PATREON_URL = "https://www.patreon.com/cw/Lovkar";
    /** The links as the line shows them. */
    static final String DISCORD_SHOWN = "discord.gg/BVztrTtXFu";
    static final String PATREON_SHOWN = "patreon.com/cw/Lovkar";
    /** The line, word for word as the owner approved it on 20 Nov 2026 (the dot between the two halves is U+00B7). */
    public static final String TEXT = "Thanks for playing Lovkar's mods! News, help and test builds on Discord: discord.gg/BVztrTtXFu"
            + " · Support the mods on Patreon: patreon.com/cw/Lovkar";
    /** The shared marker: a compound named for no single mod, and the boolean in it. */
    public static final String SHARED = "lovkar_mods";
    public static final String SHOWN = "welcome_shown";

    private static volatile int sent;

    private Welcome() {
    }

    /** Hooks the login event (server side); called once from the mod constructor. */
    public static void register() {
        NeoForge.EVENT_BUS.addListener(Welcome::onLoggedIn);
    }

    /** Lines sent since the server started (the probe counts them). */
    public static int sent() {
        return sent;
    }

    private static void onLoggedIn(final PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            offer(player);
        }
    }

    /**
     * The rule, in this order: the switch off, nothing (and no marker written); the marker set (by this mod or another
     * of the owner's), nothing; otherwise the marker is set and the line sent. True when the line was sent.
     */
    public static boolean offer(final ServerPlayer player) {
        if (player == null || !SeasonsConfig.showWelcome()) {
            return false;
        }
        if (shown(player)) {
            return false;
        }
        markShown(player);
        player.sendSystemMessage(message());
        sent++;
        return true;
    }

    /** True when the shared marker is set on this player. */
    public static boolean shown(final Player player) {
        final CompoundTag data = player.getPersistentData();
        if (!data.contains(Player.PERSISTED_NBT_TAG, Tag.TAG_COMPOUND)) {
            return false;
        }
        final CompoundTag persisted = data.getCompound(Player.PERSISTED_NBT_TAG);
        return persisted.contains(SHARED, Tag.TAG_COMPOUND) && persisted.getCompound(SHARED).getBoolean(SHOWN);
    }

    /** Sets the shared marker; compounds that are missing are made, an existing one keeps every other key it holds. */
    static void markShown(final Player player) {
        final CompoundTag data = player.getPersistentData();
        final CompoundTag persisted = data.contains(Player.PERSISTED_NBT_TAG, Tag.TAG_COMPOUND)
                ? data.getCompound(Player.PERSISTED_NBT_TAG) : new CompoundTag();
        final CompoundTag shared = persisted.contains(SHARED, Tag.TAG_COMPOUND) ? persisted.getCompound(SHARED) : new CompoundTag();
        shared.putBoolean(SHOWN, true);
        persisted.put(SHARED, shared);
        data.put(Player.PERSISTED_NBT_TAG, persisted);
    }

    /** The line: {@link #TEXT} as plain parts, the two links as underlined parts that open their URL when clicked. */
    public static Component message() {
        final int discord = TEXT.indexOf(DISCORD_SHOWN);
        final int patreon = TEXT.indexOf(PATREON_SHOWN);
        final MutableComponent line = Component.empty();
        line.append(Component.literal(TEXT.substring(0, discord)));
        line.append(link(DISCORD_SHOWN, DISCORD_URL));
        line.append(Component.literal(TEXT.substring(discord + DISCORD_SHOWN.length(), patreon)));
        line.append(link(PATREON_SHOWN, PATREON_URL));
        line.append(Component.literal(TEXT.substring(patreon + PATREON_SHOWN.length())));
        return line;
    }

    private static Component link(final String shown, final String url) {
        return Component.literal(shown).withStyle(Style.EMPTY.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url)).withUnderlined(true));
    }
}
