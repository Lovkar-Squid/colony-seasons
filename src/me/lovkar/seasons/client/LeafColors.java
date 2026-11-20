package me.lovkar.seasons.client;

import me.lovkar.seasons.block.SeasonsBlocks;
import net.minecraft.world.level.FoliageColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

/**
 * The colour of the ornament and tinsel leaves (S10d): their models draw the game's own spruce-leaf texture with
 * tint index 0 under our ornaments, and this gives that index the fixed evergreen colour vanilla gives spruce leaves
 * ({@link FoliageColor#getEvergreenColor()}, #619961 with full alpha), in the world and in the inventory, so the
 * blocks look as they did when the colour was baked into a copy of the texture. Every other tint index (the ornaments
 * and the lights) is left alone (-1).
 */
public final class LeafColors {

    private LeafColors() {
    }

    /** Mod-bus listeners; called from {@link SeasonsClient#init} on the physical client only. */
    public static void init(final IEventBus modEventBus) {
        modEventBus.addListener(LeafColors::blocks);
        modEventBus.addListener(LeafColors::items);
    }

    /** The colour of tint index {@code index}: the evergreen colour for 0 (full alpha: the item renderer reads it), else none. */
    public static int colour(final int index) {
        return index == 0 ? FoliageColor.getEvergreenColor() : -1;
    }

    private static void blocks(final RegisterColorHandlersEvent.Block event) {
        event.register((state, level, pos, index) -> colour(index), SeasonsBlocks.ORNAMENT_LEAVES.get(), SeasonsBlocks.TINSEL_LEAVES.get());
    }

    private static void items(final RegisterColorHandlersEvent.Item event) {
        event.register((stack, index) -> colour(index), SeasonsBlocks.ORNAMENT_LEAVES_ITEM.get(), SeasonsBlocks.TINSEL_LEAVES_ITEM.get());
    }
}
