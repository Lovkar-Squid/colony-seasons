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

    /**
     * The hut's switches, all on by default: "prepare all year", "decorate", and which kinds he puts up (wreaths,
     * garlands, lights, the tree). Our settings go into MineColonies' module, before its NBT is read.
     */
    public static final BuildingEntry.ModuleProducer<SettingsModule, SettingsModuleView> SETTINGS =
            new BuildingEntry.ModuleProducer<>("decorator_settings",
                    () -> (SettingsModule) new SettingsModule()
                            .with(BuildingDecorator.PREPARE_ALL_YEAR, new BoolSetting(true))
                            .with(BuildingDecorator.DECORATE, new BoolSetting(true))
                            .with(BuildingDecorator.KIND_WREATHS, new BoolSetting(true))
                            .with(BuildingDecorator.KIND_GARLANDS, new BoolSetting(true))
                            .with(BuildingDecorator.KIND_LIGHTS, new BoolSetting(true))
                            .with(BuildingDecorator.KIND_TREE, new BoolSetting(true)),
                    () -> SettingsModuleView::new);

    /** The hut window's page for the calendar, the progress and what is missing (client view: {@link DecoratorInfoModuleView}). */
    public static final BuildingEntry.ModuleProducer<DecoratorInfoModule, DecoratorInfoModuleView> INFO =
            new BuildingEntry.ModuleProducer<>("decorator_info", DecoratorInfoModule::new, () -> DecoratorInfoModuleView::new);

    /** What the hut keeps from the couriers (server only, no window tab). */
    public static final BuildingEntry.ModuleProducer<DecoratorStockModule, IBuildingModuleView> STOCK =
            new BuildingEntry.ModuleProducer<>("decorator_stock", DecoratorStockModule::new, null);

    private DecoratorModules() {
    }
}
