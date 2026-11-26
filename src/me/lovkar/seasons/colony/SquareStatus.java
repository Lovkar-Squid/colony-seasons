package me.lovkar.seasons.colony;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import me.lovkar.seasons.calendar.CalendarManager;
import me.lovkar.seasons.decor.DecorLedger;
import me.lovkar.seasons.decor.PieceLoader;
import me.lovkar.seasons.decor.SquareSets;
import me.lovkar.seasons.decor.Squares;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What the Town Square's window pages say about a square (0.2.0, chunk Q5, docs/town-square-design.md 4.3 and 4.4): the
 * main-square standing the Decorator page shows, and the set and its state the info page and the Workshop's Calendar
 * line show. Server side only and read only: it reads the colony, the ledger and the calendar, and writes and sends
 * nothing (never {@link Squares#refresh}, which writes the ledger), so a window can ask it at any time. The pages carry
 * the answers as ids and numbers; the client turns them into text.
 */
public final class SquareStatus {

    /** Whether a square is the colony's main one ({@link Squares#of}), another one is, or the colony has none built. */
    public enum Standing {
        MAIN, OTHER, NONE;

        /** The id of a packet, an unknown one is {@link #NONE}. */
        public static Standing byOrdinal(final int ordinal) {
            return ordinal >= 0 && ordinal < values().length ? values()[ordinal] : NONE;
        }
    }

    /** The state of the square's set (design 4.4): the order the checks run in is the order of {@link #state}. */
    public enum State {
        NOT_BUILT, NOT_MAIN, BEING_BUILT, COMING_DOWN, NO_SET, NO_ROOM, UP, NO_DECORATOR, WAITING_MATERIALS, GOING_UP;

        /** The id of a packet, an unknown one reads as {@link #NO_SET}. */
        public static State byOrdinal(final int ordinal) {
            return ordinal >= 0 && ordinal < values().length ? values()[ordinal] : NO_SET;
        }
    }

    /** Which square is the main one, seen from one square: its position and level ({@link BlockPos#ZERO} and 0 for none). */
    public record Main(Standing standing, BlockPos pos, int level) {
        public static final Main NONE = new Main(Standing.NONE, BlockPos.ZERO, 0);
    }

    /** The Decorator who works the main square: the citizen's name, and whether he is listed on this very square. */
    public record Worker(String name, boolean here, int workshopLevel) {
    }

    /** What the square looks like to its windows, from one square's point of view. */
    public record Status(int level, Main main, SquareSets.Order order, @Nullable SquareSets.Set wanted, @Nullable SquareSets.Set up,
                         State state, @Nullable Worker worker, List<Lack> lacks) {
        /** The set the pages name: the one standing on the square, else the one it wants ({@code null}: none). */
        public @Nullable SquareSets.Set shown() {
            return up != null ? up : wanted;
        }
    }

    /** One vanilla item the last visit was short of. */
    public record Lack(String item, int count) {
    }

    /** What {@link #state} needs to know, and nothing more (so a probe can ask it for every case). */
    public record Facts(boolean built, Standing standing, boolean busy, @Nullable SquareSets.Set wanted, @Nullable SquareSets.Set up,
                        boolean anyUp, boolean decorated, boolean levelOk, boolean noRoom, boolean decorator, boolean lacks) {
    }

    private SquareStatus() {
    }

    /**
     * The state of the square's set, by the first rule that holds: not built, not the main square, being built (its
     * set is down meanwhile), a set coming down (something stands that the order does not want), no set wanted, no room
     * for the tree, the set up (the wanted one, decorated, at its size), no Decorator, waiting for materials, going up.
     * A set that has no pieces in this version ({@link SquareSets.Set#shipped}) counts as no set. Pure.
     */
    public static State state(final Facts f) {
        if (!f.built()) {
            return State.NOT_BUILT;
        }
        if (f.standing() != Standing.MAIN) {
            return State.NOT_MAIN;
        }
        if (f.busy()) {
            return State.BEING_BUILT;
        }
        final SquareSets.Set wanted = f.wanted() != null && f.wanted().shipped() ? f.wanted() : null;
        if (f.anyUp() && (wanted == null || f.up() != wanted)) {
            return State.COMING_DOWN;
        }
        if (wanted == null) {
            return State.NO_SET;
        }
        if (f.noRoom()) {
            return State.NO_ROOM;
        }
        if (f.anyUp() && f.decorated() && f.levelOk()) {
            return State.UP;
        }
        if (!f.decorator()) {
            return State.NO_DECORATOR;
        }
        return f.lacks() ? State.WAITING_MATERIALS : State.GOING_UP;
    }

    /** Which square is the colony's main one, seen from {@code square}; reads only. {@link Main#NONE} without a colony or a built square. */
    public static Main mainOf(final BuildingTownSquare square) {
        final IColony colony = square == null ? null : square.getColony();
        if (colony == null || !(colony.getWorld() instanceof ServerLevel level)) {
            return Main.NONE;
        }
        final Squares.Square main = Squares.of(level, colony, DecorLedger.peek(level));
        if (main.source() != Squares.Source.BUILDING) {
            return Main.NONE;
        }
        return new Main(main.building().equals(square.getPosition()) ? Standing.MAIN : Standing.OTHER, main.building(), main.level());
    }

    /**
     * The Decorator who works {@code square}: the one listed on it, else, when it is the colony's main square
     * ({@code main}), one listed on another Town Square, who works the main square ([A] owner item of Q2, taken in Q5);
     * {@code null} when nobody does.
     */
    public static @Nullable Worker workerOf(final BuildingTownSquare square, final boolean main) {
        final SquareAssignmentModule own = square.assignment();
        final List<ICitizenData> mine = own == null ? List.of() : own.getAssignedCitizen();
        if (!mine.isEmpty()) {
            return worker(mine.get(0), true);
        }
        final IColony colony = square.getColony();
        if (colony == null || !main) {
            return null;
        }
        for (final IBuilding b : colony.getServerBuildingManager().getBuildings().values()) {
            if (b instanceof BuildingTownSquare other && other != square && other.assignment() != null) {
                final List<ICitizenData> theirs = other.assignment().getAssignedCitizen();
                if (!theirs.isEmpty()) {
                    return worker(theirs.get(0), false);
                }
            }
        }
        return null;
    }

    private static Worker worker(final ICitizenData citizen, final boolean here) {
        final int level = citizen.getWorkBuilding() == null ? 0 : citizen.getWorkBuilding().getBuildingLevel();
        return new Worker(String.valueOf(citizen.getName()), here, level);
    }

    /** The whole answer for one square ({@link #state} applied to what the world shows). Reads only. */
    public static Status of(final BuildingTownSquare square) {
        final int level = square.getBuildingLevel();
        final SquareSets.Order order = square.order();
        final Main main = mainOf(square);
        final IColony colony = square.getColony();
        final SquareSets.Set wanted = SquareSets.wanted(order, CalendarManager.current());
        final Worker worker = workerOf(square, main.standing() == Standing.MAIN);
        SquareSets.Set up = null;
        boolean anyUp = false;
        boolean decorated = false;
        boolean levelOk = true;
        boolean busy = false;
        boolean noRoom = false;
        final List<Lack> lacks = new ArrayList<>();
        if (main.standing() == Standing.MAIN && colony != null && colony.getWorld() instanceof ServerLevel level0) {
            final DecorLedger ledger = DecorLedger.peek(level0);
            final Squares.Square resolved = Squares.of(level0, colony, ledger);
            busy = resolved.busy();
            if (ledger != null && resolved.anchor() != null) {
                final DecorLedger.GroupView group = ledger.group(colony.getID(), DecorLedger.Owner.square(resolved.anchor()));
                anyUp = group != null && !group.entries().isEmpty();
                if (anyUp) {
                    up = setOf(group.piece());
                    decorated = group.decoratedDay() >= 0;
                    levelOk = worker == null || group.level() == SquareSets.pieceLevel(level, worker.workshopLevel());
                    // fixtures only (a Bare order in a festival window): the gift pile and the crate are no set to take down
                    if (up == null && PieceLoader.FIXTURES_ONLY.equals(group.piece())) {
                        anyUp = false;
                    }
                }
                noRoom = ledger.squareProblem(colony.getID());
            }
        }
        final boolean lacking = !square.missing().isEmpty();
        for (final Map.Entry<Item, Integer> e : square.missing().entrySet()) {
            lacks.add(new Lack(BuiltInRegistries.ITEM.getKey(e.getKey()).toString(), e.getValue()));
        }
        final State state = state(new Facts(level >= 1, main.standing(), busy, wanted, up, anyUp, decorated, levelOk, noRoom,
                worker != null, lacking));
        return new Status(level, main, order, wanted != null && wanted.shipped() ? wanted : null, up, state, worker, List.copyOf(lacks));
    }

    /** The set whose centrepiece file is {@code piece}, or {@code null} (no group, fixtures only, a file of no set of this version). */
    public static @Nullable SquareSets.Set setOf(final String piece) {
        for (final SquareSets.Set set : SquareSets.ALL) {
            if (set.centrepieces().contains(piece)) {
                return set;
            }
        }
        return null;
    }
}
