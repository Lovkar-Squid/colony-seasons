package me.lovkar.seasons.decor;

import me.lovkar.seasons.block.SeasonsBlocks;
import me.lovkar.seasons.block.WallDecorationBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Where the decorations go on one building (design 3.4). Pure: it reads the world and the ledger and never writes.
 *
 * <ol>
 *   <li>The doors come from {@link DoorFinder}: the main door, and at hut level 5 the second one too.</li>
 *   <li>The wreath goes on the wall above the door, in the cell one out from it: two up, else three up, else beside the
 *       door's upper half ({@link #WREATH_ROWS}, {@link #BESIDE_ROW}); the wall needs a sturdy face towards the outside
 *       and the cell must pass the cell rule.</li>
 *   <li>The garland runs left and right of the wreath in the same row, the lights in the row above (wreath column
 *       included), cell by cell; a walk stops at the first cell that fails the cell rule, so a torch, a window, the
 *       player's own wreath, the corner of the house or the edge of the claim ends it, and it is never touched. A cell
 *       that is already ours counts and the walk goes on.</li>
 *   <li>A building gets either the lantern string or the fairy lights, picked from its position.</li>
 * </ol>
 * No sturdy anchor at its doors: no slot, and the building gets nothing. No door at all (no door whose outside has
 * {@link DoorFinder#MIN_OUTSIDE_SKY} sky light, so a house whose only doors are inside counts as doorless): one wreath
 * on the outside wall nearest the hut block, by the same air and sturdy-face rules, and nothing when there is no such
 * spot ({@link #planDoorless}, the owner's answer of 20 Nov 2026).
 */
public final class DecorPlanner {

    /**
     * The rows a doorless building's wreath may hang in, above the hut block, in the order they are preferred at the
     * same distance: two up, three up, one up (as {@link #WREATH_ROWS}, then {@link #BESIDE_ROW}).
     */
    public static final List<Integer> DOORLESS_ROWS = List.of(2, 3, 1);

    /**
     * How far above a door's lower half the wreath's wall may be, tried in order (the design's door + 2 first). Measured
     * on real huts of six style packs (csdecorgate): many doors have stairs, a trapdoor or a slab as their lintel, with a
     * sturdy wall one higher, so door + 3 was added.
     */
    public static final List<Integer> WREATH_ROWS = List.of(2, 3);
    /**
     * Tried after the rows above the door: the wall beside the door's upper half (door + 1, left, then right), for doors
     * whose lintel and the wall above it hold no free spot (a porch roof, a sign, a lantern).
     */
    public static final int BESIDE_ROW = 1;

    /** What one hut level puts on a building (design 3.4's table). Each side is also capped by the config's maxPerSide. */
    public record Amounts(boolean wreath, int garland, int lights, boolean secondDoor) {
    }

    /**
     * One plan: the slots, and what the probe reports per building (doors found, the anchor row used, how many of
     * each kind; ours counts what is already up).
     */
    public record Plan(List<DecorPlacer.Slot> slots, int doors, int row, int wreaths, int garlands, int lights, int ours,
                       boolean loaded) {
        public static final Plan UNLOADED = new Plan(List.of(), 0, 0, 0, 0, 0, 0, false);
    }

    private DecorPlanner() {
    }

    public static Amounts amounts(final int hutLevel, final int maxPerSide) {
        final int cap = Math.max(0, maxPerSide);
        return switch (Math.max(1, Math.min(5, hutLevel))) {
            case 1 -> new Amounts(true, 0, 0, false);
            case 2 -> new Amounts(true, Math.min(cap, 2), 0, false);
            case 3 -> new Amounts(true, Math.min(cap, 3), Math.min(cap, 2), false);
            case 4 -> new Amounts(true, Math.min(cap, 4), Math.min(cap, 4), false);
            default -> new Amounts(true, Math.min(cap, 6), Math.min(cap, 6), true);
        };
    }

    /** The amounts with the switched-off kinds taken out. */
    public static Amounts limited(final Amounts amounts, final DecorKinds kinds) {
        if (kinds == null) {
            return amounts;
        }
        return new Amounts(amounts.wreath() && kinds.wreaths(), kinds.garlands() ? amounts.garland() : 0,
                kinds.lights() ? amounts.lights() : 0, amounts.secondDoor());
    }

    /** The light a building gets, the same for it every year: lantern string or fairy lights, from its position. */
    public static BlockState lightsFor(final BlockPos buildingPos) {
        final int pick = Math.floorMod(31 * (31 * buildingPos.getX() + buildingPos.getY()) + buildingPos.getZ(), 2);
        return pick == 0 ? SeasonsBlocks.LANTERN_STRING.get().defaultBlockState() : SeasonsBlocks.FAIRY_LIGHTS.get().defaultBlockState();
    }

    /**
     * The slots for one building.
     *
     * @param costs    whether each decoration costs an item (decorator.needsMaterials)
     */
    public static Plan plan(final ServerLevel level, final DoorFinder.Box box, final int hutLevel, final int maxPerSide,
                            final DecorLedger ledger, final int colony, final DecorLedger.Owner owner, final boolean costs) {
        return plan(level, box, hutLevel, maxPerSide, ledger, colony, owner, costs, DecorKinds.ALL);
    }

    /** {@link #plan} with the kinds the hut's settings allow: a switched-off kind gets no slot. */
    public static Plan plan(final ServerLevel level, final DoorFinder.Box box, final int hutLevel, final int maxPerSide,
                            final DecorLedger ledger, final int colony, final DecorLedger.Owner owner, final boolean costs,
                            final DecorKinds kinds) {
        final List<DoorFinder.Door> doors = DoorFinder.find(level, box);
        if (doors == null) {
            return Plan.UNLOADED;
        }
        final Amounts amounts = limited(amounts(hutLevel, maxPerSide), kinds);
        final BlockState light = lightsFor(owner.pos());
        final List<DecorPlacer.Slot> slots = new ArrayList<>();
        final int[] counts = new int[4];          // wreaths, garlands, lights, ours
        int row = 0;
        final int doorsToDo = amounts.secondDoor() ? 2 : 1;
        for (int d = 0; d < Math.min(doorsToDo, doors.size()); d++) {
            final int used = planDoor(level, doors.get(d), amounts, light, ledger, colony, owner, costs, slots, counts);
            if (d == 0) {
                row = used;
            }
        }
        if (doors.isEmpty() && amounts.wreath()) {
            row = planDoorless(level, box, ledger, colony, owner, costs, slots, counts);
        }
        return new Plan(List.copyOf(slots), doors.size(), row, counts[0], counts[1], counts[2], counts[3], true);
    }

    /** One spot a doorless building's wreath could take: the wall, the way out, the cell in front, its row and distance. */
    private record WallSpot(BlockPos wall, Direction out, BlockPos cell, int row, int rank, long distance) {
    }

    /**
     * A building with no door (the owner's answer of 20 Nov 2026): one wreath on the outside wall nearest the hut block,
     * no garland and no lights. A spot is a wall block W inside the box, one to three blocks above the hut block
     * ({@link #DOORLESS_ROWS}), whose face towards a horizontal way out is sturdy, with the cell C in front of it loaded
     * and outside by DoorFinder's rule (C has at least {@link DoorFinder#MIN_OUTSIDE_SKY} sky light and more than the
     * cell behind W). If the group already lists a wreath that stands as listed, or any spot's cell holds a wreath (the
     * player's own), no new one is planned (ours counts as ours). Otherwise the spot whose cell is nearest the hut block
     * (horizontally; then by row, then by position) and passes the cell rule gets the wreath. Returns the wreath's row
     * above the hut block, or 0 when there is none.
     */
    private static int planDoorless(final ServerLevel level, final DoorFinder.Box box, final DecorLedger ledger, final int colony,
                                    final DecorLedger.Owner owner, final boolean costs, final List<DecorPlacer.Slot> slots,
                                    final int[] counts) {
        if (box.cells() > DoorFinder.MAX_BOX_CELLS) {
            return 0;
        }
        final BlockPos hut = box.hut();
        final DecorLedger.GroupView group = ledger.group(colony, owner);
        if (group != null) {
            for (final Map.Entry<BlockPos, DecorLedger.Entry> e : group.entries().entrySet()) {
                final BlockPos pos = e.getKey();
                if (e.getValue().state().is(SeasonsBlocks.WREATH.get()) && level.hasChunkAt(pos)
                        && level.getBlockState(pos) == e.getValue().state()) {
                    counts[0]++;
                    counts[3]++;
                    return pos.getY() - hut.getY();
                }
            }
        }
        final List<WallSpot> spots = new ArrayList<>();
        final BlockPos.MutableBlockPos wall = new BlockPos.MutableBlockPos();
        for (int rank = 0; rank < DOORLESS_ROWS.size(); rank++) {
            final int y = hut.getY() + DOORLESS_ROWS.get(rank);
            if (y < box.min().getY() || y > box.max().getY()) {
                continue;
            }
            for (int z = box.min().getZ(); z <= box.max().getZ(); z++) {
                for (int x = box.min().getX(); x <= box.max().getX(); x++) {
                    wall.set(x, y, z);
                    final BlockState wallState = level.getBlockState(wall);
                    if (wallState.isAir()) {
                        continue;
                    }
                    for (final Direction out : Direction.Plane.HORIZONTAL) {
                        final BlockPos cell = wall.relative(out);
                        final BlockPos behind = wall.relative(out.getOpposite());
                        if (!level.hasChunkAt(cell) || !level.hasChunkAt(behind) || !wallState.isFaceSturdy(level, wall, out)) {
                            continue;
                        }
                        final BlockState there = level.getBlockState(cell);
                        if (!there.isAir() && !there.is(SeasonsBlocks.WREATH.get())) {
                            continue;
                        }
                        final int sky = level.getBrightness(LightLayer.SKY, cell);
                        if (sky < DoorFinder.MIN_OUTSIDE_SKY || sky <= level.getBrightness(LightLayer.SKY, behind)) {
                            continue;
                        }
                        final long dx = cell.getX() - hut.getX();
                        final long dz = cell.getZ() - hut.getZ();
                        spots.add(new WallSpot(wall.immutable(), out, cell.immutable(), DOORLESS_ROWS.get(rank), rank, dx * dx + dz * dz));
                    }
                }
            }
        }
        for (final WallSpot spot : spots) {
            final BlockState there = level.getBlockState(spot.cell());
            if (there.is(SeasonsBlocks.WREATH.get())) {
                if (ledger.isOurs(colony, owner, spot.cell(), there)) {
                    counts[0]++;
                    counts[3]++;
                }
                return spot.row();
            }
        }
        spots.sort(Comparator.comparingLong(WallSpot::distance).thenComparingInt(WallSpot::rank).thenComparing(WallSpot::cell));
        for (final WallSpot spot : spots) {
            final BlockState wreath = DecorPlacer.normalise(facing(SeasonsBlocks.WREATH.get().defaultBlockState(), spot.out()), spot.cell());
            if (DecorPlacer.refusal(level, ledger, colony, spot.cell(), wreath, false) == null) {
                slots.add(new DecorPlacer.Slot(spot.cell(), wreath, costs));
                counts[0]++;
                return spot.row();
            }
        }
        return 0;
    }

    /**
     * One door's slots; returns where the wreath went: 2 or 3 (that many above the door), 1 (beside the door's upper
     * half), or for no wreath the garland row's height (0 for nothing at all).
     *
     * <p>The wreath spots are tried in {@link #WREATH_ROWS} order, then {@link #BESIDE_ROW}: the first whose wall is
     * sturdy towards the outside and whose cell in front is free by the cell rule (or holds a wreath already, the
     * player's own: then no second one) takes the wreath. The garland and the lights go in that spot's row and the one
     * above; for a wreath beside the door (or none), in the first row above the door whose wall is sturdy.</p>
     */
    private static int planDoor(final ServerLevel level, final DoorFinder.Door door, final Amounts amounts, final BlockState light,
                                final DecorLedger ledger, final int colony, final DecorLedger.Owner owner, final boolean costs,
                                final List<DecorPlacer.Slot> slots, final int[] counts) {
        final Direction out = door.outside();
        final BlockState wreath = facing(SeasonsBlocks.WREATH.get().defaultBlockState(), out);
        final BlockState garland = facing(SeasonsBlocks.GARLAND.get().defaultBlockState(), out);
        final BlockState lights = facing(light, out);
        final List<BlockPos> spots = new ArrayList<>();
        for (final int up : WREATH_ROWS) {
            spots.add(door.lower().above(up));
        }
        final Direction along = door.along();
        spots.add(door.lower().relative(along.getOpposite()).above(BESIDE_ROW));
        spots.add(door.lower().relative(along, door.pair() ? 2 : 1).above(BESIDE_ROW));
        BlockPos wreathWall = null;
        boolean wreathThere = false;
        for (final BlockPos wall : spots) {
            if (!level.getBlockState(wall).isFaceSturdy(level, wall, out)) {
                continue;
            }
            final BlockPos cell = wall.relative(out);
            if (!level.hasChunkAt(cell)) {
                continue;                             // never read (and so load) a chunk that is not loaded
            }
            final BlockState there = level.getBlockState(cell);
            if (there.is(SeasonsBlocks.WREATH.get())) {
                wreathWall = wall;
                wreathThere = true;
                break;
            }
            if (DecorPlacer.refusal(level, ledger, colony, cell, DecorPlacer.normalise(wreath, cell), false) == null) {
                wreathWall = wall;
                break;
            }
        }
        BlockPos anchor = wreathWall != null && wreathWall.getY() > door.lower().getY() + BESIDE_ROW ? wreathWall : null;
        if (anchor == null) {
            for (final int up : WREATH_ROWS) {
                final BlockPos wall = door.lower().above(up);
                if (level.getBlockState(wall).isFaceSturdy(level, wall, out)) {
                    anchor = wall;
                    break;
                }
            }
        }
        if (wreathWall == null && anchor == null) {
            return 0;
        }
        if (amounts.wreath() && wreathWall != null) {
            if (wreathThere) {
                final BlockPos cell = wreathWall.relative(out);
                if (ledger.isOurs(colony, owner, cell, level.getBlockState(cell))) {
                    counts[3]++;
                    counts[0]++;
                }
            } else {
                cell(level, wreathWall.relative(out), wreath, ledger, colony, owner, costs, slots, counts, 0);
            }
        }
        if (anchor != null) {
            final Direction left = out.getCounterClockWise();
            final Direction right = out.getClockWise();
            for (final Direction side : List.of(left, right)) {
                for (int i = 1; i <= amounts.garland(); i++) {
                    if (!cell(level, anchor.relative(side, i).relative(out), garland, ledger, colony, owner, costs, slots, counts, 1)) {
                        break;
                    }
                }
            }
            if (amounts.lights() > 0) {
                final BlockPos lightsRow = anchor.above();
                if (cell(level, lightsRow.relative(out), lights, ledger, colony, owner, costs, slots, counts, 2)) {
                    for (final Direction side : List.of(left, right)) {
                        for (int i = 1; i <= amounts.lights(); i++) {
                            if (!cell(level, lightsRow.relative(side, i).relative(out), lights, ledger, colony, owner, costs, slots, counts, 2)) {
                                break;
                            }
                        }
                    }
                }
            }
        }
        if (wreathWall != null) {
            return wreathWall.getY() - door.lower().getY();
        }
        return anchor.getY() - door.lower().getY();
    }

    /**
     * One cell of a walk: ours already (the walk goes on), free by the cell rule (a slot, the walk goes on), or
     * neither (the walk stops). The wreath is a walk of one.
     */
    private static boolean cell(final ServerLevel level, final BlockPos pos, final BlockState state, final DecorLedger ledger,
                                final int colony, final DecorLedger.Owner owner, final boolean costs,
                                final List<DecorPlacer.Slot> slots, final int[] counts, final int kind) {
        if (!level.hasChunkAt(pos)) {
            return false;                             // the walk ends at a chunk that is not loaded (never loads it)
        }
        final BlockState wanted = DecorPlacer.normalise(state, pos);
        if (ledger.isOurs(colony, owner, pos, level.getBlockState(pos)) && level.getBlockState(pos).getBlock() == wanted.getBlock()) {
            counts[3]++;
            counts[kind]++;
            return true;
        }
        if (DecorPlacer.refusal(level, ledger, colony, pos, wanted, false) != null) {
            return false;
        }
        for (final DecorPlacer.Slot slot : slots) {
            if (slot.pos().equals(pos)) {
                return false;          // the second door's walk ran into the first one's
            }
        }
        slots.add(new DecorPlacer.Slot(pos, wanted, costs));
        counts[kind]++;
        return true;
    }

    private static BlockState facing(final BlockState state, final Direction out) {
        return state.setValue(WallDecorationBlock.FACING, out);
    }
}
