package me.lovkar.seasons.colony;

import com.ldtteam.blockui.views.BOWindow;
import com.minecolonies.api.colony.buildings.modules.AbstractBuildingModuleView;
import me.lovkar.seasons.ColonySeasons;
import me.lovkar.seasons.client.DecoratorInfoWindow;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** The client's copy of the Decorator's page: it reads what {@link DecoratorInfoModule#serializeToView} wrote, field for field. */
public class DecoratorInfoModuleView extends AbstractBuildingModuleView {

    private DecoratorInfoModule.Info info = empty();

    @Override
    public void deserialize(final @NotNull RegistryFriendlyByteBuf buf) {
        final int season = buf.readVarInt();
        final int dayOfYear = buf.readVarInt();
        final int yearLength = buf.readVarInt();
        final String festival = buf.readUtf();
        final boolean festivalDays = buf.readBoolean();
        final int daysUntilNext = buf.readVarInt();
        final String decorWindow = buf.readUtf();
        final String daysWindow = buf.readUtf();
        final String statusKey = buf.readUtf();
        final int args = buf.readVarInt();
        final List<String> statusArgs = new ArrayList<>();
        for (int i = 0; i < args; i++) {
            statusArgs.add(buf.readUtf());
        }
        final boolean squareProblem = buf.readBoolean();
        final int squareKind = buf.readVarInt();
        final int squareLevel = buf.readVarInt();
        final String squareSet = buf.readUtf();
        final int squareState = buf.readVarInt();
        final boolean decorateOn = buf.readBoolean();
        final int decorationsUp = buf.readVarInt();
        final int buildingsDone = buf.readVarInt();
        final int usedToday = buf.readVarInt();
        final int quota = buf.readVarInt();
        final boolean needsMaterials = buf.readBoolean();
        final int stockCap = buf.readVarInt();
        final int kits = buf.readVarInt();
        final List<DecoratorInfoModule.StockRow> stock = new ArrayList<>();
        for (int i = 0; i < kits; i++) {
            stock.add(new DecoratorInfoModule.StockRow(buf.readUtf(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean()));
        }
        final String nextKit = buf.readUtf();
        final int lacks = buf.readVarInt();
        final List<DecoratorInfoModule.MissingRow> missing = new ArrayList<>();
        for (int i = 0; i < lacks; i++) {
            missing.add(new DecoratorInfoModule.MissingRow(buf.readUtf(), buf.readVarInt()));
        }
        final boolean happinessOn = buf.readBoolean();
        final double happinessBonus = buf.readDouble();
        final boolean cropsOn = buf.readBoolean();
        final double cropSkip = buf.readDouble();
        final boolean foodOn = buf.readBoolean();
        final double foodFactor = buf.readDouble();
        final boolean winter = buf.readBoolean();
        info = new DecoratorInfoModule.Info(season, dayOfYear, yearLength, festival, festivalDays, daysUntilNext, decorWindow,
                daysWindow, statusKey, List.copyOf(statusArgs), squareProblem, squareKind, squareLevel, squareSet, squareState, decorateOn, decorationsUp, buildingsDone, usedToday, quota,
                needsMaterials, stockCap, List.copyOf(stock), nextKit, List.copyOf(missing), happinessOn, happinessBonus,
                cropsOn, cropSkip, foodOn, foodFactor, winter);
    }

    /** What the last packet said (an empty page before the first one). */
    public DecoratorInfoModule.Info info() {
        return info;
    }

    private static DecoratorInfoModule.Info empty() {
        return new DecoratorInfoModule.Info(-1, 0, 0, "", false, -1, "", "", DecoratorTexts.NO_WORKER, List.of(), false, DecoratorInfoModule.SQUARE_NONE, 0, "", 0, true, 0, 0,
                0, 0, true, 0, List.of(), "", List.of(), false, 0, false, 0, false, 1, false);
    }

    /**
     * The window comes from a factory that is typed as the base class, so this class never needs the window classes to
     * be loaded just to be verified: a dedicated server can load the view (the rig probe reads the page's packet
     * through it) while only a client ever calls this.
     */
    @Override
    public BOWindow getWindow() {
        return DecoratorInfoWindow.create(this);
    }

    @Override
    public ResourceLocation getIconResourceLocation() {
        return ResourceLocation.fromNamespaceAndPath(ColonySeasons.MODID, "textures/gui/decorator_info.png");
    }

    @Override
    public Component getDesc() {
        return Component.translatable("com.colony_seasons.gui.info.tab");
    }
}
