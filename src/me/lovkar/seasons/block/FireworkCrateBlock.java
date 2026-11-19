package me.lovkar.seasons.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;

/**
 * The crate of rockets by the tree, the launch point of the New Year show (hut level 3 and up). A plain block for
 * now: the show is a later chunk.
 */
public class FireworkCrateBlock extends Block {
    public static final MapCodec<FireworkCrateBlock> CODEC = simpleCodec(FireworkCrateBlock::new);
    /** The crate and the rockets standing in it. */
    private static final VoxelShape SHAPE = Block.box(1, 0, 1, 15, 15, 15);

    public FireworkCrateBlock(final Properties properties) {
        super(properties);
    }

    @Override
    public @NotNull MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected @NotNull VoxelShape getShape(final @NotNull BlockState state, final @NotNull BlockGetter level,
                                           final @NotNull BlockPos pos, final @NotNull CollisionContext context) {
        return SHAPE;
    }
}
