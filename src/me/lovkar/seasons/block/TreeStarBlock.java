package me.lovkar.seasons.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * The gold star on top of the village spruce: two crossed 2 px stars, {@code lit}. It needs nothing under it (it
 * sits on spruce leaves, which give no support), has no collision and shines at full brightness when lit.
 */
public class TreeStarBlock extends Block {
    public static final MapCodec<TreeStarBlock> CODEC = simpleCodec(TreeStarBlock::new);
    private static final VoxelShape SHAPE = Block.box(1, 1, 1, 15, 15, 15);

    public TreeStarBlock(final Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(Lights.LIT, false));
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
        builder.add(Lights.LIT);
    }

    @Override
    public BlockState getStateForPlacement(final BlockPlaceContext context) {
        return defaultBlockState().setValue(Lights.LIT, true);
    }

    @Override
    protected @NotNull VoxelShape getShape(final @NotNull BlockState state, final @NotNull BlockGetter level,
                                           final @NotNull BlockPos pos, final @NotNull CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected @NotNull InteractionResult useWithoutItem(final @NotNull BlockState state, final @NotNull Level level,
                                                        final @NotNull BlockPos pos, final @NotNull Player player,
                                                        final @NotNull BlockHitResult hit) {
        return Lights.switchByHand(state, level, pos, player);
    }
}
