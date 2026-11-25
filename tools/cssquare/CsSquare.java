package me.lovkar.seasons.cssquare;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ldtteam.common.network.PlayMessageType;
import com.ldtteam.structurize.api.RotationMirror;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.storage.StructurePacks;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.HiringMode;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.modules.IAssignsCitizen;
import com.minecolonies.api.colony.buildings.modules.IAssignsJob;
import com.minecolonies.api.colony.buildings.modules.IBuildingModule;
import com.minecolonies.api.colony.buildings.registry.BuildingEntry;
import com.minecolonies.api.colony.jobs.IJob;
import com.minecolonies.api.colony.workorders.WorkOrderType;
import com.minecolonies.api.items.ItemBlockHut;
import com.minecolonies.api.tileentities.AbstractTileEntityColonyBuilding;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import com.minecolonies.core.colony.buildings.modules.settings.BoolSetting;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingTownHall;
import com.minecolonies.core.colony.workorders.WorkOrderBuilding;
import com.minecolonies.core.network.messages.server.AbstractColonyServerMessage;
import com.minecolonies.core.network.messages.server.colony.building.HireFireMessage;
import com.minecolonies.core.network.messages.server.colony.building.worker.BuildingHiringModeMessage;
import com.minecolonies.core.placementhandlers.main.SurvivalHandler;
import io.netty.buffer.Unpooled;
import me.lovkar.seasons.block.SeasonsTileEntity;
import me.lovkar.seasons.calendar.CalendarManager;
import me.lovkar.seasons.calendar.DateSource;
import me.lovkar.seasons.colony.BuildingDecorator;
import me.lovkar.seasons.colony.BuildingTownSquare;
import me.lovkar.seasons.colony.DecoratorModules;
import me.lovkar.seasons.colony.DecoratorSetup;
import me.lovkar.seasons.colony.JobDecorator;
import me.lovkar.seasons.colony.SquareAssignmentModule;
import me.lovkar.seasons.colony.SquareAssignmentModuleView;
import me.lovkar.seasons.colony.SquareModules;
import me.lovkar.seasons.colony.TownSquareSetup;
import me.lovkar.seasons.decor.DecorPlacer;
import me.lovkar.seasons.decor.DecorWork;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Tuple;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Headless check of the Town Square building (0.2.0, chunk Q1, docs/town-square-design.md 2.2, 4.1, 4.3 and the points
 * of section 8 that Q1 owns). The hiring parts are modelled on cshire: every hire and fire is MineColonies' own
 * {@link HireFireMessage} (and every hiring mode its {@link BuildingHiringModeMessage}), built from bytes and run
 * through the public server handler with the colony's owner (a fake player) standing within 15 blocks of the building;
 * the rig's colony never becomes active, so the probe runs MineColonies' building colony tick itself.
 *
 * <p>The colony: a town hall, two Decorator's Workshops pasted with the command a player types, a lumberjack's hut; the
 * squares S1 and S2 placed through MineColonies' survival placement handler (the build tool's server side) with the
 * hut item and built by a finished BUILD work order; eight squares pasted for the tags, four turned and four mirrored.</p>
 *
 * <p>Modes: none (everything below), {@code restart-save} then {@code restart-load} (tools/restart.sh): a square with its
 * Decorator in MANUAL mode and a second square whose Decorator was removed without a colony tick, read back after a
 * restart before any colony tick. Prints "[cssquare] CHECK ...: OK|FAIL" lines and "[cssquare] RESULT cssquare:
 * PASS|FAIL", then halts. The two words the rig greps for stay out of the details.</p>
 */
@Mod("colonyseasonssquare")
public class CsSquare {
    private static final Logger LOGGER = LoggerFactory.getLogger("cssquare");
    private static final int SETUP_TICK = 60;
    private static final long WATCHDOG_SECONDS = 900;
    private static final String PACK = "Colony Seasons";
    private static final String SQUARE_PATH = "square/townsquare1.blueprint";
    private static final String SQUARE_CMD = "square.townsquare1";
    /** From the town hall: the Workshops, the lumberjack's hut, the two survival squares, the four tag corners. */
    private static final BlockPos W1_OFFSET = new BlockPos(14, 0, 0);
    private static final BlockPos W2_OFFSET = new BlockPos(-14, 0, 0);
    private static final BlockPos LUMBER_OFFSET = new BlockPos(0, 0, 14);
    private static final BlockPos S1_OFFSET = new BlockPos(0, 0, -40);
    private static final BlockPos S2_OFFSET = new BlockPos(0, 0, 40);
    private static final List<BlockPos> CORNERS = List.of(new BlockPos(40, 0, 40), new BlockPos(-40, 0, 40),
            new BlockPos(40, 0, -40), new BlockPos(-40, 0, -40));
    private static final List<RotationMirror> TURNED = List.of(RotationMirror.NONE, RotationMirror.R90, RotationMirror.R180, RotationMirror.R270);
    private static final List<RotationMirror> MIRRORED = List.of(RotationMirror.MIR_NONE, RotationMirror.MIR_R90, RotationMirror.MIR_R180,
            RotationMirror.MIR_R270);
    /** MineColonies' Colony.worldTickSlow runs on the ACTIVE state's TickingTransition at this rate (checked in the jar). */
    private static final int COLONY_TICK = 500;
    private static final String IDS_FILE = "cssquare-ids.properties";
    private static final int FORCE = 5;

    private int ticks;
    private boolean failed;
    private boolean finished;
    private String mode = "";
    private long setupNanos;

    private MinecraftServer server;
    private ServerLevel level;
    private ServerPlayer owner;
    private IColony colony;
    private BlockPos center;
    private BuildingDecorator w1;
    private BuildingDecorator w2;
    private IBuilding lumber;
    private BuildingTownSquare s1;
    private BuildingTownSquare s2;
    private ICitizenData d1;
    private ICitizenData d2;
    private ICitizenData jobless;
    private ICitizenData lumberjack;
    private JsonObject tagFile;
    private Set<Long> claimedBefore;
    private final List<BuildingTownSquare> tagSquares = new ArrayList<>();

    private final List<Step> steps = new ArrayList<>();
    private int stepIndex;

    public CsSquare() {
        NeoForge.EVENT_BUS.addListener(this::onTick);
    }

    // ================================================================ logging and steps (as in cshire)

    private void check(final String what, final boolean ok, final String detail) {
        LOGGER.info("[cssquare] CHECK {}: {} {}", what, ok ? "OK" : "FAIL", detail);
        failed |= !ok;
    }

    private void checkEq(final String what, final Object actual, final Object expected) {
        check(what, Objects.equals(actual, expected), "got " + actual + (Objects.equals(actual, expected) ? "" : ", wanted " + expected));
    }

    private static void note(final String text, final Object... args) {
        LOGGER.info("[cssquare] " + text, args);
    }

    private void onTick(final ServerTickEvent.Post event) {
        if (finished) {
            return;
        }
        ticks++;
        server = event.getServer();
        try {
            if (ticks == SETUP_TICK) {
                mode = readMode();
                note("mode '{}'", mode);
                setupNanos = System.nanoTime();
                level = server.overworld();
                plan();
            }
            if (ticks >= SETUP_TICK) {
                if ((System.nanoTime() - setupNanos) / 1_000_000_000L > WATCHDOG_SECONDS) {
                    check("the run finished within " + WATCHDOG_SECONDS + " s of real time", false,
                            "stopped in step " + (stepIndex < steps.size() ? steps.get(stepIndex).name : "-"));
                    finish();
                    return;
                }
                runSteps();
            }
        } catch (final Throwable t) {
            check("the probe ran without a throwable", false, t.toString().replace("Exception", "Exc"));
            LOGGER.info("[cssquare] trace: {}", Arrays.toString(Arrays.copyOf(t.getStackTrace(), Math.min(10, t.getStackTrace().length))));
            finish();
        }
    }

    private void runSteps() {
        if (stepIndex >= steps.size()) {
            finish();
            return;
        }
        final Step step = steps.get(stepIndex);
        if (!step.started) {
            step.started = true;
            step.start();
        }
        step.t++;
        final boolean done = finished || step.tick();
        if (done || step.t >= step.maxTicks) {
            note("step '{}' ran {} ticks", step.name, step.t);
            step.end(done);
            stepIndex++;
        }
    }

