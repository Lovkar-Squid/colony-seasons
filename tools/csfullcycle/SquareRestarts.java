package me.lovkar.seasons.csfullcycle;

import com.minecolonies.api.colony.workorders.IServerWorkOrder;
import com.minecolonies.api.colony.workorders.WorkOrderType;
import com.minecolonies.core.colony.workorders.WorkOrderBuilding;
import me.lovkar.seasons.block.SeasonsBlocks;
import me.lovkar.seasons.calendar.CalendarManager;
import me.lovkar.seasons.decor.DecorEvents;
import me.lovkar.seasons.decor.DecorLedger;
import me.lovkar.seasons.decor.Squares;
import me.lovkar.seasons.festival.FestivalData;
import me.lovkar.seasons.festival.Fireworks;
import me.lovkar.seasons.festival.Gathering;
import me.lovkar.seasons.festival.Gifts;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Q7's restart sets (tools/restart.sh csfullcycle square|migrate), each phase one server run on one kept world.
 *
 * <p>square (phases-square.txt): square-building's colony cut in every phase of the Decorator's year: r1 Preparing (30 Nov
 * evening, the touches up); r2 Decorating (1 Dec, some of the colony decorated, some not; the square's own tree goes up in
 * one visit, as it is fewer cells than one slice); r3 Tending (12 Dec: a wreath broken that day and an unworked REPAIR
 * order open on the square, its groups down); r4 Hosting (25 Dec inside the gathering hours, after the order was closed in
 * r4's own run); r5 Launching (31 Dec, the dusk show begun, a rocket up); r6 Taking down (7 Jan after the first take-down
 * visit); r7 plays to 10 Jan and the end check. After every load: the entries, the groups (kind, position, size), the
 * square's mark, the day's budget, the tree's lit state and lights equal what was saved, and I1 to I8 hold.</p>
 *
 * <p>migrate (phases-migrate.txt): migrate-marker's colony cut on 8 Dec; before the ledger loads, migrate-load rewrites its
 * world's {@code data/colony_seasons_decor.dat} to what 0.1.0-beta.1 writes (version 2, none of the keys 0.2.0 added) and
 * the plan continues with the square to 10 Jan.</p>
 */
final class SquareRestarts {
    private final CsFullCycle p;
    private final SquareCycle sc;
    /** The 0.1.0 keys of a colony record and of a group (git show v0.1.0-beta.1:src/me/lovkar/seasons/decor/DecorLedger.java). */
    static final Set<String> COLONY_KEYS_010 = Set.of("id", "budgetDay", "budgetUsed", "charged", "marker", "noticeDay", "squareProblem", "groups");
    static final Set<String> GROUP_KEYS_010 = Set.of("kind", "pos", "festival", "level", "piece", "decoratedDay", "tendedDay", "treeDay", "removing",
            "palette", "positions", "states", "paid");
    /** What migrate-load's rewrite found and did (checked once the plan runs). */
    static String rewrite = "";
    static boolean rewriteOk;

    SquareRestarts(final CsFullCycle probe, final SquareCycle square) {
        this.p = probe;
        this.sc = square;
    }

