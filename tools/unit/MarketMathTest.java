import me.lovkar.seasons.decor.MarketMath;
import me.lovkar.seasons.decor.MarketMath.PaidDay;

/**
 * Unit test of the festival market's arithmetic (chunk Q10): the daily cap, the payment clamp, the all-or-nothing sale
 * and the paid-day record. Run by tools/checks.sh against the built jar (a plain `java -cp <jar> MarketMathTest.java`,
 * no Minecraft class is loaded). Prints one line per failure and "MarketMathTest: OK" or exits 1.
 */
public class MarketMathTest {
    static int failures;

    static void eq(final String what, final Object got, final Object want) {
        if (!java.util.Objects.equals(got, want)) {
            failures++;
            System.out.println("FAIL " + what + ": got " + got + ", wanted " + want);
        }
    }

    public static void main(final String[] args) {
        // the cap: one coin per standing stall
        for (int stalls = 0; stalls <= 4; stalls++) {
            eq("cap " + stalls + " x 1", MarketMath.cap(stalls, 1), stalls);
            eq("cap " + stalls + " x 1000", MarketMath.cap(stalls, 1000), stalls * 1000);
            eq("cap " + stalls + " x 1000000", MarketMath.cap(stalls, 1_000_000), stalls * 1_000_000);
        }
        eq("cap of a nonsense coin value 0", MarketMath.cap(2, 0), 0);
        eq("cap of a negative coin value", MarketMath.cap(2, -5), 0);
        eq("cap of a negative stall count", MarketMath.cap(-1, 1000), 0);
        eq("cap never passes an int", MarketMath.cap(4, Integer.MAX_VALUE), Integer.MAX_VALUE);

        // the clamp
        eq("clamp 0", MarketMath.clamp(0, 0), 0);
        eq("clamp negative", MarketMath.clamp(-50, 0), 0);
        eq("clamp small", MarketMath.clamp(45, 1000), 45);
        eq("clamp at the single maximum", MarketMath.clamp(MarketMath.MAX_SINGLE, 0), MarketMath.MAX_SINGLE);
        eq("clamp above the single maximum", MarketMath.clamp(MarketMath.MAX_SINGLE + 1, 0), MarketMath.MAX_SINGLE);
        eq("clamp near the int maximum", MarketMath.clamp(500, Integer.MAX_VALUE - 100), 100);
        eq("clamp on the int maximum", MarketMath.clamp(500, Integer.MAX_VALUE), 0);
        eq("clamp with a negative balance counts it as 0", MarketMath.clamp(500, -1_000), 500);
        eq("clamp keeps balance + amount inside an int", (long) MarketMath.clamp(486, Integer.MAX_VALUE - 486L) + (Integer.MAX_VALUE - 486L) <= Integer.MAX_VALUE, true);

        // the sale: all or nothing under the cap
        eq("canSell on an empty day", MarketMath.canSell(0, 1000, 486), true);
        eq("canSell right up to the cap", MarketMath.canSell(514, 1000, 486), true);
        eq("canSell one over the cap", MarketMath.canSell(515, 1000, 486), false);
        eq("canSell at the cap", MarketMath.canSell(1000, 1000, 1), false);
        eq("canSell a price of 0", MarketMath.canSell(0, 1000, 0), false);
        eq("canSell a negative price", MarketMath.canSell(0, 1000, -3), false);
        eq("canSell with a zero cap", MarketMath.canSell(0, 0, 13), false);
        eq("canSell with the paid record of an earlier day", MarketMath.canSell(Integer.MAX_VALUE, 4000, 13), false);
        eq("canSell cannot overflow", MarketMath.canSell(Integer.MAX_VALUE, Integer.MAX_VALUE, 1), false);

        // the paid-day record
        eq("none has paid nothing today", PaidDay.NONE.paidOn(5), 0);
        PaidDay day = PaidDay.NONE.plus(5, 45);
        eq("first payment", day, new PaidDay(5, 45));
        day = day.plus(5, 13);
        eq("same day adds", day, new PaidDay(5, 58));
        eq("paid on that day", day.paidOn(5), 58);
        eq("a later day starts again", day.paidOn(6), 0);
        day = day.plus(6, 99);
        eq("next day resets", day, new PaidDay(6, 99));
        eq("an earlier day than the stored one blocks", day.paidOn(5), Integer.MAX_VALUE);
        eq("an earlier day pays nothing into the record", day.plus(5, 10), day);
        eq("a refund takes it back", day.plus(6, -99), new PaidDay(6, 0));
        eq("a refund never goes below 0", day.plus(6, -500), new PaidDay(6, 0));
        eq("the record saturates", new PaidDay(6, Integer.MAX_VALUE - 1).plus(6, 50), new PaidDay(6, Integer.MAX_VALUE));
        // what the ledger saves is two numbers; they read back as the same record
        final PaidDay saved = new PaidDay(77, 1234);
        eq("two numbers round trip", PaidDay.of(saved.day(), saved.paid()), saved);
        eq("a negative day reads as none", PaidDay.of(-4, 99), PaidDay.NONE);
        eq("a negative amount reads as 0", PaidDay.of(3, -9), new PaidDay(3, 0));
        // a restart on the same day does not pay a sale twice: the cap is read from the saved record
        PaidDay reloaded = PaidDay.of(saved.day(), saved.paid());
        eq("a reload keeps what was paid", reloaded.paidOn(77), 1234);
        eq("a reload on the same day sells up to the cap only", MarketMath.canSell(reloaded.paidOn(77), 2000, 800), false);
        eq("and one that still fits", MarketMath.canSell(reloaded.paidOn(77), 2000, 766), true);

        if (failures > 0) {
            System.out.println("MarketMathTest: " + failures + " failures");
            System.exit(1);
        }
        System.out.println("MarketMathTest: OK");
    }
}
