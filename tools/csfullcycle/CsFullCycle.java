package me.lovkar.seasons.csfullcycle;

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
import com.minecolonies.api.colony.buildings.HiringMode;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.modules.IAssignsCitizen;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.tileentities.AbstractTileEntityColonyBuilding;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import com.mojang.authlib.GameProfile;
import me.lovkar.seasons.SeasonsConfig;
import me.lovkar.seasons.ai.EntityAIWorkDecorator;
import me.lovkar.seasons.block.FestivalMarkerBlock;
import me.lovkar.seasons.block.GiftPileBlock;
import me.lovkar.seasons.block.Lights;
import me.lovkar.seasons.block.SeasonsBlocks;
import me.lovkar.seasons.calendar.CalendarManager;
import me.lovkar.seasons.calendar.DateSource;
import me.lovkar.seasons.calendar.Season;
import me.lovkar.seasons.client.ClothesRules;
import me.lovkar.seasons.colony.BuildingDecorator;
import me.lovkar.seasons.colony.DecoratorModules;
import me.lovkar.seasons.colony.DecoratorSetup;
import me.lovkar.seasons.colony.JobDecorator;
import me.lovkar.seasons.decor.DecorEvents;
import me.lovkar.seasons.decor.DecorKinds;
import me.lovkar.seasons.decor.DecorLedger;
import me.lovkar.seasons.decor.DecorPhase;
import me.lovkar.seasons.decor.DecorPlacer;
import me.lovkar.seasons.decor.DecorPlanner;
import me.lovkar.seasons.decor.DecorWork;
import me.lovkar.seasons.decor.DoorFinder;
import me.lovkar.seasons.decor.PieceLoader;
import me.lovkar.seasons.effects.CropEffect;
import me.lovkar.seasons.effects.Effects;
import me.lovkar.seasons.effects.FoodEffect;
import me.lovkar.seasons.effects.HappinessEffect;
import me.lovkar.seasons.festival.FestivalData;
import me.lovkar.seasons.festival.Fireworks;
import me.lovkar.seasons.festival.Gathering;
import me.lovkar.seasons.festival.Gifts;
import me.lovkar.seasons.festival.TreeLighting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.Item;
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
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.concurrent.TimeUnit;

/**
 * S9's whole-cycle probe: a real colony (a hand-built town hall and Decorator's hut, ten huts pasted from the real
 * blueprints of four MineColonies style packs, citizens with and without jobs, a festival marker) taken through the
 * winter on a fake clock, 28 Nov to 10 Jan, while the Decorator's own AI does all the work. The probe never calls the
 * Decorator's placing code in these modes; it only watches, breaks one wreath, takes gifts as a player would and
 * checks.
 *
 * <p>Every date gets one or more game days (morning to evening; nights are skipped by setting the clock forward), and
 * after each the daily invariants: I1 every ledger entry equals the world; I2 every cell of the first snapshot's box
 * that differs is listed in the ledger (so nothing unlisted was touched); I3 conservation of every decoration item and
 * every vanilla item the tree uses (hut, citizens' packs, item entities, what the probe holds, paid ledger entries);
 * I4 every entry lies in this colony's claim. Rockets are checked apart: the stock drops by what the shows sent up.
 * The snapshot normalises only the living properties named in {@link Snap}.</p>
 *
 * <p>Modes (the rig writes the word to probe-mode.txt): "" the base cycle; nodecorator, nomarker, emptywarehouse,
 * effects (with rigconfig-effects), serene (the Serene Seasons stand-in of tools/sereneshim); restart-save, restart-mid
 * and restart-load (tools/restart.sh with phases.txt: one kept world, two restarts); edges-low and edges-high (config
 * edge values, a shortened cycle); hostile-items, hostile-free and hostile-world ({@link Hostile}). Prints
 * "[csfullcycle] CHECK ...: OK|FAIL", "NOTE ..." and last "[csfullcycle] RESULT csfullcycle: PASS|FAIL", then halts.
 * The probe never reads the real clock; the words the rig greps for stay out of the details.</p>
 */
@Mod("colonyseasonsfullcycle")
public class CsFullCycle {
    static final Logger LOGGER = LoggerFactory.getLogger("csfullcycle");
    static final int SETUP_TICK = 60;
    static final long WATCHDOG_SECONDS = 560;
    static final int MORNING = 1000;
    /** Past bedtime: the colony's working day is over. */
    static final int EVENING = 11000;
    /** Past dusk: the New Year shows are over. */
    static final int LATE = 14000;
    /** The gathering's day ends a little after its hours. */
    static final int AFTER_HOURS = 10700;
    /** The tick rate through the gathering hours (see the gathering's day hook). */
    static final float GATHER_TICK_RATE = 400f;
    static final int HUT_LEVEL = 3;
    static final int WITHIN = 8;
    static final LocalDate FIRST = LocalDate.of(2026, 11, 28);
    static final LocalDate LAST = LocalDate.of(2027, 1, 10);
    static final String FACTS = "csfullcycle-facts.nbt";
    static final String SNAPSHOT = "csfullcycle-snapshot.nbt";
    static final UUID P1 = UUID.nameUUIDFromBytes("csfullcycle-player-one".getBytes(StandardCharsets.US_ASCII));
    static final UUID P2 = UUID.nameUUIDFromBytes("csfullcycle-player-two".getBytes(StandardCharsets.US_ASCII));

    /** Real style-pack huts pasted round the town hall: four packs, a warehouse, homes, a library and three workshops. */
    static final String[][] PASTES = {
            {"Colonial", "craftsmanship/storage/warehouse1"},
            {"Medieval Oak", "fundamentals/residence1"},
            {"Nordic Spruce", "fundamentals/residence1"},
            {"Colonial", "fundamentals/residence1"},
            {"Medieval Oak", "education/library1"},
            {"Nordic Spruce", "craftsmanship/metallurgy/blacksmith1"},
            {"Colonial", "craftsmanship/metallurgy/blacksmith1"},
            {"Medieval Oak", "craftsmanship/carpentry/fletcher1"},
            {"Nordic Spruce", "education/library1"},
            {"Shire", "fundamentals/house1"}};

    /** What a mode changes: a Decorator's hut, a marker, stock in the hut, the effects config, the Serene Seasons stand-in. */
    record Variant(boolean hut, boolean marker, boolean stock, boolean effects, boolean serene) {
        static final Variant BASE = new Variant(true, true, true, false, false);
    }

    int ticks;
    boolean failed;
    boolean finished;
    final String mode;
    final Variant variant;
    long setupNanos;

    MinecraftServer server;
    ServerLevel level;
    FakePlayer owner;
    FakePlayer p1;
    FakePlayer p2;
    IColony colony;
    int colonyId = -1;
    int ground;
    DecorLedger ledger;
    IBuilding townHall;
    BuildingDecorator hut;
    ICitizenData decorator;
    BlockPos marker;
    final List<IBuilding> buildings = new ArrayList<>();
    Snap first;
    /** Tracked items and their counts on 28 Nov. */
    final Map<Item, Integer> start = new LinkedHashMap<>();
    /** Items the probe took out of the colony's hands (a wreath "a player broke and kept"). */
    final Map<Item, Integer> held = new LinkedHashMap<>();
    /** Cells the probe changed itself on purpose (the marker it breaks in mode nodecorator): not the mod's doing, so I2 skips them. */
    final Set<BlockPos> probeChanged = new java.util.HashSet<>();
    /** What the probe's last restart phase saved (restart-mid and restart-load read it). */
    CompoundTag facts = new CompoundTag();

    final List<Step> steps = new ArrayList<>();
    int stepIndex;

    public CsFullCycle() {
        mode = readMode();
        variant = switch (mode) {
            case "nodecorator" -> new Variant(false, true, false, false, false);
            case "nomarker" -> new Variant(true, false, true, false, false);
            case "emptywarehouse" -> new Variant(true, true, false, false, false);
            case "effects" -> new Variant(true, true, true, true, false);
            case "serene" -> new Variant(true, true, true, false, true);
            default -> Variant.BASE;
        };
        // the calendar never sees the real date: the first date of the phase from the server's first poll on
        CalendarManager.setDateSource(DateSource.fixed(switch (mode) {
            case "restart-mid" -> LocalDate.of(2026, 12, 12);
            case "restart-load" -> LocalDate.of(2027, 1, 7);
            default -> FIRST;
        }));
        NeoForge.EVENT_BUS.addListener(this::onTick);
    }

    // ================================================================ logging

    void check(final String what, final boolean ok, final String detail) {
        LOGGER.info("[csfullcycle] CHECK {}: {} {}", what, ok ? "OK" : "FAIL", detail);
        failed |= !ok;
    }

    void checkEq(final String what, final Object actual, final Object expected) {
        check(what, Objects.equals(actual, expected), "got " + actual + (Objects.equals(actual, expected) ? "" : ", wanted " + expected));
    }

    static void note(final String text, final Object... args) {
        LOGGER.info("[csfullcycle] NOTE " + text, args);
    }

    // ================================================================ the tick and the steps

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

