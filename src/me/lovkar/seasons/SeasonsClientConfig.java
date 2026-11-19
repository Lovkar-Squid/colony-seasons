package me.lovkar.seasons;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * The client config, {@code colony_seasons-client.toml} in the player's {@code config/} folder: what one player
 * wants to see, which never changes the world or what other players see (design section 9).
 *
 * <p>A CLIENT config is only loaded on the physical client. On a dedicated server, and on a client before the
 * file is read, the getters answer the defaults, like the server config's getters do. Nothing here names a
 * client class, so the mod class may register it on either side.</p>
 */
public final class SeasonsClientConfig {

    /** The file name, in the player's {@code config/} folder. */
    public static final String FILE = ColonySeasons.MODID + "-client.toml";

    public static final boolean DEFAULT_SHOW_CLOTHES = true;
    public static final boolean DEFAULT_SHOW_PARTICLES = true;

    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.BooleanValue SHOW_CLOTHES;
    private static final ModConfigSpec.BooleanValue SHOW_PARTICLES;

    static {
        final ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.comment("What this player sees. Nothing here changes the world or what other players see.").push("display");
        SHOW_CLOTHES = builder
                .comment("true: colonists wear a scarf (and some a wool hat) in winter and a festive scarf during the",
                        "Winter Festival. false: they look as MineColonies draws them.")
                .translation("com.colony_seasons.config.show_clothes")
                .define("showClothes", DEFAULT_SHOW_CLOTHES);
        SHOW_PARTICLES = builder
                .comment("true: festival particles (snow over the tree, the glow of lit decorations, fireworks sparks).",
                        "false: none of this mod's particles.")
                .translation("com.colony_seasons.config.show_particles")
                .define("showParticles", DEFAULT_SHOW_PARTICLES);
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

    /** Spawn this mod's festival particles. */
    public static boolean showParticles() {
        return read(SHOW_PARTICLES);
    }
}
