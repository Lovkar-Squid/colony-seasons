package me.lovkar.seasons.colony;

import com.minecolonies.api.colony.buildings.modules.AbstractBuildingModule;
import com.minecolonies.api.colony.buildings.modules.IPersistentModule;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

/**
 * The Workshop's storage upgrade (S10e), the same as MineColonies' Warehouse storage upgrade: how many of its
 * {@link BuildingDecorator#MAX_STORAGE_STEP} steps were paid for. Each step made every rack of the hut one row of
 * nine slots larger ({@link BuildingDecorator#upgradeStorage}); the racks keep their own size in their block entity
 * (MineColonies' {@code tagSIze}), this module keeps the step, saved with the building as {@code cs_storage_step}
 * (MineColonies' Warehouse keeps its own as {@code tagStorage}).
 *
 * <p>The window's tab ({@link DecoratorStorageModuleView}) reads the one int {@link #serializeToView} writes;
 * tools/check_packets.py pairs the two.</p>
 */
public class DecoratorStorageModule extends AbstractBuildingModule implements IPersistentModule {

    static final String NBT_STEP = "cs_storage_step";

    private int step;

    /** Steps paid for, 0 to {@link BuildingDecorator#MAX_STORAGE_STEP}. */
    public int step() {
        return step;
    }

    /** One step more (never past the last); the building saves it. */
    void increment() {
        if (step < BuildingDecorator.MAX_STORAGE_STEP) {
            step++;
            markDirty();
        }
    }

    /** A step read from NBT, or anything else: kept between 0 and the last step. */
    static int clamp(final int value) {
        return Math.max(0, Math.min(BuildingDecorator.MAX_STORAGE_STEP, value));
    }

    @Override
    public void deserializeNBT(final @NotNull HolderLookup.Provider provider, final CompoundTag compound) {
        step = compound.contains(NBT_STEP) ? clamp(compound.getInt(NBT_STEP)) : 0;
    }

    @Override
    public void serializeNBT(final @NotNull HolderLookup.Provider provider, final CompoundTag compound) {
        compound.putInt(NBT_STEP, step);
    }

    @Override
    public void serializeToView(final @NotNull RegistryFriendlyByteBuf buf) {
        buf.writeInt(step);
    }
}
