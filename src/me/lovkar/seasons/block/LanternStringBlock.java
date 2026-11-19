package me.lovkar.seasons.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import org.jetbrains.annotations.NotNull;

/**
 * The lantern string (look B): two paper lanterns per block in one of three colour orders ({@code variant} 0 red
 * and yellow, 1 yellow and orange, 2 orange and red). Placed along a wall the orders cycle, because the variant comes
 * from the position along the wall ({@link #variantAt}).
 */
public class LanternStringBlock extends LitWallDecorationBlock {
    public static final MapCodec<LanternStringBlock> CODEC = simpleCodec(LanternStringBlock::new);
    public static final IntegerProperty VARIANT = IntegerProperty.create("variant", 0, 2);

    public LanternStringBlock(final Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(VARIANT, 0));
    }

    @Override
    protected @NotNull MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(VARIANT);
    }

    /** The colour order at a position: x along a wall that faces north or south, z along one facing east or west. */
    public static int variantAt(final BlockPos pos, final Direction facing) {
        return Math.floorMod(facing.getAxis() == Direction.Axis.Z ? pos.getX() : pos.getZ(), 3);
    }

    @Override
    protected BlockState placed(final BlockState state, final BlockPos pos) {
        return super.placed(state, pos).setValue(VARIANT, variantAt(pos, state.getValue(FACING)));
    }
}
