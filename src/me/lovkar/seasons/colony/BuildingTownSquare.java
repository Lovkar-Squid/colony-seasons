package me.lovkar.seasons.colony;

import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.workorders.WorkOrderType;
import com.minecolonies.core.colony.buildings.AbstractBuilding;
import me.lovkar.seasons.decor.DecorEvents;
import me.lovkar.seasons.decor.DecorLedger;
import me.lovkar.seasons.decor.Squares;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The Town Square, {@code colony_seasons:townsquare} (0.2.0, docs/town-square-design.md 4.1): a building the player
 * places and the Builder builds, which the Decorator decorates while he stays employed at his Workshop. It has no
 * worker module; one Decorator is assigned to it through {@link SquareModules#DECORATOR}. Chunk Q1 builds the shell and
 * the assignment only: nothing is placed on the square yet, and {@code DecorWork.buildingsInOrder} leaves it out of
 * the Decorator's buildings (it has no doors). Chunk Q2 makes it the colony's centrepiece ({@code decor.Squares}): a
 * work order on it takes its group down first, and its removal or completed upgrade runs the resolver again.
 *
 * <p>Its footprint is 21 x 21 at every level and the tag cells below keep their place at every level ([B] design 4.1),
 * so an upgrade never moves the square's centrepiece. It saves nothing of its own; the decoration ledger keeps which
 * square is the main one.</p>
 */
public class BuildingTownSquare extends AbstractBuilding {

    /** The levels the design gives the square (Q6 draws them). */
    public static final int MAX_LEVEL = 5;
    /**
     * The levels that have a blueprint in this build: Q1 ships one placeholder level, so a player cannot order an
     * upgrade to a blueprint that is not there. Q6 sets this to {@link #MAX_LEVEL}.
     */
    public static final int BUILT_LEVELS = 1;
    /** The look the blueprints are named after (square/townsquare1.blueprint); used while no blueprint is known. */
    public static final String DEFAULT_LOOK = "townsquare";
    /** The claim round the hut block at every built level: one chunk (MineColonies gives 2 at levels 4 and 5). */
    public static final int CLAIM_RADIUS = 1;

    /** The blueprint tags of design 4.1, read with {@code getLocationsFromTag}; the names are final from Q1 on. */
    public static final String TAG_TREE = "tree";
    public static final String TAG_GIFT = "gift";
    public static final String TAG_LAUNCH = "launch";
    public static final String TAG_GATHER = "gather";
    public static final String TAG_SIT = "sit";
    public static final String TAG_LAMP = "lamp";
    public static final String TAG_BED = "bed";
    public static final String TAG_STALL = "stall";
    public static final List<String> TAGS = List.of(TAG_TREE, TAG_GIFT, TAG_LAUNCH, TAG_GATHER, TAG_SIT, TAG_LAMP, TAG_BED, TAG_STALL);

    public BuildingTownSquare(final IColony colony, final BlockPos pos) {
        super(colony, pos);
    }

    /** The stored path, with ".blueprint" (as {@link BuildingDecorator#getBlueprintPath}). */
    @Override
    public String getBlueprintPath() {
        return BuildingDecorator.withExtension(super.getBlueprintPath());
    }

    @Override
    public void setBlueprintPath(final String path) {
        super.setBlueprintPath(BuildingDecorator.withExtension(path));
    }

    /** The look, from the blueprint's file name without its level digits ("square/townsquare1.blueprint" is "townsquare"). */
    @Override
    public @NotNull String getSchematicName() {
        return BuildingDecorator.lookOf(getBlueprintPath(), DEFAULT_LOOK);
    }

    @Override
    public int getMaxBuildingLevel() {
        return BUILT_LEVELS;
    }

    /** None before the first build, {@link #CLAIM_RADIUS} at every level after; MineColonies claims the box as well. */
    @Override
    public int getClaimRadius(final int buildingLevel) {
        return buildingLevel <= 0 ? 0 : CLAIM_RADIUS;
    }

    /** The square's Decorator module (looked up by its producer, never with getModuleMatching); null if it is somehow missing. */
    public @Nullable SquareAssignmentModule assignment() {
        return getModule(SquareModules.DECORATOR);
    }

    /**
     * [B] design 4.6 (Q2): every work order MineColonies makes for this square (the hut window's upgrade, repair and
     * removal all come here) takes the square's group down on the server, from the end of this tick, before the Builder
     * reaches CLEAR_NON_SOLIDS. An order made another way is seen by {@code DecorEvents.squareWatch}.
     */
    @Override
    protected void requestWorkOrder(final WorkOrderType type, final BlockPos builder) {
        super.requestWorkOrder(type, builder);
        if (isPendingConstruction()) {
            DecorEvents.squareOrder(this);
        }
    }

    /** The upgrade is done: the resolver runs again at the end of the tick (MineColonies sets the new level after this). */
    @Override
    public void onUpgradeComplete(final Blueprint blueprint, final int newLevel) {
        super.onUpgradeComplete(blueprint, newLevel);
        if (getColony() != null && getColony().getWorld() instanceof ServerLevel level && DecorLedger.peek(level) != null) {
            DecorEvents.squareChanged(this);
        }
    }

    /**
     * Placed (MineColonies calls this once, when the hut makes the building): a colony that has another Town Square
     * already is told that only the main one is decorated in this version (design 4.3 and 4.8). A square whose removal
     * order is done (deconstructed, its hut only waiting to be picked up) is no other square.
     */
    @Override
    public void onPlacement() {
        super.onPlacement();
        final IColony colony = getColony();
        if (colony == null || !(colony.getWorld() instanceof ServerLevel level)) {
            return;
        }
        for (final IBuilding other : colony.getServerBuildingManager().getBuildings().values()) {
            if (other instanceof BuildingTownSquare square && other != this && !square.isDeconstructed()) {
                Squares.secondSquare(level, colony.getID());
                return;
            }
        }
    }
}
