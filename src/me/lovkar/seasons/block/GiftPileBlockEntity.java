package me.lovkar.seasons.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The gift pile's saved fields: how many gifts it holds, and for each player the real day (days since 1 Jan 1970,
 * as {@code LocalDate.toEpochDay()}) of the last gift taken. The fields of S2; 0.1.0 does not use them: gifts cost
 * nothing, and who took a gift on which day is kept in festival/FestivalData for the whole server, so a pile that is
 * broken and put back by the Decorator (a new block entity) can never hand out a second gift.
 */
public class GiftPileBlockEntity extends BlockEntity {
    private static final String TAG_STOCK = "stock";
    private static final String TAG_TAKEN = "taken";
    private static final String TAG_PLAYER = "player";
    private static final String TAG_DAY = "day";

    private int stock;
    private final Map<UUID, Long> takenOn = new LinkedHashMap<>();

    public GiftPileBlockEntity(final BlockPos pos, final BlockState state) {
        super(SeasonsBlocks.GIFT_PILE_ENTITY.get(), pos, state);
    }

    public int getStock() {
        return stock;
    }

    public void setStock(final int stock) {
        this.stock = Math.max(0, stock);
        setChanged();
    }

    /** The real day this player last took a gift here, or null if never. */
    public @Nullable Long dayTakenBy(final UUID player) {
        return takenOn.get(player);
    }

    public void markTaken(final UUID player, final long epochDay) {
        takenOn.put(player, epochDay);
        setChanged();
    }

    /** Every player and the real day of their last gift, read only. */
    public Map<UUID, Long> takenOn() {
        return Collections.unmodifiableMap(takenOn);
    }

    @Override
    protected void saveAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt(TAG_STOCK, stock);
        final ListTag list = new ListTag();
        for (final Map.Entry<UUID, Long> e : takenOn.entrySet()) {
            final CompoundTag one = new CompoundTag();
            one.put(TAG_PLAYER, NbtUtils.createUUID(e.getKey()));
            one.putLong(TAG_DAY, e.getValue());
            list.add(one);
        }
        tag.put(TAG_TAKEN, list);
    }

    @Override
    protected void loadAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        stock = Math.max(0, tag.getInt(TAG_STOCK));
        takenOn.clear();
        final ListTag list = tag.getList(TAG_TAKEN, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            final CompoundTag one = list.getCompound(i);
            final Tag id = one.get(TAG_PLAYER);
            if (id != null) {
                takenOn.put(NbtUtils.loadUUID(id), one.getLong(TAG_DAY));
            }
        }
    }
}
