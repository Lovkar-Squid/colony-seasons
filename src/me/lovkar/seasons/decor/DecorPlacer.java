package me.lovkar.seasons.decor;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import me.lovkar.seasons.ColonySeasons;
import me.lovkar.seasons.block.LanternStringBlock;
import me.lovkar.seasons.block.Lights;
import me.lovkar.seasons.block.WallDecorationBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The only code that sets or clears decoration blocks (design 3.2, 3.3; the cell rule and the removal rule of the
 * S4 design, A3 and A4).
 *
 * <p>Placing, per cell and in this order: the chunk is loaded (checked without loading it); the cell is inside this
 * colony's claim; the world block {@code isAir()} (grass, flowers, snow and water stay); no ledger entry of any
 * group lists the cell; a wall block needs the wall behind it sturdy towards it and must be able to stay there; one
 * item is taken if the slot costs one; the ledger records the cell; then the block is set without neighbour updates
 * (flags 18) and read back. A cell that reads back as another state of the same block keeps its entry with that
 * state; one that reads back as anything else is forgotten and its item refunded. Nothing
 * with collision is placed for a building; only the square at a player's festival marker may have it.</p>
 *
 * <p>Removing, per entry: a cell in an unloaded chunk waits; a cell whose state equals the record exactly is
 * forgotten first, then set to air with no drops, and its item handed back if it was paid for; a cell whose state
 * differs (a player changed it, the Builder cleared it) is forgotten and left alone. A removal may have a budget (the
 * Decorator takes a big tree down in slices, the sweep has its own): the cells past it stay listed for the next one.
 * Afterwards the neighbours of the cleared cells are updated once, so snow and anything attached settle; a neighbour
 * the ledger still lists is NOT updated, because a leaf of the same tree would re-read its distance and no longer equal
 * its entry, and the next slice would then leave it standing (S9, H9).</p>
 */
public final class DecorPlacer {

    /** Where items for decorations come from and go back to. */
    public interface Stock {
        /** Takes one {@code item}; false when there is none. */
        boolean take(Item item);

        /** Hands a stack back; what does not fit anywhere is dropped (or, with no hut at all, lost). */
        void give(ItemStack stack);

        /** True when at least one {@code item} could be taken (looked at, nothing moves). */
        default boolean has(final Item item) {
            return true;
        }

        /** Which kinds of decoration may go up (the Decorator's hut settings); all of them for a stock with no hut behind it. */
        default DecorKinds kinds() {
            return DecorKinds.ALL;
        }

        /** The same stock with the kinds of {@code kinds}. */
        static Stock withKinds(final Stock inner, final DecorKinds kinds) {
            return new Stock() {
                @Override
                public boolean take(final Item item) {
                    return inner.take(item);
                }

                @Override
                public void give(final ItemStack stack) {
                    inner.give(stack);
                }

                @Override
                public boolean has(final Item item) {
                    return inner.has(item);
                }

                @Override
                public DecorKinds kinds() {
                    return kinds;
                }
            };
        }

        /** Decorations are free: everything is there, nothing comes back. */
        Stock FREE = new Stock() {
            @Override
            public boolean take(final Item item) {
                return true;
            }

            @Override
            public void give(final ItemStack stack) {
            }
        };
    }

    /** One cell to fill: where, the exact state, and whether it costs an item of that block. */
    public record Slot(BlockPos pos, BlockState state, boolean costs) {
        public Slot {
            pos = pos.immutable();
        }
    }

    /**
     * What one removal did: cells set to air, entries forgotten because the world had changed, cells waiting for their
     * chunk to load, and cells left for a later removal because the budget was used up (all still listed).
     */
    public record Removed(int removed, int forgotten, int waiting, int left) {
        public static final Removed NONE = new Removed(0, 0, 0, 0);

        public Removed(final int removed, final int forgotten, final int waiting) {
            this(removed, forgotten, waiting, 0);
        }

        public Removed plus(final Removed other) {
            return new Removed(removed + other.removed, forgotten + other.forgotten, waiting + other.waiting, left + other.left);
        }

        public int touched() {
            return removed + forgotten;
        }
    }

