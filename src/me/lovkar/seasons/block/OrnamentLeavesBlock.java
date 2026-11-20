package me.lovkar.seasons.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Spruce leaves with ornaments, a piece of the square's tree: {@code ornament_leaves} (baubles and small lights)
 * and {@code tinsel_leaves} (the gold garland swag of the grand tree). Both have {@code lit}.
 *
 * <p>They never decay and never drop on their own: the tree pieces put them among real logs and leaves, and the
 * Decorator's ledger takes off exactly the state it put down. So this is not a {@code LeavesBlock}: it has no
 * {@code distance}, {@code persistent} or {@code waterlogged} property that the world could change behind the ledger's
 * back, and no random tick. It is in the {@code #minecraft:leaves} tag (shears and hoes, like leaves).</p>
 */
public class OrnamentLeavesBlock extends Block {
    public static final MapCodec<OrnamentLeavesBlock> CODEC = simpleCodec(OrnamentLeavesBlock::new);

    public OrnamentLeavesBlock(final Properties properties) {
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
    protected boolean isRandomlyTicking(final @NotNull BlockState state) {
        return false;
    }

    @Override
    protected @NotNull InteractionResult useWithoutItem(final @NotNull BlockState state, final @NotNull Level level,
                                                        final @NotNull BlockPos pos, final @NotNull Player player,
                                                        final @NotNull BlockHitResult hit) {
        return Lights.switchByHand(state, level, pos, player);
    }
}
