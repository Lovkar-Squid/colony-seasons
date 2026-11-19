package me.lovkar.seasons.client;

import me.lovkar.seasons.calendar.Season;

import java.util.List;

/**
 * Who wears what (design section 7): the whole decision of the clothes layer, with no Minecraft class in
 * it, so {@code tools/clothescheck.sh} can test it without a game. {@link ClothesLayer} only gathers the
 * inputs from the colonist and the client state and draws what this answers.
 *
 * <ul>
 *   <li>A scarf in in-game winter, in one of {@link #COLOURS}, picked by the colonist's id so he keeps it.</li>
 *   <li>A striped festive scarf instead while the Winter Festival is on (its decoration window or its days,
 *       as the server reports it). The festival runs on real dates and the seasons in the game, so the
 *       festive scarf also shows when the game is in another season.</li>
 *   <li>A wool hat in in-game winter, of the scarf's colour, only on the models of the allow-list and only on
 *       an empty head: not over a helmet or anything else in the head slot, and not when MineColonies shows its
 *       own holiday hat (its December hat; the layer asks MineColonies, it never reads a date). The scarf is
 *       still drawn then.</li>
 *   <li>No scarf over body armour: a guard's chestplate is drawn round the neck and would cut through it.</li>
 *   <li>Nothing while the client config switch is off, while the colonist sleeps, or before the server has
 *       sent the calendar.</li>
 * </ul>
 */
public final class ClothesRules {

    /** The five scarf (and hat) colours; the texture of each is {@code scarf_<colour>.png} / {@code wool_hat_<colour>.png}. */
    public static final List<String> COLOURS = List.of("red", "green", "blue", "mustard", "plum");
    /** The striped scarf of the Winter Festival, {@code scarf_festive.png}. */
    public static final String FESTIVE = "festive";
    /** The festival id that brings out the festive scarf (the id the server sends, SeasonsConfig.WINTER_FESTIVAL). */
    public static final String WINTER_FESTIVAL = "winter_festival";

    /** Texture paths in the mod's namespace; {@code %s} is a colour or {@link #FESTIVE}. tools/check_clothes.py reads these. */
    public static final String SCARF_TEXTURE = "textures/entity/scarf_%s.png";
    public static final String HAT_TEXTURE = "textures/entity/wool_hat_%s.png";

    /** What the colonist's head shows before our hat. */
    public enum Head {
        /** Nothing: our hat may go on. */
        EMPTY,
        /** Something in the head slot (a guard's helmet, any item). */
        WORN,
        /** The slot is empty but MineColonies draws its holiday hat there. */
        HOLIDAY_HAT
    }

    /**
     * The colonist, as far as the clothes care.
     *
     * @param civilianId MineColonies' id of the colonist in his colony (stable, the same on every client)
     * @param sleeping   lying in bed
     * @param hatModel   his model and gender are on the wool hat allow-list
     * @param head       what his head shows
     * @param chestArmour he wears body armour (a chestplate MineColonies draws on him)
     */
    public record Wearer(int civilianId, boolean sleeping, boolean hatModel, Head head, boolean chestArmour) {
    }

    /**
     * What to draw.
     *
     * @param scarf a colour of {@link #COLOURS}, {@link #FESTIVE}, or {@code null} for no scarf
     * @param hat   a colour of {@link #COLOURS}, or {@code null} for no hat
     */
    public record Outfit(String scarf, String hat) {
        public static final Outfit NONE = new Outfit(null, null);

        public boolean isEmpty() {
            return scarf == null && hat == null;
        }
    }

    private ClothesRules() {
    }

    /**
     * The outfit of one colonist this frame.
     *
     * @param showClothes the client config switch
     * @param season      the season the server sent, or {@code null} before the first packet
     * @param festivals   the ids of the festivals the server says are on
     * @param wearer      the colonist
     */
    public static Outfit outfit(final boolean showClothes, final Season season, final List<String> festivals,
                                final Wearer wearer) {
        if (!showClothes || season == null || wearer.sleeping()) {
            return Outfit.NONE;
        }
        final boolean winter = season == Season.WINTER;
        final boolean festive = festivals.contains(WINTER_FESTIVAL);
        final String colour = colour(wearer.civilianId());
        final String scarf = wearer.chestArmour() ? null : festive ? FESTIVE : winter ? colour : null;
        final String hat = winter && wearer.hatModel() && wearer.head() == Head.EMPTY ? colour : null;
        return new Outfit(scarf, hat);
    }

    /** The colour of a colonist: his id picks one of the five, so he keeps it all winter and on every client. */
    public static String colour(final int civilianId) {
        return COLOURS.get(Math.floorMod(civilianId, COLOURS.size()));
    }

    /**
     * What the head shows: an item in the head slot wins; else MineColonies' display armour, which is its holiday
     * hat on an empty head (CitizenDataView.getDisplayArmor: client config holidayFeatures on and the client's
     * clock in December, decided by MineColonies).
     *
     * @param itemInHeadSlot     the colonist has anything in his head slot
     * @param minecoloniesHeadItem MineColonies' display armour for the head is not empty
     */
    public static Head head(final boolean itemInHeadSlot, final boolean minecoloniesHeadItem) {
        if (itemInHeadSlot) {
            return Head.WORN;
        }
        return minecoloniesHeadItem ? Head.HOLIDAY_HAT : Head.EMPTY;
    }

    /** The texture path of a scarf ({@code colour} is a colour or {@link #FESTIVE}). */
    public static String scarfTexture(final String colour) {
        return String.format(SCARF_TEXTURE, colour);
    }

    /** The texture path of a wool hat. */
    public static String hatTexture(final String colour) {
        return String.format(HAT_TEXTURE, colour);
    }
}
