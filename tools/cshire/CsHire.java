package me.lovkar.seasons.cshire;

import com.ldtteam.common.network.PlayMessageType;
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
import com.minecolonies.api.colony.buildings.modules.ITickingModule;
import com.minecolonies.api.colony.jobs.IJob;
import com.minecolonies.api.colony.jobs.registry.JobEntry;
import com.minecolonies.api.colony.workorders.WorkOrderType;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.tileentities.AbstractTileEntityColonyBuilding;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import com.minecolonies.core.colony.buildings.modules.settings.BoolSetting;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingTownHall;
import com.minecolonies.core.colony.workorders.WorkOrderBuilding;
import com.minecolonies.core.network.messages.server.colony.building.HireFireMessage;
import io.netty.buffer.Unpooled;
import me.lovkar.seasons.block.SeasonsTileEntity;
import me.lovkar.seasons.calendar.CalendarManager;
import me.lovkar.seasons.calendar.DateSource;
import me.lovkar.seasons.calendar.Season;
import me.lovkar.seasons.colony.BuildingDecorator;
import me.lovkar.seasons.colony.DecoratorInfoModule;
import me.lovkar.seasons.colony.DecoratorInfoModuleView;
import me.lovkar.seasons.colony.DecoratorModules;
import me.lovkar.seasons.colony.JobDecorator;
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
import net.minecraft.util.Tuple;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/**
 * Headless check of how a citizen becomes the Decorator on a real colony, the way MineColonies itself does it (chunk
 * S10b). The probes before this one hired him by calling the work module directly; the in-game test showed that the hut
 * window's hire did not move a citizen who worked elsewhere. Here every hire and fire goes through MineColonies' own
 * {@link HireFireMessage}, built from bytes the way the network layer builds it and run through its public server
 * handler, with the colony's owner (a fake player) standing where a player would stand.
 *
 * <p>The colony: a town hall, the Decorator's Workshop pasted with the same command a player types
 * ({@code /structurize paste ... Colony.Seasons decorator.workshop1}), a lumberjack's hut and a guard tower 24 blocks
 * from the Workshop on the other side of the town hall, and a second lumberjack's hut next to the Workshop (the control).
 * MineColonies drops a building message when the player is more than 15 blocks from that building, so the window's
 * "fire at the old hut" never arrives when the player stands at the Workshop.</p>
 *
 * <p>Modes (the rig writes the word to probe-mode.txt):</p>
 * <ul>
 *   <li>none: the paste (the stored blueprint paths, the corners, the module the window's messages name), then at level
 *       1 and at level 5: auto-hire with the Workshop as the only vacancy, the window's fire and hire of a jobless
 *       citizen, a hire into a full Workshop, moving an employed citizen with the player near both huts and with the
 *       player at the Workshop only; between the rounds an upgrade work order 1 to 2 and its blueprint; at the end the
 *       calendar page's refresh;</li>
 *   <li>{@code restart-save} then {@code restart-load} (tools/restart.sh): a knight moved to the Workshop from far away
 *       and a lumberjack left where he works, both read back after a restart.</li>
 * </ul>
 * After every step it checks what {@code /minecolonies citizens info} shows: the citizen's own job and work building,
 * and the lists of the Workshop and the old hut. Prints "[cshire] CHECK ...: OK|FAIL" lines and
 * "[cshire] RESULT cshire: PASS|FAIL", then halts. The two words the rig greps for stay out of the details.
 */
@Mod("colonyseasonshire")
public class CsHire {
    private static final Logger LOGGER = LoggerFactory.getLogger("cshire");
    private static final int SETUP_TICK = 60;
    private static final long WATCHDOG_SECONDS = 480;
    private static final String PACK = "Colony Seasons";
    private static final int AUTO_HIRE_BOUND = 2400;
    /** From the town hall: the Workshop's hut block (the paste position), the far huts, the control hut, where to stand for "near both". */
    private static final BlockPos WORKSHOP_OFFSET = new BlockPos(14, 0, 0);
    private static final BlockPos LUMBER_OFFSET = new BlockPos(-10, 0, 0);
    private static final BlockPos TOWER_OFFSET = new BlockPos(-10, 0, 4);
    private static final BlockPos CONTROL_OFFSET = new BlockPos(14, 0, 10);
    private static final BlockPos MIDDLE_OFFSET = new BlockPos(2, 0, 1);
    private static final LocalDate SUMMER = LocalDate.of(2026, 7, 15);
    private static final String IDS_FILE = "cshire-ids.properties";

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
    private BlockPos hutPos;
    private BuildingDecorator hut;
    private IBuilding lumber;
    private IBuilding tower;
    private IBuilding control;
    private JobEntry lumberEntry;
    private JobEntry knightEntry;
    /** A: the jobless one; W: works at the lumberjack's hut; K: the knight. */
    private ICitizenData citizenA;
    private ICitizenData citizenW;
    private ICitizenData citizenK;
    private Tuple<BlockPos, BlockPos> cornersAtSetup;

    private final List<Step> steps = new ArrayList<>();
    private int stepIndex;

    public CsHire() {
        NeoForge.EVENT_BUS.addListener(this::onTick);
    }

    // ================================================================ logging

    private void check(final String what, final boolean ok, final String detail) {
        LOGGER.info("[cshire] CHECK {}: {} {}", what, ok ? "OK" : "FAIL", detail);
        failed |= !ok;
    }

