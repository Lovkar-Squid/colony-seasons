package me.lovkar.seasons.colony;

import com.minecolonies.api.colony.buildings.registry.BuildingEntry;

/**
 * The Town Square's building modules (0.2.0). As for the Workshop's ({@link DecoratorModules}), other code finds a
 * module by the identity of its producer: {@code building.getModule(SquareModules.DECORATOR)}, never
 * {@code getModuleMatching}. Producer keys are global in MineColonies, so the square's start with
 * {@code colony_seasons_}; a key names the module's saved compound and never changes. The settings and info pages come
 * in later chunks (Q3, Q5).
 */
public final class SquareModules {

    /**
     * The square's Decorator: one colony Decorator assigned here while he stays employed at his Workshop, by the
     * window's hire (MineColonies' HireFireMessage) or by auto-hire. Its view is reached only through this lambda, so a
     * dedicated server never loads a window class for it.
     */
    public static final BuildingEntry.ModuleProducer<SquareAssignmentModule, SquareAssignmentModuleView> DECORATOR =
            new BuildingEntry.ModuleProducer<>("colony_seasons_square_decorator", SquareAssignmentModule::new,
                    () -> SquareAssignmentModuleView::new);

    private SquareModules() {
    }
}
