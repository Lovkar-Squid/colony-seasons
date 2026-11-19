import me.lovkar.seasons.calendar.Season;
import me.lovkar.seasons.client.ClothesRules;
import me.lovkar.seasons.client.ClothesRules.Head;
import me.lovkar.seasons.client.ClothesRules.Outfit;
import me.lovkar.seasons.client.ClothesRules.Wearer;
import me.lovkar.seasons.client.HatModels;

import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * The clothes decision without a game client (tools/clothescheck.sh compiles ClothesRules, HatModels and Season
 * with this file and runs it): who wears a scarf, which one, and who gets the wool hat; MineColonies' December
 * hat; the shipped hat allow-list read by the mod's own parser. Prints "CHECK ...: OK|FAIL" lines and
 * "RESULT clothescheck: PASS|FAIL"; exits 1 on a failure.
 *
 * <p>MineColonies' holiday hat is written out here the way MineColonies decides it (CitizenDataView.getDisplayArmor):
 * its client config holidayFeatures on, the client's clock in December, nothing in the head slot. The mod itself
 * never evaluates that (the client reads no date); it asks MineColonies for the display armour and passes the
 * answer to {@link ClothesRules#head}.</p>
 */
public class ClothesCheck {
    private static boolean failed;

    private static void check(final String what, final boolean ok, final Object detail) {
        System.out.println("CHECK " + what + ": " + (ok ? "OK" : "FAIL") + " " + detail);
        failed |= !ok;
    }

    private static void same(final String what, final Object got, final Object want) {
        check(what, Objects.equals(got, want), "got " + got + ", want " + want);
    }

    /** MineColonies' rule for its own hat (CitizenDataView.getDisplayArmor, HEAD), as MineColonies decides it. */
    private static boolean minecoloniesHolidayHat(final boolean holidayFeatures, final boolean decemberOnClientClock,
                                                  final boolean headSlotEmpty) {
        return holidayFeatures && decemberOnClientClock && headSlotEmpty;
    }

    /** What ClothesLayer passes for a colonist with nothing in the head slot, on a client in this month and config. */
    private static Head emptyHead(final boolean holidayFeatures, final boolean december) {
        return ClothesRules.head(false, minecoloniesHolidayHat(holidayFeatures, december, true));
    }

    private static Outfit dress(final Season season, final List<String> festivals, final int id, final boolean hatModel, final Head head) {
        return ClothesRules.outfit(true, season, festivals, new Wearer(id, false, hatModel, head, false));
    }

    public static void main(final String[] args) throws Exception {
        final List<String> none = List.of();
        final List<String> winterFestival = List.of("winter_festival");
        final List<String> both = List.of("winter_festival", "new_year");

        // nothing at all
        same("no calendar yet: nothing", ClothesRules.outfit(true, null, winterFestival, new Wearer(3, false, true, Head.EMPTY, false)), Outfit.NONE);
        same("showClothes off: nothing", ClothesRules.outfit(false, Season.WINTER, winterFestival, new Wearer(3, false, true, Head.EMPTY, false)), Outfit.NONE);
        same("asleep: nothing", ClothesRules.outfit(true, Season.WINTER, winterFestival, new Wearer(3, true, true, Head.EMPTY, false)), Outfit.NONE);
        same("summer, no festival: nothing", dress(Season.SUMMER, none, 3, true, Head.EMPTY), Outfit.NONE);
        same("autumn, New Year only: nothing", dress(Season.AUTUMN, List.of("new_year"), 3, true, Head.EMPTY), Outfit.NONE);

        // the winter scarf, his own colour
        same("colours", ClothesRules.COLOURS, List.of("red", "green", "blue", "mustard", "plum"));
        for (int id = 0; id < 5; id++) {
            same("winter scarf of colonist " + id, dress(Season.WINTER, none, id, false, Head.EMPTY).scarf(), ClothesRules.COLOURS.get(id));
        }
        same("colonist 7 keeps colonist 2's colour", ClothesRules.colour(7), ClothesRules.colour(2));
        same("a negative id still picks a colour", ClothesRules.colour(-1), "plum");
        same("the colour does not change with the season state", dress(Season.WINTER, none, 12, true, Head.EMPTY).scarf(),
                dress(Season.WINTER, List.of("new_year"), 12, true, Head.EMPTY).scarf());

        // the festive scarf: only while the Winter Festival is on, whatever the season in the game
        same("winter festival in winter: festive scarf", dress(Season.WINTER, winterFestival, 1, false, Head.EMPTY).scarf(), ClothesRules.FESTIVE);
        same("winter festival in summer: festive scarf", dress(Season.SUMMER, winterFestival, 1, false, Head.EMPTY).scarf(), ClothesRules.FESTIVE);
        same("winter festival and New Year: festive scarf", dress(Season.SPRING, both, 1, false, Head.EMPTY).scarf(), ClothesRules.FESTIVE);
        same("winter without the festival: his colour", dress(Season.WINTER, none, 1, false, Head.EMPTY).scarf(), "green");
        same("festival id is the server's", ClothesRules.WINTER_FESTIVAL, "winter_festival");

        // the wool hat: winter, allow-listed model, empty head; his colour
        same("winter, allow-listed, empty head: hat", dress(Season.WINTER, none, 2, true, Head.EMPTY), new Outfit("blue", "blue"));
        same("winter, not allow-listed: scarf, no hat", dress(Season.WINTER, none, 2, false, Head.EMPTY), new Outfit("blue", null));
        same("winter, helmet on: scarf, no hat", dress(Season.WINTER, none, 2, true, Head.WORN), new Outfit("blue", null));
        same("festival in winter: festive scarf and his hat", dress(Season.WINTER, winterFestival, 4, true, Head.EMPTY), new Outfit("festive", "plum"));
        same("festival in summer: no hat", dress(Season.SUMMER, winterFestival, 4, true, Head.EMPTY).hat(), null);

        // body armour: no scarf (the chestplate is drawn round the neck); the hat rule is the head's
        final Outfit guard = ClothesRules.outfit(true, Season.WINTER, none, new Wearer(2, false, true, Head.EMPTY, true));
        same("chestplate in winter: no scarf, the hat still", guard, new Outfit(null, "blue"));
        same("chestplate in the festival: no festive scarf", ClothesRules.outfit(true, Season.WINTER, winterFestival,
                new Wearer(2, false, false, Head.WORN, true)), Outfit.NONE);

        // MineColonies' December hat: ours stays off, the scarf stays on
        final Head december = emptyHead(true, true);
        same("December, holidayFeatures on, empty head slot: MineColonies' hat shows", december, Head.HOLIDAY_HAT);
        same("December, holidayFeatures on: no hat of ours", dress(Season.WINTER, none, 0, true, december).hat(), null);
        same("December, holidayFeatures on: the scarf still", dress(Season.WINTER, none, 0, true, december).scarf(), "red");
        same("December, festival on: festive scarf, no hat of ours", dress(Season.WINTER, winterFestival, 0, true, december), new Outfit("festive", null));
        same("December, holidayFeatures off: our hat", dress(Season.WINTER, none, 0, true, emptyHead(false, true)).hat(), "red");
        same("not December, holidayFeatures on: our hat", dress(Season.WINTER, none, 0, true, emptyHead(true, false)).hat(), "red");
        same("a helmet wins over the holiday hat", ClothesRules.head(true, true), Head.WORN);

        // texture paths
        same("scarf texture", ClothesRules.scarfTexture("red"), "textures/entity/scarf_red.png");
        same("festive texture", ClothesRules.scarfTexture(ClothesRules.FESTIVE), "textures/entity/scarf_festive.png");
        same("hat texture", ClothesRules.hatTexture("plum"), "textures/entity/wool_hat_plum.png");

        // the allow-list, with the mod's own parser on the shipped file
        final Path file = Path.of(args.length > 0 ? args[0] : "resources/assets/colony_seasons/clothes/hat_models.json");
        final HatModels hats;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            hats = HatModels.parse(reader);
        }
        check("allow-list read", !hats.entries().isEmpty(), hats.entries().size() + " entries");
        check("settler man: hat", hats.allows("minecolonies:settler", false), "");
        check("settler woman: hat", hats.allows("minecolonies:settler", true), "");
        check("citizen man (sailor hat): no hat", !hats.allows("minecolonies:citizen", false), "");
        check("builder (cap): no hat", !hats.allows("minecolonies:builder", false) && !hats.allows("minecolonies:builder", true), "");
        check("knight (guard): no hat", !hats.allows("minecolonies:knight", false), "");
        check("girl: hat", hats.allows("minecolonies:child", true), "");
        check("boy (sailor cap): no hat", !hats.allows("minecolonies:child", false), "");
        check("player skin model: no hat", !hats.allows("minecolonies:custom", false) && !hats.allows("minecolonies:custom", true), "");
        check("unknown model: no hat", !hats.allows("voyager:voyager", false) && !hats.allows(null, false), "");
        check("NONE allows nothing", !HatModels.NONE.allows("minecolonies:settler", false), "");
        for (final String bad : new String[]{"{}", "{\"models\": []}", "{\"models\": {\"settler\": [\"male\"]}}",
                "{\"models\": {\"minecolonies:settler\": [\"man\"]}}", "{\"models\": {\"minecolonies:settler\": \"male\"}}"}) {
            boolean refused;
            try {
                HatModels.parse(new StringReader(bad));
                refused = false;
            } catch (final RuntimeException expected) {
                refused = true;
            }
            check("malformed allow-list refused: " + bad, refused, "");
        }

        System.out.println("RESULT clothescheck: " + (failed ? "FAIL" : "PASS"));
        System.exit(failed ? 1 : 0);
    }
}
