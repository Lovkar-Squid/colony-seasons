package me.lovkar.seasons.colony;

import com.minecolonies.api.colony.buildings.registry.BuildingEntry;
import com.minecolonies.api.colony.jobs.registry.JobEntry;
import com.minecolonies.api.items.ItemBlockHut;
import com.minecolonies.api.sounds.EventType;
import com.minecolonies.api.sounds.ModSoundEvents;
import com.minecolonies.api.util.Tuple;
import com.minecolonies.core.colony.buildings.views.EmptyView;
import com.minecolonies.core.colony.jobs.views.DefaultJobView;
import me.lovkar.seasons.ColonySeasons;
import me.lovkar.seasons.block.BlockHutDecorator;
import me.lovkar.seasons.block.SeasonsTileEntity;
import me.lovkar.seasons.client.DecoratorClient;
import me.lovkar.seasons.decor.Kits;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;

import java.util.List;
import java.util.Map;

/**
 * Everything the Decorator registers (chunk S3): the hut block and its item, the block-entity type, the job, the
 * building, and the listeners that go with them. {@link ColonySeasons} calls {@link #init(IEventBus)} once; the
 * entries go onto the registers that class owns.
 */
public final class DecoratorSetup {

    public static final String HUT_NAME = "blockhutdecorator";
    public static final ResourceLocation JOB_ID = id("decorator");
    public static final ResourceLocation BUILDING_ID = id("decorator");
    /**
     * The Decorator's citizen model. Its path is the base name of his textures, which MineColonies looks up under
     * its own namespace: {@code minecolonies:textures/entity/citizen/<style>/decorator<male|female>1<_a|_b|_d|_w>.png}
     * (tools/check_skins.py keeps the two in step).
     */
    public static final ResourceLocation MODEL_ID = id("decorator");
    /** Whose voice lines the Decorator borrows, the first one MineColonies has. */
    public static final List<String> VOICE_SOURCES = List.of("florist", "builder", "unemployed");

    /** MineColonies' creative tab that lists every hut block. */
    private static final ResourceKey<CreativeModeTab> HUTS_TAB =
            ResourceKey.create(Registries.CREATIVE_MODE_TAB, ResourceLocation.fromNamespaceAndPath("minecolonies", "mchuts"));

    public static final DeferredBlock<BlockHutDecorator> HUT = ColonySeasons.BLOCKS.register(HUT_NAME, BlockHutDecorator::new);
    public static final DeferredItem<Item> HUT_ITEM =
            ColonySeasons.ITEMS.register(HUT_NAME, () -> new ItemBlockHut(HUT.get(), new Item.Properties()));

    /** The block-entity type of every hut of this mod; blueprints of our huts carry {@code colony_seasons:colonybuilding}. */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SeasonsTileEntity>> BUILDING_BE =
            ColonySeasons.BLOCK_ENTITIES.register("colonybuilding",
                    () -> BlockEntityType.Builder.of(SeasonsTileEntity::new, HUT.get()).build(null));

    public static final DeferredHolder<JobEntry, JobEntry> JOB = ColonySeasons.JOBS.register(JOB_ID.getPath(),
            () -> new JobEntry.Builder()
                    .setJobProducer(JobDecorator::new)
                    .setJobViewProducer(() -> DefaultJobView::new)
                    .setRegistryName(JOB_ID)
                    .createJobEntry());

    public static final DeferredHolder<BuildingEntry, BuildingEntry> BUILDING = ColonySeasons.BUILDINGS.register(BUILDING_ID.getPath(),
            () -> new BuildingEntry.Builder()
                    .setBuildingBlock(HUT.get())
                    .setBuildingProducer(BuildingDecorator::new)
                    .setBuildingViewProducer(() -> EmptyView::new)
                    .setRegistryName(BUILDING_ID)
                    .addBuildingModuleProducer(DecoratorModules.WORK)
                    .addBuildingModuleProducer(DecoratorModules.SETTINGS)
                    .addBuildingModuleProducer(DecoratorModules.STOCK)
                    .createBuildingEntry());

    /** The map the Decorator speaks with, by the job it was borrowed from; empty until common setup ran. */
    private static volatile String voiceSource = "";

    private DecoratorSetup() {
    }

    private static ResourceLocation id(final String path) {
        return ResourceLocation.fromNamespaceAndPath(ColonySeasons.MODID, path);
    }

    /** Hooks the Decorator's listeners up; the registry entries above exist as soon as this class is loaded. */
    public static void init(final IEventBus modBus) {
        modBus.addListener(EventPriority.HIGH, DecoratorSetup::registerCapabilities);
        modBus.addListener(DecoratorSetup::addToCreativeTab);
        modBus.addListener(DecoratorSetup::onCommonSetup);
        NeoForge.EVENT_BUS.addListener(Kits::onAddReloadListeners);
        NeoForge.EVENT_BUS.addListener(Kits::onTagsUpdated);
        if (FMLEnvironment.dist.isClient()) {
            DecoratorClient.init(modBus);
        }
    }

    /** MineColonies reads a hut's inventory (racks included) through this capability. */
    private static void registerCapabilities(final RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, BUILDING_BE.get(), (be, side) -> be.getItemHandlerCap(side));
    }

    private static void addToCreativeTab(final BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(HUTS_TAB)) {
            event.accept(HUT_ITEM.get());
        }
    }

    private static void onCommonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(DecoratorSetup::lendVoiceLines);
    }

    /**
     * MineColonies looks citizen voice lines up by the job's path and fails in the server tick for a job it has no
     * entry for. The Decorator speaks with the Florist's lines (a craftsman of pretty things), else the Builder's,
     * else the unemployed citizen's. If none of them exists: one warning, no entry, no crash.
     */
    static void lendVoiceLines() {
        final Map<String, Map<EventType, List<Tuple<SoundEvent, SoundEvent>>>> voices = ModSoundEvents.CITIZEN_SOUND_EVENTS;
        for (final String source : VOICE_SOURCES) {
            final Map<EventType, List<Tuple<SoundEvent, SoundEvent>>> lines = voices.get(source);
            if (lines != null && !lines.isEmpty()) {
                voices.put(JOB_ID.getPath(), lines);
                voiceSource = source;
                ColonySeasons.LOGGER.info("The Decorator speaks with the {} voice lines", source);
                return;
            }
        }
        ColonySeasons.LOGGER.warn("No citizen voice lines to lend to the Decorator (looked for {})", VOICE_SOURCES);
    }

    /** The job whose voice lines the Decorator was given, or "" (before common setup, or when none was found). */
    public static String voiceSource() {
        return voiceSource;
    }
}
