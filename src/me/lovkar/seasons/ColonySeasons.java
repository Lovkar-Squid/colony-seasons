package me.lovkar.seasons;

import com.minecolonies.api.colony.buildings.registry.BuildingEntry;
import com.minecolonies.api.colony.jobs.registry.JobEntry;
import com.minecolonies.apiimp.CommonMinecoloniesAPIImpl;
import me.lovkar.seasons.block.SeasonsBlocks;
import me.lovkar.seasons.calendar.CalendarEvents;
import me.lovkar.seasons.command.SeasonsCommands;
import me.lovkar.seasons.network.SeasonsNetwork;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforgespi.language.IModInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Colony Seasons: seasons and festivals for MineColonies.
 *
 * <p>The skeleton (chunk S0): the registries every later chunk fills, the two event buses and the server
 * config. Chunk S1 adds the calendar: its poll and login events, the season packet and the commands. No
 * block, item, job or building is registered yet.</p>
 *
 * <p>Registries are declared here and registered on the mod bus even while they are empty, so a later
 * chunk only has to {@code register(...)} an entry and never has to remember to hook a register up.
 * {@link #registers()} lists them all; the boot probe checks that each one reached the bus.</p>
 */
@Mod(ColonySeasons.MODID)
public class ColonySeasons {
    public static final String MODID = "colony_seasons";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    /** The server config file, in the world's {@code serverconfig} folder (and {@code config/} on a dedicated server). */
    public static final String SERVER_CONFIG_FILE = MODID + "-server.toml";

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, MODID);
    /** MineColonies' own registries: the Decorator's job and hut are registered here by a later chunk. */
    public static final DeferredRegister<JobEntry> JOBS = DeferredRegister.create(CommonMinecoloniesAPIImpl.JOBS, MODID);
    public static final DeferredRegister<BuildingEntry> BUILDINGS =
            DeferredRegister.create(CommonMinecoloniesAPIImpl.BUILDINGS, MODID);

    /** Set by the listeners below, so the boot probe can prove both buses are wired. */
    private static volatile boolean commonSetupSeen;
    private static volatile boolean serverStartedSeen;

    /**
     * FML hands the constructor our mod container as well as the mod bus; the container is where a
     * config is registered in NeoForge 21.1.
     */
    public ColonySeasons(final IEventBus modEventBus, final ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, SeasonsConfig.SPEC, SERVER_CONFIG_FILE);
        // S6: the client config (loaded on the physical client only) and, there only, the clothes layer and models
        container.registerConfig(ModConfig.Type.CLIENT, SeasonsClientConfig.SPEC, SeasonsClientConfig.FILE);
        if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
            me.lovkar.seasons.client.SeasonsClient.init(modEventBus);
        }
        for (final DeferredRegister<?> register : registers()) {
            register.register(modEventBus);
        }
        // mod bus: lifecycle and registration events
        modEventBus.addListener(ColonySeasons::onCommonSetup);
        modEventBus.addListener(SeasonsNetwork::registerPayloads);
        SeasonsBlocks.register(modEventBus);
        // game bus: everything that happens in a running world
        NeoForge.EVENT_BUS.addListener(ColonySeasons::onServerStarted);
        CalendarEvents.register();
        SeasonsCommands.register();

        LOGGER.info("Colony Seasons {} loaded", version(container));
    }

    /**
     * The version from the mod's own metadata (never a number typed in here). getVersion() returns a
     * Maven type that the build does not compile against, so it is asked for by name.
     */
    private static String version(final ModContainer container) {
        try {
            return String.valueOf(IModInfo.class.getMethod("getVersion").invoke(container.getModInfo()));
        } catch (final ReflectiveOperationException unreadable) {
            return "?";
        }
    }

    /** Every DeferredRegister of this mod, in registration order. */
    public static List<DeferredRegister<?>> registers() {
        return List.of(BLOCKS, ITEMS, BLOCK_ENTITIES, SOUNDS, JOBS, BUILDINGS);
    }

    private static void onCommonSetup(final FMLCommonSetupEvent event) {
        commonSetupSeen = true;
    }

    private static void onServerStarted(final ServerStartedEvent event) {
        serverStartedSeen = true;
    }

    /** True once the mod bus delivered FMLCommonSetupEvent to us. */
    public static boolean commonSetupSeen() {
        return commonSetupSeen;
    }

    /** True once the game bus delivered ServerStartedEvent to us. */
    public static boolean serverStartedSeen() {
        return serverStartedSeen;
    }
}
