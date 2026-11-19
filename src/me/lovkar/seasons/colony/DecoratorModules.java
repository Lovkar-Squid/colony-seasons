package me.lovkar.seasons.colony;

import com.minecolonies.api.colony.buildings.modules.IBuildingModuleView;
import com.minecolonies.api.colony.buildings.registry.BuildingEntry;
import com.minecolonies.api.entity.citizen.Skill;
import com.minecolonies.core.colony.buildings.modules.SettingsModule;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import com.minecolonies.core.colony.buildings.modules.settings.BoolSetting;
import com.minecolonies.core.colony.buildings.moduleviews.SettingsModuleView;
import com.minecolonies.core.colony.buildings.moduleviews.WorkerBuildingModuleView;

/**
 * The Decorator's building modules, in the order the building lists them. MineColonies finds a module by the
 * identity of its producer, so other code asks {@code building.getModule(DecoratorModules.WORK)}, never
 * {@code getModuleMatching} (which throws when nothing matches).
 */
public final class DecoratorModules {

    /** One Decorator a hut. A plain worker module: he lives in a house like any day worker and needs no bed here. */
    public static final BuildingEntry.ModuleProducer<WorkerBuildingModule, WorkerBuildingModuleView> WORK =
            new BuildingEntry.ModuleProducer<>("decorator_work",
                    () -> new WorkerBuildingModule(DecoratorSetup.JOB.get(), Skill.Creativity, Skill.Dexterity, false, building -> 1),
                    () -> WorkerBuildingModuleView::new);

    /** The hut's switches: "prepare all year" (on by default). */
    public static final BuildingEntry.ModuleProducer<SettingsModule, SettingsModuleView> SETTINGS =
            new BuildingEntry.ModuleProducer<>("decorator_settings",
                    () -> (SettingsModule) new SettingsModule().with(BuildingDecorator.PREPARE_ALL_YEAR, new BoolSetting(true)),
                    () -> SettingsModuleView::new);

    /** What the hut keeps from the couriers (server only, no window tab). */
    public static final BuildingEntry.ModuleProducer<DecoratorStockModule, IBuildingModuleView> STOCK =
            new BuildingEntry.ModuleProducer<>("decorator_stock", DecoratorStockModule::new, null);

    private DecoratorModules() {
    }
}
