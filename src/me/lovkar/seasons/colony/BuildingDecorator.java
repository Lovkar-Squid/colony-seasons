package me.lovkar.seasons.colony;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.modules.settings.ISettingKey;
import com.minecolonies.core.colony.buildings.AbstractBuilding;
import com.minecolonies.core.colony.buildings.modules.settings.BoolSetting;
import com.minecolonies.core.colony.buildings.modules.settings.SettingKey;
import com.minecolonies.core.tileentities.TileEntityColonyBuilding;
import com.minecolonies.core.tileentities.TileEntityRack;
import me.lovkar.seasons.ColonySeasons;
import me.lovkar.seasons.decor.DecorKinds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
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

    /** Setting: put decorations up at all (on), or only keep the stock and take things down (off). */
    public static final ISettingKey<BoolSetting> DECORATE = bool("decorate");
    /** Settings: which kinds he puts up (all on by default). */
    public static final ISettingKey<BoolSetting> KIND_WREATHS = bool("kind_wreaths");
    public static final ISettingKey<BoolSetting> KIND_GARLANDS = bool("kind_garlands");
    public static final ISettingKey<BoolSetting> KIND_LIGHTS = bool("kind_lights");
    public static final ISettingKey<BoolSetting> KIND_TREE = bool("kind_tree");

    /**
     * The storage upgrade (S10e), the same as MineColonies' Warehouse: {@link #MAX_STORAGE_STEP} steps of one emerald
     * block each, offered only once the hut is at {@link #STORAGE_UPGRADE_LEVEL} (the Warehouse offers it at its max
     * level); each step makes every rack one row of nine slots larger (27, 36, 45, 54).
     */
    public static final int MAX_STORAGE_STEP = 3;
    public static final int STORAGE_UPGRADE_LEVEL = MAX_LEVEL;

    static final String NBT_ASSEMBLED = "cs_assembled";
    static final String NBT_DECORATIONS_UP = "cs_decorations_up";

    /** Batches assembled at this hut, over its whole life. */
    private int assembled;
    /** True while the colony's decorations are up (S4 owns the writes; the phase table reads it). */
    private boolean decorationsUp;

    public BuildingDecorator(final IColony colony, final BlockPos pos) {
        super(colony, pos);
    }

    /**
     * A blueprint path as Structurize loads it: with ".blueprint" at the end. {@code /structurize paste ... decorator.workshop5}
     * hands MineColonies the typed path without it, MineColonies stores it as it is, and every later load of the hut's
     * blueprint (its corners, the prestige recalculation) then fails with "Error loading blueprint". Null or blank comes
     * back unchanged.
     */
    public static String withExtension(final String path) {
        if (path == null || path.isBlank() || path.endsWith(".blueprint")) {
            return path;
        }
        return path + ".blueprint";
    }

    /** The stored path, with ".blueprint" even when an older save or a typed paste stored it without (the next save writes it so). */
    @Override
    public String getBlueprintPath() {
        return withExtension(super.getBlueprintPath());
    }

    /** Stores the path with ".blueprint"; MineColonies passes it on to the hut block entity. */
    @Override
    public void setBlueprintPath(final String path) {
        super.setBlueprintPath(withExtension(path));
    }

    /** The look, from the blueprint's file name without its level digits ("decorator/workshop3.blueprint" is "workshop"). */
    @Override
    public @NotNull String getSchematicName() {
        return lookOf(getBlueprintPath());
    }

    /** The look name in a blueprint path, {@link #DEFAULT_LOOK} for none. */
    public static String lookOf(final String path) {
        return lookOf(path, DEFAULT_LOOK);
    }

    /** The look name in a blueprint path, {@code fallback} for none (the Town Square has its own). */
    public static String lookOf(final String path, final String fallback) {
        if (path == null || path.isBlank()) {
            return fallback;
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
        return name.isEmpty() ? fallback : name;
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

    private static ISettingKey<BoolSetting> bool(final String name) {
        return new SettingKey<>(BoolSetting.class, ResourceLocation.fromNamespaceAndPath(ColonySeasons.MODID, name));
    }

    private boolean flag(final ISettingKey<BoolSetting> key) {
        final Boolean value = getSettingValueOrDefault(key, Boolean.TRUE);
        return value == null || value;
    }

    /** The "decorate" setting: false means he puts nothing up (he still prepares stock and takes everything down). */
    public boolean decorateEnabled() {
        return flag(DECORATE);
    }

    /** The kinds the hut's settings allow him to put up. */
    public DecorKinds kinds() {
        return new DecorKinds(flag(KIND_WREATHS), flag(KIND_GARLANDS), flag(KIND_LIGHTS), flag(KIND_TREE));
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

    // ---------------------------------------------------------------- the storage upgrade (S10e)

    /** Storage steps paid for (0 when the module is somehow missing). */
    public int storageStep() {
        final DecoratorStorageModule storage = getModule(DecoratorModules.STORAGE);
        return storage == null ? 0 : storage.step();
    }

    /**
     * True when the hut window may offer the next storage step: built, at the upgrade level, a step left, and the
     * module that counts the steps is there (without it {@link #upgradeStorage} would do nothing after the price was
     * taken).
     */
    public boolean canUpgradeStorage() {
        return isBuilt() && getBuildingLevel() >= STORAGE_UPGRADE_LEVEL && getModule(DecoratorModules.STORAGE) != null
                && storageStep() < MAX_STORAGE_STEP;
    }

    /**
     * One storage step, as MineColonies' {@code BuildingWareHouse.upgradeContainers}: every rack of the hut (not the
     * hut block itself) grows by one row, then the step is counted and the building saved. The price is taken before
     * this is called ({@link me.lovkar.seasons.network.UpgradeStoragePayload}). Nothing happens past the last step.
     */
    public void upgradeStorage(final Level level) {
        final DecoratorStorageModule storage = getModule(DecoratorModules.STORAGE);
        if (storage == null || storage.step() >= MAX_STORAGE_STEP) {
            return;
        }
        for (final BlockPos pos : getContainers()) {
            final BlockEntity entity = level.getBlockEntity(pos);
            if (entity instanceof TileEntityRack rack && !(entity instanceof TileEntityColonyBuilding)) {
                rack.upgradeRackSize();
            }
        }
        storage.increment();
        markDirty();
    }

    /**
     * A rack that joins the hut after a storage upgrade (a new level's racks, one the player puts in) comes in at the
     * paid size, as a rack put into an upgraded Warehouse does. Never marks a rack as a warehouse rack.
     */
    @Override
    public void registerBlockPosition(final @NotNull Block block, final @NotNull BlockPos pos, final @NotNull Level world) {
        super.registerBlockPosition(block, pos, world);
        final BlockEntity entity = world.getBlockEntity(pos);
        if (entity instanceof TileEntityRack rack && !(entity instanceof TileEntityColonyBuilding)) {
            final int step = storageStep();
            while (rack.getUpgradeSize() < step) {
                rack.upgradeRackSize();
            }
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
