package me.lovkar.seasons.block;

import com.minecolonies.core.tileentities.TileEntityColonyBuilding;
import me.lovkar.seasons.colony.DecoratorSetup;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The block entity of this mod's hut blocks ({@code colony_seasons:colonybuilding}). It behaves exactly like a
 * MineColonies hut block entity; it exists only because MineColonies' own type lists MineColonies' huts and no
 * others. Blueprints of our huts carry this id.
 */
public class SeasonsTileEntity extends TileEntityColonyBuilding {

    public SeasonsTileEntity(final BlockPos pos, final BlockState state) {
        super(DecoratorSetup.BUILDING_BE.get(), pos, state);
    }
}
