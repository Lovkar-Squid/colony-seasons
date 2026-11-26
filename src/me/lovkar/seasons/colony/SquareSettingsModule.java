package me.lovkar.seasons.colony;

import com.minecolonies.api.colony.requestsystem.StandardFactoryController;
import com.minecolonies.core.colony.buildings.modules.SettingsModule;
import com.minecolonies.core.colony.buildings.modules.settings.StringSetting;
import me.lovkar.seasons.decor.SquareSets;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.List;

/**
 * The Town Square's settings (0.2.0, chunk Q3): MineColonies' own settings module with the order button
 * ({@link BuildingTownSquare#ORDER}), read back by value instead of by position.
 *
 * <p>[V] design section 8, point 9, read from the bytecode of MineColonies 1.1.1420: {@code SettingsModule} reads a
 * stored {@code StringSetting} with its own saved list and index, then {@code updateSetting} swaps in this version's
 * list and keeps the index (clamped to the list). A value is therefore found by its position, not its text: a later
 * version's "Always Night of Lanterns" at position 2 would come back as Bare. So the stored value's text is read here
 * first, through MineColonies' own factory, and after the normal read the button is set to it; a text this version
 * does not know is Follow the calendar, with one log line ({@link SquareSets.Order#of}). What is written, sent to the
 * window and read on the client is MineColonies' own, byte for byte.</p>
 */
public class SquareSettingsModule extends SettingsModule {

    @Override
    public void deserializeNBT(final HolderLookup.Provider provider, final CompoundTag compound) {
        final String stored = storedValue(provider, compound);
        super.deserializeNBT(provider, compound);
        if (stored == null) {
            return;                                   // nothing stored (a square of Q1 or Q2): the default stays
        }
        final StringSetting order = getOptionalSetting(BuildingTownSquare.ORDER).orElse(null);
        if (order == null) {
            return;
        }
        final List<String> known = order.getSettings();
        order.set(known.contains(stored) ? stored : SquareSets.Order.of(stored).key());
    }

    /** The order's text as it was saved (MineColonies' factory reads the saved setting), or {@code null}. */
    static String storedValue(final HolderLookup.Provider provider, final CompoundTag compound) {
        try {
            final CompoundTag settings = compound.contains("settings") ? compound.getCompound("settings") : compound;
            final ListTag list = settings.getList("settingslist", Tag.TAG_COMPOUND);
            final String id = BuildingTownSquare.ORDER.getUniqueId().toString();
            for (int i = 0; i < list.size(); i++) {
                final CompoundTag entry = list.getCompound(i);
                if (!id.equals(entry.getString("key"))) {
                    continue;
                }
                final Object setting = StandardFactoryController.getInstance().deserializeTag(provider, entry.getCompound("value"));
                return setting instanceof StringSetting string ? string.getValue() : "";
            }
        } catch (final RuntimeException unreadable) {
            return "";                                // unreadable: Follow the calendar (logged by Order.of)
        }
        return null;
    }
}
