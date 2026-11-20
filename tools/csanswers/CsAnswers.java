package me.lovkar.seasons.csanswers;

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
import com.minecolonies.api.colony.buildings.modules.IAssignsJob;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.tileentities.AbstractTileEntityColonyBuilding;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import com.mojang.authlib.GameProfile;
import me.lovkar.seasons.SeasonsConfig;
import me.lovkar.seasons.block.FestivalMarkerBlock;
import me.lovkar.seasons.block.FireworkCrateBlock;
import me.lovkar.seasons.block.GiftPileBlock;
import me.lovkar.seasons.block.SeasonsBlocks;
import me.lovkar.seasons.block.WallDecorationBlock;
import me.lovkar.seasons.calendar.CalendarManager;
import me.lovkar.seasons.calendar.DateSource;
import me.lovkar.seasons.colony.BuildingDecorator;
import me.lovkar.seasons.colony.DecoratorModules;
import me.lovkar.seasons.colony.DecoratorSetup;
import me.lovkar.seasons.colony.JobDecorator;
import me.lovkar.seasons.decor.DecorDrops;
import me.lovkar.seasons.decor.DecorEvents;
import me.lovkar.seasons.decor.DecorKinds;
import me.lovkar.seasons.decor.DecorLedger;
import me.lovkar.seasons.decor.DecorPlacer;
import me.lovkar.seasons.decor.DecorPlanner;
import me.lovkar.seasons.decor.DecorWork;
import me.lovkar.seasons.decor.DoorFinder;
import me.lovkar.seasons.decor.PieceLoader;
import me.lovkar.seasons.festival.Gathering;
import me.lovkar.seasons.links.Welcome;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.PlayerList;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.PlayerDataStorage;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Headless check of S10d, the owner's answers of 20 Nov 2026, on a real colony (a town hall, the Decorator's hut at
 * level 3 with its Decorator, a lumberjack's hut with its worker, a guard tower with a knight, a festival marker and the
 * tree from the mod's own piece). Modes (the rig writes the word to probe-mode.txt):
 * <ul>
 *   <li>none: the welcome line (the real login event on a fake player that keeps what it is sent: once, word for word,
 *       two links that open their URLs, the shared marker; not on a second login, not when the marker is set already
 *       by another mod, not with links.showWelcome off, kept through restoreFrom); doorless huts (a hand-built house
 *       with its hut block near one wall: one wreath in front of that wall, through DecorWork.decorate, nothing more on
 *       a second visit or a tending visit, nothing with wreaths switched off; a pasted real hut with its doors taken
 *       out: one wreath on an outside wall; a house with a door as before); drops (a survival player breaks free and
 *       paid decorations, the tree's gift pile and crate, a player's own crate and wreath, a free and a paid trunk,
 *       free and paid vanilla leaves with shears; a wreath popping off its wall; the explosion's order, the drops read
 *       while the entry is listed; a real explosion of a free and a paid podzol of a piece; the take-down still hands
 *       back what was paid); leaves at take-down (only the
 *       distance changed: removed and handed back; persistent changed or swapped to stone: left, forgotten); a second
 *       marker (one notice, which the colony's owner as a listening manager receives once, the first stays, the same
 *       marker placed again says nothing); the guards on the everyone day
 *       of the Winter Festival (the mixin applied and asked, at the same tick the guard is not brought and the worker
 *       is, the worker reaches the tree in the hours and the guard is never brought);</li>
 *   <li>{@code restart-save} then {@code restart-load} (tools/restart.sh): a player of a fixed id is welcomed and saved
 *       through the server's own player storage; after the restart the same player is read back through the player
 *       list: the marker is set and a login sends nothing.</li>
 * </ul>
 * Prints "[csanswers] CHECK ...: OK|FAIL" and "[csanswers] RESULT csanswers: PASS|FAIL", then halts. The words "error"
 * and "exception" stay out of the details.
 */
@Mod("colonyseasonsanswers")
public class CsAnswers {
    private static final Logger LOGGER = LoggerFactory.getLogger("csanswers");
    private static final int SETUP_TICK = 60;
    private static final long WATCHDOG_SECONDS = 560;

    static final LocalDate DECOR_DAY = LocalDate.of(2026, 12, 10);
    static final LocalDate FESTIVAL_DAY = LocalDate.of(2026, 12, 25);
    private static final int MORNING = 1000;
    private static final int BEFORE_HOURS = 8000;
    private static final int SAMPLE_FROM = 9300;
    private static final int WITHIN = 8;
    /** The sentence the owner approved on 20 Nov 2026, typed out here once more (the dot is U+00B7). */
    private static final String APPROVED = "Thanks for playing Lovkar's mods! News, help and test builds on Discord: discord.gg/BVztrTtXFu"
            + " · Support the mods on Patreon: patreon.com/cw/Lovkar";
    private static final String DISCORD = "https://discord.gg/BVztrTtXFu";
    private static final String PATREON = "https://www.patreon.com/cw/Lovkar";
    /** The lang key of the managers' line about a second festival marker. */
    private static final String SECOND_MARKER_KEY = "com.colony_seasons.decor.second_marker";
    private static final UUID RESTART_PLAYER = UUID.nameUUIDFromBytes("csanswers-restart-player".getBytes(StandardCharsets.US_ASCII));

    private int ticks;
    private boolean failed;
    private boolean finished;
    private final String mode;
    private long setupNanos;

    private MinecraftServer server;
    private ServerLevel level;
    private FakePlayer owner;
    private IColony colony;
    private int colonyId;
    private int ground;
    private DecorLedger ledger;
    private BuildingDecorator hut;
    private ICitizenData decorator;
    private ICitizenData worker;
    private ICitizenData guard;
    private BlockPos marker;

    private final List<Step> steps = new ArrayList<>();
    private int stepIndex;

    public CsAnswers() {
        mode = readMode();
        // the calendar must never see the real date
        CalendarManager.setDateSource(DateSource.fixed(DECOR_DAY));
        NeoForge.EVENT_BUS.addListener(this::onTick);
    }

    // ================================================================ logging

    private void check(final String what, final boolean ok, final String detail) {
        LOGGER.info("[csanswers] CHECK {}: {} {}", what, ok ? "OK" : "FAIL", detail);
        failed |= !ok;
    }

    private void checkEq(final String what, final Object actual, final Object expected) {
        check(what, Objects.equals(actual, expected), "got " + actual + (Objects.equals(actual, expected) ? "" : ", wanted " + expected));
    }

