package me.lovkar.seasons.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * A thin decoration hung on a wall face: the wreath, the garland and (through {@link LitWallDecorationBlock}) the
 * lantern string and the fairy lights.
 *
 * <p>{@code facing} is the way it looks out; the wall it hangs on is behind it. It has no collision (the
 * registration gives it {@code noCollission()}, so it never blocks a path or a door), breaks at once, and holds
 * only while the block behind it is solid in the sense wall signs and wall banners use: take that block away and
 * it pops off and drops as an item, like a ladder (one the Decorator put up for free drops nothing, S10d). Placement
 * follows the ladder: the face clicked, or else the nearest wall the player looks towards.</p>
 */
public class WallDecorationBlock extends HorizontalDirectionalBlock {
    public static final MapCodec<WallDecorationBlock> CODEC = simpleCodec(WallDecorationBlock::new);

    /** The outline: a 2 px plate against the wall (the plane and the 1 px reliefs in front of it). */
    private static final Map<Direction, VoxelShape> SHAPES = new EnumMap<>(Map.of(
            Direction.NORTH, Block.box(0, 0, 14, 16, 16, 16),
            Direction.SOUTH, Block.box(0, 0, 0, 16, 16, 2),
            Direction.EAST, Block.box(0, 0, 0, 2, 16, 16),
            Direction.WEST, Block.box(14, 0, 0, 16, 16, 16)));

    public WallDecorationBlock(final Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected @NotNull MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    /** Tells the decoration ledger about every change of this block on the server (design 3.3: a light switched is followed, anything else forgotten). */
    @Override
    protected void onRemove(final @NotNull BlockState state, final @NotNull Level level, final @NotNull BlockPos pos,
                            final @NotNull BlockState now, final boolean moving) {
        super.onRemove(state, level, pos, now, moving);
        me.lovkar.seasons.decor.DecorLedger.blockChanged(level, pos, state, now);
    }

    /**
     * What it drops: its loot table, except when the Decorator put it up for free, or it is the tree's gift pile or
     * firework crate (S10d, the owner's answer of 20 Nov 2026; see {@link me.lovkar.seasons.decor.DecorDrops}).
     */
    @Override
    protected @NotNull List<ItemStack> getDrops(final @NotNull BlockState state, final LootParams.@NotNull Builder params) {
        return me.lovkar.seasons.decor.DecorDrops.filter(state, params, super.getDrops(state, params));
    }

    @Override
    protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    protected @NotNull VoxelShape getShape(final @NotNull BlockState state, final @NotNull BlockGetter level,
                                           final @NotNull BlockPos pos, final @NotNull CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }

    /** The block behind (the wall) must be solid. */
    @Override
    protected boolean canSurvive(final @NotNull BlockState state, final @NotNull LevelReader level, final @NotNull BlockPos pos) {
        return level.getBlockState(pos.relative(state.getValue(FACING).getOpposite())).isSolid();
    }

    /** Pops off (and drops) when the wall behind it goes. */
    @Override
    protected @NotNull BlockState updateShape(final @NotNull BlockState state, final @NotNull Direction direction,
                                              final @NotNull BlockState neighbour, final @NotNull LevelAccessor level,
                                              final @NotNull BlockPos pos, final @NotNull BlockPos neighbourPos) {
        if (direction == state.getValue(FACING).getOpposite() && !state.canSurvive(level, pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, direction, neighbour, level, pos, neighbourPos);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(final BlockPlaceContext context) {
        if (!context.replacingClickedOnBlock()) {
            final BlockState clicked = context.getLevel().getBlockState(
                    context.getClickedPos().relative(context.getClickedFace().getOpposite()));
            if (clicked.is(this) && clicked.getValue(FACING) == context.getClickedFace()) {
                return null;
            }
        }
        final LevelReader level = context.getLevel();
        final BlockPos pos = context.getClickedPos();
        for (final Direction look : context.getNearestLookingDirections()) {
            if (look.getAxis().isHorizontal()) {
                final BlockState state = placed(defaultBlockState().setValue(FACING, look.getOpposite()), pos);
                if (state.canSurvive(level, pos)) {
                    return state;
                }
            }
        }
        return null;
    }

    /** The state a player's placement gives, once the facing is known (subclasses switch lights on, pick colours). */
    protected BlockState placed(final BlockState state, final BlockPos pos) {
        return state;
    }
}
