package me.lovkar.seasons.block;

import com.ldtteam.structurize.blockentities.interfaces.IBlueprintDataProviderBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.util.Tuple;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The festival marker's saved fields: the player who put it down and the id of the colony it was put down in
 * ({@link #NO_COLONY} outside every claim).
 *
 * <p>It is also a Structurize blueprint-data provider (design 5 [V]): MineColonies' idle walk only strolls at a
 * leisure site that has one, inside its {@link #getInWorldCorners() corners}. During the gathering hours the marker
 * is put on the colony's leisure sites (festival/Gathering), and a colonist whose idle walk picks it strolls round
 * the tree. The corners are a fixed box round the marker; there is no schematic, no pack and no tags, and nothing
 * of that is saved.</p>
 */
public class FestivalMarkerBlockEntity extends BlockEntity implements IBlueprintDataProviderBE {
    public static final int NO_COLONY = -1;
    /** Half the width of the box a colonist strolls in at the gathering. */
    public static final int STROLL_RADIUS = 6;
    private static final String TAG_PLACED_BY = "placedBy";
    private static final String TAG_COLONY = "colony";

    private @Nullable UUID placedBy;
    private int colonyId = NO_COLONY;
    private BlockPos cornerA = new BlockPos(-STROLL_RADIUS, -1, -STROLL_RADIUS);
    private BlockPos cornerB = new BlockPos(STROLL_RADIUS, 2, STROLL_RADIUS);
    private Map<BlockPos, List<String>> tags = new HashMap<>();

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

    // ---------------------------------------------------------------- IBlueprintDataProviderBE (the leisure site)

    @Override
    public String getSchematicName() {
        return "";
    }

    @Override
    public void setSchematicName(final String name) {
    }

    @Override
    public Map<BlockPos, List<String>> getPositionedTags() {
        return tags;
    }

    @Override
    public void setPositionedTags(final Map<BlockPos, List<String>> positionedTags) {
        tags = positionedTags == null ? new HashMap<>() : positionedTags;
    }

    @Override
    public Tuple<BlockPos, BlockPos> getSchematicCorners() {
        return new Tuple<>(cornerA, cornerB);
    }

    @Override
    public void setSchematicCorners(final BlockPos a, final BlockPos b) {
        cornerA = a;
        cornerB = b;
    }

    @Override
    public BlockPos getTilePos() {
        return worldPosition;
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void setBlueprintPath(final String path) {
    }

    @Override
    public void setPackName(final String pack) {
    }

    @Override
    public String getPackName() {
        return "";
    }

    @Override
    public String getBlueprintPath() {
        return "";
    }
}
