package me.lovkar.seasons.colony;

import com.ldtteam.blockui.views.BOWindow;
import com.minecolonies.api.colony.buildings.modules.AbstractBuildingModuleView;
import me.lovkar.seasons.client.DecoratorStorageWindow;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/** The client's copy of the Workshop's storage step: it reads what {@link DecoratorStorageModule#serializeToView} wrote. */
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

    /** The window counts its own click at once, as MineColonies' Warehouse window does; the next packet sets it right. */
    public void stepTaken() {
        if (step < BuildingDecorator.MAX_STORAGE_STEP) {
            step++;
        }
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
