package me.lovkar.seasons.csfullcycle;

import com.ldtteam.structurize.api.RotationMirror;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.placement.AbstractBlueprintIterator;
import com.ldtteam.structurize.placement.StructurePhasePlacementResult;
import com.ldtteam.structurize.placement.StructurePlacer;
import com.ldtteam.structurize.placement.structure.CreativeStructureHandler;
import com.ldtteam.structurize.storage.StructurePacks;
import com.ldtteam.structurize.util.BlockInfo;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.HiringMode;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.tileentities.AbstractTileEntityColonyBuilding;
import me.lovkar.seasons.block.FestivalMarkerBlock;
import me.lovkar.seasons.block.GiftPileBlock;
import me.lovkar.seasons.block.SeasonsBlocks;
import me.lovkar.seasons.calendar.CalendarManager;
import me.lovkar.seasons.colony.BuildingTownSquare;
import me.lovkar.seasons.colony.JobDecorator;
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
import me.lovkar.seasons.festival.Fireworks;
import me.lovkar.seasons.festival.Gathering;
import me.lovkar.seasons.festival.Gifts;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * Q7 (docs/town-square-design.md section 6): the 0.1.0 full cycle on a Town Square, and the modes that look for what the
 * chunk tests missed. The square is the real blueprint {@code square/townsquare<N>.blueprint} of our pack pasted through
 * Structurize's creative handler and registered (the recipe of cstradepost's {@code pasteSquare} and {@code planSquare}),
 * its Decorator assigned on MANUAL; the Decorator's own AI does all the work. On top of the 0.1.0 invariants I1 to I4 a
 * square mode checks after every game day: I5 one centrepiece (no square or touches group with entries anywhere but at the
 * resolver's anchor; both empty while the square is busy); I6 the fixtures (one gift pile at the {@code gift} cell, one
 * crate at the {@code launch} cell, besides the piles of the tree's own piece); I7 money (with the market: the goods that
 * left the colony times their prices equal the balance gained and the paid record, no tracked item above its start; without
 * it the balance never moves and the bridge logs nothing); I8 the plaza (no block of the square's blueprint missing or
 * replaced). The end check replaces "the world equals the snapshot": every differing cell is listed in the square's touches.
 *
 * <p>Modes: {@code square-building} (the cycle on a level 5 square, order Follow), {@code trade-post} (the same with the
 * Trade Post jar: the stalls and their money), {@code wand} ({@link StreetLegs}: marked streets through the cycle),
 * {@code migrate-marker} (a 0.1.0 colony with its marker tree up gets a square on 8 Dec), {@code touches} (the planters'
 * touches across seasons and orders), {@code builder-over-tree} ({@link BuilderLeg}); the restart sets
 * {@code square-r1} to {@code square-r7} and {@code migrate-save}, {@code migrate-load} (tools/restart.sh csfullcycle
 * square|migrate).</p>
 */
final class SquareCycle {
    static final String PACK = PieceLoader.PACK;
    /** The square's box's low corner (y: the ground's own layer): x -8..12, z 22..42, clear of every building. */
    static final BlockPos PLOT = new BlockPos(-8, 0, 22);
    /** The migrate-marker square: x 24..44, z 22..42, clear of the marker's tree at (2, 32). */
    static final BlockPos MIGRATE_PLOT = new BlockPos(24, 0, 22);
    /** Goods of each priced kit above its reserve the Workshop holds for the market (trade-post). */
    static final int SPARE_GOODS = 6;
    static final LocalDate DEC_1 = LocalDate.of(2026, 12, 1);

    private final CsFullCycle p;
    final String mode;
    BuildingTownSquare square;
    BlockPos anchor;
    BlockPos plot;
    int squareLevel;
    Blueprint blueprint;
    /** trade-post: the market's goods are counted by I7, not I3. */
    final boolean market;
    /** hostile-square: its money legs check the balance themselves (a bridge is on there without the market goods counted). */
    boolean skipMoney;
    int balance0 = Integer.MIN_VALUE;
    int logLines0;
    final Map<Item, Integer> prices = new LinkedHashMap<>();
    /** The balance after the last game day (trade-post: it may move only on the gathering days). */
    int lastBalance = Integer.MIN_VALUE;
    /** Crowd samples on the gathering days: [samples of colonists standing still near the square, those on a gather spot]. */
    final int[] crowd = new int[2];
    final StreetLegs street;
    /** What a restart phase saves beyond the facts of {@link #saveFacts} (the dusk's stock, ...). */
    final CompoundTag extra = new CompoundTag();

    SquareCycle(final CsFullCycle probe) {
        this.p = probe;
        this.mode = probe.mode;
        this.market = mode.equals("trade-post");
        this.street = mode.equals("wand") ? new StreetLegs(probe, this) : null;
    }

    // ================================================================ which modes

    static final Set<String> MODES = Set.of("square-building", "trade-post", "wand", "migrate-marker", "touches", "builder-over-tree",
            "hostile-square", "migrate-save", "migrate-load");

    static boolean handles(final String mode) {
        return MODES.contains(mode) || mode.matches("square-r[1-7]");
    }

    /** The colony a mode builds: a square instead of the marker, or (migrate) the 0.1.0 colony with its marker. */
    static CsFullCycle.Variant variant(final String mode) {
        if (mode.startsWith("migrate")) {
            return CsFullCycle.Variant.BASE;
        }
        return new CsFullCycle.Variant(true, false, true, false, false, true);
    }

