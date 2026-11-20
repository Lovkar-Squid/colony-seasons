package me.lovkar.seasons.csdecorgate;

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
import com.minecolonies.api.colony.workorders.WorkOrderType;
import com.minecolonies.api.tileentities.AbstractTileEntityColonyBuilding;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import com.minecolonies.core.colony.workorders.WorkOrderBuilding;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import me.lovkar.seasons.ColonySeasons;
import me.lovkar.seasons.SeasonsConfig;
import me.lovkar.seasons.block.FestivalMarkerBlock;
import me.lovkar.seasons.block.LanternStringBlock;
import me.lovkar.seasons.block.Lights;
import me.lovkar.seasons.block.SeasonsBlocks;
import me.lovkar.seasons.block.WallDecorationBlock;
import me.lovkar.seasons.calendar.CalendarManager;
import me.lovkar.seasons.calendar.DateSource;
import me.lovkar.seasons.colony.BuildingDecorator;
import me.lovkar.seasons.colony.DecoratorModules;
import me.lovkar.seasons.colony.DecoratorSetup;
import me.lovkar.seasons.colony.JobDecorator;
import me.lovkar.seasons.decor.DecorEvents;
import me.lovkar.seasons.decor.DecorLedger;
import me.lovkar.seasons.decor.DecorPhase;
import me.lovkar.seasons.decor.DecorPlacer;
import me.lovkar.seasons.decor.DecorPlanner;
import me.lovkar.seasons.decor.DecorWork;
import me.lovkar.seasons.decor.DoorFinder;
import me.lovkar.seasons.decor.PieceLoader;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Headless check of S4: placing, the decoration ledger and taking down. Modes (the rig writes the word to probe-mode.txt):
 * <ul>
 *   <li>none: STYLES (real level-1 hut blueprints of six MineColonies style packs, pasted with Structurize's creative
 *       handler, decorated at hut level 5 and taken down again, each in one tick against a snapshot of its box), EDGES
 *       (hand-built houses: a door under an overhang, a double door, no door, the player's own wreath, a torch in the
 *       garland row, a house across the claim edge), SQUARE (the probe's own test piece in its own pack, see
 *       make_piece.py: the [V] position formula in four facings, level skips, the 10 % rule, a missing piece, the
 *       large star switched on, a festival marker broken), LIFECYCLE (a real colony: a swapped block, a light switched,
 *       a work order, the Builder's CLEAR_NON_SOLIDS, a hut level up, BuildingRemovedModEvent, decor list and remove,
 *       the Decorator's AI decorating, tending and taking down by himself, the hut destroyed in the window and the
 *       sweep after it);</li>
 *   <li>{@code restart-save} then {@code restart-load} (tools/restart.sh): a building half done for lack of stock, the
 *       ledger file read from disk, the world saved; then the same entries and world after the restart, decorating
 *       goes on without placing a cell twice, and taking down gives back the snapshot saved in the world folder.</li>
 * </ul>
 * The date is fixed (10 Dec, a decoration window) from the probe's constructor on, so the calendar never sees the
 * real date. Prints "[csdecorgate] CHECK ...: OK|FAIL", "[csdecorgate] STYLE ..." per pasted hut and per style, and
 * "[csdecorgate] RESULT csdecorgate: PASS|FAIL", then halts. The words "error" and "exception" stay out of the details.
 */
@Mod("colonyseasonsdecorgate")
public class CsDecorGate {
    private static final Logger LOGGER = LoggerFactory.getLogger("csdecorgate");
    private static final int SETUP_TICK = 60;
    private static final long WATCHDOG_SECONDS = 520;
    static final String TEST_PACK = "Colony Seasons Test";
    static final LocalDate DECOR_DAY = LocalDate.of(2026, 12, 10);
    static final LocalDate AFTER = LocalDate.of(2027, 1, 10);
    static final LocalDate FESTIVAL_DAY = LocalDate.of(2026, 12, 25);
    private static final int MORNING = 1000;
    /** Pasted huts float this high above the ground (no ground to fit, no shadow on the ground houses later). */
    private static final int FLOAT = 30;
    private static final String RESTART_FILE = "csdecorgate-restart.nbt";

    /** The probe's test piece, cell by cell, drawn facing north (the same list as make_piece.py). */
    static final BlockPos PIECE_ANCHOR = new BlockPos(2, 0, 4);
    static final List<Object[]> TEST_PIECE = List.of(
            new Object[]{2, 0, 1, "colony_seasons:festival_trunk"}, new Object[]{2, 1, 1, "colony_seasons:festival_trunk"},
            new Object[]{2, 2, 1, "colony_seasons:festival_trunk"}, new Object[]{2, 3, 1, "colony_seasons:festival_trunk"},
            new Object[]{2, 4, 1, "colony_seasons:festival_trunk"}, new Object[]{3, 2, 1, "colony_seasons:festival_trunk"},
            new Object[]{1, 2, 1, "minecraft:spruce_leaves"}, new Object[]{2, 2, 0, "minecraft:spruce_leaves"},
            new Object[]{2, 2, 2, "colony_seasons:ornament_leaves"}, new Object[]{4, 2, 1, "minecraft:spruce_leaves"},
            new Object[]{1, 2, 2, "minecraft:spruce_leaves"}, new Object[]{1, 3, 1, "colony_seasons:ornament_leaves"},
            new Object[]{3, 3, 1, "minecraft:spruce_leaves"}, new Object[]{2, 3, 0, "minecraft:spruce_leaves"},
            new Object[]{2, 3, 2, "minecraft:spruce_leaves"}, new Object[]{1, 4, 1, "minecraft:spruce_leaves"},
            new Object[]{3, 4, 1, "colony_seasons:tinsel_leaves"}, new Object[]{2, 4, 0, "minecraft:spruce_leaves"},
            new Object[]{2, 4, 2, "colony_seasons:ornament_leaves"}, new Object[]{2, 5, 1, "colony_seasons:large_star"},
            new Object[]{2, 6, 1, "colony_seasons:large_star"}, new Object[]{3, 0, 3, "colony_seasons:gift_pile"},
            new Object[]{1, 0, 3, "minecraft:red_wool"}, new Object[]{0, 0, 3, "colony_seasons:firework_crate"});
    /** Cells skipped below hut level 2 (gift_pile tag) and 3 (firework_crate tag), relative to the anchor, north frame. */
    static final Set<BlockPos> GIFT_CELLS = Set.of(new BlockPos(1, 0, -1), new BlockPos(-1, 0, -1));
    static final Set<BlockPos> CRATE_CELLS = Set.of(new BlockPos(-2, 0, -1));

    /**
     * Six styles, seven level-1 huts each (pack names as their pack.json has them): what a young colony builds first
     * (a house, the builder, the warehouse, the tavern, the library) and two workshops. Several workshops of several
     * styles have no door at all; they get nothing, by the design.
     */
    private static final List<String> SAMPLE = List.of("fundamentals/residence1", "fundamentals/builder1", "craftsmanship/storage/warehouse1",
            "fundamentals/tavern1", "education/library1", "craftsmanship/metallurgy/blacksmith1", "craftsmanship/carpentry/fletcher1");
    private static final List<Style> STYLES = List.of(
            new Style("Medieval Oak", SAMPLE), new Style("Nordic Spruce", SAMPLE), new Style("Colonial", SAMPLE),
            new Style("Shire", List.of("fundamentals/house1", "fundamentals/builder1", "craftsmanship/storage/warehouse1",
                    "fundamentals/tavern1", "education/library1", "craftsmanship/metallurgy/blacksmith1", "craftsmanship/carpentry/fletcher1")),
            new Style("Fortress", SAMPLE), new Style("Caledonia", SAMPLE));
    private static final int[] PLOT_X = {-62, -24, 14, 52};
    private static final int[] PLOT_Z = {-62, -22};

    private record Style(String pack, List<String> huts) {
    }

    private int ticks;
    private boolean failed;
    private boolean finished;
    private final String mode;
    private long setupNanos;

    private MinecraftServer server;
    private ServerLevel level;
    private ServerPlayer owner;
    private IColony colony;
    private int colonyId;
    private int ground;
    private DecorLedger ledger;

    private final List<Step> steps = new ArrayList<>();
    private int stepIndex;

    public CsDecorGate() {
        mode = readMode();
        // the calendar must never see the real date: a sweep in a world with no window would clear the probe's work
        CalendarManager.setDateSource(DateSource.fixed(DECOR_DAY));
        NeoForge.EVENT_BUS.addListener(this::onTick);
    }

    // ================================================================ logging

    private void check(final String what, final boolean ok, final String detail) {
        LOGGER.info("[csdecorgate] CHECK {}: {} {}", what, ok ? "OK" : "FAIL", detail);
        failed |= !ok;
    }

    private void checkEq(final String what, final Object actual, final Object expected) {
        check(what, Objects.equals(actual, expected), "got " + actual + (Objects.equals(actual, expected) ? "" : ", wanted " + expected));
    }

    private static void note(final String text, final Object... args) {
        LOGGER.info("[csdecorgate] " + text, args);
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
        if (step.t % 200 == 0 && worker != null) {
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
        LOGGER.info("[csdecorgate] RESULT csdecorgate: {}", failed ? "FAIL" : "PASS");
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
                planSetup();
                planStyles();
                planEdges();
                planSquare();
                planLifecycle();
            }
            case "styles" -> {
                planSetup();
                planStyles();
                planEdges();
            }
            case "square" -> {
                planSetup();
                planSquare();
            }
            case "lifecycle" -> {
                planSetup();
                planLifecycle();
            }
            case "restart-save" -> planRestartSave();
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
        rules.getRule(GameRules.RULE_RANDOMTICKING).set(0, server);
        rules.getRule(GameRules.RULE_MOBGRIEFING).set(false, server);
        level.setWeatherParameters(24000 * 20, 0, false, false);
    }

    private void morning() {
        final long day = level.getDayTime() / 24000L;
        level.setDayTime(day * 24000L + MORNING);
    }

    private void nextMorning() {
        final long day = level.getDayTime() / 24000L;
        level.setDayTime((day + 1) * 24000L + MORNING);
    }

    /** The colony: centre (2, ground + 1, 2), chunks around forced, a fake owner subscribed. */
    private void buildColony() {
        ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, 2, 2) - 1;
        final BlockPos center = new BlockPos(2, ground + 1, 2);
        for (int cx = -6; cx <= 6; cx++) {
            for (int cz = -6; cz <= 6; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }
        owner = FakePlayerFactory.getMinecraft(level);
        owner.setPos(center.getX() + 0.5, center.getY(), center.getZ() + 0.5);
        colony = IColonyManager.getInstance().createColony(level, center, owner, "Seasons Placing Test", "Medieval Oak");
        colonyId = colony == null ? -1 : colony.getID();
        check("a colony was made", colony != null, "id " + colonyId + " at " + center + ", ground y " + ground);
    }

    /** Is the cell inside the probe colony's claim. */
    private boolean claimed(final BlockPos pos) {
        return DecorPlacer.colonyAt(level, pos) == colonyId;
    }

    private void set(final BlockPos pos, final BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }

    /**
     * A stone-brick house on the ground: floor at y0 = ground, walls up to y0 + 5, roof at y0 + 6, x0..x0+w-1,
     * z0..z0+d-1; doors (lower halves at y0 + 1) in the south wall at the given x. Returns its box; the hut cell is
     * the middle of the floor, one up.
     */
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

    /** Registers a MineColonies building on a hand-built house: its hut block at the box's hut cell, its corners the box. */
    private IBuilding register(final String hutId, final DoorFinder.Box box, final int buildingLevel, final String pack, final String path) {
        final Block hut = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(hutId)).orElse(null);
        if (hut == null) {
            check("hut block " + hutId + " exists", false, "");
            return null;
        }
        level.setBlock(box.hut(), hut.defaultBlockState(), Block.UPDATE_ALL);
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

