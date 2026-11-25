package me.lovkar.seasons.colony;

import com.ldtteam.blockui.views.BOWindow;
import com.minecolonies.api.colony.ICitizenDataView;
import com.minecolonies.api.colony.buildings.HiringMode;
import com.minecolonies.api.colony.buildings.modules.AbstractBuildingModuleView;
import com.minecolonies.api.colony.buildings.modules.IAssignmentModuleView;
import com.minecolonies.api.colony.buildings.views.IBuildingView;
import com.minecolonies.api.colony.jobs.registry.JobEntry;
import com.minecolonies.core.network.messages.server.colony.building.HireFireMessage;
import com.minecolonies.core.network.messages.server.colony.building.worker.BuildingHiringModeMessage;
import me.lovkar.seasons.client.SquareAssignmentWindow;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The client's copy of the Town Square's Decorator page (MineColonies' MinerAssignmentModuleView shape): it reads what
 * {@link SquareAssignmentModule#serializeToView} wrote, field for field. It is no WorkerBuildingModuleView, so
 * MineColonies' hire window adds a Decorator here without firing him at his Workshop. The hire window offers only
 * Decorators who are on no other square ({@link #canAssign}); the server checks the same again.
 */
public class SquareAssignmentModuleView extends AbstractBuildingModuleView implements IAssignmentModuleView {

    private final Set<Integer> ids = new LinkedHashSet<>();
    private HiringMode hiringMode = HiringMode.DEFAULT;
    private int max = SquareAssignmentModule.MAX;

    @Override
    public void deserialize(final @NotNull RegistryFriendlyByteBuf buf) {
        ids.clear();
        final int count = buf.readInt();
        for (int i = 0; i < count; i++) {
            ids.add(buf.readInt());
        }
        final int mode = buf.readInt();
        hiringMode = mode >= 0 && mode < HiringMode.values().length ? HiringMode.values()[mode] : HiringMode.DEFAULT;
        max = buf.readInt();
    }

    @Override
    public List<Integer> getAssignedCitizens() {
        return new ArrayList<>(ids);
    }

    /** The hire window's choice: listed here at once, and MineColonies' own hire message to the server. */
    @Override
    public void addCitizen(final ICitizenDataView citizen) {
        ids.add(citizen.getId());
        new HireFireMessage(buildingView, true, citizen.getId(), getProducer().getRuntimeID()).sendToServer();
    }

    @Override
    public void removeCitizen(final ICitizenDataView citizen) {
        ids.remove(citizen.getId());
        new HireFireMessage(buildingView, false, citizen.getId(), getProducer().getRuntimeID()).sendToServer();
    }

    @Override
    public HiringMode getHiringMode() {
        return hiringMode;
    }

    /** MineColonies' generic hiring-mode message (the Quarry's and the Courier's cast to their own module classes). */
    @Override
    public void setHiringMode(final HiringMode mode) {
        hiringMode = mode;
        new BuildingHiringModeMessage(buildingView, mode, getProducer().getRuntimeID()).sendToServer();
    }

    /** A grown-up Decorator who is on no other square. */
    @Override
    public boolean canAssign(final ICitizenDataView citizen) {
        if (citizen == null || citizen.isChild() || citizen.getJobView() == null || !getJobEntry().equals(citizen.getJobView().getEntry())) {
            return false;
        }
        for (final Object b : buildingView.getColony().getClientBuildingManager().getBuildings().values()) {
            if (b instanceof IBuildingView other && other != buildingView && !other.getID().equals(buildingView.getID())
                    && other.hasModuleView(SquareModules.DECORATOR)
                    && other.getModuleView(SquareModules.DECORATOR).getAssignedCitizens().contains(citizen.getId())) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int getMaxInhabitants() {
        return max;
    }

    @Override
    public boolean isFull() {
        return ids.size() >= max;
    }

    @Override
    public JobEntry getJobEntry() {
        return DecoratorSetup.JOB.get();
    }

    /** Typed as BlockUI's base window, so a dedicated server can load this view without the window classes. */
    @Override
    public BOWindow getWindow() {
        return SquareAssignmentWindow.create(this);
    }

    @Override
    public ResourceLocation getIconResourceLocation() {
        return ResourceLocation.fromNamespaceAndPath("minecolonies", "textures/gui/modules/entity.png");
    }

    @Override
    public Component getDesc() {
        return Component.translatable("com.colony_seasons.gui.square.decorator");
    }
}
