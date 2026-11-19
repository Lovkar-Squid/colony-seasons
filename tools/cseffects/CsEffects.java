package me.lovkar.seasons.cseffects;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.tileentities.AbstractTileEntityColonyBuilding;
import com.minecolonies.core.blocks.MinecoloniesCropBlock;
import com.minecolonies.core.colony.CitizenData;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import com.minecolonies.core.colony.buildings.modules.settings.BoolSetting;
import com.minecolonies.core.entity.citizen.citizenhandlers.CitizenHappinessHandler;
import io.netty.buffer.Unpooled;
import me.lovkar.seasons.SeasonsConfig;
import me.lovkar.seasons.block.SeasonsBlocks;
import me.lovkar.seasons.block.SeasonsTileEntity;
import me.lovkar.seasons.calendar.CalendarManager;
import me.lovkar.seasons.calendar.DateSource;
import me.lovkar.seasons.calendar.Season;
import me.lovkar.seasons.colony.BuildingDecorator;
import me.lovkar.seasons.colony.DecoratorInfoModule;
import me.lovkar.seasons.colony.DecoratorInfoModuleView;
import me.lovkar.seasons.colony.DecoratorModules;
import me.lovkar.seasons.colony.DecoratorSetup;
import me.lovkar.seasons.colony.DecoratorTexts;
import me.lovkar.seasons.colony.JobDecorator;
import me.lovkar.seasons.decor.DecorKinds;
import me.lovkar.seasons.decor.DecorLedger;
import me.lovkar.seasons.decor.DecorPlacer;
import me.lovkar.seasons.decor.DecorPlanner;
import me.lovkar.seasons.decor.DecorWork;
import me.lovkar.seasons.decor.DoorFinder;
import me.lovkar.seasons.effects.Effects;
import me.lovkar.seasons.effects.FoodEffect;
import me.lovkar.seasons.effects.HappinessEffect;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.level.block.CropGrowEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Headless check of the effects on the game and the Decorator's hut window data (chunk S8). Modes (the rig writes the word
 * to probe-mode.txt; the seeded config of each mode is in rigconfig-off/ and rigconfig-on/):
 * <ul>
 *   <li>{@code off}: all three switches false. A forced festival and winter change nothing: no modifier on any colonist,
 *       the happiness unchanged, no crop growth step vetoed (inside the claim or out), saturation over a game day as
 *       before (summer day against winter day), the effects' own counters at zero;</li>
 *   <li>{@code on}: all three true. On a forced festival every colonist carries the modifier and its happiness is higher by
 *       the bonus, the total a client would be sent equals the server's (the citizen window's list of reasons leaves ours
 *       out: proven), it comes back after a simulated world load and is neutral when the festival ends; in winter about
 *       half the growth steps inside the claim are vetoed and none outside or in summer, MineColonies' own crop block does
 *       not fire the event (the limit the README states); in winter colonists use a quarter more food.</li>
 * </ul>
 * The colonists' own food use is played by the probe (a small drop every few ticks): MineColonies takes no fake player for a
 * nearby player, so a headless colony is never active and its colonists never get hungry on their own. What the probe checks
 * is that the effect takes the right extra share of whatever the saturation lost.
 *
 * <p>Both modes also check the Decorator's hut window page (the info module: its packet read back by the view), the kind
 * settings and that the planner and the placing honour them on a hand-built house, and that the tree switch keeps the
 * square out of his work list.
 *
 * <p>Prints "[cseffects] CHECK ...: OK|FAIL" lines and "[cseffects] RESULT cseffects: PASS|FAIL", then halts. The words
 * "error" and "exception" stay out of the details: the rig takes such a line for a failure.</p>
 */
@Mod("colonyseasonseffects")
public class CsEffects {
    private static final Logger LOGGER = LoggerFactory.getLogger("cseffects");
    private static final int SETUP_TICK = 60;
    private static final long WATCHDOG_SECONDS = 420;
    private static final BlockPos HUT_OFFSET = new BlockPos(8, 0, 0);
    private static final LocalDate SUMMER = LocalDate.of(2026, 7, 15);
    private static final LocalDate FESTIVAL_DAY = LocalDate.of(2026, 12, 25);
    private static final LocalDate AFTER = LocalDate.of(2027, 1, 10);
    private static final int NOON = 6000;
    /** The probe plays the colonists' own food use: this much saturation every {@link #WORK_EVERY} ticks (about 5 a day). */
    private static final double WORK_DROP = 0.0085;
    private static final int WORK_EVERY = 40;
    private static final int CITIZENS = 4;

    private int ticks;
    private boolean failed;
    private boolean finished;
    private String mode = "";
    private boolean on;
    private long setupNanos;

    private MinecraftServer server;
    private ServerLevel level;
    private ServerPlayer owner;
    private IColony colony;
    private int colonyId;
    private int ground;
    private BlockPos hutPos;
    private BuildingDecorator hut;
    private final List<ICitizenData> citizens = new ArrayList<>();
    private DecorLedger ledger;

    private final List<Step> steps = new ArrayList<>();
    private int stepIndex;

    // crop tallies (the listener runs after ours)
    private int cropEventsInside;
    private int cropVetoInside;
    private int cropEventsOutside;
    private int cropVetoOutside;
    private BlockPos mcCropPos;
    private int mcCropEvents;

