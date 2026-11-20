package me.lovkar.seasons.block;

import me.lovkar.seasons.ColonySeasons;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;

/**
 * The decoration blocks of chunk S2 (design 3.1), their items, the two block entity types and the mod's creative
 * tab. The blocks and items go into {@link ColonySeasons#BLOCKS} and {@link ColonySeasons#ITEMS}; the tab has its own
 * register here, hooked to the mod bus by {@link #register}.
 *
 * <p>The ids are fixed: the tree pieces, the Decorator and the ledger of later chunks name them. Textures, models and
 * blockstates come from tools/textures/*.py; loot tables, recipes and tags from tools/gen_blockdata.py.</p>
 */
public final class SeasonsBlocks {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ColonySeasons.MODID);

    // ---- on a wall face: no collision, break at once, pop off with the wall behind
    public static final DeferredBlock<WallDecorationBlock> WREATH = ColonySeasons.BLOCKS.register("wreath",
            () -> new WallDecorationBlock(wall(SoundType.GRASS, MapColor.PLANT)));
    public static final DeferredBlock<WallDecorationBlock> GARLAND = ColonySeasons.BLOCKS.register("garland",
            () -> new WallDecorationBlock(wall(SoundType.GRASS, MapColor.PLANT)));
    public static final DeferredBlock<LanternStringBlock> LANTERN_STRING = ColonySeasons.BLOCKS.register("lantern_string",
            () -> new LanternStringBlock(wall(SoundType.WOOL, MapColor.COLOR_ORANGE).lightLevel(Lights.whenLit(Lights.LANTERN_STRING))));
    public static final DeferredBlock<LitWallDecorationBlock> FAIRY_LIGHTS = ColonySeasons.BLOCKS.register("fairy_lights",
            () -> new LitWallDecorationBlock(wall(SoundType.CHAIN, MapColor.COLOR_GRAY).lightLevel(Lights.whenLit(Lights.FAIRY_LIGHTS))));

    // ---- the tree pieces
    public static final DeferredBlock<OrnamentLeavesBlock> ORNAMENT_LEAVES = ColonySeasons.BLOCKS.register("ornament_leaves",
            () -> new OrnamentLeavesBlock(leaves().lightLevel(Lights.whenLit(Lights.ORNAMENT_LEAVES))));
    public static final DeferredBlock<OrnamentLeavesBlock> TINSEL_LEAVES = ColonySeasons.BLOCKS.register("tinsel_leaves",
            () -> new OrnamentLeavesBlock(leaves().lightLevel(Lights.whenLit(Lights.TINSEL_LEAVES))));
    public static final DeferredBlock<TreeStarBlock> TREE_STAR = ColonySeasons.BLOCKS.register("tree_star",
            () -> new TreeStarBlock(star().lightLevel(Lights.whenLit(Lights.TREE_STAR))));
    public static final DeferredBlock<LargeStarBlock> LARGE_STAR = ColonySeasons.BLOCKS.register("large_star",
            () -> new LargeStarBlock(star().lightLevel(Lights.whenLit(Lights.LARGE_STAR))));
    /**
     * The festival tree's trunk (S10c): a spruce log to look at, in no log tag, so MineColonies' Forester never cuts the
     * tree. No item, no recipe and not in {@link #ALL}: only the Decorator places it, and it drops a spruce log.
     */
    public static final DeferredBlock<FestivalTrunkBlock> FESTIVAL_TRUNK = ColonySeasons.BLOCKS.register("festival_trunk",
            () -> new FestivalTrunkBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.SPRUCE_LOG)));

    // ---- on the square
    public static final DeferredBlock<GiftPileBlock> GIFT_PILE = ColonySeasons.BLOCKS.register("gift_pile",
            () -> new GiftPileBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_RED).strength(0.5F)
                    .sound(SoundType.WOOL).noOcclusion()));
    public static final DeferredBlock<FireworkCrateBlock> FIREWORK_CRATE = ColonySeasons.BLOCKS.register("firework_crate",
            () -> new FireworkCrateBlock(BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(1.5F)
                    .sound(SoundType.WOOD).noOcclusion()));
    public static final DeferredBlock<FestivalMarkerBlock> FESTIVAL_MARKER = ColonySeasons.BLOCKS.register("festival_marker",
            () -> new FestivalMarkerBlock(BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(0.5F)
                    .sound(SoundType.WOOD).noCollission().noOcclusion().pushReaction(PushReaction.DESTROY)));

    /** Every decoration block, in creative-tab order. */
    public static final List<DeferredBlock<? extends Block>> ALL = List.of(WREATH, GARLAND, LANTERN_STRING, FAIRY_LIGHTS,
            ORNAMENT_LEAVES, TINSEL_LEAVES, TREE_STAR, LARGE_STAR, GIFT_PILE, FIREWORK_CRATE, FESTIVAL_MARKER);
    /** The wall blocks (no collision, instant break, support behind). */
    public static final List<DeferredBlock<? extends WallDecorationBlock>> WALL = List.of(WREATH, GARLAND, LANTERN_STRING, FAIRY_LIGHTS);

    public static final DeferredItem<BlockItem> WREATH_ITEM = ColonySeasons.ITEMS.registerSimpleBlockItem(WREATH);
    public static final DeferredItem<BlockItem> GARLAND_ITEM = ColonySeasons.ITEMS.registerSimpleBlockItem(GARLAND);
    public static final DeferredItem<BlockItem> LANTERN_STRING_ITEM = ColonySeasons.ITEMS.registerSimpleBlockItem(LANTERN_STRING);
    public static final DeferredItem<BlockItem> FAIRY_LIGHTS_ITEM = ColonySeasons.ITEMS.registerSimpleBlockItem(FAIRY_LIGHTS);
    public static final DeferredItem<BlockItem> ORNAMENT_LEAVES_ITEM = ColonySeasons.ITEMS.registerSimpleBlockItem(ORNAMENT_LEAVES);
    public static final DeferredItem<BlockItem> TINSEL_LEAVES_ITEM = ColonySeasons.ITEMS.registerSimpleBlockItem(TINSEL_LEAVES);
    public static final DeferredItem<BlockItem> TREE_STAR_ITEM = ColonySeasons.ITEMS.registerSimpleBlockItem(TREE_STAR);
    public static final DeferredItem<BlockItem> LARGE_STAR_ITEM = ColonySeasons.ITEMS.registerSimpleBlockItem(LARGE_STAR);
    public static final DeferredItem<BlockItem> GIFT_PILE_ITEM = ColonySeasons.ITEMS.registerSimpleBlockItem(GIFT_PILE);
    public static final DeferredItem<BlockItem> FIREWORK_CRATE_ITEM = ColonySeasons.ITEMS.registerSimpleBlockItem(FIREWORK_CRATE);
    public static final DeferredItem<BlockItem> FESTIVAL_MARKER_ITEM = ColonySeasons.ITEMS.registerSimpleBlockItem(FESTIVAL_MARKER);

    /** Every decoration item, in creative-tab order (the same order as {@link #ALL}). */
    public static final List<DeferredItem<BlockItem>> ITEMS = List.of(WREATH_ITEM, GARLAND_ITEM, LANTERN_STRING_ITEM,
            FAIRY_LIGHTS_ITEM, ORNAMENT_LEAVES_ITEM, TINSEL_LEAVES_ITEM, TREE_STAR_ITEM, LARGE_STAR_ITEM, GIFT_PILE_ITEM,
            FIREWORK_CRATE_ITEM, FESTIVAL_MARKER_ITEM);

    // ---- block entities: each type lists exactly its own block
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<GiftPileBlockEntity>> GIFT_PILE_ENTITY =
            ColonySeasons.BLOCK_ENTITIES.register("gift_pile",
                    () -> BlockEntityType.Builder.of(GiftPileBlockEntity::new, GIFT_PILE.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FestivalMarkerBlockEntity>> FESTIVAL_MARKER_ENTITY =
            ColonySeasons.BLOCK_ENTITIES.register("festival_marker",
                    () -> BlockEntityType.Builder.of(FestivalMarkerBlockEntity::new, FESTIVAL_MARKER.get()).build(null));

    /** The mod's own creative tab, "Colony Seasons" (id colony_seasons:main), listing {@link #ITEMS}; later chunks add theirs. */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.colony_seasons"))
                    .icon(() -> new ItemStack(WREATH_ITEM.get()))
                    .displayItems((parameters, output) -> ITEMS.forEach(item -> output.accept(item.get())))
                    .build());

    private SeasonsBlocks() {
    }

    /** Called once from the mod constructor: loads the registrations above and hooks the tab register to the mod bus. */
    public static void register(final IEventBus modEventBus) {
        TABS.register(modEventBus);
    }

    private static BlockBehaviour.Properties wall(final SoundType sound, final MapColor color) {
        return BlockBehaviour.Properties.of().mapColor(color).noCollission().instabreak().noOcclusion()
                .sound(sound).pushReaction(PushReaction.DESTROY);
    }

    private static BlockBehaviour.Properties leaves() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.PLANT).strength(0.2F).sound(SoundType.GRASS).noOcclusion()
                .isValidSpawn((state, level, pos, type) -> false).isSuffocating((state, level, pos) -> false)
                .isViewBlocking((state, level, pos) -> false).isRedstoneConductor((state, level, pos) -> false);
    }

    private static BlockBehaviour.Properties star() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.GOLD).strength(0.3F).sound(SoundType.METAL)
                .noCollission().noOcclusion().pushReaction(PushReaction.DESTROY);
    }
}
