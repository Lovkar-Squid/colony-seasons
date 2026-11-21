package me.lovkar.seasons;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * The client config, {@code colony_seasons-client.toml} in the player's {@code config/} folder: what one player
 * wants to see, which never changes the world or what other players see (design section 9).
 *
 * <p>A CLIENT config is only loaded on the physical client. On a dedicated server, and on a client before the
 * file is read, the getters answer the defaults, like the server config's getters do. Nothing here names a
 * client class, so the mod class may register it on either side.</p>
 *
 * <p>S10i (the owner's answer of 21 Nov 2026): the {@code showParticles} key is gone; the mod has no particles of its
 * own and nothing read it. A file 0.1.0-beta.1 wrote still loads: NeoForge drops the unknown key, keeps the others and
 * writes the file back (csconfig seeded proves it with such a file).</p>
 */
public final class SeasonsClientConfig {

    /** The file name, in the player's {@code config/} folder. */
    public static final String FILE = ColonySeasons.MODID + "-client.toml";

    public static final boolean DEFAULT_SHOW_CLOTHES = true;

    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.BooleanValue SHOW_CLOTHES;

    static {
        final ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.comment("What this player sees. Nothing here changes the world or what other players see.").push("display");
        SHOW_CLOTHES = builder
                .comment("true: colonists wear a scarf (and some a wool hat) in winter and a festive scarf during the",
                        "Winter Festival. false: they look as MineColonies draws them.")
                .translation("com.colony_seasons.config.show_clothes")
                .define("showClothes", DEFAULT_SHOW_CLOTHES);
        builder.pop();
        SPEC = builder.build();
    }

    private SeasonsClientConfig() {
    }

    private static boolean read(final ModConfigSpec.BooleanValue value) {
        if (!SPEC.isLoaded()) {
            return value.getDefault();
        }
        try {
            return value.get();
        } catch (final IllegalStateException notLoaded) {
            return value.getDefault();
        }
    }

    /** Draw the winter clothes. */
    public static boolean showClothes() {
        return read(SHOW_CLOTHES);
    }
}