    private void finish() {
        if (finished) {
            return;
        }
        finished = true;
        LOGGER.info("[cssquare] RESULT cssquare: {}", failed ? "FAIL" : "PASS");
        if (server != null) {
            server.halt(false);
        }
    }

    private static String readMode() throws Exception {
        final Path file = Path.of("probe-mode.txt");
        return Files.exists(file) ? Files.readString(file, StandardCharsets.US_ASCII).trim() : "";
    }

    private abstract static class Step {
        final String name;
        final int maxTicks;
        boolean started;
        int t;

        Step(final String name, final int maxTicks) {
            this.name = name;
            this.maxTicks = maxTicks;
        }

        void start() {
        }

        boolean tick() {
            return false;
        }

        void end(final boolean done) {
        }
    }

    private void now(final String name, final Runnable body) {
        steps.add(new Step(name, 1) {
            @Override
            void start() {
                body.run();
            }
        });
    }

    private void plan() {
        switch (mode) {
            case "" -> planDefault();
            case "restart-save" -> planRestartSave();
            case "restart-load" -> planRestartLoad();
            default -> {
                check("known mode", false, "'" + mode + "'");
                finish();
            }
        }
    }

    // ================================================================ the colony

    private void quietWorld() {
        server.setDifficulty(Difficulty.PEACEFUL, true);
        server.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        server.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        level.setWeatherParameters(24000 * 20, 0, false, false);
    }

