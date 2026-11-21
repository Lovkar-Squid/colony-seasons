package me.lovkar.seasons.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * A piece of the Decorator's room dressing in his Workshop's storeroom (S10g, docs/storage-look-design.md, variant A):
 * the {@link StorageBoxBlock storage box} and the {@link StorageCoilBlock coil}, standing on a barrel.
 *
 * <p>Only the Decorator places them (S10g2), for free: they have no item, no recipe and no loot table (the
 * registration gives them {@code noLootTable()}, so they drop nothing), they are not in the creative tab, and pick-block
 * gives nothing. Like the wall decorations they have no collision (design 3.2: nothing with collision is placed
 * automatically, and the walk path stays clear), break at once and are destroyed by a piston. {@code facing} is the way
 * the piece looks (a box's label is on its front). It stands like a carpet: on any block below it, and it breaks (with
 * nothing dropped) when that block goes.</p>
 *
 * <p>Every block the Decorator places tells the decoration ledger about every change from {@code onRemove} and passes
 * its drops through {@code DecorDrops.filter} (tools/check_decor.py); these do too, so S10g2 places them under the same
 * rules.</p>
 */
public abstract class StoragePieceBlock extends HorizontalDirectionalBlock {
    protected StoragePieceBlock(final Properties properties) {
        super(properties);
    }

    /** Tells the decoration ledger about every change of this block on the server (design 3.3: anything else forgotten). */
    @Override
    protected void onRemove(final @NotNull BlockState state, final @NotNull Level level, final @NotNull BlockPos pos,
                            final @NotNull BlockState now, final boolean moving) {
        super.onRemove(state, level, pos, now, moving);
        me.lovkar.seasons.decor.DecorLedger.blockChanged(level, pos, state, now);
    }

    /** Nothing (no loot table), through the same filter as every other block the Decorator places. */
    @Override
    protected @NotNull List<ItemStack> getDrops(final @NotNull BlockState state, final LootParams.@NotNull Builder params) {
        return me.lovkar.seasons.decor.DecorDrops.filter(state, params, super.getDrops(state, params));
    }

    /** Stands on whatever block is below it, as a carpet does. */
    @Override
    protected boolean canSurvive(final @NotNull BlockState state, final @NotNull LevelReader level, final @NotNull BlockPos pos) {
        return !level.isEmptyBlock(pos.below());
    }

    /** Breaks when the block it stands on goes. */
    @Override
    protected @NotNull BlockState updateShape(final @NotNull BlockState state, final @NotNull Direction direction,
                                              final @NotNull BlockState neighbour, final @NotNull LevelAccessor level,
                                              final @NotNull BlockPos pos, final @NotNull BlockPos neighbourPos) {
        if (direction == Direction.DOWN && !state.canSurvive(level, pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, direction, neighbour, level, pos, neighbourPos);
    }

    /** Pick-block gives nothing: the block has no item. */
    @Override
    public @NotNull ItemStack getCloneItemStack(final @NotNull LevelReader level, final @NotNull BlockPos pos, final @NotNull BlockState state) {
        return ItemStack.EMPTY;
    }

    /** The outline both pieces share in every facing (the box's walls, the coil's rings); there is no collision. */
    protected static net.minecraft.world.phys.shapes.VoxelShape outline(final double height) {
        return Block.box(1, 0, 1, 15, height, 15);
    }
}
