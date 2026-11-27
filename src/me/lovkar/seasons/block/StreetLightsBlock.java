package me.lovkar.seasons.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Thin fairy lights draped over the top of a fence post or a wall (0.2.0, Q11, the server switch
 * {@code decorator.streetLights}, off by default): a short wire with five bulbs along {@code axis}, in the cell above the
 * fence. Only the Decorator places them, for free, on fences and walls beside the colony's path blocks: no item, no
 * recipe, no loot table ({@code noLootTable()}), not in the creative tab, pick-block gives nothing. No collision, breaks at
 * once, a piston destroys it; it stays only on a block of {@code #minecraft:fences} or {@code #minecraft:walls} and goes
 * (dropping nothing) when that block goes. {@code lit} as the other lights: the Decorator puts them up unlit, the tree's
 * lighting or an empty hand switches them.
 */
public class StreetLightsBlock extends Block {
    public static final MapCodec<StreetLightsBlock> CODEC = simpleCodec(StreetLightsBlock::new);
    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.HORIZONTAL_AXIS;
    private static final VoxelShape ALONG_X = Block.box(0, 0, 6, 16, 4, 10);
    private static final VoxelShape ALONG_Z = Block.box(6, 0, 0, 10, 4, 16);

    public StreetLightsBlock(final Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(AXIS, Direction.Axis.X).setValue(Lights.LIT, false));
    }

    @Override
    public @NotNull MapCodec<? extends Block> codec() {
        return CODEC;
    }

    /** True when {@code below} can carry the lights: a fence or a wall. */
    public static boolean carries(final BlockState below) {
        return below.is(BlockTags.FENCES) || below.is(BlockTags.WALLS);
    }

    /** Tells the decoration ledger about every change of this block on the server (design 3.3: a light switched is followed, anything else forgotten). */
    @Override
    protected void onRemove(final @NotNull BlockState state, final @NotNull Level level, final @NotNull BlockPos pos,
                            final @NotNull BlockState now, final boolean moving) {
        super.onRemove(state, level, pos, now, moving);
        me.lovkar.seasons.decor.DecorLedger.blockChanged(level, pos, state, now);
    }

    /** Nothing (no loot table), through the same filter as every other block the Decorator places. */
    @Override
    protected @NotNull List<ItemStack> getDrops(final @NotNull BlockState state, final LootParams.@NotNull Builder params) {
        return me.lovkar.seasons.decor.DecorDrops.filter(state, params, super.getDrops(state, params));
    }

    @Override
    protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AXIS, Lights.LIT);
    }

    @Override
    protected @NotNull VoxelShape getShape(final @NotNull BlockState state, final @NotNull BlockGetter level,
                                           final @NotNull BlockPos pos, final @NotNull CollisionContext context) {
        return state.getValue(AXIS) == Direction.Axis.X ? ALONG_X : ALONG_Z;
    }

    /** Only on a fence or a wall. */
    @Override
    protected boolean canSurvive(final @NotNull BlockState state, final @NotNull LevelReader level, final @NotNull BlockPos pos) {
        return carries(level.getBlockState(pos.below()));
    }

    /** Goes (with nothing dropped) when the fence or wall below goes. */
    @Override
    protected @NotNull BlockState updateShape(final @NotNull BlockState state, final @NotNull Direction direction,
                                              final @NotNull BlockState neighbour, final @NotNull LevelAccessor level,
                                              final @NotNull BlockPos pos, final @NotNull BlockPos neighbourPos) {
        if (direction == Direction.DOWN && !state.canSurvive(level, pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, direction, neighbour, level, pos, neighbourPos);
    }

    /** Pick-block gives nothing: the block has no item. */
    @Override
    public @NotNull ItemStack getCloneItemStack(final @NotNull LevelReader level, final @NotNull BlockPos pos, final @NotNull BlockState state) {
        return ItemStack.EMPTY;
    }

    @Override
    protected @NotNull InteractionResult useWithoutItem(final @NotNull BlockState state, final @NotNull Level level,
                                                        final @NotNull BlockPos pos, final @NotNull Player player,
                                                        final @NotNull BlockHitResult hit) {
        return Lights.switchByHand(state, level, pos, player);
    }
}
