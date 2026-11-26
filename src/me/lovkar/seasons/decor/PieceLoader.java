package me.lovkar.seasons.decor;

import com.ldtteam.structurize.api.RotationMirror;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.blueprints.v1.BlueprintTagUtils;
import com.ldtteam.structurize.storage.StructurePacks;
import com.ldtteam.structurize.util.BlockInfo;
import me.lovkar.seasons.ColonySeasons;
import me.lovkar.seasons.block.FestivalTrunkBlock;
import me.lovkar.seasons.block.FireworkCrateBlock;
import me.lovkar.seasons.block.GiftPileBlock;
import me.lovkar.seasons.block.LargeStarBlock;
import me.lovkar.seasons.block.OrnamentLeavesBlock;
import me.lovkar.seasons.block.TreeStarBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The square's piece: a Structurize blueprint of our own pack, placed by our own code block by block at the
 * player's festival marker (design 3.5).
 *
 * <p>Contract with the pieces (chunk S5): pack "Colony Seasons"; {@link #pathFor(SquareSets.Set, int)} names the file
 * per set and level (0.2.0, Q3: the Winter Festival keeps the files of 0.1.0); a piece is drawn facing north and the
 * marker's facing turns it, or on a Town Square the square's own rotation ({@link #forSquare}: its mirror stripped, so
 * the square's set equals the marker's cell for cell); its primary offset is the marker's cell, which is
 * air in the piece (the marker is never replaced); position tags {@code gift_pile} (skipped below hut level 2),
 * {@code firework_crate} (below level 3) and {@code star}; trunk cells are our {@link FestivalTrunkBlock} (S10c: a
 * spruce log to look at, in no log tag, so MineColonies' Forester never cuts the tree), or a log block of an older or
 * a pack maker's piece.</p>
 *
 * <p>Loading never blocks: {@code getBlueprintFuture} reads on Structurize's own threads, and the result is turned
 * and read on the server thread in one go, because the pack hands out one cached Blueprint per file that other
 * callers may turn too. A missing pack or file is one warning and no tree, tried again on a later game day.</p>
 *
 * <p>World position of a cell = marker - primary offset + cell position, both read after {@code setRotationMirror}
 * (design 3.5 [V]; the placing probe proves it in all four facings against an independent rotation). A cached piece is
 * keyed by pack, path and {@link RotationMirror}; the marker passes {@link #rotation(Direction)}.</p>
 */
public final class PieceLoader {

    public static final String PACK = "Colony Seasons";
    public static final String TAG_GIFT_PILE = "gift_pile";
    public static final String TAG_FIREWORK_CRATE = "firework_crate";
    public static final String TAG_STAR = "star";
    /** Hut level from which the gift pile and the firework crate are placed. */
    public static final int GIFT_PILE_LEVEL = 2;
    public static final int FIREWORK_CRATE_LEVEL = 3;
    /**
     * The "piece" of a Town Square group that holds only the festival's fixtures (0.2.0, Q4, design 4.4: the gift pile
     * at the square's {@code gift} cell and the crate at its {@code launch} cell are placed for an active festival under
     * every order, Bare included): no file, no cells of its own; {@link #request} answers {@link #FIXTURES_PIECE}.
     */
    public static final String FIXTURES_ONLY = "fixtures/winter_festival";
    /** At most this share of a piece's cells may be blocked for the rest to be placed (and no trunk cell). */
    public static final double MAX_BLOCKED_SHARE = 0.10;

    /**
     * One cell of a turned piece, relative to the marker. {@code tree}: a cell of the tree itself (trunk, leaves,
     * ornament and tinsel leaves, star), as opposed to what stands round it (gift piles, the crate, the planter, its
     * lanterns): the tree is ready to light once these are up (S10c).
     */
    public record PieceCell(BlockPos rel, BlockState state, boolean trunk, boolean giftPile, boolean fireworkCrate, boolean star, boolean tree) {
    }

    /**
     * A piece turned by one rotation. {@code tags} says whether the anchor carried position tags; {@code min} and
     * {@code max} are the corners of its cells, relative to the marker (the square's box, S10c).
     */
    public record Piece(String pack, String path, RotationMirror rotation, List<PieceCell> cells, BlockPos offset, boolean tags,
                        BlockPos min, BlockPos max) {
    }

    /**
     * What the square needs now. {@code gone}: cells this group lists that are air now, because a block of the piece
     * went without telling the ledger (vanilla leaves have no {@code onRemove} of ours); they are among the slots, and
     * the visit forgets their entries before it places them again. {@code treeSlots}: the slots that are cells of the
     * tree itself ({@link PieceCell#tree}); none of them left open means the tree is ready (S10c).
     */
    public record SquarePlan(List<DecorPlacer.Slot> slots, int cells, int blocked, boolean trunkBlocked, boolean fits,
                             boolean waiting, int ours, List<BlockPos> gone, Set<BlockPos> treeSlots) {
        public static final SquarePlan WAITING = new SquarePlan(List.of(), 0, 0, false, false, true, 0, List.of(), Set.of());
    }

    /** The piece of {@link #FIXTURES_ONLY}: no cells, its box the anchor's cell. */
    public static final Piece FIXTURES_PIECE = new Piece(PACK, FIXTURES_ONLY, RotationMirror.NONE, List.of(), BlockPos.ZERO, false,
            BlockPos.ZERO, BlockPos.ZERO);

    record Key(String pack, String path, RotationMirror rotation) {
    }

    /** null value: loading; MISSING: not there (with the game day it was found missing). */
    private record Cached(Piece piece, long missingDay) {
    }

    private static final Map<Key, Cached> CACHE = new ConcurrentHashMap<>();
    private static final Set<Key> LOADING = ConcurrentHashMap.newKeySet();
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private PieceLoader() {
    }

    /** The Winter Festival's tree for a level: the festival marker's piece since 0.1.0 (the same files). */
    public static String pathFor(final int hutLevel) {
        return pathFor(SquareSets.WINTER, hutLevel);
    }

    /**
     * The centrepiece file of a set for a level (clamped to 1..5), or {@code null} when there is no set or the set has
     * no centrepiece in this version (the seasonal touches).
     */
    public static String pathFor(final SquareSets.Set set, final int level) {
        return set == null ? null : set.centrepiece(level);
    }

    /** The marker's facing as the rotation its piece is turned by. */
    public static RotationMirror rotation(final Direction facing) {
        return switch (facing) {
            case EAST -> RotationMirror.R90;
            case SOUTH -> RotationMirror.R180;
            case WEST -> RotationMirror.R270;
            default -> RotationMirror.NONE;
        };
    }

    /**
     * The rotation a Town Square's set is turned by: the square's own with its mirror stripped ([V] design 4.6, Q3: the
     * fallback, taken so the square's set equals the marker's version of that rotation cell for cell; the tree is near
     * symmetric, and a mirror would only move the gift piles and the crate to the other side).
     */
    public static RotationMirror forSquare(final RotationMirror square) {
        if (square == null) {
            return RotationMirror.NONE;
        }
        return square.isMirrored() ? RotationMirror.of(square.rotation(), Mirror.NONE) : square;
    }

    /** The facing a rotation turns north to: the inverse of {@link #rotation(Direction)} (a mirror is ignored). */
    public static Direction facing(final RotationMirror rotation) {
        if (rotation == null) {
            return Direction.NORTH;
        }
        return switch (rotation.rotation()) {
            case CLOCKWISE_90 -> Direction.EAST;
            case CLOCKWISE_180 -> Direction.SOUTH;
            case COUNTERCLOCKWISE_90 -> Direction.WEST;
            default -> Direction.NORTH;
        };
    }

    /** Forgets every cached piece (server stop; a /reload of the packs). */
    public static void clear() {
        CACHE.clear();
        LOADING.clear();
        WARNED.clear();
    }

    /** True when the piece is known to be missing (until it is tried again). */
    public static boolean missing(final String pack, final String path, final Direction facing) {
        return missing(pack, path, rotation(facing));
    }

    public static boolean missing(final String pack, final String path, final RotationMirror rotation) {
        if (FIXTURES_ONLY.equals(path)) {
            return false;
        }
        final Cached cached = CACHE.get(new Key(pack, path, rotation));
        return cached != null && cached.piece() == null;
    }

    /** {@link #request(ServerLevel, String, String, RotationMirror)} for a marker's facing. */
    public static Piece request(final ServerLevel level, final String pack, final String path, final Direction facing) {
        return request(level, pack, path, rotation(facing));
    }

    /**
     * The piece turned by {@code rotation}, or {@code null} while it loads or when it is missing (then one warning,
     * and it is asked for again on a later game day).
     */
    public static Piece request(final ServerLevel level, final String pack, final String path, final RotationMirror rotation) {
        if (FIXTURES_ONLY.equals(path)) {
            return FIXTURES_PIECE;                    // a Town Square's fixtures only: nothing to load
        }
        final Key key = new Key(pack, path, rotation);
        final long day = level.getDayTime() / 24000L;
        final Cached cached = CACHE.get(key);
        if (cached != null) {
            if (cached.piece() != null || cached.missingDay() == day) {
                return cached.piece();
            }
            CACHE.remove(key);                       // missing since an earlier day: try again
        }
        if (!LOADING.add(key)) {
            return null;
        }
        final MinecraftServer server = level.getServer();
        try {
            StructurePacks.getBlueprintFuture(pack, path, level.registryAccess()).whenComplete((blueprint, failure) ->
                    server.execute(() -> loaded(level, key, blueprint, failure, day)));
        } catch (final RuntimeException failure) {
            loaded(level, key, null, failure, day);
        }
        return null;
    }

    /** On the server thread: turn the shared blueprint and copy what we need in one go. */
    private static void loaded(final ServerLevel level, final Key key, final Blueprint blueprint, final Throwable failure, final long day) {
        LOADING.remove(key);
        Piece piece = null;
        if (blueprint != null && failure == null) {
            try {
                piece = read(level, blueprint, key);
            } catch (final RuntimeException unreadable) {
                warn(key, "it could not be read (" + unreadable.getClass().getSimpleName().replace("Exception", "") + ")");
            }
        } else {
            warn(key, failure == null ? "it is not in the pack" : "it could not be loaded ("
                    + failure.getClass().getSimpleName().replace("Exception", "") + ")");
        }
        CACHE.put(key, new Cached(piece, day));
    }

    private static void warn(final Key key, final String why) {
        if (WARNED.add(key.pack() + "/" + key.path())) {
            ColonySeasons.LOGGER.warn("Festival piece {} of pack '{}' is missing, so there is no tree: {}", key.path(), key.pack(), why);
        }
    }

    /**
     * A piece read at once from a blueprint of the caller's own (it is turned here), as {@link #request} reads it; not
     * cached. For the probes, which compare it with an independent turn.
     */
    public static Piece readNow(final ServerLevel level, final Blueprint blueprint, final String pack, final String path,
                                final RotationMirror rotation) {
        return read(level, blueprint, new Key(pack, path, rotation));
    }

    /** The cells of the piece turned by the key's rotation, relative to the anchor (the marker's cell). */
    static Piece read(final ServerLevel level, final Blueprint blueprint, final Key key) {
        // tags are relative to the anchor in the blueprint's own frame: read them unturned, then turn them ourselves
        blueprint.setRotationMirror(RotationMirror.NONE, level);
        final Map<BlockPos, List<String>> tagMap = tags(blueprint);
        final RotationMirror turn = key.rotation();
        final Map<String, Set<BlockPos>> tagged = new HashMap<>();
        for (final Map.Entry<BlockPos, List<String>> e : tagMap.entrySet()) {
            final BlockPos rel = turn.applyToPos(e.getKey());
            for (final String name : e.getValue()) {
                tagged.computeIfAbsent(name, n -> new HashSet<>()).add(rel);
            }
        }
        blueprint.setRotationMirror(turn, level);
        final BlockPos offset = blueprint.getPrimaryBlockOffset();
        final List<PieceCell> cells = new ArrayList<>();
        int x0 = 0;
        int y0 = 0;
        int z0 = 0;
        int x1 = 0;
        int y1 = 0;
        int z1 = 0;
        for (final BlockInfo info : blueprint.getBlockInfoAsList()) {
            final BlockState state = info.getState();
            if (state == null || state.isAir() || isSubstitution(state)) {
                continue;
            }
            final BlockPos rel = info.getPos().subtract(offset);
            if (rel.equals(BlockPos.ZERO)) {
                continue;                             // the marker's own cell is never touched
            }
            final boolean gift = state.getBlock() instanceof GiftPileBlock || has(tagged, TAG_GIFT_PILE, rel);
            final boolean crate = state.getBlock() instanceof FireworkCrateBlock || has(tagged, TAG_FIREWORK_CRATE, rel);
            final boolean star = has(tagged, TAG_STAR, rel);
            final boolean trunk = isTrunk(state);
            cells.add(new PieceCell(rel, state, trunk, gift, crate, star, trunk || star || isTreeBlock(state)));
            x0 = Math.min(x0, rel.getX());
            y0 = Math.min(y0, rel.getY());
            z0 = Math.min(z0, rel.getZ());
            x1 = Math.max(x1, rel.getX());
            y1 = Math.max(y1, rel.getY());
            z1 = Math.max(z1, rel.getZ());
        }
        return new Piece(key.pack(), key.path(), key.rotation(), List.copyOf(cells), offset, !tagMap.isEmpty(),
                new BlockPos(x0, y0, z0), new BlockPos(x1, y1, z1));
    }

    /** A trunk cell: our festival trunk, or a log block (an older piece, or a pack maker's own). */
    static boolean isTrunk(final BlockState state) {
        return state.getBlock() instanceof FestivalTrunkBlock || state.is(BlockTags.LOGS);
    }

    /** A block of the tree itself besides the trunk: leaves (vanilla or ours) and the stars. */
    static boolean isTreeBlock(final BlockState state) {
        return state.getBlock() instanceof LeavesBlock || state.getBlock() instanceof OrnamentLeavesBlock
                || state.getBlock() instanceof TreeStarBlock || state.getBlock() instanceof LargeStarBlock;
    }

    private static boolean has(final Map<String, Set<BlockPos>> tagged, final String name, final BlockPos rel) {
        final Set<BlockPos> set = tagged.get(name);
        return set != null && set.contains(rel);
    }

    private static Map<BlockPos, List<String>> tags(final Blueprint blueprint) {
        try {
            final Map<BlockPos, List<String>> map = BlueprintTagUtils.getBlueprintTags(blueprint);
            return map == null ? Map.of() : map;
        } catch (final RuntimeException none) {
            return Map.of();
        }
    }

    /** Structurize's placeholder blocks ("leave the world as it is" and friends) are never placed. */
    static boolean isSubstitution(final BlockState state) {
        final ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return "structurize".equals(id.getNamespace()) && id.getPath().contains("substitution");
    }

    // ---------------------------------------------------------------- planning the square

    /**
     * What the square needs: every cell of the piece for this hut level that is not placed yet, trunk first, then
     * bottom up. A cell is blocked when it is outside the claim or holds something that is not our own entry. A cell
     * this group lists that is air now went missing (tending puts it back): it is a slot, and it is named in
     * {@link SquarePlan#gone()} so the visit forgets its stale entry first. Reads only; the ledger is not changed here.
     *
     * @param costs     whether cells cost items (decorator.needsMaterials)
     */
    public static SquarePlan plan(final ServerLevel level, final Piece piece, final BlockPos marker, final int hutLevel,
                                  final DecorLedger ledger, final int colony, final DecorLedger.Owner owner, final boolean costs) {
        return plan(level, piece, marker, hutLevel, ledger, colony, owner, costs, List.of());
    }

    /**
     * {@link #plan(ServerLevel, Piece, BlockPos, int, DecorLedger, int, DecorLedger.Owner, boolean)} with a Town
     * Square's fixtures ({@code fixtures}, relative to the anchor, already chosen for the level): they are planned after
     * the piece's own cells like any of them, and a fixture crate (the square's {@code launch} cell) stands instead of
     * the piece's own crate (one crate, where the rockets leave from). No fixtures: the 0.1.0 plan.
     */
    public static SquarePlan plan(final ServerLevel level, final Piece piece, final BlockPos marker, final int hutLevel,
                                  final DecorLedger ledger, final int colony, final DecorLedger.Owner owner, final boolean costs,
                                  final List<PieceCell> fixtures) {
        final List<DecorPlacer.Slot> slots = new ArrayList<>();
        final List<BlockPos> gone = new ArrayList<>();
        final Set<BlockPos> treeSlots = new HashSet<>();
        int cells = 0;
        int blocked = 0;
        int ours = 0;
        boolean trunkBlocked = false;
        final List<PieceCell> order = new ArrayList<>(piece.cells());
        order.sort(Comparator.comparing((PieceCell c) -> !c.trunk()).thenComparingInt(c -> c.rel().getY())
                .thenComparing(PieceCell::rel));
        boolean ownCrate = true;
        for (final PieceCell fixture : fixtures) {
            ownCrate &= !fixture.fireworkCrate();
        }
        final int pieceCells = order.size();
        order.addAll(fixtures);
        for (int i = 0; i < order.size(); i++) {
            final PieceCell cell = order.get(i);
            final boolean fixture = i >= pieceCells;
            if (!fixture && (cell.giftPile() && hutLevel < GIFT_PILE_LEVEL || cell.fireworkCrate() && (hutLevel < FIREWORK_CRATE_LEVEL || !ownCrate))) {
                continue;
            }
            final BlockPos pos = marker.offset(cell.rel());
            if (!level.hasChunkAt(pos)) {
                return SquarePlan.WAITING;
            }
            cells++;
            final BlockState wanted = DecorPlacer.normalise(cell.state(), pos);
            final BlockState now = level.getBlockState(pos);
            final DecorLedger.Located at = ledger.at(pos);
            if (at != null && at.colony() == colony && at.owner().equals(owner) && at.entry().state() == now) {
                ours++;
                continue;
            }
            // listed by this very group and air now: ours went missing (a player broke a log); any other entry blocks
            final boolean missing = at != null && now.isAir() && at.colony() == colony && at.owner().equals(owner);
            if (!now.isAir() || at != null && !missing || DecorPlacer.colonyAt(level, pos) != colony) {
                blocked++;
                trunkBlocked |= cell.trunk();
                continue;
            }
            if (missing) {
                gone.add(pos.immutable());
            }
            slots.add(new DecorPlacer.Slot(pos, wanted, costs && costsItem(wanted)));
            if (cell.tree()) {
                treeSlots.add(pos.immutable());
            }
        }
        final boolean fits = cells > 0 && !trunkBlocked && blocked <= Math.floor(cells * MAX_BLOCKED_SHARE);
        return new SquarePlan(List.copyOf(slots), cells, blocked, trunkBlocked, fits, false, ours, List.copyOf(gone), Set.copyOf(treeSlots));
    }

    /** A square cell costs one item of its block if that item is vanilla or a kit's output; our other blocks are free in 0.1.0. */
    public static boolean costsItem(final BlockState state) {
        final Item item = DecorPlacer.itemOf(state);
        if (item == null) {
            return false;
        }
        if ("minecraft".equals(BuiltInRegistries.ITEM.getKey(item).getNamespace())) {
            return true;
        }
        for (final Kits.Kit kit : Kits.all()) {
            if (kit.output() == item) {
                return true;
            }
        }
        return false;
    }
}
