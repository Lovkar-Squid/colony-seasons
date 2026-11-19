package me.lovkar.seasons.effects;

import com.minecolonies.api.colony.IColonyManager;
import me.lovkar.seasons.SeasonsConfig;
import me.lovkar.seasons.calendar.CalendarManager;
import me.lovkar.seasons.calendar.Season;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.event.level.block.CropGrowEvent;

/**
 * Winter crop slowdown (design 8, {@code effects.winterCropSlowdown}, off by default): in in-game winter a growth step of
 * a crop inside a colony claim is vetoed with the chance {@code effects.winterCropSkipChance} (0.5: half of them).
 * Crops outside every claim are never touched, and with the switch off nothing here does anything.
 *
 * <p>Known limit (design 8 [V], proven by the cseffects probe): the event is fired by vanilla's crop blocks, which
 * includes the wheat, carrots and potatoes a MineColonies Farmer plants. MineColonies' own crop blocks
 * ({@code MinecoloniesCropBlock}, the cabbages, tomatoes and the like) grow through their own code, which does not
 * fire the event, so this switch cannot slow them down.</p>
 */
public final class CropEffect {

    /** The random source; tests may replace it with a seeded one ({@link #setRandom}). */
    private static RandomSource random;

    private CropEffect() {
    }

    /** Replace the random source (tests); {@code null} puts the level's own back. */
    public static void setRandom(final RandomSource source) {
        random = source;
    }

    /** Whether a growth step of a crop at this place is skipped now: the switch, the season and the claim all agree, and the dice. */
    public static boolean skips(final ServerLevel level, final BlockPos pos) {
        if (!SeasonsConfig.winterCropSlowdown()) {
            return false;
        }
        final CalendarManager.Snapshot snapshot = CalendarManager.current();
        if (snapshot == null || snapshot.season().season() != Season.WINTER) {
            return false;
        }
        final double chance = SeasonsConfig.winterCropSkipChance();
        if (chance <= 0 || !IColonyManager.getInstance().isCoordinateInAnyColony(level, pos)) {
            return false;
        }
        final RandomSource dice = random != null ? random : level.getRandom();
        return chance >= 1.0 || dice.nextDouble() < chance;
    }

    static void onGrow(final CropGrowEvent.Pre event) {
        if (event.getLevel() instanceof ServerLevel level && skips(level, event.getPos())) {
            event.setResult(CropGrowEvent.Pre.Result.DO_NOT_GROW);
        }
    }
}
