package me.lovkar.seasons.colony;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.HiringMode;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.modules.AbstractBuildingModule;
import com.minecolonies.api.colony.buildings.modules.IAssignsJob;
import com.minecolonies.api.colony.buildings.modules.IBuildingEventsModule;
import com.minecolonies.api.colony.buildings.modules.IPersistentModule;
import com.minecolonies.api.colony.buildings.modules.ITickingModule;
import com.minecolonies.api.colony.jobs.registry.JobEntry;
import com.minecolonies.api.colony.managers.interfaces.ICitizenManager;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.core.util.BuildingUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The Town Square's Decorator (0.2.0, chunk Q1, docs/town-square-design.md 2.2 and 4.3): which colony Decorator works
 * this square. The Courier and Warehouse pattern of MineColonies, with the Quarry's details:
 *
 * <ul>
 *   <li>[B] It is no {@code AbstractAssignedCitizenModule} (whose two hooks are package-private abstract, so a class
 *       of ours cannot be one) and no {@code WorkerBuildingModule} (whose removal takes the job off the citizen). It
 *       keeps citizen ids, never touches a job, and never moves anyone: the Decorator stays employed at his
 *       Workshop ({@link JobDecorator#assignTo} answers true for this module without moving him).</li>
 *   <li>[B] It takes only a Decorator: MineColonies' HireFireMessage does no job check of its own.</li>
 *   <li>[B] Pruning is ours: MineColonies' citizen removal cleans only its own assigned-citizen modules. A citizen who
 *       died, or is no longer a Decorator, is dropped at the next colony tick, when the save is read (the
 *       citizens and their jobs are read before the buildings) and before another citizen is assigned. Both are
 *       already left out of {@link #getAssignedCitizen()} before that, so MineColonies never counts anyone but a
 *       Decorator as the square's.</li>
 * </ul>
 * One Decorator a square ({@link #MAX}); a Decorator serves at most one square. Auto-hire (hiring mode, default
 * DEFAULT: the town hall's setting) takes the colony's lowest-id Decorator who works at a Workshop and is on no square.
 */
public class SquareAssignmentModule extends AbstractBuildingModule
        implements IAssignsJob, IBuildingEventsModule, ITickingModule, IPersistentModule {

    /** One Decorator works a square. */
    public static final int MAX = 1;
    static final String NBT_DECORATORS = "decorators";
    static final String NBT_HIRING_MODE = "hiringmode";

    /** The assigned citizens' ids, in the order they came. */
    private final Set<Integer> ids = new LinkedHashSet<>();
    private HiringMode hiringMode = HiringMode.DEFAULT;
    /** The main square as the last colony tick read it (never saved); the page is marked for sending when it changes. */
    private SquareStatus.Main lastMain;

    @Override
    public JobEntry getJobEntry() {
        return DecoratorSetup.JOB.get();
    }

    @Override
    public int getModuleMax() {
        return MAX;
    }

    private ICitizenManager citizens() {
        final IBuilding b = getBuilding();
        return b == null || b.getColony() == null ? null : b.getColony().getCitizenManager();
    }

    /** The ids as they are stored (dead or re-jobbed ones included until the next prune). */
    public List<Integer> assignedIds() {
        return List.copyOf(ids);
    }

    /**
     * The assigned citizens who still exist and are still Decorators; never changes anything (the id of a dead or
     * re-jobbed citizen waits for the next prune).
     */
    @Override
    public List<ICitizenData> getAssignedCitizen() {
        final ICitizenManager manager = citizens();
        final List<ICitizenData> out = new ArrayList<>(ids.size());
        if (manager == null) {
            return out;
        }
        for (final int id : ids) {
            final ICitizenData citizen = manager.getCivilian(id);
            if (citizen != null && citizen.getJob() instanceof JobDecorator) {
                out.add(citizen);
            }
        }
        return out;
    }

    @Override
    public List<Optional<AbstractEntityCitizen>> getAssignedEntities() {
        final List<Optional<AbstractEntityCitizen>> out = new ArrayList<>();
        for (final ICitizenData citizen : getAssignedCitizen()) {
            out.add(citizen.getEntity());
        }
        return out;
    }

    @Override
    public boolean hasAssignedCitizen(final ICitizenData citizen) {
        return citizen != null && ids.contains(citizen.getId()) && getAssignedCitizen().contains(citizen);
    }

    @Override
    public boolean hasAssignedCitizen() {
        return !getAssignedCitizen().isEmpty();
    }

    @Override
    public boolean isFull() {
        return getAssignedCitizen().size() >= getModuleMax();
    }

    @Override
    public HiringMode getHiringMode() {
        return hiringMode;
    }

    @Override
    public void setHiringMode(final HiringMode mode) {
        final HiringMode next = mode == null ? HiringMode.DEFAULT : mode;
        if (next != hiringMode) {
            hiringMode = next;
            markDirty();
        }
    }

    /** Why {@code citizen} cannot be assigned here now, or null when he can (the rules of the class comment). */
    String refusal(final ICitizenData citizen) {
        if (citizen == null) {
            return "no citizen";
        }
        if (citizen.isChild()) {
            return "a child";
        }
        if (!(citizen.getJob() instanceof JobDecorator)) {
            return citizen.getJob() == null ? "jobless" : "not a Decorator";
        }
        final IBuilding square = getBuilding();
        if (square == null || !square.canAssignCitizens()) {
            return "the square is not built";
        }
        if (isFull()) {
            return "the square has its Decorator";
        }
        for (final BuildingTownSquare other : squaresListing(square.getColony(), citizen)) {
            if (other != square) {
                return "he works another square";
            }
        }
        return null;
    }

    /**
     * Assigns a Decorator to this square. His job and his Workshop stay as they are (nothing here calls
     * {@code job.assignTo}, {@code onRemoval} or sets a work building). A refusal marks the module dirty, so the window,
     * which listed him in its own copy first, gets the true list again. The ids of the dead and the re-jobbed go first,
     * so one of them who is a Decorator again before the next colony tick cannot come back as a second Decorator here.
     */
    @Override
    public boolean assignCitizen(final ICitizenData citizen) {
        if (hasAssignedCitizen(citizen)) {
            return true;
        }
        prune();
        if (refusal(citizen) != null) {
            markDirty();
            return false;
        }
        ids.add(citizen.getId());
        markDirty();
        return true;
    }

    /** Takes a citizen off the square; he stays the Decorator, employed at his Workshop. */
    @Override
    public boolean removeCitizen(final ICitizenData citizen) {
        if (citizen != null && ids.remove(citizen.getId())) {
            markDirty();
            return true;
        }
        return false;
    }

    /**
     * Drops every id whose citizen is gone or whose job is no longer the Decorator's. Marks the module dirty only when
     * something went. Returns how many went.
     */
    public int prune() {
        final ICitizenManager manager = citizens();
        if (manager == null) {
            return 0;
        }
        int dropped = 0;
        for (final Integer id : new ArrayList<>(ids)) {
            final ICitizenData citizen = manager.getCivilian(id);
            if (citizen == null || !(citizen.getJob() instanceof JobDecorator)) {
                ids.remove(id);
                dropped++;
            }
        }
        if (dropped > 0) {
            markDirty();
        }
        return dropped;
    }

    /** Prune, then auto-hire when MineColonies' own rule for this building and hiring mode allows it. */
    @Override
    public void onColonyTick(final IColony colony) {
        prune();
        // another square became (or stopped being) the main one: the page is sent again (nothing changed, nothing is sent)
        final SquareStatus.Main main = main();
        if (!main.equals(lastMain)) {
            lastMain = main;
            markDirty();
        }
        if (isFull() || colony == null || !BuildingUtils.canAutoHire(getBuilding(), hiringMode, getJobEntry())) {
            return;
        }
        final List<ICitizenData> candidates = new ArrayList<>(colony.getCitizenManager().getCitizens());
        candidates.sort(Comparator.comparingInt(ICitizenData::getId));
        for (final ICitizenData citizen : candidates) {
            if (citizen.getJob() instanceof JobDecorator && citizen.getWorkBuilding() instanceof BuildingDecorator && !citizen.isChild()
                    && squaresListing(colony, citizen).isEmpty()) {
                assignCitizen(citizen);
                return;
            }
        }
    }

    /** The square is gone: nobody is assigned any more (each Decorator stays employed at his Workshop). */
    @Override
    public void onDestroyed() {
        if (!ids.isEmpty()) {
            ids.clear();
            markDirty();
        }
    }

    /**
     * Reads the ids and the hiring mode (a missing or short array reads as none, an unknown mode as DEFAULT), then drops
     * the dead and the re-jobbed: the colony's citizens and their jobs are read before its buildings.
     */
    @Override
    public void deserializeNBT(final @NotNull HolderLookup.Provider provider, final CompoundTag compound) {
        ids.clear();
        for (final int id : compound.getIntArray(NBT_DECORATORS)) {
            ids.add(id);
        }
        hiringMode = HiringMode.DEFAULT;
        final String mode = compound.getString(NBT_HIRING_MODE);
        for (final HiringMode m : HiringMode.values()) {
            if (m.name().equals(mode)) {
                hiringMode = m;
            }
        }
        prune();
    }

    @Override
    public void serializeNBT(final @NotNull HolderLookup.Provider provider, final CompoundTag compound) {
        compound.putIntArray(NBT_DECORATORS, ids.stream().mapToInt(Integer::intValue).toArray());
        compound.putString(NBT_HIRING_MODE, hiringMode.name());
    }

    /** The window's copy: the assigned citizens who exist, the hiring mode, the most this square takes ({@link SquareAssignmentModuleView}). */
    @Override
    public void serializeToView(final @NotNull RegistryFriendlyByteBuf buf) {
        final List<ICitizenData> shown = getAssignedCitizen();
        buf.writeInt(shown.size());
        for (final ICitizenData citizen : shown) {
            buf.writeInt(citizen.getId());
        }
        buf.writeInt(hiringMode.ordinal());
        buf.writeInt(getModuleMax());
        // which square is the colony's main one, for the page's line (Q5): the standing, its hut's position, its level
        final SquareStatus.Main main = main();
        buf.writeVarInt(main.standing().ordinal());
        buf.writeBlockPos(main.pos());
        buf.writeVarInt(main.level());
    }

    /**
     * The colony's main square as this square's page shows it (reads only; {@link SquareStatus.Main#NONE} without a
     * building). Never throws: it runs on the colony tick, where a throw would stop the huts after this one, and in the
     * packet writer; a failed reading keeps the last one.
     */
    private SquareStatus.Main main() {
        try {
            return getBuilding() instanceof BuildingTownSquare square ? SquareStatus.mainOf(square) : SquareStatus.Main.NONE;
        } catch (final RuntimeException unread) {
            return lastMain != null ? lastMain : SquareStatus.Main.NONE;
        }
    }

    /** Every Town Square of the colony whose module lists {@code citizen}, lowest building position first. */
    public static List<BuildingTownSquare> squaresListing(final IColony colony, final ICitizenData citizen) {
        final List<BuildingTownSquare> out = new ArrayList<>();
        if (colony == null || citizen == null) {
            return out;
        }
        for (final IBuilding b : colony.getServerBuildingManager().getBuildings().values()) {
            if (b instanceof BuildingTownSquare square) {
                final SquareAssignmentModule module = square.assignment();
                if (module != null && module.hasAssignedCitizen(citizen)) {
                    out.add(square);
                }
            }
        }
        out.sort(Comparator.comparing(IBuilding::getPosition));
        return out;
    }
}
