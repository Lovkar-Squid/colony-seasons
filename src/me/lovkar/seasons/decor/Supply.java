package me.lovkar.seasons.decor;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.workerbuildings.IWareHouse;
import com.minecolonies.api.util.InventoryUtils;
import net.minecraft.world.item.Item;

/**
 * The Decorator's supply order (the owner's decision of 20 Nov 2026): a decoration comes first from his own storage
 * (the Workshop's racks, the hut block and his pack: last year's, taken down), then finished decorations the colony
 * already holds in its warehouses (asked for through MineColonies' request system, so a courier brings them, and only
 * while a warehouse holds them), and only for the rest does he order the kit's ingredients and make them.
 *
 * <p>{@link #plan} is the whole rule, a pure function the probe checks row by row; {@link #held} is the one read of
 * the world it needs. Nothing here keeps state: the AI reads both again on every pass.</p>
 */
public final class Supply {

    private Supply() {
    }

    /**
     * What to do for one kit output on one pass.
     *
     * @param finished    how many finished pieces to ask the warehouse for now (0: none)
     * @param ingredients whether the kit still goes on the list of kits whose ingredients are ordered and made
     */
    public record Plan(int finished, boolean ingredients) {
        public static final Plan NOTHING = new Plan(0, false);
    }

    /**
     * The supply order for one kit output.
     *
     * @param have     in his own storage: the hut (racks included) and his pack
     * @param inflight already asked for from the warehouse and not yet delivered (his open requests for the finished item)
     * @param held     what the colony's warehouses hold of it
     * @param count    how many one batch of the kit makes
     * @param cap      the hut's stock cap
     * @return nothing while the stock with what is on its way is not "wanted" ({@link Kits#wanted}); otherwise a
     *     finished request for what the warehouses hold of the gap to the cap (none while one is still open: one at a
     *     time), and the ingredients only when, with that request counted, a batch is still wanted
     */
    public static Plan plan(final int have, final int inflight, final int held, final int count, final int cap) {
        final int own = Math.max(0, have) + Math.max(0, inflight);
        if (!Kits.wanted(own, count, cap)) {
            return Plan.NOTHING;
        }
        final int need = Math.max(0, cap - own);
        final int finished = inflight > 0 ? 0 : Math.max(0, Math.min(held, need));
        return new Plan(finished, Kits.wanted(own + finished, count, cap));
    }

    /**
     * How many of {@code item} the colony's warehouses hold, counted the way MineColonies counts a building's stock
     * (over its racks, {@link InventoryUtils#getCountFromBuilding}). A warehouse that cannot be read counts 0.
     */
    public static int held(final IColony colony, final Item item) {
        if (colony == null || item == null) {
            return 0;
        }
        int total = 0;
        try {
            for (final IWareHouse warehouse : colony.getServerBuildingManager().getWareHouses()) {
                if (warehouse == null || warehouse.getTileEntity() == null) {
                    continue;
                }
                try {
                    total += InventoryUtils.getCountFromBuilding(warehouse, stack -> stack.is(item));
                } catch (final RuntimeException unreadable) {
                    // one warehouse that cannot be read (a chunk going away) counts 0; the others still count
                }
            }
        } catch (final RuntimeException unavailable) {
            return 0;
        }
        return total;
    }
}
