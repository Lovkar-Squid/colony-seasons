package me.lovkar.seasons.cswand;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.tileentities.AbstractTileEntityColonyBuilding;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.netty.buffer.Unpooled;
import me.lovkar.seasons.SeasonsConfig;
import me.lovkar.seasons.block.LanternPostBlock;
import me.lovkar.seasons.block.Lights;
import me.lovkar.seasons.block.SeasonsBlocks;
import me.lovkar.seasons.block.SnowmanBlock;
import me.lovkar.seasons.block.StreetLightsBlock;
import me.lovkar.seasons.calendar.CalendarManager;
import me.lovkar.seasons.calendar.DateSource;
import me.lovkar.seasons.calendar.Season;
import me.lovkar.seasons.colony.BuildingDecorator;
import me.lovkar.seasons.colony.DecoratorModules;
import me.lovkar.seasons.colony.DecoratorSetup;
import me.lovkar.seasons.colony.JobDecorator;
import me.lovkar.seasons.decor.DecorEvents;
import me.lovkar.seasons.decor.DecorKinds;
import me.lovkar.seasons.decor.DecorLedger;
import me.lovkar.seasons.decor.DecorPhase;
import me.lovkar.seasons.decor.DecorPlacer;
import me.lovkar.seasons.decor.DecorWork;
import me.lovkar.seasons.decor.DoorFinder;
import me.lovkar.seasons.decor.StreetLights;
import me.lovkar.seasons.decor.StreetMarks;
import me.lovkar.seasons.decor.StreetPieces;
import me.lovkar.seasons.decor.StreetWork;
import me.lovkar.seasons.item.DecoratorWandItem;
import me.lovkar.seasons.item.WandSync;
import me.lovkar.seasons.network.StreetMarksPayload;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.ModConfigSpec;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Headless check of the Decorator's wand (0.2.0, chunk Q11) on a real colony: a town hall, the Decorator's Workshop, a
 * house and an open field plot, a three-wide dirt-path street with fences, a wall and posts beside it. Modes (the rig
 * writes the word to probe-mode.txt):
 * <ul>
 *   <li>none (the street lights off, the default): marking all five types with the wand (a garland by two clicks), every
 *       refusal with its key, the cap, a body in a cell no reason to refuse; the marks' packet; a forced window: the
 *       order (the town hall, the marks, the other buildings), every piece up in its own group by the exact state, one
 *       slot of the day's budget for all marks, a body in a snowman's cell and in a post's upper half; the ledger's file
 *       with marks and an unknown mark type; a dirt path under a snowman and a post after a neighbour update; a sweep that
 *       removes nothing; tending; a season change that swaps only the planter; a mark removed mid-window; decor list and
 *       decor remove; a colonist in a mark's cell moved out and the mark up the same visit (Q7, H28); the Decorator's own AI decorating and taking down after the window; the sweep with no Decorator;
 *       no street lights although fences stand beside the path;</li>
 *   <li>{@code lights} (rigconfig-lights: {@code decorator.streetLights = true}): only thin {@code street_lights} on the
 *       fences and the wall beside the path (every third post), none on a fence away from paths, on a column of four or
 *       in a building's box; a state with collision refused for the lights' owner; the lights kind off; the switch off
 *       and the sweep; taken down after the window;</li>
 *   <li>{@code restart-save} then {@code restart-load} (tools/restart.sh): marks and their pieces before, the same marks
 *       and entries after, no cell placed twice, then the take-down.</li>
 * </ul>
 * Prints "[cswand] CHECK ...: OK|FAIL" lines and "[cswand] RESULT cswand: PASS|FAIL", then halts. The words "error" and
 * "exception" stay out of the details.
 */
@Mod("colonyseasonswand")
public class CsWand {
    private static final Logger LOGGER = LoggerFactory.getLogger("cswand");
    private static final int SETUP_TICK = 60;
    private static final long WATCHDOG_SECONDS = 560;
    static final LocalDate DECOR_DAY = LocalDate.of(2026, 12, 10);
    static final LocalDate AFTER = LocalDate.of(2027, 1, 10);
    private static final int MORNING = 1000;
    private static final String RESTART_FILE = "cswand-restart.nbt";

    private int ticks;
    private boolean failed;
    private boolean finished;
    private final String mode;
    private long setupNanos;

    private MinecraftServer server;
    private ServerLevel level;
    private FakePlayer owner;
    private FakePlayer stranger;
    private IColony colony;
    private int colonyId;
    private int ground;
    private DecorLedger ledger;

    private IBuilding townHall;
    private BuildingDecorator hut;
    private IBuilding house;
    private IBuilding field;
    private ICitizenData worker;

    private final List<Step> steps = new ArrayList<>();
    private int stepIndex;

    public CsWand() {
        mode = readMode();
        CalendarManager.setDateSource(DateSource.fixed(DECOR_DAY));
        NeoForge.EVENT_BUS.addListener(this::onTick);
    }

    // ================================================================ logging and the run

    private void check(final String what, final boolean ok, final String detail) {
        LOGGER.info("[cswand] CHECK {}: {} {}", what, ok ? "OK" : "FAIL", detail);
        failed |= !ok;
    }

    private void checkEq(final String what, final Object actual, final Object expected) {
        check(what, Objects.equals(actual, expected), "got " + actual + (Objects.equals(actual, expected) ? "" : ", wanted " + expected));
    }

    private static void note(final String text, final Object... args) {
        LOGGER.info("[cswand] " + text, args);
    }

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
        LOGGER.info("[cswand] RESULT cswand: {}", failed ? "FAIL" : "PASS");
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

    /** A step that does its work in one tick. */
    private void now(final String name, final Runnable work) {
        step(new Step(name, 1) {
            @Override
            void start() {
                work.run();
            }
        });
    }

    private void plan() {
        switch (mode) {
            case "" -> {
                planSetup();
                planMarking();
                planWindow();
                planAfter();
            }
            case "lights" -> {
                planSetup();
                planLights();
            }
            case "restart-save" -> {
                planSetup();
                planRestartSave();
            }
            case "restart-load" -> planRestartLoad();
            default -> {
                check("known mode", false, "'" + mode + "'");
                finish();
            }
        }
    }

    // ================================================================ world, colony and street

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

    private void nextMorning() {
        final long day = level.getDayTime() / 24000L;
        level.setDayTime((day + 1) * 24000L + MORNING);
    }

    private long day() {
        return DecorWork.day(level);
    }