    /** False for a mode that is not one of the restart phases. */
    boolean plan(final String mode) {
        switch (mode) {
            case "square-r1" -> {
                p.planSetup();
                p.planDates(CsFullCycle.FIRST, LocalDate.of(2026, 11, 30), false);
                p.planSave(mode);
            }
            case "square-r2" -> {
                p.planFind(mode);
                cutMidDecorating(mode);
                p.planSave(mode);
            }
            case "square-r3" -> {
                p.planFind(mode);
                p.planDates(SquareCycle.DEC_1, LocalDate.of(2026, 12, 11), false);
                cutWithOrder(mode, LocalDate.of(2026, 12, 12));
                p.planSave(mode);
            }
            case "square-r4" -> {
                p.planFind(mode);
                closeOrder(mode);
                p.planDates(LocalDate.of(2026, 12, 13), LocalDate.of(2026, 12, 24), false);
                cutInHours(mode, LocalDate.of(2026, 12, 25));
                p.planSave(mode);
            }
            case "square-r5" -> {
                p.planFind(mode);
                restOfHours(mode, LocalDate.of(2026, 12, 25));
                p.planDates(LocalDate.of(2026, 12, 26), LocalDate.of(2026, 12, 30), false);
                cutInDusk(mode, LocalDate.of(2026, 12, 31));
                p.planSave(mode);
            }
            case "square-r6" -> {
                p.planFind(mode);
                restOfDusk(mode, LocalDate.of(2026, 12, 31));
                p.planDates(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 7), true);
                p.planSave(mode);
            }
            case "square-r7" -> {
                p.planFind(mode);
                p.planDates(LocalDate.of(2027, 1, 7), CsFullCycle.LAST, false);
                p.planEnd();
            }
            case "migrate-save" -> {
                p.planSetup();
                p.planDates(CsFullCycle.FIRST, LocalDate.of(2026, 12, 8), false);
                p.planSave(mode);
            }
            case "migrate-load" -> {
                p.step(new CsFullCycle.Step("migrate-load: the ledger file was rewritten to 0.1.0's shape before it loaded", 1) {
                    @Override
                    void start() {
                        p.check("migrate-load: the ledger file had 0.1.0's shape when the world loaded (version 2, only 0.1.0's keys)", rewriteOk, rewrite);
                    }
                });
                p.planFind(mode);
                sc.planMigrate(LocalDate.of(2026, 12, 9));
                p.planEnd();
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    // ================================================================ migrate-load: the 0.1.0 file

    /**
     * Run from the probe's constructor in mode migrate-load, before the server loads the world: the ledger's file is
     * written again as 0.1.0-beta.1 writes it (version 2; a colony record and a group with 0.1.0's keys only; no owner
     * kind 0.1.0 does not know). The square's keys cannot be there yet (the square comes on 9 Dec), so this is a check
     * that 8 Dec's file is one 0.1.0 wrote too, and the file 0.2.0 must read.
     */
    static void rewriteTo010() {
        final Path file = Path.of("world", "data", DecorLedger.FILE + ".dat");
        try {
            if (!Files.exists(file)) {
                rewrite = "no file at " + file;
                return;
            }
            final CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            final CompoundTag data = root.getCompound("data");
            final int was = data.getInt("version");
            data.putInt("version", 2);
            final List<String> dropped = new ArrayList<>();
            boolean kindsOk = true;
            final ListTag colonies = data.getList("colonies", Tag.TAG_COMPOUND);
            for (int i = 0; i < colonies.size(); i++) {
                final CompoundTag one = colonies.getCompound(i);
                for (final String key : new ArrayList<>(one.getAllKeys())) {
                    if (!COLONY_KEYS_010.contains(key)) {
                        one.remove(key);
                        dropped.add(key);
                    }
                }
                final ListTag groups = one.getList("groups", Tag.TAG_COMPOUND);
                for (int g = 0; g < groups.size(); g++) {
                    final CompoundTag group = groups.getCompound(g);
                    kindsOk &= Set.of("BUILDING", "SQUARE", "STOREROOM").contains(group.getString("kind"));
                    for (final String key : new ArrayList<>(group.getAllKeys())) {
                        if (!GROUP_KEYS_010.contains(key)) {
                            group.remove(key);
                            dropped.add("group." + key);
                        }
                    }
                }
            }
            NbtIo.writeCompressed(root, file);
            final CompoundTag back = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getCompound("data");
            boolean shape = back.getInt("version") == 2 && kindsOk;
            final ListTag again = back.getList("colonies", Tag.TAG_COMPOUND);
            for (int i = 0; i < again.size(); i++) {
                shape &= COLONY_KEYS_010.containsAll(again.getCompound(i).getAllKeys());
            }
            rewriteOk = shape && again.size() > 0;
            rewrite = "version " + was + " -> 2, " + again.size() + " colonies, dropped " + dropped + ", kinds 0.1.0's " + kindsOk;
        } catch (final java.io.IOException unreadable) {
            rewrite = "unreadable: " + unreadable.getClass().getSimpleName();
        }
    }

    // ================================================================ the cuts

    /** r2: 1 Dec until part of the colony is decorated and part is not (the Decorating phase). */
    private void cutMidDecorating(final String phase) {
        p.step(p.day(SquareCycle.DEC_1, phase + ": 1 Dec until the colony is half decorated", CsFullCycle.EVENING, p.new DayHook() {
            @Override
            boolean done() {
                final String open = p.undecorated();
                return p.t() % 10 == 0 && p.ledger.countOutdoor(p.colonyId) > 0 && !open.isEmpty() && p.ledger.groups(p.colonyId).size() >= 3;
            }

            @Override
            void end(final boolean done) {
                p.check(phase + ": cut while the Decorator decorates (some groups up, some buildings open)", done, p.ledger.countOutdoor(p.colonyId)
                        + " outdoor entries in " + p.ledger.groups(p.colonyId).size() + " groups, open: " + p.undecorated());
            }
        }));
    }

    /** r3: 12 Dec: a wreath broken that day, then an unworked REPAIR order on the square: its groups come down. */
    private void cutWithOrder(final String phase, final LocalDate d) {
        p.step(p.day(d, phase + ": " + d + " (a wreath broken, a REPAIR order on the square)", CsFullCycle.EVENING, p.new DayHook() {
            @Override
            void start() {
                p.giftOnce(d.toString(), Gifts.DAILY);
            }

            @Override
            void end(final boolean done) {
                BlockPos wreath = null;
                for (final DecorLedger.GroupView g : p.ledger.groups(p.colonyId)) {
                    for (final Map.Entry<BlockPos, DecorLedger.Entry> e : g.entries().entrySet()) {
                        if (wreath == null && g.owner().kind() == DecorLedger.Kind.BUILDING && e.getValue().paid()
                                && e.getValue().state().is(SeasonsBlocks.WREATH.get())) {
                            wreath = e.getKey();
                        }
                    }
                }
                if (wreath != null) {
                    p.level.setBlock(wreath, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    p.held.merge(SeasonsBlocks.WREATH_ITEM.get(), 1, Integer::sum);
                    p.brokenWreath = wreath;
                }
                p.check(phase + ": a wreath broken this game day (tending puts it back after the restart)", wreath != null, String.valueOf(wreath));
            }
        }));
        p.step(new CsFullCycle.Step(phase + ": the order takes the square's groups down", DecorEvents.SQUARE_WATCH * 4) {
            @Override
            void start() {
                p.colony.getWorkManager().addWorkOrder(WorkOrderBuilding.create(WorkOrderType.REPAIR, sc.square), false);
            }

            @Override
            boolean tick() {
                return SquareCycle.size(sc.set()) + SquareCycle.size(sc.touches()) == 0;
            }

            @Override
            void end(final boolean done) {
                final Squares.Square s = Squares.of(p.level, p.colony, p.ledger);
                p.check(phase + ": the REPAIR order is open and unworked, the square busy, both its groups down (within the watch)", done && s.busy()
                        && orderOnSquare() != null, "after " + t + " ticks, busy " + s.busy());
                p.daily(phase + " with the order open");
            }
        });
    }

    IServerWorkOrder orderOnSquare() {
        for (final IServerWorkOrder wo : p.colony.getWorkManager().getWorkOrders().values()) {
            if (wo.getLocation() != null && wo.getLocation().equals(sc.square.getPosition())) {
                return wo;
            }
        }
        return null;
    }

    /** r4 after the load: still busy, nothing placed meanwhile, the marker never used; the order closed, the set returns. */
    private void closeOrder(final String phase) {
        p.step(new CsFullCycle.Step(phase + ": the square is still busy after the restart", 600, true) {
            @Override
            void start() {
                p.setDate(LocalDate.of(2026, 12, 12));
                p.newGameDay(CsFullCycle.MORNING);
            }

            @Override
            boolean tick() {
                if (SquareCycle.size(sc.set()) + SquareCycle.size(sc.touches()) > 0) {
                    return true;
                }
                return false;
            }

            @Override
            void end(final boolean done) {
                final Squares.Square s = Squares.of(p.level, p.colony, p.ledger);
                p.check(phase + ": a game day's morning with the order open: the square busy, both groups empty, nothing placed, no marker used",
                        !done && s.busy() && orderOnSquare() != null && p.ledger.marker(p.colonyId) == null, "busy " + s.busy() + ", set "
                                + SquareCycle.size(sc.set()) + ", touches " + SquareCycle.size(sc.touches()));
                final IServerWorkOrder wo = orderOnSquare();
                if (wo != null) {
                    p.colony.getWorkManager().removeWorkOrder(wo);
                }
                DecorEvents.squareChanged(sc.square);
                DecorEvents.drain(p.server);
            }
        });
        p.step(p.day(LocalDate.of(2026, 12, 12), phase + ": the order closed, the set returns", CsFullCycle.EVENING, p.new DayHook() {
            @Override
            boolean done() {
                final DecorLedger.GroupView g = sc.set();
                return p.t() % 20 == 0 && g != null && g.decoratedDay() >= 0 && p.brokenWreath != null && p.ledger.at(p.brokenWreath) != null;
            }

            @Override
            void end(final boolean done) {
                final DecorLedger.GroupView g = sc.set();
                p.check(phase + ": after the order the square's set is back and the wreath broken before the restart is back too", done,
                        (g == null ? "no group" : g.entries().size() + " entries, decorated " + g.decoratedDay()) + ", wreath " + p.brokenWreath
                                + " " + (p.brokenWreath == null ? "-" : String.valueOf(p.ledger.at(p.brokenWreath))));
            }
        }));
    }

    /** r4: 25 Dec into the gathering hours (the gift taken first), then cut. */
    private void cutInHours(final String phase, final LocalDate d) {
        p.step(p.day(d, phase + ": " + d + " until inside the gathering hours", CsFullCycle.AFTER_HOURS, p.new DayHook() {
            @Override
            void start() {
                p.giftOnce(d.toString(), Gifts.FESTIVAL);
            }

            @Override
            boolean done() {
                return p.timeOfDay() >= Gathering.hours()[0] + 1500;
            }

            @Override
            void end(final boolean done) {
                p.server.tickRateManager().setTickRate(20);
                p.check(phase + ": cut inside the gathering hours, the Decorator hosting", done && Gathering.inHours(p.level), "time " + p.timeOfDay()
                        + ", phase " + (p.job() == null ? "-" : p.job().phase()));
            }
        }));
    }

    /** r5 after the load: the rest of the hours; the crowd is at the square again and a second gift today is refused. */
    private void restOfHours(final String phase, final LocalDate d) {
        p.step(new CsFullCycle.Step(phase + ": the rest of the gathering hours", 12000, false) {
            int samples;
            int near;

            @Override
            void start() {
                p.setDate(d);
                p.server.tickRateManager().setTickRate(CsFullCycle.GATHER_TICK_RATE);   // the pathfinder's time, as the gathering's day hook gives it
                final BlockPos pile = p.pile();
                p.checkEq(phase + ": a second gift on 25 Dec after the restart is refused", pile == null ? null : Gifts.take(p.level, pile, p.p1),
                        Gifts.Outcome.ALREADY);
            }

            @Override
            boolean tick() {
                if (t % 40 == 0 && Gathering.inHours(p.level)) {
                    for (final com.minecolonies.api.colony.ICitizenData c : p.jobless()) {
                        samples++;
                        near += CsFullCycle.distTo(c, sc.anchor) <= Gathering.SQUARE_KEEP_RADIUS ? 1 : 0;
                    }
                }
                return p.timeOfDay() >= CsFullCycle.AFTER_HOURS;
            }

            @Override
            void end(final boolean done) {
                p.server.tickRateManager().setTickRate(20);
                p.check(phase + ": the crowd is back at the square after the restart (most samples within its keep radius)", samples > 0
                        && near * 2 >= samples, near + " of " + samples);
                p.daily(phase + " after the hours");
            }
        });
    }

    /** r5: 31 Dec until its dusk show began and a rocket is up; the stock at the dusk's start and the rockets flown are saved. */
    private void cutInDusk(final String phase, final LocalDate d) {
        p.step(p.day(d, phase + ": " + d + " until the dusk show began", CsFullCycle.LATE, p.new DayHook() {
            @Override
            void start() {
                p.giftOnce(d.toString(), Gifts.NEW_YEAR);
                duskStock = p.rocketsInStock();
                duskFlown = Fireworks.rockets();
                p.stockNewYear = duskStock;
            }

            @Override
            boolean done() {
                return Fireworks.rockets() > duskFlown;
            }

            @Override
            void end(final boolean done) {
                p.check(phase + ": cut in the dusk show, a rocket up", done, (Fireworks.rockets() - duskFlown) + " flew, stock " + p.rocketsInStock());
                sc.extra.putInt("duskStock", duskStock);
                sc.extra.putInt("duskFlown", Fireworks.rockets() - duskFlown);
            }
        }));
    }

    int duskStock;
    int duskFlown;

    /** r6 after the load: the rest of the dusk; across both runs the show took at most one show's rockets from the stock. */
    private void restOfDusk(final String phase, final LocalDate d) {
        p.step(new CsFullCycle.Step(phase + ": the rest of 31 Dec's dusk", 6000, true) {
            int flown0;

            @Override
            void start() {
                p.setDate(d);
                flown0 = Fireworks.rockets();
            }

            @Override
            boolean tick() {
                return p.timeOfDay() >= CsFullCycle.LATE;
            }

            @Override
            void end(final boolean done) {
                final int stock0 = p.facts.getInt("duskStock");
                final int before = p.facts.getInt("duskFlown");
                final int used = stock0 - p.rocketsInStock();
                final int flown = before + Fireworks.rockets() - flown0;
                p.check(phase + ": one dusk takes at most one show's rockets from the stock across the restart, every one of them flown",
                        used <= Fireworks.SHOW_ROCKETS && used == flown && used > 0, used + " from stock, " + flown + " flew (" + before + " before the restart)");
                // the 1 Jan check counts the New Year's rockets from the eve's stock: what flew before this run counts too
                p.stockNewYear = stock0;
                p.flownNewYear = flown0 - before;
                p.rocketsAdded = 0;
                p.daily(phase + " after the dusk");
            }
        });
        p.planMidnight();
    }

    // ================================================================ after every load

    /** What every square phase saves beyond the base facts: the groups (kind, position, size), the budget, the lit lights. */
    static void saveGroups(final CsFullCycle p, final CompoundTag f) {
        final ListTag groups = new ListTag();
        for (final DecorLedger.GroupView g : p.ledger.groups(p.colonyId)) {
            final CompoundTag one = new CompoundTag();
            one.putString("kind", g.owner().kind().name());
            one.putLong("pos", g.owner().pos().asLong());
            one.putInt("size", g.entries().size());
            groups.add(one);
        }
        f.put("groupList", groups);
        f.putInt("budget", p.ledger.budgetUsed(p.colonyId, p.day()));
        f.putLong("gameDay", p.day());
        final DecorLedger.SquareMark mark = p.ledger.squareMark(p.colonyId);
        if (mark != null) {
            f.putLong("markBuilding", mark.building().asLong());
            f.putLong("markAnchor", mark.anchor().asLong());
        }
    }

    static void compareGroups(final CsFullCycle p, final String phase, final CompoundTag f) {
        if (!f.contains("groupList")) {
            return;
        }
        final List<String> saved = new ArrayList<>();
        for (final Tag t : f.getList("groupList", Tag.TAG_COMPOUND)) {
            final CompoundTag one = (CompoundTag) t;
            saved.add(one.getString("kind") + " " + BlockPos.of(one.getLong("pos")).toShortString() + " " + one.getInt("size"));
        }
        final List<String> now = new ArrayList<>();
        for (final DecorLedger.GroupView g : p.ledger.groups(p.colonyId)) {
            now.add(g.owner().kind().name() + " " + g.owner().pos().toShortString() + " " + g.entries().size());
        }
        p.check(phase + ": the groups read back the same (kind, position, size)", new java.util.TreeSet<>(saved).equals(new java.util.TreeSet<>(now)),
                "saved " + saved + ", now " + now);
        final DecorLedger.SquareMark mark = p.ledger.squareMark(p.colonyId);
        final String want = f.contains("markBuilding") ? BlockPos.of(f.getLong("markBuilding")).toShortString() + " " + BlockPos.of(f.getLong("markAnchor")).toShortString() : "-";
        final String got = mark == null ? "-" : mark.building().toShortString() + " " + mark.anchor().toShortString();
        p.checkEq(phase + ": the square's mark reads back the same", got, want);
        p.checkEq(phase + ": the day's budget reads back the same", p.ledger.budgetUsed(p.colonyId, f.getLong("gameDay")), f.getInt("budget"));
        final FestivalData.ColonyState s = FestivalData.of(p.level).peek(p.colonyId);
        p.check(phase + ": the tree's lit state and its lit lights read back the same", (s != null && s.treeLit()) == f.getBoolean("lit")
                && p.lights()[0] == f.getInt("lights"), "lit " + (s != null && s.treeLit()) + ", " + p.lights()[0] + " lit lights, saved "
                + f.getBoolean("lit") + " and " + f.getInt("lights"));
    }
}