    private void checkEq(final String what, final Object actual, final Object expected) {
        check(what, Objects.equals(actual, expected), "got " + actual + (Objects.equals(actual, expected) ? "" : ", wanted " + expected));
    }

    private static void note(final String text, final Object... args) {
        LOGGER.info("[cshire] " + text, args);
    }

    // ================================================================ the tick

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
            check("the probe ran without a throwable", false, t.toString());
            LOGGER.info("[cshire] trace: {}", Arrays.toString(Arrays.copyOf(t.getStackTrace(), Math.min(8, t.getStackTrace().length))));
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
            if (step.sprint) {
                server.tickRateManager().requestGameToSprint(step.maxTicks + 200);
            }
            step.start();
        }
        step.t++;
        final boolean done = step.tick();
        if (done || step.t >= step.maxTicks) {
            if (step.sprint) {
                server.tickRateManager().stopSprinting();
            }
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
        if (server != null) {
            server.tickRateManager().stopSprinting();
        }
        LOGGER.info("[cshire] RESULT cshire: {}", failed ? "FAIL" : "PASS");
        if (server != null) {
            server.halt(false);
        }
    }

    private static String readMode() throws Exception {
        final Path file = Path.of("probe-mode.txt");
        return Files.exists(file) ? Files.readString(file, StandardCharsets.US_ASCII).trim() : "";
    }

    /** One stretch of the run: up to {@code maxTicks} server ticks; {@code tick()} returning true ends it early. */
    private abstract static class Step {
        final String name;
        final int maxTicks;
        final boolean sprint;
        boolean started;
        int t;

        Step(final String name, final int maxTicks, final boolean sprint) {
            this.name = name;
            this.maxTicks = maxTicks;
            this.sprint = sprint;
        }

        void start() {
        }

        boolean tick() {
            return false;
        }

        void end(final boolean done) {
        }
    }

    /** A step that does everything in one tick. */
    private void now(final String name, final Runnable body) {
        steps.add(new Step(name, 1, false) {
            @Override
            void start() {
                body.run();
            }
        });
    }

    private void step(final Step step) {
        steps.add(step);
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

    /** No mobs, no weather: only the probe and MineColonies change anything here. */
    private void quietWorld() {
        server.setDifficulty(Difficulty.PEACEFUL, true);
        server.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        server.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        level.setWeatherParameters(24000 * 20, 0, false, false);
    }

    private void setDate(final LocalDate date) {
        CalendarManager.setDateSource(DateSource.fixed(date));
        CalendarManager.refresh(server);
    }

    /** The colony "Seasons Test" with a town hall; the owner is the fake player every message comes from. */
    private void buildColony() {
        // not at 0,0,0: MineColonies warns about a building at the zero position
        final int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, 2, 2);
        center = new BlockPos(2, y, 2);
        for (int cx = -3; cx <= 3; cx++) {
            for (int cz = -3; cz <= 3; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }
        owner = FakePlayerFactory.getMinecraft(level);
        stand(center);
        colony = IColonyManager.getInstance().createColony(level, center, owner, "Seasons Test", "Medieval Oak");
        note("colony {} created at {}", colony == null ? "none" : colony.getID(), center);
        final Block townHall = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.fromNamespaceAndPath("minecolonies", "blockhuttownhall")).orElse(null);
        if (townHall != null) {
            level.setBlock(center, townHall.defaultBlockState(), 3);
            if (level.getBlockEntity(center) instanceof AbstractTileEntityColonyBuilding te) {
                note("town hall registered: {}", colony.getServerBuildingManager().addNewBuilding(te, level) != null);
            }
        }
        hutPos = center.offset(WORKSHOP_OFFSET);
    }

    /** Puts the owner on a block (feet in it), the way a player stands there with the hut window open. */
    private void stand(final BlockPos at) {
        owner.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
    }

    private String distances() {
        final StringBuilder out = new StringBuilder("player at " + owner.blockPosition().toShortString());
        for (final IBuilding b : new IBuilding[] {hut, lumber, tower, control}) {
            if (b != null) {
                out.append(", ").append(Math.round(Math.sqrt(owner.blockPosition().distSqr(b.getID())) * 10) / 10.0).append(" from ").append(label(b));
            }
        }
        return out.toString();
    }

    private String label(final IBuilding b) {
        if (b == null) {
            return "none";
        }
        if (b == hut) {
            return "the Workshop";
        }
        if (b == lumber) {
            return "the lumberjack's hut";
        }
        if (b == tower) {
            return "the guard tower";
        }
        if (b == control) {
            return "the control hut";
        }
        return b.getBuildingDisplayName() + "@" + b.getID().toShortString();
    }

    /** What a player types, with the owner as the source: creative, permission 4; the replies are kept. */
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

    /** The Workshop pasted as the owner pastes it in game: the bare path, no ".blueprint". */
    private void pasteWorkshop() {
        owner.setGameMode(GameType.CREATIVE);
        note("owner game mode {}", owner.gameMode.getGameModeForPlayer());
        final String line = "structurize paste " + hutPos.getX() + " " + hutPos.getY() + " " + hutPos.getZ() + " Colony.Seasons decorator.workshop1";
        final Replies replies = command(line);
        note("'{}' said {}", line, replies.said);
        check("the paste command is accepted", replies.said.stream().anyMatch(s -> s.contains("paste.success")), String.valueOf(replies.said));
        final IBuilding building = colony.getServerBuildingManager().getBuilding(hutPos);
        check("the pasted hut block makes a BuildingDecorator at once", building instanceof BuildingDecorator,
                building == null ? "none" : building.getClass().getSimpleName());
        if (building instanceof BuildingDecorator d) {
            hut = d;
            cornersAtSetup = d.getCorners();
            note("right after the command: level {}, built {}, path '{}', block entity path '{}', corners {} {}", d.getBuildingLevel(), d.isBuilt(),
                    d.getBlueprintPath(), d.getTileEntity() == null ? "-" : d.getTileEntity().getBlueprintPath(),
                    cornersAtSetup.getA().toShortString(), cornersAtSetup.getB().toShortString());
        }
    }

    /** True when the corners are the blueprint's box (13 x 16 x 11), not one block. */
    private static boolean blueprintBox(final Tuple<BlockPos, BlockPos> corners) {
        final BlockPos a = corners.getA();
        final BlockPos b = corners.getB();
        final int dx = Math.abs(b.getX() - a.getX()) + 1;
        final int dy = Math.abs(b.getY() - a.getY()) + 1;
        final int dz = Math.abs(b.getZ() - a.getZ()) + 1;
        return dx == 13 && dy == 16 && dz == 11;
    }

    private static String box(final Tuple<BlockPos, BlockPos> corners) {
        return corners.getA().toShortString() + " to " + corners.getB().toShortString();
    }

    private void pasteSteps() {
        now("setup", () -> {
            setDate(SUMMER);
            quietWorld();
            buildColony();
            pasteWorkshop();
            if (hut == null) {
                finish();
            }
        });
        step(new Step("the paste", 600, false) {
            @Override
            boolean tick() {
                final AbstractTileEntityColonyBuilding te = hut.getTileEntity();
                return t >= 100 && te != null && !te.getSchematicName().isEmpty();
            }

            @Override
            void end(final boolean done) {
                final AbstractTileEntityColonyBuilding te = hut.getTileEntity();
                check("the paste finished (the hut block entity has its blueprint data)", done, te == null ? "no block entity" : "'" + te.getSchematicName() + "'");
                checkEq("the hut block entity is ours", te == null ? null : te.getClass().getSimpleName(), SeasonsTileEntity.class.getSimpleName());
                note("after the paste: level {}, built {}, deconstructed {}, schematic name '{}'", hut.getBuildingLevel(), hut.isBuilt(),
                        hut.isDeconstructed(), te == null ? "-" : te.getSchematicName());
                check("the pasted hut is built and has a level", hut.isBuilt() && hut.getBuildingLevel() >= 1, "level " + hut.getBuildingLevel());
                checkPaths("after the paste");
                check("the corners computed while the command ran are the blueprint's box (13 x 16 x 11), not one block",
                        blueprintBox(cornersAtSetup), box(cornersAtSetup));
                check("the corners after the paste are the blueprint's box", blueprintBox(hut.getCorners()), box(hut.getCorners()));
                final IBuildingModule byId = hut.getModule(DecoratorModules.WORK.getRuntimeID());
                final WorkerBuildingModule byProducer = hut.getModule(DecoratorModules.WORK);
                check("the module the window's messages name (by runtime id) is the work module, and it assigns jobs",
                        byId != null && byId == byProducer && byId instanceof IAssignsJob,
                        "id " + DecoratorModules.WORK.getRuntimeID() + " -> " + (byId == null ? "none" : byId.getClass().getSimpleName()));
                checkEq("the work module's job", byProducer.getJobEntry().getKey().toString(), "colony_seasons:decorator");
            }
        });
    }

    /** Both stored paths carry ".blueprint", as MineColonies' own huts have them and as Structurize loads them. */
    private void checkPaths(final String when) {
        final String building = hut.getBlueprintPath();
        final String block = hut.getTileEntity() == null ? null : hut.getTileEntity().getBlueprintPath();
        check("the Workshop's stored blueprint path ends with .blueprint (" + when + ")", building != null && building.endsWith(".blueprint"), "'" + building + "'");
        check("the hut block entity's blueprint path ends with .blueprint (" + when + ")", block != null && block.endsWith(".blueprint"), "'" + block + "'");
        final Blueprint bp = building == null ? null : StructurePacks.getBlueprint(hut.getStructurePack(), building, level.registryAccess());
        check("the stored path loads a blueprint (" + when + ")", bp != null, "pack '" + hut.getStructurePack() + "'");
    }

    /** A hand-built MineColonies hut (a block, registered at level 1) whose job modules hire nobody by themselves. */
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

    private static IAssignsJob jobModule(final IBuilding building, final String jobPath) {
        if (building == null) {
            return null;
        }
        for (final IAssignsJob module : building.getModulesByType(IAssignsJob.class)) {
            if (jobPath == null || module.getJobEntry().getKey().getPath().equals(jobPath)) {
                return module;
            }
        }
        return null;
    }

    private WorkerBuildingModule work() {
        return hut.getModule(DecoratorModules.WORK);
    }

    private ICitizenData spawn(final BlockPos at) {
        final ICitizenData data = colony.getCitizenManager().createAndRegisterCivilianData();
        colony.getCitizenManager().spawnOrCreateCitizen(data, level, at);
        return data;
    }

    /** Sets up an employment that is not under test: a direct assign, as a save would load it. */
    private void employ(final ICitizenData citizen, final IBuilding building, final String jobPath) {
        final IAssignsJob module = jobModule(building, jobPath);
        final boolean ok = module != null && module.assignCitizen(citizen);
        check("set up: " + citizen.getId() + " works at " + label(building) + " (" + jobPath + ")", ok && citizen.getJob() != null
                && citizen.getWorkBuilding() == building, citizen.getJob() == null ? "no job" : citizen.getJob().getJobRegistryEntry().getKey().toString());
    }

    private void farHuts() {
        lumber = stockHut("minecolonies:blockhutlumberjack", center.offset(LUMBER_OFFSET));
        tower = stockHut("minecolonies:blockhutguardtower", center.offset(TOWER_OFFSET));
        final IAssignsJob lumberModule = jobModule(lumber, "lumberjack");
        final IAssignsJob knightModule = jobModule(tower, "knight");
        check("the lumberjack's hut and the guard tower have their job modules", lumberModule != null && knightModule != null,
                "lumberjack " + (lumberModule == null ? "-" : lumberModule.getModuleMax()) + ", knight " + (knightModule == null ? "-" : knightModule.getModuleMax()));
        if (lumberModule == null || knightModule == null) {
            finish();
            return;
        }
        lumberEntry = lumberModule.getJobEntry();
        knightEntry = knightModule.getJobEntry();
        citizenW = spawn(center.offset(-6, 0, -2));
        employ(citizenW, lumber, "lumberjack");
        citizenK = spawn(center.offset(-6, 0, 6));
        employ(citizenK, tower, "knight");
        work().setHiringMode(HiringMode.MANUAL);
        note("set up: W {} at the lumberjack's hut {}, K {} at the guard tower {}; {}", citizenW.getId(), lumber.getID().toShortString(),
                citizenK.getId(), tower.getID().toShortString(), distances());
    }

    // ================================================================ the window's messages

    /**
     * MineColonies' {@link HireFireMessage} as the server receives it: the bytes in the order its protected constructors
     * read them (dimension, colony, building, hire, citizen, module), read by that constructor, written back by its own
     * {@code toBytes} to prove the layout, then run through the public server handler with the owner as the sender.
     */
    private boolean message(final IBuilding building, final boolean hire, final ICitizenData citizen, final int moduleId) {
        try {
            final RegistryFriendlyByteBuf in = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
            in.writeUtf(level.dimension().location().toString());
            in.writeInt(colony.getID());
            in.writeBlockPos(building.getID());
            in.writeBoolean(hire);
            in.writeInt(citizen.getId());
            in.writeInt(moduleId);
            final byte[] sent = new byte[in.readableBytes()];
            in.getBytes(in.readerIndex(), sent);
            final Constructor<HireFireMessage> reader = HireFireMessage.class.getDeclaredConstructor(RegistryFriendlyByteBuf.class, PlayMessageType.class);
            reader.setAccessible(true);
            final HireFireMessage msg = reader.newInstance(in, HireFireMessage.TYPE);
            final Method writer = HireFireMessage.class.getDeclaredMethod("toBytes", RegistryFriendlyByteBuf.class);
            writer.setAccessible(true);
            final RegistryFriendlyByteBuf out = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
            writer.invoke(msg, out);
            final byte[] back = new byte[out.readableBytes()];
            out.getBytes(out.readerIndex(), back);
            if (!Arrays.equals(sent, back)) {
                check("the hire/fire message reads and writes the same bytes", false, sent.length + " vs " + back.length + " bytes");
                return false;
            }
            msg.onExecute(null, owner);
            note("message {} citizen {} at {} (module {}), {}", hire ? "hire" : "fire", citizen.getId(), label(building), moduleId, distances());
            return true;
        } catch (final ReflectiveOperationException | RuntimeException unbuilt) {
            check("the hire/fire message could be built and run", false, unbuilt.toString());
            return false;
        }
    }

    private void hireAtWorkshop(final ICitizenData citizen) {
        message(hut, true, citizen, DecoratorModules.WORK.getRuntimeID());
    }

    private void fireAtWorkshop(final ICitizenData citizen) {
        message(hut, false, citizen, DecoratorModules.WORK.getRuntimeID());
    }

    /** The window's fire at the old hut: the module whose job is the citizen's job (as WindowHireWorker picks it). */
    private void fireAtOldHut(final ICitizenData citizen, final IBuilding old) {
        final IJob<?> job = citizen.getJob();
        IAssignsJob module = null;
        for (final IAssignsJob m : old.getModulesByType(IAssignsJob.class)) {
            if (job != null && m.getJobEntry().equals(job.getJobRegistryEntry())) {
                module = m;
            }
        }
        if (module == null) {
            check("the old hut has the module of the citizen's job", false, label(old));
            return;
        }
        message(old, false, citizen, module.getProducer().getRuntimeID());
    }

    // ================================================================ what /minecolonies citizens info shows

    private enum Want { DECORATOR, JOBLESS, OLD }

    private long listedAtWorkshop(final ICitizenData citizen) {
        return work().getAssignedCitizen().stream().filter(c -> c == citizen).count();
    }

    private static boolean listedAt(final IBuilding building, final ICitizenData citizen) {
        if (building == null) {
            return false;
        }
        for (final IAssignsJob module : building.getModulesByType(IAssignsJob.class)) {
            if (module.hasAssignedCitizen(citizen)) {
                return true;
            }
        }
        return false;
    }

    private static String jobName(final IJob<?> job) {
        return job == null ? "no job" : job.getJobRegistryEntry().getKey().getPath();
    }

    /** The citizen as /minecolonies citizens info reads him, against what this step wants. */
    private void look(final String where, final ICitizenData citizen, final Want want, final IBuilding old, final JobEntry oldEntry) {
        final IJob<?> job = citizen.getJob();
        final IBuilding workAt = citizen.getWorkBuilding();
        final long here = listedAtWorkshop(citizen);
        final boolean atOld = listedAt(old, citizen);
        final Optional<AbstractEntityCitizen> entity = citizen.getEntity();
        final boolean entityAgrees = entity.isEmpty() || entity.get().getCitizenJobHandler().getColonyJob() == job;
        final boolean ok = switch (want) {
            case DECORATOR -> job instanceof JobDecorator && workAt == hut && here == 1 && !atOld;
            case JOBLESS -> job == null && workAt == null && here == 0 && !atOld;
            case OLD -> job != null && job.getJobRegistryEntry().equals(oldEntry) && workAt == old && atOld && here == 0;
        };
        final String wanted = switch (want) {
            case DECORATOR -> "the Decorator at the Workshop";
            case JOBLESS -> "jobless";
            case OLD -> oldEntry.getKey().getPath() + " at " + label(old);
        };
        check(where + ": citizen " + citizen.getId() + " is " + wanted, ok && entityAgrees,
                "job " + jobName(job) + ", works at " + label(workAt) + ", Workshop lists him " + here + "x, " + label(old) + " lists him " + atOld
                        + ", entity " + (entity.isEmpty() ? "absent" : entityAgrees ? "agrees" : "says " + jobName(entity.get().getCitizenJobHandler().getColonyJob())));
    }

    /**
     * An active colony runs its buildings' colony tick (auto-hire among it) every 500 ticks. A colony is active only
     * while a real player stands in it, and MineColonies refuses a fake player as that subscriber, so on the headless rig
     * it stays inactive and nothing would ever hire. While it is inactive the probe runs MineColonies' own building
     * manager tick at that interval: every building in MineColonies' order, every module's own auto-hire code.
     */
    private void colonyClock(final int t) {
        if (t % 500 == 0 && !colony.isActive()) {
            colony.getServerBuildingManager().onColonyTick(colony);
        }
    }

    /** Why auto-hire would or would not run now: the colony's state and MineColonies' own gate for this hut. */
    private void diagnose(final String when) {
        final ICitizenData jobless = colony.getCitizenManager().getJoblessCitizen();
        note("{}: colony state {} active {}, close subscribers {}, can assign {}, can auto-hire {}, jobless citizen {} (child {}), hiring {}",
                when, colony.getState(), colony.isActive(), colony.getPackageManager().getCloseSubscribers().size(), hut.canAssignCitizens(),
                com.minecolonies.core.util.BuildingUtils.canAutoHire(hut, work().getHiringMode(), work().getJobEntry()),
                jobless == null ? "none" : jobless.getId(), jobless != null && jobless.isChild(), work().getHiringMode());
    }

    // ================================================================ one round at a level

    private void round(final int lv) {
        final String at = "L" + lv;
        // a. auto-hire: the Workshop is the only vacancy and one citizen has no job
        step(new Step(at + " auto-hire", AUTO_HIRE_BOUND, true) {
            final List<ICitizenData> jobless = new ArrayList<>();

            @Override
            void start() {
                if (citizenA == null) {
                    citizenA = spawn(center.offset(2, 0, 4));
                }
                for (final ICitizenData c : colony.getCitizenManager().getCitizens()) {
                    if (c.getWorkBuilding() == null) {
                        jobless.add(c);
                    }
                }
                if (lv == 1) {
                    work().setHiringMode(HiringMode.AUTO);
                } else {
                    final BoolSetting autoHire = colony.getSettings().getSetting(BuildingTownHall.AUTO_HIRING_MODE);
                    note("town hall auto-hiring was {}", autoHire.getValue());
                    if (!autoHire.getValue()) {
                        autoHire.trigger();
                    }
                    work().setHiringMode(HiringMode.DEFAULT);
                }
                note("{}: hiring mode {}, town hall auto-hiring {}, jobless {}, citizens {}, Workshop built {} level {}", name, work().getHiringMode(),
                        colony.getSettings().getSetting(BuildingTownHall.AUTO_HIRING_MODE).getValue(),
                        jobless.stream().map(ICitizenData::getId).toList(), colony.getCitizenManager().getCurrentCitizenCount(), hut.isBuilt(), hut.getBuildingLevel());
                diagnose(name);
                // MineColonies ticks its buildings in the order they were added: a new hut comes last, so any older hut
                // with a vacancy takes a jobless citizen first (the in-game colony had several)
                final List<String> order = new ArrayList<>();
                for (final IBuilding b : colony.getServerBuildingManager().getBuildings().values()) {
                    order.add(label(b));
                }
                note("{}: buildings in MineColonies' tick order: {}", name, order);
            }

            @Override
            boolean tick() {
                colonyClock(t);
                final ICitizenData first = work().getFirstCitizen();
                return first != null && first.getJob() instanceof JobDecorator;
            }

            @Override
            void end(final boolean done) {
                if (!done) {
                    diagnose(name + " not hired");
                }
                final ICitizenData hired = work().getFirstCitizen();
                check(at + ": auto-hire (" + work().getHiringMode() + ") gives the Workshop a jobless citizen within " + AUTO_HIRE_BOUND + " ticks",
                        done && hired != null && jobless.contains(hired), done ? "citizen " + hired.getId() + " after " + t + " ticks" : "nobody");
                work().setHiringMode(HiringMode.MANUAL);
                if (hired != null) {
                    citizenA = hired;
                    look(at + " after auto-hire", citizenA, Want.DECORATOR, null, null);
                }
            }
        });
        now(at + " the window's fire and hire", () -> {
            stand(hutPos.offset(0, 0, 3));
            // d. fire at the Workshop
            fireAtWorkshop(citizenA);
            look(at + " fired by the window", citizenA, Want.JOBLESS, null, null);
            checkEq(at + ": the Workshop's list after the fire", work().getAssignedCitizen().size(), 0);
            // b. hire a jobless citizen
            hireAtWorkshop(citizenA);
            look(at + " a jobless citizen hired by the window", citizenA, Want.DECORATOR, null, null);
            // a full Workshop takes nobody and takes nobody from his work
            stand(center.offset(MIDDLE_OFFSET));
            hireAtWorkshop(citizenW);
            look(at + " a hire into the full Workshop", citizenW, Want.OLD, lumber, lumberEntry);
            look(at + " the Decorator stays when the Workshop is full", citizenA, Want.DECORATOR, null, null);
            stand(hutPos.offset(0, 0, 3));
            fireAtWorkshop(citizenA);
            look(at + " fired again", citizenA, Want.JOBLESS, null, null);
        });
        // c. moving an employed citizen: the window's order, fire at the old hut, then hire at the Workshop
        move(at + " near", true, lv == 1);
        move(at + " far", false, lv == 1);
        if (lv == 1) {
            now(at + " control: a far move into a stock hut", () -> {
                stand(hutPos);
                final IAssignsJob controlModule = jobModule(control, "lumberjack");
                fireAtOldHut(citizenK, tower);
                message(control, true, citizenK, controlModule.getProducer().getRuntimeID());
                final boolean moved = citizenK.getWorkBuilding() == control;
                note("NOTE control: a far move of the knight into a MineColonies lumberjack's hut {} (job {}, works at {})",
                        moved ? "worked" : "fails too", jobName(citizenK.getJob()), label(citizenK.getWorkBuilding()));
                if (moved || citizenK.getJob() == null) {
                    controlModule.removeCitizen(citizenK);
                    employ(citizenK, tower, "knight");
                }
            });
        }
    }

    /**
     * One move as the hut window sends it. {@code near}: the owner stands within 15 blocks of both huts; else at the
     * Workshop, 24 blocks from the old hut. {@code lumberNear}: at level 1 the lumberjack moves near and the knight far,
     * at level 5 the other way round. Afterwards the citizen goes back to his old hut (not under test).
     */
    private void move(final String at, final boolean near, final boolean lumberNear) {
        now(at + " move", () -> {
            final boolean lumberMoves = near == lumberNear;
            final ICitizenData who = lumberMoves ? citizenW : citizenK;
            final IBuilding old = lumberMoves ? lumber : tower;
            final JobEntry oldEntry = lumberMoves ? lumberEntry : knightEntry;
            final String kind = lumberMoves ? "lumberjack" : "knight";
            look(at + " before the move", who, Want.OLD, old, oldEntry);
            stand(near ? center.offset(MIDDLE_OFFSET) : hutPos);
            fireAtOldHut(who, old);
            if (near) {
                look(at + " the fire at the old hut arrives (player near it)", who, Want.JOBLESS, old, oldEntry);
            } else {
                look(at + " the fire at the old hut is dropped (player " + Math.round(Math.sqrt(owner.blockPosition().distSqr(old.getID()))) + " blocks away)",
                        who, Want.OLD, old, oldEntry);
            }
            hireAtWorkshop(who);
            look(at + " moved: the " + kind + " is hired at the Workshop", who, Want.DECORATOR, old, oldEntry);
            // back as he was (not under test): fired at the Workshop by the window, employed again at the old hut
            stand(hutPos.offset(0, 0, 3));
            fireAtWorkshop(who);
            if (who.getWorkBuilding() == null) {
                employ(who, old, kind);
            }
            look(at + " back at work after the move", who, Want.OLD, old, oldEntry);
        });
    }

    // ================================================================ the upgrade work order

    private void workOrderSteps() {
        final WorkOrderBuilding[] order = new WorkOrderBuilding[1];
        final Blueprint[] loaded = new Blueprint[1];
        step(new Step("the upgrade work order 1 to 2", 400, false) {
            @Override
            void start() {
                checkEq("the Workshop is at level 1 before the upgrade", hut.getBuildingLevel(), 1);
                final WorkOrderBuilding wo = WorkOrderBuilding.create(WorkOrderType.UPGRADE, hut);
                order[0] = wo;
                checkEq("the upgrade's target level", wo.getTargetLevel(), 2);
                checkEq("the upgrade's blueprint path", wo.getStructurePath(), "decorator/workshop2.blueprint");
                checkEq("the upgrade's pack", wo.getStructurePack(), PACK);
                check("Structurize finds the upgrade's blueprint", StructurePacks.getBlueprint(wo.getStructurePack(), wo.getStructurePath(), level.registryAccess()) != null,
                        wo.getStructurePath());
                colony.getWorkManager().addWorkOrder(wo, false);
                check("the work order is listed by the colony", colony.getWorkManager().getWorkOrder(wo.getID()) == wo, "id " + wo.getID());
                // the Builder loads it through the work order itself
                wo.loadBlueprint(level, bp -> loaded[0] = bp);
            }

            @Override
            boolean tick() {
                return loaded[0] != null;
            }

            @Override
            void end(final boolean done) {
                check("the work order loads its blueprint as the Builder does", done && loaded[0] != null,
                        loaded[0] == null ? "nothing" : loaded[0].getSizeX() + " x " + loaded[0].getSizeY() + " x " + loaded[0].getSizeZ());
                // finished the way the Builder finishes it
                hut.setBuildingLevel(2);
                hut.onUpgradeComplete(loaded[0], 2);
                colony.getWorkManager().removeWorkOrder(order[0]);
                checkEq("the Workshop is at level 2 after the upgrade", hut.getBuildingLevel(), 2);
                checkPaths("after the upgrade");
            }
        });
    }

    private void toLevel(final int lv) {
        now("level " + lv, () -> {
            final Blueprint bp = StructurePacks.getBlueprint(PACK, "decorator/workshop" + lv + ".blueprint", level.registryAccess());
            check("the level " + lv + " blueprint loads", bp != null, "");
            hut.setBuildingLevel(lv);
            hut.onUpgradeComplete(bp, lv);
            checkEq("the Workshop's level", hut.getBuildingLevel(), lv);
        });
    }

    // ================================================================ the calendar page

    private DecoratorInfoModule.Info page(final DecoratorInfoModule module) {
        final RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        module.serializeToView(buf);
        final DecoratorInfoModuleView view = new DecoratorInfoModuleView();
        view.deserialize(buf);
        return view.info();
    }

    private void calendarSteps() {
        now("the calendar page", () -> {
            final DecoratorInfoModule module = hut.getModule(DecoratorModules.INFO);
            final boolean ticking = module instanceof ITickingModule;
            check("the calendar page's module ticks with the colony", ticking, module.getClass().getSimpleName());
            if (!ticking) {
                return;
            }
            final ITickingModule tick = (ITickingModule) module;
            tick.onColonyTick(colony);
            module.clearDirty();
            tick.onColonyTick(colony);
            check("a colony tick with nothing changed does not mark the page", !module.isDirty, "");

            final Season was = CalendarManager.current().season().season();
            final Season other = Season.byOrdinal((was.ordinal() + 1) % 4);
            CalendarManager.setSeason(server, other);
            tick.onColonyTick(colony);
            check("the first colony tick after a season change marks the page", module.isDirty, was + " -> " + other);
            DecoratorInfoModule.Info shown = page(module);
            checkEq("the page shows the new season", shown.season(), other.ordinal());
            checkEq("the page shows today's day", shown.dayOfYear(), CalendarManager.current().season().dayOfYear());
            module.clearDirty();
            tick.onColonyTick(colony);
            check("the next tick with nothing changed leaves it", !module.isDirty, "");

            final int dayBefore = CalendarManager.current().season().dayOfYear();
            level.setDayTime(level.getDayTime() + 24000);
            CalendarManager.refresh(server);
            final int dayAfter = CalendarManager.current().season().dayOfYear();
            tick.onColonyTick(colony);
            check("a new day marks the page", module.isDirty, "day " + dayBefore + " -> " + dayAfter);
            shown = page(module);
            checkEq("the page shows the new day", shown.dayOfYear(), dayAfter);
            module.clearDirty();
        });
    }

    // ================================================================ the modes

    private void planDefault() {
        pasteSteps();
        now("the far huts", () -> {
            farHuts();
            control = stockHut("minecolonies:blockhutlumberjack", center.offset(CONTROL_OFFSET));
            note("Workshop {}, {}", hutPos.toShortString(), distances());
        });
        round(1);
        workOrderSteps();
        toLevel(5);
        round(5);
        calendarSteps();
        hiringModeSteps();
        now("the end", () -> note("citizens at the end: {}", colony.getCitizenManager().getCurrentCitizenCount()));
    }

    // ================================================================ the old hut's hiring mode (S10c)

    private static String modes(final IBuilding building) {
        final List<String> out = new ArrayList<>();
        for (final IAssignsCitizen module : building.getModulesByType(IAssignsCitizen.class)) {
            out.add(String.valueOf(module.getHiringMode()));
        }
        return String.join(",", out);
    }

    /**
     * The window's hire of a citizen who works elsewhere, with the player at the Workshop (the fire at the old hut is
     * dropped, so the Workshop's hire takes him off it): the old hut keeps its hiring mode, whatever it was, right after
     * the hire and after the colony's building ticks (its auto-hire among them). The in-game test found the old hut on
     * Manual after such a hire.
     */
    private void hiringModeSteps() {
        now("the old hut keeps its hiring mode after a window hire", () -> {
            for (final HiringMode mode : List.of(HiringMode.DEFAULT, HiringMode.AUTO, HiringMode.MANUAL)) {
                look("hiring mode " + mode + ": before", citizenW, Want.OLD, lumber, lumberEntry);
                for (final IAssignsCitizen module : lumber.getModulesByType(IAssignsCitizen.class)) {
                    module.setHiringMode(mode);
                }
                final String before = modes(lumber);
                stand(hutPos);
                fireAtOldHut(citizenW, lumber);
                hireAtWorkshop(citizenW);
                look("hiring mode " + mode + ": hired at the Workshop from far away", citizenW, Want.DECORATOR, lumber, lumberEntry);
                checkEq("hiring mode " + mode + ": the lumberjack's hut keeps it right after the hire", modes(lumber), before);
                for (int i = 0; i < 3; i++) {
                    colony.getServerBuildingManager().onColonyTick(colony);
                }
                checkEq("hiring mode " + mode + ": and after three colony ticks", modes(lumber), before);
                // back as it was (not under test): fired at the Workshop, whoever auto-hire put in his place let go, employed again
                stand(hutPos.offset(0, 0, 3));
                fireAtWorkshop(citizenW);
                final IAssignsJob lumberModule = jobModule(lumber, "lumberjack");
                for (final ICitizenData other : new ArrayList<>(lumberModule.getAssignedCitizen())) {
                    if (other != citizenW) {
                        lumberModule.removeCitizen(other);
                    }
                }
                if (citizenW.getWorkBuilding() == null) {
                    employ(citizenW, lumber, "lumberjack");
                }
            }
            for (final IAssignsCitizen module : lumber.getModulesByType(IAssignsCitizen.class)) {
                module.setHiringMode(HiringMode.MANUAL);
            }
        });
    }

    private void planRestartSave() {
        pasteSteps();
        toLevel(5);
        now("save: the knight moved from far away", () -> {
            farHuts();
            look("save: before the move", citizenK, Want.OLD, tower, knightEntry);
            stand(hutPos);
            fireAtOldHut(citizenK, tower);
            look("save: the fire at the guard tower is dropped", citizenK, Want.OLD, tower, knightEntry);
            hireAtWorkshop(citizenK);
            look("save: the knight is hired at the Workshop", citizenK, Want.DECORATOR, tower, knightEntry);
            look("save: the lumberjack still works", citizenW, Want.OLD, lumber, lumberEntry);
            checkPaths("before the save");
            final Properties ids = new Properties();
            ids.setProperty("decorator", Integer.toString(citizenK.getId()));
            ids.setProperty("lumberjack", Integer.toString(citizenW.getId()));
            try {
                final Path file = server.getWorldPath(LevelResource.ROOT).resolve(IDS_FILE);
                try (var out = Files.newBufferedWriter(file, StandardCharsets.US_ASCII)) {
                    ids.store(out, "cshire restart");
                }
            } catch (final java.io.IOException unwritten) {
                check("the citizen ids are written for the restart", false, unwritten.getClass().getSimpleName());
            }
            note("saving: colony {}, Workshop {} level {}, Decorator {}, lumberjack {}", colony.getID(), hutPos.toShortString(),
                    hut.getBuildingLevel(), citizenK.getId(), citizenW.getId());
            check("the world saved", server.saveEverything(false, true, true), "");
        });
    }

    private void planRestartLoad() {
        step(new Step("load: the colony comes back", 2400, false) {
            @Override
            boolean tick() {
                final IColony found = IColonyManager.getInstance().getColonyByWorld(1, level);
                if (found == null) {
                    return false;
                }
                for (final IBuilding b : found.getServerBuildingManager().getBuildings().values()) {
                    if (b instanceof BuildingDecorator d && !d.getModule(DecoratorModules.WORK).getAssignedCitizen().isEmpty()) {
                        colony = found;
                        hut = d;
                        return t >= 40;
                    }
                }
                return false;
            }

            @Override
            void end(final boolean done) {
                check("the colony and its Workshop with a worker are back", done, colony == null ? "no colony" : "colony " + colony.getID());
                if (!done) {
                    finish();
                    return;
                }
                hutPos = hut.getPosition();
                center = colony.getCenter();
                owner = FakePlayerFactory.getMinecraft(level);
                colony.getPackageManager().addCloseSubscriber(owner);
                lumber = colony.getServerBuildingManager().getBuilding(center.offset(LUMBER_OFFSET));
                tower = colony.getServerBuildingManager().getBuilding(center.offset(TOWER_OFFSET));
                check("the lumberjack's hut and the guard tower are back", lumber != null && tower != null, "");
                checkEq("the Workshop's level after the restart", hut.getBuildingLevel(), 5);
                final Properties ids = new Properties();
                try (var in = Files.newBufferedReader(server.getWorldPath(LevelResource.ROOT).resolve(IDS_FILE), StandardCharsets.US_ASCII)) {
                    ids.load(in);
                } catch (final java.io.IOException unread) {
                    check("the citizen ids of the first run are there", false, unread.getClass().getSimpleName());
                    finish();
                    return;
                }
                citizenK = colony.getCitizenManager().getCivilian(Integer.parseInt(ids.getProperty("decorator", "-1")));
                citizenW = colony.getCitizenManager().getCivilian(Integer.parseInt(ids.getProperty("lumberjack", "-1")));
                check("both citizens are back", citizenK != null && citizenW != null, ids.toString());
                if (citizenK == null || citizenW == null || lumber == null || tower == null) {
                    finish();
                    return;
                }
                lumberEntry = jobModule(lumber, "lumberjack").getJobEntry();
                knightEntry = jobModule(tower, "knight").getJobEntry();
                look("load: the moved knight", citizenK, Want.DECORATOR, tower, knightEntry);
                checkEq("load: the Workshop lists one worker", work().getAssignedCitizen().size(), 1);
                look("load: the lumberjack", citizenW, Want.OLD, lumber, lumberEntry);
                checkPaths("after the restart");
            }
        });
    }
}
