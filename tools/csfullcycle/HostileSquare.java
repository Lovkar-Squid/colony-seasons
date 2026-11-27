package me.lovkar.seasons.csfullcycle;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.workorders.IServerWorkOrder;
import com.minecolonies.api.colony.workorders.WorkOrderType;
import com.minecolonies.core.colony.workorders.WorkOrderBuilding;
import com.mojang.authlib.GameProfile;
import me.lovkar.seasons.block.SeasonsBlocks;
import me.lovkar.seasons.calendar.CalendarManager;
import me.lovkar.seasons.calendar.Season;
import me.lovkar.seasons.colony.BuildingDecorator;
import me.lovkar.seasons.colony.BuildingTownSquare;
import me.lovkar.seasons.colony.DecoratorModules;
import me.lovkar.seasons.colony.DecoratorSetup;
import me.lovkar.seasons.compat.TradePostBridge;
import me.lovkar.seasons.decor.DecorEvents;
import me.lovkar.seasons.decor.DecorLedger;
import me.lovkar.seasons.decor.DecorPlacer;
import me.lovkar.seasons.decor.DecorWork;
import me.lovkar.seasons.decor.Kits;
import me.lovkar.seasons.decor.MarketMath;
import me.lovkar.seasons.decor.MarketStalls;
import me.lovkar.seasons.decor.PieceLoader;
import me.lovkar.seasons.decor.SquareSets;
import me.lovkar.seasons.decor.Squares;
import me.lovkar.seasons.decor.StreetMarks;
import me.lovkar.seasons.festival.Fireworks;
import me.lovkar.seasons.festival.Gathering;
import me.lovkar.seasons.item.DecoratorWandItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Q7's hostile review of the Town Square (mode hostile-square): someone trying to break it. Colony A: a town hall, the
 * Workshop at level 3 with its Decorator (in MineColonies' data only, so no AI of his runs: every visit is the probe's own
 * call of {@link DecorWork}), a registered festival marker and the level 5 square; a Trade Post bridge switched on without
 * the jar (a {@link TradePostBridge} told Trade Post is there; it pays MineColonies' own statistic). The legs, findings
 * numbered from H20: H20 set swaps (Bare, Always, Follow with the tree half up, a paid piece broken and kept between the
 * take-down and the put-up, the Workshop full when the pieces come back); H21 ghosts on upgrade (an order while a take-down
 * is half done, listed cells cleared as the Builder's CLEAR_NON_SOLIDS would before the drain); H22 an order in the
 * gathering hours and in the dusk; H23 two squares (a tie, one raised above, the main hut broken, both busy, the Decorator
 * listed on the lower one or on none); H24 the square's box reaching into colony B's claim; H25 marks and claims; H26 money
 * (a write that pays one point too many, a read-back that throws, the cap with stalls broken, the set swapped and a second
 * main square, five stall take-downs, a sale while busy); H27 a touch pumpkin carved by a player. After each leg the daily
 * invariants I1 to I8 (I3 exact).
 */
final class HostileSquare {
    private final CsFullCycle p;
    private final SquareCycle sc;
    private static final LocalDate DECOR_DAY = LocalDate.of(2026, 12, 10);
    private static final LocalDate FESTIVAL_DAY = LocalDate.of(2026, 12, 25);
    private static final LocalDate EVE = LocalDate.of(2026, 12, 31);
    private static final LocalDate AUTUMN_DAY = LocalDate.of(2027, 10, 15);
    /** The second square's plot (x 30..50, z 22..42). */
    private static final BlockPos PLOT_2 = new BlockPos(30, 0, 22);
    private BlockPos realMarker;
    private BuildingTownSquare s2;
    private IColony b;
    private final Map<Item, Integer> prices = new LinkedHashMap<>();

    HostileSquare(final CsFullCycle probe, final SquareCycle square) {
        this.p = probe;
        this.sc = square;
    }

    // ================================================================ helpers

    private void now(final String name, final Runnable body) {
        p.step(new CsFullCycle.Step(name, 1) {
            @Override
            void start() {
                body.run();
            }
        });
    }

    private void waitFor(final String name, final int ticks, final BooleanSupplier until, final Runnable after) {
        p.step(new CsFullCycle.Step(name, ticks, true) {
            @Override
            boolean tick() {
                return until.getAsBoolean();
            }

            @Override
            void end(final boolean done) {
                after.run();
            }
        });
    }

    private void finding(final String id, final String kind, final String text, final Object... args) {
        CsFullCycle.note("FINDING " + id + " owner:" + kind + " " + text, args);
    }

    private DecorPlacer.Stock stock() {
        return DecorWork.stockOf(p.hut, null);
    }

    private void order(final BuildingTownSquare square, final SquareSets.Order order) {
        square.settings().getSetting(BuildingTownSquare.ORDER).set(order.key());
        square.markDirty();
    }

    /** Visits the main square's set until it is complete (or nothing more goes up); returns the last outcome's status. */
    private String decorate(final BuildingTownSquare square) {
        final Squares.Square s = Squares.refresh(p.level, p.colony, p.ledger);
        if (s.source() != Squares.Source.BUILDING || !square.getPosition().equals(s.building())) {
            return "not the main square";
        }
        final DecorWork.Target t = DecorWork.Target.townSquare(square, s.anchor());
        DecorWork.Outcome last = null;
        for (int i = 0; i < 12; i++) {
            last = DecorWork.decorate(p.level, p.colonyId, t, CsFullCycle.HUT_LEVEL, stock(), p.day(), p.ledger);
            if (last.complete() || last.placed() == 0 && last.open() == 0) {
                break;
            }
        }
        return last == null ? "-" : last.status();
    }

    private int size(final BuildingTownSquare square, final boolean touches) {
        final BlockPos a = Squares.treeCell(square);
        if (a == null) {
            return 0;
        }
        final DecorLedger.GroupView g = p.ledger.group(p.colonyId, touches ? DecorLedger.Owner.touches(a) : DecorLedger.Owner.square(a));
        return g == null ? 0 : g.entries().size();
    }

    private IServerWorkOrder orderOn(final IBuilding building) {
        for (final IServerWorkOrder wo : p.colony.getWorkManager().getWorkOrders().values()) {
            if (wo.getLocation() != null && wo.getLocation().equals(building.getPosition())) {
                return wo;
            }
        }
        return null;
    }

    private void repair(final BuildingTownSquare square) {
        p.colony.getWorkManager().addWorkOrder(WorkOrderBuilding.create(WorkOrderType.REPAIR, square), false);
    }

    private void close(final BuildingTownSquare square) {
        final IServerWorkOrder wo = orderOn(square);
        if (wo != null) {
            p.colony.getWorkManager().removeWorkOrder(wo);
        }
        DecorEvents.squareChanged(square);
        DecorEvents.drain(p.server);
    }

    private void insert(final Item item, final int n) {
        int left = n;
        while (left > 0) {
            final int one = Math.min(left, item.getDefaultMaxStackSize());
            ItemHandlerHelper.insertItemStacked(p.hut.getItemHandlerCap(), new ItemStack(item, one), false);
            left -= one;
        }
    }

    private int held(final Item item) {
        return BuildingDecorator.countIn(p.hut.getItemHandlerCap(), s -> s.is(item));
    }

    private int goods() {
        int n = 0;
        for (final Item item : prices.keySet()) {
            n += held(item);
        }
        return n;
    }

    private int balance() {
        return p.colony.getStatisticsManager().getStatTotal(TradePostBridge.STAT);
    }

    /** A bridge told Trade Post is there; {@code extra} points more than asked are written, {@code throwAfter} makes the read-back throw. */
    private static TradePostBridge bridge(final int extra, final boolean throwAfter) {
        return new TradePostBridge(() -> true, () -> true) {
            boolean wrote;

            @Override
            protected void write(final IColony colony, final int amount) {
                super.write(colony, amount + extra);
                wrote = true;
            }

            @Override
            protected int read(final IColony colony) {
                if (throwAfter && wrote) {
                    throw new IllegalStateException("probe: the read-back fails");
                }
                return super.read(colony);
            }
        };
    }

    // ================================================================ plan

    void plan() {
        setup();
        planMoney();
        planSwaps();
        planGhosts();
        planHours();
        planTwoSquares();
        planForeign();
        planCarve();
        now("hostile-square: the end", () -> {
            MarketStalls.setBridge(null);
            p.daily("hostile-square end");
        });
    }

    private void setup() {
        p.step(new CsFullCycle.Step("hostile-square setup: colony A, the Workshop, the marker, the level 5 square", 120, true) {
            @Override
            void start() {
                p.setDate(DECOR_DAY);
                p.quietWorld();
                p.ground = p.level.getHeight(Heightmap.Types.MOTION_BLOCKING, 2, 2) - 1;
                final BlockPos center = new BlockPos(2, p.ground + 1, 2);
                p.forceChunks(7);
                p.owner = FakePlayerFactory.getMinecraft(p.level);
                p.colony = IColonyManager.getInstance().createColony(p.level, center, p.owner, "Seasons Hostile Square", "Medieval Oak");
                p.colonyId = p.colony == null ? -1 : p.colony.getID();
                p.check("hostile-square: colony A was made", p.colony != null, "id " + p.colonyId);
                if (p.colony == null) {
                    p.finish();
                    return;
                }
                p.townHall = p.register(p.colony, "minecolonies:blockhuttownhall", p.house(-3, -1, 11, 7, 2), 1);
                final IBuilding d = p.register(p.colony, BuiltInRegistries.BLOCK.getKey(DecoratorSetup.HUT.get()).toString(),
                        p.house(12, -1, 11, 7, 17), CsFullCycle.HUT_LEVEL);
                p.hut = d instanceof BuildingDecorator bd ? bd : null;
                p.buildings.addAll(List.of(p.townHall, p.hut));
                final ICitizenData data = p.colony.getCitizenManager().createAndRegisterCivilianData();
                data.setIsChild(false);
                p.decorator = data;
                p.check("hostile-square: the Decorator is hired (in MineColonies' data only: no AI of his runs)",
                        p.hut.getModule(DecoratorModules.WORK).assignCitizen(data), "");
                realMarker = new BlockPos(20, p.ground + 1, 34);
                p.check("hostile-square: the marker's cell is in the claim", p.claimed(realMarker), realMarker.toShortString());
                p.marker = realMarker;
                p.placeMarker();
                MarketStalls.setBridge(bridge(0, false));
                sc.skipMoney = true;
                sc.placeInSetup();
                p.players();
                p.setTimeOfDay(CsFullCycle.MORNING);
            }

            @Override
            boolean tick() {
                return t >= 40 && sc.locate() && PieceLoader.request(p.level, PieceLoader.PACK, PieceLoader.pathFor(CsFullCycle.HUT_LEVEL),
                        Direction.NORTH) != null;
            }

            @Override
            void end(final boolean done) {
                p.check("hostile-square: the square is located and the tree's piece loaded", done, "after " + t + " ticks");
                if (!done) {
                    p.finish();
                    return;
                }
                p.checkEq("hostile-square: the marker is registered as the fallback", p.ledger.marker(p.colonyId), realMarker);
                final Map<Item, Integer> need = new LinkedHashMap<>();
                sc.addNeeds(need, DECOR_DAY, DECOR_DAY);
                prices.putAll(MarketStalls.prices());
                for (final Item item : List.of(SeasonsBlocks.WREATH_ITEM.get(), SeasonsBlocks.GARLAND_ITEM.get(), SeasonsBlocks.LANTERN_STRING_ITEM.get(),
                        SeasonsBlocks.FAIRY_LIGHTS_ITEM.get(), SeasonsBlocks.ORNAMENT_LEAVES_ITEM.get(), SeasonsBlocks.TREE_STAR_ITEM.get(),
                        Items.SPRUCE_LOG, Items.SPRUCE_LEAVES, Items.STRIPPED_SPRUCE_LOG, Items.SPRUCE_SLAB, Items.RED_WOOL, Items.WHITE_WOOL)) {
                    p.start.put(item, 0);
                }
                // twice the set's need: a second square's set and the take-downs' refunds always find their items
                for (final Map.Entry<Item, Integer> e : need.entrySet()) {
                    insert(e.getKey(), 2 * e.getValue());
                    p.start.merge(e.getKey(), 2 * e.getValue(), Integer::sum);
                }
                for (final Kits.Kit kit : Kits.all()) {
                    if (prices.containsKey(kit.output())) {
                        final int n = Math.max(1, kit.count()) + 40;
                        insert(kit.output(), n);
                        p.start.merge(kit.output(), n, Integer::sum);
                    }
                }
                p.first = Snap.take(p.level, new BlockPos(-14, p.ground - 1, -6), new BlockPos(28, p.ground + 24, 46));
                CsFullCycle.note("hostile-square: the Workshop holds {}", CsFullCycle.text(p.start));
                p.daily("hostile-square setup");
            }
        });
    }

    // ================================================================ H26 money

    private int goodsValueGone(final Map<Item, Integer> before) {
        int v = 0;
        for (final Map.Entry<Item, Integer> e : prices.entrySet()) {
            v += (before.getOrDefault(e.getKey(), 0) - held(e.getKey())) * e.getValue();
        }
        return v;
    }

    private Map<Item, Integer> goodsNow() {
        final Map<Item, Integer> m = new LinkedHashMap<>();
        for (final Item item : prices.keySet()) {
            m.put(item, held(item));
        }
        return m;
    }

    private void planMoney() {
        now("H26: the square's set with its four stalls goes up", () -> {
            final String status = decorate(sc.square);
            final int standing = MarketStalls.standing(p.level, p.ledger, p.colonyId, sc.square, sc.anchor);
            p.check("H26: the set and four stalls stand (the bridge on, a level 5 square, the window)", standing == 4, standing + " stalls, " + status);
            p.daily("H26 set up");
        });
        now("H26 M0: one sale with a sound bridge", () -> {
            final Map<Item, Integer> before = goodsNow();
            final int b0 = balance();
            final MarketStalls.Sale sale = MarketStalls.sellOne(p.level, p.colony, sc.square, p.hut, p.colony.getDay());
            p.check("H26 M0: one sale takes one good and pays exactly its price", sale != null && balance() - b0 == sale.price()
                    && goodsValueGone(before) == sale.price(), (sale == null ? "no sale" : sale.price() + " points") + ", balance +" + (balance() - b0));
            moneyCheck("M0", before, b0);
        });
        now("H26 M1: a write that pays one point more than asked", () -> {
            MarketStalls.setBridge(bridge(1, false));
            final Map<Item, Integer> before = goodsNow();
            final int b0 = balance();
            final int lines = TradePostBridge.logLines();
            final MarketStalls.Sale sale = MarketStalls.sellOne(p.level, p.colony, sc.square, p.hut, p.colony.getDay());
            CsFullCycle.note("H26 M1: sale {}, balance +{}, goods gone worth {}, bridge lines +{}, fell back {}", sale, balance() - b0,
                    goodsValueGone(before), TradePostBridge.logLines() - lines, MarketStalls.bridge().fellBack());
            p.check("H26 M1: the colony gains no money without a good consumed (the bridge falls back with one WARN line)", balance() - b0 <= 0
                    || goodsValueGone(before) > 0 && balance() - b0 <= goodsValueGone(before) + 1, "balance +" + (balance() - b0) + ", goods gone worth "
                    + goodsValueGone(before));
            p.checkEq("H26 M1: one bridge line and the bridge off", (TradePostBridge.logLines() - lines) + " " + MarketStalls.bridge().fellBack(), "1 true");
            final MarketStalls.Sale after = MarketStalls.sellOne(p.level, p.colony, sc.square, p.hut, p.colony.getDay());
            p.check("H26 M1: after the fall-back nothing more is sold", after == null, String.valueOf(after));
            reconcile("M1", before, b0);
        });
        now("H26 M2: a read-back that throws after the write", () -> {
            MarketStalls.setBridge(bridge(0, true));
            final Map<Item, Integer> before = goodsNow();
            final int b0 = balance();
            final int lines = TradePostBridge.logLines();
            final MarketStalls.Sale sale = MarketStalls.sellOne(p.level, p.colony, sc.square, p.hut, p.colony.getDay());
            CsFullCycle.note("H26 M2: sale {}, balance +{}, goods gone worth {}, bridge lines +{}", sale, balance() - b0, goodsValueGone(before),
                    TradePostBridge.logLines() - lines);
            p.check("H26 M2: the colony gains no money without a good consumed", balance() - b0 <= 0 || goodsValueGone(before) == balance() - b0,
                    "balance +" + (balance() - b0) + ", goods gone worth " + goodsValueGone(before));
            p.checkEq("H26 M2: one bridge line and the bridge off", (TradePostBridge.logLines() - lines) + " " + MarketStalls.bridge().fellBack(), "1 true");
            reconcile("M2", before, b0);
        });
        now("H26 M3: the cap, then a stall broken and the set swapped", () -> {
            MarketStalls.setBridge(bridge(0, false));
            final Map<Item, Integer> before = goodsNow();
            final int b0 = balance();
            int sales = 0;
            while (sales < 400 && MarketStalls.sellOne(p.level, p.colony, sc.square, p.hut, p.colony.getDay()) != null) {
                sales++;
            }
            final int day = p.colony.getDay();
            final int paid = p.ledger.marketPaid(p.colonyId, day);
            final int cap = MarketMath.cap(4, MarketStalls.bridge().coinValue());
            final int smallest = prices.values().stream().mapToInt(Integer::intValue).min().orElse(0);
            p.check("H26 M3: sales stop at the day's cap", paid <= cap && paid > cap - smallest && sales > 0 && goods() > 0,
                    sales + " sales, " + paid + " of " + cap + " paid today, " + goods() + " goods left");
            reconcile("M3 to the cap", before, b0);
            // a stall cell broken: three stand, the cap is three coins, and what was paid is more than that already
            final BlockPos stallCell = firstStallCell();
            if (stallCell != null) {
                p.level.destroyBlock(stallCell, false);
            }
            final MarketStalls.Sale broken = MarketStalls.sellOne(p.level, p.colony, sc.square, p.hut, day);
            order(sc.square, SquareSets.Order.BARE);
            decorate(sc.square);
            order(sc.square, SquareSets.Order.FOLLOW);
            decorate(sc.square);
            final MarketStalls.Sale swapped = MarketStalls.sellOne(p.level, p.colony, sc.square, p.hut, day);
            p.check("H26 M3: nothing more that colony day after a stall was broken and the set swapped", broken == null && swapped == null
                    && p.ledger.marketPaid(p.colonyId, day) == paid, "broken " + broken + ", swapped " + swapped + ", paid " + p.ledger.marketPaid(p.colonyId, day));
            p.daily("H26 M3");
        });
        now("H26 M5: the stalls taken down and put up five times", () -> {
            final int[] mats0 = mats();
            for (int i = 0; i < 5; i++) {
                order(sc.square, SquareSets.Order.BARE);
                decorate(sc.square);
                order(sc.square, SquareSets.Order.FOLLOW);
                decorate(sc.square);
            }
            final int standing = MarketStalls.standing(p.level, p.ledger, p.colonyId, sc.square, sc.anchor);
            final int[] mats1 = mats();
            p.check("H26 M5: five take-downs and put-ups: the four stalls stand, their materials are where they were", standing == 4
                    && java.util.Arrays.equals(mats0, mats1), standing + " stalls, materials " + java.util.Arrays.toString(mats0) + " -> "
                    + java.util.Arrays.toString(mats1));
            p.daily("H26 M5");
        });
        now("H26 M6: an order on the square", () -> repair(sc.square));
        waitFor("H26 M6: the watch takes the square down", DecorEvents.SQUARE_WATCH * 3, () -> size(sc.square, false) == 0, () -> {
            final Map<Item, Integer> before = goodsNow();
            final int b0 = balance();
            final int paid0 = p.ledger.marketPaid(p.colonyId, p.colony.getDay() + 1);
            final MarketStalls.Sale sale = MarketStalls.sellOne(p.level, p.colony, sc.square, p.hut, p.colony.getDay() + 1);
            p.check("H26 M6: no sale while the square is busy (its stalls are down), not even on a fresh colony day", sale == null
                    && balance() == b0 && p.ledger.marketPaid(p.colonyId, p.colony.getDay() + 1) == paid0, String.valueOf(sale));
            reconcile("M6", before, b0);
            close(sc.square);
            decorate(sc.square);
            p.daily("H26 M6 closed");
        });
    }

    private Item itemAtStall = Items.AIR;

    private BlockPos firstStallCell() {
        final DecorLedger.GroupView g = sc.set();
        if (g == null) {
            return null;
        }
        for (final BlockPos c : MarketStalls.cells(sc.square, sc.anchor)) {
            final DecorLedger.Entry e = g.entries().get(c);
            if (e != null && !e.paid()) {
                return c;
            }
        }
        for (final BlockPos c : MarketStalls.cells(sc.square, sc.anchor)) {
            final DecorLedger.Entry e = g.entries().get(c);
            if (e != null) {
                // a paid cell: the player who breaks it keeps its item (destroyBlock without drops: held by the probe)
                itemAtStall = DecorPlacer.itemOf(e.state());
                p.held.merge(itemAtStall, 1, Integer::sum);
                return c;
            }
        }
        return null;
    }

    private int[] mats() {
        return new int[] {held(Items.STRIPPED_SPRUCE_LOG), held(Items.SPRUCE_SLAB), held(Items.RED_WOOL), held(Items.WHITE_WOOL)};
    }

    /** After a money leg: what left the colony as goods is worth exactly what the colony was paid (within the leg). */
    private void moneyCheck(final String leg, final Map<Item, Integer> before, final int b0) {
        p.checkEq("H26 " + leg + ": the goods that left are worth what the colony gained", goodsValueGone(before), balance() - b0);
        sold(before);
        p.daily("H26 " + leg);
    }

    /** The goods a sale took left the colony for good: the start counts of I3 follow them. */
    private void sold(final Map<Item, Integer> before) {
        for (final Item item : prices.keySet()) {
            final int gone = before.getOrDefault(item, 0) - held(item);
            if (gone != 0) {
                p.start.merge(item, -gone, Integer::sum);
            }
        }
    }

    /** As {@link #moneyCheck}, allowing the one point a wrong write adds (M1) once a good was consumed. */
    private void reconcile(final String leg, final Map<Item, Integer> before, final int b0) {
        final int gone = goodsValueGone(before);
        final int gained = balance() - b0;
        p.check("H26 " + leg + ": every point the colony gained is backed by a good it gave (at most the bad write's one point more)",
                gained <= 0 && gone == 0 || gone > 0 && gained >= gone && gained <= gone + 1, "gained " + gained + ", goods gone worth " + gone);
        sold(before);
        p.daily("H26 " + leg);
    }

    // ================================================================ H20 set swaps

    private Item logItem = Items.AIR;

    private void planSwaps() {
        now("H20: Bare, Always Winter, Follow with the tree half up", () -> {
            final List<String> seen = new ArrayList<>();
            order(sc.square, SquareSets.Order.BARE);
            seen.add("bare " + decorate(sc.square) + " -> " + size(sc.square, false));
            // half the leaves out of the Workshop: the tree goes up half
            final int leaves = held(Items.SPRUCE_LEAVES);
            final int keep = leaves - (leaves / 2 + 40);
            final int[] out = {0};
            extract(Items.SPRUCE_LEAVES, Math.max(0, leaves - keep), out);
            p.held.merge(Items.SPRUCE_LEAVES, out[0], Integer::sum);
            order(sc.square, SquareSets.Order.ALWAYS_WINTER);
            seen.add("always " + decorate(sc.square) + " -> " + size(sc.square, false));
            final DecorLedger.GroupView half = sc.set();
            final boolean wasHalf = half != null && half.decoratedDay() < 0 && !half.entries().isEmpty();
            order(sc.square, SquareSets.Order.BARE);
            seen.add("bare " + decorate(sc.square) + " -> " + size(sc.square, false));
            order(sc.square, SquareSets.Order.FOLLOW);
            seen.add("follow " + decorate(sc.square) + " -> " + size(sc.square, false));
            insert(Items.SPRUCE_LEAVES, out[0]);
            p.held.merge(Items.SPRUCE_LEAVES, -out[0], Integer::sum);
            seen.add("follow, leaves back " + decorate(sc.square) + " -> " + size(sc.square, false));
            final DecorLedger.GroupView g = sc.set();
            p.check("H20: swaps within one game day with the tree half up: the tree complete again at the end", wasHalf && g != null && g.decoratedDay() >= 0,
                    String.join("; ", seen));
            p.daily("H20 swaps");
        });
        now("H20: a paid piece broken and kept between the take-down and the put-up", () -> {
            final DecorLedger.Owner own = DecorLedger.Owner.square(sc.anchor);
            DecorPlacer.remove(p.level, p.ledger, p.colonyId, own, stock(), 60);    // the swap's take-down, a slice of it
            BlockPos log = null;
            final DecorLedger.GroupView g = sc.set();
            if (g != null) {
                for (final Map.Entry<BlockPos, DecorLedger.Entry> e : g.entries().entrySet()) {
                    if (log == null && e.getValue().paid() && "minecraft".equals(BuiltInRegistries.BLOCK.getKey(e.getValue().state().getBlock()).getNamespace())) {
                        log = e.getKey();
                        logItem = DecorPlacer.itemOf(e.getValue().state());
                    }
                }
            }
            if (log != null) {
                p.level.setBlock(log, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                p.held.merge(logItem, 1, Integer::sum);
            }
            DecorPlacer.remove(p.level, p.ledger, p.colonyId, own, stock(), Integer.MAX_VALUE);
            p.check("H20: the broken paid cell is forgotten, not refunded (no dupe)", log != null && p.ledger.at(log) == null, log + " " + logItem);
            p.daily("H20 broken between");
            // the player gives it back to the Workshop: the next put-up finds it
            if (log != null) {
                insert(logItem, 1);
                p.held.merge(logItem, -1, Integer::sum);
            }
            decorate(sc.square);
            p.daily("H20 put up again");
        });
        now("H20: the Workshop full when the pieces come back", () -> {
            final IItemHandler store = p.hut.getItemHandlerCap();
            int junk = 0;
            while (junk < 64 * 200 && ItemHandlerHelper.insertItemStacked(store, new ItemStack(Items.COBBLESTONE, 64), false).isEmpty()) {
                junk += 64;
            }
            ItemHandlerHelper.insertItemStacked(store, new ItemStack(Items.COBBLESTONE, 64), false);
            order(sc.square, SquareSets.Order.BARE);
            final String status = decorate(sc.square);
            final int dropped = drops();
            p.check("H20: with the Workshop full the pieces that come back lie at the hut (none lost, none doubled)", dropped > 0, dropped + " items dropped, "
                    + status);
            p.daily("H20 Workshop full");
            // tidy: the junk out, the dropped pieces back in
            extract(Items.COBBLESTONE, Integer.MAX_VALUE, new int[1]);
            for (final ItemEntity e : p.level.getEntitiesOfClass(ItemEntity.class, new AABB(p.hut.getPosition()).inflate(8))) {
                ItemHandlerHelper.insertItemStacked(store, e.getItem().copy(), false);
                e.discard();
            }
            order(sc.square, SquareSets.Order.FOLLOW);
            decorate(sc.square);
            p.daily("H20 tidied");
        });
    }

    private int drops() {
        int n = 0;
        for (final ItemEntity e : p.level.getEntitiesOfClass(ItemEntity.class, new AABB(p.hut.getPosition()).inflate(8))) {
            n += e.getItem().getCount();
        }
        return n;
    }

    private void extract(final Item item, final int max, final int[] got) {
        final IItemHandler store = p.hut.getItemHandlerCap();
        for (int slot = 0; slot < store.getSlots() && got[0] < max; slot++) {
            final ItemStack s = store.getStackInSlot(slot);
            if (s.is(item)) {
                got[0] += store.extractItem(slot, Math.min(s.getCount(), max - got[0]), false).getCount();
            }
        }
    }

    // ================================================================ H21 ghosts on upgrade

    private final List<BlockPos> cleared = new ArrayList<>();

    private void planGhosts() {
        now("H21: an order while a take-down is half done, cells cleared before the drain", () -> {
            final DecorLedger.Owner own = DecorLedger.Owner.square(sc.anchor);
            order(sc.square, SquareSets.Order.BARE);
            DecorPlacer.remove(p.level, p.ledger, p.colonyId, own, stock(), 50);
            repair(sc.square);
            // the Builder's CLEAR_NON_SOLIDS: listed cells set to air before our drain runs (a paid one's item is gone with it)
            final DecorLedger.GroupView g = sc.set();
            if (g != null) {
                for (final Map.Entry<BlockPos, DecorLedger.Entry> e : g.entries().entrySet()) {
                    if (cleared.size() < 6 && e.getValue().paid()) {
                        cleared.add(e.getKey());
                    }
                }
            }
            for (final BlockPos c : cleared) {
                final Item item = DecorPlacer.itemOf(p.ledger.at(c).entry().state());
                p.level.setBlock(c, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                p.start.merge(item, -1, Integer::sum);      // the Builder took it: not ours to give back
            }
        });
        waitFor("H21: the watch drains the rest", DecorEvents.SQUARE_WATCH * 3, () -> size(sc.square, false) + size(sc.square, true) == 0, () -> {
            p.check("H21: no entry left in the square's box, only the cleared items missing (I3), none doubled, the anchor unchanged",
                    sc.entriesIn(sc.square) == 0 && sc.anchor.equals(Squares.treeCell(sc.square)) && cleared.size() == 6
                            && p.ledger.squareMark(p.colonyId) != null && p.ledger.squareMark(p.colonyId).anchor().equals(sc.anchor),
                    sc.entriesIn(sc.square) + " entries in the box, " + cleared.size() + " cleared");
            p.daily("H21 drained");
            close(sc.square);
            order(sc.square, SquareSets.Order.FOLLOW);
            final String status = decorate(sc.square);
            p.check("H21: after the order closed the plaza is whole (I8) and the set goes up again", sc.plazaDiff().isEmpty() && sc.set() != null
                    && sc.set().decoratedDay() >= 0, sc.plazaDiff().size() + " plaza blocks differ, " + status);
            p.daily("H21 closed");
        });
    }

    // ================================================================ H22 an order in the hours

    private void planHours() {
        for (final boolean dusk : List.of(false, true)) {
            final String when = dusk ? "the 31 Dec dusk" : "the gathering hours";
            final int[] rockets = new int[1];
            now("H22: an order in " + when, () -> {
                p.setDate(dusk ? EVE : FESTIVAL_DAY);
                p.setTimeOfDay(dusk ? Fireworks.DUSK_FROM + 100 : Gathering.hours()[0] + 500);
                decorate(sc.square);
                rockets[0] = p.rocketsInStock();
                repair(sc.square);
            });
            final int[] ticks = new int[1];
            final boolean[] up = new boolean[1];
            p.step(new CsFullCycle.Step("H22: the watch in " + when, DecorEvents.SQUARE_WATCH + 3, true) {
                @Override
                boolean tick() {
                    ticks[0] = t;
                    up[0] |= p.rocketsInStock() > rockets[0];
                    return size(sc.square, false) + size(sc.square, true) == 0;
                }

                @Override
                void end(final boolean done) {
                    p.check("H22: in " + when + " the square's groups are down within " + (DecorEvents.SQUARE_WATCH + 3) + " ticks, the rockets never rise,"
                            + " the marker not used", done && !up[0] && p.ledger.group(p.colonyId, DecorLedger.Owner.square(realMarker)) == null
                            && !realMarker.equals(Gathering.site(p.level, p.colony)), "after " + ticks[0] + " ticks, site " + Gathering.site(p.level, p.colony));
                    close(sc.square);
                    decorate(sc.square);
                    p.daily("H22 " + when);
                }
            });
        }
        now("H22: back to 10 Dec", () -> {
            p.setDate(DECOR_DAY);
            p.setTimeOfDay(CsFullCycle.MORNING);
        });
    }

    // ================================================================ H23 two squares

    private void planTwoSquares() {
        final int[] notices = new int[2];
        now("H23: a second square of the same level", () -> {
            notices[0] = Squares.secondSquareNotices();
            notices[1] = Squares.markerUnusedNotices();
            final BlockPos plot = PLOT_2.offset(0, p.ground, 0);
            final BlockPos hutAt = sc.paste(plot, 5);
            IBuilding built = hutAt == null ? null : p.colony.getServerBuildingManager().getBuilding(hutAt);
            if (built == null && hutAt != null && p.level.getBlockEntity(hutAt) instanceof com.minecolonies.api.tileentities.AbstractTileEntityColonyBuilding te) {
                built = p.colony.getServerBuildingManager().addNewBuilding(te, p.level);
            }
            s2 = built instanceof BuildingTownSquare s ? s : null;
            if (s2 == null) {
                p.check("H23: the second square registers", false, String.valueOf(built));
                return;
            }
            s2.setCorners(plot, plot.offset(20, 19, 20));
            s2.setBuildingLevel(5);
            s2.setStructurePack(SquareCycle.PACK);
            s2.setBlueprintPath("square/townsquare5.blueprint");
            s2.assignment().setHiringMode(com.minecolonies.api.colony.buildings.HiringMode.MANUAL);
            CsFullCycle.note("H23: the second square at {}, in the claim {}", s2.getPosition().toShortString(), p.claimed(plot.offset(20, 1, 20)));
        });
        waitFor("H23: its tags read", 200, () -> s2 == null || Squares.treeCell(s2) != null, () -> {
            if (s2 == null) {
                return;
            }
            DecorEvents.sweep(p.level);
            final Squares.Square s = Squares.refresh(p.level, p.colony, p.ledger);
            p.check("H23: a second square of equal level: the first keeps the tree, one notice", s.building().equals(sc.square.getPosition())
                    && Squares.secondSquareNotices() - notices[0] <= 1 && size(s2, false) == 0, "main " + s.building().toShortString() + ", "
                    + (Squares.secondSquareNotices() - notices[0]) + " notices");
            CsFullCycle.note("H23: second-square notices on placement: {}", Squares.secondSquareNotices() - notices[0]);
            p.daily("H23 a tie");
        });
        now("H23: the second square raised above the first: the tree moves once", () -> {
            sc.square.setBuildingLevel(4);
            DecorEvents.squareChanged(sc.square);
            DecorEvents.drain(p.server);
            DecorEvents.sweep(p.level);
            final String status = decorate(s2);
            final Squares.Square s = Squares.of(p.level, p.colony, p.ledger);
            p.check("H23: the higher square is the main one, the lower's groups are down, its set up on the higher", s.building().equals(s2.getPosition())
                    && size(sc.square, false) + size(sc.square, true) == 0 && size(s2, false) > 0, status);
            // M4: a second main square after the cap: the colony's paid record is the colony's, not the square's
            final MarketStalls.Sale sale = MarketStalls.sellOne(p.level, p.colony, s2, p.hut, p.colony.getDay());
            p.check("H26 M4: a second main square after the cap sells nothing that colony day", sale == null, String.valueOf(sale));
            p.daily("H23 raised");
        });
        now("H23: both squares busy", () -> {
            repair(sc.square);
            repair(s2);
        });
        waitFor("H23: both busy, nothing hosted anywhere", DecorEvents.SQUARE_WATCH * 3, () -> size(s2, false) + size(s2, true) == 0, () -> {
            p.check("H23: both busy: no group up anywhere, the marker not used", size(s2, false) + size(sc.square, false) == 0
                    && p.ledger.group(p.colonyId, DecorLedger.Owner.square(realMarker)) == null, "");
            p.daily("H23 both busy");
            close(sc.square);
            close(s2);
        });
        now("H23: the Decorator listed on the lower square only, then on none", () -> {
            p.check("H23: the Decorator listed on the lower square works the main one (as built)",
                    DecorWork.centrepiece(p.level, p.colony, p.ledger, sc.square) != null
                            && DecorWork.centrepiece(p.level, p.colony, p.ledger, sc.square).building() == s2, "");
            p.check("H23: listed on no square he works none", DecorWork.centrepiece(p.level, p.colony, p.ledger, null) == null, "");
        });
        now("H23: the main square's hut broken", () -> {
            decorate(s2);
            final BlockPos hut = s2.getPosition();
            final int unused = Squares.markerUnusedNotices();
            s2.destroy();
            p.level.setBlock(hut, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            DecorEvents.drain(p.server);
            final Squares.Square s = Squares.refresh(p.level, p.colony, p.ledger);
            p.check("H23: the main hut broken: its groups gone at once, the first square main again, no new marker notice",
                    s.source() == Squares.Source.BUILDING && s.building().equals(sc.square.getPosition()) && Squares.markerUnusedNotices() == unused
                            && p.ledger.groups(p.colonyId).stream().noneMatch(g -> g.owner().pos().equals(Squares.treeCell(s2)) && !g.entries().isEmpty()),
                    String.valueOf(s));
            sc.square.setBuildingLevel(5);
            DecorEvents.squareChanged(sc.square);
            DecorEvents.drain(p.server);
            decorate(sc.square);
            p.daily("H23 hut broken");
        });
    }

    // ================================================================ H24 foreign claim, H25 marks and claims

    private BlockPos markCell;
    private BuildingTownSquare s3;
    private int aBorder;

    private void planForeign() {
        now("H24: colony B next door, the claims touching; H25: a mark of A near the border", () -> {
            for (int cx = 3; cx <= 14; cx++) {
                for (int cz = -6; cz <= 6; cz++) {
                    p.level.setChunkForced(cx, cz, true);
                }
            }
            final FakePlayer o = FakePlayerFactory.get(p.level, new GameProfile(UUID.nameUUIDFromBytes("hostile-square-b".getBytes()), "ownerhsb"));
            b = IColonyManager.getInstance().createColony(p.level, new BlockPos(146, p.ground + 1, 2), o, "Seasons Hostile Square B", "Colonial");
            p.check("H24: colony B was made next door", b != null, b == null ? "-" : "id " + b.getID());
            if (b == null) {
                return;
            }
            p.register(b, "minecolonies:blockhuttownhall", p.house(141, -1, 11, 7, 146), 1);
            aBorder = lastOf(p.colonyId, 30);
            CsFullCycle.note("H24: A's claim reaches x {} at z 30, B's starts at x {}", aBorder, firstOf(b.getID(), 30));
            markCell = new BlockPos(aBorder - 3, p.ground + 1, 10);
            final ItemStack wand = new ItemStack(SeasonsBlocks.DECORATORS_WAND.get());
            DecoratorWandItem.setMode(wand, StreetMarks.Type.SNOWMAN_A);
            final String marked = DecoratorWandItem.click(p.level, p.owner, wand, markCell.below(), Direction.UP, false);
            p.checkEq("H25: A's manager marks a spot in A's claim", marked, "com.colony_seasons.wand.marked");
            final BlockPos inB = new BlockPos(firstOf(b.getID(), 10) + 4, p.ground + 1, 10);
            final String refused = DecoratorWandItem.click(p.level, p.owner, wand, inB.below(), Direction.UP, false);
            p.check("H25: A's manager clicking inside B's claim is refused (the cell is B's: no right to manage there), no mark for A or B",
                    refused != null && refused.startsWith("com.colony_seasons.wand.refused") && p.ledger.markAt(p.colonyId, inB) == null
                            && p.ledger.markAt(b.getID(), inB) == null, String.valueOf(refused));
            final StreetMarks.Mark mark = p.ledger.markAt(p.colonyId, markCell);
            if (mark != null) {
                DecorWork.decorate(p.level, p.colonyId, me.lovkar.seasons.decor.StreetWork.target(p.level, mark, null), CsFullCycle.HUT_LEVEL, stock(),
                        p.day(), p.ledger);
            }
            final DecorLedger.GroupView g = p.ledger.group(p.colonyId, DecorLedger.Owner.street(markCell));
            p.check("H25: the mark's snowman stands", g != null && !g.entries().isEmpty(), g == null ? "no group" : g.entries().size() + " entries");
        });
        now("H24: a square of A whose box reaches into B's claim", () -> {
            if (b == null) {
                return;
            }
            final BlockPos plot = new BlockPos(aBorder - 14, p.ground, 24);
            final BlockPos hutAt = sc.paste(plot, 5);
            IBuilding built = hutAt == null ? null : p.colony.getServerBuildingManager().getBuilding(hutAt);
            if (built == null && hutAt != null && p.level.getBlockEntity(hutAt) instanceof com.minecolonies.api.tileentities.AbstractTileEntityColonyBuilding te) {
                built = p.colony.getServerBuildingManager().addNewBuilding(te, p.level);
            }
            s3 = built instanceof BuildingTownSquare s ? s : null;
            p.check("H24: the square registers in A (its hut in A's claim)", s3 != null && p.claimed(hutAt), String.valueOf(hutAt));
            if (s3 == null) {
                return;
            }
            s3.setCorners(plot, plot.offset(20, 19, 20));
            s3.setBuildingLevel(5);
            s3.setStructurePack(SquareCycle.PACK);
            s3.setBlueprintPath("square/townsquare5.blueprint");
            s3.assignment().setHiringMode(com.minecolonies.api.colony.buildings.HiringMode.MANUAL);
            sc.square.setBuildingLevel(4);
            DecorEvents.squareChanged(sc.square);
        });
        waitFor("H24: its tags read", 200, () -> s3 == null || Squares.treeCell(s3) != null, () -> {
            if (s3 == null) {
                return;
            }
            DecorEvents.drain(p.server);
            DecorEvents.sweep(p.level);
            final int notices0 = DecorWork.notices();
            final List<String> outcomes = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                outcomes.add(decorate(s3));
            }
            final BlockPos a3 = Squares.treeCell(s3);
            int inB = 0;
            int stallsInA = 0;
            for (final BlockPos stall : s3.getLocationsFromTag(BuildingTownSquare.TAG_STALL)) {
                boolean all = true;
                for (final BlockPos c : MarketStalls.footprint(stall, a3)) {
                    all &= DecorPlacer.colonyAt(p.level, c) == p.colonyId;
                }
                stallsInA += all ? 1 : 0;
            }
            for (final BlockPos pos : p.ledger.entries(p.colonyId).keySet()) {
                inB += DecorPlacer.colonyAt(p.level, pos) == b.getID() ? 1 : 0;
            }
            final int standing = MarketStalls.standing(p.level, p.ledger, p.colonyId, s3, a3);
            CsFullCycle.note("H24: the square across the border: {}; {} entries of A, {} stalls wholly in A, {} standing, {} no-room notices",
                    outcomes, p.ledger.count(p.colonyId), stallsInA, standing, DecorWork.notices() - notices0);
            p.check("H24: no entry of A in B's claim, B lists none, a stall with a cell in B never stands, no-room told at most once a day",
                    inB == 0 && p.ledger.count(b.getID()) == 0 && standing <= stallsInA && DecorWork.notices() - notices0 <= 1,
                    inB + " in B, " + standing + " standing of " + stallsInA + " wholly in A, " + (DecorWork.notices() - notices0) + " notices");
            final Map<BlockPos, DecorLedger.Entry> entriesA = new LinkedHashMap<>(p.ledger.entries(p.colonyId));
            DecorEvents.sweep(p.level);
            final CommandReplies replies = new CommandReplies();
            p.server.getCommands().performPrefixedCommand(p.owner.createCommandSourceStack().withSource(replies).withPermission(4),
                    "colonyseasons decor remove " + b.getID());
            p.check("H24: B's sweep and decor remove for B leave A's entries as they were", p.ledger.entries(p.colonyId).equals(entriesA),
                    entriesA.size() + " -> " + p.ledger.entries(p.colonyId).size() + ", " + replies.said);
            p.daily("H24 across the border");
            // the square goes again, the first one is the main square again
            final BlockPos hut = s3.getPosition();
            s3.destroy();
            p.level.setBlock(hut, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            DecorEvents.drain(p.server);
            sc.square.setBuildingLevel(5);
            DecorEvents.squareChanged(sc.square);
            DecorEvents.drain(p.server);
            decorate(sc.square);
            p.daily("H24 back");
        });
        now("H25: the mark's chunk leaves A's claim", () -> {
            if (markCell == null || p.ledger.markAt(p.colonyId, markCell) == null) {
                p.check("H25: A's mark exists", false, "");
                return;
            }
            final int marks0 = p.ledger.marks(p.colonyId).size();
            // the colony's claim shrinks (MineColonies' own unclaim of one chunk, what an admin's claim command does)
            com.minecolonies.core.util.ChunkDataHelper.staticClaimInRange((com.minecolonies.core.colony.Colony) p.colony, false, markCell, 0, p.level, true);
            final int now = DecorPlacer.colonyAt(p.level, markCell);
            CsFullCycle.note("H25: the mark's cell is now claimed by {} (A is {}, B is {})", now, p.colonyId, b == null ? -1 : b.getID());
            if (now == p.colonyId) {
                finding("H25", "tech", "MineColonies' unclaim of the mark's chunk did not take: the leg could not move the claim");
                return;
            }
            DecorEvents.sweep(p.level);
            DecorEvents.sweep(p.level);
            final DecorLedger.GroupView after = p.ledger.group(p.colonyId, DecorLedger.Owner.street(markCell));
            p.check("H25: a mark whose cell left the claim: its group down, the mark gone, the cap count down by one", (after == null
                    || after.entries().isEmpty()) && p.ledger.markAt(p.colonyId, markCell) == null && p.ledger.marks(p.colonyId).size() == marks0 - 1,
                    "group " + (after == null ? "-" : after.entries().size()) + ", mark " + p.ledger.markAt(p.colonyId, markCell) + ", marks " + marks0
                            + " -> " + p.ledger.marks(p.colonyId).size());
            p.daily("H25");
        });
    }

    private int lastOf(final int colony, final int z) {
        int last = Integer.MIN_VALUE;
        for (int x = 20; x <= 200; x++) {
            if (DecorPlacer.colonyAt(p.level, new BlockPos(x, p.ground + 1, z)) == colony) {
                last = Math.max(last, x);
            }
        }
        return last;
    }

    /** Chat lines a command sends back (their lang keys). */
    private static final class CommandReplies implements net.minecraft.commands.CommandSource {
        final List<String> said = new ArrayList<>();

        @Override
        public void sendSystemMessage(final net.minecraft.network.chat.Component component) {
            said.add(component.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t ? t.getKey() : component.getString());
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }
    }

    private int firstOf(final int colony, final int z) {
        for (int x = 20; x <= 200; x++) {
            if (DecorPlacer.colonyAt(p.level, new BlockPos(x, p.ground + 1, z)) == colony) {
                return x;
            }
        }
        return Integer.MAX_VALUE;
    }

    // ================================================================ H27 a touch pumpkin carved

    private void planCarve() {
        waitFor("H27: the autumn touch's piece loads", 400, () -> PieceLoader.request(p.level, PieceLoader.PACK,
                SquareSets.TOUCH_PREFIX + "autumn.blueprint", Direction.NORTH) != null, () -> {
        });
        now("H27: autumn between the windows: the touches", () -> {
            p.setDate(AUTUMN_DAY);
            CalendarManager.setSeason(p.server, Season.AUTUMN);
            p.newGameDay(CsFullCycle.MORNING);
            decorate(sc.square);                            // the festival's set comes down first (no set is wanted now)
            for (int i = 0; i < 4; i++) {
                final DecorWork.Target t = DecorWork.nextTouches(p.level, p.colony, p.ledger, sc.square, p.day(), true, sc.anchor);
                if (t == null) {
                    break;
                }
                DecorWork.touch(p.level, p.colonyId, t, p.day(), p.ledger, true);
            }
            p.check("H27: the autumn touch stands on every bed", sc.touchesUp() == null, String.valueOf(sc.touchesUp()));
        });
        now("H27: a player carves one with shears, every game day", () -> {
            final List<BlockPos> beds = DecorWork.bedCells(sc.square);
            final BlockPos bed = beds.get(0);
            final FakePlayer w = p.p1;
            final Map<Item, Integer> got = new LinkedHashMap<>();
            final List<String> days = new ArrayList<>();
            for (int day = 0; day < 3; day++) {
                final BlockState state = p.level.getBlockState(bed);
                final ItemStack shears = new ItemStack(Items.SHEARS);
                w.setItemInHand(InteractionHand.MAIN_HAND, shears);
                final BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(bed), Direction.NORTH, bed, false);
                state.useItemOn(shears, p.level, w, InteractionHand.MAIN_HAND, hit);
                final BlockState carved = p.level.getBlockState(bed);
                p.level.destroyBlock(bed, true, w);                    // and breaks the carved one
                int seeds = 0;
                for (final ItemEntity e : p.level.getEntitiesOfClass(ItemEntity.class, new AABB(bed).inflate(3))) {
                    got.merge(e.getItem().getItem(), e.getItem().getCount(), Integer::sum);
                    seeds += e.getItem().getCount();
                    e.discard();
                }
                days.add(CsFullCycle.shortName(state) + " -> " + CsFullCycle.shortName(carved) + ", " + seeds + " items");
                // the next game day's touch visit
                p.newGameDay(CsFullCycle.MORNING);
                final DecorWork.Target t = DecorWork.nextTouches(p.level, p.colony, p.ledger, sc.square, p.day(), true, sc.anchor);
                if (t != null) {
                    DecorWork.touch(p.level, p.colonyId, t, p.day(), p.ledger, true);
                }
            }
            final StringBuilder text = new StringBuilder();
            for (final Map.Entry<Item, Integer> e : got.entrySet()) {
                text.append(e.getValue()).append(' ').append(BuiltInRegistries.ITEM.getKey(e.getKey()).getPath()).append(", ");
            }
            finding("H27", "fyi", "a player who carves a planter's autumn pumpkin with shears and breaks it gets, per bed and game day: {} ({}); the "
                    + "touches are free, so this is a small farm of pumpkins and seeds from the square's beds; no code changed", text, String.join("; ", days));
            p.check("H27: measured (the touches stay the Decorator's own: every entry equals the world after the next visit)",
                    p.ledger.at(bed) == null || p.ledger.at(bed).entry().state() == p.level.getBlockState(bed), String.valueOf(p.ledger.at(bed)));
            p.daily("H27");
        });
    }
}
