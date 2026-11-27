package me.lovkar.seasons.cstradepost;

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
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.tileentities.AbstractTileEntityColonyBuilding;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import me.lovkar.seasons.ColonySeasons;
import me.lovkar.seasons.SeasonsConfig;
import me.lovkar.seasons.block.SeasonsBlocks;
import me.lovkar.seasons.calendar.CalendarManager;
import me.lovkar.seasons.calendar.DateSource;
import me.lovkar.seasons.colony.BuildingDecorator;
import me.lovkar.seasons.colony.BuildingTownSquare;
import me.lovkar.seasons.colony.DecoratorModules;
import me.lovkar.seasons.colony.DecoratorSetup;
import me.lovkar.seasons.colony.JobDecorator;
import me.lovkar.seasons.colony.SquareInfoModule;
import me.lovkar.seasons.colony.SquareInfoModuleView;
import me.lovkar.seasons.colony.SquareModules;
import me.lovkar.seasons.compat.TradePostBridge;
import me.lovkar.seasons.decor.DecorEvents;
import me.lovkar.seasons.decor.DecorLedger;
import me.lovkar.seasons.decor.DecorPlacer;
import me.lovkar.seasons.decor.DecorWork;
import me.lovkar.seasons.decor.DoorFinder;
import me.lovkar.seasons.decor.Kits;
import me.lovkar.seasons.decor.MarketMath;
import me.lovkar.seasons.decor.MarketStalls;
import me.lovkar.seasons.decor.PieceLoader;
import me.lovkar.seasons.decor.SquareSets;
import me.lovkar.seasons.decor.Squares;
import me.lovkar.seasons.festival.FestivalDirector;
import me.lovkar.seasons.festival.Gathering;
import me.lovkar.seasons.festival.Gifts;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
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
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Headless check of Q10, the festival market and its Trade Post bridge, on a real colony: a Town Square of level 5 (its
 * blueprint pasted and registered as the main square, the Decorator assigned), the Winter Festival's window open, a
 * Workshop stocked with festival goods and the stalls' materials. Modes (the rig writes the word to probe-mode.txt):
 * <ul>
 *   <li>{@code without}: Trade Post is not installed. The mod booted, no class of Trade Post and none of the client package
 *       is loaded, the bridge is off and logged nothing, the square holds exactly the tree, the gift pile and the crate
 *       (no stall cell listed, no market set), a festival day run through the gathering hours with a buyer at a stall
 *       sells nothing, {@code current_balance} was never written, the saved ledger has no market keys, the info page has no
 *       market line; the ledger's two market keys survive a save and a load in memory, and a tag without them loads as
 *       never paid;</li>
 *   <li>{@code with}: Trade Post for MineColonies 1.06.012 next to the mod: the bridge on, one version line, our prices
 *       load and Trade Post's own {@code marketplaceValue} is above 0 for each priced good and 0 for the gift and the
 *       fixtures, the four stalls stand (every cell air, listed, equal to its state, the vendor's column and the lamp posts'
 *       cells free), one sale pays exactly its price and takes one good, the sales stop at the cap, the next colony day has
 *       a fresh cap and an earlier one sells nothing, every "nothing sold" row of the tick, the switch off, a payment that
 *       throws or does not move the balance (the good and the record come back, one WARN line, the bridge off), a balance the
 *       self-test cannot read (no market wanted, no stall planned), a foreign block on one pad (that stall is left out, the
 *       tree stands, the cap is three coins, the stall comes at the next tending once the block is gone), the window's end
 *       (the group gone, every stall item back in the Workshop);</li>
 *   <li>{@code restart-save}, {@code restart-mid}, {@code restart-gone} (tools/restart.sh): sales short of the cap and the
 *       world saved; after the restart the paid record, the balance and the stalls are as they were and selling on stops at
 *       the cap with the two runs together; then the Trade Post jar is taken out (dropjars.txt) and the world loads without
 *       an error line, the bridge is off, the statistic and the ledger's keys are still there and nothing is paid.</li>
 * </ul>
 * Prints "[cstradepost] CHECK ...: OK|FAIL" and "[cstradepost] RESULT cstradepost: PASS|FAIL", then halts. The words
 * "error" and "exception" stay out of the details. Trade Post is reached by reflection only, so the same probe jar loads
 * without it.
 */
@Mod("colonyseasonstradepost")
public class CsTradePost {
    private static final Logger LOGGER = LoggerFactory.getLogger("cstradepost");
    private static final int SETUP_TICK = 60;
    private static final long WATCHDOG_SECONDS = 560;

    static final LocalDate DECOR_DAY = LocalDate.of(2026, 12, 10);
    static final LocalDate FESTIVAL_DAY = LocalDate.of(2026, 12, 25);
    static final LocalDate OUT_OF_SEASON = LocalDate.of(2027, 3, 15);
    private static final int MORNING = 1000;
    private static final int IN_HOURS = 9000;
    private static final String BALANCE = "current_balance";
    private static final String RESTART_FILE = "cstradepost-restart.nbt";
    private static final String SQUARE_FILE = "square/townsquare5.blueprint";
    private static final BlockPos SQUARE_PLOT = new BlockPos(-8, 0, 22);
    private static final String TP_ROOT = String.join(".", "com", "deathfrog", "mctradepost");
    /** The four materials a stall is built of: taken from the Workshop for real, everything else of the tree is conjured. */
    private static final Set<Item> STALL_ITEMS = Set.of(Items.STRIPPED_SPRUCE_LOG, Items.SPRUCE_SLAB, Items.RED_WOOL, Items.WHITE_WOOL);

    private int ticks;
    private boolean failed;
    private boolean finished;
    private final String mode;
    private long setupNanos;
    private long clock = 100_000L;

    private MinecraftServer server;
    private ServerLevel level;
    private FakePlayer owner;
    private IColony colony;
    private int colonyId;
    private int ground;
    private DecorLedger ledger;
    private BuildingDecorator hut;
    private IBuilding townHall;
    private ICitizenData decorator;
    private final List<ICitizenData> offDuty = new ArrayList<>();
    private BuildingTownSquare townSquare;
    private BlockPos anchor;
    private long day0;

    private final List<Step> steps = new ArrayList<>();
    private int stepIndex;

    public CsTradePost() {
        mode = readMode();
        CalendarManager.setDateSource(DateSource.fixed(DECOR_DAY));
        NeoForge.EVENT_BUS.addListener(this::onTick);
    }

    // ================================================================ logging and the tick

    private void check(final String what, final boolean ok, final String detail) {
        LOGGER.info("[cstradepost] CHECK {}: {} {}", what, ok ? "OK" : "FAIL", detail);
        failed |= !ok;
    }

    private void checkEq(final String what, final Object actual, final Object expected) {
        check(what, Objects.equals(actual, expected), "got " + actual + (Objects.equals(actual, expected) ? "" : ", wanted " + expected));
    }

