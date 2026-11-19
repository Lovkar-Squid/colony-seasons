package me.lovkar.seasons.csdecorator;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.requestable.RequestTag;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.entity.citizen.Skill;
import com.minecolonies.api.sounds.ModSoundEvents;
import com.minecolonies.api.tileentities.AbstractTileEntityColonyBuilding;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import com.minecolonies.core.colony.buildings.modules.settings.BoolSetting;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import me.lovkar.seasons.SeasonsConfig;
import me.lovkar.seasons.ai.EntityAIWorkDecorator.DecoratorState;
import me.lovkar.seasons.block.SeasonsTileEntity;
import me.lovkar.seasons.calendar.CalendarManager;
import me.lovkar.seasons.calendar.DateSource;
import me.lovkar.seasons.calendar.FestivalCalendar;
import me.lovkar.seasons.calendar.SeasonState;
import me.lovkar.seasons.colony.BuildingDecorator;
import me.lovkar.seasons.colony.DecoratorModules;
import me.lovkar.seasons.colony.DecoratorSetup;
import me.lovkar.seasons.colony.JobDecorator;
import me.lovkar.seasons.decor.DecorPhase;
import me.lovkar.seasons.decor.Kits;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Tuple;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Headless check of the Decorator (chunk S3) on a real colony: a town hall, the Decorator's hut at level 1, one
 * citizen hired into it, his AI running for real for three game days. Modes (the rig writes the word to probe-mode.txt):
 * <ul>
 *   <li>none: setup checks (voice lines, registrations, kits, the phase table, what the hut keeps), then the days:
 *       D1 requests from an empty hut, D1 materials in the hut become decorations up to the cap with the AI reset every
 *       300 ticks, N1 a night in bed, D2 three thousand ticks of rain, D2 a level-2 hut in the Decorating window, N2,
 *       D3 the Hosting and Taking-down stubs, the Off season with "prepare all year" off and on, N3. Every 100 ticks
 *       the materials and decorations are counted everywhere (hut, pack, dropped items): nothing may appear or go;</li>
 *   <li>{@code free} (seeded config needsMaterials = false): no request, no material used, nothing made;</li>
 *   <li>{@code restart-save} then {@code restart-load} (tools/restart.sh): stock, the building's NBT and the setting
 *       survive a server restart, and he goes on working afterwards.</li>
 * </ul>
 * The kits come from this probe's own datapack (res/data/csdecorator/decorator_kits/): cs_test (2 wool of any colour
 * and 1 clay ball make 2 name tags) and three broken ones that must be skipped. The date is fixed through
 * {@link CalendarManager#setDateSource}, so the run never depends on the real date. The game runs through tick sprint
 * (real ticks, the AI really runs); the clock is set forward only from dusk to just before dawn. Mob spawning is off
 * and the citizen is kept fed and well, so nothing but the Decorator's own work can change his stock.
 *
 * <p>Prints "[csdecorator] CHECK ...: OK|FAIL" lines and "[csdecorator] RESULT csdecorator: PASS|FAIL", then halts.
 * The words "error" and "exception" stay out of the details: the rig takes such a line for a failure.</p>
 */
@Mod("colonyseasonsdecorator")
public class CsDecorator {
    private static final Logger LOGGER = LoggerFactory.getLogger("csdecorator");
    private static final int SETUP_TICK = 60;
    private static final long WATCHDOG_SECONDS = 300;
    private static final ResourceLocation TEST_KIT = ResourceLocation.fromNamespaceAndPath("csdecorator", "cs_test");
    private static final List<ResourceLocation> BAD_KITS = List.of(
            ResourceLocation.fromNamespaceAndPath("csdecorator", "cs_bad_item"),
            ResourceLocation.fromNamespaceAndPath("csdecorator", "cs_bad_json"),
            ResourceLocation.fromNamespaceAndPath("csdecorator", "cs_bad_tag"));
    private static final List<String> SHIPPED = List.of("fairy_lights", "garland", "lantern_string", "ornament_leaves", "tree_star", "wreath");
    private static final BlockPos HUT_OFFSET = new BlockPos(8, 0, 0);
    private static final LocalDate SUMMER = LocalDate.of(2026, 7, 15);
    private static final LocalDate DECOR_DAY = LocalDate.of(2026, 12, 10);
    private static final LocalDate FESTIVAL_DAY = LocalDate.of(2026, 12, 25);
    private static final LocalDate AFTER = LocalDate.of(2027, 1, 10);
    private static final int DUSK = 12500;
    private static final int BEFORE_DAWN = 23000;

    private static final Predicate<ItemStack> WOOL = stack -> stack.is(ItemTags.WOOL);
    private static final Predicate<ItemStack> CLAY = stack -> stack.is(Items.CLAY_BALL);
    private static final Predicate<ItemStack> TAGS = stack -> stack.is(Items.NAME_TAG);

    private int ticks;
    private boolean failed;
    private boolean finished;
    private String mode = "";
    private long setupNanos;

    private MinecraftServer server;
    private ServerLevel level;
    private ServerPlayer owner;
    private IColony colony;
    private BlockPos hutPos;
    private BuildingDecorator hut;
    private ICitizenData citizen;
    private long startDayTime;
    /** Wool and clay put into the system by the probe so far. */
    private int woolIn;
    private int clayIn;

    private final List<Step> steps = new ArrayList<>();
    private int stepIndex;

    public CsDecorator() {
        NeoForge.EVENT_BUS.addListener(this::onTick);
    }

    // ================================================================ logging

    private void check(final String what, final boolean ok, final String detail) {
        LOGGER.info("[csdecorator] CHECK {}: {} {}", what, ok ? "OK" : "FAIL", detail);
        failed |= !ok;
    }

    private void checkEq(final String what, final Object actual, final Object expected) {
        check(what, Objects.equals(actual, expected), "got " + actual + (Objects.equals(actual, expected) ? "" : ", wanted " + expected));
    }

    private static void note(final String text, final Object... args) {
        LOGGER.info("[csdecorator] " + text, args);
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
            check("the probe ran without a throwable", false, t.toString());
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
            step.dayTimeAtStart = level.getDayTime();
            if (step.sprint) {
                server.tickRateManager().requestGameToSprint(step.maxTicks + 200);
            }
            step.start();
        }
        step.t++;
        if (step.t % 200 == 0 && citizen != null) {
            keepWell();
        }
        final boolean done = step.tick();
        if (done || step.t >= step.maxTicks) {
            final double seconds = Math.max(1e-3, (System.nanoTime() - step.nanos) / 1e9);
            note("step '{}' ran {} ticks in {} s ({} ticks/s), day time {} -> {}", step.name, step.t,
                    String.format("%.1f", seconds), Math.round(step.t / seconds), step.dayTimeAtStart, level.getDayTime());
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
        LOGGER.info("[csdecorator] RESULT csdecorator: {}", failed ? "FAIL" : "PASS");
        if (server != null) {
            server.halt(false);
        }
    }

    private static String readMode() throws Exception {
        final Path file = Path.of("probe-mode.txt");
        return Files.exists(file) ? Files.readString(file, StandardCharsets.US_ASCII).trim() : "";
    }

    /** One stretch of the run: up to {@code maxTicks} server ticks, sprinting; {@code tick()} returning true ends it early. */
    private abstract static class Step {
        final String name;
        final int maxTicks;
        final boolean sprint;
        boolean started;
        int t;
        long nanos;
        long dayTimeAtStart;

        Step(final String name, final int maxTicks) {
            this(name, maxTicks, true);
        }

        Step(final String name, final int maxTicks, final boolean sprint) {
            this.name = name;
            this.maxTicks = maxTicks;
            this.sprint = sprint;
        }

        void start() {
        }

        /** Called every tick; true when the step is done. */
        boolean tick() {
            return false;
        }

        /** {@code done}: tick() said so (false: the step ran out of ticks). */
        void end(final boolean done) {
        }
    }

    private void step(final Step step) {
        steps.add(step);
    }

    private void plan() {
        switch (mode) {
            case "" -> planDefault();
            case "free" -> planFree();
            case "restart-save" -> planRestartSave();
            case "restart-load" -> planRestartLoad();
            default -> {
                check("known mode", false, "'" + mode + "'");
                finish();
            }
        }
    }

    // ================================================================ setup

    private void setDate(final LocalDate date) {
        CalendarManager.setDateSource(DateSource.fixed(date));
        CalendarManager.refresh(server);
    }

    /** No mobs, no weather of its own, a clear sky: only the Decorator changes anything here. */
    private void quietWorld() {
        server.setDifficulty(Difficulty.PEACEFUL, true);
        server.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        server.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        level.setWeatherParameters(24000 * 20, 0, false, false);
    }

    /** A colony with a town hall, the Decorator's hut at level 1 and one citizen hired into it. */
    private void buildColony() {
        // not at 0,0,0: MineColonies warns about a building at the zero position
        final int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, 2, 2);
        final BlockPos center = new BlockPos(2, y, 2);
        for (int cx = -3; cx <= 3; cx++) {
            for (int cz = -3; cz <= 3; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }
        owner = FakePlayerFactory.getMinecraft(level);
        owner.setPos(center.getX() + 0.5, center.getY(), center.getZ() + 0.5);
        colony = IColonyManager.getInstance().createColony(level, center, owner, "Seasons Test", "Medieval Oak");
        note("colony {} created at {}", colony == null ? "none" : colony.getID(), center);
        final Block townHall = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.fromNamespaceAndPath("minecolonies", "blockhuttownhall")).orElse(null);
        if (townHall != null) {
            level.setBlock(center, townHall.defaultBlockState(), 3);
            if (level.getBlockEntity(center) instanceof AbstractTileEntityColonyBuilding te) {
                note("town hall registered: {}", colony.getServerBuildingManager().addNewBuilding(te, level) != null);
            }
        }
        // createColony made the owner a close subscriber already: that keeps the colony active

        hutPos = center.offset(HUT_OFFSET);
        level.setBlock(hutPos, DecoratorSetup.HUT.get().defaultBlockState(), 3);
        final BlockEntity be = level.getBlockEntity(hutPos);
        check("the hut block makes our block entity", be instanceof SeasonsTileEntity,
                be == null ? "none" : be.getClass().getSimpleName() + " " + BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()));
        final IBuilding building = be instanceof AbstractTileEntityColonyBuilding te ? colony.getServerBuildingManager().addNewBuilding(te, level) : null;
        check("the hut registers as a BuildingDecorator", building instanceof BuildingDecorator,
                building == null ? "none" : building.getClass().getSimpleName() + " " + building.getBuildingType().getRegistryName());
        hut = (BuildingDecorator) building;
        hut.setBuildingLevel(1);
        hut.markDirty();

        citizen = colony.getCitizenManager().createAndRegisterCivilianData();
        colony.getCitizenManager().spawnOrCreateCitizen(citizen, level, hutPos.offset(0, 1, 2));
        final WorkerBuildingModule work = hut.getModule(DecoratorModules.WORK);
        final boolean hired = work.assignCitizen(citizen);
        check("a citizen is hired as the Decorator", hired && citizen.getJob() instanceof JobDecorator,
                citizen.getName() + " -> " + (citizen.getJob() == null ? "no job" : citizen.getJob().getClass().getSimpleName()));
        startDayTime = level.getDayTime();
        note("ready: hut at {} level {}, citizen {} (entity {}), day time {}", hutPos, hut.getBuildingLevel(),
                citizen.getId(), citizen.getEntity().isPresent(), startDayTime);
    }

    /** Fed and well: hunger or a disease would take him off work for reasons that are not the Decorator's. */
    private void keepWell() {
        citizen.setSaturation(ICitizenData.MAX_SATURATION);
        if (citizen.getCitizenDiseaseHandler().isSick()) {
            citizen.getCitizenDiseaseHandler().cure();
            note("cured a disease");
        }
    }

    private JobDecorator job() {
        return citizen.getJob() instanceof JobDecorator j ? j : null;
    }

    // ================================================================ counting

    private IItemHandler hutInventory() {
        return hut.getItemHandlerCap();
    }

    /** Everything of one kind that exists here: in the hut, in his pack, and dropped on the ground nearby. */
    private int everywhere(final Predicate<ItemStack> what) {
        int total = BuildingDecorator.countIn(hutInventory(), what) + BuildingDecorator.countIn(citizen.getInventory(), what);
        for (final ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, new AABB(hutPos).inflate(32), e -> what.test(e.getItem()))) {
            total += drop.getItem().getCount();
        }
        return total;
    }

    private void supply(final int wool, final int clay) {
        final IItemHandler inv = hutInventory();
        final ItemStack w = ItemHandlerHelper.insertItemStacked(inv, new ItemStack(Items.WHITE_WOOL, wool), false);
        final ItemStack c = ItemHandlerHelper.insertItemStacked(inv, new ItemStack(Items.CLAY_BALL, clay), false);
        check("the probe's materials fit in the hut", w.isEmpty() && c.isEmpty(), wool + " wool, " + clay + " clay");
        woolIn += wool;
        clayIn += clay;
    }

    /** wool + 2A = wool put in, clay + A = clay put in, name tags = 2A, everything counted everywhere. */
    private final class Ledger {
        final String where;
        int checks;
        boolean broken;

        Ledger(final String where) {
            this.where = where;
        }

        void look() {
            checks++;
            final int a = hut.assembled();
            final int wool = everywhere(WOOL);
            final int clay = everywhere(CLAY);
            final int tags = everywhere(TAGS);
            final boolean ok = wool + 2 * a == woolIn && clay + a == clayIn && tags == 2 * a;
            if (!ok && !broken) {
                broken = true;
                check("nothing appears or goes (" + where + ")", false,
                        "A " + a + ", wool " + wool + " of " + woolIn + ", clay " + clay + " of " + clayIn + ", name tags " + tags);
            }
        }

        void report() {
            look();
            if (!broken) {
                check("nothing appears or goes (" + where + ")", true, checks + " counts, A " + hut.assembled()
                        + ", wool " + everywhere(WOOL) + ", clay " + everywhere(CLAY) + ", name tags " + everywhere(TAGS));
            }
        }
    }

    /** The citizen's own state (WORK, SLEEP, IDLE, ...), as MineColonies' citizen AI has it. */
    private String citizenState() {
        final Optional<AbstractEntityCitizen> entity = citizen.getEntity();
        if (entity.isPresent() && entity.get() instanceof EntityCitizen e && e.getCitizenAI() != null && e.getCitizenAI().getState() != null) {
            return e.getCitizenAI().getState().toString();
        }
        return "none";
    }

    // ================================================================ checks at setup

    private void setupChecks() {
        final var voices = ModSoundEvents.CITIZEN_SOUND_EVENTS.get(DecoratorSetup.JOB_ID.getPath());
        check("CITIZEN_SOUND_EVENTS has the decorator", voices != null && !voices.isEmpty(),
                "lines lent from '" + DecoratorSetup.voiceSource() + "'");

        check("hut block and item registered", BuiltInRegistries.BLOCK.getKey(DecoratorSetup.HUT.get()).toString().equals("colony_seasons:blockhutdecorator")
                && BuiltInRegistries.ITEM.getKey(DecoratorSetup.HUT_ITEM.get()).toString().equals("colony_seasons:blockhutdecorator"), "");
        checkEq("the block entity type", String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(DecoratorSetup.BUILDING_BE.get())),
                "colony_seasons:colonybuilding");
        check("the hut has an item handler capability", level.getCapability(Capabilities.ItemHandler.BLOCK, hutPos, null) != null, "at " + hutPos);
        checkEq("building max level", hut.getMaxBuildingLevel(), 5);
        checkEq("schematic name with no blueprint", hut.getSchematicName(), "workshop");
        checkEq("look of decorator/workshop3.blueprint", BuildingDecorator.lookOf("decorator/workshop3.blueprint"), "workshop");
        final WorkerBuildingModule work = hut.getModule(DecoratorModules.WORK);
        check("worker module: Creativity then Dexterity, one worker", work.getPrimarySkill() == Skill.Creativity
                && work.getSecondarySkill() == Skill.Dexterity && work.getModuleMax() == 1, work.getPrimarySkill() + ", " + work.getSecondarySkill());
        check("prepare all year is on by default", hut.prepareAllYear(), "");
        check("the stock module is server only", DecoratorModules.STOCK.hasServerModule() && !DecoratorModules.STOCK.hasView(), "");
        checkEq("stock cap at level 1", hut.stockCap(), 16);
        check("the Decorator wears his own model", job() != null && job().getModel().equals(DecoratorSetup.MODEL_ID), String.valueOf(DecoratorSetup.MODEL_ID));

        kitChecks();
        phaseChecks();
        keepChecks();
    }

    private void kitChecks() {
        final Kits.Kit test = Kits.byId(TEST_KIT);
        check("the probe's test kit is loaded", test != null && test.output() == Items.NAME_TAG && test.count() == 2
                && test.ingredients().size() == 2, String.valueOf(test));
        for (final ResourceLocation bad : BAD_KITS) {
            check("broken kit " + bad + " is skipped", Kits.skipped().containsKey(bad) && Kits.byId(bad) == null,
                    String.valueOf(Kits.skipped().get(bad)));
        }
        for (final String name : SHIPPED) {
            final ResourceLocation id = ResourceLocation.fromNamespaceAndPath("colony_seasons", name);
            final List<ResourceLocation> items = shippedItemIds(name);
            final boolean all = !items.isEmpty() && items.stream().allMatch(BuiltInRegistries.ITEM::containsKey);
            final boolean loaded = Kits.byId(id) != null;
            check("shipped kit " + name + " is loaded exactly when its items exist", loaded == all && (loaded || Kits.skipped().containsKey(id)),
                    (loaded ? "loaded" : "skipped") + ", items " + (all ? "all there" : "not all there"));
        }
        final Set<String> testKeys = new LinkedHashSet<>();
        if (test != null) {
            test.ingredients().forEach(i -> testKeys.add(i.key()));
        }
        boolean shared = false;
        for (final Kits.Kit kit : Kits.all()) {
            if (!kit.id().equals(TEST_KIT)) {
                for (final Kits.Ingredient i : kit.ingredients()) {
                    shared |= testKeys.contains(i.key());
                }
            }
        }
        check("no other kit takes the test kit's ingredients", !shared, testKeys.toString());
        note("kits loaded: {}; skipped: {}", Kits.all().stream().map(Kits.Kit::id).toList(), Kits.skipped().keySet());
    }

    /** The item ids a shipped kit file names (its output and item ingredients), read from the server's data. */
    private List<ResourceLocation> shippedItemIds(final String name) {
        final List<ResourceLocation> ids = new ArrayList<>();
        final Optional<Resource> file = server.getResourceManager().getResource(
                ResourceLocation.fromNamespaceAndPath("colony_seasons", Kits.FOLDER + "/" + name + ".json"));
        if (file.isEmpty()) {
            return ids;
        }
        try (Reader reader = file.get().openAsReader()) {
            final JsonObject kit = JsonParser.parseReader(reader).getAsJsonObject();
            ids.add(ResourceLocation.parse(kit.getAsJsonObject("output").get("item").getAsString()));
            for (final JsonElement ing : kit.getAsJsonArray("ingredients")) {
                if (ing.getAsJsonObject().has("item")) {
                    ids.add(ResourceLocation.parse(ing.getAsJsonObject().get("item").getAsString()));
                }
            }
        } catch (final Exception unreadable) {
            ids.clear();
        }
        return ids;
    }

    private void phaseChecks() {
        final SeasonState season = CalendarManager.current().season();
        final FestivalCalendar calendar = FestivalCalendar.fromConfig();
        checkEq("phase before the first poll", DecorPhase.of(null, false), DecorPhase.OFF_SEASON);
        checkEq("phase on 15 Jul", DecorPhase.of(snapshot(season, calendar, SUMMER), false), DecorPhase.OFF_SEASON);
        checkEq("phase on 10 Dec", DecorPhase.of(snapshot(season, calendar, DECOR_DAY), false), DecorPhase.DECORATING);
        checkEq("phase on 25 Dec", DecorPhase.of(snapshot(season, calendar, FESTIVAL_DAY), false), DecorPhase.HOSTING);
        checkEq("phase on 31 Dec (New Year)", DecorPhase.of(snapshot(season, calendar, LocalDate.of(2026, 12, 31)), false), DecorPhase.HOSTING);
        checkEq("phase on 10 Jan with decorations up", DecorPhase.of(snapshot(season, calendar, AFTER), true), DecorPhase.TAKING_DOWN);
        checkEq("phase on 10 Jan with decorations down", DecorPhase.of(snapshot(season, calendar, AFTER), false), DecorPhase.OFF_SEASON);
    }

    private static CalendarManager.Snapshot snapshot(final SeasonState season, final FestivalCalendar calendar, final LocalDate date) {
        return new CalendarManager.Snapshot(season, calendar.at(date), "", date);
    }

    /** What MineColonies is told the hut keeps: the output up to the cap, one batch of each ingredient. */
    private void keepChecks() {
        final Map<Predicate<ItemStack>, Tuple<Integer, Boolean>> keep = hut.getRequiredItemsAndAmount();
        checkKeep(keep, "name tags (output)", new ItemStack(Items.NAME_TAG), hut.stockCap());
        checkKeep(keep, "wool (ingredient)", new ItemStack(Items.RED_WOOL), 2);
        checkKeep(keep, "clay (ingredient)", new ItemStack(Items.CLAY_BALL), 1);
    }

    private void checkKeep(final Map<Predicate<ItemStack>, Tuple<Integer, Boolean>> keep, final String what, final ItemStack stack, final int atLeast) {
        int best = 0;
        for (final Map.Entry<Predicate<ItemStack>, Tuple<Integer, Boolean>> entry : keep.entrySet()) {
            if (entry.getKey().test(stack)) {
                best = Math.max(best, entry.getValue().getA());
            }
        }
        check("the hut keeps " + what, best >= atLeast, best + " kept, wanted at least " + atLeast);
    }

    // ================================================================ requests

    private List<IRequest<?>> openRequests() {
        return new ArrayList<>(hut.getOpenRequests(citizen.getId()));
    }

    private int openClay() {
        int n = 0;
        for (final IRequest<?> r : openRequests()) {
            if (r.getRequest() instanceof Stack s && s.getStack().is(Items.CLAY_BALL)) {
                n++;
            }
        }
        return n;
    }

    private int openWool() {
        int n = 0;
        for (final IRequest<?> r : openRequests()) {
            if (r.getRequest() instanceof RequestTag t && t.getTag().equals(ItemTags.WOOL)) {
                n++;
            }
        }
        return n;
    }

    // ================================================================ the days, for the default mode

    private void planDefault() {
        step(new Step("setup", 1, false) {
            @Override
            void start() {
                setDate(SUMMER);
                quietWorld();
                buildColony();
                setupChecks();
            }
        });
        step(new Step("D1 requests from an empty hut", 5000) {
            int decisions0;

            @Override
            void start() {
                decisions0 = job().decisions();
            }

            @Override
            boolean tick() {
                return t % 20 == 0 && job().decisions() - decisions0 >= 20 && openClay() > 0 && openWool() > 0;
            }

            @Override
            void end(final boolean done) {
                final int decisions = job().decisions() - decisions0;
                check("an open request for clay (Stack) and for the wool tag (RequestTag)", openClay() >= 1 && openWool() >= 1,
                        "clay " + openClay() + ", wool " + openWool() + ", all open " + openRequests().size());
                check("one request per ingredient after 20 decisions", decisions >= 20 && openClay() == 1 && openWool() == 1,
                        decisions + " decisions, clay " + openClay() + ", wool " + openWool());
                checkEq("nothing assembled without materials", hut.assembled(), 0);
            }
        });
        step(new Step("D1 materials become decorations, AI reset every 300 ticks", 9000) {
            final Ledger ledger = new Ledger("D1 with resets");
            int resets;
            int reachedAt = -1;

            @Override
            void start() {
                supply(24, 12);
            }

            @Override
            boolean tick() {
                if (t % 300 == 0) {
                    job().resetAI();
                    resets++;
                }
                if (t % 100 == 0) {
                    ledger.look();
                }
                if (reachedAt < 0 && hut.assembled() >= 8) {
                    reachedAt = t;
                }
                return reachedAt >= 0 && t >= reachedAt + 600;
            }

            @Override
            void end(final boolean done) {
                ledger.report();
                check("materials in the hut became decorations", done && hut.assembled() == 8, "A " + hut.assembled()
                        + " after " + (reachedAt < 0 ? "-" : reachedAt) + " ticks, " + resets + " AI resets on the way, "
                        + job().visits(DecoratorState.ASSEMBLE) + " assemblies");
                checkEq("stock stops at the cap (level 1)", hut.stock(Items.NAME_TAG), 16);
                checkEq("materials left over", everywhere(WOOL) + " wool, " + everywhere(CLAY) + " clay", "8 wool, 4 clay");
            }
        });
        toDusk("D1");
        night("N1");
        step(new Step("D2 rain", 3000) {
            final Ledger ledger = new Ledger("D2 in the rain");
            final Set<String> states = new LinkedHashSet<>();

            @Override
            void start() {
                level.setWeatherParameters(0, 3000, true, false);
            }

            @Override
            boolean tick() {
                if (t % 100 == 0) {
                    ledger.look();
                }
                if (t > 200) {
                    states.add(citizenState());
                }
                return false;
            }

            @Override
            void end(final boolean done) {
                level.setWeatherParameters(24000 * 20, 0, false, false);
                ledger.report();
                check("the rain took him off work (an AI reset when he goes back)", states.stream().anyMatch(s -> !s.startsWith("WORK")),
                        "citizen states in the rain " + states);
                checkEq("stock stays at the cap after the rain", hut.stock(Items.NAME_TAG), 16);
                check("surplus materials are still there", everywhere(WOOL) == 8 && everywhere(CLAY) == 4,
                        everywhere(WOOL) + " wool, " + everywhere(CLAY) + " clay");
            }
        });
        step(new Step("D2 level 2 in the decoration window", 6000) {
            final Ledger ledger = new Ledger("D2 at level 2");

            @Override
            void start() {
                hut.setBuildingLevel(2);
                hut.markDirty();
                setDate(DECOR_DAY);
            }

            @Override
            boolean tick() {
                if (t % 100 == 0) {
                    ledger.look();
                }
                return t > 400 && hut.assembled() >= 12 && job().visits(DecoratorState.DECORATING) > 0 && t % 20 == 0;
            }

            @Override
            void end(final boolean done) {
                ledger.report();
                checkEq("stock at the level-2 cap", hut.stock(Items.NAME_TAG), 24);
                checkEq("the materials are used up", everywhere(WOOL) + everywhere(CLAY), 0);
                checkEq("phase in the decoration window", job().phase(), DecorPhase.DECORATING);
                check("the Decorating stub was visited", job().visits(DecoratorState.DECORATING) > 0,
                        job().visits(DecoratorState.DECORATING) + " visits");
            }
        });
        toDusk("D2");
        night("N2");
        phaseStep("D3 festival day", FESTIVAL_DAY, false, DecoratorState.HOSTING, DecorPhase.HOSTING);
        phaseStep("D3 after the window, decorations up", AFTER, true, DecoratorState.TAKING_DOWN, DecorPhase.TAKING_DOWN);
        step(new Step("D3 off season, prepare all year off", 6000) {
            final Ledger ledger = new Ledger("D3 off season");
            int decisions0;
            int assembled0;

            @Override
            void start() {
                hut.setDecorationsUp(false);
                setDate(SUMMER);
                hut.setBuildingLevel(3);
                hut.markDirty();
                setPrepareAllYear(false);
                supply(4, 2);
                decisions0 = job().decisions();
                assembled0 = hut.assembled();
            }

            @Override
            boolean tick() {
                if (t % 100 == 0) {
                    ledger.look();
                }
                return t >= 2000 && job().decisions() - decisions0 >= 20;
            }

            @Override
            void end(final boolean done) {
                ledger.report();
                check("no assembly in the off season with prepare all year off", hut.assembled() == assembled0,
                        "A " + assembled0 + " -> " + hut.assembled() + " in " + t + " ticks, " + (job().decisions() - decisions0) + " decisions");
                checkEq("phase in the off season", job().phase(), DecorPhase.OFF_SEASON);
            }
        });
        step(new Step("D3 prepare all year back on", 4000) {
            final Ledger ledger = new Ledger("D3 prepare all year on");

            @Override
            void start() {
                setPrepareAllYear(true);
            }

            @Override
            boolean tick() {
                if (t % 100 == 0) {
                    ledger.look();
                }
                return hut.stock(Items.NAME_TAG) >= 28 && t % 20 == 0;
            }

            @Override
            void end(final boolean done) {
                ledger.report();
                checkEq("with the setting on again he prepares in the off season", hut.stock(Items.NAME_TAG), 28);
                checkEq("batches assembled in all", hut.assembled(), 14);
            }
        });
        toDusk("D3");
        night("N3");
        step(new Step("the end", 1, false) {
            @Override
            void start() {
                final long days = level.getDayTime() - startDayTime;
                check("three game days went by", days >= 3 * 24000L, days + " ticks of day time");
                check("the citizen is still the hut's Decorator", citizen.getJob() instanceof JobDecorator
                        && hut.getModule(DecoratorModules.WORK).getAssignedCitizen().contains(citizen), citizen.getName());
            }
        });
    }

    private void setPrepareAllYear(final boolean on) {
        final BoolSetting setting = hut.getSetting(me.lovkar.seasons.colony.BuildingDecorator.PREPARE_ALL_YEAR);
        if (setting.getValue() != on) {
            setting.trigger();
            hut.markDirty();
        }
        checkEq("prepare all year set to " + on, hut.prepareAllYear(), on);
    }

    /** Waits on a phase until its stub was visited. */
    private void phaseStep(final String name, final LocalDate date, final boolean up, final DecoratorState state, final DecorPhase phase) {
        step(new Step(name, 4000) {
            final Ledger ledger = new Ledger(name);
            int visits0;

            @Override
            void start() {
                hut.setDecorationsUp(up);
                setDate(date);
                visits0 = job().visits(state);
            }

            @Override
            boolean tick() {
                if (t % 100 == 0) {
                    ledger.look();
                }
                return job().visits(state) > visits0 && job().phase() == phase;
            }

            @Override
            void end(final boolean done) {
                ledger.report();
                check("the " + state + " stub was visited on " + date, done, (job().visits(state) - visits0) + " visits, phase " + job().phase());
            }
        });
    }

    /** The afternoon runs out by itself: no jump across work time. */
    private void toDusk(final String day) {
        step(new Step(day + " to dusk", 13000) {
            final Ledger ledger = new Ledger(day + " afternoon");

            @Override
            boolean tick() {
                if (t % 100 == 0) {
                    ledger.look();
                }
                return level.getDayTime() % 24000L >= DUSK;
            }

            @Override
            void end(final boolean done) {
                ledger.report();
            }
        });
    }

    /** Dusk until he is in bed, then the clock forward to just before dawn, then on to the next morning. */
    private void night(final String name) {
        step(new Step(name + " to bed", 4000) {
            final Ledger ledger = new Ledger(name + " evening");
            final Set<String> states = new LinkedHashSet<>();

            @Override
            boolean tick() {
                if (t % 100 == 0) {
                    ledger.look();
                }
                states.add(citizenState());
                return t >= 600 && states.contains("SLEEP");
            }

            @Override
            void end(final boolean done) {
                ledger.report();
                check(name + ": he went to bed (an AI reset in the morning)", states.contains("SLEEP"), "citizen states " + states);
                final long day = level.getDayTime() / 24000L;
                level.setDayTime(day * 24000L + BEFORE_DAWN);
            }
        });
        step(new Step(name + " to morning", 4000) {
            final Ledger ledger = new Ledger(name + " to morning");

            @Override
            boolean tick() {
                if (t % 100 == 0) {
                    ledger.look();
                }
                final long time = level.getDayTime() % 24000L;
                return time >= 500 && time < DUSK && "WORK".equals(citizenState()) || (time >= 1500 && time < DUSK);
            }

            @Override
            void end(final boolean done) {
                ledger.report();
                note("{} over: day time {}, citizen {}", name, level.getDayTime(), citizenState());
            }
        });
    }

    // ================================================================ mode free

    private void planFree() {
        step(new Step("setup", 1, false) {
            @Override
            void start() {
                setDate(SUMMER);
                quietWorld();
                buildColony();
                check("the seeded config says decorations are free", !SeasonsConfig.needsMaterials(), "needsMaterials " + SeasonsConfig.needsMaterials());
                supply(4, 2);
            }
        });
        step(new Step("free: 2400 ticks with materials in the hut", 6000) {
            int decisions0;

            @Override
            void start() {
                decisions0 = job().decisions();
            }

            @Override
            boolean tick() {
                return t >= 2400 && job().decisions() - decisions0 >= 20;
            }

            @Override
            void end(final boolean done) {
                checkEq("no open request", openRequests().size(), 0);
                checkEq("the materials are untouched in the hut", BuildingDecorator.countIn(hutInventory(), WOOL) + " wool, "
                        + BuildingDecorator.countIn(hutInventory(), CLAY) + " clay", "4 wool, 2 clay");
                checkEq("nothing assembled", hut.assembled(), 0);
                checkEq("no decoration made from nothing", everywhere(TAGS), 0);
                check("he was deciding all along", job().decisions() - decisions0 >= 20, (job().decisions() - decisions0) + " decisions");
            }
        });
    }

    // ================================================================ restart

    private void planRestartSave() {
        step(new Step("setup", 1, false) {
            @Override
            void start() {
                setDate(SUMMER);
                quietWorld();
                buildColony();
                supply(6, 3);
            }
        });
        step(new Step("save: three batches", 6000) {
            @Override
            boolean tick() {
                return hut.assembled() >= 3;
            }

            @Override
            void end(final boolean done) {
                check("three batches assembled before the save", done && hut.assembled() == 3, "A " + hut.assembled() + " in " + t + " ticks");
                hut.setDecorationsUp(true);
                setPrepareAllYear(false);
                note("saving: colony {}, hut at {}, A {}, name tags {}, decorations up {}, prepare all year {}", colony.getID(), hutPos,
                        hut.assembled(), hut.stock(Items.NAME_TAG), hut.decorationsUp(), hut.prepareAllYear());
                server.tickRateManager().stopSprinting();
                check("the world saved", server.saveEverything(false, true, true), "");
            }
        });
    }

    private void planRestartLoad() {
        step(new Step("load: find the colony again", 2400, false) {
            @Override
            boolean tick() {
                final IColony found = IColonyManager.getInstance().getColonyByWorld(1, level);
                if (found == null) {
                    return false;
                }
                for (final IBuilding b : found.getServerBuildingManager().getBuildings().values()) {
                    if (b instanceof BuildingDecorator d) {
                        colony = found;
                        hut = d;
                        return true;
                    }
                }
                return false;
            }

            @Override
            void end(final boolean done) {
                check("the colony and its Decorator's hut are back", done, colony == null ? "no colony" : "colony " + colony.getID());
                if (!done) {
                    finish();
                    return;
                }
                hutPos = hut.getPosition();
                setDate(SUMMER);
                quietWorld();
                owner = FakePlayerFactory.getMinecraft(level);
                colony.getPackageManager().addCloseSubscriber(owner);
                checkEq("the hut is where it was", hutPos, colony.getCenter().offset(HUT_OFFSET));
                checkEq("the hut is still level 1", hut.getBuildingLevel(), 1);
                final List<ICitizenData> hired = hut.getModule(DecoratorModules.WORK).getAssignedCitizen();
                citizen = hired.isEmpty() ? null : hired.get(0);
                check("the citizen is still hired as the Decorator", citizen != null && citizen.getJob() instanceof JobDecorator,
                        citizen == null ? "nobody" : citizen.getName());
                if (citizen == null) {
                    finish();
                    return;
                }
                woolIn = 6;
                clayIn = 3;
                checkEq("stock after the restart", hut.stock(Items.NAME_TAG), 6);
                checkEq("materials after the restart", everywhere(WOOL) + everywhere(CLAY), 0);
                checkEq("batches assembled (cs_assembled)", hut.assembled(), 3);
                checkEq("decorations up (cs_decorations_up)", hut.decorationsUp(), true);
                checkEq("prepare all year after the restart", hut.prepareAllYear(), false);
            }
        });
        step(new Step("load: one more batch", 3000) {
            final Ledger ledger = new Ledger("after the restart");

            @Override
            void start() {
                setPrepareAllYear(true);
                hut.setDecorationsUp(false);
                supply(2, 1);
            }

            @Override
            boolean tick() {
                if (t % 100 == 0) {
                    ledger.look();
                }
                return hut.assembled() >= 4 && t % 20 == 0;
            }

            @Override
            void end(final boolean done) {
                ledger.report();
                check("he goes on working after the restart", done && hut.assembled() == 4, "A " + hut.assembled() + " in " + t + " ticks");
                checkEq("stock after one more batch", hut.stock(Items.NAME_TAG), 8);
            }
        });
    }
}