    /** The date the calendar shows from the server's first poll on (a restart phase starts on its cut's date). */
    static LocalDate firstDate(final String mode) {
        return switch (mode) {
            case "square-r2", "square-r3" -> DEC_1;
            case "square-r4" -> LocalDate.of(2026, 12, 12);
            case "square-r5" -> LocalDate.of(2026, 12, 25);
            case "square-r6" -> LocalDate.of(2026, 12, 31);
            case "square-r7" -> LocalDate.of(2027, 1, 7);
            case "migrate-load" -> LocalDate.of(2026, 12, 8);
            case "touches" -> LocalDate.of(2027, 3, 15);
            default -> CsFullCycle.FIRST;
        };
    }

    void plan(final String mode) {
        switch (mode) {
            case "square-building", "trade-post" -> {
                p.planSetup();
                p.planDates(CsFullCycle.FIRST, CsFullCycle.LAST, false);
                p.planEnd();
            }
            case "wand" -> {
                p.planSetup();
                street.plan();
                p.planEnd();
            }
            case "migrate-marker" -> {
                p.planSetup();
                p.planDates(CsFullCycle.FIRST, LocalDate.of(2026, 12, 8), false);
                planMigrate(LocalDate.of(2026, 12, 9));
                p.planEnd();
            }
            case "touches" -> new TouchLegs(p, this).plan();
            case "builder-over-tree" -> new BuilderLeg(p, this).plan();
            default -> {
                if (!new SquareRestarts(p, this).plan(mode)) {
                    p.check("known mode", false, "'" + mode + "'");
                    p.finish();
                }
            }
        }
    }

    // ================================================================ the square

    /** Pastes the blueprint with its box's low corner at {@code at}; returns the hut block's position, or null. */
    BlockPos paste(final BlockPos at, final int lv) {
        final String file = "square/townsquare" + lv + ".blueprint";
        try {
            final Blueprint bp = StructurePacks.getBlueprintFuture(PACK, file, p.level.registryAccess()).get(60, TimeUnit.SECONDS);
            if (bp == null) {
                p.check("the square's blueprint " + file + " loads", false, "");
                return null;
            }
            bp.setRotationMirror(RotationMirror.NONE, p.level);
            final BlockPos hutAt = at.offset(bp.getPrimaryBlockOffset());
            final CreativeStructureHandler handler = new CreativeStructureHandler(p.level, hutAt, bp, RotationMirror.NONE, true);
            final StructurePlacer placer = new StructurePlacer(handler);
            int rounds = 0;
            while (rounds++ < 4000) {
                final StructurePhasePlacementResult result = placer.executeStructureStep(p.level, null, placer.getIterator().getProgressPos(),
                        StructurePlacer.Operation.BLOCK_PLACEMENT, () -> placer.getIterator().increment((info, pos, h) -> false), false);
                if (result.getIteratorPos().equals(AbstractBlueprintIterator.NULL_POS)) {
                    break;
                }
            }
            blueprint = bp;
            return hutAt;
        } catch (final Exception unreadable) {
            p.check("the square's blueprint " + file + " pastes", false, unreadable.getClass().getSimpleName().replace("Exception", ""));
            return null;
        }
    }

    /** The pasted square registered as a Town Square of the colony at {@code lv}, its Decorator assigned (MANUAL). */
    BuildingTownSquare register(final BlockPos hutAt, final BlockPos at, final int lv) {
        IBuilding building = p.colony.getServerBuildingManager().getBuilding(hutAt);
        if (building == null && p.level.getBlockEntity(hutAt) instanceof AbstractTileEntityColonyBuilding te) {
            building = p.colony.getServerBuildingManager().addNewBuilding(te, p.level);
        }
        if (!(building instanceof BuildingTownSquare built)) {
            p.check("the pasted square registers as a Town Square", false, String.valueOf(building));
            return null;
        }
        built.setCorners(at, at.offset(blueprint.getSizeX() - 1, blueprint.getSizeY() - 1, blueprint.getSizeZ() - 1));
        built.setBuildingLevel(lv);
        built.setStructurePack(PACK);
        built.setBlueprintPath("square/townsquare" + Math.max(1, lv) + ".blueprint");
        built.assignment().setHiringMode(HiringMode.MANUAL);
        built.markDirty();
        if (p.decorator != null) {
            p.check("the Decorator is assigned to the square (he stays employed at his Workshop)", built.assignment().assignCitizen(p.decorator)
                    && p.decorator.getWorkBuilding() == p.hut && p.decorator.getJob() instanceof JobDecorator j && j.findSquare() == built, "");
        }
        return built;
    }

    /** Setup, after the citizens: the level 5 square on its plot (the square modes). */
    void placeInSetup() {
        squareLevel = 5;
        plot = PLOT.offset(0, p.ground, 0);
        final BlockPos hutAt = paste(plot, squareLevel);
        square = hutAt == null ? null : register(hutAt, plot, squareLevel);
        if (square == null) {
            p.finish();
            return;
        }
        logLines0 = TradePostBridge.logLines();
        boolean inClaim = true;
        for (final BlockPos corner : corners()) {
            inClaim &= p.claimed(new BlockPos(corner.getX(), p.ground + 1, corner.getZ()));
        }
        check("the square's four corners lie in the colony's claim", inClaim, plot.toShortString());
    }

    /** The four corners of the square's box at its own height range (low and high). */
    List<BlockPos> corners() {
        final BlockPos a = square.getCorners().getA();
        final BlockPos b = square.getCorners().getB();
        return List.of(a, b, new BlockPos(a.getX(), a.getY(), b.getZ()), new BlockPos(b.getX(), b.getY(), a.getZ()));
    }

