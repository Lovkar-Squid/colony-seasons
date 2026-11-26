package me.lovkar.seasons.csfestival;

import com.ldtteam.structurize.api.RotationMirror;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.placement.AbstractBlueprintIterator;
import com.ldtteam.structurize.placement.StructurePhasePlacementResult;
import com.ldtteam.structurize.placement.StructurePlacer;
import com.ldtteam.structurize.placement.structure.CreativeStructureHandler;
import com.ldtteam.structurize.storage.StructurePacks;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.permissions.Action;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import com.minecolonies.api.entity.ai.statemachine.states.CitizenAIState;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.tileentities.AbstractTileEntityColonyBuilding;
import com.minecolonies.core.entity.ai.workers.CitizenAI;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import com.mojang.authlib.GameProfile;
import me.lovkar.seasons.ColonySeasons;
import me.lovkar.seasons.SeasonsConfig;
import me.lovkar.seasons.ai.EntityAIWorkDecorator;
import me.lovkar.seasons.block.FestivalMarkerBlock;
import me.lovkar.seasons.block.Lights;
import me.lovkar.seasons.block.SeasonsBlocks;
import me.lovkar.seasons.calendar.CalendarEvents;
import me.lovkar.seasons.calendar.CalendarManager;
import me.lovkar.seasons.calendar.DateSource;
import me.lovkar.seasons.calendar.Season;
import me.lovkar.seasons.colony.BuildingDecorator;
import me.lovkar.seasons.colony.BuildingTownSquare;
import me.lovkar.seasons.colony.DecoratorModules;
import me.lovkar.seasons.colony.DecoratorSetup;
import me.lovkar.seasons.colony.DecoratorTexts;
import me.lovkar.seasons.colony.JobDecorator;
import me.lovkar.seasons.decor.DecorEvents;
import me.lovkar.seasons.decor.DecorLedger;
import me.lovkar.seasons.decor.DecorPlacer;
import me.lovkar.seasons.decor.DecorWork;
import me.lovkar.seasons.decor.DoorFinder;
import me.lovkar.seasons.decor.PieceLoader;
import me.lovkar.seasons.decor.Squares;
import me.lovkar.seasons.festival.FestivalData;
import me.lovkar.seasons.festival.FestivalDirector;
import me.lovkar.seasons.festival.Fireworks;
import me.lovkar.seasons.festival.Gathering;
import me.lovkar.seasons.festival.Gifts;
import me.lovkar.seasons.festival.TreeLighting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Headless check of S7, the festival events, on a real colony with the Decorator, a festival marker and the tree from
 * the mod's own pieces (village_spruce_full at hut level 3). Modes (the rig writes the word to probe-mode.txt):
 * <ul>
 *   <li>none: the tree finished and its players told; lit by a fake player's right-click (every light of the colony,
 *       the strings on the town hall too, the ledger equal to the world, a chime and rockets) and, after a reset, by
 *       the Decorator two game days later; gifts (one per player and real day, a second try refused, a pile a player
 *       placed refused, the Winter Festival's and New Year's tables, opened into items); the gathering on 25 Dec (the
 *       everyone day: off-duty colonists and workers within 8 blocks of the tree, the Decorator hosting, the leisure
 *       site, the song; back at work after the hours, everyone in bed after bedtime) and the next game day (only the
 *       off-duty ones); New Year dusk fireworks from stock (the big show the Decorator stays up for and launches
 *       himself at the square before he goes to bed, a sparkle, nothing without a player, and a sparkle beside the
 *       tree once the crate is gone; every show's rockets climb into the sky); the midnight show with the fake clock
 *       stepping over midnight; the season line; three game days of a forced Winter Festival; then the square of the
 *       third in-game test (S10c): the tree put up by the Decorator's own AI while he and a colonist stand in its cells
 *       (nobody is boxed in, he stands outside the box, the square completes and another building follows the same game
 *       day), and with an armor stand in a gift pile's cell (that cell refused, the square left for the day after three
 *       visits that place nothing, the tree ready all the same and lit by the Decorator two game days later, the square
 *       complete the day after the stand is gone);</li>
 *   <li>newyear (S10c): the New Year dusk of the third in-game test replayed (a forced New Year, the clock sprinted from
 *       11800 through the dusk with decorations free: the show goes up and he goes to bed), then the Decorator asleep
 *       through the dusk in the rain, with rockets from his stock and with decorations free: the server launches the
 *       show from the crate; a fake player in the claim is the colony's audience, found by the real claim test;</li>
 *   <li>nomixin: the mod jar built without its mixin (prepare.sh): it loads, the mixin is absent and never asked, the
 *       off-duty gathering works, the workers stay at work, everyone is in bed after bedtime; on New Year's eve the
 *       Decorator goes to bed too and the server launches the dusk show from the square;</li>
 *   <li>voyager: Voyager 0.4.0 next to the mod: both mixins are in CitizenAI, the workers come on the everyone day,
 *       the Decorator stays up for the dusk show and launches it, and the Astronomer works at night while the others
 *       sleep;</li>
 *   <li>square (0.2.0, Q4): the same colony with a Town Square instead of the festival marker (the square's blueprint
 *       pasted and registered as the main square, its Decorator assigned): the tree on its {@code tree} cell, lit by a
 *       player and by the Decorator, the gifts, and the gathering with the crowd on the standing places of its
 *       {@code gather} tags and the square's hut as the leisure site in the hours (and off the list after them), the
 *       Decorator hosting outside the tree's box, the dusk shows from the crate and, once it is gone, from the
 *       {@code launch} tag's cell; the keep radius of a square, the fallback to the ring when the tag reads empty;</li>
 *   <li>{@code restart-save} then {@code restart-load} (tools/restart.sh): a gift taken and the tree lit, the world saved;
 *       after the restart the same player is refused, another one gets a gift, the tree is still lit, no notice and no
 *       season line is sent again.</li>
 * </ul>
 * Prints "[csfestival] CHECK ...: OK|FAIL" and "[csfestival] RESULT csfestival: PASS|FAIL", then halts. The words
 * "error" and "exception" stay out of the details.
 */
@Mod("colonyseasonsfestival")
public class CsFestival {
    private static final Logger LOGGER = LoggerFactory.getLogger("csfestival");
    private static final int SETUP_TICK = 60;
    private static final long WATCHDOG_SECONDS = 560;

    static final LocalDate DECOR_DAY = LocalDate.of(2026, 12, 10);
    static final LocalDate FESTIVAL_DAY = LocalDate.of(2026, 12, 25);
    static final LocalDate NEW_YEAR_EVE = LocalDate.of(2026, 12, 31);
    static final LocalDate OUT_OF_SEASON = LocalDate.of(2027, 3, 15);
    private static final int MORNING = 1000;
    private static final int BEFORE_HOURS = 8000;
    private static final int SAMPLE_FROM = 9300;
    private static final int WITHIN = 8;
    /** The crowd of a Town Square stands up to 10 blocks from its tree. */
    private static final int WITHIN_SQUARE = 12;
    /** MineColonies' bedtime: the citizen state SLEEP and the states of its sleep AI. */
    private static final Set<String> BEDTIME_STATES = Set.of("SLEEP", "WALKING_HOME", "FIND_BED", "SLEEPING");
    /** MineColonies' citizen AI at work: WORK, then WORKING. */
    private static final Set<String> AT_WORK = Set.of(CitizenAIState.WORK.toString(), CitizenAIState.WORKING.toString());
    private static final String RESTART_FILE = "csfestival-restart.nbt";
    private static final UUID P1 = UUID.nameUUIDFromBytes("csfestival-player-one".getBytes(StandardCharsets.US_ASCII));
    private static final UUID P2 = UUID.nameUUIDFromBytes("csfestival-player-two".getBytes(StandardCharsets.US_ASCII));
    /** A player who is not of the colony (MineColonies' neutral rank). */
    private static final UUID P3 = UUID.nameUUIDFromBytes("csfestival-visitor".getBytes(StandardCharsets.US_ASCII));

    private int ticks;
    private boolean failed;
    private boolean finished;
    private final String mode;
    private long setupNanos;

    private MinecraftServer server;
    private ServerLevel level;
    private FakePlayer owner;
    private FakePlayer one;
    private FakePlayer two;
    private FakePlayer visitor;
    private IColony colony;
    private int colonyId;
    private int ground;
    private DecorLedger ledger;
    private BuildingDecorator hut;
    private IBuilding townHall;
    private ICitizenData decorator;
    private ICitizenData astronomer;
    private final List<ICitizenData> workers = new ArrayList<>();
    private final List<ICitizenData> offDuty = new ArrayList<>();
    private BlockPos marker;
    /** square mode: the colony's Town Square (its {@code tree} cell is {@link #marker} once the tags are read). */
    private BuildingTownSquare townSquare;
    private boolean present = true;
    private IBuilding lumberjackHut;
    private IBuilding masonHut;
    /** newyear: the colony's audience, a fake player in the claim about 20 blocks from the marker. */
    private FakePlayer watcher;

    private final List<Step> steps = new ArrayList<>();
    private int stepIndex;

    public CsFestival() {
        mode = readMode();
        // the calendar must never see the real date
        CalendarManager.setDateSource(DateSource.fixed(DECOR_DAY));
        Fireworks.setPresence((lvl, c) -> present && c.getID() == colonyId);
        NeoForge.EVENT_BUS.addListener(this::onTick);
    }

    // ================================================================ logging

    private void check(final String what, final boolean ok, final String detail) {
        LOGGER.info("[csfestival] CHECK {}: {} {}", what, ok ? "OK" : "FAIL", detail);
        failed |= !ok;
    }

    private void checkEq(final String what, final Object actual, final Object expected) {
        check(what, Objects.equals(actual, expected), "got " + actual + (Objects.equals(actual, expected) ? "" : ", wanted " + expected));
    }

