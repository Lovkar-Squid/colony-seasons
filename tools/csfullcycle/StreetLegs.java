package me.lovkar.seasons.csfullcycle;

import com.minecolonies.api.colony.buildings.IBuilding;
import me.lovkar.seasons.block.Lights;
import me.lovkar.seasons.block.SeasonsBlocks;
import me.lovkar.seasons.calendar.CalendarManager;
import me.lovkar.seasons.calendar.Season;
import me.lovkar.seasons.decor.DecorEvents;
import me.lovkar.seasons.decor.DecorLedger;
import me.lovkar.seasons.decor.DoorFinder;
import me.lovkar.seasons.decor.StreetLights;
import me.lovkar.seasons.decor.StreetMarks;
import me.lovkar.seasons.item.DecoratorWandItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Q7 mode wand (rigconfig-wand: {@code decorator.streetLights = true}): marked streets through the cycle on a Town Square
 * colony. A street east of the Workshop, built before the first snapshot (flat grass, a three-wide dirt path along x,
 * fences on its north side, a wall on its south side), and on 28 Nov all five mark types made the way a player makes them
 * ({@link DecoratorWandItem#click}, the garland by two clicks on its posts). Checked: a mark inside the square's box is
 * refused; nothing of a mark is up before 1 Dec; by 2 Dec every mark's pieces and the street lights stand; the posts and
 * the lights come on with the tree's lighting; a snowman broken by hand drops nothing and is back the next game day; a
 * shift-click removal and a new mark in the middle of the window; a hut registered over the planter takes its group down
 * (the mark kept, as built) and the planter is back once the hut is gone; a forced season change swaps the planter; after
 * the window no STREET or LIGHTS entry is left and the colony's marks are the ones it should keep.
 */
final class StreetLegs {
    private final CsFullCycle p;
    private final SquareCycle sc;
    private BlockPos sa;
    private BlockPos sb;
    private BlockPos p1;
    private BlockPos p2;
    private BlockPos p3;
    private BlockPos pl;
    private BlockPos extra;
    private BlockPos hutCell;
    private IBuilding plot;
    private BlockState planterBefore;
    private final Set<String> done = new HashSet<>();
    private final List<BlockPos> fences = new ArrayList<>();

    StreetLegs(final CsFullCycle probe, final SquareCycle square) {
        this.p = probe;
        this.sc = square;
    }

    private BlockPos at(final int x, final int dy, final int z) {
        return new BlockPos(x, p.ground + dy, z);
    }

    /** The street's box (the first snapshot covers it). */
    List<BlockPos> corners() {
        return List.of(at(14, -2, 6), at(62, 10, 22));
    }

    /** Setup: the street (cells at ground + 1, its path at ground), clear of every building and the square. */
    void build() {
        for (int x = 16; x <= 60; x++) {
            for (int z = 8; z <= 20; z++) {
                p.set(at(x, -2, z), Blocks.DIRT.defaultBlockState());
                p.set(at(x, -1, z), Blocks.DIRT.defaultBlockState());
                p.set(at(x, 0, z), Blocks.GRASS_BLOCK.defaultBlockState());
                for (int dy = 1; dy <= 8; dy++) {
                    p.set(at(x, dy, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
        for (int x = 18; x <= 54; x++) {
            for (int z = 13; z <= 15; z++) {
                p.set(at(x, 0, z), Blocks.DIRT_PATH.defaultBlockState());
            }
        }
        for (int x = 18; x <= 26; x++) {
            fences.add(at(x, 1, 12));
            p.level.setBlock(at(x, 1, 12), Blocks.OAK_FENCE.defaultBlockState(), Block.UPDATE_ALL);
        }
        for (int x = 40; x <= 46; x++) {
            p.level.setBlock(at(x, 1, 16), Blocks.COBBLESTONE_WALL.defaultBlockState(), Block.UPDATE_ALL);
        }
        sa = at(24, 1, 14);
        sb = at(30, 1, 17);
        p1 = at(34, 1, 11);
        p2 = at(34, 1, 17);
        p3 = at(42, 1, 14);
        pl = at(50, 1, 18);
        extra = at(56, 1, 10);
        boolean claimed = true;
        for (final BlockPos c : List.of(sa, sb, p1, p2, p3, pl, extra, at(16, 1, 8), at(60, 1, 20))) {
            claimed &= p.claimed(c);
        }
        p.check("wand: the street lies in the colony's claim", claimed, "");
    }

    private ItemStack wand(final StreetMarks.Type type) {
        final ItemStack stack = new ItemStack(SeasonsBlocks.DECORATORS_WAND.get());
        DecoratorWandItem.setMode(stack, type);
        return stack;
    }

    private String click(final StreetMarks.Type type, final BlockPos cell) {
        return DecoratorWandItem.click(p.level, p.owner, wand(type), cell.below(), Direction.UP, false);
    }

    private int streetEntries() {
        int n = 0;
        for (final DecorLedger.GroupView g : p.ledger.groups(p.colonyId)) {
            if (g.owner().kind() == DecorLedger.Kind.STREET || g.owner().kind() == DecorLedger.Kind.LIGHTS) {
                n += g.entries().size();
            }
        }
        return n;
    }

    private int size(final BlockPos mark) {
        final DecorLedger.GroupView g = p.ledger.group(p.colonyId, DecorLedger.Owner.street(mark));
        return g == null ? 0 : g.entries().size();
    }

    void plan() {
        p.step(new CsFullCycle.Step("wand: the five marks made with the wand on 28 Nov", 1) {
            @Override
            void start() {
                final String a = click(StreetMarks.Type.SNOWMAN_A, sa);
                final String bb = click(StreetMarks.Type.SNOWMAN_B, sb);
                final String q1 = click(StreetMarks.Type.POST, p1);
                final String q2 = click(StreetMarks.Type.POST, p2);
                final String q3 = click(StreetMarks.Type.POST, p3);
                final ItemStack garland = wand(StreetMarks.Type.GARLAND);
                DecoratorWandItem.click(p.level, p.owner, garland, p1.below(), Direction.UP, false);
                final String g = DecoratorWandItem.click(p.level, p.owner, garland, p2.below(), Direction.UP, false);
                final String plant = click(StreetMarks.Type.PLANTER, pl);
                p.check("wand: all five types marked (two snowmen, three posts, a garland between two of them, a planter)", List.of(a, bb, q1, q2, q3, g, plant)
                        .stream().allMatch("com.colony_seasons.wand.marked"::equals) && p.ledger.marks(p.colonyId).size() == 7, List.of(a, bb, q1, q2, q3, g, plant)
                        + ", " + p.ledger.marks(p.colonyId).size() + " marks");
                final BlockPos inSquare = sc.tagCell(me.lovkar.seasons.colony.BuildingTownSquare.TAG_GATHER);
                p.checkEq("wand: a mark on the Town Square (a free standing spot of its paving) is refused: it is a building's box",
                        click(StreetMarks.Type.SNOWMAN_A, inSquare), "com.colony_seasons.wand.refused.building");
                p.daily("wand marks made");
            }
        });
        p.planDates(CsFullCycle.FIRST, CsFullCycle.LAST, false);
    }

    /** Bodies seen in an open mark's cells while the window is open: who, how often (the probe's look at what keeps a mark open). */
    private final java.util.Map<String, Integer> bodies = new java.util.TreeMap<>();

    /** Every tick of a day: who stands in the cells of a mark that is not up yet. */
    void tick(final int t) {
        if (t % 2 != 0 || !me.lovkar.seasons.decor.DecorPhase.windowOpen(CalendarManager.current())) {
            return;
        }
        for (final StreetMarks.Mark mark : p.ledger.marks(p.colonyId)) {
            if (size(mark.pos()) > 0) {
                continue;
            }
            final List<me.lovkar.seasons.decor.DecorPlacer.Slot> slots = me.lovkar.seasons.decor.StreetPieces.slots(mark,
                    CalendarManager.current().season() == null ? null : CalendarManager.current().season().season());
            for (final net.minecraft.world.entity.Entity e : me.lovkar.seasons.decor.DecorPlacer.blockers(p.level, slots)) {
                final boolean decorator = p.decorator != null && p.decorator.getEntity().map(d -> d == e).orElse(false);
                bodies.merge(mark.type().id() + " " + mark.pos().toShortString() + ": " + (decorator ? "the Decorator"
                        : e.getType().toShortString()), 1, Integer::sum);
            }
        }
    }

    /** After every game day of the cycle (the timeline's variant hook). */
    void daily(final LocalDate d) {
        final String key = d.toString();
        final int m = d.getMonthValue();
        final int dom = d.getDayOfMonth();
        if (m == 11) {
            if (streetEntries() != 0) {
                p.check(key + ": nothing of a mark is up before the window", false, streetEntries() + " street entries");
            }
            return;
        }
        if (!done.add(key)) {
            return;                                   // a date of two game days: its actions once, after the first
        }
        if (m == 12 && dom == 2) {
            CsFullCycle.note("wand: bodies seen in open marks' cells on 1 and 2 Dec (every other tick): {}", bodies);
            final List<String> open = new ArrayList<>();
            for (final StreetMarks.Mark mark : p.ledger.marks(p.colonyId)) {
                if (size(mark.pos()) == 0) {
                    open.add(mark.type().id() + " " + mark.pos().toShortString());
                }
            }
            final DecorLedger.GroupView lights = p.ledger.group(p.colonyId, StreetLights.owner(p.colony));
            for (final StreetMarks.Mark mark : p.ledger.marks(p.colonyId)) {
                if (size(mark.pos()) == 0) {
                    final List<me.lovkar.seasons.decor.DecorPlacer.Slot> slots = me.lovkar.seasons.decor.StreetPieces.slots(mark,
                            CalendarManager.current().season().season());
                    final List<String> why = new ArrayList<>();
                    for (final me.lovkar.seasons.decor.DecorPlacer.Slot slot : slots) {
                        why.add(slot.pos().toShortString() + " " + me.lovkar.seasons.decor.DecorPlacer.refusal(p.level, p.ledger, p.colonyId, slot.pos(),
                                me.lovkar.seasons.decor.DecorPlacer.normalise(slot.state(), slot.pos()), true) + " under "
                                + CsFullCycle.shortName(p.level.getBlockState(slot.pos().below())));
                    }
                    CsFullCycle.note("wand: open mark {} {}: fruitless today {}, cells {}, bodies {}, the Decorator at {}", mark.type().id(),
                            mark.pos().toShortString(), p.ledger.fruitless(p.colonyId, DecorLedger.Owner.street(mark.pos()), p.day()), why,
                            me.lovkar.seasons.decor.DecorPlacer.blockers(p.level, slots).size(),
                            p.decorator.getEntity().map(e -> e.blockPosition().toShortString()).orElse("-"));
                }
            }
            p.check(key + ": every mark's pieces stand and the street lights are up", open.isEmpty() && lights != null && !lights.entries().isEmpty(),
                    "open " + open + ", lights " + (lights == null ? 0 : lights.entries().size()));
        }
        if (m == 12 && dom == 5) {
            // the tree was lit this morning (the base check): the posts' lanterns and the street lights are lit with it
            int lit = 0;
            int unlit = 0;
            for (final DecorLedger.GroupView g : p.ledger.groups(p.colonyId)) {
                if (g.owner().kind() != DecorLedger.Kind.STREET && g.owner().kind() != DecorLedger.Kind.LIGHTS) {
                    continue;
                }
                for (final BlockPos pos : g.entries().keySet()) {
                    final BlockState now = p.level.getBlockState(pos);
                    if (now.hasProperty(Lights.LIT)) {
                        if (now.getValue(Lights.LIT)) {
                            lit++;
                        } else {
                            unlit++;
                        }
                    }
                }
            }
            p.check(key + ": the posts and the street lights came on with the tree's lighting", lit > 0 && unlit == 0, lit + " lit, " + unlit + " unlit");
            // a player breaks snowman A
            final int before = size(sa);
            p.level.destroyBlock(sa, true, p.p1);
            final int drops = p.level.getEntitiesOfClass(ItemEntity.class, new AABB(sa).inflate(2)).size();
            p.check(key + ": a snowman broken by hand drops nothing (the street pieces are free)", before > 0 && drops == 0 && size(sa) < before,
                    before + " -> " + size(sa) + " entries, " + drops + " drops");
        }
        if (m == 12 && dom == 6) {
            p.check(key + ": the broken snowman is back the next game day", size(sa) > 0 && p.level.getBlockState(sa).is(SeasonsBlocks.SNOWMAN.get()),
                    size(sa) + " entries, " + CsFullCycle.shortName(p.level.getBlockState(sa)));
        }
        if (m == 12 && dom == 10) {
            final String removed = DecoratorWandItem.click(p.level, p.owner, wand(StreetMarks.Type.SNOWMAN_A), sb, Direction.UP, true);
            p.check(key + ": snowman B removed by a shift-click: the mark and its pieces gone", "com.colony_seasons.wand.removed".equals(removed)
                    && p.ledger.markAt(p.colonyId, sb) == null && size(sb) == 0 && p.level.getBlockState(sb).isAir(), removed);
            p.checkEq(key + ": a new mark in the middle of the window", click(StreetMarks.Type.SNOWMAN_A, extra), "com.colony_seasons.wand.marked");
        }
        if (m == 12 && dom == 12) {
            p.check(key + ": the new mark's snowman went up on a decorating visit", size(extra) > 0, size(extra) + " entries");
            planterBefore = p.level.getBlockState(pl);
            hutCell = pl.offset(2, 0, 1);
            plot = p.register(p.colony, "minecolonies:blockhutstonemason", new DoorFinder.Box(pl.offset(-2, -1, -1), pl.offset(2, 3, 1), hutCell), 1);
            p.probeChanged.add(hutCell);
            DecorEvents.sweep(p.level);
            p.check(key + ": a hut registered over the planter takes its group down, the mark is kept (as built)", plot != null && size(pl) == 0
                    && p.ledger.markAt(p.colonyId, pl) != null, (plot == null ? "no hut" : "hut") + ", " + size(pl) + " entries");
        }
        if (m == 12 && dom == 13 && plot != null) {
            plot.destroy();
            p.level.setBlock(hutCell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            p.probeChanged.remove(hutCell);
            p.buildings.remove(plot);
        }
        if (m == 12 && dom == 15) {
            p.check("13 to 15 Dec: with the hut gone the planter is back", size(pl) > 0, size(pl) + " entries");
            planterBefore = p.level.getBlockState(pl);
            final Season now = CalendarManager.current().season().season();
            final Season next = Season.values()[(now.ordinal() + 1) % Season.values().length];
            CalendarManager.setSeason(p.server, next);
            CsFullCycle.note("wand: the season forced from {} to {} (the planter is {})", now, next, CsFullCycle.shortName(planterBefore));
        }
        if (m == 12 && dom == 17) {
            final BlockState after = p.level.getBlockState(pl);
            p.check("15 to 17 Dec: a forced season change swaps the planter's block", size(pl) > 0 && after.getBlock() != planterBefore.getBlock(),
                    CsFullCycle.shortName(planterBefore) + " -> " + CsFullCycle.shortName(after));
        }
        if (m == 1 && dom == 10) {
            final Set<BlockPos> marks = new HashSet<>();
            for (final StreetMarks.Mark mark : p.ledger.marks(p.colonyId)) {
                marks.add(mark.pos());
            }
            final Set<BlockPos> want = new HashSet<>(List.of(sa, p1, p2, p3, pl, extra));
            p.check(key + ": after the window no street piece and no street light is listed; the marks are kept (snowman B removed, the new one added)",
                    streetEntries() == 0 && marks.size() == 7 && marks.containsAll(want), streetEntries() + " entries, " + marks.size() + " marks");
        }
    }
}
