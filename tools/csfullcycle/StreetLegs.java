package me.lovkar.seasons.csfullcycle;

import net.minecraft.core.BlockPos;

import java.time.LocalDate;
import java.util.List;

/** Q7 mode wand: marked streets through the cycle on a Town Square. */
final class StreetLegs {
    private final CsFullCycle p;
    private final SquareCycle sc;

    StreetLegs(final CsFullCycle probe, final SquareCycle square) {
        this.p = probe;
        this.sc = square;
    }

    List<BlockPos> corners() {
        return List.of();
    }

    void plan() {
        p.planDates(CsFullCycle.FIRST, CsFullCycle.LAST, false);
    }

    void daily(final LocalDate d) {
    }
}
