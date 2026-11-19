package me.lovkar.seasons.decor;

import com.minecolonies.api.colony.buildings.IBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Tuple;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds a building's doors (design 3.4 steps 1 and 2). MineColonies keeps no entrance list, so the building's box
 * ({@link IBuilding#getCorners()}) is scanned for the lower halves of blocks in {@code #minecraft:doors}.
 *
 * <p>The two sides of a door are its {@code facing} and the opposite way. The outside is the side whose cell one
 * block out has more sky light; on a tie, the side farther from the hut block. Two lower halves side by side with
 * the same facing are one double door. A door counts only when its outside gets at least {@link #MIN_OUTSIDE_SKY}
 * sky light, so the doors between two rooms of a house are not taken for its front door. The main door comes first:
 * the most sky light outside, then the lowest, then the nearest to the hut block.</p>
 */
public final class DoorFinder {

    /** The least sky light the outside of a door needs (a room behind a window gets less than an open street). */
    public static final int MIN_OUTSIDE_SKY = 8;
    /** A box larger than this is not scanned (no building is that big; a broken corner pair could be). */
    public static final long MAX_BOX_CELLS = 600_000L;

    /** A building's box: two corners (inclusive) and the hut block. */
    public record Box(BlockPos min, BlockPos max, BlockPos hut) {
        public Box {
            final BlockPos lo = new BlockPos(Math.min(min.getX(), max.getX()), Math.min(min.getY(), max.getY()), Math.min(min.getZ(), max.getZ()));
            final BlockPos hi = new BlockPos(Math.max(min.getX(), max.getX()), Math.max(min.getY(), max.getY()), Math.max(min.getZ(), max.getZ()));
            min = lo;
            max = hi;
            hut = hut.immutable();
        }

        /** The box of a MineColonies building: its corners and its hut position. */
        public static Box of(final IBuilding building) {
            final Tuple<BlockPos, BlockPos> corners = building.getCorners();
            if (corners == null || corners.getA() == null || corners.getB() == null) {
                return new Box(building.getPosition(), building.getPosition(), building.getPosition());
            }
            return new Box(corners.getA(), corners.getB(), building.getPosition());
        }

        public boolean contains(final BlockPos pos) {
            return pos.getX() >= min.getX() && pos.getX() <= max.getX() && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                    && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
        }

        public long cells() {
            return (long) (max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1) * (max.getZ() - min.getZ() + 1);
        }

        /** Every chunk the box touches is loaded. */
        public boolean loaded(final Level level) {
            for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++) {
                for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++) {
                    if (!level.hasChunk(cx, cz)) {
                        return false;
                    }
                }
            }
            return true;
        }

        /** The box grown by {@code n} on every side. */
        public Box grown(final int n) {
            return new Box(min.offset(-n, -n, -n), max.offset(n, n, n), hut);
        }
    }

    /**
     * One door. {@code lower} is the lower half (of the leaf with the lower coordinate along the wall for a double
     * door), {@code outside} the way out, {@code sky} the sky light one block out and {@code insideSky} on the other side.
     */
    public record Door(BlockPos lower, Direction outside, int sky, int insideSky, boolean pair) {

        /** The wall's direction that points the positive way (east or south); a double door's other leaf is that way. */
        public Direction along() {
            return positive(outside.getClockWise());
        }
    }

    private DoorFinder() {
    }

    /**
     * The building's doors, main door first; an empty list when it has none; {@code null} when part of the box is in
     * a chunk that is not loaded (look again later).
     */
    public static List<Door> find(final Level level, final Box box) {
        if (!box.loaded(level)) {
            return null;
        }
        final List<Door> doors = new ArrayList<>();
        if (box.cells() > MAX_BOX_CELLS) {
            return doors;
        }
        final List<BlockPos> lowers = new ArrayList<>();
        final BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int y = box.min().getY(); y <= box.max().getY(); y++) {
            for (int z = box.min().getZ(); z <= box.max().getZ(); z++) {
                for (int x = box.min().getX(); x <= box.max().getX(); x++) {
                    at.set(x, y, z);
                    final BlockState state = level.getBlockState(at);
                    if (isLowerDoor(state) && isUpperOf(level.getBlockState(at.above()), state)) {
                        lowers.add(at.immutable());
                    }
                }
            }
        }
        final Set<BlockPos> taken = new HashSet<>();
        for (final BlockPos lower : lowers) {
            if (taken.contains(lower)) {
                continue;
            }
            final BlockState state = level.getBlockState(lower);
            final Direction facing = state.getValue(DoorBlock.FACING);
            // the wall runs along the facing's clockwise axis; the leaf with the lower coordinate along it is "the" door
            final Direction along = positive(facing.getClockWise());
            BlockPos chosen = lower;
            boolean pair = false;
            final BlockPos before = lower.relative(along.getOpposite());
            final BlockPos after = lower.relative(along);
            if (lowers.contains(before) && !taken.contains(before) && sameFacing(level, before, facing)) {
                chosen = before;
                pair = true;
                taken.add(before);
            } else if (lowers.contains(after) && !taken.contains(after) && sameFacing(level, after, facing)) {
                pair = true;
                taken.add(after);
            }
            taken.add(lower);
            final Door door = door(level, box, chosen, facing, pair);
            if (door.sky() >= MIN_OUTSIDE_SKY) {
                doors.add(door);
            }
        }
        doors.sort(Comparator.comparingInt((Door d) -> -d.sky())
                .thenComparingInt(d -> d.lower().getY())
                .thenComparingDouble(d -> d.lower().distSqr(box.hut()))
                .thenComparing(Door::lower));
        return doors;
    }

    private static Door door(final Level level, final Box box, final BlockPos lower, final Direction facing, final boolean pair) {
        final int front = level.getBrightness(LightLayer.SKY, lower.relative(facing));
        final int back = level.getBrightness(LightLayer.SKY, lower.relative(facing.getOpposite()));
        Direction outside;
        if (front != back) {
            outside = front > back ? facing : facing.getOpposite();
        } else {
            final double f = lower.relative(facing).distSqr(box.hut());
            final double b = lower.relative(facing.getOpposite()).distSqr(box.hut());
            outside = f >= b ? facing : facing.getOpposite();
        }
        final int sky = outside == facing ? front : back;
        final int inside = outside == facing ? back : front;
        return new Door(lower, outside, sky, inside, pair);
    }

    /** The axis direction that points the positive way (east or south). */
    static Direction positive(final Direction direction) {
        return direction.getAxisDirection() == Direction.AxisDirection.POSITIVE ? direction : direction.getOpposite();
    }

    private static boolean sameFacing(final Level level, final BlockPos pos, final Direction facing) {
        final BlockState state = level.getBlockState(pos);
        return isLowerDoor(state) && state.getValue(DoorBlock.FACING) == facing;
    }

    static boolean isLowerDoor(final BlockState state) {
        return state.is(BlockTags.DOORS) && state.hasProperty(DoorBlock.HALF) && state.hasProperty(DoorBlock.FACING)
                && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER;
    }

    private static boolean isUpperOf(final BlockState above, final BlockState lower) {
        return above.getBlock() == lower.getBlock() && above.hasProperty(DoorBlock.HALF) && above.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER;
    }
}
