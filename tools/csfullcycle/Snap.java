package me.lovkar.seasons.csfullcycle;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The first snapshot of the full-cycle probe: every cell of one box (the claim's buildings, the square, two blocks of
 * margin) as block states, saved in the world folder as gzip NBT (a palette and one index per cell), so it survives the
 * restarts of {@code tools/restart.sh}.
 *
 * <p>Comparison is by block state, after {@link #living} has set the properties that the colony's life changes back to
 * their resting value. Only these are normalised: OPEN and POWERED on doors, fence gates and trapdoors (colonists walk
 * through them); OCCUPIED on beds (colonists sleep in them); LIT on blocks that are not colony_seasons blocks (a
 * furnace or a campfire of a pasted hut). Our own blocks' LIT is never normalised: the tree's lights must go back
 * exactly as they were (gone).</p>
 */
final class Snap {
    final BlockPos min;
    final BlockPos max;
    private final List<BlockState> palette;
    private final int[] cells;

    private Snap(final BlockPos min, final BlockPos max, final List<BlockState> palette, final int[] cells) {
        this.min = min.immutable();
        this.max = max.immutable();
        this.palette = palette;
        this.cells = cells;
    }

    int sizeX() {
        return max.getX() - min.getX() + 1;
    }

    int sizeY() {
        return max.getY() - min.getY() + 1;
    }

    int sizeZ() {
        return max.getZ() - min.getZ() + 1;
    }

    int size() {
        return cells.length;
    }

    boolean contains(final BlockPos pos) {
        return pos.getX() >= min.getX() && pos.getX() <= max.getX() && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
    }

    private int index(final int x, final int y, final int z) {
        return ((y - min.getY()) * sizeZ() + (z - min.getZ())) * sizeX() + (x - min.getX());
    }

    /** The recorded state of a cell (normalised), or {@code null} outside the box. */
    BlockState at(final BlockPos pos) {
        return contains(pos) ? palette.get(cells[index(pos.getX(), pos.getY(), pos.getZ())]) : null;
    }

    /** The resting value of the properties the colony's life changes (see the class comment). */
    static BlockState living(final BlockState state) {
        BlockState s = state;
        final Block block = s.getBlock();
        if (block instanceof DoorBlock || block instanceof FenceGateBlock || block instanceof TrapDoorBlock) {
            if (s.hasProperty(BlockStateProperties.OPEN)) {
                s = s.setValue(BlockStateProperties.OPEN, false);
            }
            if (s.hasProperty(BlockStateProperties.POWERED)) {
                s = s.setValue(BlockStateProperties.POWERED, false);
            }
        }
        if (block instanceof BedBlock && s.hasProperty(BedBlock.OCCUPIED)) {
            s = s.setValue(BedBlock.OCCUPIED, false);
        }
        if (s.hasProperty(BlockStateProperties.LIT) && !"colony_seasons".equals(BuiltInRegistries.BLOCK.getKey(block).getNamespace())) {
            s = s.setValue(BlockStateProperties.LIT, false);
        }
        return s;
    }

    static Snap take(final ServerLevel level, final BlockPos min, final BlockPos max) {
        final List<BlockState> palette = new ArrayList<>();
        final Map<BlockState, Integer> index = new HashMap<>();
        final int sx = max.getX() - min.getX() + 1;
        final int sy = max.getY() - min.getY() + 1;
        final int sz = max.getZ() - min.getZ() + 1;
        final int[] cells = new int[sx * sy * sz];
        final BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        int i = 0;
        for (int y = min.getY(); y <= max.getY(); y++) {
            for (int z = min.getZ(); z <= max.getZ(); z++) {
                for (int x = min.getX(); x <= max.getX(); x++) {
                    final BlockState state = living(level.getBlockState(at.set(x, y, z)));
                    Integer p = index.get(state);
                    if (p == null) {
                        p = palette.size();
                        palette.add(state);
                        index.put(state, p);
                    }
                    cells[i++] = p;
                }
            }
        }
        return new Snap(min, max, palette, cells);
    }

    /**
     * The cells whose normalised state differs from the snapshot now, in box order; {@code keep} decides which are
     * reported at all, and at most {@code limit} are returned ({@code total[0]} gets the full count).
     */
    List<BlockPos> diff(final ServerLevel level, final Predicate<BlockPos> keep, final int limit, final int[] total) {
        final List<BlockPos> out = new ArrayList<>();
        final BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        int i = 0;
        int n = 0;
        for (int y = min.getY(); y <= max.getY(); y++) {
            for (int z = min.getZ(); z <= max.getZ(); z++) {
                for (int x = min.getX(); x <= max.getX(); x++) {
                    final BlockState want = palette.get(cells[i++]);
                    at.set(x, y, z);
                    if (living(level.getBlockState(at)) != want && keep.test(at)) {
                        n++;
                        if (out.size() < limit) {
                            out.add(at.immutable());
                        }
                    }
                }
            }
        }
        if (total != null) {
            total[0] = n;
        }
        return out;
    }

    void save(final Path file) throws IOException {
        final CompoundTag root = new CompoundTag();
        root.putLong("min", min.asLong());
        root.putLong("max", max.asLong());
        final ListTag p = new ListTag();
        for (final BlockState state : palette) {
            p.add(NbtUtils.writeBlockState(state));
        }
        root.put("palette", p);
        root.putIntArray("cells", cells);
        NbtIo.writeCompressed(root, file);
    }

    static Snap load(final Path file, final HolderGetter<Block> blocks) throws IOException {
        final CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        final ListTag p = root.getList("palette", 10);
        final List<BlockState> palette = new ArrayList<>();
        for (int i = 0; i < p.size(); i++) {
            palette.add(NbtUtils.readBlockState(blocks, p.getCompound(i)));
        }
        return new Snap(BlockPos.of(root.getLong("min")), BlockPos.of(root.getLong("max")), palette, root.getIntArray("cells"));
    }
}
