package me.lovkar.seasons.block;

import com.minecolonies.api.blocks.AbstractBlockHut;
import com.minecolonies.api.colony.buildings.registry.BuildingEntry;
import com.minecolonies.core.tileentities.TileEntityColonyBuilding;
import me.lovkar.seasons.ColonySeasons;
import me.lovkar.seasons.colony.DecoratorSetup;
import me.lovkar.seasons.colony.TownSquareSetup;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The Town Square's hut block, {@code colony_seasons:blockhuttownsquare}: the notice board that stands on the square
 * (the owner's pick of the pictures round, docs/town-square-design.md). The same two overrides as
 * {@link BlockHutDecorator}: our namespace in {@link #getRegistryName()}, and the block entity from our own type.
 */
public class BlockHutTownSquare extends AbstractBlockHut<BlockHutTownSquare> {

    @Override
    public String getHutName() {
        return TownSquareSetup.HUT_NAME;
    }

    @Override
    public BuildingEntry getBuildingEntry() {
        return TownSquareSetup.BUILDING.get();
    }

    @Override
    public @NotNull ResourceLocation getRegistryName() {
        return ResourceLocation.fromNamespaceAndPath(ColonySeasons.MODID, TownSquareSetup.HUT_NAME);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(@NotNull final BlockPos pos, @NotNull final BlockState state) {
        final TileEntityColonyBuilding te = DecoratorSetup.BUILDING_BE.get().create(pos, state);
        if (te != null) {
            te.registryName = getBuildingEntry().getRegistryName();
        }
        return te;
    }
}
