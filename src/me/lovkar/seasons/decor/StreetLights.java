package me.lovkar.seasons.decor;

import com.ldtteam.structurize.api.RotationMirror;
import com.minecolonies.api.colony.IColony;
import me.lovkar.seasons.SeasonsConfig;
import me.lovkar.seasons.block.SeasonsBlocks;
import me.lovkar.seasons.block.StreetLightsBlock;
import me.lovkar.seasons.calendar.CalendarManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.WallSide;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Thin street lights on fences (0.2.0, Q11; the owner's "a setting" for streets, docs/IDEAS.md: automatic street
 * decoration only as a switch, off by default, and thin pieces without collision only). With {@code decorator.streetLights}
 * on and the Workshop's "lights" kind on, in a decoration window the Decorator drapes {@code street_lights} over the top
 * of the fences and walls that stand beside the colony's path blocks ({@code #minecolonies:pathblocks}), about every third
 * post, at most {@link #MAX_LIGHTS} per colony, as one ledger group at the colony's centre ({@link DecorLedger.Kind#LIGHTS})
 * that is taken down after the window like any other. Every light goes through {@link DecorPlacer#place} with an owner
 * that never may collide, so a state with collision is refused there.
 *
 * <p>A cell qualifies when it is air, loaded, in the colony's claim, outside every building's box, directly above a
 * fence or wall that is the top of a column of at most {@link #MAX_FENCE} such blocks, and a path block lies beside the
 * column's ground (the 8 blocks round it, one up to one down). The colony's loaded, claimed chunks are looked at in a
 * spiral from its centre, {@link #CHUNKS_PER_CALL} at a time (a cursor in memory); no chunk is ever loaded for it.</p>
 */
public final class StreetLights {

    public static final int MAX_LIGHTS = 48;
    /** Fence or wall blocks in one column at most (a taller wall is a wall of a building, not a street's fence). */
    public static final int MAX_FENCE = 3;
    /** Chunks round the colony's centre that are looked at (a square of this radius, in a spiral from the centre). */
    public static final int RADIUS_CHUNKS = 8;
    /** Loaded chunks looked at per call. */
    public static final int CHUNKS_PER_CALL = 6;
    /** One post in this many gets lights (by its position, so a straight fence gets every third). */
    public static final int SPACING = 3;
    public static final TagKey<Block> PATHS = BlockTags.create(ResourceLocation.fromNamespaceAndPath("minecolonies", "pathblocks"));
    /** The piece key of the lights' group. */
    public static final String PIECE = "street_lights";

    private static final List<int[]> SPIRAL = new ArrayList<>();

    static {
        for (int dx = -RADIUS_CHUNKS; dx <= RADIUS_CHUNKS; dx++) {
            for (int dz = -RADIUS_CHUNKS; dz <= RADIUS_CHUNKS; dz++) {
                SPIRAL.add(new int[] {dx, dz});
            }
        }
        SPIRAL.sort(Comparator.comparingInt((int[] o) -> o[0] * o[0] + o[1] * o[1]).thenComparingInt(o -> o[0]).thenComparingInt(o -> o[1]));
    }

    /** One colony's scan, in memory only: where the cursor is, what it found, whether a whole pass is done. */
    private static final class Scan {
        int cursor;
        boolean complete;
        long idleDay = Long.MIN_VALUE;
        final Set<BlockPos> found = new LinkedHashSet<>();
    }

    private record Key(ResourceKey<Level> dimension, int colony) {
    }

    private static final Map<Key, Scan> SCANS = new HashMap<>();

    private StreetLights() {
    }

    /** Forgets every scan (the server stopped). */
    public static void clear() {
        SCANS.clear();
    }

    private static Scan scan(final ServerLevel level, final int colony) {
        return SCANS.computeIfAbsent(new Key(level.dimension(), colony), k -> new Scan());
    }

    /** The colony's lights group's key. */
    public static DecorLedger.Owner owner(final IColony colony) {
        return DecorLedger.Owner.lights(colony.getCenter());
    }

    // ---------------------------------------------------------------- choosing

    /**
     * The lights as a place to work at now, or {@code null}: only with the switch on, the hut's lights kind on and a
     * decoration window open, while the group is not finished for this window and was not left for today, and when the
     * scan (one more bounded step now) has found a cell that is open. A whole pass that finds nothing open marks the
     * group finished (it is tended once a game day from then on).
     */
    public static DecorWork.Target next(final ServerLevel level, final IColony colony, final DecorLedger ledger, final DecorKinds kinds, final long day) {
        if (!SeasonsConfig.streetLights() || !kinds.lights() || !DecorPhase.windowOpen(CalendarManager.current())) {
            return null;
        }
        final int id = colony.getID();
        final DecorLedger.Owner owner = owner(colony);
        final DecorLedger.GroupView group = ledger.group(id, owner);
        if (group != null && group.decoratedDay() >= 0 || ledger.fruitless(id, owner, day) >= DecorWork.MAX_FRUITLESS_VISITS) {
            return null;
        }
        final Scan scan = scan(level, id);
        if (scan.idleDay == day) {
            return null;
        }
        final List<DoorFinder.Box> boxes = StreetMarks.buildingBoxes(colony);
        step(level, colony, scan, boxes);
        final List<BlockPos> open = open(level, ledger, id, scan, boxes, group == null ? 0 : group.size());
        if (open.isEmpty()) {
            if (scan.complete) {
                scan.idleDay = day;               // a whole pass found nothing open: not looked for again today
                if (group != null && !group.entries().isEmpty()) {
                    ledger.setDecorated(id, owner, day);   // every light it found is up: finished for this window, tended from now on
                    ledger.flush(level);
                }
            }
            return null;
        }
        return target(level, owner, open.get(0));
    }

    /** The lights' target for tending (the switch on), standing near the first light the group lists; {@code null} without one. */
    public static DecorWork.Target target(final ServerLevel level, final IColony colony, final DecorLedger ledger) {
        if (!SeasonsConfig.streetLights()) {
            return null;
        }
        final DecorLedger.Owner owner = owner(colony);
        final DecorLedger.GroupView group = ledger.group(colony.getID(), owner);
        final BlockPos at = group == null || group.entries().isEmpty() ? colony.getCenter() : group.entries().keySet().iterator().next();
        return target(level, owner, at);
    }

    private static DecorWork.Target target(final ServerLevel level, final DecorLedger.Owner owner, final BlockPos at) {
        return new DecorWork.Target(owner, new DoorFinder.Box(at, at, owner.pos()), null, StreetWork.stand(level, List.of(at), at), "",
                RotationMirror.NONE);
    }

    // ---------------------------------------------------------------- one visit

    /**
     * One visit: one more step of the scan, then every open cell it found goes up through {@link DecorPlacer#place} (free,
     * unlit), up to {@link #MAX_LIGHTS} in the group. The group is finished for this window once a whole pass of the scan
     * is done and nothing it found is open; the day's budget is counted once (the group is its own slot) when anything
     * was placed and he was not tending. With the hut's lights kind off ({@code placing} false) nothing goes up: what
     * stands is left as it is, as with every other kind's switch.
     */
    public static DecorWork.Outcome visit(final ServerLevel level, final int colony, final DecorWork.Target target, final long day,
                                          final DecorLedger ledger, final boolean tending, final boolean placing) {
        if (!SeasonsConfig.streetLights()) {
            return DecorWork.Outcome.of(0, 0, false, "street lights are switched off");
        }
        if (!placing) {
            return DecorWork.Outcome.of(0, 0, false, "the Workshop's lights are switched off");
        }
        final IColony owner = StreetMarks.colonyOf(level, colony);
        if (owner == null) {
            return DecorWork.Outcome.of(0, 0, false, "the colony is gone");
        }
        final DecorLedger.Owner key = target.owner();
        final Scan scan = scan(level, colony);
        final List<DoorFinder.Box> boxes = StreetMarks.buildingBoxes(owner);
        step(level, owner, scan, boxes);
        DecorLedger.GroupView group = ledger.group(colony, key);
        final List<BlockPos> open = open(level, ledger, colony, scan, boxes, group == null ? 0 : group.size());
        final List<DecorPlacer.Slot> slots = new ArrayList<>();
        for (final BlockPos cell : open) {
            slots.add(new DecorPlacer.Slot(cell, state(level.getBlockState(cell.below())), false));
        }
        final String festival = DecorWork.festival();
        int placed = 0;
        if (!slots.isEmpty()) {
            ledger.plan(colony, key, festival, 0, PIECE);
            placed = DecorPlacer.place(level, ledger, colony, key, festival, 0, slots, DecorPlacer.Stock.FREE).count();
        }
        final int left = slots.size() - placed;
        group = ledger.group(colony, key);
        if (group != null && group.entries().isEmpty()) {
            ledger.dropGroup(colony, key);
        } else if (group != null) {
            if (scan.complete && left == 0) {
                ledger.setDecorated(colony, key, day);
            }
            if (tending) {
                ledger.setTended(colony, key, day);
            }
        }
        if (!tending) {
            if (placed > 0) {
                ledger.clearFruitless(colony, key);
                ledger.charge(colony, key.budgetKey(), day);
            } else if (left > 0) {
                ledger.addFruitless(colony, key, day);
            }
        }
        ledger.flush(level);
        return DecorWork.Outcome.of(placed, left, scan.complete && left == 0,
                "street lights: " + placed + " placed, " + left + " still open" + (scan.complete ? "" : ", still looking"));
    }

    /** The state the lights take on {@code fence}: along the fence's run (its connections), unlit. */
    public static BlockState state(final BlockState fence) {
        return SeasonsBlocks.STREET_LIGHTS.get().defaultBlockState().setValue(StreetLightsBlock.AXIS, axis(fence));
    }

    /** The way a fence or a wall runs: x when it joins east or west, z when only north or south, x for a lone post. */
    static Direction.Axis axis(final BlockState fence) {
        if (fence.hasProperty(CrossCollisionBlock.EAST) && (fence.getValue(CrossCollisionBlock.EAST) || fence.getValue(CrossCollisionBlock.WEST))) {
            return Direction.Axis.X;
        }
        if (fence.hasProperty(CrossCollisionBlock.NORTH) && (fence.getValue(CrossCollisionBlock.NORTH) || fence.getValue(CrossCollisionBlock.SOUTH))) {
            return Direction.Axis.Z;
        }
        if (fence.hasProperty(WallBlock.EAST_WALL) && (fence.getValue(WallBlock.EAST_WALL) != WallSide.NONE || fence.getValue(WallBlock.WEST_WALL) != WallSide.NONE)) {
            return Direction.Axis.X;
        }
        if (fence.hasProperty(WallBlock.NORTH_WALL) && (fence.getValue(WallBlock.NORTH_WALL) != WallSide.NONE || fence.getValue(WallBlock.SOUTH_WALL) != WallSide.NONE)) {
            return Direction.Axis.Z;
        }
        return Direction.Axis.X;
    }

    // ---------------------------------------------------------------- the scan

    /** The found cells that still qualify and are not listed, in the order found, as many as the group has room for. */
    private static List<BlockPos> open(final ServerLevel level, final DecorLedger ledger, final int colony, final Scan scan,
                                       final List<DoorFinder.Box> boxes, final int have) {
        final List<BlockPos> out = new ArrayList<>();
        for (final BlockPos cell : scan.found) {
            if (have + out.size() >= MAX_LIGHTS) {
                break;
            }
            if (ledger.at(cell) == null && qualifies(level, colony, cell, boxes)) {
                out.add(cell);
            }
        }
        return out;
    }

    /** One bounded step: up to {@link #CHUNKS_PER_CALL} loaded chunks of the spiral; a pass that reached its end starts again. */
    private static void step(final ServerLevel level, final IColony colony, final Scan scan, final List<DoorFinder.Box> boxes) {
        if (scan.cursor >= SPIRAL.size()) {
            scan.cursor = 0;                          // the next pass (what it found stays: a cell that no longer qualifies is skipped)
        }
        final ChunkPos centre = new ChunkPos(colony.getCenter());
        int looked = 0;
        while (looked < CHUNKS_PER_CALL && scan.cursor < SPIRAL.size()) {
            final int[] at = SPIRAL.get(scan.cursor++);
            final int cx = centre.x + at[0];
            final int cz = centre.z + at[1];
            if (!level.hasChunk(cx, cz)) {
                continue;                             // never loaded for this
            }
            looked++;
            final BlockPos middle = new BlockPos(cx * 16 + 8, colony.getCenter().getY(), cz * 16 + 8);
            if (DecorPlacer.colonyAt(level, middle) != colony.getID()) {
                continue;
            }
            for (int x = cx * 16; x < cx * 16 + 16; x++) {
                for (int z = cz * 16; z < cz * 16 + 16; z++) {
                    if (Math.floorMod(x + z, SPACING) != 0) {
                        continue;
                    }
                    final BlockPos cell = column(level, x, z);
                    if (cell != null && scan.found.size() < MAX_LIGHTS * 4 && qualifies(level, colony.getID(), cell, boxes)) {
                        scan.found.add(cell);
                    }
                }
            }
        }
        if (scan.cursor >= SPIRAL.size()) {
            scan.complete = true;
        }
    }

    /** The air cell above the top of a fence column at (x, z) (from the heightmap), or {@code null}. */
    private static @Nullable BlockPos column(final ServerLevel level, final int x, final int z) {
        final int free = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        final BlockPos top = new BlockPos(x, free - 1, z);
        if (!StreetLightsBlock.carries(level.getBlockState(top))) {
            return null;
        }
        return top.above();
    }

    /**
     * True when {@code cell} can take street lights now: loaded and air, in the colony's claim, outside every building's
     * box, above a fence or wall column of at most {@link #MAX_FENCE} blocks whose ground has a path block beside it.
     */
    public static boolean qualifies(final ServerLevel level, final int colony, final BlockPos cell, final List<DoorFinder.Box> boxes) {
        if (!level.hasChunkAt(cell) || !level.getBlockState(cell).isAir() || DecorPlacer.colonyAt(level, cell) != colony
                || StreetMarks.inside(boxes, cell)) {
            return false;
        }
        BlockPos below = cell.below();
        int fence = 0;
        while (fence <= MAX_FENCE && level.hasChunkAt(below) && StreetLightsBlock.carries(level.getBlockState(below))) {
            fence++;
            below = below.below();
        }
        if (fence == 0 || fence > MAX_FENCE) {
            return false;
        }
        final BlockPos ground = below;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    final BlockPos next = ground.offset(dx, dy, dz);
                    if (level.hasChunkAt(next) && level.getBlockState(next).is(PATHS)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** For the probe: how many cells the colony's scan found, and whether a whole pass is done. */
    public static int[] scanned(final ServerLevel level, final int colony) {
        final Scan scan = SCANS.get(new Key(level.dimension(), colony));
        return scan == null ? new int[] {0, 0} : new int[] {scan.found.size(), scan.complete ? 1 : 0};
    }
}
