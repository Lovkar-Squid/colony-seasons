package me.lovkar.seasons.block;

import com.minecolonies.core.tileentities.TileEntityColonyBuilding;
import me.lovkar.seasons.colony.BuildingDecorator;
import me.lovkar.seasons.colony.DecoratorSetup;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

/**
 * The block entity of this mod's hut blocks ({@code colony_seasons:colonybuilding}). It behaves exactly like a
 * MineColonies hut block entity; it exists only because MineColonies' own type lists MineColonies' huts and no
 * others. Blueprints of our huts carry this id.
 *
 * <p>Its blueprint path always ends with ".blueprint" ({@link BuildingDecorator#withExtension}): a hut pasted with
 * {@code /structurize paste} is handed the typed path without it, and Structurize cannot load that.</p>
 */
public class SeasonsTileEntity extends TileEntityColonyBuilding {

    public SeasonsTileEntity(final BlockPos pos, final BlockState state) {
        super(DecoratorSetup.BUILDING_BE.get(), pos, state);
    }

    @Override
    public String getBlueprintPath() {
        return BuildingDecorator.withExtension(super.getBlueprintPath());
    }

    @Override
    public void setBlueprintPath(final String path) {
        super.setBlueprintPath(BuildingDecorator.withExtension(path));
    }

    /** A path saved without ".blueprint" by an older version is put right as it is read (MineColonies also reads the field itself). */
    @Override
    public void loadAdditional(final @NotNull CompoundTag compound, final @NotNull HolderLookup.Provider provider) {
        super.loadAdditional(compound, provider);
        final String stored = super.getBlueprintPath();
        if (stored != null && !stored.equals(BuildingDecorator.withExtension(stored))) {
            super.setBlueprintPath(BuildingDecorator.withExtension(stored));
        }
    }
}
