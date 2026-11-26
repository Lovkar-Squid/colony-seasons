package me.lovkar.seasons.decor;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import me.lovkar.seasons.ColonySeasons;
import me.lovkar.seasons.block.FestivalTrunkBlock;
import me.lovkar.seasons.block.LanternStringBlock;
import me.lovkar.seasons.block.Lights;
import me.lovkar.seasons.block.WallDecorationBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

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
 * with collision is placed for a building; only the square's group and its seasonal touches (Q12) may have it, at a
 * player's festival marker or on a Town Square the player built (the owner's answer of 20 Nov 2026), and never into a
 * cell where an entity stands (S10c: the cell waits for a later visit).</p>
 *
 * <p>Removing, per entry: a cell in an unloaded chunk waits; a cell whose state equals the record exactly is
 * forgotten first, then set to air with no drops, and its item handed back if it was paid for. One exception, the
 * owner's answer of 20 Nov 2026: a vanilla leaf of our piece whose only change since it was recorded is its
 * {@code distance} ({@link #leafDistanceOnly}; {@code persistent} and {@code waterlogged} as recorded) is removed the
 * same way. A cell whose state differs in any other way (a player changed it, the Builder cleared it) is forgotten and
 * left alone. A removal may have a budget (the
 * Decorator takes a big tree down in slices, the sweep has its own): the cells past it stay listed for the next one.
 * Afterwards the neighbours of the cleared cells are updated once, so snow and anything attached settle; a neighbour
 * the ledger still lists is not updated (it comes down in a later slice exactly as listed: a leaf would re-read its
 * distance, half a large star would pop off and drop), nor one in a chunk that is not loaded (never loaded for it).</p>
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
        return refusal(level, ledger, colony, pos, state, square, null);
    }

    /**
     * {@link #refusal(ServerLevel, DecorLedger, int, BlockPos, BlockState, boolean)} with the entities that may stand in
     * the cells already looked up ({@link #blockers}; {@code null}: looked up for this cell). A block with collision is
     * never set into a cell any entity's bounding box takes ("entity", S10c): the cell stays open and is tried again
     * on a later visit, so nobody is ever boxed in by the tree.
     */
    public static String refusal(final ServerLevel level, final DecorLedger ledger, final int colony, final BlockPos pos,
                                 final BlockState state, final boolean square, final List<Entity> near) {
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
        final VoxelShape shape = state.getCollisionShape(level, pos);
        if (!shape.isEmpty()) {
            if (!square) {
                return "collision";
            }
            if (inEntity(level, pos, shape, near)) {
                return "entity";
            }
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

    /**
     * An entity that a block must never be set into: alive, not a spectator, with a body (not a marker, a display or
     * an interaction box), and not an item, an experience orb or a projectile (a block pushes those out).
     */
    public static boolean blocks(final Entity entity) {
        return entity.isAlive() && !entity.isSpectator() && !(entity instanceof ItemEntity) && !(entity instanceof ExperienceOrb)
                && !(entity instanceof Projectile) && !(entity instanceof Marker) && !(entity instanceof Display)
                && !(entity instanceof Interaction) && entity.getBbWidth() > 0 && entity.getBbHeight() > 0;
    }

    /**
     * The entities ({@link #blocks}) whose bounding box reaches into the cells of {@code slots}, in one query over
     * their box. It reads loaded entity sections only and never loads a chunk.
     */
    public static List<Entity> blockers(final ServerLevel level, final List<Slot> slots) {
        if (slots.isEmpty()) {
            return List.of();
        }
        AABB box = null;
        for (final Slot slot : slots) {
            box = box == null ? new AABB(slot.pos()) : box.minmax(new AABB(slot.pos()));
        }
        return level.getEntities((Entity) null, box, DecorPlacer::blocks);
    }

    /** True when an entity's box strictly overlaps the block's collision shape set at {@code pos} (a body standing on top of it does not). */
    private static boolean inEntity(final ServerLevel level, final BlockPos pos, final VoxelShape shape, final List<Entity> near) {
        final List<Entity> who = near != null ? near : level.getEntities((Entity) null, new AABB(pos), DecorPlacer::blocks);
        if (who.isEmpty()) {
            return false;
        }
        for (final AABB part : shape.toAabbs()) {
            final AABB at = part.move(pos);
            for (final Entity entity : who) {
                if (entity.getBoundingBox().intersects(at)) {
                    return true;
                }
            }
        }
        return false;
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

    /** The item a block costs, or {@code null} for none. The festival tree's trunk has no item: it costs a spruce log. */
    public static Item itemOf(final BlockState state) {
        if (state.getBlock() instanceof FestivalTrunkBlock) {
            return Items.SPRUCE_LOG;
        }
        final Item item = state.getBlock().asItem();
        return item == Items.AIR ? null : item;
    }

    // ---------------------------------------------------------------- placing

    /**
     * What one placing did: the cells set (in order), the cells the cell rule refused with its reason ("entity",
     * "survive", "wall", "occupied", ...; "listed" when the ledger refused the entry, "stopped" when the world did not
     * keep the block), and how many slots waited for their item (S10c: a visit that places nothing says why).
     */
    public record PlaceResult(List<BlockPos> placed, Map<BlockPos, String> refused, int noStock) {
        public static final PlaceResult NONE = new PlaceResult(List.of(), Map.of(), 0);

        public int count() {
            return placed.size();
        }

        public PlaceResult plus(final PlaceResult other) {
            final List<BlockPos> p = new ArrayList<>(placed);
            p.addAll(other.placed);
            final Map<BlockPos, String> r = new LinkedHashMap<>(refused);
            r.putAll(other.refused);
            return new PlaceResult(List.copyOf(p), Collections.unmodifiableMap(r), noStock + other.noStock);
        }

        /** The refusals counted by reason, and the slots without stock as "stock" (for a log line). */
        public Map<String, Integer> reasons() {
            final Map<String, Integer> out = new TreeMap<>();
            for (final String why : refused.values()) {
                out.merge(why, 1, Integer::sum);
            }
            if (noStock > 0) {
                out.put("stock", noStock);
            }
            return out;
        }
    }

    /**
     * Fills the slots that pass the cell rule, in order; a slot whose item is missing is skipped. Each block is listed
     * in the ledger before it is set, and before the next one (design 3.2, 3.3).
     */
    public static PlaceResult place(final ServerLevel level, final DecorLedger ledger, final int colony, final DecorLedger.Owner owner,
                                    final String festival, final int hutLevel, final List<Slot> slots, final Stock stock) {
        // the square's set and its seasonal touches (Q12) may have collision: only at a place the player chose (a marker
        // he put down, a Town Square he built); who stands in their cells is looked up once (nobody moves within this tick)
        final boolean square = owner.collides();
        final List<Entity> near = square ? blockers(level, slots) : null;
        final List<BlockPos> placed = new ArrayList<>();
        final Map<BlockPos, String> refused = new LinkedHashMap<>();
        int noStock = 0;
        for (final Slot slot : slots) {
            final BlockState state = normalise(slot.state(), slot.pos());
            final String why = refusal(level, ledger, colony, slot.pos(), state, square, near);
            if (why != null) {
                refused.put(slot.pos(), why);
                continue;
            }
            final Item item = slot.costs() ? itemOf(state) : null;
            if (item != null && !stock.take(item)) {
                noStock++;
                continue;
            }
            if (!ledger.record(colony, owner, festival, hutLevel, slot.pos(), state, item != null)) {
                refund(stock, item);
                refused.put(slot.pos(), "listed");
                continue;
            }
            level.setBlock(slot.pos(), state, PLACE_FLAGS);
            final BlockState now = level.getBlockState(slot.pos());
            if (now != state && now.getBlock() == state.getBlock()) {
                // it settled into another state of itself: still ours, so the entry follows (never a block nobody lists)
                if (!ledger.restate(slot.pos(), now) && ledger.at(slot.pos()) == null) {
                    ledger.record(colony, owner, festival, hutLevel, slot.pos(), now, item != null);
                }
                placed.add(slot.pos());
                continue;
            }
            if (now != state) {
                // something stopped it (a protection mod, another mod's block): no trace of us is left
                ledger.forget(slot.pos());
                refund(stock, item);
                refused.put(slot.pos(), "stopped");
                continue;
            }
            placed.add(slot.pos());
        }
        return new PlaceResult(List.copyOf(placed), Collections.unmodifiableMap(refused), noStock);
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
        return remove(level, ledger, colony, owner, sink, budget, pos -> true);
    }

    /**
     * {@link #remove(ServerLevel, DecorLedger, int, DecorLedger.Owner, Stock, int)} restricted to the entries whose
     * position {@code only} accepts (the storeroom's check takes off the pieces that are not in its target, S10g2): the
     * other entries are not touched and not counted. Same rule, same flags, the same neighbour updates, and the group is
     * dropped at the end only if it lists nothing any more.
     */
    public static Removed remove(final ServerLevel level, final DecorLedger ledger, final int colony, final DecorLedger.Owner owner,
                                 final Stock sink, final int budget, final Predicate<BlockPos> only) {
        final DecorLedger.GroupView group = ledger.group(colony, owner);
        if (group == null) {
            return Removed.NONE;
        }
        final List<Map.Entry<BlockPos, DecorLedger.Entry>> work = new ArrayList<>();
        for (final Map.Entry<BlockPos, DecorLedger.Entry> e : group.entries().entrySet()) {
            if (only.test(e.getKey())) {
                work.add(e);
            }
        }
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
            if (now == entry.state() || leafDistanceOnly(entry.state(), now)) {
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
        final BlockState air = Blocks.AIR.defaultBlockState();
        final BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();
        for (final BlockPos pos : cleared) {
            for (final Direction direction : Direction.values()) {
                next.setWithOffset(pos, direction);
                // a cell still listed comes down later as listed; a chunk that is not loaded is never loaded for this (S9 review, H9c)
                if (!level.hasChunkAt(next) || ledger.at(next) != null) {
                    continue;
                }
                level.neighborShapeChanged(direction.getOpposite(), air, next, pos, Block.UPDATE_ALL, Block.UPDATE_LIMIT);
                level.neighborChanged(next, Blocks.AIR, pos);
            }
        }
        ledger.dropGroup(colony, owner);
        return new Removed(removed, forgotten, waiting, left);
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
            if (!level.hasChunkAt(e.getKey())) {
                continue;
            }
            final BlockState recorded = e.getValue().state();
            final BlockState now = level.getBlockState(e.getKey());
            if (now != recorded && leafDistanceOnly(recorded, now)) {
                ledger.restate(e.getKey(), now);
                followed++;
            }
        }
        return followed;
    }

    /**
     * True when {@code recorded} is a vanilla leaf (a {@link LeavesBlock} with {@code distance}) and {@code now} is the
     * same block whose only difference from it is {@code distance}: {@code persistent}, {@code waterlogged} and every
     * other property as recorded. Such a leaf is still the one we put there (a neighbour changed and it re-read its
     * distance); the take-down removes it like an exact match (the owner's answer of 20 Nov 2026).
     */
    public static boolean leafDistanceOnly(final BlockState recorded, final BlockState now) {
        return recorded != null && now != null && recorded.getBlock() instanceof LeavesBlock && recorded.hasProperty(LeavesBlock.DISTANCE)
                && now.getBlock() == recorded.getBlock()
                && now.setValue(LeavesBlock.DISTANCE, recorded.getValue(LeavesBlock.DISTANCE)) == recorded;
    }

    /**
     * A vanilla block of a free piece in the way of an explosion that destroys blocks ({@link DecorDrops}, the owner's
     * answer of 20 Nov 2026): set to air with no drops a moment before the explosion would have broken it. Its entry stays
     * listed, exactly as after a player broke it.
     */
    static void clearBlown(final ServerLevel level, final BlockPos pos) {
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
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
