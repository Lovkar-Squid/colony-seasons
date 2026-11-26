package me.lovkar.seasons.client;

import com.ldtteam.blockui.views.BOWindow;
import com.minecolonies.core.client.gui.modules.building.SpecialAssignmentModuleWindow;
import me.lovkar.seasons.ColonySeasons;
import me.lovkar.seasons.colony.SquareAssignmentModuleView;
import net.minecraft.resources.ResourceLocation;

/**
 * The Town Square's Decorator page (0.2.0, chunk Q1): MineColonies' own special assignment window (the Quarry's), on our
 * layout. It lists the square's Decorator; "manage" opens MineColonies' hire window for this square, which offers only
 * Decorators on no other square and adds the chosen one without moving him from his Workshop; "recall" calls him to
 * the square's hut block.
 */
public class SquareAssignmentWindow extends SpecialAssignmentModuleWindow {

    private static final ResourceLocation LAYOUT = ResourceLocation.fromNamespaceAndPath(ColonySeasons.MODID, "gui/square_assignment.xml");

    /** The window for a view, typed as BlockUI's base window (see {@link SquareAssignmentModuleView#getWindow}). */
    public static BOWindow create(final SquareAssignmentModuleView view) {
        return new SquareAssignmentWindow(view);
    }

    public SquareAssignmentWindow(final SquareAssignmentModuleView view) {
        super(view, LAYOUT);
    }
}
