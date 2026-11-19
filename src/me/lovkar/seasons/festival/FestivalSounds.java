package me.lovkar.seasons.festival;

import me.lovkar.seasons.ColonySeasons;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;

/**
 * The festival's own sounds, synthesized by {@code tools/sfx/*.py} (nothing copied): the chime when the tree is
 * lit and the short song of the gathering. Their files and subtitles are in {@code assets/colony_seasons/sounds.json}.
 */
public final class FestivalSounds {
    public static final DeferredHolder<SoundEvent, SoundEvent> CHIME = register("festival.chime");
    public static final DeferredHolder<SoundEvent, SoundEvent> SONG = register("festival.song");

    private FestivalSounds() {
    }

    private static DeferredHolder<SoundEvent, SoundEvent> register(final String name) {
        return ColonySeasons.SOUNDS.register(name,
                () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(ColonySeasons.MODID, name)));
    }

    /** Loads the registrations above (called once from the mod constructor). */
    static void load() {
    }
}
