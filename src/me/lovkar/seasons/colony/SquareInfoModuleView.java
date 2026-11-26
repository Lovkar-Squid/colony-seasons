package me.lovkar.seasons.colony;

import com.ldtteam.blockui.views.BOWindow;
import com.minecolonies.api.colony.buildings.modules.AbstractBuildingModuleView;
import me.lovkar.seasons.ColonySeasons;
import me.lovkar.seasons.client.TownSquareInfoWindow;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** The client's copy of the Town Square's info page: it reads what {@link SquareInfoModule#serializeToView} wrote, field for field. */
public class SquareInfoModuleView extends AbstractBuildingModuleView {

    private SquareInfoModule.Info info = SquareInfoModule.empty();

    @Override
    public void deserialize(final @NotNull RegistryFriendlyByteBuf buf) {
        final int level = buf.readVarInt();
        final int maxLevel = buf.readVarInt();
        final String order = buf.readUtf();
        final int standing = buf.readVarInt();
        final BlockPos mainPos = buf.readBlockPos();
        final int mainLevel = buf.readVarInt();
        final String wantedSet = buf.readUtf();
        final String upSet = buf.readUtf();
        final int state = buf.readVarInt();
        final int changeKind = buf.readVarInt();
        final String changeSet = buf.readUtf();
        final String changeDate = buf.readUtf();
        final String festival = buf.readUtf();
        final boolean festivalDays = buf.readBoolean();
        final int daysUntilNext = buf.readVarInt();
        final String decorator = buf.readUtf();
        final boolean decoratorHere = buf.readBoolean();
        final int lacks = buf.readVarInt();
        final List<SquareInfoModule.MissingRow> missing = new ArrayList<>();
        for (int i = 0; i < lacks; i++) {
            missing.add(new SquareInfoModule.MissingRow(buf.readUtf(), buf.readVarInt()));
        }
        final int nextLevel = buf.readVarInt();
        final int adds = buf.readVarInt();
        final List<SquareInfoModule.AddedRow> added = new ArrayList<>();
        for (int i = 0; i < adds; i++) {
            added.add(new SquareInfoModule.AddedRow(buf.readUtf(), buf.readVarInt()));
        }
        info = new SquareInfoModule.Info(level, maxLevel, order, standing, mainPos, mainLevel, wantedSet, upSet, state, changeKind,
                changeSet, changeDate, festival, festivalDays, daysUntilNext, decorator, decoratorHere, List.copyOf(missing),
                nextLevel, List.copyOf(added));
    }

    /** What the last packet said (an empty page before the first one). */
    public SquareInfoModule.Info info() {
        return info;
    }

    /** Typed as BlockUI's base window, so a dedicated server can load this view without the window classes (as {@link DecoratorInfoModuleView}). */
    @Override
    public BOWindow getWindow() {
        return TownSquareInfoWindow.create(this);
    }

    @Override
    public ResourceLocation getIconResourceLocation() {
        return ResourceLocation.fromNamespaceAndPath(ColonySeasons.MODID, "textures/gui/decorator_info.png");
    }

    @Override
    public Component getDesc() {
        return Component.translatable("com.colony_seasons.gui.square.info.tab");
    }
}
