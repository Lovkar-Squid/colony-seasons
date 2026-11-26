package me.lovkar.seasons.colony;

import com.minecolonies.api.colony.buildings.registry.BuildingEntry;
import com.minecolonies.core.colony.buildings.modules.settings.StringSetting;
import com.minecolonies.core.colony.buildings.moduleviews.SettingsModuleView;
import me.lovkar.seasons.decor.SquareSets;

/**
 * The Town Square's building modules (0.2.0). As for the Workshop's ({@link DecoratorModules}), other code finds a
 * module by the identity of its producer: {@code building.getModule(SquareModules.DECORATOR)}, never
 * {@code getModuleMatching}. Producer keys are global in MineColonies, so the square's start with
 * {@code colony_seasons_}; a key names the module's saved compound and never changes. The settings page with the order
 * came in Q3; the info page in Q5.
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

    /**
     * The square's settings (Q3, docs/town-square-design.md 4.4): the order, one cycling button with Follow the calendar
     * (the default), Always Winter Festival and Bare. MineColonies' own settings page shows it.
     */
    public static final BuildingEntry.ModuleProducer<SquareSettingsModule, SettingsModuleView> SETTINGS =
            new BuildingEntry.ModuleProducer<>("colony_seasons_square_settings", SquareModules::settingsModule, () -> SettingsModuleView::new);

    /**
     * The square's info page (Q5, docs/town-square-design.md 4.4): level, order, the set and its state, the next change,
     * the festival, the Decorator, what is missing and what the next level adds. Server only reads; the view and its
     * window are reached through the lambda, so a dedicated server never loads a window class.
     */
    public static final BuildingEntry.ModuleProducer<SquareInfoModule, SquareInfoModuleView> INFO =
            new BuildingEntry.ModuleProducer<>("colony_seasons_square_info", SquareInfoModule::new, () -> SquareInfoModuleView::new);

    private SquareModules() {
    }

    /** A new settings module of the square, its order on the default (what the producer makes; the probe reads saves with it). */
    public static SquareSettingsModule settingsModule() {
        return (SquareSettingsModule) new SquareSettingsModule().with(BuildingTownSquare.ORDER, new StringSetting(SquareSets.orderKeys(), 0));
    }
}
