package me.lovkar.seasons.decor;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.lovkar.seasons.ColonySeasons;
import me.lovkar.seasons.calendar.CalendarManager;
import me.lovkar.seasons.colony.BuildingDecorator;
import me.lovkar.seasons.colony.BuildingTownSquare;
import me.lovkar.seasons.colony.SquareAssignmentModule;
import me.lovkar.seasons.compat.TradePostBridge;
import me.lovkar.seasons.festival.Gathering;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.neoforged.neoforge.items.IItemHandler;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The festival market on a Town Square (0.2.0, chunk Q10, docs/town-square-design.md 7.2 b): while the Trade Post bridge
 * is active ({@link TradePostBridge}) and a festival set stands on a square of level 4 or more, each of its {@code stall}
 * tag cells gets a stall, and on festival days, in the gathering hours, colonists at the stalls buy: each sale takes one
 * festival good out of the Workshop and pays the colony that good's price ({@code data/mctradepost/item_values.json}) into
 * its Trade Post balance, up to one Trade Coin per standing stall per colony day ({@link MarketMath#cap}).
 *
 * <p>What it is not: it places and removes nothing itself. The stalls are fixtures of the square's group
 * ({@link #fixtures}, read by {@code DecorWork.fixtures}), placed by the same cell rule as the tree (air only, inside the
 * claim, ledgered first, exact-state removal) and taken down with the group when the window ends or a work order makes
 * the square busy. It keeps no goods and no money: a stall's stock is the Workshop's item handler, "paid today" is the
 * ledger's {@code marketDay}/{@code marketPaid}, written before the colony is paid. The goods a sale takes were made from
 * paid materials and stay gone; the stall blocks come back to the Workshop as items, never as money ([B] design 7.3).</p>
 *
 * <p>The stall (T2 A, striped awnings) in its own frame at the tag cell: {@code front} is the horizontal direction
 * towards the tree cell, {@code u} runs across it from -1 to 1, {@code v} from 0 (back) to 1 (front) and {@code y} from 0 at
 * the tag cell's height. Two stripped spruce posts at {@code u = +-1, v = 0}, three high; a counter of top slabs at
 * {@code v = 1, y = 0}; wool over all six columns at {@code y = 3}, red, white, red across {@code u}. The tag cell and the
 * two cells above it stay air (the vendor stands there), and every cell lies inside the pad's 3 x 2 columns, so a lamp
 * post's hanging cell stays free. The whole stall is left-right symmetric, so a mirrored square needs no special case.</p>
 */
public final class MarketStalls {

    /** At most one sale per stall in this many ticks. */
    public static final int SALE_TICKS = 200;
    /** A colonist this near a stall's tag cell (blocks) is a buyer. */
    public static final double BUYER_RADIUS = 5.0;
    /** The cells of one stall: 6 posts, 3 counter slabs, 6 awning blocks. */
    public static final int STALL_CELLS = 15;

    private static final String PRICE_FILE = "/data/mctradepost/item_values.json";

    private static volatile TradePostBridge bridge = new TradePostBridge();
    private static volatile Map<Item, Integer> priceCache;
    private static volatile List<Kits.Kit> priceCacheKits;
    private static final Map<SaleKey, Long> LAST_SALE = new HashMap<>();
    private static int rotation;

    private record SaleKey(ResourceKey<Level> dimension, int colony, BlockPos cell) {
    }

    private MarketStalls() {
    }

    // ---------------------------------------------------------------- the bridge

    /** The bridge in use: the real one, or the probe's. */
    public static TradePostBridge bridge() {
        return bridge;
    }

    /** For the probe: another bridge (its answers and its write); {@code null} makes a new real one. */
    public static void setBridge(final TradePostBridge replacement) {
        bridge = replacement == null ? new TradePostBridge() : replacement;
        priceCache = null;
    }

    /** A new server session (started or stopped): a new bridge, nothing remembered of the last one. The caller announces it on start. */
    public static void newSession() {
        bridge = new TradePostBridge();
        priceCache = null;
        priceCacheKits = null;
        synchronized (LAST_SALE) {
            LAST_SALE.clear();
        }
        rotation = 0;
    }

    // ---------------------------------------------------------------- the stalls

    /** The stall tag cells of a square, lowest first. */
    static List<BlockPos> stallCells(final BuildingTownSquare square) {
        final List<BlockPos> tagged = square.getLocationsFromTag(BuildingTownSquare.TAG_STALL);
        final List<BlockPos> out = new ArrayList<>();
        if (tagged != null) {
            for (final BlockPos cell : tagged) {
                out.add(cell.immutable());
            }
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    /** Whether the square holds the festival market's stalls now: the bridge is active and {@link SquareSets#market} says so. */
    public static boolean wanted(final BuildingTownSquare square, final CalendarManager.Snapshot now) {
        if (square == null || now == null || !bridge.active()) {
            return false;
        }
        return SquareSets.market(SquareSets.wanted(square.order(), now), now, square.getBuildingLevel(), true);
    }

    /** One cell of a stall: where, and what is built there. */
    record Cell(BlockPos pos, BlockState state) {
    }

    /** The direction a stall at {@code tag} faces: towards the tree cell (north when they are the same column). */
    static Direction frontOf(final BlockPos tag, final BlockPos anchor) {
        final int dx = anchor.getX() - tag.getX();
        final int dz = anchor.getZ() - tag.getZ();
        return dx == 0 && dz == 0 ? Direction.NORTH : Direction.getNearest(dx, 0, dz);
    }

    /** The stall's cells in the world, bottom up, for the tag cell {@code stallCell} of a square whose tree stands at {@code anchor}. */
    static List<Cell> layout(final BlockPos stallCell, final BlockPos anchor) {
        final Direction front = frontOf(stallCell, anchor);
        final Direction across = front.getClockWise();
        final BlockState post = Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState();
        final BlockState slab = Blocks.SPRUCE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP);
        final BlockState red = Blocks.RED_WOOL.defaultBlockState();
        final BlockState white = Blocks.WHITE_WOOL.defaultBlockState();
        final List<Cell> out = new ArrayList<>();
        for (int u = -1; u <= 1; u += 2) {
            for (int y = 0; y <= 2; y++) {
                out.add(new Cell(at(stallCell, front, across, u, 0, y), post));
            }
        }
        for (int u = -1; u <= 1; u++) {
            out.add(new Cell(at(stallCell, front, across, u, 1, 0), slab));
        }
        for (int u = -1; u <= 1; u++) {
            for (int v = 0; v <= 1; v++) {
                out.add(new Cell(at(stallCell, front, across, u, v, 3), u == 0 ? white : red));
            }
        }
        out.sort(Comparator.comparingInt((Cell c) -> c.pos().getY()).thenComparing(Cell::pos));
        return out;
    }

    private static BlockPos at(final BlockPos tag, final Direction front, final Direction across, final int u, final int v, final int y) {
        return tag.relative(across, u).relative(front, v).above(y);
    }

    /** Pure geometry: every world cell of the stall at {@code stallCell} for a tree at {@code anchor}. */
    public static List<BlockPos> footprint(final BlockPos stallCell, final BlockPos anchor) {
        final List<BlockPos> out = new ArrayList<>();
        for (final Cell cell : layout(stallCell, anchor)) {
            out.add(cell.pos());
        }
        return out;
    }

    /** Every footprint cell of every stall of the square, whether the market is on or not (what the tree's box leaves out). */
    public static Set<BlockPos> cells(final BuildingTownSquare square, final BlockPos anchor) {
        final Set<BlockPos> out = new HashSet<>();
        for (final BlockPos stall : stallCells(square)) {
            out.addAll(footprint(stall, anchor));
        }
        return out;
    }

    /**
     * The stalls to plan for a target, as cells relative to the anchor, bottom up: for each stall tag cell whose whole
     * footprint is free (air, or this square's own listed block), in the claim and not in an unloaded chunk (that one
     * makes the plan wait). A stall with a foreign block or outside the claim is left out whole and never counts as
     * blocked, so the tree's 10 % rule is as it was. Nothing while {@link #wanted} says no.
     */
    public static List<PieceLoader.PieceCell> fixtures(final DecorWork.Target target) {
        if (!(target.building() instanceof BuildingTownSquare square) || !(square.getColony().getWorld() instanceof ServerLevel level)) {
            return List.of();
        }
        if (!wanted(square, CalendarManager.current())) {
            return List.of();
        }
        final DecorLedger ledger = DecorLedger.peek(level);
        final int colony = square.getColony().getID();
        final BlockPos anchor = target.owner().pos();
        final List<PieceLoader.PieceCell> out = new ArrayList<>();
        for (final BlockPos stall : stallCells(square)) {
            final List<Cell> cells = layout(stall, anchor);
            if (!free(level, ledger, colony, target.owner(), cells)) {
                continue;
            }
            for (final Cell cell : cells) {
                out.add(new PieceLoader.PieceCell(cell.pos().subtract(anchor), cell.state(), false, false, false, false, false));
            }
        }
        return out;
    }

    private static boolean free(final ServerLevel level, final DecorLedger ledger, final int colony, final DecorLedger.Owner owner,
                                final List<Cell> cells) {
        for (final Cell cell : cells) {
            final BlockPos pos = cell.pos();
            if (!level.hasChunkAt(pos)) {
                continue;                             // the plan waits for the chunk, as it does for every cell
            }
            if (DecorPlacer.colonyAt(level, pos) != colony) {
                return false;
            }
            if (!level.getBlockState(pos).isAir()) {
                final DecorLedger.Located at = ledger == null ? null : ledger.at(pos);
                if (at == null || at.colony() != colony || !at.owner().equals(owner)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** How many stalls of the square stand complete: every footprint cell listed in its group and equal to the world. */
    public static int standing(final ServerLevel level, final DecorLedger ledger, final int colony, final BuildingTownSquare square,
                               final BlockPos anchor) {
        if (ledger == null || anchor == null) {
            return 0;
        }
        final DecorLedger.GroupView group = ledger.group(colony, DecorLedger.Owner.square(anchor));
        if (group == null) {
            return 0;
        }
        int n = 0;
        for (final BlockPos stall : stallCells(square)) {
            boolean whole = true;
            for (final Cell cell : layout(stall, anchor)) {
                final DecorLedger.Entry entry = group.entries().get(cell.pos());
                if (entry == null || entry.state() != cell.state() || !level.hasChunkAt(cell.pos())
                        || level.getBlockState(cell.pos()) != entry.state()) {
                    whole = false;
                    break;
                }
            }
            if (whole) {
                n++;
            }
        }
        return n;
    }

    /**
     * True when the market is wanted and a stall that can be planned is not yet listed whole in {@code group}: the square
     * is not "done" then (the Decorator's tending puts the rest up). Reads the world for the foreign-block rule only.
     */
    public static boolean open(final DecorLedger.GroupView group, final DecorWork.Target target) {
        final List<PieceLoader.PieceCell> stalls = fixtures(target);
        if (stalls.isEmpty()) {
            return false;
        }
        final BlockPos anchor = target.owner().pos();
        for (final PieceLoader.PieceCell cell : stalls) {
            if (group == null || !group.entries().containsKey(anchor.offset(cell.rel()))) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- the prices

    /**
     * What each festival good sells for, in points: read from our own jar's {@code data/mctradepost/item_values.json}
     * (never Trade Post's list), only the outputs of the loaded kits, only while the bridge is active. Empty when the
     * bridge is not active or the file cannot be read.
     */
    public static Map<Item, Integer> prices() {
        if (!bridge.active()) {
            return Map.of();
        }
        final List<Kits.Kit> kits = Kits.all();
        final Map<Item, Integer> known = priceCache;
        if (known != null && priceCacheKits == kits) {
            return known;
        }
        final Map<Item, Integer> out = new LinkedHashMap<>();
        try (InputStream in = ColonySeasons.class.getResourceAsStream(PRICE_FILE)) {
            if (in != null) {
                final JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                final JsonObject values = root.getAsJsonObject("values");
                final Set<Item> outputs = new HashSet<>();
                for (final Kits.Kit kit : kits) {
                    outputs.add(kit.output());
                }
                for (final Map.Entry<String, JsonElement> e : values.entrySet()) {
                    final ResourceLocation id = ResourceLocation.tryParse(e.getKey());
                    final Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
                    final int price = e.getValue().isJsonPrimitive() ? e.getValue().getAsInt() : 0;
                    if (item != null && outputs.contains(item) && price >= 1) {
                        out.put(item, price);
                    }
                }
            }
        } catch (final Exception unreadable) {
            bridge.fail("the price list of the festival goods could not be read", unreadable);
            return Map.of();
        }
        priceCache = Map.copyOf(out);
        priceCacheKits = kits;
        return priceCache;
    }

    // ---------------------------------------------------------------- the sales

    /** One sale: the good and the points it paid. */
    public record Sale(Item item, int price) {
    }

    /** The Decorator who works the main square: the one listed on it, else one listed on another square; {@code null} for nobody. */
    static ICitizenData decoratorOf(final IColony colony, final BuildingTownSquare square) {
        final SquareAssignmentModule own = square.assignment();
        if (own != null && !own.getAssignedCitizen().isEmpty()) {
            return own.getAssignedCitizen().get(0);
        }
        for (final IBuilding b : colony.getServerBuildingManager().getBuildings().values()) {
            if (b instanceof BuildingTownSquare other && other != square && other.assignment() != null
                    && !other.assignment().getAssignedCitizen().isEmpty()) {
                return other.assignment().getAssignedCitizen().get(0);
            }
        }
        return null;
    }

    private static BuildingDecorator workshopOf(final ICitizenData decorator) {
        return decorator != null && decorator.getWorkBuilding() instanceof BuildingDecorator hut ? hut : null;
    }

    /** What of {@code item} the Workshop holds above its reserve (one kit batch of it): the goods it may sell. */
    private static int spare(final BuildingDecorator workshop, final Kits.Kit kit) {
        final IItemHandler store = workshop.getItemHandlerCap();
        if (store == null) {
            return 0;
        }
        return BuildingDecorator.countIn(store, s -> s.is(kit.output())) - Math.max(1, kit.count());
    }

    /** Whether the Workshop holds a priced good above its reserve. */
    private static boolean anySpare(final BuildingDecorator workshop, final Map<Item, Integer> prices) {
        for (final Kits.Kit kit : Kits.all()) {
            if (prices.containsKey(kit.output()) && spare(workshop, kit) > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * One sale if one fits: a priced good the Workshop holds above its reserve whose price fits under what is left of the
     * day's cap ({@link MarketMath#canSell}), the goods taken in turn. In this order: (1) one good out of the Workshop's
     * own item handler, (2) the price added to the day's paid record and the ledger saved, (3) the colony paid
     * ({@link TradePostBridge#credit}). If (3) fails or anything throws, the good goes back and the record is taken back
     * (the bridge has fallen back by then). {@code null} when nothing was sold.
     *
     * @param day the colony day ({@code colony.getDay()}) the cap and the paid record are counted on
     */
    public static Sale sellOne(final ServerLevel level, final IColony colony, final BuildingTownSquare square,
                               final BuildingDecorator workshop, final long day) {
        if (workshop == null || workshop.getItemHandlerCap() == null || !bridge.selfTest(colony)) {
            return null;
        }
        final DecorLedger ledger = DecorLedger.get(level);
        final Squares.Square resolved = Squares.of(level, colony, ledger);
        if (resolved.anchor() == null) {
            return null;
        }
        final int stalls = standing(level, ledger, colony.getID(), square, resolved.anchor());
        final int cap = MarketMath.cap(stalls, bridge.coinValue());
        final int paid = ledger.marketPaid(colony.getID(), day);
        final Map<Item, Integer> prices = prices();
        final List<Kits.Kit> kits = new ArrayList<>(Kits.all());
        kits.removeIf(kit -> !prices.containsKey(kit.output()));
        if (kits.isEmpty()) {
            return null;
        }
        Kits.Kit pick = null;
        final int start = Math.floorMod(rotation, kits.size());
        for (int i = 0; i < kits.size() && pick == null; i++) {
            final Kits.Kit kit = kits.get((start + i) % kits.size());
            if (spare(workshop, kit) > 0 && MarketMath.canSell(paid, cap, prices.get(kit.output()))) {
                pick = kit;
            }
        }
        if (pick == null) {
            return null;
        }
        final Item item = pick.output();
        final int price = prices.get(item);
        final DecorPlacer.Stock stock = DecorWork.stockOf(workshop, null);
        boolean took = false;
        boolean added = false;
        try {
            took = stock.take(item);
            if (!took) {
                return null;
            }
            ledger.addMarketPaid(colony.getID(), day, price);
            added = true;
            ledger.flush(level);
            if (bridge.credit(colony, price)) {
                rotation++;
                return new Sale(item, price);
            }
        } catch (final Throwable failed) {
            bridge.fail("a sale at the festival market failed", failed);
        }
        // anything but a paid sale: the good back, the record back
        try {
            if (added) {
                ledger.addMarketPaid(colony.getID(), day, -price);
                ledger.flush(level);
            }
        } finally {
            if (took) {
                stock.give(new ItemStack(item));
            }
        }
        return null;
    }

    /**
     * One look at one colony from the festival director's poll: on a gathering day, in the gathering hours and out of the
     * rain, with the market wanted on the main square, a complete stall, its Decorator at the square and another colonist
     * near the stall, at most one sale per stall in {@link #SALE_TICKS} ticks. Nothing is written when any of that is not so.
     */
    public static void tick(final ServerLevel level, final IColony colony, final CalendarManager.Snapshot snapshot, final long gameTime) {
        if (!bridge.active() || !Gathering.gatheringDay(snapshot) || !Gathering.inHours(level) || level.isRaining()) {
            return;
        }
        final DecorLedger ledger = DecorLedger.peek(level);
        if (ledger == null) {
            return;
        }
        final Squares.Square resolved = Squares.of(level, colony, ledger);
        if (resolved.source() != Squares.Source.BUILDING || !resolved.located() || resolved.busy() || resolved.anchor() == null) {
            return;
        }
        if (!(colony.getServerBuildingManager().getBuilding(resolved.building()) instanceof BuildingTownSquare square)
                || !wanted(square, snapshot)) {
            return;
        }
        final ICitizenData decorator = decoratorOf(colony, square);
        final BuildingDecorator workshop = workshopOf(decorator);
        final AbstractEntityCitizen host = decorator == null ? null : decorator.getEntity().orElse(null);
        final BlockPos anchor = resolved.anchor();
        if (workshop == null || host == null || host.blockPosition().distSqr(anchor) > (double) Gathering.SQUARE_KEEP_RADIUS * Gathering.SQUARE_KEEP_RADIUS) {
            return;
        }
        for (final BlockPos stall : stallCells(square)) {
            if (stallStands(level, ledger, colony.getID(), anchor, stall) && buyerNear(colony, host, stall)) {
                final SaleKey key = new SaleKey(level.dimension(), colony.getID(), stall);
                synchronized (LAST_SALE) {
                    final Long last = LAST_SALE.get(key);
                    if (last != null && gameTime - last < SALE_TICKS) {
                        continue;
                    }
                }
                final Sale sale = sellOne(level, colony, square, workshop, colony.getDay());
                if (sale != null) {
                    synchronized (LAST_SALE) {
                        LAST_SALE.put(key, gameTime);
                    }
                }
            }
        }
    }

    /** True when the one stall at {@code stall} stands complete. */
    private static boolean stallStands(final ServerLevel level, final DecorLedger ledger, final int colony, final BlockPos anchor,
                                       final BlockPos stall) {
        final DecorLedger.GroupView group = ledger.group(colony, DecorLedger.Owner.square(anchor));
        if (group == null) {
            return false;
        }
        for (final Cell cell : layout(stall, anchor)) {
            final DecorLedger.Entry entry = group.entries().get(cell.pos());
            if (entry == null || entry.state() != cell.state() || !level.hasChunkAt(cell.pos()) || level.getBlockState(cell.pos()) != entry.state()) {
                return false;
            }
        }
        return true;
    }

    private static boolean buyerNear(final IColony colony, final AbstractEntityCitizen host, final BlockPos stall) {
        final double reach = BUYER_RADIUS * BUYER_RADIUS;
        for (final ICitizenData citizen : colony.getCitizenManager().getCitizens()) {
            final AbstractEntityCitizen entity = citizen.getEntity().orElse(null);
            if (entity != null && entity != host && entity.isAlive() && entity.blockPosition().distSqr(stall) <= reach) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- the info page

    /** What the info page says about the market: {@code state} 0 hidden, 1 closed until the next festival, 2 no goods in stock, 3 open with what was paid today of the cap. */
    public record Line(int state, int paid, int cap) {
        public static final Line HIDDEN = new Line(0, 0, 0);
    }

    /** The market line of one Town Square's info page; reads only, and is {@link Line#HIDDEN} unless the bridge is active and this is the main square of level 4 or more. */
    public static Line line(final BuildingTownSquare square) {
        final IColony colony = square == null ? null : square.getColony();
        if (colony == null || !(colony.getWorld() instanceof ServerLevel level) || !bridge.active()
                || square.getBuildingLevel() < SquareSets.MARKET_LEVEL) {
            return Line.HIDDEN;
        }
        final DecorLedger ledger = DecorLedger.peek(level);
        final Squares.Square resolved = Squares.of(level, colony, ledger);
        if (resolved.source() != Squares.Source.BUILDING || !resolved.building().equals(square.getPosition())) {
            return Line.HIDDEN;
        }
        final CalendarManager.Snapshot now = CalendarManager.current();
        final int stalls = resolved.anchor() == null ? 0 : standing(level, ledger, colony.getID(), square, resolved.anchor());
        if (!Gathering.gatheringDay(now) || !wanted(square, now) || stalls == 0 || resolved.busy()) {
            return new Line(1, 0, 0);
        }
        final int cap = MarketMath.cap(stalls, bridge.coinValue());
        final int paid = Math.min(cap, ledger.marketPaid(colony.getID(), colony.getDay()));
        final ICitizenData decorator = decoratorOf(colony, square);
        final BuildingDecorator workshop = workshopOf(decorator);
        final boolean spare = workshop != null && anySpare(workshop, prices());
        return new Line(!spare && paid < cap ? 2 : 3, paid, cap);
    }
}
