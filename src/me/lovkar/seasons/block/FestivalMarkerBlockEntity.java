package me.lovkar.seasons.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * The festival marker's saved fields: the player who put it down and the id of the colony it was put down in
 * ({@link #NO_COLONY} outside every claim). A later chunk uses them to find the colony's square.
 */
public class FestivalMarkerBlockEntity extends BlockEntity {
    public static final int NO_COLONY = -1;
    private static final String TAG_PLACED_BY = "placedBy";
    private static final String TAG_COLONY = "colony";

    private @Nullable UUID placedBy;
    private int colonyId = NO_COLONY;

    public FestivalMarkerBlockEntity(final BlockPos pos, final BlockState state) {
        super(SeasonsBlocks.FESTIVAL_MARKER_ENTITY.get(), pos, state);
    }

    public @Nullable UUID getPlacedBy() {
        return placedBy;
    }

    public void setPlacedBy(final @Nullable UUID placedBy) {
        this.placedBy = placedBy;
        setChanged();
    }

    public int getColonyId() {
        return colonyId;
    }

    public void setColonyId(final int colonyId) {
        this.colonyId = colonyId;
        setChanged();
    }

    @Override
    protected void saveAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (placedBy != null) {
            tag.putUUID(TAG_PLACED_BY, placedBy);
        }
        tag.putInt(TAG_COLONY, colonyId);
    }

    @Override
    protected void loadAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        placedBy = tag.hasUUID(TAG_PLACED_BY) ? tag.getUUID(TAG_PLACED_BY) : null;
        colonyId = tag.contains(TAG_COLONY) ? tag.getInt(TAG_COLONY) : NO_COLONY;
    }
}
