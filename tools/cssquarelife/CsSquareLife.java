package me.lovkar.seasons.cssquarelife;

import com.ldtteam.structurize.api.RotationMirror;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.placement.AbstractBlueprintIterator;
import com.ldtteam.structurize.placement.StructurePhasePlacementResult;
import com.ldtteam.structurize.placement.StructurePlacer;
import com.ldtteam.structurize.placement.structure.CreativeStructureHandler;
import com.ldtteam.structurize.storage.StructurePacks;
import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.HiringMode;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.modules.IAssignsCitizen;
import com.minecolonies.api.colony.buildings.modules.IAssignsJob;
import com.minecolonies.api.colony.buildings.registry.IBuildingDataManager;
import com.minecolonies.api.colony.workorders.WorkOrderType;
import com.minecolonies.api.eventbus.events.colony.buildings.BuildingConstructionModEvent;
import com.minecolonies.api.tileentities.AbstractTileEntityColonyBuilding;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import com.minecolonies.core.colony.buildings.modules.settings.BoolSetting;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingTownHall;
import com.minecolonies.core.colony.workorders.WorkOrderBuilding;
import com.minecolonies.core.placementhandlers.main.SurvivalHandler;
import com.mojang.authlib.GameProfile;
import me.lovkar.seasons.block.FestivalMarkerBlock;
import me.lovkar.seasons.block.SeasonsBlocks;
import me.lovkar.seasons.calendar.CalendarManager;
import me.lovkar.seasons.calendar.DateSource;
import me.lovkar.seasons.colony.BuildingDecorator;
import me.lovkar.seasons.colony.BuildingTownSquare;
import me.lovkar.seasons.colony.DecoratorModules;
import me.lovkar.seasons.colony.SquareAssignmentModule;
import me.lovkar.seasons.colony.TownSquareSetup;
import me.lovkar.seasons.decor.DecorEvents;
import me.lovkar.seasons.decor.DecorLedger;
import me.lovkar.seasons.decor.DecorPlacer;
import me.lovkar.seasons.decor.DecorWork;
import me.lovkar.seasons.decor.Squares;
import me.lovkar.seasons.festival.Gathering;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Headless check of the Town Square's lifecycle (0.2.0, chunk Q2, docs/town-square-design.md 4.3, 4.6 to 4.8 and the
 * points 3, 6, 7 and 13 of section 8): one centrepiece per colony ({@code decor.Squares}), the ledger's format version 3,
 * work orders, removal and the switch from a 0.1.0 festival marker.
 *
 * <p>The colony: a town hall, a Builder's hut with a builder (citizen data only, except in mode builder, so no Builder
 * walks and builds: every work order is MineColonies' own, made through the square's {@code requestRepair} or
 * {@code requestRemoval} or the work manager, and the probe does what the Builder would do), a Decorator's Workshop
 * pasted with the command a player types and its Decorator (citizen data only, so his AI never runs and nothing is
 * placed by him), a festival marker put down by the colony's owner, and Town Squares placed through MineColonies'
 * survival placement handler and built by what the Builder does when he finishes (bytecode order: the order goes,
 * {@code onUpgradeComplete}, the new level, the construction event). Decorations are seeded by the probe: each cell
 * recorded in the ledger before its block is set, our trunk and vanilla spruce logs and leaves. The date is fixed in
 * July (no decoration window), so nothing is put up.</p>
 *
 * <p>Modes: none (everything above), {@code builder} (section 8 point 7 with the Builder's own AI at work on the square's
 * REPAIR orders, {@link #planBuilder}), {@code restart-save} then {@code restart-load} (tools/restart.sh: a switch stopped
 * half way). Prints "[cssquarelife] CHECK ...: OK|FAIL" and "[cssquarelife] RESULT cssquarelife: PASS|FAIL", then halts.
 * The two words the rig greps for stay out of the details.</p>
 */
@Mod("colonyseasonssquarelife")
public class CsSquareLife {
    private static final Logger LOGGER = LoggerFactory.getLogger("cssquarelife");
    private static final int SETUP_TICK = 60;
    private static final long WATCHDOG_SECONDS = 900;
    private static final LocalDate OFF_SEASON = LocalDate.of(2026, 7, 15);
    private static final String PACK = "Colony Seasons";
    private static final String SQUARE_PATH = "square/townsquare1.blueprint";
    private static final int FORCE = 5;
    private static final int FILE_COLONY = 7;
    private static final String IDS_FILE = "cssquarelife-ids.properties";
    /** Seeding sets blocks without neighbour or shape updates (the leaves keep their distance). */
    private static final int SEED_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private static final BlockPos W1_OFFSET = new BlockPos(14, 0, 0);
    private static final BlockPos BUILDER_OFFSET = new BlockPos(-14, 0, 0);
    private static final BlockPos SLOT_A = new BlockPos(0, 0, -40);
    private static final BlockPos SLOT_B = new BlockPos(0, 0, 40);
    private static final BlockPos SLOT_C = new BlockPos(40, 0, 40);
    private static final BlockPos SLOT_D = new BlockPos(-40, 0, 40);
    private static final BlockPos SLOT_E = new BlockPos(-40, 0, -40);
    private static final BlockPos MARKER_1 = new BlockPos(30, 0, -20);
    private static final BlockPos ORPHAN = new BlockPos(30, 0, 20);
    private static final BlockPos COLONY_2 = new BlockPos(300, 0, 2);

    private static final BlockState LOG = Blocks.SPRUCE_LOG.defaultBlockState();
    private static final BlockState LEAVES = Blocks.SPRUCE_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true)
            .setValue(LeavesBlock.DISTANCE, 1);

    private int ticks;
    private boolean failed;
    private boolean finished;
    private final String mode;
    private long setupNanos;

    private MinecraftServer server;
    private ServerLevel level;
    private ServerPlayer owner;
    private IColony colony;
    private BlockPos center;
    private BuildingDecorator w1;
    private IBuilding builderHut;
    /** The Builder's citizen data (an entity only in mode builder). */
    private ICitizenData builder;
    private ICitizenData d1;
    private Blueprint squareBlueprint;
    private BlockPos m1;
    private BuildingTownSquare s1;
    private BuildingTownSquare s2;
    private BuildingTownSquare s3;
    private BuildingTownSquare s4;
    private Staff baseline;

    private final List<Step> steps = new ArrayList<>();
    private int stepIndex;
    /** restart-load: the ledger file as it was on disk in the first server tick. */
    private CompoundTag firstFile;

    public CsSquareLife() {
        mode = readMode();
        // the calendar never sees the real date: in July no window is open, so no decorating, and a sweep's rules are fixed
        CalendarManager.setDateSource(DateSource.fixed(OFF_SEASON));
        NeoForge.EVENT_BUS.addListener(this::onTick);
    }

    // ================================================================ logging and steps

    private void check(final String what, final boolean ok, final String detail) {
        LOGGER.info("[cssquarelife] CHECK {}: {} {}", what, ok ? "OK" : "FAIL", detail);
        failed |= !ok;
    }

    private void checkEq(final String what, final Object actual, final Object expected) {
        check(what, Objects.equals(actual, expected), "got " + actual + (Objects.equals(actual, expected) ? "" : ", wanted " + expected));
    }

    private static void note(final String text, final Object... args) {
        LOGGER.info("[cssquarelife] " + text, args);
    }

    private void onTick(final ServerTickEvent.Post event) {
        if (finished) {
            return;
        }
        ticks++;
        server = event.getServer();
        try {
            if (ticks == 1 && "restart-load".equals(mode)) {
                // the ledger file as the first run left it, read before any sweep of this run (the rig sweeps every 100 ticks)
                try {
                    firstFile = NbtIo.readCompressed(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(DecorLedger.FILE + ".dat"),
                            NbtAccounter.unlimitedHeap());
                    note("the ledger file read in the first tick, game time {}", server.overworld().getGameTime());
                } catch (final java.io.IOException unread) {
                    firstFile = null;
                }
            }
            if (ticks == SETUP_TICK) {
                note("mode '{}'", mode);
                setupNanos = System.nanoTime();
                level = server.overworld();
                CalendarManager.refresh(server);
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
            LOGGER.info("[cssquarelife] trace: {}", Arrays.toString(Arrays.copyOf(t.getStackTrace(), Math.min(12, t.getStackTrace().length))));
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
            step.startTick = ticks;
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
        LOGGER.info("[cssquarelife] RESULT cssquarelife: {}", failed ? "FAIL" : "PASS");
        if (server != null) {
            server.halt(false);
        }
    }

    private static String readMode() {
        try {
            final Path file = Path.of("probe-mode.txt");
            return Files.exists(file) ? Files.readString(file, StandardCharsets.US_ASCII).trim() : "";
        } catch (final java.io.IOException unread) {
            return "";
        }
    }

    private abstract static class Step {
        final String name;
        final int maxTicks;
        boolean started;
        int t;
        int startTick;

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
            case "builder" -> planBuilder();
            case "restart-save" -> planRestartSave();
            case "restart-load" -> planRestartLoad();
            default -> {
                check("known mode", false, "'" + mode + "'");
                finish();
            }
        }
    }

    // ================================================================ small helpers

    private DecorLedger ledger() {
        return DecorLedger.get(level);
    }

    private int cid() {
        return colony.getID();
    }

    private int size(final int colonyId, final DecorLedger.Owner owner) {
        final DecorLedger.RoomMark m = ledger().roomMark(colonyId, owner);
        return m == null ? 0 : m.size();
    }

    private int size(final DecorLedger.Owner owner) {
        return size(cid(), owner);
    }

    private static DecorLedger.Owner sq(final BlockPos anchor) {
        return DecorLedger.Owner.square(anchor);
    }

    private BlockPos anchorOf(final BuildingTownSquare square) {
        final List<BlockPos> cells = square.getLocationsFromTag(BuildingTownSquare.TAG_TREE);
        return cells.isEmpty() ? null : cells.get(0).immutable();
    }

    /** Every item in the Workshop and on the ground round it (an overflow is dropped at the hut). */
    private int workshopItems() {
        int total = BuildingDecorator.countIn(w1.getItemHandlerCap(), s -> true);
        for (final ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, new AABB(w1.getPosition()).inflate(12))) {
            total += drop.getItem().getCount();
        }
        return total;
    }

    private static boolean leafDistanceOnly(final BlockState listed, final BlockState now) {
        return listed.getBlock() == now.getBlock() && listed.getBlock() instanceof LeavesBlock
                && now.setValue(LeavesBlock.DISTANCE, listed.getValue(LeavesBlock.DISTANCE)) == listed;
    }

    /** The items the removal rule gives back for a group as the world stands now: paid entries whose block is still theirs. */
    private int refundNow(final int colonyId, final DecorLedger.Owner owner) {
        final DecorLedger.GroupView group = ledger().group(colonyId, owner);
        int n = 0;
        if (group != null) {
            for (final Map.Entry<BlockPos, DecorLedger.Entry> e : group.entries().entrySet()) {
                final BlockState now = level.getBlockState(e.getKey());
                if (e.getValue().paid() && DecorPlacer.itemOf(e.getValue().state()) != null
                        && (now == e.getValue().state() || leafDistanceOnly(e.getValue().state(), now))) {
                    n++;
                }
            }
        }
        return n;
    }

    /**
     * Cells round {@code base}: dx and dz in -r..r, dy 0..h-1, the base cell itself left out (it stays air, as a tree
     * piece's anchor does). Columns by dx: a spruce log, leaves, our trunk, leaves, ...: every leaf has a log beside it,
     * so its distance is 1 and stays 1.
     */
    private static Map<BlockPos, BlockState> tree(final BlockPos base, final int r, final int h) {
        final BlockState trunk = SeasonsBlocks.FESTIVAL_TRUNK.get().defaultBlockState();
        final Map<BlockPos, BlockState> out = new LinkedHashMap<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = 0; dy < h; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    final int i = Math.floorMod(dx + r, 4);
                    out.put(base.offset(dx, dy, dz), i == 0 ? LOG : i == 2 ? trunk : LEAVES);
                }
            }
        }
        return out;
    }

    /** Seeds a group: every cell recorded before its block is set; every other one paid (all when {@code allPaid}). */
    private void seed(final String what, final int colonyId, final DecorLedger.Owner owner, final Map<BlockPos, BlockState> cells, final boolean allPaid) {
        int i = 0;
        int refused = 0;
        final DecorLedger ledger = ledger();
        for (final Map.Entry<BlockPos, BlockState> e : cells.entrySet()) {
            final boolean paid = allPaid || i++ % 2 == 0;
            if (!ledger.record(colonyId, owner, "", 1, e.getKey(), e.getValue(), paid)) {
                refused++;
            }
            level.setBlock(e.getKey(), e.getValue(), SEED_FLAGS);
        }
        ledger.flush(level);
        check(what + ": " + cells.size() + " cells seeded (each listed before its block was set)", refused == 0 && size(colonyId, owner) == cells.size(),
                refused + " refused, " + size(colonyId, owner) + " listed");
    }

    private List<BlockPos> notAir(final Iterable<BlockPos> cells) {
        final List<BlockPos> out = new ArrayList<>();
        for (final BlockPos p : cells) {
            if (!level.getBlockState(p).isAir()) {
                out.add(p);
            }
        }
        return out;
    }

    private Squares.Square resolve() {
        return Squares.of(level, colony, ledger());
    }

    private String label(final IBuilding b) {
        if (b == null) {
            return "none";
        }
        if (b == s1) {
            return "S1";
        }
        if (b == s2) {
            return "S2";
        }
        if (b == s3) {
            return "S3";
        }
        if (b == s4) {
            return "S4";
        }
        return b.getClass().getSimpleName() + "@" + b.getID().toShortString();
    }

    private String label(final BlockPos hut) {
        return hut == null ? "none" : label(colony.getServerBuildingManager().getBuilding(hut));
    }

    private String show(final Squares.Square s) {
        return s.source() + (s.source() == Squares.Source.BUILDING ? " " + label(s.building()) : "") + " anchor "
                + (s.anchor() == null ? "-" : s.anchor().toShortString()) + (s.located() ? "" : " unlocated") + (s.busy() ? " busy" : "");
    }

    // ================================================================ the world and the colony

    private void quietWorld() {
        server.setDifficulty(Difficulty.PEACEFUL, true);
        final GameRules rules = server.getGameRules();
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        rules.getRule(GameRules.RULE_RANDOMTICKING).set(0, server);
        rules.getRule(GameRules.RULE_DOFIRETICK).set(false, server);
        level.setWeatherParameters(24000 * 20, 0, false, false);
    }

    private void force(final BlockPos at, final int radius, final boolean on) {
        final int cx0 = at.getX() >> 4;
        final int cz0 = at.getZ() >> 4;
        for (int cx = cx0 - radius; cx <= cx0 + radius; cx++) {
            for (int cz = cz0 - radius; cz <= cz0 + radius; cz++) {
                level.setChunkForced(cx, cz, on);
            }
        }
    }

    private void buildColony() {
        final int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, 2, 2);
        center = new BlockPos(2, y, 2);
        force(center, FORCE, true);
        owner = FakePlayerFactory.getMinecraft(level);
        stand(center);
        colony = IColonyManager.getInstance().createColony(level, center, owner, "Seasons Square Life", "Medieval Oak");
        check("a colony was made", colony != null, colony == null ? "-" : "id " + colony.getID() + " at " + center.toShortString());
        stockHut(colony, "minecolonies:blockhuttownhall", center);
        final BoolSetting autoHire = colony.getSettings().getSetting(BuildingTownHall.AUTO_HIRING_MODE);
        if (autoHire.getValue()) {
            autoHire.trigger();
        }
    }

    private void stand(final BlockPos at) {
        owner.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
    }

    private IBuilding stockHut(final IColony c, final String hutId, final BlockPos at) {
        final Block block = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(hutId)).orElse(null);
        if (block == null) {
            check("hut block " + hutId + " exists", false, "");
            return null;
        }
        level.setBlock(at, block.defaultBlockState(), Block.UPDATE_ALL);
        final BlockEntity be = level.getBlockEntity(at);
        IBuilding building = c.getServerBuildingManager().getBuilding(at);
        if (building == null && be instanceof AbstractTileEntityColonyBuilding te) {
            building = c.getServerBuildingManager().addNewBuilding(te, level);
        }
        if (building == null) {
            check("building " + hutId + " registers", false, "at " + at.toShortString());
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
            if (module.getJobEntry().getKey().getPath().equals(jobPath)) {
                return module;
            }
        }
        return null;
    }

    private static WorkerBuildingModule work(final BuildingDecorator hut) {
        return hut.getModule(DecoratorModules.WORK);
    }

    /** A citizen in MineColonies' data only (no entity, so no AI of his runs), an adult. */
    private ICitizenData dataCitizen(final IColony c) {
        final ICitizenData data = c.getCitizenManager().createAndRegisterCivilianData();
        data.setIsChild(false);
        return data;
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

    private void pasteWorkshop() {
        steps.add(new Step("setup: the Workshop pasted", 800) {
            BlockPos at;

            @Override
            void start() {
                at = center.offset(W1_OFFSET);
                owner.setGameMode(GameType.CREATIVE);
                stand(at.offset(0, 0, 3));
                final Replies replies = command("structurize paste " + at.getX() + " " + at.getY() + " " + at.getZ()
                        + " Colony.Seasons decorator.workshop1 NONE true");
                check("setup: the paste command is accepted", replies.said.stream().anyMatch(s -> s.contains("paste.success")), String.valueOf(replies.said));
            }

            @Override
            boolean tick() {
                final IBuilding found = colony.getServerBuildingManager().getBuilding(at);
                if (found instanceof BuildingDecorator hut && hut.getTileEntity() != null && !hut.getTileEntity().getSchematicName().isEmpty()) {
                    w1 = hut;
                }
                return t >= 100 && w1 != null;
            }

            @Override
            void end(final boolean done) {
                check("setup: the Workshop is there with its hut data", done && w1 != null, "at " + at.toShortString());
                if (w1 == null) {
                    finish();
                    return;
                }
                work(w1).setHiringMode(HiringMode.MANUAL);
            }
        });
    }

    private void setupSteps(final boolean builder) {
        now("setup: the colony", () -> {
            quietWorld();
            checkEq("setup: no decoration window is open in July", me.lovkar.seasons.decor.DecorPhase.windowOpen(CalendarManager.current()), false);
            check("setup: MineColonies' event bus subscriptions are in and the colony manager counts as loaded",
                    DecorEvents.subscribed() && DecorEvents.managerLoaded(), "");
            buildColony();
            squareBlueprint = StructurePacks.getBlueprint(PACK, SQUARE_PATH, level.registryAccess());
            check("setup: the square's blueprint loads", squareBlueprint != null, SQUARE_PATH);
            if (builder) {
                builderHut = stockHut(colony, "minecolonies:blockhutbuilder", center.offset(BUILDER_OFFSET));
                final IAssignsJob m = jobModule(builderHut, "builder");
                final ICitizenData b = dataCitizen(colony);
                this.builder = b;
                check("setup: a builder works at the Builder's hut (MineColonies' work orders need one)", m != null && m.assignCitizen(b)
                        && b.getWorkBuilding() == builderHut, b.getJob() == null ? "no job" : b.getJob().getJobRegistryEntry().getKey().toString());
            }
        });
        pasteWorkshop();
        now("setup: the Decorator", () -> {
            d1 = dataCitizen(colony);
            check("setup: the Decorator is hired at the Workshop", work(w1).assignCitizen(d1) && d1.getWorkBuilding() == w1
                    && d1.getJob() instanceof me.lovkar.seasons.colony.JobDecorator, "");
            check("setup: the Workshop starts empty", workshopItems() == 0, workshopItems() + " items");
        });
    }

    /** A Town Square placed as a player places it: MineColonies' survival placement handler with the hut item. */
    private BuildingTownSquare placeSquare(final String name, final IColony c, final ServerPlayer by, final BlockPos at) {
        by.setGameMode(GameType.SURVIVAL);
        by.getInventory().clearContent();
        by.getInventory().add(new ItemStack(TownSquareSetup.HUT_ITEM.get()));
        by.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 3.5);
        String why = "";
        try {
            new SurvivalHandler().handle(squareBlueprint, PACK, SQUARE_PATH, false, level, by, at, RotationMirror.NONE);
        } catch (final Throwable t) {
            why = t.toString().replace("Exception", "Exc");
        }
        final IBuilding b = c.getServerBuildingManager().getBuilding(at);
        check(name + ": placed in survival through MineColonies' placement handler", b instanceof BuildingTownSquare && b.getBuildingLevel() == 0,
                b == null ? "none " + why : b.getClass().getSimpleName() + " level " + b.getBuildingLevel());
        by.setGameMode(GameType.CREATIVE);
        if (b instanceof BuildingTownSquare square) {
            square.assignment().setHiringMode(HiringMode.MANUAL);
            return square;
        }
        return null;
    }

    /**
     * A built square's blueprint tags are read a little later: MineColonies loads the blueprint off the server thread
     * (see {@link #finishBuild}) and hands its data to the hut's block entity in the building's colony tick. The rig's
     * colony never becomes active, so this step runs that building tick itself, as cssquare does; after 100 ticks it
     * asks for the schematic data once more.
     */
    private void waitTags(final String name, final java.util.function.Supplier<BuildingTownSquare> square) {
        steps.add(new Step(name + ": its tags are read", 400) {
            boolean asked;

            @Override
            boolean tick() {
                final BuildingTownSquare sq = square.get();
                if (sq == null || anchorOf(sq) != null) {
                    return true;
                }
                sq.onColonyTick(sq.getColony());
                if (t == 100 && !asked) {
                    asked = true;
                    sq.safeUpdateTEDataFromSchematic();
                }
                return anchorOf(sq) != null;
            }

            @Override
            void end(final boolean done) {
                final BuildingTownSquare sq = square.get();
                check(name + ": its tags are read (" + (asked ? "after a second schematic request" : "in a building tick") + ")",
                        sq != null && anchorOf(sq) != null, "after " + t + " ticks");
                if (sq == null || anchorOf(sq) == null) {
                    finish();
                }
            }
        });
    }

    /**
     * What MineColonies' Builder does when he finishes a work order on a building (AbstractEntityAIStructureWithWorkOrder,
     * the path for a hut whose block entity has its schematic name): the order, with its blueprint, is completed (its
     * {@code onCompleted} hands the blueprint's block-entity data, the tags among it, to the hut) and goes, then
     * {@code onUpgradeComplete} with the target level, then the new level, then the construction event on MineColonies'
     * bus. Returns the mark right after {@code onUpgradeComplete} (before the level is set).
     */
    private DecorLedger.SquareMark finishBuild(final BuildingTownSquare square, final WorkOrderType type, final int target) {
        final IColony c = square.getColony();
        final WorkOrderBuilding wo = WorkOrderBuilding.create(type, square);
        c.getWorkManager().addWorkOrder(wo, false);
        wo.setBlueprint(squareBlueprint, level);
        wo.onCompleted(c, null);
        c.getWorkManager().removeWorkOrder(wo);
        square.onUpgradeComplete(squareBlueprint, target);
        final DecorLedger.SquareMark early = ledger().squareMark(c.getID());
        square.setBuildingLevel(target);
        square.markDirty();
        IMinecoloniesAPI.getInstance().getEventBus().post(new BuildingConstructionModEvent(square, wo));
        return early;
    }

    /** The resolver runs for this square (the hook the construction event and onUpgradeComplete use). */
    private void locate(final BuildingTownSquare square) {
        DecorEvents.squareChanged(square);
        DecorEvents.drain(server);
    }

    /**
     * Sets a square's level directly, without a work order (and so without its blueprint's blocks): only to make one square
     * outrank another. The real upgrade is worked in mode builder, by MineColonies' own Builder.
     */
    private void forceLevel(final IBuilding building, final int lv) {
        try {
            final java.lang.reflect.Field f = com.minecolonies.core.colony.buildings.AbstractSchematicProvider.class.getDeclaredField("buildingLevel");
            f.setAccessible(true);
            f.setInt(building, lv);
        } catch (final ReflectiveOperationException unreachable) {
            check("the level of " + label(building) + " can be set to " + lv, false, unreachable.getClass().getSimpleName());
        }
    }

    /** Waits until the game time is {@code phase} past one of the rig's own sweeps (every 100 ticks). */
    private void waitPhase(final String name, final int phase) {
        steps.add(new Step(name, 2 * DecorEvents.SWEEP_INTERVAL + 5) {
            @Override
            boolean tick() {
                return level.getGameTime() % DecorEvents.SWEEP_INTERVAL == phase;
            }
        });
    }

    private WorkOrderBuilding orderAt(final BuildingTownSquare square) {
        for (final WorkOrderBuilding wo : square.getColony().getWorkManager().getWorkOrdersOfType(WorkOrderBuilding.class)) {
            if (wo.getLocation().equals(square.getID())) {
                return wo;
            }
        }
        return null;
    }

    private void placeMarker(final IColony c, final ServerPlayer by, final BlockPos at) {
        final BlockState state = SeasonsBlocks.FESTIVAL_MARKER.get().defaultBlockState().setValue(FestivalMarkerBlock.FACING, Direction.NORTH);
        level.setBlock(at, state, Block.UPDATE_ALL);
        SeasonsBlocks.FESTIVAL_MARKER.get().setPlacedBy(level, at, state, by, new ItemStack(SeasonsBlocks.FESTIVAL_MARKER_ITEM.get()));
    }

    private List<String> decorList() {
        return command("colonyseasons decor list " + cid()).said;
    }

    // ================================================================ step 1: the ledger's files and the 0.1.0 reader

    private static CompoundTag group(final String kind, final BlockPos pos, final List<BlockState> palette, final long[] positions,
                                     final int[] states, final byte[] paid) {
        final CompoundTag g = new CompoundTag();
        g.putString("kind", kind);
        g.putLong("pos", pos.asLong());
        g.putString("festival", "colony_seasons:winter_festival");
        g.putInt("level", 3);
        g.putString("piece", "");
        g.putLong("decoratedDay", 11);
        g.putLong("tendedDay", 12);
        g.putLong("treeDay", -1);
        g.putBoolean("removing", false);
        final ListTag p = new ListTag();
        for (final BlockState s : palette) {
            p.add(NbtUtils.writeBlockState(s));
        }
        g.put("palette", p);
        g.putLongArray("positions", positions);
        g.putIntArray("states", states);
        g.putByteArray("paid", paid);
        return g;
    }

    private static final BlockPos FILE_HUT = new BlockPos(4000, 70, 4000);
    private static final BlockPos FILE_MARKER = new BlockPos(4020, 70, 4000);
    private static final BlockPos FILE_SQUARE = new BlockPos(4040, 70, 4000);
    private static final BlockPos FILE_ANCHOR = new BlockPos(4038, 70, 3992);

    /** A ledger file as a build of {@code version} writes it ({@code -1}: no version at all), with or without a storeroom. */
    private static CompoundTag file(final int version, final boolean room) {
        final BlockState log = LOG;
        final BlockState leaves = LEAVES;
        final CompoundTag one = new CompoundTag();
        one.putInt("id", FILE_COLONY);
        one.putLong("budgetDay", 12);
        one.putInt("budgetUsed", 2);
        final ListTag charged = new ListTag();
        final CompoundTag c = new CompoundTag();
        c.putString("kind", "BUILDING");
        c.putLong("pos", FILE_HUT.asLong());
        charged.add(c);
        one.put("charged", charged);
        one.putLong("marker", FILE_MARKER.asLong());
        one.putLong("noticeDay", -1);
        one.putBoolean("squareProblem", false);
        final ListTag groups = new ListTag();
        groups.add(group("BUILDING", FILE_HUT, List.of(log, leaves),
                new long[] {FILE_HUT.offset(0, 2, 4).asLong(), FILE_HUT.offset(1, 2, 4).asLong(), FILE_HUT.offset(2, 2, 4).asLong()},
                new int[] {0, 1, 1}, new byte[] {1, 0, 1}));
        groups.add(group("SQUARE", FILE_MARKER, List.of(log), new long[] {FILE_MARKER.offset(0, 1, 0).asLong()}, new int[] {0}, new byte[] {1}));
        if (room) {
            groups.add(group("STOREROOM", FILE_HUT, List.of(SeasonsBlocks.STORAGE_BOX.get().defaultBlockState()),
                    new long[] {FILE_HUT.offset(1, 1, -3).asLong(), FILE_HUT.offset(2, 1, -3).asLong()}, new int[] {0, 0}, new byte[] {0, 0}));
        }
        one.put("groups", groups);
        final ListTag all = new ListTag();
        all.add(one);
        final CompoundTag root = new CompoundTag();
        if (version >= 0) {
            root.putInt("version", version);
        }
        root.put("colonies", all);
        return root;
    }

    private static String canon(final Map<BlockPos, DecorLedger.Entry> entries) {
        final Map<Long, String> sorted = new TreeMap<>();
        entries.forEach((p, e) -> sorted.put(p.asLong(), e.state() + (e.paid() ? "/paid" : "")));
        return sorted.toString();
    }

    /** One group in words, field by field (so groups of this build and of the 0.1.0 reader can be compared). */
    private static String canon(final DecorLedger.GroupView g) {
        return g.owner() + " " + g.festival() + " " + g.level() + " " + g.piece() + " " + g.decoratedDay() + " " + g.tendedDay() + " "
                + g.chargedDay() + " " + g.removing() + " " + g.treeDay() + " " + canon(g.entries());
    }

    private static String canon010(final me.lovkar.seasons.cssquarelife.v010.DecorLedger.GroupView g) {
        final Map<Long, String> sorted = new TreeMap<>();
        g.entries().forEach((p, e) -> sorted.put(p.asLong(), e.state() + (e.paid() ? "/paid" : "")));
        return g.owner().kind().name().toLowerCase(java.util.Locale.ROOT) + " " + g.owner().pos().toShortString() + " " + g.festival() + " "
                + g.level() + " " + g.piece() + " " + g.decoratedDay() + " " + g.tendedDay() + " " + g.chargedDay() + " " + g.removing() + " "
                + g.treeDay() + " " + sorted;
    }

    private static List<String> canonAll(final DecorLedger ledger, final int colonyId) {
        return ledger.groups(colonyId).stream().map(CsSquareLife::canon).toList();
    }

    private void ledgerFiles() {
        final HolderLookup.Provider provider = level.registryAccess();
        final int warnings = DecorLedger.newerVersionWarnings();
        final DecorLedger none = DecorLedger.load(file(-1, false), provider);
        final DecorLedger v1 = DecorLedger.load(file(1, false), provider);
        final DecorLedger v2 = DecorLedger.load(file(2, true), provider);
        checkEq("files: a version 1 file, its groups", v1.groups(FILE_COLONY).stream().map(g -> g.owner().kind() + " " + g.size()).toList(),
                List.of("BUILDING 3", "SQUARE 1"));
        checkEq("files: a file with no version reads as version 1 (groups, entries)", canonAll(none, FILE_COLONY), canonAll(v1, FILE_COLONY));
        check("files: version 1 and no version: the marker, the day's budget, no square mark", v1.marker(FILE_COLONY) != null
                && v1.marker(FILE_COLONY).equals(FILE_MARKER) && v1.budgetUsed(FILE_COLONY, 12) == 2 && v1.squareMark(FILE_COLONY) == null
                && none.squareMark(FILE_COLONY) == null && Squares.marker(v1, FILE_COLONY).equals(FILE_MARKER), "");
        checkEq("files: a version 2 file with a storeroom, its groups", v2.groups(FILE_COLONY).stream().map(g -> g.owner().kind() + " " + g.size()).toList(),
                List.of("BUILDING 3", "SQUARE 1", "STOREROOM 2"));
        check("files: version 2: the marker and budget as written, no square mark", FILE_MARKER.equals(v2.marker(FILE_COLONY))
                && v2.budgetUsed(FILE_COLONY, 12) == 2 && v2.squareMark(FILE_COLONY) == null, "");
        checkEq("files: none of these is taken for a newer file", DecorLedger.newerVersionWarnings(), warnings);

        // version 3: the mark goes through a save and a load; this build writes 3
        final List<String> before = canonAll(v2, FILE_COLONY);
        check("files: the mark is stored (and a second store of the same mark changes nothing)", v2.setSquareMark(FILE_COLONY, FILE_SQUARE, FILE_ANCHOR)
                && !v2.setSquareMark(FILE_COLONY, FILE_SQUARE, FILE_ANCHOR), "");
        final CompoundTag saved = v2.save(new CompoundTag(), provider);
        checkEq("files: this build writes version 3", saved.getInt("version"), 3);
        final DecorLedger v3 = DecorLedger.load(saved, provider);
        checkEq("files: version 3 round trip: the mark", v3.squareMark(FILE_COLONY), new DecorLedger.SquareMark(FILE_SQUARE, FILE_ANCHOR));
        checkEq("files: version 3 round trip: the groups and entries", canonAll(v3, FILE_COLONY), before);
        check("files: version 3: the marker is kept and only the fallback (the resolver's marker is none while a mark is stored)",
                FILE_MARKER.equals(v3.marker(FILE_COLONY)) && Squares.marker(v3, FILE_COLONY) == null, "");
        checkEq("files: a version 3 file of this build is not a newer one", DecorLedger.newerVersionWarnings(), warnings);
        // one key without the other reads as no mark
        final CompoundTag half = saved.copy();
        half.getList("colonies", Tag.TAG_COMPOUND).getCompound(0).remove("squareAnchor");
        checkEq("files: a colony with only one of the two square keys reads as no mark", DecorLedger.load(half, provider).squareMark(FILE_COLONY), null);
        check("files: clearing the mark never makes a record", !v3.clearSquareMark(FILE_COLONY + 100) && !v3.colonies().contains(FILE_COLONY + 100)
                && v3.clearSquareMark(FILE_COLONY) && v3.squareMark(FILE_COLONY) == null && !v3.clearSquareMark(FILE_COLONY), "");
        downgrade(provider, saved);
    }

    /**
     * Section 8 point 13, our side: a version 3 file read by the 0.1.0 reader (tools/cssquarelife/v010/DecorLedger.java is
     * the tag's own file with its package line changed, checked by check_reader.py). The file this build writes with a
     * square's group at the centrepiece and the marker kept.
     */
    private void downgrade(final HolderLookup.Provider provider, final CompoundTag v3file) {
        final DecorLedger ours = DecorLedger.load(v3file, provider);
        final BlockState log = LOG;
        check("downgrade: a square's group at the centrepiece is listed", ours.record(FILE_COLONY, sq(FILE_ANCHOR), "", 1, FILE_ANCHOR.above(), log, true)
                && ours.record(FILE_COLONY, sq(FILE_ANCHOR), "", 1, FILE_ANCHOR.above(2), LEAVES, false), "");
        final CompoundTag tag = ours.save(new CompoundTag(), provider);
        final List<String> ourGroups = canonAll(ours, FILE_COLONY);
        final int old = me.lovkar.seasons.cssquarelife.v010.DecorLedger.newerVersionWarnings();
        final me.lovkar.seasons.cssquarelife.v010.DecorLedger reader = me.lovkar.seasons.cssquarelife.v010.DecorLedger.load(tag, provider);
        checkEq("downgrade: the 0.1.0 reader warns once that the file is newer", me.lovkar.seasons.cssquarelife.v010.DecorLedger.newerVersionWarnings() - old, 1);
        checkEq("downgrade: the 0.1.0 reader has every group and entry as this build wrote them",
                reader.groups(FILE_COLONY).stream().map(CsSquareLife::canon010).toList(), ourGroups);
        check("downgrade: the 0.1.0 reader has the marker and the day's budget", FILE_MARKER.equals(reader.marker(FILE_COLONY))
                && reader.budgetUsed(FILE_COLONY, 12) == 2, String.valueOf(reader.marker(FILE_COLONY)));
        // the 0.1.0 sweep takes a square's group down when !pos.equals(marker): the group at the centrepiece is not the marker's
        boolean squareGoes = false;
        boolean markerStays = false;
        for (final me.lovkar.seasons.cssquarelife.v010.DecorLedger.GroupView g : reader.groups(FILE_COLONY)) {
            if (g.owner().kind() == me.lovkar.seasons.cssquarelife.v010.DecorLedger.Kind.SQUARE) {
                final boolean goes = !g.owner().pos().equals(reader.marker(FILE_COLONY));
                squareGoes |= g.owner().pos().equals(FILE_ANCHOR) && goes;
                markerStays |= g.owner().pos().equals(FILE_MARKER) && !goes;
            }
        }
        check("downgrade: under the 0.1.0 rule the square's group comes down as 'owner gone' and the marker's stays", squareGoes && markerStays, "");
        final CompoundTag back = reader.save(new CompoundTag(), provider);
        final CompoundTag backColony = back.getList("colonies", Tag.TAG_COMPOUND).getCompound(0);
        check("downgrade: the 0.1.0 reader saves version 2 without the square keys", back.getInt("version") == 2
                && !backColony.contains("squareBuilding") && !backColony.contains("squareAnchor"), "version " + back.getInt("version"));
        final DecorLedger again = DecorLedger.load(back, provider);
        check("downgrade: this build reads that file back with no mark and the same groups", again.squareMark(FILE_COLONY) == null
                && canonAll(again, FILE_COLONY).equals(ourGroups), "");
        note("NOTE FINDING H1 owner:fyi downgrade: a world saved by 0.2.0 and opened with 0.1.0 loads its ledger with one newer-version warning; "
                + "0.1.0's sweep then takes the Town Square's decorations down (their position is not the marker's) and the marker's tree "
                + "goes up again; MineColonies drops the unknown townsquare building when it reads the colony (point 13 below). A real boot "
                + "of the 0.1.0 jar on a saved world is not part of this probe");
    }

    // ================================================================ point 9 (section 8 point 3): nothing changes at the Workshop

    private record Staff(boolean full, boolean has, List<Integer> listed, List<Integer> all, IBuilding works, Map<String, Integer> jobs,
                         Integer jobless) {
    }

    private Staff staff() {
        final Map<String, Integer> jobs = new TreeMap<>();
        for (final ICitizenData c : colony.getCitizenManager().getCitizens()) {
            jobs.merge(c.getJob() == null ? "none" : c.getJob().getJobRegistryEntry().getKey().toString(), 1, Integer::sum);
        }
        final ICitizenData jobless = colony.getCitizenManager().getJoblessCitizen();
        return new Staff(work(w1).isFull(), work(w1).hasAssignedCitizen(), work(w1).getAssignedCitizen().stream().map(ICitizenData::getId).toList(),
                w1.getAllAssignedCitizen().stream().map(ICitizenData::getId).sorted().toList(), d1.getWorkBuilding(), jobs,
                jobless == null ? null : jobless.getId());
    }

    // ================================================================ A: the switch from the marker

    private Map<BlockPos, BlockState> t1;
    private BlockPos changed;
    private BlockPos foreign;
    private int itemsBefore;
    private int refund;
    private int unusedBefore;

    private void switchSteps() {
        now("switch: the marker's tree, a changed cell, a foreign block; a square at level 0", () -> {
            m1 = center.offset(MARKER_1);
            placeMarker(colony, owner, m1);
            checkEq("switch: the owner's marker is the colony's marker", ledger().marker(cid()), m1);
            t1 = tree(m1, 3, 7);
            foreign = m1.offset(3, 6, 3);
            t1.remove(foreign);
            seed("switch: the marker's tree", cid(), sq(m1), t1, false);
            level.setBlock(foreign, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            changed = m1.offset(1, 2, 0);                      // a spruce log column: dx + 3 = 4
            checkEq("switch: the changed cell was a log", level.getBlockState(changed), LOG);
            level.setBlock(changed, Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_ALL);
            check("switch: a vanilla block the player changed stays listed (vanilla blocks do not tell the ledger)",
                    ledger().at(changed) != null, "");
            final Squares.Square marker = resolve();
            check("switch: with only the marker the resolver names the marker", marker.source() == Squares.Source.MARKER && marker.hosts(m1),
                    show(marker));
            checkEq("switch: a sweep leaves the marker's tree up", DecorEvents.sweep(level), 0);
            check("switch: decor list names the marker", decorList().contains("com.colony_seasons.command.decor.list.square.marker"), "");
            s1 = placeSquare("switch: S1", colony, owner, center.offset(SLOT_A));
            if (s1 == null) {
                finish();
                return;
            }
            check("switch: a square at level 0 is not the main square: the marker keeps its tree", resolve().source() == Squares.Source.MARKER
                    && DecorEvents.sweep(level) == 0 && size(sq(m1)) == t1.size(), show(resolve()) + ", " + size(sq(m1)) + " listed");
        });
        now("switch: S1 built to level 1", () -> {
            itemsBefore = workshopItems();
            unusedBefore = Squares.markerUnusedNotices();
            refund = refundNow(cid(), sq(m1));
            ledger().setSquareProblem(cid(), true);            // the tree had no room at the marker this window
            final DecorLedger.SquareMark early = finishBuild(s1, WorkOrderType.BUILD, 1);
            checkEq("switch: during onUpgradeComplete the level is still 0, so nothing is decided there (the resolver waits for the end of the tick)",
                    early, null);
            check("switch: the completed order gave the hut its blueprint data: the tree tag reads at once", anchorOf(s1) != null, "");
            DecorEvents.drain(server);
            check("switch: at the end of the tick the construction hooks have run the resolver: the mark is stored",
                    ledger().squareMark(cid()) != null, String.valueOf(ledger().squareMark(cid())));
            final BlockPos a1 = anchorOf(s1);
            checkEq("switch: S1's tree cell is the blueprint's (hut + (-2, 0, -8))", a1, s1.getPosition().offset(-2, 0, -8));
            checkEq("switch: the main square's mark is stored", ledger().squareMark(cid()), new DecorLedger.SquareMark(s1.getPosition(), a1));
            final Squares.Square sq = resolve();
            check("switch: the resolver names S1 at its tree cell, located and free", sq.source() == Squares.Source.BUILDING && sq.building().equals(s1.getPosition())
                    && a1.equals(sq.anchor()) && sq.located() && !sq.busy() && sq.hosts(a1) && !sq.hosts(m1), show(sq));
            checkEq("switch: the managers are told once that the marker is not used", Squares.markerUnusedNotices() - unusedBefore, 1);
            check("switch: the marker's 'no room for the tree' note is cleared (the Workshop window shows it no more)",
                    !ledger().squareProblem(cid()), "");
            check("switch: the marker stays registered (the fallback), the resolver's marker is none, no square target",
                    m1.equals(ledger().marker(cid())) && Squares.marker(ledger(), cid()) == null && DecorWork.squareTarget(level, ledger(), cid()) == null, "");
            check("switch: the gathering is at the square's tree cell now (Q4), not at the marker or the town hall",
                    Gathering.site(level, colony).equals(a1), String.valueOf(Gathering.site(level, colony)));
            check("switch: the mark is on disk at once (the ledger has nothing unwritten)", !ledger().isDirty(), "");
            // the rig's own sweep may have started already (it runs every 100 ticks): one pass takes at most its budget
            final int sizeBefore = size(sq(m1));
            final int touched = DecorEvents.sweep(level);
            check("switch: one sweep takes at most its budget of the marker's tree, the rest marked for removal",
                    touched == Math.min(DecorEvents.SWEEP_BUDGET, sizeBefore) && size(sq(m1)) == sizeBefore - touched
                            && (size(sq(m1)) == 0 || ledger().group(cid(), sq(m1)).removing()),
                    touched + " touched of " + sizeBefore + " (" + t1.size() + " seeded), " + size(sq(m1)) + " left");
            check("switch: decor list names the Town Square", decorList().contains("com.colony_seasons.command.decor.list.square.building"), "");
        });
        steps.add(new Step("switch: the sweeps finish the marker's tree", 600) {
            @Override
            boolean tick() {
                if (t % 20 == 0) {
                    DecorEvents.sweep(level);
                }
                return ledger().group(cid(), sq(m1)) == null;
            }

            @Override
            void end(final boolean done) {
                final List<BlockPos> cells = new ArrayList<>(t1.keySet());
                cells.remove(changed);
                final List<BlockPos> left = notAir(cells);
                check("switch: every listed cell whose block was still ours is air, the group is gone", done && left.isEmpty(),
                        left.size() + " not air" + (left.isEmpty() ? "" : ", e.g. " + left.get(0).toShortString() + " " + level.getBlockState(left.get(0))));
                check("switch: the cell the player changed and the foreign block are left alone",
                        level.getBlockState(changed).is(Blocks.OAK_PLANKS) && level.getBlockState(foreign).is(Blocks.STONE), "");
                checkEq("switch: the items of the paid cells are in the Workshop", workshopItems() - itemsBefore, refund);
                checkEq("switch: still one notice", Squares.markerUnusedNotices() - unusedBefore, 1);
                ledger().flush(level);
                final int writes = ledger().writes();
                final int touched = DecorEvents.sweep(level);
                check("switch: a further pass removes nothing and writes nothing", touched == 0 && ledger().writes() == writes,
                        touched + " touched, " + (ledger().writes() - writes) + " writes");
                check("switch: nothing of ours is listed for the colony any more, no square target",
                        ledger().countOutdoor(cid()) == 0 && DecorWork.squareTarget(level, ledger(), cid()) == null, ledger().countOutdoor(cid()) + " listed");
                // the Decorator's AI keeps its target while he walks: one who chose the marker before the switch arrives now
                final DecorWork.Outcome late = DecorWork.decorate(level, cid(), DecorWork.Target.square(m1, Direction.NORTH,
                        me.lovkar.seasons.decor.PieceLoader.PACK), 1, DecorPlacer.Stock.FREE, DecorWork.day(level), ledger());
                check("switch: a Decorator who chose the marker before the switch places nothing there when he arrives", late.placed() == 0
                        && late.status().startsWith("a Town Square is the colony's centrepiece") && ledger().group(cid(), sq(m1)) == null
                        && ledger().countOutdoor(cid()) == 0, late.status());
            }
        });
    }

    // ================================================================ point 9 for real

    private void staffSteps() {
        now("point 3: the Decorator on the square changes nothing at the Workshop", () -> {
            final SquareAssignmentModule m = s1.assignment();
            check("point 3: S1 takes the Decorator", m.assignCitizen(d1) && s1.getAllAssignedCitizen().contains(d1), m.assignedIds().toString());
            final Staff now = staff();
            checkEq("point 3: the Workshop's worker state, his work building and the colony's job counts equal the unassigned baseline", now, baseline);
            check("point 3: his work building is still the Workshop", d1.getWorkBuilding() == w1, String.valueOf(d1.getWorkBuilding()));
            note("NOTE point 3: the Workshop's module full {} / has {} / lists {}, jobs {}, jobless {}", now.full(), now.has(), now.listed(), now.jobs(),
                    now.jobless());
        });
    }

    // ================================================================ C: work orders on the main square

    private int lostBefore;
    private int orderCellsBefore;
    private int sweptBefore;

    /**
     * One work order on S1 with a seeded group at its centrepiece: {@code make} makes the order; the group must be empty
     * within {@code bound} ticks of it; then the probe clears the blueprint's air cells as the Builder would, and the
     * order goes. {@code firstClear} cells are cleared by the "Builder" in the same tick, before the take-down.
     */
    private void orderCase(final String name, final int r, final int h, final int bound, final int firstClear, final java.util.function.Supplier<String> make) {
        final Map<BlockPos, BlockState>[] cells = new Map[1];
        final List<BlockPos> cleared = new ArrayList<>();
        steps.add(new Step(name, bound + 200) {
            int made;
            int emptyAt = -1;
            BlockPos a1;

            @Override
            void start() {
                a1 = anchorOf(s1);
                cells[0] = tree(a1, r, h);
                seed(name, cid(), sq(a1), cells[0], false);
                itemsBefore = workshopItems();
                lostBefore = DecorEvents.lostItems();
                orderCellsBefore = DecorEvents.squareOrderCells();
                sweptBefore = DecorEvents.swept();
                int n = 0;
                for (final BlockPos p : cells[0].keySet()) {
                    if (n++ % 4 == 1 && cleared.size() < firstClear) {
                        cleared.add(p);
                    }
                }
                made = ticks;
                final String how = make.get();
                check(name + ": the work order is open (" + how + ")", orderAt(s1) != null && s1.isPendingConstruction(), "");
                for (final BlockPos p : cleared) {
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);    // the Builder got there first
                }
                refund = refundNow(cid(), sq(a1));
                final Squares.Square busy = resolve();
                check(name + ": the square is busy: it stays the main square and hosts nothing (the marker is not used)", busy.source() == Squares.Source.BUILDING
                        && busy.busy() && !busy.hosts(a1) && !busy.hosts(m1) && Squares.marker(ledger(), cid()) == null, show(busy));
            }

            @Override
            boolean tick() {
                if (emptyAt < 0 && size(sq(a1)) == 0) {
                    emptyAt = ticks;
                }
                return emptyAt >= 0;
            }

            @Override
            void end(final boolean done) {
                final int took = emptyAt < 0 ? -1 : emptyAt - made;
                check(name + ": the group is down within " + bound + " ticks of the order (section 8 point 7, our side)", done && took <= bound,
                        "after " + took + " ticks, " + cells[0].size() + " cells");
                note("NOTE point 7 ({}): {} cells taken down {} ticks after the order was made", name, cells[0].size(), took);
                // the Builder's CLEAR_NON_SOLIDS: every blueprint-air cell set to air
                for (final BlockPos p : cells[0].keySet()) {
                    if (!level.getBlockState(p).isAir()) {
                        level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
                checkEq(name + ": the Builder's clearing found nothing of ours (0 forgotten): every paid item came back to the Workshop",
                        workshopItems() - itemsBefore, refund);
                checkEq(name + ": nothing was lost", DecorEvents.lostItems() - lostBefore, 0);
                // our trunk tells the ledger when the Builder clears it (forgotten at once); a vanilla log or leaf stays listed
                final int ours = (int) cleared.stream().filter(p -> cells[0].get(p).getBlock() instanceof me.lovkar.seasons.block.FestivalTrunkBlock).count();
                checkEq(name + ": the take-down touched every cell still listed when the order came", DecorEvents.swept() - sweptBefore,
                        cells[0].size() - ours);
                check(name + ": the order's own take-down did the work (not only the sweep)", DecorEvents.squareOrderCells() > orderCellsBefore,
                        (DecorEvents.squareOrderCells() - orderCellsBefore) + " cells");
                if (!cleared.isEmpty()) {
                    check(name + ": the cells the Builder cleared first stay as he left them (air), and no item came for them",
                            notAir(cleared).isEmpty() && ledger().group(cid(), sq(a1)) == null, cleared.size() + " cells");
                }
                final WorkOrderBuilding wo = orderAt(s1);
                if (wo != null) {
                    colony.getWorkManager().removeWorkOrder(wo);
                }
            }
        });
        steps.add(new Step(name + ": after the order", DecorEvents.SQUARE_WATCH + 10) {
            @Override
            void end(final boolean done) {
                DecorEvents.sweep(level);
                final BlockPos a1 = anchorOf(s1);
                check(name + ": after the order the mark is intact and the square is free again", new DecorLedger.SquareMark(s1.getPosition(), a1)
                        .equals(ledger().squareMark(cid())) && !resolve().busy() && resolve().hosts(a1), show(resolve()));
                check(name + ": nothing was put up (Q2 places nothing on a square)", ledger().group(cid(), sq(a1)) == null && ledger().countOutdoor(cid()) == 0
                        && notAir(cells[0].keySet()).isEmpty(), ledger().countOutdoor(cid()) + " listed");
            }
        });
    }

    private void orderSteps() {
        orderCase("REPAIR (requestRepair)", 3, 7, 2, 0, () -> {
            s1.requestRepair(BlockPos.ZERO);
            return "the hut window's repair";
        });
        orderCase("REPAIR, the Builder clearing cells first", 1, 5, 1, 4, () -> {
            s1.requestRepair(BlockPos.ZERO);
            return "the hut window's repair";
        });
        orderCase("UPGRADE (the work manager)", 3, 7, DecorEvents.SQUARE_WATCH + 3, 0, () -> {
            final WorkOrderBuilding wo = WorkOrderBuilding.create(WorkOrderType.UPGRADE, s1);
            // Q6: the five levels are there, so the order to level 2 names level 2's own blueprint (the work manager's claim check finds the file)
            checkEq("UPGRADE: the order's blueprint is level 2's", wo.getStructurePath(), "square/townsquare2.blueprint");
            colony.getWorkManager().addWorkOrder(wo, false);
            return "level " + wo.getCurrentLevel() + " to " + wo.getTargetLevel() + ", an order that does not come through the square's request: the watch sees it";
        });
        // REMOVE: the take-down, then what the Builder does when the removal is done
        final Map<BlockPos, BlockState>[] cells = new Map[1];
        steps.add(new Step("REMOVE (requestRemoval)", 100) {
            int made;
            BlockPos a1;

            @Override
            void start() {
                a1 = anchorOf(s1);
                cells[0] = tree(a1, 2, 4);
                seed("REMOVE", cid(), sq(a1), cells[0], true);
                itemsBefore = workshopItems();
                refund = refundNow(cid(), sq(a1));
                made = ticks;
                s1.requestRemoval(owner, BlockPos.ZERO);
                final WorkOrderBuilding wo = orderAt(s1);
                check("REMOVE: the removal order is open", wo != null && wo.getWorkOrderType() == WorkOrderType.REMOVE && s1.isPendingConstruction(), "");
            }

            @Override
            boolean tick() {
                return size(sq(a1)) == 0;
            }

            @Override
            void end(final boolean done) {
                check("REMOVE: the group is down within 1 tick of the order", done && ticks - made <= 1, "after " + (ticks - made) + " ticks");
                checkEq("REMOVE: every item back in the Workshop", workshopItems() - itemsBefore, refund);
                // the Builder has taken the plaza down: the order is completed and goes, the building is marked deconstructed
                final WorkOrderBuilding wo = orderAt(s1);
                if (wo != null) {
                    colony.getWorkManager().removeWorkOrder(wo);
                }
                s1.setDeconstructed();
                IMinecoloniesAPI.getInstance().getEventBus().post(new BuildingConstructionModEvent(s1, wo));
                DecorEvents.drain(server);
                final Squares.Square back = resolve();
                check("REMOVE done: the deconstructed square is no main square, the resolver falls back to the marker",
                        back.source() == Squares.Source.MARKER && m1.equals(back.anchor()) && back.hosts(m1), show(back));
                check("REMOVE done: the mark is cleared, the marker is the square again (a square target)", ledger().squareMark(cid()) == null
                        && m1.equals(Squares.marker(ledger(), cid())) && DecorWork.squareTarget(level, ledger(), cid()) != null, "");
                check("REMOVE done: decor list names the marker", decorList().contains("com.colony_seasons.command.decor.list.square.marker"), "");
            }
        });
    }

    // ================================================================ D: a removed square

    private int secondBefore;

    private void removedSteps() {
        now("removed: S2 placed", () -> {
            secondBefore = Squares.secondSquareNotices();
            unusedBefore = Squares.markerUnusedNotices();
            s2 = placeSquare("removed: S2", colony, owner, center.offset(SLOT_B));
            if (s2 == null) {
                finish();
                return;
            }
            checkEq("removed: a deconstructed square is not 'another square' when S2 is placed", Squares.secondSquareNotices() - secondBefore, 0);
        });
        now("removed: S2 built", () -> {
            finishBuild(s2, WorkOrderType.BUILD, 1);
            DecorEvents.drain(server);
        });
        waitTags("removed: S2", () -> s2);
        now("removed: S2 located, its group, the hut broken", () -> {
            locate(s2);
            final BlockPos a2 = anchorOf(s2);
            check("removed: S2 is the main square, the marker's notice again", resolve().source() == Squares.Source.BUILDING
                    && s2.getPosition().equals(resolve().building()) && Squares.markerUnusedNotices() - unusedBefore == 1, show(resolve()));
            final Map<BlockPos, BlockState> cells = tree(a2, 2, 4);
            seed("removed", cid(), sq(a2), cells, true);
            itemsBefore = workshopItems();
            refund = refundNow(cid(), sq(a2));
            final BlockPos hut = s2.getPosition();
            s2.destroy();
            level.setBlock(hut, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            DecorEvents.drain(server);
            check("removed: the group is gone in the same tick, every cell air", ledger().group(cid(), sq(a2)) == null && notAir(cells.keySet()).isEmpty(),
                    size(sq(a2)) + " listed");
            checkEq("removed: every item back in the Workshop", workshopItems() - itemsBefore, refund);
            check("removed: the mark is cleared and the resolver falls back to the marker", ledger().squareMark(cid()) == null
                    && resolve().source() == Squares.Source.MARKER && resolve().hosts(m1), show(resolve()));
            final int swept = DecorEvents.swept();
            final int writes = ledger().writes();
            DecorEvents.drain(server);
            check("removed: a second drain removes nothing and writes nothing", DecorEvents.swept() == swept && ledger().writes() == writes, "");
            s2 = null;
        });
    }

    // ================================================================ E: a second square

    private BlockPos a3;
    private BlockPos a4;
    private Map<BlockPos, BlockState> g3;

    private void secondSteps() {
        now("second: S3 placed", () -> {
            secondBefore = Squares.secondSquareNotices();
            s3 = placeSquare("second: S3", colony, owner, center.offset(SLOT_C));
            if (s3 == null) {
                finish();
            }
        });
        now("second: S3 built", () -> {
            finishBuild(s3, WorkOrderType.BUILD, 1);
            DecorEvents.drain(server);
        });
        waitTags("second: S3", () -> s3);
        now("second: S3 located, S4 placed", () -> {
            locate(s3);
            a3 = anchorOf(s3);
            g3 = tree(a3, 1, 5);
            seed("second: S3's group", cid(), sq(a3), g3, false);
            checkEq("second: S3 is the main square", resolve().building(), s3.getPosition());
            s4 = placeSquare("second: S4", colony, owner, center.offset(SLOT_D));
            if (s4 == null) {
                finish();
                return;
            }
            checkEq("second: the managers are told once that only the main square is decorated", Squares.secondSquareNotices() - secondBefore, 1);
        });
        now("second: S4 built", () -> {
            finishBuild(s4, WorkOrderType.BUILD, 1);
            DecorEvents.drain(server);
        });
        waitTags("second: S4", () -> s4);
        now("second: S4 located, an order on it", () -> {
            locate(s4);
            a4 = anchorOf(s4);
            check("second: S4 lies before S3 (lowest position alone would pick S4)", s4.getPosition().compareTo(s3.getPosition()) < 0, "");
            check("second: on a tie the main square keeps the tree: still S3", resolve().building().equals(s3.getPosition())
                    && new DecorLedger.SquareMark(s3.getPosition(), a3).equals(ledger().squareMark(cid())) && DecorEvents.sweep(level) == 0
                    && size(sq(a3)) == g3.size(), show(resolve()));
            checkEq("second: S4 built sends no second notice", Squares.secondSquareNotices() - secondBefore, 1);
            // Q5, the owner item of Q2: a Decorator listed on S4, which is not the main square, works the main square (S3's)
            final DecorWork.Target viaS4 = DecorWork.centrepiece(level, colony, ledger(), s4);
            check("second: a Decorator listed on S4 (not the main square) works the main square: S3's tree cell, S3 the building",
                    viaS4 != null && viaS4.owner().equals(sq(a3)) && viaS4.building() == s3 && viaS4.onTownSquare(), String.valueOf(viaS4));
            final DecorWork.Target viaS3 = DecorWork.centrepiece(level, colony, ledger(), s3);
            check("second: the main square's own Decorator gets the same target", viaS3 != null && viaS3.owner().equals(sq(a3)) && viaS3.building() == s3,
                    String.valueOf(viaS3));
            checkEq("second: nobody listed on a square gets no target at the square", DecorWork.centrepiece(level, colony, ledger(), null), null);
            // Q5: the Decorator page of S4 says S3 is the main square, and S4's info page says it is not
            final net.minecraft.network.RegistryFriendlyByteBuf page = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), level.registryAccess());
            s4.assignment().serializeToView(page);
            final me.lovkar.seasons.colony.SquareAssignmentModuleView pageView = new me.lovkar.seasons.colony.SquareAssignmentModuleView();
            pageView.deserialize(page);
            checkEq("second: S4's Decorator page names S3 as the main square", pageView.main(),
                    new me.lovkar.seasons.colony.SquareStatus.Main(me.lovkar.seasons.colony.SquareStatus.Standing.OTHER, s3.getPosition(), 1));
            checkEq("second: ... the page was read to its last byte", page.readableBytes(), 0);
            final me.lovkar.seasons.colony.SquareStatus.Status s4Status = me.lovkar.seasons.colony.SquareStatus.of(s4);
            check("second: S4's state is Not main, S3's is not (it is the main one)", s4Status.state() == me.lovkar.seasons.colony.SquareStatus.State.NOT_MAIN
                    && me.lovkar.seasons.colony.SquareStatus.of(s3).state() != me.lovkar.seasons.colony.SquareStatus.State.NOT_MAIN, s4Status.state().toString());
            s4.requestRepair(BlockPos.ZERO);
            check("second: a repair order on S4 is open", orderAt(s4) != null, "");
        });
        steps.add(new Step("second: an order on S4 leaves S3's group alone", DecorEvents.SQUARE_WATCH + 10) {
            @Override
            void end(final boolean done) {
                DecorEvents.sweep(level);
                check("second: S3's group is untouched while S4 has an order", size(sq(a3)) == g3.size() && !resolve().busy()
                        && resolve().building().equals(s3.getPosition()), size(sq(a3)) + " listed, " + show(resolve()));
                colony.getWorkManager().removeWorkOrder(orderAt(s4));
                unusedBefore = Squares.markerUnusedNotices();
                itemsBefore = workshopItems();
                refund = refundNow(cid(), sq(a3));
                forceLevel(s4, 2);
                locate(s4);
                checkEq("second: a strictly higher S4 is the main square now", ledger().squareMark(cid()), new DecorLedger.SquareMark(s4.getPosition(), a4));
                checkEq("second: no marker notice when the main square moves", Squares.markerUnusedNotices() - unusedBefore, 0);
            }
        });
        steps.add(new Step("second: the old centrepiece's group goes", 400) {
            @Override
            boolean tick() {
                if (t % 20 == 0) {
                    DecorEvents.sweep(level);
                }
                return ledger().group(cid(), sq(a3)) == null;
            }

            @Override
            void end(final boolean done) {
                check("second: S3's group comes down by the sweep, every cell air", done && notAir(g3.keySet()).isEmpty(), "");
                checkEq("second: its items are in the Workshop", workshopItems() - itemsBefore, refund);
                final int writes = ledger().writes();
                Squares.refresh(level, colony, ledger());
                check("second: the main square moved once (a second look changes nothing)", ledger().writes() == writes
                        && new DecorLedger.SquareMark(s4.getPosition(), a4).equals(ledger().squareMark(cid())), "");
                forceLevel(s4, 1);
                Squares.refresh(level, colony, ledger());
                checkEq("second: back on a tie, S4 now keeps it", resolve().building(), s4.getPosition());
                checkEq("second: still one second-square notice", Squares.secondSquareNotices() - secondBefore, 1);
            }
        });
    }

    // ================================================================ F: tags that cannot be read

    private void unreadableSteps() {
        now("unreadable: the main square's tags empty, with the stored anchor", () -> {
            final AbstractTileEntityColonyBuilding te4 = s4.getTileEntity();
            final AbstractTileEntityColonyBuilding te3 = s3.getTileEntity();
            final Map<BlockPos, List<String>> tags4 = new HashMap<>(te4.getPositionedTags());
            final Map<BlockPos, List<String>> tags3 = new HashMap<>(te3.getPositionedTags());
            final Map<BlockPos, BlockState> g4 = tree(a4, 1, 3);
            seed("unreadable: S4's group", cid(), sq(a4), g4, false);
            te4.setPositionedTags(new HashMap<>());
            check("unreadable: S4's tree tag reads empty", s4.getLocationsFromTag(BuildingTownSquare.TAG_TREE).isEmpty(), "");
            final Squares.Square stored = resolve();
            check("unreadable: the stored anchor stands in: S4 located at its cell", stored.source() == Squares.Source.BUILDING && stored.located()
                    && a4.equals(stored.anchor()) && s4.getPosition().equals(stored.building()), show(stored));
            check("unreadable: nothing comes down", DecorEvents.sweep(level) == 0 && size(sq(a4)) == g4.size(), "");
            // a strictly higher S3 whose tags cannot be read either, and the mark names S4: S3 is main but not located
            forceLevel(s3, 3);
            te3.setPositionedTags(new HashMap<>());
            final Squares.Square lost = Squares.refresh(level, colony, ledger());
            check("unreadable: S3 higher with no tags and no stored anchor of its own: main, not located", lost.source() == Squares.Source.BUILDING
                    && s3.getPosition().equals(lost.building()) && !lost.located() && !lost.hosts(a4), show(lost));
            checkEq("unreadable: the mark is left as it is", ledger().squareMark(cid()), new DecorLedger.SquareMark(s4.getPosition(), a4));
            check("unreadable: nothing is removed and nothing is placed (no square target)", DecorEvents.sweep(level) == 0 && size(sq(a4)) == g4.size()
                    && DecorWork.squareTarget(level, ledger(), cid()) == null, size(sq(a4)) + " listed");
            check("unreadable: decor list says the tree cell cannot be read", decorList().contains("com.colony_seasons.command.decor.list.square.unlocated"), "");
            // the tags come back: S3 is located, the mark moves, S4's group goes
            te3.setPositionedTags(tags3);
            te4.setPositionedTags(tags4);
            Squares.refresh(level, colony, ledger());
            checkEq("unreadable: S3's tags back: S3 is the main square at its cell", ledger().squareMark(cid()), new DecorLedger.SquareMark(s3.getPosition(), a3));
            int n = 0;
            while (size(sq(a4)) > 0 && n++ < 5) {
                DecorEvents.sweep(level);
            }
            check("unreadable: then the group at S4's cell comes down", ledger().group(cid(), sq(a4)) == null && notAir(g4.keySet()).isEmpty(), "");
            forceLevel(s3, 1);
            Squares.refresh(level, colony, ledger());
            checkEq("unreadable: S3 back at level 1 keeps the tree on the tie", resolve().building(), s3.getPosition());
        });
    }

    // ================================================================ G: point 13, MineColonies' side

    private void unknownTypeSteps() {
        now("point 13: a building whose type is no longer registered", () -> {
            final int before = colony.getServerBuildingManager().getBuildings().size();
            final CompoundTag saved = s4.serializeNBT(level.registryAccess());
            // the type id avoids the words the rig greps for: MineColonies logs it at its error level with a trace
            saved.putString("type", "lifetest:gone_building");
            IBuilding made = null;
            String threw = "";
            try {
                made = IBuildingDataManager.getInstance().createFrom(colony, saved, level.registryAccess());
            } catch (final Throwable t) {
                threw = t.getClass().getSimpleName().replace("Exception", "Exc");
            }
            check("point 13: MineColonies makes no building of an unknown type and throws nothing (its colony read skips a null)",
                    made == null && threw.isEmpty(), threw.isEmpty() ? String.valueOf(made) : threw);
            check("point 13: the colony's other buildings are untouched", colony.getServerBuildingManager().getBuildings().size() == before
                    && colony.getServerBuildingManager().getBuilding(s4.getPosition()) == s4, before + " buildings");
            note("NOTE FINDING H2 owner:fyi point 13: from the bytecode, RegisteredStructureManager.read adds a building only when createFrom "
                    + "answers one, and createFrom answers none for a type that is not registered (logging 'Unknown building type'): with "
                    + "Colony Seasons removed or downgraded to 0.1.x, MineColonies drops the Town Square from the colony when it loads it, "
                    + "and its hut block is gone with the mod; its decorations stay listed and come down by 0.1.0's rules");
            // the decorations of a square that is gone: an orphan square group is taken down by the sweep
            final BlockPos orphan = center.offset(ORPHAN);
            final Map<BlockPos, BlockState> cells = tree(orphan, 1, 3);
            seed("point 13: an orphan square group", cid(), sq(orphan), cells, true);
            int n = 0;
            while (ledger().group(cid(), sq(orphan)) != null && n++ < 5) {
                DecorEvents.sweep(level);
            }
            check("point 13: the sweep takes the orphan group down", ledger().group(cid(), sq(orphan)) == null && notAir(cells.keySet()).isEmpty(), "");
        });
    }

    // ================================================================ H: a deleted colony

    private void deletedSteps() {
        final int[] id2 = {-1};
        final BlockPos[] marker2 = new BlockPos[1];
        final BlockPos[] anchor2 = new BlockPos[1];
        final FakePlayer[] owner2 = new FakePlayer[1];
        final BuildingTownSquare[] s5 = new BuildingTownSquare[1];
        final IColony[] c2 = new IColony[1];
        now("deleted: a second colony and its Town Square", () -> {
            final BlockPos at = new BlockPos(COLONY_2.getX(), center.getY(), COLONY_2.getZ());
            force(at, 4, true);
            owner2[0] = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes("cssquarelife-owner2".getBytes(StandardCharsets.US_ASCII)),
                    "cslife_owner2"));
            owner2[0].setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
            c2[0] = IColonyManager.getInstance().createColony(level, at, owner2[0], "Seasons Square Life Two", "Medieval Oak");
            check("deleted: the second colony was made", c2[0] != null, c2[0] == null ? "-" : "id " + c2[0].getID());
            if (c2[0] == null) {
                finish();
                return;
            }
            id2[0] = c2[0].getID();
            stockHut(c2[0], "minecolonies:blockhuttownhall", at);
            s5[0] = placeSquare("deleted: S5", c2[0], owner2[0], at.offset(0, 0, -40));
        });
        now("over the marker: S5 built where the colony's marker and its tree stood", () -> {
            if (s5[0] == null) {
                finish();
                return;
            }
            // design 8 (A8 point 3): the marker stands inside the square's box; the Builder's first build clears the box
            final BlockPos marker0 = s5[0].getPosition().offset(2, 0, -12);
            final int noticesBefore = Squares.markerUnusedNotices();
            placeMarker(c2[0], owner2[0], marker0);
            checkEq("over the marker: the marker inside the unbuilt square's box is the colony's marker", ledger().marker(id2[0]), marker0);
            final Map<BlockPos, BlockState> tree0 = tree(marker0, 1, 3);
            seed("over the marker: the marker's tree", id2[0], sq(marker0), tree0, true);
            final Squares.Square before = Squares.of(level, c2[0], ledger());
            check("over the marker: with the square at level 0 the marker has the tree", before.source() == Squares.Source.MARKER && before.hosts(marker0),
                    show(before));
            for (final BlockPos p : tree0.keySet()) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);      // the first build clears the box
            }
            level.setBlock(marker0, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            check("over the marker: the marker's block replaced, it is no longer the colony's marker", ledger().marker(id2[0]) == null
                    && ledger().markerColony(marker0) == -1, "");
            DecorEvents.drain(server);
            check("over the marker: its group went through markerRemoved: no entry is left (no ghost)", ledger().group(id2[0], sq(marker0)) == null
                    && ledger().count(id2[0]) == 0 && notAir(tree0.keySet()).isEmpty(), ledger().count(id2[0]) + " listed");
            unusedBefore = Squares.markerUnusedNotices();
            finishBuild(s5[0], WorkOrderType.BUILD, 1);
            DecorEvents.drain(server);
            check("over the marker: S5 built is the main square, and no notice (no marker is registered any more)",
                    ledger().squareMark(id2[0]) != null && Squares.markerUnusedNotices() == noticesBefore, String.valueOf(ledger().squareMark(id2[0])));
        });
        waitTags("deleted: S5", () -> s5[0]);
        now("deleted: S5 located, a marker and a group, the colony deleted", () -> {
            final BlockPos at = new BlockPos(COLONY_2.getX(), center.getY(), COLONY_2.getZ());
            locate(s5[0]);
            anchor2[0] = anchorOf(s5[0]);
            checkEq("deleted: its square is its main square", ledger().squareMark(id2[0]), new DecorLedger.SquareMark(s5[0].getPosition(), anchor2[0]));
            marker2[0] = at.offset(30, 0, 0);
            placeMarker(c2[0], owner2[0], marker2[0]);
            check("deleted: a marker put down after the square registers as the fallback, with the notice",
                    marker2[0].equals(ledger().marker(id2[0])) && Squares.markerUnusedNotices() - unusedBefore == 1, "");
            // a stale marker (registered, its block gone) while the mark is not stored yet: the placement's refresh stores
            // the mark and tells the managers; the new marker then replaces the stale one without a second line
            ledger().unregisterMarker(id2[0], marker2[0]);
            ledger().clearSquareMark(id2[0]);
            final BlockPos stale = at.offset(30, 0, 8);
            final boolean staleIn = ledger().registerMarker(id2[0], stale) && level.getBlockState(stale).isAir();
            final int beforeStale = Squares.markerUnusedNotices();
            marker2[0] = at.offset(30, 0, 4);
            placeMarker(c2[0], owner2[0], marker2[0]);
            check("deleted: a marker put down while a stale one is registered and no mark is stored: it replaces the stale one, the mark is "
                            + "stored, one notice", staleIn && marker2[0].equals(ledger().marker(id2[0])) && ledger().markerColony(stale) == -1
                            && new DecorLedger.SquareMark(s5[0].getPosition(), anchor2[0]).equals(ledger().squareMark(id2[0]))
                            && Squares.markerUnusedNotices() - beforeStale == 1,
                    (Squares.markerUnusedNotices() - beforeStale) + " notices, stale registered " + staleIn);
            seed("deleted: S5's group", id2[0], sq(anchor2[0]), tree(anchor2[0], 1, 3), true);
            IColonyManager.getInstance().deleteColonyByWorld(id2[0], false, level);
        });
        steps.add(new Step("deleted: after the deletion", 60) {
            @Override
            void end(final boolean done) {
                if (id2[0] < 0) {
                    check("deleted: the case ran", false, "no colony");
                    return;
                }
                check("deleted: MineColonies has no such colony any more", IColonyManager.getInstance().getColonyByWorld(id2[0], level) == null, "");
                check("deleted: the square's hut went with it, so its group and its mark went at once", ledger().squareMark(id2[0]) == null
                        && ledger().group(id2[0], sq(anchor2[0])) == null, size(id2[0], sq(anchor2[0])) + " listed");
                // a mark left behind (as when the removal event never came): the sweep's look at a gone colony clears it
                ledger().setSquareMark(id2[0], marker2[0].offset(1, 0, 0), anchor2[0]);
                DecorEvents.sweep(level);
                check("deleted: the sweep takes the marker and the mark off at once and drops the record", ledger().marker(id2[0]) == null
                        && ledger().squareMark(id2[0]) == null && !ledger().colonies().contains(id2[0]) && ledger().markerColony(marker2[0]) == -1,
                        "record " + ledger().colonies().contains(id2[0]));
                final FakePlayer o3 = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes("cssquarelife-owner3".getBytes(StandardCharsets.US_ASCII)),
                        "cslife_owner3"));
                final BlockPos at = new BlockPos(COLONY_2.getX(), center.getY(), COLONY_2.getZ());
                o3.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
                final IColony c3 = IColonyManager.getInstance().createColony(level, at, o3, "Seasons Square Life Three", "Medieval Oak");
                if (c3 != null) {
                    check("deleted: a new colony" + (c3.getID() == id2[0] ? " with the deleted one's id" : "") + " inherits no marker and no mark",
                            ledger().marker(c3.getID()) == null && ledger().squareMark(c3.getID()) == null, "id " + c3.getID());
                    IColonyManager.getInstance().deleteColonyByWorld(c3.getID(), false, level);
                }
            }
        });
    }

    // ================================================================ I: section 8 point 6, substitution cells

    /**
     * The Builder's CLEAR_NON_SOLIDS stage run with Structurize's own placer over a small blueprint of air and
     * {@code structurize:blocksubstitution} cells: MineColonies' stage is {@code executeStructureStep(BLOCK_PLACEMENT)}
     * with {@code getIterator().decrement(skip)} (top down), where {@code skip} (its lambda$structureStep$6, copied from
     * the bytecode without the don't-touch list) skips every cell whose blueprint block is not an AirBlock and every
     * empty world cell. The probe walks bottom up ({@code increment}, as the paste probes do from a fresh iterator); the
     * cells it visits are the same. Our blocks in the air cells are cleared, those in the substitution cells are not.
     */
    private void substitutionSteps() {
        now("point 6: the Builder's clearing over substitution cells", () -> {
            final BlockPos base = a3.offset(-1, 1, -1);
            final Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
            final Map<BlockPos, Boolean> keep = new LinkedHashMap<>();
            final BlockState trunk = SeasonsBlocks.FESTIVAL_TRUNK.get().defaultBlockState();
            for (int x = 0; x < 3; x++) {
                for (int y = 0; y < 3; y++) {
                    for (int z = 0; z < 3; z++) {
                        final BlockPos p = base.offset(x, y, z);
                        cells.put(p, x == 1 ? LEAVES : x == 0 ? LOG : trunk);
                        keep.put(p, (x + y + z) % 2 == 0);
                    }
                }
            }
            seed("point 6: S3's group", cid(), sq(a3), cells, false);
            final Blueprint bp;
            final List<String> notes = new ArrayList<>();
            try {
                bp = new Blueprint((short) 3, (short) 3, (short) 3, level.registryAccess());
                final BlockState sub = com.ldtteam.structurize.blocks.ModBlocks.blockSubstitution.get().defaultBlockState();
                for (final Map.Entry<BlockPos, Boolean> e : keep.entrySet()) {
                    bp.addBlockState(e.getKey().subtract(base), e.getValue() ? sub : Blocks.AIR.defaultBlockState());
                }
                bp.setRotationMirror(RotationMirror.NONE, level);
                final CreativeStructureHandler handler = new CreativeStructureHandler(level, base.offset(bp.getPrimaryBlockOffset()), bp, RotationMirror.NONE, true);
                final StructurePlacer placer = new StructurePlacer(handler);
                int rounds = 0;
                while (rounds++ < 500) {
                    final StructurePhasePlacementResult result = placer.executeStructureStep(level, null, placer.getIterator().getProgressPos(),
                            StructurePlacer.Operation.BLOCK_PLACEMENT, () -> placer.getIterator().increment((info, pos, h) ->
                                    !(info.getBlockInfo().getState().getBlock() instanceof AirBlock) || h.getWorld().isEmptyBlock(pos)), false);
                    if (result.getIteratorPos().equals(AbstractBlueprintIterator.NULL_POS)) {
                        break;
                    }
                }
                notes.add(rounds + " rounds");
            } catch (final Throwable t) {
                note("NOTE FINDING H3 owner:tech point 6: Structurize's placer could not be driven over a hand-made blueprint here ({}); "
                        + "the protection is the take-down before every work order, proven above", t.getClass().getSimpleName().replace("Exception", "Exc"));
                DecorPlacer.remove(level, ledger(), cid(), sq(a3), DecorWork.sinkOf(colony, new int[1]));
                return;
            }
            final List<String> wrong = new ArrayList<>();
            int cleared = 0;
            int kept = 0;
            for (final Map.Entry<BlockPos, Boolean> e : keep.entrySet()) {
                final BlockState now = level.getBlockState(e.getKey());
                if (e.getValue()) {
                    kept += now.getBlock() == cells.get(e.getKey()).getBlock() ? 1 : 0;
                    if (now.getBlock() != cells.get(e.getKey()).getBlock() || ledger().at(e.getKey()) == null) {
                        wrong.add("substitution " + e.getKey().toShortString() + " " + now);
                    }
                } else {
                    cleared += now.isAir() ? 1 : 0;
                    if (!now.isAir()) {
                        wrong.add("air " + e.getKey().toShortString() + " " + now);
                    }
                }
            }
            check("point 6: CLEAR_NON_SOLIDS clears our blocks in the blueprint's air cells and passes over the substitution cells",
                    wrong.isEmpty(), cleared + " cleared, " + kept + " kept, " + notes + (wrong.isEmpty() ? "" : ", " + wrong.subList(0, Math.min(3, wrong.size()))));
            note("NOTE FINDING H3 owner:tech point 6: Structurize's placer with MineColonies' CLEAR_NON_SOLIDS skip rule leaves our blocks in "
                    + "structurize:blocksubstitution cells and clears those in air cells ({} and {} of 27). The first build's CLEAR stage and REMOVE's "
                    + "skipRemoval are read from the bytecode only; the [B] protection is the take-down before every work order. The real "
                    + "blueprints (Q6) keep the tree's 11 x 11 column as air, not substitution cells (design 4.6 [A]): the first build then clears "
                    + "whatever stands there, and the take-down (proven below and in mode builder with MineColonies' own Builder) keeps our tree "
                    + "out of every later order's way", cleared, kept);
            final int[] lost = new int[1];
            DecorPlacer.remove(level, ledger(), cid(), sq(a3), DecorWork.sinkOf(colony, lost));
            check("point 6: the rest of the group comes down by the rule, the cleared cells forgotten", ledger().group(cid(), sq(a3)) == null
                    && notAir(cells.keySet()).isEmpty(), "");
        });
    }

    // ================================================================ the plans

    private void planDefault() {
        now("ledger files", this::ledgerFiles);
        setupSteps(true);
        now("point 3: the baseline", () -> baseline = staff());
        switchSteps();
        staffSteps();
        orderSteps();
        removedSteps();
        secondSteps();
        unreadableSteps();
        unknownTypeSteps();
        substitutionSteps();
        deletedSteps();
        now("summary", () -> note("NOTE section 8: Q2 proves points 3 (the Workshop's state with the Decorator on the square), 6 (CLEAR_NON_SOLIDS "
                + "over substitution cells, with Structurize's placer), 7 (here our side: ticks from a work order to an empty group; mode "
                + "builder measures the Builder's own side with his AI at work) and 13 (the 0.1.0 reader and MineColonies' unknown type)"));
    }

    // ================================================================ mode builder: section 8 point 7 with MineColonies' Builder at work

    /** MineColonies' work manager runs every 20 ticks of an active colony (Colony's tickWorkManager transition). */
    private static final int WORK_MANAGER_TICK = 20;
    /** The most ticks one work order of the Builder may take here. */
    private static final int BUILDER_TICKS = 6000;
    /** A cell of the blueprint's air on the paving, where a torch stands: the Builder's CLEAR_NON_SOLIDS takes it. */
    private BlockPos witness;

    /**
     * Mode builder: section 8 point 7 with the Builder's own AI. S1 is built as in the default mode and its plaza's blocks
     * are set as the blueprint has them (what a finished build leaves), so a repair has nothing to place; the builder is
     * given an entity at his hut and works on his own. Each case seeds a tree at the centrepiece and a torch on the
     * paving, makes a REPAIR order and measures, in ticks from the order: our group empty; the Builder's first stage on
     * the square (his structure handler's); his CLEAR_NON_SOLIDS; his first change of any block in the box that was not
     * ours (the torch, normally); the order completed. The rig's colony never becomes active (no player), so the probe
     * runs MineColonies' work manager itself at its own rate, {@link #WORK_MANAGER_TICK}; nothing else of the Builder's
     * work is done for him.
     */
    private void planBuilder() {
        setupSteps(true);
        now("builder: S1 placed and built", () -> {
            s1 = placeSquare("builder: S1", colony, owner, center.offset(SLOT_A));
            if (s1 == null) {
                finish();
                return;
            }
            finishBuild(s1, WorkOrderType.BUILD, 1);
            DecorEvents.drain(server);
        });
        waitTags("builder: S1", () -> s1);
        now("builder: S1 is the main square, its plaza stands, the Builder works", () -> {
            locate(s1);
            final BlockPos a1 = anchorOf(s1);
            checkEq("builder: S1 is the main square", ledger().squareMark(cid()), new DecorLedger.SquareMark(s1.getPosition(), a1));
            final int set = pastePlaza(s1);
            final List<BlockPos> differ = plazaDiff(s1);
            check("builder: the plaza's blocks stand as the blueprint has them (a finished build)", set > 0 && differ.isEmpty(),
                    set + " blocks set, " + differ.size() + " differ" + (differ.isEmpty() ? "" : " " + differ.get(0).toShortString()));
            witness = witnessCell(s1);
            check("builder: a cell of the blueprint's air on the paving for the torch", witness != null, String.valueOf(witness));
            if (witness == null) {
                finish();
                return;
            }
            level.setDayTime(level.getDayTime() / 24000L * 24000L + 1000L);
            builder.setSaturation(ICitizenData.MAX_SATURATION);
            colony.getCitizenManager().spawnOrCreateCitizen(builder, level, builderHut.getPosition().offset(0, 0, 2));
            check("builder: the builder has an entity and the builder's AI", builder.getEntity().isPresent() && builder.getJob() != null
                    && builder.getJob().getWorkerAI() != null, builder.getEntity().isPresent() ? builderState() : "no entity");
        });
        steps.add(new Step("builder: the Builder settles at his hut", 200) {
            @Override
            boolean tick() {
                builder.setSaturation(ICitizenData.MAX_SATURATION);
                return false;
            }

            @Override
            void end(final boolean done) {
                note("NOTE builder: before the first order the builder is {}", builderState());
            }
        });
        builderCase("builder: REPAIR from the hut window, this Builder chosen", 2, () -> {
            s1.requestRepair(builderHut.getPosition());
            return "the hut window's repair, the Builder named";
        });
        builderCase("builder: REPAIR through the work manager (the watch)", DecorEvents.SQUARE_WATCH + 3, () -> {
            colony.getWorkManager().addWorkOrder(WorkOrderBuilding.create(WorkOrderType.REPAIR, s1), false);
            return "an order that does not come through the square's request; the work manager gives it to the Builder";
        });
        builderCase("builder: UPGRADE 1 to 2 from the hut window's route", 2, () -> {
            // MineColonies' work manager gives an order to a Builder whose hut is at least the target level (WorkOrder.canBeResolved)
            builderHut.setBuildingLevel(2);
            s1.requestUpgrade(owner, builderHut.getPosition());
            final WorkOrderBuilding wo = orderAt(s1);
            checkEq("builder: the order is an UPGRADE to level 2 with level 2's blueprint", wo == null ? null
                    : wo.getWorkOrderType() + " " + wo.getTargetLevel() + " " + wo.getStructurePath(), "UPGRADE 2 square/townsquare2.blueprint");
            return "the hut window's upgrade, the Builder named";
        }, 2);
        now("summary", () -> note("NOTE section 8 point 7 (builder): measured with MineColonies' own Builder AI on the square's REPAIR "
                + "(the order with the least to do before CLEAR_NON_SOLIDS) and, with the five levels of Q6, on a real UPGRADE 1 to 2, which first "
                + "builds level 2's solid blocks"));
    }

    /** Sets the square's blueprint blocks in the world (not air, not substitution cells, not the hut block): what its build left. */
    private int pastePlaza(final BuildingTownSquare square) {
        final BlockPos origin = square.getPosition().subtract(squareBlueprint.getPrimaryBlockOffset());
        int n = 0;
        for (final com.ldtteam.structurize.util.BlockInfo info : squareBlueprint.getBlockInfoAsList()) {
            final BlockState state = info.getState();
            final BlockPos at = origin.offset(info.getPos());
            if (state == null || state.isAir() || structurizeBlock(state) || at.equals(square.getPosition())) {
                continue;
            }
            level.setBlock(at, state, SEED_FLAGS);
            n++;
        }
        return n;
    }

    /** Cells where the world differs from the blueprint (not air, not substitution cells, not the hut block). */
    private List<BlockPos> plazaDiff(final BuildingTownSquare square) {
        return plazaDiff(square, squareBlueprint);
    }

    /** As above against the blueprint of another level. */
    private List<BlockPos> plazaDiff(final BuildingTownSquare square, final Blueprint blueprint) {
        final BlockPos origin = square.getPosition().subtract(blueprint.getPrimaryBlockOffset());
        final List<BlockPos> out = new ArrayList<>();
        for (final com.ldtteam.structurize.util.BlockInfo info : blueprint.getBlockInfoAsList()) {
            final BlockState state = info.getState();
            final BlockPos at = origin.offset(info.getPos());
            if (state != null && !state.isAir() && !structurizeBlock(state) && !at.equals(square.getPosition()) && level.getBlockState(at) != state) {
                out.add(at);
            }
        }
        return out;
    }

    private static boolean structurizeBlock(final BlockState state) {
        return "structurize".equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace());
    }

    /** A blueprint air cell one above the paving, outside the tree's 11 x 11 column, on no tag, away from the hut; or null. */
    private BlockPos witnessCell(final BuildingTownSquare square) {
        return witnessCell(square, null);
    }

    /** As above, and (when {@code other} is given) a cell that is air in that level's blueprint too, so an upgrade leaves it alone. */
    private BlockPos witnessCell(final BuildingTownSquare square, final Blueprint other) {
        final Map<BlockPos, BlockState> there = new HashMap<>();
        if (other != null) {
            for (final com.ldtteam.structurize.util.BlockInfo info : other.getBlockInfoAsList()) {
                there.put(info.getPos(), info.getState());
            }
        }
        final BlockPos origin = square.getPosition().subtract(squareBlueprint.getPrimaryBlockOffset());
        final Set<BlockPos> tagged = new java.util.HashSet<>();
        for (final String tag : BuildingTownSquare.TAGS) {
            tagged.addAll(square.getLocationsFromTag(tag));
        }
        final BlockPos tree = anchorOf(square);
        for (final com.ldtteam.structurize.util.BlockInfo info : squareBlueprint.getBlockInfoAsList()) {
            final BlockPos at = origin.offset(info.getPos());
            if (info.getState() == null || !(info.getState().getBlock() instanceof AirBlock) || at.getY() != tree.getY()
                    || Math.abs(at.getX() - tree.getX()) <= 5 && Math.abs(at.getZ() - tree.getZ()) <= 5 || tagged.contains(at)
                    || at.distManhattan(square.getPosition()) < 4 || !level.getBlockState(at).isAir()
                    || other != null && !(there.get(info.getPos()) == null || there.get(info.getPos()).isAir())
                    || !level.getBlockState(at.below()).isFaceSturdy(level, at.below(), Direction.UP)) {
                continue;
            }
            return at.immutable();
        }
        return null;
    }

    /** The Builder's AI state, the stage of his structure handler, where he stands. */
    private String builderState() {
        final Object ai = builder.getJob() == null ? null : builder.getJob().getWorkerAI();
        final String state = ai instanceof com.minecolonies.api.entity.ai.ITickingStateAI t ? String.valueOf(t.getState()) : "no AI";
        final Object stage = builderStage();
        final String at = builder.getEntity().map(e -> e.blockPosition().toShortString()).orElse("no entity");
        return state + (stage == null ? "" : " " + stage) + " at " + at;
    }

    /** The stage the Builder's structure handler is in (BUILD_SOLID, CLEAR_NON_SOLIDS, ...), or null without one. */
    private Object builderStage() {
        final Object ai = builder.getJob() == null ? null : builder.getJob().getWorkerAI();
        if (!(ai instanceof com.minecolonies.core.entity.ai.workers.AbstractEntityAIStructure<?, ?>)) {
            return null;
        }
        try {
            final java.lang.reflect.Field f = com.minecolonies.core.entity.ai.workers.AbstractEntityAIStructure.class.getDeclaredField("structurePlacer");
            f.setAccessible(true);
            final Object placer = f.get(ai);
            if (placer instanceof com.minecolonies.api.util.Tuple<?, ?> t
                    && t.getB() instanceof com.minecolonies.core.entity.ai.workers.util.BuildingStructureHandler<?, ?> handler) {
                return handler.getStage();
            }
        } catch (final ReflectiveOperationException unreachable) {
            return "stage unknown (" + unreachable.getClass().getSimpleName() + ")";
        }
        return null;
    }

    /**
     * One REPAIR order on S1 worked by the Builder's own AI. {@code ourBound} is how many ticks after the order our group
     * must be empty. The checks: the group was empty before the Builder's CLEAR_NON_SOLIDS and before his first change
     * of a block in the box; no block of ours changed while it was listed; every paid item came back to the Workshop and
     * nothing was lost; the Builder took the torch (his CLEAR_NON_SOLIDS did run over the box); the order completed, and
     * then the mark is intact, the square free and nothing put up.
     */
    private void builderCase(final String name, final int ourBound, final java.util.function.Supplier<String> make) {
        builderCase(name, ourBound, make, 0);
    }

    /**
     * What the Builder needs to build the plaza of {@code bp} over the world: one entry per item, the count of blocks that
     * differ from the world (not air, not Structurize's placeholder blocks, not the hut block), plus a few spare.
     */
    private Map<net.minecraft.world.item.Item, Integer> needFor(final BuildingTownSquare square, final Blueprint bp) {
        final Map<net.minecraft.world.item.Item, Integer> need = new LinkedHashMap<>();
        final BlockPos origin = square.getPosition().subtract(bp.getPrimaryBlockOffset());
        for (final com.ldtteam.structurize.util.BlockInfo info : bp.getBlockInfoAsList()) {
            final BlockState state = info.getState();
            final BlockPos at = origin.offset(info.getPos());
            if (state != null && structurizeBlock(state) && BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().equals("blocksolidsubstitution")) {
                // "a solid block of this terrain": the Builder fills it with the ground's own block, dirt here (it asked for 54 of it)
                need.merge(Items.DIRT, 1, Integer::sum);
                continue;
            }
            if (state == null || state.isAir() || structurizeBlock(state) || at.equals(square.getPosition()) || level.getBlockState(at) == state) {
                continue;
            }
            need.merge(state.getBlock().asItem(), 1, Integer::sum);
        }
        need.replaceAll((item, n) -> Math.min(n + 16, 128));
        return need;
    }

    /** Puts the items into the Builder's own inventory (what his AI counts first, before it asks anyone). */
    private int giveBuilder(final Map<net.minecraft.world.item.Item, Integer> items) {
        final com.minecolonies.api.inventory.InventoryCitizen inv = builder.getEntity().get().getInventoryCitizen();
        int given = 0;
        for (final Map.Entry<net.minecraft.world.item.Item, Integer> e : items.entrySet()) {
            int left = e.getValue();
            while (left > 0) {
                final int n = Math.min(left, e.getKey().getDefaultMaxStackSize());
                final ItemStack rest = net.neoforged.neoforge.items.ItemHandlerHelper.insertItemStacked(inv, new ItemStack(e.getKey(), n), false);
                given += n - rest.getCount();
                left -= n;
                if (!rest.isEmpty()) {
                    break;
                }
            }
        }
        return given;
    }

    /**
     * As above; {@code upgradeTo} above 0 makes it the UPGRADE of the hut window's route to that level: the Builder is given
     * what the level's blocks need, and after the order the building is that level (its blueprint path, the mark and the
     * anchor unchanged, its tags the level's, the plaza the level's blueprint).
     */
    private void builderCase(final String name, final int ourBound, final java.util.function.Supplier<String> make, final int upgradeTo) {
        steps.add(new Step(name, BUILDER_TICKS) {
            BlockPos a1;
            Map<BlockPos, BlockState> cells;
            final Map<BlockPos, BlockState> box = new LinkedHashMap<>();
            long made;
            int down = -1;
            int onSquare = -1;
            int clearNonSolids = -1;
            int firstChange = -1;
            String firstWhat = "-";
            int torchTaken = -1;
            int completed = -1;
            final List<String> oursTouched = new ArrayList<>();
            int before;
            int lost;
            int paid;
            final List<String> stages = new ArrayList<>();
            Blueprint target;
            boolean toppedUp;

            @Override
            void start() {
                a1 = anchorOf(s1);
                cells = tree(a1, 3, 7);
                seed(name, cid(), sq(a1), cells, false);
                if (upgradeTo > 0) {
                    target = StructurePacks.getBlueprint(PACK, "square/townsquare" + upgradeTo + ".blueprint", level.registryAccess());
                    check(name + ": the target level's blueprint loads", target != null, "");
                    if (target == null) {
                        finish();
                        return;
                    }
                    witness = witnessCell(s1, target);
                    check(name + ": a torch cell that is air at both levels", witness != null, String.valueOf(witness));
                    if (witness == null) {
                        finish();
                        return;
                    }
                    final Map<net.minecraft.world.item.Item, Integer> need = needFor(s1, target);
                    final int given = giveBuilder(need);
                    check(name + ": the Builder is given what the level's blocks need", given == need.values().stream().mapToInt(Integer::intValue).sum(),
                            given + " items of " + need.size() + " kinds");
                }
                level.setBlock(witness, Blocks.TORCH.defaultBlockState(), Block.UPDATE_ALL);
                before = workshopItems();
                lost = DecorEvents.lostItems();
                paid = refundNow(cid(), sq(a1));
                final net.minecraft.util.Tuple<BlockPos, BlockPos> corners = s1.getCorners();
                for (final BlockPos p : BlockPos.betweenClosed(corners.getA(), corners.getB())) {
                    if (!cells.containsKey(p) && !p.equals(s1.getPosition())) {
                        box.put(p.immutable(), level.getBlockState(p));
                    }
                }
                made = level.getGameTime();
                final String how = make.get();
                check(name + ": the work order is open (" + how + ")", orderAt(s1) != null && s1.isPendingConstruction(), builderState());
            }

            @Override
            boolean tick() {
                final int at = (int) (level.getGameTime() - made);
                builder.setSaturation(ICitizenData.MAX_SATURATION);
                if (level.getGameTime() % WORK_MANAGER_TICK == 0) {
                    colony.getWorkManager().onColonyTick(colony);
                }
                final DecorLedger.GroupView group = ledger().group(cid(), sq(a1));
                if (down < 0 && size(sq(a1)) == 0) {
                    down = at;
                }
                if (group != null) {
                    for (final Map.Entry<BlockPos, DecorLedger.Entry> e : group.entries().entrySet()) {
                        final BlockState now = level.getBlockState(e.getKey());
                        if (now != e.getValue().state() && !leafDistanceOnly(e.getValue().state(), now) && oursTouched.size() < 5) {
                            oursTouched.add(e.getKey().toShortString() + " at " + at + ": " + now);
                        }
                    }
                }
                final Object stage = builderStage();
                if (stage != null) {
                    if (onSquare < 0) {
                        onSquare = at;
                    }
                    final String s = String.valueOf(stage);
                    if (stages.isEmpty() || !stages.get(stages.size() - 1).startsWith(s + " ")) {
                        stages.add(s + " " + at);
                    }
                    if (clearNonSolids < 0 && "CLEAR_NON_SOLIDS".equals(s)) {
                        clearNonSolids = at;
                    }
                }
                if (firstChange < 0) {
                    for (final Map.Entry<BlockPos, BlockState> e : box.entrySet()) {
                        final BlockState now = level.getBlockState(e.getKey());
                        if (now != e.getValue() && !tape(now) && !tape(e.getValue())) {
                            firstChange = at;
                            firstWhat = e.getKey().toShortString() + " " + BuiltInRegistries.BLOCK.getKey(e.getValue().getBlock()).getPath() + " to "
                                    + BuiltInRegistries.BLOCK.getKey(now.getBlock()).getPath();
                            break;
                        }
                    }
                }
                if (torchTaken < 0 && !level.getBlockState(witness).is(Blocks.TORCH)) {
                    torchTaken = at;
                }
                if (orderAt(s1) == null && completed < 0) {
                    completed = at;
                }
                if (upgradeTo > 0 && !toppedUp && builderHut instanceof com.minecolonies.core.colony.buildings.AbstractBuildingStructureBuilder sb
                        && !sb.getNeededResources().isEmpty() && builderState().startsWith("NEEDS_ITEM")) {
                    // the list the order made itself (the terrain's solid substitution cells want dirt, which the pre-stock did not count): what he lacks
                    final Map<net.minecraft.world.item.Item, Integer> lacking = new LinkedHashMap<>();
                    for (final com.minecolonies.core.colony.buildings.utils.BuildingBuilderResource r : sb.getNeededResources().values()) {
                        if (r.getAvailable() < r.getAmount()) {
                            lacking.merge(r.getItemStack().getItem(), r.getAmount() - r.getAvailable() + 8, Integer::sum);
                        }
                    }
                    final int given = giveBuilder(lacking);
                    toppedUp = true;
                    note("NOTE {}: at {} ticks the Builder lacked {} of the order's own list; given {} items", name, at, lacking.keySet().stream()
                            .map(i -> BuiltInRegistries.ITEM.getKey(i).getPath()).toList(), at, given);
                }
                if (t % 1000 == 0) {
                    note("{}: at {} ticks the builder is {}", name, at, builderState());
                    if (upgradeTo > 0) {
                        final List<String> open = new ArrayList<>();
                        for (final com.minecolonies.api.colony.requestsystem.request.IRequest<?> r : builderHut.getOpenRequests(builder.getId())) {
                            open.add((r.getRequest() instanceof com.minecolonies.api.colony.requestsystem.requestable.Stack st ? st.getStack() + " count " + st.getCount() + " min " + st.getMinimumCount() : String.valueOf(r.getRequest())) + " " + r.getState());
                        }
                        final StringBuilder inv = new StringBuilder();
                        final com.minecolonies.api.inventory.InventoryCitizen ic = builder.getInventory();
                        for (int i = 0; i < ic.getSlots(); i++) {
                            if (!ic.getStackInSlot(i).isEmpty()) {
                                inv.append(ic.getStackInSlot(i).getCount()).append(' ').append(BuiltInRegistries.ITEM.getKey(ic.getStackInSlot(i).getItem()).getPath()).append(", ");
                            }
                        }
                        note("{}: open requests {}; inventory {}", name, open, inv);
                    }
                }
                return completed >= 0;
            }

            @Override
            void end(final boolean done) {
                final String times = "group down " + down + ", the Builder's structure loaded (his first stage) " + onSquare + ", CLEAR_NON_SOLIDS " + clearNonSolids
                        + ", first change in the box " + firstChange + " (" + firstWhat + "), torch taken " + torchTaken + ", completed " + completed
                        + "; stages " + stages;
                note("NOTE point 7 ({}): ticks from the order: {}", name, times);
                check(name + ": the Builder completed the order with his own AI", done && completed >= 0, "after " + t + " ticks, " + builderState());
                check(name + ": our group is down within " + ourBound + " ticks of the order", down >= 0 && down <= ourBound, "after " + down + " ticks");
                check(name + ": our group was down before the Builder's first stage on the square began (nothing of his can touch the box earlier)",
                        down >= 0 && onSquare > down, times);
                check(name + ": our group was down before the Builder's CLEAR_NON_SOLIDS reached the square", down >= 0 && clearNonSolids > down, times);
                check(name + ": our group was down before the Builder's first change in the box", down >= 0 && (firstChange < 0 || firstChange > down), times);
                check(name + ": the Builder's CLEAR_NON_SOLIDS ran over the box (he took the torch, after our group was down)", torchTaken > down, times);
                check(name + ": no block of ours changed while it was listed (the Builder never got there first)", oursTouched.isEmpty(), oursTouched.toString());
                checkEq(name + ": every paid item came back to the Workshop", workshopItems() - before, paid);
                checkEq(name + ": nothing was lost", DecorEvents.lostItems() - lost, 0);
                check(name + ": the tree's cells are air", notAir(cells.keySet()).isEmpty(), notAir(cells.keySet()).size() + " not air");
                if (upgradeTo > 0 && done) {
                    checkEq(name + ": the building's level after the Builder's upgrade", s1.getBuildingLevel(), upgradeTo);
                    // MineColonies never rewrites a building's stored path on an upgrade (it names the placed file); the level's file is named by the order
                    checkEq(name + ": the stored blueprint path is the placed one (MineColonies keeps it through upgrades)", s1.getBlueprintPath(), SQUARE_PATH);
                    checkEq(name + ": a repair order now names the new level's blueprint", WorkOrderBuilding.create(WorkOrderType.REPAIR, s1).getStructurePath(),
                            "square/townsquare" + upgradeTo + ".blueprint");
                    checkEq(name + ": the next upgrade order names the next level's blueprint", WorkOrderBuilding.create(WorkOrderType.UPGRADE, s1).getStructurePath(),
                            "square/townsquare" + (upgradeTo + 1) + ".blueprint");
                    checkEq(name + ": the square's mark and anchor are unchanged", ledger().squareMark(cid()), new DecorLedger.SquareMark(s1.getPosition(), a1));
                    checkEq(name + ": the tree tag is still the same cell", anchorOf(s1), a1);
                    final Map<String, java.util.Set<BlockPos>> want = new TreeMap<>();
                    for (final Map.Entry<BlockPos, List<String>> e : com.ldtteam.structurize.blueprints.v1.BlueprintTagUtils.getBlueprintTags(target).entrySet()) {
                        for (final String tag : e.getValue()) {
                            if (BuildingTownSquare.TAGS.contains(tag)) {
                                want.computeIfAbsent(tag, k -> new java.util.TreeSet<>()).add(s1.getPosition().offset(e.getKey()));
                            }
                        }
                    }
                    final Map<String, java.util.Set<BlockPos>> got = new TreeMap<>();
                    for (final String tag : BuildingTownSquare.TAGS) {
                        final java.util.Set<BlockPos> cellsOfTag = new java.util.TreeSet<>(s1.getLocationsFromTag(tag));
                        if (!cellsOfTag.isEmpty()) {
                            got.put(tag, cellsOfTag);
                        }
                    }
                    check(name + ": getLocationsFromTag is the level " + upgradeTo + " blueprint's tag cells", got.equals(want),
                            got.values().stream().mapToInt(java.util.Set::size).sum() + " cells of " + got.keySet() + ", wanted "
                                    + want.values().stream().mapToInt(java.util.Set::size).sum() + " of " + want.keySet());
                    final List<BlockPos> differ = plazaDiff(s1, target);
                    check(name + ": the plaza the Builder left is the level " + upgradeTo + " blueprint's blocks", differ.isEmpty(),
                            differ.size() + " differ" + (differ.isEmpty() ? "" : ", e.g. " + differ.get(0).toShortString() + " is " + level.getBlockState(differ.get(0))));
                }
            }
        });
        steps.add(new Step(name + ": after the order", DecorEvents.SQUARE_WATCH + 10) {
            @Override
            void end(final boolean done) {
                DecorEvents.sweep(level);
                final BlockPos a1 = anchorOf(s1);
                check(name + ": after the order the mark is intact and the square is free again", new DecorLedger.SquareMark(s1.getPosition(), a1)
                        .equals(ledger().squareMark(cid())) && !resolve().busy() && resolve().hosts(a1), show(resolve()));
                check(name + ": nothing was put up (Q2 places nothing on a square)", ledger().group(cid(), sq(a1)) == null && ledger().countOutdoor(cid()) == 0,
                        ledger().countOutdoor(cid()) + " listed");
            }
        });
    }

    private static boolean tape(final BlockState state) {
        final ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return "minecolonies".equals(id.getNamespace()) && id.getPath().contains("tape");
    }

    // ================================================================ the restart in the middle of a switch

    private void planRestartSave() {
        setupSteps(false);
        final Map<BlockPos, BlockState>[] tree = new Map[1];
        now("save: the marker's tree and S1 at level 0", () -> {
            m1 = center.offset(MARKER_1);
            placeMarker(colony, owner, m1);
            tree[0] = tree(m1, 3, 9);
            foreign = m1.offset(3, 8, 3);
            tree[0].remove(foreign);
            seed("save: the marker's tree", cid(), sq(m1), tree[0], false);
            level.setBlock(foreign, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            changed = m1.offset(1, 4, 0);
            level.setBlock(changed, Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_ALL);
            s1 = placeSquare("save: S1", colony, owner, center.offset(SLOT_A));
            if (s1 == null) {
                finish();
            }
        });
        // the build just after one of the rig's own sweeps, the switch's first sweep well before the next (each 100 ticks)
        waitPhase("save: just after one of the rig's sweeps", 1);
        final long[] builtAt = new long[1];
        now("save: S1 built", () -> {
            builtAt[0] = level.getGameTime();
            finishBuild(s1, WorkOrderType.BUILD, 1);
            DecorEvents.drain(server);
        });
        waitTags("save: S1", () -> s1);
        steps.add(new Step("save: S1 located and one sweep, between two of the rig's own sweeps", 200) {
            @Override
            boolean tick() {
                return level.getGameTime() % DecorEvents.SWEEP_INTERVAL == 10;
            }

            @Override
            void end(final boolean done) {
                check("save: no sweep of the rig's own came between the build and now", done
                        && level.getGameTime() / DecorEvents.SWEEP_INTERVAL == builtAt[0] / DecorEvents.SWEEP_INTERVAL,
                        "built at " + builtAt[0] + ", now " + level.getGameTime());
                locate(s1);
                final BlockPos a1 = anchorOf(s1);
                checkEq("save: the mark is stored", ledger().squareMark(cid()), new DecorLedger.SquareMark(s1.getPosition(), a1));
                checkEq("save: one notice", Squares.markerUnusedNotices(), 1);
                check("save: S1 takes the Decorator", s1.assignment().assignCitizen(d1), "");
                final int touched = DecorEvents.sweep(level);
                final DecorLedger.GroupView g = ledger().group(cid(), sq(m1));
                check("save: one sweep took its budget, the rest is marked for removal", touched == DecorEvents.SWEEP_BUDGET && g != null && g.removing()
                        && g.size() == tree[0].size() - DecorEvents.SWEEP_BUDGET, touched + " touched, " + (g == null ? 0 : g.size()) + " left");
                final Properties ids = new Properties();
                ids.setProperty("colony", Integer.toString(cid()));
                ids.setProperty("s1", Long.toString(s1.getPosition().asLong()));
                ids.setProperty("anchor", Long.toString(a1.asLong()));
                ids.setProperty("marker", Long.toString(m1.asLong()));
                ids.setProperty("w1", Long.toString(w1.getPosition().asLong()));
                ids.setProperty("d1", Integer.toString(d1.getId()));
                ids.setProperty("left", Integer.toString(g == null ? 0 : g.size()));
                ids.setProperty("foreign", Long.toString(foreign.asLong()));
                ids.setProperty("changed", Long.toString(changed.asLong()));
                final StringBuilder cells = new StringBuilder();
                for (final BlockPos p : tree[0].keySet()) {
                    cells.append(cells.isEmpty() ? "" : ",").append(p.asLong());
                }
                ids.setProperty("cells", cells.toString());
                try (var out = Files.newBufferedWriter(server.getWorldPath(LevelResource.ROOT).resolve(IDS_FILE), StandardCharsets.US_ASCII)) {
                    ids.store(out, "cssquarelife restart");
                } catch (final java.io.IOException unwritten) {
                    check("save: the ids are written for the restart", false, unwritten.getClass().getSimpleName());
                }
                check("save: the world saved", server.saveEverything(false, true, true), "game time " + level.getGameTime());
            }
        });
    }

    private void planRestartLoad() {
        final Properties ids = new Properties();
        now("load: the ledger file as the first run left it", () -> {
            try (var in = Files.newBufferedReader(server.getWorldPath(LevelResource.ROOT).resolve(IDS_FILE), StandardCharsets.US_ASCII)) {
                ids.load(in);
            } catch (final java.io.IOException unread) {
                check("load: the ids of the first run are there", false, unread.getClass().getSimpleName());
                finish();
                return;
            }
            try {
                if (firstFile == null) {
                    throw new java.io.IOException("not read in the first tick");
                }
                final CompoundTag data = firstFile.getCompound("data");
                CompoundTag one = null;
                for (final Tag t : data.getList("colonies", Tag.TAG_COMPOUND)) {
                    if (((CompoundTag) t).getInt("id") == Integer.parseInt(ids.getProperty("colony"))) {
                        one = (CompoundTag) t;
                    }
                }
                int left = -1;
                boolean removing = false;
                if (one != null) {
                    for (final Tag g : one.getList("groups", Tag.TAG_COMPOUND)) {
                        final CompoundTag group = (CompoundTag) g;
                        if ("SQUARE".equals(group.getString("kind")) && group.getLong("pos") == Long.parseLong(ids.getProperty("marker"))) {
                            left = group.getLongArray("positions").length;
                            removing = group.getBoolean("removing");
                        }
                    }
                }
                check("load: the file on disk is version 3 with the mark, the marker and the half-down tree marked for removal",
                        data.getInt("version") == 3 && one != null && one.getLong("squareBuilding") == Long.parseLong(ids.getProperty("s1"))
                                && one.getLong("squareAnchor") == Long.parseLong(ids.getProperty("anchor"))
                                && one.getLong("marker") == Long.parseLong(ids.getProperty("marker"))
                                && left == Integer.parseInt(ids.getProperty("left")) && removing,
                        "version " + data.getInt("version") + ", " + left + " listed, removing " + removing + ", game time " + level.getGameTime());
            } catch (final java.io.IOException | RuntimeException unread) {
                check("load: the ledger file reads", false, unread.getClass().getSimpleName().replace("Exception", "Exc"));
            }
        });
        steps.add(new Step("load: the colony comes back", 2400) {
            @Override
            boolean tick() {
                final IColony found = IColonyManager.getInstance().getColonyByWorld(Integer.parseInt(ids.getProperty("colony", "-1")), level);
                if (found == null) {
                    return false;
                }
                final IBuilding a = found.getServerBuildingManager().getBuilding(BlockPos.of(Long.parseLong(ids.getProperty("s1", "0"))));
                final IBuilding w = found.getServerBuildingManager().getBuilding(BlockPos.of(Long.parseLong(ids.getProperty("w1", "0"))));
                if (a instanceof BuildingTownSquare sa && w instanceof BuildingDecorator ws && sa.getTileEntity() != null) {
                    colony = found;
                    s1 = sa;
                    w1 = ws;
                    return t >= 20;
                }
                return false;
            }

            @Override
            void end(final boolean done) {
                check("load: the colony, the square and the Workshop are back", done, "");
                if (!done) {
                    finish();
                    return;
                }
                center = colony.getCenter();
                owner = FakePlayerFactory.getMinecraft(level);
                m1 = BlockPos.of(Long.parseLong(ids.getProperty("marker")));
                final BlockPos anchor = BlockPos.of(Long.parseLong(ids.getProperty("anchor")));
                checkEq("load: the mark is read back", ledger().squareMark(cid()), new DecorLedger.SquareMark(s1.getPosition(), anchor));
                checkEq("load: the marker is read back (still registered, the fallback)", ledger().marker(cid()), m1);
                final Squares.Square sq = resolve();
                check("load: the resolver names S1 at its cell", sq.source() == Squares.Source.BUILDING && s1.getPosition().equals(sq.building())
                        && anchor.equals(sq.anchor()) && sq.located(), show(sq));
                checkEq("load: no notice after the restart (the mark was there)", Squares.markerUnusedNotices(), 0);
                d1 = colony.getCitizenManager().getCivilian(Integer.parseInt(ids.getProperty("d1", "-1")));
                check("load: the Decorator's work building is the Workshop, and S1 lists him (section 8 point 3 on load)", d1 != null
                        && d1.getWorkBuilding() == w1 && s1.assignment().hasAssignedCitizen(d1), d1 == null ? "no citizen" : String.valueOf(d1.getWorkBuilding()));
            }
        });
        steps.add(new Step("load: the sweeps finish the switch", 800) {
            @Override
            boolean tick() {
                if (t % 20 == 0) {
                    DecorEvents.sweep(level);
                }
                return ledger().group(cid(), sq(m1)) == null;
            }

            @Override
            void end(final boolean done) {
                final BlockPos changedAt = BlockPos.of(Long.parseLong(ids.getProperty("changed")));
                final BlockPos foreignAt = BlockPos.of(Long.parseLong(ids.getProperty("foreign")));
                final List<String> wrong = new ArrayList<>();
                int n = 0;
                for (final String s : ids.getProperty("cells", "").split(",")) {
                    if (s.isEmpty()) {
                        continue;
                    }
                    n++;
                    final BlockPos p = BlockPos.of(Long.parseLong(s));
                    final BlockState now = level.getBlockState(p);
                    if (p.equals(changedAt) ? !now.is(Blocks.OAK_PLANKS) : !now.isAir()) {
                        wrong.add(p.toShortString() + " " + now);
                    }
                }
                if (!level.getBlockState(foreignAt).is(Blocks.STONE)) {
                    wrong.add("the foreign block " + level.getBlockState(foreignAt));
                }
                check("load: the sweeps finished the tree, and the world equals the expectation cell for cell", done && wrong.isEmpty(),
                        n + " cells" + (wrong.isEmpty() ? "" : ", " + wrong.size() + " differ, e.g. " + wrong.get(0)));
                check("load: nothing was placed, no second notice, no square target", ledger().countOutdoor(cid()) == 0 && Squares.markerUnusedNotices() == 0
                        && DecorWork.squareTarget(level, ledger(), cid()) == null, ledger().countOutdoor(cid()) + " listed");
            }
        });
    }
}