    /** Placing: tell clients, skip the neighbours' shape updates. */
    public static final int PLACE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    /** Clearing: the same, and no drops. */
    public static final int CLEAR_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
    /** Colony id for "no colony here". */
    public static final int NO_COLONY = -1;

    private DecorPlacer() {
    }

    // ---------------------------------------------------------------- the cell rule

    /** The colony whose claim holds {@code pos}; never loads a chunk (an unloaded one gives {@link #NO_COLONY}). */
    public static int colonyAt(final ServerLevel level, final BlockPos pos) {
        if (!level.hasChunkAt(pos)) {
            return NO_COLONY;
        }
        try {
            final IColony colony = IColonyManager.getInstance().getIColony(level, pos);
            return colony == null ? NO_COLONY : colony.getID();
        } catch (final RuntimeException unavailable) {
            return NO_COLONY;
        }
    }

    /** Why a cell cannot take a block, or {@code null} when it can (steps 1 to 5 of the cell rule). */
    public static String refusal(final ServerLevel level, final DecorLedger ledger, final int colony, final BlockPos pos,
                                 final BlockState state, final boolean square) {
        if (!level.hasChunkAt(pos)) {
            return "unloaded";
        }
        if (colonyAt(level, pos) != colony) {
            return "claim";
        }
        if (!level.getBlockState(pos).isAir()) {
            return "occupied";
        }
        if (ledger.at(pos) != null) {
            return "listed";
        }
        if (!square && !state.getCollisionShape(level, pos).isEmpty()) {
            return "collision";
        }
        if (state.getBlock() instanceof WallDecorationBlock) {
            final Direction facing = state.getValue(WallDecorationBlock.FACING);
            final BlockPos wall = pos.relative(facing.getOpposite());
            if (!level.hasChunkAt(wall)) {
                return "unloaded";                    // the wall may sit across a chunk border: never load it to read it
            }
            if (!level.getBlockState(wall).isFaceSturdy(level, wall, facing)) {
                return "wall";
            }
        }
        if (!state.canSurvive(level, pos)) {
            return "survive";
        }
        return null;
    }

    /** The state as the Decorator puts it: lights off, the lantern string's colour order from its position, leaves that never decay, no water. */
    public static BlockState normalise(final BlockState state, final BlockPos pos) {
        BlockState s = state;
        if (s.hasProperty(Lights.LIT) && ColonySeasons.MODID.equals(BuiltInRegistries.BLOCK.getKey(s.getBlock()).getNamespace())) {
            s = s.setValue(Lights.LIT, false);
        }
        if (s.getBlock() instanceof LanternStringBlock) {
            s = s.setValue(LanternStringBlock.VARIANT, LanternStringBlock.variantAt(pos, s.getValue(WallDecorationBlock.FACING)));
        }
        if (s.getBlock() instanceof LeavesBlock && s.hasProperty(LeavesBlock.PERSISTENT)) {
            s = s.setValue(LeavesBlock.PERSISTENT, true);
        }
        if (s.hasProperty(BlockStateProperties.WATERLOGGED)) {
            s = s.setValue(BlockStateProperties.WATERLOGGED, false);
        }
        return s;
    }

    /** The item a block costs, or {@code null} for none. */
    public static Item itemOf(final BlockState state) {
        final Item item = state.getBlock().asItem();
        return item == Items.AIR ? null : item;
    }

    // ---------------------------------------------------------------- placing

