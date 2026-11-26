package me.lovkar.seasons.csclient;

import io.netty.buffer.Unpooled;
import me.lovkar.seasons.ColonySeasons;
import me.lovkar.seasons.SeasonsClientConfig;
import me.lovkar.seasons.calendar.Season;
import me.lovkar.seasons.colony.DecoratorInfoModule;
import me.lovkar.seasons.colony.DecoratorInfoModuleView;
import me.lovkar.seasons.colony.SquareAssignmentModule;
import me.lovkar.seasons.colony.SquareAssignmentModuleView;
import me.lovkar.seasons.colony.SquareInfoModule;
import me.lovkar.seasons.colony.SquareInfoModuleView;
import me.lovkar.seasons.colony.SquareStatus;
import me.lovkar.seasons.network.SeasonStatePayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.config.ModConfigs;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Headless check of the client half of Colony Seasons on the dedicated test server (chunk S6): the server boots
 * with the clothes code in the jar, the client config {@code colony_seasons-client.toml} is registered as a CLIENT
 * config but, as on every dedicated server, never loaded, so its getters answer the hand-written defaults of design
 * section 9; and the season packet decodes a season id outside 0 to 3 to a real season instead of null.
 * Touches no class of the {@code client} package. Prints "[csclient] CHECK ..." lines and
 * "[csclient] RESULT csclient: PASS|FAIL", then halts.
 */
