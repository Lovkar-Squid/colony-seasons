package me.lovkar.seasons.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.NotNull;

/**
 * A wall decoration that gives light when {@code lit}: the fairy lights (and, with its colour orders,
 * {@link LanternStringBlock}). The light level comes from the registration ({@link Lights#whenLit}); the lit model
 * draws the bulbs at full brightness.
 */
public class LitWallDecorationBlock extends WallDecorationBlock {
    public static final MapCodec<LitWallDecorationBlock> CODEC = simpleCodec(LitWallDecorationBlock::new);

    public LitWallDecorationBlock(final Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(Lights.LIT, false));
    }

    @Override
    protected @NotNull MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(Lights.LIT);
    }

    @Override
    protected BlockState placed(final BlockState state, final BlockPos pos) {
        return state.setValue(Lights.LIT, true);
    }

    @Override
    protected @NotNull InteractionResult useWithoutItem(final @NotNull BlockState state, final @NotNull Level level,
                                                        final @NotNull BlockPos pos, final @NotNull Player player,
                                                        final @NotNull BlockHitResult hit) {
        return Lights.switchByHand(state, level, pos, player);
    }
}
