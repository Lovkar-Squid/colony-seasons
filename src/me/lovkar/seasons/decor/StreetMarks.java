package me.lovkar.seasons.decor;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.permissions.Action;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The spots the player marks with the Decorator's wand (0.2.0, Q11; docs/town-square-design.md "Addendum: the marking
 * wand", docs/IDEAS.md "Street decorations"). A mark says what goes where: snowman A (the default), snowman B, the
 * festive lantern post, a garland across a street between two marked posts, or a seasonal planter. The marks are the
 * colony's, kept in the decoration ledger's record of the colony ({@link DecorLedger#marks}); the Decorator dresses them
 * in every festival window as one more ledger group per mark ({@link DecorLedger.Kind#STREET}, {@link StreetWork}) and
 * takes them down by the ledger rule after it. The marks stay for the next window.
 *
 * <p>A spot the player chose may hold blocks with collision, as the festival marker and the Town Square do (the owner's
 * answer of 20 Nov 2026 for player-chosen places). {@link #add} refuses, each with its own lang key: a cell outside this
 * colony's claim or not loaded, a player without the right to manage huts in that colony, a cell that is not air, is
 * listed in the ledger or is a cell of another mark, a cell inside any building's box (the Town Square's too), a spot
 * within {@link #MARKER_CLEARANCE} blocks of the colony's festival marker, the cap of {@link #MAX_MARKS}, a snowman or a
 * post with no ground under it, and a planter with no sturdy top under it. A body standing in a cell is no reason to
 * refuse: the cell waits when the Decorator comes.</p>
 */
public final class StreetMarks {

    /** Marks per colony at most. */
    public static final int MAX_MARKS = 64;
    /** Air cells between the two posts' upper halves a garland may span, at most (at least one). */
    public static final int GARLAND_MAX = 9;
    /** No mark within this many blocks (on x and on z) of the colony's festival marker: the tree stands there. */
    public static final int MARKER_CLEARANCE = 5;
    /** The ground of a snowman or a post: its collision reaches this high at least (a dirt path's 15 px count). */
    public static final double GROUND_TOP = 15.0 / 16.0;

    /** What goes on a mark; the id is stable (saved in the ledger, sent to the wand's holder). */
    public enum Type {
        SNOWMAN_A("snowman_a"),
        SNOWMAN_B("snowman_b"),
        POST("lantern_post"),
        GARLAND("garland"),
        PLANTER("planter");

        private final String id;

        Type(final String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        /** The wand's next mode (snowman A after the planter). */
        public Type next() {
            return values()[(ordinal() + 1) % values().length];
        }

        /** The type with this id, or {@code null}. */
        public static Type byId(final String id) {
            for (final Type type : values()) {
                if (type.id.equals(id)) {
                    return type;
                }
            }
            return null;
        }

        /** The lang key of the type's name (the wand's lines). */
        public String nameKey() {
            return switch (this) {
                case SNOWMAN_A -> "com.colony_seasons.wand.type.snowman_a";
                case SNOWMAN_B -> "com.colony_seasons.wand.type.snowman_b";
                case POST -> "com.colony_seasons.wand.type.lantern_post";
                case GARLAND -> "com.colony_seasons.wand.type.garland";
                case PLANTER -> "com.colony_seasons.wand.type.planter";
            };
        }
    }

    /**
     * One mark. {@code pos} is its first cell and its key; {@code facing} the way a snowman looks and a post's crook
     * reaches (towards the player who marked it); a garland's {@code facing} is the way from {@code pos} to {@code to},
     * its first and last span cell at the posts' y + 1 ({@code to} is {@code null} for every other type).
     */
    public record Mark(BlockPos pos, Type type, Direction facing, @Nullable BlockPos to) {
        public Mark {
            pos = pos.immutable();
            to = to == null ? null : to.immutable();
            facing = facing == null || facing.getAxis().isVertical() ? Direction.NORTH : facing;
        }
    }

    /** A garland between two posts as {@link #garland} makes it: the mark, or why there is none. */
    public record Garland(@Nullable Mark mark, @Nullable String refusal) {
    }

    private StreetMarks() {
    }

    // ---------------------------------------------------------------- marking

    /**
     * Marks a spot: {@code null} on success (the mark is stored and the ledger written), else the lang key of the
     * refusal and nothing changes. {@code player} {@code null} skips only the permission test (the probe).
     */
    public static String add(final ServerLevel level, final @Nullable ServerPlayer player, final Mark mark) {
        final List<BlockPos> cells = StreetPieces.cells(mark);
        final int colony = DecorPlacer.colonyAt(level, mark.pos());
        if (colony == DecorPlacer.NO_COLONY) {
            return "com.colony_seasons.wand.refused.claim";
        }
        for (final BlockPos cell : cells) {
            if (DecorPlacer.colonyAt(level, cell) != colony) {
                return "com.colony_seasons.wand.refused.claim";
            }
        }
        final IColony owner = colonyOf(level, colony);
        if (owner == null) {
            return "com.colony_seasons.wand.refused.claim";
        }
        if (player != null && !owner.getPermissions().hasPermission(player, Action.MANAGE_HUTS)) {
            return "com.colony_seasons.wand.refused.permission";
        }
        final DecorLedger ledger = DecorLedger.get(level);
        for (final BlockPos cell : cells) {
            if (!level.getBlockState(cell).isAir()) {
                return "com.colony_seasons.wand.refused.occupied";
            }
            if (ledger.at(cell) != null) {
                return "com.colony_seasons.wand.refused.listed";
            }
            if (ledger.markCovering(colony, cell) != null) {
                return "com.colony_seasons.wand.refused.taken";
            }
        }
        final String rule = placeRule(level, owner, ledger, mark);
        if (rule != null) {
            return rule;
        }
        if (ledger.marks(colony).size() >= MAX_MARKS || !ledger.addMark(colony, mark)) {
            return "com.colony_seasons.wand.refused.cap";
        }
        ledger.flush(level);
        return null;
    }

    /**
     * The rules a mark must keep for as long as it stands, also when the Decorator comes to dress it: no cell inside a
     * building's box, none near the festival marker, ground under a snowman and a post, a sturdy top under a planter, a
     * garland's two posts still marked in a line. {@code null} when it keeps them, else the lang key.
     */
    static String placeRule(final ServerLevel level, final IColony colony, final DecorLedger ledger, final Mark mark) {
        final List<BlockPos> cells = StreetPieces.cells(mark);
        final List<DoorFinder.Box> boxes = buildingBoxes(colony);
        for (final BlockPos cell : cells) {
            if (inside(boxes, cell)) {
                return "com.colony_seasons.wand.refused.building";
            }
        }
        final BlockPos marker = ledger.marker(colony.getID());
        if (marker != null) {
            for (final BlockPos cell : cells) {
                if (Math.abs(cell.getX() - marker.getX()) <= MARKER_CLEARANCE && Math.abs(cell.getZ() - marker.getZ()) <= MARKER_CLEARANCE) {
                    return "com.colony_seasons.wand.refused.marker";
                }
            }
        }
        switch (mark.type()) {
            case SNOWMAN_A, SNOWMAN_B, POST -> {
                if (!ground(level, mark.pos().below())) {
                    return "com.colony_seasons.wand.refused.ground";
                }
            }
            case PLANTER -> {
                final BlockPos below = mark.pos().below();
                if (!level.hasChunkAt(below) || !level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
                    return "com.colony_seasons.wand.refused.planter";
                }
            }
            case GARLAND -> {
                final BlockPos[] ends = posts(mark);
                final Mark a = ledger.markAt(colony.getID(), ends[0]);
                final Mark b = ledger.markAt(colony.getID(), ends[1]);
                if (a == null || b == null || a.type() != Type.POST || b.type() != Type.POST) {
                    return "com.colony_seasons.wand.refused.garland";
                }
            }
        }
        return null;
    }

    /** Ground for a snowman or a post: a loaded block whose collision reaches at least 15 px and not above the cell (a dirt path counts, a fence does not). */
    public static boolean ground(final ServerLevel level, final BlockPos below) {
        if (!level.hasChunkAt(below)) {
            return false;
        }
        final BlockState state = level.getBlockState(below);
        final VoxelShape shape = state.getCollisionShape(level, below);
        if (shape.isEmpty()) {
            return false;
        }
        final double top = shape.max(Direction.Axis.Y);
        return top >= GROUND_TOP - 1e-6 && top <= 1.0 + 1e-6;
    }

    /**
     * The garland between two post marks: both posts, at the same height, on one line along x or z, with 1 to
     * {@link #GARLAND_MAX} cells between their upper halves; the span runs through the cells between, at the posts' y + 1.
     */
    public static Garland garland(final Mark first, final Mark second) {
        if (first == null || second == null || first.type() != Type.POST || second.type() != Type.POST) {
            return new Garland(null, "com.colony_seasons.wand.refused.garland");
        }
        final BlockPos a = first.pos();
        final BlockPos b = second.pos();
        final Direction along;
        final int gap;
        if (a.getY() != b.getY()) {
            return new Garland(null, "com.colony_seasons.wand.refused.garland");
        } else if (a.getX() == b.getX() && a.getZ() != b.getZ()) {
            along = Direction.SOUTH;
            gap = Math.abs(a.getZ() - b.getZ()) - 1;
        } else if (a.getZ() == b.getZ() && a.getX() != b.getX()) {
            along = Direction.EAST;
            gap = Math.abs(a.getX() - b.getX()) - 1;
        } else {
            return new Garland(null, "com.colony_seasons.wand.refused.garland");
        }
        if (gap < 1 || gap > GARLAND_MAX) {
            return new Garland(null, "com.colony_seasons.wand.refused.garland");
        }
        final BlockPos low = a.compareTo(b) < 0 ? a : b;
        final BlockPos high = low == a ? b : a;
        return new Garland(new Mark(low.above().relative(along), Type.GARLAND, along, high.above().relative(along.getOpposite())), null);
    }

    /** The first cells (lower halves) of the two posts a garland hangs between. */
    public static BlockPos[] posts(final Mark garland) {
        final BlockPos to = garland.to() == null ? garland.pos() : garland.to();
        return new BlockPos[] {garland.pos().relative(garland.facing().getOpposite()).below(), to.relative(garland.facing()).below()};
    }

    // ---------------------------------------------------------------- removing

    /**
     * Removes the mark whose first cell is {@code pos}: the mark goes, and its pieces come down at once by the exact-state
     * rule (a block the player changed stays and is forgotten; cells in unloaded chunks wait for the sweep). A post takes
     * the garlands hanging on it with it. True when there was such a mark.
     */
    public static boolean remove(final ServerLevel level, final int colony, final BlockPos pos) {
        final DecorLedger ledger = DecorLedger.get(level);
        final Mark mark = ledger.removeMark(colony, pos);
        if (mark == null) {
            return false;
        }
        takeDown(level, ledger, colony, DecorLedger.Owner.street(mark.pos()));
        if (mark.type() == Type.POST) {
            for (final Mark other : ledger.marks(colony)) {
                if (other.type() == Type.GARLAND) {
                    final BlockPos[] ends = posts(other);
                    if (ends[0].equals(mark.pos()) || ends[1].equals(mark.pos())) {
                        remove(level, colony, other.pos());
                    }
                }
            }
        }
        ledger.flush(level);
        return true;
    }

    /** One mark's group comes down now by the rule, items to the colony's Workshop; cells that wait are marked for the sweep. */
    static DecorPlacer.Removed takeDown(final ServerLevel level, final DecorLedger ledger, final int colony, final DecorLedger.Owner owner) {
        final int[] lost = new int[1];
        final DecorPlacer.Removed removed = DecorPlacer.remove(level, ledger, colony, owner, DecorWork.sinkOf(colonyOf(level, colony), lost));
        if (removed.waiting() > 0) {
            ledger.markRemoving(colony, owner);
        }
        return removed;
    }

    /**
     * The mark a wand's click means: one with a piece in the clicked block, one standing on the clicked block (its ground),
     * or one whose cell is in front of the clicked face; {@code null} for none.
     */
    public static Mark near(final DecorLedger ledger, final int colony, final BlockPos clicked, final Direction face) {
        Mark mark = ledger.markCovering(colony, clicked);
        if (mark == null) {
            final Mark above = ledger.markAt(colony, clicked.above());
            mark = above != null && above.type() != Type.GARLAND ? above : null;
        }
        if (mark == null) {
            mark = ledger.markCovering(colony, clicked.relative(face));
        }
        return mark;
    }

    // ---------------------------------------------------------------- small helpers

    /** The boxes of every building of the colony (the Town Square's too). */
    static List<DoorFinder.Box> buildingBoxes(final IColony colony) {
        final List<DoorFinder.Box> boxes = new ArrayList<>();
        for (final IBuilding building : colony.getServerBuildingManager().getBuildings().values()) {
            boxes.add(DoorFinder.Box.of(building));
        }
        return boxes;
    }

    static boolean inside(final List<DoorFinder.Box> boxes, final BlockPos cell) {
        for (final DoorFinder.Box box : boxes) {
            if (box.contains(cell)) {
                return true;
            }
        }
        return false;
    }

    /** The colony MineColonies has with this id in this level, or {@code null}. */
    static IColony colonyOf(final ServerLevel level, final int colony) {
        try {
            return IColonyManager.getInstance().getColonyByWorld(colony, level);
        } catch (final RuntimeException unavailable) {
            return null;
        }
    }

    /** A mark in words for a log line. */
    public static String describe(final Mark mark) {
        return mark.type().id() + " at " + mark.pos().toShortString() + (mark.to() == null ? "" : " to " + mark.to().toShortString())
                + " facing " + mark.facing().getName().toLowerCase(Locale.ROOT);
    }
}