@Mod("colonyseasonsclient")
public class CsClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("csclient");
    private int ticks = 0;
    private boolean failed = false;

    public CsClient() {
        NeoForge.EVENT_BUS.addListener(this::onTick);
    }

    private void check(final String what, final boolean ok, final String detail) {
        LOGGER.info("[csclient] CHECK {}: {} {}", what, ok ? "OK" : "FAIL", detail);
        failed |= !ok;
    }

    private void onTick(final ServerTickEvent.Post event) {
        if (++ticks != 100) {
            return;
        }
        final MinecraftServer server = event.getServer();
        try {
            check("dedicated server", FMLEnvironment.dist.isDedicatedServer(), String.valueOf(FMLEnvironment.dist));

            final List<String> clientFiles = ModConfigs.getConfigFileNames(ColonySeasons.MODID, ModConfig.Type.CLIENT);
            check("client config registered", clientFiles.equals(List.of("colony_seasons-client.toml")), String.valueOf(clientFiles));
            check("client config file name constant", "colony_seasons-client.toml".equals(SeasonsClientConfig.FILE), SeasonsClientConfig.FILE);
            check("client config not loaded on a server", !SeasonsClientConfig.SPEC.isLoaded(), "a CLIENT config is read by the physical client only");
            // design section 9, by hand
            check("showClothes default", SeasonsClientConfig.showClothes(), String.valueOf(SeasonsClientConfig.showClothes()));
            // S10i: showParticles is gone; csconfig loads the client file itself and checks its keys
            check("default constant", SeasonsClientConfig.DEFAULT_SHOW_CLOTHES, "true");

            final SeasonStatePayload sent = new SeasonStatePayload(Season.WINTER, 40, 48, List.of("winter_festival"));
            final SeasonStatePayload back = decode(encoded(server, sent));
            check("packet round trip", sent.equals(back), String.valueOf(back));
            for (final int id : new int[]{4, 9, 1000, -1}) {
                final RegistryFriendlyByteBuf raw = buffer(server);
                raw.writeVarInt(id);
                raw.writeVarInt(5);
                raw.writeVarInt(48);
                raw.writeVarInt(0);
                final SeasonStatePayload odd = decode(raw);
                final Season want = id < 0 ? Season.SPRING : Season.WINTER;
                check("season id " + id + " decodes to a real season", odd.season() == want, "got " + odd.season() + ", want " + want);
            }
            windowPages(server);
        } catch (final Throwable t) {
            check("probe ran to the end", false, t.toString());
        }
        LOGGER.info("[csclient] RESULT csclient: {}", failed ? "FAIL" : "PASS");
        server.halt(false);
    }

    /** The window classes of the mod's client package: a dedicated server reads the pages' views, and never loads these. */
    private static final List<String> WINDOW_CLASSES = List.of("me.lovkar.seasons.client.TownSquareInfoWindow",
            "me.lovkar.seasons.client.SquareAssignmentWindow", "me.lovkar.seasons.client.DecoratorInfoWindow",
            "me.lovkar.seasons.client.DecoratorStorageWindow");

    /**
     * Q5: the Town Square's three pages and the Workshop's Calendar page, on a dedicated server: each module writes its
     * (empty) page, its view reads it to the last byte, and no window class of the client package has been loaded by
     * any of it (the views name their windows only inside getWindow, typed as BlockUI's base class).
     */
    private void windowPages(final MinecraftServer server) throws Throwable {
        final RegistryFriendlyByteBuf info = buffer(server);
        new SquareInfoModule().serializeToView(info);
        final SquareInfoModuleView infoView = new SquareInfoModuleView();
        infoView.deserialize(info);
        check("Q5: the square's info page reads back its empty packet to the last byte", info.readableBytes() == 0
                && infoView.info().equals(SquareInfoModule.empty()), String.valueOf(infoView.info()));

        final RegistryFriendlyByteBuf assignment = buffer(server);
        new SquareAssignmentModule().serializeToView(assignment);
        final SquareAssignmentModuleView assignmentView = new SquareAssignmentModuleView();
        assignmentView.deserialize(assignment);
        check("Q5: the square's Decorator page reads back its empty packet: no Decorator, no main square", assignment.readableBytes() == 0
                && assignmentView.getAssignedCitizens().isEmpty() && assignmentView.main().equals(SquareStatus.Main.NONE),
                assignmentView.main() + ", " + assignment.readableBytes() + " bytes left");

        final RegistryFriendlyByteBuf calendar = buffer(server);
        new DecoratorInfoModule().serializeToView(calendar);
        final DecoratorInfoModuleView calendarView = new DecoratorInfoModuleView();
        calendarView.deserialize(calendar);
        check("Q5: the Workshop's Calendar page reads back its empty packet: the line says no square", calendar.readableBytes() == 0
                && calendarView.info().squareKind() == DecoratorInfoModule.SQUARE_NONE, calendar.readableBytes() + " bytes left");

        final ClassLoader loader = SquareInfoModuleView.class.getClassLoader();
        final MethodHandle find = findLoadedClass();
        if (find == null) {
            check("Q5: the JVM lets the probe ask which classes are loaded", false, "no trusted lookup");
            return;
        }
        final List<String> loaded = new ArrayList<>();
        for (final String name : WINDOW_CLASSES) {
            if (find.invoke(loader, name) != null) {
                loaded.add(name);
            }
        }
        check("Q5: no window class of the client package was loaded on the dedicated server", loaded.isEmpty(), String.valueOf(loaded));
        check("Q5: the check can see a loaded class (this view is one)", find.invoke(loader, SquareInfoModuleView.class.getName()) != null, "");
    }

    /**
     * ClassLoader.findLoadedClass(String), reached through the JDK's trusted lookup (read by sun.misc.Unsafe: the JVM
     * opens java.lang to nobody else, and the rig's start line is not ours to change), or {@code null}. Test code only.
     */
    private static MethodHandle findLoadedClass() {
        try {
            final Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            final sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            final Field trusted = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
            final MethodHandles.Lookup lookup = (MethodHandles.Lookup) unsafe.getObject(unsafe.staticFieldBase(trusted), unsafe.staticFieldOffset(trusted));
            return lookup.findVirtual(ClassLoader.class, "findLoadedClass", MethodType.methodType(Class.class, String.class));
        } catch (final Throwable unreachable) {
            LOGGER.info("[csclient] NOTE no trusted lookup: {}", unreachable.getClass().getSimpleName());
            return null;
        }
    }

    private static RegistryFriendlyByteBuf buffer(final MinecraftServer server) {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
    }

    private static RegistryFriendlyByteBuf encoded(final MinecraftServer server, final SeasonStatePayload payload) {
        final RegistryFriendlyByteBuf buf = buffer(server);
        SeasonStatePayload.STREAM_CODEC.encode(buf, payload);
        return buf;
    }

    private static SeasonStatePayload decode(final RegistryFriendlyByteBuf buf) {
        return SeasonStatePayload.STREAM_CODEC.decode(buf);
    }
}
