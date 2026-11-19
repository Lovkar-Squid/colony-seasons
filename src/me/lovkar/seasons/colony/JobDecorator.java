package me.lovkar.seasons.colony;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.core.colony.jobs.AbstractJob;
import me.lovkar.seasons.ai.EntityAIWorkDecorator;
import me.lovkar.seasons.ai.EntityAIWorkDecorator.DecoratorState;
import me.lovkar.seasons.decor.DecorPhase;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
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
    private String statusKey = "";
    private String[] statusArgs = new String[0];

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

    /**
     * What he is doing, as a lang key of {@link DecoratorTexts} plus its arguments; the hut window translates it for
     * each player. This text is for logs and probes: the key, then the arguments in brackets.
     */
    public String status() {
        return statusArgs.length == 0 ? statusKey : statusKey + " " + Arrays.toString(statusArgs);
    }

    /** The lang key of the status line ("" before the first one). */
    public String statusKey() {
        return statusKey;
    }

    /** The arguments of the status line (item ids and plain text), never null. */
    public String[] statusArgs() {
        return statusArgs.clone();
    }

    /** Sets the status line: a {@link DecoratorTexts} key and what it is about. */
    public void setStatus(final String key, final Object... args) {
        statusKey = key == null ? "" : key;
        final String[] text = new String[args == null ? 0 : args.length];
        for (int i = 0; i < text.length; i++) {
            text[i] = String.valueOf(args[i]);
        }
        statusArgs = text;
    }
}