    static String where(final Throwable t) {
        final StringBuilder at = new StringBuilder();
        int n = 0;
        for (final StackTraceElement e : t.getStackTrace()) {
            if (n++ >= 8) {
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
            if (finished) {
                return;
            }
        }
        step.t++;
        if (step.t % 200 == 0) {
            keepWell();
        }
        final boolean done = step.tick();
        if (done || step.t >= step.maxTicks) {
            final double seconds = Math.max(1e-3, (System.nanoTime() - step.nanos) / 1e9);
            LOGGER.info("[csfullcycle] step '{}' ran {} ticks in {} s ({} ticks/s)", step.name, step.t, String.format("%.1f", seconds),
                    Math.round(step.t / seconds));
            step.end(done);
            server.tickRateManager().stopSprinting();
            stepIndex++;
        }
    }

    void finish() {
        if (finished) {
            return;
        }
        finished = true;
        if (server != null) {
            server.tickRateManager().stopSprinting();
        }
        Fireworks.setPresence(null);
        LOGGER.info("[csfullcycle] RESULT csfullcycle: {}", failed ? "FAIL" : "PASS");
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
    abstract static class Step {
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

    void step(final Step step) {
        steps.add(step);
    }

    private void plan() {
        switch (mode) {
            case "", "nodecorator", "nomarker", "emptywarehouse", "effects", "serene" -> {
                planSetup();
                planDates(FIRST, LAST, false);
                planEnd();
            }
            case "restart-save" -> {
                planSetup();
                planDates(FIRST, LocalDate.of(2026, 12, 12), false);
                planSave("restart-save");
            }
            case "restart-mid" -> {
                planFind("restart-mid");
                planDates(LocalDate.of(2026, 12, 13), LocalDate.of(2027, 1, 7), true);
                planSave("restart-mid");
            }
            case "restart-load" -> {
                planFind("restart-load");
                planDates(LocalDate.of(2027, 1, 7), LAST, false);
                planEnd();
            }
            case "edges-low", "edges-high" -> {
                planSetup();
                planEdges(mode.equals("edges-low"));
            }
            default -> {
                if (mode.startsWith("hostile-")) {
                    new Hostile(this).plan(mode);
                } else {
                    check("known mode", false, "'" + mode + "'");
                    finish();
                }
            }
        }
    }

    // ================================================================ world helpers

    void setDate(final LocalDate date) {
        CalendarManager.setDateSource(DateSource.fixed(date));
        CalendarManager.refresh(server);
    }

    void setDateTime(final LocalDateTime time) {
        CalendarManager.setDateSource(DateSource.fixed(time));
        CalendarManager.refresh(server);
    }

    void quietWorld() {
        server.setDifficulty(Difficulty.PEACEFUL, true);
        final GameRules rules = server.getGameRules();
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        rules.getRule(GameRules.RULE_DOFIRETICK).set(false, server);
        rules.getRule(GameRules.RULE_RANDOMTICKING).set(0, server);
        rules.getRule(GameRules.RULE_MOBGRIEFING).set(false, server);
        level.setWeatherParameters(24000 * 20, 0, false, false);
    }

    long day() {
        return level.getDayTime() / 24000L;
    }

    long timeOfDay() {
        return level.getDayTime() % 24000L;
    }

    /** The next game day at {@code time} (the night in between is skipped). */
    void newGameDay(final int time) {
        level.setDayTime((day() + 1) * 24000L + time);
    }

    void setTimeOfDay(final int time) {
        level.setDayTime(day() * 24000L + time);
    }

    void forceChunks(final int radius) {
        for (int cx = -radius; cx <= radius; cx++) {
            for (int cz = -radius; cz <= radius; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }
    }

    void set(final BlockPos pos, final BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }

    boolean claimed(final BlockPos pos) {
        return DecorPlacer.colonyAt(level, pos) == colonyId;
    }

    /** A stone-brick house on the ground with doors in its south wall (the csdecorgate helper). */
    DoorFinder.Box house(final int x0, final int z0, final int w, final int d, final int... doors) {
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
    IBuilding register(final IColony col, final String hutId, final DoorFinder.Box box, final int buildingLevel) {
        final Block block = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(hutId)).orElse(null);
        if (block == null) {
            check("hut block " + hutId + " exists", false, "");
            return null;
        }
        level.setBlock(box.hut(), block.defaultBlockState(), Block.UPDATE_ALL);
        final BlockEntity be = level.getBlockEntity(box.hut());
        final IBuilding building = be instanceof AbstractTileEntityColonyBuilding te ? col.getServerBuildingManager().addNewBuilding(te, level) : null;
        if (building == null) {
            check("building " + hutId + " registers", false, "at " + box.hut());
            return null;
        }
        building.setCorners(box.min(), box.max());
        building.setBuildingLevel(buildingLevel);
        building.markDirty();
        manual(building);
        return building;
    }

    /** Nobody is hired by MineColonies on its own: the jobless colonists of the probe stay jobless. */
    static void manual(final IBuilding building) {
        for (final IAssignsCitizen module : building.getModulesByType(IAssignsCitizen.class)) {
            module.setHiringMode(HiringMode.MANUAL);
        }
    }

    record Pasted(String pack, String path, BlockPos min, BlockPos max, BlockPos anchor) {
    }

    /** Pastes a hut blueprint with its box's low corner at {@code plot} (Structurize's creative handler, as csdecorgate does). */
    Pasted paste(final String pack, final String path, final BlockPos plot, final Blueprint loaded) {
        try {
            final Blueprint blueprint = loaded != null ? loaded : StructurePacks.getBlueprintFuture(pack, path + ".blueprint", level.registryAccess())
                    .get(60, TimeUnit.SECONDS);
            if (blueprint == null) {
                check("blueprint " + pack + " " + path + " loads", false, "not found");
                return null;
            }
            blueprint.setRotationMirror(RotationMirror.NONE, level);
            final BlockPos anchor = plot.offset(blueprint.getPrimaryBlockOffset());
            final CreativeStructureHandler handler = new CreativeStructureHandler(level, anchor, blueprint, RotationMirror.NONE, true);
            final StructurePlacer placer = new StructurePlacer(handler);
            int rounds = 0;
            while (rounds++ < 2000) {
                final StructurePhasePlacementResult result = placer.executeStructureStep(level, null, placer.getIterator().getProgressPos(),
                        StructurePlacer.Operation.BLOCK_PLACEMENT, () -> placer.getIterator().increment((info, at, h) -> false), false);
                if (result.getIteratorPos().equals(AbstractBlueprintIterator.NULL_POS)) {
                    break;
                }
            }
            final BlockPos max = plot.offset(blueprint.getSizeX() - 1, blueprint.getSizeY() - 1, blueprint.getSizeZ() - 1);
            return new Pasted(pack, path, plot, max, anchor);
        } catch (final Exception unreadable) {
            check("blueprint " + pack + " " + path + " pastes", false, unreadable.getClass().getSimpleName().replace("Exception", ""));
            return null;
        }
    }

    /** The pasted hut's own block becomes a MineColonies building of the colony, its corners the pasted box. */
    IBuilding registerPasted(final Pasted p) {
        final BlockEntity be = level.getBlockEntity(p.anchor());
        if (!(be instanceof AbstractTileEntityColonyBuilding te)) {
            check("the pasted " + p.pack() + " " + p.path() + " has its hut block at the anchor", false, String.valueOf(level.getBlockState(p.anchor())));
            return null;
        }
        // MineColonies' hut block entity may register itself with the colony when it loads; then it is there already
        IBuilding building = colony.getServerBuildingManager().getBuilding(p.anchor());
        if (building == null) {
            building = colony.getServerBuildingManager().addNewBuilding(te, level);
        }
        if (building == null) {
            check("the pasted " + p.pack() + " " + p.path() + " registers", false, "");
            return null;
        }
        building.setCorners(p.min(), p.max());
        building.setBuildingLevel(1);
        building.setStructurePack(p.pack());
        building.setBlueprintPath(p.path() + ".blueprint");
        building.markDirty();
        manual(building);
        return building;
    }

    ICitizenData spawn(final BlockPos at) {
        final ICitizenData data = colony.getCitizenManager().createAndRegisterCivilianData();
        colony.getCitizenManager().spawnOrCreateCitizen(data, level, at);
        return data;
    }

    ICitizenData hire(final IBuilding building, final BlockPos at) {
        final ICitizenData data = spawn(at);
        final WorkerBuildingModule module = building.getModulesByType(WorkerBuildingModule.class).stream().findFirst().orElse(null);
        check("a citizen is hired into " + BuiltInRegistries.BLOCK.getKey(level.getBlockState(building.getPosition()).getBlock()),
                module != null && module.assignCitizen(data) && data.getJob() != null,
                data.getJob() == null ? "no job" : data.getJob().getClass().getSimpleName());
        return data;
    }

    /** Fed and well: hunger or a disease would take them off their work for reasons that are not the festival's. */
    void keepWell() {
        if (colony == null) {
            return;
        }
        for (final ICitizenData c : colony.getCitizenManager().getCitizens()) {
            c.setSaturation(ICitizenData.MAX_SATURATION);
            if (c.getCitizenDiseaseHandler().isSick()) {
                c.getCitizenDiseaseHandler().cure();
            }
        }
    }

    JobDecorator job() {
        return decorator != null && decorator.getJob() instanceof JobDecorator j ? j : null;
    }

    FakePlayer player(final UUID id, final String name) {
        final FakePlayer p = FakePlayerFactory.get(level, new GameProfile(id, name));
        p.setPos(2.5, ground + 1, 12.5);
        p.getInventory().selected = 8;
        return p;
    }

    void players() {
        p1 = player(P1, "csfullcycle_one");
        p2 = player(P2, "csfullcycle_two");
        for (final FakePlayer p : List.of(p1, p2)) {
            colony.getPermissions().addPlayer(p.getGameProfile(), colony.getPermissions().getRankFriend());
        }
        final IColony mine = colony;
        Fireworks.setPresence((lvl, col) -> col == mine);
    }

    DecorLedger.Owner square() {
        return marker == null ? null : DecorLedger.Owner.square(marker);
    }

    /** The Decorator's gift pile at the tree, standing as listed; {@code null} without one. */
    BlockPos pile() {
        final DecorLedger.GroupView g = square() == null ? null : ledger.group(colonyId, square());
        if (g == null) {
            return null;
        }
        for (final Map.Entry<BlockPos, DecorLedger.Entry> e : g.entries().entrySet()) {
            if (e.getValue().state().getBlock() instanceof GiftPileBlock && level.getBlockState(e.getKey()) == e.getValue().state()) {
                return e.getKey();
            }
        }
        return null;
    }

    static int count(final Player p, final java.util.function.Predicate<ItemStack> what) {
        int n = 0;
        for (final ItemStack s : p.getInventory().items) {
            n += what.test(s) ? s.getCount() : 0;
        }
        return n;
    }

    static int gifts(final Player p, final ResourceKey<LootTable> table) {
        return count(p, s -> s.is(Gifts.GIFT.get()) && Gifts.tableOf(s).equals(table));
    }

    int rocketsInStock() {
        return hut == null ? 0 : hut.stock(Items.FIREWORK_ROCKET);
    }

    /** {lit, unlit} lights the ledger lists for the colony, read from the world. */
    int[] lights() {
        final int[] n = new int[2];
        for (final Map.Entry<BlockPos, DecorLedger.Entry> e : ledger.entries(colonyId).entrySet()) {
            final BlockState now = level.getBlockState(e.getKey());
            if (TreeLighting.isLight(now)) {
                n[now.getValue(Lights.LIT) ? 0 : 1]++;
            }
        }
        return n;
    }

    /** Off duty in the probe's sense: a colonist with no job (the Decorator has one). */
    List<ICitizenData> jobless() {
        final List<ICitizenData> out = new ArrayList<>();
        for (final ICitizenData c : colony.getCitizenManager().getCitizens()) {
            if (c.getJob() == null) {
                out.add(c);
            }
        }
        return out;
    }

    static double distTo(final ICitizenData c, final BlockPos site) {
        final AbstractEntityCitizen e = c.getEntity().orElse(null);
        return e == null ? Double.MAX_VALUE : Math.sqrt(e.blockPosition().distSqr(site));
    }

    // ================================================================ setup

    /** The colony with all its buildings, citizens and the marker; then the stock and the first snapshot. */
    private void planSetup() {
        final List<Pasted> pasted = new ArrayList<>();
        step(new Step("setup: the colony, its buildings, citizens and the marker", 120, true) {
            @Override
            void start() {
                setDate(FIRST);
                quietWorld();
                if (variant.effects()) {
                    CalendarManager.setSeason(server, Season.WINTER);   // the first twelve game days are winter
                }
                ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, 2, 2) - 1;
                final BlockPos center = new BlockPos(2, ground + 1, 2);
                forceChunks(7);
                owner = FakePlayerFactory.getMinecraft(level);
                owner.setPos(center.getX() + 0.5, center.getY(), center.getZ() + 0.5);
                colony = IColonyManager.getInstance().createColony(level, center, owner, "Seasons Full Cycle", "Medieval Oak");
                colonyId = colony == null ? -1 : colony.getID();
                check("a colony was made", colony != null, "id " + colonyId + " at " + center.toShortString() + ", ground y " + ground);
                if (colony == null) {
                    finish();
                    return;
                }
                townHall = register(colony, "minecolonies:blockhuttownhall", house(-3, -1, 11, 7, 2), 1);
                buildings.add(townHall);
                if (variant.hut()) {
                    final IBuilding d = register(colony, BuiltInRegistries.BLOCK.getKey(DecoratorSetup.HUT.get()).toString(),
                            house(12, -1, 11, 7, 17), HUT_LEVEL);
                    hut = d instanceof BuildingDecorator b ? b : null;
                    buildings.add(hut);
                }
                // the real huts: rows north of the town hall, then south of the square
                int x = -60;
                int z = -60;
                int rowDepth = 0;
                boolean south = false;
                for (final String[] one : PASTES) {
                    Blueprint bp = null;
                    try {
                        bp = StructurePacks.getBlueprintFuture(one[0], one[1] + ".blueprint", level.registryAccess()).get(60, TimeUnit.SECONDS);
                    } catch (final Exception unreadable) {
                        check("blueprint " + one[0] + " " + one[1] + " loads", false, unreadable.getClass().getSimpleName().replace("Exception", ""));
                    }
                    if (bp == null) {
                        continue;
                    }
                    if (x + bp.getSizeX() > 74) {
                        x = -60;
                        z += rowDepth + 4;
                        rowDepth = 0;
                    }
                    if (!south && z + bp.getSizeZ() > -6) {
                        south = true;
                        x = -60;
                        z = 44;
                        rowDepth = 0;
                    }
                    final Pasted p = paste(one[0], one[1], new BlockPos(x, ground + 1, z), bp);
                    if (p != null) {
                        pasted.add(p);
                    }
                    x += bp.getSizeX() + 4;
                    rowDepth = Math.max(rowDepth, bp.getSizeZ());
                }
                for (final Pasted p : pasted) {
                    final IBuilding b = registerPasted(p);
                    if (b != null) {
                        buildings.add(b);
                    }
                    note("pasted {} {} at {} to {}, hut {}", p.pack(), p.path(), p.min().toShortString(), p.max().toShortString(),
                            b == null ? "-" : BuiltInRegistries.BLOCK.getKey(level.getBlockState(p.anchor()).getBlock()));
                }
                check("twelve buildings registered (eleven without the Decorator's hut), from four style packs",
                        buildings.size() == (variant.hut() ? 12 : 11) && !buildings.contains(null), buildings.size() + " buildings");
                check("the town hall is the colony's town hall", colony.getServerBuildingManager().getTownHall() == townHall, "");
                boolean inClaim = true;
                String out = "";
                for (final IBuilding b : buildings) {
                    if (b == null) {
                        continue;
                    }
                    final DoorFinder.Box box = DoorFinder.Box.of(b);
                    for (final BlockPos corner : List.of(box.min(), box.max(), new BlockPos(box.min().getX(), ground + 1, box.max().getZ()),
                            new BlockPos(box.max().getX(), ground + 1, box.min().getZ()))) {
                        if (!claimed(corner)) {
                            inClaim = false;
                            out = corner.toShortString();
                        }
                    }
                }
                check("every building lies inside the colony's claim", inClaim, out);

                // citizens: the Decorator, four workers, two with no job
                if (hut != null) {
                    decorator = spawn(hut.getPosition().offset(0, 0, 4));
                    check("a citizen is hired as the Decorator", hut.getModule(DecoratorModules.WORK).assignCitizen(decorator)
                            && decorator.getJob() instanceof JobDecorator, decorator.getName());
                }
                int hired = 0;
                for (final IBuilding b : buildings) {
                    if (b != null && b != hut && hired < 4 && !b.getModulesByType(WorkerBuildingModule.class).isEmpty()) {
                        hire(b, center.offset(-4 + 2 * hired, 0, 9));
                        hired++;
                    }
                }
                check("four workers hired into the pasted huts", hired == 4, hired + " hired");
                spawn(center.offset(-2, 0, 11));
                spawn(center.offset(2, 0, 11));
                check("two colonists have no job", jobless().size() >= 2, jobless().size() + " jobless");

                if (variant.marker()) {
                    marker = new BlockPos(2, ground + 1, 32);
                    placeMarker();
                    checkEq("the marker is the colony's square", ledger.marker(colonyId), marker);
                }
                players();
                setTimeOfDay(MORNING);
            }

            @Override
            boolean tick() {
                return t >= 100 && !level.getChunkSource().getLightEngine().hasLightWork();
            }
        });
        step(new Step("setup: the tree piece, the stock and the first snapshot", 400, true) {
            @Override
            boolean tick() {
                return marker == null || PieceLoader.request(level, PieceLoader.PACK, PieceLoader.pathFor(HUT_LEVEL), Direction.NORTH) != null;
            }

            @Override
            void end(final boolean done) {
                check("the tree piece of hut level " + HUT_LEVEL + " is loaded", done, PieceLoader.pathFor(HUT_LEVEL));
                final Map<Item, Integer> need = needs();
                note("the twelve buildings and the tree need {}", text(need));
                for (final Item item : List.of(SeasonsBlocks.WREATH_ITEM.get(), SeasonsBlocks.GARLAND_ITEM.get(), SeasonsBlocks.LANTERN_STRING_ITEM.get(),
                        SeasonsBlocks.FAIRY_LIGHTS_ITEM.get(), SeasonsBlocks.ORNAMENT_LEAVES_ITEM.get(), SeasonsBlocks.TREE_STAR_ITEM.get(),
                        SeasonsBlocks.GIFT_PILE_ITEM.get(), SeasonsBlocks.FIREWORK_CRATE_ITEM.get(), Items.SPRUCE_LOG, Items.SPRUCE_LEAVES)) {
                    start.put(item, 0);
                }
                if (hut != null && variant.stock()) {
                    need.merge(SeasonsBlocks.WREATH_ITEM.get(), 1, Integer::sum);       // a spare for the one a player breaks on 5 Dec
                    final IItemHandler store = hut.getItemHandlerCap();
                    boolean fit = true;
                    for (final Map.Entry<Item, Integer> e : need.entrySet()) {
                        int left = e.getValue();
                        while (left > 0) {
                            final int n = Math.min(left, e.getKey().getDefaultMaxStackSize());
                            fit &= ItemHandlerHelper.insertItemStacked(store, new ItemStack(e.getKey(), n), false).isEmpty();
                            left -= n;
                        }
                        start.put(e.getKey(), start.getOrDefault(e.getKey(), 0) + e.getValue());
                    }
                    for (int i = 0; i < 3; i++) {
                        fit &= ItemHandlerHelper.insertItemStacked(store, new ItemStack(Items.FIREWORK_ROCKET, Fireworks.SHOW_ROCKETS), false).isEmpty();
                    }
                    check("the stock fits in the hut: what the buildings and the tree need, a spare wreath, three shows of rockets", fit,
                            text(need) + ", rockets " + rocketsInStock());
                }
                // the first snapshot: the claim's buildings, the square and two blocks of margin
                BlockPos lo = new BlockPos(Integer.MAX_VALUE, ground - 1, Integer.MAX_VALUE);
                BlockPos hi = new BlockPos(Integer.MIN_VALUE, ground + 24, Integer.MIN_VALUE);
                for (final IBuilding b : buildings) {
                    final DoorFinder.Box box = DoorFinder.Box.of(b);
                    lo = new BlockPos(Math.min(lo.getX(), box.min().getX()), lo.getY(), Math.min(lo.getZ(), box.min().getZ()));
                    hi = new BlockPos(Math.max(hi.getX(), box.max().getX()), Math.max(hi.getY(), box.max().getY()), Math.max(hi.getZ(), box.max().getZ()));
                }
                final BlockPos sq = marker != null ? marker : new BlockPos(2, ground + 1, 32);
                lo = new BlockPos(Math.min(lo.getX(), sq.getX() - 9), lo.getY(), Math.min(lo.getZ(), sq.getZ() - 9));
                hi = new BlockPos(Math.max(hi.getX(), sq.getX() + 9), Math.max(hi.getY(), sq.getY() + 20), Math.max(hi.getZ(), sq.getZ() + 9));
                first = Snap.take(level, lo.offset(-2, 0, -2), hi.offset(2, 2, 2));
                note("first snapshot: {} cells, {} to {}", first.size(), first.min.toShortString(), first.max.toShortString());
                saveFacts("setup");
                daily("28 Nov setup");
            }
        });
    }

    /** The festival marker, put down by the "player" the way a player does. */
    void placeMarker() {
        final BlockState m = SeasonsBlocks.FESTIVAL_MARKER.get().defaultBlockState().setValue(FestivalMarkerBlock.FACING, Direction.NORTH);
        level.setBlock(marker, m, Block.UPDATE_ALL);
        SeasonsBlocks.FESTIVAL_MARKER.get().setPlacedBy(level, marker, m, owner, new ItemStack(SeasonsBlocks.FESTIVAL_MARKER_ITEM.get()));
    }

    /** What the colony's buildings and its tree cost at the hut level (the planners read the world, nothing is placed). */
    Map<Item, Integer> needs() {
        final Map<Item, Integer> need = new LinkedHashMap<>();
        if (hut == null) {
            return need;
        }
        for (final IBuilding b : buildings) {
            final DecorPlanner.Plan plan = DecorPlanner.plan(level, DoorFinder.Box.of(b), HUT_LEVEL, SeasonsConfig.maxPerSide(), ledger, colonyId,
                    DecorLedger.Owner.building(b.getPosition()), true, DecorKinds.ALL);
            for (final DecorPlacer.Slot slot : plan.slots()) {
                final Item item = DecorPlacer.itemOf(slot.state());
                if (item != null && slot.costs()) {
                    need.merge(item, 1, Integer::sum);
                }
            }
        }
        if (marker != null) {
            final PieceLoader.Piece piece = PieceLoader.request(level, PieceLoader.PACK, PieceLoader.pathFor(HUT_LEVEL), Direction.NORTH);
            if (piece != null) {
                final PieceLoader.SquarePlan plan = PieceLoader.plan(level, piece, marker, HUT_LEVEL, ledger, colonyId, square(), true);
                for (final DecorPlacer.Slot slot : plan.slots()) {
                    final Item item = DecorPlacer.itemOf(slot.state());
                    if (item != null && slot.costs()) {
                        need.merge(item, 1, Integer::sum);
                    }
                }
            }
        }
        return need;
    }

    static String text(final Map<Item, Integer> items) {
        final StringBuilder b = new StringBuilder();
        for (final Map.Entry<Item, Integer> e : items.entrySet()) {
            if (b.length() > 0) {
                b.append(", ");
            }
            b.append(e.getValue()).append(' ').append(BuiltInRegistries.ITEM.getKey(e.getKey()).getPath());
        }
        return b.toString();
    }

    // ================================================================ the daily invariants

    /** I1 to I4 after a game day (see the class comment); one CHECK line. */
    void daily(final String when) {
        final List<String> wrong = new ArrayList<>();
        final Map<BlockPos, DecorLedger.Entry> entries = ledger.entries(colonyId);
        // I1
        int i1 = 0;
        String i1first = "";
        for (final Map.Entry<BlockPos, DecorLedger.Entry> e : entries.entrySet()) {
            if (level.hasChunkAt(e.getKey()) && level.getBlockState(e.getKey()) != e.getValue().state()) {
                if (i1++ == 0) {
                    i1first = e.getKey().toShortString() + " " + level.getBlockState(e.getKey()) + " vs " + e.getValue().state();
                }
            }
        }
        if (i1 > 0) {
            wrong.add("I1 " + i1 + " entries differ from the world, first " + i1first);
        }
        // I2
        if (first != null) {
            final int[] total = new int[1];
            final List<BlockPos> unlisted = first.diff(level, p -> ledger.at(p) == null && !probeChanged.contains(p), 20, total);
            if (total[0] > 0) {
                final StringBuilder b = new StringBuilder();
                for (final BlockPos p : unlisted) {
                    b.append(p.toShortString()).append(' ').append(shortName(level.getBlockState(p))).append(" was ")
                            .append(shortName(first.at(p))).append("; ");
                }
                wrong.add("I2 " + total[0] + " unlisted cells differ from the first snapshot: " + b);
            }
        }
        // I3
        final Map<Item, Integer> now = conservation();
        for (final Map.Entry<Item, Integer> e : start.entrySet()) {
            final int got = now.getOrDefault(e.getKey(), 0);
            if (got != e.getValue()) {
                wrong.add("I3 " + BuiltInRegistries.ITEM.getKey(e.getKey()).getPath() + " " + got + " of " + e.getValue());
            }
        }
        // I4
        int i4 = 0;
        for (final BlockPos pos : entries.keySet()) {
            if (level.hasChunkAt(pos) && !claimed(pos)) {
                i4++;
            }
        }
        if (i4 > 0) {
            wrong.add("I4 " + i4 + " entries outside the claim");
        }
        check("daily invariants " + when, wrong.isEmpty(), entries.size() + " entries, phase " + (job() == null ? "-" : job().phase())
                + (wrong.isEmpty() ? "" : "; " + String.join("; ", wrong)));
    }

    static String shortName(final BlockState state) {
        return state == null ? "-" : BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
    }

    /** Every tracked item where it can be: the hut, the colony's citizens, items lying in the box, the probe's hands, paid entries. */
    Map<Item, Integer> conservation() {
        final Map<Item, Integer> n = new LinkedHashMap<>();
        for (final Item item : start.keySet()) {
            final java.util.function.Predicate<ItemStack> is = s -> s.is(item);
            int c = hut == null ? 0 : BuildingDecorator.countIn(hut.getItemHandlerCap(), is);
            for (final ICitizenData citizen : colony.getCitizenManager().getCitizens()) {
                c += BuildingDecorator.countIn(citizen.getInventory(), is);
            }
            n.put(item, c + held.getOrDefault(item, 0));
        }
        if (first != null) {
            for (final ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, new AABB(first.min.getCenter(), first.max.getCenter()).inflate(4))) {
                n.computeIfPresent(drop.getItem().getItem(), (k, v) -> v + drop.getItem().getCount());
            }
        }
        for (final DecorLedger.Entry e : ledger.entries(colonyId).values()) {
            final Item item = DecorPlacer.itemOf(e.state());
            if (e.paid() && item != null) {
                n.computeIfPresent(item, (k, v) -> v + 1);
            }
        }
        return n;
    }

    /** True when every building with door slots is decorated (or marked empty) at the hut level, and the tree is complete. */
    String undecorated() {
        final List<String> open = new ArrayList<>();
        for (final IBuilding b : buildings) {
            final DecorLedger.GroupView g = ledger.group(colonyId, DecorLedger.Owner.building(b.getPosition()));
            if (g == null || g.decoratedDay() < 0) {
                open.add(BuiltInRegistries.BLOCK.getKey(level.getBlockState(b.getPosition()).getBlock()).getPath()
                        + (g == null ? " (no group)" : " (" + g.size() + " up)"));
            }
        }
        if (marker != null) {
            final DecorLedger.GroupView g = ledger.group(colonyId, square());
            if (g == null || g.decoratedDay() < 0 || !g.piece().equals(PieceLoader.pathFor(HUT_LEVEL))) {
                open.add("the tree" + (g == null ? "" : " (" + g.size() + " up)"));
            }
        }
        return String.join(", ", open);
    }

    // ================================================================ the timeline

    /** A per-date hook: what the probe does and checks on a game day of that date. */
    abstract class DayHook {
        void start() {
        }

        void tick(final int t) {
        }

        boolean done() {
            return false;
        }

        void end(final boolean done) {
        }
    }

    /** One game day of a date, from the morning to {@code until} (a time of day), then the daily invariants. */
    Step day(final LocalDate date, final String label, final int until, final DayHook hook) {
        return new Step(label, until - MORNING + 300, true) {
            @Override
            void start() {
                setDate(date);
                newGameDay(MORNING);
                keepWell();
                hook.start();
            }

            @Override
            boolean tick() {
                hook.tick(t);
                return timeOfDay() >= until || hook.done();
            }

            @Override
            void end(final boolean done) {
                hook.end(done);
                variantDaily(date);
                daily(label);
            }
        };
    }

    // counters read across days
    int pulls0;
    int lightings0;
    int countEve;
    int stockNewYear;
    int flownNewYear;
    int rocketsAdded;
    BlockPos brokenWreath;
    boolean stopAtFirstTakeDown;

    /** The dates {@code from} to {@code to}, each with its game days and checks. */
    void planDates(final LocalDate from, final LocalDate to, final boolean stopEarly) {
        stopAtFirstTakeDown = stopEarly;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            planDate(d, d.equals(to) && stopEarly);
        }
    }

    private void planDate(final LocalDate d, final boolean stopEarly) {
        final int m = d.getMonthValue();
        final int dom = d.getDayOfMonth();
        final String label = d.toString();
        final boolean decorating = variant.hut() && (variant.stock() || variant.marker());
        if (m == 11) {
            step(day(d, label, EVENING, new DayHook() {
                @Override
                void start() {
                    pulls0 = Gathering.pulls();
                }

                @Override
                void end(final boolean done) {
                    // the outside decorations (a Workshop's storeroom would stand packed before a window, S10g2)
                    check(label + ": before the window nothing is up, gifts are closed, nobody is called", ledger.countOutdoor(colonyId) == 0
                                    && !Gifts.season(CalendarManager.current()) && Gathering.pulls() == pulls0,
                            ledger.count(colonyId) + " entries, gifts season " + Gifts.season(CalendarManager.current()) + ", " + (Gathering.pulls() - pulls0) + " pulls");
                }
            }));
            return;
        }
        if (m == 12 && dom <= 2) {
            final int days = 2;
            for (int i = 0; i < days; i++) {
                final boolean lastOfDate = i == days - 1;
                final String l = label + " game day " + (i + 1);
                step(day(d, l, EVENING, new DayHook() {
                    @Override
                    void end(final boolean done) {
                        note("{}: {} entries, {} groups, status '{}'", l, ledger.count(colonyId), ledger.groups(colonyId).size(),
                                job() == null ? "-" : job().status());
                        if (dom == 2 && lastOfDate) {
                            decoratedByNow(label);
                        }
                    }
                }));
            }
            return;
        }
        if (m == 12 && dom == 3) {
            step(day(d, label, EVENING, new DayHook() {
                @Override
                void end(final boolean done) {
                    giftOnce(label, Gifts.DAILY);
                }
            }));
            return;
        }
        if (m == 12 && dom == 5) {
            step(day(d, label, EVENING, new DayHook() {
                @Override
                void start() {
                    if (decorating && marker != null && variant.stock()) {
                        final FestivalData.ColonyState s = FestivalData.of(level).peek(colonyId);
                        final int[] l = lights();
                        check("the tree is lit within two game days of being finished (by the Decorator: no player clicked)",
                                TreeLighting.lightings() > 0 && s != null && s.treeLit() && l[1] == 0 && l[0] > 0,
                                TreeLighting.lightings() + " lightings, lit by '" + (s == null ? "-" : s.litBy()) + "', " + l[0] + " lit and " + l[1] + " unlit lights");
                    }
                }

                @Override
                void end(final boolean done) {
                    // a player breaks one of the wreaths and keeps it
                    brokenWreath = null;
                    for (final DecorLedger.GroupView g : ledger.groups(colonyId)) {
                        for (final Map.Entry<BlockPos, DecorLedger.Entry> e : g.entries().entrySet()) {
                            if (brokenWreath == null && g.owner().kind() == DecorLedger.Kind.BUILDING && e.getValue().state().is(SeasonsBlocks.WREATH.get())
                                    && e.getValue().paid()) {
                                brokenWreath = e.getKey();
                            }
                        }
                    }
                    if (brokenWreath != null) {
                        level.setBlock(brokenWreath, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                        held.merge(SeasonsBlocks.WREATH_ITEM.get(), 1, Integer::sum);
                        check("a wreath a player broke is forgotten by the ledger", ledger.at(brokenWreath) == null, brokenWreath.toShortString());
                    } else {
                        check("a listed wreath to break", !decorating || !variant.stock(), "none found");
                    }
                }
            }));
            return;
        }
        if (m == 12 && dom == 6) {
            step(day(d, label, EVENING, new DayHook() {
                @Override
                boolean done() {
                    return brokenWreath != null && level.getBlockState(brokenWreath).is(SeasonsBlocks.WREATH.get()) && ledger.at(brokenWreath) != null;
                }

                @Override
                void end(final boolean done) {
                    if (brokenWreath != null) {
                        check("the broken wreath is back the next game day (tending)", done, brokenWreath.toShortString() + ", status '"
                                + (job() == null ? "-" : job().status()) + "'");
                    }
                }
            }));
            return;
        }
        if (m == 12 && dom == 12 && mode.equals("restart-save")) {
            step(day(d, label, EVENING, new DayHook() {
                @Override
                void end(final boolean done) {
                    giftOnce(label, Gifts.DAILY);
                }
            }));
            return;
        }
        if (m == 12 && (dom == 23 || dom == 27)) {
            step(day(d, label, EVENING, new DayHook() {
                @Override
                void start() {
                    pulls0 = Gathering.pulls();
                }

                @Override
                void end(final boolean done) {
                    checkEq(label + ": not a gathering day, nobody is called", Gathering.pulls() - pulls0, 0);
                    if (dom == 27 && mode.equals("nodecorator") && marker != null && !(level.getBlockState(marker).getBlock() instanceof FestivalMarkerBlock)) {
                        placeMarker();                // the marker broken on 25 Dec goes back, so the world can equal the snapshot
                        probeChanged.remove(marker);
                    }
                }
            }));
            return;
        }
        if (m == 12 && dom >= 24 && dom <= 26) {
            step(day(d, label, AFTER_HOURS, gathering(d, label)));
            return;
        }
        if (m == 12 && dom == 31) {
            step(day(d, label, LATE, newYearDusk(d, label, true)));
            planMidnight();
            return;
        }
        if (m == 1 && dom == 1) {
            step(day(d, label, LATE, newYearDusk(d, label, false)));
            return;
        }
        if (m == 1 && dom <= 6) {
            step(day(d, label, EVENING, new DayHook() {
                @Override
                void end(final boolean done) {
                    if (decorating && countEve > 0) {
                        check(label + ": the decorations stay up (tending only)", ledger.count(colonyId) >= countEve,
                                ledger.count(colonyId) + " entries, " + countEve + " on New Year's Eve");
                    }
                }
            }));
            return;
        }
        if (m == 1 && dom >= 7 && dom <= 9) {
            step(day(d, label, EVENING, new DayHook() {
                int before;
                int visits0;

                @Override
                void start() {
                    before = ledger.count(colonyId);
                    visits0 = job() == null ? 0 : job().visits(EntityAIWorkDecorator.DecoratorState.TAKING_DOWN);
                    if (dom == 7) {
                        final BlockPos pile = pile();
                        if (pile != null) {
                            checkEq(label + ": after the window the gift pile is closed", Gifts.take(level, pile, p2), Gifts.Outcome.CLOSED);
                        }
                    }
                }

                @Override
                boolean done() {
                    return stopEarly && job() != null && job().visits(EntityAIWorkDecorator.DecoratorState.TAKING_DOWN) > visits0
                            && ledger.count(colonyId) < before && !ledger.groups(colonyId).isEmpty();
                }

                @Override
                void end(final boolean done) {
                    final int after = ledger.count(colonyId);
                    if (stopEarly) {
                        check(label + ": the first take-down visit: some groups down, others still listed", done && after < before && after > 0,
                                before + " -> " + after + " entries, " + ledger.groups(colonyId).size() + " groups");
                    } else if (decorating && before > 0) {
                        check(label + ": the take-down goes on, the count falls", after < before, before + " -> " + after + " entries, status '"
                                + (job() == null ? "-" : job().status()) + "'");
                    }
                }
            }));
            return;
        }
        if (m == 1 && dom == 10) {
            for (int i = 0; i < 3; i++) {
                step(day(d, label + " game day " + (i + 1), EVENING, new DayHook() {
                    @Override
                    void start() {
                        if (!ledger.hasOutdoorEntries(colonyId) && (hut == null || !hut.decorationsUp())) {
                            setTimeOfDay(EVENING - 200);   // everything is down: this game day only passes
                        }
                    }
                }));
            }
            return;
        }
        step(day(d, label, EVENING, new DayHook() {
        }));
    }

    /** End of 2 Dec: what the variant says should be up is up. */
    private void decoratedByNow(final String label) {
        if (!variant.hut()) {
            checkEq(label + ": with no Decorator nothing is placed", ledger.count(colonyId), 0);
            final BlockPos own = marker.offset(2, 0, 2);
            final BlockState pile = SeasonsBlocks.GIFT_PILE.get().defaultBlockState();
            set(own, pile);
            checkEq(label + ": a gift pile a player put down hands out nothing", Gifts.take(level, own, p1), Gifts.Outcome.NOT_DECORATORS);
            set(own, Blocks.AIR.defaultBlockState());
            return;
        }
        if (!variant.stock()) {
            checkEq(label + ": with no materials nothing is placed", ledger.count(colonyId), 0);
            final int open = hut.getOpenRequests(decorator.getId()).size();
            check(label + ": the Decorator's requests for materials are open", open > 0, open + " open requests");
            timeNextToDecorate(label);
            return;
        }
        final String open = undecorated();
        check(label + ": every building with door slots is decorated (or marked empty)" + (marker != null ? " and the tree is complete" : ""),
                open.isEmpty(), open.isEmpty() ? ledger.count(colonyId) + " entries in " + ledger.groups(colonyId).size() + " groups" : "open: " + open);
        // the Workshop's page counts its buildings done in the ledger itself: the same number as the groups' copies say
        int fromGroups = 0;
        for (final DecorLedger.GroupView g : ledger.groups(colonyId)) {
            if (g.owner().kind() == DecorLedger.Kind.BUILDING && g.decoratedDay() >= 0 && g.size() > 0) {
                fromGroups++;
            }
        }
        final int onPage = hut == null ? -1 : hut.getModule(DecoratorModules.INFO).read().buildingsDone();
        check(label + ": the Workshop's page counts the decorated buildings as the ledger's groups do", fromGroups > 0 && onPage == fromGroups,
                onPage + " on the page, " + fromGroups + " in the groups");
        if (marker == null) {
            check(label + ": no marker: no square group, no gift pile, no site for fireworks", ledger.groups(colonyId).stream()
                    .noneMatch(g -> g.owner().kind() == DecorLedger.Kind.SQUARE) && Fireworks.site(level, colony) == null, "");
            return;
        }
        final BlockPos pile = pile();
        check(label + ": the tree has its gift pile", pile != null, "");
        if (pile != null) {
            giftOnce(label, Gifts.DAILY);
        }
    }

    /** p1 takes today's gift: GIVEN from {@code table}, the second click ALREADY. */
    void giftOnce(final String label, final ResourceKey<LootTable> table) {
        final BlockPos pile = pile();
        if (pile == null) {
            return;
        }
        final int before = gifts(p1, table);
        final Gifts.Outcome a = Gifts.take(level, pile, p1);
        final Gifts.Outcome b = Gifts.take(level, pile, p1);
        check(label + ": one gift a player a real day (" + table.location().getPath() + "), the second click refused",
                a == Gifts.Outcome.GIVEN && b == Gifts.Outcome.ALREADY && gifts(p1, table) == before + 1, a + ", " + b + ", " + (gifts(p1, table) - before) + " gifts");
    }

    /** NOTE: the mean time of one nextToDecorate call with the colony's buildings (S4's hot path, measured only). */
    void timeNextToDecorate(final String label) {
        final DecorPlacer.Stock stock = DecorWork.stockOf(hut, decorator.getEntity().orElse(null));
        final long t0 = System.nanoTime();
        final int n = 20;
        for (int i = 0; i < n; i++) {
            DecorWork.nextToDecorate(level, colony, HUT_LEVEL, hut.getPosition(), ledger, stock, day());
        }
        final double ms = (System.nanoTime() - t0) / 1e6 / n;
        note("{}: one nextToDecorate call with {} buildings takes {} ms on average ({} calls)", label, buildings.size(), String.format("%.2f", ms), n);
    }

    /** 24 to 26 Dec: the gathering hours. */
    private DayHook gathering(final LocalDate d, final String label) {
        final int dom = d.getDayOfMonth();
        return new DayHook() {
            int songs0;
            int handed0;
            int pullsAt0;
            int hostingAtStart = -1;
            int hostingAtEnd = -1;
            int samples;
            int bestNear;
            int sampleSize;
            double farthest;
            BlockPos site;
            String seen = "";
            boolean slowed;

            @Override
            void start() {
                songs0 = Gathering.songs();
                handed0 = Gifts.handed();
                pullsAt0 = Gathering.pulls();
                if (dom == 24) {
                    giftOnce(label, Gifts.FESTIVAL);
                }
            }

            @Override
            void tick(final int t) {
                final long time = timeOfDay();
                final int[] hours = Gathering.hours();
                if (!slowed && time >= hours[0] - 400) {
                    // MineColonies finds paths on its own threads: at a full sprint a pull's path is asked for again before
                    // the last one was found, and the colonists stand still. 400 ticks a second leaves the pathfinder its time.
                    slowed = true;
                    server.tickRateManager().stopSprinting();
                    server.tickRateManager().setTickRate(GATHER_TICK_RATE);
                }
                if (hostingAtStart < 0 && time >= hours[0] && job() != null) {
                    hostingAtStart = job().visits(EntityAIWorkDecorator.DecoratorState.HOSTING);
                }
                if (hostingAtEnd < 0 && time >= hours[1] && job() != null) {
                    hostingAtEnd = job().visits(EntityAIWorkDecorator.DecoratorState.HOSTING);
                }
                if (time >= hours[0] + 400 && time < hours[1] - 100 && t % 40 == 0) {
                    site = Gathering.site(level, colony);
                    final List<ICitizenData> off = jobless();
                    sampleSize = Math.max(sampleSize, off.size());
                    if (samples == 20) {
                        seen = where(off, site);
                    }
                    int near = 0;
                    for (final ICitizenData c : off) {
                        final double dist = distTo(c, site);
                        near += dist <= WITHIN ? 1 : 0;
                        if (dist != Double.MAX_VALUE) {
                            farthest = Math.max(farthest, dist);
                        }
                    }
                    samples++;
                    bestNear = Math.max(bestNear, near);
                }
            }

            @Override
            void end(final boolean done) {
                server.tickRateManager().setTickRate(20);
                final BlockPos want = expectedSite(dom);
                check(label + ": the off-duty colonists gather at " + (want == null ? "-" : want.toShortString()) + " in the hours",
                        site != null && site.equals(want) && samples > 0 && bestNear * 2 >= Math.max(1, sampleSize) && farthest <= Gathering.CALL_RADIUS,
                        "site " + (site == null ? "-" : site.toShortString()) + ", at best " + bestNear + " of " + sampleSize + " within " + WITHIN
                                + " in " + samples + " samples, farthest " + String.format("%.0f", farthest) + ", " + (Gathering.pulls() - pullsAt0) + " pulls"
                                + (bestNear * 2 >= Math.max(1, sampleSize) ? "" : "; mid-hours: " + seen));
                check(label + ": the song plays", Gathering.songs() > songs0, (Gathering.songs() - songs0) + " songs");
                if (hut != null) {
                    check(label + ": the Decorator hands colonists gifts", Gifts.handed() > handed0, (Gifts.handed() - handed0) + " handed");
                    final int[] hours = Gathering.hours();
                    final int visits = hostingAtStart < 0 || hostingAtEnd < 0 ? -1 : hostingAtEnd - hostingAtStart;
                    final int bound = (hours[1] - hours[0]) / EntityAIWorkDecorator.PHASE_VISIT_TICKS + 1;
                    check(label + ": a Hosting visit is counted once per visit, not per pass (the S7 hand-off)", visits >= 0 && visits <= bound,
                            visits + " visits in the hours, at most " + bound);
                }
                if (!variant.hut()) {
                    clothes(label);
                }
                if (dom == 25 && mode.equals("nodecorator") && marker != null) {
                    level.setBlock(marker, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    probeChanged.add(marker);
                    check(label + ": the marker broken: the colony has no square any more", ledger.marker(colonyId) == null, "");
                }
            }
        };
    }

    /** For a failing gathering check: each colonist's AI state, distance to the site and whether his path is done. */
    private static String where(final List<ICitizenData> who, final BlockPos site) {
        final StringBuilder b = new StringBuilder();
        for (final ICitizenData c : who) {
            final AbstractEntityCitizen e = c.getEntity().orElse(null);
            String state = "no entity";
            if (e instanceof com.minecolonies.core.entity.citizen.EntityCitizen ec && ec.getCitizenAI() != null) {
                state = String.valueOf(ec.getCitizenAI().getState()) + (ec.getNavigation().isDone() ? " idle path" : " walking")
                        + (ec.getCitizenSleepHandler() != null && ec.getCitizenSleepHandler().isAsleep() ? " asleep" : "");
            }
            b.append(c.getId()).append(' ').append(state).append(' ').append(String.format("%.0f", distTo(c, site)))
                    .append(e == null ? "" : " at " + e.blockPosition().toShortString()).append("; ");
        }
        return b.toString();
    }

    /** Where the colony should gather on the given festival day. */
    private BlockPos expectedSite(final int dom) {
        if (marker == null || mode.equals("nodecorator") && dom == 26) {
            return townHall.getPosition();
        }
        return marker;
    }

    /** With no Decorator the colonists still dress: the festive scarf on festival dates (the client's rules, read on the server). */
    private void clothes(final String label) {
        final CalendarManager.Snapshot s = CalendarManager.current();
        final ClothesRules.Outfit outfit = ClothesRules.outfit(true, s.season().season(), s.festivals().ids(),
                new ClothesRules.Wearer(3, false, true, ClothesRules.Head.EMPTY, false));
        check(label + ": colonists wear the festive scarf (no Decorator needed)", ClothesRules.FESTIVE.equals(outfit.scarf()), String.valueOf(outfit));
    }

    /** 31 Dec and 1 Jan: the day and its dusk show. */
    private DayHook newYearDusk(final LocalDate d, final String label, final boolean eve) {
        return new DayHook() {
            int stock0;
            int flown0;
            int shows0;
            int seen;
            boolean added;
            boolean notedEmpty = true;

            @Override
            void start() {
                stock0 = rocketsInStock();
                flown0 = Fireworks.rockets();
                shows0 = Fireworks.shows();
                if (eve) {
                    stockNewYear = stock0;
                    flownNewYear = flown0;
                    rocketsAdded = 0;
                    giftOnce(label, Gifts.NEW_YEAR);
                }
            }

            @Override
            void tick(final int t) {
                final long time = timeOfDay();
                if (time > Fireworks.DUSK_FROM && t % 10 == 0) {
                    final BlockPos at = marker != null ? marker : townHall.getPosition();
                    seen = Math.max(seen, level.getEntitiesOfClass(FireworkRocketEntity.class, new AABB(at).inflate(64)).size());
                }
                // the empty warehouse: an empty dusk notes nothing, rockets put in the hut during it give that dusk's show
                if (eve && hut != null && !variant.stock() && !added && time >= Fireworks.DUSK_FROM + Fireworks.LAUNCH_GRACE + 100) {
                    final FestivalData.ColonyState s = FestivalData.of(level).peek(colonyId);
                    notedEmpty = s != null && s.lastDuskGameDay() == day();
                    ItemHandlerHelper.insertItemStacked(hut.getItemHandlerCap(), new ItemStack(Items.FIREWORK_ROCKET, Fireworks.SHOW_ROCKETS), false);
                    rocketsAdded += Fireworks.SHOW_ROCKETS;
                    added = true;
                }
            }

            @Override
            void end(final boolean done) {
                final int used = stock0 + (added ? Fireworks.SHOW_ROCKETS : 0) - rocketsInStock();
                final int flown = Fireworks.rockets() - flown0;
                if (eve) {
                    countEve = ledger.count(colonyId);
                }
                if (hut == null || marker == null) {
                    checkEq(label + ": no " + (hut == null ? "Decorator" : "square") + ", no dusk show", Fireworks.shows() - shows0, 0);
                    return;
                }
                if (!variant.stock() && eve) {
                    check(label + ": a dusk with no rockets notes nothing; rockets put in the hut during it give that dusk's show",
                            !notedEmpty && added && used == Fireworks.SHOW_ROCKETS && flown == Fireworks.SHOW_ROCKETS,
                            "noted before the rockets came " + notedEmpty + ", " + used + " used, " + flown + " flew");
                    return;
                }
                if (variant.stock()) {
                    check(label + ": the dusk show goes up from the Decorator's stock", used > 0 && flown == used && seen > 0,
                            used + " from stock, " + flown + " flew, " + seen + " rocket entities seen");
                }
                if (!eve) {
                    final int allUsed = stockNewYear + rocketsAdded - rocketsInStock();
                    final int allFlown = Fireworks.rockets() - flownNewYear;
                    check("31 Dec to 1 Jan: the rocket stock dropped by exactly what the shows sent up", allUsed == allFlown,
                            allUsed + " from stock, " + allFlown + " flew");
                }
            }
        };
    }

    private void planMidnight() {
        final LocalDateTime startAt = LocalDateTime.of(2026, 12, 31, 23, 59, 50);
        step(new Step("31 Dec: the midnight show (the fake clock steps over midnight)", 600, true) {
            LocalDateTime now = startAt;
            int countdowns0;
            int shows0;
            int before;
            int flown0;

            @Override
            void start() {
                before = rocketsInStock();
                flown0 = Fireworks.rockets();
                countdowns0 = Fireworks.countdowns();
                shows0 = Fireworks.midnightShows();
                setDateTime(now);
            }

            @Override
            boolean tick() {
                if (t % 20 == 0) {
                    now = now.plusSeconds(1);
                    setDateTime(now);
                }
                return now.isAfter(startAt.plusSeconds(25));
            }

            @Override
            void end(final boolean done) {
                final int countdowns = Fireworks.countdowns() - countdowns0;
                checkEq("midnight: one midnight show", Fireworks.midnightShows() - shows0, 1);
                if (hut != null && marker != null) {
                    check("midnight: a countdown on the action bar in the last seconds", countdowns >= 8 && countdowns <= 10, countdowns + " seconds counted");
                    final int used = before - rocketsInStock();
                    check("midnight: its rockets come from the stock and fly", used == Math.min(before, Fireworks.MIDNIGHT_ROCKETS)
                            && Fireworks.rockets() - flown0 == used, used + " from stock, " + (Fireworks.rockets() - flown0) + " flew");
                } else {
                    checkEq("midnight: no square or no Decorator, no rockets", Fireworks.rockets() - flown0, 0);
                }
                setDate(LocalDate.of(2027, 1, 1));
            }
        });
    }

    /** What every variant checks after each game day, on top of the invariants. */
    private void variantDaily(final LocalDate d) {
        final CalendarManager.Snapshot s = CalendarManager.current();
        final String label = d.toString();
        if (!variant.hut() || !variant.stock() && hut != null) {
            if (ledger.count(colonyId) != 0) {
                checkEq(label + ": nothing is ever placed (" + (hut == null ? "no Decorator" : "no materials") + ")", ledger.count(colonyId), 0);
            }
        }
        if (variant.serene()) {
            final boolean ok = s.season().season() == Season.AUTUMN && s.season().yearLength() == 96 && !CalendarManager.bridge().fellBack();
            if (!ok || d.getDayOfMonth() % 7 == 0) {
                check(label + ": the season follows the Serene Seasons stand-in", ok, s.season().season() + ", year " + s.season().yearLength()
                        + ", fell back " + CalendarManager.bridge().fellBack());
            }
        }
        if (variant.effects()) {
            effectsDaily(label, s);
        }
    }

    private void effectsDaily(final String label, final CalendarManager.Snapshot s) {
        final boolean festival = !s.festivals().days().isEmpty();
        int with = 0;
        int all = 0;
        for (final ICitizenData c : colony.getCitizenManager().getCitizens()) {
            all++;
            with += HappinessEffect.present(c) ? 1 : 0;
        }
        check(label + ": the happiness bonus only on festival days", festival ? all > 0 && with == all : with == 0,
                with + " of " + all + " colonists" + (festival ? ", a festival day" : ""));
        final boolean winter = s.season().season() == Season.WINTER;
        final BlockPos inside = new BlockPos(2, ground + 1, 12);
        int skips = 0;
        for (int i = 0; i < 2000; i++) {
            skips += CropEffect.skips(level, inside) ? 1 : 0;
        }
        int outside = 0;
        for (int i = 0; i < 300; i++) {
            outside += CropEffect.skips(level, new BlockPos(3000, ground + 1, 8)) ? 1 : 0;
        }
        final double ratio = skips / 2000.0;
        check(label + ": crops and food follow the season (" + s.season().season() + ")", winter
                        ? ratio > 0.44 && ratio < 0.56 && outside == 0 && FoodEffect.active()
                        : skips == 0 && outside == 0 && !FoodEffect.active(),
                String.format("%.3f", ratio) + " of growth steps skipped in the claim, " + outside + " outside, food " + FoodEffect.active());
    }

    // ================================================================ the end

    private void planEnd() {
        step(new Step("10 Jan: everything is down and the world equals the first snapshot", 1) {
            @Override
            void start() {
                setDate(LAST);
                final boolean up = hut != null && hut.decorationsUp();
                check("the ledger is empty, no groups left, the hut says the decorations are down", ledger.count(colonyId) == 0
                        && ledger.groups(colonyId).isEmpty() && !up, ledger.count(colonyId) + " entries, " + ledger.groups(colonyId).size() + " groups, up " + up);
                final Map<Item, Integer> now = conservation();
                check("every tracked item is back where it was (I3 exact)", now.equals(withHeld(start)), "now " + text(now) + "; at the start " + text(start));
                final int[] total = new int[1];
                final List<BlockPos> diff = first.diff(level, p -> true, 20, total);
                final StringBuilder b = new StringBuilder();
                for (final BlockPos p : diff) {
                    b.append(p.toShortString()).append(' ').append(shortName(level.getBlockState(p))).append(" was ").append(shortName(first.at(p)))
                            .append(ledger.at(p) == null ? " (unlisted)" : " (listed)").append("; ");
                }
                check("the world equals the first snapshot cell for cell (" + first.size() + " cells)", total[0] == 0, total[0] + " differ " + b);
                if (hut != null && !variant.stock()) {
                    final int open = hut.getOpenRequests(decorator.getId()).size();
                    check("10 Jan: the Decorator's requests are still open, nothing broke", open > 0, open + " open requests");
                }
                if (variant.effects()) {
                    effectsOff();
                }
            }
        });
    }

    /** Start counts as they should read now: what the probe holds is counted in conservation() already. */
    private Map<Item, Integer> withHeld(final Map<Item, Integer> base) {
        return new LinkedHashMap<>(base);
    }

    /** The three switches set off through the config spec: a pass later nothing of the effects is left. */
    private void effectsOff() {
        CalendarManager.setForcedFestival(server, SeasonsConfig.WINTER_FESTIVAL);
        CalendarManager.setSeason(server, Season.WINTER);
        Effects.pass();
        int with = 0;
        for (final ICitizenData c : colony.getCitizenManager().getCitizens()) {
            with += HappinessEffect.present(c) ? 1 : 0;
        }
        final BlockPos inside = new BlockPos(2, ground + 1, 12);
        int skipsOn = 0;
        for (int i = 0; i < 400; i++) {
            skipsOn += CropEffect.skips(level, inside) ? 1 : 0;
        }
        check("effects on during a forced festival in winter: the bonus, the crops and the food are on", with > 0 && skipsOn > 0 && FoodEffect.active(),
                with + " with the bonus, " + skipsOn + " of 400 skipped, food " + FoodEffect.active());
        // the spec's own values (private in SeasonsConfig): what a server owner's edit of the file does, set at once
        for (final String field : List.of("FESTIVAL_HAPPINESS", "WINTER_CROP_SLOWDOWN", "WINTER_FOOD_NEED")) {
            try {
                final java.lang.reflect.Field f = SeasonsConfig.class.getDeclaredField(field);
                f.setAccessible(true);
                final ModConfigSpec.BooleanValue b = (ModConfigSpec.BooleanValue) f.get(null);
                b.set(false);
                b.clearCache();
            } catch (final ReflectiveOperationException | ClassCastException unreadable) {
                check("the config switch " + field + " can be set", false, unreadable.getClass().getSimpleName().replace("Exception", ""));
            }
        }
        Effects.pass();
        int after = 0;
        for (final ICitizenData c : colony.getCitizenManager().getCitizens()) {
            after += HappinessEffect.present(c) ? 1 : 0;
        }
        int skipsOff = 0;
        for (int i = 0; i < 400; i++) {
            skipsOff += CropEffect.skips(level, inside) ? 1 : 0;
        }
        check("the three switches off: a pass later no colonist has the bonus, no growth step is skipped, no extra food",
                after == 0 && skipsOff == 0 && !FoodEffect.active() && !SeasonsConfig.festivalHappiness(),
                after + " with the bonus, " + skipsOff + " skipped, food " + FoodEffect.active());
        CalendarManager.setForcedFestival(server, "");
    }

    // ================================================================ restarts

    /** Writes what the next phase needs into the world folder. */
    void saveFacts(final String phase) {
        final CompoundTag f = new CompoundTag();
        f.putString("phase", phase);
        f.putInt("colony", colonyId);
        f.putInt("ground", ground);
        f.putLong("townHall", townHall.getPosition().asLong());
        if (hut != null) {
            f.putLong("hut", hut.getPosition().asLong());
        }
        if (marker != null) {
            f.putLong("marker", marker.asLong());
        }
        final long[] all = new long[buildings.size()];
        for (int i = 0; i < all.length; i++) {
            all[i] = buildings.get(i).getPosition().asLong();
        }
        f.putLongArray("buildings", all);
        f.put("start", items(start));
        f.put("held", items(held));
        f.putInt("entries", ledger.count(colonyId));
        f.putInt("groups", ledger.groups(colonyId).size());
        final FestivalData.ColonyState s = FestivalData.of(level).peek(colonyId);
        f.putBoolean("lit", s != null && s.treeLit());
        f.putInt("lights", lights()[0]);
        try {
            NbtIo.writeCompressed(f, server.getWorldPath(LevelResource.ROOT).resolve(FACTS));
            if (phase.equals("setup")) {
                first.save(server.getWorldPath(LevelResource.ROOT).resolve(SNAPSHOT));
            }
        } catch (final java.io.IOException unwritable) {
            check("the probe's facts are saved in the world folder", false, phase);
        }
        facts = f;
    }

    static ListTag items(final Map<Item, Integer> map) {
        final ListTag list = new ListTag();
        for (final Map.Entry<Item, Integer> e : map.entrySet()) {
            final CompoundTag one = new CompoundTag();
            one.putString("item", BuiltInRegistries.ITEM.getKey(e.getKey()).toString());
            one.putInt("count", e.getValue());
            list.add(one);
        }
        return list;
    }

    static Map<Item, Integer> items(final ListTag list) {
        final Map<Item, Integer> map = new LinkedHashMap<>();
        for (final Tag t : list) {
            final CompoundTag one = (CompoundTag) t;
            map.put(BuiltInRegistries.ITEM.get(ResourceLocation.parse(one.getString("item"))), one.getInt("count"));
        }
        return map;
    }

    private void planSave(final String phase) {
        step(new Step(phase + ": save", 1) {
            @Override
            void start() {
                saveFacts(phase);
                note("{} ends with {} entries in {} groups, tree lit {}", phase, facts.getInt("entries"), facts.getInt("groups"), facts.getBoolean("lit"));
                server.tickRateManager().stopSprinting();
                check(phase + ": the world saved", server.saveEverything(false, true, true), "");
            }
        });
    }

    /** After a restart: the colony, its buildings and the probe's facts again. */
    private void planFind(final String phase) {
        step(new Step(phase + ": find the colony again", 2400) {
            @Override
            boolean tick() {
                try {
                    facts = NbtIo.readCompressed(server.getWorldPath(LevelResource.ROOT).resolve(FACTS), NbtAccounter.unlimitedHeap());
                } catch (final java.io.IOException unreadable) {
                    return false;
                }
                final IColony found = IColonyManager.getInstance().getColonyByWorld(facts.getInt("colony"), level);
                if (found == null) {
                    return false;
                }
                colony = found;
                colonyId = found.getID();
                return found.getServerBuildingManager().getBuilding(BlockPos.of(facts.getLong("townHall"))) != null;
            }

            @Override
            void end(final boolean done) {
                check(phase + ": the colony and the probe's facts are back", done, colony == null ? "no colony" : "colony " + colonyId);
                if (!done) {
                    finish();
                    return;
                }
                ground = facts.getInt("ground");
                quietWorld();
                forceChunks(7);
                owner = FakePlayerFactory.getMinecraft(level);
                townHall = colony.getServerBuildingManager().getBuilding(BlockPos.of(facts.getLong("townHall")));
                if (facts.contains("hut")) {
                    hut = colony.getServerBuildingManager().getBuilding(BlockPos.of(facts.getLong("hut"))) instanceof BuildingDecorator b ? b : null;
                    decorator = hut == null ? null : hut.getAllAssignedCitizen().stream().filter(Objects::nonNull).findFirst().orElse(null);
                }
                marker = facts.contains("marker") ? BlockPos.of(facts.getLong("marker")) : null;
                for (final long pos : facts.getLongArray("buildings")) {
                    final IBuilding b = colony.getServerBuildingManager().getBuilding(BlockPos.of(pos));
                    if (b != null) {
                        buildings.add(b);
                    }
                }
                start.putAll(items(facts.getList("start", Tag.TAG_COMPOUND)));
                held.putAll(items(facts.getList("held", Tag.TAG_COMPOUND)));
                try {
                    first = Snap.load(server.getWorldPath(LevelResource.ROOT).resolve(SNAPSHOT), level.holderLookup(Registries.BLOCK));
                } catch (final java.io.IOException unreadable) {
                    check(phase + ": the first snapshot reads back", false, "");
                }
                players();
                check(phase + ": the buildings, the hut and the Decorator are back", buildings.size() == facts.getLongArray("buildings").length
                        && hut != null && decorator != null, buildings.size() + " buildings, Decorator " + (decorator == null ? "-" : decorator.getName()));
                checkEq(phase + ": the ledger reads back the same entries", ledger.count(colonyId), facts.getInt("entries"));
                checkEq(phase + ": and the same groups", ledger.groups(colonyId).size(), facts.getInt("groups"));
                if (phase.equals("restart-mid")) {
                    final FestivalData.ColonyState s = FestivalData.of(level).peek(colonyId);
                    check("restart-mid: the tree is still lit, its lights as they were", s != null && s.treeLit() == facts.getBoolean("lit")
                            && lights()[0] == facts.getInt("lights"), "lit " + (s != null && s.treeLit()) + ", " + lights()[0] + " lit lights");
                    setDate(LocalDate.of(2026, 12, 12));
                    final BlockPos pile = pile();
                    check("restart-mid: a second gift on 12 Dec after the restart is refused", pile != null
                            && Gifts.take(level, pile, p1) == Gifts.Outcome.ALREADY, pile == null ? "no pile" : "");
                }
                daily(phase + " read back");
            }
        });
    }

    // ================================================================ edge values

    private void planEdges(final boolean low) {
        final int[] shows0 = new int[1];
        final LocalDate[] dates = {LocalDate.of(2026, 11, 28), LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 2), LocalDate.of(2026, 12, 24),
                LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 7), LocalDate.of(2027, 1, 8), LocalDate.of(2027, 1, 9),
                LocalDate.of(2027, 1, 10)};
        for (final LocalDate d : dates) {
            final String label = (low ? "edges-low " : "edges-high ") + d;
            final int until = d.getMonthValue() == 12 && d.getDayOfMonth() == 31 || d.getMonthValue() == 1 && d.getDayOfMonth() == 1 ? LATE : EVENING;
            step(day(d, label, until, new DayHook() {
                @Override
                void start() {
                    if (d.equals(dates[0])) {
                        shows0[0] = Fireworks.shows();
                    }
                }

                @Override
                void end(final boolean done) {
                    final CalendarManager.Snapshot s = CalendarManager.current();
                    final int[] hours = Gathering.hours();
                    if (low) {
                        check(label + ": every festival off, a 4-day year, hours out of order: nothing placed, gifts closed, no show, the default hours",
                                ledger.count(colonyId) == 0 && !Gifts.season(s) && Fireworks.shows() == shows0[0] && s.season().yearLength() == 4
                                        && hours[0] == SeasonsConfig.DEFAULT_GATHER_FROM_TICK && hours[1] == SeasonsConfig.DEFAULT_GATHER_UNTIL_TICK,
                                ledger.count(colonyId) + " entries, gifts " + Gifts.season(s) + ", " + (Fireworks.shows() - shows0[0]) + " shows, year "
                                        + s.season().yearLength() + ", hours " + hours[0] + "-" + hours[1]);
                    } else {
                        check(label + ": a 960-day year and gatherUntilTick 23999: the hours end before bedtime", s.season().yearLength() == 960
                                        && hours[1] <= Gathering.LATEST_UNTIL && hours[1] < Gathering.BEDTIME,
                                "year " + s.season().yearLength() + ", hours " + hours[0] + "-" + hours[1]);
                        if (d.getMonthValue() == 12 && d.getDayOfMonth() == 2) {
                            final String open = undecorated();
                            check(label + ": decorations still go up", ledger.count(colonyId) > 0, ledger.count(colonyId) + " entries"
                                    + (open.isEmpty() ? "" : ", open: " + open));
                        }
                    }
                }
            }));
        }
        step(new Step((low ? "edges-low" : "edges-high") + ": the end", 1) {
            @Override
            void start() {
                final int[] total = new int[1];
                first.diff(level, p -> true, 1, total);
                check("after the shortened cycle nothing is listed and the world equals the first snapshot", ledger.count(colonyId) == 0 && total[0] == 0,
                        ledger.count(colonyId) + " entries, " + total[0] + " cells differ");
            }
        });
    }
}
