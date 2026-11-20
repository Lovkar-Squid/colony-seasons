package me.lovkar.seasons.colony;

/**
 * The lang keys of what the Decorator says he is doing (his status line in the hut window). The server only sends a
 * key and its arguments, so each player reads it in their own language. An argument that is an item id is shown as
 * that item's name by the window; anything else is shown as it is.
 *
 * <p>The keys are written out in full so that tools/check_lang.py finds them.</p>
 */
public final class DecoratorTexts {

    public static final String NO_WORKER = "com.colony_seasons.status.no_worker";
    public static final String HUT_UNBUILT = "com.colony_seasons.status.hut_unbuilt";
    public static final String FREE = "com.colony_seasons.status.free";
    public static final String WINDOW_ONLY = "com.colony_seasons.status.window_only";
    public static final String NO_KITS = "com.colony_seasons.status.no_kits";
    public static final String STOCK_FULL = "com.colony_seasons.status.stock_full";
    public static final String ASSEMBLING = "com.colony_seasons.status.assembling";
    public static final String ASSEMBLED = "com.colony_seasons.status.assembled";
    public static final String NO_ROOM = "com.colony_seasons.status.no_room";
    public static final String WAITING_MATERIALS = "com.colony_seasons.status.waiting_materials";
    /** S10e: finished pieces asked for from the warehouse; arguments: how many, the item. */
    public static final String FETCHING = "com.colony_seasons.status.fetching";
    public static final String DECORATING_OFF = "com.colony_seasons.status.decorating_off";
    public static final String TENDING_AT = "com.colony_seasons.status.tending_at";
    public static final String DAY_LIMIT_DECORATING = "com.colony_seasons.status.day_limit_decorating";
    public static final String NOTHING_TO_DECORATE = "com.colony_seasons.status.nothing_to_decorate";
    public static final String ON_WAY_DECORATE = "com.colony_seasons.status.on_way_decorate";
    public static final String DECORATED_AT = "com.colony_seasons.status.decorated_at";
    public static final String TENDED_AT = "com.colony_seasons.status.tended_at";
    public static final String DOWN_DONE = "com.colony_seasons.status.down_done";
    public static final String DAY_LIMIT_DOWN = "com.colony_seasons.status.day_limit_down";
    public static final String WAITING_CHUNKS = "com.colony_seasons.status.waiting_chunks";
    public static final String ON_WAY_DOWN = "com.colony_seasons.status.on_way_down";
    public static final String TOOK_DOWN_AT = "com.colony_seasons.status.took_down_at";
    // the festival states (S7): Hosting at the tree, Launching at the square, the shows
    public static final String ON_WAY_GATHERING = "com.colony_seasons.status.on_way_gathering";
    public static final String HOSTING = "com.colony_seasons.status.hosting";
    public static final String NO_SQUARE_SHOW = "com.colony_seasons.status.no_square_show";
    public static final String ON_WAY_SHOW = "com.colony_seasons.status.on_way_show";
    public static final String SHOW_READY = "com.colony_seasons.status.show_ready";
    public static final String SHOW_READY_FREE = "com.colony_seasons.status.show_ready_free";
    public static final String LAUNCHED_DUSK = "com.colony_seasons.status.launched_dusk";
    public static final String LAUNCHED_SPARKLE = "com.colony_seasons.status.launched_sparkle";
    // the server launched a show from the square without him: the midnight show, or a dusk show he was not up for
    public static final String WENT_UP_DUSK = "com.colony_seasons.status.went_up_dusk";
    public static final String WENT_UP_SPARKLE = "com.colony_seasons.status.went_up_sparkle";
    public static final String WENT_UP_MIDNIGHT = "com.colony_seasons.status.went_up_midnight";
    public static final String NO_ROCKETS_DUSK = "com.colony_seasons.status.no_rockets_dusk";
    public static final String NO_ROCKETS_SPARKLE = "com.colony_seasons.status.no_rockets_sparkle";
    public static final String NO_ROCKETS_MIDNIGHT = "com.colony_seasons.status.no_rockets_midnight";

    private DecoratorTexts() {
    }
}
