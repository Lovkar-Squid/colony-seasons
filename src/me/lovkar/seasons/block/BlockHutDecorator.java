package me.lovkar.seasons.block;

import com.minecolonies.api.blocks.AbstractBlockHut;
import com.minecolonies.api.colony.buildings.registry.BuildingEntry;
import com.minecolonies.core.tileentities.TileEntityColonyBuilding;
import me.lovkar.seasons.ColonySeasons;
import me.lovkar.seasons.colony.DecoratorSetup;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The Decorator's hut block, {@code colony_seasons:blockhutdecorator}.
 *
 * <p>Two overrides that every MineColonies addon hut needs: {@code AbstractColonyBlock} hard-codes the
 * {@code minecolonies} namespace in {@link #getRegistryName()}, and MineColonies' own block-entity type only
 * accepts MineColonies' own hut blocks, so the block entity is created from our type
 * ({@link SeasonsTileEntity}, {@code colony_seasons:colonybuilding}).</p>
 */
public class BlockHutDecorator extends AbstractBlockHut<BlockHutDecorator> {

    @Override
    public String getHutName() {
        return DecoratorSetup.HUT_NAME;
    }

    @Override
    public BuildingEntry getBuildingEntry() {
        return DecoratorSetup.BUILDING.get();
    }

    @Override
    public @NotNull ResourceLocation getRegistryName() {
        return ResourceLocation.fromNamespaceAndPath(ColonySeasons.MODID, DecoratorSetup.HUT_NAME);
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
