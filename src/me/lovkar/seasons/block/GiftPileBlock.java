package me.lovkar.seasons.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

/**
 * Four wrapped boxes at the foot of the tree. Its block entity ({@link GiftPileBlockEntity}) keeps the stock and
 * who took a gift on which real day; handing gifts out comes in a later chunk. It faces the player who put it down;
 * its outline and collision are the four boxes (low enough to step over).
 */
public class GiftPileBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public static final MapCodec<GiftPileBlock> CODEC = simpleCodec(GiftPileBlock::new);
    /** The boxes of the model as drawn (facing north): tools/textures/floor.py GIFT_BOXES. */
    private static final double[][] BOXES = {{1, 0, 1, 9, 6, 9}, {9, 0, 7, 15, 5, 13}, {3, 0, 10, 7, 4, 14}, {10, 0, 2, 14, 3, 6}};
    private static final Map<Direction, VoxelShape> SHAPES = new EnumMap<>(Direction.class);

    static {
        for (final Direction facing : Direction.Plane.HORIZONTAL) {
            VoxelShape shape = Shapes.empty();
            for (final double[] b : BOXES) {
                shape = Shapes.or(shape, turned(b, facing));
            }
            SHAPES.put(facing, shape.optimize());
        }
    }

    public GiftPileBlock(final Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    /** A box of the north-facing model turned the way the blockstate turns the model (y 90 = north to east). */
    private static VoxelShape turned(final double[] b, final Direction facing) {
        double x0 = b[0], z0 = b[2], x1 = b[3], z1 = b[5];
        final int quarters = switch (facing) {
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        };
        for (int i = 0; i < quarters; i++) {
            final double nx0 = 16 - z1, nx1 = 16 - z0;
            z0 = x0;
            z1 = x1;
            x0 = nx0;
            x1 = nx1;
        }
        return Block.box(x0, b[1], z0, x1, b[4], z1);
    }

    @Override
    protected @NotNull MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(final BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected @NotNull VoxelShape getShape(final @NotNull BlockState state, final @NotNull BlockGetter level,
                                           final @NotNull BlockPos pos, final @NotNull CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(final @NotNull BlockPos pos, final @NotNull BlockState state) {
        return new GiftPileBlockEntity(pos, state);
    }
}
