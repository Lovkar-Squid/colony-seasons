package me.lovkar.seasons.colony;

import me.lovkar.seasons.decor.SquareSets;

/**
 * The lang keys the Town Square's pages (and the Workshop's Calendar line about the square) show, as literals so
 * tools/check_lang.py sees them. The server sends ids and numbers; the client turns them into these keys, so each
 * player reads the page in their own language.
 */
public final class SquareTexts {

    private SquareTexts() {
    }

    /** The one line that says what state a square's set is in. */
    public static String stateKey(final SquareStatus.State state) {
        return switch (state) {
            case NOT_BUILT -> "com.colony_seasons.gui.square.state.not_built";
            case NOT_MAIN -> "com.colony_seasons.gui.square.state.not_main";
            case BEING_BUILT -> "com.colony_seasons.gui.square.state.being_built";
            case COMING_DOWN -> "com.colony_seasons.gui.square.state.coming_down";
            case NO_SET -> "com.colony_seasons.gui.square.state.no_set";
            case NO_ROOM -> "com.colony_seasons.gui.square.state.no_room";
            case UP -> "com.colony_seasons.gui.square.state.up";
            case NO_DECORATOR -> "com.colony_seasons.gui.square.state.no_decorator";
            case WAITING_MATERIALS -> "com.colony_seasons.gui.square.state.waiting_materials";
            case GOING_UP -> "com.colony_seasons.gui.square.state.going_up";
        };
    }

    /** The short state the Calendar's line ends with ("Square: level 1, Winter Festival, going up."). */
    public static String shortStateKey(final SquareStatus.State state) {
        return switch (state) {
            case NOT_BUILT -> "com.colony_seasons.gui.square.short.not_built";
            case NOT_MAIN -> "com.colony_seasons.gui.square.short.not_main";
            case BEING_BUILT -> "com.colony_seasons.gui.square.short.being_built";
            case COMING_DOWN -> "com.colony_seasons.gui.square.short.coming_down";
            case NO_SET -> "com.colony_seasons.gui.square.short.no_set";
            case NO_ROOM -> "com.colony_seasons.gui.square.short.no_room";
            case UP -> "com.colony_seasons.gui.square.short.up";
            case NO_DECORATOR -> "com.colony_seasons.gui.square.short.no_decorator";
            case WAITING_MATERIALS -> "com.colony_seasons.gui.square.short.waiting_materials";
            case GOING_UP -> "com.colony_seasons.gui.square.short.going_up";
        };
    }

    /** A slot tag of the square's blueprint as the thing it holds ("%1$s lamp spots"); an unknown tag is shown as it is. */
    public static String slotKey(final String tag) {
        return switch (tag) {
            case "tree" -> "com.colony_seasons.gui.square.slot.tree";
            case "gift" -> "com.colony_seasons.gui.square.slot.gift";
            case "launch" -> "com.colony_seasons.gui.square.slot.launch";
            case "gather" -> "com.colony_seasons.gui.square.slot.gather";
            case "sit" -> "com.colony_seasons.gui.square.slot.sit";
            case "lamp" -> "com.colony_seasons.gui.square.slot.lamp";
            case "bed" -> "com.colony_seasons.gui.square.slot.bed";
            case "stall" -> "com.colony_seasons.gui.square.slot.stall";
            default -> tag;
        };
    }

    /** The lang key of a set's name; an id of a set this version does not know is shown as it is. */
    public static String setKey(final String id) {
        for (final SquareSets.Set set : SquareSets.ALL) {
            if (set.id().equals(id)) {
                return set.name();
            }
        }
        return id;
    }
}
