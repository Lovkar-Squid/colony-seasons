package me.lovkar.seasons.decor;

/**
 * Which kinds of decoration the Decorator may put up, from the switches in his hut's settings (design 4, S8). The
 * planner leaves a switched-off kind out of the plan; what is already standing is never touched by a switch (only
 * taking down removes it). Switching the tree off stops a new tree from going up; a tree that stands stays until the
 * take-down, like everything else he placed.
 *
 * @param wreaths  the wreath above a door
 * @param garlands the garland beside it
 * @param lights   the lantern string or fairy lights above that
 * @param tree     the decorated tree on the festival square
 */
public record DecorKinds(boolean wreaths, boolean garlands, boolean lights, boolean tree) {

    /** Everything on: the default of the settings, and what a caller with no Decorator hut gets. */
    public static final DecorKinds ALL = new DecorKinds(true, true, true, true);
}
