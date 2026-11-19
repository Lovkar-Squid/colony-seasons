package me.lovkar.seasons.csboot;

import me.lovkar.seasons.ColonySeasons;
import me.lovkar.seasons.SeasonsConfig;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Headless boot check on the dedicated test server: Colony Seasons loaded next to MineColonies, every
 * DeferredRegister of {@link ColonySeasons} reached the mod bus and saw its registry event, both event
 * buses deliver to the mod, the server config spec is registered (its file exists and it is loaded) and
 * the server ticks. Prints "[csboot] CHECK ..." lines and "[csboot] RESULT csboot: PASS|FAIL", then halts.
 */
@Mod("colonyseasonsboot")
public class CsBoot {
    private static final Logger LOGGER = LoggerFactory.getLogger("csboot");
    private int ticks = 0;
    private boolean failed = false;

    public CsBoot() {
        NeoForge.EVENT_BUS.addListener(this::onTick);
    }

    private void check(final String what, final boolean ok, final String detail) {
        LOGGER.info("[csboot] CHECK {}: {} {}", what, ok ? "OK" : "FAIL", detail);
        failed |= !ok;
    }

    private void onTick(final ServerTickEvent.Post event) {
        if (++ticks != 100) {
            return;
        }
        final MinecraftServer server = event.getServer();
        try {
            check("mods loaded", ModList.get().isLoaded("colony_seasons") && ModList.get().isLoaded("minecolonies"),
                    "colony_seasons and minecolonies");

            final List<DeferredRegister<?>> registers = ColonySeasons.registers();
            check("registers declared", registers.size() == 6, registers.size() + " of BLOCKS, ITEMS, BLOCK_ENTITIES, SOUNDS, JOBS, BUILDINGS");
            for (final DeferredRegister<?> register : registers) {
                final String name = String.valueOf(register.getRegistryName());
                check("register " + name + " on the mod bus", flag(register, "registeredEventBus"), "register(modEventBus) was called");
                check("register " + name + " saw its registry event", flag(register, "seenRegisterEvent"), "RegisterEvent reached it");
            }

            check("mod bus listener", ColonySeasons.commonSetupSeen(), "FMLCommonSetupEvent delivered");
            check("game bus listener", ColonySeasons.serverStartedSeen(), "ServerStartedEvent delivered");

            check("server config loaded", SeasonsConfig.SPEC.isLoaded(), "colony_seasons-server.toml is the live SERVER config");
            final Path file = Path.of("config", ColonySeasons.SERVER_CONFIG_FILE);
            check("server config file", Files.exists(file), file.toString());
            check("server ticks", true, ticks + " ticks");
        } catch (final Throwable t) {
            check("probe ran to the end", false, t.toString());
        }
        LOGGER.info("[csboot] RESULT csboot: {}", failed ? "FAIL" : "PASS");
        server.halt(false);
    }

    /** A private boolean of DeferredRegister: the API has no getter for "was it hooked to a bus". */
    private static boolean flag(final DeferredRegister<?> register, final String field) throws ReflectiveOperationException {
        final Field f = DeferredRegister.class.getDeclaredField(field);
        f.setAccessible(true);
        return f.getBoolean(register);
    }
}
