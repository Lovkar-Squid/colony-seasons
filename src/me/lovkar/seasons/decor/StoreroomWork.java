package me.lovkar.seasons.decor;

import com.ldtteam.structurize.api.RotationMirror;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import me.lovkar.seasons.SeasonsConfig;
import me.lovkar.seasons.block.SeasonsBlocks;
import me.lovkar.seasons.block.StorageBoxBlock;
import me.lovkar.seasons.block.StoragePieceBlock;
import me.lovkar.seasons.block.WallDecorationBlock;
import me.lovkar.seasons.colony.DecoratorTexts;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The storeroom in the Decorator's Workshop (S10g2, docs/storage-look-design.md 3.2 to 3.4, variant A): a target state
 * worked out from his year and the hut alone, and one check that brings the room to it. The room is the ledger's
 * {@link DecorLedger.Kind#STOREROOM} group of the Workshop; its spots are the blueprint's position tags
 * ({@link StoreroomSpots}).
 *
 * <ul>
 *   <li>{@link #target}: the festival set while his year is in a festival phase (decorating, hosting, or taking down
 *       while the outside decorations are still up), the packed set otherwise and whenever the hut's "decorate" setting
 *       is off;</li>
 *   <li>{@link #due}: the room has not been checked today for this target and this Workshop level;</li>
 *   <li>{@link #check}: entries the world changed are forgotten; every listed piece that is not in the target comes off
 *       by the removal rule (exact match); then every target piece that is missing goes into air, for free, with its
 *       facing worked out from the walls ({@link #facing}). A swap (a box from full to nearly empty) is a removal and a
 *       placing; no state is edited in place.</li>
 * </ul>
 *
 * <p>Nothing here sets a block itself: every change goes through {@link DecorPlacer} (the cell rule, the removal rule,
 * the ledger before the block). Nothing here keeps state: the room's progress is its ledger group, whose
 * {@code festival}, {@code level} and {@code tendedDay} say what the last complete check left there
 * ({@link DecorLedger#setRoom}). The check never charges the day's building budget, never marks a group decorated,
 * never touches "decorations up" and never takes an item from the hut or gives one to it: the pieces are free, so
 * they drop nothing when broken.</p>
 */
public final class StoreroomWork {

    /**
     * What one check did: blocks set to air, entries forgotten because the world had changed, blocks placed, whether
     * the room is now the target (nothing left by the budget, nothing waiting for a chunk), and the status line to show
     * (null when no block changed, so an existing line is not overwritten).
     */
    public record Result(int removed, int forgotten, int placed, boolean complete, @Nullable String status) {
        public static final Result NONE = new Result(0, 0, 0, false, null);

        /** Blocks set or cleared. */
        public int changes() {
            return removed + placed;
        }
    }

    /** The sides a piece looks at for its wall, in the building's own frame: north, east, west, south (decorator_hut._SIDES). */
    private static final List<BlockPos> SIDES = List.of(new BlockPos(0, 0, -1), new BlockPos(1, 0, 0), new BlockPos(-1, 0, 0),
            new BlockPos(0, 0, 1));
    /** The way a standing piece faces with no wall beside it: south, into the room, in the building's frame. */
    private static final BlockPos INTO_ROOM = new BlockPos(0, 0, 1);
    /** What a packed piece stands on and the racks: furniture, never a wall (decorator_hut.FURNITURE and RACK). */
    private static final Set<String> FURNITURE = Set.of("minecraft:barrel", "minecolonies:blockminecoloniesrack");
    /** Names of thin or partial blocks that are no wall to hang on (decorator_hut._NOT_FULL, word for word). */
    private static final List<String> NOT_FULL = List.of("lantern", "ladder", "glass", "_pane", "_bars", "chain", "torch",
            "stonecutter", "_door", "_trapdoor", "_stairs", "_slab", "_carpet", "_fence", "_wall");

    private StoreroomWork() {
    }

    // ---------------------------------------------------------------- the target

    /**
     * The set the room should show ({@code ""} for the packed set): the festival set of {@code festivalId} (its set,
     * {@link StoreroomSpots#setOf}) in {@link DecorPhase#DECORATING}, {@link DecorPhase#HOSTING} and
     * {@link DecorPhase#TAKING_DOWN}; the packed set in {@link DecorPhase#OFF_SEASON} and whenever the hut's "decorate"
     * setting is off. While taking down with no window open ({@code festivalId} ""), a room that shows a festival set
     * keeps that set until the take-down is over, so a later festival's room is never swapped to another set's on the way.
     *
     * @param room the room's group, read only in {@link DecorPhase#TAKING_DOWN} (may be null)
     */
    public static String target(final DecorPhase phase, final boolean decorateEnabled, final String festivalId,
                                final DecorLedger.@Nullable GroupView room) {
        if (phase == null || phase == DecorPhase.OFF_SEASON || !decorateEnabled) {
            return "";
        }
        final boolean noWindow = festivalId == null || festivalId.isEmpty();
        if (phase == DecorPhase.TAKING_DOWN && noWindow && room != null && !room.festival().isEmpty()) {
            return room.festival();
        }
        return StoreroomSpots.setOf(festivalId);
    }

    /**
     * True when the room wants a check: the switch is on, its group is not marked for the sweep, and there is no group
     * yet, or the last complete check was for another target or another Workshop level, or on an earlier game day.
     * Reads the group in place ({@link DecorLedger#roomMark}).
     */
    public static boolean due(final DecorLedger ledger, final int colony, final BlockPos hut, final String target,
                              final int hutLevel, final long day) {
        if (!SeasonsConfig.storeroomDressing()) {
            return false;
        }
        final DecorLedger.RoomMark mark = ledger.roomMark(colony, DecorLedger.Owner.storeroom(hut));
        if (mark == null) {
            return true;
        }
        if (mark.removing()) {
            return false;
        }
        return !mark.festival().equals(target == null ? "" : target) || mark.level() != hutLevel || mark.tendedDay() < day;
    }

    /** True when the group shows a festival set: a hung piece, or a box nearly empty. */
    public static boolean holdsFestival(final DecorLedger.GroupView group) {
        if (group == null) {
            return false;
        }
        for (final DecorLedger.Entry entry : group.entries().values()) {
            final BlockState state = entry.state();
            if (state.getBlock() instanceof WallDecorationBlock) {
                return true;
            }
            if (state.getBlock() == SeasonsBlocks.STORAGE_BOX.get() && state.getValue(StorageBoxBlock.FILL) == StorageBoxBlock.Fill.LOW) {
                return true;
            }
        }
        return false;
    }

    /** True when the Workshop at {@code hut} has a worker (the sweep's per-hut rule for a festive room after the window). */
    public static boolean staffed(final IColony colony, final BlockPos hut) {
        final IBuilding building = colony == null ? null : colony.getServerBuildingManager().getBuilding(hut);
        if (building == null) {
            return false;
        }
        for (final ICitizenData citizen : building.getAllAssignedCitizen()) {
            if (citizen != null) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- the check

    /**
     * One check of the room of the Workshop {@code hut} against {@code target} ({@link #target}), at most
     * {@code maxChanges} blocks removed, forgotten or placed. In this order:
     * <ol>
     *   <li>the switch off, the hut's chunk or block entity not loaded, or the group marked for the sweep: nothing;</li>
     *   <li>the Workshop under construction (an upgrade or repair, design 3.2): every listed piece comes off by the rule,
     *       nothing is placed (storage-look 3.4);</li>
     *   <li>no spot read from the hut: nothing, and not complete (an empty read is never a packed room with nothing in
     *       it);</li>
     *   <li>entries whose block the world changed are forgotten, the world left alone;</li>
     *   <li>a listed piece is kept when the target has its block at its position (facing and lit aside: a lit string of
     *       lights or a wall that changed beside a piece is no reason to take it off and put it back); every other one
     *       comes off by the removal rule;</li>
     *   <li>every target piece that is missing is placed into air, free, facing away from its wall.</li>
     * </ol>
     * A complete check notes its target, the level and the day in the group ({@link DecorLedger#setRoom}).
     */
    public static Result check(final ServerLevel level, final IColony colony, final IBuilding hut, final String target,
                               final DecorLedger ledger, final long day, final int maxChanges) {
        if (!SeasonsConfig.storeroomDressing() || colony == null || hut == null) {
            return Result.NONE;
        }
        final BlockPos hutPos = hut.getPosition();
        if (!level.hasChunkAt(hutPos) || hut.getTileEntity() == null) {
            return Result.NONE;
        }
        final int id = colony.getID();
        final DecorLedger.Owner owner = DecorLedger.Owner.storeroom(hutPos);
        final String want = target == null ? "" : target;
        final int[] lost = new int[1];
        final DecorPlacer.Stock sink = DecorWork.sinkOf(colony, lost);
        if (DecorWork.busy(colony, hut)) {
            // design 3.2 / storage-look 3.4: off before the Builder's clearing stage gets there; nothing goes up meanwhile
            if (ledger.group(id, owner) == null) {
                return Result.NONE;
            }
            final DecorPlacer.Removed off = DecorPlacer.remove(level, ledger, id, owner, sink, Math.max(0, maxChanges));
            ledger.flush(level);
            return new Result(off.removed(), off.forgotten(), 0, false, off.removed() > 0 ? DecoratorTexts.ROOM_PACKING : null);
        }
        final DecorLedger.RoomMark mark = ledger.roomMark(id, owner);
        if (mark != null && mark.removing()) {
            return Result.NONE;
        }
        final Map<BlockPos, StoreroomSpots.Spot> spots = new LinkedHashMap<>();
        for (final String set : StoreroomSpots.SETS) {
            for (final StoreroomSpots.Spot spot : StoreroomSpots.read(hut, set)) {
                spots.putIfAbsent(spot.pos(), spot);
            }
        }
        if (spots.isEmpty()) {
            return Result.NONE;
        }
        int budget = Math.max(0, maxChanges);

        // the world changed under a listed piece (a player, the Builder): forget it, leave the world alone (design 3.3)
        int forgotten = 0;
        DecorLedger.GroupView group = ledger.group(id, owner);
        if (group != null) {
            for (final Map.Entry<BlockPos, DecorLedger.Entry> e : group.entries().entrySet()) {
                if (budget <= 0) {
                    break;
                }
                if (level.hasChunkAt(e.getKey()) && level.getBlockState(e.getKey()) != e.getValue().state()) {
                    ledger.forget(e.getKey());
                    forgotten++;
                    budget--;
                }
            }
        }

        // the target: every spot's block in its state (a spot of the target's set in the festival, every other packed)
        final Map<BlockPos, BlockState> desired = new LinkedHashMap<>();
        for (final StoreroomSpots.Spot spot : spots.values()) {
            final BlockState state = StoreroomSpots.state(spot, spot.set().equals(want));
            if (state != null) {
                desired.put(spot.pos(), state);
            }
        }

        // what stays and what comes off
        group = ledger.group(id, owner);
        final Set<BlockPos> keep = new HashSet<>();
        final Set<BlockPos> off = new HashSet<>();
        if (group != null) {
            for (final Map.Entry<BlockPos, DecorLedger.Entry> e : group.entries().entrySet()) {
                final BlockState wanted = desired.get(e.getKey());
                if (wanted != null && samePiece(e.getValue().state(), wanted)) {
                    keep.add(e.getKey());
                } else {
                    off.add(e.getKey());
                }
            }
        }
        DecorPlacer.Removed removed = DecorPlacer.Removed.NONE;
        if (!off.isEmpty()) {
            removed = DecorPlacer.remove(level, ledger, id, owner, sink, budget, off::contains);
            budget -= removed.touched();
        }

        // what is missing goes into air, free, facing away from its wall
        int waiting = removed.waiting() + removed.left();
        final List<DecorPlacer.Slot> slots = new ArrayList<>();
        int skippedForBudget = 0;
        final int hutLevel = hut.getBuildingLevel();
        for (final Map.Entry<BlockPos, BlockState> e : desired.entrySet()) {
            final BlockPos pos = e.getKey();
            if (keep.contains(pos)) {
                continue;
            }
            final DecorLedger.Located listed = ledger.at(pos);
            if (listed != null && listed.colony() == id && listed.owner().equals(owner)) {
                continue;                             // still ours and waiting to come off (its chunk, the budget)
            }
            if (!sidesLoaded(level, pos)) {
                waiting++;                            // the wall may sit across a chunk border: never load it to look
                continue;
            }
            final StoreroomSpots.Spot spot = spots.get(pos);
            final Direction facing = facing(level, hut, ledger, pos, spot.kind());
            if (facing == null) {
                continue;                             // a hung piece with no wall beside it: nothing to hang it on
            }
            if (slots.size() >= budget) {
                skippedForBudget++;
                continue;
            }
            slots.add(new DecorPlacer.Slot(pos, e.getValue().setValue(BlockStateProperties.HORIZONTAL_FACING, facing), false));
        }
        int placed = 0;
        if (!slots.isEmpty()) {
            final DecorPlacer.PlaceResult result = DecorPlacer.place(level, ledger, id, owner, want, hutLevel, slots, DecorPlacer.Stock.FREE);
            placed = result.count();
            for (final String why : result.refused().values()) {
                if ("unloaded".equals(why)) {
                    waiting++;
                }
            }
        }
        final boolean complete = waiting == 0 && skippedForBudget == 0;
        if (complete) {
            ledger.setRoom(id, owner, want, hutLevel, day);
        }
        ledger.flush(level);
        final String status = removed.removed() + placed == 0 ? null
                : want.isEmpty() ? DecoratorTexts.ROOM_PACKING : DecoratorTexts.ROOM_UNPACKING;
        return new Result(removed.removed(), forgotten + removed.forgotten(), placed, complete, status);
    }

    /** The same block with the same properties, facing and lit aside (the compare of storage-look 3.2's check). */
    static boolean samePiece(final BlockState listed, final BlockState wanted) {
        if (listed.getBlock() != wanted.getBlock()) {
            return false;
        }
        for (final Property<?> property : listed.getProperties()) {
            if (property == BlockStateProperties.HORIZONTAL_FACING || property == BlockStateProperties.LIT) {
                continue;
            }
            if (!listed.getValue(property).equals(wanted.getValue(property))) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- the facing

    /** Every cell {@link #facing} looks at is in a loaded chunk. */
    private static boolean sidesLoaded(final ServerLevel level, final BlockPos pos) {
        for (final Direction direction : Direction.Plane.HORIZONTAL) {
            if (!level.hasChunkAt(pos.relative(direction))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The way the piece at {@code pos} faces (tools/decorator_hut.spot_facing, storage-look 3.5): away from the first
     * whole wall block beside it, looking north, east, west and south in the building's own frame (the Workshop's
     * rotation and mirror applied as {@code getLocationsFromTag} applies them); a standing piece with no such wall faces
     * south in the building's frame, into the room; a hung piece with none has no facing ({@code null}). A side in a
     * chunk that is not loaded is never loaded to look at it: it counts as no wall.
     */
    public static @Nullable Direction facing(final ServerLevel level, final IBuilding hut, final DecorLedger ledger, final BlockPos pos,
                                             final StoreroomSpots.Kind kind) {
        final RotationMirror turn = hut.getRotationMirror() == null ? RotationMirror.NONE : hut.getRotationMirror();
        for (final BlockPos local : SIDES) {
            final BlockPos offset = turn.applyToPos(local);
            final BlockPos side = pos.offset(offset);
            if (level.hasChunkAt(side) && wholeWall(level, ledger, side)) {
                return Direction.fromDelta(-offset.getX(), -offset.getY(), -offset.getZ());
            }
        }
        if (kind == StoreroomSpots.Kind.HANG) {
            return null;
        }
        final BlockPos into = turn.applyToPos(INTO_ROOM);
        return Direction.fromDelta(into.getX(), into.getY(), into.getZ());
    }

    /**
     * A whole block a wall piece can hang on and a standing piece turns its back to (decorator_hut.full_wall_block): not
     * air, not a piece of ours or a cell the ledger lists (the room's own pieces are air in the blueprint), not
     * furniture (a barrel, a rack), and no thin or partial block by its name.
     */
    static boolean wholeWall(final ServerLevel level, final DecorLedger ledger, final BlockPos pos) {
        final BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.getBlock() instanceof StoragePieceBlock || state.getBlock() instanceof WallDecorationBlock
                || ledger.at(pos) != null) {
            return false;
        }
        final String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (FURNITURE.contains(name)) {
            return false;
        }
        for (final String thin : NOT_FULL) {
            if (name.contains(thin)) {
                return false;
            }
        }
        return true;
    }
}
