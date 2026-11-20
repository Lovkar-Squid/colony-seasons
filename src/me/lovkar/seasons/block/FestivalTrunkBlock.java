package me.lovkar.seasons.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

/**
 * The trunk of the festival tree (S10c): a spruce log to look at (vanilla's spruce log models, strength and sound),
 * but our own block, in no log tag. MineColonies' Forester takes a block of {@code #minecolonies:tree} (which holds
 * {@code #minecraft:logs}) for the foot of a tree and would cut the festival tree down for free logs, and the
 * Decorator would put it back the next day; this block is in neither, so the festival tree is never a tree to him.
 *
 * <p>It has no item of its own: the Decorator pays one spruce log for it ({@code DecorPlacer.itemOf}), gets one back
 * when he takes it down, and a player who breaks it gets a spruce log (its loot table). Pick-block gives a spruce log
 * too. Like every block the Decorator places, it tells the decoration ledger about every change from
 * {@code onRemove}.</p>
 */
public class FestivalTrunkBlock extends RotatedPillarBlock {
    public static final MapCodec<FestivalTrunkBlock> CODEC = simpleCodec(FestivalTrunkBlock::new);

    public FestivalTrunkBlock(final Properties properties) {
        super(properties);
    }

    @Override
    public @NotNull MapCodec<? extends RotatedPillarBlock> codec() {
        return CODEC;
    }

    /** Tells the decoration ledger about every change of this block on the server (design 3.3: anything but a light switched is forgotten). */
    @Override
    protected void onRemove(final @NotNull BlockState state, final @NotNull Level level, final @NotNull BlockPos pos,
                            final @NotNull BlockState now, final boolean moving) {
        super.onRemove(state, level, pos, now, moving);
        me.lovkar.seasons.decor.DecorLedger.blockChanged(level, pos, state, now);
    }

    /** Pick-block: the spruce log it stands for (the block has no item). */
    @Override
    public @NotNull ItemStack getCloneItemStack(final @NotNull LevelReader level, final @NotNull BlockPos pos, final @NotNull BlockState state) {
        return new ItemStack(Items.SPRUCE_LOG);
    }
}