    private static void note(final String text, final Object... args) {
        LOGGER.info("[csanswers] " + text, args);
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
        LOGGER.info("[csanswers] RESULT csanswers: {}", failed ? "FAIL" : "PASS");
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
                planTree();
                planWelcome();
                planDoorless();
                planDrops();
                planLeaves();
                planSecondMarker();
                planGuards();
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

    /** A stone-brick house on the ground with doors in its south wall; the hut cell is the middle of the floor (the csdecorgate helper). */
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

    /** A citizen hired into the building's job module named {@code jobPath} (null: the first worker module). */
    private ICitizenData hire(final IBuilding building, final String jobPath, final BlockPos at) {
        final ICitizenData data = spawn(at);
        boolean ok = false;
        if (jobPath == null) {
            final WorkerBuildingModule module = building.getFirstModuleOccurance(WorkerBuildingModule.class);
            ok = module != null && module.assignCitizen(data);
        } else {
            for (final IAssignsJob module : building.getModulesByType(IAssignsJob.class)) {
                if (module.getJobEntry().getKey().getPath().equals(jobPath)) {
                    ok = module.assignCitizen(data);
                }
            }
        }
        check("a citizen is hired into " + BuiltInRegistries.BLOCK.getKey(level.getBlockState(building.getPosition()).getBlock())
                        + (jobPath == null ? "" : " (" + jobPath + ")"), ok && data.getJob() != null,
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

    private static EntityCitizen entity(final ICitizenData c) {
        final AbstractEntityCitizen e = c == null ? null : c.getEntity().orElse(null);
        return e instanceof EntityCitizen citizen ? citizen : null;
    }

    private static String state(final ICitizenData c) {
        final EntityCitizen e = entity(c);
        return e != null && e.getCitizenAI() != null && e.getCitizenAI().getState() != null ? e.getCitizenAI().getState().toString() : "none";
    }

    private double dist(final ICitizenData c) {
        final AbstractEntityCitizen e = c == null ? null : c.getEntity().orElse(null);
        return e == null ? Double.MAX_VALUE : Math.sqrt(e.position().distanceToSqr(Vec3.atBottomCenterOf(marker)));
    }

    private DecorLedger.Owner square() {
        return DecorLedger.Owner.square(marker);
    }

    private BlockPos firstOf(final DecorLedger.Owner owner, final java.util.function.Predicate<BlockState> what) {
        final DecorLedger.GroupView g = ledger.group(colonyId, owner);
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

    /** Item entities within 1.5 blocks of the cell, counted (stack sizes) and taken away. */
    private int dropsAt(final BlockPos pos) {
        int n = 0;
        for (final ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(1.5))) {
            n += e.getItem().getCount();
            e.discard();
        }
        return n;
    }

    /** A survival player of the colony (its owner) breaks the block with {@code tool} in hand; the items dropped, -1 when it did not break. */
    private int breakAsPlayer(final BlockPos pos, final ItemStack tool) {
        dropsAt(pos);
        owner.setItemInHand(InteractionHand.MAIN_HAND, tool);
        final boolean broke = owner.gameMode.destroyBlock(pos);
        owner.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        final int n = dropsAt(pos);
        return broke && level.getBlockState(pos).isAir() ? n : -1;
    }

    /** A small TNT-rule explosion (no drop decay by default) at the cell that breaks that cell only. */
    private void blowUp(final BlockPos pos) {
        final net.minecraft.world.level.ExplosionDamageCalculator only = new net.minecraft.world.level.ExplosionDamageCalculator() {
            @Override
            public boolean shouldBlockExplode(final net.minecraft.world.level.Explosion explosion, final net.minecraft.world.level.BlockGetter getter,
                                              final BlockPos at, final BlockState state, final float power) {
                return at.equals(pos);
            }
        };
        level.explode(null, null, only, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 1.0f, false,
                net.minecraft.world.level.Level.ExplosionInteraction.TNT);
    }

    /** A stock that always has the item, and keeps what it is handed back. */
    private static final class Counting implements DecorPlacer.Stock {
        int taken;
        final Map<Item, Integer> back = new LinkedHashMap<>();

        @Override
        public boolean take(final Item item) {
            taken++;
            return true;
        }

        @Override
        public void give(final ItemStack stack) {
            back.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }

        int back(final Item item) {
            return back.getOrDefault(item, 0);
        }
    }

    /** Places one block the way the Decorator does (listed first), paid or free; true when it went up with that mark. */
    private boolean put(final DecorLedger.Owner groupOwner, final BlockPos pos, final BlockState state, final boolean paid) {
        final DecorPlacer.PlaceResult r = DecorPlacer.place(level, ledger, colonyId, groupOwner, DecorWork.festival(), 3,
                List.of(new DecorPlacer.Slot(pos, state, paid)), new Counting());
        final DecorLedger.Located at = ledger.at(pos);
        final boolean ok = r.count() == 1 && at != null && at.entry().paid() == paid;
        if (!ok) {
            note("could not put {} at {}: {}", state, pos.toShortString(), r.reasons());
        }
        return ok;
    }

    // ================================================================ setup

    private void planSetup() {
        step(new Step("setup: the colony, the Decorator's hut, a worker, a guard, the marker", 80, true) {
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
                colony = IColonyManager.getInstance().createColony(level, center, owner, "Seasons Answers Test", "Medieval Oak");
                colonyId = colony == null ? -1 : colony.getID();
                check("a colony was made", colony != null, "id " + colonyId + " at " + center);
                if (colony == null) {
                    finish();
                    return;
                }
                register("minecolonies:blockhuttownhall", house(-3, -1, 11, 7, 2), 1, null, null);
                final IBuilding d = register(BuiltInRegistries.BLOCK.getKey(DecoratorSetup.HUT.get()).toString(), house(12, -1, 11, 7, 17), 3, null, null);
                hut = d instanceof BuildingDecorator b ? b : null;
                final IBuilding lumberjack = register("minecolonies:blockhutlumberjack", house(-40, 16, 11, 7, -35), 1,
                        "Medieval Oak", "fundamentals/lumberjack1.blueprint");
                final IBuilding tower = register("minecolonies:blockhutguardtower", house(28, 16, 11, 7, 33), 1, null, null);
                check("the buildings are registered", hut != null && lumberjack != null && tower != null, "");
                if (hut == null || lumberjack == null || tower == null) {
                    finish();
                    return;
                }
                decorator = colony.getCitizenManager().createAndRegisterCivilianData();
                colony.getCitizenManager().spawnOrCreateCitizen(decorator, level, hut.getPosition().offset(0, 0, 4));
                check("a citizen is hired as the Decorator", hut.getModule(DecoratorModules.WORK).assignCitizen(decorator)
                        && decorator.getJob() instanceof JobDecorator, decorator.getName());
                worker = hire(lumberjack, null, lumberjack.getPosition().offset(0, 0, 5));
                guard = hire(tower, "knight", tower.getPosition().offset(0, 0, 5));
                check("the knight's job is a guard's, the lumberjack's is not", guard.getJob() != null && guard.getJob().isGuard()
                        && worker.getJob() != null && !worker.getJob().isGuard(), "");
                // the festival marker where the tree should stand, put down by the "player" the way a player does
                marker = new BlockPos(2, ground + 1, 32);
                final BlockState m = SeasonsBlocks.FESTIVAL_MARKER.get().defaultBlockState().setValue(FestivalMarkerBlock.FACING, Direction.NORTH);
                level.setBlock(marker, m, Block.UPDATE_ALL);
                SeasonsBlocks.FESTIVAL_MARKER.get().setPlacedBy(level, marker, m, owner, new ItemStack(SeasonsBlocks.FESTIVAL_MARKER_ITEM.get()));
                checkEq("the marker is the colony's square", ledger.marker(colonyId), marker);
                setTimeOfDay(MORNING);
            }

            @Override
            boolean tick() {
                return t >= 60;
            }
        });
    }

    private void planTree() {
        step(new Step("the tree goes up at the marker (village_spruce_full, hut level 3)", 800, true) {
            DecorWork.Outcome last;

            @Override
            boolean tick() {
                if (t % 20 == 0 && (last == null || !last.complete())) {
                    last = DecorWork.decorate(level, colonyId, DecorWork.Target.square(marker, Direction.NORTH, PieceLoader.PACK), 3,
                            DecorPlacer.Stock.FREE, day(), ledger);
                }
                return last != null && last.complete();
            }

            @Override
            void end(final boolean done) {
                final DecorLedger.GroupView g = ledger.group(colonyId, square());
                check("the tree is finished at the marker, with its gift pile and firework crate", done && g != null && g.size() > 50
                                && firstOf(square(), s -> s.getBlock() instanceof GiftPileBlock) != null
                                && firstOf(square(), s -> s.getBlock() instanceof FireworkCrateBlock) != null,
                        (last == null ? "-" : last.status()) + ", " + (g == null ? 0 : g.size()) + " cells");
            }
        });
    }

    // ================================================================ the welcome line (item 7)

    /** A fake player that keeps every system message it is sent (FakePlayer itself drops them). */
    static final class Listener extends FakePlayer {
        final List<Component> got = new ArrayList<>();

        Listener(final ServerLevel level, final GameProfile profile) {
            super(level, profile);
        }

        @Override
        public void sendSystemMessage(final Component message, final boolean overlay) {
            got.add(message);
        }

        /** MineColonies' messages come this way (FakePlayer drops them too). */
        @Override
        public void displayClientMessage(final Component message, final boolean overlay) {
            got.add(message);
        }

        int welcomes() {
            int n = 0;
            for (final Component c : got) {
                n += c.getString().equals(APPROVED) ? 1 : 0;
            }
            return n;
        }

        /** Messages that carry the lang key {@code key} anywhere in their tree (MineColonies may put a prefix before it). */
        int heard(final String key) {
            int n = 0;
            for (final Component c : got) {
                n += hasKey(c, key) ? 1 : 0;
            }
            return n;
        }

        private static boolean hasKey(final Component c, final String key) {
            if (c.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t && key.equals(t.getKey())) {
                return true;
            }
            for (final Component sibling : c.getSiblings()) {
                if (hasKey(sibling, key)) {
                    return true;
                }
            }
            return false;
        }
    }

    private Listener listener(final String name) {
        return listener(UUID.nameUUIDFromBytes(("csanswers-" + name).getBytes(StandardCharsets.US_ASCII)), name);
    }

    private Listener listener(final UUID id, final String name) {
        final Listener p = new Listener(level, new GameProfile(id, "csanswers_" + name));
        p.setPos(2.5, ground + 1, -10.5);
        return p;
    }

    private boolean loginPosted = true;

    /** The real login event, posted on the game bus the way the player list posts it; if another mod's listener chokes on a fake player, Welcome.offer is called. */
    private void login(final Listener p) {
        try {
            NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedInEvent(p));
        } catch (final Throwable choked) {
            loginPosted = false;
            note("the login event did not get through for a fake player ({}); Welcome.offer is called instead",
                    choked.getClass().getSimpleName().replace("Exception", "X"));
            Welcome.offer(p);
        }
    }

    /** The persistent marker at the binding path, read with the literal names. */
    private static CompoundTag shared(final Player p) {
        final CompoundTag data = p.getPersistentData();
        if (!data.contains("PlayerPersisted", Tag.TAG_COMPOUND)) {
            return null;
        }
        final CompoundTag persisted = data.getCompound("PlayerPersisted");
        return persisted.contains("lovkar_mods", Tag.TAG_COMPOUND) ? persisted.getCompound("lovkar_mods") : null;
    }

    private static boolean marked(final Player p) {
        final CompoundTag shared = shared(p);
        return shared != null && shared.getBoolean("welcome_shown");
    }

    private void showWelcome(final boolean on) {
        try {
            final Field f = SeasonsConfig.class.getDeclaredField("SHOW_WELCOME");
            f.setAccessible(true);
            final ModConfigSpec.BooleanValue b = (ModConfigSpec.BooleanValue) f.get(null);
            b.set(on);
            b.clearCache();
        } catch (final ReflectiveOperationException | ClassCastException unreadable) {
            check("the switch links.showWelcome can be set", false, unreadable.getClass().getSimpleName().replace("Exception", "X"));
        }
    }

    private void planWelcome() {
        step(new Step("welcome: the line once per player, the shared marker, the switch", 1) {
            @Override
            void start() {
                checkEq("Welcome.TEXT is the approved sentence word for word", Welcome.TEXT, APPROVED);
                check("links.showWelcome is on by default", SeasonsConfig.showWelcome() && SeasonsConfig.DEFAULT_SHOW_WELCOME, "");
                final int sent0 = Welcome.sent();
                // first login: one line
                final Listener first = listener("first");
                check("a new player has no marker", !marked(first), "");
                login(first);
                checkEq("the first login: exactly one welcome line", first.welcomes(), 1);
                note("messages to the first player: {}", first.got.stream().map(Component::getString).toList());
                final Component line = first.got.stream().filter(c -> c.getString().equals(APPROVED)).findFirst().orElse(null);
                final List<String> links = new ArrayList<>();
                if (line != null) {
                    line.visit((FormattedText.StyledContentConsumer<Void>) (style, text) -> {
                        final ClickEvent click = style.getClickEvent();
                        if (click != null) {
                            links.add(text + " -> " + click.getAction().name() + " " + click.getValue());
                        }
                        return Optional.empty();
                    }, Style.EMPTY);
                }
                checkEq("its two links open their URLs from the right words", links,
                        List.of("discord.gg/BVztrTtXFu -> OPEN_URL " + DISCORD, "patreon.com/cw/Lovkar -> OPEN_URL " + PATREON));
                check("the marker is set at PlayerPersisted / lovkar_mods / welcome_shown", marked(first) && Welcome.shown(first), "");
                checkEq("the line was counted once", Welcome.sent() - sent0, 1);
                // second login: nothing
                login(first);
                checkEq("the second login: no line", first.welcomes(), 1);
                // another mod of the owner's set the marker first, and keeps a key of its own beside it
                final Listener other = listener("other");
                final CompoundTag persisted = new CompoundTag();
                final CompoundTag shared = new CompoundTag();
                shared.putBoolean("welcome_shown", true);
                shared.putInt("other_mod_key", 7);
                persisted.put("lovkar_mods", shared);
                persisted.putString("another_compound", "kept");
                other.getPersistentData().put("PlayerPersisted", persisted);
                login(other);
                check("the marker already set by another mod: no line, its key untouched", other.welcomes() == 0
                        && shared(other) != null && shared(other).getInt("other_mod_key") == 7 && marked(other)
                        && "kept".equals(other.getPersistentData().getCompound("PlayerPersisted").getString("another_compound")),
                        other.got.size() + " messages");
                // a key of another mod in the shared compound, no marker yet: the line, and the key is kept
                final Listener sibling = listener("sibling");
                final CompoundTag p2 = new CompoundTag();
                final CompoundTag s2 = new CompoundTag();
                s2.putInt("other_mod_key", 9);
                p2.put("lovkar_mods", s2);
                sibling.getPersistentData().put("PlayerPersisted", p2);
                login(sibling);
                check("a shared compound with another key and no marker: the line, the marker, the other key kept", sibling.welcomes() == 1
                        && marked(sibling) && shared(sibling).getInt("other_mod_key") == 9, sibling.got.size() + " messages");
                // the switch off: nothing, no marker
                showWelcome(false);
                final Listener off = listener("off");
                login(off);
                check("links.showWelcome off: no line and no marker written", off.welcomes() == 0 && !marked(off)
                        && !off.getPersistentData().contains("PlayerPersisted"), off.got.size() + " messages");
                showWelcome(true);
                check("the switch is back on", SeasonsConfig.showWelcome(), "");
                // death: the new player object of the same player keeps the marker
                final Listener reborn = listener(first.getUUID(), "first");
                try {
                    reborn.restoreFrom(first, false);
                } catch (final RuntimeException refused) {
                    note("restoreFrom on a fake player: {}", refused.getClass().getSimpleName().replace("Exception", "X"));
                }
                check("after death (restoreFrom) the marker is kept", marked(reborn), "");
                login(reborn);
                checkEq("and a login after it sends no line", reborn.welcomes(), 0);
                check("the real login event reached the welcome (no fallback was needed)", loginPosted, "");
            }
        });
    }

    // ================================================================ doorless huts (item 1)

    /** Pastes a hut blueprint with its box's low corner at {@code plot} (the csdecorgate helper). */
    private DoorFinder.Box paste(final String pack, final String path, final BlockPos plot) {
        try {
            final Blueprint blueprint = StructurePacks.getBlueprintFuture(pack, path, level.registryAccess()).get(60, TimeUnit.SECONDS);
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
            return new DoorFinder.Box(plot, max, anchor);
        } catch (final Exception unreadable) {
            check("blueprint " + pack + " " + path + " pastes", false, unreadable.getClass().getSimpleName().replace("Exception", "X"));
            return null;
        }
    }

    private int countIn(final DecorLedger.Owner groupOwner, final Block block) {
        final DecorLedger.GroupView g = ledger.group(colonyId, groupOwner);
        int n = 0;
        if (g != null) {
            for (final DecorLedger.Entry e : g.entries().values()) {
                n += e.state().getBlock() == block ? 1 : 0;
            }
        }
        return n;
    }

    private void planDoorless() {
        final DoorFinder.Box[] boxes = new DoorFinder.Box[3];
        final IBuilding[] buildings = new IBuilding[2];
        final int[] doorsOut = new int[1];
        step(new Step("doorless: a hand-built house without a door, one with a door, a real hut pasted", 300, true) {
            @Override
            void start() {
                // the doorless house: 11 x 7, its hut block one in from the north wall
                final DoorFinder.Box shell = house(-20, -22, 11, 7);
                boxes[0] = new DoorFinder.Box(shell.min(), shell.max(), new BlockPos(-15, ground + 1, -21));
                buildings[0] = register("minecolonies:blockhutcitizen", boxes[0], 1, null, null);
                // a house with a door in its south wall at x 5
                boxes[1] = house(0, -22, 11, 7, 5);
                buildings[1] = register("minecolonies:blockhutcitizen", boxes[1], 1, null, null);
                // a real hut (a residence of the Medieval Oak style) pasted in the air, its doors taken out
                boxes[2] = paste("Medieval Oak", "fundamentals/residence1.blueprint", new BlockPos(-62, ground + 30, -62));
                if (boxes[2] != null) {
                    for (final BlockPos pos : BlockPos.betweenClosed(boxes[2].min(), boxes[2].max())) {
                        if (level.getBlockState(pos).is(BlockTags.DOORS)) {
                            if (level.getBlockState(pos).getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
                                doorsOut[0]++;
                            }
                            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
                        }
                    }
                }
            }

            @Override
            boolean tick() {
                return t >= 60 && !level.getChunkSource().getLightEngine().hasLightWork();
            }

            @Override
            void end(final boolean done) {
                check("the buildings registered", buildings[0] != null && buildings[1] != null, "");
                if (buildings[0] == null || buildings[1] == null) {
                    return;
                }
                // the doorless house
                final DecorWork.Target target = DecorWork.Target.building(buildings[0]);
                final DecorPlanner.Plan off = DecorPlanner.plan(level, target.box(), 1, 6, ledger, colonyId, target.owner(), true,
                        new DecorKinds(false, true, true, true));
                check("wreaths switched off: the doorless house gets nothing", off.slots().isEmpty() && off.wreaths() == 0,
                        off.slots().size() + " slots");
                final DecorWork.Outcome first = DecorWork.decorate(level, colonyId, target, 1, DecorPlacer.Stock.FREE, day(), ledger);
                final BlockPos want = new BlockPos(-15, ground + 3, -23);
                final BlockState there = level.getBlockState(want);
                final DecorLedger.GroupView g = ledger.group(colonyId, target.owner());
                check("the doorless house gets exactly one wreath, in front of its north wall two above the hut block",
                        first.placed() == 1 && g != null && g.size() == 1 && there.is(SeasonsBlocks.WREATH.get())
                                && there.getValue(WallDecorationBlock.FACING) == Direction.NORTH && ledger.isOurs(colonyId, target.owner(), want, there),
                        first.status() + ", at " + (g == null ? "-" : g.entries().keySet()));
                final DecorWork.Outcome again = DecorWork.decorate(level, colonyId, target, 1, DecorPlacer.Stock.FREE, day(), ledger);
                final DecorWork.Outcome tended = DecorWork.tend(level, colonyId, target, 1, DecorPlacer.Stock.FREE, day() + 1, ledger);
                final DecorPlanner.Plan five = DecorPlanner.plan(level, target.box(), 5, 6, ledger, colonyId, target.owner(), true);
                check("a second visit and a tending visit add nothing; at hut level 5 it is still the one wreath, no garland or lights",
                        again.placed() == 0 && tended.placed() == 0 && ledger.group(colonyId, target.owner()).size() == 1
                                && five.slots().isEmpty() && five.wreaths() == 1 && five.ours() == 1 && five.garlands() == 0 && five.lights() == 0,
                        again.status() + " / " + tended.status() + " / level 5: " + five.slots().size() + " slots, " + five.wreaths() + " wreaths");
                // the house with a door: as before
                final DecorWork.Target doorTarget = DecorWork.Target.building(buildings[1]);
                final DecorPlanner.Plan door = DecorPlanner.plan(level, doorTarget.box(), 1, 6, ledger, colonyId, doorTarget.owner(), true);
                final DecorPlacer.Slot slot = door.slots().isEmpty() ? null : door.slots().get(0);
                check("a house with a door: one wreath two above the door, facing out", door.doors() == 1 && door.slots().size() == 1
                                && slot != null && slot.pos().equals(new BlockPos(5, ground + 3, -15)) && slot.state().is(SeasonsBlocks.WREATH.get())
                                && slot.state().getValue(WallDecorationBlock.FACING) == Direction.SOUTH,
                        door.doors() + " doors, " + door.slots().size() + " slots" + (slot == null ? "" : " at " + slot.pos().toShortString()));
                // the real hut without its doors
                if (boxes[2] != null) {
                    final List<DoorFinder.Door> doors = DoorFinder.find(level, boxes[2]);
                    check("the pasted residence had doors and has none now", doorsOut[0] > 0 && doors != null && doors.isEmpty(),
                            doorsOut[0] + " doors taken out, " + (doors == null ? "unloaded" : doors.size() + " found"));
                    final DecorWork.Target pasted = DecorWork.Target.box(boxes[2]);
                    final DecorWork.Outcome o = DecorWork.decorate(level, colonyId, pasted, 5, DecorPlacer.Stock.FREE, day(), ledger);
                    final DecorLedger.GroupView pg = ledger.group(colonyId, pasted.owner());
                    final Map.Entry<BlockPos, DecorLedger.Entry> only = pg == null || pg.size() != 1 ? null : pg.entries().entrySet().iterator().next();
                    boolean outside = false;
                    String detail = o.status();
                    if (only != null && only.getValue().state().is(SeasonsBlocks.WREATH.get())) {
                        final Direction out = only.getValue().state().getValue(WallDecorationBlock.FACING);
                        final BlockPos wall = only.getKey().relative(out.getOpposite());
                        final int front = level.getBrightness(LightLayer.SKY, only.getKey());
                        final int behind = level.getBrightness(LightLayer.SKY, wall.relative(out.getOpposite()));
                        outside = front >= DoorFinder.MIN_OUTSIDE_SKY && front > behind && level.getBlockState(wall).isFaceSturdy(level, wall, out)
                                && boxes[2].contains(wall);
                        detail += ", at " + only.getKey().subtract(boxes[2].min()).toShortString() + " facing " + out + ", sky " + front + " / " + behind;
                    }
                    check("the pasted residence without doors gets exactly one wreath, on an outside wall of its own", only != null && outside, detail);
                    DecorPlacer.remove(level, ledger, colonyId, pasted.owner(), DecorPlacer.Stock.FREE);
                }
            }
        });
    }

    // ================================================================ drops (item 2)

    private void planDrops() {
        step(new Step("drops: free and paid decorations broken by a player, popping off, the explosion's order, the take-down", 20, true) {
            @Override
            void start() {
                final int y = ground + 1;
                final DecorLedger.Owner walls = DecorLedger.Owner.square(new BlockPos(48, y, -40));
                final DecorLedger.Owner pieces = DecorLedger.Owner.square(new BlockPos(48, y, -30));
                final int suppressed0 = DecorDrops.suppressed();
                // walls for the wreaths: stone blocks, the wreath in the cell south of each
                final BlockState wreath = SeasonsBlocks.WREATH.get().defaultBlockState().setValue(WallDecorationBlock.FACING, Direction.SOUTH);
                final int[] xs = {36, 40, 44, 48, 52, 56, 60};
                for (final int x : xs) {
                    set(new BlockPos(x, y, -46), Blocks.STONE.defaultBlockState());
                }
                final BlockPos freeWreath = new BlockPos(36, y, -45);
                final BlockPos paidWreath = new BlockPos(40, y, -45);
                final BlockPos freePop = new BlockPos(44, y, -45);
                final BlockPos paidPop = new BlockPos(48, y, -45);
                final BlockPos playerWreath = new BlockPos(52, y, -45);
                final BlockPos freeRead = new BlockPos(56, y, -45);
                final BlockPos paidRead = new BlockPos(60, y, -45);
                check("the test wreaths go up as the Decorator puts them (listed, free or paid)", put(walls, freeWreath, wreath, false)
                        && put(walls, paidWreath, wreath, true) && put(walls, freePop, wreath, false) && put(walls, paidPop, wreath, true)
                        && put(walls, freeRead, wreath, false) && put(walls, paidRead, wreath, true), "");
                set(playerWreath, wreath);
                checkEq("a free wreath broken by a player drops nothing", breakAsPlayer(freeWreath, ItemStack.EMPTY), 0);
                checkEq("a paid wreath broken by a player drops its item", breakAsPlayer(paidWreath, ItemStack.EMPTY), 1);
                checkEq("a player's own wreath (not listed) drops its item", breakAsPlayer(playerWreath, ItemStack.EMPTY), 1);
                // popping off: the wall goes
                level.setBlock(new BlockPos(44, y, -46), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                level.setBlock(new BlockPos(48, y, -46), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                check("the wreaths popped off when their walls went", level.getBlockState(freePop).isAir() && level.getBlockState(paidPop).isAir(), "");
                checkEq("a free wreath that pops off drops nothing", dropsAt(freePop), 0);
                checkEq("a paid wreath that pops off drops its item", dropsAt(paidPop), 1);
                // the order an explosion uses: the drops are read while the entry is still listed
                final List<ItemStack> freeDrops = Block.getDrops(level.getBlockState(freeRead), level, freeRead, null);
                final List<ItemStack> paidDrops = Block.getDrops(level.getBlockState(paidRead), level, paidRead, null);
                check("read while listed (an explosion's order): free nothing, paid its item", freeDrops.isEmpty()
                        && paidDrops.size() == 1 && paidDrops.get(0).is(SeasonsBlocks.WREATH_ITEM.get()), freeDrops + " / " + paidDrops);
                // the take-down: the paid one handed back, nothing dropped, the free one gone
                final Counting sink = new Counting();
                final DecorPlacer.Removed removed = DecorPlacer.remove(level, ledger, colonyId, walls, sink);
                check("the take-down still hands back what was paid and drops nothing", removed.removed() == 2
                                && sink.back(SeasonsBlocks.WREATH_ITEM.get()) == 1 && dropsAt(freeRead) + dropsAt(paidRead) == 0
                                && level.getBlockState(freeRead).isAir() && level.getBlockState(paidRead).isAir(),
                        removed + ", back " + sink.back);

                // the tree's gift pile and crate
                final BlockPos pile = firstOf(square(), s -> s.getBlock() instanceof GiftPileBlock);
                final BlockPos crate = firstOf(square(), s -> s.getBlock() instanceof FireworkCrateBlock);
                note("the tree's gift pile is {}, its crate {}", pile == null || ledger.at(pile) == null ? "-" : ledger.at(pile).entry().paid() ? "paid" : "free",
                        crate == null || ledger.at(crate) == null ? "-" : ledger.at(crate).entry().paid() ? "paid" : "free");
                checkEq("the tree's gift pile broken by a player drops nothing", pile == null ? -2 : breakAsPlayer(pile, ItemStack.EMPTY), 0);
                checkEq("the tree's firework crate broken by a player drops nothing", crate == null ? -2 : breakAsPlayer(crate, ItemStack.EMPTY), 0);
                // a crate and a gift pile listed as paid drop nothing either
                final BlockPos paidCrate = new BlockPos(36, y, -35);
                final BlockPos paidPile = new BlockPos(40, y, -35);
                check("a crate and a gift pile go up listed as paid", put(pieces, paidCrate, SeasonsBlocks.FIREWORK_CRATE.get().defaultBlockState(), true)
                        && put(pieces, paidPile, SeasonsBlocks.GIFT_PILE.get().defaultBlockState(), true), "");
                checkEq("a crate listed as paid drops nothing", breakAsPlayer(paidCrate, ItemStack.EMPTY), 0);
                checkEq("a gift pile listed as paid drops nothing", breakAsPlayer(paidPile, ItemStack.EMPTY), 0);
                // a player's own crate
                final BlockPos playerCrate = new BlockPos(44, y, -35);
                set(playerCrate, SeasonsBlocks.FIREWORK_CRATE.get().defaultBlockState());
                checkEq("a player's own crate (not listed) drops its item", breakAsPlayer(playerCrate, ItemStack.EMPTY), 1);
                // the trunk: free nothing, paid a spruce log
                final BlockPos freeTrunk = new BlockPos(48, y, -35);
                final BlockPos paidTrunk = new BlockPos(52, y, -35);
                check("a free and a paid trunk go up", put(pieces, freeTrunk, SeasonsBlocks.FESTIVAL_TRUNK.get().defaultBlockState(), false)
                        && put(pieces, paidTrunk, SeasonsBlocks.FESTIVAL_TRUNK.get().defaultBlockState(), true), "");
                checkEq("a free trunk broken by a player drops nothing", breakAsPlayer(freeTrunk, new ItemStack(Items.IRON_AXE)), 0);
                checkEq("a paid trunk broken by a player drops its spruce log", breakAsPlayer(paidTrunk, new ItemStack(Items.IRON_AXE)), 1);
                // vanilla spruce leaves of a piece (they never tell the ledger): free nothing, paid the leaves, with shears
                final BlockPos freeLeaf = new BlockPos(56, y, -35);
                final BlockPos paidLeaf = new BlockPos(60, y, -35);
                check("a free and a paid spruce leaf go up", put(pieces, freeLeaf, Blocks.SPRUCE_LEAVES.defaultBlockState(), false)
                        && put(pieces, paidLeaf, Blocks.SPRUCE_LEAVES.defaultBlockState(), true), "");
                checkEq("a free spruce leaf broken with shears drops nothing", breakAsPlayer(freeLeaf, new ItemStack(Items.SHEARS)), 0);
                checkEq("a paid spruce leaf broken with shears drops the leaves", breakAsPlayer(paidLeaf, new ItemStack(Items.SHEARS)), 1);
                check("the drops taken away were counted", DecorDrops.suppressed() - suppressed0 >= 7, (DecorDrops.suppressed() - suppressed0) + " counted");
                // a real explosion (it reads a vanilla block's drops without the drop event): free nothing, paid as usual
                final BlockPos freeBlown = new BlockPos(36, y, -39);
                final BlockPos paidBlown = new BlockPos(44, y, -39);
                check("a free and a paid podzol go up", put(pieces, freeBlown, Blocks.PODZOL.defaultBlockState(), false)
                        && put(pieces, paidBlown, Blocks.PODZOL.defaultBlockState(), true), "");
                dropsAt(freeBlown);
                dropsAt(paidBlown);
                final int blown0 = DecorDrops.suppressed();
                // MineColonies' default keeps a colony's blocks out of an explosion: the free block is left alone too
                blowUp(freeBlown);
                check("MineColonies' default protection: the explosion leaves the free podzol standing and nothing is taken",
                        level.getBlockState(freeBlown).is(Blocks.PODZOL) && dropsAt(freeBlown) == 0 && DecorDrops.suppressed() == blown0,
                        level.getBlockState(freeBlown).toString());
                final net.neoforged.neoforge.common.ModConfigSpec.EnumValue<com.minecolonies.api.colony.permissions.Explosions> guard =
                        com.minecolonies.api.IMinecoloniesAPI.getInstance().getConfig().getServer().turnOffExplosionsInColonies;
                final com.minecolonies.api.colony.permissions.Explosions was = guard.get();
                guard.set(com.minecolonies.api.colony.permissions.Explosions.DAMAGE_EVERYTHING);
                guard.clearCache();
                try {
                    blowUp(freeBlown);
                    blowUp(paidBlown);
                } finally {
                    guard.set(was);
                    guard.clearCache();
                }
                check("with colony explosions on, the explosions broke both", level.getBlockState(freeBlown).isAir() && level.getBlockState(paidBlown).isAir(),
                        level.getBlockState(freeBlown) + " / " + level.getBlockState(paidBlown));
                checkEq("a free podzol of a piece blown up drops nothing", dropsAt(freeBlown), 0);
                checkEq("a paid podzol of a piece blown up drops its dirt", dropsAt(paidBlown), 1);
                checkEq("the blown free block was counted once", DecorDrops.suppressed() - blown0, 1);
                checkEq("MineColonies' explosion setting is back", guard.get(), was);
                // what is left of the test groups goes (one tick: the sweep never sees them)
                DecorPlacer.remove(level, ledger, colonyId, walls, DecorPlacer.Stock.FREE);
                DecorPlacer.remove(level, ledger, colonyId, pieces, DecorPlacer.Stock.FREE);
                ledger.dropGroup(colonyId, walls);
                ledger.dropGroup(colonyId, pieces);
            }
        });
    }

    // ================================================================ leaves at take-down (item 5)

    private void planLeaves() {
        step(new Step("leaves: the take-down removes a leaf whose only change is its distance", 20, true) {
            @Override
            void start() {
                final int y = ground + 1;
                final DecorLedger.Owner group = DecorLedger.Owner.square(new BlockPos(48, y, -20));
                final BlockPos distance = new BlockPos(36, y, -25);
                final BlockPos persistent = new BlockPos(40, y, -25);
                final BlockPos swapped = new BlockPos(44, y, -25);
                final BlockPos exact = new BlockPos(48, y, -25);
                final BlockState leaf = Blocks.SPRUCE_LEAVES.defaultBlockState();
                check("four paid spruce leaves go up", put(group, distance, leaf, true) && put(group, persistent, leaf, true)
                        && put(group, swapped, leaf, true) && put(group, exact, leaf, true), "");
                final BlockState recorded = ledger.at(distance) == null ? leaf : ledger.at(distance).entry().state();
                check("they are listed persistent, as the Decorator puts leaves", recorded.getValue(LeavesBlock.PERSISTENT), recorded.toString());
                final int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
                level.setBlock(distance, recorded.setValue(LeavesBlock.DISTANCE, recorded.getValue(LeavesBlock.DISTANCE) == 3 ? 4 : 3), flags);
                level.setBlock(persistent, recorded.setValue(LeavesBlock.PERSISTENT, false), flags);
                level.setBlock(swapped, Blocks.STONE.defaultBlockState(), flags);
                check("the three changes are in the world and the ledger still lists the old states",
                        level.getBlockState(distance) != recorded && ledger.at(distance) != null && ledger.at(persistent) != null && ledger.at(swapped) != null, "");
                final Counting sink = new Counting();
                final DecorPlacer.Removed removed = DecorWork.takeDown(level, colonyId, group, sink, day(), ledger);
                check("the leaf whose only change is its distance is taken down and forgotten, its item handed back",
                        level.getBlockState(distance).isAir() && ledger.at(distance) == null, level.getBlockState(distance).toString());
                check("the leaf whose persistent changed is left and forgotten", level.getBlockState(persistent).is(Blocks.SPRUCE_LEAVES)
                        && ledger.at(persistent) == null, level.getBlockState(persistent).toString());
                check("the leaf swapped to stone is left and forgotten", level.getBlockState(swapped).is(Blocks.STONE) && ledger.at(swapped) == null, "");
                check("the exact leaf is taken down as before", level.getBlockState(exact).isAir() && ledger.at(exact) == null, "");
                check("two removed, two forgotten, two spruce leaves handed back, nothing dropped", removed.removed() == 2 && removed.forgotten() == 2
                                && sink.back(Items.SPRUCE_LEAVES) == 2 && dropsAt(distance) + dropsAt(exact) == 0 && ledger.group(colonyId, group) == null,
                        removed + ", back " + sink.back);
                set(persistent, Blocks.AIR.defaultBlockState());
                set(swapped, Blocks.AIR.defaultBlockState());
            }
        });
    }

    // ================================================================ a second marker (item 4)

    private void planSecondMarker() {
        final int[] size0 = new int[1];
        step(new Step("a second festival marker: one notice to the managers, the first one counts", 3, true) {
            @Override
            void start() {
                final DecorLedger.GroupView tree = ledger.group(colonyId, square());
                size0[0] = tree == null ? -1 : tree.size();
                final int n0 = DecorEvents.secondMarkerNotices();
                // the colony's owner, online as a listener: MineColonies' forManagers() reaches the important players
                // of the colony that are managers (a fake player cannot subscribe the usual way, so it is put into
                // the set for the length of this call only, before the package manager's next tick)
                final Listener manager = new Listener(level, owner.getGameProfile());
                manager.setPos(owner.getX(), owner.getY(), owner.getZ());
                final java.util.Set<net.minecraft.server.level.ServerPlayer> important = colony.getPackageManager().getImportantColonyPlayers();
                final BlockPos second = new BlockPos(16, ground + 1, 32);
                final BlockState m = SeasonsBlocks.FESTIVAL_MARKER.get().defaultBlockState().setValue(FestivalMarkerBlock.FACING, Direction.NORTH);
                important.add(manager);
                int heard;
                int heardAgain;
                try {
                    level.setBlock(second, m, Block.UPDATE_ALL);
                    SeasonsBlocks.FESTIVAL_MARKER.get().setPlacedBy(level, second, m, owner, new ItemStack(SeasonsBlocks.FESTIVAL_MARKER_ITEM.get()));
                    heard = manager.heard(SECOND_MARKER_KEY);
                    // the first marker placed again where it stands: nothing to say
                    SeasonsBlocks.FESTIVAL_MARKER.get().setPlacedBy(level, marker, level.getBlockState(marker), owner,
                            new ItemStack(SeasonsBlocks.FESTIVAL_MARKER_ITEM.get()));
                    heardAgain = manager.heard(SECOND_MARKER_KEY);
                } finally {
                    important.remove(manager);
                }
                note("messages to the colony's manager: {}", manager.got.stream().map(Component::getString).toList());
                checkEq("a second marker in the colony: exactly one notice", DecorEvents.secondMarkerNotices() - n0, 1);
                checkEq("the colony's manager got exactly one line '" + SECOND_MARKER_KEY + "'", heard, 1);
                checkEq("the first marker stays the colony's square", ledger.marker(colonyId), marker);
                checkEq("the first marker placed again: the manager got no second line", heardAgain, 1);
                checkEq("the first marker placed again: no notice", DecorEvents.secondMarkerNotices() - n0, 1);
                checkEq("and it is still the square", ledger.marker(colonyId), marker);
                level.setBlock(second, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }

            @Override
            boolean tick() {
                return t >= 2;
            }

            @Override
            void end(final boolean done) {
                final DecorLedger.GroupView tree = ledger.group(colonyId, square());
                checkEq("the square is unchanged (the second marker broken takes nothing with it)", tree == null ? -1 : tree.size(), size0[0]);
                checkEq("the first marker is still registered", ledger.marker(colonyId), marker);
            }
        });
    }

    // ================================================================ the guards (item 3)

    private void planGuards() {
        final int[] calls0 = new int[1];
        step(new Step("guards: 25 Dec, the morning before the hours", 700, true) {
            @Override
            void start() {
                setDate(FESTIVAL_DAY);
                nextDayAt(BEFORE_HOURS);
                calls0[0] = Gathering.mixinCalls();
            }

            @Override
            boolean tick() {
                return timeOfDay() >= SeasonsConfig.DEFAULT_GATHER_FROM_TICK + 100;
            }

            @Override
            void end(final boolean done) {
                check("the mod jar has its mixin config", me.lovkar.seasons.ColonySeasons.class.getResource("/colony_seasons.mixins.json") != null, "");
                // read before any call of the probe's own: only the citizens' AI asked
                check("the mixin is applied: the citizens' AI asks the gathering", Gathering.mixinCalls() > calls0[0],
                        (Gathering.mixinCalls() - calls0[0]) + " calls");
                check("whoComes is everyone_once_a_day", Gathering.WHO_EVERYONE_ONCE.equals(SeasonsConfig.whoComes()), SeasonsConfig.whoComes());
            }
        });
        step(new Step("guards: the festival hours of the everyone day", 2300, true) {
            boolean sameTick;
            boolean guardBrought;
            int everyoneSeen;
            double workerMin = Double.MAX_VALUE;
            double guardMin = Double.MAX_VALUE;
            int samples;
            final java.util.Set<String> guardStates = new java.util.LinkedHashSet<>();
            final java.util.Set<String> workerStates = new java.util.LinkedHashSet<>();

            @Override
            boolean tick() {
                final long time = timeOfDay();
                if (t % 20 == 0 && time >= SeasonsConfig.DEFAULT_GATHER_FROM_TICK + 100) {
                    final EntityCitizen g = entity(guard);
                    final EntityCitizen w = entity(worker);
                    if (g != null && w != null && Gathering.everyone().contains(colony)) {
                        everyoneSeen++;
                        final boolean guardNow = Gathering.bringWorker(g);
                        final boolean workerNow = Gathering.bringWorker(w);
                        guardBrought |= guardNow;
                        if (!sameTick && workerNow && !guardNow) {
                            sameTick = true;
                            note("at {}: the worker is brought, the guard is not", time);
                        }
                    }
                    if (time >= SAMPLE_FROM) {
                        samples++;
                        workerMin = Math.min(workerMin, dist(worker));
                        guardMin = Math.min(guardMin, dist(guard));
                        guardStates.add(state(guard));
                        workerStates.add(state(worker));
                    }
                }
                return time >= SeasonsConfig.DEFAULT_GATHER_UNTIL_TICK - 40;
            }

            @Override
            void end(final boolean done) {
                check("the colony gathers everyone in the hours today (the everyone day)", everyoneSeen > 0 && samples > 0,
                        everyoneSeen + " polls with everyone, " + samples + " samples");
                check("at the same tick the guard is not brought and the worker is", sameTick, "");
                check("over the hours the guard is never brought", !guardBrought, "guard states " + guardStates);
                check("the worker reaches the tree in the hours", workerMin <= WITHIN, String.format("nearest %.1f, states %s", workerMin, workerStates));
                check("the guard stays at his post (never within " + WITHIN + " blocks of the tree)", guardMin > WITHIN,
                        String.format("nearest %.1f, states %s", guardMin, guardStates));
            }
        });
    }

    // ================================================================ restart (item 7: the marker is saved with the player)

    private Path playerFile() {
        return server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(RESTART_PLAYER + ".dat");
    }

    private PlayerDataStorage storage() throws ReflectiveOperationException {
        try {
            final Field f = MinecraftServer.class.getDeclaredField("playerDataStorage");
            f.setAccessible(true);
            return (PlayerDataStorage) f.get(server);
        } catch (final NoSuchFieldException other) {
            final Field f = PlayerList.class.getDeclaredField("playerIo");
            f.setAccessible(true);
            return (PlayerDataStorage) f.get(server.getPlayerList());
        }
    }

    private void planRestartSave() {
        step(new Step("restart-save: a player welcomed and saved through the server's player storage", 40) {
            @Override
            void start() {
                ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, 2, 2) - 1;
                final Listener p = listener(RESTART_PLAYER, "restart");
                login(p);
                check("the player is welcomed once and marked", p.welcomes() == 1 && marked(p), p.got.size() + " messages");
                try {
                    storage().save(p);
                } catch (final ReflectiveOperationException | ClassCastException unreachable) {
                    check("the server's player storage is reachable", false, unreachable.getClass().getSimpleName().replace("Exception", "X"));
                }
                check("the player's file is written", Files.exists(playerFile()), "");
            }

            @Override
            boolean tick() {
                return t >= 20;
            }
        });
    }

    private void planRestartLoad() {
        step(new Step("restart-load: the same player read back: marked, no line", 40) {
            @Override
            void start() {
                ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, 2, 2) - 1;
                check("the player's file is still there", Files.exists(playerFile()), "");
                final Listener p = listener(RESTART_PLAYER, "restart");
                check("a fresh player object has no marker before it is read", !marked(p), "");
                final Optional<CompoundTag> read = server.getPlayerList().load(p);
                check("the player list reads the saved player back", read.isPresent(), "");
                check("after the restart the marker is set", marked(p) && Welcome.shown(p), "");
                login(p);
                checkEq("and a login sends no line", p.welcomes(), 0);
                checkEq("nothing was sent in this run", Welcome.sent(), 0);
            }

            @Override
            boolean tick() {
                return t >= 20;
            }
        });
    }
}
