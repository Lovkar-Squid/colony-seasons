package me.lovkar.seasons.csstoreroom;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ldtteam.structurize.api.RotationMirror;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.HiringMode;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.modules.IAssignsCitizen;
import com.minecolonies.api.colony.workorders.WorkOrderType;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.tileentities.AbstractTileEntityColonyBuilding;
import com.minecolonies.core.colony.workorders.WorkOrderBuilding;
import me.lovkar.seasons.SeasonsConfig;
import me.lovkar.seasons.block.SeasonsBlocks;
import me.lovkar.seasons.block.StoragePieceBlock;
import me.lovkar.seasons.block.WallDecorationBlock;
import me.lovkar.seasons.calendar.CalendarManager;
import me.lovkar.seasons.calendar.DateSource;
import me.lovkar.seasons.colony.BuildingDecorator;
import me.lovkar.seasons.colony.DecoratorModules;
import me.lovkar.seasons.colony.DecoratorSetup;
import me.lovkar.seasons.colony.DecoratorTexts;
import me.lovkar.seasons.colony.JobDecorator;
import me.lovkar.seasons.decor.DecorEvents;
import me.lovkar.seasons.decor.DecorLedger;
import me.lovkar.seasons.decor.DecorPhase;
import me.lovkar.seasons.decor.DecorWork;
import me.lovkar.seasons.decor.StoreroomSpots;
import me.lovkar.seasons.decor.StoreroomWork;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.items.IItemHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Headless check of the storeroom in the Decorator's Workshop (chunk S10g2, docs/storage-look-design.md 3.2 to 3.4 and
 * the probe list of section 5; variant A). The room's two sets per level are golden in
 * tools/csstoreroom/res/csstoreroom/sets.json, written by tools/build_pack.py from decorator_hut.storeroom_set; every
 * room placed here is compared with them, turned with the building, block state for block state.
 *
 * <p>Modes (the rig writes the word to probe-mode.txt):</p>
 * <ul>
 *   <li>none: the ledger's files (a version 1 file and one with no version load the same, a version 2 file with a
 *       storeroom group goes through a save and a load unchanged); the Workshop pasted at level 1 turned (R90) with the
 *       command a player types, the room packed and dressed and packed again by the check, a second check the same day
 *       changing nothing (no block, no write, no budget), free pieces that drop nothing; upgrade work orders 1 to 5
 *       (one of them inside a forced window): the sweep takes the room off within its interval, nothing goes up while
 *       the order is open, and after it the new level's set is there; a Workshop pasted at level 5 mirrored (MIR_R270),
 *       both sets; a hand-built Workshop with no tags (an empty read changes nothing, a listed piece of it stays) and
 *       its removal (the building-removed hook takes its room); then the real Decorator: off season, a window's start and
 *       end (the room festive until the outside is down, then packed; the outside ledger empty, the room's group still
 *       listed), festival force and clear both ways, no calendar state yet (as before a session's first poll: the
 *       dressed room is left as it is), a Decorator hired in the middle of a window, decor list and decor remove and
 *       the room coming back, no Decorator after a window (a festive room goes, a packed one stays, also in an
 *       unstaffed second Workshop of a staffed colony; with only packed rooms listed the sweep says the decorations
 *       are down at the unstaffed huts), the switch off and on, and the Builder clearing the cells (the ledger forgets
 *       them, the next day's check puts them back);</li>
 *   <li>{@code restart-save} then {@code restart-load} (tools/restart.sh): a check stopped in the middle (part taken
 *       off, part placed) in a forced window with nobody hired; after the restart the ledger equals the world and the
 *       next check ends at the festival set.</li>
 * </ul>
 * The default run writes the rooms it placed at levels 1 and 5 (turned back to the blueprint's frame) to
 * csstoreroom-dump.json in the rig's server folder, for tools/render_s10g.py --from-dump. Prints
 * "[csstoreroom] CHECK ...: OK|FAIL" lines and "[csstoreroom] RESULT csstoreroom: PASS|FAIL", then halts.
 */
@Mod("colonyseasonsstoreroom")
public class CsStoreroom {
    private static final Logger LOGGER = LoggerFactory.getLogger("csstoreroom");
    private static final int SETUP_TICK = 60;
    private static final long WATCHDOG_SECONDS = 900;
    private static final String WINTER = StoreroomSpots.WINTER;
    /** From the town hall: the Workshop turned (upgraded 1 to 5), the one mirrored (pasted at 5), a hand-built one. */
    private static final BlockPos TURNED_OFFSET = new BlockPos(14, 0, 0);
    private static final BlockPos MIRRORED_OFFSET = new BlockPos(-14, 0, 14);
    private static final BlockPos HAND_OFFSET = new BlockPos(0, 0, -16);
    private static final RotationMirror TURNED = RotationMirror.R90;
    private static final RotationMirror MIRRORED = RotationMirror.MIR_R270;
    /** A cell inside the Workshop he can stand in, from the hut block in the building's frame (decorator_hut (0, 1, 2)). */
    private static final BlockPos INSIDE = new BlockPos(0, 0, 2);
    private static final LocalDate SUMMER = LocalDate.of(2026, 7, 15);
    private static final LocalDate DECOR_DAY = LocalDate.of(2026, 12, 5);
    private static final LocalDate AFTER = LocalDate.of(2027, 1, 10);
    private static final int MORNING = 1000;
    private static final String IDS_FILE = "csstoreroom-ids.properties";
    private static final String DUMP_FILE = "csstoreroom-dump.json";
    /** The ledger file tests' colony (no real colony has it). */
    private static final int FILE_COLONY = 77;

    private int ticks;
    private boolean failed;
    private boolean finished;
    private String mode = "";
    private long setupNanos;

    private MinecraftServer server;
    private ServerLevel level;
    private ServerPlayer owner;
    private IColony colony;
    private BlockPos center;
    private JsonObject sets;
    private final BuildingDecorator[] turned = new BuildingDecorator[1];
    private final BuildingDecorator[] mirrored = new BuildingDecorator[1];
    private ICitizenData decorator;
    private ICitizenData second;
    /** Status keys the Decorators showed since the last reset (each tick of the AI steps). */
    private final Set<String> statuses = new TreeSet<>();
    private final JsonObject dump = new JsonObject();

    private final List<Step> steps = new ArrayList<>();
    private int stepIndex;

    public CsStoreroom() {
        NeoForge.EVENT_BUS.addListener(this::onTick);
    }

    // ================================================================ logging and steps (as in cshire)

    private void check(final String what, final boolean ok, final String detail) {
        LOGGER.info("[csstoreroom] CHECK {}: {} {}", what, ok ? "OK" : "FAIL", detail);
        failed |= !ok;
    }

    private void checkEq(final String what, final Object actual, final Object expected) {
        check(what, Objects.equals(actual, expected), "got " + actual + (Objects.equals(actual, expected) ? "" : ", wanted " + expected));
    }

    private static void note(final String text, final Object... args) {
        LOGGER.info("[csstoreroom] " + text, args);
    }

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
                sets = readSets();
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
            LOGGER.info("[csstoreroom] trace: {}", Arrays.toString(Arrays.copyOf(t.getStackTrace(), Math.min(10, t.getStackTrace().length))));
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
            if (step.sprint) {
                server.tickRateManager().requestGameToSprint(step.maxTicks + 200);
            }
            step.start();
        }
        step.t++;
        final boolean done = finished || step.tick();
        if (done || step.t >= step.maxTicks) {
            if (step.sprint) {
                server.tickRateManager().stopSprinting();
            }
            note("step '{}' ran {} ticks", step.name, step.t);
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
        LOGGER.info("[csstoreroom] RESULT csstoreroom: {}", failed ? "FAIL" : "PASS");
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

    private void now(final String name, final Runnable body) {
        steps.add(new Step(name, 1, false) {
            @Override
            void start() {
                body.run();
            }
        });
    }

    private void step(final Step step) {
        steps.add(step);
    }

    private void plan() {
        switch (mode) {
            case "" -> planDefault();
            case "restart-save" -> planRestartSave();
            case "restart-load" -> planRestartLoad();
            default -> {
                check("known mode", false, "'" + mode + "'");
                finish();
            }
        }
    }

    // ================================================================ the world

    private void setDate(final LocalDate date) {
        CalendarManager.setDateSource(DateSource.fixed(date));
        CalendarManager.refresh(server);
    }

    private void quietWorld() {
        server.setDifficulty(Difficulty.PEACEFUL, true);
        server.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        server.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        level.setWeatherParameters(24000 * 20, 0, false, false);
    }

    /** Before dusk the clock goes on to the next morning: nobody goes to bed, the game days still count up. */
    private void keepDay() {
        final long time = level.getDayTime();
        if (time % 24000L > 11000L) {
            level.setDayTime((time / 24000L + 1) * 24000L + MORNING);
        }
    }

    private void keepWell() {
        for (final ICitizenData c : colony.getCitizenManager().getCitizens()) {
            c.setSaturation(ICitizenData.MAX_SATURATION);
            if (c.getCitizenDiseaseHandler().isSick()) {
                c.getCitizenDiseaseHandler().cure();
            }
        }
    }

    private void newGameDay() {
        level.setDayTime((day() + 1) * 24000L + MORNING);
    }

    private long day() {
        return DecorWork.day(level);
    }

    private DecorLedger ledger() {
        return DecorLedger.get(level);
    }

    private void buildColony() {
        final int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, 2, 2);
        center = new BlockPos(2, y, 2);
        for (int cx = -3; cx <= 3; cx++) {
            for (int cz = -3; cz <= 3; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }
        owner = FakePlayerFactory.getMinecraft(level);
        owner.setPos(center.getX() + 0.5, center.getY(), center.getZ() + 0.5);
        colony = IColonyManager.getInstance().createColony(level, center, owner, "Seasons Storeroom", "Medieval Oak");
        note("colony {} created at {}", colony == null ? "none" : colony.getID(), center);
        final Block townHall = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.fromNamespaceAndPath("minecolonies", "blockhuttownhall")).orElse(null);
        if (townHall != null) {
            level.setBlock(center, townHall.defaultBlockState(), 3);
            if (level.getBlockEntity(center) instanceof AbstractTileEntityColonyBuilding te) {
                final IBuilding hall = colony.getServerBuildingManager().addNewBuilding(te, level);
                note("town hall registered: {}", hall != null);
                if (hall != null) {
                    manual(hall);
                }
            }
        }
        owner.setGameMode(GameType.CREATIVE);
    }

    /** Nobody is hired by MineColonies on its own: the probe hires and fires. */
    private static void manual(final IBuilding building) {
        for (final IAssignsCitizen module : building.getModulesByType(IAssignsCitizen.class)) {
            module.setHiringMode(HiringMode.MANUAL);
        }
    }

    private Replies command(final String line) {
        final Replies out = new Replies();
        server.getCommands().performPrefixedCommand(owner.createCommandSourceStack().withSource(out).withPermission(4), line);
        return out;
    }

    private static final class Replies implements CommandSource {
        final List<Component> said = new ArrayList<>();

        List<String> keys() {
            final List<String> out = new ArrayList<>();
            for (final Component c : said) {
                out.add(c.getContents() instanceof TranslatableContents t ? t.getKey() : c.getString());
            }
            return out;
        }

        @Override
        public void sendSystemMessage(final Component component) {
            said.add(component);
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
    }

    /** decorator.needsMaterials or decorator.storeroomDressing set at once through the config spec (a server owner's edit). */
    private void setConfig(final String field, final String key, final boolean on) {
        try {
            final java.lang.reflect.Field f = SeasonsConfig.class.getDeclaredField(field);
            f.setAccessible(true);
            final ModConfigSpec.BooleanValue b = (ModConfigSpec.BooleanValue) f.get(null);
            b.set(on);
            b.clearCache();
        } catch (final ReflectiveOperationException | ClassCastException unreadable) {
            check(key + " can be set", false, unreadable.getClass().getSimpleName().replace("Exception", ""));
        }
    }

    private void dressing(final boolean on) {
        setConfig("STOREROOM_DRESSING", "decorator.storeroomDressing", on);
        checkEq("decorator.storeroomDressing is now " + on, SeasonsConfig.storeroomDressing(), on);
    }

    /**
     * The calendar as a session has it before its first poll: no snapshot. Only the snapshot goes; the clock, the
     * bridge and the saved data stay, so the next poll gives back the same state.
     */
    private void noSnapshot() {
        try {
            final java.lang.reflect.Field f = CalendarManager.class.getDeclaredField("current");
            f.setAccessible(true);
            f.set(null, null);
        } catch (final ReflectiveOperationException unreadable) {
            check("the calendar's snapshot can be taken away", false, unreadable.getClass().getSimpleName().replace("Exception", ""));
        }
    }

    // ================================================================ the Workshops

    /** The Workshop pasted at a level with a rotation as a player pastes it, then waited for until its hut data is in. */
    private void paste(final String name, final BlockPos offset, final int lv, final RotationMirror rm, final BuildingDecorator[] into) {
        steps.add(new Step(name, 600, false) {
            BlockPos at;

            @Override
            void start() {
                at = center.offset(offset);
                owner.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 3.5);
                final String line = "structurize paste " + at.getX() + " " + at.getY() + " " + at.getZ()
                        + " Colony.Seasons decorator.workshop" + lv + " " + rm.name() + " true";
                final Replies replies = command(line);
                note("'{}' said {}", line, replies.keys());
                check(name + ": the paste command is accepted", replies.keys().stream().anyMatch(s -> s.contains("paste.success")), String.valueOf(replies.keys()));
            }

            @Override
            boolean tick() {
                if (into[0] == null && colony.getServerBuildingManager().getBuilding(at) instanceof BuildingDecorator d) {
                    into[0] = d;
                    manual(d);
                }
                final AbstractTileEntityColonyBuilding te = into[0] == null ? null : into[0].getTileEntity();
                return t >= 100 && te != null && !te.getSchematicName().isEmpty();
            }

            @Override
            void end(final boolean done) {
                final BuildingDecorator hut = into[0];
                check(name + ": the paste finished with a Workshop", done && hut != null, hut == null ? "none at " + at.toShortString() : "");
                if (hut == null) {
                    finish();
                    return;
                }
                if (hut.getBuildingLevel() != lv) {
                    hut.setBuildingLevel(lv);
                    hut.markDirty();
                }
                checkEq(name + ": the building's rotation and mirror", hut.getRotationMirror(), rm);
                check(name + ": nobody works there", hut.getAllAssignedCitizen().isEmpty(), "");
            }
        });
    }

    // ================================================================ the golden sets

    private static JsonObject readSets() {
        try (InputStream in = CsStoreroom.class.getResourceAsStream("/csstoreroom/sets.json")) {
            if (in == null) {
                throw new IllegalStateException("no csstoreroom/sets.json in the probe jar");
            }
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (final java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BlockState parse(final String state) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), state, false).blockState();
        } catch (final com.mojang.brigadier.exceptions.CommandSyntaxException bad) {
            throw new IllegalStateException("unreadable state in sets.json: " + state, bad);
        }
    }

    /** A facing turned with the building, as the game turns the blueprint's own blocks. */
    private static BlockState turn(final BlockState state, final RotationMirror rm) {
        if (!state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return state;
        }
        final Direction f = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        final BlockPos n = rm.applyToPos(new BlockPos(f.getStepX(), f.getStepY(), f.getStepZ()));
        return state.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.fromDelta(n.getX(), n.getY(), n.getZ()));
    }

    private final Map<String, Map<BlockPos, BlockState>> goldenCache = new java.util.HashMap<>();

    /** The golden set of level {@code lv} for this Workshop, in world positions and facings. */
    private Map<BlockPos, BlockState> golden(final IBuilding hut, final int lv, final boolean festival) {
        final RotationMirror rm = hut.getRotationMirror();
        return goldenCache.computeIfAbsent(hut.getPosition().toShortString() + " " + rm + " " + lv + " " + festival,
                k -> goldenOf(hut, rm, lv, festival));
    }

    private Map<BlockPos, BlockState> goldenOf(final IBuilding hut, final RotationMirror rm, final int lv, final boolean festival) {
        final Map<BlockPos, BlockState> out = new TreeMap<>();
        for (final JsonElement e : sets.getAsJsonObject("levels").getAsJsonObject(String.valueOf(lv)).getAsJsonArray(festival ? "festival" : "packed")) {
            final JsonArray a = e.getAsJsonArray();
            final BlockPos offset = new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
            out.put(hut.getPosition().offset(rm.applyToPos(offset)), turn(parse(a.get(3).getAsString()), rm));
        }
        return out;
    }

    /** Every spot cell of level {@code lv} (both sets). */
    private Set<BlockPos> cells(final IBuilding hut, final int lv) {
        final Set<BlockPos> out = new TreeSet<>(golden(hut, lv, false).keySet());
        out.addAll(golden(hut, lv, true).keySet());
        return out;
    }

    private static boolean ours(final BlockState state) {
        return state.getBlock() instanceof StoragePieceBlock || state.getBlock() instanceof WallDecorationBlock;
    }

    /** What the ledger lists for the Workshop's room. */
    private Map<BlockPos, BlockState> listed(final IBuilding hut) {
        final Map<BlockPos, BlockState> out = new TreeMap<>();
        final DecorLedger.GroupView g = ledger().group(colony.getID(), DecorLedger.Owner.storeroom(hut.getPosition()));
        if (g != null) {
            g.entries().forEach((p, e) -> out.put(p, e.state()));
        }
        return out;
    }

    /** Pieces of ours standing in the spot cells of level {@code lv} (and of every level, with lv 0). */
    private Map<BlockPos, BlockState> inWorld(final IBuilding hut, final int lv) {
        final Set<BlockPos> where = new TreeSet<>();
        if (lv == 0) {
            for (int l = 1; l <= 5; l++) {
                where.addAll(cells(hut, l));
            }
        } else {
            where.addAll(cells(hut, lv));
        }
        final Map<BlockPos, BlockState> out = new TreeMap<>();
        for (final BlockPos p : where) {
            final BlockState s = level.getBlockState(p);
            if (ours(s)) {
                out.put(p, s);
            }
        }
        return out;
    }

    private boolean isRoom(final IBuilding hut, final int lv, final boolean festival) {
        final Map<BlockPos, BlockState> want = golden(hut, lv, festival);
        return listed(hut).equals(want) && inWorld(hut, 0).equals(want);
    }

    private static String diff(final Map<BlockPos, BlockState> got, final Map<BlockPos, BlockState> want) {
        if (got.equals(want)) {
            return got.size() + " pieces";
        }
        for (final Map.Entry<BlockPos, BlockState> e : want.entrySet()) {
            if (!e.getValue().equals(got.get(e.getKey()))) {
                return got.size() + " of " + want.size() + "; at " + e.getKey().toShortString() + " " + got.get(e.getKey()) + ", wanted " + e.getValue();
            }
        }
        for (final Map.Entry<BlockPos, BlockState> e : got.entrySet()) {
            if (!want.containsKey(e.getKey())) {
                return got.size() + " of " + want.size() + "; extra at " + e.getKey().toShortString() + " " + e.getValue();
            }
        }
        return "?";
    }

    /** The room equals the golden set: listed exactly, in the world exactly (no piece of ours in another spot cell of any level), at tags MineColonies hands out. */
    private void room(final String what, final IBuilding hut, final int lv, final boolean festival) {
        final Map<BlockPos, BlockState> want = golden(hut, lv, festival);
        final String set = (festival ? "the festival set" : "the packed set") + " of level " + lv;
        check(what + ": the ledger lists exactly " + set, listed(hut).equals(want), diff(listed(hut), want));
        check(what + ": the world holds exactly " + set + " in the spot cells", inWorld(hut, 0).equals(want), diff(inWorld(hut, 0), want));
        final Set<BlockPos> tags = new HashSet<>();
        for (final StoreroomSpots.Spot s : StoreroomSpots.read(hut, WINTER)) {
            tags.add(s.pos());
        }
        check(what + ": every piece stands at a spot tag MineColonies hands out", tags.containsAll(want.keySet()), tags.size() + " tags");
        boolean unpaid = true;
        final DecorLedger.GroupView g = ledger().group(colony.getID(), DecorLedger.Owner.storeroom(hut.getPosition()));
        if (g != null) {
            for (final DecorLedger.Entry e : g.entries().values()) {
                unpaid &= !e.paid();
            }
        }
        check(what + ": every piece is listed as free", unpaid, "");
    }

    /** The room as placed, turned back to the blueprint's frame, for the renders (tools/render_s10g.py --from-dump). */
    private void dumpRoom(final IBuilding hut, final int lv, final boolean festival) {
        final RotationMirror rm = hut.getRotationMirror();
        RotationMirror back = null;
        for (final RotationMirror candidate : RotationMirror.values()) {
            if (candidate.applyToPos(rm.applyToPos(new BlockPos(1, 0, 0))).equals(new BlockPos(1, 0, 0))
                    && candidate.applyToPos(rm.applyToPos(new BlockPos(0, 0, 1))).equals(new BlockPos(0, 0, 1))) {
                back = candidate;
            }
        }
        if (back == null) {
            check("the building's turn can be undone for the dump", false, rm.name());
            return;
        }
        final JsonArray out = new JsonArray();
        for (final Map.Entry<BlockPos, BlockState> e : listed(hut).entrySet()) {
            final BlockPos off = back.applyToPos(e.getKey().subtract(hut.getPosition()));
            final JsonArray one = new JsonArray();
            one.add(off.getX());
            one.add(off.getY());
            one.add(off.getZ());
            one.add(BlockStateParser.serialize(turn(e.getValue(), back)));
            out.add(one);
        }
        if (!dump.has(String.valueOf(lv))) {
            dump.add(String.valueOf(lv), new JsonObject());
        }
        dump.getAsJsonObject(String.valueOf(lv)).add(festival ? "festival" : "packed", out);
    }

    private void writeDump() {
        final JsonObject root = new JsonObject();
        root.add("levels", dump);
        try {
            Files.writeString(Path.of(DUMP_FILE), root.toString(), StandardCharsets.UTF_8);
            note("the placed rooms are in {} in the rig's server folder", DUMP_FILE);
        } catch (final java.io.IOException unwritten) {
            check("the dump for the renders is written", false, unwritten.getClass().getSimpleName());
        }
    }

    // ================================================================ direct checks (what the Decorator runs at his hut)

    private StoreroomWork.Result direct(final BuildingDecorator hut, final String target) {
        return direct(hut, target, Integer.MAX_VALUE);
    }

    private StoreroomWork.Result direct(final BuildingDecorator hut, final String target, final int maxChanges) {
        final StoreroomWork.Result r = StoreroomWork.check(level, colony, hut, target, ledger(), day(), maxChanges);
        note("check of {} for '{}': {}", hut.getPosition().toShortString(), target, r);
        return r;
    }

    private boolean due(final BuildingDecorator hut, final String target) {
        return StoreroomWork.due(ledger(), colony.getID(), hut.getPosition(), target, hut.getBuildingLevel(), day());
    }

    private int itemsNear(final IBuilding hut) {
        int n = 0;
        for (final ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(hut.getPosition()).inflate(12))) {
            n += e.getItem().getCount();
        }
        return n;
    }

    private static int items(final IItemHandler handler) {
        int n = 0;
        if (handler != null) {
            for (int i = 0; i < handler.getSlots(); i++) {
                n += handler.getStackInSlot(i).getCount();
            }
        }
        return n;
    }

    // ================================================================ the default run

    private void planDefault() {
        now("setup", () -> {
            setDate(SUMMER);
            quietWorld();
            buildColony();
            setsFileSane();
            ledgerFiles();
        });
        paste("the turned Workshop at level 1", TURNED_OFFSET, 1, TURNED, turned);
        now("level 1: packed, dressed, packed (direct checks)", this::levelOne);
        for (int lv = 2; lv <= 5; lv++) {
            upgrade(lv, lv == 4);
        }
        now("level 5: both sets", () -> {
            final BuildingDecorator hut = turned[0];
            room("level 5 R90 after the upgrade", hut, 5, false);
            dumpRoom(hut, 5, false);
            direct(hut, WINTER);
            room("level 5 R90 dressed", hut, 5, true);
            dumpRoom(hut, 5, true);
            direct(hut, "");
            room("level 5 R90 packed again", hut, 5, false);
        });
        paste("the mirrored Workshop at level 5", MIRRORED_OFFSET, 5, MIRRORED, mirrored);
        now("level 5 MIR_R270: both sets", () -> {
            final BuildingDecorator hut = mirrored[0];
            check("MIR_R270: nothing is up before a check", listed(hut).isEmpty() && inWorld(hut, 0).isEmpty(), "");
            direct(hut, "");
            room("level 5 MIR_R270 packed", hut, 5, false);
            direct(hut, WINTER);
            room("level 5 MIR_R270 dressed", hut, 5, true);
            final StoreroomWork.Result r = direct(hut, "");
            room("level 5 MIR_R270 packed again", hut, 5, false);
            checkEq("MIR_R270: the way back takes off 20 and puts up 6", r.removed() + "/" + r.placed(), "20/6");
        });
        now("a Workshop with no tags", this::handBuilt);
        planAi();
        now("the end", this::writeDump);
    }

    /** The spot file names the code's set, and the design's numbers (storage-look section 4). */
    private void setsFileSane() {
        final List<String> named = new ArrayList<>();
        sets.getAsJsonArray("sets").forEach(e -> named.add(e.getAsString()));
        checkEq("sets.json names the code's sets", named, StoreroomSpots.SETS);
        final Map<Integer, String> want = Map.of(1, "5/13", 2, "5/13", 3, "6/20", 4, "6/20", 5, "6/20");
        for (int lv = 1; lv <= 5; lv++) {
            final JsonObject one = sets.getAsJsonObject("levels").getAsJsonObject(String.valueOf(lv));
            checkEq("sets.json level " + lv + ": packed / festival pieces", one.getAsJsonArray("packed").size() + "/"
                    + one.getAsJsonArray("festival").size(), want.get(lv));
        }
    }

    private void levelOne() {
        final BuildingDecorator hut = turned[0];
        final int id = colony.getID();
        final DecorLedger ledger = ledger();
        final IItemHandler store = hut.getItemHandlerCap();
        final int stock0 = items(store);
        final int near0 = itemsNear(hut);
        check("level 1: nothing is listed for the room before a check", ledger.group(id, DecorLedger.Owner.storeroom(hut.getPosition())) == null, "");
        check("level 1: a room with no group is due", due(hut, ""), "");
        StoreroomWork.Result r = direct(hut, "");
        check("level 1: the first check packs the room", r.complete() && r.placed() == 5 && r.removed() == 0, String.valueOf(r));
        checkEq("level 1: the packing check says so", r.status(), DecoratorTexts.ROOM_PACKING);
        room("level 1 R90 packed", hut, 1, false);
        dumpRoom(hut, 1, false);
        check("level 1: a room checked today for its target is not due", !due(hut, ""), "");
        check("level 1: the festival target is due at once", due(hut, WINTER), "");
        final DecorLedger.GroupView g = ledger.group(id, DecorLedger.Owner.storeroom(hut.getPosition()));
        check("level 1: the group notes what the check left (packed, level 1, today)", g != null && g.festival().isEmpty() && g.level() == 1
                && g.tendedDay() == day() && g.decoratedDay() < 0, g == null ? "none" : g.festival() + "/" + g.level() + "/" + g.tendedDay());
        check("level 1: the outside forms leave the room out", !ledger.hasOutdoorEntries(id) && ledger.countOutdoor(id) == 0
                && ledger.hasEntries(id) && ledger.count(id) == 5, ledger.countOutdoor(id) + " outside, " + ledger.count(id) + " in all");
        check("level 1: the room is no group to take down or tend", DecorWork.nextToTakeDown(level, colony, hut.getPosition(), ledger) == null
                && DecorWork.nextToTend(level, colony, hut.getPosition(), ledger, day() + 1) == null
                && DecorWork.targetOf(level, colony, ledger, DecorLedger.Owner.storeroom(hut.getPosition())) == null, "");
        checkEq("level 1: the room costs nothing of the day's budget", ledger.budgetUsed(id, day()), 0);

        // a second check the same day changes nothing: no block, no write, no budget, no status line
        final int writes = ledger.writes();
        final Map<BlockPos, BlockState> world = inWorld(hut, 0);
        r = direct(hut, "");
        check("level 1: a second check the same day changes no block", r.changes() == 0 && r.forgotten() == 0 && r.complete()
                && inWorld(hut, 0).equals(world), String.valueOf(r));
        checkEq("level 1: and writes nothing", ledger.writes(), writes);
        checkEq("level 1: and has no status line", r.status(), null);
        checkEq("level 1: and costs no budget", ledger.budgetUsed(id, day()), 0);

        r = direct(hut, WINTER);
        check("level 1: the festival check swaps every piece (5 off, 13 up)", r.complete() && r.removed() == 5 && r.placed() == 13, String.valueOf(r));
        checkEq("level 1: the unpacking check says so", r.status(), DecoratorTexts.ROOM_UNPACKING);
        room("level 1 R90 dressed", hut, 1, true);
        dumpRoom(hut, 1, true);
        check("level 1: the dressed room holds a festival set", StoreroomWork.holdsFestival(ledger.group(id, DecorLedger.Owner.storeroom(hut.getPosition()))), "");
        checkEq("level 1: the dressed group's set", ledger.group(id, DecorLedger.Owner.storeroom(hut.getPosition())).festival(), WINTER);

        // a free piece broken by a player drops nothing; the ledger forgets it and the next check puts it back
        BlockPos wreath = null;
        for (final Map.Entry<BlockPos, BlockState> e : listed(hut).entrySet()) {
            if (e.getValue().getBlock() == SeasonsBlocks.WREATH.get()) {
                wreath = e.getKey();
            }
        }
        check("level 1: a wreath hangs in the dressed room", wreath != null, "");
        if (wreath != null) {
            owner.setGameMode(GameType.SURVIVAL);
            final boolean broke = level.destroyBlock(wreath, true, owner);
            owner.setGameMode(GameType.CREATIVE);
            check("level 1: a player breaks the room's wreath", broke && level.getBlockState(wreath).isAir(), String.valueOf(level.getBlockState(wreath)));
            checkEq("level 1: the free wreath dropped nothing", itemsNear(hut), near0);
            check("level 1: the ledger forgot it", ledger.at(wreath) == null, "");
            r = direct(hut, WINTER);
            check("level 1: the next check puts it back", r.placed() == 1 && r.removed() == 0, String.valueOf(r));
            room("level 1 R90 dressed again", hut, 1, true);
        }
        // a box broken by a player drops nothing either
        final BlockPos box = listed(hut).keySet().stream().filter(p -> level.getBlockState(p).getBlock() == SeasonsBlocks.STORAGE_BOX.get())
                .findFirst().orElse(null);
        if (box != null) {
            owner.setGameMode(GameType.SURVIVAL);
            level.destroyBlock(box, true, owner);
            owner.setGameMode(GameType.CREATIVE);
            checkEq("level 1: a free box broken by a player dropped nothing", itemsNear(hut), near0);
        }
        r = direct(hut, "");
        check("level 1: packed again (13 off, 5 up)", r.complete() && r.placed() == 5, String.valueOf(r));
        room("level 1 R90 packed again", hut, 1, false);
        checkEq("level 1: no item came into the hut or went out of it", items(store), stock0);
        checkEq("level 1: no item lies about", itemsNear(hut), near0);
        checkEq("level 1: still nothing of the day's budget", ledger.budgetUsed(id, day()), 0);
    }

    /**
     * Upgrade the turned Workshop to {@code lv} the way the Builder does: the order opens (the sweep takes the room off
     * within its interval; a check places nothing while it is open), the order completes (MineColonies writes the new
     * level's tags into the hut block), and the next check puts the new level's set up. {@code inWindow}: in a forced
     * window, so the room is dressed before and after.
     */
    private void upgrade(final int lv, final boolean inWindow) {
        final WorkOrderBuilding[] order = new WorkOrderBuilding[1];
        final Blueprint[] loaded = new Blueprint[1];
        final String target = inWindow ? WINTER : "";
        steps.add(new Step("the upgrade work order " + (lv - 1) + " to " + lv + (inWindow ? " in a forced window" : ""), 400, false) {
            Map<BlockPos, BlockState> before;
            int goneAt = -1;

            @Override
            void start() {
                final BuildingDecorator hut = turned[0];
                if (inWindow) {
                    final Replies replies = command("colonyseasons festival force " + SeasonsConfig.WINTER_FESTIVAL);
                    check("level " + lv + ": the festival is forced", DecorPhase.windowOpen(CalendarManager.current()), String.valueOf(replies.keys()));
                    direct(hut, target);
                    room("level " + (lv - 1) + " in the window, before the upgrade", hut, lv - 1, true);
                }
                before = listed(hut);
                check("level " + lv + ": the room is up before the order", !before.isEmpty(), before.size() + " pieces");
                final WorkOrderBuilding wo = WorkOrderBuilding.create(WorkOrderType.UPGRADE, hut);
                order[0] = wo;
                colony.getWorkManager().addWorkOrder(wo, false);
                check("level " + lv + ": the Workshop is busy with the order", DecorWork.busy(colony, hut), "");
                wo.loadBlueprint(level, bp -> loaded[0] = bp);
            }

            @Override
            boolean tick() {
                final BuildingDecorator hut = turned[0];
                if (goneAt < 0 && ledger().group(colony.getID(), DecorLedger.Owner.storeroom(hut.getPosition())) == null) {
                    goneAt = t;
                }
                return goneAt >= 0 && loaded[0] != null;
            }

            @Override
            void end(final boolean done) {
                final BuildingDecorator hut = turned[0];
                check("level " + lv + ": the sweep takes the room off within its interval once the order opens",
                        goneAt >= 0 && goneAt <= DecorEvents.SWEEP_INTERVAL + 1, "after " + goneAt + " ticks");
                check("level " + lv + ": nothing of the room is left in the world", inWorld(hut, 0).isEmpty(), diff(inWorld(hut, 0), Map.of()));
                final StoreroomWork.Result busy = direct(hut, target);
                check("level " + lv + ": a check while the order is open places nothing and is not complete", busy.placed() == 0 && !busy.complete()
                        && listed(hut).isEmpty() && inWorld(hut, 0).isEmpty(), String.valueOf(busy));
                if (loaded[0] == null) {
                    check("level " + lv + ": the order loads its blueprint", false, "");
                    finish();
                    return;
                }
                final int set = build(hut, loaded[0]);
                note("level {}: the Builder's work done by the probe, {} cells set", lv, set);
                try {
                    order[0].onCompleted(colony, null);
                } catch (final RuntimeException e) {
                    check("level " + lv + ": the order completes", false, e.toString());
                }
                hut.setBuildingLevel(lv);
                hut.onUpgradeComplete(loaded[0], lv);
                colony.getWorkManager().removeWorkOrder(order[0]);
                check("level " + lv + ": the order is done", !DecorWork.busy(colony, hut) && hut.getBuildingLevel() == lv, "");
                check("level " + lv + ": the room is due for the new level", due(hut, target), "");
                final StoreroomWork.Result r = direct(hut, target);
                check("level " + lv + ": the check after the upgrade is complete", r.complete() && r.removed() == 0, String.valueOf(r));
                room("level " + lv + " R90 after the upgrade" + (inWindow ? ", in the window" : ""), hut, lv, inWindow);
                if (inWindow) {
                    final Replies replies = command("colonyseasons festival clear");
                    check("level " + lv + ": the forced festival is cleared", !DecorPhase.windowOpen(CalendarManager.current()), String.valueOf(replies.keys()));
                    direct(hut, "");
                    room("level " + lv + " R90 packed after the window", hut, lv, false);
                }
            }
        });
    }

    /**
     * The new level's blueprint built into the world as the Builder builds it (every cell of the order's blueprint,
     * turned with the building; the hut block and Structurize's substitution blocks left as they are). Returns the cells
     * changed. The order's blueprint is the unturned one (AbstractWorkOrder.setBlueprint does not turn it).
     */
    private int build(final IBuilding hut, final Blueprint blueprint) {
        // the order's blueprint may come turned already (Structurize turns a loaded blueprint in place): turn only the rest
        final RotationMirror rm = blueprint.getRotationMirror() == hut.getRotationMirror() ? RotationMirror.NONE : hut.getRotationMirror();
        note("the order's blueprint: turned {}, the building {}, anchor {}, size {} {} {}", blueprint.getRotationMirror(), hut.getRotationMirror(),
                blueprint.getPrimaryBlockOffset().toShortString(), blueprint.getSizeX(), blueprint.getSizeY(), blueprint.getSizeZ());
        if (blueprint.getRotationMirror() != RotationMirror.NONE && rm != RotationMirror.NONE) {
            check("the order's blueprint is unturned or turned as the building", false, String.valueOf(blueprint.getRotationMirror()));
            return 0;
        }
        final BlockPos anchor = blueprint.getPrimaryBlockOffset();
        int changed = 0;
        int shown = 0;
        final List<com.ldtteam.structurize.util.BlockInfo> infos = new ArrayList<>(blueprint.getBlockInfoAsList());
        infos.sort(java.util.Comparator.comparingInt(i -> i.getPos().getY()));
        for (final com.ldtteam.structurize.util.BlockInfo info : infos) {
            final BlockPos offset = info.getPos().subtract(anchor);
            if (offset.equals(BlockPos.ZERO)) {
                continue;
            }
            final BlockState state = info.getState();
            if ("structurize".equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace())) {
                continue;
            }
            final BlockPos at = hut.getPosition().offset(rm.applyToPos(offset));
            final BlockState turnedState = rm.applyToBlockState(state);
            if (level.getBlockState(at) != turnedState) {
                if (shown++ < 4) {
                    note("build: {} was {}, becomes {}", at.toShortString(), level.getBlockState(at), turnedState);
                }
                level.setBlock(at, turnedState, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                changed++;
            }
        }
        return changed;
    }

    /** A Workshop built by hand: no tags, so nothing to dress; then removed, and its room group goes with it. */
    private void handBuilt() {
        final int id = colony.getID();
        final BlockPos at = center.offset(HAND_OFFSET);
        level.setBlock(at, DecoratorSetup.HUT.get().defaultBlockState(), 3);
        final BlockEntity be = level.getBlockEntity(at);
        final IBuilding b = be instanceof AbstractTileEntityColonyBuilding te ? colony.getServerBuildingManager().addNewBuilding(te, level) : null;
        final BuildingDecorator hand = b instanceof BuildingDecorator d ? d : null;
        check("a hand-built Workshop registers", hand != null, String.valueOf(b));
        if (hand == null) {
            return;
        }
        manual(hand);
        hand.setCorners(at.offset(-2, -1, -2), at.offset(2, 3, 2));
        hand.setBuildingLevel(1);
        final int writes = ledger().writes();
        StoreroomWork.Result r = direct(hand, "");
        check("no tags: an empty read changes nothing and is not complete", r.changes() == 0 && !r.complete() && r.status() == null
                && ledger().group(id, DecorLedger.Owner.storeroom(at)) == null, String.valueOf(r));
        checkEq("no tags: and writes nothing", ledger().writes(), writes);
        // a piece listed for its room (as if its tags had been there): an empty read never takes it for a packed room
        final BlockPos barrel = at.offset(2, 0, 0);
        final BlockPos piece = barrel.above();
        final BlockState box = SeasonsBlocks.STORAGE_BOX.get().defaultBlockState();
        level.setBlock(barrel, Blocks.BARREL.defaultBlockState(), 3);
        ledger().record(id, DecorLedger.Owner.storeroom(at), "", 1, piece, box, false);
        level.setBlock(piece, box, 3);
        r = direct(hand, WINTER);
        check("no tags: a listed piece stays listed and in the world", r.changes() == 0 && ledger().at(piece) != null
                && level.getBlockState(piece) == box, String.valueOf(r));
        // the building removed: its storeroom group goes at the end of the tick with its outside group
        hand.destroy();
        DecorEvents.drain(server);
        check("removed: the hand-built Workshop's room goes with it", ledger().group(id, DecorLedger.Owner.storeroom(at)) == null
                && level.getBlockState(piece).isAir(), String.valueOf(level.getBlockState(piece)));
        level.setBlock(barrel, Blocks.AIR.defaultBlockState(), 3);
    }

    // ================================================================ the ledger's files

    private static CompoundTag group(final String kind, final BlockPos pos, final String festival, final int lv, final long decorated,
                                     final long tended, final List<BlockState> palette, final long[] positions, final int[] states,
                                     final byte[] paid) {
        final CompoundTag g = new CompoundTag();
        g.putString("kind", kind);
        g.putLong("pos", pos.asLong());
        g.putString("festival", festival);
        g.putInt("level", lv);
        g.putString("piece", "");
        g.putLong("decoratedDay", decorated);
        g.putLong("tendedDay", tended);
        g.putLong("treeDay", -1);
        g.putBoolean("removing", false);
        final ListTag p = new ListTag();
        for (final BlockState s : palette) {
            p.add(NbtUtils.writeBlockState(s));
        }
        g.put("palette", p);
        g.putLongArray("positions", positions);
        g.putIntArray("states", states);
        g.putByteArray("paid", paid);
        return g;
    }

    /** A ledger file as a build of version {@code version} writes it ({@code -1}: no version at all), with or without a storeroom group. */
    private static CompoundTag file(final int version, final boolean room) {
        final BlockState wreath = SeasonsBlocks.WREATH.get().defaultBlockState();
        final BlockState garland = SeasonsBlocks.GARLAND.get().defaultBlockState();
        final BlockState box = SeasonsBlocks.STORAGE_BOX.get().defaultBlockState();
        final BlockPos hut = new BlockPos(4000, 70, 4000);
        final BlockPos marker = new BlockPos(4020, 70, 4000);
        final CompoundTag one = new CompoundTag();
        one.putInt("id", FILE_COLONY);
        one.putLong("budgetDay", 12);
        one.putInt("budgetUsed", 2);
        final ListTag charged = new ListTag();
        final CompoundTag c = new CompoundTag();
        c.putString("kind", "BUILDING");
        c.putLong("pos", hut.asLong());
        charged.add(c);
        one.put("charged", charged);
        one.putLong("marker", marker.asLong());
        one.putLong("noticeDay", -1);
        one.putBoolean("squareProblem", false);
        final ListTag groups = new ListTag();
        groups.add(group("BUILDING", hut, SeasonsConfig.WINTER_FESTIVAL, 3, 11, 12, List.of(wreath, garland),
                new long[] {hut.offset(0, 2, 4).asLong(), hut.offset(1, 2, 4).asLong(), hut.offset(2, 2, 4).asLong()},
                new int[] {0, 1, 1}, new byte[] {1, 0, 1}));
        groups.add(group("SQUARE", marker, SeasonsConfig.WINTER_FESTIVAL, 3, 11, -1, List.of(garland),
                new long[] {marker.offset(0, 1, 0).asLong()}, new int[] {0}, new byte[] {0}));
        if (room) {
            groups.add(group("STOREROOM", hut, "", 3, -1, 12, List.of(box),
                    new long[] {hut.offset(1, 1, -3).asLong(), hut.offset(2, 1, -3).asLong()}, new int[] {0, 0}, new byte[] {0, 0}));
        }
        one.put("groups", groups);
        final ListTag all = new ListTag();
        all.add(one);
        final CompoundTag root = new CompoundTag();
        if (version >= 0) {
            root.putInt("version", version);
        }
        root.put("colonies", all);
        return root;
    }

    private void ledgerFiles() {
        final HolderLookup.Provider provider = level.registryAccess();
        final int warnings = DecorLedger.newerVersionWarnings();
        final DecorLedger v1 = DecorLedger.load(file(1, false), provider);
        final DecorLedger none = DecorLedger.load(file(-1, false), provider);
        final List<DecorLedger.GroupView> groups = v1.groups(FILE_COLONY);
        checkEq("a version 1 file: its groups", groups.stream().map(g -> g.owner().kind() + " " + g.size()).toList(), List.of("BUILDING 3", "SQUARE 1"));
        final DecorLedger.GroupView b = groups.isEmpty() ? null : groups.get(0);
        check("a version 1 file: the building's group reads as written", b != null && b.festival().equals(SeasonsConfig.WINTER_FESTIVAL)
                && b.level() == 3 && b.decoratedDay() == 11 && b.tendedDay() == 12 && b.chargedDay() == 12
                && b.entries().values().stream().map(e -> e.paid() ? 1 : 0).toList().equals(List.of(1, 0, 1)), String.valueOf(b));
        check("a version 1 file: the day's budget and the marker", v1.budgetUsed(FILE_COLONY, 12) == 2 && v1.marker(FILE_COLONY) != null, "");
        checkEq("a file with no version reads as version 1", none.groups(FILE_COLONY), groups);
        checkEq("a file with no version: the same entries", none.entries(FILE_COLONY), v1.entries(FILE_COLONY));
        check("version 1: the outside forms equal the all-kinds forms (no storeroom)", v1.hasOutdoorEntries(FILE_COLONY)
                && v1.countOutdoor(FILE_COLONY) == v1.count(FILE_COLONY) && v1.count(FILE_COLONY) == 4, v1.countOutdoor(FILE_COLONY) + "/" + v1.count(FILE_COLONY));
        final DecorLedger v1back = DecorLedger.load(v1.save(new CompoundTag(), provider), provider);
        checkEq("a version 1 ledger saved by this build reads back the same", v1back.groups(FILE_COLONY), groups);

        final DecorLedger v2 = DecorLedger.load(file(2, true), provider);
        checkEq("a version 2 file with a storeroom: its groups", v2.groups(FILE_COLONY).stream().map(g -> g.owner().kind() + " " + g.size()).toList(),
                List.of("BUILDING 3", "SQUARE 1", "STOREROOM 2"));
        check("version 2: the outside forms leave the storeroom out", v2.countOutdoor(FILE_COLONY) == 4 && v2.count(FILE_COLONY) == 6
                && v2.hasOutdoorEntries(FILE_COLONY), v2.countOutdoor(FILE_COLONY) + "/" + v2.count(FILE_COLONY));
        final CompoundTag saved = v2.save(new CompoundTag(), provider);
        checkEq("this build writes version 2", saved.getInt("version"), 2);
        final DecorLedger back = DecorLedger.load(saved, provider);
        checkEq("version 2 with a storeroom: save and load give the same groups", back.groups(FILE_COLONY), v2.groups(FILE_COLONY));
        checkEq("version 2 with a storeroom: and the same entries", back.entries(FILE_COLONY), v2.entries(FILE_COLONY));
        checkEq("no file of these is taken for a newer one", DecorLedger.newerVersionWarnings(), warnings);
        // a room with only its storeroom: nothing is up outside, but the record is not dropped while the room is listed
        final DecorLedger roomOnly = DecorLedger.load(file(2, true), provider);
        for (final DecorLedger.GroupView g : roomOnly.groups(FILE_COLONY)) {
            if (g.owner().outdoor()) {
                for (final BlockPos p : g.entries().keySet()) {
                    roomOnly.forget(p);
                }
                roomOnly.dropGroup(FILE_COLONY, g.owner());
            }
        }
        check("only a storeroom listed: no outside entries, the colony's record is kept", !roomOnly.hasOutdoorEntries(FILE_COLONY)
                && roomOnly.hasEntries(FILE_COLONY) && !roomOnly.dropColony(FILE_COLONY) && roomOnly.colonies().contains(FILE_COLONY), "");
    }

    // ================================================================ the real Decorator

    private ICitizenData spawn(final BlockPos at) {
        final ICitizenData data = colony.getCitizenManager().createAndRegisterCivilianData();
        colony.getCitizenManager().spawnOrCreateCitizen(data, level, at);
        return data;
    }

    private BlockPos inside(final IBuilding hut) {
        return hut.getPosition().offset(hut.getRotationMirror().applyToPos(INSIDE));
    }

    /** Puts a Decorator inside his Workshop (the walk there is MineColonies'; this only saves ticks). */
    private void bringIn(final ICitizenData citizen, final IBuilding hut) {
        final AbstractEntityCitizen e = citizen == null ? null : citizen.getEntity().orElse(null);
        if (e != null) {
            final BlockPos p = inside(hut);
            e.teleportTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
            e.getNavigation().stop();
        }
    }

    private boolean hire(final ICitizenData citizen, final BuildingDecorator hut) {
        return hut.getModule(DecoratorModules.WORK).assignCitizen(citizen) && citizen.getJob() instanceof JobDecorator;
    }

    private void fire(final ICitizenData citizen, final BuildingDecorator hut) {
        hut.getModule(DecoratorModules.WORK).removeCitizen(citizen);
    }

    private static JobDecorator job(final ICitizenData citizen) {
        return citizen != null && citizen.getJob() instanceof JobDecorator j ? j : null;
    }

    /** Every status key the Decorators show, collected tick by tick. */
    private void watch() {
        for (final ICitizenData c : new ICitizenData[] {decorator, second}) {
            final JobDecorator j = job(c);
            if (j != null) {
                statuses.add(j.status().split(" ", 2)[0]);
            }
        }
    }

    /** A step of the AI part: the day kept, the colonists fed, the statuses watched. */
    private abstract class Live extends Step {
        Live(final String name, final int maxTicks) {
            super(name, maxTicks, true);
        }

        @Override
        boolean tick() {
            keepDay();
            if (t % 20 == 0) {
                keepWell();
            }
            watch();
            return live();
        }

        abstract boolean live();
    }

    private int quota() {
        return DecorWork.quota(turned[0].getBuildingLevel());
    }

    /** Today's building budget used up, so nothing goes up outside (the room has no budget). */
    private void fillBudget() {
        while (ledger().budgetUsed(colony.getID(), day()) < quota()) {
            ledger().spendBudget(colony.getID(), day());
        }
    }

    private void planAi() {
        now("the Decorator is hired (off season, decorations free)", () -> {
            setConfig("NEEDS_MATERIALS", "decorator.needsMaterials", false);
            check("decorator.needsMaterials is off for the AI part", !SeasonsConfig.needsMaterials(), "");
            decorator = spawn(inside(turned[0]));
            check("a citizen is hired as the Decorator of the turned Workshop", hire(decorator, turned[0]), decorator.getName());
            newGameDay();
            bringIn(decorator, turned[0]);
            statuses.clear();
        });
        step(new Live("off season: he checks the packed room at the hut and changes nothing", 1200) {
            @Override
            boolean live() {
                return job(decorator) != null && job(decorator).roomChecks() > 0 && t > 40;
            }

            @Override
            void end(final boolean done) {
                check("off season: the real Decorator checks his room at the hut", done, "room checks " + (job(decorator) == null ? "-" : job(decorator).roomChecks()));
                room("off season, after his check", turned[0], 5, false);
                check("off season: a check that changes nothing shows no room line", !statuses.contains(DecoratorTexts.ROOM_PACKING)
                        && !statuses.contains(DecoratorTexts.ROOM_UNPACKING), String.valueOf(statuses));
                checkEq("off season: his phase", job(decorator).phase(), DecorPhase.OFF_SEASON);
            }
        });
        step(new Live("a window's start: the room is dressed and the outside decorated", 9000) {
            @Override
            void start() {
                statuses.clear();
                setDate(DECOR_DAY);
            }

            @Override
            boolean live() {
                return isRoom(turned[0], 5, true) && ledger().countOutdoor(colony.getID()) > 0 && t > 40;
            }

            @Override
            void end(final boolean done) {
                check("window: the real Decorator dressed his room and decorated outside", done, ledger().countOutdoor(colony.getID()) + " outside");
                room("window, dressed by him", turned[0], 5, true);
                check("window: his status said he unpacked the storeroom", statuses.contains(DecoratorTexts.ROOM_UNPACKING), String.valueOf(statuses));
                check("window: the room was never charged to the budget", !ledger().chargedOn(colony.getID(), DecorLedger.Owner.storeroom(turned[0].getPosition()), day()), "");
            }
        });
        takeDownSteps("the window's end", () -> setDate(AFTER));
        step(new Live("festival force: the room is dressed", 9000) {
            @Override
            void start() {
                statuses.clear();
                newGameDay();
                final Replies r = command("colonyseasons festival force " + SeasonsConfig.WINTER_FESTIVAL);
                check("force: the festival is forced", DecorPhase.windowOpen(CalendarManager.current()), String.valueOf(r.keys()));
                bringIn(decorator, turned[0]);
            }

            @Override
            boolean live() {
                return isRoom(turned[0], 5, true) && ledger().countOutdoor(colony.getID()) > 0 && t > 40;
            }

            @Override
            void end(final boolean done) {
                check("force: the room is dressed and the outside decorated", done, ledger().countOutdoor(colony.getID()) + " outside");
                room("force, dressed", turned[0], 5, true);
            }
        });
        takeDownSteps("festival clear with the outside up", () -> {
            final Replies r = command("colonyseasons festival clear");
            check("clear: the forced festival is cleared", !DecorPhase.windowOpen(CalendarManager.current()), String.valueOf(r.keys()));
        });
        step(new Live("festival force with nothing up outside: the room is dressed", 3000) {
            @Override
            void start() {
                statuses.clear();
                newGameDay();
                fillBudget();
                command("colonyseasons festival force " + SeasonsConfig.WINTER_FESTIVAL);
                bringIn(decorator, turned[0]);
            }

            @Override
            boolean live() {
                fillBudget();
                return isRoom(turned[0], 5, true) && t > 40;
            }

            @Override
            void end(final boolean done) {
                check("force with the day's budget used up: the room is dressed all the same", done, "");
                checkEq("force with the day's budget used up: nothing went up outside", ledger().countOutdoor(colony.getID()), 0);
            }
        });
        step(new Live("before the calendar's first poll: the dressed room is left as it is", 400) {
            int decisions0;
            boolean festive = true;
            boolean sawNone;

            @Override
            void start() {
                statuses.clear();
                decisions0 = job(decorator).decisions();
                bringIn(decorator, turned[0]);
                noSnapshot();
            }

            @Override
            boolean live() {
                fillBudget();
                festive &= isRoom(turned[0], 5, true);
                // with no snapshot his phase reads Off season (DecorPhase.of): his decisions saw none
                sawNone |= job(decorator).phase() == DecorPhase.OFF_SEASON;
                if (t % 100 == 0) {
                    bringIn(decorator, turned[0]);
                }
                noSnapshot();                         // after this tick's poll: the next tick's decisions see none either
                return false;
            }

            @Override
            void end(final boolean done) {
                CalendarManager.refresh(server);
                check("no snapshot: he decided at his hut with no calendar state", sawNone && job(decorator).decisions() > decisions0,
                        (job(decorator).decisions() - decisions0) + " decisions, saw none " + sawNone);
                check("no snapshot: the dressed room is left as it is (no packing before the first poll)", festive && isRoom(turned[0], 5, true),
                        diff(listed(turned[0]), golden(turned[0], 5, true)));
                check("no snapshot: no room line", !statuses.contains(DecoratorTexts.ROOM_PACKING), String.valueOf(statuses));
                check("no snapshot: the first poll gives the forced festival back", DecorPhase.windowOpen(CalendarManager.current()), "");
            }
        });
        step(new Live("festival clear with nothing up outside: packed at once", 600) {
            @Override
            void start() {
                statuses.clear();
                command("colonyseasons festival clear");
                bringIn(decorator, turned[0]);
            }

            @Override
            boolean live() {
                fillBudget();
                return isRoom(turned[0], 5, false);
            }

            @Override
            void end(final boolean done) {
                check("clear with nothing up outside: the room is packed at once (his next check at the hut)", done && t <= 400, "after " + t + " ticks");
                checkEq("clear with nothing up outside: his phase", job(decorator).phase(), DecorPhase.OFF_SEASON);
                check("clear: his status said he packed the storeroom away", statuses.contains(DecoratorTexts.ROOM_PACKING), String.valueOf(statuses));
            }
        });
        step(new Live("a Decorator hired in the middle of a window", 6000) {
            @Override
            void start() {
                statuses.clear();
                newGameDay();
                setDate(DECOR_DAY);
                room("the mirrored Workshop, nobody there, the window open: still packed", mirrored[0], 5, false);
                second = spawn(inside(mirrored[0]));
                check("a second citizen is hired as the Decorator of the mirrored Workshop", hire(second, mirrored[0]), second.getName());
                bringIn(second, mirrored[0]);
            }

            @Override
            boolean live() {
                return isRoom(mirrored[0], 5, true) && t > 40;
            }

            @Override
            void end(final boolean done) {
                check("hired in the window: he finds the room packed and unpacks it", done, "room checks " + (job(second) == null ? "-" : job(second).roomChecks()));
                room("hired in the window", mirrored[0], 5, true);
            }
        });
        step(new Live("decor list, decor remove and the rooms coming back", 6000) {
            @Override
            void start() {
                final int id = colony.getID();
                final Replies list = command("colonyseasons decor list " + id);
                int roomLines = 0;
                for (final Component c : list.said) {
                    if (c.getContents() instanceof TranslatableContents tc && tc.getKey().equals("com.colony_seasons.command.decor.list.group")
                            && tc.getArgs().length > 0 && tc.getArgs()[0] instanceof Component kind
                            && kind.getContents() instanceof TranslatableContents k && k.getKey().equals("com.colony_seasons.command.decor.kind.storeroom")) {
                        roomLines++;
                    }
                }
                checkEq("decor list: each Workshop's storeroom on a line of its own", roomLines, 2);
                final Replies remove = command("colonyseasons decor remove " + id);
                note("decor remove said {}", remove.keys());
                check("decor remove: no storeroom group is left", ledger().groups(id).stream().noneMatch(g -> g.owner().kind() == DecorLedger.Kind.STOREROOM), "");
                check("decor remove: both rooms are bare", inWorld(turned[0], 0).isEmpty() && inWorld(mirrored[0], 0).isEmpty(), "");
                bringIn(decorator, turned[0]);
                bringIn(second, mirrored[0]);
            }

            @Override
            boolean live() {
                return isRoom(turned[0], 5, true) && isRoom(mirrored[0], 5, true) && t > 40;
            }

            @Override
            void end(final boolean done) {
                check("decor remove: the Decorators dress both rooms again", done, "");
            }
        });
        step(new Live("no Decorator after a window: the unstaffed Workshop's festive room goes", 400) {
            int checksAtFire;

            @Override
            void start() {
                fire(second, mirrored[0]);
                check("the mirrored Workshop's Decorator is fired", mirrored[0].getAllAssignedCitizen().isEmpty(), "");
                setDate(AFTER);
            }

            boolean kept = true;

            @Override
            boolean live() {
                kept &= ledger().group(colony.getID(), DecorLedger.Owner.storeroom(turned[0].getPosition())) != null;
                return ledger().group(colony.getID(), DecorLedger.Owner.storeroom(mirrored[0].getPosition())) == null && t > 1;
            }

            @Override
            void end(final boolean done) {
                check("after the window: the sweep takes off the festive room of the Workshop with no worker", done
                        && t <= 2 * DecorEvents.SWEEP_INTERVAL + 1 && inWorld(mirrored[0], 0).isEmpty(), "after " + t + " ticks");
                check("after the window: the staffed Workshop's room is never taken by the sweep", kept, "");
            }
        });
        takeDownSteps("after the window, with one Workshop staffed", () -> { });
        step(new Live("no Decorator at all after a window: packed rooms stay", 500) {
            @Override
            void start() {
                fire(decorator, turned[0]);
                check("no Decorator is left in the colony", !DecorWork.staffed(colony), "");
                direct(mirrored[0], "");
                room("the unstaffed Workshop packed by a check", mirrored[0], 5, false);
                // as a hut whose Decorator left with the decorations up: the sweep's reset reads the outside only
                turned[0].setDecorationsUp(true);
                mirrored[0].setDecorationsUp(true);
                check("no Decorator: nothing is listed outside, only the packed rooms", !ledger().hasOutdoorEntries(colony.getID())
                        && ledger().hasEntries(colony.getID()), "");
            }

            @Override
            boolean live() {
                return false;
            }

            @Override
            void end(final boolean done) {
                room("no Decorator, " + t + " ticks after the window: the turned room", turned[0], 5, false);
                room("no Decorator, " + t + " ticks after the window: the mirrored room", mirrored[0], 5, false);
                check("no Decorator: the sweep says the decorations are down at both unstaffed huts, the packed rooms listed",
                        !turned[0].decorationsUp() && !mirrored[0].decorationsUp(), "turned " + turned[0].decorationsUp() + ", mirrored " + mirrored[0].decorationsUp());
            }
        });
        step(new Live("the switch off: the sweep takes the rooms off and nothing is placed", 900) {
            @Override
            void start() {
                dressing(false);
                check("hired again", hire(decorator, turned[0]), "");
                bringIn(decorator, turned[0]);
            }

            @Override
            boolean live() {
                if (t == 300) {
                    newGameDay();
                }
                return false;
            }

            @Override
            void end(final boolean done) {
                check("switch off: no storeroom group is left", ledger().groups(colony.getID()).stream().noneMatch(g -> g.owner().kind() == DecorLedger.Kind.STOREROOM), "");
                check("switch off: both rooms are bare", inWorld(turned[0], 0).isEmpty() && inWorld(mirrored[0], 0).isEmpty(), "");
                check("switch off: the room is never due", !due(turned[0], "") && !due(turned[0], WINTER), "");
                final StoreroomWork.Result r = direct(turned[0], "");
                check("switch off: a check places nothing", r.changes() == 0 && listed(turned[0]).isEmpty(), String.valueOf(r));
            }
        });
        step(new Live("the switch on again: he dresses the room", 3000) {
            @Override
            void start() {
                dressing(true);
                bringIn(decorator, turned[0]);
            }

            @Override
            boolean live() {
                return isRoom(turned[0], 5, false) && t > 40;
            }

            @Override
            void end(final boolean done) {
                check("switch on: the real Decorator packs the room again", done, "");
            }
        });
        final Map<BlockPos, BlockState> cleared = new LinkedHashMap<>();
        now("the Builder clears the cells: the ledger forgets them, the world is his", () -> {
            final int near0 = itemsNear(turned[0]);
            final int writes = ledger().writes();
            cleared.putAll(listed(turned[0]));
            for (final BlockPos p : cleared.keySet()) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);   // as the Builder's clearing stage does
            }
            check("the Builder cleared the room: the ledger forgot every entry", listed(turned[0]).isEmpty() && cleared.size() == 6, cleared.size() + " cells");
            check("the Builder cleared the room: the cells are as he left them (air)", inWorld(turned[0], 0).isEmpty()
                    && cleared.keySet().stream().allMatch(p -> level.getBlockState(p).isAir()), "");
            checkEq("the Builder cleared the room: nothing dropped", itemsNear(turned[0]), near0);
            final DecorLedger.RoomMark mark = ledger().roomMark(colony.getID(), DecorLedger.Owner.storeroom(turned[0].getPosition()));
            check("the Builder cleared the room: checked today, so not due again today", mark == null || !due(turned[0], ""),
                    mark == null ? "no group" : mark.toString());
            note("the Builder's clearing: {} ledger writes", ledger().writes() - writes);
        });
        step(new Live("the next day's check puts the pieces back", 3000) {
            @Override
            void start() {
                newGameDay();
                bringIn(decorator, turned[0]);
            }

            @Override
            boolean live() {
                return isRoom(turned[0], 5, false) && t > 20;
            }

            @Override
            void end(final boolean done) {
                check("the next day the real Decorator puts the room back", done, "");
            }
        });
    }

    /**
     * The take-down after a window (the calendar turned by {@code turn}): while anything is up outside the room stays
     * festive, then it is packed; the outside ledger is empty, the room's group still listed, his phase Off season and
     * the hut's "decorations up" off. Through the real AI's decide() and takingDown().
     */
    private void takeDownSteps(final String label, final Runnable turn) {
        step(new Live(label + ": festive until the outside is down, then packed", 20000) {
            boolean festiveWhileUp = true;
            int downVisits0;

            @Override
            void start() {
                statuses.clear();
                downVisits0 = job(decorator) == null ? 0 : job(decorator).visits(me.lovkar.seasons.ai.EntityAIWorkDecorator.DecoratorState.TAKING_DOWN);
                turn.run();
            }

            @Override
            boolean live() {
                final int id = colony.getID();
                if (ledger().hasOutdoorEntries(id) && !isRoom(turned[0], 5, true)) {
                    festiveWhileUp = false;
                }
                return !ledger().hasOutdoorEntries(id) && isRoom(turned[0], 5, false) && job(decorator) != null
                        && job(decorator).phase() == DecorPhase.OFF_SEASON && !turned[0].decorationsUp();
            }

            @Override
            void end(final boolean done) {
                final int id = colony.getID();
                check(label + ": the outside came down and the room was packed", done, ledger().countOutdoor(id) + " outside, phase "
                        + (job(decorator) == null ? "-" : job(decorator).phase()));
                check(label + ": the room stayed festive while anything was up outside", festiveWhileUp, "");
                room(label + ", after the take-down", turned[0], 5, false);
                check(label + ": the outside ledger is empty", !ledger().hasOutdoorEntries(id) && ledger().countOutdoor(id) == 0, ledger().countOutdoor(id) + " entries");
                check(label + ": the room's group is still listed", ledger().group(id, DecorLedger.Owner.storeroom(turned[0].getPosition())) != null
                        && ledger().hasEntries(id), "");
                check(label + ": his phase is Off season and the hut says the decorations are down", job(decorator).phase() == DecorPhase.OFF_SEASON
                        && !turned[0].decorationsUp(), "");
                check(label + ": he took down outside through his own take-down", job(decorator).visits(me.lovkar.seasons.ai.EntityAIWorkDecorator.DecoratorState.TAKING_DOWN) > downVisits0, "");
                check(label + ": his status said he packed the storeroom away", statuses.contains(DecoratorTexts.ROOM_PACKING), String.valueOf(statuses));
            }
        });
    }

    // ================================================================ the restart

    private void planRestartSave() {
        now("save: setup", () -> {
            setDate(SUMMER);
            quietWorld();
            buildColony();
        });
        paste("save: the turned Workshop at level 1", TURNED_OFFSET, 1, TURNED, turned);
        now("save: a check stopped in the middle", () -> {
            final BuildingDecorator hut = turned[0];
            direct(hut, "");
            room("save: packed first", hut, 1, false);
            final Replies r = command("colonyseasons festival force " + SeasonsConfig.WINTER_FESTIVAL);
            check("save: the festival is forced (saved with the world)", DecorPhase.windowOpen(CalendarManager.current()), String.valueOf(r.keys()));
            // 7 changes: the 5 packed pieces come off first (all of them differ), then 2 of the 13 festival pieces go up
            final StoreroomWork.Result half = direct(hut, WINTER, 7);
            check("save: the check stopped half done (5 off, 2 of 13 up, not complete)", half.removed() == 5 && half.placed() == 2 && !half.complete(),
                    String.valueOf(half));
            final Map<BlockPos, BlockState> listed = listed(hut);
            final Map<BlockPos, BlockState> festival = golden(hut, 1, true);
            check("save: the ledger lists the 2 festival pieces that are up", listed.size() == 2 && festival.entrySet().containsAll(listed.entrySet()),
                    diff(listed, festival));
            checkEq("save: the world holds what the ledger lists", inWorld(hut, 0), listed);
            check("save: the half-done room is not marked done", due(hut, WINTER), "");
            final Properties ids = new Properties();
            ids.setProperty("colony", Integer.toString(colony.getID()));
            ids.setProperty("hut", Long.toString(hut.getPosition().asLong()));
            try (var out = Files.newBufferedWriter(server.getWorldPath(LevelResource.ROOT).resolve(IDS_FILE), StandardCharsets.US_ASCII)) {
                ids.store(out, "csstoreroom restart");
            } catch (final java.io.IOException unwritten) {
                check("save: the ids are written for the restart", false, unwritten.getClass().getSimpleName());
            }
            check("save: the world saved", server.saveEverything(false, true, true), "");
        });
    }

    private void planRestartLoad() {
        final Properties ids = new Properties();
        step(new Step("load: the colony and the Workshop come back", 2400, false) {
            @Override
            void start() {
                try (var in = Files.newBufferedReader(server.getWorldPath(LevelResource.ROOT).resolve(IDS_FILE), StandardCharsets.US_ASCII)) {
                    ids.load(in);
                } catch (final java.io.IOException unread) {
                    check("load: the ids of the first run are there", false, unread.getClass().getSimpleName());
                    finish();
                }
            }

            @Override
            boolean tick() {
                final IColony found = IColonyManager.getInstance().getColonyByWorld(Integer.parseInt(ids.getProperty("colony", "-1")), level);
                if (found == null) {
                    return false;
                }
                final IBuilding b = found.getServerBuildingManager().getBuilding(BlockPos.of(Long.parseLong(ids.getProperty("hut", "0"))));
                if (b instanceof BuildingDecorator d && d.getTileEntity() != null && !d.getTileEntity().getSchematicName().isEmpty()) {
                    colony = found;
                    turned[0] = d;
                    return t >= 60;
                }
                return false;
            }

            @Override
            void end(final boolean done) {
                check("load: the colony and its Workshop are back", done, "");
                if (!done) {
                    finish();
                    return;
                }
                owner = FakePlayerFactory.getMinecraft(level);
                final BuildingDecorator hut = turned[0];
                check("load: nobody works at the Workshop", hut.getAllAssignedCitizen().isEmpty(), "");
                final CalendarManager.Snapshot snapshot = CalendarManager.current();
                checkEq("load: the forced festival came back with the world", snapshot == null ? "-" : snapshot.forced(), SeasonsConfig.WINTER_FESTIVAL);
                final Map<BlockPos, BlockState> listed = listed(hut);
                checkEq("load: the ledger lists the 2 pieces of the half-done check", listed.size(), 2);
                checkEq("load: the ledger equals the world in every room cell", inWorld(hut, 0), listed);
                final String target = StoreroomWork.target(DecorPhase.of(snapshot, false), hut.decorateEnabled(), DecorWork.festival(), null);
                checkEq("load: the target in the forced window", target, WINTER);
                final StoreroomWork.Result r = direct(hut, target);
                check("load: the next check ends the swap (11 up, nothing off)", r.complete() && r.placed() == 11 && r.removed() == 0, String.valueOf(r));
                room("load: after the restart", hut, 1, true);
                check("load: and the room is no longer due today", !due(hut, target), "");
            }
        });
    }
}
