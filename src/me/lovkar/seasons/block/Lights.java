package me.lovkar.seasons.block;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.gameevent.GameEvent;
import org.jetbrains.annotations.Nullable;

import java.util.function.ToIntFunction;

/**
 * The {@code lit} property the decoration lights share (lantern string, fairy lights, ornament and tinsel leaves,
 * the two stars), how much light each gives, and switching it.
 *
 * <p>Code that places a light (the Decorator, the tree) sets {@code lit} itself; the block's default state is off.
 * A player's own light comes on when it is placed, and a right-click with an empty main hand switches it (with
 * something in the hand the click places or uses that item as usual, so a tree can be built from ornament leaves
 * without switching every block it is clicked against).</p>
 */
public final class Lights {
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    /** Light levels when lit (vanilla: lantern 15, torch 14, soul lantern 10). */
    public static final int LANTERN_STRING = 12;
    public static final int FAIRY_LIGHTS = 9;
    public static final int ORNAMENT_LEAVES = 7;
    public static final int TINSEL_LEAVES = 5;
    public static final int TREE_STAR = 14;
    public static final int LARGE_STAR = 15;

    private Lights() {
    }

    /** For {@code Properties.lightLevel}: {@code level} when lit, 0 otherwise. */
    public static ToIntFunction<BlockState> whenLit(final int level) {
        return state -> state.getValue(LIT) ? level : 0;
    }

    /** The right-click of a light block: switches it when the main hand is empty, otherwise passes. */
    public static InteractionResult switchByHand(final BlockState state, final Level level, final BlockPos pos,
                                                 final Player player) {
        if (!player.getMainHandItem().isEmpty()) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide) {
            setLit(level, pos, state, !state.getValue(LIT), player);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** Sets {@code lit} with the copper bulb's click; a two-block star's other half follows by itself. */
    public static void setLit(final Level level, final BlockPos pos, final BlockState state, final boolean lit,
                              final @Nullable Player player) {
        level.setBlock(pos, state.setValue(LIT, lit), Block.UPDATE_ALL);
        level.playSound(null, pos, lit ? SoundEvents.COPPER_BULB_TURN_ON : SoundEvents.COPPER_BULB_TURN_OFF,
                SoundSource.BLOCKS, 0.6F, 1.2F);
        level.gameEvent(player, GameEvent.BLOCK_CHANGE, pos);
    }
}
