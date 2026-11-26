package me.lovkar.seasons.decor;

import me.lovkar.seasons.ColonySeasons;
import me.lovkar.seasons.SeasonsConfig;
import me.lovkar.seasons.calendar.CalendarManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Town Square's sets and the order the player gives (0.2.0, chunk Q3, docs/town-square-design.md 4.2 to 4.4).
 *
 * <p>A set is what the Decorator puts on the square: a centrepiece at the {@code tree} tag (a piece per level, the
 * same files as the festival marker's tree for the Winter Festival) and, from the later chunks on, small pieces at
 * the other tags. Only the Winter Festival ships with the square; every other festival's set comes with that
 * festival's version ([B] owner decision 6). The seasonal touches are in the catalogue, without pieces until the
 * square's blueprints bring them (Q6).</p>
 *
 * <p>The order ({@link Order}, the square's settings page) with the owner's answers of 20 Nov 2026 [B]:</p>
 * <ul>
 *   <li>Follow the calendar (the default): in a festival's decoration window that festival's set, between the
 *       windows the seasonal touches.</li>
 *   <li>Always one chosen set: that set in every festival window, whichever festival it is, and the seasonal touches
 *       between them, as the full sets come only in festival windows.</li>
 *   <li>Bare: no set at all. Events follow the calendar whatever the order (Q4).</li>
 * </ul>
 * A stored order this version does not know (a later version's set, then a downgrade) reads as Follow the calendar,
 * with one log line ({@link Order#of}). The tree size follows the lower of the square's level and the Workshop's
 * ({@link #pieceLevel}). Pure but for that one log line.
 */
public final class SquareSets {

    /** One set: its id, its name (a lang key), the festivals it belongs to, its centrepiece files per level (none: empty). */
    public record Set(String id, String name, List<String> festivals, List<String> centrepieces, List<String> slots) {

        /** The centrepiece file for a level, clamped to 1..5 (the list holds one file per level), or {@code null}. */
        public String centrepiece(final int level) {
            if (centrepieces.isEmpty()) {
                return null;
            }
            return centrepieces.get(Math.max(1, Math.min(centrepieces.size(), level)) - 1);
        }

        /** True when this set has something to put up in this version. */
        public boolean shipped() {
            return !centrepieces.isEmpty();
        }
    }

    private static final String SMALL = "pieces/village_spruce_small.blueprint";

    /**
     * The Winter Festival (and the New Year inside its window): the village spruce in its four sizes, the grand tree at
     * level 5. The files and their levels are the 0.1.0 marker's, unchanged. Slots from section 4.2: the tree, the gift
     * pile, the crate, garlands and strings on the lamp posts (the pile and the crate stand in the piece itself in Q3).
     */
    public static final Set WINTER = new Set("winter_festival", "com.colony_seasons.square.set.winter_festival",
            List.of(SeasonsConfig.WINTER_FESTIVAL, SeasonsConfig.NEW_YEAR),
            List.of(SMALL, SMALL, "pieces/village_spruce_full.blueprint", "pieces/village_spruce_rich.blueprint",
                    "pieces/grand_tree.blueprint"),
            List.of("tree", "gift", "launch", "lamp"));

    /** Between the festivals: small touches in the planters by the in-game season; their pieces come with Q6. */
    public static final Set SEASONAL = new Set("seasonal", "com.colony_seasons.square.set.seasonal", List.of(), List.of(), List.of("bed"));

    /** Every set of this version, in the order the settings page lists the "Always" entries of those that ship. */
    public static final List<Set> ALL = List.of(WINTER, SEASONAL);

    /** What the player asks of the square. The value stored by MineColonies' settings button is the lang key. */
    public enum Order {
        FOLLOW("com.colony_seasons.square.order.follow", null),
        ALWAYS_WINTER("com.colony_seasons.square.order.always_winter_festival", WINTER),
        BARE("com.colony_seasons.square.order.bare", null);

        private final String key;
        private final Set always;

        Order(final String key, final Set always) {
            this.key = key;
            this.always = always;
        }

        /** The stored value and the button's text (a lang key). */
        public String key() {
            return key;
        }

        /** The chosen set of an "Always" order, else {@code null}. */
        public Set always() {
            return always;
        }

        /** A stored value read back: an unknown one (null, empty, a later version's set) is {@link #FOLLOW}, logged once per value. */
        public static Order of(final String value) {
            for (final Order order : values()) {
                if (order.key.equals(value)) {
                    return order;
                }
            }
            unknown(value);
            return FOLLOW;
        }
    }

    /** The values of the settings button, in its order; the first is the default. */
    public static List<String> orderKeys() {
        final List<String> keys = new ArrayList<>();
        for (final Order order : Order.values()) {
            keys.add(order.key());
        }
        return List.copyOf(keys);
    }

    private static final java.util.Set<String> UNKNOWN = ConcurrentHashMap.newKeySet();
    private static int unknownReads;

    /** Unknown stored orders read since the server started (the probe counts them). */
    public static int unknownReads() {
        return unknownReads;
    }

    static void unknown(final String value) {
        unknownReads++;
        if (UNKNOWN.add(String.valueOf(value))) {
            ColonySeasons.LOGGER.info("A Town Square's order '{}' is not known to this version: it reads as Follow the calendar", value);
        }
    }

    private SquareSets() {
    }

    /** The set of a festival id (one whose window is open), or {@code null} when no set of this version belongs to it. */
    public static Set forFestival(final String festival) {
        for (final Set set : ALL) {
            if (set.festivals().contains(festival)) {
                return set;
            }
        }
        return null;
    }

    /**
     * The set the square wants now: none for Bare; in a festival's decoration window the chosen set of an "Always"
     * order, else the first open window's set (the seasonal touches when that festival has none in this version);
     * between the windows the seasonal touches, under every order but Bare ([B]: the full sets only in festival
     * windows). {@code null} snapshot (before the calendar's first poll): the seasonal touches.
     */
    public static Set wanted(final Order order, final CalendarManager.Snapshot snapshot) {
        if (order == Order.BARE) {
            return null;
        }
        if (!DecorPhase.windowOpen(snapshot)) {
            return SEASONAL;
        }
        if (order != null && order.always() != null) {
            return order.always();
        }
        for (final String festival : snapshot.festivals().decor()) {
            final Set set = forFestival(festival);
            if (set != null) {
                return set;
            }
        }
        return SEASONAL;
    }

    /** [B] owner answer of 20 Nov 2026: the centrepiece's size follows the lower of the square's level and the Workshop's. */
    public static int pieceLevel(final int squareLevel, final int workshopLevel) {
        return Math.min(squareLevel, workshopLevel);
    }

    /**
     * The tag slots a square of this level has ([A] design 4.2, the counts at most; the square's blueprint says where):
     * the {@code tree} and the {@code gift} from level 1, {@code lamp} posts from 2, {@code bed}s and the {@code launch}
     * from 3, {@code stall}s from 4. Q3 puts the centrepiece on {@code tree}; the other slots are filled by the later
     * chunks (the gathering, the gift and the crate in Q4, the small pieces with the blueprints in Q6).
     */
    public static Map<String, Integer> slots(final int squareLevel) {
        final int level = Math.max(1, Math.min(5, squareLevel));
        final Map<String, Integer> out = new LinkedHashMap<>();
        out.put("tree", 1);
        out.put("gift", 1);
        out.put("gather", level >= 5 ? 24 : level >= 3 ? 16 : level >= 2 ? 12 : 8);
        out.put("sit", level >= 5 ? 12 : level >= 3 ? 6 : 2);
        if (level >= 2) {
            out.put("lamp", level >= 4 ? 8 : 4);
        }
        if (level >= 3) {
            out.put("bed", level >= 5 ? 8 : 4);
            out.put("launch", 1);
        }
        if (level >= 4) {
            out.put("stall", level >= 5 ? 4 : 2);
        }
        return out;
    }
}
