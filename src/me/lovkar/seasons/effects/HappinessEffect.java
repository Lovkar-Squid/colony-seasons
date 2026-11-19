package me.lovkar.seasons.effects;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.entity.citizen.citizenhandlers.ICitizenHappinessHandler;
import com.minecolonies.api.entity.citizen.happiness.ExpirationBasedHappinessModifier;
import com.minecolonies.api.entity.citizen.happiness.IHappinessModifier;
import com.minecolonies.api.entity.citizen.happiness.StaticHappinessSupplier;
import com.minecolonies.api.research.util.ResearchConstants;
import me.lovkar.seasons.SeasonsConfig;
import me.lovkar.seasons.calendar.CalendarManager;

/**
 * Festival happiness (design 8, {@code effects.festivalHappiness}, off by default): on festival days every colonist
 * gets a happiness bonus through an {@link ExpirationBasedHappinessModifier} with our own id.
 *
 * <p>MineColonies keeps only its own fixed list of modifier ids across a world load and shows only those in the citizen
 * window, so a custom id is dropped on every load and never listed there. Two things follow, and both are on purpose:</p>
 * <ul>
 *   <li>the server puts the modifier back on every pass ({@link Effects#TICKS}) while a festival's days last, and swaps
 *       in a neutral one (factor 1, which the happiness formula skips) when they end or the switch is turned off, so
 *       nothing outlives the festival; a server that stops running the mod loses it by itself (it is not saved, and it
 *       expires after {@link #PERIOD_DAYS} game days);</li>
 *   <li>the total a player sees is still the server's: MineColonies sends the finished number with each citizen, our
 *       modifier is in it, and only the list of reasons behind it leaves ours out. The Decorator's hut window says what
 *       the switch does.</li>
 * </ul>
 *
 * <p>The strength is in happiness points (MineColonies shows 0 to 10). Happiness is the weighted mean of every
 * modifier's factor times ten (a factor of exactly 1 is left out of the mean), so the factor that adds {@code bonus}
 * points is worked out per citizen from the other modifiers, and again on every pass, because they move.</p>
 */
public final class HappinessEffect {

    /** Our modifier's id. Not one of MineColonies' own, so it is never saved and never shown in the citizen window. */
    public static final String ID = "colonyseasons_festival";
    /** Its weight in the mean: a firm voice among MineColonies' own (theirs run from 1 to a few). */
    public static final double WEIGHT = 4.0;
    /** Game days a modifier lasts without being put back (the periodic pass puts it back long before). */
    public static final int PERIOD_DAYS = 2;
    /** A modifier whose factor is this close to the wanted one is left as it is. */
    static final double TOLERANCE = 0.05;
    /** The factor of a modifier that does nothing: MineColonies skips factor 1 in the mean. */
    static final double NEUTRAL = 1.0;
    static final double MAX_FACTOR = 6.0;

    private HappinessEffect() {
    }

    /** True while the effect is on and a festival's days are open: the bonus is wanted. */
    public static boolean active() {
        final CalendarManager.Snapshot snapshot = CalendarManager.current();
        return SeasonsConfig.festivalHappiness() && SeasonsConfig.festivalHappinessBonus() > 0
                && snapshot != null && !snapshot.festivals().days().isEmpty();
    }

    /** One pass over every colony: puts the bonus on (festival days) or takes it off (anything else). */
    public static void applyAll() {
        final boolean on = active();
        for (final IColony colony : IColonyManager.getInstance().getAllColonies()) {
            if (colony != null) {
                apply(colony, on);
            }
        }
    }

    /** The same for one colony. */
    public static void apply(final IColony colony, final boolean on) {
        for (final ICitizenData citizen : colony.getCitizenManager().getCitizens()) {
            if (citizen != null) {
                sync(colony, citizen, on);
            }
        }
    }

    /** True when the citizen carries our modifier and it is doing something. */
    public static boolean present(final ICitizenData citizen) {
        final ICitizenHappinessHandler handler = citizen.getCitizenHappinessHandler();
        final IHappinessModifier mine = handler == null ? null : handler.getModifier(ID);
        return mine != null && mine.getFactor(citizen) != NEUTRAL;
    }

    /** Brings one citizen in line: the bonus on, or ours neutral. */
    public static void sync(final IColony colony, final ICitizenData citizen, final boolean on) {
        final ICitizenHappinessHandler handler = citizen.getCitizenHappinessHandler();
        if (handler == null) {
            return;
        }
        final IHappinessModifier mine = handler.getModifier(ID);
        final boolean doing = mine != null && mine.getFactor(citizen) != NEUTRAL;
        if (!on) {
            if (doing) {
                handler.addModifier(new ExpirationBasedHappinessModifier(ID, WEIGHT, new StaticHappinessSupplier(NEUTRAL), 0));
            }
            return;
        }
        final double wanted = factorFor(colony, citizen, handler, SeasonsConfig.festivalHappinessBonus());
        if (doing && Math.abs(mine.getFactor(citizen) - wanted) < TOLERANCE) {
            return;
        }
        handler.addModifier(new ExpirationBasedHappinessModifier(ID, WEIGHT, new StaticHappinessSupplier(wanted), PERIOD_DAYS));
    }

    /**
     * The factor of a modifier of weight {@link #WEIGHT} that raises this citizen's happiness by {@code bonus} points:
     * with the others' weighted mean {@code m} and weight {@code W}, the new mean is {@code (m W + f w) / (W + w)}, so
     * {@code f = m + b (W + w) / w} for {@code b} the bonus in mean units (points divided by ten, and by the research's
     * happiness multiplier). Never exactly 1 (that would be skipped) and never above {@link #MAX_FACTOR}.
     */
    static double factorFor(final IColony colony, final ICitizenData citizen, final ICitizenHappinessHandler handler, final double bonus) {
        double sum = 0;
        double weights = 0;
        for (final String id : handler.getModifiers()) {
            if (ID.equals(id)) {
                continue;
            }
            final IHappinessModifier other = handler.getModifier(id);
            if (other == null) {
                continue;
            }
            final double factor = other.getFactor(citizen);
            if (factor == NEUTRAL) {
                continue;
            }
            sum += factor * other.getWeight();
            weights += other.getWeight();
        }
        final double mean = weights > 0 ? sum / weights : NEUTRAL;
        double research = 1.0;
        try {
            research += colony.getResearchManager().getResearchEffects().getEffectStrength(ResearchConstants.HAPPINESS);
        } catch (final RuntimeException noResearch) {
            // a colony with no research manager yet: the plain multiplier
        }
        final double inMean = bonus / (10.0 * Math.max(0.1, research));
        double factor = mean + inMean * (weights + WEIGHT) / WEIGHT;
        factor = Math.max(0.0, Math.min(MAX_FACTOR, factor));
        if (Math.abs(factor - NEUTRAL) < 1e-6) {
            factor = NEUTRAL + 1e-6;
        }
        return factor;
    }
}