    /**
     * Fills the slots that pass the cell rule, in order; a slot whose item is missing is skipped. Returns how many
     * blocks were placed.
     */
    public static int place(final ServerLevel level, final DecorLedger ledger, final int colony, final DecorLedger.Owner owner,
                            final String festival, final int hutLevel, final List<Slot> slots, final Stock stock) {
        final boolean square = owner.kind() == DecorLedger.Kind.SQUARE;
        int placed = 0;
        for (final Slot slot : slots) {
            final BlockState state = normalise(slot.state(), slot.pos());
            if (refusal(level, ledger, colony, slot.pos(), state, square) != null) {
                continue;
            }
            final Item item = slot.costs() ? itemOf(state) : null;
            if (item != null && !stock.take(item)) {
                continue;
            }
            if (!ledger.record(colony, owner, festival, hutLevel, slot.pos(), state, item != null)) {
                refund(stock, item);
                continue;
            }
            level.setBlock(slot.pos(), state, PLACE_FLAGS);
            final BlockState now = level.getBlockState(slot.pos());
            if (now != state && now.getBlock() == state.getBlock()) {
                // it settled into another state of itself: still ours, so the entry follows (never a block nobody lists)
                if (!ledger.restate(slot.pos(), now) && ledger.at(slot.pos()) == null) {
                    ledger.record(colony, owner, festival, hutLevel, slot.pos(), now, item != null);
                }
                placed++;
                continue;
            }
            if (now != state) {
                // something stopped it (a protection mod, another mod's block): no trace of us is left
                ledger.forget(slot.pos());
                refund(stock, item);
                continue;
            }
            placed++;
        }
        return placed;
    }

    private static void refund(final Stock stock, final Item item) {
        if (item != null) {
            stock.give(new ItemStack(item));
        }
    }

    // ---------------------------------------------------------------- removing

    /** Removes a whole group by the rule (no budget). */
    public static Removed remove(final ServerLevel level, final DecorLedger ledger, final int colony, final DecorLedger.Owner owner,
                                 final Stock sink) {
        return remove(level, ledger, colony, owner, sink, Integer.MAX_VALUE);
    }

