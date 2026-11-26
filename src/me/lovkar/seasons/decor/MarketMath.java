package me.lovkar.seasons.decor;

/**
 * The arithmetic of the festival market (0.2.0, chunk Q10, docs/town-square-design.md 7.3), kept apart from every
 * Minecraft and MineColonies class so a plain {@code java} run can test it (tools/unit/MarketMathTest.java). All
 * amounts are in Trade Post's own unit, the value point (the dagger-and-double-bar sign shown on the Town Hall), where one
 * Trade Coin is {@code tradeCoinValue} points (1000 unless the player changed it).
 *
 * <p>The daily cap is one Trade Coin per standing stall: with the coin at 1000 points, 1000 for one stall, 2000 for the
 * two stalls of a level 4 square, 4000 for the four of level 5 (owner answer of 20 Nov 2026, T1 B). Trade Post keeps one
 * 32-bit number per colony day, so every payment is clamped before it is made.</p>
 */
public final class MarketMath {

    /** The most one single payment may be, whatever the cap says: a few coins at most ([B] design 7.3, overflow). */
    public static final int MAX_SINGLE = 100_000;

    private MarketMath() {
    }

    /** What the stalls may earn in one colony day: {@code stalls} x {@code coinValue}, 0 for no stall or a nonsense value. Never above an int. */
    public static int cap(final int stalls, final int coinValue) {
        if (stalls <= 0 || coinValue <= 0) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, (long) stalls * coinValue);
    }

    /**
     * The most of {@code amount} that may be paid on top of a balance of {@code total}: nothing for an amount that is 0
     * or less, never more than {@link #MAX_SINGLE}, never so much that the balance passes an int, never below 0.
     */
    public static int clamp(final long amount, final long total) {
        if (amount <= 0) {
            return 0;
        }
        final long room = Integer.MAX_VALUE - Math.max(total, 0L);
        return (int) Math.max(0L, Math.min(amount, Math.min(MAX_SINGLE, room)));
    }

    /** Whether one more sale of {@code price} fits under {@code cap} when {@code paid} is already paid today: all or nothing, never a part. */
    public static boolean canSell(final int paid, final int cap, final int price) {
        return price > 0 && paid >= 0 && (long) paid <= (long) cap - price;
    }

    /**
     * What the stalls paid on one colony day, as the ledger saves it: the day and the amount. {@link #NONE} (day -1) is
     * "never paid". A later day starts again at 0, the same day adds, an earlier day than the stored one is a clock gone
     * backwards: it counts as the cap already reached, so nothing is paid twice.
     */
    public record PaidDay(long day, int paid) {
        public static final PaidDay NONE = new PaidDay(-1L, 0);

        /** The record as read back from its two saved numbers: a day below 0 is none, a negative amount is 0. */
        public static PaidDay of(final long day, final int paid) {
            return day < 0 ? NONE : new PaidDay(day, Math.max(0, paid));
        }

        /** What counts as paid on {@code today}: the amount on the same day, 0 on a later one, "everything" on an earlier one. */
        public int paidOn(final long today) {
            if (today == day) {
                return paid;
            }
            return today > day ? 0 : Integer.MAX_VALUE;
        }

        /** The record after {@code amount} more (or less, a refund) was paid on {@code today}; unchanged when {@code today} is earlier than the stored day. */
        public PaidDay plus(final long today, final int amount) {
            if (today < day || today < 0) {
                return this;
            }
            final long sum = (long) paidOn(today) + amount;
            return new PaidDay(today, (int) Math.max(0L, Math.min(Integer.MAX_VALUE, sum)));
        }
    }
}
