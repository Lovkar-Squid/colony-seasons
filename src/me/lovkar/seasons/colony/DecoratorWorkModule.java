package me.lovkar.seasons.colony;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.HiringMode;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.modules.IAssignsCitizen;
import com.minecolonies.api.colony.buildings.modules.IAssignsJob;
import com.minecolonies.api.colony.jobs.IJob;
import com.minecolonies.api.colony.jobs.registry.JobEntry;
import com.minecolonies.api.entity.citizen.Skill;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import me.lovkar.seasons.ColonySeasons;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * The Decorator's work module: MineColonies' plain worker module, plus one thing its hut window needs.
 *
 * <p>When a player hires a citizen who works elsewhere, MineColonies' hire window sends two messages: a fire to the
 * citizen's old hut, then a hire to this one. The server drops a building message when the player stands more than 15
 * blocks from that building, so from the Workshop the fire at a distant hut never arrives. The hire then reaches
 * {@link WorkerBuildingModule#assignCitizen}, whose job check refuses a citizen who still has another job; the window
 * showed him as the Decorator only because it changes its own copy first (the in-game test of 20 Nov 2026). Here the
 * hire does what the lost fire would have done: it takes him off his old hut, exactly as that hut's fire button does,
 * and then hires him. MineColonies' colony permission check for this hut still applies; auto-hire and loading a save
 * are unchanged, as both only ever hand over a jobless citizen or one with this job.</p>
 */
public class DecoratorWorkModule extends WorkerBuildingModule {

    public DecoratorWorkModule(final JobEntry entry, final Skill primary, final Skill secondary, final boolean canWorkingDuringRain,
                               final Function<IBuilding, Integer> sizeLimit) {
        super(entry, primary, secondary, canWorkingDuringRain, sizeLimit);
    }

    /** True while a save is read: loading keeps MineColonies' own rule (a listed citizen with another job is not taken). */
    private boolean loading;

    @Override
    public void deserializeNBT(final @NotNull HolderLookup.Provider provider, final CompoundTag compound) {
        loading = true;
        try {
            super.deserializeNBT(provider, compound);
        } finally {
            loading = false;
        }
    }

    @Override
    public boolean assignCitizen(final ICitizenData citizen) {
        // a child (a pupil at the school) is never taken off his place: the hut window never offers one
        if (!loading && citizen != null && !citizen.isChild() && !hasAssignedCitizen(citizen) && !isFull() && citizen.getJob() != null
                && !getJobEntry().equals(citizen.getJob().getJobRegistryEntry())) {
            release(citizen);
        }
        final boolean hired = super.assignCitizen(citizen);
        if (!hired && !loading && citizen != null) {
            // the window already lists him: a new copy of the hut puts its list right
            markDirty();
        }
        return hired;
    }

    /**
     * What the fire button at the citizen's old hut does: each job module there that lists him lets him go. The old
     * hut's hiring mode is noted first and put back afterwards wherever it changed (S10c: a hut a player left on
     * automatic hiring must stay so, and fill the place again itself).
     */
    private void release(final ICitizenData citizen) {
        final IJob<?> old = citizen.getJob();
        final String oldJob = old.getJobRegistryEntry() == null ? "?" : String.valueOf(old.getJobRegistryEntry().getKey());
        final IBuilding oldBuilding = citizen.getWorkBuilding();
        final BlockPos oldPos = oldBuilding == null ? null : oldBuilding.getID();
        final Map<IAssignsCitizen, HiringMode> modes = new LinkedHashMap<>();
        if (oldBuilding != null) {
            for (final IAssignsCitizen module : oldBuilding.getModulesByType(IAssignsCitizen.class)) {
                modes.put(module, module.getHiringMode());
            }
            for (final IAssignsJob module : oldBuilding.getModulesByType(IAssignsJob.class)) {
                if (module.hasAssignedCitizen(citizen)) {
                    module.removeCitizen(citizen);
                }
            }
        }
        if (citizen.getJob() != null) {
            // a job no module listed: take it off him the same way
            citizen.getJob().onRemoval();
        }
        boolean restored = false;
        for (final Map.Entry<IAssignsCitizen, HiringMode> e : modes.entrySet()) {
            if (e.getKey().getHiringMode() != e.getValue()) {
                e.getKey().setHiringMode(e.getValue());
                restored = true;
            }
        }
        if (restored) {
            oldBuilding.markDirty();
        }
        ColonySeasons.LOGGER.info("Decorator's Workshop at {}: citizen {} leaves his job {} at {} to be hired here; that hut keeps its hiring mode {}{}",
                building == null ? "?" : building.getID().toShortString(), citizen.getId(), oldJob,
                oldPos == null ? "no building" : oldPos.toShortString(), modes.isEmpty() ? "(none)" : modes.values(),
                restored ? " (put back)" : "");
    }
}
