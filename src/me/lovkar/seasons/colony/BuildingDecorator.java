package me.lovkar.seasons.colony;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.modules.settings.ISettingKey;
import com.minecolonies.core.colony.buildings.AbstractBuilding;
import com.minecolonies.core.colony.buildings.modules.settings.BoolSetting;
import com.minecolonies.core.colony.buildings.modules.settings.SettingKey;
import me.lovkar.seasons.ColonySeasons;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.NotNull;

import java.util.function.Predicate;

/**
 * The Decorator's hut, {@code colony_seasons:decorator}: levels 1 to 5, one worker. A non-crafter (the template is
 * Voyager's Observatory): the Decorator assembles his kits himself, so no recipe module and no crafting window.
 *
 * <p>What has to survive lives here or in items, never in the AI (MineColonies resets a worker's AI for sleep,
 * rain and raids): the number of batches he has assembled ({@code cs_assembled}) and whether the colony's
 * decorations are up ({@code cs_decorations_up}, written by the placing chunk S4). Stock is items in the hut and in
 * his own pack.</p>
 */
public class BuildingDecorator extends AbstractBuilding {

    public static final int MAX_LEVEL = 5;
    /** The look the blueprints are named after (decorator/workshop1..5.blueprint); used while no blueprint is known. */
    public static final String DEFAULT_LOOK = "workshop";
    /**
     * Stock cap per kit output: {@code STOCK_CAP_BASE + STOCK_CAP_PER_LEVEL x level}, 16 at level 1 to 48 at level 5.
     * A higher hut keeps more ready; the cap stops him turning the whole warehouse into wreaths.
     */
    public static final int STOCK_CAP_BASE = 8;
    public static final int STOCK_CAP_PER_LEVEL = 8;

    /** Setting: assemble decorations all year (on), or only from the first day of a decoration window (off). */
    public static final ISettingKey<BoolSetting> PREPARE_ALL_YEAR =
            new SettingKey<>(BoolSetting.class, ResourceLocation.fromNamespaceAndPath(ColonySeasons.MODID, "prepare_all_year"));

    static final String NBT_ASSEMBLED = "cs_assembled";
    static final String NBT_DECORATIONS_UP = "cs_decorations_up";

    /** Batches assembled at this hut, over its whole life. */
    private int assembled;
    /** True while the colony's decorations are up (S4 owns the writes; the phase table reads it). */
    private boolean decorationsUp;

    public BuildingDecorator(final IColony colony, final BlockPos pos) {
        super(colony, pos);
    }

    /** The look, from the blueprint's file name without its level digits ("decorator/workshop3.blueprint" is "workshop"). */
    @Override
    public @NotNull String getSchematicName() {
        return lookOf(getBlueprintPath());
    }

    /** The look name in a blueprint path, {@link #DEFAULT_LOOK} for none. */
    public static String lookOf(final String path) {
        if (path == null || path.isBlank()) {
            return DEFAULT_LOOK;
        }
        String name = path.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        if (name.endsWith(".blueprint")) {
            name = name.substring(0, name.length() - ".blueprint".length());
        }
        int end = name.length();
        while (end > 0 && Character.isDigit(name.charAt(end - 1))) {
            end--;
        }
        name = name.substring(0, end);
        return name.isEmpty() ? DEFAULT_LOOK : name;
    }

    @Override
    public int getMaxBuildingLevel() {
        return MAX_LEVEL;
    }

    /** How many of one kit's output the hut keeps ready at its current level. */
    public int stockCap() {
        return STOCK_CAP_BASE + STOCK_CAP_PER_LEVEL * Math.max(0, getBuildingLevel());
    }

    /** How many of {@code item} are in the hut (its racks included) and in the packs of its workers. */
    public int stock(final Item item) {
        return count(stack -> stack.is(item));
    }

    /** How many items matching {@code what} are in the hut and in the packs of its workers. */
    public int count(final Predicate<ItemStack> what) {
        int total = countIn(getItemHandlerCap(), what);
        for (final ICitizenData citizen : getAllAssignedCitizen()) {
            total += countIn(citizen.getInventory(), what);
        }
        return total;
    }

    /** How many items matching {@code what} one inventory holds (0 for none). */
    public static int countIn(final IItemHandler handler, final Predicate<ItemStack> what) {
        if (handler == null) {
            return 0;
        }
        int total = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            final ItemStack stack = handler.getStackInSlot(slot);
            if (!stack.isEmpty() && what.test(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** The "prepare all year" setting (true when the settings module is somehow missing). */
    public boolean prepareAllYear() {
        final Boolean value = getSettingValueOrDefault(PREPARE_ALL_YEAR, Boolean.TRUE);
        return value == null || value;
    }

    /** Batches assembled at this hut so far. */
    public int assembled() {
        return assembled;
    }

    /** One more batch assembled. */
    public void addAssembled() {
        assembled++;
        markDirty();
    }

    public boolean decorationsUp() {
        return decorationsUp;
    }

    public void setDecorationsUp(final boolean up) {
        if (decorationsUp != up) {
            decorationsUp = up;
            markDirty();
        }
    }

    @Override
    public void deserializeNBT(final @NotNull HolderLookup.Provider provider, final CompoundTag tag) {
        super.deserializeNBT(provider, tag);
        assembled = tag.contains(NBT_ASSEMBLED) ? Math.max(0, tag.getInt(NBT_ASSEMBLED)) : 0;
        decorationsUp = tag.contains(NBT_DECORATIONS_UP) && tag.getBoolean(NBT_DECORATIONS_UP);
    }

    @Override
    public CompoundTag serializeNBT(final @NotNull HolderLookup.Provider provider) {
        final CompoundTag tag = super.serializeNBT(provider);
        tag.putInt(NBT_ASSEMBLED, assembled);
        tag.putBoolean(NBT_DECORATIONS_UP, decorationsUp);
        return tag;
    }
}
