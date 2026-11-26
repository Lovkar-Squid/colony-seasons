package me.lovkar.seasons.block;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.mojang.serialization.MapCodec;
import me.lovkar.seasons.decor.DecorEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The festival marker: a stake with a pennant that the PLAYER puts where the colony's tree should stand (design
 * 3.5). Its {@code facing} points at the player who placed it, so the pennant is seen broadside from there; the
 * tree piece is placed relative to the marker and its facing. No collision. Its block entity
 * ({@link FestivalMarkerBlockEntity}) keeps who placed it and the colony it was placed in; placed in a colony that
 * has no marker yet by a player, it becomes that colony's square (the decoration ledger keeps it), and broken it takes
 * its square's decorations with it. A marker set by a command or pasted with a blueprint (a builder's too) is a plain
 * block: it has no player placing it, so it never becomes a square.
 */
public class FestivalMarkerBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public static final MapCodec<FestivalMarkerBlock> CODEC = simpleCodec(FestivalMarkerBlock::new);
    private static final VoxelShape SHAPE = Block.box(4, 0, 4, 12, 16, 12);

    public FestivalMarkerBlock(final Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
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
        return SHAPE;
    }

    @Override
    public void setPlacedBy(final @NotNull Level level, final @NotNull BlockPos pos, final @NotNull BlockState state,
                            final @Nullable LivingEntity placer, final @NotNull ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide || !(level.getBlockEntity(pos) instanceof FestivalMarkerBlockEntity marker)) {
            return;
        }
        marker.setPlacedBy(placer == null ? null : placer.getUUID());
        marker.setColonyId(colonyAt(level, pos));
        // only a player's own placing chooses the square (design 3.5; 3.2 with the owner's answer of 20 Nov 2026: nothing
        // with collision goes up except at a marker the player put down or on a Town Square the player built).
        // Structurize's paste and MineColonies' builder call this with no placer, and /setblock never calls it, so a
        // marker from a blueprint or a command is a plain block (S10h). While a Town Square is the colony's main square
        // the marker registers as its fallback, and DecorEvents.markerPlaced tells the managers that it is not used
        if (level instanceof ServerLevel server && placer instanceof Player && marker.getColonyId() != FestivalMarkerBlockEntity.NO_COLONY) {
            DecorEvents.markerPlaced(server, marker.getColonyId(), pos);
        }
    }

    /** The marker going (broken, blown up, replaced): its square comes down at the end of the tick. */
    @Override
    protected void onRemove(final @NotNull BlockState state, final @NotNull Level level, final @NotNull BlockPos pos,
                            final @NotNull BlockState now, final boolean moving) {
        if (level instanceof ServerLevel server && !now.is(this)) {
            DecorEvents.markerRemoved(server, pos);
        }
        super.onRemove(state, level, pos, now, moving);
    }

    /** The id of the colony whose claim holds the position, or {@link FestivalMarkerBlockEntity#NO_COLONY}. */
    private static int colonyAt(final Level level, final BlockPos pos) {
        try {
            final IColony colony = IColonyManager.getInstance().getIColony(level, pos);
            return colony == null ? FestivalMarkerBlockEntity.NO_COLONY : colony.getID();
        } catch (final RuntimeException unavailable) {
            // MineColonies not ready for this level: the colony is looked up again when it is needed
            return FestivalMarkerBlockEntity.NO_COLONY;
        }
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(final @NotNull BlockPos pos, final @NotNull BlockState state) {
        return new FestivalMarkerBlockEntity(pos, state);
    }
}