    /** True once the square's tags read: its tree cell is the probe's "marker" (the centrepiece cell) from then on. */
    boolean locate() {
        if (square == null) {
            return true;
        }
        if (anchor != null) {
            return true;
        }
        final BlockPos tree = Squares.treeCell(square);
        if (tree == null) {
            return false;
        }
        anchor = tree;
        final Squares.Square s = Squares.refresh(p.level, p.colony, p.ledger);
        check("the square is level " + squareLevel + " and the colony's main square, its anchor the tree cell", square.getBuildingLevel() == squareLevel
                && s.source() == Squares.Source.BUILDING && anchor.equals(s.anchor()) && p.ledger.squareMark(p.colonyId) != null
                && p.ledger.squareMark(p.colonyId).anchor().equals(anchor), "anchor " + anchor.toShortString() + ", " + s);
        p.marker = anchor;
        return true;
    }

    /** The box corners the first snapshot must cover (the square's and, in mode wand, the street's). */
    List<BlockPos> snapshotCorners() {
        final List<BlockPos> out = new ArrayList<>();
        if (square != null) {
            out.add(square.getCorners().getA().offset(-2, -1, -2));
            out.add(square.getCorners().getB().offset(2, 2, 2));
        }
        if (street != null) {
            out.addAll(street.corners());
        }
        return out;
    }

    DecorWork.Target target() {
        return DecorWork.Target.townSquare(square, anchor);
    }

    /** What the square's set costs on 1 Dec (the order's set, its fixtures, and with the market its stalls), added to {@code need}. */
    void addNeeds(final Map<Item, Integer> need) {
        addNeeds(need, DEC_1, CsFullCycle.FIRST);
    }

    /** As above with the calendar on {@code at} for the plan, then back on {@code back}. */
    void addNeeds(final Map<Item, Integer> need, final LocalDate at, final LocalDate back) {
        p.setDate(at);
        try {
            final DecorWork.Target t = target();
            final int lv = DecorWork.pieceLevel(t, CsFullCycle.HUT_LEVEL);
            final String path = DecorWork.piecePath(t, CsFullCycle.HUT_LEVEL);
            final PieceLoader.Piece piece = path == null ? null : PieceLoader.request(p.level, t.pack(), path, t.rotation());
            if (piece == null) {
                p.check("the square's tree piece is loaded for the stock", false, String.valueOf(path));
                return;
            }
            final PieceLoader.SquarePlan plan = PieceLoader.plan(p.level, piece, anchor, lv, p.ledger, p.colonyId, t.owner(), true,
                    DecorWork.fixtures(t, lv));
            for (final DecorPlacer.Slot slot : plan.slots()) {
                final Item item = DecorPlacer.itemOf(slot.state());
                if (item != null && slot.costs()) {
                    need.merge(item, 1, Integer::sum);
                }
            }
            final int stalls = MarketStalls.fixtures(t).size();
            CsFullCycle.note("the square's set on {}: {} ({} cells, piece {}, level {}), {} stall cells", at, CsFullCycle.text(need),
                    plan.slots().size(), path, lv, stalls);
            if (market) {
                check("the market's stalls are planned on the square (the bridge on, level 5, a festival window)", stalls > 0, stalls + " stall cells");
            }
        } finally {
            p.setDate(back);
        }
    }

    /** trade-post: goods of every priced kit above the Workshop's reserve, for the stalls to sell. */
    boolean extraStock(final IItemHandler store) {
        if (!market) {
            return true;
        }
        prices.putAll(MarketStalls.prices());
        boolean fit = true;
        for (final Kits.Kit kit : Kits.all()) {
            final Integer price = prices.get(kit.output());
            if (price == null) {
                continue;
            }
            final int n = Math.max(1, kit.count()) + SPARE_GOODS;
            fit &= ItemHandlerHelper.insertItemStacked(store, new ItemStack(kit.output(), n), false).isEmpty();
            p.start.merge(kit.output(), n, Integer::sum);
        }
        balance0 = balance();
        lastBalance = balance0;
        check("the bridge is on with Trade Post next to the mod, the prices are read", MarketStalls.bridge().active() && !prices.isEmpty(),
                prices.size() + " prices, balance " + balance0);
        return fit;
    }

    /** A good the market may sell (trade-post): I3 leaves it to I7. */
    boolean sold(final Item item) {
        return market && prices.containsKey(item);
    }

    int balance() {
        try {
            return p.colony.getStatisticsManager().getStatTotal(TradePostBridge.STAT);
        } catch (final RuntimeException unreadable) {
            return Integer.MIN_VALUE;
        }
    }

    // ================================================================ the invariants I5 to I8

    /** The lowest cell of a tag, or null. */
    BlockPos tagCell(final String tag) {
        final List<BlockPos> cells = square == null ? null : square.getLocationsFromTag(tag);
        if (cells == null || cells.isEmpty()) {
            return null;
        }
        BlockPos lowest = cells.get(0);
        for (final BlockPos c : cells) {
            if (c.compareTo(lowest) < 0) {
                lowest = c;
            }
        }
        return lowest.immutable();
    }