    // ---------------------------------------------------------------- snapshots and the invariant

    private Map<BlockPos, BlockState> snapshot(final DoorFinder.Box box) {
        final Map<BlockPos, BlockState> snap = new LinkedHashMap<>();
        for (final BlockPos pos : BlockPos.betweenClosed(box.min(), box.max())) {
            snap.put(pos.immutable(), level.getBlockState(pos));
        }
        return snap;
    }

    private List<BlockPos> changed(final Map<BlockPos, BlockState> snap) {
        final List<BlockPos> out = new ArrayList<>();
        for (final Map.Entry<BlockPos, BlockState> e : snap.entrySet()) {
            if (level.getBlockState(e.getKey()) != e.getValue()) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** Every ledger entry of the colony equals the world (the cells are loaded here). */
    private void invariant(final String where) {
        int bad = 0;
        String first = "";
        final Map<BlockPos, DecorLedger.Entry> all = ledger.entries(colonyId);
        for (final Map.Entry<BlockPos, DecorLedger.Entry> e : all.entrySet()) {
            final BlockState now = level.getBlockState(e.getKey());
            if (now != e.getValue().state()) {
                if (bad++ == 0) {
                    first = e.getKey().toShortString() + " listed " + e.getValue().state() + ", world " + now;
                }
            }
        }
        check("every entry equals the world (" + where + ")", bad == 0, all.size() + " entries" + (bad > 0 ? ", " + bad + " differ, first " + first : ""));
    }

    /** The checks every decorated building gets: what changed was air and is listed exactly, nothing else changed, the states are as the Decorator puts them. */
    private boolean decoratedRight(final String what, final Map<BlockPos, BlockState> snap, final DecorLedger.Owner owner, final boolean quiet) {
        final DecorLedger.GroupView group = ledger.group(colonyId, owner);
        final Map<BlockPos, DecorLedger.Entry> entries = group == null ? Map.of() : group.entries();
        final List<BlockPos> diff = changed(snap);
        final List<String> wrong = new ArrayList<>();
        for (final BlockPos pos : diff) {
            final DecorLedger.Entry entry = entries.get(pos);
            if (!snap.get(pos).isAir()) {
                wrong.add(pos.toShortString() + " was " + snap.get(pos).getBlock().getDescriptionId());
            } else if (entry == null || entry.state() != level.getBlockState(pos)) {
                wrong.add(pos.toShortString() + " not listed exactly");
            }
        }
        for (final Map.Entry<BlockPos, DecorLedger.Entry> e : entries.entrySet()) {
            final BlockState state = e.getValue().state();
            if (!snap.containsKey(e.getKey())) {
                wrong.add(e.getKey().toShortString() + " outside the box");
            } else if (!diff.contains(e.getKey())) {
                wrong.add(e.getKey().toShortString() + " listed but unchanged");
            }
            if (!state.getCollisionShape(level, e.getKey()).isEmpty()) {
                wrong.add(e.getKey().toShortString() + " has collision");
            }
            if (state.hasProperty(Lights.LIT) && state.getValue(Lights.LIT)) {
                wrong.add(e.getKey().toShortString() + " lit");
            }
            if (state.getBlock() instanceof LanternStringBlock
                    && state.getValue(LanternStringBlock.VARIANT) != LanternStringBlock.variantAt(e.getKey(), state.getValue(WallDecorationBlock.FACING))) {
                wrong.add(e.getKey().toShortString() + " lantern colour order");
            }
            if (!(state.getBlock() instanceof WallDecorationBlock)) {
                wrong.add(e.getKey().toShortString() + " not a wall decoration");
            }
        }
        if (!quiet || !wrong.isEmpty()) {
            check(what + ": every changed cell was air and is listed, nothing else changed", wrong.isEmpty(),
                    diff.size() + " changed, " + entries.size() + " listed" + (wrong.isEmpty() ? "" : ", " + wrong.subList(0, Math.min(4, wrong.size()))));
        }
        return wrong.isEmpty();
    }

    private boolean takenDownRight(final String what, final Map<BlockPos, BlockState> snap, final DecorLedger.Owner owner, final boolean quiet) {
        final List<BlockPos> diff = changed(snap);
        final boolean gone = ledger.group(colonyId, owner) == null;
        if (!quiet || !diff.isEmpty() || !gone) {
            check(what + ": after taking down the world equals the snapshot and the group is gone", diff.isEmpty() && gone,
                    diff.size() + " cells differ" + (diff.isEmpty() ? "" : " (first " + diff.get(0).toShortString() + " " + level.getBlockState(diff.get(0)) + ")")
                            + ", group " + (gone ? "gone" : "still listed"));
        }
        return diff.isEmpty() && gone;
    }

    private int countKind(final DecorLedger.Owner owner, final Block block) {
        final DecorLedger.GroupView group = ledger.group(colonyId, owner);
        int n = 0;
        if (group != null) {
            for (final DecorLedger.Entry e : group.entries().values()) {
                if (e.state().getBlock() == block) {
                    n++;
                }
            }
        }
        return n;
    }

    private int countLights(final DecorLedger.Owner owner) {
        return countKind(owner, SeasonsBlocks.LANTERN_STRING.get()) + countKind(owner, SeasonsBlocks.FAIRY_LIGHTS.get());
    }

    /** A stock that hands out up to {@code limit} items and counts what comes back. */
    private static final class CountingStock implements DecorPlacer.Stock {
        final int limit;
        int taken;
        int given;

        CountingStock(final int limit) {
            this.limit = limit;
        }

        @Override
        public boolean take(final Item item) {
            if (taken >= limit) {
                return false;
            }
            taken++;
            return true;
        }

        @Override
        public void give(final ItemStack stack) {
            given += stack.getCount();
        }

        @Override
        public boolean has(final Item item) {
            return taken < limit;
        }
    }

    private long day() {
        return DecorWork.day(level);
    }

    // ================================================================ setup

    private void planSetup() {
        step(new Step("setup", 1) {
            @Override
            void start() {
                setDate(DECOR_DAY);
                quietWorld();
                buildColony();
                check("the decoration window is open", DecorPhase.windowOpen(CalendarManager.current()), String.valueOf(CalendarManager.current().festivals()));
                check("MineColonies' event bus subscriptions are in", DecorEvents.subscribed(), "");
                check("the colony manager counts as loaded (the sweep may run)", DecorEvents.managerLoaded(), "");
                checkEq("the amounts table at level 1", DecorPlanner.amounts(1, 6), new DecorPlanner.Amounts(true, 0, 0, false));
                checkEq("the amounts table at level 3", DecorPlanner.amounts(3, 6), new DecorPlanner.Amounts(true, 3, 2, false));
                checkEq("the amounts table at level 5 with maxPerSide 4", DecorPlanner.amounts(5, 4), new DecorPlanner.Amounts(true, 4, 4, true));
                checkEq("piece path for levels 1..5", List.of(PieceLoader.pathFor(1), PieceLoader.pathFor(2), PieceLoader.pathFor(3), PieceLoader.pathFor(4), PieceLoader.pathFor(5)),
                        List.of("pieces/village_spruce_small.blueprint", "pieces/village_spruce_small.blueprint", "pieces/village_spruce_full.blueprint",
                                "pieces/village_spruce_rich.blueprint", "pieces/grand_tree.blueprint"));
                checkEq("Creativity 0 / 10 / 40 pauses", List.of(me.lovkar.seasons.ai.EntityAIWorkDecorator.visitPause(0),
                        me.lovkar.seasons.ai.EntityAIWorkDecorator.visitPause(10), me.lovkar.seasons.ai.EntityAIWorkDecorator.visitPause(40)), List.of(200, 140, 40));
                check("a kit larger than the cap is made while below it, not past it", me.lovkar.seasons.decor.Kits.wanted(0, 32, 16)
                        && me.lovkar.seasons.decor.Kits.wanted(15, 32, 16) && !me.lovkar.seasons.decor.Kits.wanted(16, 32, 16)
                        && me.lovkar.seasons.decor.Kits.wanted(12, 4, 16) && !me.lovkar.seasons.decor.Kits.wanted(13, 4, 16), "");
            }
        });
    }

    // ================================================================ STYLES

    private final List<Map<String, int[]>> styleStats = new ArrayList<>();

    private void planStyles() {
        for (final Style style : STYLES) {
            final List<Pasted> pasted = new ArrayList<>();
            step(new Step("styles: paste " + style.pack(), 700, true) {
                @Override
                void start() {
                    for (int i = 0; i < style.huts().size() && i < PLOT_X.length * PLOT_Z.length; i++) {
                        final BlockPos plot = new BlockPos(PLOT_X[i % PLOT_X.length], ground + FLOAT, PLOT_Z[i / PLOT_X.length]);
                        final Pasted p = paste(style.pack(), style.huts().get(i) + ".blueprint", plot);
                        if (p != null) {
                            pasted.add(p);
                        }
                    }
                    note("pasted {} huts", pasted.size());
                }

                @Override
                boolean tick() {
                    return t >= 60 && !level.getChunkSource().getLightEngine().hasLightWork();
                }

                @Override
                void end(final boolean done) {
                    if (!done) {
                        note("light was still working after {} ticks", t);
                    }
                    for (final Pasted p : pasted) {
                        styleOne(p);
                    }
                    for (final Pasted p : pasted) {
                        clear(p.box().grown(2));
                    }
                }
            });
        }
        step(new Step("styles: summary", 1) {
            @Override
            void start() {
                int good = 0;
                final Map<String, int[]> perStyle = new LinkedHashMap<>();
                for (final Map<String, int[]> one : styleStats) {
                    for (final Map.Entry<String, int[]> e : one.entrySet()) {
                        final int[] sum = perStyle.computeIfAbsent(e.getKey(), k -> new int[7]);
                        for (int i = 0; i < 7; i++) {
                            sum[i] += e.getValue()[i];
                        }
                    }
                }
                for (final Map.Entry<String, int[]> e : perStyle.entrySet()) {
                    final int[] s = e.getValue();
                    note("STYLE {} summary: {} huts, {} with a wreath, {} doors, {} wreaths, {} garlands, {} lights (hut level 5); {} with a wreath at hut level 1",
                            e.getKey(), s[0], s[1], s[2], s[3], s[4], s[5], s[6]);
                    if (s[0] > 0 && s[1] * 2 > s[0]) {
                        good++;
                    }
                }
                check("every pasted hut: changed cells were air and are listed exactly, nothing else changed, no collision, unlit, "
                        + "lantern colour orders from their position, and taking down gives back the snapshot", styleOk == styleAll && styleAll >= STYLES.size() * 4,
                        styleOk + " of " + styleAll);
                check("at least 4 of 6 styles have a wreath on more than half their huts", good >= 4 && perStyle.size() >= 6,
                        good + " of " + perStyle.size() + " styles");
            }
        });
    }

    private record Pasted(String pack, String path, DoorFinder.Box box) {
    }

    /** Pastes a hut blueprint with its box's low corner at {@code plot} (Structurize's creative handler, as Colonies at War's WallOrders.paste does). */
    private Pasted paste(final String pack, final String path, final BlockPos plot) {
        try {
            final Blueprint blueprint = StructurePacks.getBlueprintFuture(pack, path, level.registryAccess()).get(60, TimeUnit.SECONDS);
            if (blueprint == null) {
                check("blueprint " + pack + " " + path + " loads", false, "not found");
                return null;
            }
            blueprint.setRotationMirror(RotationMirror.NONE, level);
            final BlockPos offset = blueprint.getPrimaryBlockOffset();
            final BlockPos anchor = plot.offset(offset);
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
            return new Pasted(pack, path, new DoorFinder.Box(plot, max, anchor));
        } catch (final Exception unreadable) {
            check("blueprint " + pack + " " + path + " pastes", false, unreadable.getClass().getSimpleName().replace("Exception", ""));
            return null;
        }
    }

    /** Clears a pasted hut: air everywhere in its box (no drops, no updates) and its entities gone. */
    private void clear(final DoorFinder.Box box) {
        for (final BlockPos pos : BlockPos.betweenClosed(box.min(), box.max())) {
            if (!level.getBlockState(pos).isAir()) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
            }
        }
        for (final Entity e : level.getEntities((Entity) null, new AABB(box.min()).minmax(new AABB(box.max())).inflate(1), e -> !(e instanceof Player))) {
            e.discard();
        }
    }

    /** One pasted hut: snapshot, decorate at hut level 5, check, take down, check (all in this tick). */
    private void styleOne(final Pasted p) {
        final DoorFinder.Box box = p.box();
        final Map<BlockPos, BlockState> snap = snapshot(box.grown(2));
        final DecorWork.Target target = DecorWork.Target.box(box);
        final DecorPlanner.Plan plan = DecorPlanner.plan(level, box, 5, 6, ledger, colonyId, target.owner(), true);
        final DecorPlanner.Plan young = DecorPlanner.plan(level, box, 1, 6, ledger, colonyId, target.owner(), true);
        final DecorWork.Outcome outcome = DecorWork.decorate(level, colonyId, target, 5, DecorPlacer.Stock.FREE, day(), ledger);
        final String name = p.pack() + " " + p.path().replace(".blueprint", "");
        final boolean placedRight = decoratedRight("style " + name, snap, target.owner(), true);
        final int wreaths = countKind(target.owner(), SeasonsBlocks.WREATH.get());
        final int garlands = countKind(target.owner(), SeasonsBlocks.GARLAND.get());
        final int lights = countLights(target.owner());
        note("STYLE {}: doors {}, wreath {}, garland {}, lights {}, row {}, placed {} (hut level 5); main door wreath at hut level 1: {}",
                name, plan.doors(), wreaths, garlands, lights, plan.row(), outcome.placed(), young.wreaths());
        doorNotes(name, box);
        DecorPlacer.remove(level, ledger, colonyId, target.owner(), DecorPlacer.Stock.FREE);
        final boolean downRight = takenDownRight("style " + name, snap, target.owner(), true);
        if (placedRight && downRight) {
            styleOk++;
        }
        styleAll++;
        final Map<String, int[]> stat = new HashMap<>();
        stat.put(p.pack(), new int[]{1, wreaths > 0 ? 1 : 0, plan.doors(), wreaths, garlands, lights, young.wreaths() > 0 ? 1 : 0});
        styleStats.add(stat);
    }

    private int styleOk;
    private int styleAll;

    /** For the lead: every lower door half in the box and what is above and in front of it. */
    private void doorNotes(final String name, final DoorFinder.Box box) {
        final List<DoorFinder.Door> counted = DoorFinder.find(level, box);
        for (final BlockPos pos : BlockPos.betweenClosed(box.min(), box.max())) {
            final BlockState state = level.getBlockState(pos);
            if (!state.is(net.minecraft.tags.BlockTags.DOORS) || !state.hasProperty(DoorBlock.HALF) || state.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) {
                continue;
            }
            final BlockPos lower = pos.immutable();
            DoorFinder.Door door = null;
            for (final DoorFinder.Door d : counted == null ? List.<DoorFinder.Door>of() : counted) {
                if (d.lower().equals(lower)) {
                    door = d;
                }
            }
            final Direction facing = state.getValue(DoorBlock.FACING);
            final int front = level.getBrightness(net.minecraft.world.level.LightLayer.SKY, lower.relative(facing));
            final int back = level.getBrightness(net.minecraft.world.level.LightLayer.SKY, lower.relative(facing.getOpposite()));
            final Direction out = door != null ? door.outside() : front >= back ? facing : facing.getOpposite();
            final StringBuilder above = new StringBuilder();
            for (int up = 1; up <= 3; up++) {
                final BlockPos wall = lower.above(up);
                final BlockState w = level.getBlockState(wall);
                final BlockState f = level.getBlockState(wall.relative(out));
                above.append(" | +").append(up).append(' ').append(shortName(w)).append(w.isFaceSturdy(level, wall, out) ? " sturdy" : "")
                        .append(", front ").append(shortName(f));
            }
            for (final Direction side : List.of(out.getCounterClockWise(), out.getClockWise())) {
                final BlockPos wall = lower.above(1).relative(side);
                final BlockState w = level.getBlockState(wall);
                above.append(" | beside+1 ").append(shortName(w)).append(w.isFaceSturdy(level, wall, out) ? " sturdy" : "")
                        .append(", front ").append(shortName(level.getBlockState(wall.relative(out))));
            }
            note("DOOR {} at {} {}: {} out {}, sky {} / {}{}", name, lower.subtract(box.min()).toShortString(), shortName(state),
                    door == null ? "not counted" : door.pair() ? "pair" : "single", out, Math.max(front, back), Math.min(front, back), above);
        }
    }

    private static String shortName(final BlockState state) {
        final ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return ("minecraft".equals(id.getNamespace()) ? id.getPath() : id.toString());
    }

    // ================================================================ EDGES

    private void planEdges() {
        step(new Step("edges: hand-built houses", 60, true) {
            final Map<String, DoorFinder.Box> houses = new LinkedHashMap<>();
            int edgeX;

            @Override
            void start() {
                houses.put("overhang", house(-60, 36, 11, 7, -55));
                houses.put("double door", house(-45, 36, 11, 7, -40, -39));
                houses.put("no door", house(-30, 36, 11, 7));
                houses.put("player wreath", house(-15, 36, 11, 7, -10));
                houses.put("torch", house(0, 36, 11, 7, 5));
                // the overhang: a porch roof in the lights row, two deep
                for (int x = -58; x <= -52; x++) {
                    for (int z = 43; z <= 44; z++) {
                        set(new BlockPos(x, ground + 4, z), Blocks.STONE_BRICK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
                    }
                }
                // the player's own wreath above his door (not in the ledger)
                level.setBlock(new BlockPos(-10, ground + 3, 43), SeasonsBlocks.WREATH.get().defaultBlockState()
                        .setValue(WallDecorationBlock.FACING, Direction.SOUTH), Block.UPDATE_ALL);
                // a torch in the garland row, east of the wreath
                level.setBlock(new BlockPos(6, ground + 3, 43), Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.SOUTH), Block.UPDATE_ALL);
                // the claim edge: the last claimed x on the house's row
                edgeX = 0;
                while (edgeX < 200 && claimed(new BlockPos(edgeX + 1, ground + 3, 43))) {
                    edgeX++;
                }
                note("the claim ends at x {} on z 43", edgeX);
                houses.put("claim edge", house(edgeX - 5, 36, 11, 7, edgeX));
            }

            @Override
            boolean tick() {
                return t >= 40 && !level.getChunkSource().getLightEngine().hasLightWork();
            }

            @Override
            void end(final boolean done) {
                for (final Map.Entry<String, DoorFinder.Box> e : houses.entrySet()) {
                    edge(e.getKey(), e.getValue(), edgeX);
                }
            }
        });
    }

    private void edge(final String name, final DoorFinder.Box box, final int edgeX) {
        final Map<BlockPos, BlockState> snap = snapshot(box.grown(2));
        final DecorWork.Target target = DecorWork.Target.box(box);
        final DecorPlanner.Plan plan = DecorPlanner.plan(level, box, 5, 6, ledger, colonyId, target.owner(), true);
        DecorWork.decorate(level, colonyId, target, 5, DecorPlacer.Stock.FREE, day(), ledger);
        decoratedRight("edge '" + name + "'", snap, target.owner(), false);
        final int wreaths = countKind(target.owner(), SeasonsBlocks.WREATH.get());
        final int garlands = countKind(target.owner(), SeasonsBlocks.GARLAND.get());
        final int lights = countLights(target.owner());
        final String counts = "doors " + plan.doors() + ", wreath " + wreaths + ", garland " + garlands + ", lights " + lights;
        switch (name) {
            case "overhang" -> check("a door under an overhang: wreath and garland, no lights in the overhang's row",
                    plan.doors() == 1 && wreaths == 1 && garlands == 10 && lights == 0, counts);
            case "double door" -> check("a double door gets exactly one wreath", plan.doors() == 1 && wreaths == 1 && garlands == 10 && lights == 11, counts);
            case "no door" -> {
                final DecorLedger.GroupView g = ledger.group(colonyId, target.owner());
                check("a house with no door gets nothing", plan.doors() == 0 && (g == null || g.entries().isEmpty()), counts);
            }
            case "player wreath" -> check("the player's wreath stays, the garland goes on both sides of it",
                    wreaths == 0 && garlands == 10 && lights == 11, counts);
            case "torch" -> {
                boolean pastTorch = false;
                final DecorLedger.GroupView g = ledger.group(colonyId, target.owner());
                if (g != null) {
                    for (final Map.Entry<BlockPos, DecorLedger.Entry> e : g.entries().entrySet()) {
                        pastTorch |= e.getKey().getY() == ground + 3 && e.getKey().getX() >= 6;
                    }
                }
                check("a torch in the garland row ends that side's walk", wreaths == 1 && garlands == 5 && lights == 11 && !pastTorch, counts);
            }
            case "claim edge" -> {
                boolean outside = false;
                final DecorLedger.GroupView g = ledger.group(colonyId, target.owner());
                if (g != null) {
                    for (final BlockPos pos : g.entries().keySet()) {
                        outside |= pos.getX() > edgeX || !claimed(pos);
                    }
                }
                check("a house across the claim edge: nothing outside the claim", wreaths == 1 && garlands == 5 && lights == 6 && !outside, counts);
            }
            default -> {
            }
        }
        DecorPlacer.remove(level, ledger, colonyId, target.owner(), DecorPlacer.Stock.FREE);
        takenDownRight("edge '" + name + "'", snap, target.owner(), false);
        if ("player wreath".equals(name)) {
            check("the player's own wreath is still there after taking down",
                    level.getBlockState(new BlockPos(-10, ground + 3, 43)).is(SeasonsBlocks.WREATH.get()), "");
        }
        if ("torch".equals(name)) {
            check("the torch is untouched", level.getBlockState(new BlockPos(6, ground + 3, 43)).is(Blocks.WALL_TORCH), "");
        }
    }

    // ================================================================ SQUARE

    private static final Direction[] FACINGS = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    private BlockPos marker(final int x, final int z, final Direction facing) {
        final BlockPos pos = new BlockPos(x, ground + 1, z);
        level.setBlock(pos, SeasonsBlocks.FESTIVAL_MARKER.get().defaultBlockState().setValue(FestivalMarkerBlock.FACING, facing), Block.UPDATE_ALL);
        return pos;
    }

    private static Rotation rotationOf(final Direction facing) {
        return switch (facing) {
            case EAST -> Rotation.CLOCKWISE_90;
            case SOUTH -> Rotation.CLOCKWISE_180;
            case WEST -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    /** The test piece's expected cells relative to the marker, turned for a facing: rel -> block id. */
    private static Map<BlockPos, String> expectedPiece(final Direction facing) {
        final Map<BlockPos, String> out = new HashMap<>();
        for (final Object[] c : TEST_PIECE) {
            final BlockPos rel = new BlockPos((int) c[0], (int) c[1], (int) c[2]).subtract(PIECE_ANCHOR);
            out.put(rel.rotate(rotationOf(facing)), (String) c[3]);
        }
        return out;
    }

    private DoorFinder.Box squareBox(final BlockPos marker) {
        return new DoorFinder.Box(marker.offset(-5, -1, -5), marker.offset(5, 8, 5), marker);
    }

    private void planSquare() {
        final Map<Direction, PieceLoader.Piece> pieces = new HashMap<>();
        step(new Step("square: load the test piece in four facings", 400, true) {
            @Override
            boolean tick() {
                boolean all = true;
                for (final Direction facing : FACINGS) {
                    if (!pieces.containsKey(facing)) {
                        final PieceLoader.Piece piece = PieceLoader.request(level, TEST_PACK, PieceLoader.pathFor(5), facing);
                        if (piece != null) {
                            pieces.put(facing, piece);
                        } else {
                            all = false;
                        }
                    }
                    for (int hutLevel = 1; hutLevel <= 4; hutLevel++) {
                        all &= PieceLoader.request(level, TEST_PACK, PieceLoader.pathFor(hutLevel), facing) != null;
                    }
                }
                PieceLoader.request(level, "Colony Seasons Missing", PieceLoader.pathFor(1), Direction.NORTH);
                return all && PieceLoader.missing("Colony Seasons Missing", PieceLoader.pathFor(1), Direction.NORTH);
            }

            @Override
            void end(final boolean done) {
                check("the test piece loads (getBlueprintFuture, never blocking) in four facings", pieces.size() == 4, pieces.size() + " facings");
                check("a missing piece is found missing, with no tree and no throwable", PieceLoader.missing("Colony Seasons Missing", PieceLoader.pathFor(1), Direction.NORTH), "");
                final PieceLoader.Piece north = pieces.get(Direction.NORTH);
                if (north != null) {
                    checkEq("the test piece's primary offset is the marker's cell", north.offset(), PIECE_ANCHOR);
                    check("the test piece's position tags are read from the anchor", north.tags(), "");
                    checkEq("the test piece's cells (the anchor's air left out)", north.cells().size(), TEST_PIECE.size());
                }
                for (final Map.Entry<Direction, PieceLoader.Piece> e : pieces.entrySet()) {
                    final Map<BlockPos, String> want = expectedPiece(e.getKey());
                    final Map<BlockPos, String> got = new HashMap<>();
                    for (final PieceLoader.PieceCell cell : e.getValue().cells()) {
                        got.put(cell.rel(), BuiltInRegistries.BLOCK.getKey(cell.state().getBlock()).toString());
                    }
                    check("[V] marker - primary offset + cell position, after setRotationMirror, equals an independent turn ("
                            + e.getKey() + ")", got.equals(want), got.equals(want) ? want.size() + " cells" : "got " + got + ", wanted " + want);
                    int gifts = 0;
                    int crates = 0;
                    int stars = 0;
                    for (final PieceLoader.PieceCell cell : e.getValue().cells()) {
                        gifts += cell.giftPile() ? 1 : 0;
                        crates += cell.fireworkCrate() ? 1 : 0;
                        stars += cell.star() ? 1 : 0;
                    }
                    check("tags turn with the piece (" + e.getKey() + ")", gifts == 2 && crates == 1 && stars == 1,
                            gifts + " gift pile cells, " + crates + " crate, " + stars + " star");
                }
            }
        });
        step(new Step("square: four facings in the world, level skips, the large star switched on", 1) {
            @Override
            void start() {
                final int[] xs = {-50, -30, -10, 10};
                for (int i = 0; i < 4; i++) {
                    final Direction facing = FACINGS[i];
                    final BlockPos m = marker(xs[i], 60, facing);
                    final DoorFinder.Box box = squareBox(m);
                    final Map<BlockPos, BlockState> snap = snapshot(box);
                    final DecorWork.Target target = DecorWork.Target.square(m, facing, TEST_PACK);
                    final int hutLevel = 1 + i;           // levels 1..4: the skips
                    final DecorWork.Outcome outcome = DecorWork.decorate(level, colonyId, target, hutLevel, DecorPlacer.Stock.FREE, day(), ledger);
                    final DecorLedger.GroupView group = ledger.group(colonyId, target.owner());
                    final Map<BlockPos, String> want = expectedPiece(facing);
                    int right = 0;
                    int skipped = 0;
                    for (final Map.Entry<BlockPos, String> e : want.entrySet()) {
                        final boolean giftCell = GIFT_CELLS.contains(unturn(e.getKey(), facing));
                        final boolean crateCell = CRATE_CELLS.contains(unturn(e.getKey(), facing));
                        final boolean skip = giftCell && hutLevel < 2 || crateCell && hutLevel < 3;
                        final BlockState world = level.getBlockState(m.offset(e.getKey()));
                        final String id = BuiltInRegistries.BLOCK.getKey(world.getBlock()).toString();
                        if (skip) {
                            skipped += world.isAir() ? 1 : 0;
                        } else if (id.equals(e.getValue())) {
                            right++;
                        }
                    }
                    final int expectSkipped = hutLevel < 2 ? 3 : hutLevel < 3 ? 1 : 0;
                    check("the tree stands where the [V] formula says, " + facing + ", hut level " + hutLevel + " (skips: gift pile below 2, crate below 3)",
                            right == want.size() - expectSkipped && skipped == expectSkipped && group != null && group.size() == want.size() - expectSkipped,
                            right + " cells right, " + skipped + " skipped, " + (group == null ? 0 : group.size()) + " listed, " + outcome.status());
                    check("the marker is untouched (" + facing + ")", level.getBlockState(m).is(SeasonsBlocks.FESTIVAL_MARKER.get())
                            && level.getBlockState(m).getValue(FestivalMarkerBlock.FACING) == facing, "");
                    if (i == 3 && group != null) {
                        // the large star, switched on by hand: both halves' entries follow
                        BlockPos lower = null;
                        for (final BlockPos pos : group.entries().keySet()) {
                            final BlockState s = level.getBlockState(pos);
                            if (s.is(SeasonsBlocks.LARGE_STAR.get()) && s.getValue(me.lovkar.seasons.block.LargeStarBlock.HALF) == DoubleBlockHalf.LOWER) {
                                lower = pos;
                            }
                        }
                        if (lower != null) {
                            Lights.setLit(level, lower, level.getBlockState(lower), true, null);
                            final DecorLedger.Located a = ledger.at(lower);
                            final DecorLedger.Located b = ledger.at(lower.above());
                            check("[V] onRemove fires on a same-block change: the large star switched on, both halves' entries follow",
                                    a != null && b != null && a.entry().state() == level.getBlockState(lower) && b.entry().state() == level.getBlockState(lower.above())
                                            && level.getBlockState(lower).getValue(Lights.LIT) && level.getBlockState(lower.above()).getValue(Lights.LIT), "");
                        } else {
                            check("the large star is there", false, "");
                        }
                    }
                    invariant("square " + facing);
                    DecorPlacer.remove(level, ledger, colonyId, target.owner(), DecorPlacer.Stock.FREE);
                    final List<BlockPos> diff = changed(snap);
                    check("taking the square down gives back the snapshot (" + facing + ")", diff.isEmpty() && ledger.group(colonyId, target.owner()) == null,
                            diff.size() + " cells differ" + (diff.isEmpty() ? "" : ", first " + diff.get(0).toShortString() + " " + level.getBlockState(diff.get(0))));
                }
            }
        });
        step(new Step("square: the 10 % rule", 1) {
            @Override
            void start() {
                final BlockPos m = marker(30, 60, Direction.NORTH);
                final DecorWork.Target target = DecorWork.Target.square(m, Direction.NORTH, TEST_PACK);
                final Map<BlockPos, String> want = expectedPiece(Direction.NORTH);
                final List<BlockPos> leaves = new ArrayList<>();
                BlockPos trunk = null;
                for (final Map.Entry<BlockPos, String> e : want.entrySet()) {
                    if (e.getValue().equals("minecraft:spruce_leaves")) {
                        leaves.add(m.offset(e.getKey()));
                    }
                    if (e.getValue().equals("colony_seasons:festival_trunk") && e.getKey().getY() == 1) {
                        trunk = m.offset(e.getKey());
                    }
                }
                leaves.sort(BlockPos::compareTo);
                final BlockState stone = Blocks.COBBLESTONE.defaultBlockState();
                // 2 of 24 cells (8 %), no trunk: the rest goes up, the two are left alone
                final Map<BlockPos, BlockState> snap = snapshot(squareBox(m));
                set(leaves.get(0), stone);
                set(leaves.get(1), stone);
                final Map<BlockPos, BlockState> blocked = snapshot(squareBox(m));
                DecorWork.decorate(level, colonyId, target, 5, DecorPlacer.Stock.FREE, day(), ledger);
                final DecorLedger.GroupView g8 = ledger.group(colonyId, target.owner());
                check("8 % of the cells blocked, no trunk: the rest is placed", g8 != null && g8.size() == want.size() - 2
                        && level.getBlockState(leaves.get(0)) == stone && level.getBlockState(leaves.get(1)) == stone, (g8 == null ? 0 : g8.size()) + " placed of " + want.size());
                DecorPlacer.remove(level, ledger, colonyId, target.owner(), DecorPlacer.Stock.FREE);
                check("the 8 % square comes down to the snapshot", changed(blocked).isEmpty(), changed(blocked).size() + " cells differ");
                // 4 of 24 (17 %): nothing, and the managers are told once a day
                set(leaves.get(2), stone);
                set(leaves.get(3), stone);
                final Map<BlockPos, BlockState> blocked4 = snapshot(squareBox(m));
                final int notices0 = DecorWork.notices();
                final DecorWork.Outcome o = DecorWork.decorate(level, colonyId, target, 5, DecorPlacer.Stock.FREE, day(), ledger);
                DecorWork.decorate(level, colonyId, target, 5, DecorPlacer.Stock.FREE, day(), ledger);
                final DecorLedger.GroupView g15 = ledger.group(colonyId, target.owner());
                check("more than 10 % blocked: nothing is placed", (g15 == null || g15.size() == 0) && changed(blocked4).isEmpty(), o.status());
                check("the square's problem is noted for the hut window", ledger.squareProblem(colonyId), "");
                checkEq("the managers are told once on this day, not twice", DecorWork.notices() - notices0, 1);
                level.setDayTime(level.getDayTime() + 24000L);
                DecorWork.decorate(level, colonyId, target, 5, DecorPlacer.Stock.FREE, day(), ledger);
                checkEq("and once more on the next day", DecorWork.notices() - notices0, 2);
                ledger.dropGroup(colonyId, target.owner());
                // only a trunk cell blocked: nothing
                for (int i = 0; i < 4; i++) {
                    set(leaves.get(i), Blocks.AIR.defaultBlockState());
                }
                set(trunk, stone);
                final Map<BlockPos, BlockState> trunkSnap = snapshot(squareBox(m));
                DecorWork.decorate(level, colonyId, target, 5, DecorPlacer.Stock.FREE, day(), ledger);
                final DecorLedger.GroupView gt = ledger.group(colonyId, target.owner());
                check("a trunk cell blocked: nothing is placed", (gt == null || gt.size() == 0) && changed(trunkSnap).isEmpty(), "");
                ledger.dropGroup(colonyId, target.owner());
                set(trunk, Blocks.AIR.defaultBlockState());
                check("the 10 % area is back as it was", changed(snap).isEmpty(), changed(snap).size() + " cells differ");
                ledger.setSquareProblem(colonyId, false);
                // a missing piece through the whole path: no tree, no throwable
                final DecorWork.Target missing = DecorWork.Target.square(m, Direction.NORTH, "Colony Seasons Missing");
                final DecorWork.Outcome none = DecorWork.decorate(level, colonyId, missing, 1, DecorPlacer.Stock.FREE, day(), ledger);
                check("a missing piece: no tree, no throwable", none.placed() == 0 && changed(snap).isEmpty(), none.status());
            }
        });
        step(new Step("square: tending puts back what a player broke", 1) {
            @Override
            void start() {
                // all in one tick, so no scheduled leaf tick can change a neighbour's distance in between
                final BlockPos m = marker(30, 60, Direction.NORTH);
                final DecorWork.Target target = DecorWork.Target.square(m, Direction.NORTH, TEST_PACK);
                final Map<BlockPos, BlockState> snap = snapshot(squareBox(m));
                final DecorWork.Outcome up = DecorWork.decorate(level, colonyId, target, 5, DecorPlacer.Stock.FREE, day(), ledger);
                // a trunk cell (our festival trunk: it tells the ledger), a spruce leaf (vanilla: it never does) and one of our ornament leaves
                final BlockPos log = m.offset(0, 2, -3);
                final BlockPos leaf = m.offset(-1, 2, -3);
                final BlockPos ornament = m.offset(0, 2, -2);
                final Map<BlockPos, BlockState> had = new LinkedHashMap<>();
                for (final BlockPos pos : List.of(log, leaf, ornament)) {
                    had.put(pos, level.getBlockState(pos));
                    level.destroyBlock(pos, false);
                }
                check("the square is up, and a player broke a trunk cell, a spruce leaf and an ornament leaf",
                        up.complete() && had.get(log).is(SeasonsBlocks.FESTIVAL_TRUNK.get()) && had.get(leaf).is(Blocks.SPRUCE_LEAVES)
                                && had.get(ornament).is(SeasonsBlocks.ORNAMENT_LEAVES.get()) && level.getBlockState(log).isAir()
                                && level.getBlockState(leaf).isAir() && level.getBlockState(ornament).isAir(), up.status());
                check("the vanilla leaf is still listed (no onRemove of ours), the trunk's and the ornament leaf's entries went with them",
                        ledger.at(log) == null && ledger.at(leaf) != null && ledger.at(ornament) == null, "");
                level.setDayTime(level.getDayTime() + 24000L);
                final DecorWork.Outcome tended = DecorWork.tend(level, colonyId, target, 5, DecorPlacer.Stock.FREE, day(), ledger);
                final List<String> wrong = new ArrayList<>();
                for (final Map.Entry<BlockPos, BlockState> e : had.entrySet()) {
                    final BlockState now = level.getBlockState(e.getKey());
                    final DecorLedger.Located at = ledger.at(e.getKey());
                    if (now != e.getValue() || at == null || at.entry().state() != now) {
                        wrong.add(e.getKey().toShortString() + " " + shortName(now) + (at == null ? " unlisted" : ""));
                    }
                }
                final DecorLedger.GroupView group = ledger.group(colonyId, target.owner());
                check("tending puts the broken log, leaf and ornament leaf back, each listed exactly", tended.placed() == 3 && wrong.isEmpty()
                        && group != null && group.size() == TEST_PIECE.size(), tended.status() + (wrong.isEmpty() ? "" : ", " + wrong));
                invariant("square tended");
                DecorPlacer.remove(level, ledger, colonyId, target.owner(), DecorPlacer.Stock.FREE);
                final List<BlockPos> diff = changed(snap);
                check("the tended square comes down to the snapshot", diff.isEmpty() && ledger.group(colonyId, target.owner()) == null,
                        diff.size() + " cells differ");
            }
        });
        step(new Step("square: a registered marker broken takes its square", 400, true) {
            BlockPos m;
            DecorLedger.Owner square;
            Map<BlockPos, BlockState> snap;

            @Override
            void start() {
                m = new BlockPos(50, ground + 1, 60);
                snap = snapshot(squareBox(m));
                final BlockState state = SeasonsBlocks.FESTIVAL_MARKER.get().defaultBlockState().setValue(FestivalMarkerBlock.FACING, Direction.WEST);
                level.setBlock(m, state, Block.UPDATE_ALL);
                state.getBlock().setPlacedBy(level, m, state, owner, ItemStack.EMPTY);
                checkEq("a marker put down in the colony becomes its square", ledger.marker(colonyId), m);
                final DecorWork.Target target = DecorWork.squareTarget(level, ledger, colonyId);
                check("the square target follows the marker and its facing", target != null && target.facing() == Direction.WEST, String.valueOf(target));
                square = DecorLedger.Owner.square(m);
                DecorWork.decorate(level, colonyId, DecorWork.Target.square(m, Direction.WEST, TEST_PACK), 5, DecorPlacer.Stock.FREE, day(), ledger);
                check("the square is up at the registered marker", ledger.group(colonyId, square) != null && ledger.group(colonyId, square).size() == TEST_PIECE.size(), "");
                level.setBlock(m, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                check("the broken marker is no longer the colony's square", ledger.marker(colonyId) == null, "");
            }

            @Override
            boolean tick() {
                return t >= 2 && ledger.group(colonyId, square) == null;
            }

            @Override
            void end(final boolean done) {
                check("the marker broken: its square comes down at the end of the tick", done && changed(snap).isEmpty(), "after " + t + " ticks, "
                        + changed(snap).size() + " cells differ");
                // he was on his way to the square when the marker went: the visit itself puts nothing up
                final DecorWork.Outcome late = DecorWork.decorate(level, colonyId, DecorWork.Target.square(m, Direction.WEST, TEST_PACK), 5,
                        DecorPlacer.Stock.FREE, day(), ledger);
                check("a visit to a square whose marker is gone places nothing", late.placed() == 0 && changed(snap).isEmpty()
                        && ledger.group(colonyId, square) == null, late.status());
            }
        });
        step(new Step("square: the real pieces of the Colony Seasons pack", 400, true) {
            final Map<String, PieceLoader.Piece> real = new LinkedHashMap<>();
            final List<String> paths = List.of(PieceLoader.pathFor(1), PieceLoader.pathFor(3), PieceLoader.pathFor(4), PieceLoader.pathFor(5));
            boolean present;

            @Override
            void start() {
                present = StructurePacks.getStructurePack(PieceLoader.PACK) != null;
            }

            @Override
            boolean tick() {
                if (!present) {
                    return true;
                }
                for (final String path : paths) {
                    if (!real.containsKey(path)) {
                        final PieceLoader.Piece piece = PieceLoader.request(level, PieceLoader.PACK, path, Direction.NORTH);
                        if (piece != null) {
                            real.put(path, piece);
                        } else if (PieceLoader.missing(PieceLoader.PACK, path, Direction.NORTH)) {
                            real.put(path, null);
                        }
                    }
                }
                return real.size() == paths.size();
            }

            @Override
            void end(final boolean done) {
                if (!present) {
                    note("NOTE: the pack '{}' is not in this build (its pieces come from chunk S5); the real pieces are not checked", PieceLoader.PACK);
                    return;
                }
                // in the free middle of the claim (the style plots are cleared by now), one after the other
                int x = -45;
                for (final String path : paths) {
                    final PieceLoader.Piece piece = real.get(path);
                    if (piece == null) {
                        check("real piece " + path + " loads", false, "missing");
                        continue;
                    }
                    final BlockPos m = marker(x, -40, Direction.NORTH);
                    x += 30;
                    final DoorFinder.Box box = new DoorFinder.Box(m.offset(-20, -3, -20), m.offset(20, 48, 20), m);
                    final Map<BlockPos, BlockState> snap = snapshot(box);
                    final DecorWork.Target target = DecorWork.Target.square(m, Direction.NORTH, PieceLoader.PACK);
                    final int hutLevel = path.equals(PieceLoader.pathFor(5)) ? 5 : path.equals(PieceLoader.pathFor(4)) ? 4 : path.equals(PieceLoader.pathFor(3)) ? 3 : 2;
                    int rounds = 0;
                    DecorWork.Outcome o;
                    do {
                        o = DecorWork.decorate(level, colonyId, target, hutLevel, DecorPlacer.Stock.FREE, day(), ledger);
                    } while (!o.complete() && o.placed() > 0 && ++rounds < 20);
                    final DecorLedger.GroupView g = ledger.group(colonyId, target.owner());
                    int wrong = 0;
                    for (final BlockPos pos : changed(snap)) {
                        if (!snap.get(pos).isAir() || g == null || !g.entries().containsKey(pos)) {
                            wrong++;
                        }
                    }
                    check("real piece " + path + ": placed, every changed cell was air and is listed, the marker untouched",
                            g != null && g.size() > 0 && wrong == 0 && level.getBlockState(m).is(SeasonsBlocks.FESTIVAL_MARKER.get()),
                            (g == null ? 0 : g.size()) + " listed, " + wrong + " wrong, " + o.status());
                    DecorPlacer.remove(level, ledger, colonyId, target.owner(), DecorPlacer.Stock.FREE);
                    level.setBlock(m, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    final Map<BlockPos, BlockState> after = new HashMap<>(snap);
                    after.remove(m);
                    int differ = 0;
                    for (final Map.Entry<BlockPos, BlockState> e : after.entrySet()) {
                        if (level.getBlockState(e.getKey()) != e.getValue()) {
                            differ++;
                        }
                    }
                    check("real piece " + path + ": taking it down gives back the snapshot", differ == 0, differ + " cells differ");
                }
            }
        });
        planGrassOnPlanter();
    }

    /**
     * The stuck square of the third in-game test (S10c): the grand tree with short grass on one of the planter's chiseled
     * stone brick cells, decorations costing items (the config default) from a stock that has everything. The grass
     * keeps that brick cell (the cell rule places only into air), so the lantern on it cannot stand ("survive"): it
     * holds nothing back, the free cells (the large star, the tinsel, the gifts, the crate) go up, the tree is ready, and
     * after three visits that place nothing the square is left for the day. Taking it down gives back the world.
     */
    private void planGrassOnPlanter() {
        step(new Step("square: the grand tree with short grass on a planter cell, decorations from stock (S10c)", 400, true) {
            PieceLoader.Piece piece;

            @Override
            boolean tick() {
                piece = PieceLoader.request(level, PieceLoader.PACK, PieceLoader.pathFor(5), Direction.NORTH);
                return piece != null || PieceLoader.missing(PieceLoader.PACK, PieceLoader.pathFor(5), Direction.NORTH);
            }

            @Override
            void end(final boolean done) {
                check("the grand tree piece loads", piece != null, PieceLoader.pathFor(5));
                if (piece == null) {
                    return;
                }
                check("decorations cost items here (decorator.needsMaterials, the config default)", SeasonsConfig.needsMaterials(), "");
                final BlockPos m = marker(-45, -40, Direction.NORTH);
                BlockPos lantern = null;
                final Map<BlockPos, BlockState> cells = new HashMap<>();
                for (final PieceLoader.PieceCell c : piece.cells()) {
                    cells.put(c.rel(), c.state());
                }
                for (final PieceLoader.PieceCell c : piece.cells()) {
                    final BlockState below = cells.get(c.rel().below());
                    if (lantern == null && c.state().is(Blocks.LANTERN) && below != null && below.is(Blocks.CHISELED_STONE_BRICKS)) {
                        lantern = m.offset(c.rel());
                    }
                }
                check("the piece has a lantern on a chiseled stone brick of the planter", lantern != null, "");
                if (lantern == null) {
                    level.setBlock(m, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    return;
                }
                final BlockPos brick = lantern.below();
                set(brick, Blocks.SHORT_GRASS.defaultBlockState());
                final DoorFinder.Box box = new DoorFinder.Box(m.offset(-8, -2, -8), m.offset(8, 20, 8), m);
                final Map<BlockPos, BlockState> snap = snapshot(box);
                final DecorWork.Target target = DecorWork.Target.square(m, Direction.NORTH, PieceLoader.PACK);
                final long today = day();
                final List<String> visits = new ArrayList<>();
                DecorWork.Outcome o;
                int zero = 0;
                for (int i = 0; i < 12 && zero < DecorWork.MAX_FRUITLESS_VISITS; i++) {
                    o = DecorWork.decorate(level, colonyId, target, 5, DecorPlacer.Stock.FREE, today, ledger);
                    visits.add(o.status());
                    zero = o.placed() == 0 ? zero + 1 : 0;
                }
                final DecorLedger.GroupView g = ledger.group(colonyId, target.owner());
                final BlockState lanternState = DecorPlacer.normalise(Blocks.LANTERN.defaultBlockState(), lantern);
                check("the lantern on the grassy brick cell is refused: it cannot stand there", level.getBlockState(lantern).isAir()
                        && "survive".equals(DecorPlacer.refusal(level, ledger, colonyId, lantern, lanternState, true)),
                        DecorPlacer.refusal(level, ledger, colonyId, lantern, lanternState, true) + ", brick cell " + shortName(level.getBlockState(brick)));
                int gifts = 0;
                int pieceGifts = 0;
                for (final PieceLoader.PieceCell c : piece.cells()) {
                    pieceGifts += c.giftPile() ? 1 : 0;
                }
                boolean star = false;
                boolean tinsel = false;
                boolean crate = false;
                if (g != null) {
                    for (final DecorLedger.Entry e : g.entries().values()) {
                        gifts += e.state().is(SeasonsBlocks.GIFT_PILE.get()) ? 1 : 0;
                        star |= e.state().is(SeasonsBlocks.LARGE_STAR.get());
                        tinsel |= e.state().is(SeasonsBlocks.TINSEL_LEAVES.get());
                        crate |= e.state().is(SeasonsBlocks.FIREWORK_CRATE.get());
                    }
                }
                check("the free cells go up all the same: the large star, the tinsel, every gift pile, the crate",
                        star && tinsel && crate && gifts == pieceGifts && pieceGifts > 0, "star " + star + ", tinsel " + tinsel + ", crate "
                                + crate + ", gifts " + gifts + " of " + pieceGifts);
                check("the tree itself is up: its ready day is noted", g != null && g.treeDay() == today && g.decoratedDay() < 0,
                        g == null ? "no group" : "tree " + g.treeDay() + ", decorated " + g.decoratedDay());
                check("after " + DecorWork.MAX_FRUITLESS_VISITS + " visits that place nothing the square is left for today",
                        ledger.fruitless(colonyId, target.owner(), today) >= DecorWork.MAX_FRUITLESS_VISITS
                                && visits.get(visits.size() - 1).contains("left for today"), visits.toString());
                invariant("the grand tree on grass");
                DecorPlacer.remove(level, ledger, colonyId, target.owner(), DecorPlacer.Stock.FREE);
                final List<BlockPos> diff = changed(snap);
                check("taking the grand tree down gives back the snapshot, the grass included", diff.isEmpty() && ledger.group(colonyId, target.owner()) == null,
                        diff.size() + " cells differ" + (diff.isEmpty() ? "" : ", first " + diff.get(0).toShortString() + " " + level.getBlockState(diff.get(0))));
                set(brick, Blocks.AIR.defaultBlockState());
                level.setBlock(m, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        });
    }

    /** A turned relative position back in the north frame. */
    private static BlockPos unturn(final BlockPos rel, final Direction facing) {
        final Rotation r = rotationOf(facing);
        final Rotation back = switch (r) {
            case CLOCKWISE_90 -> Rotation.COUNTERCLOCKWISE_90;
            case COUNTERCLOCKWISE_90 -> Rotation.CLOCKWISE_90;
            default -> r;
        };
        return rel.rotate(back);
    }

    // ================================================================ LIFECYCLE on a real colony

    private IBuilding townHall;
    private IBuilding h1;
    private IBuilding h2;
    private IBuilding h3;
    private BuildingDecorator hut;
    private ICitizenData worker;
    private final Map<String, DoorFinder.Box> boxes = new LinkedHashMap<>();

    private DecorLedger.Owner ownerOf(final IBuilding b) {
        return DecorLedger.Owner.building(b.getPosition());
    }

    private int size(final IBuilding b) {
        final DecorLedger.GroupView g = ledger.group(colonyId, ownerOf(b));
        return g == null ? 0 : g.size();
    }

    private void planLifecycle() {
        step(new Step("lifecycle: the colony's buildings", 60, true) {
            @Override
            void start() {
                boxes.put("town hall", house(-3, -1, 11, 7, 2));
                boxes.put("hut", house(12, -1, 11, 7, 17));
                boxes.put("h1", house(-40, 16, 11, 7, -35));
                boxes.put("h2", house(-22, 16, 11, 7, -17));
                boxes.put("h3", house(-4, 16, 11, 7, 1));
                townHall = register("minecolonies:blockhuttownhall", boxes.get("town hall"), 1, null, null);
                final IBuilding decorator = register(BuiltInRegistries.BLOCK.getKey(DecoratorSetup.HUT.get()).toString(), boxes.get("hut"), 1, null, null);
                hut = decorator instanceof BuildingDecorator d ? d : null;
                h1 = register("minecolonies:blockhutlumberjack", boxes.get("h1"), 1, "Medieval Oak", "fundamentals/lumberjack1.blueprint");
                h2 = register("minecolonies:blockhutstonemason", boxes.get("h2"), 1, "Medieval Oak", "craftsmanship/masonry/stonemason1.blueprint");
                h3 = register("minecolonies:blockhutsawmill", boxes.get("h3"), 1, "Medieval Oak", "craftsmanship/carpentry/sawmill1.blueprint");
                check("the lifecycle buildings are registered", townHall != null && hut != null && h1 != null && h2 != null && h3 != null,
                        "town hall " + (townHall != null) + ", Decorator's hut " + (hut != null));
                check("the town hall is the colony's town hall", colony.getServerBuildingManager().getTownHall() == townHall, "");
            }

            @Override
            boolean tick() {
                return t >= 30 && !level.getChunkSource().getLightEngine().hasLightWork();
            }
        });
        step(new Step("lifecycle: decorate, swap, switch, CLEAR_NON_SOLIDS, level up", 1) {
            @Override
            void start() {
                if (hut == null || h1 == null || h2 == null || h3 == null || townHall == null) {
                    return;
                }
                for (final IBuilding b : List.of(townHall, hut, h1, h2, h3)) {
                    DecorWork.decorate(level, colonyId, DecorWork.Target.building(b), 3, DecorPlacer.Stock.FREE, day(), ledger);
                }
                check("each building is decorated at hut level 3 (wreath, 3 + 3 garland, 2 + 1 + 2 lights)",
                        size(townHall) == 12 && size(hut) == 12 && size(h1) == 12 && size(h2) == 12 && size(h3) == 12,
                        size(townHall) + ", " + size(hut) + ", " + size(h1) + ", " + size(h2) + ", " + size(h3));
                // the same visit again finds nothing to do: the ledger does not change, so its file is not written
                ledger.flush(level);
                final int writes = ledger.writes();
                final DecorWork.Outcome again = DecorWork.decorate(level, colonyId, DecorWork.Target.building(townHall), 3, DecorPlacer.Stock.FREE, day(), ledger);
                check("a second visit to a decorated building changes nothing and writes no ledger file", again.placed() == 0 && again.complete()
                        && ledger.writes() == writes, again.status() + ", " + (ledger.writes() - writes) + " writes");
                invariant("decorated");
                // the "player" swaps one of the garlands for a plank
                final BlockPos garland = firstOf(h1, SeasonsBlocks.GARLAND.get());
                level.setBlock(garland, Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_ALL);
                swapped = garland;
                check("a swapped block: its entry is forgotten at once and the block is left", ledger.at(garland) == null
                        && level.getBlockState(garland).is(Blocks.OAK_PLANKS), "at " + garland.toShortString());
                // a light switched on by hand
                final BlockPos light = firstLight(h1);
                Lights.setLit(level, light, level.getBlockState(light), true, null);
                switched = light;
                final DecorLedger.Located at = ledger.at(light);
                check("a light switched on by hand: the entry follows it", at != null && at.entry().state() == level.getBlockState(light)
                        && level.getBlockState(light).getValue(Lights.LIT), "at " + light.toShortString());
                invariant("swapped and switched");
                // the Builder's CLEAR_NON_SOLIDS sets every non-solid in the blueprint's air cells to air
                final List<BlockPos> h3Cells = new ArrayList<>(ledger.group(colonyId, ownerOf(h3)).entries().keySet());
                for (final BlockPos pos : h3Cells) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
                check("the Builder clears our blocks (CLEAR_NON_SOLIDS): no ghost entries", size(h3) == 0, h3Cells.size() + " cleared");
                invariant("after CLEAR_NON_SOLIDS");
                // the Decorator's hut goes up a level: the next visit tops up, nothing is placed twice
                final int before = size(h1);
                final Set<BlockPos> had = new HashSet<>(ledger.group(colonyId, ownerOf(h1)).entries().keySet());
                final DecorWork.Outcome up = DecorWork.decorate(level, colonyId, DecorWork.Target.building(h1), 4, DecorPlacer.Stock.FREE, day(), ledger);
                final Set<BlockPos> now = new HashSet<>(ledger.group(colonyId, ownerOf(h1)).entries().keySet());
                check("the hut a level up: topped up, no crash, the old entries kept", now.containsAll(had) && size(h1) > before
                        && ledger.group(colonyId, ownerOf(h1)).level() == 4, before + " -> " + size(h1) + ", " + up.status());
                invariant("after the level up");
            }
        });
        step(new Step("lifecycle: a work order", 900, true) {
            int order = -1;
            int goneAt = -1;
            boolean skipped = true;

            @Override
            void start() {
                if (h2 == null) {
                    return;
                }
                final WorkOrderBuilding wo = WorkOrderBuilding.create(WorkOrderType.REPAIR, h2);
                colony.getWorkManager().addWorkOrder(wo, false);
                order = wo.getID();
                check("a repair order makes the building pending construction", DecorWork.busy(colony, h2) && h2.isPendingConstruction(), "order " + order);
                // he set out to tend it before the order opened: the visit is turned away and does not count as tended
                final DecorLedger.GroupView before = ledger.group(colonyId, ownerOf(h2));
                final DecorWork.Outcome turned = DecorWork.tend(level, colonyId, DecorWork.Target.building(h2), 3, DecorPlacer.Stock.FREE, day(), ledger);
                final DecorLedger.GroupView after = ledger.group(colonyId, ownerOf(h2));
                check("a tending visit turned away by the open order places nothing and is not noted as tended", before != null && after != null
                        && turned.placed() == 0 && after.tendedDay() == before.tendedDay() && after.size() == before.size(),
                        turned.status() + ", tended day " + (before == null ? "-" : before.tendedDay()) + " -> " + (after == null ? "-" : after.tendedDay()));
            }

            @Override
            boolean tick() {
                if (h2 == null) {
                    return true;
                }
                if (goneAt < 0 && size(h2) == 0) {
                    goneAt = t;
                }
                if (goneAt >= 0 && t < goneAt + 300) {
                    final DecorWork.Target next = DecorWork.nextToDecorate(level, colony, 3, hut.getPosition(), ledger, DecorPlacer.Stock.FREE, day());
                    skipped &= next == null || !next.owner().equals(ownerOf(h2));
                    return false;
                }
                return goneAt >= 0;
            }

            @Override
            void end(final boolean done) {
                check("an open work order: the building's decorations are removed within 200 ticks", goneAt >= 0 && goneAt <= 200, "after " + goneAt + " ticks");
                check("the building is skipped while the order is open", skipped && size(h2) == 0, "");
                // he picked it before the order opened and walks in now: the visit itself refuses it
                final DecorWork.Outcome late = DecorWork.decorate(level, colonyId, DecorWork.Target.building(h2), 3, DecorPlacer.Stock.FREE, day(), ledger);
                check("a visit that arrives while the order is open places nothing", late.placed() == 0 && size(h2) == 0, late.status());
                final List<BlockPos> leftovers = new ArrayList<>();
                for (final BlockPos pos : BlockPos.betweenClosed(boxes.get("h2").grown(2).min(), boxes.get("h2").grown(2).max())) {
                    if (level.getBlockState(pos).getBlock() instanceof WallDecorationBlock) {
                        leftovers.add(pos.immutable());
                    }
                }
                check("none of our blocks is left on it", leftovers.isEmpty(), leftovers.size() + " left");
                colony.getWorkManager().removeWorkOrder(order);
                check("the order is gone, the building is free again", !DecorWork.busy(colony, h2), "");
                // h2 was counted against today's budget before the order. The rest of the budget goes to other
                // buildings now (in a full run the square's real pieces used it up already), so h2 must come back
                // without a second slot: the day's count is the colony's, not the dropped group's.
                note("today's building budget before the order is done: {} of {}", ledger.budgetUsed(colonyId, day()), DecorWork.quota(3));
                while (ledger.budgetUsed(colonyId, day()) < DecorWork.quota(3)) {
                    ledger.spendBudget(colonyId, day());
                }
                final int spent = ledger.budgetUsed(colonyId, day());
                final DecorWork.Target next = DecorWork.nextToDecorate(level, colony, 3, hut.getPosition(), ledger, DecorPlacer.Stock.FREE, day());
                check("decorated again after the order, with the day's budget used up: it is the next target", next != null && next.owner().equals(ownerOf(h2)),
                        String.valueOf(next == null ? null : next.owner()));
                if (next != null) {
                    DecorWork.decorate(level, colonyId, next, 3, DecorPlacer.Stock.FREE, day(), ledger);
                }
                checkEq("its decorations are back", size(h2), 12);
                checkEq("counted today already: putting it back up takes no second slot of the day's budget", ledger.budgetUsed(colonyId, day()), spent);
                invariant("after the work order");
            }
        });
        step(new Step("lifecycle: a building removed", 40, true) {
            Map<BlockPos, DecorLedger.Entry> cells;

            @Override
            void start() {
                DecorWork.decorate(level, colonyId, DecorWork.Target.building(h3), 3, DecorPlacer.Stock.FREE, day(), ledger);
                cells = new LinkedHashMap<>(ledger.group(colonyId, ownerOf(h3)).entries());
                check("the cleared building is decorated again", cells.size() == 12, cells.size() + " entries");
                h3.destroy();
            }

            @Override
            boolean tick() {
                return t >= 2 && ledger.group(colonyId, ownerOf(h3)) == null;
            }

            @Override
            void end(final boolean done) {
                boolean air = true;
                for (final BlockPos pos : cells.keySet()) {
                    air &= level.getBlockState(pos).isAir();
                }
                check("BuildingRemovedModEvent: that building's decorations go at the end of the tick", done && t <= 3 && air, "after " + t + " ticks");
                invariant("after the building was removed");
            }
        });
        step(new Step("lifecycle: decor list and decor remove", 1) {
            @Override
            void start() {
                final int before = ledger.count(colonyId);
                boolean refused;
                try {
                    server.getCommands().getDispatcher().execute("colonyseasons decor remove " + colonyId,
                            server.createCommandSourceStack().withPermission(0).withSuppressedOutput());
                    refused = false;
                } catch (final CommandSyntaxException denied) {
                    refused = true;
                }
                check("decor remove from a source without permission 2 is refused", refused && ledger.count(colonyId) == before, before + " entries kept");
                int listed;
                try {
                    listed = server.getCommands().getDispatcher().execute("colonyseasons decor list " + colonyId,
                            server.createCommandSourceStack().withSuppressedOutput());
                } catch (final CommandSyntaxException denied) {
                    listed = -1;
                }
                checkEq("decor list shows the colony", listed, 1);
                final Map<BlockPos, DecorLedger.Entry> all = new LinkedHashMap<>(ledger.entries(colonyId));
                int result;
                try {
                    result = server.getCommands().getDispatcher().execute("colonyseasons decor remove " + colonyId,
                            server.createCommandSourceStack().withSuppressedOutput());
                } catch (final CommandSyntaxException denied) {
                    result = -1;
                }
                boolean air = true;
                for (final BlockPos pos : all.keySet()) {
                    air &= level.getBlockState(pos).isAir();
                }
                check("decor remove clears the colony in one go", result > 0 && ledger.count(colonyId) == 0 && ledger.groups(colonyId).isEmpty() && air,
                        all.size() + " entries before, " + ledger.count(colonyId) + " after");
                check("the swapped plank is still there", swapped != null && level.getBlockState(swapped).is(Blocks.OAK_PLANKS), "");
                check("the switched light was removed by the rule all the same", switched != null && level.getBlockState(switched).isAir(), "");
            }
        });
        planAi();
        step(new Step("lifecycle: the hut destroyed in the window, then the sweep", 1400, true) {
            int afterWindowAt = -1;

            @Override
            void start() {
                setDate(DECOR_DAY);
                for (final IBuilding b : List.of(townHall, hut, h1, h2)) {
                    DecorWork.decorate(level, colonyId, DecorWork.Target.building(b), 3, DecorPlacer.Stock.FREE, day(), ledger);
                }
                final int all = ledger.count(colonyId);
                final Map<BlockPos, DecorLedger.Entry> hutCells = new LinkedHashMap<>(ledger.group(colonyId, ownerOf(hut)).entries());
                hut.destroy();
                DecorEvents.drain(server);
                boolean air = true;
                for (final BlockPos pos : hutCells.keySet()) {
                    air &= level.getBlockState(pos).isAir();
                }
                check("the Decorator's hut destroyed in the window: its own decorations go", ledger.group(colonyId, ownerOf(hut)) == null && air
                        && ledger.count(colonyId) == all - hutCells.size(), hutCells.size() + " of " + all);
                check("the rest stays up while the window is open", ledger.count(colonyId) > 0, ledger.count(colonyId) + " entries");
            }

            @Override
            boolean tick() {
                if (t == 220) {
                    check("the rest is still up 200 ticks into the window (no sweep while it is open)", ledger.count(colonyId) > 0, ledger.count(colonyId) + " entries");
                    setDate(AFTER);
                    afterWindowAt = t;
                }
                return afterWindowAt > 0 && !ledger.hasEntries(colonyId);
            }

            @Override
            void end(final boolean done) {
                check("after the window, with no Decorator's hut left, the server sweep clears everything", done && ledger.count(colonyId) == 0,
                        (t - afterWindowAt) + " ticks after the window, " + DecorEvents.swept() + " cells swept in all, " + DecorEvents.lostItems() + " items lost");
            }
        });
    }

    private BlockPos swapped;
    private BlockPos switched;

    private BlockPos firstOf(final IBuilding b, final Block block) {
        for (final Map.Entry<BlockPos, DecorLedger.Entry> e : ledger.group(colonyId, ownerOf(b)).entries().entrySet()) {
            if (e.getValue().state().getBlock() == block) {
                return e.getKey();
            }
        }
        return null;
    }

    private BlockPos firstLight(final IBuilding b) {
        final BlockPos a = firstOf(b, SeasonsBlocks.LANTERN_STRING.get());
        return a != null ? a : firstOf(b, SeasonsBlocks.FAIRY_LIGHTS.get());
    }

    // ---------------------------------------------------------------- the Decorator's AI by himself

    private static final Predicate<ItemStack> DECORATIONS = s -> s.is(SeasonsBlocks.WREATH_ITEM.get()) || s.is(SeasonsBlocks.GARLAND_ITEM.get())
            || s.is(SeasonsBlocks.LANTERN_STRING_ITEM.get()) || s.is(SeasonsBlocks.FAIRY_LIGHTS_ITEM.get());

    private int everywhere(final Predicate<ItemStack> what) {
        int total = BuildingDecorator.countIn(hut.getItemHandlerCap(), what) + (worker == null ? 0 : BuildingDecorator.countIn(worker.getInventory(), what));
        for (final ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, new AABB(hut.getPosition()).inflate(96), e -> what.test(e.getItem()))) {
            total += drop.getItem().getCount();
        }
        return total;
    }

    private void keepWell() {
        worker.setSaturation(ICitizenData.MAX_SATURATION);
        if (worker.getCitizenDiseaseHandler().isSick()) {
            worker.getCitizenDiseaseHandler().cure();
        }
    }

    private JobDecorator job() {
        return worker != null && worker.getJob() instanceof JobDecorator j ? j : null;
    }

    private void planAi() {
        final int[] itemsBefore = new int[1];
        final BlockPos[] broken = new BlockPos[1];
        step(new Step("AI: he decorates by himself", 9000, true) {
            @Override
            void start() {
                setDate(DECOR_DAY);
                nextMorning();                     // a new game day: the API tests above used today's budget
                hut.setBuildingLevel(2);
                hut.markDirty();
                final IItemHandler store = hut.getItemHandlerCap();
                for (final ItemStack stack : List.of(new ItemStack(SeasonsBlocks.WREATH_ITEM.get(), 8), new ItemStack(SeasonsBlocks.GARLAND_ITEM.get(), 32),
                        new ItemStack(SeasonsBlocks.LANTERN_STRING_ITEM.get(), 16), new ItemStack(SeasonsBlocks.FAIRY_LIGHTS_ITEM.get(), 16))) {
                    check("decorations fit in the hut", ItemHandlerHelper.insertItemStacked(store, stack, false).isEmpty(), stack.toString());
                }
                worker = colony.getCitizenManager().createAndRegisterCivilianData();
                colony.getCitizenManager().spawnOrCreateCitizen(worker, level, hut.getPosition().offset(0, 0, 4));
                final WorkerBuildingModule work = hut.getModule(DecoratorModules.WORK);
                check("a citizen is hired as the Decorator", work.assignCitizen(worker) && worker.getJob() instanceof JobDecorator, worker.getName());
                itemsBefore[0] = everywhere(DECORATIONS);
                note("{} decorations in the hut before he starts", itemsBefore[0]);
            }

            @Override
            boolean tick() {
                int decorated = 0;
                for (final DecorLedger.GroupView g : ledger.groups(colonyId)) {
                    decorated += g.size() > 0 && g.decoratedDay() >= 0 ? 1 : 0;
                }
                return t % 20 == 0 && decorated >= 2;
            }

            @Override
            void end(final boolean done) {
                int decorated = 0;
                for (final DecorLedger.GroupView g : ledger.groups(colonyId)) {
                    decorated += g.size() > 0 ? 1 : 0;
                }
                check("the Decorator decorates at least 2 buildings by himself", done && decorated >= 2,
                        decorated + " buildings in " + t + " ticks, status '" + (job() == null ? "-" : job().status()) + "'");
                check("the hut says the decorations are up", hut.decorationsUp(), "");
                invariant("after his decorating");
                for (final DecorLedger.GroupView g : ledger.groups(colonyId)) {
                    for (final Map.Entry<BlockPos, DecorLedger.Entry> e : g.entries().entrySet()) {
                        if (broken[0] == null && g.decoratedDay() >= 0 && e.getValue().state().is(SeasonsBlocks.GARLAND.get())) {
                            broken[0] = e.getKey();
                        }
                    }
                }
                if (broken[0] != null) {
                    level.setBlock(broken[0], Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    check("a garland broken by a player is forgotten", ledger.at(broken[0]) == null, broken[0].toShortString());
                } else {
                    check("a garland of his to break", false, "none found");
                }
            }
        });
        step(new Step("AI: tending puts the broken garland back the next day, a festival day", 9000, true) {
            int hosting0;

            @Override
            void start() {
                // 25 Dec: Hosting (S7's stub) on the festival days, and the upkeep carries on behind it
                setDate(FESTIVAL_DAY);
                nextMorning();
                hosting0 = job() == null ? 0 : job().visits(me.lovkar.seasons.ai.EntityAIWorkDecorator.DecoratorState.HOSTING);
            }

            @Override
            boolean tick() {
                if (broken[0] == null) {
                    return true;
                }
                final DecorLedger.Located at = ledger.at(broken[0]);
                return t % 20 == 0 && at != null && level.getBlockState(broken[0]).is(SeasonsBlocks.GARLAND.get());
            }

            @Override
            void end(final boolean done) {
                check("tending puts the broken garland back on the next day", done && broken[0] != null, "after " + t + " ticks, "
                        + (job() == null ? "" : job().visits(me.lovkar.seasons.ai.EntityAIWorkDecorator.DecoratorState.TENDING) + " tending visits"));
                final int hosting = job() == null ? 0 : job().visits(me.lovkar.seasons.ai.EntityAIWorkDecorator.DecoratorState.HOSTING) - hosting0;
                check("on a festival day the Hosting stub is visited and the upkeep goes on behind it", hosting > 0 && job().phase() == DecorPhase.HOSTING,
                        hosting + " hosting visits, phase " + (job() == null ? "-" : job().phase()));
                invariant("after tending");
            }
        });
        step(new Step("AI: taking down after the window", 12000, true) {
            @Override
            void start() {
                setDate(AFTER);
                nextMorning();
            }

            @Override
            boolean tick() {
                if (t % 4000 == 3999) {
                    nextMorning();
                }
                return t % 20 == 0 && !ledger.hasEntries(colonyId) && !hut.decorationsUp();
            }

            @Override
            void end(final boolean done) {
                check("after the window he takes everything down and the hut says so", done, "after " + t + " ticks, "
                        + ledger.count(colonyId) + " entries left, status '" + (job() == null ? "-" : job().status()) + "'");
                final int after = everywhere(DECORATIONS);
                checkEq("decoration items balance: what was in the hut is back, less the garland the player broke", after, itemsBefore[0] - 1);
                hut.getModule(DecoratorModules.WORK).removeCitizen(worker);
                note("the Decorator is let go: {}", worker.getJob() == null ? "no job" : worker.getJob().getClass().getSimpleName());
            }
        });
    }

    // ================================================================ restart

    private void planRestartSave() {
        final Map<BlockPos, BlockState> snap = new LinkedHashMap<>();
        final int[] open = new int[1];
        step(new Step("restart-save: half a building for lack of stock", 60, true) {
            @Override
            void start() {
                setDate(DECOR_DAY);
                quietWorld();
                buildColony();
                boxes.put("town hall", house(-3, -1, 11, 7, 2));
                boxes.put("r1", house(-40, 16, 11, 7, -35));
                boxes.put("r2", house(-22, 16, 11, 7, -17));
                townHall = register("minecolonies:blockhuttownhall", boxes.get("town hall"), 1, null, null);
                h1 = register("minecolonies:blockhutlumberjack", boxes.get("r1"), 1, "Medieval Oak", "fundamentals/lumberjack1.blueprint");
                h2 = register("minecolonies:blockhutstonemason", boxes.get("r2"), 1, "Medieval Oak", "craftsmanship/masonry/stonemason1.blueprint");
            }

            @Override
            boolean tick() {
                return t >= 30 && !level.getChunkSource().getLightEngine().hasLightWork();
            }

            @Override
            void end(final boolean done) {
                snap.putAll(snapshot(boxes.get("r1").grown(2)));
                snap.putAll(snapshot(boxes.get("r2").grown(2)));
                final CountingStock plenty = new CountingStock(1000);
                DecorWork.decorate(level, colonyId, DecorWork.Target.building(h1), 3, plenty, day(), ledger);
                final CountingStock short4 = new CountingStock(4);
                final DecorWork.Outcome half = DecorWork.decorate(level, colonyId, DecorWork.Target.building(h2), 3, short4, day(), ledger);
                open[0] = half.open();
                check("one building done, the other half done for lack of stock", size(h1) == 12 && size(h2) == 4 && half.open() == 8,
                        size(h1) + " and " + size(h2) + " placed, " + half.open() + " open");
            }
        });
        step(new Step("restart-save: the ledger file on disk", 200, true) {
            String detail = "";

            /** The ledger as the file on disk has it (the ledger writes it at once; the probe still polls, to be robust). */
            boolean fileMatches() {
                try {
                    final CompoundTag root = NbtIo.readCompressed(dataFile(), NbtAccounter.unlimitedHeap());
                    final DecorLedger fromDisk = DecorLedger.load(root.getCompound("data"), level.registryAccess());
                    detail = fromDisk.count(colonyId) + " entries on disk, " + ledger.count(colonyId) + " in memory";
                    return fromDisk.entries(colonyId).equals(ledger.entries(colonyId));
                } catch (final java.io.IOException unreadable) {
                    detail = "the file is not readable yet";
                    return false;
                }
            }

            @Override
            boolean tick() {
                return t % 5 == 0 && fileMatches();
            }

            @Override
            void end(final boolean done) {
                check("the ledger file read from disk lists every placed entry before any autosave", done && level.getGameTime() < 6000,
                        detail + " after " + t + " ticks, game time " + level.getGameTime());
                final CompoundTag facts = new CompoundTag();
                facts.putInt("entries", ledger.count(colonyId));
                facts.putInt("open", open[0]);
                final ListTag cells = new ListTag();
                for (final Map.Entry<BlockPos, BlockState> e : snap.entrySet()) {
                    final CompoundTag one = new CompoundTag();
                    one.putLong("pos", e.getKey().asLong());
                    one.put("state", NbtUtils.writeBlockState(e.getValue()));
                    cells.add(one);
                }
                facts.put("snapshot", cells);
                try {
                    NbtIo.writeCompressed(facts, server.getWorldPath(LevelResource.ROOT).resolve(RESTART_FILE));
                    check("the snapshot is saved in the world folder", true, snap.size() + " cells");
                } catch (final java.io.IOException unwritable) {
                    check("the snapshot is saved in the world folder", false, "");
                }
                server.tickRateManager().stopSprinting();
                check("the world saved", server.saveEverything(false, true, true), "");
            }
        });
    }

    private Path dataFile() {
        return DimensionType.getStorageFolder(level.dimension(), server.getWorldPath(LevelResource.ROOT)).resolve("data").resolve(DecorLedger.FILE + ".dat");
    }

    private void planRestartLoad() {
        step(new Step("restart-load: find the colony again", 2400) {
            @Override
            boolean tick() {
                final IColony found = IColonyManager.getInstance().getColonyByWorld(1, level);
                if (found == null) {
                    return false;
                }
                colony = found;
                colonyId = found.getID();
                ground = found.getCenter().getY() - 1;
                h1 = found.getServerBuildingManager().getBuilding(new BlockPos(-35, ground + 1, 19));
                h2 = found.getServerBuildingManager().getBuilding(new BlockPos(-17, ground + 1, 19));
                return h1 != null && h2 != null;
            }

            @Override
            void end(final boolean done) {
                check("the colony and its buildings are back", done, colony == null ? "no colony" : "colony " + colonyId);
                if (!done) {
                    finish();
                    return;
                }
                quietWorld();
                owner = FakePlayerFactory.getMinecraft(level);
                colony.getPackageManager().addCloseSubscriber(owner);
                for (int cx = -6; cx <= 6; cx++) {
                    for (int cz = -6; cz <= 6; cz++) {
                        level.setChunkForced(cx, cz, true);
                    }
                }
                CompoundTag facts = null;
                try {
                    facts = NbtIo.readCompressed(server.getWorldPath(LevelResource.ROOT).resolve(RESTART_FILE), NbtAccounter.unlimitedHeap());
                } catch (final java.io.IOException unreadable) {
                    check("the saved snapshot reads back", false, "");
                    finish();
                    return;
                }
                savedFacts = facts;
                checkEq("the ledger has the same entries after the restart", ledger.count(colonyId), facts.getInt("entries"));
                checkEq("the half-done building still has its four", size(h2), 4);
                invariant("after the restart");
                check("the window is open after the restart", DecorPhase.windowOpen(CalendarManager.current()), "");
            }
        });
        step(new Step("restart-load: go on, nothing twice, then take down", 40, true) {
            @Override
            boolean tick() {
                return t >= 20;
            }

            @Override
            void end(final boolean done) {
                final Set<BlockPos> before = new HashSet<>(ledger.entries(colonyId).keySet());
                final Map<BlockPos, BlockState> worldBefore = new HashMap<>();
                for (final BlockPos pos : before) {
                    worldBefore.put(pos, level.getBlockState(pos));
                }
                final CountingStock plenty = new CountingStock(1000);
                final DecorWork.Outcome more = DecorWork.decorate(level, colonyId, DecorWork.Target.building(h2), 3, plenty, day(), ledger);
                final Set<BlockPos> after = new HashSet<>(ledger.entries(colonyId).keySet());
                final Set<BlockPos> added = new HashSet<>(after);
                added.removeAll(before);
                boolean untouched = true;
                for (final BlockPos pos : before) {
                    untouched &= level.getBlockState(pos) == worldBefore.get(pos);
                }
                check("decorating goes on where it stopped: the open cells only, no cell placed twice", more.placed() == savedFacts.getInt("open")
                        && added.size() == more.placed() && after.containsAll(before) && untouched && size(h2) == 12,
                        more.placed() + " placed, " + added.size() + " new entries, " + size(h2) + " on the building");
                checkEq("items used equal the new paid entries", plenty.taken, added.size());
                invariant("after going on");
                // taken down with decor remove: this colony has no Decorator's hut, so the items of what comes down
                // are lost, and the answer says how many (one paid item per entry that still stands as recorded)
                int paid = 0;
                for (final Map.Entry<BlockPos, DecorLedger.Entry> e : ledger.entries(colonyId).entrySet()) {
                    if (e.getValue().paid() && level.getBlockState(e.getKey()) == e.getValue().state() && DecorPlacer.itemOf(e.getValue().state()) != null) {
                        paid++;
                    }
                }
                final int entriesBefore = ledger.count(colonyId);
                final List<Component> said = new ArrayList<>();
                final CommandSource listener = new CommandSource() {
                    @Override
                    public void sendSystemMessage(final Component message) {
                        said.add(message);
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
                };
                int result;
                try {
                    result = server.getCommands().getDispatcher().execute("colonyseasons decor remove " + colonyId,
                            server.createCommandSourceStack().withSource(listener));
                } catch (final CommandSyntaxException denied) {
                    result = -1;
                }
                String lostSaid = null;
                for (final Component message : said) {
                    if (message.getContents() instanceof TranslatableContents tc && tc.getKey().equals("com.colony_seasons.command.decor.remove.lost")
                            && tc.getArgs().length > 0) {
                        lostSaid = String.valueOf(tc.getArgs()[0]);
                    }
                }
                check("decor remove in a colony with no Decorator's hut says how many items were lost", result > 0 && paid > 0
                        && String.valueOf(paid).equals(lostSaid), "said " + lostSaid + ", " + paid + " paid entries");
                int differ = 0;
                String first = "";
                for (final Tag t : savedFacts.getList("snapshot", Tag.TAG_COMPOUND)) {
                    final CompoundTag one = (CompoundTag) t;
                    final BlockPos pos = BlockPos.of(one.getLong("pos"));
                    final BlockState want = NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK), one.getCompound("state"));
                    if (level.getBlockState(pos) != want) {
                        if (differ++ == 0) {
                            first = pos.toShortString() + " " + level.getBlockState(pos) + " vs " + want;
                        }
                    }
                }
                check("taking down after the restart gives back the snapshot saved before decorating", differ == 0 && ledger.count(colonyId) == 0,
                        entriesBefore + " entries before, " + differ + " cells differ " + first);
            }
        });
    }

    private CompoundTag savedFacts;
}
