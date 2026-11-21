package me.lovkar.seasons.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;

/**
 * A coil of garland or of fairy lights lying on a barrel in the storeroom (S10g, variant A), packed away. In the
 * festival it is taken off (it is out on the buildings). One block, two models (blockstates/storage_coil.json, drawn by
 * tools/textures/storage.py). See {@link StoragePieceBlock} for what it shares with the box.
 */
public class StorageCoilBlock extends StoragePieceBlock {
    public static final MapCodec<StorageCoilBlock> CODEC = simpleCodec(StorageCoilBlock::new);
    public static final EnumProperty<Kind> KIND = EnumProperty.create("kind", Kind.class);
    private static final VoxelShape SHAPE = outline(4);

    /** What is coiled; the name is the blockstate value and the piece name in the Workshop's tags (coil_garland, ...). */
    public enum Kind implements StringRepresentable {
        GARLAND("garland"), LIGHTS("lights");

        private final String id;

        Kind(final String id) {
            this.id = id;
        }

        @Override
        public @NotNull String getSerializedName() {
            return id;
        }
    }

    public StorageCoilBlock(final Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(KIND, Kind.GARLAND));
    }

    @Override
    protected @NotNull MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, KIND);
    }

    @Override
    protected @NotNull VoxelShape getShape(final @NotNull BlockState state, final @NotNull BlockGetter level,
                                           final @NotNull BlockPos pos, final @NotNull CollisionContext context) {
        return SHAPE;
    }
}