    private void forceChunks() {
        for (int cx = -6; cx <= 6; cx++) {
            for (int cz = -6; cz <= 6; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }
    }

    private void buildColony() {
        ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, 2, 2) - 1;
        final BlockPos center = new BlockPos(2, ground + 1, 2);
        forceChunks();
        owner = FakePlayerFactory.getMinecraft(level);
        owner.setPos(center.getX() + 0.5, center.getY(), center.getZ() + 0.5);
        owner.setYRot(0);
        colony = IColonyManager.getInstance().createColony(level, center, owner, "Seasons Wand Test", "Medieval Oak");
        colonyId = colony == null ? -1 : colony.getID();
        stranger = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("00000000-0000-4000-8000-0000000000aa"), "wand_stranger"));
        check("a colony was made", colony != null, "id " + colonyId + " at " + center + ", ground y " + ground);
    }

    private void set(final BlockPos pos, final BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }

    private BlockPos at(final int x, final int dy, final int z) {
        return new BlockPos(x, ground + dy, z);
    }

    /** A stone-brick house: floor at ground, walls up to ground + 5, roof at ground + 6; doors in the south wall. */
    private DoorFinder.Box houseAt(final int x0, final int z0, final int w, final int d, final int... doors) {
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
            final BlockState lower = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH)
                    .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER).setValue(DoorBlock.HINGE, DoorHingeSide.LEFT);
            set(new BlockPos(doors[i], y0 + 1, zs), lower);
            set(new BlockPos(doors[i], y0 + 2, zs), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        }
        return new DoorFinder.Box(new BlockPos(x0, y0, z0), new BlockPos(x0 + w - 1, y0 + 6, z0 + d - 1),
                new BlockPos(x0 + w / 2, y0 + 1, z0 + d / 2));
    }

    private IBuilding register(final String hutId, final DoorFinder.Box box, final int buildingLevel) {
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
        building.markDirty();
        return building;
    }

    // ---- the street (cells at ground + 1; the street's dirt path at ground)
    private BlockPos sa;
    private BlockPos sb;
    private BlockPos p1;
    private BlockPos p2;
    private BlockPos p3;
    private BlockPos pl;
    private BlockPos markerAt;
    private final List<BlockPos> pathFences = new ArrayList<>();
    private final List<BlockPos> wallBlocks = new ArrayList<>();
    private BlockPos tallFence;
    private final List<BlockPos> farFences = new ArrayList<>();
    private BlockPos fieldFence;

    /** The street's layout: flat grass, a three-wide dirt path along x, fences, a wall, a tall fence, a far fence, the field plot. */
    private void buildStreet() {
        for (int x = -24; x <= 24; x++) {
            for (int z = -42; z <= -3; z++) {
                set(at(x, -2, z), Blocks.DIRT.defaultBlockState());
                set(at(x, -1, z), Blocks.DIRT.defaultBlockState());
                set(at(x, 0, z), Blocks.GRASS_BLOCK.defaultBlockState());
                for (int dy = 1; dy <= 8; dy++) {
                    set(at(x, dy, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
        for (int x = -22; x <= 14; x++) {
            for (int z = -20; z <= -18; z++) {
                set(at(x, 0, z), Blocks.DIRT_PATH.defaultBlockState());
            }
        }
        for (int x = 10; x <= 14; x++) {
            set(at(x, 0, -22), Blocks.DIRT_PATH.defaultBlockState());
        }
        // fences on the north side of the street, a wall on its south side, a column of four, fences away from any path
        for (int x = -22; x <= -14; x++) {
            pathFences.add(at(x, 1, -21));
        }
        for (int x = 0; x <= 6; x++) {
            wallBlocks.add(at(x, 1, -17));
        }
        tallFence = at(-12, 1, -21);
        for (int x = -12; x <= -6; x++) {
            farFences.add(at(x, 1, -27));
        }
        fieldFence = at(11, 1, -23);
        for (final BlockPos f : pathFences) {
            level.setBlock(f, Blocks.OAK_FENCE.defaultBlockState(), Block.UPDATE_ALL);
        }
        for (final BlockPos w : wallBlocks) {
            level.setBlock(w, Blocks.COBBLESTONE_WALL.defaultBlockState(), Block.UPDATE_ALL);
        }
        for (int dy = 0; dy < 4; dy++) {
            level.setBlock(tallFence.above(dy), Blocks.OAK_FENCE.defaultBlockState(), Block.UPDATE_ALL);
        }
        for (final BlockPos f : farFences) {
            level.setBlock(f, Blocks.OAK_FENCE.defaultBlockState(), Block.UPDATE_ALL);
        }
        level.setBlock(fieldFence, Blocks.OAK_FENCE.defaultBlockState(), Block.UPDATE_ALL);
        sa = at(-16, 1, -19);
        sb = at(-12, 1, -16);
        p1 = at(-6, 1, -22);
        p2 = at(-6, 1, -16);
        p3 = at(2, 1, -19);
        pl = at(8, 1, -16);
        markerAt = at(-20, 1, -30);
    }

    private void buildings() {
        townHall = register("minecolonies:blockhuttownhall", houseAt(-3, -1, 11, 7, 2), 1);
        final IBuilding decorator = register(BuiltInRegistries.BLOCK.getKey(DecoratorSetup.HUT.get()).toString(), houseAt(12, -1, 11, 7, 17), 1);
        hut = decorator instanceof BuildingDecorator d ? d : null;
        house = register("minecolonies:blockhutlumberjack", houseAt(-40, 16, 11, 7, -35), 1);
        // an open plot (a field): its box holds a fence beside a path, and no walls
        field = register("minecolonies:blockhutstonemason", new DoorFinder.Box(at(9, 0, -25), at(15, 4, -22), at(13, 1, -25)), 1);
        check("the buildings are registered", townHall != null && hut != null && house != null && field != null,
                "town hall " + (townHall != null) + ", Workshop " + (hut != null) + ", house " + (house != null) + ", field " + (field != null));
    }

    private void planSetup() {
        step(new Step("setup: the colony, its buildings and the street", 80, true) {
            @Override
            void start() {
                setDate(DECOR_DAY);
                quietWorld();
                buildColony();
                if (colony == null) {
                    finish();
                    return;
                }
                buildStreet();
                buildings();
                check("the decoration window is open", DecorPhase.windowOpen(CalendarManager.current()), String.valueOf(CalendarManager.current().festivals()));
                check("the colony manager counts as loaded (the sweep may run)", DecorEvents.managerLoaded(), "");
                checkEq("decorator.streetLights", SeasonsConfig.streetLights(), "lights".equals(mode));
                checkEq("the street lights are off by default", SeasonsConfig.DEFAULT_STREET_LIGHTS, false);
                boolean claimed = true;
                for (final BlockPos p : List.of(sa, sb, p1, p2, p3, pl, markerAt, at(-22, 1, -40), at(10, 1, -40), at(14, 1, -12))) {
                    claimed &= DecorPlacer.colonyAt(level, p) == colonyId;
                }
                check("the street area is in the colony's claim", claimed, "");
            }

            @Override
            boolean tick() {
                return t >= 40 && !level.getChunkSource().getLightEngine().hasLightWork();
            }
        });
    }

    // ================================================================ helpers

    private ItemStack wand(final StreetMarks.Type type) {
        final ItemStack stack = new ItemStack(SeasonsBlocks.DECORATORS_WAND.get());
        DecoratorWandItem.setMode(stack, type);
        return stack;
    }

    /** A wand click on the top of the block under {@code cell} (that marks {@code cell}). */
    private String clickOn(final FakePlayer who, final ItemStack wand, final BlockPos cell) {
        return DecoratorWandItem.click(level, who, wand, cell.below(), Direction.UP, false);
    }

    private DecorLedger.Owner street(final BlockPos mark) {
        return DecorLedger.Owner.street(mark);
    }

    private StreetMarks.Mark mark(final BlockPos pos) {
        return ledger.markAt(colonyId, pos);
    }

    private StreetMarks.Mark garlandMark() {
        for (final StreetMarks.Mark m : ledger.marks(colonyId)) {
            if (m.type() == StreetMarks.Type.GARLAND) {
                return m;
            }
        }
        return null;
    }

    private Season season() {
        final CalendarManager.Snapshot now = CalendarManager.current();
        return now == null || now.season() == null ? null : now.season().season();
    }

    /**
     * "" when every cell of the mark holds the piece the mark wants (unlit, facing as marked), listed exactly in the mark's
     * own group, unpaid; else what differs.
     */
    private String up(final StreetMarks.Mark m) {
        if (m == null) {
            return "no mark";
        }
        final List<String> wrong = new ArrayList<>();
        final DecorLedger.GroupView g = ledger.group(colonyId, street(m.pos()));
        final List<DecorPlacer.Slot> slots = StreetPieces.slots(m, season());
        for (final DecorPlacer.Slot slot : slots) {
            final BlockState want = DecorPlacer.normalise(slot.state(), slot.pos());
            final BlockState now = level.getBlockState(slot.pos());
            final DecorLedger.Entry e = g == null ? null : g.entries().get(slot.pos());
            if (now.getBlock() != want.getBlock() || (want.hasProperty(SnowmanBlock.FACING) && now.getValue(SnowmanBlock.FACING) != want.getValue(SnowmanBlock.FACING))) {
                wrong.add(slot.pos().toShortString() + " is " + now);
            }
            if (e == null || e.state() != now || e.paid()) {
                wrong.add(slot.pos().toShortString() + " listed " + (e == null ? "nothing" : e.state() + (e.paid() ? " paid" : "")));
            }
            if (now.hasProperty(Lights.LIT) && now.getValue(Lights.LIT)) {
                wrong.add(slot.pos().toShortString() + " lit");
            }
        }
        if (g != null && g.entries().size() != slots.size()) {
            wrong.add(g.entries().size() + " listed for " + slots.size() + " cells");
        }
        return wrong.isEmpty() ? "" : wrong.toString();
    }

    /** True when every cell of the mark is air and the mark's group is gone. */
    private boolean down(final StreetMarks.Mark m) {
        boolean air = true;
        for (final BlockPos cell : StreetPieces.cells(m)) {
            air &= level.getBlockState(cell).isAir();
        }
        return air && ledger.group(colonyId, street(m.pos())) == null;
    }

    private void invariant(final String where) {
        int bad = 0;
        String first = "";
        final Map<BlockPos, DecorLedger.Entry> all = ledger.entries(colonyId);
        for (final Map.Entry<BlockPos, DecorLedger.Entry> e : all.entrySet()) {
            final BlockState now = level.getBlockState(e.getKey());
            if (now != e.getValue().state() && bad++ == 0) {
                first = e.getKey().toShortString() + " listed " + e.getValue().state() + ", world " + now;
            }
        }
        check("every entry equals the world (" + where + ")", bad == 0, all.size() + " entries" + (bad > 0 ? ", " + bad + " differ, first " + first : ""));
    }

    /** Entries of the colony's STREET groups. */
    private int streetEntries() {
        int n = 0;
        for (final DecorLedger.GroupView g : ledger.groups(colonyId)) {
            if (g.owner().kind() == DecorLedger.Kind.STREET) {
                n += g.size();
            }
        }
        return n;
    }

    /**
     * The decorating visits a Decorator makes while the next target is a mark (at most {@code max}); returns the visits
     * made. A mark whose cell cannot be filled now is offered again up to three times a game day, then left for the day.
     */
    private int streetVisits(final int max) {
        int visits = 0;
        while (visits < max) {
            final DecorWork.Target next = DecorWork.nextToDecorate(level, colony, 1, hut.getPosition(), ledger, DecorPlacer.Stock.FREE, day());
            if (next == null || next.owner().kind() != DecorLedger.Kind.STREET) {
                break;
            }
            final DecorWork.Outcome o = DecorWork.decorate(level, colonyId, next, 1, DecorPlacer.Stock.FREE, day(), ledger);
            note("street visit at {}: {}", next.owner().pos().toShortString(), o.status());
            visits++;
        }
        return visits;
    }

    /** Decorating visits as the Decorator makes them, buildings and marks, while there is a target (at most {@code max}). */
    private int decorateAll(final int max) {
        int visits = 0;
        DecorWork.Target next;
        while (visits < max && (next = DecorWork.nextToDecorate(level, colony, 1, hut.getPosition(), ledger, DecorPlacer.Stock.FREE, day())) != null) {
            DecorWork.decorate(level, colonyId, next, 1, DecorPlacer.Stock.FREE, day(), ledger);
            visits++;
        }
        return visits;
    }

    /** Tending visits while there is a group to tend today (at most {@code max}). */
    private int tendAll(final int max) {
        int visits = 0;
        DecorWork.Target next;
        while (visits < max && (next = DecorWork.nextToTend(level, colony, hut.getPosition(), ledger, day())) != null) {
            final DecorWork.Outcome o = DecorWork.tend(level, colonyId, next, 1, DecorPlacer.Stock.FREE, day(), ledger);
            note("tending at {} {}: {}", next.owner().kind(), next.owner().pos().toShortString(), o.status());
            visits++;
        }
        return visits;
    }

    /**
     * Tending visits as the Decorator makes them, with {@code stock}, while there is a group to tend today (at most
     * {@code max}), counted per group in {@code at}; returns the visits made ({@code max} when it never ran out).
     */
    private int tendCounting(final Map<DecorLedger.Owner, Integer> at, final int max, final int hutLevel, final DecorPlacer.Stock stock) {
        int visits = 0;
        DecorWork.Target next;
        while (visits < max && (next = DecorWork.nextToTend(level, colony, hut.getPosition(), ledger, day())) != null) {
            final DecorWork.Outcome o = DecorWork.tend(level, colonyId, next, hutLevel, stock, day(), ledger);
            if (visits < 12) {
                note("tending at {} {}: {}", next.owner().kind(), next.owner().pos().toShortString(), o.status());
            }
            at.merge(next.owner(), 1, Integer::sum);
            visits++;
        }
        return visits;
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

    /** Runs a command; the translatable keys it said (empty when it was refused). */
    private List<String> command(final String line) {
        final List<String> said = new ArrayList<>();
        final CommandSource listener = new CommandSource() {
            @Override
            public void sendSystemMessage(final Component message) {
                collect(message, said);
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
        try {
            server.getCommands().getDispatcher().execute(line, server.createCommandSourceStack().withSource(listener));
        } catch (final CommandSyntaxException denied) {
            said.clear();
        }
        return said;
    }

    private static void collect(final Component message, final List<String> said) {
        if (message.getContents() instanceof TranslatableContents tc) {
            said.add(tc.getKey());
            for (final Object arg : tc.getArgs()) {
                if (arg instanceof Component c) {
                    collect(c, said);
                }
            }
        }
        for (final Component sibling : message.getSiblings()) {
            collect(sibling, said);
        }
    }

    // ================================================================ marking

    private void planMarking() {
        now("marking: the five types with the wand, a garland by two clicks", () -> {
            final ArmorStand body = new ArmorStand(level, sb.getX() + 0.5, sb.getY(), sb.getZ() + 0.5);
            level.addFreshEntity(body);
            stands.add(body);
            checkEq("snowman A marked with the wand (a body standing in the cell is no reason to refuse)", clickOn(owner, wand(StreetMarks.Type.SNOWMAN_A), sa),
                    "com.colony_seasons.wand.marked");
            checkEq("snowman B marked with a body standing in its cell", clickOn(owner, wand(StreetMarks.Type.SNOWMAN_B), sb), "com.colony_seasons.wand.marked");
            checkEq("lantern post 1 marked", clickOn(owner, wand(StreetMarks.Type.POST), p1), "com.colony_seasons.wand.marked");
            checkEq("lantern post 2 marked", clickOn(owner, wand(StreetMarks.Type.POST), p2), "com.colony_seasons.wand.marked");
            checkEq("lantern post 3 marked (on the dirt path)", clickOn(owner, wand(StreetMarks.Type.POST), p3), "com.colony_seasons.wand.marked");
            checkEq("planter marked", clickOn(owner, wand(StreetMarks.Type.PLANTER), pl), "com.colony_seasons.wand.marked");
            final ItemStack garland = wand(StreetMarks.Type.GARLAND);
            checkEq("garland: a click on a snowman is no post", clickOn(owner, garland, sa), "com.colony_seasons.wand.garland.not_post");
            checkEq("garland: the first click picks post 1 (its ground)", DecoratorWandItem.click(level, owner, garland, p1.below(), Direction.UP, false),
                    "com.colony_seasons.wand.garland.first");
            checkEq("garland: the second click on post 2 marks it", DecoratorWandItem.click(level, owner, garland, p2.below(), Direction.UP, false),
                    "com.colony_seasons.wand.marked");
            final StreetMarks.Mark g = garlandMark();
            check("the garland spans the street at the posts' y + 1, between their upper halves", g != null && g.pos().equals(p1.above().south())
                    && g.to().equals(p2.above().north()) && g.facing() == Direction.SOUTH && StreetPieces.cells(g).size() == 5,
                    g == null ? "none" : StreetMarks.describe(g));
            checkEq("seven marks (the garland is one)", ledger.marks(colonyId).size(), 7);
            final StreetMarks.Mark a = mark(sa);
            check("a snowman faces the player who marked it", a != null && a.facing() == owner.getDirection().getOpposite(), a == null ? "none" : StreetMarks.describe(a));
            check("nothing is placed by marking", streetEntries() == 0 && level.getBlockState(sa).isAir() && level.getBlockState(p1.above()).isAir(), "");
            checkEq("the wand's mode cycles from snowman A to the planter and round",
                    List.of(StreetMarks.Type.SNOWMAN_A.next(), StreetMarks.Type.PLANTER.next()), List.of(StreetMarks.Type.SNOWMAN_B, StreetMarks.Type.SNOWMAN_A));
            checkEq("a new wand is in snowman A's mode", DecoratorWandItem.mode(new ItemStack(SeasonsBlocks.DECORATORS_WAND.get())), StreetMarks.Type.SNOWMAN_A);
        });
        now("marking: every refusal says why", this::refusals);
        now("marking: the cap of 64 marks", this::cap);
        now("marking: the marks' packet", this::packet);
    }

    private final List<ArmorStand> stands = new ArrayList<>();

    private String add(final FakePlayer who, final StreetMarks.Type type, final BlockPos cell) {
        final String why = StreetMarks.add(level, who, new StreetMarks.Mark(cell, type, Direction.NORTH, null));
        return why == null ? "marked" : why;
    }

    private void refusals() {
        // outside the claim: the first loaded cell east of the street that no colony claims
        BlockPos outside = null;
        for (int x = 16; x < 100 && outside == null; x++) {
            final BlockPos p = at(x, 1, -19);
            if (level.hasChunkAt(p) && DecorPlacer.colonyAt(level, p) == DecorPlacer.NO_COLONY) {
                outside = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, p);
            }
        }
        check("an unclaimed loaded cell is found", outside != null, String.valueOf(outside));
        if (outside != null) {
            checkEq("refused: outside the claim", add(owner, StreetMarks.Type.SNOWMAN_A, outside), "com.colony_seasons.wand.refused.claim");
        }
        checkEq("refused: a player without the right to manage huts", add(stranger, StreetMarks.Type.SNOWMAN_A, at(-20, 1, -14)),
                "com.colony_seasons.wand.refused.permission");
        checkEq("refused by the wand too, for him", clickOn(stranger, wand(StreetMarks.Type.SNOWMAN_A), at(-20, 1, -14)), "com.colony_seasons.wand.refused.permission");
        level.setBlock(at(-20, 1, -14), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        checkEq("refused: a cell that is not air", add(owner, StreetMarks.Type.SNOWMAN_A, at(-20, 1, -14)), "com.colony_seasons.wand.refused.occupied");
        level.setBlock(at(-20, 1, -14), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        // a cell the ledger lists (an air cell whose entry a vanilla piece broken by hand leaves behind)
        final BlockPos listed = at(-18, 1, -14);
        ledger.record(colonyId, DecorLedger.Owner.building(townHall.getPosition()), "", 1, listed, Blocks.SPRUCE_LEAVES.defaultBlockState(), false);
        checkEq("refused: a cell the ledger lists", add(owner, StreetMarks.Type.SNOWMAN_A, listed), "com.colony_seasons.wand.refused.listed");
        ledger.forget(listed);
        checkEq("refused: a cell of another mark (a snowman)", add(owner, StreetMarks.Type.PLANTER, sa), "com.colony_seasons.wand.refused.taken");
        checkEq("refused: a cell of another mark (a post's upper half)", add(owner, StreetMarks.Type.SNOWMAN_B, p3.above()), "com.colony_seasons.wand.refused.taken");
        checkEq("refused: a cell of another mark (the garland)", add(owner, StreetMarks.Type.PLANTER, garlandMark().pos().south()), "com.colony_seasons.wand.refused.taken");
        checkEq("refused: inside a building's box (the open field plot)", add(owner, StreetMarks.Type.SNOWMAN_A, at(13, 1, -24)), "com.colony_seasons.wand.refused.building");
        checkEq("refused: inside the town hall", add(owner, StreetMarks.Type.SNOWMAN_A, at(0, 2, 2)), "com.colony_seasons.wand.refused.building");
        ledger.registerMarker(colonyId, markerAt);
        checkEq("refused: within 5 blocks of the festival marker", add(owner, StreetMarks.Type.SNOWMAN_A, markerAt.offset(3, 0, -2)), "com.colony_seasons.wand.refused.marker");
        checkEq("not refused 6 blocks from the marker (only checked, the mark is taken off at once)", add(owner, StreetMarks.Type.SNOWMAN_A, markerAt.offset(6, 0, 0)), "marked");
        StreetMarks.remove(level, colonyId, markerAt.offset(6, 0, 0));
        ledger.unregisterMarker(colonyId, markerAt);
        checkEq("refused: a snowman over air", add(owner, StreetMarks.Type.SNOWMAN_A, at(-20, 4, -36)), "com.colony_seasons.wand.refused.ground");
        checkEq("refused: a post on a fence (its collision reaches above the cell)", add(owner, StreetMarks.Type.POST, pathFences.get(0).above()),
                "com.colony_seasons.wand.refused.ground");
        checkEq("refused: a planter on a dirt path (no full top)", add(owner, StreetMarks.Type.PLANTER, at(-10, 1, -19)), "com.colony_seasons.wand.refused.planter");
        checkEq("not refused: a snowman on a dirt path (15 px count as ground)", StreetMarks.ground(level, sa.below()), true);
        checkEq("refused: a garland from a snowman", StreetMarks.garland(mark(sb), mark(p2)).refusal(), "com.colony_seasons.wand.refused.garland");
        checkEq("refused: a garland between two posts not in a line", StreetMarks.garland(mark(p1), mark(p3)).refusal(), "com.colony_seasons.wand.refused.garland");
        final StreetMarks.Mark far = new StreetMarks.Mark(at(-6, 1, -34), StreetMarks.Type.POST, Direction.NORTH, null);
        checkEq("a fourth post, 12 blocks north of post 1", StreetMarks.add(level, owner, far), null);
        checkEq("refused: a garland across 11 cells (9 at most)", StreetMarks.garland(mark(p1), far).refusal(), "com.colony_seasons.wand.refused.garland");
        StreetMarks.remove(level, colonyId, far.pos());
        checkEq("still seven marks after the refusals", ledger.marks(colonyId).size(), 7);
    }

    private void cap() {
        final List<BlockPos> extra = new ArrayList<>();
        int added = 0;
        outer:
        for (int z = -40; z <= -32; z += 2) {
            for (int x = -22; x <= 10; x += 2) {
                if (ledger.marks(colonyId).size() >= StreetMarks.MAX_MARKS) {
                    break outer;
                }
                final BlockPos cell = at(x, 1, z);
                if ("marked".equals(add(owner, StreetMarks.Type.SNOWMAN_A, cell))) {
                    extra.add(cell);
                    added++;
                }
            }
        }
        checkEq("the colony fills up to 64 marks", ledger.marks(colonyId).size(), StreetMarks.MAX_MARKS);
        checkEq("refused: the 65th mark", add(owner, StreetMarks.Type.SNOWMAN_A, at(-21, 1, -41)), "com.colony_seasons.wand.refused.cap");
        for (final BlockPos cell : extra) {
            StreetMarks.remove(level, colonyId, cell);
        }
        checkEq("the extra marks removed again (" + added + ")", ledger.marks(colonyId).size(), 7);
    }

    private void packet() {
        final FakePlayer holder = owner;
        holder.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(SeasonsBlocks.DECORATORS_WAND.get()));
        holder.setPos(sa.getX() + 0.5, sa.getY(), sa.getZ() + 3.5);
        final StreetMarksPayload first = WandSync.update(holder);
        check("a wand's holder in the colony is sent its marks", first != null && first.colony() == colonyId && first.entries().size() == 7,
                first == null ? "nothing" : first.colony() + ", " + first.entries().size() + " entries");
        check("and nothing again while nothing changed", WandSync.update(holder) == null, "");
        final BlockPos cell = at(-20, 1, -14);
        add(owner, StreetMarks.Type.SNOWMAN_A, cell);
        final StreetMarksPayload second = WandSync.update(holder);
        check("a new mark: the list again", second != null && second.entries().size() == 8, second == null ? "nothing" : second.entries().size() + " entries");
        StreetMarks.remove(level, colonyId, cell);
        final StreetMarksPayload third = WandSync.update(holder);
        check("a mark removed: the list again", third != null && third.entries().size() == 7, third == null ? "nothing" : third.entries().size() + " entries");
        holder.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        add(owner, StreetMarks.Type.SNOWMAN_A, cell);
        check("no wand in hand: nothing is sent", WandSync.update(holder) == null, "");
        StreetMarks.remove(level, colonyId, cell);
        holder.setPos(2.5, ground + 1, 2.5);
        // the codec: a round trip, and a list longer than a colony can have is cut on reading
        final RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        StreetMarksPayload.STREAM_CODEC.encode(buf, third);
        final StreetMarksPayload back = StreetMarksPayload.STREAM_CODEC.decode(buf);
        checkEq("the packet reads back as written", back, third);
        check("nothing is left in the buffer", buf.readableBytes() == 0, buf.readableBytes() + " bytes");
        final RegistryFriendlyByteBuf big = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        big.writeVarInt(colonyId);
        big.writeVarInt(100);
        for (int i = 0; i < 100; i++) {
            big.writeBlockPos(at(i, 1, 0));
            big.writeUtf("snowman_a", StreetMarksPayload.MAX_TYPE);
            big.writeBoolean(false);
        }
        final StreetMarksPayload clamped = StreetMarksPayload.STREAM_CODEC.decode(big);
        check("a list of 100 is read as 64 and the buffer is read to its end", clamped.entries().size() == StreetMarks.MAX_MARKS && big.readableBytes() == 0,
                clamped.entries().size() + " entries, " + big.readableBytes() + " bytes left");
    }

    // ================================================================ the forced window

    private Map<DecorLedger.Owner, String> before;

    private void planWindow() {
        now("window: the order, every piece up, one slot of the budget, a body in a cell", () -> {
            // a body standing in the upper half of post 3 (floating there): neither half may go up
            final ArmorStand high = new ArmorStand(level, p3.getX() + 0.5, p3.getY() + 1, p3.getZ() + 0.5);
            high.setNoGravity(true);
            level.addFreshEntity(high);
            stands.add(high);
            final DecorWork.Target first = DecorWork.nextToDecorate(level, colony, 1, hut.getPosition(), ledger, DecorPlacer.Stock.FREE, day());
            check("the town hall first", first != null && first.owner().equals(DecorLedger.Owner.building(townHall.getPosition())),
                    first == null ? "none" : first.owner().toString());
            if (first != null) {
                DecorWork.decorate(level, colonyId, first, 1, DecorPlacer.Stock.FREE, day(), ledger);
            }
            final int budget0 = ledger.budgetUsed(colonyId, day());
            final DecorWork.Target second = DecorWork.nextToDecorate(level, colony, 1, hut.getPosition(), ledger, DecorPlacer.Stock.FREE, day());
            check("then a mark (the square has no group here; the marks come before the other buildings)", second != null
                    && second.owner().kind() == DecorLedger.Kind.STREET, second == null ? "none" : second.owner().toString());
            // the first visit (at the planter) serves post 3 too: with a body in its upper cell no half of it may go up
            streetVisits(1);
            check("the first visit: a body in post 3's upper cell, and not even its lower half went up", level.getBlockState(p3).isAir()
                    && level.getBlockState(p3.above()).isAir() && ledger.group(colonyId, street(p3)) == null,
                    level.getBlockState(p3) + " / " + level.getBlockState(p3.above()));
            final int visits = 1 + streetVisits(11);
            note("{} street visits", visits);
            check("a body in a snowman's cell: that cell stays air, the snowman's group is not made", level.getBlockState(sb).isAir()
                    && ledger.group(colonyId, street(sb)) == null, String.valueOf(level.getBlockState(sb)));
            check("a body in a post's upper cell: neither half goes up", level.getBlockState(p3).isAir() && level.getBlockState(p3.above()).isAir()
                    && ledger.group(colonyId, street(p3)) == null, level.getBlockState(p3) + " / " + level.getBlockState(p3.above()));
            for (final BlockPos m : List.of(sa, p1, p2, pl)) {
                checkEq("the other marks went up: " + mark(m).type().id() + " at " + m.toShortString(), up(mark(m)), "");
            }
            checkEq("the garland went up between the two posts", up(garlandMark()), "");
            checkEq("the day's budget rose by exactly one for all the marks", ledger.budgetUsed(colonyId, day()) - budget0, 1);
            check("the marks' slot is the street's one", ledger.chargedOn(colonyId, DecorLedger.Owner.STREET_BUDGET, day()), "");
            check("the two marks with a body in them were left for today", DecorWork.nextToDecorate(level, colony, 1, hut.getPosition(), ledger,
                    DecorPlacer.Stock.FREE, day()) == null || DecorWork.nextToDecorate(level, colony, 1, hut.getPosition(), ledger, DecorPlacer.Stock.FREE,
                    day()).owner().kind() != DecorLedger.Kind.STREET, "");
            for (final ArmorStand s : stands) {
                s.discard();
            }
            stands.clear();
            nextMorning();
            final int budget1 = ledger.budgetUsed(colonyId, day());
            final int again = streetVisits(4);
            checkEq("the bodies gone: snowman B filled on a later visit (the next day)", up(mark(sb)), "");
            checkEq("the bodies gone: post 3 up whole", up(mark(p3)), "");
            check("in one or two visits", again >= 1 && again <= 2, again + " visits");
            checkEq("that day too one slot of the budget for the marks", ledger.budgetUsed(colonyId, day()) - budget1, 1);
            final DecorWork.Target after = DecorWork.nextToDecorate(level, colony, 1, hut.getPosition(), ledger, DecorPlacer.Stock.FREE, day());
            check("with every mark done the next target is a building", after != null && after.owner().kind() == DecorLedger.Kind.BUILDING,
                    after == null ? "none" : after.owner().toString());
            check("a snowman's piece has collision, the garland's none, nothing is solid", !level.getBlockState(sa).getCollisionShape(level, sa).isEmpty()
                    && level.getBlockState(garlandMark().pos()).getCollisionShape(level, garlandMark().pos()).isEmpty()
                    && !level.getBlockState(sa).isSolid() && !level.getBlockState(p1).isSolid(), "");
            check("a group per mark, keyed at the mark's first cell", ledger.groups(colonyId).stream().filter(g -> g.owner().kind() == DecorLedger.Kind.STREET).count() == 7, "");
            checkEq("decor list names a marked spot", command("colonyseasons decor list " + colonyId).contains("com.colony_seasons.command.decor.kind.street"), true);
            invariant("the street up");
            final Set<DecorLedger.Owner> street = new HashSet<>();
            before = new LinkedHashMap<>();
            for (final DecorLedger.GroupView g : ledger.groups(colonyId)) {
                if (g.owner().kind() == DecorLedger.Kind.STREET) {
                    street.add(g.owner());
                    before.put(g.owner(), String.valueOf(new TreeMap<>(g.entries())));
                }
            }
        });
        now("window: the ledger's file with marks", this::files);
        step(new Step("window: a dirt path under a snowman and a post stays a dirt path", 12) {
            BlockPos control;

            @Override
            void start() {
                // the control: a dirt path under a solid block turns to dirt after the same update
                control = at(-20, 0, -36);
                set(control, Blocks.DIRT_PATH.defaultBlockState());
                level.setBlock(control.above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                for (final BlockPos path : List.of(sa.below(), p3.below(), control)) {
                    level.neighborShapeChanged(Direction.UP, level.getBlockState(path.above()), path, path.above(), Block.UPDATE_ALL, 512);
                }
            }

            @Override
            boolean tick() {
                return t >= 10;
            }

            @Override
            void end(final boolean done) {
                check("the control turned to dirt under a solid block", !level.getBlockState(control).is(Blocks.DIRT_PATH), String.valueOf(level.getBlockState(control)));
                check("the dirt path under the snowman is still a dirt path", level.getBlockState(sa.below()).is(Blocks.DIRT_PATH), String.valueOf(level.getBlockState(sa.below())));
                check("the dirt path under post 3 is still a dirt path", level.getBlockState(p3.below()).is(Blocks.DIRT_PATH), String.valueOf(level.getBlockState(p3.below())));
                level.setBlock(control.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        });
        now("window: one sweep with all marks standing removes nothing", () -> {
            final int entries = ledger.count(colonyId);
            final int swept = DecorEvents.sweep(level);
            check("the sweep removed nothing", swept == 0 && ledger.count(colonyId) == entries, swept + " cells swept, " + entries + " -> " + ledger.count(colonyId));
        });
        now("window: the street lights stay off", () -> {
            check("no street lights group", ledger.group(colonyId, StreetLights.owner(colony)) == null, "");
            check("the lights find no target with the switch off", StreetLights.next(level, colony, ledger, DecorKinds.ALL, day()) == null, "");
            int lights = 0;
            for (final BlockPos f : pathFences) {
                lights += level.getBlockState(f.above()).is(SeasonsBlocks.STREET_LIGHTS.get()) ? 1 : 0;
            }
            for (final BlockPos w : wallBlocks) {
                lights += level.getBlockState(w.above()).is(SeasonsBlocks.STREET_LIGHTS.get()) ? 1 : 0;
            }
            check("no street_lights block on the fences and the wall beside the path", lights == 0, lights + " found");
        });
        now("window: tending puts a broken snowman back the next day", () -> {
            level.destroyBlock(sa, true, owner);
            check("a snowman broken by hand: forgotten, and nothing drops (it was free)", ledger.at(sa) == null
                    && level.getEntitiesOfClass(ItemEntity.class, new AABB(sa).inflate(2)).isEmpty(), "");
            streetVisits(3);
            check("the same day it is not put back (the mark is done)", level.getBlockState(sa).isAir(), String.valueOf(level.getBlockState(sa)));
            nextMorning();
            tendAll(12);
            checkEq("the next day's tending put it back", up(mark(sa)), "");
            invariant("after tending");
        });
        now("window: a finished mark its rule turns away is tended once a day, not on every call", () -> {
            // the ground under the finished snowman taken away by hand: the snowman stands (it needs no support), the rule refuses the spot
            final BlockPos ground = sa.below();
            final BlockState was = level.getBlockState(ground);
            level.setBlock(ground, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            check("the snowman still stands, listed, on no ground", level.getBlockState(sa).getBlock() instanceof SnowmanBlock && ledger.at(sa) != null
                    && !StreetMarks.ground(level, ground), String.valueOf(level.getBlockState(sa)));
            nextMorning();
            final Map<DecorLedger.Owner, Integer> at = new LinkedHashMap<>();
            final int max = 40;
            final int visits = tendCounting(at, max, 1, DecorPlacer.Stock.FREE);
            note("{} tending visits: {}", visits, at);
            check("the day's tending comes to an end", visits < max, visits + " visits, " + at.getOrDefault(street(sa), 0) + " at the snowman");
            check("the turned-away snowman was looked at once at most, and its day is noted",
                    at.getOrDefault(street(sa), 0) <= 1 && ledger.group(colonyId, street(sa)).tendedDay() == day(), at.getOrDefault(street(sa), 0) + " visits");
            checkEq("the snowman is as it was", up(mark(sa)), "");
            level.setBlock(ground, was, Block.UPDATE_ALL);
            invariant("after the ground came back");
        });
        now("window: a broken post half takes the garland down, then all come back", () -> {
            level.destroyBlock(p1, true, owner);
            check("post 1's lower half broken: both halves gone and forgotten", level.getBlockState(p1).isAir() && level.getBlockState(p1.above()).isAir()
                    && ledger.at(p1) == null && ledger.at(p1.above()) == null, "");
            nextMorning();
            // one tending visit at the garland: it serves the due posts within reach too, the garland last
            final DecorWork.Outcome o = DecorWork.tend(level, colonyId, StreetWork.target(level, garlandMark(), null), 1, DecorPlacer.Stock.FREE, day(), ledger);
            note("tending at the garland: {}", o.status());
            checkEq("tending: post 1 back", up(mark(p1)), "");
            check("tending: the garland came down (its post had gone before the visit)", down(garlandMark()), "");
            // a body in one garland cell (a passer-by's head under the garland): that thin cell waits too
            final List<BlockPos> span = StreetPieces.cells(garlandMark());
            final BlockPos head = span.get(1);
            final ArmorStand passer = new ArmorStand(level, head.getX() + 0.5, head.getY(), head.getZ() + 0.5);
            passer.setNoGravity(true);
            level.addFreshEntity(passer);
            streetVisits(1);
            final DecorLedger.GroupView partial = ledger.group(colonyId, street(garlandMark().pos()));
            check("a body in a garland cell: that cell stays air and unlisted, the rest of the garland went up", level.getBlockState(head).isAir()
                    && ledger.at(head) == null && partial != null && partial.size() == span.size() - 1,
                    level.getBlockState(head) + ", " + (partial == null ? "no group" : partial.size() + " of " + span.size() + " listed"));
            passer.discard();
            final int visits = streetVisits(3);
            checkEq("then the next visit put the garland back", up(garlandMark()), "");
            check("in one visit", visits == 1, visits + " visits");
            tendAll(12);
            invariant("after the post");
        });
        now("window: a season change swaps only the planter", () -> {
            final Season now = season();
            final Season next = Season.values()[(now.ordinal() + 1) % Season.values().length];
            final Map<DecorLedger.Owner, String> others = new LinkedHashMap<>();
            for (final DecorLedger.GroupView g : ledger.groups(colonyId)) {
                if (g.owner().kind() == DecorLedger.Kind.STREET && !g.owner().equals(street(pl))) {
                    others.put(g.owner(), String.valueOf(new TreeMap<>(g.entries())));
                }
            }
            final BlockState old = level.getBlockState(pl);
            CalendarManager.setSeason(server, next);
            final DecorWork.Target target = DecorWork.nextToDecorate(level, colony, 1, hut.getPosition(), ledger, DecorPlacer.Stock.FREE, day());
            check("the planter is open again in the new season", target != null && target.owner().equals(street(pl)), target == null ? "none" : target.owner().toString());
            streetVisits(3);
            checkEq("the planter holds the new season's block", up(mark(pl)), "");
            check("its block changed", level.getBlockState(pl).getBlock() != old.getBlock() || now == next, old + " -> " + level.getBlockState(pl));
            final Map<DecorLedger.Owner, String> after = new LinkedHashMap<>();
            for (final DecorLedger.GroupView g : ledger.groups(colonyId)) {
                if (g.owner().kind() == DecorLedger.Kind.STREET && !g.owner().equals(street(pl))) {
                    after.put(g.owner(), String.valueOf(new TreeMap<>(g.entries())));
                }
            }
            checkEq("every other mark's group as it was", after, others);
            CalendarManager.setSeason(server, now);
            streetVisits(3);
            checkEq("back to the season before", up(mark(pl)), "");
        });
        now("window: a mark removed mid-window", () -> {
            final StreetMarks.Mark g = garlandMark();
            final BlockPos plank = StreetPieces.cells(g).get(2);
            level.setBlock(plank, Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_ALL);
            check("a garland cell changed by hand is forgotten", ledger.at(plank) == null, "");
            final Map<DecorLedger.Owner, String> untouched = new LinkedHashMap<>();
            for (final DecorLedger.GroupView v : ledger.groups(colonyId)) {
                if (v.owner().kind() == DecorLedger.Kind.STREET && !v.owner().equals(street(p2)) && !v.owner().equals(street(g.pos()))) {
                    untouched.put(v.owner(), String.valueOf(new TreeMap<>(v.entries())));
                }
            }
            // shift-right-click on post 2's upper half
            checkEq("the wand removes post 2 (its upper half clicked)", DecoratorWandItem.click(level, owner, wand(StreetMarks.Type.SNOWMAN_A), p2.above(), Direction.NORTH, true),
                    "com.colony_seasons.wand.removed");
            check("post 2's cells are air at once, its group gone", down(new StreetMarks.Mark(p2, StreetMarks.Type.POST, Direction.NORTH, null)), "");
            check("its garland mark went with it, its cells air but the plank", mark(p2) == null && garlandMark() == null
                    && level.getBlockState(plank).is(Blocks.OAK_PLANKS) && level.getBlockState(g.pos()).isAir() && level.getBlockState(g.to()).isAir()
                    && ledger.group(colonyId, street(g.pos())) == null, String.valueOf(level.getBlockState(plank)));
            final Map<DecorLedger.Owner, String> after = new LinkedHashMap<>();
            for (final DecorLedger.GroupView v : ledger.groups(colonyId)) {
                if (v.owner().kind() == DecorLedger.Kind.STREET) {
                    after.put(v.owner(), String.valueOf(new TreeMap<>(v.entries())));
                }
            }
            checkEq("the other marks are untouched", after, untouched);
            checkEq("a click where there is no mark", DecoratorWandItem.click(level, owner, wand(StreetMarks.Type.SNOWMAN_A), at(-20, 0, -14), Direction.UP, true),
                    "com.colony_seasons.wand.no_mark");
            checkEq("the stranger may not remove a mark", DecoratorWandItem.click(level, stranger, wand(StreetMarks.Type.SNOWMAN_A), sa, Direction.UP, true),
                    "com.colony_seasons.wand.refused.permission");
            level.setBlock(plank, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            checkEq("post 2 marked again", clickOn(owner, wand(StreetMarks.Type.POST), p2), "com.colony_seasons.wand.marked");
            final ItemStack w = wand(StreetMarks.Type.GARLAND);
            DecoratorWandItem.click(level, owner, w, p1.below(), Direction.UP, false);
            checkEq("and the garland again", DecoratorWandItem.click(level, owner, w, p2.below(), Direction.UP, false), "com.colony_seasons.wand.marked");
            streetVisits(3);
            checkEq("post 2 back up", up(mark(p2)), "");
            checkEq("the garland back up", up(garlandMark()), "");
            invariant("after removing a mark");
        });
        now("window: decor remove takes the pieces down and keeps the marks", () -> {
            final int marks = ledger.marks(colonyId).size();
            final List<String> said = command("colonyseasons decor remove " + colonyId);
            check("decor remove ran", !said.isEmpty(), String.valueOf(said));
            check("no street entry left, every mark kept", streetEntries() == 0 && ledger.marks(colonyId).size() == marks, streetEntries() + " entries, "
                    + ledger.marks(colonyId).size() + " marks");
            boolean air = true;
            for (final StreetMarks.Mark m : ledger.marks(colonyId)) {
                for (final BlockPos c : StreetPieces.cells(m)) {
                    air &= level.getBlockState(c).isAir();
                }
            }
            check("every mark's cells are air", air, "");
        });
        now("window: a colonist standing in a mark's cell steps out first and the mark goes up (Q7, H28)", () -> {
            final StreetMarks.Mark m = mark(sa);
            final ICitizenData c = colony.getCitizenManager().createAndRegisterCivilianData();
            colony.getCitizenManager().spawnOrCreateCitizen(c, level, sa);
            final com.minecolonies.api.entity.citizen.AbstractEntityCitizen body = c.getEntity().orElse(null);
            final List<AABB> cells = new ArrayList<>();
            for (final BlockPos cell : StreetPieces.cells(m)) {
                cells.add(new AABB(cell));
            }
            if (body != null) {
                body.teleportTo(sa.getX() + 0.5, sa.getY(), sa.getZ() + 0.5);
            }
            final boolean stoodIn = body != null && cells.stream().anyMatch(body.getBoundingBox()::intersects);
            final int evictions0 = DecorWork.evictions();
            DecorWork.decorate(level, colonyId, StreetWork.target(level, m, null), 1, DecorPlacer.Stock.FREE, day(), ledger);
            final boolean out = body != null && cells.stream().noneMatch(body.getBoundingBox()::intersects);
            final String up = up(m);
            check("H28: a colonist in a mark's cell is moved out and the mark goes up the same visit (his body does not keep it open)",
                    stoodIn && DecorWork.evictions() > evictions0 && out && up.isEmpty(), "stood in " + stoodIn + ", evictions +"
                            + (DecorWork.evictions() - evictions0) + ", out " + out + ", " + (up.isEmpty() ? "up" : up));
            // as before: the pieces down, the colonist gone
            command("colonyseasons decor remove " + colonyId);
            colony.getCitizenManager().removeCivilian(c);
            if (body != null && body.isAlive()) {
                body.discard();
            }
            check("H28: the mark is down again and the colonist gone", down(m) && streetEntries() == 0
                    && colony.getCitizenManager().getCivilian(c.getId()) == null, "down " + down(m) + ", " + streetEntries() + " street entries, colonist "
                    + (colony.getCitizenManager().getCivilian(c.getId()) == null ? "gone" : "still listed"));
        });
    }

    // ---------------------------------------------------------------- the ledger's file

    private void files() {
        final HolderLookup.Provider provider = level.registryAccess();
        final CompoundTag saved = ledger.save(new CompoundTag(), provider);
        checkEq("the file is version 3", saved.getInt("version"), 3);
        final int unknown0 = DecorLedger.unknownWarnings();
        final int newer0 = DecorLedger.newerVersionWarnings();
        final DecorLedger back = DecorLedger.load(saved, provider);
        final CompoundTag again = back.save(new CompoundTag(), provider);
        checkEq("save, load, save: the same file (marks and the marks' groups included)", again, saved);
        checkEq("the marks read back", back.marks(colonyId), ledger.marks(colonyId));
        checkEq("no warning for this build's own file", DecorLedger.unknownWarnings() - unknown0 + DecorLedger.newerVersionWarnings() - newer0, 0);
        // a mark of a type this build does not know
        final CompoundTag odd = saved.copy();
        CompoundTag one = null;
        for (final Tag t : odd.getList("colonies", Tag.TAG_COMPOUND)) {
            if (((CompoundTag) t).getInt("id") == colonyId) {
                one = (CompoundTag) t;
            }
        }
        final ListTag marks = one == null ? new ListTag() : one.getList("marks", Tag.TAG_COMPOUND);
        final CompoundTag unknownMark = new CompoundTag();
        unknownMark.putLong("pos", at(-20, 1, -14).asLong());
        unknownMark.putString("type", "reindeer");
        unknownMark.putByte("facing", (byte) 2);
        marks.add(unknownMark);
        if (one != null) {
            one.put("marks", marks);
        }
        final DecorLedger withOdd = DecorLedger.load(odd, provider);
        checkEq("a file with an unknown mark type: one warning", DecorLedger.unknownWarnings() - unknown0, 1);
        checkEq("and every known mark loaded", withOdd.marks(colonyId), ledger.marks(colonyId));
        // a version 3 file without marks (as Q10 and Q12 wrote it)
        final CompoundTag plain = saved.copy();
        for (final Tag t : plain.getList("colonies", Tag.TAG_COMPOUND)) {
            ((CompoundTag) t).remove("marks");
        }
        final int unknown1 = DecorLedger.unknownWarnings();
        final DecorLedger noMarks = DecorLedger.load(plain, provider);
        check("a version 3 file without marks loads with no warning and no marks", DecorLedger.unknownWarnings() == unknown1
                && noMarks.marks(colonyId).isEmpty() && noMarks.count(colonyId) == ledger.count(colonyId), "");
        check("a colony without marks writes no marks key", !noMarks.save(new CompoundTag(), provider).getList("colonies", Tag.TAG_COMPOUND)
                .getCompound(0).contains("marks"), "");
        check("the marks' groups are outdoor decorations", ledger.hasOutdoorEntries(colonyId) && ledger.countOutdoor(colonyId) >= streetEntries(), "");
    }

    // ================================================================ the Decorator's own AI and the end of the window

    private void planAfter() {
        final int[] budget = new int[2];
        step(new Step("AI: the Decorator puts the marks up by himself", 9000, true) {
            @Override
            void start() {
                setDate(DECOR_DAY);
                nextMorning();
                worker = colony.getCitizenManager().createAndRegisterCivilianData();
                colony.getCitizenManager().spawnOrCreateCitizen(worker, level, hut.getPosition().offset(0, 0, 4));
                final WorkerBuildingModule work = hut.getModule(DecoratorModules.WORK);
                check("a citizen is hired as the Decorator", work.assignCitizen(worker) && worker.getJob() instanceof JobDecorator, worker.getName());
            }

            @Override
            boolean tick() {
                if (t % 20 != 0) {
                    return false;
                }
                for (final StreetMarks.Mark m : ledger.marks(colonyId)) {
                    if (!StreetWork.done(ledger, colonyId, m, season())) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            void end(final boolean done) {
                check("the Decorator put every mark up by himself", done, "after " + t + " ticks, status '" + (job() == null ? "-" : job().status()) + "'");
                for (final StreetMarks.Mark m : ledger.marks(colonyId)) {
                    checkEq("by himself: " + StreetMarks.describe(m), up(m), "");
                }
                check("the hut says the decorations are up", hut.decorationsUp(), "");
                for (final DecorLedger.GroupView g : ledger.groups(colonyId)) {
                    if (g.owner().kind() != DecorLedger.Kind.STREET) {
                        note("also up: {} with {} entries {}", g.owner(), g.size(), g.entries().values());
                    }
                }
                invariant("after his decorating");
            }
        });
        step(new Step("AI: he takes the marks down after the window", 12000, true) {
            int marks;

            @Override
            void start() {
                marks = ledger.marks(colonyId).size();
                setDate(AFTER);
                nextMorning();
                budget[0] = ledger.budgetUsed(colonyId, day());
            }

            @Override
            boolean tick() {
                return t % 20 == 0 && streetEntries() == 0;
            }

            @Override
            void end(final boolean done) {
                check("after the window he takes every mark's pieces down", done, "after " + t + " ticks, " + streetEntries() + " street entries left, status '"
                        + (job() == null ? "-" : job().status()) + "'");
                checkEq("the marks stay for the next window", ledger.marks(colonyId).size(), marks);
                check("the marks' take-down used the street's one slot of the day", ledger.chargedOn(colonyId, DecorLedger.Owner.STREET_BUDGET, day()), "");
                boolean air = true;
                for (final StreetMarks.Mark m : ledger.marks(colonyId)) {
                    air &= down(m);
                }
                check("every mark's cells are air and its group gone", air, "");
                budget[1] = ledger.budgetUsed(colonyId, day()) - budget[0];
                final List<String> counted = new ArrayList<>();
                for (final IBuilding b : List.of(townHall, hut, house, field)) {
                    if (ledger.chargedOn(colonyId, DecorLedger.Owner.building(b.getPosition()), day())) {
                        counted.add(b.getPosition().toShortString());
                    }
                }
                if (ledger.chargedOn(colonyId, DecorLedger.Owner.STREET_BUDGET, day())) {
                    counted.add("street");
                }
                // a building he decorated by himself (one with a free cell) has its own slot; the marks have one between them
                check("the take-down counted one slot for all the marks", counted.contains("street") && budget[1] == counted.size(),
                        budget[1] + " slots, counted " + counted);
            }
        });
        step(new Step("sweep: with no Decorator the marks come down after the window", 900, true) {
            int afterAt = -1;

            @Override
            void start() {
                hut.getModule(DecoratorModules.WORK).removeCitizen(worker);
                worker = null;
                setDate(DECOR_DAY);
                nextMorning();
                decorateAll(20);
                note("{} street entries up again", streetEntries());
            }

            @Override
            boolean tick() {
                if (t == 5) {
                    check("the marks are up again", streetEntries() > 0, streetEntries() + " entries");
                    setDate(AFTER);
                    afterAt = t;
                }
                return afterAt > 0 && streetEntries() == 0;
            }

            @Override
            void end(final boolean done) {
                check("with no Decorator the sweep takes the marks' pieces down after the window", done, (t - afterAt) + " ticks after the window");
                check("and keeps the marks", ledger.marks(colonyId).size() == 7, ledger.marks(colonyId).size() + " marks");
            }
        });
        now("a colony that is gone takes its marks with it", () -> {
            final DecorLedger copy = DecorLedger.load(ledger.save(new CompoundTag(), level.registryAccess()), level.registryAccess());
            check("clearMarks forgets them", copy.clearMarks(colonyId) && copy.marks(colonyId).isEmpty() && !copy.clearMarks(colonyId), "");
        });
    }

    // ================================================================ mode lights

    private void planLights() {
        now("lights: decorate the buildings, then the lights", () -> {
            for (final IBuilding b : List.of(townHall, hut, house, field)) {
                DecorWork.decorate(level, colonyId, DecorWork.Target.building(b), 3, DecorPlacer.Stock.FREE, day(), ledger);
            }
            check("the lights kind off: no target", StreetLights.next(level, colony, ledger, new DecorKinds(true, true, false, true), day()) == null, "");
            final DecorWork.Outcome off = DecorWork.decorate(level, colonyId, StreetLights.target(level, colony, ledger), 3,
                    DecorPlacer.Stock.withKinds(DecorPlacer.Stock.FREE, new DecorKinds(true, true, false, true)), day(), ledger);
            check("the lights kind off: a visit puts nothing up", off.placed() == 0 && ledger.group(colonyId, StreetLights.owner(colony)) == null, off.status());
            DecorWork.Target target = null;
            int calls = 0;
            while (calls < 200) {
                calls++;
                target = DecorWork.nextToDecorate(level, colony, 3, hut.getPosition(), ledger, DecorPlacer.Stock.FREE, day());
                if (target != null && target.owner().kind() != DecorLedger.Kind.LIGHTS) {
                    DecorWork.decorate(level, colonyId, target, 3, DecorPlacer.Stock.FREE, day(), ledger);
                    continue;
                }
                if (target != null) {
                    break;
                }
            }
            check("after the buildings the next target is the street lights", target != null && target.owner().kind() == DecorLedger.Kind.LIGHTS,
                    calls + " calls, " + (target == null ? "none" : target.owner().toString()));
            int visits = 0;
            while (target != null && visits < 100) {
                final DecorWork.Outcome o = DecorWork.decorate(level, colonyId, target, 3, DecorPlacer.Stock.FREE, day(), ledger);
                visits++;
                if (o.placed() > 0) {
                    note("lights visit: {}", o.status());
                }
                target = DecorWork.nextToDecorate(level, colony, 3, hut.getPosition(), ledger, DecorPlacer.Stock.FREE, day());
                if (target != null && target.owner().kind() != DecorLedger.Kind.LIGHTS) {
                    break;
                }
            }
            final int[] scanned = StreetLights.scanned(level, colonyId);
            note("{} lights visits, the scan found {} cells, pass complete {}", visits, scanned[0], scanned[1]);
            final DecorLedger.GroupView group = ledger.group(colonyId, StreetLights.owner(colony));
            final Set<BlockPos> want = new HashSet<>();
            for (final BlockPos f : pathFences) {
                if (Math.floorMod(f.getX() + f.getZ(), StreetLights.SPACING) == 0) {
                    want.add(f.above());
                }
            }
            for (final BlockPos w : wallBlocks) {
                if (Math.floorMod(w.getX() + w.getZ(), StreetLights.SPACING) == 0) {
                    want.add(w.above());
                }
            }
            final Set<BlockPos> all = group == null ? Set.of() : group.entries().keySet();
            final Set<BlockPos> got = new HashSet<>();
            int elsewhere = 0;
            for (final BlockPos p : all) {
                if (p.getX() >= -24 && p.getX() <= 24 && p.getZ() >= -42 && p.getZ() <= -3) {
                    got.add(p);
                } else {
                    elsewhere++;
                }
            }
            note("{} lights outside the probe's street area (the world's own fences beside paths)", elsewhere);
            checkEq("in the street area: lights on every third post of the fence and the wall beside the path, nowhere else", toMap(got), toMap(want));
            final List<String> wrong = new ArrayList<>();
            for (final BlockPos p : all) {
                final BlockState s = level.getBlockState(p);
                if (!s.is(SeasonsBlocks.STREET_LIGHTS.get()) || !s.getCollisionShape(level, p).isEmpty() || !StreetLightsBlock.carries(level.getBlockState(p.below()))
                        || s.getValue(Lights.LIT) || s.getValue(StreetLightsBlock.AXIS) != Direction.Axis.X || group.entries().get(p).paid()) {
                    wrong.add(p.toShortString() + " " + s);
                }
            }
            for (final BlockPos p : all) {
                if (!besidePath(p)) {
                    wrong.add(p.toShortString() + " no path beside its fence");
                }
            }
            check("each light: a street_lights state, no collision, on a fence or a wall beside a path, unlit, free", wrong.isEmpty(), String.valueOf(wrong));
            check("none on the far fence, the column of four or the fence in the field plot", !got.contains(tallFence.above(4)) && !got.contains(fieldFence.above())
                    && farFences.stream().noneMatch(f -> got.contains(f.above())), "");
            check("at most " + StreetLights.MAX_LIGHTS, all.size() <= StreetLights.MAX_LIGHTS, all.size() + " lights");
            for (int i = 0; i < 200 && ledger.group(colonyId, StreetLights.owner(colony)).decoratedDay() < 0; i++) {
                DecorWork.nextToDecorate(level, colony, 3, hut.getPosition(), ledger, DecorPlacer.Stock.FREE, day());
            }
            check("the lights' group is finished for the window once a whole pass found nothing open",
                    ledger.group(colonyId, StreetLights.owner(colony)).decoratedDay() >= 0, "");
            final BlockPos open = at(-20, 2, -14);
            final DecorPlacer.PlaceResult r = DecorPlacer.place(level, ledger, colonyId, StreetLights.owner(colony), "", 0,
                    List.of(new DecorPlacer.Slot(open, SeasonsBlocks.SNOWMAN.get().defaultBlockState(), false)), DecorPlacer.Stock.FREE);
            check("a state with collision offered to the lights' owner is refused", r.count() == 0 && "collision".equals(r.refused().get(open))
                    && level.getBlockState(open).isAir(), String.valueOf(r.refused()));
            checkEq("decor list names the street lights", command("colonyseasons decor list " + colonyId).contains("com.colony_seasons.command.decor.kind.lights"), true);
            invariant("the lights up");
        });
        now("lights: a light broken by hand comes back with tending", () -> {
            final DecorLedger.GroupView group = ledger.group(colonyId, StreetLights.owner(colony));
            final BlockPos one = group.entries().keySet().iterator().next();
            level.destroyBlock(one, true, owner);
            check("broken: forgotten, nothing dropped", ledger.at(one) == null && level.getEntitiesOfClass(ItemEntity.class, new AABB(one).inflate(2)).isEmpty(), "");
            nextMorning();
            final DecorWork.Outcome off = DecorWork.tend(level, colonyId, StreetLights.target(level, colony, ledger), 3,
                    DecorPlacer.Stock.withKinds(DecorPlacer.Stock.FREE, new DecorKinds(true, true, false, true)), day(), ledger);
            check("with the Workshop's lights kind off tending puts nothing back", off.placed() == 0 && level.getBlockState(one).isAir(), off.status());
            final DecorLedger.GroupView looked = ledger.group(colonyId, StreetLights.owner(colony));
            check("and that visit notes the day", looked != null && looked.tendedDay() == day(), looked == null ? "no group" : "tended " + looked.tendedDay() + ", today " + day());
            final Map<DecorLedger.Owner, Integer> at = new LinkedHashMap<>();
            final int max = 30;
            final int visits = tendCounting(at, max, 3, DecorPlacer.Stock.withKinds(DecorPlacer.Stock.FREE, new DecorKinds(true, true, false, true)));
            note("{} tending visits with the lights kind off: {}", visits, at);
            check("with the lights kind off the day's tending comes to an end and the lights are not offered again",
                    visits < max && !at.containsKey(StreetLights.owner(colony)), visits + " visits, " + at.getOrDefault(StreetLights.owner(colony), 0) + " at the lights");
            nextMorning();
            tendAll(10);
            check("the next day's tending put it back", level.getBlockState(one).is(SeasonsBlocks.STREET_LIGHTS.get()) && ledger.at(one) != null, "");
        });
        now("lights: the switch off, the sweep takes them down", () -> {
            final ModConfigSpec.BooleanValue value = streetLightsValue();
            if (value == null) {
                check("the switch can be turned off here", false, "no config value");
                return;
            }
            value.set(false);
            final int entries = ledger.group(colonyId, StreetLights.owner(colony)).size();
            DecorEvents.sweep(level);
            check("with the switch off the sweep takes every light down", ledger.group(colonyId, StreetLights.owner(colony)) == null, entries + " lights before");
            check("and the fences stay", pathFences.stream().allMatch(f -> level.getBlockState(f).is(Blocks.OAK_FENCE))
                    && pathFences.stream().allMatch(f -> level.getBlockState(f.above()).isAir()), "");
            value.set(true);
        });
        now("lights: taken down after the window", () -> {
            nextMorning();
            DecorWork.Target target = StreetLights.next(level, colony, ledger, DecorKinds.ALL, day());
            int calls = 0;
            while (target == null && calls++ < 100) {
                target = StreetLights.next(level, colony, ledger, DecorKinds.ALL, day());
            }
            if (target != null) {
                DecorWork.decorate(level, colonyId, target, 3, DecorPlacer.Stock.FREE, day(), ledger);
            }
            final DecorLedger.GroupView group = ledger.group(colonyId, StreetLights.owner(colony));
            check("up again", group != null && group.size() > 0, group == null ? "none" : group.size() + " lights");
            setDate(AFTER);
            final DecorPlacer.Removed removed = DecorWork.takeDown(level, colonyId, StreetLights.owner(colony), DecorPlacer.Stock.FREE, day(), ledger);
            check("after the window the take-down removes them all", ledger.group(colonyId, StreetLights.owner(colony)) == null && removed.removed() > 0,
                    removed.removed() + " removed");
            check("the lights find no target after the window", StreetLights.next(level, colony, ledger, DecorKinds.ALL, day()) == null, "");
        });
    }

    /** Under a light: a column of one to three fences or walls whose ground has a path block beside it (one up to one down). */
    private boolean besidePath(final BlockPos light) {
        BlockPos below = light.below();
        int n = 0;
        while (n <= StreetLights.MAX_FENCE && StreetLightsBlock.carries(level.getBlockState(below))) {
            n++;
            below = below.below();
        }
        if (n == 0 || n > StreetLights.MAX_FENCE) {
            return false;
        }
        for (final BlockPos p : BlockPos.betweenClosed(below.offset(-1, -1, -1), below.offset(1, 1, 1))) {
            if ((p.getX() != below.getX() || p.getZ() != below.getZ()) && level.getBlockState(p).is(StreetLights.PATHS)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Boolean> toMap(final Set<BlockPos> set) {
        final Map<String, Boolean> out = new TreeMap<>();
        for (final BlockPos p : set) {
            out.put(p.toShortString(), true);
        }
        return out;
    }

    private static ModConfigSpec.BooleanValue streetLightsValue() {
        try {
            final java.lang.reflect.Field field = SeasonsConfig.class.getDeclaredField("STREET_LIGHTS");
            field.setAccessible(true);
            return (ModConfigSpec.BooleanValue) field.get(null);
        } catch (final ReflectiveOperationException unreadable) {
            return null;
        }
    }

    // ================================================================ restart

    private void planRestartSave() {
        now("restart-save: marks and their pieces", () -> {
            for (final BlockPos m : List.of(sa, sb, p1, p2, p3)) {
                final StreetMarks.Type type = m.equals(sa) ? StreetMarks.Type.SNOWMAN_A : m.equals(sb) ? StreetMarks.Type.SNOWMAN_B : StreetMarks.Type.POST;
                checkEq("marked " + type.id(), StreetMarks.add(level, owner, new StreetMarks.Mark(m, type, Direction.EAST, null)), null);
            }
            checkEq("marked the planter", StreetMarks.add(level, owner, new StreetMarks.Mark(pl, StreetMarks.Type.PLANTER, Direction.NORTH, null)), null);
            checkEq("marked the garland", StreetMarks.add(level, owner, StreetMarks.garland(mark(p1), mark(p2)).mark()), null);
            final DecorWork.Target hall = DecorWork.nextToDecorate(level, colony, 1, hut.getPosition(), ledger, DecorPlacer.Stock.FREE, day());
            if (hall != null && hall.owner().kind() == DecorLedger.Kind.BUILDING) {
                DecorWork.decorate(level, colonyId, hall, 1, DecorPlacer.Stock.FREE, day(), ledger);
            }
            streetVisits(6);
            for (final StreetMarks.Mark m : ledger.marks(colonyId)) {
                checkEq("up before the restart: " + StreetMarks.describe(m), up(m), "");
            }
        });
        step(new Step("restart-save: the ledger file on disk, then the world saved", 200, true) {
            String detail = "";

            boolean fileMatches() {
                try {
                    final CompoundTag root = NbtIo.readCompressed(dataFile(), NbtAccounter.unlimitedHeap());
                    final DecorLedger fromDisk = DecorLedger.load(root.getCompound("data"), level.registryAccess());
                    detail = fromDisk.marks(colonyId).size() + " marks and " + fromDisk.count(colonyId) + " entries on disk";
                    return fromDisk.marks(colonyId).equals(ledger.marks(colonyId)) && fromDisk.entries(colonyId).equals(ledger.entries(colonyId));
                } catch (final java.io.IOException unreadable) {
                    detail = "not readable yet";
                    return false;
                }
            }

            @Override
            boolean tick() {
                return t % 5 == 0 && fileMatches();
            }

            @Override
            void end(final boolean done) {
                check("the ledger file on disk has every mark and entry before any autosave", done, detail);
                final CompoundTag facts = new CompoundTag();
                final ListTag marks = new ListTag();
                for (final StreetMarks.Mark m : ledger.marks(colonyId)) {
                    final CompoundTag one = new CompoundTag();
                    one.putString("mark", StreetMarks.describe(m));
                    marks.add(one);
                }
                facts.put("marks", marks);
                facts.putString("entries", String.valueOf(new TreeMap<>(ledger.entries(colonyId))));
                facts.putInt("ground", ground);
                try {
                    NbtIo.writeCompressed(facts, server.getWorldPath(LevelResource.ROOT).resolve(RESTART_FILE));
                    check("the facts are saved in the world folder", true, marks.size() + " marks");
                } catch (final java.io.IOException unwritable) {
                    check("the facts are saved in the world folder", false, "");
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
                hut = found.getServerBuildingManager().getBuilding(new BlockPos(17, ground + 1, 2)) instanceof BuildingDecorator d ? d : null;
                return hut != null;
            }

            @Override
            void end(final boolean done) {
                check("the colony and its Workshop are back", done, colony == null ? "no colony" : "colony " + colonyId);
                if (!done) {
                    finish();
                    return;
                }
                quietWorld();
                forceChunks();
                owner = FakePlayerFactory.getMinecraft(level);
                colony.getPackageManager().addCloseSubscriber(owner);
            }
        });
        step(new Step("restart-load: the same marks and pieces, nothing twice, then the take-down", 40, true) {
            @Override
            boolean tick() {
                return t >= 20;
            }

            @Override
            void end(final boolean done) {
                final CompoundTag facts;
                try {
                    facts = NbtIo.readCompressed(server.getWorldPath(LevelResource.ROOT).resolve(RESTART_FILE), NbtAccounter.unlimitedHeap());
                } catch (final java.io.IOException unreadable) {
                    check("the saved facts read back", false, "");
                    return;
                }
                final List<String> marks = new ArrayList<>();
                for (final StreetMarks.Mark m : ledger.marks(colonyId)) {
                    marks.add(StreetMarks.describe(m));
                }
                final List<String> savedMarks = new ArrayList<>();
                for (final Tag t : facts.getList("marks", Tag.TAG_COMPOUND)) {
                    savedMarks.add(((CompoundTag) t).getString("mark"));
                }
                checkEq("the marks after the restart", marks, savedMarks);
                checkEq("the entries after the restart", String.valueOf(new TreeMap<>(ledger.entries(colonyId))), facts.getString("entries"));
                invariant("after the restart");
                check("the window is open after the restart", DecorPhase.windowOpen(CalendarManager.current()), "");
                final Map<BlockPos, DecorLedger.Entry> before = new LinkedHashMap<>(ledger.entries(colonyId));
                final DecorWork.Target next = DecorWork.nextToDecorate(level, colony, 1, hut.getPosition(), ledger, DecorPlacer.Stock.FREE, day());
                check("no mark is open after the restart", next == null || next.owner().kind() != DecorLedger.Kind.STREET,
                        next == null ? "none" : next.owner().toString());
                int placed = 0;
                for (final StreetMarks.Mark m : ledger.marks(colonyId)) {
                    placed += StreetWork.visit(level, colonyId, StreetWork.target(level, m, null), day(), ledger, false).placed();
                }
                check("a visit to every mark places nothing twice", placed == 0 && ledger.entries(colonyId).equals(before), placed + " placed");
                final StreetMarks.Mark first = ledger.marks(colonyId).get(0);
                setDate(AFTER);
                int rounds = 0;
                while (streetEntries() > 0 && rounds++ < 10) {
                    DecorWork.takeDown(level, colonyId, ledger.groups(colonyId).stream().filter(g -> g.owner().kind() == DecorLedger.Kind.STREET
                            && !g.entries().isEmpty()).findFirst().orElseThrow().owner(), DecorPlacer.Stock.FREE, day(), ledger);
                }
                check("the take-down after the restart leaves no street entry and keeps the marks", streetEntries() == 0 && !ledger.marks(colonyId).isEmpty()
                        && down(first), rounds + " take-down visits");
            }
        });
    }
}
