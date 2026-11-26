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
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The festive lantern post (0.2.0, Q11, the owner's pick of the pictures round: post B, the crook with a garland): a
 * blackstone post two blocks tall wound with a garland, its crook at the top reaching out along {@code facing} with a
 * lantern hanging from it. Two halves ({@code half}, the way the large star and doors do it); {@code lit} on either half
 * is copied to the other: the lantern shines when lit, and so do the small bulbs in the garland round the lower half. A
 * player's post comes on lit; the Decorator puts his up unlit, and the tree's lighting (or a hand) switches it on. Only
 * the lower half drops the item. Not "solid" ({@code forceSolidOff}): a dirt path under it stays a dirt path.
 */
public class LanternPostBlock extends Block {
    public static final MapCodec<LanternPostBlock> CODEC = simpleCodec(LanternPostBlock::new);
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    private static final VoxelShape LOWER = Shapes.or(Block.box(4, 0, 4, 12, 3, 12), Block.box(6, 3, 6, 10, 16, 10));
    private static final VoxelShape UPPER = Block.box(6, 0, 6, 10, 16, 10);

    public LanternPostBlock(final Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(HALF, DoubleBlockHalf.LOWER).setValue(FACING, Direction.NORTH)
                .setValue(Lights.LIT, false));
    }

    /** The light of one state: the lantern (upper half) and the garland's bulbs (lower half) when lit, nothing when not. */
    public static int light(final BlockState state) {
        if (!state.getValue(Lights.LIT)) {
            return 0;
        }
        return state.getValue(HALF) == DoubleBlockHalf.UPPER ? Lights.LANTERN_POST : Lights.LANTERN_POST_BULBS;
    }

    @Override
    public @NotNull MapCodec<? extends Block> codec() {
        return CODEC;
    }

    /** Tells the decoration ledger about every change of this block on the server (design 3.3: a light switched is followed, anything else forgotten). */
    @Override
    protected void onRemove(final @NotNull BlockState state, final @NotNull Level level, final @NotNull BlockPos pos,
                            final @NotNull BlockState now, final boolean moving) {
        super.onRemove(state, level, pos, now, moving);
        me.lovkar.seasons.decor.DecorLedger.blockChanged(level, pos, state, now);
    }

    /** What it drops: its loot table (the lower half only), except when the Decorator put it up (for free; {@link me.lovkar.seasons.decor.DecorDrops}). */
    @Override
    protected @NotNull List<ItemStack> getDrops(final @NotNull BlockState state, final LootParams.@NotNull Builder params) {
        return me.lovkar.seasons.decor.DecorDrops.filter(state, params, super.getDrops(state, params));
    }

    @Override
    protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HALF, FACING, Lights.LIT);
    }

    @Override
    protected @NotNull VoxelShape getShape(final @NotNull BlockState state, final @NotNull BlockGetter level,
                                           final @NotNull BlockPos pos, final @NotNull CollisionContext context) {
        return state.getValue(HALF) == DoubleBlockHalf.LOWER ? LOWER : UPPER;
    }

    /** Room for the upper half is needed; the crook reaches towards the player; a player's post comes on lit. */
    @Override
    public @Nullable BlockState getStateForPlacement(final BlockPlaceContext context) {
        final BlockPos pos = context.getClickedPos();
        final Level level = context.getLevel();
        if (pos.getY() < level.getMaxBuildHeight() - 1 && level.getBlockState(pos.above()).canBeReplaced(context)) {
            return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite()).setValue(Lights.LIT, true);
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