    public CsEffects() {
        NeoForge.EVENT_BUS.addListener(this::onTick);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::tally);
    }

    // ================================================================ logging

    private void check(final String what, final boolean ok, final String detail) {
        LOGGER.info("[cseffects] CHECK {}: {} {}", what, ok ? "OK" : "FAIL", detail);
        failed |= !ok;
    }

    private void checkEq(final String what, final Object actual, final Object expected) {
        check(what, Objects.equals(actual, expected), "got " + actual + (Objects.equals(actual, expected) ? "" : ", wanted " + expected));
    }

    private static void note(final String text, final Object... args) {
        LOGGER.info("[cseffects] " + text, args);
    }

    private static String f(final double value) {
        return String.format(Locale.ROOT, "%.3f", value);
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
                on = "on".equals(mode);
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
            final StringBuilder where = new StringBuilder();
            for (int i = 0; i < Math.min(14, t.getStackTrace().length); i++) {
                where.append(" < ").append(t.getStackTrace()[i].getClassName().replaceAll("^.*[.]", "")).append('.').append(t.getStackTrace()[i].getMethodName())
                        .append(':').append(t.getStackTrace()[i].getLineNumber());
            }
            Throwable cause = t.getCause();
            while (cause != null) {
                where.append(" | cause ").append(cause.toString().replace("Exception", "Exc")).append(cause.getStackTrace().length > 0 ? " at " + cause.getStackTrace()[0] : "");
                cause = cause.getCause();
            }
            check("the probe ran without a throwable", false, t.toString().replace("Exception", "Exc") + where);
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
            step.nanos = System.nanoTime();
            if (step.sprint) {
                server.tickRateManager().requestGameToSprint(step.maxTicks + 200);
            }
            step.start();
        }
        step.t++;
        final boolean done = step.tick();
        if (done || step.t >= step.maxTicks) {
            final double seconds = Math.max(1e-3, (System.nanoTime() - step.nanos) / 1e9);
            note("step '{}' ran {} ticks in {} s", step.name, step.t, String.format(Locale.ROOT, "%.1f", seconds));
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
        LOGGER.info("[cseffects] RESULT cseffects: {}", failed ? "FAIL" : "PASS");
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
        long nanos;

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

    private void step(final Step step) {
        steps.add(step);
    }

    private void plan() {
        if (!"off".equals(mode) && !"on".equals(mode)) {
            check("known mode", false, "'" + mode + "'");
            finish();
            return;
        }
        planSetup();
        planWindow();
        planSettings();
        planHappiness();
        planCrops();
        planFood();
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

    private void set(final BlockPos pos, final BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }

    /** A colony with a town hall, the Decorator's hut (level 3) with one Decorator, and more citizens without a job. */
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
        colony = IColonyManager.getInstance().createColony(level, center, owner, "Seasons Effects Test", "Medieval Oak");
        colonyId = colony == null ? -1 : colony.getID();
        check("a colony was made", colony != null, "id " + colonyId + " at " + center + ", ground y " + ground);
        final Block townHall = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.fromNamespaceAndPath("minecolonies", "blockhuttownhall")).orElse(null);
        if (townHall != null) {
            level.setBlock(center, townHall.defaultBlockState(), 3);
            if (level.getBlockEntity(center) instanceof AbstractTileEntityColonyBuilding te) {
                colony.getServerBuildingManager().addNewBuilding(te, level);
            }
        }
        hutPos = center.offset(HUT_OFFSET);
        level.setBlock(hutPos, DecoratorSetup.HUT.get().defaultBlockState(), 3);
        final BlockEntity be = level.getBlockEntity(hutPos);
        check("the hut block makes our block entity", be instanceof SeasonsTileEntity, be == null ? "none" : be.getClass().getSimpleName());
        final IBuilding building = be instanceof AbstractTileEntityColonyBuilding te ? colony.getServerBuildingManager().addNewBuilding(te, level) : null;
        check("the hut registers as a BuildingDecorator", building instanceof BuildingDecorator, String.valueOf(building));
        hut = (BuildingDecorator) building;
        hut.setBuildingLevel(3);
        hut.markDirty();

        for (int i = 0; i < CITIZENS; i++) {
            final ICitizenData citizen = colony.getCitizenManager().createAndRegisterCivilianData();
            colony.getCitizenManager().spawnOrCreateCitizen(citizen, level, hutPos.offset(i, 1, 2));
            citizens.add(citizen);
        }
        final WorkerBuildingModule work = hut.getModule(DecoratorModules.WORK);
        check("the first citizen is hired as the Decorator", work.assignCitizen(citizens.get(0)) && citizens.get(0).getJob() instanceof JobDecorator, "");
        ledger = DecorLedger.get(level);
        note("colony {} ready: {} citizens, hut at {} level {}", colonyId, citizens.size(), hutPos, hut.getBuildingLevel());
    }

    private boolean claimed(final BlockPos pos) {
        return IColonyManager.getInstance().isCoordinateInAnyColony(level, pos);
    }

    private JobDecorator job() {
        return citizens.get(0).getJob() instanceof JobDecorator j ? j : null;
    }

    // ================================================================ setup

    private void planSetup() {
        step(new Step("setup", 1, false) {
            @Override
            void start() {
                setDate(SUMMER);
                quietWorld();
                buildColony();
                level.setDayTime(NOON);
                // the config of this mode, read back through the getters
                checkEq("festivalHappiness in mode " + mode, SeasonsConfig.festivalHappiness(), on);
                checkEq("winterCropSlowdown in mode " + mode, SeasonsConfig.winterCropSlowdown(), on);
                checkEq("winterFoodNeed in mode " + mode, SeasonsConfig.winterFoodNeed(), on);
                checkEq("the bonus, skip chance and food factor are the documented defaults",
                        List.of(SeasonsConfig.festivalHappinessBonus(), SeasonsConfig.winterCropSkipChance(), SeasonsConfig.winterFoodNeedFactor()),
                        List.of(1.0, 0.5, 1.25));
                note("colony active right after creation: {}", colony.isActive());
                check("the info module is registered with a view", DecoratorModules.INFO.hasServerModule() && DecoratorModules.INFO.hasView()
                        && hut.getModule(DecoratorModules.INFO) != null, "");
            }
        });
    }

    // ================================================================ the hut window page

    private DecoratorInfoModule.Info viaPacket(final DecoratorInfoModule module, final boolean[] consumed) {
        final RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        module.serializeToView(buf);
        final DecoratorInfoModuleView view = new DecoratorInfoModuleView();
        view.deserialize(buf);
        consumed[0] = buf.readableBytes() == 0;
        return view.info();
    }

    private void planWindow() {
        step(new Step("the hut window page", 400, true) {
            @Override
            void start() {
                CalendarManager.setSeason(server, Season.WINTER);
                CalendarManager.setForcedFestival(server, "winter_festival");
                setDate(FESTIVAL_DAY);
            }

            @Override
            boolean tick() {
                return t >= 300 && job() != null && !job().statusKey().isEmpty();
            }

            @Override
            void end(final boolean done) {
                final DecoratorInfoModule module = hut.getModule(DecoratorModules.INFO);
                final boolean[] consumed = new boolean[1];
                final DecoratorInfoModule.Info server0 = module.read();
                final DecoratorInfoModule.Info client = viaPacket(module, consumed);
                check("the page's packet reads back to what the server meant, every byte used", server0.equals(client) && consumed[0],
                        consumed[0] ? "" : "bytes left over");
                checkEq("the calendar on the page", List.of(client.season(), client.yearLength()), List.of(Season.WINTER.ordinal(), 48));
                checkEq("the festival on the page", client.festival(), "winter_festival");
                check("festival days are on, with both windows", client.festivalDays() && client.decorWindow().equals("12-01 01-06")
                        && client.daysWindow().equals("12-24 12-26"), client.decorWindow() + " / " + client.daysWindow());
                check("his status is a lang key of ours with its arguments", client.statusKey().startsWith("com.colony_seasons.status."),
                        client.statusKey() + " " + client.statusArgs());
                check("the page says what each effect does", client.happinessOn() == on && client.cropsOn() == on && client.foodOn() == on
                        && client.winter() && client.stockCap() == hut.stockCap() && client.quota() == DecorWork.quota(3),
                        "happiness " + client.happinessOn() + ", crops " + client.cropsOn() + ", food " + client.foodOn() + ", cap " + client.stockCap());
                check("the stock rows list the kits, the next kit is named", !client.stock().isEmpty() && !client.nextKit().isEmpty(),
                        client.stock().size() + " rows, next " + client.nextKit());
                check("an empty hut lacks materials for the next kit", !client.missing().isEmpty(), client.missing().toString());
                note("page: {}", client);
                // no festival and the summer: the next festival with its days until
                CalendarManager.setForcedFestival(server, "");
                setDate(SUMMER);
                CalendarManager.setSeason(server, Season.SUMMER);
                final DecoratorInfoModule.Info summer = viaPacket(module, consumed);
                check("in summer with no festival on the page names the next one and the days until it",
                        summer.festival().equals("winter_festival") && summer.daysUntilNext() > 0 && !summer.festivalDays() && !summer.winter()
                                && consumed[0], summer.festival() + " in " + summer.daysUntilNext() + " days");
                // nobody works at a hut with no worker
                final DecoratorInfoModule bare = new DecoratorInfoModule();
                final RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
                bare.serializeToView(buf);
                final DecoratorInfoModuleView view = new DecoratorInfoModuleView();
                view.deserialize(buf);
                check("a module that belongs to no building still sends a packet the view reads", buf.readableBytes() == 0
                        && view.info().statusKey().equals(DecoratorTexts.NO_WORKER), view.info().statusKey());
            }
        });
    }

    // ================================================================ settings and the kinds

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
            final BlockState lower = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, net.minecraft.core.Direction.NORTH)
                    .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER).setValue(DoorBlock.HINGE, DoorHingeSide.LEFT);
            set(new BlockPos(doors[i], y0 + 1, zs), lower);
            set(new BlockPos(doors[i], y0 + 2, zs), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        }
        return new DoorFinder.Box(new BlockPos(x0, y0, z0), new BlockPos(x0 + w - 1, y0 + 6, z0 + d - 1),
                new BlockPos(x0 + w / 2, y0 + 1, z0 + d / 2));
    }

    private int countOf(final DecorLedger.Owner owner, final Block... blocks) {
        final DecorLedger.GroupView group = ledger.group(colonyId, owner);
        int n = 0;
        if (group != null) {
            for (final DecorLedger.Entry entry : group.entries().values()) {
                for (final Block block : blocks) {
                    n += entry.state().is(block) ? 1 : 0;
                }
            }
        }
        return n;
    }

    private void toggle(final net.minecraft.core.BlockPos unused, final com.minecolonies.api.colony.buildings.modules.settings.ISettingKey<BoolSetting> key) {
        hut.getModule(DecoratorModules.SETTINGS).getSetting(key).trigger();
    }

    private void planSettings() {
        step(new Step("settings, kinds and the planner on a hand-built house", 60, true) {
            DoorFinder.Box box;

            @Override
            void start() {
                box = house(-30, 36, 11, 7, -25);
            }

            @Override
            boolean tick() {
                return t >= 40 && !level.getChunkSource().getLightEngine().hasLightWork();
            }

            @Override
            void end(final boolean done) {
                checkEq("every setting is on by default", hut.kinds(), DecorKinds.ALL);
                check("decorating is on by default", hut.decorateEnabled(), "");
                check("the house stands inside the claim", claimed(new BlockPos(-25, ground + 3, 43)), "");
                final DecorWork.Target target = DecorWork.Target.box(box);
                final DecorPlanner.Plan all = DecorPlanner.plan(level, box, 5, 6, ledger, colonyId, target.owner(), true, DecorKinds.ALL);
                check("with everything on the house gets a wreath, garlands and lights", all.wreaths() == 1 && all.garlands() > 0 && all.lights() > 0,
                        all.wreaths() + " wreath, " + all.garlands() + " garland, " + all.lights() + " lights");
                final DecorPlanner.Plan same = DecorPlanner.plan(level, box, 5, 6, ledger, colonyId, target.owner(), true);
                check("the old call is the all-kinds call", same.slots().equals(all.slots()), "");
                final DecorPlanner.Plan noWreath = DecorPlanner.plan(level, box, 5, 6, ledger, colonyId, target.owner(), true, new DecorKinds(false, true, true, true));
                final DecorPlanner.Plan noGarland = DecorPlanner.plan(level, box, 5, 6, ledger, colonyId, target.owner(), true, new DecorKinds(true, false, true, true));
                final DecorPlanner.Plan noLights = DecorPlanner.plan(level, box, 5, 6, ledger, colonyId, target.owner(), true, new DecorKinds(true, true, false, true));
                final DecorPlanner.Plan nothing = DecorPlanner.plan(level, box, 5, 6, ledger, colonyId, target.owner(), true, new DecorKinds(false, false, false, true));
                check("no wreaths: the planner leaves the wreath out and nothing else",
                        noWreath.wreaths() == 0 && noWreath.garlands() == all.garlands() && noWreath.lights() == all.lights(), noWreath.toString());
                check("no garlands: the planner leaves the garlands out and nothing else",
                        noGarland.garlands() == 0 && noGarland.wreaths() == all.wreaths() && noGarland.lights() == all.lights(), noGarland.toString());
                check("no lights: the planner leaves the lights out and nothing else",
                        noLights.lights() == 0 && noLights.wreaths() == all.wreaths() && noLights.garlands() == all.garlands(), noLights.toString());
                check("all three off: nothing is planned", nothing.slots().isEmpty(), nothing.slots().size() + " slots");

                // through the hut's settings and the stock the AI hands to DecorWork
                toggle(null, BuildingDecorator.KIND_GARLANDS);
                toggle(null, BuildingDecorator.KIND_TREE);
                final DecorKinds kinds = hut.kinds();
                check("the settings switch the kinds off", !kinds.garlands() && !kinds.tree() && kinds.wreaths() && kinds.lights(), kinds.toString());
                final DecorPlacer.Stock stock = DecorWork.stockOf(hut, null);
                checkEq("the stock the AI uses carries the hut's kinds", stock.kinds(), kinds);
                final DecorPlacer.Stock free = DecorPlacer.Stock.withKinds(DecorPlacer.Stock.FREE, kinds);
                final long day = level.getDayTime() / 24000L;
                final DecorWork.Outcome outcome = DecorWork.decorate(level, colonyId, target, 5, free, day, ledger);
                final int wreaths = countOf(target.owner(), SeasonsBlocks.WREATH.get());
                final int garlands = countOf(target.owner(), SeasonsBlocks.GARLAND.get());
                final int lights = countOf(target.owner(), SeasonsBlocks.LANTERN_STRING.get(), SeasonsBlocks.FAIRY_LIGHTS.get());
                check("DecorWork with garlands off puts up a wreath and lights and no garland",
                        outcome.placed() > 0 && wreaths == 1 && garlands == 0 && lights == all.lights(), outcome.placed() + " placed: " + wreaths + " wreath, "
                                + garlands + " garland, " + lights + " lights");
                DecorPlacer.remove(level, ledger, colonyId, target.owner(), DecorPlacer.Stock.FREE);
                ledger.dropGroup(colonyId, target.owner());
                toggle(null, BuildingDecorator.KIND_GARLANDS);
                toggle(null, BuildingDecorator.KIND_TREE);
                checkEq("switching the kinds back on", hut.kinds(), DecorKinds.ALL);
            }
        });

        // the tree: with the switch off the square is not even looked at; with it on, it is (its piece is asked for)
        final String piecePath = me.lovkar.seasons.decor.PieceLoader.pathFor(5);
        final net.minecraft.core.Direction facing = net.minecraft.core.Direction.NORTH;
        step(new Step("the tree switch: the square is looked at only when the switch is on", 500, true) {
            BlockPos marker;
            boolean offAsked;
            boolean offered;
            int offTicks;

            @Override
            void start() {
                marker = new BlockPos(-30, ground + 1, 60);
                set(marker.below(), Blocks.STONE.defaultBlockState());
                level.setBlock(marker, SeasonsBlocks.FESTIVAL_MARKER.get().defaultBlockState()
                        .setValue(me.lovkar.seasons.block.FestivalMarkerBlock.FACING, facing), Block.UPDATE_ALL);
                setDate(SUMMER);
                me.lovkar.seasons.decor.PieceLoader.clear();
                ledger.registerMarker(colonyId, marker);
                check("the marker is registered with the ledger", marker.equals(ledger.marker(colonyId)), String.valueOf(ledger.marker(colonyId)));
            }

            @Override
            boolean tick() {
                if (t % 10 != 0) {
                    return false;
                }
                final long day = level.getDayTime() / 24000L;
                if (t <= 100) {
                    final DecorWork.Target next = DecorWork.nextToDecorate(level, colony, 5, hutPos, ledger,
                            DecorPlacer.Stock.withKinds(DecorPlacer.Stock.FREE, new DecorKinds(true, true, true, false)), day);
                    offTicks = t;
                    offAsked |= next != null && next.owner().kind() == DecorLedger.Kind.SQUARE;
                    if (t == 100) {
                        // nobody has asked for the piece yet: a first request finds nothing cached (and starts the load phase B waits for)
                        offAsked |= me.lovkar.seasons.decor.PieceLoader.request(level, me.lovkar.seasons.decor.PieceLoader.PACK, piecePath, facing) != null
                                || me.lovkar.seasons.decor.PieceLoader.missing(me.lovkar.seasons.decor.PieceLoader.PACK, piecePath, facing);
                    }
                    return false;
                }
                final DecorWork.Target next = DecorWork.nextToDecorate(level, colony, 5, hutPos, ledger,
                        DecorPlacer.Stock.withKinds(DecorPlacer.Stock.FREE, DecorKinds.ALL), day);
                offered = next != null && next.owner().kind() == DecorLedger.Kind.SQUARE;
                return offered || me.lovkar.seasons.decor.PieceLoader.missing(me.lovkar.seasons.decor.PieceLoader.PACK, piecePath, facing);
            }

            @Override
            void end(final boolean done) {
                check("with the tree off the square is never offered and its piece is never asked for", !offAsked, offTicks + " ticks of looking");
                check("with the tree on the square is looked at: it is offered, or its piece is found missing", done, "after " + t + " ticks, " + (offered ? "offered" : "piece missing"));
                level.setBlock(marker, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                me.lovkar.seasons.decor.PieceLoader.clear();
            }
        });
    }

    // ================================================================ happiness

    private double server0(final ICitizenData citizen) {
        return citizen.getCitizenHappinessHandler().getHappiness(colony, citizen);
    }

    /**
     * What a client is sent for one citizen: MineColonies' own packet, read as far as the happiness total (the view class
     * itself cannot be loaded on a server, it reaches into the client). The order is the one of
     * {@code CitizenData.serializeViewNetworkData}: name, gender, entity id, paused, child, home (flag and position),
     * work building (flag and position), saturation, happiness; the saturation read here is checked against the
     * citizen's own, so a misread order cannot pass unnoticed. Returns {saturation, happiness}.
     */
    private double[] clientPacket(final ICitizenData citizen) {
        final RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        ((CitizenData) citizen).serializeViewNetworkData(buf);
        buf.readUtf();
        buf.readBoolean();
        buf.readInt();
        buf.readBoolean();
        buf.readBoolean();
        if (buf.readBoolean()) {
            buf.readBlockPos();
        }
        if (buf.readBoolean()) {
            buf.readBlockPos();
        }
        final double saturation = buf.readDouble();
        final double happiness = buf.readDouble();
        return new double[] {saturation, happiness};
    }

    /** The list of reasons a client builds from the same packet: a handler fed the written modifiers, as the view does. */
    private CitizenHappinessHandler clientReasons(final ICitizenData citizen) {
        final net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        citizen.getCitizenHappinessHandler().write(level.registryAccess(), tag, true);
        final CitizenHappinessHandler client = new CitizenHappinessHandler();
        client.read(level.registryAccess(), tag, true);
        return client;
    }

    private void planHappiness() {
        final double[] baseline = new double[CITIZENS];
        step(new Step("festival happiness: the festival, the client's total, a world load, the end", 1, false) {
            @Override
            void start() throws RuntimeException {
                CalendarManager.setForcedFestival(server, "");
                setDate(SUMMER);
                note("the colony is {}: MineColonies does not take a fake player for a nearby player, so nothing here ticks the colonists' hunger on its own",
                        colony.isActive() ? "active" : "inactive");
                Effects.pass();
                for (int i = 0; i < CITIZENS; i++) {
                    baseline[i] = server0(citizens.get(i));
                }
                note("happiness before the festival: {}", List.of(f(baseline[0]), f(baseline[1]), f(baseline[2]), f(baseline[3])));
                check("no colonist carries the modifier outside a festival", citizens.stream().noneMatch(HappinessEffect::present), "");
                check("every colonist has a happiness to start from", java.util.Arrays.stream(baseline).allMatch(h -> !Double.isNaN(h) && h < 9.0),
                        "needs room under the cap of 10 to see a bonus: " + java.util.Arrays.toString(baseline));

                // the festival days of the real calendar
                setDate(FESTIVAL_DAY);
                check("festival days are on", !CalendarManager.current().festivals().days().isEmpty(), "");
                Effects.pass();
                int present = 0;
                double worst = 0;
                for (int i = 0; i < CITIZENS; i++) {
                    final ICitizenData c = citizens.get(i);
                    present += HappinessEffect.present(c) ? 1 : 0;
                    worst = Math.max(worst, Math.abs(server0(c) - baseline[i] - (on ? SeasonsConfig.festivalHappinessBonus() : 0.0)));
                }
                if (on) {
                    checkEq("on a festival every colonist carries the modifier", present, CITIZENS);
                    check("each colonist's happiness is higher by the bonus (1.0 points)", worst < 0.02, "largest deviation " + f(worst));
                } else {
                    checkEq("off: no colonist carries the modifier on a festival", present, 0);
                    check("off: every colonist's happiness is exactly what it was", worst == 0.0, "largest change " + f(worst));
                    check("off: the happiness handler has no entry of ours at all", citizens.stream()
                            .allMatch(c -> c.getCitizenHappinessHandler().getModifier(HappinessEffect.ID) == null), "");
                }

                // the total a client is shown
                int same = 0;
                int parsed = 0;
                boolean listed = false;
                for (int i = 0; i < CITIZENS; i++) {
                    final ICitizenData c = citizens.get(i);
                    final double[] sent = clientPacket(c);
                    parsed += Math.abs(sent[0] - c.getSaturation()) < 1e-9 ? 1 : 0;
                    same += Math.abs(sent[1] - server0(c)) < 1e-9 ? 1 : 0;
                    listed |= clientReasons(c).getModifier(HappinessEffect.ID) != null;
                }
                check("the client packet was read in the right order (its saturation is the citizen's)", parsed == CITIZENS, parsed + " of " + CITIZENS);
                check("[V] the happiness total a client is sent equals the server's", same == CITIZENS, same + " of " + CITIZENS);
                check("[V] the client's list of reasons (the citizen window) does not hold our modifier", !listed, "");

                if (on) {
                    // a world load: what MineColonies writes and reads back keeps none of ours
                    final ICitizenData c0 = citizens.get(0);
                    final net.minecraft.nbt.CompoundTag saved = new net.minecraft.nbt.CompoundTag();
                    c0.getCitizenHappinessHandler().write(level.registryAccess(), saved, false);
                    final CitizenHappinessHandler loaded = new CitizenHappinessHandler(c0);
                    loaded.read(level.registryAccess(), saved, false);
                    check("[V] MineColonies drops a custom id on a world load", loaded.getModifier(HappinessEffect.ID) == null
                            && loaded.getModifiers().size() < c0.getCitizenHappinessHandler().getModifiers().size(),
                            loaded.getModifiers().size() + " ids loaded, " + c0.getCitizenHappinessHandler().getModifiers().size() + " before");
                    // lose ours the way a load does, the server puts it back by itself on its next pass
                    for (final ICitizenData c : citizens) {
                        ((CitizenHappinessHandler) c.getCitizenHappinessHandler()).happinessFactors.remove(HappinessEffect.ID);
                    }
                    check("after a simulated load nobody carries it", citizens.stream().noneMatch(HappinessEffect::present), "");
                }
            }
        });
        step(new Step("festival happiness: the server's own passes put it back", 260, true) {
            @Override
            void end(final boolean done) {
                int present = 0;
                for (final ICitizenData c : citizens) {
                    present += HappinessEffect.present(c) ? 1 : 0;
                }
                checkEq(on ? "the server's passes put the modifier back on every colonist" : "off: the server's passes never put one on",
                        present, on ? CITIZENS : 0);
            }
        });
        step(new Step("festival happiness: the festival ends", 1, false) {
            @Override
            void start() {
                setDate(AFTER);
                check("festival days are over", CalendarManager.current().festivals().days().isEmpty(), "");
                Effects.pass();
                check("after the festival nobody carries the modifier", citizens.stream().noneMatch(HappinessEffect::present), "");
                double worst = 0;
                for (int i = 0; i < CITIZENS; i++) {
                    worst = Math.max(worst, Math.abs(server0(citizens.get(i)) - baseline[i]));
                }
                check("after the festival the happiness is what it was before it", worst < 0.15, "largest change " + f(worst));
                setDate(SUMMER);
            }
        });
    }

    // ================================================================ crops

    private void tally(final CropGrowEvent.Pre event) {
        final BlockPos pos = event.getPos();
        final boolean veto = event.getResult() == CropGrowEvent.Pre.Result.DO_NOT_GROW;
        if (mcCropPos != null && pos.equals(mcCropPos)) {
            mcCropEvents++;
        } else if (pos.getX() < 1000) {
            cropEventsInside++;
            cropVetoInside += veto ? 1 : 0;
        } else {
            cropEventsOutside++;
            cropVetoOutside += veto ? 1 : 0;
        }
    }

    private static final int FIELD = 20;
    private static final int TRIALS = 25;
    private BlockPos insideAt;
    private BlockPos outsideAt;

    private void field(final BlockPos at, final int groundY) {
        for (int dx = 0; dx < FIELD; dx++) {
            for (int dz = 0; dz < FIELD; dz++) {
                final BlockPos soil = new BlockPos(at.getX() + dx, groundY, at.getZ() + dz);
                set(soil, Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7));
                set(soil.above(), Blocks.WHEAT.defaultBlockState());
            }
        }
    }

    /** Random-ticks every plant of a field {@link #TRIALS} times; a plant that grew is set back so it never tops out. Returns the growth steps. */
    private int grow(final BlockPos at, final int groundY) {
        int grown = 0;
        for (int dx = 0; dx < FIELD; dx++) {
            for (int dz = 0; dz < FIELD; dz++) {
                final BlockPos pos = new BlockPos(at.getX() + dx, groundY + 1, at.getZ() + dz);
                for (int k = 0; k < TRIALS; k++) {
                    final BlockState before = level.getBlockState(pos);
                    if (!before.is(Blocks.WHEAT)) {
                        set(pos, Blocks.WHEAT.defaultBlockState());
                        continue;
                    }
                    before.randomTick(level, pos, level.getRandom());
                    final BlockState after = level.getBlockState(pos);
                    if (after.is(Blocks.WHEAT) && after.getValue(CropBlock.AGE) > before.getValue(CropBlock.AGE)) {
                        grown++;
                        set(pos, Blocks.WHEAT.defaultBlockState());
                    }
                }
            }
        }
        return grown;
    }

    private void resetTally() {
        cropEventsInside = 0;
        cropVetoInside = 0;
        cropEventsOutside = 0;
        cropVetoOutside = 0;
    }

    private static double ratio(final int part, final int whole) {
        return whole == 0 ? Double.NaN : (double) part / whole;
    }

    private void planCrops() {
        step(new Step("crops: lay out two fields, inside the claim and far outside it", 80, true) {
            int groundOut;

            @Override
            void start() {
                insideAt = new BlockPos(0, 0, 8);
                outsideAt = new BlockPos(3000, 0, 8);
                for (int cx = 186; cx <= 190; cx++) {
                    for (int cz = -1; cz <= 3; cz++) {
                        level.setChunkForced(cx, cz, true);
                    }
                }
                level.setDayTime(NOON);
            }

            @Override
            boolean tick() {
                return t >= 60 && level.hasChunkAt(new BlockPos(3010, 64, 20));
            }

            @Override
            void end(final boolean done) {
                final int groundIn = level.getHeight(Heightmap.Types.MOTION_BLOCKING, 5, 15) - 1;
                groundOut = level.getHeight(Heightmap.Types.MOTION_BLOCKING, 3005, 15) - 1;
                field(insideAt, groundIn);
                field(outsideAt, groundOut);
                insideGround = groundIn;
                outsideGround = groundOut;
                check("one field is inside the claim, the other outside every claim",
                        claimed(new BlockPos(0, groundIn + 1, 8)) && claimed(new BlockPos(19, groundIn + 1, 27))
                                && !claimed(new BlockPos(3000, groundOut + 1, 8)) && !claimed(new BlockPos(3019, groundOut + 1, 27)),
                        "inside " + claimed(new BlockPos(0, groundIn + 1, 8)) + "/" + claimed(new BlockPos(19, groundIn + 1, 27))
                                + ", outside " + claimed(new BlockPos(3000, groundOut + 1, 8)));
            }
        });
        step(new Step("crops: light", 60, true) {
            @Override
            boolean tick() {
                return t >= 40 && !level.getChunkSource().getLightEngine().hasLightWork();
            }
        });
        step(new Step("crops: winter and summer trials, and MineColonies' own crop block", 1, false) {
            @Override
            void start() {
                level.setDayTime(NOON);
                CalendarManager.setSeason(server, Season.WINTER);
                check("it is winter in the game", CalendarManager.current().season().season() == Season.WINTER, "");
                resetTally();
                final int growIn = grow(insideAt, insideGround);
                final int vetoedIn = cropVetoInside;
                final int eventsIn = cropEventsInside;
                final int growOut = grow(outsideAt, outsideGround);
                note("winter: inside {} steps of {} events ({} vetoed), outside {} steps of {} events ({} vetoed)", growIn, eventsIn, vetoedIn,
                        growOut, cropEventsOutside, cropVetoOutside);
                check("the trials saw enough growth steps to say anything", eventsIn > 3000 && cropEventsOutside > 3000 && growIn > 100 && growOut > 100,
                        eventsIn + " / " + cropEventsOutside + " events, " + growIn + " / " + growOut + " steps");
                final double vetoIn = ratio(vetoedIn, eventsIn);
                final double vetoOut = ratio(cropVetoOutside, cropEventsOutside);
                final double growRatio = (double) growIn / Math.max(1, growOut);
                if (on) {
                    check("winter: about half the growth steps inside the claim are vetoed", vetoIn > 0.46 && vetoIn < 0.54, f(vetoIn));
                    check("winter: no growth step outside the claim is vetoed", cropVetoOutside == 0, cropVetoOutside + " of " + cropEventsOutside);
                    check("winter: the crops inside the claim grow about half as fast as those outside", growRatio > 0.38 && growRatio < 0.62, f(growRatio));
                } else {
                    check("off: no growth step is vetoed, inside the claim or outside", cropVetoInside == 0 && cropVetoOutside == 0,
                            cropVetoInside + " / " + cropVetoOutside);
                    check("off: crops inside the claim grow as fast as those outside", growRatio > 0.8 && growRatio < 1.25, f(growRatio));
                }

                // the same trials in summer: nothing is vetoed anywhere
                CalendarManager.setSeason(server, Season.SUMMER);
                resetTally();
                final int summerIn = grow(insideAt, insideGround);
                check("summer: no growth step is vetoed", cropVetoInside == 0 && cropEventsInside > 3000, cropVetoInside + " of " + cropEventsInside + " events");
                note("summer: inside {} steps", summerIn);

                // MineColonies' own crop block grows through its own code
                CalendarManager.setSeason(server, Season.WINTER);
                MinecoloniesCropBlock crop = null;
                for (final Block block : BuiltInRegistries.BLOCK) {
                    if (block instanceof MinecoloniesCropBlock c) {
                        crop = c;
                        break;
                    }
                }
                check("MineColonies has a crop block of its own", crop != null, crop == null ? "none" : String.valueOf(BuiltInRegistries.BLOCK.getKey(crop)));
                if (crop != null) {
                    mcCropPos = new BlockPos(10, insideGround + 1, 30);
                    set(mcCropPos.below(), Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7));
                    set(mcCropPos, crop.defaultBlockState());
                    mcCropEvents = 0;
                    final IntegerProperty age = ageOf(crop.defaultBlockState());
                    int steps = 0;
                    for (int i = 0; i < 4000; i++) {
                        final BlockState before = level.getBlockState(mcCropPos);
                        if (!before.is(crop)) {
                            set(mcCropPos, crop.defaultBlockState());
                            continue;
                        }
                        crop.attemptGrow(before, level, mcCropPos);
                        final BlockState after = level.getBlockState(mcCropPos);
                        if (age != null && after.is(crop) && after.getValue(age) > before.getValue(age)) {
                            steps++;
                            set(mcCropPos, crop.defaultBlockState());
                        }
                    }
                    note("MineColonies' crop {}: {} growth steps through attemptGrow, {} CropGrowEvent.Pre events", BuiltInRegistries.BLOCK.getKey(crop), steps, mcCropEvents);
                    check("[V] MineColonies' own crop block grows, and does not fire CropGrowEvent.Pre (so the switch cannot touch it)",
                            steps > 0 && mcCropEvents == 0, steps + " steps, " + mcCropEvents + " events");
                    mcCropPos = null;
                }
                CalendarManager.setSeason(server, Season.SUMMER);
            }
        });
    }

    private int insideGround;
    private int outsideGround;

    private static IntegerProperty ageOf(final BlockState state) {
        for (final Property<?> property : state.getProperties()) {
            if (property instanceof IntegerProperty integer && "age".equals(integer.getName())) {
                return integer;
            }
        }
        return null;
    }

    // ================================================================ food

    private final double[] last = new double[CITIZENS];

    /** One game day: the probe plays the colonists' own food use, and sums what every saturation lost (whoever took it). */
    private final class Day extends Step {
        double total;
        final String label;

        Day(final String label) {
            super(label, 24300, true);
            this.label = label;
        }

        @Override
        void start() {
            level.setDayTime(24000L * 100 + 1000);
            CalendarManager.setSeason(server, label.contains("winter") ? Season.WINTER : Season.SUMMER);
            FoodEffect.reset();
            for (int i = 0; i < CITIZENS; i++) {
                citizens.get(i).setSaturation(ICitizenData.MAX_SATURATION);
                last[i] = citizens.get(i).getSaturation();
            }
        }

        @Override
        boolean tick() {
            if (t % WORK_EVERY == 0) {
                for (final ICitizenData c : citizens) {
                    c.setSaturation(Math.max(0.0, c.getSaturation() - WORK_DROP));
                }
            }
            for (int i = 0; i < CITIZENS; i++) {
                final double now = citizens.get(i).getSaturation();
                if (now < last[i]) {
                    total += last[i] - now;
                }
                last[i] = now;
            }
            return t >= 24000;
        }
    }

    private void planFood() {
        final Day summer = new Day("food: a summer day");
        final Day winter = new Day("food: a winter day");
        step(summer);
        step(winter);
        step(new Step("food: the counts", 1, false) {
            @Override
            void start() {
                final double ratio = winter.total / Math.max(1e-9, summer.total);
                note("saturation lost over a game day: summer {}, winter {} (ratio {}); the effect's own counts: base {}, extra {}",
                        f(summer.total), f(winter.total), f(ratio), f(FoodEffect.baseDrop()), f(FoodEffect.extraDrop()));
                check("the colonists used food on the summer day (the test is not vacuous)", summer.total > CITIZENS * 2.0, f(summer.total));
                if (on) {
                    final double extra = FoodEffect.extraDrop() / Math.max(1e-9, FoodEffect.baseDrop());
                    check("winter: the extra food use is a quarter of the usual, within 10 % of the target", extra > 0.225 && extra < 0.275, f(extra));
                    check("winter: a day costs a quarter more saturation than the summer day, within 10 % of the target", ratio > 1.125 && ratio < 1.375, f(ratio));
                    check("the effect's counts and the measured loss agree", Math.abs(FoodEffect.baseDrop() + FoodEffect.extraDrop() - winter.total) < 0.06 * winter.total,
                            f(FoodEffect.baseDrop() + FoodEffect.extraDrop()) + " against " + f(winter.total));
                } else {
                    check("off: nothing was taken on top", FoodEffect.extraDrop() == 0 && FoodEffect.baseDrop() == 0, f(FoodEffect.extraDrop()) + " / " + f(FoodEffect.baseDrop()));
                    check("off: a winter day costs the same saturation as a summer day", ratio > 0.92 && ratio < 1.08, f(ratio));
                }
                check("the colonists were never starved by the test", citizens.stream().allMatch(c -> c.getSaturation() > 5), "");
            }
        });
    }
}
