package me.lovkar.seasons.colony;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.core.colony.jobs.AbstractJob;
import me.lovkar.seasons.ai.EntityAIWorkDecorator;
import me.lovkar.seasons.ai.EntityAIWorkDecorator.DecoratorState;
import me.lovkar.seasons.decor.DecorPhase;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.EnumMap;
import java.util.Map;

/**
 * The Decorator, {@code colony_seasons:decorator}. A day worker: he prepares decorations at his hut all year, puts
 * them up when a festival's window opens and takes them down afterwards.
 *
 * <p>Nothing is saved in the job. What he has done lives in the building and in items; the fields here are
 * transient diagnostics (the phase of the last decision, how often each phase state was visited, a status line)
 * that the probes and the hut window of a later chunk read.</p>
 */
public class JobDecorator extends AbstractJob<EntityAIWorkDecorator, JobDecorator> {

    private DecorPhase phase = DecorPhase.OFF_SEASON;
    private final Map<DecoratorState, Integer> visits = new EnumMap<>(DecoratorState.class);
    private int decisions;
    private String status = "";

    public JobDecorator(final ICitizenData citizen) {
        super(citizen);
    }

    @Override
    public EntityAIWorkDecorator generateAI() {
        return new EntityAIWorkDecorator(this);
    }

    @Override
    public @NotNull ResourceLocation getModel() {
        return DecoratorSetup.MODEL_ID;
    }

    /** The phase of his last decision. */
    public DecorPhase phase() {
        return phase;
    }

    public void setPhase(final DecorPhase phase) {
        this.phase = phase == null ? DecorPhase.OFF_SEASON : phase;
    }

    /** How often the AI went through {@code state} since the server started (a phase stub, or assembling). */
    public int visits(final DecoratorState state) {
        return visits.getOrDefault(state, 0);
    }

    public void recordVisit(final DecoratorState state) {
        visits.merge(state, 1, Integer::sum);
    }

    /** How many decisions the AI has taken since the server started. */
    public int decisions() {
        return decisions;
    }

    public void countDecision() {
        decisions++;
    }

    /** What he is doing, in English, for anyone who asks (a later chunk shows it in the hut window). */
    public String status() {
        return status;
    }

    public void setStatus(final String status) {
        this.status = status == null ? "" : status;
    }
}
