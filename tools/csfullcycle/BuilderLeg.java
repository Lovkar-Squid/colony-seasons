package me.lovkar.seasons.csfullcycle;

/** Q7 mode builder-over-tree: MineColonies' Builder upgrades the square while the tree stands. */
final class BuilderLeg {
    private final CsFullCycle p;
    private final SquareCycle sc;

    BuilderLeg(final CsFullCycle probe, final SquareCycle square) {
        this.p = probe;
        this.sc = square;
    }

    void plan() {
        p.check("known mode", false, "builder-over-tree not written yet");
        p.finish();
    }
}
