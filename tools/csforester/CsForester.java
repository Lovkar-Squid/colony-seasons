package me.lovkar.seasons.csforester;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.items.ModTags;
import com.minecolonies.api.tileentities.AbstractTileEntityColonyBuilding;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import com.minecolonies.core.colony.buildings.modules.settings.BoolSetting;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingLumberjack;
import com.minecolonies.core.entity.ai.workers.util.Tree;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import me.lovkar.seasons.ColonySeasons;
import me.lovkar.seasons.block.FestivalMarkerBlock;
import me.lovkar.seasons.block.FestivalTrunkBlock;
import me.lovkar.seasons.block.SeasonsBlocks;
import me.lovkar.seasons.calendar.CalendarManager;
import me.lovkar.seasons.calendar.DateSource;
import me.lovkar.seasons.decor.DecorLedger;
import me.lovkar.seasons.decor.DecorPlacer;
import me.lovkar.seasons.decor.DecorWork;
import me.lovkar.seasons.decor.DoorFinder;
import me.lovkar.seasons.decor.PieceLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Headless check of S10c item 5 (the owner's point): MineColonies' Forester must never take the festival tree for a
 * tree, or he cuts it for free logs and the Decorator puts it back the next day.
 *
 * <p>A real colony with a Forester's hut (level 1, an employed Forester with a stone axe and a stone hoe), its work area
 * restricted to a box that holds the festival tree of hut level 4 (the village spruce with the most ornament leaves:
 * they are leaves without "persistent", so with a log trunk MineColonies' leaf test is the likeliest to pass; put up
 * through {@link DecorWork}) and, 20 blocks east of the marker, a natural oak (non-persistent leaves). Three game days of his
 * work with the server sprinting. Checks: our trunk block is in no log tag at runtime; MineColonies' own
 * {@link Tree#checkTree} says "no tree" at every trunk cell of the festival tree and "tree" at the oak; the oak is cut
 * and its logs are the Forester's (if it is not cut the probe fails: it never passes by default); the festival tree is
 * whole and every ledger entry equals the world; neither his pack nor his hut got a spruce log, spruce leaves or any of
 * our items.</p>
 *
 * Prints "[csforester] CHECK ...: OK|FAIL" and "[csforester] RESULT csforester: PASS|FAIL", then halts. The words
 * "error" and "exception" stay out of the details.
 */
@Mod("colonyseasonsforester")
public class CsForester {
    private static final Logger LOGGER = LoggerFactory.getLogger("csforester");
    private static final int SETUP_TICK = 60;
    private static final long WATCHDOG_SECONDS = 520;
    private static final LocalDate DECOR_DAY = LocalDate.of(2026, 12, 10);
    private static final int MORNING = 1000;
    private static final int EVENING = 10400;
    private static final int DAYS = 3;
    private static final int HUT_LEVEL = 4;

    private int ticks;
    private boolean failed;
    private boolean finished;
    private long setupNanos;

    private MinecraftServer server;
    private ServerLevel level;
    private FakePlayer owner;
    private IColony colony;
    private int colonyId;
    private int ground;
    private DecorLedger ledger;
    private BlockPos marker;
    private BlockPos oak;
    private final List<BlockPos> oakLogs = new ArrayList<>();
    private BuildingLumberjack lumber;
    private ICitizenData forester;
    private int squareSize;
    private Map<String, Integer> heldAtStart = new TreeMap<>();

    private final List<Step> steps = new ArrayList<>();
    private int stepIndex;

    public CsForester() {
        CalendarManager.setDateSource(DateSource.fixed(DECOR_DAY));
        NeoForge.EVENT_BUS.addListener(this::onTick);
    }

    // ================================================================ logging

    private void check(final String what, final boolean ok, final String detail) {
        LOGGER.info("[csforester] CHECK {}: {} {}", what, ok ? "OK" : "FAIL", detail);
        failed |= !ok;
    }

    private void checkEq(final String what, final Object actual, final Object expected) {
        check(what, Objects.equals(actual, expected), "got " + actual + (Objects.equals(actual, expected) ? "" : ", wanted " + expected));
    }

    private static void note(final String text, final Object... args) {
        LOGGER.info("[csforester] " + text, args);
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
            server.tickRateManager().stopSprinting();
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
        LOGGER.info("[csforester] RESULT csforester: {}", failed ? "FAIL" : "PASS");
        if (server != null) {
            server.halt(false);
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

    // ================================================================ world helpers

    private long day() {
        return level.getDayTime() / 24000L;
    }

    private long timeOfDay() {
        return level.getDayTime() % 24000L;
    }

    private void set(final BlockPos pos, final BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }

    /** A stone-brick house on the ground with a door in its south wall (the csfestival helper). */
    private DoorFinder.Box house(final int x0, final int z0, final int w, final int d, final int door) {
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
        final BlockState lower = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH)
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER).setValue(DoorBlock.HINGE, DoorHingeSide.LEFT);
        set(new BlockPos(door, y0 + 1, zs), lower);
        set(new BlockPos(door, y0 + 2, zs), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        return new DoorFinder.Box(new BlockPos(x0, y0, z0), new BlockPos(x0 + w - 1, y0 + 6, z0 + d - 1),
                new BlockPos(x0 + w / 2, y0 + 1, z0 + d / 2));
    }

    /** Registers a MineColonies building on a hand-built house (the csfestival helper). */
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

    /** Fed and well: hunger or a disease would take him off work for reasons that are not the tree's. */
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
        if (entity instanceof EntityCitizen e && e.getCitizenJobHandler().getColonyJob() != null
                && e.getCitizenJobHandler().getColonyJob().getWorkerAI() != null) {
            return e.getCitizenAI().getState() + "/" + e.getCitizenJobHandler().getColonyJob().getWorkerAI().getState();
        }
        return entity instanceof EntityCitizen e && e.getCitizenAI() != null ? String.valueOf(e.getCitizenAI().getState()) : "none";
    }

    /**
     * A natural oak by hand, the same every run: a five-log trunk and a blob of oak leaves that are not persistent, each
     * with the distance vanilla gives it (the trunk is straight and the blob convex, so it is the step count to the
     * nearest log).
     */
    private void growOak(final BlockPos base) {
        for (int y = 0; y < 5; y++) {
            final BlockPos log = base.above(y);
            set(log, Blocks.OAK_LOG.defaultBlockState());
            oakLogs.add(log);
        }
        for (int y = 2; y <= 5; y++) {
            final int r = y <= 3 ? 2 : 1;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx == 0 && dz == 0 && y < 5 || r == 2 && Math.abs(dx) == 2 && Math.abs(dz) == 2 || r == 1 && Math.abs(dx) + Math.abs(dz) > 1) {
                        continue;
                    }
                    final int distance = Math.max(1, Math.min(7, Math.abs(dx) + Math.abs(dz) + (y == 5 ? 1 : 0)));
                    set(base.offset(dx, y, dz), Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, distance)
                            .setValue(LeavesBlock.PERSISTENT, false));
                }
            }
        }
    }

    private boolean isTree(final BlockPos pos) {
        return Tree.checkTree(level, pos, List.of(), 0, IColonyManager.getInstance().getCompatibilityManager().getLeavesToSaplingMapCopy());
    }

    /** What the Forester holds and his hut stores, by item id. */
    private Map<String, Integer> held() {
        final Map<String, Integer> out = new TreeMap<>();
        final List<IItemHandler> handlers = new ArrayList<>();
        handlers.add(forester.getInventory());
        if (lumber.getItemHandlerCap() != null) {
            handlers.add(lumber.getItemHandlerCap());
        }
        for (final IItemHandler h : handlers) {
            for (int slot = 0; slot < h.getSlots(); slot++) {
                final ItemStack s = h.getStackInSlot(slot);
                if (!s.isEmpty()) {
                    out.merge(BuiltInRegistries.ITEM.getKey(s.getItem()).toString(), s.getCount(), Integer::sum);
                }
            }
        }
        return out;
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

    // ================================================================ the plan

    private void plan() {
        step(new Step("setup: the colony, the Forester's hut and its Forester, the festival marker", 20, true) {
            @Override
            void start() {
                server.setDifficulty(Difficulty.PEACEFUL, true);
                final GameRules rules = server.getGameRules();
                rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
                rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
                rules.getRule(GameRules.RULE_DOFIRETICK).set(false, server);
                level.setWeatherParameters(24000 * 20, 0, false, false);
                CalendarManager.refresh(server);
                ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, 2, 2) - 1;
                final BlockPos center = new BlockPos(2, ground + 1, 2);
                for (int cx = -4; cx <= 4; cx++) {
                    for (int cz = -4; cz <= 4; cz++) {
                        level.setChunkForced(cx, cz, true);
                    }
                }
                owner = FakePlayerFactory.getMinecraft(level);
                owner.setPos(center.getX() + 0.5, center.getY(), center.getZ() + 0.5);
                colony = IColonyManager.getInstance().createColony(level, center, owner, "Seasons Forester Test", "Medieval Oak");
                colonyId = colony == null ? -1 : colony.getID();
                check("a colony was made", colony != null, "id " + colonyId + " at " + center);
                if (colony == null) {
                    finish();
                    return;
                }
                register("minecolonies:blockhuttownhall", house(-3, -12, 11, 7, 2), 1);
                final IBuilding b = register("minecolonies:blockhutlumberjack", house(-14, 12, 9, 7, -10), 1);
                lumber = b instanceof BuildingLumberjack l ? l : null;
                check("the Forester's hut is registered", lumber != null, String.valueOf(b));
                if (lumber == null) {
                    finish();
                    return;
                }
                forester = colony.getCitizenManager().createAndRegisterCivilianData();
                colony.getCitizenManager().spawnOrCreateCitizen(forester, level, lumber.getPosition().offset(0, 0, 5));
                final WorkerBuildingModule module = lumber.getFirstModuleOccurance(WorkerBuildingModule.class);
                check("a citizen is hired as the Forester", module != null && module.assignCitizen(forester) && forester.getJob() != null,
                        forester.getJob() == null ? "no job" : forester.getJob().getClass().getSimpleName());
                for (final Item tool : List.of(Items.STONE_AXE, Items.STONE_AXE, Items.STONE_HOE)) {
                    ItemHandlerHelper.insertItemStacked(forester.getInventory(), new ItemStack(tool), false);
                }
                marker = new BlockPos(2, ground + 1, 30);
                final BlockState m = SeasonsBlocks.FESTIVAL_MARKER.get().defaultBlockState().setValue(FestivalMarkerBlock.FACING, Direction.NORTH);
                level.setBlock(marker, m, Block.UPDATE_ALL);
                SeasonsBlocks.FESTIVAL_MARKER.get().setPlacedBy(level, marker, m, owner, new ItemStack(SeasonsBlocks.FESTIVAL_MARKER_ITEM.get()));
                checkEq("the marker is the colony's square", ledger.marker(colonyId), marker);
                oak = marker.offset(20, 0, 0);
                growOak(oak);
                // his work area: the festival tree and the oak, nothing else
                final BlockPos from = marker.offset(-9, -2, -9);
                final BlockPos to = marker.offset(25, 24, 9);
                lumber.setRestrictedArea(from, to);
                final BoolSetting restrict = lumber.getSetting(BuildingLumberjack.RESTRICT);
                if (!restrict.getValue()) {
                    restrict.trigger();
                }
                check("his work area is restricted to the box with both trees", lumber.shouldRestrict()
                        && lumber.getStartRestriction() != null && lumber.getEndRestriction() != null, from.toShortString() + " to " + to.toShortString());
            }

            @Override
            boolean tick() {
                return PieceLoader.request(level, PieceLoader.PACK, PieceLoader.pathFor(HUT_LEVEL), Direction.NORTH) != null && t >= 10;
            }
        });
        step(new Step("the festival tree goes up at the marker (hut level " + HUT_LEVEL + ")", 200, true) {
            DecorWork.Outcome last;

            @Override
            boolean tick() {
                if (t % 5 == 0 && (last == null || !last.complete())) {
                    last = DecorWork.decorate(level, colonyId, DecorWork.Target.square(marker, Direction.NORTH, PieceLoader.PACK), HUT_LEVEL,
                            DecorPlacer.Stock.FREE, day(), ledger);
                }
                return last != null && last.complete();
            }

            @Override
            void end(final boolean done) {
                final DecorLedger.GroupView g = ledger.group(colonyId, DecorLedger.Owner.square(marker));
                squareSize = g == null ? 0 : g.size();
                check("the festival tree is up", done && squareSize > 100, (last == null ? "-" : last.status()) + ", " + squareSize + " cells");
                final BlockState trunk = SeasonsBlocks.FESTIVAL_TRUNK.get().defaultBlockState();
                check("the festival trunk is in no log tag at runtime (#minecraft:logs, logs_that_burn, #minecolonies:tree)",
                        !trunk.is(BlockTags.LOGS) && !trunk.is(BlockTags.LOGS_THAT_BURN) && !trunk.is(ModTags.tree), "");
                int trunks = 0;
                int trees = 0;
                if (g != null) {
                    for (final Map.Entry<BlockPos, DecorLedger.Entry> e : g.entries().entrySet()) {
                        if (e.getValue().state().getBlock() instanceof FestivalTrunkBlock) {
                            trunks++;
                            trees += isTree(e.getKey()) ? 1 : 0;
                        }
                    }
                }
                check("MineColonies' tree check says no tree at every trunk cell of the festival tree", trunks > 0 && trees == 0,
                        trees + " of " + trunks + " trunk cells taken for a tree");
                check("and says tree at the natural oak", isTree(oak), oak.toShortString());
                heldAtStart = held();
                note("the Forester holds {} at the start", heldAtStart);
                invariant("the tree up");
            }
        });
        for (int d = 1; d <= DAYS; d++) {
            final int n = d;
            step(new Step("game day " + n + " of the Forester's work", 12000, true) {
                @Override
                void start() {
                    level.setDayTime((day() + 1) * 24000L + MORNING);
                }

                @Override
                boolean tick() {
                    if (t % 2000 == 0) {
                        final AbstractEntityCitizen e = forester.getEntity().orElse(null);
                        note("day {} at {}: the Forester is {} at {}; oak logs left {}", n, timeOfDay(), state(forester),
                                e == null ? "-" : e.blockPosition().toShortString(), oakLeft());
                    }
                    return timeOfDay() >= EVENING;
                }

                @Override
                void end(final boolean done) {
                    final DecorLedger.GroupView g = ledger.group(colonyId, DecorLedger.Owner.square(marker));
                    checkEq("game day " + n + ": the festival tree is whole", g == null ? 0 : g.size(), squareSize);
                    invariant("game day " + n);
                    note("day {}: the Forester holds {}", n, held());
                }
            });
        }
        step(new Step("the end: what the Forester got", 1, false) {
            @Override
            void start() {
                final Map<String, Integer> now = held();
                check("the natural oak is cut (it never passes by default)", oakLeft() == 0, oakLeft() + " of " + oakLogs.size() + " logs left");
                check("its logs are the Forester's (his pack or his hut)", now.getOrDefault("minecraft:oak_log", 0) > heldAtStart.getOrDefault("minecraft:oak_log", 0),
                        now.toString());
                final List<String> wrong = new ArrayList<>();
                for (final Map.Entry<String, Integer> e : now.entrySet()) {
                    final boolean ours = e.getKey().startsWith(ColonySeasons.MODID + ":");
                    final boolean spruce = e.getKey().equals("minecraft:spruce_log") || e.getKey().equals("minecraft:spruce_leaves");
                    if ((ours || spruce) && e.getValue() > heldAtStart.getOrDefault(e.getKey(), 0)) {
                        wrong.add(e.getKey() + " x" + e.getValue());
                    }
                }
                check("nothing of the festival tree is his (no spruce log, no spruce leaves, none of our items)", wrong.isEmpty(), wrong.toString());
                final DecorLedger.GroupView g = ledger.group(colonyId, DecorLedger.Owner.square(marker));
                checkEq("the festival tree is whole after " + DAYS + " game days", g == null ? 0 : g.size(), squareSize);
                invariant("the end");
            }
        });
    }

    private int oakLeft() {
        int n = 0;
        for (final BlockPos log : oakLogs) {
            n += level.getBlockState(log).is(Blocks.OAK_LOG) ? 1 : 0;
        }
        return n;
    }
}
