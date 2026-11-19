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

    private DecoratorTexts() {
    }
}
