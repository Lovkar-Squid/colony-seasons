package me.lovkar.seasons.csfullcycle;

import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import me.lovkar.seasons.calendar.CalendarManager;
import me.lovkar.seasons.calendar.Season;
import me.lovkar.seasons.colony.BuildingDecorator;
import me.lovkar.seasons.colony.BuildingTownSquare;
import me.lovkar.seasons.colony.DecoratorModules;
import me.lovkar.seasons.colony.DecoratorSetup;
import me.lovkar.seasons.colony.JobDecorator;
import me.lovkar.seasons.decor.DecorLedger;
import me.lovkar.seasons.decor.DecorWork;
import me.lovkar.seasons.decor.PieceLoader;
import me.lovkar.seasons.decor.SquareSets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.items.ItemHandlerHelper;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Q7 mode touches: the seasonal touches in the planters of a level 5 Town Square (Q12) across seasons, orders and the
 * Winter Festival's window, on a light colony (a town hall, the Workshop at level 3 with its Decorator, two colonists and
 * the square). The in-game season is set with {@link CalendarManager#setSeason}; each sample gets one or two game days of
 * the Decorator's own AI and the daily invariants. Checked: each season's touch on all eight beds as a TOUCHES group, "the
 * decorations are up" never set, the day's budget never used, no item moved (they are free); a season change swaps only
 * the planters; a touch broken by hand drops nothing and is back the next game day; Bare: none; Always Winter between the
 * windows: the touches, as under Follow ([B] owner answer of 20 Nov 2026: the full sets only in festival windows; Q3 built
 * it so); in the window the touches are down no later than the day the first tree cell is listed and back after the
 * take-down; I3 exact.
 */
final class TouchLegs {
    private final CsFullCycle p;
    private final SquareCycle sc;
    /** The Workshop's items when the touches start: they must never move (the touches are free). */
    private int hutItems0;

    TouchLegs(final CsFullCycle probe, final SquareCycle square) {
        this.p = probe;
        this.sc = square;
    }

    private int hutItems() {
        return BuildingDecorator.countIn(p.hut.getItemHandlerCap(), s -> true);
    }

    void plan() {
        final LocalDate march = LocalDate.of(2027, 3, 15);
        p.step(new CsFullCycle.Step("touches setup: a town hall, the Workshop, the Decorator, two colonists, the level 5 square", 120, true) {
            @Override
            void start() {
                p.setDate(march);
                p.quietWorld();
                p.ground = p.level.getHeight(Heightmap.Types.MOTION_BLOCKING, 2, 2) - 1;
                final BlockPos center = new BlockPos(2, p.ground + 1, 2);
                p.forceChunks(5);
                p.owner = FakePlayerFactory.getMinecraft(p.level);
                p.colony = IColonyManager.getInstance().createColony(p.level, center, p.owner, "Seasons Touches", "Medieval Oak");
                p.colonyId = p.colony == null ? -1 : p.colony.getID();
                p.check("touches: a colony was made", p.colony != null, "id " + p.colonyId);
                if (p.colony == null) {
                    p.finish();
                    return;
                }
                p.townHall = p.register(p.colony, "minecolonies:blockhuttownhall", p.house(-3, -1, 11, 7, 2), 1);
                final IBuilding d = p.register(p.colony, BuiltInRegistries.BLOCK.getKey(DecoratorSetup.HUT.get()).toString(),
                        p.house(12, -1, 11, 7, 17), CsFullCycle.HUT_LEVEL);
                p.hut = d instanceof BuildingDecorator b ? b : null;
                p.buildings.addAll(List.of(p.townHall, p.hut));
                p.decorator = p.spawn(p.hut.getPosition().offset(0, 0, 4));
                p.check("touches: a citizen is hired as the Decorator", p.hut.getModule(DecoratorModules.WORK).assignCitizen(p.decorator)
                        && p.decorator.getJob() instanceof JobDecorator, "");
                p.spawn(center.offset(-2, 0, 11));
                p.spawn(center.offset(2, 0, 11));
                sc.placeInSetup();
                p.players();
                p.setTimeOfDay(CsFullCycle.MORNING);
            }

            @Override
            boolean tick() {
                return t >= 60 && sc.locate() && !p.level.getChunkSource().getLightEngine().hasLightWork();
            }

            @Override
            void end(final boolean done) {
                p.check("touches: the square is located", done && sc.anchor != null, "");
                if (sc.anchor == null) {
                    p.finish();
                    return;
                }
                for (final Item item : List.of(net.minecraft.world.item.Items.SPRUCE_LOG, net.minecraft.world.item.Items.SPRUCE_LEAVES)) {
                    p.start.put(item, 0);
                }
                p.first = Snap.take(p.level, new BlockPos(-12, p.ground - 1, -4), new BlockPos(26, p.ground + 24, 46));
                hutItems0 = hutItems();
                p.daily("touches setup");
            }
        });
        int dayOfYear = 0;
        for (final Season season : List.of(Season.SPRING, Season.SUMMER, Season.AUTUMN, Season.WINTER)) {
            final LocalDate d = march.plusDays(dayOfYear++);
            p.step(p.day(d, "touches: " + season.id(), CsFullCycle.EVENING, p.new DayHook() {
                int outsideBefore;

                @Override
                void start() {
                    CalendarManager.setSeason(p.server, season);
                    outsideBefore = outside();
                }

                @Override
                boolean done() {
                    return p.t() > 400 && sc.touchesUp() == null;
                }

                @Override
                void end(final boolean done) {
                    final String up = sc.touchesUp();
                    final DecorLedger.GroupView g = sc.touches();
                    p.check("touches: " + season.id() + ": the season's touch on every bed, a TOUCHES group, not decorations up, no budget, no item moved",
                            up == null && g != null && g.owner().kind() == DecorLedger.Kind.TOUCHES && g.piece().equals(SquareSets.TOUCH_PREFIX + season.id()
                                    + ".blueprint") && !p.hut.decorationsUp() && p.ledger.budgetUsed(p.colonyId, p.day()) == 0 && hutItems() == hutItems0,
                            (up == null ? (g == null ? "-" : g.piece() + " on " + g.entries().size() + " beds") : up) + ", up " + p.hut.decorationsUp()
                                    + ", budget " + p.ledger.budgetUsed(p.colonyId, p.day()) + ", hut items " + hutItems0 + " -> " + hutItems());
                    p.check("touches: " + season.id() + ": only the planters changed (every other cell as it was, no set listed)", outside() == 0
                            && SquareCycle.size(sc.set()) == 0, outside() + " other cells differ (before the season " + outsideBefore + ")");
                }
            }));
        }
        // a touch broken by hand: no drop, back the next game day
        final BlockPos[] broken = new BlockPos[1];
        final LocalDate dBreak = march.plusDays(dayOfYear++);
        p.step(p.day(dBreak, "touches: one broken by hand", CsFullCycle.EVENING, p.new DayHook() {
            @Override
            void start() {
                final List<BlockPos> beds = DecorWork.bedCells(sc.square);
                broken[0] = beds.get(0);
                final BlockState was = p.level.getBlockState(broken[0]);
                final int suppressed0 = me.lovkar.seasons.decor.DecorDrops.suppressed();
                p.level.destroyBlock(broken[0], true, p.p1);
                final int drops = p.level.getEntitiesOfClass(ItemEntity.class, new AABB(broken[0]).inflate(2)).size();
                // a vanilla touch (the winter boughs are spruce leaves) never tells the ledger: its entry goes on the next visit
                p.check("touches: a touch broken by hand drops nothing", drops == 0 && p.level.getBlockState(broken[0]).isAir(),
                        CsFullCycle.shortName(was) + ", " + drops + " drops, " + (me.lovkar.seasons.decor.DecorDrops.suppressed() - suppressed0)
                                + " drops taken away, listed " + (p.ledger.at(broken[0]) != null));
            }

            @Override
            void end(final boolean done) {
                CsFullCycle.note("touches: the broken touch at the end of its game day: {}", p.ledger.at(broken[0]) == null ? "open" : "back");
            }
        }));
        final LocalDate dBack = march.plusDays(dayOfYear++);
        p.step(p.day(dBack, "touches: the next game day", CsFullCycle.EVENING, p.new DayHook() {
            @Override
            boolean done() {
                return p.t() > 400 && sc.touchesUp() == null;
            }

            @Override
            void end(final boolean done) {
                p.check("touches: the broken touch is back the next game day, at no cost", sc.touchesUp() == null && hutItems() == hutItems0,
                        String.valueOf(sc.touchesUp()));
            }
        }));
        // Bare: none
        final LocalDate dBare = march.plusDays(dayOfYear++);
        p.step(p.day(dBare, "touches: Bare", CsFullCycle.EVENING, p.new DayHook() {
            @Override
            void start() {
                order(SquareSets.Order.BARE);
            }

            @Override
            boolean done() {
                return p.t() > 400 && SquareCycle.size(sc.touches()) == 0;
            }

            @Override
            void end(final boolean done) {
                p.check("touches: Bare: the planters are emptied, nothing is listed", SquareCycle.size(sc.touches()) == 0 && p.ledger.count(p.colonyId) == 0
                        && outside() == 0 && bedsAir(), p.ledger.count(p.colonyId) + " entries");
            }
        }));
        // Always Winter between the windows (June): the touches, as under Follow
        final LocalDate june = LocalDate.of(2027, 6, 15);
        p.step(p.day(june, "touches: Always Winter in June", CsFullCycle.EVENING, p.new DayHook() {
            @Override
            void start() {
                order(SquareSets.Order.ALWAYS_WINTER);
                CalendarManager.setSeason(p.server, Season.SUMMER);
            }

            @Override
            boolean done() {
                return p.t() > 400 && sc.touchesUp() == null;
            }

            @Override
            void end(final boolean done) {
                p.check("touches: Always Winter between the windows: no tree (the full sets come only in festival windows), the season's touches",
                        SquareCycle.size(sc.set()) == 0 && sc.touchesUp() == null, sc.touchesUp() + ", set " + SquareCycle.size(sc.set()));
            }
        }));
        // Follow again, the window opens: the touches go before the tree's first cell is listed
        final Map<Item, Integer> need = new LinkedHashMap<>();
        p.step(new CsFullCycle.Step("touches: the tree's piece loads", 400, true) {
            @Override
            boolean tick() {
                return PieceLoader.request(p.level, PieceLoader.PACK, PieceLoader.pathFor(CsFullCycle.HUT_LEVEL), net.minecraft.core.Direction.NORTH) != null;
            }

            @Override
            void end(final boolean done) {
                p.check("touches: the tree's piece is loaded", done, PieceLoader.pathFor(CsFullCycle.HUT_LEVEL));
            }
        });
        p.step(new CsFullCycle.Step("touches: the Workshop stocked for the tree, the order Follow", 1) {
            @Override
            void start() {
                order(SquareSets.Order.FOLLOW);
                sc.addNeeds(need, SquareCycle.DEC_1, june);
                need.putIfAbsent(net.minecraft.world.item.Items.SPRUCE_LOG, 0);
                for (final Map.Entry<Item, Integer> e : need.entrySet()) {
                    int left = e.getValue();
                    while (left > 0) {
                        final int n = Math.min(left, e.getKey().getDefaultMaxStackSize());
                        ItemHandlerHelper.insertItemStacked(p.hut.getItemHandlerCap(), new ItemStack(e.getKey(), n), false);
                        left -= n;
                    }
                    p.start.merge(e.getKey(), e.getValue(), Integer::sum);
                }
                hutItems0 = hutItems();
            }
        });
        final boolean[] overlap = new boolean[1];
        final int[] firstTreeDay = {-1};
        final int[] touchesGoneDay = {-1};
        for (int i = 0; i < 3; i++) {
            final int n = i;
            p.step(p.day(SquareCycle.DEC_1.plusDays(i), "touches: the window, game day " + (i + 1), CsFullCycle.EVENING, p.new DayHook() {
                @Override
                void tick(final int t) {
                    if (t % 10 != 0) {
                        return;
                    }
                    final boolean tree = SquareCycle.size(sc.set()) > 0;
                    final boolean touches = SquareCycle.size(sc.touches()) > 0;
                    overlap[0] |= tree && touches;
                    if (tree && firstTreeDay[0] < 0) {
                        firstTreeDay[0] = n;
                    }
                    if (!touches && touchesGoneDay[0] < 0) {
                        touchesGoneDay[0] = n;
                    }
                }
            }));
        }
        p.step(new CsFullCycle.Step("touches: the window's tree", 1) {
            @Override
            void start() {
                final DecorLedger.GroupView g = sc.set();
                p.check("touches: in the window the touches are down no later than the day the tree's first cell is listed, never both at once",
                        !overlap[0] && touchesGoneDay[0] >= 0 && firstTreeDay[0] >= touchesGoneDay[0], "touches gone on game day " + touchesGoneDay[0]
                                + ", first tree cell on " + firstTreeDay[0] + ", overlap " + overlap[0]);
                p.check("touches: the tree stands complete on the square", g != null && g.decoratedDay() >= 0 && g.piece().equals(PieceLoader.pathFor(3)),
                        g == null ? "no group" : g.piece() + " " + g.entries().size() + " decorated " + g.decoratedDay());
            }
        });
        for (int i = 7; i <= 9; i++) {
            final LocalDate d = LocalDate.of(2027, 1, i);
            p.step(p.day(d, "touches: " + d + " (the take-down)", CsFullCycle.EVENING, p.new DayHook() {
                @Override
                boolean done() {
                    return p.t() > 400 && SquareCycle.size(sc.set()) == 0 && sc.touchesUp() == null;
                }
            }));
        }
        sc.settle("touches 9 Jan");
        p.step(new CsFullCycle.Step("touches: the end", 1) {
            @Override
            void start() {
                p.check("touches: after the take-down the season's touches are back and the tree is gone", SquareCycle.size(sc.set()) == 0
                        && sc.touchesUp() == null, String.valueOf(sc.touchesUp()));
                sc.endChecks("touches 9 Jan");
            }
        });
    }

    private void order(final SquareSets.Order order) {
        sc.square.settings().getSetting(BuildingTownSquare.ORDER).set(order.key());
        sc.square.markDirty();
    }

    /** Cells of the first snapshot that differ and are not a bed of the square. */
    private int outside() {
        final List<BlockPos> beds = DecorWork.bedCells(sc.square);
        final int[] total = new int[1];
        p.first.diff(p.level, pos -> !beds.contains(pos), 0, total);
        return total[0];
    }

    private boolean bedsAir() {
        for (final BlockPos bed : DecorWork.bedCells(sc.square)) {
            if (!p.level.getBlockState(bed).isAir()) {
                return false;
            }
        }
        return true;
    }
}