    void daily(final List<String> wrong, final Map<Item, Integer> now) {
        final Squares.Square s = Squares.of(p.level, p.colony, p.ledger);
        // I5 one centrepiece
        for (final DecorLedger.GroupView g : p.ledger.groups(p.colonyId)) {
            final DecorLedger.Kind kind = g.owner().kind();
            if ((kind == DecorLedger.Kind.SQUARE || kind == DecorLedger.Kind.TOUCHES) && !g.entries().isEmpty()) {
                if (s.anchor() == null || !g.owner().pos().equals(s.anchor())) {
                    wrong.add("I5 a " + kind + " group of " + g.entries().size() + " at " + g.owner().pos().toShortString() + ", the anchor is "
                            + (s.anchor() == null ? "-" : s.anchor().toShortString()));
                } else if (s.busy()) {
                    wrong.add("I5 the square is busy and its " + kind + " group lists " + g.entries().size());
                }
            }
        }
        // I6 the fixtures
        if (square != null && anchor != null) {
            final BlockPos gift = tagCell(BuildingTownSquare.TAG_GIFT);
            final BlockPos launch = tagCell(BuildingTownSquare.TAG_LAUNCH);
            int crates = 0;
            boolean giftAtCell = false;
            final DecorLedger.GroupView g = p.ledger.group(p.colonyId, DecorLedger.Owner.square(anchor));
            if (g != null) {
                for (final Map.Entry<BlockPos, DecorLedger.Entry> e : g.entries().entrySet()) {
                    if (e.getValue().state().is(SeasonsBlocks.FIREWORK_CRATE.get())) {
                        crates++;
                        if (!e.getKey().equals(launch)) {
                            wrong.add("I6 a crate at " + e.getKey().toShortString() + ", the launch cell is " + launch);
                        }
                    }
                    if (e.getValue().state().getBlock() instanceof GiftPileBlock && e.getKey().equals(gift)) {
                        giftAtCell = true;
                    }
                }
            }
            if (crates > 1) {
                wrong.add("I6 " + crates + " crates listed");
            }
            if (gift != null && g != null && !g.entries().isEmpty() && g.decoratedDay() >= 0 && !giftAtCell && festivalFixtures()) {
                wrong.add("I6 no gift pile at the gift cell " + gift.toShortString());
            }
            // I8 the plaza
            final List<BlockPos> plaza = plazaDiff();
            if (!plaza.isEmpty()) {
                wrong.add("I8 " + plaza.size() + " blocks of the square's blueprint differ, first " + plaza.get(0).toShortString() + " "
                        + CsFullCycle.shortName(p.level.getBlockState(plaza.get(0))));
            }
        }
        // I7 money
        final int b = balance();
        if (skipMoney) {
            return;
        }
        if (market) {
            int goodsValue = 0;
            for (final Map.Entry<Item, Integer> e : prices.entrySet()) {
                final int start = p.start.getOrDefault(e.getKey(), 0);
                final int got = now.getOrDefault(e.getKey(), 0);
                if (got > start) {
                    wrong.add("I7 " + BuiltInRegistries.ITEM.getKey(e.getKey()).getPath() + " " + got + " of " + start + " (more than at the start)");
                }
                goodsValue += (start - got) * e.getValue();
            }
            final int paid = paidSoFar();
            if (goodsValue != b - balance0 || paid != b - balance0) {
                wrong.add("I7 goods gone worth " + goodsValue + ", balance gained " + (b - balance0) + ", paid record " + paid);
            }
        } else if (b != Integer.MIN_VALUE && b != 0 || TradePostBridge.logLines() != logLines0) {
            wrong.add("I7 without the market the balance is " + b + " and the bridge logged " + (TradePostBridge.logLines() - logLines0) + " lines");
        }
    }

    /** The paid record summed: the ledger keeps one colony day (the newest); earlier days are added as they pass. */
    private final Map<Long, Integer> paidByDay = new LinkedHashMap<>();

    int paidSoFar() {
        final MarketMath.PaidDay day = p.ledger.market(p.colonyId);
        if (day != null && day.day() >= 0) {
            paidByDay.put(day.day(), day.paid());
        }
        int sum = 0;
        for (final int v : paidByDay.values()) {
            sum += v;
        }
        return sum;
    }

    /** The square holds the Winter Festival's fixtures now (its gift pile at the gift cell). */
    boolean festivalFixtures() {
        final CalendarManager.Snapshot now = CalendarManager.current();
        return now != null && SquareSets.winterFixtures(SquareSets.wanted(square.order(), now), now)
                && DecorWork.pieceLevel(target(), CsFullCycle.HUT_LEVEL) >= DecorWork.SQUARE_GIFT_LEVEL;
    }

