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
 * The storeroom's labelled box (S10g, variant A): an open spruce box on a barrel with a colour-coded card label on its
 * front, holding one kind of decoration ({@link #CONTENT}), full while the decorations are packed away and nearly empty
 * ({@link Fill#LOW}) while they are out on the colony's buildings. One block, ten models
 * (blockstates/storage_box.json, drawn by tools/textures/storage.py). See {@link StoragePieceBlock} for what it shares
 * with the coil.
 */
public class StorageBoxBlock extends StoragePieceBlock {
    public static final MapCodec<StorageBoxBlock> CODEC = simpleCodec(StorageBoxBlock::new);
    public static final EnumProperty<Content> CONTENT = EnumProperty.create("content", Content.class);
    public static final EnumProperty<Fill> FILL = EnumProperty.create("fill", Fill.class);
    private static final VoxelShape SHAPE = outline(10);

    /** What lies in the box; the name is the blockstate value and the piece name in the Workshop's tags (box_wreath, ...). */
    public enum Content implements StringRepresentable {
        WREATH("wreath"), GARLAND("garland"), LIGHTS("lights"), LANTERNS("lanterns"), GIFTS("gifts");

        private final String id;

        Content(final String id) {
            this.id = id;
        }

        @Override
        public @NotNull String getSerializedName() {
            return id;
        }
    }

    /** Full (packed away) or nearly empty (its decorations are out). */
    public enum Fill implements StringRepresentable {
        FULL("full"), LOW("low");

        private final String id;

        Fill(final String id) {
            this.id = id;
        }

        @Override
        public @NotNull String getSerializedName() {
            return id;
        }
    }

    public StorageBoxBlock(final Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(CONTENT, Content.WREATH)
                .setValue(FILL, Fill.FULL));
    }

    @Override
    protected @NotNull MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, CONTENT, FILL);
    }

    @Override
    protected @NotNull VoxelShape getShape(final @NotNull BlockState state, final @NotNull BlockGetter level,
                                           final @NotNull BlockPos pos, final @NotNull CollisionContext context) {
        return SHAPE;
    }
}
