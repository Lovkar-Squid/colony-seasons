package me.lovkar.seasons.client;

import com.ldtteam.blockui.PaneBuilders;
import com.ldtteam.blockui.controls.Button;
import com.ldtteam.blockui.controls.ItemIcon;
import com.ldtteam.blockui.controls.Text;
import com.ldtteam.blockui.views.BOWindow;
import com.minecolonies.core.client.gui.AbstractModuleWindow;
import me.lovkar.seasons.ColonySeasons;
import me.lovkar.seasons.colony.BuildingDecorator;
import me.lovkar.seasons.colony.DecoratorStorageModuleView;
import me.lovkar.seasons.network.UpgradeStoragePayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The Workshop's "Upgrade Storage" tab (S10e), MineColonies' Warehouse storage window without its sort button: one row
 * for the price (an emerald block: the player's count against the one needed), the steps paid ("1 of 3") and the "↥"
 * button that sends {@link UpgradeStoragePayload}. As in the Warehouse window, the button is offered only at the
 * upgrade level with a step left; below that level it reads a dark red "X" with a tooltip naming the level. The
 * client greys the button when a player outside creative has no emerald block; the server decides either way.
 * After a click the window counts the step at once and locks the button until it is opened again, as MineColonies does.
 */
public class DecoratorStorageWindow extends AbstractModuleWindow<DecoratorStorageModuleView> {

    private static final ResourceLocation LAYOUT = ResourceLocation.fromNamespaceAndPath(ColonySeasons.MODID, "gui/decorator_storage.xml");
    private static final int RED = 0xFF0000;
    private static final int DARK_GREEN = 0x006400;
    private static final int BLACK = 0x000000;
    /** The price of one step, as in MineColonies' Warehouse window. */
    private static final int PRICE = 1;

    private boolean lockUpgrade;

    /** The window for a view, typed as BlockUI's base window (see {@link DecoratorStorageModuleView#getWindow}). */
    public static BOWindow create(final DecoratorStorageModuleView view) {
        return new DecoratorStorageWindow(view);
    }

    public DecoratorStorageWindow(final DecoratorStorageModuleView view) {
        super(view, LAYOUT);
        registerButton("resourceAdd", this::upgrade);
    }

    @Override
    public void onOpened() {
        super.onOpened();
        updateResourcePane();
    }

    private void upgrade() {
        PacketDistributor.sendToServer(new UpgradeStoragePayload(buildingView.getID()));
        moduleView.stepTaken();
        lockUpgrade = true;
        updateResourcePane();
    }

    /** Emerald blocks the player holds (the price counts as held in creative, as in MineColonies). */
    private static int playerHas(final LocalPlayer player) {
        if (player == null) {
            return 0;
        }
        if (player.isCreative()) {
            return PRICE;
        }
        int total = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            final ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(Items.EMERALD_BLOCK)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private void updateResourcePane() {
        final int step = moduleView.step();
        final int level = buildingView.getBuildingLevel();
        final boolean atLevel = level >= BuildingDecorator.STORAGE_UPGRADE_LEVEL;
        final boolean offered = step < BuildingDecorator.MAX_STORAGE_STEP && atLevel && !lockUpgrade;
        final int have = playerHas(Minecraft.getInstance().player);

        final Text name = findPaneOfTypeByID("resourceName", Text.class);
        final Text missing = findPaneOfTypeByID("resourceMissing", Text.class);
        final Text neededText = findPaneOfTypeByID("resourceAvailableNeeded", Text.class);
        final Button button = findPaneOfTypeByID("resourceAdd", Button.class);
        final Text progress = findPaneOfTypeByID("upgradeProgress", Text.class);
        final ItemIcon icon = findPaneOfTypeByID("resourceIcon", ItemIcon.class);
        if (name == null || missing == null || neededText == null || button == null || progress == null) {
            return;
        }

        progress.setText(Component.translatable("com.colony_seasons.gui.storage.progress", step, BuildingDecorator.MAX_STORAGE_STEP));
        final int color;
        if (!offered) {
            button.disable();
            color = BLACK;
        } else if (have < PRICE) {
            button.disable();
            color = RED;
        } else {
            button.enable();
            color = DARK_GREEN;
        }
        name.setColors(color);
        missing.setColors(color);
        neededText.setColors(color);
        if (!atLevel) {
            name.hide();
            missing.hide();
            neededText.hide();
            button.setText(Component.literal("X").setStyle(Style.EMPTY.withColor(ChatFormatting.DARK_RED)));
            PaneBuilders.tooltipBuilder()
                    .append(Component.translatable("com.colony_seasons.gui.storage.disabled.1"))
                    .appendNL(Component.translatable("com.colony_seasons.gui.storage.disabled.2", BuildingDecorator.STORAGE_UPGRADE_LEVEL))
                    .hoverPane(button)
                    .build();
        }
        name.setText(Items.EMERALD_BLOCK.getDescription().copy());
        final int lacking = PRICE - Math.min(have, PRICE);
        if (offered && lacking > 0) {
            missing.setText(Component.literal("-" + lacking));
        } else {
            missing.clearText();
        }
        neededText.setText(Component.literal(Math.min(have, 99) + " / " + PRICE));
        if (icon != null && atLevel) {
            icon.setItem(new ItemStack(Items.EMERALD_BLOCK, 1));
        }
    }
}