    private void buildColony() {
        final int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, 2, 2);
        center = new BlockPos(2, y, 2);
        for (int cx = -FORCE; cx <= FORCE; cx++) {
            for (int cz = -FORCE; cz <= FORCE; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }
        owner = FakePlayerFactory.getMinecraft(level);
        stand(center);
        colony = IColonyManager.getInstance().createColony(level, center, owner, "Seasons Square", "Medieval Oak");
        note("colony {} created at {}", colony == null ? "none" : colony.getID(), center);
        final Block townHall = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.fromNamespaceAndPath("minecolonies", "blockhuttownhall")).orElse(null);
        if (townHall != null) {
            level.setBlock(center, townHall.defaultBlockState(), 3);
            if (level.getBlockEntity(center) instanceof AbstractTileEntityColonyBuilding te) {
                note("town hall registered: {}", colony.getServerBuildingManager().addNewBuilding(te, level) != null);
            }
        }
        final BoolSetting autoHire = colony.getSettings().getSetting(BuildingTownHall.AUTO_HIRING_MODE);
        if (autoHire.getValue()) {
            autoHire.trigger();
        }
    }

    private void stand(final BlockPos at) {
        owner.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
    }

    private Replies command(final String line) {
        final Replies out = new Replies();
        server.getCommands().performPrefixedCommand(owner.createCommandSourceStack().withSource(out).withPermission(4), line);
        return out;
    }

    private static final class Replies implements CommandSource {
        final List<String> said = new ArrayList<>();

        @Override
        public void sendSystemMessage(final Component component) {
            said.add(component.getContents() instanceof TranslatableContents t ? t.getKey() : component.getString());
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }
    }

    /**
     * A blueprint of our pack pasted as a player pastes it (creative, the paste command), then waited for until its
     * building is there with its hut data; squares are put on MANUAL hiring as soon as they appear.
     */
    private void paste(final String name, final BlockPos offset, final String blueprint, final RotationMirror rm, final Consumer<IBuilding> into) {
        steps.add(new Step(name, 800) {
            IBuilding found;
            BlockPos at;

            @Override
            void start() {
                at = center.offset(offset);
                owner.setGameMode(GameType.CREATIVE);
                stand(at.offset(0, 0, 3));
                final String line = "structurize paste " + at.getX() + " " + at.getY() + " " + at.getZ() + " Colony.Seasons " + blueprint + " " + rm.name() + " true";
                final Replies replies = command(line);
                check(name + ": the paste command is accepted", replies.said.stream().anyMatch(s -> s.contains("paste.success")), String.valueOf(replies.said));
            }

            @Override
            boolean tick() {
                if (found == null) {
                    found = colony.getServerBuildingManager().getBuilding(at);
                    if (found instanceof BuildingTownSquare square) {
                        square.assignment().setHiringMode(HiringMode.MANUAL);
                    }
                }
                final AbstractTileEntityColonyBuilding te = found == null ? null : found.getTileEntity();
                return t >= 100 && te != null && !te.getSchematicName().isEmpty();
            }

            @Override
            void end(final boolean done) {
                check(name + ": the building is there with its hut data", done && found != null,
                        found == null ? "none; at the hut position: " + level.getBlockState(at) : found.getClass().getSimpleName());
                if (found == null) {
                    finish();
                    return;
                }
                into.accept(found);
            }
        });
    }

    private void pasteWorkshops() {
        paste("the Workshop W1", W1_OFFSET, "decorator.workshop1", RotationMirror.NONE, b -> w1 = (BuildingDecorator) b);
        paste("the Workshop W2", W2_OFFSET, "decorator.workshop1", RotationMirror.NONE, b -> w2 = (BuildingDecorator) b);
        now("the Workshops on manual hiring, the lumberjack's hut", () -> {
            work(w1).setHiringMode(HiringMode.MANUAL);
            work(w2).setHiringMode(HiringMode.MANUAL);
            lumber = stockHut("minecolonies:blockhutlumberjack", center.offset(LUMBER_OFFSET));
        });
    }

    private IBuilding stockHut(final String hutId, final BlockPos at) {
        final Block block = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(hutId)).orElse(null);
        if (block == null) {
            check("hut block " + hutId + " exists", false, "");
            return null;
        }
        level.setBlock(at, block.defaultBlockState(), Block.UPDATE_ALL);
        final BlockEntity be = level.getBlockEntity(at);
        IBuilding building = colony.getServerBuildingManager().getBuilding(at);
        if (building == null && be instanceof AbstractTileEntityColonyBuilding te) {
            building = colony.getServerBuildingManager().addNewBuilding(te, level);
        }
        if (building == null) {
            check("building " + hutId + " registers", false, "at " + at);
            return null;
        }
        building.setBuildingLevel(1);
        building.markDirty();
        for (final IAssignsCitizen module : building.getModulesByType(IAssignsCitizen.class)) {
            module.setHiringMode(HiringMode.MANUAL);
        }
        return building;
    }

    private static WorkerBuildingModule work(final BuildingDecorator hut) {
        return hut.getModule(DecoratorModules.WORK);
    }

    private static IAssignsJob jobModule(final IBuilding building, final String jobPath) {
        if (building == null) {
            return null;
        }
        for (final IAssignsJob module : building.getModulesByType(IAssignsJob.class)) {
            if (module.getJobEntry().getKey().getPath().equals(jobPath)) {
                return module;
            }
        }
        return null;
    }

    private ICitizenData spawn(final BlockPos at) {
        final ICitizenData data = colony.getCitizenManager().createAndRegisterCivilianData();
        colony.getCitizenManager().spawnOrCreateCitizen(data, level, at);
        return data;
    }

    private void colonyTick() {
        colony.getServerBuildingManager().onColonyTick(colony);
    }

    private String label(final IBuilding b) {
        if (b == null) {
            return "none";
        }
        if (b == w1) {
            return "W1";
        }
        if (b == w2) {
            return "W2";
        }
        if (b == s1) {
            return "S1";
        }
        if (b == s2) {
            return "S2";
        }
        if (b == lumber) {
            return "the lumberjack's hut";
        }
        return b.getClass().getSimpleName() + "@" + b.getID().toShortString();
    }

    private static String jobName(final IJob<?> job) {
        return job == null ? "no job" : job.getJobRegistryEntry().getKey().getPath();
    }

    private int decoratorCount() {
        int n = 0;
        for (final ICitizenData c : colony.getCitizenManager().getCitizens()) {
            n += c.getJob() instanceof JobDecorator ? 1 : 0;
        }
        return n;
    }

    // ================================================================ the messages

    /** A MineColonies building message as the server receives it: read from bytes, written back to prove the layout, run. */
    private boolean message(final Class<? extends AbstractColonyServerMessage> type, final PlayMessageType<?> messageType, final IBuilding building,
                            final Consumer<RegistryFriendlyByteBuf> body, final String what) {
        try {
            final RegistryFriendlyByteBuf in = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
            in.writeUtf(level.dimension().location().toString());
            in.writeInt(colony.getID());
            in.writeBlockPos(building.getID());
            body.accept(in);
            final byte[] sent = new byte[in.readableBytes()];
            in.getBytes(in.readerIndex(), sent);
            final Constructor<? extends AbstractColonyServerMessage> reader = type.getDeclaredConstructor(RegistryFriendlyByteBuf.class, PlayMessageType.class);
            reader.setAccessible(true);
            final AbstractColonyServerMessage msg = reader.newInstance(in, messageType);
            final Method writer = type.getDeclaredMethod("toBytes", RegistryFriendlyByteBuf.class);
            writer.setAccessible(true);
            final RegistryFriendlyByteBuf out = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
            writer.invoke(msg, out);
            final byte[] back = new byte[out.readableBytes()];
            out.getBytes(out.readerIndex(), back);
            if (!Arrays.equals(sent, back)) {
                check("the message " + what + " reads and writes the same bytes", false, sent.length + " vs " + back.length + " bytes");
                return false;
            }
            msg.onExecute(null, owner);
            note("message {} at {} (player {} blocks away)", what, label(building),
                    Math.round(Math.sqrt(owner.blockPosition().distSqr(building.getID()))));
            return true;
        } catch (final ReflectiveOperationException | RuntimeException unbuilt) {
            check("the message " + what + " could be built and run", false, unbuilt.toString());
            return false;
        }
    }

    private void hireFire(final IBuilding building, final boolean hire, final ICitizenData citizen, final int moduleId) {
        stand(building.getID().offset(0, 0, 3));
        message(HireFireMessage.class, HireFireMessage.TYPE, building, buf -> {
            buf.writeBoolean(hire);
            buf.writeInt(citizen.getId());
            buf.writeInt(moduleId);
        }, (hire ? "hire " : "fire ") + citizen.getId());
    }

    private void hireAt(final IBuilding building, final ICitizenData citizen) {
        hireFire(building, true, citizen, moduleIdFor(building));
    }

    private void fireAt(final IBuilding building, final ICitizenData citizen) {
        hireFire(building, false, citizen, moduleIdFor(building));
    }

    private int moduleIdFor(final IBuilding building) {
        if (building instanceof BuildingTownSquare) {
            return SquareModules.DECORATOR.getRuntimeID();
        }
        if (building instanceof BuildingDecorator) {
            return DecoratorModules.WORK.getRuntimeID();
        }
        return jobModule(building, "lumberjack").getProducer().getRuntimeID();
    }

    private void hiringMode(final BuildingTownSquare square, final HiringMode m) {
        stand(square.getID().offset(0, 0, 3));
        message(BuildingHiringModeMessage.class, BuildingHiringModeMessage.TYPE, square, buf -> {
            buf.writeInt(m.ordinal());
            buf.writeInt(SquareModules.DECORATOR.getRuntimeID());
        }, "hiring mode " + m);
    }

    // ================================================================ step 1: the registry

    private void registry() {
        checkEq("the hut block's id", BuiltInRegistries.BLOCK.getKey(TownSquareSetup.HUT.get()).toString(), "colony_seasons:blockhuttownsquare");
        checkEq("the hut item's id", BuiltInRegistries.ITEM.getKey(TownSquareSetup.HUT_ITEM.get()).toString(), "colony_seasons:blockhuttownsquare");
        check("the hut item is MineColonies' hut item", TownSquareSetup.HUT_ITEM.get() instanceof ItemBlockHut, TownSquareSetup.HUT_ITEM.get().getClass().getSimpleName());
        final BuildingEntry entry = TownSquareSetup.BUILDING.get();
        checkEq("the building's id", entry.getRegistryName().toString(), "colony_seasons:townsquare");
        check("the building's hut block is ours", entry.getBuildingBlock() == TownSquareSetup.HUT.get(), String.valueOf(entry.getBuildingBlock()));
        check("the building's modules hold the square's Decorator module", entry.getModuleProducers().contains(SquareModules.DECORATOR),
                entry.getModuleProducers().size() + " producers");
        checkEq("the module's key", SquareModules.DECORATOR.key, "colony_seasons_square_decorator");
        boolean valid = true;
        for (final BlockState s : TownSquareSetup.HUT.get().getStateDefinition().getPossibleStates()) {
            valid &= DecoratorSetup.BUILDING_BE.get().isValid(s);
        }
        check("the hut block-entity type takes every state of both huts", valid && DecoratorSetup.BUILDING_BE.get().isValid(DecoratorSetup.HUT.get().defaultBlockState()),
                BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(DecoratorSetup.BUILDING_BE.get()).toString());
        check("the Decorator's job is still registered", DecoratorSetup.JOB.get() != null, String.valueOf(DecoratorSetup.JOB.getId()));
    }

    // ================================================================ the tag file and the tags

    private static JsonObject readTags() {
        try (InputStream in = CsSquare.class.getResourceAsStream("/cssquare/square_tags.json")) {
            if (in == null) {
                throw new IllegalStateException("no cssquare/square_tags.json in the probe jar");
            }
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (final java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BlockPos pos(final JsonElement e) {
        final JsonArray a = e.getAsJsonArray();
        return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
    }

    private Set<String> seatTags() {
        final Set<String> out = new HashSet<>();
        tagFile.getAsJsonArray("seat").forEach(e -> out.add(e.getAsString()));
        return out;
    }

    private void tagFileSane() {
        final List<String> names = new ArrayList<>();
        tagFile.getAsJsonArray("tags").forEach(e -> names.add(e.getAsString()));
        checkEq("the tag file's tags are the building's", names, BuildingTownSquare.TAGS);
        final JsonObject one = tagFile.getAsJsonObject("levels").getAsJsonObject(String.valueOf(BuildingTownSquare.BUILT_LEVELS));
        check("the tag file has the built level", one != null, "");
        int cells = 0;
        for (final String n : BuildingTownSquare.TAGS) {
            cells += one == null || !one.has(n) ? 0 : one.getAsJsonArray(n).size();
        }
        check("the tag file names cells", cells >= 15, cells + " cells");
    }

    /** tag -> world cells the file wants for a square whose hut block stands at the building's position, turned. */
    private Map<String, Set<BlockPos>> expected(final IBuilding b, final RotationMirror rm) {
        final Map<String, Set<BlockPos>> out = new TreeMap<>();
        final JsonObject one = tagFile.getAsJsonObject("levels").getAsJsonObject(String.valueOf(Math.max(1, b.getBuildingLevel())));
        for (final String tag : BuildingTownSquare.TAGS) {
            final Set<BlockPos> cells = new TreeSet<>();
            for (final JsonElement e : one.getAsJsonArray(tag)) {
                cells.add(b.getID().offset(rm.applyToPos(pos(e))));
            }
            out.put(tag, cells);
        }
        return out;
    }

    private static Map<String, Set<BlockPos>> actual(final IBuilding b) {
        final Map<String, Set<BlockPos>> out = new TreeMap<>();
        for (final String tag : BuildingTownSquare.TAGS) {
            out.put(tag, new TreeSet<>(b.getLocationsFromTag(tag)));
        }
        return out;
    }

    /** Every tag of the square's cached map, ours and others (the anchor's name tag). */
    private static Set<String> cachedNames(final IBuilding b) {
        final AbstractTileEntityColonyBuilding te = b.getTileEntity();
        return te == null ? Set.of() : new TreeSet<>(te.getCachedWorldTagNamePosMap().keySet());
    }

    private void tags(final String at, final BuildingTownSquare b, final RotationMirror rm, final boolean world) {
        final Map<String, Set<BlockPos>> want = expected(b, rm);
        final Map<String, Set<BlockPos>> got = actual(b);
        final List<String> differ = new ArrayList<>();
        for (final String tag : BuildingTownSquare.TAGS) {
            if (!want.get(tag).equals(got.get(tag))) {
                differ.add(tag + " got " + got.get(tag) + " wanted " + want.get(tag));
            }
        }
        check(at + ": getLocationsFromTag gives every tag turned with the building", differ.isEmpty(),
                BuildingTownSquare.TAGS.size() + " tags, " + want.values().stream().mapToInt(Set::size).sum() + " cells"
                        + (differ.isEmpty() ? "" : "; " + differ.get(0)));
        final Set<String> names = cachedNames(b);
        final Set<String> extra = new TreeSet<>(names);
        BuildingTownSquare.TAGS.forEach(extra::remove);
        check(at + ": the cached tag map holds the square's tags and the anchor's name only", names.containsAll(BuildingTownSquare.TAGS)
                && extra.stream().allMatch(n -> n.startsWith("name=")), "others " + extra);
        if (rm != RotationMirror.NONE) {
            check(at + ": the turned cells are not the unturned ones (the check sees the turn)", !expected(b, RotationMirror.NONE).equals(want), "");
        }
        if (!world) {
            return;
        }
        final Set<String> seats = seatTags();
        final List<String> bad = new ArrayList<>();
        int n = 0;
        for (final Map.Entry<String, Set<BlockPos>> e : want.entrySet()) {
            for (final BlockPos p : e.getValue()) {
                n++;
                final BlockState s = level.getBlockState(p);
                if (seats.contains(e.getKey()) ? !s.is(BlockTags.STAIRS) : !s.isAir()) {
                    bad.add(e.getKey() + " " + p.toShortString() + " holds " + s);
                }
            }
        }
        for (final BlockPos tree : want.get(BuildingTownSquare.TAG_TREE)) {
            final BlockState under = level.getBlockState(tree.below());
            if (!under.isCollisionShapeFullBlock(level, tree.below())) {
                bad.add("under the tree " + tree.below().toShortString() + " is " + under);
            }
        }
        check(at + ": in the world every tag cell is air (a seat tag a stair) and the cell under the tree is solid", bad.isEmpty(),
                bad.isEmpty() ? n + " cells" : bad.size() + " wrong, e.g. " + bad.get(0));
    }

    private static int[] size(final Tuple<BlockPos, BlockPos> corners) {
        final BlockPos a = corners.getA();
        final BlockPos b = corners.getB();
        return new int[] {Math.abs(b.getX() - a.getX()) + 1, Math.abs(b.getY() - a.getY()) + 1, Math.abs(b.getZ() - a.getZ()) + 1};
    }

    // ================================================================ step 4: eight squares for the tags

    private void tagGroup(final String group, final List<RotationMirror> rms, final boolean breakAfter) {
        for (int i = 0; i < rms.size(); i++) {
            final RotationMirror rm = rms.get(i);
            final int index = i;
            paste(group + " " + rm.name(), CORNERS.get(i), SQUARE_CMD, rm, b -> {
                if (!(b instanceof BuildingTownSquare square)) {
                    check(group + " " + rm.name() + ": the pasted hut makes a BuildingTownSquare", false, b.getClass().getSimpleName());
                    return;
                }
                tagSquares.add(square);
                note("{} {}: square {} at corner {}", group, rm.name(), square.getID().toShortString(), index);
            });
        }
        now(group + ": the tags", () -> {
            final int from = tagSquares.size() - rms.size();
            for (int i = 0; i < rms.size(); i++) {
                final BuildingTownSquare sq = tagSquares.get(from + i);
                final RotationMirror rm = rms.get(i);
                final String at = "tags " + rm.name();
                checkEq(at + ": the building's rotation and mirror", sq.getRotationMirror(), rm);
                checkEq(at + ": the pasted square's level", sq.getBuildingLevel(), 1);
                checkEq(at + ": hiring mode", sq.assignment().getHiringMode(), HiringMode.MANUAL);
                check(at + ": the corners are 21 x 20 x 21", Arrays.equals(size(sq.getCorners()), new int[] {21, 20, 21}),
                        Arrays.toString(size(sq.getCorners())));
                checkEq(at + ": the hut block entity is ours", sq.getTileEntity() == null ? null : sq.getTileEntity().getClass().getSimpleName(),
                        SeasonsTileEntity.class.getSimpleName());
                tags(at, sq, rm, true);
                sq.safeUpdateTEDataFromSchematic();
            }
        });
        steps.add(new Step(group + ": the tags after safeUpdateTEDataFromSchematic", 80) {
            @Override
            void end(final boolean done) {
                final int from = tagSquares.size() - rms.size();
                for (int i = 0; i < rms.size(); i++) {
                    tags("tags " + rms.get(i).name() + " after the schematic update", tagSquares.get(from + i), rms.get(i), false);
                }
            }
        });
        if (breakAfter) {
            now(group + ": broken", () -> {
                final int from = tagSquares.size() - rms.size();
                for (int i = rms.size() - 1; i >= 0; i--) {
                    final BuildingTownSquare sq = tagSquares.remove(from + i);
                    final BlockPos at = sq.getID();
                    sq.destroy();
                    level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    check(group + " " + rms.get(i).name() + ": the square is gone", colony.getServerBuildingManager().getBuilding(at) == null, at.toShortString());
                }
            });
        }
    }

    // ================================================================ steps 2 and 3: placing and building

    private Set<Long> claimed() {
        final Set<Long> out = new TreeSet<>();
        final ChunkPos home = new ChunkPos(center);
        for (int cx = home.x - FORCE; cx <= home.x + FORCE; cx++) {
            for (int cz = home.z - FORCE; cz <= home.z + FORCE; cz++) {
                if (DecorPlacer.colonyAt(level, new BlockPos(cx * 16 + 8, center.getY(), cz * 16 + 8)) == colony.getID()) {
                    out.add(ChunkPos.asLong(cx, cz));
                }
            }
        }
        return out;
    }

    private BuildingTownSquare placeSurvival(final String name, final BlockPos at) {
        owner.setGameMode(GameType.SURVIVAL);
        owner.getInventory().clearContent();
        owner.getInventory().add(new ItemStack(TownSquareSetup.HUT_ITEM.get()));
        final int before = owner.getInventory().countItem(TownSquareSetup.HUT_ITEM.get());
        stand(at.offset(0, 0, 3));
        final Blueprint bp = StructurePacks.getBlueprint(PACK, SQUARE_PATH, level.registryAccess());
        check(name + ": the square's blueprint loads", bp != null, SQUARE_PATH);
        boolean handled = false;
        String why = "";
        try {
            new SurvivalHandler().handle(bp, PACK, SQUARE_PATH, false, level, owner, at, RotationMirror.NONE);
            handled = colony.getServerBuildingManager().getBuilding(at) instanceof BuildingTownSquare;
            why = handled ? "" : "no building after the handler, at the hut position " + level.getBlockState(at);
        } catch (final Throwable t) {
            why = t.toString();
        }
        if (!handled) {
            note("NOTE FINDING H1 owner:fyi {}: MineColonies' survival placement handler did not place the square headless ({}); placed through the "
                    + "hut block's build-tool hook instead. The real build tool is for the in-game test (Q8).", name, why);
            final BlockState state = TownSquareSetup.HUT.get().defaultBlockState();
            level.setBlock(at, state, Block.UPDATE_ALL);
            TownSquareSetup.HUT.get().onBlockPlacedByBuildTool(level, at, state, owner, new ItemStack(TownSquareSetup.HUT_ITEM.get()),
                    RotationMirror.NONE, PACK, SQUARE_PATH);
        }
        final IBuilding b = colony.getServerBuildingManager().getBuilding(at);
        check(name + ": placed in survival through the build tool's server side (MineColonies' survival handler)", handled, why);
        if (handled) {
            checkEq(name + ": the hut item was used", owner.getInventory().countItem(TownSquareSetup.HUT_ITEM.get()), before - 1);
        }
        check(name + ": the hut block makes a BuildingTownSquare", b instanceof BuildingTownSquare, b == null ? "none" : b.getClass().getSimpleName());
        owner.setGameMode(GameType.CREATIVE);
        return b instanceof BuildingTownSquare square ? square : null;
    }

    /** The square at level 0: no assignment, no auto-hire. */
    private void unbuilt(final String name, final BuildingTownSquare square, final ICitizenData decorator) {
        checkEq(name + ": level before the build", square.getBuildingLevel(), 0);
        check(name + ": not built, so it takes no citizens", !square.isBuilt() && !square.canAssignCitizens(), "built " + square.isBuilt());
        checkEq(name + ": the stored blueprint path", square.getBlueprintPath(), SQUARE_PATH);
        checkEq(name + ": the claim radius at level 0", square.getClaimRadius(0), 0);
        final SquareAssignmentModule m = square.assignment();
        check(name + ": the Decorator module is there, found by its producer and by the runtime id the messages name",
                m != null && square.getModule(SquareModules.DECORATOR.getRuntimeID()) == m && m instanceof IAssignsJob, "id " + SquareModules.DECORATOR.getRuntimeID());
        check(name + ": an unbuilt square refuses the Decorator (module)", !m.assignCitizen(decorator) && m.assignedIds().isEmpty(), m.assignedIds().toString());
        hireAt(square, decorator);
        check(name + ": an unbuilt square refuses the Decorator (the window's hire)", m.assignedIds().isEmpty(), m.assignedIds().toString());
        hiringMode(square, HiringMode.AUTO);
        colonyTick();
        check(name + ": an unbuilt square on automatic hiring hires nobody", m.assignedIds().isEmpty() && m.getHiringMode() == HiringMode.AUTO,
                m.assignedIds() + ", mode " + m.getHiringMode());
        hiringMode(square, HiringMode.MANUAL);
    }

    private void build(final String name, final BuildingTownSquare[] square) {
        final WorkOrderBuilding[] order = new WorkOrderBuilding[1];
        final Blueprint[] loaded = new Blueprint[1];
        steps.add(new Step(name + ": the build order 0 to 1", 400) {
            @Override
            void start() {
                final WorkOrderBuilding wo = WorkOrderBuilding.create(WorkOrderType.BUILD, square[0]);
                order[0] = wo;
                checkEq(name + ": the order's target level", wo.getTargetLevel(), 1);
                checkEq(name + ": the order's blueprint", wo.getStructurePath(), SQUARE_PATH);
                checkEq(name + ": the order's pack", wo.getStructurePack(), PACK);
                colony.getWorkManager().addWorkOrder(wo, false);
                wo.loadBlueprint(level, bp -> loaded[0] = bp);
            }

            @Override
            boolean tick() {
                return loaded[0] != null;
            }

            @Override
            void end(final boolean done) {
                check(name + ": the order loads its blueprint as the Builder does", done, "");
                if (!done) {
                    finish();
                    return;
                }
                final BuildingTownSquare b = square[0];
                order[0].onCompleted(colony, null);
                b.setBuildingLevel(1);
                b.onUpgradeComplete(loaded[0], 1);
                colony.getWorkManager().removeWorkOrder(order[0]);
                checkEq(name + ": level after the build", b.getBuildingLevel(), 1);
                check(name + ": built and taking citizens", b.isBuilt() && b.canAssignCitizens(), "");
                checkEq(name + ": the blueprint path after the build", b.getBlueprintPath(), SQUARE_PATH);
                check(name + ": the corners are the 21 x 20 x 21 box", Arrays.equals(size(b.getCorners()), new int[] {21, 20, 21}),
                        Arrays.toString(size(b.getCorners())) + " " + b.getCorners().getA().toShortString() + " " + b.getCorners().getB().toShortString());
                checkEq(name + ": the max level this build offers", b.getMaxBuildingLevel(), BuildingTownSquare.BUILT_LEVELS);
                final List<Integer> radii = new ArrayList<>();
                for (int lv = 0; lv <= BuildingTownSquare.MAX_LEVEL; lv++) {
                    radii.add(b.getClaimRadius(lv));
                }
                checkEq(name + ": the claim radius at levels 0 to 5 (MineColonies' default gives 2 at 4 and 5)", radii, List.of(0, 1, 1, 1, 1, 1));
                final Tuple<BlockPos, BlockPos> c = b.getCorners();
                final List<String> out = new ArrayList<>();
                for (int x = Math.min(c.getA().getX(), c.getB().getX()); x <= Math.max(c.getA().getX(), c.getB().getX()); x += 4) {
                    for (int z = Math.min(c.getA().getZ(), c.getB().getZ()); z <= Math.max(c.getA().getZ(), c.getB().getZ()); z += 4) {
                        if (DecorPlacer.colonyAt(level, new BlockPos(x, b.getID().getY(), z)) != colony.getID()) {
                            out.add(x + "," + z);
                        }
                    }
                }
                check(name + ": every chunk the box touches is claimed by this colony", out.isEmpty(), out.isEmpty() ? "" : out.size() + " cells outside, e.g. " + out.get(0));
                tags(name + " built", b, RotationMirror.NONE, false);
            }
        });
    }

    // ================================================================ step 5: assigning

    /** He is the Decorator, employed at {@code ws}, the same job object as before; on {@code square} (or on none). */
    private void linked(final String where, final ICitizenData d, final BuildingDecorator ws, final BuildingTownSquare square, final IJob<?> jobBefore) {
        final IJob<?> job = d.getJob();
        final boolean decorator = job instanceof JobDecorator;
        final boolean atWs = d.getWorkBuilding() == ws;
        final boolean wsLists = work(ws).getAssignedCitizen().contains(d);
        final boolean wsAll = ws.getAllAssignedCitizen().contains(d);
        final boolean same = jobBefore == null || job == jobBefore;
        final List<BuildingTownSquare> on = SquareAssignmentModule.squaresListing(colony, d);
        final boolean squareOk = square == null ? on.isEmpty() : on.equals(List.of(square)) && square.getAllAssignedCitizen().equals(Set.of(d));
        final BuildingTownSquare found = decorator ? ((JobDecorator) job).findSquare() : null;
        check(where + ": citizen " + d.getId() + " is the Decorator at " + label(ws) + (square == null ? ", on no square" : ", assigned to " + label(square)),
                decorator && atWs && wsLists && wsAll && same && squareOk && found == square,
                "job " + jobName(job) + (same ? "" : " (a new job object)") + ", works at " + label(d.getWorkBuilding()) + ", " + label(ws) + " lists him "
                        + wsLists + "/" + wsAll + ", squares " + on.stream().map(this::label).toList() + ", findSquare " + label(found));
    }

    private void unchanged(final String where, final ICitizenData c, final String job, final IBuilding at) {
        check(where + ": citizen " + c.getId() + " is unchanged (" + job + " at " + label(at) + ") and on no square",
                jobName(c.getJob()).equals(job) && c.getWorkBuilding() == at && SquareAssignmentModule.squaresListing(colony, c).isEmpty(),
                "job " + jobName(c.getJob()) + ", works at " + label(c.getWorkBuilding()));
    }

    private void refused(final String where, final BuildingTownSquare square, final ICitizenData c, final String job, final IBuilding at,
                         final ICitizenData listed) {
        final SquareAssignmentModule m = square.assignment();
        final List<Integer> before = m.assignedIds();
        m.clearDirty();
        final boolean took = m.assignCitizen(c);
        check(where + " (module): refused, and the module marked dirty so the window's copy is put right", !took && m.assignedIds().equals(before) && m.isDirty,
                "took " + took + ", list " + m.assignedIds());
        hireAt(square, c);
        checkEq(where + " (the window's hire): the list", m.assignedIds(), before);
        if (job != null) {
            unchanged(where, c, job, at);
        }
        if (listed != null) {
            check(where + ": the listed Decorator stays", m.hasAssignedCitizen(listed), m.assignedIds().toString());
        }
    }

    private void assignSteps() {
        now("assign: the module, the window's fire and hire", () -> {
            final SquareAssignmentModule m = s1.assignment();
            final IJob<?> job = d1.getJob();
            final int decorators = decoratorCount();
            linked("before", d1, w1, null, job);
            check("assign: the module takes the Decorator", m.assignCitizen(d1), m.assignedIds().toString());
            linked("assigned by the module", d1, w1, s1, job);
            checkEq("assign: the square's list", m.assignedIds(), List.of(d1.getId()));
            checkEq("assign: the Workshop still has exactly one worker", work(w1).getAssignedCitizen(), List.of(d1));
            fireAt(s1, d1);
            linked("fired by the window at the square", d1, w1, null, job);
            hireAt(s1, d1);
            linked("hired by the window at the square", d1, w1, s1, job);
            checkEq("assign: Decorators in the colony", decoratorCount(), decorators);
            check("assign: a second hire of the listed Decorator changes nothing", m.assignCitizen(d1) && m.assignedIds().equals(List.of(d1.getId())), m.assignedIds().toString());
            final long t0 = System.nanoTime();
            ((JobDecorator) d1.getJob()).findSquare();
            note("findSquare took {} us for {} buildings (for the record)", (System.nanoTime() - t0) / 1000, colony.getServerBuildingManager().getBuildings().size());
            // the view on a dedicated server: it loads, and reads what the module wrote
            final RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
            m.serializeToView(buf);
            final SquareAssignmentModuleView view = new SquareAssignmentModuleView();
            view.deserialize(buf);
            check("the window's view reads the module's packet field for field (on a dedicated server, no window class loaded)",
                    view.getAssignedCitizens().equals(List.of(d1.getId())) && view.getHiringMode() == m.getHiringMode() && view.getMaxInhabitants() == 1
                            && view.isFull() && buf.readableBytes() == 0 && view.getJobEntry() == DecoratorSetup.JOB.get(),
                    view.getAssignedCitizens() + ", " + view.getHiringMode() + ", max " + view.getMaxInhabitants() + ", " + buf.readableBytes() + " bytes left");
        });
        now("assign: refusals", () -> {
            final IJob<?> job = d1.getJob();
            refused("a lumberjack", s1, lumberjack, "lumberjack", lumber, d1);
            refused("a jobless citizen", s1, jobless, "no job", null, d1);
            // the same two at the empty S2, where only the job check can refuse them
            refused("a lumberjack at an empty square", s2, lumberjack, "lumberjack", lumber, null);
            refused("a jobless citizen at an empty square", s2, jobless, "no job", null, null);
            refused("a second Decorator into a full square", s1, d2, null, null, d1);
            linked("the second Decorator after the refusal", d2, w2, null, null);
            refused("the Decorator onto a second square while he is on the first", s2, d1, null, null, null);
            linked("after the refusals", d1, w1, s1, job);
        });
        now("assign: the job's own assignTo", () -> {
            final JobDecorator job = (JobDecorator) d1.getJob();
            check("assignTo(the square's module) answers true without moving him", job.assignTo(s1.assignment()), "");
            linked("after assignTo(the square's module)", d1, w1, s1, job);
            check("assignTo(a module of another job) answers false", !job.assignTo(jobModule(lumber, "lumberjack")), "");
            linked("after assignTo(the lumberjack's module)", d1, w1, s1, job);
            check("assignTo(his Workshop's work module) goes on as MineColonies does it", job.assignTo(work(w1)), "");
            linked("after assignTo(his Workshop's module)", d1, w1, s1, job);
        });
        workshopUpgrade();
    }

    private void workshopUpgrade() {
        final WorkOrderBuilding[] order = new WorkOrderBuilding[1];
        final Blueprint[] loaded = new Blueprint[1];
        steps.add(new Step("assign: the Workshop's upgrade 1 to 2", 400) {
            IJob<?> job;

            @Override
            void start() {
                job = d1.getJob();
                final WorkOrderBuilding wo = WorkOrderBuilding.create(WorkOrderType.UPGRADE, w1);
                order[0] = wo;
                colony.getWorkManager().addWorkOrder(wo, false);
                wo.loadBlueprint(level, bp -> loaded[0] = bp);
            }

            @Override
            boolean tick() {
                return loaded[0] != null;
            }

            @Override
            void end(final boolean done) {
                check("the Workshop's upgrade order loads its blueprint", done, "");
                if (done) {
                    order[0].onCompleted(colony, null);
                    w1.setBuildingLevel(2);
                    w1.onUpgradeComplete(loaded[0], 2);
                }
                colony.getWorkManager().removeWorkOrder(order[0]);
                checkEq("the Workshop's level after the upgrade", w1.getBuildingLevel(), 2);
                linked("after the Workshop's upgrade", d1, w1, s1, job);
            }
        });
    }

    // ================================================================ step 6: hiring modes

    private BuildingDecorator workshopOf(final ICitizenData d) {
        return d.getWorkBuilding() instanceof BuildingDecorator hut ? hut : null;
    }

    private void empty(final BuildingTownSquare square) {
        for (final ICitizenData c : square.assignment().getAssignedCitizen()) {
            fireAt(square, c);
        }
        square.assignment().prune();
    }

    private void hiringSteps() {
        now("hiring: manual", () -> {
            empty(s1);
            empty(s2);
            hiringMode(s2, HiringMode.MANUAL);
            hiringMode(s1, HiringMode.MANUAL);
            checkEq("hiring: the message set MANUAL", s1.assignment().getHiringMode(), HiringMode.MANUAL);
            colonyTick();
            colonyTick();
            check("hiring: MANUAL hires nobody", s1.assignment().assignedIds().isEmpty(), s1.assignment().assignedIds().toString());
        });
        now("hiring: automatic", () -> {
            hiringMode(s1, HiringMode.AUTO);
            checkEq("hiring: the message set AUTO", s1.assignment().getHiringMode(), HiringMode.AUTO);
            check("hiring: the town hall's auto-hire setting is off", !colony.getSettings().getSetting(BuildingTownHall.AUTO_HIRING_MODE).getValue(), "");
            colonyTick();
            final List<ICitizenData> got = s1.assignment().getAssignedCitizen();
            final ICitizenData lowest = d1.getId() < d2.getId() ? d1 : d2;
            check("hiring: AUTO hires within one colony tick, with the town hall's setting off: the lowest-id free Decorator", got.equals(List.of(lowest)),
                    got.stream().map(ICitizenData::getId).toList() + ", wanted " + lowest.getId());
            if (!got.isEmpty()) {
                linked("hired by AUTO", got.get(0), workshopOf(got.get(0)), s1, null);
            }
            empty(s1);
        });
        now("hiring: default", () -> {
            hiringMode(s1, HiringMode.DEFAULT);
            colonyTick();
            check("hiring: DEFAULT with the town hall's setting off hires nobody", s1.assignment().assignedIds().isEmpty(), s1.assignment().assignedIds().toString());
            final BoolSetting autoHire = colony.getSettings().getSetting(BuildingTownHall.AUTO_HIRING_MODE);
            autoHire.trigger();
            check("hiring: the town hall's setting is on", autoHire.getValue(), "");
            colonyTick();
            check("hiring: DEFAULT with the town hall's setting on hires within one colony tick", s1.assignment().getAssignedCitizen().size() == 1,
                    s1.assignment().assignedIds().toString());
            autoHire.trigger();
            empty(s1);
        });
        now("hiring: two squares, two Decorators", () -> {
            hiringMode(s1, HiringMode.AUTO);
            hiringMode(s2, HiringMode.AUTO);
            colonyTick();
            colonyTick();
            final List<ICitizenData> a = s1.assignment().getAssignedCitizen();
            final List<ICitizenData> b = s2.assignment().getAssignedCitizen();
            check("hiring: each square gets a different Decorator", a.size() == 1 && b.size() == 1 && a.get(0) != b.get(0),
                    "S1 " + a.stream().map(ICitizenData::getId).toList() + ", S2 " + b.stream().map(ICitizenData::getId).toList());
            for (final ICitizenData d : List.of(d1, d2)) {
                check("hiring: Decorator " + d.getId() + " is on one square only", SquareAssignmentModule.squaresListing(colony, d).size() == 1,
                        SquareAssignmentModule.squaresListing(colony, d).stream().map(this::label).toList().toString());
            }
            hiringMode(s1, HiringMode.MANUAL);
            hiringMode(s2, HiringMode.MANUAL);
        });
    }

    // ================================================================ step 7: pruning

    private ICitizenData hireNewDecorator(final BuildingDecorator ws, final BlockPos at) {
        final ICitizenData c = spawn(at);
        hireAt(ws, c);
        check("set up: a new citizen " + c.getId() + " is hired as the Decorator at " + label(ws), c.getJob() instanceof JobDecorator && c.getWorkBuilding() == ws,
                jobName(c.getJob()));
        return c;
    }

    private void pruneSteps() {
        now("prune: death", () -> {
            final BuildingTownSquare square = s2.assignment().getAssignedCitizen().isEmpty() ? s1 : s2;
            final ICitizenData dead = square.assignment().getAssignedCitizen().get(0);
            final BuildingDecorator ws = workshopOf(dead);
            final int id = dead.getId();
            colony.getCitizenManager().removeCivilian(dead);
            check("prune: after the citizen's removal the square lists nobody at once", square.assignment().getAssignedCitizen().isEmpty()
                    && !square.assignment().hasAssignedCitizen() && !square.assignment().isFull(), square.assignment().getAssignedCitizen().toString());
            check("prune: his id waits for the next colony tick", square.assignment().assignedIds().contains(id), square.assignment().assignedIds().toString());
            colonyTick();
            check("prune: after one colony tick his id is gone", square.assignment().assignedIds().isEmpty(), square.assignment().assignedIds().toString());
            final ICitizenData fresh = hireNewDecorator(ws, ws.getID().offset(0, 0, 3));
            if (dead == d1) {
                d1 = fresh;
            } else {
                d2 = fresh;
            }
        });
        now("prune: fired at his Workshop", () -> {
            final ICitizenData d = d1;
            final BuildingDecorator ws = workshopOf(d);
            empty(s1);
            check("set up: S1 takes the Decorator", s1.assignment().assignCitizen(d), "");
            fireAt(ws, d);
            check("prune: fired at the Workshop, he has no job", d.getJob() == null, jobName(d.getJob()));
            colonyTick();
            check("prune: after one colony tick the square has dropped him", s1.assignment().assignedIds().isEmpty(), s1.assignment().assignedIds().toString());
            hireAt(ws, d);
            linked("hired again at his Workshop", d, ws, null, null);
        });
        now("prune: hired as a lumberjack", () -> {
            final ICitizenData d = d1;
            final BuildingDecorator ws = workshopOf(d);
            check("set up: S1 takes the Decorator", s1.assignment().assignCitizen(d), "");
            // the lumberjack's hut has one place: its own lumberjack steps out for the move (not under test)
            fireAt(lumber, lumberjack);
            fireAt(ws, d);
            hireAt(lumber, d);
            check("prune: he is the lumberjack now", "lumberjack".equals(jobName(d.getJob())) && d.getWorkBuilding() == lumber, jobName(d.getJob()));
            colonyTick();
            check("prune: after one colony tick the square has dropped the lumberjack", s1.assignment().assignedIds().isEmpty(), s1.assignment().assignedIds().toString());
            fireAt(lumber, d);
            hireAt(ws, d);
            linked("back at his Workshop", d, ws, null, null);
            hireAt(lumber, lumberjack);
            check("set up: the lumberjack is back at his hut", "lumberjack".equals(jobName(lumberjack.getJob())) && lumberjack.getWorkBuilding() == lumber,
                    jobName(lumberjack.getJob()));
            note("NOTE colony tick: MineColonies runs the buildings' colony tick every {} ticks of an active colony (Colony.worldTickSlow); the square "
                    + "prunes and auto-hires there, and when the save is read", COLONY_TICK);
        });
    }

    // ================================================================ step 8 and the square broken

    private void endSteps() {
        now("no decorating at the square", () -> {
            final List<IBuilding> order = DecorWork.buildingsInOrder(colony, center);
            check("DecorWork.buildingsInOrder holds no Town Square, and still the Workshops", order.stream().noneMatch(b -> b instanceof BuildingTownSquare)
                    && order.contains(w1) && order.contains(w2), order.size() + " buildings");
        });
        now("the square broken", () -> {
            empty(s2);
            check("set up: S2 takes the Decorator", s2.assignment().assignCitizen(d2), "");
            final IJob<?> job = d2.getJob();
            final SquareAssignmentModule m = s2.assignment();
            final BlockPos at = s2.getID();
            s2.destroy();
            level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            check("broken: the square's list is cleared", m.assignedIds().isEmpty() && colony.getServerBuildingManager().getBuilding(at) == null, m.assignedIds().toString());
            s2 = null;
            linked("after the square was broken", d2, workshopOf(d2), null, job);
            note("NOTE section 8: Q1 proves points 1, 2, 3, 4, 5, 8, 10 and 11; 6, 7 and 13 are Q2's (the take-down and the downgrade), 9 is Q3's, "
                    + "12 is Q4's; point 5's upgrade waits for Q6's levels (this build ships one)");
        });
        unloadSteps();
    }

    /** Point 5: with the square's chunk unloaded its tags read empty; loaded again, they are back. */
    private void unloadSteps() {
        final BuildingTownSquare[] target = new BuildingTownSquare[1];
        final RotationMirror[] rm = new RotationMirror[1];
        steps.add(new Step("unload: the square's chunk", 600) {
            ChunkPos chunk;

            @Override
            void start() {
                target[0] = tagSquares.get(tagSquares.size() - 1);
                rm[0] = target[0].getRotationMirror();
                chunk = new ChunkPos(target[0].getID());
                server.getGameRules().getRule(GameRules.RULE_SPAWN_CHUNK_RADIUS).set(0, server);
                for (int cx = chunk.x - 2; cx <= chunk.x + 2; cx++) {
                    for (int cz = chunk.z - 2; cz <= chunk.z + 2; cz++) {
                        level.setChunkForced(cx, cz, false);
                    }
                }
                owner.setPos(center.getX(), center.getY(), center.getZ());
            }

            int notFull = -1;

            @Override
            boolean tick() {
                if (notFull < 0 && !level.hasChunk(chunk.x, chunk.z)) {
                    notFull = t;
                }
                // the chunk leaves the loaded set first; its block entities go when it is really unloaded
                return notFull >= 0 && target[0].getTileEntity() == null;
            }

            @Override
            void end(final boolean done) {
                if (done) {
                    check("unload: with the hut's chunk unloaded the square has no block entity and every tag reads empty",
                            actual(target[0]).values().stream().allMatch(Set::isEmpty), "not loaded after " + notFull + " ticks, block entity gone after " + t);
                } else if (notFull >= 0) {
                    // the chunk is no longer loaded, but MineColonies' building still holds its block entity, which is not removed yet
                    tags("unload: the chunk not loaded, its block entity still held", target[0], rm[0], false);
                    note("NOTE FINDING H2 owner:tech unload: the hut's chunk {} left the loaded set after {} ticks, but its block entity was not removed "
                            + "within {} ticks, and getLocationsFromTag kept answering the square's (right) cells from it. The tags read empty only once "
                            + "the block entity is gone (AbstractBuildingContainer.getLocationsFromTag answers an empty list for a null getTileEntity(), "
                            + "and AbstractBuilding.getTileEntity() answers null for a removed block entity whose block is not loaded). Consumers must "
                            + "check that a cell is loaded before they touch it, as design 3.2 already does, not wait for empty tags", chunk, notFull, t);
                } else {
                    note("NOTE unload: the rig kept the chunk {} loaded for {} ticks; from the bytecode, AbstractBuildingContainer.getLocationsFromTag "
                            + "answers an empty list when getTileEntity() is null, and AbstractBuilding.getTileEntity() answers null while the hut's block is "
                            + "not loaded (WorldUtil.isBlockLoaded)", chunk, t);
                }
                for (int cx = chunk.x - 2; cx <= chunk.x + 2; cx++) {
                    for (int cz = chunk.z - 2; cz <= chunk.z + 2; cz++) {
                        level.setChunkForced(cx, cz, true);
                    }
                }
            }
        });
        steps.add(new Step("unload: the chunk loaded again", 400) {
            @Override
            boolean tick() {
                return t >= 20 && target[0].getTileEntity() != null && !target[0].getTileEntity().getSchematicName().isEmpty();
            }

            @Override
            void end(final boolean done) {
                check("unload: the chunk is back with the square's block entity", done, "");
                if (done) {
                    tags("unload: loaded again", target[0], rm[0], true);
                }
            }
        });
    }

    // ================================================================ the plans

    private void setupSteps() {
        now("setup", () -> {
            CalendarManager.setDateSource(DateSource.fixed(java.time.LocalDate.of(2026, 7, 15)));
            CalendarManager.refresh(server);
            quietWorld();
            buildColony();
            tagFile = readTags();
            tagFileSane();
            claimedBefore = claimed();
            note("the colony's claim: {} chunks of the {} looked at", claimedBefore.size(), (2 * FORCE + 1) * (2 * FORCE + 1));
        });
        pasteWorkshops();
        now("the Decorators", () -> {
            d1 = hireNewDecorator(w1, w1.getID().offset(0, 0, 3));
            d2 = hireNewDecorator(w2, w2.getID().offset(0, 0, 3));
        });
    }

    private void squareSteps() {
        final BuildingTownSquare[] a = new BuildingTownSquare[1];
        final BuildingTownSquare[] b = new BuildingTownSquare[1];
        now("place: S1 and S2 in survival", () -> {
            a[0] = placeSurvival("S1", center.offset(S1_OFFSET));
            b[0] = placeSurvival("S2", center.offset(S2_OFFSET));
            s1 = a[0];
            s2 = b[0];
            if (s1 == null || s2 == null) {
                finish();
                return;
            }
            unbuilt("S1", s1, d1);
            check("place: MineColonies lets a second square be placed (canPlaceAt is the same for every position)",
                    TownSquareSetup.HUT.get().canPlaceAt(center.offset(S2_OFFSET), owner), "");
            note("NOTE second square: nothing in MineColonies stops it; a per-colony limit would need a veto of our own when the hut is placed "
                    + "(onBlockPlacedByBuildTool or setPlacedBy of BlockHutTownSquare). Q1 enforces none (Q2 decides the main square)");
        });
        build("S1", a);
        build("S2", b);
        now("the claim after the builds", () -> {
            final Set<Long> after = claimed();
            check("claim: the colony's claim before the squares is part of the claim after their builds (nothing shrank)", after.containsAll(claimedBefore),
                    claimedBefore.size() + " chunks before, " + after.size() + " after");
        });
    }

    private void planDefault() {
        now("registry", this::registry);
        setupSteps();
        now("the jobless citizen and the lumberjack", () -> {
            jobless = spawn(center.offset(2, 0, 4));
            lumberjack = spawn(center.offset(0, 0, 10));
            final IAssignsJob m = jobModule(lumber, "lumberjack");
            check("set up: the lumberjack works at his hut", m != null && m.assignCitizen(lumberjack) && lumberjack.getWorkBuilding() == lumber,
                    jobName(lumberjack.getJob()));
        });
        tagGroup("turned", TURNED, true);
        tagGroup("mirrored", MIRRORED, false);
        squareSteps();
        assignSteps();
        hiringSteps();
        pruneSteps();
        endSteps();
    }

    // ================================================================ the restart

    private void planRestartSave() {
        setupSteps();
        squareSteps();
        now("save: S1 with its Decorator, S2 with a removed one", () -> {
            hiringMode(s1, HiringMode.MANUAL);
            hireAt(s1, d1);
            linked("save: S1's Decorator", d1, w1, s1, null);
            check("save: S2 takes the second Decorator", s2.assignment().assignCitizen(d2), "");
            hiringMode(s2, HiringMode.MANUAL);
            final int gone = d2.getId();
            colony.getCitizenManager().removeCivilian(d2);
            check("save: S2 still holds the removed citizen's id (no colony tick yet)", s2.assignment().assignedIds().equals(List.of(gone)),
                    s2.assignment().assignedIds().toString());
            final Properties ids = new Properties();
            ids.setProperty("d1", Integer.toString(d1.getId()));
            ids.setProperty("d2", Integer.toString(gone));
            ids.setProperty("s1", Long.toString(s1.getID().asLong()));
            ids.setProperty("s2", Long.toString(s2.getID().asLong()));
            ids.setProperty("w1", Long.toString(w1.getID().asLong()));
            try (var out = Files.newBufferedWriter(server.getWorldPath(LevelResource.ROOT).resolve(IDS_FILE), StandardCharsets.US_ASCII)) {
                ids.store(out, "cssquare restart");
            } catch (final java.io.IOException unwritten) {
                check("the ids are written for the restart", false, unwritten.getClass().getSimpleName());
            }
            note("saving: colony {}, S1 {} with {}, S2 {} with the removed {}", colony.getID(), s1.getID().toShortString(), d1.getId(), s2.getID().toShortString(), gone);
            check("the world saved", server.saveEverything(false, true, true), "");
        });
    }

    private void planRestartLoad() {
        steps.add(new Step("load: the colony comes back", 2400) {
            Properties ids;

            @Override
            void start() {
                ids = new Properties();
                try (var in = Files.newBufferedReader(server.getWorldPath(LevelResource.ROOT).resolve(IDS_FILE), StandardCharsets.US_ASCII)) {
                    ids.load(in);
                } catch (final java.io.IOException unread) {
                    check("the ids of the first run are there", false, unread.getClass().getSimpleName());
                    finish();
                }
            }

            @Override
            boolean tick() {
                final IColony found = IColonyManager.getInstance().getColonyByWorld(1, level);
                if (found == null) {
                    return false;
                }
                final IBuilding a = found.getServerBuildingManager().getBuilding(BlockPos.of(Long.parseLong(ids.getProperty("s1", "0"))));
                final IBuilding b = found.getServerBuildingManager().getBuilding(BlockPos.of(Long.parseLong(ids.getProperty("s2", "0"))));
                final IBuilding w = found.getServerBuildingManager().getBuilding(BlockPos.of(Long.parseLong(ids.getProperty("w1", "0"))));
                if (a instanceof BuildingTownSquare sa && b instanceof BuildingTownSquare sb && w instanceof BuildingDecorator ws && sa.getTileEntity() != null) {
                    colony = found;
                    s1 = sa;
                    s2 = sb;
                    w1 = ws;
                    return t >= 40;
                }
                return false;
            }

            @Override
            void end(final boolean done) {
                check("load: the colony, both squares and the Workshop are back", done, colony == null ? "no colony" : "colony " + colony.getID());
                if (!done) {
                    finish();
                    return;
                }
                center = colony.getCenter();
                owner = FakePlayerFactory.getMinecraft(level);
                tagFile = readTags();
                d1 = colony.getCitizenManager().getCivilian(Integer.parseInt(ids.getProperty("d1", "-1")));
                final int gone = Integer.parseInt(ids.getProperty("d2", "-1"));
                check("load: the Decorator is back", d1 != null, ids.toString());
                if (d1 == null) {
                    finish();
                    return;
                }
                // before any colony tick
                checkEq("load: S1 lists its Decorator", s1.assignment().assignedIds(), List.of(d1.getId()));
                checkEq("load: S1's hiring mode", s1.assignment().getHiringMode(), HiringMode.MANUAL);
                linked("load", d1, w1, s1, null);
                check("load: the removed citizen is gone", colony.getCitizenManager().getCivilian(gone) == null, "id " + gone);
                check("load: S2 dropped the removed citizen's id when the save was read", s2.assignment().assignedIds().isEmpty(), s2.assignment().assignedIds().toString());
                checkEq("load: S1's level", s1.getBuildingLevel(), 1);
                colonyTick();
                checkEq("load: after one colony tick S1 still lists its Decorator", s1.assignment().assignedIds(), List.of(d1.getId()));
                checkEq("load: and stays MANUAL", s1.assignment().getHiringMode(), HiringMode.MANUAL);
                linked("load, after one colony tick", d1, w1, s1, null);
                tags("load: S1's tags", s1, RotationMirror.NONE, false);
            }
        });
    }
}
