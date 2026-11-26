package me.lovkar.seasons.decor;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import me.lovkar.seasons.ColonySeasons;
import me.lovkar.seasons.colony.BuildingTownSquare;
import me.lovkar.seasons.festival.Gathering;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The resolver (0.2.0, Q2, docs/town-square-design.md 4.3, 4.6 and 4.8): the one place a colony's festival centrepiece
 * stands. In order: the colony's main Town Square (built, level 1 or more), else its registered festival marker, else
 * the town hall, else nothing.
 *
 * <ul>
 *   <li>The main square: the built square with the highest level. On a tie the square the ledger's stored mark names
 *       keeps it, else the lowest hut position: a second square of the same level never takes the tree from the first
 *       (the design's "lowest id" would, since every square of this build has the same one level). A square whose work
 *       order MineColonies has finished as a removal (deconstructed: the plaza is gone, only its hut stays to be picked
 *       up) is no candidate. Being busy never changes the choice.</li>
 *   <li>Its anchor is the world cell of its {@code tree} tag (the lowest one, should a blueprint have several). When the
 *       tag reads empty (the hut's block entity is not there) and the stored mark names this square, the stored anchor
 *       stands in; otherwise the square is not located, and then nothing is taken down and nothing is put up for it.</li>
 *   <li>[B] A built square wins over the marker from level 1 on, also while it is busy (a work order on it, or one
 *       inside its box): then nothing is hosted anywhere, so its own group comes down for the Builder and the marker is
 *       not used meanwhile (no tree jumping back and forth during an upgrade).</li>
 *   <li>[B] The resolver names exactly one anchor; every square group at another position is taken down by the sweep
 *       ({@link Square#hosts}).</li>
 * </ul>
 *
 * <p>{@link #of} only reads. {@link #refresh} also stores the main square's mark in the ledger
 * ({@link DecorLedger.SquareMark}), so the rest of the mod can ask {@link #marker} in constant time, and tells the
 * managers once that the festival marker is not used while the Town Square stands. Server thread only.</p>
 */
public final class Squares {

    /** Where the centrepiece stands: a Town Square, the festival marker, the town hall (nothing goes up there), none. */
    public enum Source { BUILDING, MARKER, HALL, NONE }

    /**
     * The resolver's answer. {@code building} is the hut's position (the main square's, or the town hall's), {@code level}
     * its level; {@code located} false only for a square whose centrepiece cell cannot be read now.
     */
    public record Square(Source source, BlockPos anchor, BlockPos building, int level, boolean located, boolean busy) {
        /** True when the group at {@code pos} is the colony's centrepiece now: a located, free square or the marker there. */
        public boolean hosts(final BlockPos pos) {
            return (source == Source.BUILDING || source == Source.MARKER) && located && !busy && anchor != null && anchor.equals(pos);
        }
    }

    private static int markerUnusedNotices;
    private static int secondSquareNotices;
    /** Huts whose blueprint has more than one {@code tree} cell, logged once each. */
    private static final Set<BlockPos> MANY_TREES = new HashSet<>();

    private Squares() {
    }

    /** "The festival marker is not used while the Town Square stands" lines sent since the server started (the probe counts them). */
    public static int markerUnusedNotices() {
        return markerUnusedNotices;
    }

    /** "Only the main Town Square is decorated" lines sent since the server started (the probe counts them). */
    public static int secondSquareNotices() {
        return secondSquareNotices;
    }

    /** The colony's main square as it is now; reads the world and the ledger, writes nothing, sends nothing. */
    public static Square of(final ServerLevel level, final IColony colony, final DecorLedger ledger) {
        final int id = colony.getID();
        final DecorLedger.SquareMark mark = ledger == null ? null : ledger.squareMark(id);
        BuildingTownSquare main = null;
        for (final IBuilding b : colony.getServerBuildingManager().getBuildings().values()) {
            if (b instanceof BuildingTownSquare square && candidate(square) && (main == null || better(square, main, mark))) {
                main = square;
            }
        }
        if (main != null) {
            final BlockPos hut = main.getPosition();
            BlockPos anchor = treeCell(main);
            if (anchor == null && mark != null && mark.building().equals(hut)) {
                anchor = mark.anchor();               // the tag reads empty now (its block entity is not there): the stored cell
            }
            return new Square(Source.BUILDING, anchor, hut, main.getBuildingLevel(), anchor != null, DecorWork.busy(colony, main));
        }
        final BlockPos marker = ledger == null ? null : ledger.marker(id);
        if (marker != null) {
            return new Square(Source.MARKER, marker, null, 0, true, false);
        }
        final IBuilding hall = colony.getServerBuildingManager().getTownHall();
        if (hall != null) {
            return new Square(Source.HALL, hall.getPosition(), hall.getPosition(), hall.getBuildingLevel(), true, false);
        }
        return new Square(Source.NONE, null, null, 0, true, false);
    }

    /**
     * {@link #of}, then the ledger's mark follows it: a located main square is stored, an unlocated one leaves the mark as
     * it is (its stored anchor keeps standing in), anything else clears it. When the mark is stored where there was none
     * while the colony has a festival marker, the marker leaves MineColonies' leisure sites at once and the managers get
     * one line that the marker is not used while the Town Square stands. Writes the ledger file when the mark changed.
     */
    public static Square refresh(final ServerLevel level, final IColony colony, final DecorLedger ledger) {
        final Square square = of(level, colony, ledger);
        if (ledger == null) {
            return square;
        }
        final int id = colony.getID();
        if (square.source() == Source.BUILDING) {
            if (!square.located()) {
                return square;
            }
            final boolean first = ledger.squareMark(id) == null;
            if (ledger.setSquareMark(id, square.building(), square.anchor())) {
                final BlockPos marker = ledger.marker(id);
                if (first && marker != null) {
                    Gathering.markerGone(level, id, marker);
                    ledger.setSquareProblem(id, false);   // "no room at the marker" belonged to the marker, which is not used now
                    ledger.flush(level);
                    markerUnused(level, id);
                } else {
                    ledger.flush(level);
                }
            }
            return square;
        }
        if (ledger.clearSquareMark(id)) {
            ledger.flush(level);
        }
        return square;
    }

    /**
     * The colony's festival marker while it holds the centrepiece, else {@code null}: {@code null} while a Town Square's
     * mark is stored (the marker is only the fallback then). Constant time; what the 0.1.0 readers of the marker ask.
     */
    public static BlockPos marker(final DecorLedger ledger, final int colony) {
        return ledger == null || ledger.squareMark(colony) != null ? null : ledger.marker(colony);
    }

    /** "The festival marker is not used while the Town Square stands", to the colony's managers. */
    static void markerUnused(final ServerLevel level, final int colony) {
        markerUnusedNotices++;
        DecorWork.toManagers(level, colony, "com.colony_seasons.decor.marker_unused");
    }

    /** A second Town Square was placed in a colony that has one: the managers are told once, for this placement. */
    public static void secondSquare(final ServerLevel level, final int colony) {
        secondSquareNotices++;
        DecorWork.toManagers(level, colony, "com.colony_seasons.decor.second_square");
    }

    /** A square that can be the main one: built (level 1 or more) and not taken down by a finished removal order. */
    private static boolean candidate(final BuildingTownSquare square) {
        return square.getBuildingLevel() >= 1 && !square.isDeconstructed();
    }

    /** True when {@code a} is the main square rather than {@code b}: a higher level, else the stored mark's, else the lower position. */
    private static boolean better(final BuildingTownSquare a, final BuildingTownSquare b, final DecorLedger.SquareMark mark) {
        if (a.getBuildingLevel() != b.getBuildingLevel()) {
            return a.getBuildingLevel() > b.getBuildingLevel();
        }
        if (mark != null) {
            if (mark.building().equals(a.getPosition())) {
                return true;
            }
            if (mark.building().equals(b.getPosition())) {
                return false;
            }
        }
        return a.getPosition().compareTo(b.getPosition()) < 0;
    }

    /** The world cell of the square's {@code tree} tag (the lowest of several), or {@code null} when the tag reads empty. */
    public static BlockPos treeCell(final BuildingTownSquare square) {
        final List<BlockPos> cells = square.getLocationsFromTag(BuildingTownSquare.TAG_TREE);
        if (cells == null || cells.isEmpty()) {
            return null;
        }
        BlockPos lowest = cells.get(0);
        for (final BlockPos cell : cells) {
            if (cell.compareTo(lowest) < 0) {
                lowest = cell;
            }
        }
        if (cells.size() > 1 && MANY_TREES.add(square.getPosition().immutable())) {
            ColonySeasons.LOGGER.info("The Town Square at {} has {} cells tagged tree; its centrepiece stands at the lowest, {}",
                    square.getPosition().toShortString(), cells.size(), lowest.toShortString());
        }
        return lowest.immutable();
    }
}
