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
import me.lovkar.seasons.colony.SquareInfoModule;
import me.lovkar.seasons.colony.SquareInfoModuleView;
import me.lovkar.seasons.colony.SquareStatus;
import me.lovkar.seasons.colony.SquareTexts;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;

import java.time.Month;
import java.time.MonthDay;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The Town Square's info page of the hut window (0.2.0, chunk Q5, docs/town-square-design.md 4.4): a scrolling list of
 * short lines that the server's packet fills ({@link SquareInfoModule}): the level, the order, the set it wants and its
 * state, the next change of set, the festival, the Decorator, what is missing and what the next level adds. It is the
 * Calendar page's shape ({@link DecoratorInfoWindow}): the page follows the server while it is open, a line wider than a
 * row is cut by the client's font into as many rows as it needs, and all text goes through lang keys. The server sends
 * ids and numbers only.
 */
public class TownSquareInfoWindow extends AbstractModuleWindow<SquareInfoModuleView> {

    private static final ResourceLocation LAYOUT = ResourceLocation.fromNamespaceAndPath(ColonySeasons.MODID, "gui/town_square_info.xml");

    /** A row's text width in font units; the layout's rows are the Calendar's (tools/check_windows.py keeps the two in step). */
    static final int ROW_WIDTH = 192;

    private List<Component> rows;
    private SquareInfoModule.Info shown;
    private ScrollingList list;

    /** The window for a view, typed as BlockUI's base window (see {@link SquareInfoModuleView#getWindow}). */
    public static BOWindow create(final SquareInfoModuleView view) {
        return new TownSquareInfoWindow(view);
    }

