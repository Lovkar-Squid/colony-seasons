package me.lovkar.seasons.effects;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import me.lovkar.seasons.SeasonsConfig;
import me.lovkar.seasons.calendar.CalendarManager;
import me.lovkar.seasons.calendar.Season;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Winter food need (design 8, {@code effects.winterFoodNeed}, off by default): in in-game winter colonists use
 * {@code effects.winterFoodNeedFactor} (1.25: about a quarter) times the food they usually do.
 *
 * <p>MineColonies has no per-colony food multiplier and no event for it, so this works from outside, once per
 * {@link Effects#TICKS} ticks: it remembers each citizen's saturation, and when it finds the saturation lower than at
 * the last look, it takes {@code (factor - 1)} times that drop off as well. The drop it saw is already what MineColonies
 * made of the colonist's work (its own food modifier included), so the extra is exactly that share of what he used,
 * whatever the server's food modifier is; it is taken with {@code setSaturation} for that reason ({@code
 * decreaseSaturation} would scale it by the modifier a second time). A saturation that went up (he ate) is only
 * remembered, so eating is never taxed and never refunded. Nothing is saved: after a restart the first look only
 * remembers. A colony that nobody is near does not tick its colonists' hunger, so there is nothing to see and nothing
 * is taken.</p>
 */
public final class FoodEffect {

    private static final Map<Long, Double> LAST = new HashMap<>();
    private static double baseDrop;
    private static double extraDrop;

    private FoodEffect() {
    }

    /** What colonists used on their own since the server started, in winter with the effect on (probe). */
    public static double baseDrop() {
        return baseDrop;
    }

    /** What the effect took on top of that since the server started (probe). */
    public static double extraDrop() {
        return extraDrop;
    }

    /** Forgets everything (a new server session, or a test). */
    public static void reset() {
        LAST.clear();
        baseDrop = 0;
        extraDrop = 0;
    }

    /** True while the effect is on and it is winter in the game. */
    public static boolean active() {
        final CalendarManager.Snapshot snapshot = CalendarManager.current();
        return SeasonsConfig.winterFoodNeed() && SeasonsConfig.winterFoodNeedFactor() > 1.0
                && snapshot != null && snapshot.season().season() == Season.WINTER;
    }

    /** One pass over every active colony. */
    public static void applyAll() {
        if (!active()) {
            LAST.clear();
            return;
        }
        final Set<Long> seen = new HashSet<>();
        for (final IColony colony : IColonyManager.getInstance().getAllColonies()) {
            if (colony != null) {
                for (final ICitizenData citizen : colony.getCitizenManager().getCitizens()) {
                    if (citizen != null) {
                        seen.add(look(colony, citizen));
                    }
                }
            }
        }
        LAST.keySet().retainAll(seen);
    }

    /** One look at one citizen; returns the key it is remembered under. */
    static long look(final IColony colony, final ICitizenData citizen) {
        final long key = ((long) colony.getID() << 32) | (citizen.getId() & 0xFFFFFFFFL);
        final double now = citizen.getSaturation();
        final Double before = LAST.get(key);
        if (before != null && before > now) {
            final double drop = before - now;
            baseDrop += drop;
            final double wanted = drop * (SeasonsConfig.winterFoodNeedFactor() - 1.0);
            citizen.setSaturation(Math.max(0.0, now - wanted));
            final double after = citizen.getSaturation();
            extraDrop += Math.max(0, now - after);
            LAST.put(key, after);
        } else {
            LAST.put(key, now);
        }
        return key;
    }
}
