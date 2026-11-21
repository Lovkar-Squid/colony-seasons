package me.lovkar.seasons.client;

import com.ldtteam.blockui.Pane;
import com.ldtteam.blockui.controls.ButtonImage;
import com.ldtteam.blockui.controls.Text;
import com.ldtteam.blockui.views.BOWindow;
import com.ldtteam.blockui.views.ScrollingList;
import com.minecolonies.core.client.gui.AbstractModuleWindow;
import com.minecolonies.core.client.gui.WindowInfo;
import me.lovkar.seasons.ColonySeasons;
import me.lovkar.seasons.calendar.Festival;
import me.lovkar.seasons.calendar.Season;
import me.lovkar.seasons.colony.DecoratorInfoModule;
import me.lovkar.seasons.colony.DecoratorInfoModuleView;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The Decorator's page of the hut window (chunk S8): a scrolling list of short lines that the server's packet fills
 * ({@link DecoratorInfoModule}): the calendar, the festival, what he is doing, the decorations up, the stock against the
 * cap, what the next kit lacks and what the effect switches do. Below the list, outside it and always in view, the
 * Discord / Support line with the two links (S10d; text only, it cannot be clicked; S10h moved it out of the list,
 * where at the default GUI scale it sat half cut on the list's bottom edge until the player scrolled). All text goes
 * through lang keys, so it follows the player's language; the server sends keys, ids and numbers only.
 *
 * <p>The page follows the server while it is open: when the view's info changes (a new packet), the rows are made
 * again (S9, the S8 hand-off). Each row of the layout holds one visual line: a line wider than a row is cut by the
 * client's font, at a word, into as many rows as it needs, so nothing is cut off; if that fails, the line goes in as it
 * is. The server sends the page again when anything on it changes ({@link DecoratorInfoModule#onColonyTick}).</p>
 */
public class DecoratorInfoWindow extends AbstractModuleWindow<DecoratorInfoModuleView> {

    private static final ResourceLocation LAYOUT = ResourceLocation.fromNamespaceAndPath(ColonySeasons.MODID, "gui/decorator_info.xml");

    /**
     * A row's text width in font units. The list is 168 px wide, but BlockUI's scrolling list keeps 8 px of it for the
     * scrollbar, so a row shows 160 px; the layout's text pane is 156 px at x 2 with text scale 0.8, which is 195 font
     * units, and a row is filled to a little less so the last letter is never cut (tools/check_windows.py keeps the
     * three in step). The pane does not wrap: these rows are already one visual line each.
     */
    static final int ROW_WIDTH = 192;
    /** The Discord / Support line (approved words, tools/check_links.py), shown in the layout's "links" pane under the list. */
    static final String LINKS_KEY = "com.colony_seasons.gui.info.links";

    private List<Component> rows;
    private DecoratorInfoModule.Info shown;
    private ScrollingList list;

    /** The window for a view, typed as BlockUI's base window (see {@link DecoratorInfoModuleView#getWindow}). */
    public static BOWindow create(final DecoratorInfoModuleView view) {
        return new DecoratorInfoWindow(view);
    }

    public DecoratorInfoWindow(final DecoratorInfoModuleView view) {
        super(view, LAYOUT);
        shown = view.info();
        rows = rows(shown);

        // the red "?" MineColonies puts on every hut window, here on the page as well
        final ButtonImage help = findPaneOfTypeByID("info", ButtonImage.class);
        if (help != null) {
            help.setHandler(clicked -> new WindowInfo(moduleView.getBuildingView()).open());
        }

        // the owner's decision of 20 Nov 2026: a Discord / Support line with both links; its own pane under the list (S10h)
        final Text links = findPaneOfTypeByID("links", Text.class);
        if (links != null) {
            links.setText(Component.translatable(LINKS_KEY));
        }

        list = findPaneOfTypeByID("lines", ScrollingList.class);
        if (list != null) {
            list.setDataProvider(new ScrollingList.DataProvider() {
                @Override
                public int getElementCount() {
                    return rows.size();
                }

                @Override
                public void updateElement(final int index, final Pane row) {
                    final Text text = row.findPaneOfTypeByID("line", Text.class);
                    if (text != null && index < rows.size()) {
                        text.setText(rows.get(index));
                    }
                }
            });
        }
    }

    /** Every client tick: when the server sent a new state while the page is open, the rows are made again. */
    @Override
    public void onUpdate() {
        super.onUpdate();
        final DecoratorInfoModule.Info now = moduleView.info();
        if (!Objects.equals(now, shown)) {
            shown = now;
            rows = rows(now);
            if (list != null) {
                list.refreshElementPanes();
            }
        }
    }

    /** The page's rows: every line of {@link #lines}, cut into rows of one visual line each. */
    static List<Component> rows(final DecoratorInfoModule.Info info) {
        final List<Component> out = new ArrayList<>();
        for (final Component line : lines(info)) {
            out.addAll(wrap(line));
        }
        return out;
    }

    /** One line as rows no wider than {@link #ROW_WIDTH}, by the client's font; on any failure the line as it is. */
    static List<Component> wrap(final Component line) {
        try {
            final List<FormattedText> parts = Minecraft.getInstance().font.getSplitter().splitLines(line, ROW_WIDTH, Style.EMPTY);
            if (parts.isEmpty()) {
                return List.of(line);
            }
            final List<Component> out = new ArrayList<>();
            for (final FormattedText part : parts) {
                final MutableComponent row = Component.empty();
                part.visit((style, text) -> {
                    row.append(Component.literal(text).withStyle(style));
                    return Optional.empty();
                }, Style.EMPTY);
                out.add(row);
            }
            return out;
        } catch (final RuntimeException unwrapped) {
            return List.of(line);
        }
    }

    /** Every line of the page, in order. */
    static List<Component> lines(final DecoratorInfoModule.Info info) {
        final List<Component> out = new ArrayList<>();
        final Season season = Season.byOrdinal(info.season());
        out.add(season == null
                ? Component.translatable("com.colony_seasons.gui.info.no_calendar").withStyle(ChatFormatting.GRAY)
                : Component.translatable("com.colony_seasons.gui.info.today", Component.translatable(season.translationKey()),
                info.dayOfYear() + 1, info.yearLength()));

        if (info.festival().isEmpty()) {
            out.add(Component.translatable("com.colony_seasons.gui.info.festival.none"));
        } else {
            final Component name = Component.translatable(Festival.nameKey(info.festival()));
            if (info.daysUntilNext() >= 0) {
                out.add(Component.translatable("com.colony_seasons.gui.info.festival.next", name, info.daysUntilNext()));
            } else {
                out.add(Component.translatable(info.festivalDays() ? "com.colony_seasons.gui.info.festival.days"
                        : "com.colony_seasons.gui.info.festival.decor", name).withStyle(ChatFormatting.DARK_GREEN));
            }
            final String[] decor = info.decorWindow().split(" ");
            final String[] days = info.daysWindow().split(" ");
            if (decor.length == 2 && days.length == 2) {
                out.add(Component.translatable("com.colony_seasons.gui.info.windows", decor[0], decor[1], days[0], days[1]));
            }
        }

        out.add(Component.translatable("com.colony_seasons.gui.info.now", status(info)));
        if (!info.decorateOn()) {
            out.add(Component.translatable("com.colony_seasons.gui.info.decorate_off").withStyle(ChatFormatting.DARK_RED));
        }
        out.add(Component.translatable("com.colony_seasons.gui.info.progress", info.decorationsUp(), info.buildingsDone()));
        out.add(Component.translatable("com.colony_seasons.gui.info.today_quota", info.usedToday(), info.quota()));

        if (info.stock().isEmpty()) {
            out.add(Component.translatable("com.colony_seasons.gui.info.stock.none"));
        } else {
            out.add(Component.translatable("com.colony_seasons.gui.info.stock.head", info.stockCap()));
            for (final DecoratorInfoModule.StockRow row : info.stock()) {
                out.add(Component.translatable("com.colony_seasons.gui.info.stock.row", itemName(row.item()), row.have(), info.stockCap()));
            }
        }

        if (!info.needsMaterials()) {
            out.add(Component.translatable("com.colony_seasons.gui.info.missing.free"));
        } else if (info.nextKit().isEmpty()) {
            out.add(Component.translatable("com.colony_seasons.gui.info.missing.full"));
        } else if (info.missing().isEmpty()) {
            out.add(Component.translatable("com.colony_seasons.gui.info.missing.ready", itemName(info.nextKit())));
        } else {
            MutableComponent lacks = Component.empty();
            for (int i = 0; i < info.missing().size(); i++) {
                final DecoratorInfoModule.MissingRow row = info.missing().get(i);
                lacks = lacks.append(Component.translatable("com.colony_seasons.gui.info.missing.item", row.count(), ingredientName(row.ingredient())));
                if (i + 1 < info.missing().size()) {
                    lacks = lacks.append(", ");
                }
            }
            out.add(Component.translatable("com.colony_seasons.gui.info.missing", itemName(info.nextKit()), lacks).withStyle(ChatFormatting.GOLD));
        }

        out.add(Component.translatable("com.colony_seasons.gui.info.effects.head"));
        out.add(info.happinessOn()
                ? Component.translatable("com.colony_seasons.gui.info.effects.happiness.on", String.format(Locale.ROOT, "%.1f", info.happinessBonus()))
                : Component.translatable("com.colony_seasons.gui.info.effects.happiness.off"));
        out.add(info.cropsOn()
                ? Component.translatable("com.colony_seasons.gui.info.effects.crops.on", Math.round(info.cropSkip() * 100))
                : Component.translatable("com.colony_seasons.gui.info.effects.crops.off"));
        out.add(info.foodOn()
                ? Component.translatable("com.colony_seasons.gui.info.effects.food.on", String.format(Locale.ROOT, "%.2f", info.foodFactor()))
                : Component.translatable("com.colony_seasons.gui.info.effects.food.off"));
        return out;
    }

    /** The status line: the server's key and arguments; an argument that is an item id shows the item's name. */
    private static Component status(final DecoratorInfoModule.Info info) {
        final Object[] args = new Object[info.statusArgs().size()];
        for (int i = 0; i < args.length; i++) {
            args[i] = argument(info.statusArgs().get(i));
        }
        return Component.translatable(info.statusKey(), args);
    }

    private static Object argument(final String text) {
        if (text.indexOf(':') > 0) {
            final ResourceLocation id = ResourceLocation.tryParse(text);
            final Optional<Item> item = id == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(id);
            if (item.isPresent()) {
                return item.get().getDescription();
            }
        }
        return text;
    }

    /** An item id as the item's name (the id itself when this client does not know the item). */
    static Component itemName(final String id) {
        final ResourceLocation location = ResourceLocation.tryParse(id);
        final Optional<Item> item = location == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(location);
        return item.isPresent() ? item.get().getDescription() : Component.literal(id);
    }

    /** "item:ns:name" as the item's name, "tag:ns:name" as the tag's name (vanilla's translation, else the id). */
    static Component ingredientName(final String key) {
        if (key.startsWith("tag:")) {
            final ResourceLocation tag = ResourceLocation.tryParse(key.substring(4));
            if (tag == null) {
                return Component.literal(key);
            }
            final String translation = "tag.item." + tag.getNamespace() + "." + tag.getPath().replace('/', '.');
            return Component.translatableWithFallback(translation, tag.toString());
        }
        return itemName(key.startsWith("item:") ? key.substring(5) : key);
    }
}
