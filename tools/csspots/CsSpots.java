package me.lovkar.seasons.csspots;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ldtteam.structurize.api.RotationMirror;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.workorders.WorkOrderType;
import com.minecolonies.api.tileentities.AbstractTileEntityColonyBuilding;
import com.minecolonies.core.colony.workorders.WorkOrderBuilding;
import me.lovkar.seasons.block.SeasonsBlocks;
import me.lovkar.seasons.colony.BuildingDecorator;
import me.lovkar.seasons.decor.StoreroomSpots;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.phys.AABB;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Headless check of the storeroom's pieces and spots (chunk S10g1, docs/storage-look-design.md 3.5 and 5).
 *
 * <ul>
 *   <li>The two new blocks, storage_box and storage_coil: no item registered and none in the creative tab, no loot
 *       table (the empty one) and no drops whether broken or popped off, no collision but an outline, pick-block gives
 *       nothing, they stand on a barrel and go when it goes, every state has its blockstate variant and models.</li>
 *   <li>The Workshop pasted at level 1, turned (R90), with the command a player types: every storeroom spot tag that
 *       {@code IBuilding.getLocationsFromTag} hands out is the blueprint's offset turned with the building
 *       (tools/csspots/res/csspots/spots.json, written by tools/build_pack.py), nothing else of ours is in the cached
 *       tag map, {@link StoreroomSpots#read} lists the same, and in the world each packed spot is air on a barrel and
 *       each hung spot is air beside a whole wall block. The first [V] of 3.5.</li>
 *   <li>Upgrade work orders 1 to 2, 2 to 3, 3 to 4 and 4 to 5, finished the way the Builder finishes them (the order's
 *       blueprint loaded and completed, the level set): before the order completes the cached tags are still the old
 *       level's; after it, exactly the new level's, turned. The second [V] of 3.5.</li>
 *   <li>A second Workshop pasted fresh at level 5, mirrored and turned (MIR_R270): its tags and its world as above.</li>
 * </ul>
 * Prints "[csspots] CHECK ...: OK|FAIL" lines and "[csspots] RESULT csspots: PASS|FAIL", then halts. The two words
 * the rig greps for stay out of the details.
 */
@Mod("colonyseasonsspots")
public class CsSpots {
    private static final Logger LOGGER = LoggerFactory.getLogger("csspots");
    private static final int SETUP_TICK = 60;
    private static final long WATCHDOG_SECONDS = 420;
    private static final String PACK = "Colony Seasons";
    private static final String RACK = "minecolonies:blockminecoloniesrack";
    /** From the town hall: the Workshop turned (upgraded 1 to 5) and the Workshop mirrored (pasted at 5). */
    private static final BlockPos TURNED_OFFSET = new BlockPos(14, 0, 0);
    private static final BlockPos MIRRORED_OFFSET = new BlockPos(-14, 0, 14);
    private static final RotationMirror TURNED = RotationMirror.R90;
    private static final RotationMirror MIRRORED = RotationMirror.MIR_R270;
    /** The design's counts (docs/storage-look-design.md section 4): packed pieces and hung blocks at levels 1 and 5. */
    private static final Map<Integer, int[]> DESIGN_COUNTS = Map.of(1, new int[] {5, 10}, 5, new int[] {6, 15});

    private int ticks;
    private boolean failed;
    private boolean finished;
    private long setupNanos;

    private MinecraftServer server;
    private ServerLevel level;
    private ServerPlayer owner;
    private IColony colony;
    private BlockPos center;
    private JsonObject spots;

    private final List<Step> steps = new ArrayList<>();
    private int stepIndex;

    public CsSpots() {
        NeoForge.EVENT_BUS.addListener(this::onTick);
    }

    // ================================================================ logging and steps (as in cshire)

    private void check(final String what, final boolean ok, final String detail) {
        LOGGER.info("[csspots] CHECK {}: {} {}", what, ok ? "OK" : "FAIL", detail);
        failed |= !ok;
    }

    private void checkEq(final String what, final Object actual, final Object expected) {
        check(what, Objects.equals(actual, expected), "got " + actual + (Objects.equals(actual, expected) ? "" : ", wanted " + expected));
    }

    private static void note(final String text, final Object... args) {
        LOGGER.info("[csspots] " + text, args);
    }

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
            LOGGER.info("[csspots] trace: {}", Arrays.toString(Arrays.copyOf(t.getStackTrace(), Math.min(8, t.getStackTrace().length))));
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
            step.start();
        }
        step.t++;
        final boolean done = finished || step.tick();
        if (done || step.t >= step.maxTicks) {
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
        LOGGER.info("[csspots] RESULT csspots: {}", failed ? "FAIL" : "PASS");
        if (server != null) {
            server.halt(false);
        }
    }

    private abstract static class Step {
        final String name;
        final int maxTicks;
        boolean started;
        int t;

        Step(final String name, final int maxTicks) {
            this.name = name;
            this.maxTicks = maxTicks;
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
        steps.add(new Step(name, 1) {
            @Override
            void start() {
                body.run();
            }
        });
    }

    // ================================================================ the plan

    private void plan() {
        final BuildingDecorator[] turned = new BuildingDecorator[1];
        final BuildingDecorator[] mirrored = new BuildingDecorator[1];
        now("setup", () -> {
            quietWorld();
            buildColony();
            spots = readSpots();
            spotFileSane();
            pieces();
        });
        paste("the turned Workshop at level 1", TURNED_OFFSET, 1, TURNED, turned);
        now("level 1 tags, turned", () -> tags(turned[0], 1, TURNED, true));
        for (int lv = 2; lv <= 5; lv++) {
            upgrade(turned, lv);
        }
        paste("the mirrored Workshop at level 5", MIRRORED_OFFSET, 5, MIRRORED, mirrored);
        now("level 5 tags, mirrored and turned", () -> tags(mirrored[0], 5, MIRRORED, true));
    }

    // ================================================================ the colony

    private void quietWorld() {
        server.setDifficulty(Difficulty.PEACEFUL, true);
        server.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        server.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        level.setWeatherParameters(24000 * 20, 0, false, false);
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
        colony = IColonyManager.getInstance().createColony(level, center, owner, "Seasons Spots", "Medieval Oak");
        note("colony {} created at {}", colony == null ? "none" : colony.getID(), center);
        final Block townHall = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.fromNamespaceAndPath("minecolonies", "blockhuttownhall")).orElse(null);
        if (townHall != null) {
            level.setBlock(center, townHall.defaultBlockState(), 3);
            if (level.getBlockEntity(center) instanceof AbstractTileEntityColonyBuilding te) {
                note("town hall registered: {}", colony.getServerBuildingManager().addNewBuilding(te, level) != null);
            }
        }
        owner.setGameMode(GameType.CREATIVE);
    }

    private Replies command(final String line) {
        final Replies out = new Replies();
        server.getCommands().performPrefixedCommand(owner.createCommandSourceStack().withSource(out).withPermission(4), line);
        return out;
    }

    private static final class Replies implements CommandSource {
        final List<String> said = new ArrayList<>();

        @Override
        public void sendSystemMessage(final Component component) {
            said.add(component.getContents() instanceof TranslatableContents t ? t.getKey() : component.getString());
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

    /** The Workshop pasted at a level with a rotation as a player pastes it, then waited for until its hut data is in. */
    private void paste(final String name, final BlockPos offset, final int lv, final RotationMirror rm, final BuildingDecorator[] into) {
        steps.add(new Step(name, 600) {
            BlockPos at;

            @Override
            void start() {
                at = center.offset(offset);
                owner.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 3.5);
                final String line = "structurize paste " + at.getX() + " " + at.getY() + " " + at.getZ()
                        + " Colony.Seasons decorator.workshop" + lv + " " + rm.name() + " true";
                final Replies replies = command(line);
                note("'{}' said {}", line, replies.said);
                check(name + ": the paste command is accepted", replies.said.stream().anyMatch(s -> s.contains("paste.success")), String.valueOf(replies.said));
            }

            @Override
            boolean tick() {
                if (into[0] == null && colony.getServerBuildingManager().getBuilding(at) instanceof BuildingDecorator d) {
                    into[0] = d;
                    note("{}: the BuildingDecorator is there after {} ticks", name, t);
                }
                final AbstractTileEntityColonyBuilding te = into[0] == null ? null : into[0].getTileEntity();
                return t >= 100 && te != null && !te.getSchematicName().isEmpty();
            }

            @Override
            void end(final boolean done) {
                final BuildingDecorator hut = into[0];
                check(name + ": the hut block makes a BuildingDecorator", hut != null,
                        hut == null ? "none; at the hut position: " + level.getBlockState(at) : "");
                check(name + ": the paste finished (the hut block has its blueprint data)", done && hut != null, "");
                if (hut == null) {
                    finish();
                    return;
                }
                if (hut != null) {
                    checkEq(name + ": the building's rotation and mirror", hut.getRotationMirror(), rm);
                    checkEq(name + ": the building's level", hut.getBuildingLevel(), lv);
                }
            }
        });
    }

    /**
     * Upgrade the turned Workshop to {@code lv} the way the Builder finishes an upgrade: an UPGRADE work order for the
     * hut, its blueprint loaded through the order, the order completed (MineColonies reads the new blueprint's hut data
     * into the hut block there), the level set and the upgrade completed.
     */
    private void upgrade(final BuildingDecorator[] hut, final int lv) {
        final WorkOrderBuilding[] order = new WorkOrderBuilding[1];
        final Blueprint[] loaded = new Blueprint[1];
        steps.add(new Step("the upgrade work order " + (lv - 1) + " to " + lv, 400) {
            @Override
            void start() {
                checkEq("level " + lv + ": the Workshop is at level " + (lv - 1) + " before the upgrade", hut[0].getBuildingLevel(), lv - 1);
                final WorkOrderBuilding wo = WorkOrderBuilding.create(WorkOrderType.UPGRADE, hut[0]);
                order[0] = wo;
                checkEq("level " + lv + ": the order's target level", wo.getTargetLevel(), lv);
                checkEq("level " + lv + ": the order's blueprint", wo.getStructurePath(), "decorator/workshop" + lv + ".blueprint");
                checkEq("level " + lv + ": the order's rotation and mirror", wo.getRotationMirror(), hut[0].getRotationMirror());
                colony.getWorkManager().addWorkOrder(wo, false);
                wo.loadBlueprint(level, bp -> loaded[0] = bp);
            }

            @Override
            boolean tick() {
                return loaded[0] != null;
            }

            @Override
            void end(final boolean done) {
                check("level " + lv + ": the order loads its blueprint as the Builder does", done && loaded[0] != null, "");
                if (loaded[0] == null) {
                    finish();
                    return;
                }
                checkEq("level " + lv + ": before the order completes, the cached tags are still level " + (lv - 1) + "'s",
                        ours(hut[0]), expected(hut[0], lv - 1, hut[0].getRotationMirror()));
                try {
                    order[0].onCompleted(colony, null);
                } catch (final RuntimeException e) {
                    check("level " + lv + ": the order completes", false, e.toString());
                }
                hut[0].setBuildingLevel(lv);
                hut[0].onUpgradeComplete(loaded[0], lv);
                colony.getWorkManager().removeWorkOrder(order[0]);
                checkEq("level " + lv + ": the Workshop's level after the upgrade", hut[0].getBuildingLevel(), lv);
                tags(hut[0], lv, TURNED, false);
            }
        });
    }

    // ================================================================ the spot file

    private static JsonObject readSpots() {
        try (InputStream in = CsSpots.class.getResourceAsStream("/csspots/spots.json")) {
            if (in == null) {
                throw new IllegalStateException("no csspots/spots.json in the probe jar");
            }
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (final java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> strings(final JsonArray a) {
        final List<String> out = new ArrayList<>();
        a.forEach(e -> out.add(e.getAsString()));
        return out;
    }

    /** The file and the code name the same sets and pieces, and the file has the design's counts. */
    private void spotFileSane() {
        checkEq("the spot file's sets are the code's", strings(spots.getAsJsonArray("sets")), StoreroomSpots.SETS);
        checkEq("the spot file's packed pieces are the code's", strings(spots.getAsJsonArray("packedPieces")), StoreroomSpots.PACKED_PIECES);
        checkEq("the spot file's hung pieces are the code's", strings(spots.getAsJsonArray("hungPieces")), StoreroomSpots.HUNG_PIECES);
        for (int lv = 1; lv <= 5; lv++) {
            int pack = 0;
            int hang = 0;
            final Set<String> unknown = new TreeSet<>();
            for (final JsonElement e : spots.getAsJsonObject("levels").getAsJsonArray(String.valueOf(lv))) {
                final String tag = e.getAsJsonArray().get(3).getAsString();
                final StoreroomSpots.Spot s = StoreroomSpots.parse(tag, BlockPos.ZERO);
                if (s == null) {
                    unknown.add(tag);
                } else if (s.kind() == StoreroomSpots.Kind.PACK) {
                    pack++;
                } else {
                    hang++;
                }
            }
            check("level " + lv + ": every spot tag in the file is one the code knows", unknown.isEmpty(), unknown.toString());
            final int[] want = DESIGN_COUNTS.get(lv);
            check("level " + lv + ": the file has packed and hung spots" + (want == null ? "" : " in the design's numbers"),
                    want == null ? pack > 0 && hang > 0 : pack == want[0] && hang == want[1], pack + " packed, " + hang + " hung");
        }
    }

    // ================================================================ the tags

    /** tag -> world positions the file wants for level {@code lv} of a Workshop whose hut block stands at the building's position, turned. */
    private Map<String, Set<BlockPos>> expected(final IBuilding hut, final int lv, final RotationMirror rm) {
        final Map<String, Set<BlockPos>> out = new TreeMap<>();
        for (final JsonElement e : spots.getAsJsonObject("levels").getAsJsonArray(String.valueOf(lv))) {
            final JsonArray a = e.getAsJsonArray();
            final BlockPos offset = new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
            out.computeIfAbsent(a.get(3).getAsString(), k -> new TreeSet<>()).add(hut.getID().offset(rm.applyToPos(offset)));
        }
        return out;
    }

    /** tag -> world positions of every tag of ours in the hut block's cached tag map. */
    private static Map<String, Set<BlockPos>> ours(final IBuilding hut) {
        final Map<String, Set<BlockPos>> out = new TreeMap<>();
        final AbstractTileEntityColonyBuilding te = hut.getTileEntity();
        if (te == null) {
            return out;
        }
        te.getCachedWorldTagNamePosMap().forEach((name, at) -> {
            if (name.startsWith(StoreroomSpots.PACK_PREFIX) || name.startsWith(StoreroomSpots.HANG_PREFIX)) {
                out.computeIfAbsent(name, k -> new TreeSet<>()).addAll(at);
            }
        });
        return out;
    }

    private void tags(final BuildingDecorator hut, final int lv, final RotationMirror rm, final boolean world) {
        if (hut == null) {
            check("level " + lv + ": a Workshop to read", false, "none");
            return;
        }
        final String at = "level " + lv + " " + rm.name() + ": ";
        final Map<String, Set<BlockPos>> want = expected(hut, lv, rm);
        int same = 0;
        final List<String> differ = new ArrayList<>();
        for (final Map.Entry<String, Set<BlockPos>> e : want.entrySet()) {
            final Set<BlockPos> got = new TreeSet<>(hut.getLocationsFromTag(e.getKey()));
            if (got.equals(e.getValue())) {
                same++;
            } else {
                differ.add(e.getKey() + " got " + got + " wanted " + e.getValue());
            }
        }
        check(at + "getLocationsFromTag gives every spot turned with the building", differ.isEmpty(),
                same + " of " + want.size() + " tags" + (differ.isEmpty() ? "" : "; " + differ.get(0)));
        checkEq(at + "the cached tag map holds exactly this level's spots", ours(hut), want);
        if (rm != RotationMirror.NONE) {
            final Map<String, Set<BlockPos>> unturned = expected(hut, lv, RotationMirror.NONE);
            check(at + "the turned spots are not the unturned ones (the check sees the turn)", !unturned.equals(want), "");
        }
        // what the mod's helper lists
        final Map<String, Set<BlockPos>> helper = new TreeMap<>();
        int states = 0;
        int missingStates = 0;
        for (final StoreroomSpots.Spot s : StoreroomSpots.read(hut, StoreroomSpots.WINTER)) {
            helper.computeIfAbsent(s.tag(), k -> new TreeSet<>()).add(s.pos());
            final BlockState packed = StoreroomSpots.state(s, false);
            final BlockState festival = StoreroomSpots.state(s, true);
            final boolean ok = switch (s.kind()) {
                case HANG -> packed == null && festival != null;
                case PACK -> packed != null && (s.piece().startsWith("coil_") ? festival == null : festival != null);
            };
            states++;
            missingStates += ok ? 0 : 1;
        }
        checkEq(at + "StoreroomSpots.read lists the same spots", helper, want);
        check(at + "every spot has its block packed and in the festival (a coil none in the festival, a hung piece none packed)",
                missingStates == 0 && states > 0, states + " spots, " + missingStates + " without");
        if (!world) {
            return;
        }
        // the world the paste left: packed spots air on a barrel, hung spots air beside a whole wall block
        final List<String> bad = new ArrayList<>();
        for (final Map.Entry<String, Set<BlockPos>> e : want.entrySet()) {
            final boolean packed = e.getKey().startsWith(StoreroomSpots.PACK_PREFIX);
            for (final BlockPos p : e.getValue()) {
                if (!level.getBlockState(p).isAir()) {
                    bad.add(e.getKey() + " " + p.toShortString() + " holds " + level.getBlockState(p));
                } else if (packed && !level.getBlockState(p.below()).is(Blocks.BARREL)) {
                    bad.add(e.getKey() + " " + p.toShortString() + " stands on " + level.getBlockState(p.below()));
                } else if (!packed && Direction.Plane.HORIZONTAL.stream().noneMatch(d -> wall(p.relative(d)))) {
                    bad.add(e.getKey() + " " + p.toShortString() + " has no wall beside it");
                }
            }
        }
        check(at + "in the world every packed spot is air on a barrel and every hung spot air beside a wall", bad.isEmpty(),
                bad.isEmpty() ? want.values().stream().mapToInt(Set::size).sum() + " spots" : bad.size() + " wrong, e.g. " + bad.get(0));
    }

    /** A whole block a wall decoration hangs on, not furniture (a barrel or a rack). */
    private boolean wall(final BlockPos p) {
        final BlockState s = level.getBlockState(p);
        return s.isCollisionShapeFullBlock(level, p) && !s.is(Blocks.BARREL)
                && !RACK.equals(BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString());
    }

    // ================================================================ the two blocks

    private void pieces() {
        final BlockPos base = center.offset(0, 0, -20);
        int i = 0;
        for (final DeferredBlock<? extends Block> block : List.of(SeasonsBlocks.STORAGE_BOX, SeasonsBlocks.STORAGE_COIL)) {
            final String name = block.getId().getPath();
            final Block b = block.get();
            check(name + ": no item is registered", !BuiltInRegistries.ITEM.containsKey(block.getId()) && b.asItem() == Items.AIR,
                    "item " + BuiltInRegistries.ITEM.getKey(b.asItem()));
            check(name + ": not in the creative tab's list", SeasonsBlocks.ALL.stream().noneMatch(d -> d.get() == b), "");
            checkEq(name + ": its loot table is the empty one", b.getLootTable(), BuiltInLootTables.EMPTY);
            blockstateFile(block);
            int noDrops = 0;
            for (final BlockState s : b.getStateDefinition().getPossibleStates()) {
                noDrops += Block.getDrops(s, level, base, null).isEmpty() ? 1 : 0;
            }
            checkEq(name + ": no state drops anything", noDrops, b.getStateDefinition().getPossibleStates().size());

            final BlockPos barrel = base.offset(4 * i++, 0, 0);
            final BlockPos at = barrel.above();
            level.setBlock(barrel, Blocks.BARREL.defaultBlockState(), Block.UPDATE_ALL);
            final BlockState state = b.defaultBlockState();
            check(name + ": stands on a barrel", state.canSurvive(level, at), "");
            check(name + ": does not stand on air", !state.canSurvive(level, at.above()), "");
            level.setBlock(at, state, Block.UPDATE_ALL);
            final BlockState placed = level.getBlockState(at);
            checkEq(name + ": is placed", placed, state);
            check(name + ": no collision", placed.getCollisionShape(level, at).isEmpty(), placed.getCollisionShape(level, at).toString());
            check(name + ": an outline to aim at", !placed.getShape(level, at).isEmpty(), placed.getShape(level, at).bounds().toString());
            final ItemStack pick = b.getCloneItemStack(level, at, placed);
            check(name + ": pick-block gives nothing", pick.isEmpty(), pick.toString());
            // the barrel goes: the piece goes with it and nothing drops
            level.setBlock(barrel, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            check(name + ": goes when the barrel under it goes", level.getBlockState(at).isAir(), level.getBlockState(at).toString());
            checkEq(name + ": nothing dropped when it went", itemsNear(at), 0);
            // broken by hand on a new barrel: nothing drops
            level.setBlock(barrel, Blocks.BARREL.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(at, state, Block.UPDATE_ALL);
            final boolean broke = level.destroyBlock(at, true, owner);
            check(name + ": breaks", broke && level.getBlockState(at).isAir(), level.getBlockState(at).toString());
            checkEq(name + ": nothing dropped when broken", itemsNear(at), 0);
            level.setBlock(barrel, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    private int itemsNear(final BlockPos pos) {
        int n = 0;
        for (final ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2))) {
            n += e.getItem().getCount();
        }
        return n;
    }

    /** Every state matches a variant of the block's blockstate file, every variant a state, every model is in the jar. */
    private void blockstateFile(final DeferredBlock<? extends Block> block) {
        final String name = block.getId().getPath();
        try {
            final var file = ModList.get().getModFileById("colony_seasons").getFile();
            final Path path = file.findResource("assets", "colony_seasons", "blockstates", name + ".json");
            if (!Files.exists(path)) {
                check(name + ": blockstate file", false, "not in the jar");
                return;
            }
            final JsonObject variants = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("variants");
            final List<BlockState> all = block.get().getStateDefinition().getPossibleStates();
            int uncovered = 0;
            for (final BlockState state : all) {
                uncovered += variants.keySet().stream().anyMatch(k -> matches(state, k)) ? 0 : 1;
            }
            int badKeys = 0;
            final Set<String> models = new HashSet<>();
            int missing = 0;
            for (final Map.Entry<String, JsonElement> e : variants.entrySet()) {
                badKeys += all.stream().anyMatch(s -> matches(s, e.getKey())) ? 0 : 1;
                final String ref = e.getValue().getAsJsonObject().get("model").getAsString();
                if (models.add(ref) && !Files.exists(file.findResource(("assets/colony_seasons/models/" + ref.substring(ref.indexOf(':') + 1) + ".json").split("/")))) {
                    missing++;
                }
            }
            check(name + ": blockstate file", uncovered == 0 && badKeys == 0 && missing == 0,
                    all.size() + " states, " + variants.size() + " variants, " + models.size() + " models"
                            + (uncovered + badKeys + missing == 0 ? "" : "; uncovered " + uncovered + ", stray " + badKeys + ", missing " + missing));
        } catch (final java.io.IOException e) {
            check(name + ": blockstate file", false, e.toString());
        }
    }

    private static boolean matches(final BlockState state, final String key) {
        for (final String kv : key.split(",")) {
            final String[] p = kv.split("=");
            final Property<?> property = state.getBlock().getStateDefinition().getProperty(p[0]);
            if (property == null || !valueName(state, property).equals(p[1])) {
                return false;
            }
        }
        return true;
    }

    private static <T extends Comparable<T>> String valueName(final BlockState state, final Property<T> property) {
        return property.getName(state.getValue(property));
    }
}
