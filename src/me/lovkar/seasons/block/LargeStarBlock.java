package me.lovkar.seasons.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The large star of the grand tree: 24 px across, two blocks tall, as a lower and an upper half ({@code half}, the
 * way doors and tall flowers do it) so nothing can be built into its top. {@code lit} on either half is copied to
 * the other. Only the lower half drops the item (its loot table says so); breaking either half takes both.
 */
public class LargeStarBlock extends Block {
    public static final MapCodec<LargeStarBlock> CODEC = simpleCodec(LargeStarBlock::new);
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;
    private static final VoxelShape LOWER = Block.box(1, 0, 1, 15, 16, 15);
    private static final VoxelShape UPPER = Block.box(1, 0, 1, 15, 11, 15);

    public LargeStarBlock(final Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(HALF, DoubleBlockHalf.LOWER).setValue(Lights.LIT, false));
    }

    @Override
    public @NotNull MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HALF, Lights.LIT);
    }

    @Override
    protected @NotNull VoxelShape getShape(final @NotNull BlockState state, final @NotNull BlockGetter level,
                                           final @NotNull BlockPos pos, final @NotNull CollisionContext context) {
        return state.getValue(HALF) == DoubleBlockHalf.LOWER ? LOWER : UPPER;
    }

    /** Room for the upper half is needed; a player's star comes on lit. */
    @Override
    public @Nullable BlockState getStateForPlacement(final BlockPlaceContext context) {
        final BlockPos pos = context.getClickedPos();
        final Level level = context.getLevel();
        if (pos.getY() < level.getMaxBuildHeight() - 1 && level.getBlockState(pos.above()).canBeReplaced(context)) {
            return defaultBlockState().setValue(Lights.LIT, true);
        }
        return null;
    }

    @Override
    public void setPlacedBy(final @NotNull Level level, final @NotNull BlockPos pos, final @NotNull BlockState state,
                            final @Nullable LivingEntity placer, final @NotNull ItemStack stack) {
        level.setBlock(pos.above(), state.setValue(HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
    }

    /** A half without its partner goes; a partner's {@code lit} is copied. */
    @Override
    protected @NotNull BlockState updateShape(final @NotNull BlockState state, final @NotNull Direction direction,
                                              final @NotNull BlockState neighbour, final @NotNull LevelAccessor level,
                                              final @NotNull BlockPos pos, final @NotNull BlockPos neighbourPos) {
        final DoubleBlockHalf half = state.getValue(HALF);
        final Direction towardsPartner = half == DoubleBlockHalf.LOWER ? Direction.UP : Direction.DOWN;
        if (direction != towardsPartner) {
            return super.updateShape(state, direction, neighbour, level, pos, neighbourPos);
        }
        if (!neighbour.is(this) || neighbour.getValue(HALF) == half) {
            return Blocks.AIR.defaultBlockState();
        }
        return state.setValue(Lights.LIT, neighbour.getValue(Lights.LIT));
    }

    /** In creative, breaking the upper half must not let the lower half drop its item (vanilla tall plants). */
    @Override
    public @NotNull BlockState playerWillDestroy(final @NotNull Level level, final @NotNull BlockPos pos,
                                                 final @NotNull BlockState state, final @NotNull Player player) {
        if (!level.isClientSide && player.isCreative() && state.getValue(HALF) == DoubleBlockHalf.UPPER) {
            final BlockPos below = pos.below();
            final BlockState lower = level.getBlockState(below);
            if (lower.is(this) && lower.getValue(HALF) == DoubleBlockHalf.LOWER) {
                level.setBlock(below, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
                level.levelEvent(player, 2001, below, Block.getId(lower));
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected @NotNull InteractionResult useWithoutItem(final @NotNull BlockState state, final @NotNull Level level,
                                                        final @NotNull BlockPos pos, final @NotNull Player player,
                                                        final @NotNull BlockHitResult hit) {
        return Lights.switchByHand(state, level, pos, player);
    }
}
