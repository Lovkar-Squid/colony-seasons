package me.lovkar.seasons.colony;

/**
 * What the Workshop's "Upgrade Storage" tab shows, and its wait for the server after a click (S10h). Plain Java with no
 * client class, so a dedicated server (the csstorage probe) can drive it with the same numbers the window reads.
 *
 * <p>The in-game test of 21 Nov 2026 found the tab stale after a click: the count of emerald blocks stayed at the old
 * number and the button stayed dark until the window was opened again. MineColonies' Warehouse window has the same
 * shape (it counts the step at once, locks the button and draws once, before the server has taken the block) and is
 * redrawn only when it is opened. Here the window asks {@link #shown} every client tick and redraws when the answer
 * differs from what it drew, so the count follows the player's inventory as the server syncs it, and the step follows
 * the module view as MineColonies' building packet updates it in place.</p>
 *
 * <p>A click starts a wait: the tab shows one step more at once and offers no button. The wait ends when the server's
 * step (the view) differs from the step clicked on, which is the server's answer to a successful click, or after
 * {@link #ANSWER_TICKS} client ticks, which covers a refused click (the server changes nothing and says why in chat).
 * The view itself is never counted up on the client, so the server's value always wins and nothing is counted twice.</p>
 */
public final class StorageTabState {

    /** Emerald blocks one step costs, as in MineColonies' Warehouse window. */
    public static final int PRICE = 1;
    /**
     * Client ticks a click waits for the server's step before the button is offered again. MineColonies sends a dirty
     * building to its subscribers every 20 ticks, so a successful click is answered well within this; a refused one
     * never changes the step and ends the wait here.
     */
    public static final int ANSWER_TICKS = 60;

    /** One drawing of the tab: the step shown, whether the hut is at the upgrade level, whether the button is offered, the blocks held. */
    public record Shown(int step, boolean atLevel, boolean offered, int have) {

        /** The button can be clicked: offered and the price is held (always held in creative, see the window). */
        public boolean clickable() {
            return offered && have >= PRICE;
        }
    }

    private boolean waiting;
    private int clickedAt;
    private int waited;

    /** A click on the button while the server's step is {@code serverStep}. */
    public void clicked(final int serverStep) {
        waiting = true;
        clickedAt = serverStep;
        waited = 0;
    }

    /** One client tick with the server's step as the view has it now: ends the wait on the server's answer or after {@link #ANSWER_TICKS}. */
    public void tick(final int serverStep) {
        if (waiting && (serverStep != clickedAt || ++waited >= ANSWER_TICKS)) {
            waiting = false;
        }
    }

    /** True from a click until the server's answer (or the end of {@link #ANSWER_TICKS}). */
    public boolean waiting() {
        return waiting;
    }

    /**
     * What the tab shows now: the server's step (one more while a click waits, never past the last), the hut's level
     * against the upgrade level, the button offered only with a step left, at the level and with no click waiting.
     */
    public Shown shown(final int serverStep, final int buildingLevel, final int have) {
        final int step = waiting ? Math.min(clickedAt + 1, BuildingDecorator.MAX_STORAGE_STEP) : serverStep;
        final boolean atLevel = buildingLevel >= BuildingDecorator.STORAGE_UPGRADE_LEVEL;
        return new Shown(step, atLevel, atLevel && step < BuildingDecorator.MAX_STORAGE_STEP && !waiting, Math.max(0, have));
    }
}
