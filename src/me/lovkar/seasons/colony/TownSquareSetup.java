package me.lovkar.seasons.colony;

import com.minecolonies.api.colony.buildings.registry.BuildingEntry;
import com.minecolonies.api.items.ItemBlockHut;
import com.minecolonies.core.colony.buildings.views.EmptyView;
import me.lovkar.seasons.ColonySeasons;
import me.lovkar.seasons.block.BlockHutTownSquare;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * Everything the Town Square registers (0.2.0, chunk Q1, docs/town-square-design.md 4.1): the hut block (the notice
 * board) and its item, and the building {@code colony_seasons:townsquare}. The block entity is the mod's one hut type
 * ({@link DecoratorSetup#BUILDING_BE}, which lists this hut too). {@link ColonySeasons} calls {@link #init(IEventBus)}
 * in its constructor, so this class (and with it the register calls below) is loaded before the registry events.
 *
 * <p>The building has no worker module: the Decorator stays employed at his Workshop and is assigned here through
 * {@link SquareModules#DECORATOR}, the way the Courier serves the Warehouse.</p>
 */
public final class TownSquareSetup {

    public static final String HUT_NAME = "blockhuttownsquare";
    public static final ResourceLocation BUILDING_ID = ResourceLocation.fromNamespaceAndPath(ColonySeasons.MODID, "townsquare");

    public static final DeferredBlock<BlockHutTownSquare> HUT = ColonySeasons.BLOCKS.register(HUT_NAME, BlockHutTownSquare::new);
    public static final DeferredItem<Item> HUT_ITEM =
            ColonySeasons.ITEMS.register(HUT_NAME, () -> new ItemBlockHut(HUT.get(), new Item.Properties()));

    public static final DeferredHolder<BuildingEntry, BuildingEntry> BUILDING = ColonySeasons.BUILDINGS.register(BUILDING_ID.getPath(),
            () -> new BuildingEntry.Builder()
                    .setBuildingBlock(HUT.get())
                    .setBuildingProducer(BuildingTownSquare::new)
                    .setBuildingViewProducer(() -> EmptyView::new)
                    .setRegistryName(BUILDING_ID)
                    .addBuildingModuleProducer(SquareModules.DECORATOR)
                    .createBuildingEntry());

    private TownSquareSetup() {
    }

    /** Hooks the square's listeners up; the registry entries above exist as soon as this class is loaded. */
    public static void init(final IEventBus modBus) {
        modBus.addListener(TownSquareSetup::addToCreativeTab);
    }

    /** MineColonies' hut tab lists the square's hut next to the Workshop's, as every hut is listed there. */
    private static void addToCreativeTab(final BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(DecoratorSetup.HUTS_TAB)) {
            event.accept(HUT_ITEM.get());
        }
    }
}