    private static void note(final String text, final Object... args) {
        LOGGER.info("[csfestival] " + text, args);
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
                note("mode '{}'", mode);
                setupNanos = System.nanoTime();
                level = server.overworld();
                ledger = DecorLedger.get(level);
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
            check("the probe ran without a throwable", false, t.toString().replace("Exception", "X") + " at " + where(t));
            finish();
        }
    }

    private static String where(final Throwable t) {
        final StringBuilder at = new StringBuilder();
        int n = 0;
        for (final StackTraceElement e : t.getStackTrace()) {
            if (n++ >= 6) {
                break;
            }
            at.append(e.getClassName().substring(e.getClassName().lastIndexOf('.') + 1)).append('.').append(e.getMethodName())
                    .append(':').append(e.getLineNumber()).append(' ');
        }
        return at.toString().trim();
    }

    private void runSteps() {
        if (stepIndex >= steps.size()) {
            finish();
            return;
        }
        final Step step = steps.get(stepIndex);
        if (!step.started) {
            step.started = true;
            step.nanos = System.nanoTime();
            if (step.sprint) {
                server.tickRateManager().requestGameToSprint(step.maxTicks + 200);
            }
            step.start();
        }
        step.t++;
        if (step.t % 100 == 0 && colony != null) {
            keepWell();
        }
        final boolean done = step.tick();
        if (done || step.t >= step.maxTicks) {
            final double seconds = Math.max(1e-3, (System.nanoTime() - step.nanos) / 1e9);
            note("step '{}' ran {} ticks in {} s", step.name, step.t, String.format("%.1f", seconds));
            step.end(done);
            server.tickRateManager().stopSprinting();
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
        LOGGER.info("[csfestival] RESULT csfestival: {}", failed ? "FAIL" : "PASS");
        if (server != null) {
            server.halt(false);
        }
    }

    private static String readMode() {
        try {
            final Path file = Path.of("probe-mode.txt");
            return Files.exists(file) ? Files.readString(file, StandardCharsets.US_ASCII).trim() : "";
        } catch (final java.io.IOException unreadable) {
            return "";
        }
    }

    /** One stretch of the run: up to {@code maxTicks} server ticks; {@code tick()} returning true ends it early. */
    private abstract static class Step {
        final String name;
        final int maxTicks;
        final boolean sprint;
        boolean started;
        int t;
        long nanos;

        Step(final String name, final int maxTicks) {
            this(name, maxTicks, false);
        }

        Step(final String name, final int maxTicks, final boolean sprint) {
            this.name = name;
            this.maxTicks = maxTicks;
            this.sprint = sprint;
        }

        void start() {
        }

        boolean tick() {
            return true;
        }

        void end(final boolean done) {
        }
    }

    private void step(final Step step) {
        steps.add(step);
    }

    private void plan() {
        switch (mode) {
            case "" -> {
                planSetup(false);
                planRules();
                planTree();
                planLighting();
                planDecoratorLights();
                planGifts();
                planGathering(true);
                planSecondDay();
                planDusk();
                planLaunching();
                planMidnight();
                planSeasonLine();
                planForcedRun();
                planBoxedIn();
                planHeld();
            }
            case "newyear" -> {
                planSetup(false);
                planTree();
                planNewYear();
            }
            case "square" -> {
                planSetup(false);
                planSquarePlace();
                planTree();
                planSquareEvents();
                planLighting();
                planDecoratorLights();
                planGifts();
                planGathering(true);
                planSquareNoTags();
                planDusk();
                planLaunching();
            }
            case "nomixin" -> {
                planSetup(false);
                planTree();
                planGathering(false);
                planShow(false);
            }
            case "voyager" -> {
                planSetup(true);
                planTree();
                planGathering(true);
                planShow(true);
                planNight();
            }
            case "restart-save" -> {
                planSetup(false);
                planTree();
                planRestartSave();
            }
            case "restart-load" -> planRestartLoad();
            default -> {
                check("known mode", false, "'" + mode + "'");
                finish();
            }
        }
    }

    // ================================================================ world and colony

    private void setDate(final LocalDate date) {
        CalendarManager.setDateSource(DateSource.fixed(date));
        CalendarManager.refresh(server);
    }

    private void quietWorld() {
        server.setDifficulty(Difficulty.PEACEFUL, true);
        final GameRules rules = server.getGameRules();
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        rules.getRule(GameRules.RULE_DOFIRETICK).set(false, server);
        rules.getRule(GameRules.RULE_MOBGRIEFING).set(false, server);
        level.setWeatherParameters(24000 * 20, 0, false, false);
    }

    private long day() {
        return level.getDayTime() / 24000L;
    }

    private long timeOfDay() {
        return level.getDayTime() % 24000L;
    }

    private void setTimeOfDay(final int time) {
        level.setDayTime(day() * 24000L + time);
    }

    private void nextDayAt(final int time) {
        level.setDayTime((day() + 1) * 24000L + time);
    }

    private void set(final BlockPos pos, final BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }

    /** A stone-brick house on the ground with doors in its south wall (the csdecorgate helper). */
    private DoorFinder.Box house(final int x0, final int z0, final int w, final int d, final int... doors) {
        final int y0 = ground;
        final BlockState brick = Blocks.STONE_BRICKS.defaultBlockState();
        for (int x = x0; x < x0 + w; x++) {
            for (int z = z0; z < z0 + d; z++) {
                for (int y = y0; y <= y0 + 6; y++) {
                    final boolean shell = y == y0 || y == y0 + 6 || x == x0 || x == x0 + w - 1 || z == z0 || z == z0 + d - 1;
                    set(new BlockPos(x, y, z), shell ? brick : Blocks.AIR.defaultBlockState());
                }
            }
        }
        final int zs = z0 + d - 1;
        for (int i = 0; i < doors.length; i++) {
            final DoorHingeSide hinge = i % 2 == 0 ? DoorHingeSide.LEFT : DoorHingeSide.RIGHT;
            final BlockState lower = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH)
                    .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER).setValue(DoorBlock.HINGE, hinge);
            set(new BlockPos(doors[i], y0 + 1, zs), lower);
            set(new BlockPos(doors[i], y0 + 2, zs), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        }
        return new DoorFinder.Box(new BlockPos(x0, y0, z0), new BlockPos(x0 + w - 1, y0 + 6, z0 + d - 1),
                new BlockPos(x0 + w / 2, y0 + 1, z0 + d / 2));
    }

    /** Registers a MineColonies building on a hand-built house (the csdecorgate helper). */
    private IBuilding register(final String hutId, final DoorFinder.Box box, final int buildingLevel, final String pack, final String path) {
        final Block block = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(hutId)).orElse(null);
        if (block == null) {
            check("hut block " + hutId + " exists", false, "");
            return null;
        }
        level.setBlock(box.hut(), block.defaultBlockState(), Block.UPDATE_ALL);
        final BlockEntity be = level.getBlockEntity(box.hut());
        final IBuilding building = be instanceof AbstractTileEntityColonyBuilding te ? colony.getServerBuildingManager().addNewBuilding(te, level) : null;
        if (building == null) {
            check("building " + hutId + " registers", false, "at " + box.hut());
            return null;
        }
        building.setCorners(box.min(), box.max());
        building.setBuildingLevel(buildingLevel);
        if (pack != null) {
            building.setStructurePack(pack);
            building.setBlueprintPath(path);
        }
        building.markDirty();
        return building;
    }

    private ICitizenData spawn(final BlockPos at) {
        final ICitizenData data = colony.getCitizenManager().createAndRegisterCivilianData();
        colony.getCitizenManager().spawnOrCreateCitizen(data, level, at);
        return data;
    }

    private ICitizenData hire(final IBuilding building, final BlockPos at) {
        final ICitizenData data = spawn(at);
        final WorkerBuildingModule module = building.getFirstModuleOccurance(WorkerBuildingModule.class);
        check("a citizen is hired into " + BuiltInRegistries.BLOCK.getKey(level.getBlockState(building.getPosition()).getBlock()),
                module != null && module.assignCitizen(data) && data.getJob() != null,
                data.getJob() == null ? "no job" : data.getJob().getClass().getSimpleName());
        return data;
    }

    /** Fed and well: hunger or a disease would take them off duty for reasons that are not the festival's. */
    private void keepWell() {
        for (final ICitizenData c : colony.getCitizenManager().getCitizens()) {
            c.setSaturation(ICitizenData.MAX_SATURATION);
            if (c.getCitizenDiseaseHandler().isSick()) {
                c.getCitizenDiseaseHandler().cure();
            }
        }
    }

    private static String state(final ICitizenData c) {
        final AbstractEntityCitizen entity = c == null ? null : c.getEntity().orElse(null);
        if (entity instanceof EntityCitizen e && e.getCitizenAI() != null && e.getCitizenAI().getState() != null) {
            return e.getCitizenAI().getState().toString();
        }
        return "none";
    }

    private double dist(final ICitizenData c) {
        final AbstractEntityCitizen entity = c.getEntity().orElse(null);
        return entity == null ? Double.MAX_VALUE : Math.sqrt(entity.position().distanceToSqr(Vec3.atBottomCenterOf(marker)));
    }

    private JobDecorator job() {
        return decorator != null && decorator.getJob() instanceof JobDecorator j ? j : null;
    }

    private DecorLedger.Owner square() {
        return DecorLedger.Owner.square(marker);
    }

    private BlockPos firstOf(final java.util.function.Predicate<BlockState> what) {
        final DecorLedger.GroupView g = ledger.group(colonyId, square());
        if (g == null) {
            return null;
        }
        for (final Map.Entry<BlockPos, DecorLedger.Entry> e : g.entries().entrySet()) {
            if (what.test(e.getValue().state())) {
                return e.getKey();
            }
        }
        return null;
    }

    /** Every ledger entry of the colony equals the world (the cells are loaded here). */
    private void invariant(final String where) {
        int bad = 0;
        String first = "";
        for (final Map.Entry<BlockPos, DecorLedger.Entry> e : ledger.entries(colonyId).entrySet()) {
            if (level.getBlockState(e.getKey()) != e.getValue().state()) {
                if (bad++ == 0) {
                    first = e.getKey().toShortString() + " " + level.getBlockState(e.getKey()) + " vs " + e.getValue().state();
                }
            }
        }
        check("every ledger entry equals the world (" + where + ")", bad == 0, bad + " differ " + first);
    }

    /** {lit, unlit} lights the ledger lists for the colony, read from the world. */
    private int[] lights() {
        final int[] n = new int[2];
        for (final Map.Entry<BlockPos, DecorLedger.Entry> e : ledger.entries(colonyId).entrySet()) {
            final BlockState now = level.getBlockState(e.getKey());
            if (TreeLighting.isLight(now)) {
                n[now.getValue(Lights.LIT) ? 0 : 1]++;
            }
        }
        return n;
    }

    /** Lights of the colony's buildings (not the square) that are lit. */
    private int buildingLightsLit() {
        int n = 0;
        for (final DecorLedger.GroupView g : ledger.groups(colonyId)) {
            if (g.owner().kind() != DecorLedger.Kind.BUILDING) {
                continue;
            }
            for (final BlockPos pos : g.entries().keySet()) {
                final BlockState now = level.getBlockState(pos);
                n += TreeLighting.isLight(now) && now.getValue(Lights.LIT) ? 1 : 0;
            }
        }
        return n;
    }

    private FestivalData data() {
        return FestivalData.get(server);
    }

    private FestivalData.ColonyState festival() {
        return data().colony(colonyId);
    }

    private FakePlayer player(final UUID id, final String name) {
        final FakePlayer p = FakePlayerFactory.get(level, new GameProfile(id, name));
        p.setPos(marker.getX() + 0.5, marker.getY(), marker.getZ() + 3.5);
        p.getInventory().selected = 8;                      // gifts land in slot 0, the hand stays empty
        return p;
    }

    /** A right-click with whatever is in the main hand, the way the server handles one (the event, then the block). */
    private InteractionResult click(final FakePlayer p, final BlockPos pos) {
        return p.gameMode.useItemOn(p, level, p.getMainHandItem(), InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
    }

    private static int count(final Player p, final java.util.function.Predicate<ItemStack> what) {
        int n = 0;
        for (final ItemStack s : p.getInventory().items) {
            n += what.test(s) ? s.getCount() : 0;
        }
        return n;
    }

    private static int gifts(final Player p, final ResourceKey<LootTable> table) {
        return count(p, s -> s.is(Gifts.GIFT.get()) && Gifts.tableOf(s).equals(table));
    }

    /** Opens one gift of the player's (the way the server handles a right-click with it); returns the items it gave. */
    private int open(final FakePlayer p) {
        for (int slot = 0; slot < p.getInventory().items.size(); slot++) {
            final ItemStack s = p.getInventory().items.get(slot);
            if (s.is(Gifts.GIFT.get())) {
                final ItemStack one = s.split(1);
                p.setItemInHand(InteractionHand.MAIN_HAND, one);
                final int before = count(p, x -> !x.isEmpty() && !x.is(Gifts.GIFT.get()));
                p.gameMode.useItem(p, level, one, InteractionHand.MAIN_HAND);
                final int after = count(p, x -> !x.isEmpty() && !x.is(Gifts.GIFT.get()));
                p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                return after - before;
            }
        }
        return -1;
    }

    // ================================================================ setup

    private void planSetup(final boolean observatory) {
        step(new Step("setup: the colony, its buildings, the Decorator, workers and off-duty colonists, the marker", 80, true) {
            @Override
            void start() {
                setDate(DECOR_DAY);
                quietWorld();
                ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, 2, 2) - 1;
                final BlockPos center = new BlockPos(2, ground + 1, 2);
                for (int cx = -6; cx <= 6; cx++) {
                    for (int cz = -6; cz <= 6; cz++) {
                        level.setChunkForced(cx, cz, true);
                    }
                }
                owner = FakePlayerFactory.getMinecraft(level);
                owner.setPos(center.getX() + 0.5, center.getY(), center.getZ() + 0.5);
                colony = IColonyManager.getInstance().createColony(level, center, owner, "Seasons Festival Test", "Medieval Oak");
                colonyId = colony == null ? -1 : colony.getID();
                check("a colony was made", colony != null, "id " + colonyId + " at " + center);
                if (colony == null) {
                    finish();
                    return;
                }
                townHall = register("minecolonies:blockhuttownhall", house(-3, -1, 11, 7, 2), 1, null, null);
                final IBuilding d = register(BuiltInRegistries.BLOCK.getKey(DecoratorSetup.HUT.get()).toString(), house(12, -1, 11, 7, 17), 3, null, null);
                hut = d instanceof BuildingDecorator b ? b : null;
                final IBuilding lumberjack = register("minecolonies:blockhutlumberjack", house(-40, 16, 11, 7, -35), 1,
                        "Medieval Oak", "fundamentals/lumberjack1.blueprint");
                final IBuilding mason = register("minecolonies:blockhutstonemason", house(-22, 16, 11, 7, -17), 1,
                        "Medieval Oak", "craftsmanship/masonry/stonemason1.blueprint");
                lumberjackHut = lumberjack;
                masonHut = mason;
                check("the buildings are registered", townHall != null && hut != null && lumberjack != null && mason != null, "");
                if (hut == null || lumberjack == null || mason == null) {
                    finish();
                    return;
                }
                decorator = colony.getCitizenManager().createAndRegisterCivilianData();
                colony.getCitizenManager().spawnOrCreateCitizen(decorator, level, hut.getPosition().offset(0, 0, 4));
                check("a citizen is hired as the Decorator", hut.getModule(DecoratorModules.WORK).assignCitizen(decorator)
                        && decorator.getJob() instanceof JobDecorator, decorator.getName());
                workers.add(hire(lumberjack, lumberjack.getPosition().offset(0, 0, 5)));
                workers.add(hire(mason, mason.getPosition().offset(0, 0, 5)));
                if (observatory) {
                    final IBuilding obs = register("voyager:blockhutobservatory", house(-4, 16, 11, 7, 1), 1, null, null);
                    check("Voyager's Observatory registers next to the mod", obs != null, "");
                    if (obs != null) {
                        astronomer = hire(obs, obs.getPosition().offset(0, 0, 5));
                        check("the Astronomer is hired", astronomer.getJob() != null
                                && astronomer.getJob().getClass().getSimpleName().equals("JobAstronomer"),
                                astronomer.getJob() == null ? "no job" : astronomer.getJob().getClass().getSimpleName());
                    }
                }
                for (int i = 0; i < 3; i++) {
                    offDuty.add(spawn(center.offset(-2 + 2 * i, 0, 8)));
                }
                for (final ICitizenData c : offDuty) {
                    check("an off-duty colonist has no job", c.getJob() == null, c.getName());
                }
                // the festival marker where the tree should stand, put down by the "player" the way a player does
                // (square mode: no marker; the cell is where the square's tree tag turns out to be, the middle of its plot)
                marker = new BlockPos(2, ground + 1, 32);
                if (!"square".equals(mode)) {
                    final BlockState m = SeasonsBlocks.FESTIVAL_MARKER.get().defaultBlockState().setValue(FestivalMarkerBlock.FACING, Direction.NORTH);
                    level.setBlock(marker, m, Block.UPDATE_ALL);
                    SeasonsBlocks.FESTIVAL_MARKER.get().setPlacedBy(level, marker, m, owner, new ItemStack(SeasonsBlocks.FESTIVAL_MARKER_ITEM.get()));
                    checkEq("the marker is the colony's square", ledger.marker(colonyId), marker);
                    check("[V] the marker's block entity is a blueprint-data provider (a leisure site can stroll in it)",
                            level.getBlockEntity(marker) instanceof com.ldtteam.structurize.blockentities.interfaces.IBlueprintDataProviderBE be
                                    && be.getInWorldCorners().getA().equals(marker.offset(-6, -1, -6)), "");
                }
                // the town hall gets its decorations (with strings of lights at hut level 3), so lighting the tree has strings to light
                DecorWork.decorate(level, colonyId, DecorWork.Target.building(townHall), 3, DecorPlacer.Stock.FREE, day(), ledger);
                one = player(P1, "csfestival_one");
                two = player(P2, "csfestival_two");
                visitor = player(P3, "csfestival_visitor");
                // the colony's players: friends of the colony (MineColonies' protection lets them use its blocks)
                for (final FakePlayer p : List.of(one, two)) {
                    colony.getPermissions().addPlayer(p.getGameProfile(), colony.getPermissions().getRankFriend());
                }
                check("the two players are of the colony and may use its blocks, the visitor may not",
                        colony.getPermissions().hasPermission(one, Action.RIGHTCLICK_BLOCK)
                                && colony.getPermissions().hasPermission(two, Action.RIGHTCLICK_BLOCK)
                                && !colony.getPermissions().hasPermission(visitor, Action.RIGHTCLICK_BLOCK), "");
                setTimeOfDay(MORNING);
            }

            @Override
            boolean tick() {
                return t >= 60;
            }

            @Override
            void end(final boolean done) {
                int strings = 0;
                for (final BlockPos pos : ledger.group(colonyId, DecorLedger.Owner.building(townHall.getPosition())) == null ? List.<BlockPos>of()
                        : ledger.group(colonyId, DecorLedger.Owner.building(townHall.getPosition())).entries().keySet()) {
                    strings += TreeLighting.isLight(level.getBlockState(pos)) ? 1 : 0;
                }
                check("the town hall has strings of lights, unlit", strings > 0 && buildingLightsLit() == 0, strings + " lights");
                note("citizens: decorator {}, workers {}, off duty {}", state(decorator), workers.stream().map(CsFestival::state).toList(),
                        offDuty.stream().map(CsFestival::state).toList());
            }
        });
    }

    // ================================================================ the Town Square (0.2.0, Q4)

    /** The square's one blueprint level in this build, and its plot: the low corner of its 21 x 21 box, so that its tree cell is where the marker stands in the other modes. */
    private static final String SQUARE_FILE = "square/townsquare1.blueprint";
    private static final BlockPos SQUARE_PLOT = new BlockPos(-8, 0, 22);

    /** The floor block of the launch tag's cell (the cell is air: the rockets leave from the top of the floor). */
    private BlockPos launchBlock() {
        final List<BlockPos> cells = townSquare.getLocationsFromTag(BuildingTownSquare.TAG_LAUNCH);
        return cells.isEmpty() ? null : cells.get(0).below();
    }

    /** The standing places of the square's gather cells, as the crowd should find them. */
    private Set<BlockPos> gatherStands() {
        final Set<BlockPos> out = new LinkedHashSet<>();
        for (final BlockPos cell : townSquare.getLocationsFromTag(BuildingTownSquare.TAG_GATHER)) {
            final BlockPos stand = Gathering.standable(level, cell);
            if (stand != null) {
                out.add(stand);
            }
        }
        return out;
    }

    /** Sets a level this build has no blueprint for (MineColonies refuses a level above the building's maximum): a level 3 square puts up the full spruce with its gift pile and crate. */
    private void forceLevel(final IBuilding building, final int lv) {
        try {
            final java.lang.reflect.Field f = com.minecolonies.core.colony.buildings.AbstractSchematicProvider.class.getDeclaredField("buildingLevel");
            f.setAccessible(true);
            f.setInt(building, lv);
        } catch (final ReflectiveOperationException unreachable) {
            check("the level of the square can be set to " + lv, false, unreachable.getClass().getSimpleName());
        }
    }

    /** The square's blueprint pasted with its box's low corner at the plot (Structurize's creative handler); returns the hut block's position, or null. */
    private BlockPos pasteSquare() {
        try {
            final Blueprint blueprint = StructurePacks.getBlueprintFuture(PieceLoader.PACK, SQUARE_FILE, level.registryAccess())
                    .get(60, java.util.concurrent.TimeUnit.SECONDS);
            if (blueprint == null) {
                check("the square's blueprint loads", false, SQUARE_FILE);
                return null;
            }
            blueprint.setRotationMirror(RotationMirror.NONE, level);
            final BlockPos plot = SQUARE_PLOT.offset(0, ground, 0);
            final BlockPos anchor = plot.offset(blueprint.getPrimaryBlockOffset());
            final CreativeStructureHandler handler = new CreativeStructureHandler(level, anchor, blueprint, RotationMirror.NONE, true);
            final StructurePlacer placer = new StructurePlacer(handler);
            int rounds = 0;
            while (rounds++ < 4000) {
                final StructurePhasePlacementResult result = placer.executeStructureStep(level, null, placer.getIterator().getProgressPos(),
                        StructurePlacer.Operation.BLOCK_PLACEMENT, () -> placer.getIterator().increment((info, at, h) -> false), false);
                if (result.getIteratorPos().equals(AbstractBlueprintIterator.NULL_POS)) {
                    break;
                }
            }
            return anchor;
        } catch (final Exception unreadable) {
            check("the square's blueprint pastes", false, unreadable.getClass().getSimpleName().replace("Exception", ""));
            return null;
        }
    }

    /** The square goes up, is registered as the colony's main square (level 3, so the full spruce) and gets the Decorator. */
    private void planSquarePlace() {
        step(new Step("square: the Town Square pasted, registered, its Decorator assigned", 1) {
            @Override
            void start() {
                final BlockPos hutAt = pasteSquare();
                if (hutAt == null) {
                    finish();
                    return;
                }
                IBuilding building = colony.getServerBuildingManager().getBuilding(hutAt);
                if (building == null && level.getBlockEntity(hutAt) instanceof AbstractTileEntityColonyBuilding te) {
                    building = colony.getServerBuildingManager().addNewBuilding(te, level);
                }
                if (!(building instanceof BuildingTownSquare square)) {
                    check("the pasted square registers as a Town Square", false, String.valueOf(building));
                    finish();
                    return;
                }
                final BlockPos plot = SQUARE_PLOT.offset(0, ground, 0);
                square.setCorners(plot, plot.offset(20, 19, 20));
                square.setBuildingLevel(1);
                square.setStructurePack(PieceLoader.PACK);
                square.setBlueprintPath(SQUARE_FILE);
                square.assignment().setHiringMode(com.minecolonies.api.colony.buildings.HiringMode.MANUAL);
                square.markDirty();
                check("the Decorator is assigned to the square (he stays employed at his Workshop)",
                        square.assignment().assignCitizen(decorator) && decorator.getWorkBuilding() == hut
                                && decorator.getJob() instanceof JobDecorator j && j.findSquare() == square, "");
                forceLevel(square, 3);
                townSquare = square;
            }
        });
        step(new Step("square: its tags are read, it is the colony's main square", 200) {
            @Override
            boolean tick() {
                return Squares.treeCell(townSquare) != null;
            }

            @Override
            void end(final boolean done) {
                check("the square's tree tag reads", done, "after " + t + " ticks");
                if (!done) {
                    finish();
                    return;
                }
                marker = Squares.treeCell(townSquare);
                Squares.refresh(level, colony, ledger);
                for (final FakePlayer pl : List.of(one, two, visitor)) {
                    pl.setPos(marker.getX() + 0.5, marker.getY(), marker.getZ() + 3.5);
                }
                note("the square's tree cell {}, its hut {}", marker.toShortString(), townSquare.getPosition().toShortString());
                check("the square is level 3 and the colony's main square (its mark is stored)",
                        townSquare.getBuildingLevel() == 3 && ledger.squareMark(colonyId) != null
                                && ledger.squareMark(colonyId).anchor().equals(marker), String.valueOf(ledger.squareMark(colonyId)));
            }
        });
    }

    /** What the square gives the events once its tree stands: the site, the centre, the crowd's spots, the show's cell. */
    private void planSquareEvents() {
        step(new Step("square: the gathering, the lighting and the show find the square's tags", 1) {
            @Override
            void start() {
                final Squares.Centre centre = Squares.centre(ledger, colonyId);
                check("the colony's festival centre is the square (tree cell and hut)", centre != null && centre.onSquare()
                        && centre.anchor().equals(marker) && centre.hut().equals(townSquare.getPosition()), String.valueOf(centre));
                checkEq("no festival marker is registered in this colony", ledger.marker(colonyId), null);
                checkEq("the gathering's site is the square's tree cell (not the town hall)", Gathering.site(level, colony), marker);
                final Set<BlockPos> stands = gatherStands();
                final List<BlockPos> spots = Gathering.spots(level, colony, marker);
                check("the crowd's spots are exactly the standing places of the square's gather tags", stands.size() >= 8
                        && spots.size() == stands.size() && new LinkedHashSet<>(spots).equals(stands), spots.size() + " spots, " + stands.size() + " tagged");
                final AABB box = treeBox();
                check("no spot is inside the tree's box", box != null && spots.stream().noneMatch(sp -> box.intersects(new AABB(sp))), String.valueOf(box));
                checkEq("a Town Square keeps its crowd within the square's keep radius", Gathering.keepRadius(level, colony, marker), Gathering.SQUARE_KEEP_RADIUS);
                check("the tree is finished from the square's group (the lighting reads the tree cell)", TreeLighting.tree(ledger, colonyId) != null, "");
                final BlockPos crate = firstOf(st -> st.is(SeasonsBlocks.FIREWORK_CRATE.get()));
                checkEq("the show leaves from the crate the square's tree has", Fireworks.site(level, colony), crate);
                // where the Decorator waits for the show: never inside the tree (S10c), also when it leaves from the crate by the trunk
                final BlockPos naive = crate == null ? null : Gathering.standable(level, crate.relative(Direction.SOUTH, 2));
                note("two blocks south of the crate {} is {} ({} the tree's box)", crate, naive,
                        naive != null && box != null && box.intersects(body(naive)) ? "inside" : "outside");
                final BlockPos fromCrate = crate == null ? null : EntityAIWorkDecorator.squareLaunchStand(level, box, marker, crate);
                check("he waits for the show from the crate outside the tree's box", fromCrate != null && box != null
                        && !box.intersects(body(fromCrate)), fromCrate + ", crate " + crate + ", box " + box);
                final BlockPos launch = launchBlock();
                final BlockPos fromLaunch = launch == null ? null : EntityAIWorkDecorator.squareLaunchStand(level, box, marker, launch);
                check("he waits for the show from the launch cell beside it, outside the tree's box", fromLaunch != null && box != null
                        && !box.intersects(body(fromLaunch)) && fromLaunch.distSqr(launch) <= 3 * 3 + 3 * 3, fromLaunch + ", launch " + launch);
                check("the square's hut is not a leisure site outside the hours",
                        !colony.getServerBuildingManager().getLeisureSites().contains(townSquare.getPosition()), "");
                check("the hut block's block entity is a blueprint-data provider (the leisure wander strolls in it)",
                        level.getBlockEntity(townSquare.getPosition()) instanceof com.ldtteam.structurize.blockentities.interfaces.IBlueprintDataProviderBE, "");
            }
        });
    }

    /** Per colonist: samples taken while he stood still, and samples where he stood on (within 3 blocks of) one of the crowd's spots. */
    private final Map<ICitizenData, int[]> still = new LinkedHashMap<>();

    private void sampleSpots(final List<ICitizenData> who) {
        if (timeOfDay() < SAMPLE_FROM) {
            return;
        }
        final List<BlockPos> spots = Gathering.spots(level, colony, marker);
        for (final ICitizenData c : who) {
            final AbstractEntityCitizen e = c.getEntity().orElse(null);
            if (e == null || !e.getNavigation().isDone() || dist(c) > Gathering.SQUARE_KEEP_RADIUS) {
                continue;
            }
            final int[] n = still.computeIfAbsent(c, k -> new int[2]);
            n[0]++;
            n[1] += spots.stream().anyMatch(sp -> e.position().distanceToSqr(Vec3.atBottomCenterOf(sp)) <= 9.0) ? 1 : 0;
        }
    }

    private void checkSpots() {
        int total = 0;
        int onSpot = 0;
        for (final int[] n : still.values()) {
            total += n[0];
            onSpot += n[1];
        }
        note("colonists standing still: {} samples, {} of them on a gather spot", total, onSpot);
        check("the crowd stands on the gather spots (most of the samples of colonists standing still are within 3 blocks of one)",
                total >= 5 && onSpot * 4 >= total * 3, onSpot + " of " + total);
    }

    /** The 0.1.0 ring still stands behind the tags: with the gather tags gone the crowd finds the ring, the tags back give them back. */
    private void planSquareNoTags() {
        step(new Step("square: no gather tags, the crowd falls back to the ring round the tree", 1) {
            @Override
            void start() {
                if (!(level.getBlockEntity(townSquare.getPosition()) instanceof com.ldtteam.structurize.blockentities.interfaces.IBlueprintDataProviderBE be)) {
                    check("the hut's block entity holds the tags", false, "");
                    return;
                }
                final Set<BlockPos> stands = gatherStands();
                final List<BlockPos> rel = new ArrayList<>();
                for (final Map.Entry<BlockPos, List<String>> e : be.getPositionedTags().entrySet()) {
                    if (e.getValue().contains(BuildingTownSquare.TAG_GATHER)) {
                        rel.add(e.getKey());
                    }
                }
                for (final BlockPos r : rel) {
                    be.removeTag(r, BuildingTownSquare.TAG_GATHER);
                }
                check("the gather tags are gone from the hut", townSquare.getLocationsFromTag(BuildingTownSquare.TAG_GATHER).isEmpty(), rel.size() + " removed");
                final List<BlockPos> ring = Gathering.spots(level, colony, marker);
                final AABB box = treeBox();
                check("the ring has spots, at most 24, none in the tree's box, all within the square's keep radius", !ring.isEmpty() && ring.size() <= 24
                        && ring.stream().noneMatch(sp -> box != null && box.intersects(new AABB(sp)))
                        && ring.stream().allMatch(sp -> sp.distSqr(marker) <= (double) Gathering.SQUARE_KEEP_RADIUS * Gathering.SQUARE_KEEP_RADIUS), ring.size() + " spots");
                check("they are not the tag spots", !new LinkedHashSet<>(ring).equals(stands), "");
                for (final BlockPos r : rel) {
                    be.addTag(r, BuildingTownSquare.TAG_GATHER);
                }
                checkEq("the tags back, the crowd's spots are the tag spots again", new LinkedHashSet<>(Gathering.spots(level, colony, marker)), stands);
            }
        });
    }

    // ================================================================ the rules without a world

    /** The festival hours guard (the S0 hand-off) and the three values of events.whoComes, without waiting for the clock. */
    private void planRules() {
        step(new Step("rules: the hours guard and who comes", 1) {
            @Override
            void start() {
                final int from = SeasonsConfig.DEFAULT_GATHER_FROM_TICK;
                final int until = SeasonsConfig.DEFAULT_GATHER_UNTIL_TICK;
                checkEq("the default hours are kept", toList(Gathering.hours()), List.of(from, until));
                checkEq("an end past bedtime is cut to " + Gathering.LATEST_UNTIL, toList(Gathering.hours(9000, 12000)),
                        List.of(9000, Gathering.LATEST_UNTIL));
                checkEq("an end not after the start falls back to the defaults", toList(Gathering.hours(9000, 9000)), List.of(from, until));
                checkEq("an end before the start falls back to the defaults", toList(Gathering.hours(10000, 9000)), List.of(from, until));
                checkEq("a start after the latest end falls back to the defaults", toList(Gathering.hours(10550, 10590)), List.of(from, until));
                check("the latest end leaves time before bedtime", Gathering.LATEST_UNTIL < Gathering.BEDTIME, "");
                // who comes: throwaway colony ids, so the real colony's saved day is not touched
                final FestivalData data = data();
                final int id = 9999;
                final long realDay = FESTIVAL_DAY.toEpochDay();
                check("everyone_once_a_day: everyone on the first festival game day of the real day",
                        Gathering.everyoneNow(Gathering.WHO_EVERYONE_ONCE, id, data, realDay, 40), "");
                check("everyone_once_a_day: the same game day again, still everyone",
                        Gathering.everyoneNow(Gathering.WHO_EVERYONE_ONCE, id, data, realDay, 40), "");
                check("everyone_once_a_day: the next game day of that real day, only the off-duty ones",
                        !Gathering.everyoneNow(Gathering.WHO_EVERYONE_ONCE, id, data, realDay, 41), "");
                check("everyone_once_a_day: the next real day, everyone again",
                        Gathering.everyoneNow(Gathering.WHO_EVERYONE_ONCE, id, data, realDay + 1, 42), "");
                check("off_duty: never the workers", !Gathering.everyoneNow(Gathering.WHO_OFF_DUTY, id + 1, data, realDay, 40), "");
                check("everyone: the workers on every festival game day", Gathering.everyoneNow(Gathering.WHO_EVERYONE, id + 2, data, realDay, 40)
                        && Gathering.everyoneNow(Gathering.WHO_EVERYONE, id + 2, data, realDay, 41), "");
            }
        });
    }

    private static List<Integer> toList(final int[] hours) {
        return List.of(hours[0], hours[1]);
    }

    // ================================================================ the tree

    private void planTree() {
        step(new Step("the tree goes up at the marker (village_spruce_full, hut level 3)", 800, true) {
            DecorWork.Outcome last;
            int notices0;

            @Override
            void start() {
                notices0 = TreeLighting.notices();
            }

            @Override
            boolean tick() {
                if (t % 20 == 0 && (last == null || !last.complete())) {
                    last = DecorWork.decorate(level, colonyId, treeTarget(), 3, DecorPlacer.Stock.FREE, day(), ledger);
                }
                return last != null && last.complete() && t % 20 == 0 && TreeLighting.notices() > notices0;
            }

            @Override
            void end(final boolean done) {
                final DecorLedger.GroupView g = ledger.group(colonyId, square());
                check("the tree is finished at the marker", last != null && last.complete() && g != null && g.size() > 50,
                        (last == null ? "-" : last.status()) + ", " + (g == null ? 0 : g.size()) + " cells");
                check("it has ornaments, the star, the gift pile and the firework crate", firstOf(s -> s.is(SeasonsBlocks.ORNAMENT_LEAVES.get())) != null
                                && firstOf(s -> s.is(SeasonsBlocks.TREE_STAR.get())) != null && firstOf(s -> s.is(SeasonsBlocks.GIFT_PILE.get())) != null
                                && firstOf(s -> s.is(SeasonsBlocks.FIREWORK_CRATE.get())) != null, "");
                checkEq("the colony's players are told once that the tree is finished", TreeLighting.notices() - notices0, 1);
                checkEq("the day it was finished is saved (the Decorator's two-day timer)", festival().treeReadyDay(), day());
                checkEq("the ledger notes the day the tree itself was up", g == null ? -1L : g.treeDay(), day());
                check("its trunk is the festival trunk, in no log tag (the Forester never takes it for a tree)",
                        firstOf(st -> st.is(SeasonsBlocks.FESTIVAL_TRUNK.get())) != null && firstOf(st -> st.is(BlockTags.LOGS)) == null, "");
                check("the tree is not lit yet", !festival().treeLit() && lights()[0] == 0, lights()[1] + " unlit lights");
                invariant("the tree up");
            }
        });
    }

    // ================================================================ lighting

    private void planLighting() {
        step(new Step("a player's right-click on the tree lights the colony", 200, true) {
            int lightings0;
            int rockets0;
            InteractionResult result;

            @Override
            void start() {
                lightings0 = TreeLighting.lightings();
                rockets0 = Fireworks.rockets();
                final BlockPos log = firstOf(s -> s.is(SeasonsBlocks.FESTIVAL_TRUNK.get()));
                if (log != null) {
                    click(visitor, log);
                    check("a visitor (not of the colony) cannot light the tree", !festival().treeLit() && lights()[0] == 0
                            && TreeLighting.lightings() == lightings0, lights()[0] + " lit");
                }
                result = log == null ? null : click(one, log);
            }

            @Override
            boolean tick() {
                return t >= 120;
            }

            @Override
            void end(final boolean done) {
                check("the click on a log of the tree is taken", result != null && result.consumesAction(), String.valueOf(result));
                final int[] n = lights();
                check("every light of the colony is lit: the ornaments, the star and the strings on the town hall",
                        n[0] > 0 && n[1] == 0 && buildingLightsLit() > 0, n[0] + " lit, " + n[1] + " unlit, " + buildingLightsLit() + " on buildings");
                check("the star is lit", firstOf(s -> s.is(SeasonsBlocks.TREE_STAR.get()) && s.getValue(Lights.LIT)) != null, "");
                check("the tree is lit by the player (saved)", festival().treeLit() && TreeLighting.BY_PLAYER.equals(festival().litBy()), festival().litBy());
                checkEq("one lighting, with the chime", TreeLighting.lightings() - lightings0, 1);
                checkEq("a few rockets went up from the square", Fireworks.rockets() - rockets0, Fireworks.LIGHTING_ROCKETS);
                invariant("lit by a player");
                final InteractionResult again = click(one, firstOf(s -> s.is(SeasonsBlocks.FESTIVAL_TRUNK.get())));
                checkEq("a second click on the lit tree lights nothing again", TreeLighting.lightings() - lightings0, 1);
                note("second click: {}", again);
            }
        });
    }

    private void planDecoratorLights() {
        final long[] ready = new long[1];
        step(new Step("the Decorator lights it: a fresh unlit tree", 120, true) {
            int notices0;

            @Override
            void start() {
                // every light off again (the ledger follows), and the colony's tree forgotten: as if just finished
                for (final Map.Entry<BlockPos, DecorLedger.Entry> e : ledger.entries(colonyId).entrySet()) {
                    final BlockState s = level.getBlockState(e.getKey());
                    if (TreeLighting.isLight(s) && s.getValue(Lights.LIT)) {
                        level.setBlock(e.getKey(), s.setValue(Lights.LIT, false), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                    }
                }
                data().resetTree(colonyId);
                notices0 = TreeLighting.notices();
            }

            @Override
            boolean tick() {
                return t >= 60 && TreeLighting.notices() > notices0;
            }

            @Override
            void end(final boolean done) {
                ready[0] = festival().treeReadyDay();
                check("the fresh tree is told again and timed from today", TreeLighting.notices() == notices0 + 1 && ready[0] == day(),
                        "ready day " + ready[0] + ", today " + day());
                invariant("all lights off");
            }
        });
        step(new Step("one game day later nobody lit it and it stays dark", 80, true) {
            @Override
            void start() {
                nextDayAt(MORNING);
            }

            @Override
            boolean tick() {
                return t >= 60;
            }

            @Override
            void end(final boolean done) {
                check("one day later it is still dark", !festival().treeLit() && lights()[0] == 0, "day " + day() + ", ready " + ready[0]);
            }
        });
        step(new Step("two game days later the Decorator lights it", 200, true) {
            int lightings0;

            @Override
            void start() {
                lightings0 = TreeLighting.lightings();
                nextDayAt(MORNING);
            }

            @Override
            boolean tick() {
                return t >= 60 && festival().treeLit();
            }

            @Override
            void end(final boolean done) {
                final int[] n = lights();
                check("two game days after it was finished the Decorator lit it", done && TreeLighting.BY_DECORATOR.equals(festival().litBy())
                        && TreeLighting.lightings() == lightings0 + 1 && n[1] == 0 && n[0] > 0, "day " + day() + ", ready " + ready[0]
                        + ", by '" + festival().litBy() + "', " + n[0] + " lit, " + n[1] + " unlit");
                invariant("lit by the Decorator");
            }
        });
    }

    // ================================================================ gifts

    private void planGifts() {
        step(new Step("gifts: one a player and real day, the better ones on festival days", 1) {
            @Override
            void start() {
                final BlockPos pile = firstOf(s -> s.is(SeasonsBlocks.GIFT_PILE.get()));
                if (pile == null) {
                    check("the tree has a gift pile", false, "");
                    return;
                }
                final int given0 = Gifts.given();
                click(one, pile);
                checkEq("player one takes today's gift (the daily one)", gifts(one, Gifts.DAILY), 1);
                click(one, pile);
                checkEq("a second try the same real day is refused", gifts(one, Gifts.DAILY), 1);
                checkEq("the claim is saved for the real day", data().giftsOn(P1, DECOR_DAY.toEpochDay()), 1);
                click(two, pile);
                checkEq("player two takes a gift of his own", gifts(two, Gifts.DAILY), 1);
                click(visitor, pile);
                checkEq("a visitor (not of the colony) gets no gift", gifts(visitor, Gifts.DAILY), 0);
                // a gift pile a player put down himself is a decoration
                final BlockPos own = marker.offset(5, 0, -4);
                final BlockState ownWas = level.getBlockState(own);
                level.setBlock(own, SeasonsBlocks.GIFT_PILE.get().defaultBlockState(), Block.UPDATE_ALL);
                setDate(LocalDate.of(2026, 12, 11));
                click(one, own);
                checkEq("a gift pile a player placed hands out nothing", gifts(one, Gifts.DAILY) + gifts(one, Gifts.FESTIVAL), 1);
                checkEq("gifts handed out so far", Gifts.given() - given0, 2);
                final int got = open(one);
                check("a gift opens into the items of its table, and is used up", got > 0 && gifts(one, Gifts.DAILY) == 0, got + " items");
                // the Winter Festival days: the better gift, once again per real day
                setDate(FESTIVAL_DAY);
                click(one, pile);
                click(one, pile);
                checkEq("on 25 Dec the gift is the Winter Festival's, one only", gifts(one, Gifts.FESTIVAL), 1);
                final int festive = open(one);
                check("the Winter Festival gift opens", festive > 0, festive + " items");
                // New Year: a few festival rockets inside
                setDate(NEW_YEAR_EVE);
                click(one, pile);
                checkEq("on 31 Dec the gift is New Year's", gifts(one, Gifts.NEW_YEAR), 1);
                final int rockets0 = count(one, s -> s.is(Items.FIREWORK_ROCKET));
                open(one);
                final int rockets = count(one, s -> s.is(Items.FIREWORK_ROCKET)) - rockets0;
                final boolean colourful = count(one, s -> s.is(Items.FIREWORK_ROCKET) && s.get(DataComponents.FIREWORKS) != null
                        && !s.get(DataComponents.FIREWORKS).explosions().isEmpty()) >= 3;
                check("the New Year gift holds a few festival rockets", rockets >= 3 && colourful, rockets + " rockets");
                level.setBlock(own, ownWas, Block.UPDATE_ALL);
                setDate(DECOR_DAY);
                invariant("after the gifts");
            }
        });
    }

    // ================================================================ the gathering

    /** Per colonist: samples taken and samples within {@link #WITHIN} blocks of the tree. */
    private final Map<ICitizenData, int[]> near = new LinkedHashMap<>();

    private void sample(final List<ICitizenData> who) {
        for (final ICitizenData c : who) {
            final int[] n = near.computeIfAbsent(c, k -> new int[2]);
            n[0]++;
            n[1] += dist(c) <= within() ? 1 : 0;
        }
    }

    private String nearText(final List<ICitizenData> who) {
        final List<String> out = new ArrayList<>();
        for (final ICitizenData c : who) {
            final int[] n = near.getOrDefault(c, new int[2]);
            out.add(n[1] + "/" + n[0]);
        }
        return out.toString();
    }

    private boolean mostlyNear(final List<ICitizenData> who) {
        for (final ICitizenData c : who) {
            final int[] n = near.getOrDefault(c, new int[2]);
            if (n[0] == 0 || n[1] * 2 < n[0]) {
                return false;
            }
        }
        return !who.isEmpty();
    }

    /**
     * The gathering of 25 Dec. {@code everyoneDay}: the first festival game day of the real day, so with the mixin the
     * workers come too; without it (mode nomixin) only the off-duty ones.
     */
    private void planGathering(final boolean everyoneDay) {
        final boolean mixin = !"nomixin".equals(mode);
        final int[] brought0 = new int[1];
        final int[] calls0 = new int[1];
        // his hosting visits counted from the morning: he may enter Hosting on the very tick the hours begin, before the
        // hours step starts, and then stays in it the whole afternoon (one visit, which a count from that step would miss)
        final int[] hosting0 = new int[1];
        final Set<String> workerStates = new LinkedHashSet<>();
        step(new Step("gathering: 25 Dec, the morning before the hours", 700, true) {
            @Override
            void start() {
                setDate(FESTIVAL_DAY);
                nextDayAt(BEFORE_HOURS);
                near.clear();
                brought0[0] = Gathering.broughtWorkers();
                calls0[0] = Gathering.mixinCalls();
                hosting0[0] = job() == null ? 0 : job().visits(EntityAIWorkDecorator.DecoratorState.HOSTING);
            }

            @Override
            boolean tick() {
                return timeOfDay() >= SeasonsConfig.DEFAULT_GATHER_FROM_TICK;
            }

            @Override
            void end(final boolean done) {
                check("before the hours nobody is brought", Gathering.broughtWorkers() == brought0[0], "");
                if ("nomixin".equals(mode)) {
                    check("the nomixin jar has no mixin config", ColonySeasons.class.getResource("/colony_seasons.mixins.json") == null, "");
                } else {
                    check("the mod jar has its mixin config", ColonySeasons.class.getResource("/colony_seasons.mixins.json") != null, "");
                }
            }
        });
        step(new Step("gathering: the festival hours", 2300, true) {
            boolean leisure;
            int handed0;
            int songs0;
            int trips0;
            int hostSamples;
            int hostInside;
            final Set<String> statusKeys = new LinkedHashSet<>();

            @Override
            void start() {
                handed0 = job() == null ? 0 : job().giftsHanded();
                songs0 = Gathering.songs();
                trips0 = Gathering.tripsEnded();
            }

            @Override
            boolean tick() {
                final long time = timeOfDay();
                if (time >= SAMPLE_FROM && t % 20 == 0) {
                    sample(offDuty);
                    sample(workers);
                    for (final ICitizenData w : workers) {
                        workerStates.add(state(w));
                    }
                    leisure |= colony.getServerBuildingManager().getLeisureSites().contains(leisureCell());
                    if (townSquare != null) {
                        sampleSpots(offDuty);
                    }
                    if (job() != null) {
                        statusKeys.add(job().statusKey());
                        if (DecoratorTexts.HOSTING.equals(job().statusKey())) {
                            hostSamples++;
                            hostInside += inside(decorator, treeBox()) ? 1 : 0;
                        }
                    }
                }
                return time >= SeasonsConfig.DEFAULT_GATHER_UNTIL_TICK - 40;
            }

            @Override
            void end(final boolean done) {
                check("the off-duty colonists stand within " + within() + " blocks of the tree in the hours", mostlyNear(offDuty), nearText(offDuty));
                if (everyoneDay && mixin) {
                    check("the everyone day: the workers come too (the mixin)", mostlyNear(workers) && Gathering.broughtWorkers() > brought0[0],
                            nearText(workers) + ", states " + workerStates);
                    checkEq("today's everyone day is saved for the colony", festival().everyoneGameDay(), day());
                } else {
                    check("without the mixin the workers are not brought", Gathering.broughtWorkers() == brought0[0], "states " + workerStates);
                }
                if (mixin) {
                    check("the mixin is applied and asked", Gathering.mixinCalls() > calls0[0], (Gathering.mixinCalls() - calls0[0]) + " calls");
                } else {
                    checkEq("the mixin is absent and never asked", Gathering.mixinCalls(), 0);
                }
                check("[V] the " + (townSquare != null ? "Town Square's hut" : "marker") + " is one of the colony's leisure sites in the hours", leisure, "");
                if (townSquare != null) {
                    checkSpots();
                }
                check("the song played", Gathering.songs() > songs0, (Gathering.songs() - songs0) + " times");
                final int hosting = job() == null ? 0 : job().visits(EntityAIWorkDecorator.DecoratorState.HOSTING) - hosting0[0];
                final int handed = job() == null ? 0 : job().giftsHanded() - handed0;
                check("the Decorator hosts at the tree and hands colonists gifts (hearts, a line)", hosting > 0 && handed > 0,
                        hosting + " hosting visits, " + handed + " gifts, status '" + (job() == null ? "-" : job().status()) + "'");
                check("his status line in the hours is the hosting one (a lang key)", statusKeys.contains(DecoratorTexts.HOSTING), statusKeys.toString());
                check("he hosts standing outside the tree's box (S10c)", hostSamples > 0 && hostInside == 0, hostInside + " of " + hostSamples + " samples inside");
                note("{} leisure trips to other places ended so the colonist could come", Gathering.tripsEnded() - trips0);
            }
        });
        step(new Step("gathering: after the hours", 200, true) {
            @Override
            boolean tick() {
                return timeOfDay() >= 10570;
            }

            @Override
            void end(final boolean done) {
                final List<String> states = workers.stream().map(CsFestival::state).toList();
                check("after the hours the workers are back at work", states.stream().noneMatch("IDLE"::equals), states.toString());
                check("the " + (townSquare != null ? "Town Square's hut" : "marker") + " is off the leisure sites again",
                        !colony.getServerBuildingManager().getLeisureSites().contains(leisureCell()), "");
            }
        });
        step(new Step("gathering: bedtime", 2600, true) {
            int brought;
            int pulls;

            @Override
            void start() {
                brought = Gathering.broughtWorkers();
                pulls = Gathering.pulls();
            }

            @Override
            boolean tick() {
                return timeOfDay() >= 12800;
            }

            @Override
            void end(final boolean done) {
                final Map<String, String> states = new LinkedHashMap<>();
                boolean all = true;
                for (final ICitizenData c : colony.getCitizenManager().getCitizens()) {
                    final String s = state(c);
                    states.put(c.getName(), s);
                    all &= BEDTIME_STATES.contains(s) || c == astronomer;
                }
                // the test colony has no beds, so they look for one (FIND_BED) or sleep where they are: either is
                // MineColonies' own bedtime; none of them is held at the tree, at work or idle
                check("nobody is awake past bedtime (every colonist in MineColonies' sleep routine)", all, states.toString());
                checkEq("nobody was brought after the hours", Gathering.broughtWorkers(), brought);
                checkEq("nobody was walked to the tree after the hours", Gathering.pulls(), pulls);
            }
        });
    }

    private void planSecondDay() {
        final int[] brought0 = new int[1];
        step(new Step("the next game day of the same real day: only the off-duty colonists come", 2700, true) {
            @Override
            void start() {
                nextDayAt(BEFORE_HOURS);
                near.clear();
                brought0[0] = Gathering.broughtWorkers();
            }

            @Override
            boolean tick() {
                final long time = timeOfDay();
                if (time >= SAMPLE_FROM && t % 20 == 0) {
                    sample(offDuty);
                }
                return time >= SeasonsConfig.DEFAULT_GATHER_UNTIL_TICK - 40;
            }

            @Override
            void end(final boolean done) {
                check("the off-duty colonists gather again", mostlyNear(offDuty), nearText(offDuty));
                checkEq("the workers are not brought on the second game day of the real day", Gathering.broughtWorkers(), brought0[0]);
                check("the colony's everyone day is still the first one", festival().everyoneGameDay() == day() - 1,
                        festival().everyoneGameDay() + " vs today " + day());
            }
        });
    }

    // ================================================================ the night (mode voyager)

    private void planNight() {
        final Set<String> astro = new LinkedHashSet<>();
        step(new Step("Voyager: both mixins are in CitizenAI", 1) {
            @Override
            void start() {
                boolean ours = false;
                boolean ours2 = false;
                boolean theirs = false;
                for (final Method m : CitizenAI.class.getDeclaredMethods()) {
                    ours |= m.getName().contains("comeToTheGathering");
                    ours2 |= m.getName().contains("stayUpForTheShow");
                    theirs |= m.getName().contains("keepTheNightWatch");
                }
                check("our mixin is applied (the gathering and the show)", ours && ours2, ours + ", " + ours2);
                check("Voyager's night-shift mixin is applied next to it", theirs, "");
            }
        });
        step(new Step("Voyager: the Astronomer works at night", 1400, true) {
            @Override
            void start() {
                setTimeOfDay(13000);
            }

            @Override
            boolean tick() {
                if (t % 20 == 0 && astronomer != null) {
                    astro.add(state(astronomer));
                }
                return t >= 1200;
            }

            @Override
            void end(final boolean done) {
                check("the Astronomer works at night (Voyager's mixin), the others sleep", astro.contains("WORK") || astro.contains("WORKING"),
                        "astronomer states " + astro + ", off duty " + offDuty.stream().map(CsFestival::state).toList());
            }
        });
    }

    // ================================================================ fireworks

    private int rocketsInStock() {
        return hut.stock(Items.FIREWORK_ROCKET);
    }

    private void addRockets(final int n) {
        check("rockets fit in the hut", ItemHandlerHelper.insertItemStacked(hut.getItemHandlerCap(), new ItemStack(Items.FIREWORK_ROCKET, n), false).isEmpty(), "");
    }

    private void planDusk() {
        planShow(true);                               // the first dusk of the real day: the big show, by the Decorator
        dusk("the next dusk the same real day: a sparkle", true, Fireworks.SPARKLE_ROCKETS, 0, false);
        dusk("a dusk with nobody in the colony: nothing", false, 0, 0, false);
        // hut levels 1 and 2 build no crate, and the trunk stands on the marker: the rockets must go up beside the tree
        dusk("a dusk without the crate: a sparkle beside the tree", true, Fireworks.SPARKLE_ROCKETS, 5, true);
    }

    private void dusk(final String name, final boolean player, final int expected, final int stock, final boolean noCrate) {
        step(new Step("fireworks: " + name, 1600, true) {
            int before;
            int shows0;
            int rockets0;
            int byHim0;
            int byServer0;
            final Set<String> his = new LinkedHashSet<>();
            BlockPos site;
            /** The highest a rocket of this step got above the top of the block it left from, in whole blocks. */
            int rise = -1;

            @Override
            void start() {
                setDate(NEW_YEAR_EVE);
                present = player;
                if (stock > 0) {
                    addRockets(stock);
                }
                nextDayAt(12300);
                if (noCrate) {
                    final BlockPos crate = firstOf(s -> s.is(SeasonsBlocks.FIREWORK_CRATE.get()));
                    check("the tree has a firework crate to take away", crate != null, "");
                    if (crate != null) {
                        level.setBlock(crate, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);   // the ledger forgets it
                    }
                }
                site = Fireworks.site(level, colony);
                before = rocketsInStock();
                shows0 = Fireworks.shows();
                rockets0 = Fireworks.rockets();
                byHim0 = Fireworks.decoratorShows();
                byServer0 = Fireworks.serverShows();
            }

            @Override
            boolean tick() {
                if (t % 50 == 0) {
                    his.add(state(decorator));
                }
                if (site != null) {
                    for (final FireworkRocketEntity r : level.getEntitiesOfClass(FireworkRocketEntity.class, new AABB(site).inflate(64))) {
                        rise = Math.max(rise, (int) Math.floor(r.getY() - site.getY() - 1));
                    }
                }
                return timeOfDay() >= Fireworks.DUSK_FROM + Fireworks.LAUNCH_GRACE + 300;   // the fallback's time too
            }

            @Override
            void end(final boolean done) {
                final int used = before - rocketsInStock();
                final int flown = Fireworks.rockets() - rockets0;
                final int seen = level.getEntitiesOfClass(FireworkRocketEntity.class, new AABB(marker).inflate(64)).size();
                checkEq("rockets taken from the Decorator's stock (" + name + ")", used, expected);
                checkEq("rockets that flew from the square (" + name + ")", flown, expected);
                checkEq("shows (" + name + ")", Fireworks.shows() - shows0, expected > 0 ? 1 : 0);
                if (expected > 0) {
                    // a rocket started inside a block bursts on its first tick, where it started
                    check("the rockets climb into the sky (" + name + ")", rise >= 3, rise + " blocks above " + site);
                }
                if (noCrate) {
                    check("without the crate they leave beside the tree with open sky, not from the marker under the trunk",
                            site != null && !site.equals(marker) && Fireworks.openSky(level, site.above()), site + ", marker " + marker);
                    if (townSquare != null) {
                        checkEq("on a Town Square they leave from the floor of the launch tag's cell", site, launchBlock());
                    }
                    invariant("the crate taken away");
                } else if (townSquare != null) {
                    checkEq("on a Town Square with a crate they leave from the crate (" + name + ")", site, firstOf(s -> s.is(SeasonsBlocks.FIREWORK_CRATE.get())));
                }
                note("{} rocket entities about at the end, stock {} -> {}; launched by the Decorator {}, by the server {}; his states {}", seen,
                        before, rocketsInStock(), Fireworks.decoratorShows() - byHim0, Fireworks.serverShows() - byServer0, his);
                present = true;
            }
        });
    }

    /**
     * New Year's eve from the afternoon to after dusk, the first dusk of the real day (the big show from stock). With
     * the mixin the Decorator stays up past bedtime, waits at the square, launches the show himself and then goes to
     * bed; without it he goes to bed at bedtime like everybody else and the server launches the show from the square.
     */
    private void planShow(final boolean mixin) {
        final String name = mixin ? "fireworks: the Decorator stays up and launches the first dusk show of the real day"
                : "fireworks: without the mixin he sleeps and the first dusk show goes up from the square";
        step(new Step(name, 4200, true) {
            int before;
            int byHim0;
            int byServer0;
            int kept0;
            BlockPos site;
            long launchedAt = -1;
            /** His distance to the square when the first rocket took off. */
            double from = Double.MAX_VALUE;
            /** His citizen AI states between bedtime and dusk, and his status keys from bedtime on. */
            final Set<String> evening = new LinkedHashSet<>();
            final Set<String> keys = new LinkedHashSet<>();
            /** His citizen AI states in the last 400 ticks before dusk. */
            final Set<String> beforeDusk = new LinkedHashSet<>();
            String after = "";
            /** Rockets flown up to the tick before the show (the tree's lighting rockets, if the Decorator lights it now, are not the show's). */
            int rocketsBefore;
            int last;

            @Override
            void start() {
                setDate(NEW_YEAR_EVE);
                present = true;
                addRockets(20);
                nextDayAt(10000);
                site = Fireworks.site(level, colony);
                before = rocketsInStock();
                last = Fireworks.rockets();
                byHim0 = Fireworks.decoratorShows();
                byServer0 = Fireworks.serverShows();
                kept0 = Fireworks.keptUp();
            }

            @Override
            boolean tick() {
                final long time = timeOfDay();
                if (time > Gathering.BEDTIME + 100 && time < Fireworks.DUSK_FROM && t % 20 == 0) {
                    evening.add(state(decorator));
                    if (time >= Fireworks.DUSK_FROM - 400) {
                        beforeDusk.add(state(decorator));
                    }
                }
                if (time > Gathering.BEDTIME && job() != null) {
                    keys.add(job().statusKey());
                }
                if (launchedAt < 0 && Fireworks.decoratorShows() + Fireworks.serverShows() > byHim0 + byServer0) {
                    launchedAt = time;
                    rocketsBefore = last;
                    final AbstractEntityCitizen him = decorator.getEntity().orElse(null);
                    from = him == null || site == null ? Double.MAX_VALUE : Math.sqrt(him.position().distanceToSqr(Vec3.atBottomCenterOf(site)));
                }
                last = Fireworks.rockets();
                if (launchedAt >= 0 && time >= launchedAt + 120 && (BEDTIME_STATES.contains(state(decorator)) || time >= launchedAt + 600)) {
                    after = state(decorator);
                    return true;
                }
                return false;
            }

            @Override
            void end(final boolean done) {
                checkEq("the first dusk of the real day: the big show from the Decorator's stock", before - rocketsInStock(), Fireworks.SHOW_ROCKETS);
                checkEq("its rockets flew from the square", launchedAt < 0 ? 0 : Fireworks.rockets() - rocketsBefore, Fireworks.SHOW_ROCKETS);
                if (mixin) {
                    check("past bedtime he stays at work for the show (the mixin keeps him up)",
                            !evening.isEmpty() && AT_WORK.containsAll(evening) && Fireworks.keptUp() > kept0, evening + ", kept up " + (Fireworks.keptUp() - kept0) + " times");
                    checkEq("he launched it himself", Fireworks.decoratorShows() - byHim0, 1);
                    checkEq("the server did not launch it for him", Fireworks.serverShows() - byServer0, 0);
                    check("he stood at the square when the rockets went up", from <= 6, String.format("%.1f blocks from %s", from, site));
                    check("his status line says he launched it (a lang key)", keys.contains(DecoratorTexts.LAUNCHED_DUSK), keys.toString());
                    check("after the show he goes to bed (MineColonies' sleep routine)", BEDTIME_STATES.contains(after), after);
                } else {
                    // the test colony has no homes, and MineColonies lets a colonist without a home back to work at night
                    // now and then; what counts here is that nothing of ours kept him up and the show still went up
                    check("without the mixin bedtime is MineColonies' own: he goes into its sleep routine", evening.stream().anyMatch(BEDTIME_STATES::contains),
                            evening + ", before dusk " + beforeDusk);
                    checkEq("the mixin never kept him up", Fireworks.keptUp() - kept0, 0);
                    checkEq("one dusk show went up", Fireworks.serverShows() - byServer0 + Fireworks.decoratorShows() - byHim0, 1);
                    check("at dusk, at the latest when the server stops waiting for him", launchedAt >= Fireworks.DUSK_FROM
                            && launchedAt <= Fireworks.DUSK_FROM + Fireworks.LAUNCH_GRACE + CalendarEvents.POLL_TICKS, String.valueOf(launchedAt));
                    if (Fireworks.serverShows() > byServer0) {
                        check("his status line says it went up without him (a lang key)", keys.contains(DecoratorTexts.WENT_UP_DUSK), keys.toString());
                    }
                }
                note("show at {} ({} after dusk began) by the Decorator {}, by the server {}; he stood {} blocks from {}, then {}; evening states {}, "
                        + "before dusk {}", launchedAt, launchedAt - Fireworks.DUSK_FROM, Fireworks.decoratorShows() - byHim0,
                        Fireworks.serverShows() - byServer0, String.format("%.1f", from), site, after, evening, beforeDusk);
                setDate(DECOR_DAY);
            }
        });
    }

    /** New Year's day, before dusk: the Decorator's Launching state takes him to the square to get the show ready. */
    private void planLaunching() {
        final Set<String> keys = Set.of(DecoratorTexts.ON_WAY_SHOW, DecoratorTexts.SHOW_READY, DecoratorTexts.SHOW_READY_FREE);
        step(new Step("New Year's day: the Decorator goes Launching at the square", 900, true) {
            int launching0;
            final Set<String> seen = new LinkedHashSet<>();

            @Override
            void start() {
                setDate(NEW_YEAR_EVE);
                nextDayAt(6000);
                launching0 = job() == null ? 0 : job().visits(EntityAIWorkDecorator.DecoratorState.LAUNCHING);
            }

            @Override
            boolean tick() {
                if (job() != null) {
                    seen.add(job().statusKey());
                }
                return job() != null && job().visits(EntityAIWorkDecorator.DecoratorState.LAUNCHING) > launching0
                        && keys.contains(job().statusKey());
            }

            @Override
            void end(final boolean done) {
                final int visits = job() == null ? 0 : job().visits(EntityAIWorkDecorator.DecoratorState.LAUNCHING) - launching0;
                check("on the New Year days the Decorator goes Launching and gets the show ready at the square", done,
                        visits + " launching visits, status keys " + seen);
            }
        });
    }

    private void planMidnight() {
        final LocalDateTime start = LocalDateTime.of(2026, 12, 31, 23, 59, 50);
        step(new Step("the midnight show: the fake clock steps over midnight", 600, true) {
            LocalDateTime now = start;
            int countdowns0;
            int shows0;
            int before;

            @Override
            void start() {
                addRockets(30);
                setTimeOfDay(6000);
                before = rocketsInStock();
                countdowns0 = Fireworks.countdowns();
                shows0 = Fireworks.midnightShows();
                CalendarManager.setDateSource(DateSource.fixed(now));
            }

            @Override
            boolean tick() {
                if (t % 20 == 0) {
                    now = now.plusSeconds(1);
                    CalendarManager.setDateSource(DateSource.fixed(now));
                }
                return now.isAfter(start.plusSeconds(19));
            }

            @Override
            void end(final boolean done) {
                final int countdowns = Fireworks.countdowns() - countdowns0;
                check("a countdown on the action bar in the last seconds", countdowns >= 8 && countdowns <= 10, countdowns + " seconds counted");
                checkEq("one midnight show", Fireworks.midnightShows() - shows0, 1);
                checkEq("the midnight is noted (no second show)", data().midnightShownDay(), LocalDate.of(2027, 1, 1).toEpochDay());
                checkEq("the midnight show uses the stock", before - rocketsInStock(), Fireworks.MIDNIGHT_ROCKETS);
                setDate(DECOR_DAY);
            }
        });
    }

    // ================================================================ the season line, three forced days

    private void planSeasonLine() {
        step(new Step("the season turns: one line to the colony's players", 120, true) {
            int lines0;
            int afterFirst;

            @Override
            void start() {
                lines0 = FestivalDirector.seasonLines();
                final Season now = CalendarManager.current().season().season();
                CalendarManager.setSeason(server, now == Season.SPRING ? Season.SUMMER : Season.SPRING);
            }

            @Override
            boolean tick() {
                if (t == 60) {
                    afterFirst = FestivalDirector.seasonLines();
                }
                return t >= 120;
            }

            @Override
            void end(final boolean done) {
                checkEq("one season line for the one colony", afterFirst - lines0, 1);
                checkEq("and no second one while the season stays", FestivalDirector.seasonLines() - lines0, 1);
            }
        });
    }

    private void planForcedRun() {
        step(new Step("three game days of a forced Winter Festival (the log stays clean)", 76000, true) {
            final Set<Long> days = new LinkedHashSet<>();
            int pulls0;
            long first;

            @Override
            void start() {
                setDate(OUT_OF_SEASON);
                CalendarManager.setForcedFestival(server, SeasonsConfig.WINTER_FESTIVAL);
                nextDayAt(0);
                first = day();
                pulls0 = Gathering.pulls();
            }

            @Override
            boolean tick() {
                days.add(day());
                return day() >= first + 3;
            }

            @Override
            void end(final boolean done) {
                check("three forced festival days ran", done && days.size() >= 3, days.size() + " game days");
                check("the forced festival was on", CalendarManager.current().festivals().days().contains(SeasonsConfig.WINTER_FESTIVAL), "");
                check("they gathered on those days", Gathering.pulls() > pulls0, (Gathering.pulls() - pulls0) + " pulls");
                CalendarManager.setForcedFestival(server, "");
            }
        });
    }

    // ================================================================ the square of the third in-game test (S10c)

    /** decorator.needsMaterials set at once through the config spec (what a server owner's edit of the file does). */
    private void needsMaterials(final boolean on) {
        try {
            final java.lang.reflect.Field f = SeasonsConfig.class.getDeclaredField("NEEDS_MATERIALS");
            f.setAccessible(true);
            final ModConfigSpec.BooleanValue b = (ModConfigSpec.BooleanValue) f.get(null);
            b.set(on);
            b.clearCache();
        } catch (final ReflectiveOperationException | ClassCastException unreadable) {
            check("decorator.needsMaterials can be set", false, unreadable.getClass().getSimpleName().replace("Exception", ""));
        }
        checkEq("decorator.needsMaterials is now " + on, SeasonsConfig.needsMaterials(), on);
    }

    /** The colony's square piece (hut level 3, facing north), loaded since the tree went up from it. */
    private PieceLoader.Piece piece() {
        return PieceLoader.request(level, PieceLoader.PACK, PieceLoader.pathFor(3), Direction.NORTH);
    }

    private AABB treeBox() {
        if (townSquare != null) {
            return DecorWork.townSquareBox(level, ledger, colonyId, townSquare, marker, 3);
        }
        return DecorWork.squareBox(level, ledger, colonyId, marker, Direction.NORTH, PieceLoader.PACK, PieceLoader.pathFor(3));
    }

    /** What the Decorator works on for the tree: the Town Square's centrepiece (square mode) or the marker's square. */
    private DecorWork.Target treeTarget() {
        return townSquare != null ? DecorWork.Target.townSquare(townSquare, marker)
                : DecorWork.Target.square(marker, Direction.NORTH, PieceLoader.PACK);
    }

    /** The cell MineColonies lists as the colony's leisure site: the square's hut, else the marker. */
    private BlockPos leisureCell() {
        return townSquare != null ? townSquare.getPosition() : marker;
    }

    private int within() {
        return townSquare != null ? WITHIN_SQUARE : WITHIN;
    }

    /** A colonist's body standing at {@code feet}: the feet and the head cell. */
    private static AABB body(final BlockPos feet) {
        return new AABB(feet.getX(), feet.getY(), feet.getZ(), feet.getX() + 1, feet.getY() + 2, feet.getZ() + 1);
    }

    private boolean inside(final ICitizenData c, final AABB box) {
        final AbstractEntityCitizen e = c == null ? null : c.getEntity().orElse(null);
        return e != null && box != null && e.getBoundingBox().intersects(box);
    }

    /** A listed cell of the square with collision that a living body overlaps, or "" (there must never be one). */
    private String boxedIn() {
        final DecorLedger.GroupView g = ledger.group(colonyId, square());
        final AABB box = treeBox();
        if (g == null || box == null) {
            return "";
        }
        final List<LivingEntity> bodies = level.getEntitiesOfClass(LivingEntity.class, box.inflate(1), LivingEntity::isAlive);
        for (final Map.Entry<BlockPos, DecorLedger.Entry> e : g.entries().entrySet()) {
            final VoxelShape shape = e.getValue().state().getCollisionShape(level, e.getKey());
            if (shape.isEmpty()) {
                continue;
            }
            for (final AABB part : shape.toAabbs()) {
                final AABB at = part.move(e.getKey());
                for (final LivingEntity body : bodies) {
                    if (body.getBoundingBox().intersects(at)) {
                        return body.getType().toShortString() + " in " + e.getKey().toShortString() + " " + shortName(e.getValue().state());
                    }
                }
            }
        }
        return "";
    }

    private static String shortName(final BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
    }

    /** A building of the colony other than the town hall counted against the day's budget on game day {@code d}. */
    private String otherCharged(final long d) {
        for (final IBuilding b : List.of(hut, lumberjackHut, masonHut)) {
            if (b != null && ledger.chargedOn(colonyId, DecorLedger.Owner.building(b.getPosition()), d)) {
                return BuiltInRegistries.BLOCK.getKey(level.getBlockState(b.getPosition()).getBlock()).getPath();
            }
        }
        return "";
    }

    /** Everything of the colony taken down and the tree forgotten, a fresh game day: the square as if never decorated. */
    private void clearColony() {
        DecorPlacer.removeColony(level, ledger, colonyId, DecorPlacer.Stock.FREE);
        ledger.dropEmptyGroups(colonyId);
        ledger.flush(level);
        data().resetTree(colonyId);
        hut.setDecorationsUp(false);
        nextDayAt(MORNING);
        checkEq("the colony's decorations are down", ledger.count(colonyId), 0);
    }

    /** Two cells of the tree a body standing on the ground takes with its head (the lowest ring of boughs, not the trunk). */
    private List<BlockPos> bodyCells() {
        final List<BlockPos> out = new ArrayList<>();
        final PieceLoader.Piece p = piece();
        if (p == null) {
            return out;
        }
        final Set<BlockPos> taken = new java.util.HashSet<>();
        for (final PieceLoader.PieceCell c : p.cells()) {
            taken.add(c.rel());
        }
        for (final PieceLoader.PieceCell c : p.cells()) {
            final BlockPos r = c.rel();
            if (c.tree() && !c.trunk() && r.getY() == 1 && Math.abs(r.getX()) + Math.abs(r.getZ()) == 2 && !taken.contains(r.below())
                    && out.size() < 2 && (out.isEmpty() || out.get(0).distSqr(marker.offset(r.below())) > 4)) {
                out.add(marker.offset(r.below()));        // the feet cell; the head is in the tree cell above
            }
        }
        return out;
    }

    private static void put(final ICitizenData c, final BlockPos feet) {
        final AbstractEntityCitizen e = c.getEntity().orElse(null);
        if (e != null) {
            e.teleportTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
            e.getNavigation().stop();
        }
    }

    private void planBoxedIn() {
        final List<BlockPos> cells = new ArrayList<>();
        final long[] today = new long[1];
        step(new Step("square, boxed in: the tree down, the Decorator and a colonist stand in its cells", 1) {
            @Override
            void start() {
                setDate(DECOR_DAY);
                needsMaterials(false);
                clearColony();
                today[0] = day();
                cells.addAll(bodyCells());
                check("two cells of the tree where a body stands", cells.size() == 2, cells.toString());
                if (cells.size() == 2) {
                    put(decorator, cells.get(0));
                    put(offDuty.get(0), cells.get(1));
                }
                final AABB box = treeBox();
                check("the Decorator and a colonist stand in the tree's box", inside(decorator, box) && inside(offDuty.get(0), box),
                        "box " + box);
            }
        });
        step(new Step("square, boxed in: the Decorator's AI puts the tree up, nobody is boxed in, he goes on", 12000, true) {
            String boxed = "";
            String heInside = "";
            int rounds;
            int evictions0;
            boolean outsideAtFirst;
            int size;

            @Override
            void start() {
                evictions0 = DecorWork.evictions();
            }

            @Override
            boolean tick() {
                final DecorLedger.GroupView g = ledger.group(colonyId, square());
                final int now = g == null ? 0 : g.size();
                final AABB box = treeBox();
                if (now == 0 && cells.size() == 2) {
                    put(offDuty.get(0), cells.get(1));        // he stands there until the tree goes up
                }
                if (now > size) {
                    rounds++;
                    if (inside(decorator, box)) {
                        heInside = "at " + now + " cells";
                    }
                    if (size == 0) {
                        outsideAtFirst = !inside(decorator, box) && !inside(offDuty.get(0), box);
                    }
                    size = now;
                }
                if (boxed.isEmpty() && t % 5 == 0) {
                    boxed = boxedIn();
                }
                return g != null && g.decoratedDay() >= 0 && !otherCharged(today[0]).isEmpty();
            }

            @Override
            void end(final boolean done) {
                final DecorLedger.GroupView g = ledger.group(colonyId, square());
                check("no living body is ever in a listed block of the tree", boxed.isEmpty(), boxed);
                check("both stood outside the box when the tree went up (the colonist moved out)", outsideAtFirst
                        && DecorWork.evictions() > evictions0, "evictions " + (DecorWork.evictions() - evictions0));
                check("the Decorator stood outside the box whenever cells of the square went up", heInside.isEmpty() && rounds > 0,
                        rounds + " rounds" + (heInside.isEmpty() ? "" : ", inside " + heInside));
                check("the square is complete", g != null && g.decoratedDay() >= 0, g == null ? "no group" : g.size() + " cells");
                check("another building than the town hall is decorated the same game day", !otherCharged(today[0]).isEmpty() && day() == today[0],
                        "'" + otherCharged(today[0]) + "', day " + day() + " (started " + today[0] + ")");
                invariant("the tree up round where they stood");
            }
        });
    }

    private void planHeld() {
        final BlockPos[] gift = new BlockPos[1];
        final ArmorStand[] stand = new ArmorStand[1];
        final long[] ready = new long[1];
        final int[] notices0 = new int[1];
        step(new Step("square, held: the tree down, an armor stand in a gift pile's cell", 1) {
            @Override
            void start() {
                clearColony();
                final PieceLoader.Piece p = piece();
                if (p != null) {
                    for (final PieceLoader.PieceCell c : p.cells()) {
                        if (c.giftPile() && gift[0] == null) {
                            gift[0] = marker.offset(c.rel());
                        }
                    }
                }
                check("the piece has a gift pile cell", gift[0] != null, "");
                if (gift[0] == null) {
                    return;
                }
                stand[0] = new ArmorStand(level, gift[0].getX() + 0.5, gift[0].getY(), gift[0].getZ() + 0.5);
                check("an armor stand stands in the gift pile's cell", level.addFreshEntity(stand[0]), gift[0].toShortString());
                notices0[0] = TreeLighting.notices();
            }
        });
        step(new Step("square, held: that cell is refused, the square is left for today, the other buildings go on", 12000, true) {
            final long[] today = new long[1];

            @Override
            void start() {
                today[0] = day();
            }

            @Override
            boolean tick() {
                return ledger.fruitless(colonyId, square(), today[0]) >= DecorWork.MAX_FRUITLESS_VISITS && !otherCharged(today[0]).isEmpty()
                        && TreeLighting.notices() > notices0[0];
            }

            @Override
            void end(final boolean done) {
                final DecorLedger.GroupView g = ledger.group(colonyId, square());
                final BlockState giftState = SeasonsBlocks.GIFT_PILE.get().defaultBlockState();
                check("the gift pile's cell is refused for the stand (the cell rule says entity)", gift[0] != null && level.getBlockState(gift[0]).isAir()
                        && "entity".equals(DecorPlacer.refusal(level, ledger, colonyId, gift[0], DecorPlacer.normalise(giftState, gift[0]), true)),
                        gift[0] == null ? "-" : DecorPlacer.refusal(level, ledger, colonyId, gift[0], giftState, true));
                check("after " + DecorWork.MAX_FRUITLESS_VISITS + " visits that place nothing the square is left for today",
                        ledger.fruitless(colonyId, square(), today[0]) >= DecorWork.MAX_FRUITLESS_VISITS, ledger.fruitless(colonyId, square(), today[0]) + " visits");
                check("another building is decorated that game day", !otherCharged(today[0]).isEmpty() && day() == today[0], otherCharged(today[0]));
                check("the square is not complete, its tree is up all the same", g != null && g.decoratedDay() < 0 && g.treeDay() == today[0],
                        g == null ? "no group" : "decorated " + g.decoratedDay() + ", tree " + g.treeDay());
                checkEq("the colony's players are told once that the tree is ready", TreeLighting.notices() - notices0[0], 1);
                ready[0] = festival().treeReadyDay();
                checkEq("the two-day timer starts today", ready[0], today[0]);
                check("not lit yet", !festival().treeLit(), "");
                invariant("the square held");
            }
        });
        step(new Step("square, held: one game day later it is still dark", 400, true) {
            @Override
            void start() {
                nextDayAt(MORNING);
            }

            @Override
            boolean tick() {
                return t >= 300;
            }

            @Override
            void end(final boolean done) {
                check("one day later it is still dark", !festival().treeLit(), "day " + day() + ", ready " + ready[0]);
            }
        });
        step(new Step("square, held: two game days later the Decorator lights it, the gift cell still open", 400, true) {
            int lightings0;

            @Override
            void start() {
                lightings0 = TreeLighting.lightings();
                nextDayAt(MORNING);
            }

            @Override
            boolean tick() {
                return t >= 60 && festival().treeLit();
            }

            @Override
            void end(final boolean done) {
                check("two game days after the tree was up the Decorator lit it", done && TreeLighting.BY_DECORATOR.equals(festival().litBy())
                        && TreeLighting.lightings() == lightings0 + 1, "day " + day() + ", ready " + ready[0] + ", by '" + festival().litBy() + "'");
                check("the gift pile's cell is still open (the stand is there)", gift[0] != null && level.getBlockState(gift[0]).isAir()
                        && stand[0] != null && stand[0].isAlive(), "");
                checkEq("no second notice", TreeLighting.notices() - notices0[0], 1);
                invariant("lit with the gift cell open");
            }
        });
        step(new Step("square, held: the stand gone, the next day's visit completes the square", 9000, true) {
            @Override
            void start() {
                if (stand[0] != null) {
                    stand[0].discard();
                }
                nextDayAt(MORNING);
            }

            @Override
            boolean tick() {
                final DecorLedger.GroupView g = ledger.group(colonyId, square());
                return g != null && g.decoratedDay() >= 0;
            }

            @Override
            void end(final boolean done) {
                final DecorLedger.GroupView g = ledger.group(colonyId, square());
                check("the square is complete, the gift pile in its cell and listed", done && gift[0] != null
                        && level.getBlockState(gift[0]).is(SeasonsBlocks.GIFT_PILE.get()) && ledger.at(gift[0]) != null,
                        g == null ? "no group" : "decorated " + g.decoratedDay());
                checkEq("the tree stays lit", festival().treeLit(), true);
                invariant("the square complete");
                needsMaterials(true);
            }
        });
    }

    // ================================================================ New Year (S10c)

    private static final LocalDate NY_A = LocalDate.of(2027, 1, 2);
    private static final LocalDate NY_B = LocalDate.of(2027, 1, 1);
    private static final LocalDate NY_C = LocalDate.of(2026, 12, 31);

    /** Every firework rocket out of the hut and his pack, then {@code n} into the hut. */
    private void rocketsInHut(final int n) {
        for (final net.neoforged.neoforge.items.IItemHandler h : new net.neoforged.neoforge.items.IItemHandler[] {hut.getItemHandlerCap(),
                decorator.getInventory()}) {
            for (int slot = 0; slot < h.getSlots(); slot++) {
                if (h.getStackInSlot(slot).is(Items.FIREWORK_ROCKET)) {
                    h.extractItem(slot, 64, false);
                }
            }
        }
        if (n > 0) {
            addRockets(n);
        }
    }

    private int rocketsHeld() {
        int n = rocketsInStock();
        final net.neoforged.neoforge.items.IItemHandler pack = decorator.getInventory();
        for (int slot = 0; slot < pack.getSlots(); slot++) {
            n += pack.getStackInSlot(slot).is(Items.FIREWORK_ROCKET) ? pack.getStackInSlot(slot).getCount() : 0;
        }
        return n;
    }

    private void planNewYear() {
        step(new Step("newyear: the audience is a fake player in the claim, found by the real claim test", 1) {
            @Override
            void start() {
                watcher = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes("csfestival-watcher".getBytes(StandardCharsets.US_ASCII)),
                        "csfestival_watcher"));
                watcher.setPos(marker.getX() - 20 + 0.5, marker.getY(), marker.getZ() + 0.5);
                Fireworks.setPresence((lvl, c) -> c.getID() == colonyId && Fireworks.playersIn(lvl, c, List.of(watcher)));
                check("the claim test finds the watcher 20 blocks from the marker in the colony", Fireworks.playersIn(level, colony, List.of(watcher)),
                        watcher.blockPosition().toShortString());
                CalendarManager.setForcedFestival(server, SeasonsConfig.NEW_YEAR);
                // lit now, so the Decorator's lighting two game days on (and its greeting rockets) is not in the shows below
                TreeLighting.light(level, colony, TreeLighting.BY_PLAYER, null);
            }
        });
        // (a) the third in-game test: the clock sprinted from 11800 through the dusk, decorations free
        final long[] shownAt = {-1};
        final String[] by = {""};
        step(new Step("newyear (a): a forced New Year, decorations free, the Decorator gets the show ready by day", 2000, true) {
            int launching0;

            @Override
            void start() {
                setDate(NY_A);
                needsMaterials(false);
                rocketsInHut(0);
                nextDayAt(6000);
                launching0 = job() == null ? 0 : job().visits(EntityAIWorkDecorator.DecoratorState.LAUNCHING);
            }

            @Override
            boolean tick() {
                return job() != null && job().visits(EntityAIWorkDecorator.DecoratorState.LAUNCHING) > launching0 && t >= 200;
            }

            @Override
            void end(final boolean done) {
                check("New Year is on by force", CalendarManager.current().festivals().days().contains(SeasonsConfig.NEW_YEAR), "");
                check("on the New Year day he goes Launching", done, "status " + (job() == null ? "-" : job().statusKey()));
            }
        });
        step(new Step("newyear (a): at 11800 the clock is sprinted 1000 ticks, as in the test", 1000, true) {
            int byHim0;
            int byServer0;
            int midnight0;

            @Override
            void start() {
                setTimeOfDay(11800);
                byHim0 = Fireworks.decoratorShows();
                byServer0 = Fireworks.serverShows();
                midnight0 = Fireworks.midnightShows();
                note("at 11800: the Decorator is {}, status {}", state(decorator), job() == null ? "-" : job().statusKey());
            }

            @Override
            boolean tick() {
                if (shownAt[0] < 0 && Fireworks.decoratorShows() + Fireworks.serverShows() > byHim0 + byServer0) {
                    shownAt[0] = timeOfDay();
                    by[0] = Fireworks.decoratorShows() > byHim0 ? "the Decorator" : "the server";
                }
                return false;
            }

            @Override
            void end(final boolean done) {
                note("the sprint ended at {}; the show went up at {}; the Decorator is {}", timeOfDay(), shownAt[0], state(decorator));
                checkEq("no midnight show on the way", Fireworks.midnightShows() - midnight0, 0);
            }
        });
        step(new Step("newyear (a): to 13100, the dusk over", 400, true) {
            int byHim0;
            int byServer0;

            @Override
            void start() {
                byHim0 = Fireworks.decoratorShows();
                byServer0 = Fireworks.serverShows();
            }

            @Override
            boolean tick() {
                return timeOfDay() >= 13100;
            }

            @Override
            void end(final boolean done) {
                check("the dusk show went up inside the sprint, from 12500 and before 12806 (the test's look at the clock)",
                        shownAt[0] >= Fireworks.DUSK_FROM && shownAt[0] < 12806, "at " + shownAt[0]);
                note("RUN 3 replay: the show went up at {} of the game day, launched by {}", shownAt[0], by[0]);
                final FestivalData.ColonyState st = data().peek(colonyId);
                checkEq("the dusk of this game day is noted", st == null ? -1L : st.lastDuskGameDay(), day());
                check("by 13100 the Decorator is in bed (MineColonies' sleep routine)", BEDTIME_STATES.contains(state(decorator)), state(decorator));
                checkEq("no second show this dusk", Fireworks.decoratorShows() - byHim0 + Fireworks.serverShows() - byServer0, 0);
            }
        });
        asleep("(b)", NY_B, true);
        asleep("(c)", NY_C, false);
        step(new Step("newyear: the end", 1) {
            @Override
            void start() {
                CalendarManager.setForcedFestival(server, "");
                needsMaterials(true);
            }
        });
    }

    /** He sleeps through the dusk (rain, so nothing keeps him up): the server launches the show from the crate. */
    private void asleep(final String label, final LocalDate date, final boolean materials) {
        step(new Step("newyear " + label + ": rain, he sleeps through the dusk, decorations " + (materials ? "from stock" : "free"), 4200, true) {
            int byHim0;
            int byServer0;
            int rockets0;
            int midnight0;
            int held0;
            BlockPos crate;
            BlockPos site;
            long launchedAt = -1;
            String hisState = "";
            int rise = -1;

            @Override
            void start() {
                setDate(date);
                needsMaterials(materials);
                rocketsInHut(Fireworks.SHOW_ROCKETS);
                nextDayAt(9000);
                level.setWeatherParameters(0, 24000, true, false);
                crate = firstOf(s -> s.is(SeasonsBlocks.FIREWORK_CRATE.get()));
                site = Fireworks.site(level, colony);
                byHim0 = Fireworks.decoratorShows();
                byServer0 = Fireworks.serverShows();
                rockets0 = Fireworks.rockets();
                midnight0 = Fireworks.midnightShows();
                held0 = rocketsHeld();
            }

            @Override
            boolean tick() {
                if (launchedAt < 0 && Fireworks.decoratorShows() + Fireworks.serverShows() > byHim0 + byServer0) {
                    launchedAt = timeOfDay();
                    hisState = state(decorator);
                }
                if (site != null) {
                    for (final FireworkRocketEntity r : level.getEntitiesOfClass(FireworkRocketEntity.class, new AABB(site).inflate(64))) {
                        rise = Math.max(rise, (int) Math.floor(r.getY() - site.getY() - 1));
                    }
                }
                return launchedAt >= 0 && timeOfDay() >= launchedAt + 200 || timeOfDay() >= Fireworks.DUSK_UNTIL;
            }

            @Override
            void end(final boolean done) {
                level.setWeatherParameters(24000 * 20, 0, false, false);
                check("the show went up at dusk " + label, launchedAt >= Fireworks.DUSK_FROM && launchedAt < Fireworks.DUSK_UNTIL, "at " + launchedAt);
                // the test colony has no beds: MineColonies' sleep routine, or idle in the rain, but never at work (nothing keeps him up)
                check("he was not up when it went up (not at work: asleep or idle) " + label, !hisState.isEmpty() && !"none".equals(hisState)
                        && !AT_WORK.contains(hisState), hisState);
                checkEq("the server launched it " + label, Fireworks.serverShows() - byServer0, 1);
                checkEq("not he " + label, Fireworks.decoratorShows() - byHim0, 0);
                check("from the firework crate " + label, crate != null && crate.equals(site), site + ", crate " + crate);
                checkEq("rockets that flew " + label, Fireworks.rockets() - rockets0, Fireworks.SHOW_ROCKETS);
                checkEq("rockets taken from his stock " + label, held0 - rocketsHeld(), materials ? Fireworks.SHOW_ROCKETS : 0);
                check("the rockets climb into the sky " + label, rise >= 3, rise + " blocks above " + site);
                checkEq("no midnight show on the way " + label, Fireworks.midnightShows() - midnight0, 0);
            }
        });
    }

    // ================================================================ restart

    private void planRestartSave() {
        step(new Step("restart-save: the tree lit and a gift taken, then the world saved", 80, true) {
            @Override
            void start() {
                final BlockPos log = firstOf(s -> s.is(SeasonsBlocks.FESTIVAL_TRUNK.get()));
                click(one, log);
                check("the tree is lit before the restart", festival().treeLit() && lights()[1] == 0, "");
                final BlockPos pile = firstOf(s -> s.is(SeasonsBlocks.GIFT_PILE.get()));
                click(one, pile);
                checkEq("player one has today's gift before the restart", gifts(one, Gifts.DAILY), 1);
                final CompoundTag facts = new CompoundTag();
                facts.putInt("colony", colonyId);
                facts.putLong("marker", marker.asLong());
                facts.putLong("pile", pile.asLong());
                facts.putLong("log", log.asLong());
                final DecorLedger.GroupView tree = ledger.group(colonyId, square());
                facts.putLong("treeDay", tree == null ? -1 : tree.treeDay());
                check("the tree's ready day is in the ledger before the restart", tree != null && tree.treeDay() >= 0, "");
                facts.putString("season", data().lastSeason());
                try {
                    NbtIo.writeCompressed(facts, server.getWorldPath(LevelResource.ROOT).resolve(RESTART_FILE));
                } catch (final java.io.IOException unwritable) {
                    check("the facts file is written", false, "");
                }
            }

            @Override
            boolean tick() {
                return t >= 40;
            }
        });
    }

    private void planRestartLoad() {
        final CompoundTag[] facts = new CompoundTag[1];
        step(new Step("restart-load: the colony again", 2400) {
            @Override
            boolean tick() {
                if (facts[0] == null) {
                    try {
                        facts[0] = NbtIo.readCompressed(server.getWorldPath(LevelResource.ROOT).resolve(RESTART_FILE), NbtAccounter.unlimitedHeap());
                    } catch (final java.io.IOException unreadable) {
                        return false;
                    }
                }
                colony = IColonyManager.getInstance().getColonyByWorld(facts[0].getInt("colony"), level);
                return colony != null && DecorEvents.managerLoaded() && FestivalDirector.polls() >= 3;
            }

            @Override
            void end(final boolean done) {
                check("the colony is back after the restart", done && colony != null, "");
                if (!done || colony == null) {
                    finish();
                    return;
                }
                colonyId = colony.getID();
                marker = BlockPos.of(facts[0].getLong("marker"));
                quietWorld();
                for (int cx = -6; cx <= 6; cx++) {
                    for (int cz = -6; cz <= 6; cz++) {
                        level.setChunkForced(cx, cz, true);
                    }
                }
                owner = FakePlayerFactory.getMinecraft(level);
                colony.getPackageManager().addCloseSubscriber(owner);
                one = player(P1, "csfestival_one");
                two = player(P2, "csfestival_two");
                check("the players are still of the colony after the restart", colony.getPermissions().hasPermission(one, Action.RIGHTCLICK_BLOCK)
                        && colony.getPermissions().hasPermission(two, Action.RIGHTCLICK_BLOCK), "");
            }
        });
        step(new Step("restart-load: the claim, the lit tree, no repeats", 80, true) {
            @Override
            boolean tick() {
                return t >= 60;
            }

            @Override
            void end(final boolean done) {
                final BlockPos pile = BlockPos.of(facts[0].getLong("pile"));
                checkEq("the claim is read back from the world", data().giftsOn(P1, DECOR_DAY.toEpochDay()), 1);
                click(one, pile);
                checkEq("after the restart player one is still refused today", gifts(one, Gifts.DAILY), 0);
                click(two, pile);
                checkEq("player two still gets his", gifts(two, Gifts.DAILY), 1);
                check("the tree is still lit, by the player", festival().treeLit() && TreeLighting.BY_PLAYER.equals(festival().litBy())
                        && lights()[1] == 0 && lights()[0] > 0, lights()[0] + " lit, " + lights()[1] + " unlit");
                checkEq("no second notice that the tree is finished", TreeLighting.notices(), 0);
                final DecorLedger.GroupView tree = DecorLedger.get(level).group(colonyId, DecorLedger.Owner.square(marker));
                checkEq("the tree's ready day survives the restart", tree == null ? -2L : tree.treeDay(), facts[0].getLong("treeDay"));
                checkEq("no season line after the restart (the season told is saved)", FestivalDirector.seasonLines(), 0);
                checkEq("the season told is read back", data().lastSeason(), facts[0].getString("season"));
                invariant("after the restart");
            }
        });
    }
}
