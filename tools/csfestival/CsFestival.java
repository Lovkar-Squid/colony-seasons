package me.lovkar.seasons.csfestival;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.permissions.Action;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
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
import me.lovkar.seasons.calendar.CalendarManager;
import me.lovkar.seasons.calendar.DateSource;
import me.lovkar.seasons.calendar.Season;
import me.lovkar.seasons.colony.BuildingDecorator;
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
import net.neoforged.fml.common.Mod;
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
 *       off-duty ones); New Year dusk fireworks from stock (the big show, a sparkle, nothing without a player); the
 *       midnight show with the fake clock stepping over midnight; the season line; three game days of a forced
 *       Winter Festival;</li>
 *   <li>nomixin: the mod jar built without its mixin (prepare.sh): it loads, the mixin is absent and never asked, the
 *       off-duty gathering works, the workers stay at work, everyone is in bed after bedtime;</li>
 *   <li>voyager: Voyager 0.4.0 next to the mod: both mixins are in CitizenAI, the workers come on the everyone day,
 *       and the Astronomer works at night while the others sleep;</li>
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
    /** MineColonies' bedtime: the citizen state SLEEP and the states of its sleep AI. */
    private static final Set<String> BEDTIME_STATES = Set.of("SLEEP", "WALKING_HOME", "FIND_BED", "SLEEPING");
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
    private boolean present = true;

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
                planMidnight();
                planSeasonLine();
                planForcedRun();
            }
            case "nomixin" -> {
                planSetup(false);
                planTree();
                planGathering(false);
            }
            case "voyager" -> {
                planSetup(true);
                planTree();
                planGathering(true);
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
                marker = new BlockPos(2, ground + 1, 32);
                final BlockState m = SeasonsBlocks.FESTIVAL_MARKER.get().defaultBlockState().setValue(FestivalMarkerBlock.FACING, Direction.NORTH);
                level.setBlock(marker, m, Block.UPDATE_ALL);
                SeasonsBlocks.FESTIVAL_MARKER.get().setPlacedBy(level, marker, m, owner, new ItemStack(SeasonsBlocks.FESTIVAL_MARKER_ITEM.get()));
                checkEq("the marker is the colony's square", ledger.marker(colonyId), marker);
                check("[V] the marker's block entity is a blueprint-data provider (a leisure site can stroll in it)",
                        level.getBlockEntity(marker) instanceof com.ldtteam.structurize.blockentities.interfaces.IBlueprintDataProviderBE be
                                && be.getInWorldCorners().getA().equals(marker.offset(-6, -1, -6)), "");
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
                    last = DecorWork.decorate(level, colonyId, DecorWork.Target.square(marker, Direction.NORTH, PieceLoader.PACK), 3,
                            DecorPlacer.Stock.FREE, day(), ledger);
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
                final BlockPos log = firstOf(s -> s.is(BlockTags.LOGS));
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
                final InteractionResult again = click(one, firstOf(s -> s.is(BlockTags.LOGS)));
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
                final BlockPos own = marker.offset(10, 0, 8);
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
                level.setBlock(own, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
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
            n[1] += dist(c) <= WITHIN ? 1 : 0;
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
        final Set<String> workerStates = new LinkedHashSet<>();
        step(new Step("gathering: 25 Dec, the morning before the hours", 700, true) {
            @Override
            void start() {
                setDate(FESTIVAL_DAY);
                nextDayAt(BEFORE_HOURS);
                near.clear();
                brought0[0] = Gathering.broughtWorkers();
                calls0[0] = Gathering.mixinCalls();
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
            int hosting0;
            int handed0;
            int songs0;
            int trips0;
            final Set<String> statusKeys = new LinkedHashSet<>();

            @Override
            void start() {
                hosting0 = job() == null ? 0 : job().visits(EntityAIWorkDecorator.DecoratorState.HOSTING);
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
                    leisure |= colony.getServerBuildingManager().getLeisureSites().contains(marker);
                    if (job() != null) {
                        statusKeys.add(job().statusKey());
                    }
                }
                return time >= SeasonsConfig.DEFAULT_GATHER_UNTIL_TICK - 40;
            }

            @Override
            void end(final boolean done) {
                check("the off-duty colonists stand within " + WITHIN + " blocks of the tree in the hours", mostlyNear(offDuty), nearText(offDuty));
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
                check("[V] the marker is one of the colony's leisure sites in the hours", leisure, "");
                check("the song played", Gathering.songs() > songs0, (Gathering.songs() - songs0) + " times");
                final int hosting = job() == null ? 0 : job().visits(EntityAIWorkDecorator.DecoratorState.HOSTING) - hosting0;
                final int handed = job() == null ? 0 : job().giftsHanded() - handed0;
                check("the Decorator hosts at the tree and hands colonists gifts (hearts, a line)", hosting > 0 && handed > 0,
                        hosting + " hosting visits, " + handed + " gifts, status '" + (job() == null ? "-" : job().status()) + "'");
                check("his status line in the hours is the hosting one (a lang key)", statusKeys.contains(DecoratorTexts.HOSTING), statusKeys.toString());
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
                check("the marker is off the leisure sites again", !colony.getServerBuildingManager().getLeisureSites().contains(marker), "");
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
                boolean theirs = false;
                for (final Method m : CitizenAI.class.getDeclaredMethods()) {
                    ours |= m.getName().contains("comeToTheGathering");
                    theirs |= m.getName().contains("keepTheNightWatch");
                }
                check("our mixin is applied", ours, "");
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
        dusk("the first dusk of the real day: the big show from stock", true, Fireworks.SHOW_ROCKETS, 20);
        dusk("the next dusk the same real day: a sparkle", true, Fireworks.SPARKLE_ROCKETS, 0);
        dusk("a dusk with nobody in the colony: nothing", false, 0, 0);
    }

    private void dusk(final String name, final boolean player, final int expected, final int stock) {
        step(new Step("fireworks: " + name, 1200, true) {
            int before;
            int shows0;
            int rockets0;

            @Override
            void start() {
                setDate(NEW_YEAR_EVE);
                present = player;
                if (stock > 0) {
                    addRockets(stock);
                }
                nextDayAt(12300);
                before = rocketsInStock();
                shows0 = Fireworks.shows();
                rockets0 = Fireworks.rockets();
            }

            @Override
            boolean tick() {
                return timeOfDay() >= Fireworks.DUSK_FROM + 400;
            }

            @Override
            void end(final boolean done) {
                final int used = before - rocketsInStock();
                final int flown = Fireworks.rockets() - rockets0;
                final int seen = level.getEntitiesOfClass(FireworkRocketEntity.class, new AABB(marker).inflate(64)).size();
                checkEq("rockets taken from the Decorator's stock (" + name + ")", used, expected);
                checkEq("rockets that flew from the square (" + name + ")", flown, expected);
                checkEq("shows (" + name + ")", Fireworks.shows() - shows0, expected > 0 ? 1 : 0);
                note("{} rocket entities about at the end, stock {} -> {}", seen, before, rocketsInStock());
                present = true;
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

    // ================================================================ restart

    private void planRestartSave() {
        step(new Step("restart-save: the tree lit and a gift taken, then the world saved", 80, true) {
            @Override
            void start() {
                final BlockPos log = firstOf(s -> s.is(BlockTags.LOGS));
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
                checkEq("no season line after the restart (the season told is saved)", FestivalDirector.seasonLines(), 0);
                checkEq("the season told is read back", data().lastSeason(), facts[0].getString("season"));
                invariant("after the restart");
            }
        });
    }
}