    private static void note(final String text, final Object... args) {
        LOGGER.info("[cstradepost] " + text, args);
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
        LOGGER.info("[cstradepost] RESULT cstradepost: {}", failed ? "FAIL" : "PASS");
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
            case "without" -> {
                planSetup();
                planSquare();
                planWithoutUp();
                planWithoutChecks();
            }
            case "with" -> {
                planSetup();
                planSquare();
                planBridge();
                planStallsUp();
                planFirstSale();
                planTickMatrix();
                planSwitchAndFailures();
                planSellOn();
                planForeignBlock();
                planWindowEnds();
            }
            case "restart-save" -> {
                planSetup();
                planSquare();
                planBridge();
                planStallsUp();
                planRestartSave();
            }
            case "restart-mid" -> planRestartLoad(false);
            case "restart-gone" -> planRestartLoad(true);
            default -> {
                check("known mode", false, "'" + mode + "'");
                finish();
            }
        }
    }

    // ================================================================ reflection into Trade Post (never a compile reference)

    private static Class<?> tpClass(final String shortName) throws ClassNotFoundException {
        return Class.forName(TP_ROOT + "." + shortName, false, CsTradePost.class.getClassLoader());
    }

    private static boolean tradePostThere() {
        try {
            tpClass("MCTPConfig");
            return true;
        } catch (final ClassNotFoundException absent) {
            return false;
        }
    }

    /** Trade Post's own value of an item ({@code ItemValueManager.get}). */
    private static int tpValue(final Item item) throws ReflectiveOperationException {
        final Method get = tpClass("api.util.ItemValueManager").getMethod("get", Item.class);
        return (Integer) get.invoke(null, item);
    }

    /** What its Marketplace would sell one for ({@code MarketplaceItemListModule.marketplaceValue}). */
    private static int tpMarketValue(final ItemStack stack) throws ReflectiveOperationException {
        final Method value = tpClass("core.colony.buildings.modules.MarketplaceItemListModule").getMethod("marketplaceValue", ItemStack.class);
        return (Integer) value.invoke(null, stack);
    }

    private static int tpCoinValue() throws ReflectiveOperationException {
        final Object holder = tpClass("MCTPConfig").getField("tradeCoinValue").get(null);
        return (Integer) holder.getClass().getMethod("get").invoke(holder);
    }

    /** ClassLoader.findLoadedClass through the JDK's trusted lookup (as csclient does); {@code null} when it cannot be reached. */
    private static MethodHandle findLoadedClass() {
        try {
            final Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            final sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            final Field trusted = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
            final MethodHandles.Lookup lookup = (MethodHandles.Lookup) unsafe.getObject(unsafe.staticFieldBase(trusted), unsafe.staticFieldOffset(trusted));
            return lookup.findVirtual(ClassLoader.class, "findLoadedClass", MethodType.methodType(Class.class, String.class));
        } catch (final Throwable unreachable) {
            note("NOTE no trusted lookup: {}", unreachable.getClass().getSimpleName());
            return null;
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

    private void setTimeOfDay(final int time) {
        level.setDayTime(day() * 24000L + time);
    }

    private void set(final BlockPos pos, final BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }

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

    private void keepWell() {
        for (final ICitizenData c : colony.getCitizenManager().getCitizens()) {
            c.setSaturation(ICitizenData.MAX_SATURATION);
            if (c.getCitizenDiseaseHandler().isSick()) {
                c.getCitizenDiseaseHandler().cure();
            }
        }
    }

    private static void put(final ICitizenData c, final BlockPos feet) {
        final AbstractEntityCitizen e = c.getEntity().orElse(null);
        if (e != null) {
            e.teleportTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
            e.getNavigation().stop();
        }
    }

    private DecorLedger.Owner owner() {
        return DecorLedger.Owner.square(anchor);
    }

    private DecorWork.Target target() {
        return DecorWork.Target.townSquare(townSquare, anchor);
    }

    private DecorLedger.GroupView group() {
        return ledger.group(colonyId, owner());
    }

    private int groupSize() {
        final DecorLedger.GroupView g = group();
        return g == null ? 0 : g.entries().size();
    }

    // ================================================================ the stock: Workshop goods, stall materials

    /**
     * The Decorator's stock for the probe: the four stall materials are really taken from the Workshop and given back to
     * it; everything else the tree and its fixtures cost is conjured and vanishes again (the Workshop's goods, which the
     * sales count, are never touched by the tree).
     */
    private DecorPlacer.Stock stock() {
        final DecorPlacer.Stock real = DecorWork.stockOf(hut, null);
        return new DecorPlacer.Stock() {
            @Override
            public boolean take(final Item item) {
                return !STALL_ITEMS.contains(item) || real.take(item);
            }

            @Override
            public void give(final ItemStack stack) {
                if (STALL_ITEMS.contains(stack.getItem())) {
                    real.give(stack);
                }
            }

            @Override
            public boolean has(final Item item) {
                return !STALL_ITEMS.contains(item) || real.has(item);
            }

            @Override
            public me.lovkar.seasons.decor.DecorKinds kinds() {
                return real.kinds();
            }
        };
    }

    private int held(final Item item) {
        return BuildingDecorator.countIn(hut.getItemHandlerCap(), s -> s.is(item));
    }

    private List<Item> goods() {
        final List<Item> out = new ArrayList<>();
        for (final Kits.Kit kit : Kits.all()) {
            out.add(kit.output());
        }
        return out;
    }

    private int goodsHeld() {
        int n = 0;
        for (final Item item : goods()) {
            n += held(item);
        }
        return n;
    }

    private void insert(final Item item, final int n) {
        int left = n;
        while (left > 0) {
            final int one = Math.min(left, item.getDefaultMaxStackSize());
            final ItemStack rest = ItemHandlerHelper.insertItemStacked(hut.getItemHandlerCap(), new ItemStack(item, one), false);
            if (!rest.isEmpty()) {
                check("the Workshop holds " + n + " " + item, false, rest.getCount() + " did not fit");
                return;
            }
            left -= one;
        }
    }

    /** {@code goods} of each festival good and {@code mats} of each stall material into the Workshop. */
    private void stockUp(final int goods, final int mats) {
        for (final Item item : goods()) {
            insert(item, goods);
        }
        for (final Item item : STALL_ITEMS) {
            insert(item, mats);
        }
    }

    private int[] stallItems() {
        return new int[] {held(Items.STRIPPED_SPRUCE_LOG), held(Items.SPRUCE_SLAB), held(Items.RED_WOOL), held(Items.WHITE_WOOL)};
    }

    // ================================================================ setup

    private void planSetup() {
        step(new Step("setup: the colony, the town hall, the Workshop at level 5 with the Decorator, off-duty colonists", 80, true) {
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
                colony = IColonyManager.getInstance().createColony(level, center, owner, "Seasons Market Test", "Medieval Oak");
                colonyId = colony == null ? -1 : colony.getID();
                check("a colony was made", colony != null, "id " + colonyId + " at " + center);
                if (colony == null) {
                    finish();
                    return;
                }
                townHall = register("minecolonies:blockhuttownhall", house(-3, -1, 11, 7, 2), 1);
                final IBuilding d = register(BuiltInRegistries.BLOCK.getKey(DecoratorSetup.HUT.get()).toString(), house(12, -1, 11, 7, 17), 5);
                hut = d instanceof BuildingDecorator b ? b : null;
                check("the buildings are registered", townHall != null && hut != null, "");
                if (hut == null) {
                    finish();
                    return;
                }
                decorator = colony.getCitizenManager().createAndRegisterCivilianData();
                colony.getCitizenManager().spawnOrCreateCitizen(decorator, level, hut.getPosition().offset(0, 0, 4));
                check("a citizen is hired as the Decorator", hut.getModule(DecoratorModules.WORK).assignCitizen(decorator)
                        && decorator.getJob() instanceof JobDecorator, decorator.getName());
                // the Decorator's own AI puts nothing up and takes nothing of the Workshop's stock: the probe counts the goods exactly
                if (hut.decorateEnabled()) {
                    hut.getModule(DecoratorModules.SETTINGS).getSetting(BuildingDecorator.DECORATE).trigger();
                }
                check("the Workshop's decorate setting is off for the count", !hut.decorateEnabled(), "");
                for (int i = 0; i < 3; i++) {
                    final ICitizenData c = colony.getCitizenManager().createAndRegisterCivilianData();
                    colony.getCitizenManager().spawnOrCreateCitizen(c, level, center.offset(-2 + 2 * i, 0, 8));
                    offDuty.add(c);
                }
                setTimeOfDay(MORNING);
            }

            @Override
            boolean tick() {
                return t >= 60;
            }
        });
    }

    /** The square's blueprint pasted with its box's low corner at the plot (Structurize's creative handler); the hut block's position, or null. */
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
            final BlockPos at = plot.offset(blueprint.getPrimaryBlockOffset());
            final CreativeStructureHandler handler = new CreativeStructureHandler(level, at, blueprint, RotationMirror.NONE, true);
            final StructurePlacer placer = new StructurePlacer(handler);
            int rounds = 0;
            while (rounds++ < 4000) {
                final StructurePhasePlacementResult result = placer.executeStructureStep(level, null, placer.getIterator().getProgressPos(),
                        StructurePlacer.Operation.BLOCK_PLACEMENT, () -> placer.getIterator().increment((info, pos, h) -> false), false);
                if (result.getIteratorPos().equals(AbstractBlueprintIterator.NULL_POS)) {
                    break;
                }
            }
            return at;
        } catch (final Exception unreadable) {
            check("the square's blueprint pastes", false, unreadable.getClass().getSimpleName().replace("Exception", ""));
            return null;
        }
    }

    private void planSquare() {
        step(new Step("square: the level 5 Town Square pasted, registered, its Decorator assigned", 1) {
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
                square.setBuildingLevel(5);
                square.setStructurePack(PieceLoader.PACK);
                square.setBlueprintPath(SQUARE_FILE);
                square.assignment().setHiringMode(com.minecolonies.api.colony.buildings.HiringMode.MANUAL);
                square.markDirty();
                check("the Decorator is assigned to the square (he stays employed at his Workshop)",
                        square.assignment().assignCitizen(decorator) && decorator.getWorkBuilding() == hut
                                && decorator.getJob() instanceof JobDecorator j && j.findSquare() == square, "");
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
                anchor = Squares.treeCell(townSquare);
                Squares.refresh(level, colony, ledger);
                final List<BlockPos> stalls = townSquare.getLocationsFromTag(BuildingTownSquare.TAG_STALL);
                checkEq("a level 5 square has four stall tags", stalls == null ? 0 : stalls.size(), 4);
                check("the square is level 5 and the colony's main square", townSquare.getBuildingLevel() == 5 && ledger.squareMark(colonyId) != null
                        && ledger.squareMark(colonyId).anchor().equals(anchor), String.valueOf(ledger.squareMark(colonyId)));
            }
        });
    }

    // ================================================================ the square goes up

    /** Decorates until the visit says complete (or {@code maxTicks} pass); {@code last[0]} holds the last outcome. */
    private Step decorateStep(final String name, final int maxTicks, final DecorWork.Outcome[] last, final Runnable after) {
        return new Step(name, maxTicks, true) {
            @Override
            boolean tick() {
                if (t % 20 == 0) {
                    last[0] = DecorWork.decorate(level, colonyId, target(), hut.getBuildingLevel(), stock(), day(), ledger);
                }
                return t % 20 == 0 && last[0] != null && last[0].complete();
            }

            @Override
            void end(final boolean done) {
                check(name + ": the visit completed", done, last[0] == null ? "no visit" : last[0].status());
                after.run();
            }
        };
    }

    /** The cells of the tree and its fixtures the plan lists, from the piece: what a square without a market may hold. */
    private Set<BlockPos> baseline() {
        final Set<BlockPos> out = new HashSet<>();
        final PieceLoader.Piece piece = PieceLoader.request(level, PieceLoader.PACK, DecorWork.piecePath(target(), hut.getBuildingLevel()),
                target().rotation());
        if (piece != null) {
            for (final PieceLoader.PieceCell cell : piece.cells()) {
                out.add(anchor.offset(cell.rel()));
            }
        }
        for (final PieceLoader.PieceCell cell : DecorWork.fixtures(target(), 5)) {
            out.add(anchor.offset(cell.rel()));
        }
        return out;
    }

    private int count(final java.util.function.Predicate<BlockState> what) {
        final DecorLedger.GroupView g = group();
        return g == null ? 0 : (int) g.entries().values().stream().filter(e -> what.test(e.state())).count();
    }

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

    // ================================================================ mode without

    private void planWithoutUp() {
        final DecorWork.Outcome[] last = new DecorWork.Outcome[1];
        step(new Step("without: the Workshop is stocked, the Winter Festival's window is open", 1) {
            @Override
            void start() {
                stockUp(20, 40);
            }
        });
        step(decorateStep("without: the square goes up", 600, last, () -> {
        }));
    }

    private void planWithoutChecks() {
        step(new Step("without: nothing of the market exists", 120) {
            @Override
            void start() {
                setDate(FESTIVAL_DAY);
                setTimeOfDay(MORNING);
            }

            @Override
            boolean tick() {
                return t >= 40;
            }

            @Override
            void end(final boolean done) {
                // the bridge and Trade Post
                check("no class of Trade Post is there (Class.forName fails)", !tradePostThere(), "");
                final MethodHandle find = findLoadedClass();
                if (find != null) {
                    boolean loaded = false;
                    try {
                        for (final String name : List.of("MCTPConfig", "api.util.ItemValueManager", "core.colony.buildings.modules.MarketplaceItemListModule")) {
                            loaded |= find.invoke(CsTradePost.class.getClassLoader(), TP_ROOT + "." + name) != null;
                        }
                        check("no class of Trade Post was loaded", !loaded, "");
                        check("the check can see a loaded class (the bridge is one)",
                                find.invoke(CsTradePost.class.getClassLoader(), TradePostBridge.class.getName()) != null, "");
                        boolean window = false;
                        for (final String name : List.of("me.lovkar.seasons.client.TownSquareInfoWindow", "me.lovkar.seasons.client.SquareAssignmentWindow",
                                "me.lovkar.seasons.client.DecoratorInfoWindow", "me.lovkar.seasons.client.DecoratorStorageWindow")) {
                            window |= find.invoke(CsTradePost.class.getClassLoader(), name) != null;
                        }
                        check("no class of the client package was loaded on the dedicated server", !window, "");
                    } catch (final Throwable unreachable) {
                        check("the loaded-class check ran", false, unreachable.getClass().getSimpleName());
                    }
                }
                check("the bridge is not active (Trade Post is not installed)", !MarketStalls.bridge().active(), "");
                checkEq("the bridge logged no line (INFO or above)", TradePostBridge.logLines(), 0);
                check("the switch alone does not make it active", SeasonsConfig.tradePostBridge() && !MarketStalls.bridge().active(), "");
                // the square
                final DecorLedger.GroupView g = group();
                check("the square stands (the tree, the gift pile(s), the one crate)", g != null && count(s -> s.is(SeasonsBlocks.FESTIVAL_TRUNK.get())) > 0
                        && count(s -> s.is(SeasonsBlocks.GIFT_PILE.get())) >= 1 && count(s -> s.is(SeasonsBlocks.FIREWORK_CRATE.get())) == 1,
                        g == null ? "no group" : g.size() + " cells, " + count(s -> s.is(SeasonsBlocks.GIFT_PILE.get())) + " piles, "
                                + count(s -> s.is(SeasonsBlocks.FIREWORK_CRATE.get())) + " crates");
                final Set<BlockPos> allowed = baseline();
                int stray = 0;
                if (g != null) {
                    for (final BlockPos pos : g.entries().keySet()) {
                        stray += allowed.contains(pos) ? 0 : 1;
                    }
                }
                checkEq("every cell of the group belongs to the tree or the two fixtures (the square cycle is the 0.2.0 one)", stray, 0);
                final Set<BlockPos> footprints = MarketStalls.cells(townSquare, anchor);
                check("the stalls' footprints are known (60 cells)", footprints.size() == 60, String.valueOf(footprints.size()));
                int inStalls = 0;
                for (final BlockPos pos : footprints) {
                    inStalls += (ledger.at(pos) != null ? 1 : 0) + (level.getBlockState(pos).isAir() ? 0 : 1);
                }
                checkEq("no stall cell is listed or built", inStalls, 0);
                check("no market set is wanted", !SquareSets.market(SquareSets.wanted(townSquare.order(), CalendarManager.current()),
                        CalendarManager.current(), 5, MarketStalls.bridge().active()) && !MarketStalls.wanted(townSquare, CalendarManager.current()), "");
                checkEq("no stall fixture is planned", MarketStalls.fixtures(target()).size(), 0);
                checkEq("the prices are not read", MarketStalls.prices().size(), 0);
                // a festival day through the gathering hours, the Decorator and a colonist at a stall
                final int goodsBefore = goodsHeld();
                final BlockPos stall = townSquare.getLocationsFromTag(BuildingTownSquare.TAG_STALL).get(0);
                put(decorator, Gathering.standable(level, anchor.offset(-5, 0, 0)) == null ? anchor.offset(-5, 0, 0) : Gathering.standable(level, anchor.offset(-5, 0, 0)));
                put(offDuty.get(0), Gathering.standable(level, stall.relative(Direction.getNearest(anchor.getX() - stall.getX(), 0, anchor.getZ() - stall.getZ()), 3)));
                setTimeOfDay(IN_HOURS);
                check("it is a gathering day, in the hours", Gathering.gatheringDay(CalendarManager.current()) && Gathering.inHours(level), "");
                MarketStalls.tick(level, colony, CalendarManager.current(), clock += 1000);
                MarketStalls.tick(level, colony, CalendarManager.current(), clock += 1000);
                setTimeOfDay(MORNING);
                put(decorator, hut.getPosition().offset(0, 0, 4));
                put(offDuty.get(0), townHall.getPosition().offset(0, 0, 4));
                checkEq("nothing was sold: the Workshop holds the same goods", goodsHeld(), goodsBefore);
                check("current_balance was never written", !colony.getStatisticsManager().getStatTypes().contains(BALANCE)
                        && colony.getStatisticsManager().getStatTotal(BALANCE) == 0, String.valueOf(colony.getStatisticsManager().getStatTypes()));
                checkEq("the bridge still logged nothing after the festival day", TradePostBridge.logLines(), 0);
                final CompoundTag saved = ledger.save(new CompoundTag(), level.registryAccess());
                boolean marketKeys = false;
                for (final net.minecraft.nbt.Tag one : saved.getList("colonies", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                    marketKeys |= ((CompoundTag) one).contains("marketDay") || ((CompoundTag) one).contains("marketPaid");
                }
                check("the saved ledger has no market key", !marketKeys, "");
                checkEq("the info page has no market line", townSquare.getModule(SquareModules.INFO).read().marketState(), SquareInfoModule.MARKET_HIDDEN);
                checkEq("the market line is hidden", MarketStalls.line(townSquare), MarketStalls.Line.HIDDEN);
                checkEq("nothing was ever paid today", ledger.marketPaid(colonyId, colony.getDay()), 0);
                // the ledger's two keys in memory: a tag with them survives a save and a load, a tag without them reads as never paid
                ledger.addMarketPaid(colonyId, 7, 321);
                final DecorLedger reloaded = DecorLedger.load(ledger.save(new CompoundTag(), level.registryAccess()), level.registryAccess());
                checkEq("the market keys survive a save and a load", reloaded.market(colonyId), new MarketMath.PaidDay(7, 321));
                // a colony that is gone forgets its paid day (DecorEvents.gone), so a new one with its id starts with nothing paid
                ledger.clearMarket(colonyId);
                checkEq("a cleared record is never paid", ledger.market(colonyId), MarketMath.PaidDay.NONE);
                checkEq("... and saves no market key", DecorLedger.load(ledger.save(new CompoundTag(), level.registryAccess()), level.registryAccess())
                        .market(colonyId), MarketMath.PaidDay.NONE);
                ledger.addMarketPaid(colonyId, 7, 321);
                final CompoundTag bare = ledger.save(new CompoundTag(), level.registryAccess());
                for (final net.minecraft.nbt.Tag one : bare.getList("colonies", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                    ((CompoundTag) one).remove("marketDay");
                    ((CompoundTag) one).remove("marketPaid");
                }
                checkEq("a tag without the market keys reads as never paid", DecorLedger.load(bare, level.registryAccess()).market(colonyId), MarketMath.PaidDay.NONE);
                ledger.clearMarket(colonyId);
                invariant("without the market");
            }
        });
    }

    // ================================================================ mode with: the bridge, the prices, the stalls

    private void planBridge() {
        step(new Step("bridge: Trade Post is there, the bridge is on, one line, our prices", 1) {
            @Override
            void start() {
                final TradePostBridge bridge = MarketStalls.bridge();
                check("Trade Post's classes are there", tradePostThere(), "");
                check("the bridge is active", bridge.active(), "");
                checkEq("the bridge logged exactly one line (the version)", TradePostBridge.logLines(), 1);
                check("the self-test passes", bridge.selfTest(colony), "");
                try {
                    checkEq("the coin value is Trade Post's own", bridge.coinValue(), tpCoinValue());
                    note("NOTE a Trade Coin is {} points", bridge.coinValue());
                } catch (final ReflectiveOperationException unreadable) {
                    check("Trade Post's coin value can be read", false, unreadable.getClass().getSimpleName());
                }
                final Map<Item, Integer> prices = MarketStalls.prices();
                checkEq("six goods are priced", prices.size(), 6);
                // Q13: the Decorator also makes firework rockets and firework crates, but the stalls sell neither
                check("the Workshop's kits are eight now, and the rocket and the crate are among them",
                        Kits.all().size() >= 8 && goods().contains(Items.FIREWORK_ROCKET) && goods().contains(SeasonsBlocks.FIREWORK_CRATE_ITEM.get()),
                        String.valueOf(Kits.all().size()));
                check("neither the firework rocket nor the firework crate has a price", !prices.containsKey(Items.FIREWORK_ROCKET)
                        && !prices.containsKey(SeasonsBlocks.FIREWORK_CRATE_ITEM.get()), prices.keySet().toString());
                try {
                    for (final Map.Entry<Item, Integer> e : prices.entrySet()) {
                        final int market = tpMarketValue(new ItemStack(e.getKey()));
                        check("Trade Post's Marketplace values " + BuiltInRegistries.ITEM.getKey(e.getKey()) + " above 0", market > 0
                                && tpValue(e.getKey()) > 0, "price " + e.getValue() + ", its list " + tpValue(e.getKey()) + ", marketplaceValue " + market);
                    }
                    final List<Item> free = List.of(Gifts.GIFT.get(), SeasonsBlocks.GIFT_PILE_ITEM.get(), SeasonsBlocks.FIREWORK_CRATE_ITEM.get(),
                            SeasonsBlocks.FESTIVAL_MARKER_ITEM.get(), SeasonsBlocks.TINSEL_LEAVES_ITEM.get(), SeasonsBlocks.LARGE_STAR_ITEM.get());
                    for (final Item item : free) {
                        checkEq("Trade Post's Marketplace does not value " + BuiltInRegistries.ITEM.getKey(item), tpMarketValue(new ItemStack(item)), 0);
                    }
                    // the price ceilings against Trade Post's own measured values (one batch's ingredients + 25 %)
                    for (final Kits.Kit kit : Kits.all()) {
                        long total = 0;
                        for (final Kits.Ingredient ingredient : kit.ingredients()) {
                            int unit;
                            if (ingredient.item() != null) {
                                unit = tpValue(ingredient.item());
                            } else {
                                unit = Integer.MAX_VALUE;
                                for (final var holder : BuiltInRegistries.ITEM.getTagOrEmpty(ingredient.tag())) {
                                    unit = Math.min(unit, tpValue(holder.value()));
                                }
                            }
                            total += (long) unit * ingredient.count();
                        }
                        final int price = prices.getOrDefault(kit.output(), 0);
                        final long ceiling = (long) (total * 1.25);
                        check("the price of " + BuiltInRegistries.ITEM.getKey(kit.output()) + " keeps to its ingredients + 25 %",
                                (long) price * kit.count() <= ceiling, price + " x " + kit.count() + " against " + ceiling + " (ingredients " + total + ")");
                    }
                } catch (final ReflectiveOperationException unreadable) {
                    check("Trade Post's price list can be asked", false, unreadable.getClass().getSimpleName() + " " + unreadable.getMessage());
                }
            }
        });
    }

    private void planStallsUp() {
        final DecorWork.Outcome[] last = new DecorWork.Outcome[1];
        final int[][] before = new int[1][];
        step(new Step("stalls: the Workshop is stocked", 1) {
            @Override
            void start() {
                stockUp(20, 40);
                before[0] = stallItems();
                initialItems[0] = before[0];
                checkEq("the window is open and the bridge on: the market is wanted", MarketStalls.wanted(townSquare, CalendarManager.current()), true);
                checkEq("four stalls are planned: 60 cells", MarketStalls.fixtures(target()).size(), 60);
            }
        });
        step(decorateStep("stalls: the square goes up with its market", 800, last, () -> {
            final BlockPos any = townSquare.getLocationsFromTag(BuildingTownSquare.TAG_STALL).get(0);
            note("the first stall at {}, front {}", any.toShortString(), Direction.getNearest(anchor.getX() - any.getX(), 0, anchor.getZ() - any.getZ()));
            checkEq("four stalls stand complete", MarketStalls.standing(level, ledger, colonyId, townSquare, anchor), 4);
            int cellsOk = 0;
            int airBefore = 0;
            final DecorLedger.GroupView g = group();
            for (final BlockPos pos : MarketStalls.cells(townSquare, anchor)) {
                final DecorLedger.Entry e = g == null ? null : g.entries().get(pos);
                cellsOk += e != null && level.getBlockState(pos) == e.state() ? 1 : 0;
            }
            checkEq("every stall cell is listed and equals the world", cellsOk, 60);
            checkEq("the stalls took 24 stripped spruce posts", before[0][0] - held(Items.STRIPPED_SPRUCE_LOG), 24);
            checkEq("the stalls took 16 red wool", before[0][2] - held(Items.RED_WOOL), 16);
            checkEq("the stalls took 8 white wool", before[0][3] - held(Items.WHITE_WOOL), 8);
            check("the stalls took spruce slabs (12 for the counters, the tree may use more)", before[0][1] - held(Items.SPRUCE_SLAB) >= 12,
                    String.valueOf(before[0][1] - held(Items.SPRUCE_SLAB)));
            // the vendor's column and the lamp posts' cells are free
            int vendorBlocked = 0;
            for (final BlockPos stall : townSquare.getLocationsFromTag(BuildingTownSquare.TAG_STALL)) {
                for (int dy = 0; dy <= 2; dy++) {
                    vendorBlocked += level.getBlockState(stall.above(dy)).isAir() && ledger.at(stall.above(dy)) == null ? 0 : 1;
                }
            }
            checkEq("the tag cell and the two above it stay air at every stall", vendorBlocked, 0);
            final Set<BlockPos> footprints = MarketStalls.cells(townSquare, anchor);
            int lampTouched = 0;
            for (final BlockPos lamp : townSquare.getLocationsFromTag(BuildingTownSquare.TAG_LAMP)) {
                lampTouched += footprints.contains(lamp) ? 1 : 0;
                lampTouched += level.getBlockState(lamp).isAir() ? 0 : 1;
                final BlockPos log = lamp.below(2);
                for (final Direction side : Direction.Plane.HORIZONTAL) {
                    lampTouched += footprints.contains(log.relative(side)) ? 1 : 0;
                }
            }
            checkEq("no lamp cell and no cell beside a lamp post's log is a stall's", lampTouched, 0);
            // the footprint in the four fronts (pure geometry)
            boolean shapes = true;
            for (final Direction d : Direction.Plane.HORIZONTAL) {
                final BlockPos tag = anchor.relative(d, 9);
                final List<BlockPos> cells = MarketStalls.footprint(tag, anchor);
                final Set<BlockPos> columns = new HashSet<>();
                boolean vendor = false;
                for (final BlockPos c : cells) {
                    columns.add(new BlockPos(c.getX(), 0, c.getZ()));
                    vendor |= c.getX() == tag.getX() && c.getZ() == tag.getZ() && c.getY() <= tag.getY() + 2;
                }
                final Set<BlockPos> distinct = new HashSet<>(cells);
                final BlockPos behind = tag.relative(d, 0);
                shapes &= cells.size() == MarketStalls.STALL_CELLS && distinct.size() == cells.size() && columns.size() == 6 && !vendor
                        && cells.stream().allMatch(c -> c.getY() >= behind.getY() && c.getY() <= behind.getY() + 3);
                // the front is towards the tree: the counter row is one block nearer the tree than the tag
                int nearer = 0;
                for (final BlockPos c : cells) {
                    nearer += c.distSqr(anchor.atY(c.getY())) < tag.distSqr(anchor.atY(c.getY())) ? 1 : 0;
                }
                shapes &= nearer >= 6;
            }
            check("the footprint is 15 cells in a 3 x 2 pad round the tag, in each of the four fronts, with the vendor's column free", shapes, "");
            final BlockPos one = townSquare.getLocationsFromTag(BuildingTownSquare.TAG_STALL).get(0);
            final BlockState awning = level.getBlockState(MarketStalls.footprint(one, anchor).stream().max(java.util.Comparator.comparingInt(BlockPos::getY)).get());
            check("the awning is wool", awning.is(Blocks.RED_WOOL) || awning.is(Blocks.WHITE_WOOL), String.valueOf(awning));
            checkEq("the awnings of four stalls: 16 red, 8 white", count(s -> s.is(Blocks.RED_WOOL)) * 100 + count(s -> s.is(Blocks.WHITE_WOOL)), 1608);
            check("the tree stands", count(s -> s.is(SeasonsBlocks.FESTIVAL_TRUNK.get())) > 0, "");
            check("no 'no room' problem is raised", !ledger.squareProblem(colonyId), "");
            checkEq("the stalls are in the group (the market is part of the set)", g == null ? -1 : g.entries().keySet().containsAll(footprints) ? 1 : 0, 1);
            invariant("the square with its market");
            airBefore = 0;
        }));
    }

    // ================================================================ the sales

    private int balance() {
        return colony.getStatisticsManager().getStatTotal(BALANCE);
    }

    private int paidToday() {
        return ledger.marketPaid(colonyId, colony.getDay());
    }

    private void planFirstSale() {
        step(new Step("sale: a festival day, the first sale pays exactly its price", 220) {
            @Override
            void start() {
                setDate(FESTIVAL_DAY);
                setTimeOfDay(MORNING);
            }

            @Override
            boolean tick() {
                return t >= 200;
            }

            @Override
            void end(final boolean done) {
                day0 = colony.getDay();
                note("the colony day is {}", day0);
                SquareInfoModule.Info info = townSquare.getModule(SquareModules.INFO).read();
                checkEq("the info page says the market is open (on a festival day with stalls and goods)", info.marketState(), SquareInfoModule.MARKET_OPEN);
                checkEq("... with nothing paid yet and a cap of four coins", info.marketPaid() + "/" + info.marketCap(), "0/" + 4 * MarketStalls.bridge().coinValue());
                final int goods = goodsHeld();
                final int cash = balance();
                final Map<Item, Integer> prices = MarketStalls.prices();
                final BuildingDecorator workshop = hut;
                final Map<Item, Integer> heldBefore = new java.util.LinkedHashMap<>();
                for (final Item item : goods()) {
                    heldBefore.put(item, held(item));
                }
                final MarketStalls.Sale sale = MarketStalls.sellOne(level, colony, townSquare, workshop, day0);
                check("one good was sold", sale != null, String.valueOf(sale));
                if (sale == null) {
                    return;
                }
                checkEq("the balance rose by exactly the price", balance() - cash, sale.price());
                checkEq("the price is the one of our file", sale.price(), prices.get(sale.item()));
                checkEq("the Workshop holds exactly one good fewer", goods - goodsHeld(), 1);
                checkEq("... of the good that was sold", heldBefore.get(sale.item()) - held(sale.item()), 1);
                checkEq("the paid record is the price", paidToday(), sale.price());
                info = townSquare.getModule(SquareModules.INFO).read();
                checkEq("the info line says what was paid today", info.marketPaid() + "/" + info.marketState(), sale.price() + "/" + SquareInfoModule.MARKET_OPEN);
                // the packet reads back field for field, the market line with it
                final net.minecraft.network.RegistryFriendlyByteBuf buf = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), server.registryAccess());
                townSquare.getModule(SquareModules.INFO).serializeToView(buf);
                final SquareInfoModuleView view = new SquareInfoModuleView();
                view.deserialize(buf);
                check("the info page's packet carries the market line to the client's view", buf.readableBytes() == 0
                        && view.info().marketState() == SquareInfoModule.MARKET_OPEN && view.info().marketPaid() == sale.price(), String.valueOf(view.info().marketPaid()));
                invariant("after a sale");
            }
        });
    }

    /** The state a "nothing sold" row must leave as it was: the balance, the paid record and the goods. */
    private String snapshotState() {
        return balance() + "/" + paidToday() + "/" + goodsHeld();
    }

    private static Direction frontOf(final BlockPos stall, final BlockPos anchor) {
        return Direction.getNearest(anchor.getX() - stall.getX(), 0, anchor.getZ() - stall.getZ());
    }

    private BlockPos stall(final int i) {
        final List<BlockPos> tags = new ArrayList<>(townSquare.getLocationsFromTag(BuildingTownSquare.TAG_STALL));
        tags.sort(java.util.Comparator.naturalOrder());
        return tags.get(i);
    }

    private BlockPos buyerSpot(final BlockPos stall) {
        final BlockPos spot = Gathering.standable(level, stall.relative(frontOf(stall, anchor), 3));
        return spot == null ? stall.relative(frontOf(stall, anchor), 3) : spot;
    }

    private BlockPos hostSpot() {
        final BlockPos spot = Gathering.standable(level, anchor.offset(-5, 0, 0));
        return spot == null ? anchor.offset(-5, 0, 0) : spot;
    }

    private void away() {
        put(decorator, hut.getPosition().offset(0, 0, 4));
        for (int i = 0; i < offDuty.size(); i++) {
            put(offDuty.get(i), townHall.getPosition().offset(i, 0, 4));
        }
    }

    /** Moves the Decorator and a buyer where the row wants them, sets the hour, and returns; the caller ticks. */
    private void arrange(final boolean host, final boolean buyer, final int hour) {
        away();
        if (host) {
            put(decorator, hostSpot());
        }
        if (buyer) {
            put(offDuty.get(0), buyerSpot(stall(0)));
        }
        setTimeOfDay(hour);
    }

    /** One look of the festival director's tick at this colony; the world is put back (nobody at the stalls, the morning) at once. */
    private void tickAt(final long gameTime) {
        MarketStalls.tick(level, colony, CalendarManager.current(), gameTime);
        setTimeOfDay(MORNING);
        away();
    }

    private void row(final String what, final boolean host, final boolean buyer, final int hour) {
        arrange(host, buyer, hour);
        final String before = snapshotState();
        tickAt(clock += 1000);
        checkEq(what + ": nothing sold, nothing paid, nothing written", snapshotState(), before);
    }

    private void planTickMatrix() {
        step(new Step("tick: every row that sells nothing, the switch, then one buyer at one stall", 1) {
            @Override
            void start() {
                check("it is a gathering day with the four stalls standing", Gathering.gatheringDay(CalendarManager.current())
                        && MarketStalls.standing(level, ledger, colonyId, townSquare, anchor) == 4, "");
                arrange(true, true, IN_HOURS);
                check("the right conditions hold: hours, no rain, the Decorator and a buyer at the stalls", Gathering.inHours(level) && !level.isRaining(), "");
                away();
                setTimeOfDay(MORNING);

                setDate(DECOR_DAY);
                row("not a festival day (the window is open, the days are not)", true, true, IN_HOURS);
                setDate(FESTIVAL_DAY);
                row("outside the gathering hours", true, true, MORNING);
                row("the Decorator is not at the square", false, true, IN_HOURS);
                row("no other colonist at a stall", true, false, IN_HOURS);
                // a square of level 3 has no market
                final java.lang.reflect.Field levelField = levelField();
                setLevel(townSquare, 3);
                row("a square of level 3", true, true, IN_HOURS);
                checkEq("... and its info page has no market line", townSquare.getModule(SquareModules.INFO).read().marketState(), SquareInfoModule.MARKET_HIDDEN);
                setLevel(townSquare, 5);
                // the order Bare wants no set, so no market
                townSquare.settings().getSetting(BuildingTownSquare.ORDER).set(SquareSets.Order.BARE.key());
                row("the order Bare", true, true, IN_HOURS);
                townSquare.settings().getSetting(BuildingTownSquare.ORDER).set(SquareSets.Order.FOLLOW.key());
                check("level 5 and Follow again: the market is wanted", MarketStalls.wanted(townSquare, CalendarManager.current()) && levelField != null, "");

                // the switch: off stops payment (the stalls stand, the page has no line), on sells again
                final int stallsBefore = MarketStalls.standing(level, ledger, colonyId, townSquare, anchor);
                MarketStalls.setBridge(new TradePostBridge(() -> false, () -> true));
                row("the switch tradePostBridge off", true, true, IN_HOURS);
                checkEq("... the stalls stand all the same", MarketStalls.standing(level, ledger, colonyId, townSquare, anchor), stallsBefore);
                checkEq("... the info page has no market line", townSquare.getModule(SquareModules.INFO).read().marketState(), SquareInfoModule.MARKET_HIDDEN);
                checkEq("... and the market plans nothing more", MarketStalls.fixtures(target()).size(), 0);
                MarketStalls.setBridge(null);
                check("the switch back on: the real bridge is active", MarketStalls.bridge().active(), "");

                // a buyer at one stall: exactly one sale, not a second inside the interval, one more after it
                arrange(true, true, IN_HOURS);
                final int cash = balance();
                final int goods = goodsHeld();
                final long paid = paidToday();
                final long when = clock += 1000;
                MarketStalls.tick(level, colony, CalendarManager.current(), when);
                final int price = balance() - cash;
                check("a buyer at a stall: one sale, paid exactly its price, one good fewer", price > 0 && goods - goodsHeld() == 1 && paidToday() - paid == price,
                        price + " paid, " + (goods - goodsHeld()) + " goods, record +" + (paidToday() - paid));
                MarketStalls.tick(level, colony, CalendarManager.current(), when + 10);
                checkEq("the same stall sells nothing 10 ticks later", balance() - cash, price);
                MarketStalls.tick(level, colony, CalendarManager.current(), when + MarketStalls.SALE_TICKS);
                check("... and one more once the interval is over", balance() - cash > price && goods - goodsHeld() == 2, (balance() - cash) + " paid in all");
                setTimeOfDay(MORNING);
                away();
                invariant("after the ticks");
            }
        });
    }

    private java.lang.reflect.Field levelField() {
        try {
            final java.lang.reflect.Field f = com.minecolonies.core.colony.buildings.AbstractSchematicProvider.class.getDeclaredField("buildingLevel");
            f.setAccessible(true);
            return f;
        } catch (final ReflectiveOperationException unreachable) {
            check("the level of the square can be set", false, unreachable.getClass().getSimpleName());
            return null;
        }
    }

    private void setLevel(final IBuilding building, final int lv) {
        try {
            levelField().setInt(building, lv);
        } catch (final ReflectiveOperationException | RuntimeException unreachable) {
            check("the level of the square can be set to " + lv, false, unreachable.getClass().getSimpleName());
        }
    }

    // ---------------------------------------------------------------- a payment that fails, the edge of the balance

    private void planSwitchAndFailures() {
        step(new Step("failures: a payment that throws, one that does not move the balance, amounts the clamp refuses", 1) {
            @Override
            void start() {
                final int goods = goodsHeld();
                final int cash = balance();
                final long paid = paidToday();
                final int lines = TradePostBridge.logLines();
                final TradePostBridge throwing = new TradePostBridge(() -> true, () -> true) {
                    @Override
                    protected void write(final IColony c, final int amount) {
                        throw new IllegalStateException("the probe makes the payment fail");
                    }
                };
                MarketStalls.setBridge(throwing);
                check("the sale is refused and nothing is sold", MarketStalls.sellOne(level, colony, townSquare, hut, colony.getDay()) == null, "");
                check("the bridge fell back", throwing.fellBack() && !throwing.active(), "");
                checkEq("... with exactly one log line", TradePostBridge.logLines() - lines, 1);
                checkEq("the good is back in the Workshop", goodsHeld(), goods);
                checkEq("the paid record is as it was", paidToday(), (int) paid);
                checkEq("the balance is as it was", balance(), cash);
                check("the bridge stays off: no further sale, no further line", MarketStalls.sellOne(level, colony, townSquare, hut, colony.getDay()) == null
                        && TradePostBridge.logLines() - lines == 1, "");
                checkEq("the market line is gone with it", townSquare.getModule(SquareModules.INFO).read().marketState(), SquareInfoModule.MARKET_HIDDEN);

                final TradePostBridge silent = new TradePostBridge(() -> true, () -> true) {
                    @Override
                    protected void write(final IColony c, final int amount) {
                        // the statistic is "renamed": nothing moves
                    }
                };
                MarketStalls.setBridge(silent);
                check("a payment that does not move the balance is not a sale", MarketStalls.sellOne(level, colony, townSquare, hut, colony.getDay()) == null, "");
                check("the bridge turned itself off with one more line", silent.fellBack() && TradePostBridge.logLines() - lines == 2, String.valueOf(TradePostBridge.logLines() - lines));
                checkEq("the good is back again", goodsHeld(), goods);
                checkEq("the paid record is as it was again", paidToday(), (int) paid);
                checkEq("the balance is as it was again", balance(), cash);

                // the self-test ([B] 7.1): a balance that cannot be read turns the bridge off before any stall is planned
                final TradePostBridge unreadable = new TradePostBridge(() -> true, () -> true) {
                    @Override
                    protected int read(final IColony c) {
                        throw new IllegalStateException("the probe makes the balance unreadable");
                    }
                };
                MarketStalls.setBridge(unreadable);
                check("a balance the self-test cannot read: no market is wanted", !MarketStalls.wanted(townSquare, CalendarManager.current()), "");
                check("... the bridge fell back with one more line, and no stall is planned", unreadable.fellBack() && TradePostBridge.logLines() - lines == 3
                        && MarketStalls.fixtures(target()).isEmpty(), String.valueOf(TradePostBridge.logLines() - lines));
                checkEq("... and nothing was sold or paid", snapshotState(), cash + "/" + paid + "/" + goods);

                MarketStalls.setBridge(null);
                final TradePostBridge real = MarketStalls.bridge();
                final int quiet = TradePostBridge.logLines();
                check("a payment of 0, a negative one and one over the single maximum are refused", !real.credit(colony, 0) && !real.credit(colony, -40)
                        && !real.credit(colony, MarketMath.MAX_SINGLE + 1), "");
                check("... without a line and with the bridge still on", TradePostBridge.logLines() == quiet && real.active(), "");
                // the edge of an int: 50 points of room are left, so 51 is refused and 50 is paid
                final int day = colony.getDay();
                final int room = 50;
                final int bumped = Integer.MAX_VALUE - room - balance();
                colony.getStatisticsManager().incrementBy(BALANCE, bumped, day);
                check("the balance is 50 below an int's end", balance() == Integer.MAX_VALUE - room, String.valueOf(balance()));
                check("a payment that would pass an int is refused, and nothing is written", !real.credit(colony, room + 1) && balance() == Integer.MAX_VALUE - room
                        && real.active(), "");
                check("one that just fits is paid", real.credit(colony, room) && balance() == Integer.MAX_VALUE, String.valueOf(balance()));
                colony.getStatisticsManager().incrementBy(BALANCE, -(bumped + room), day);
                checkEq("the balance is put back", balance(), cash);
                check("the real bridge is still on and logged nothing", real.active() && TradePostBridge.logLines() == quiet, "");
                invariant("after the failures");
            }
        });
    }

    // ---------------------------------------------------------------- the cap, the next day, an earlier one

    private void planSellOn() {
        step(new Step("cap: selling on stops at four coins, the next day has a fresh cap, an earlier one sells nothing", 1) {
            @Override
            void start() {
                final int coin = MarketStalls.bridge().coinValue();
                final int cap = MarketMath.cap(4, coin);
                final int cash0 = balance();
                final int paid0 = paidToday();
                final Item crate = SeasonsBlocks.FIREWORK_CRATE_ITEM.get();
                final int rockets0 = held(Items.FIREWORK_ROCKET);
                final int crates0 = held(crate);
                check("the Workshop holds rockets and crates (neither is for sale)", rockets0 > 8 && crates0 > 8, rockets0 + " rockets, " + crates0 + " crates");
                int sold = 0;
                int sum = 0;
                int stepsOver = 0;
                MarketStalls.Sale sale;
                while ((sale = MarketStalls.sellOne(level, colony, townSquare, hut, colony.getDay())) != null && sold < 400) {
                    sold++;
                    sum += sale.price();
                    stepsOver += paidToday() > cap ? 1 : 0;
                }
                checkEq("no firework rocket was sold in " + sold + " sales", held(Items.FIREWORK_ROCKET), rockets0);
                checkEq("no firework crate was sold in " + sold + " sales", held(crate), crates0);
                note("{} sales to the cap, {} points", sold, sum);
                checkEq("the paid record never passed the cap", stepsOver, 0);
                check("the record ends below the cap and above the cap less the cheapest good", paidToday() <= cap && paidToday() > cap - 500, paidToday() + " of " + cap);
                checkEq("the balance is the sum of the prices sold", balance() - cash0, sum);
                checkEq("the record is what it was plus the sum", paidToday() - paid0, sum);
                boolean fits = false;
                final Map<Item, Integer> prices = MarketStalls.prices();
                for (final Kits.Kit kit : Kits.all()) {
                    fits |= prices.containsKey(kit.output()) && held(kit.output()) > Math.max(1, kit.count())
                            && MarketMath.canSell(paidToday(), cap, prices.get(kit.output()));
                }
                check("no priced good the Workshop could spare still fits under the cap", !fits, "");
                final String stateAtCap = snapshotState();
                arrange(true, true, IN_HOURS);
                tickAt(clock += 1000);
                checkEq("a buyer at a stall at the cap: nothing is sold", snapshotState(), stateAtCap);
                final SquareInfoModule.Info info = townSquare.getModule(SquareModules.INFO).read();
                check("the info page shows the day's money against the cap", info.marketCap() == cap && info.marketPaid() == paidToday()
                        && info.marketState() == SquareInfoModule.MARKET_OPEN, info.marketPaid() + "/" + info.marketCap() + " state " + info.marketState());

                // the next colony day: a fresh cap
                final long tomorrow = colony.getDay() + 1L;
                final int cash1 = balance();
                final MarketStalls.Sale next = MarketStalls.sellOne(level, colony, townSquare, hut, tomorrow);
                check("the next colony day sells again", next != null && ledger.marketPaid(colonyId, tomorrow) == next.price() && balance() - cash1 == next.price(),
                        String.valueOf(next));
                checkEq("the record is for the new day", ledger.market(colonyId).day(), tomorrow);
                final String state = snapshotState();
                check("an earlier day than the stored one sells nothing", MarketStalls.sellOne(level, colony, townSquare, hut, tomorrow - 1) == null
                        && snapshotState().equals(state), "");
                invariant("after the cap");
            }
        });
    }

    // ---------------------------------------------------------------- a foreign block on one pad

    /** The square's whole group taken down by the removal rule (in slices); true once nothing is listed. */
    private boolean takeAllDown() {
        for (int i = 0; i < 20 && groupSize() > 0; i++) {
            DecorWork.takeDown(level, colonyId, owner(), stock(), day(), ledger);
        }
        ledger.dropEmptyGroups(colonyId);
        ledger.flush(level);
        return groupSize() == 0;
    }

    private final int[][] initialItems = new int[1][];

    private void planForeignBlock() {
        final DecorWork.Outcome[] last = new DecorWork.Outcome[1];
        final BlockPos[] stone = new BlockPos[1];
        step(new Step("foreign block: the square comes down, a stone stands on one pad", 1) {
            @Override
            void start() {
                check("the group is taken down by the removal rule", takeAllDown(), groupSize() + " left");
                int stray = 0;
                for (final BlockPos pos : MarketStalls.cells(townSquare, anchor)) {
                    stray += level.getBlockState(pos).isAir() ? 0 : 1;
                }
                checkEq("every stall cell is air again", stray, 0);
                final BlockPos tag = stall(1);
                final BlockPos counter = MarketStalls.footprint(tag, anchor).stream().filter(c -> c.getY() == tag.getY() && !c.equals(tag))
                        .min(java.util.Comparator.comparingDouble((BlockPos c) -> c.distSqr(anchor.atY(c.getY()))).thenComparing(c -> c)).get();
                stone[0] = counter;
                set(counter, Blocks.STONE.defaultBlockState());
                check("a stone stands in the second stall's counter", level.getBlockState(counter).is(Blocks.STONE), counter.toShortString());
                check("that stall is left out of the plan: three stalls, 45 cells", MarketStalls.fixtures(target()).size() == 45,
                        String.valueOf(MarketStalls.fixtures(target()).size()));
            }
        });
        step(decorateStep("foreign block: the square goes up again", 800, last, () -> {
            checkEq("three stalls stand", MarketStalls.standing(level, ledger, colonyId, townSquare, anchor), 3);
            int listed = 0;
            for (final BlockPos pos : MarketStalls.footprint(stall(1), anchor)) {
                listed += ledger.at(pos) != null ? 1 : 0;
            }
            checkEq("not one cell of that stall is listed", listed, 0);
            check("the stone is still there, unlisted", level.getBlockState(stone[0]).is(Blocks.STONE) && ledger.at(stone[0]) == null, "");
            check("the tree went up all the same, and no 'no room' notice", count(s -> s.is(SeasonsBlocks.FESTIVAL_TRUNK.get())) > 0
                    && !ledger.squareProblem(colonyId), "");
            invariant("with a foreign block on one pad");
        }));
        step(new Step("foreign block: the cap is three coins; the stone goes, the fourth stall comes at the next tending", 1) {
            @Override
            void start() {
                final int coin = MarketStalls.bridge().coinValue();
                final long freshDay = colony.getDay() + 5L;
                final int cash0 = balance();
                int sum = 0;
                int sold = 0;
                MarketStalls.Sale sale;
                while ((sale = MarketStalls.sellOne(level, colony, townSquare, hut, freshDay)) != null && sold < 400) {
                    sold++;
                    sum += sale.price();
                }
                final int cap = MarketMath.cap(3, coin);
                check("the stalls paid up to three coins and no more", ledger.marketPaid(colonyId, freshDay) <= cap && ledger.marketPaid(colonyId, freshDay) > cap - 500,
                        ledger.marketPaid(colonyId, freshDay) + " of " + cap);
                checkEq("the balance is the sum of the prices", balance() - cash0, sum);
                set(stone[0], Blocks.AIR.defaultBlockState());
                final long day = day();
                check("a market wanted and a stall not whole: the square is not 'done' (open)", MarketStalls.open(group(), target()), "");
                check("... and the Decorator's choice of what to decorate picks the square again", picksSquare(), "");
                final DecorWork.Outcome tended = DecorWork.tend(level, colonyId, target(), hut.getBuildingLevel(), stock(), day, ledger);
                note("tending: {}", tended.status());
                checkEq("the fourth stall stands after one tending visit", MarketStalls.standing(level, ledger, colonyId, townSquare, anchor), 4);
                check("... and the square is done again (not open, not picked)", !MarketStalls.open(group(), target()) && !picksSquare(), "");
                invariant("after the fourth stall");
            }
        });
    }

    /** True when the Decorator's choice of the next place to decorate is the square (the {@code squareDone} rule, reached through nextToDecorate). */
    private boolean picksSquare() {
        for (int i = 0; i < 6; i++) {
            final DecorWork.Target t = DecorWork.nextToDecorate(level, colony, hut.getBuildingLevel(), hut.getPosition(), ledger, stock(), day(), townSquare);
            if (t == null) {
                return false;
            }
            if (t.owner().equals(owner())) {
                return true;
            }
            // the town hall (and the Workshop) come before the square: decorate them, the next choice is the square's
            DecorWork.decorate(level, colonyId, t, hut.getBuildingLevel(), stock(), day(), ledger);
        }
        return false;
    }

    // ---------------------------------------------------------------- the window ends

    private void planWindowEnds() {
        step(new Step("window end: the set goes, the stalls with it, every stall item is back in the Workshop", 600, true) {
            @Override
            void start() {
                setDate(OUT_OF_SEASON);
            }

            @Override
            boolean tick() {
                if (t % 20 == 0) {
                    DecorWork.decorate(level, colonyId, target(), hut.getBuildingLevel(), stock(), day(), ledger);
                }
                return t % 20 == 0 && groupSize() == 0;
            }

            @Override
            void end(final boolean done) {
                check("the group is gone: the window is over", done, groupSize() + " cells left");
                int stray = 0;
                for (final BlockPos pos : MarketStalls.cells(townSquare, anchor)) {
                    stray += level.getBlockState(pos).isAir() ? 0 : 1;
                }
                checkEq("every stall cell is air again", stray, 0);
                checkEq("no market is wanted out of season", MarketStalls.wanted(townSquare, CalendarManager.current()), false);
                final int[] items = stallItems();
                check("every stall item is back in the Workshop: the posts, the slabs and the wool as stocked", java.util.Arrays.equals(items, initialItems[0]),
                        java.util.Arrays.toString(items) + " vs " + java.util.Arrays.toString(initialItems[0]));
                checkEq("the info page says the market is closed until the next festival", townSquare.getModule(SquareModules.INFO).read().marketState(),
                        SquareInfoModule.MARKET_CLOSED);
                invariant("after the window");
            }
        });
    }

    // ================================================================ restart

    private void planRestartSave() {
        step(new Step("restart-save: a festival day, three sales short of the cap, then the world saved", 300) {
            @Override
            void start() {
                setDate(FESTIVAL_DAY);
                setTimeOfDay(MORNING);
            }

            @Override
            boolean tick() {
                return t >= 200;
            }

            @Override
            void end(final boolean done) {
                day0 = colony.getDay();
                int sum = 0;
                for (int i = 0; i < 3; i++) {
                    final MarketStalls.Sale sale = MarketStalls.sellOne(level, colony, townSquare, hut, day0);
                    check("sale " + (i + 1) + " goes through", sale != null, String.valueOf(sale));
                    sum += sale == null ? 0 : sale.price();
                }
                checkEq("the record is what was paid", paidToday(), sum);
                check("short of the cap", sum < MarketMath.cap(4, MarketStalls.bridge().coinValue()), String.valueOf(sum));
                setTimeOfDay(MORNING);
                final CompoundTag facts = new CompoundTag();
                facts.putInt("colony", colonyId);
                facts.putLong("anchor", anchor.asLong());
                facts.putInt("day", colony.getDay());
                facts.putInt("paid", paidToday());
                facts.putInt("balance", balance());
                facts.putInt("goods", goodsHeld());
                facts.putInt("coin", MarketStalls.bridge().coinValue());
                facts.putInt("groupSize", groupSize());
                writeFacts(facts);
                note("saved: day {}, {} paid, balance {}", colony.getDay(), paidToday(), balance());
            }
        });
        step(new Step("restart-save: the world is saved", 80) {
            @Override
            boolean tick() {
                return t >= 40;
            }
        });
    }

    private void writeFacts(final CompoundTag facts) {
        try {
            NbtIo.writeCompressed(facts, server.getWorldPath(LevelResource.ROOT).resolve(RESTART_FILE));
        } catch (final java.io.IOException unwritable) {
            check("the facts file is written", false, "");
        }
    }

    private void planRestartLoad(final boolean gone) {
        final CompoundTag[] facts = new CompoundTag[1];
        step(new Step((gone ? "restart-gone" : "restart-mid") + ": the colony again", 2400) {
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
                if (colony == null || !DecorEvents.managerLoaded() || FestivalDirector.polls() < 3) {
                    return false;
                }
                for (final IBuilding b : colony.getServerBuildingManager().getBuildings().values()) {
                    if (b instanceof BuildingTownSquare square) {
                        townSquare = square;
                    } else if (b instanceof BuildingDecorator d) {
                        hut = d;
                    }
                }
                townHall = colony.getServerBuildingManager().getTownHall();
                anchor = BlockPos.of(facts[0].getLong("anchor"));
                return townSquare != null && hut != null && Squares.treeCell(townSquare) != null;
            }

            @Override
            void end(final boolean done) {
                check("the colony, the Workshop and the square are back after the restart", done && colony != null && townSquare != null && hut != null, "");
                if (!done) {
                    finish();
                    return;
                }
                colonyId = colony.getID();
                ledger = DecorLedger.get(level);
                quietWorld();
                setDate(FESTIVAL_DAY);
                setTimeOfDay(MORNING);
                decorator = hut.getModule(DecoratorModules.WORK).getAssignedCitizen().isEmpty() ? null
                        : hut.getModule(DecoratorModules.WORK).getAssignedCitizen().get(0);
                for (final ICitizenData c : colony.getCitizenManager().getCitizens()) {
                    if (c != decorator) {
                        offDuty.add(c);
                    }
                }
                for (int cx = -6; cx <= 6; cx++) {
                    for (int cz = -6; cz <= 6; cz++) {
                        level.setChunkForced(cx, cz, true);
                    }
                }
            }
        });
        step(new Step((gone ? "restart-gone" : "restart-mid") + ": what the first run left", 80, true) {
            @Override
            boolean tick() {
                return t >= 40;
            }

            @Override
            void end(final boolean done) {
                if (gone) {
                    afterGone(facts[0]);
                } else {
                    afterRestart(facts[0]);
                }
            }
        });
    }

    private void afterRestart(final CompoundTag facts) {
        check("Trade Post is there again", tradePostThere() && MarketStalls.bridge().active(), "");
        checkEq("the bridge logged its one line", TradePostBridge.logLines(), 1);
        checkEq("the colony day is the same after the restart (it is saved with the colony)", colony.getDay(), facts.getInt("day"));
        checkEq("the paid record is read back: the day", ledger.market(colonyId).day(), (long) facts.getInt("day"));
        checkEq("the paid record is read back: the amount", ledger.market(colonyId).paid(), facts.getInt("paid"));
        checkEq("the balance is as it was", balance(), facts.getInt("balance"));
        checkEq("the Workshop holds the same goods", goodsHeld(), facts.getInt("goods"));
        checkEq("the four stalls still stand", MarketStalls.standing(level, ledger, colonyId, townSquare, anchor), 4);
        checkEq("the group has the same cells", groupSize(), facts.getInt("groupSize"));
        // the restart did not pay anything: the balance and the record are what was saved (above); a buyer at a stall sells at once?
        final int coin = facts.getInt("coin");
        final int cap = MarketMath.cap(4, coin);
        final int day = facts.getInt("day");
        final int cash0 = balance();
        final int paid0 = facts.getInt("paid");
        int sum = 0;
        int sold = 0;
        MarketStalls.Sale sale;
        while ((sale = MarketStalls.sellOne(level, colony, townSquare, hut, day)) != null && sold < 400) {
            sold++;
            sum += sale.price();
        }
        note("{} more sales, {} points", sold, sum);
        check("selling on stops at the cap with both runs together", paid0 + sum <= cap && paid0 + sum > cap - 500, (paid0 + sum) + " of " + cap);
        checkEq("the record is the first run's plus this run's", ledger.market(colonyId).paid(), paid0 + sum);
        checkEq("the balance is what it was plus the prices sold now: nothing was paid twice", balance() - cash0, sum);
        checkEq("the Workshop holds one good fewer for each sale", facts.getInt("goods") - goodsHeld(), sold);
        final CompoundTag next = facts.copy();
        next.putInt("paid", ledger.market(colonyId).paid());
        next.putInt("balance", balance());
        next.putInt("goods", goodsHeld());
        writeFacts(next);
        invariant("after the restart");
    }

    private void afterGone(final CompoundTag facts) {
        check("Trade Post is gone (Class.forName fails)", !tradePostThere(), "");
        check("the bridge is not active", !MarketStalls.bridge().active(), "");
        checkEq("the bridge logged nothing", TradePostBridge.logLines(), 0);
        check("the world loaded with the stalls in the ledger: the group is as it was", groupSize() == facts.getInt("groupSize"), String.valueOf(groupSize()));
        check("the statistic stays in the colony data as a plain key, with its total", colony.getStatisticsManager().getStatTypes().contains(BALANCE)
                && balance() == facts.getInt("balance"), balance() + " vs " + facts.getInt("balance"));
        checkEq("the ledger keeps the paid record", ledger.market(colonyId).paid(), facts.getInt("paid"));
        checkEq("... and its day", ledger.market(colonyId).day(), (long) facts.getInt("day"));
        checkEq("the stalls still stand until the group comes down", MarketStalls.standing(level, ledger, colonyId, townSquare, anchor), 4);
        final String before = snapshotState();
        check("no sale without Trade Post", MarketStalls.sellOne(level, colony, townSquare, hut, colony.getDay()) == null, "");
        arrange(true, true, IN_HOURS);
        tickAt(clock += 1000);
        checkEq("a festival day in the hours with a buyer at a stall: nothing sold, nothing paid", snapshotState(), before);
        checkEq("the info page has no market line", townSquare.getModule(SquareModules.INFO).read().marketState(), SquareInfoModule.MARKET_HIDDEN);
        checkEq("the market plans nothing", MarketStalls.fixtures(DecorWork.Target.townSquare(townSquare, anchor)).size(), 0);
        checkEq("the prices are not read", MarketStalls.prices().size(), 0);
        checkEq("still no line from the bridge", TradePostBridge.logLines(), 0);
        invariant("without Trade Post, after a payout");
    }
}