    /**
     * Cells whose block differs from the square's blueprint of its level (not air, not substitution cells, not the hut
     * block); by block, as walls and panes take their connections from their neighbours once placed.
     */
    List<BlockPos> plazaDiff() {
        final List<BlockPos> out = new ArrayList<>();
        if (blueprint == null || square == null) {
            return out;
        }
        final BlockPos origin = square.getPosition().subtract(blueprint.getPrimaryBlockOffset());
        for (final BlockInfo info : blueprint.getBlockInfoAsList()) {
            final BlockState state = info.getState();
            final BlockPos at = origin.offset(info.getPos());
            if (state != null && !state.isAir() && !"structurize".equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace())
                    && !at.equals(square.getPosition()) && p.level.getBlockState(at).getBlock() != state.getBlock()) {
                out.add(at);
            }
        }
        return out;
    }

    /**
     * True when {@code pos} holds MineColonies' construction tape: it puts it round a building's box while a work order
     * is open there, and takes it away when the order closes (I2 leaves those cells to MineColonies).
     */
    boolean tape(final BlockPos pos) {
        final net.minecraft.resources.ResourceLocation id = BuiltInRegistries.BLOCK.getKey(p.level.getBlockState(pos).getBlock());
        return "minecolonies".equals(id.getNamespace()) && id.getPath().contains("tape");
    }

    // ================================================================ hooks of the timeline

    void check(final String what, final boolean ok, final String detail) {
        p.check(what, ok, detail);
    }

    DecorLedger.GroupView touches() {
        return anchor == null ? null : p.ledger.group(p.colonyId, DecorLedger.Owner.touches(anchor));
    }

    DecorLedger.GroupView set() {
        return anchor == null ? null : p.ledger.group(p.colonyId, DecorLedger.Owner.square(anchor));
    }

    static int size(final DecorLedger.GroupView g) {
        return g == null ? 0 : g.entries().size();
    }

    /** The touches stand on every bed of the square with the piece the order and the season want now. */
    String touchesUp() {
        final String want = SquareSets.touchPath(square.order(), CalendarManager.current());
        final DecorLedger.GroupView g = touches();
        final List<BlockPos> beds = DecorWork.bedCells(square);
        if (want == null) {
            return "no touch wanted now";
        }
        if (g == null || !want.equals(g.piece())) {
            return "touches " + (g == null ? "none" : g.piece()) + ", wanted " + want;
        }
        int on = 0;
        for (final BlockPos bed : beds) {
            final DecorLedger.Located at = p.ledger.at(bed);
            on += at != null && at.owner().equals(DecorLedger.Owner.touches(anchor)) && p.level.getBlockState(bed) == at.entry().state() ? 1 : 0;
        }
        return on == beds.size() && !beds.isEmpty() ? null : on + " of " + beds.size() + " beds carry the touch";
    }

    void november(final String label, final int dom) {
        if (square == null || dom != 30) {
            return;
        }
        final String up = touchesUp();
        check(label + ": the square carries only the season's touches (every bed), no set, the hut says nothing is up", up == null
                        && size(set()) == 0 && !p.hut.decorationsUp(),
                (up == null ? size(touches()) + " touch entries" : up) + ", set " + size(set()) + ", up " + p.hut.decorationsUp());
    }

    void decorated(final String label) {
        if (square == null) {
            return;
        }
        final DecorLedger.GroupView g = set();
        final int lv = DecorWork.pieceLevel(target(), CsFullCycle.HUT_LEVEL);
        check(label + ": the square's tree is the piece of the lower of the square's and the Workshop's level (" + lv + ")",
                g != null && g.piece().equals(PieceLoader.pathFor(lv)) && g.level() == lv && lv == Math.min(squareLevel, CsFullCycle.HUT_LEVEL),
                g == null ? "no group" : g.piece() + " level " + g.level());
        final BlockPos gift = tagCell(BuildingTownSquare.TAG_GIFT);
        final BlockPos launch = tagCell(BuildingTownSquare.TAG_LAUNCH);
        final DecorLedger.Located pile = gift == null ? null : p.ledger.at(gift);
        final DecorLedger.Located crate = launch == null ? null : p.ledger.at(launch);
        check(label + ": the gift pile stands at the square's gift cell, the crate at its launch cell", pile != null
                        && pile.entry().state().getBlock() instanceof GiftPileBlock && (squareLevel < 3 || crate != null
                        && crate.entry().state().is(SeasonsBlocks.FIREWORK_CRATE.get())),
                "gift " + gift + " " + (pile == null ? "-" : CsFullCycle.shortName(pile.entry().state())) + ", launch " + launch + " "
                        + (crate == null ? "-" : CsFullCycle.shortName(crate.entry().state())));
        check(label + ": the touches are down while the festival's set stands", size(touches()) == 0, size(touches()) + " touch entries");
        if (!market) {
            int stallCells = 0;
            for (final BlockPos c : MarketStalls.cells(square, anchor)) {
                stallCells += p.ledger.at(c) != null ? 1 : 0;
            }
            check(label + ": no market without Trade Post: no stall cell listed, the bridge logged nothing", stallCells == 0
                    && TradePostBridge.logLines() == logLines0 && !MarketStalls.bridge().active(), stallCells + " stall cells, "
                    + (TradePostBridge.logLines() - logLines0) + " bridge lines");
        } else {
            final int standing = MarketStalls.standing(p.level, p.ledger, p.colonyId, square, anchor);
            check(label + ": the four stalls stand on the square (the bridge on)", standing == 4, standing + " standing");
        }
    }

    void variantDaily(final LocalDate d) {
        if (market) {
            final int b = balance();
            final boolean gathering = Gathering.gatheringDay(CalendarManager.current());
            if (!gathering && b != lastBalance) {
                check(d + ": the balance moves only on the gathering days", false, lastBalance + " to " + b);
            }
            if (gathering) {
                CsFullCycle.note("{}: the market paid {} points today, balance {}", d, b - lastBalance, b);
            }
            lastBalance = b;
            final MarketMath.PaidDay paid = p.ledger.market(p.colonyId);
            if (paid != null && paid.day() >= 0) {
                final int stalls = MarketStalls.standing(p.level, p.ledger, p.colonyId, square, anchor);
                final int cap = MarketMath.cap(Math.max(stalls, 4), MarketStalls.bridge().coinValue());
                if (paid.paid() > cap) {
                    check(d + ": a colony day's payments stay under the cap", false, paid.paid() + " of " + cap);
                }
            }
            if (d.equals(LocalDate.of(2026, 12, 26))) {
                check("24 to 26 Dec: the stalls sold goods and the colony was paid for them", b > balance0, (b - balance0) + " points");
            }
        }
        if (street != null) {
            street.daily(d);
        }
    }

    void sampleCrowd(final List<ICitizenData> off) {
        if (square == null || anchor == null) {
            return;
        }
        final List<BlockPos> spots = Gathering.spots(p.level, p.colony, anchor);
        for (final ICitizenData c : off) {
            final AbstractEntityCitizen e = c.getEntity().orElse(null);
            if (e == null || !e.getNavigation().isDone() || CsFullCycle.distTo(c, anchor) > Gathering.SQUARE_KEEP_RADIUS) {
                continue;
            }
            crowd[0]++;
            crowd[1] += spots.stream().anyMatch(sp -> e.position().distanceToSqr(Vec3.atBottomCenterOf(sp)) <= 9.0) ? 1 : 0;
        }
    }

    void crowdChecked(final String label) {
        if (square == null) {
            return;
        }
        // at least half: with the market's stalls up some colonists stand at a stall to buy instead (trade-post saw 34 of 49)
        check(label + ": the crowd stands on the square's gather spots (at least half the still samples within 3 blocks of one)", crowd[0] >= 3
                && crowd[1] * 2 >= crowd[0], crowd[1] + " of " + crowd[0]);
        crowd[0] = 0;
        crowd[1] = 0;
    }

    void dusk(final String label) {
        if (square == null || squareLevel < 3) {
            return;
        }
        final BlockPos launch = tagCell(BuildingTownSquare.TAG_LAUNCH);
        checkEq(label + ": the show leaves from the crate at the square's launch cell", Fireworks.site(p.level, p.colony), launch);
    }

    void checkEq(final String what, final Object actual, final Object expected) {
        p.checkEq(what, actual, expected);
    }

    // ================================================================ the end

    void planEnd() {
        settle("10 Jan");
        p.step(new CsFullCycle.Step("10 Jan: everything but the square's touches is down, the world equals the first snapshot elsewhere", 1) {
            @Override
            void start() {
                p.setDate(CsFullCycle.LAST);
                endChecks("10 Jan");
                if (realMarker != null) {
                    check("10 Jan: the festival marker still stands and is registered (the fallback)", markerAt(realMarker)
                            && realMarker.equals(p.ledger.marker(p.colonyId)), String.valueOf(p.ledger.marker(p.colonyId)));
                }
            }
        });
    }

    /**
     * A last morning for the planters: a season that turned on the shortened last days is looked at by the Decorator within
     * {@code TOUCH_CHECK_TICKS} (a swap waits while the new piece loads), so the end waits up to twice that for the touches.
     */
    void settle(final String label) {
        p.step(new CsFullCycle.Step(label + ": the planters settle (the season's touches)", 2 * me.lovkar.seasons.ai.EntityAIWorkDecorator.TOUCH_CHECK_TICKS + 400, true) {
            @Override
            void start() {
                p.newGameDay(CsFullCycle.MORNING);
            }

            @Override
            boolean tick() {
                return square == null || squareLevel < 3 || square.order() == SquareSets.Order.BARE || t > 20 && touchesUp() == null;
            }

            @Override
            void end(final boolean done) {
                CsFullCycle.note("{}: the planters settled after {} ticks: {}", label, t, square == null ? "-" : String.valueOf(touchesUp()));
            }
        });
    }

    void endChecks(final String label) {
        final boolean up = p.hut != null && p.hut.decorationsUp();
        final List<String> other = new ArrayList<>();
        for (final DecorLedger.GroupView g : p.ledger.groups(p.colonyId)) {
            if (!g.entries().isEmpty() && !(anchor != null && g.owner().equals(DecorLedger.Owner.touches(anchor)))) {
                other.add(g.owner().kind() + " " + g.owner().pos().toShortString() + " " + g.entries().size());
            }
        }
        check(label + ": nothing is listed but the square's touches, the hut says the decorations are down", other.isEmpty() && !up
                && p.ledger.countOutdoor(p.colonyId) == 0, other + ", up " + up);
        final Map<Item, Integer> now = p.conservation();
        final Map<Item, Integer> want = new LinkedHashMap<>(p.start);
        if (market) {
            for (final Item good : prices.keySet()) {
                want.put(good, now.getOrDefault(good, 0));     // I7 counts the goods
            }
        }
        check(label + ": every tracked item is back where it was (I3 exact)", now.equals(want), "now " + CsFullCycle.text(now) + "; at the start "
                + CsFullCycle.text(want));
        final int[] total = new int[1];
        final DecorLedger.Owner own = anchor == null ? null : DecorLedger.Owner.touches(anchor);
        final List<BlockPos> diff = p.first.diff(p.level, pos -> {
            final DecorLedger.Located at = p.ledger.at(pos);
            return at == null || own == null || !at.owner().equals(own);
        }, 20, total);
        final StringBuilder b = new StringBuilder();
        for (final BlockPos pos : diff) {
            b.append(pos.toShortString()).append(' ').append(CsFullCycle.shortName(p.level.getBlockState(pos))).append(" was ")
                    .append(CsFullCycle.shortName(p.first.at(pos))).append("; ");
        }
        check(label + ": every cell that differs from the first snapshot is a touch of the square (" + p.first.size() + " cells)", total[0] == 0,
                total[0] + " differ " + b);
        if (square != null && squareLevel >= 3 && square.order() != SquareSets.Order.BARE) {
            final String touched = touchesUp();
            check(label + ": the season's touches are back on the square after the take-down", touched == null, String.valueOf(touched));
        }
        if (market) {
            final int b2 = balance();
            check(label + ": the market's money equals the goods it sold and the paid record (I7)", b2 - balance0 == paidSoFar() && b2 > balance0,
                    (b2 - balance0) + " points, paid record " + paidSoFar());
        }
    }

    // ================================================================ migrate-marker

    /** The marker tree's cell the probe changes before the switch (it must stay), and the item it took. */
    BlockPos changedCell;
    Item changedItem;
    int notices0;
    BlockPos realMarker;

    /** 8 Dec evening: the Town Square hut at level 0; 9 Dec nothing changes; 10 Dec level 1 and the switch; then the cycle on to 10 Jan. */
    void planMigrate(final LocalDate from) {
        p.step(new CsFullCycle.Step("8 Dec evening: a Town Square placed at level 0 (its plaza pasted), the snapshot re-read for its box", 1) {
            @Override
            void start() {
                realMarker = p.marker;
                squareLevel = 1;
                plot = MIGRATE_PLOT.offset(0, p.ground, 0);
                final BlockPos hutAt = paste(plot, 1);
                square = hutAt == null ? null : register(hutAt, plot, 0);
                if (square == null) {
                    p.finish();
                    return;
                }
                boolean inClaim = true;
                for (final BlockPos corner : corners()) {
                    inClaim &= p.claimed(new BlockPos(corner.getX(), p.ground + 1, corner.getZ()));
                }
                check("migrate: the square's corners lie in the claim and its box holds no entry", inClaim && entriesIn(square) == 0, plot.toShortString());
                p.first.reread(p.level, square.getCorners().getA().offset(-2, -1, -2), square.getCorners().getB().offset(2, 2, 2));
                notices0 = Squares.markerUnusedNotices();
            }
        });
        p.step(new CsFullCycle.Step("migrate: the square's tags are read", 200) {
            @Override
            boolean tick() {
                return Squares.treeCell(square) != null;
            }

            @Override
            void end(final boolean done) {
                check("migrate: the square's tags read", done, "after " + t + " ticks");
                if (!done) {
                    p.finish();
                    return;
                }
                anchor = Squares.treeCell(square);
            }
        });
        final LocalDate d9 = from;
        p.step(p.day(d9, d9 + " (the square at level 0)", CsFullCycle.EVENING, p.new DayHook() {
            int entries0;

            @Override
            void start() {
                entries0 = p.ledger.count(p.colonyId);
            }

            @Override
            void end(final boolean done) {
                final Squares.Square s = Squares.of(p.level, p.colony, p.ledger);
                check(d9 + ": a square at level 0 changes nothing: the marker holds the tree, no square mark, no notice",
                        s.source() == Squares.Source.MARKER && p.ledger.squareMark(p.colonyId) == null && p.ledger.count(p.colonyId) == entries0
                                && Squares.markerUnusedNotices() == notices0 && size(set()) == 0,
                        s.source() + ", " + entries0 + " -> " + p.ledger.count(p.colonyId) + " entries");
            }
        }));
        final LocalDate d10 = from.plusDays(1);
        p.step(p.day(d10, d10 + " (the square built: level 1)", CsFullCycle.EVENING, p.new DayHook() {
            final Map<BlockPos, Integer> away = new HashMap<>();
            boolean grew;
            int markerEntries;
            String gift;

            @Override
            void start() {
                p.giftOnce(d10 + " at the marker", Gifts.DAILY);
                // a cell of the marker's tree a player changed: the exact-state rule leaves it
                final DecorLedger.GroupView tree = p.ledger.group(p.colonyId, DecorLedger.Owner.square(realMarker));
                markerEntries = size(tree);
                if (tree != null) {
                    for (final Map.Entry<BlockPos, DecorLedger.Entry> e : tree.entries().entrySet()) {
                        if (changedCell == null && e.getValue().paid() && e.getValue().state().is(Blocks.SPRUCE_LOG)) {
                            changedCell = e.getKey();
                        }
                    }
                }
                if (changedCell != null) {
                    changedItem = Items.SPRUCE_LOG;
                    p.set(changedCell, Blocks.OAK_PLANKS.defaultBlockState());
                    p.held.merge(changedItem, 1, Integer::sum);       // the player broke the log and kept it
                    p.probeChanged.add(changedCell);
                }
                square.setBuildingLevel(1);
                square.markDirty();
                DecorEvents.squareChanged(square);
                DecorEvents.drain(p.server);
                final Squares.Square s = Squares.of(p.level, p.colony, p.ledger);
                check(d10 + ": at level 1 the square is the colony's centrepiece", s.source() == Squares.Source.BUILDING && anchor.equals(s.anchor())
                        && p.ledger.squareMark(p.colonyId) != null, String.valueOf(s));
                checkEq(d10 + ": the managers are told once that the marker is not used", Squares.markerUnusedNotices() - notices0, 1);
                p.marker = anchor;
            }

            @Override
            void tick(final int t) {
                if (t % 20 != 0) {
                    return;
                }
                for (final DecorLedger.GroupView g : p.ledger.groups(p.colonyId)) {
                    final DecorLedger.Kind kind = g.owner().kind();
                    if ((kind == DecorLedger.Kind.SQUARE || kind == DecorLedger.Kind.TOUCHES) && !g.owner().pos().equals(anchor)) {
                        final Integer last = away.put(g.owner().pos(), g.entries().size());
                        grew |= last != null && g.entries().size() > last;
                    }
                }
            }

            @Override
            void end(final boolean done) {
                check(d10 + ": the marker's group never grows while it comes down (I5 every 20 ticks)", !grew, String.valueOf(away));
                final DecorLedger.GroupView old = p.ledger.group(p.colonyId, DecorLedger.Owner.square(realMarker));
                check(d10 + ": the marker's tree came down by the exact-state rule: the changed cell stays and is forgotten", size(old) == 0
                        && changedCell != null && p.level.getBlockState(changedCell).is(Blocks.OAK_PLANKS) && p.ledger.at(changedCell) == null,
                        markerEntries + " -> " + size(old) + " entries, changed " + changedCell);
                final DecorLedger.GroupView g = set();
                check(d10 + ": the Decorator built the level 1 set on the square from those items", g != null && g.decoratedDay() >= 0
                        && g.piece().equals(PieceLoader.pathFor(1)), g == null ? "no group" : g.piece() + ", " + g.entries().size() + " entries, decorated "
                        + g.decoratedDay() + ", status '" + (p.job() == null ? "-" : p.job().status()) + "'");
                final BlockPos pile = p.pile();
                final Gifts.Outcome again = pile == null ? null : Gifts.take(p.level, pile, p.p1);
                checkEq(d10 + ": no second gift on the switch day at the square's pile", again, Gifts.Outcome.ALREADY);
                checkEq(d10 + ": the marker is still registered (the fallback)", p.ledger.marker(p.colonyId), realMarker);
            }
        }));
        final LocalDate d11 = from.plusDays(2);
        p.step(p.day(d11, d11 + " (a marker placed anew)", CsFullCycle.EVENING, p.new DayHook() {
            int before;

            @Override
            void start() {
                // the changed cell goes back as it was (the player's planks taken away), so the end can equal the snapshot
                p.set(changedCell, Blocks.AIR.defaultBlockState());
                p.held.merge(changedItem, -1, Integer::sum);
                p.start.merge(changedItem, -1, Integer::sum);       // that log left the colony with the player
                p.probeChanged.remove(changedCell);
                before = Squares.markerUnusedNotices();
                p.level.setBlock(realMarker, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                checkEq(d11 + ": the broken marker is unregistered", p.ledger.marker(p.colonyId), null);
                final BlockPos keep = p.marker;
                p.marker = realMarker;
                p.placeMarker();
                p.marker = keep;
                checkEq(d11 + ": a marker placed anew registers as the fallback", p.ledger.marker(p.colonyId), realMarker);
                checkEq(d11 + ": with one notice that it is not used", Squares.markerUnusedNotices() - before, 1);
            }

            @Override
            void end(final boolean done) {
                final Squares.Square s = Squares.of(p.level, p.colony, p.ledger);
                check(d11 + ": the square stays the centrepiece, nothing at the marker", s.source() == Squares.Source.BUILDING
                        && p.ledger.group(p.colonyId, DecorLedger.Owner.square(realMarker)) == null, String.valueOf(s));
            }
        }));
        p.planDates(from.plusDays(3), CsFullCycle.LAST, false);
    }

    /** Ledger entries inside a building's box. */
    int entriesIn(final IBuilding b) {
        final BlockPos a = b.getCorners().getA();
        final BlockPos z = b.getCorners().getB();
        int n = 0;
        for (final BlockPos pos : p.ledger.entries(p.colonyId).keySet()) {
            if (pos.getX() >= Math.min(a.getX(), z.getX()) && pos.getX() <= Math.max(a.getX(), z.getX()) && pos.getZ() >= Math.min(a.getZ(), z.getZ())
                    && pos.getZ() <= Math.max(a.getZ(), z.getZ())) {
                n++;
            }
        }
        return n;
    }

    // ================================================================ restarts

    void saveFacts(final CompoundTag f) {
        if (square != null) {
            f.putLong("square", square.getPosition().asLong());
            f.putInt("squareLevel", squareLevel);
            f.putLong("squarePlot", plot.asLong());
        }
        if (anchor != null) {
            f.putLong("anchor", anchor.asLong());
        }
        if (realMarker != null) {
            f.putLong("realMarker", realMarker.asLong());
        }
        f.putInt("balance0", balance0);
        f.putInt("logLines0", logLines0);
        SquareRestarts.saveGroups(p, f);
        for (final String key : extra.getAllKeys()) {
            f.put(key, extra.get(key).copy());
        }
    }

    void find(final String phase, final CompoundTag f) {
        if (f.contains("square")) {
            square = p.colony.getServerBuildingManager().getBuilding(BlockPos.of(f.getLong("square"))) instanceof BuildingTownSquare s ? s : null;
            squareLevel = f.getInt("squareLevel");
            plot = BlockPos.of(f.getLong("squarePlot"));
            try {
                blueprint = StructurePacks.getBlueprintFuture(PACK, "square/townsquare" + Math.max(1, squareLevel) + ".blueprint",
                        p.level.registryAccess()).get(60, TimeUnit.SECONDS);
            } catch (final Exception unreadable) {
                blueprint = null;
            }
            check(phase + ": the Town Square is back with its Decorator", square != null && p.job() != null && p.job().findSquare() == square,
                    square == null ? "no square" : "level " + square.getBuildingLevel());
        }
        if (f.contains("anchor")) {
            anchor = BlockPos.of(f.getLong("anchor"));
        }
        if (f.contains("realMarker")) {
            realMarker = BlockPos.of(f.getLong("realMarker"));
        }
        balance0 = f.getInt("balance0");
        logLines0 = TradePostBridge.logLines();
        SquareRestarts.compareGroups(p, phase, f);
    }

    /** The cells of the tags of the square, sorted (for messages). */
    TreeSet<BlockPos> cells(final String tag) {
        return new TreeSet<>(square.getLocationsFromTag(tag));
    }

    /** True when {@code pos} holds the festival marker block. */
    boolean markerAt(final BlockPos pos) {
        return pos != null && p.level.getBlockState(pos).getBlock() instanceof FestivalMarkerBlock;
    }
}
