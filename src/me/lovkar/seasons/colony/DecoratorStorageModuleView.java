package me.lovkar.seasons.colony;

import com.ldtteam.blockui.views.BOWindow;
import com.minecolonies.api.colony.buildings.modules.AbstractBuildingModuleView;
import me.lovkar.seasons.client.DecoratorStorageWindow;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * The client's copy of the Workshop's storage step: it reads what {@link DecoratorStorageModule#serializeToView} wrote.
 * MineColonies deserializes a building's module views in place when the server sends the building again, so an open
 * window reads the new step from this same object. The client never counts a step here itself (S10h): the window's
 * {@link StorageTabState} shows a click at once and waits for this value, so the server's step always wins.
 */
public class DecoratorStorageModuleView extends AbstractBuildingModuleView {

    private int step;

    @Override
    public void deserialize(final @NotNull RegistryFriendlyByteBuf buf) {
        step = buf.readInt();
    }

    /** Steps paid for, as the last packet said. */
    public int step() {
        return step;
    }

    /** Typed as BlockUI's base window, so a dedicated server can load this view without the window classes. */
    @Override
    public BOWindow getWindow() {
        return DecoratorStorageWindow.create(this);
    }

    @Override
    public ResourceLocation getIconResourceLocation() {
        return ResourceLocation.fromNamespaceAndPath("minecolonies", "textures/gui/modules/inventory.png");
    }

    @Override
    public Component getDesc() {
        return Component.translatable("com.colony_seasons.gui.storage.tab");
    }
}