    /**
     * Removes up to {@code budget} entries of one group by the removal rule, top down; the rest stay listed
     * ({@link Removed#left}). A group with no entries left is dropped from the ledger.
     */
    public static Removed remove(final ServerLevel level, final DecorLedger ledger, final int colony, final DecorLedger.Owner owner,
                                 final Stock sink, final int budget) {
        final DecorLedger.GroupView group = ledger.group(colony, owner);
        if (group == null) {
            return Removed.NONE;
        }
        final List<Map.Entry<BlockPos, DecorLedger.Entry>> work = new ArrayList<>(group.entries().entrySet());
        work.sort(Comparator.comparingInt((Map.Entry<BlockPos, DecorLedger.Entry> e) -> -e.getKey().getY())
                .thenComparing(Map.Entry::getKey));
        int removed = 0;
        int forgotten = 0;
        int waiting = 0;
        int left = 0;
        int spent = 0;
        final List<BlockPos> cleared = new ArrayList<>();
        for (final Map.Entry<BlockPos, DecorLedger.Entry> e : work) {
            final BlockPos pos = e.getKey();
            if (!level.hasChunkAt(pos)) {
                waiting++;
                continue;
            }
            if (spent >= budget) {
                left++;
                continue;
            }
            spent++;
            final DecorLedger.Entry entry = e.getValue();
            final BlockState now = level.getBlockState(pos);
            ledger.forget(pos);
            if (now == entry.state()) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), CLEAR_FLAGS);
                cleared.add(pos);
                removed++;
                if (entry.paid()) {
                    final Item item = itemOf(entry.state());
                    if (item != null) {
                        sink.give(new ItemStack(item));
                    }
                }
            } else {
                forgotten++;
            }
        }
        settleAround(level, ledger, cleared);
        ledger.dropGroup(colony, owner);
        return new Removed(removed, forgotten, waiting, left);
    }

    /**
     * Tells the neighbours of cleared cells that air is there now (a shape update and a neighbour update each), except
     * neighbours in unloaded chunks (never loaded for it) and neighbours the ledger still lists: those keep their exact
     * state until their own removal, which updates their neighbours in turn.
     */
    private static void settleAround(final ServerLevel level, final DecorLedger ledger, final List<BlockPos> cleared) {
        final BlockState air = Blocks.AIR.defaultBlockState();
        final BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();
        for (final BlockPos pos : cleared) {
            for (final Direction direction : Direction.values()) {
                next.setWithOffset(pos, direction);
                if (!level.hasChunkAt(next) || ledger.at(next) != null) {
                    continue;
                }
                final BlockPos neighbour = next.immutable();
                level.neighborShapeChanged(direction.getOpposite(), air, neighbour, pos, Block.UPDATE_ALL, 512);
                level.neighborChanged(neighbour, Blocks.AIR, pos);
            }
        }
    }

    /** Every group of a colony, by the rule; groups with cells in unloaded chunks are marked for the sweep. */
    public static Removed removeColony(final ServerLevel level, final DecorLedger ledger, final int colony, final Stock sink) {
        Removed all = Removed.NONE;
        for (final DecorLedger.GroupView group : ledger.groups(colony)) {
            final Removed one = remove(level, ledger, colony, group.owner(), sink);
            if (one.waiting() > 0) {
                ledger.markRemoving(colony, group.owner());
            }
            all = all.plus(one);
        }
        return all;
    }

    // ---------------------------------------------------------------- leaves

    /**
     * Vanilla leaves of a group settle to the distance the world gives them (as their own tick would), and the
     * entries follow, so a neighbour update later does not turn our leaves into "changed by someone" at take-down.
     */
    public static int settleLeaves(final ServerLevel level, final DecorLedger ledger, final int colony, final DecorLedger.Owner owner) {
        final DecorLedger.GroupView group = ledger.group(colony, owner);
        if (group == null) {
            return 0;
        }
        int changed = 0;
        for (int round = 0; round < LeavesBlock.DECAY_DISTANCE; round++) {
            int thisRound = 0;
            for (final Map.Entry<BlockPos, DecorLedger.Entry> e : ledger.group(colony, owner).entries().entrySet()) {
                final BlockState state = e.getValue().state();
                if (!(state.getBlock() instanceof LeavesBlock) || !level.hasChunkAt(e.getKey())
                        || level.getBlockState(e.getKey()) != state) {
                    continue;
                }
                final BlockState settled = state.setValue(LeavesBlock.DISTANCE, distanceAt(level, e.getKey()));
                if (settled != state) {
                    ledger.restate(e.getKey(), settled);
                    level.setBlock(e.getKey(), settled, PLACE_FLAGS);
                    thisRound++;
                }
            }
            changed += thisRound;
            if (thisRound == 0) {
                break;
            }
        }
        return changed;
    }

    /**
     * Listed vanilla leaves whose block re-read its distance since (a neighbour changed: a player broke a log of the tree,
     * which never tells the ledger) are still the leaves we put there: their entries follow the world, as
     * {@link #settleLeaves}' do. Only the same block with the same {@code persistent} and only {@code distance} changed;
     * anything else stays "changed by someone". Run by a visit to the square before it plans, so the take-down later
     * finds every leaf exactly as listed (S9, H17). Returns how many entries followed.
     */
    public static int followLeaves(final ServerLevel level, final DecorLedger ledger, final int colony, final DecorLedger.Owner owner) {
        final DecorLedger.GroupView group = ledger.group(colony, owner);
        if (group == null) {
            return 0;
        }
        int followed = 0;
        for (final Map.Entry<BlockPos, DecorLedger.Entry> e : group.entries().entrySet()) {
            final BlockState recorded = e.getValue().state();
            if (!(recorded.getBlock() instanceof LeavesBlock) || !recorded.hasProperty(LeavesBlock.DISTANCE) || !level.hasChunkAt(e.getKey())) {
                continue;
            }
            final BlockState now = level.getBlockState(e.getKey());
            if (now != recorded && now.getBlock() == recorded.getBlock()
                    && now.setValue(LeavesBlock.DISTANCE, recorded.getValue(LeavesBlock.DISTANCE)) == recorded) {
                ledger.restate(e.getKey(), now);
                followed++;
            }
        }
        return followed;
    }

    /** Vanilla's leaf distance at a position (1 next to a log, 7 far from any). */
    private static int distanceAt(final ServerLevel level, final BlockPos pos) {
        int distance = LeavesBlock.DECAY_DISTANCE;
        final BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (final Direction direction : Direction.values()) {
            at.setWithOffset(pos, direction);
            if (!level.hasChunkAt(at)) {
                continue;                             // never load a chunk for a leaf's neighbour
            }
            distance = Math.min(distance, LeavesBlock.getOptionalDistanceAt(level.getBlockState(at)).orElse(LeavesBlock.DECAY_DISTANCE) + 1);
            if (distance == 1) {
                break;
            }
        }
        return distance;
    }
}