    public TownSquareInfoWindow(final SquareInfoModuleView view) {
        super(view, LAYOUT);
        shown = view.info();
        rows = rows(shown);

        // the red "?" MineColonies puts on every hut window, here on the page as well
        final ButtonImage help = findPaneOfTypeByID("info", ButtonImage.class);
        if (help != null) {
            help.setHandler(clicked -> new WindowInfo(moduleView.getBuildingView()).open());
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
        final SquareInfoModule.Info now = moduleView.info();
        if (!Objects.equals(now, shown)) {
            shown = now;
            rows = rows(now);
            if (list != null) {
                list.refreshElementPanes();
            }
        }
    }

    /** The page's rows: every line of {@link #lines}, cut into rows of one visual line each. */
    static List<Component> rows(final SquareInfoModule.Info info) {
        final List<Component> out = new ArrayList<>();
        for (final Component line : lines(info)) {
            out.addAll(DecoratorInfoWindow.wrap(line, ROW_WIDTH));
        }
        return out;
    }

    /** Every line of the page, in order. */
    static List<Component> lines(final SquareInfoModule.Info info) {
        final List<Component> out = new ArrayList<>();
        final SquareStatus.State state = SquareStatus.State.byOrdinal(info.state());
        final SquareStatus.Standing standing = SquareStatus.Standing.byOrdinal(info.standing());

        out.add(info.level() < 1
                ? Component.translatable("com.colony_seasons.gui.square.info.not_built")
                : Component.translatable("com.colony_seasons.gui.square.info.level", info.level(), info.maxLevel()));
        if (standing == SquareStatus.Standing.OTHER) {
            out.add(Component.translatable("com.colony_seasons.gui.square.info.other_main", info.mainLevel(),
                    info.mainPos().toShortString()).withStyle(ChatFormatting.DARK_RED));
        }
        out.add(Component.translatable("com.colony_seasons.gui.square.info.order", Component.translatable(info.order())));
        out.add(info.wantedSet().isEmpty()
                ? Component.translatable("com.colony_seasons.gui.square.info.set_none")
                : Component.translatable("com.colony_seasons.gui.square.info.set", Component.translatable(SquareTexts.setKey(info.wantedSet()))));
        if (!info.upSet().isEmpty()) {
            out.add(Component.translatable("com.colony_seasons.gui.square.info.on_square", Component.translatable(SquareTexts.setKey(info.upSet()))));
        }
        out.add(Component.translatable(SquareTexts.stateKey(state)).withStyle(stateColour(state)));

        switch (info.changeKind()) {
            case SquareInfoModule.CHANGE_FROM -> out.add(Component.translatable("com.colony_seasons.gui.square.info.change_from",
                    Component.translatable(SquareTexts.setKey(info.changeSet())), date(info.changeDate())));
            case SquareInfoModule.CHANGE_UNTIL -> out.add(Component.translatable("com.colony_seasons.gui.square.info.change_until",
                    Component.translatable(SquareTexts.setKey(info.changeSet())), date(info.changeDate())));
            default -> out.add(Component.translatable("com.colony_seasons.gui.square.info.change_none"));
        }

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
        }

        if (info.decorator().isEmpty()) {
            out.add(Component.translatable("com.colony_seasons.gui.square.info.no_decorator").withStyle(ChatFormatting.DARK_RED));
        } else {
            out.add(Component.translatable(info.decoratorHere() ? "com.colony_seasons.gui.square.info.decorator"
                    : "com.colony_seasons.gui.square.info.decorator_elsewhere", info.decorator()));
        }

        // the festival market (Q10): only while Trade Post is in play (the server sends no state then)
        switch (info.marketState()) {
            case SquareInfoModule.MARKET_OPEN -> out.add(Component.translatable("com.colony_seasons.gui.square.info.market.open",
                    String.format(Locale.ROOT, "%,d", info.marketPaid()), String.format(Locale.ROOT, "%,d", info.marketCap())));
            case SquareInfoModule.MARKET_NO_GOODS -> out.add(Component.translatable("com.colony_seasons.gui.square.info.market.nostock"));
            case SquareInfoModule.MARKET_CLOSED -> out.add(Component.translatable("com.colony_seasons.gui.square.info.market.closed"));
            default -> {
            }
        }

        if (info.missing().isEmpty()) {
            out.add(Component.translatable("com.colony_seasons.gui.square.info.missing_none"));
        } else {
            MutableComponent lacks = Component.empty();
            for (int i = 0; i < info.missing().size(); i++) {
                final SquareInfoModule.MissingRow row = info.missing().get(i);
                lacks = lacks.append(Component.translatable("com.colony_seasons.gui.info.missing.item", row.count(), DecoratorInfoWindow.itemName(row.item())));
                if (i + 1 < info.missing().size()) {
                    lacks = lacks.append(", ");
                }
            }
            out.add(Component.translatable("com.colony_seasons.gui.square.info.missing", lacks).withStyle(ChatFormatting.GOLD));
        }

        if (info.nextLevel() <= 0) {
            out.add(Component.translatable("com.colony_seasons.gui.square.info.highest"));
        } else {
            MutableComponent adds = Component.empty();
            for (int i = 0; i < info.added().size(); i++) {
                final SquareInfoModule.AddedRow row = info.added().get(i);
                adds = adds.append(Component.translatable(SquareTexts.slotKey(row.tag()), row.count()));
                if (i + 1 < info.added().size()) {
                    adds = adds.append(", ");
                }
            }
            out.add(Component.translatable("com.colony_seasons.gui.square.info.next_level", info.nextLevel(), adds));
        }
        return out;
    }

    private static ChatFormatting stateColour(final SquareStatus.State state) {
        return switch (state) {
            case UP -> ChatFormatting.DARK_GREEN;
            case NO_ROOM, NO_DECORATOR, NOT_MAIN, NOT_BUILT -> ChatFormatting.DARK_RED;
            case WAITING_MATERIALS, COMING_DOWN, BEING_BUILT -> ChatFormatting.GOLD;
            default -> ChatFormatting.BLACK;
        };
    }

    /** "MM-DD" as "1 Dec" in the player's language (the date as it is when the language or the text is not understood). */
    static String date(final String monthDay) {
        try {
            final MonthDay parsed = Festival.parse(monthDay);
            Locale locale = Locale.ENGLISH;
            try {
                locale = Locale.forLanguageTag(Minecraft.getInstance().getLanguageManager().getSelected().replace('_', '-'));
            } catch (final RuntimeException unknown) {
                // no client language yet: English
            }
            return parsed.getDayOfMonth() + " " + Month.of(parsed.getMonthValue()).getDisplayName(TextStyle.SHORT, locale);
        } catch (final RuntimeException unreadable) {
            return monthDay;
        }
    }
}
