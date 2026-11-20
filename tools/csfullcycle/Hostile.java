package me.lovkar.seasons.csfullcycle;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.mojang.authlib.GameProfile;
import me.lovkar.seasons.SeasonsConfig;
import me.lovkar.seasons.ai.EntityAIWorkDecorator;
import me.lovkar.seasons.block.FestivalMarkerBlock;
import me.lovkar.seasons.block.FireworkCrateBlock;
import me.lovkar.seasons.block.GiftPileBlock;
import me.lovkar.seasons.block.LargeStarBlock;
import me.lovkar.seasons.block.Lights;
import me.lovkar.seasons.block.SeasonsBlocks;
import me.lovkar.seasons.calendar.CalendarManager;
import me.lovkar.seasons.colony.BuildingDecorator;
import me.lovkar.seasons.colony.DecoratorModules;
import me.lovkar.seasons.colony.DecoratorSetup;
import me.lovkar.seasons.colony.JobDecorator;
import me.lovkar.seasons.decor.DecorEvents;
import me.lovkar.seasons.decor.DecorLedger;
import me.lovkar.seasons.decor.DecorPlacer;
import me.lovkar.seasons.decor.DecorWork;
import me.lovkar.seasons.decor.DoorFinder;
import me.lovkar.seasons.decor.Kits;
import me.lovkar.seasons.decor.PieceLoader;
import me.lovkar.seasons.festival.FestivalData;
import me.lovkar.seasons.festival.FestivalDirector;
import me.lovkar.seasons.festival.Gathering;
import me.lovkar.seasons.festival.Gifts;
import me.lovkar.seasons.festival.TreeLighting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.items.ItemHandlerHelper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The hostile review (S9): attacks on the mod by someone trying to break it, each written as checks against the rig.
 * Modes:
 * <ul>
 *   <li>hostile-items: dupes. H1 two colonies' piles in one tick and the claim through a save and load; H2 a pile a
 *       player crafted on a listed tree cell that went (a stale entry); H3 a gift used with a full inventory, a stack of
 *       two; H4 one group taken down three ways in one tick; H5 a player breaks a paid wreath; H5b a player breaks the
 *       free gift pile and crate of the tree (since S10d they drop nothing); H6 no cycle through the kits and recipes.</li>
 *   <li>hostile-free (decorator.needsMaterials = false): H7 a player breaks wreaths and logs every game day and tending
 *       puts them back (since S10d nothing he put up for free drops).</li>
 *   <li>hostile-world: H8 the grand tree with empty, then partial stock; H9 its take-down in slices; H9b the server
 *       sweep's slices; H9c a slice boundary at every cell (review); H10 chunks that unload; H11 five years; H12 a deleted colony; H13 two colonies with touching
 *       claims; H14 a second marker; H15 the marker broken in the gathering hours; H16 the hut destroyed while he hosts
 *       and rebuilt at level 1; H17 a player breaks ten tree cells; H18 decor remove in the middle of the festival (what
 *       uninstalling needs); H19 a ledger file of a newer version.</li>
 * </ul>
 * A finding that goes to the owner instead of a fix prints "NOTE FINDING H.. owner:ask|fyi ..." and the mode still
 * passes.
 */
final class Hostile {
    private final CsFullCycle p;
    private static final LocalDate DECOR_DAY = LocalDate.of(2026, 12, 10);
    private static final LocalDate FESTIVAL_DAY = LocalDate.of(2026, 12, 25);
    private static final LocalDate AFTER = LocalDate.of(2027, 1, 10);

    private IBuilding h1;
    private IBuilding h2;
    private Snap area;
    private Snap houses;

    Hostile(final CsFullCycle probe) {
        this.p = probe;
    }

    void plan(final String mode) {
        switch (mode) {
            case "hostile-items" -> {
                setup(true);
                planItems();
            }
            case "hostile-free" -> {
                setup(true);
                planFree();
            }
            case "hostile-world" -> {
                setup(true);
                planWorld();
            }
            default -> {
                p.check("known mode", false, "'" + mode + "'");
                p.finish();
            }
        }
    }

    // ================================================================ helpers

    private ServerLevel level() {
        return p.level;
    }

    private DecorLedger ledger() {
        return p.ledger;
    }

    private long day() {
        return p.day();
    }

    /** A stock that hands out what {@code gives} allows (as many as asked) and counts what it gave and got back. */
    static final class Counting implements DecorPlacer.Stock {
        final Predicate<Item> gives;
        final Map<Item, Integer> taken = new HashMap<>();
        final Map<Item, Integer> back = new HashMap<>();

        Counting(final Predicate<Item> gives) {
            this.gives = gives;
        }

        @Override
        public boolean take(final Item item) {
            if (!gives.test(item)) {
                return false;
            }
            taken.merge(item, 1, Integer::sum);
            return true;
        }

        @Override
        public void give(final ItemStack stack) {
            back.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }

        @Override
        public boolean has(final Item item) {
            return gives.test(item);
        }

        int takenAll() {
            return taken.values().stream().mapToInt(Integer::intValue).sum();
        }

        int backAll() {
            return back.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    private FakePlayer player(final String name, final IColony... friendOf) {
        final FakePlayer f = FakePlayerFactory.get(level(), new GameProfile(UUID.nameUUIDFromBytes(("csfullcycle-" + name).getBytes(StandardCharsets.US_ASCII)),
                "csfc_" + name));
        f.setPos(2.5, p.ground + 1, 12.5);
        for (final IColony c : friendOf) {
            c.getPermissions().addPlayer(f.getGameProfile(), c.getPermissions().getRankFriend());
        }
        return f;
    }

    private DecorWork.Target squareOf(final BlockPos marker) {
        return DecorWork.Target.square(marker, Direction.NORTH, PieceLoader.PACK);
    }

    /** Paid entries of a group that still stand as recorded (what a take-down hands back). */
    private int paidStanding(final int colony, final DecorLedger.Owner owner) {
        final DecorLedger.GroupView g = ledger().group(colony, owner);
        int n = 0;
        if (g != null) {
            for (final Map.Entry<BlockPos, DecorLedger.Entry> e : g.entries().entrySet()) {
                if (e.getValue().paid() && DecorPlacer.itemOf(e.getValue().state()) != null && level().getBlockState(e.getKey()) == e.getValue().state()) {
                    n++;
                }
            }
        }
        return n;
    }

    private int size(final int colony, final DecorLedger.Owner owner) {
        final DecorLedger.GroupView g = ledger().group(colony, owner);
        return g == null ? 0 : g.size();
    }

    private BlockPos firstOf(final int colony, final DecorLedger.Owner owner, final Predicate<BlockState> what) {
        final DecorLedger.GroupView g = ledger().group(colony, owner);
        if (g != null) {
            for (final Map.Entry<BlockPos, DecorLedger.Entry> e : g.entries().entrySet()) {
                if (what.test(e.getValue().state()) && level().getBlockState(e.getKey()) == e.getValue().state()) {
                    return e.getKey();
                }
            }
        }
        return null;
    }

    private int drops(final BlockPos at, final double r, final Predicate<ItemStack> what) {
        int n = 0;
        for (final ItemEntity e : level().getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(r), e -> what.test(e.getItem()))) {
            n += e.getItem().getCount();
        }
        return n;
    }

    private Map<Item, Integer> pickUp(final BlockPos at, final double r) {
        final Map<Item, Integer> got = new LinkedHashMap<>();
        for (final ItemEntity e : level().getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(r))) {
            got.merge(e.getItem().getItem(), e.getItem().getCount(), Integer::sum);
            e.discard();
        }
        return got;
    }

    private int hutItems(final BuildingDecorator hut) {
        return hut == null ? 0 : BuildingDecorator.countIn(hut.getItemHandlerCap(), s -> true);
    }

    /** Every listed cell of the colony equals the world. */
    private boolean entriesEqualWorld(final int colony, final String[] first) {
        for (final Map.Entry<BlockPos, DecorLedger.Entry> e : ledger().entries(colony).entrySet()) {
            if (level().hasChunkAt(e.getKey()) && level().getBlockState(e.getKey()) != e.getValue().state()) {
                first[0] = e.getKey().toShortString() + " " + CsFullCycle.shortName(level().getBlockState(e.getKey()));
                return false;
            }
        }
        return true;
    }

    private String areaDiff(final Snap snap) {
        final int[] total = new int[1];
        final List<BlockPos> d = snap.diff(level(), x -> true, 6, total);
        final StringBuilder b = new StringBuilder(total[0] + " cells differ");
        for (final BlockPos pos : d) {
            b.append("; ").append(pos.toShortString()).append(' ').append(CsFullCycle.shortName(level().getBlockState(pos)))
                    .append(" was ").append(CsFullCycle.shortName(snap.at(pos)));
        }
        return b.toString();
    }

    private int areaDiffCount(final Snap snap) {
        final int[] total = new int[1];
        snap.diff(level(), x -> true, 0, total);
        return total[0];
    }

    private void finding(final String id, final String kind, final String text, final Object... args) {
        CsFullCycle.note("FINDING " + id + " owner:" + kind + " " + text, args);
    }

    // ================================================================ setup

    /** Colony A: a town hall, a Decorator's hut (level 3, nobody hired), two houses and a marker; the date in the window. */
    private void setup(final boolean marker) {
        p.step(new CsFullCycle.Step("hostile setup: colony A", 120, true) {
            @Override
            void start() {
                p.setDate(DECOR_DAY);
                p.quietWorld();
                p.ground = level().getHeight(Heightmap.Types.MOTION_BLOCKING, 2, 2) - 1;
                final BlockPos center = new BlockPos(2, p.ground + 1, 2);
                p.forceChunks(7);
                p.owner = FakePlayerFactory.getMinecraft(level());
                p.colony = IColonyManager.getInstance().createColony(level(), center, p.owner, "Seasons Hostile A", "Medieval Oak");
                p.colonyId = p.colony == null ? -1 : p.colony.getID();
                p.check("colony A was made", p.colony != null, "id " + p.colonyId);
                if (p.colony == null) {
                    p.finish();
                    return;
                }
                p.townHall = p.register(p.colony, "minecolonies:blockhuttownhall", p.house(-3, -1, 11, 7, 2), 1);
                final IBuilding d = p.register(p.colony, BuiltInRegistries.BLOCK.getKey(DecoratorSetup.HUT.get()).toString(), p.house(12, -1, 11, 7, 17), 3);
                p.hut = d instanceof BuildingDecorator b ? b : null;
                h1 = p.register(p.colony, "minecolonies:blockhutlumberjack", p.house(-40, 16, 11, 7, -35), 1);
                h2 = p.register(p.colony, "minecolonies:blockhutstonemason", p.house(-22, 16, 11, 7, -17), 1);
                p.buildings.addAll(List.of(p.townHall, p.hut, h1, h2));
                if (marker) {
                    p.marker = new BlockPos(2, p.ground + 1, 32);
                    p.placeMarker();
                }
                p.players();
                p.setTimeOfDay(CsFullCycle.MORNING);
            }

            @Override
            boolean tick() {
                return t >= 60 && !level().getChunkSource().getLightEngine().hasLightWork();
            }

            @Override
            void end(final boolean done) {
                if (p.marker != null) {
                    area = Snap.take(level(), p.marker.offset(-12, -2, -12), p.marker.offset(12, 30, 12));
                }
                houses = Snap.take(level(), new BlockPos(-45, p.ground - 1, -5), new BlockPos(26, p.ground + 10, 26));
            }
        });
    }

    // ================================================================ hostile-items

    private IColony b;
    private int bId;
    private BlockPos markerB;

    private void planItems() {
        p.step(new CsFullCycle.Step("H1: colony B with its own tree", 40, true) {
            @Override
            void start() {
                for (int cx = 14; cx <= 24; cx++) {
                    for (int cz = -4; cz <= 4; cz++) {
                        level().setChunkForced(cx, cz, true);
                    }
                }
                final FakePlayer ownerB = player("ownerb");
                b = IColonyManager.getInstance().createColony(level(), new BlockPos(300, p.ground + 1, 2), ownerB, "Seasons Hostile B", "Colonial");
                bId = b == null ? -1 : b.getID();
                p.check("colony B was made", b != null, "id " + bId);
                if (b == null) {
                    p.finish();
                    return;
                }
                p.register(b, "minecolonies:blockhuttownhall", p.house(295, -1, 11, 7, 300), 1);
                markerB = new BlockPos(300, p.ground + 1, 32);
                final BlockState m = SeasonsBlocks.FESTIVAL_MARKER.get().defaultBlockState().setValue(FestivalMarkerBlock.FACING, Direction.NORTH);
                level().setBlock(markerB, m, Block.UPDATE_ALL);
                SeasonsBlocks.FESTIVAL_MARKER.get().setPlacedBy(level(), markerB, m, ownerB, new ItemStack(SeasonsBlocks.FESTIVAL_MARKER_ITEM.get()));
                p.checkEq("colony B's marker is its square", ledger().marker(bId), markerB);
            }

            @Override
            boolean tick() {
                return t >= 30;
            }
        });
        p.step(new CsFullCycle.Step("H1: both trees go up (hut level 3, free stock)", 600, true) {
            DecorWork.Outcome a;
            DecorWork.Outcome o;

            @Override
            boolean tick() {
                if (t % 5 == 0) {
                    if (a == null || !a.complete()) {
                        a = DecorWork.decorate(level(), p.colonyId, squareOf(p.marker), 3, DecorPlacer.Stock.FREE, day(), ledger());
                    }
                    if (o == null || !o.complete()) {
                        o = DecorWork.decorate(level(), bId, squareOf(markerB), 3, DecorPlacer.Stock.FREE, day(), ledger());
                    }
                }
                return a != null && a.complete() && o != null && o.complete();
            }

            @Override
            void end(final boolean done) {
                p.check("H1: both trees are up", done, (a == null ? "-" : a.status()) + " / " + (o == null ? "-" : o.status()));
            }
        });
        p.step(new CsFullCycle.Step("H1, H3: the gift claims", 1) {
            @Override
            void start() {
                final BlockPos pileA = firstOf(p.colonyId, p.square(), s -> s.getBlock() instanceof GiftPileBlock);
                final BlockPos pileB = firstOf(bId, DecorLedger.Owner.square(markerB), s -> s.getBlock() instanceof GiftPileBlock);
                final FakePlayer x = player("both", p.colony, b);
                final Gifts.Outcome one = pileA == null ? null : Gifts.take(level(), pileA, x);
                final Gifts.Outcome two = pileB == null ? null : Gifts.take(level(), pileB, x);
                p.check("H1: a player of two colonies clicks both piles in one tick: one gift", one == Gifts.Outcome.GIVEN && two == Gifts.Outcome.ALREADY,
                        one + ", " + two);
                final FestivalData data = FestivalData.get(p.server);
                final CompoundTag saved = data.save(new CompoundTag(), level().registryAccess());
                final FestivalData loaded = FestivalData.load(saved, level().registryAccess());
                final long today = CalendarManager.current().date().toEpochDay();
                p.checkEq("H1: the claim is in the saved festival data (a restart refuses the second click: restart-mid proves it on disk)",
                        loaded.giftsOn(x.getUUID(), today), 1);

                // H3: a full inventory, a stack of two gifts
                final FakePlayer z = player("full", p.colony);
                z.getInventory().clearContent();
                final ItemStack two2 = Gifts.gift(Gifts.DAILY);
                two2.setCount(2);
                z.getInventory().setItem(0, two2);
                for (int slot = 1; slot < z.getInventory().items.size(); slot++) {
                    z.getInventory().setItem(slot, new ItemStack(Items.STONE, 64));
                }
                z.getInventory().selected = 0;
                final BlockPos at = z.blockPosition();
                final int before = drops(at, 4, s -> true);
                final int opened0 = Gifts.opened();
                z.gameMode.useItem(z, level(), z.getInventory().getItem(0), InteractionHand.MAIN_HAND);
                final int stone = CsFullCycle.count(z, s -> s.is(Items.STONE));
                final ItemStack left = z.getInventory().getItem(0);
                final int dropped = drops(at, 4, s -> true) - before;
                p.check("H3: a gift opened with a full inventory: one of the two is used, what it holds drops at the player, nothing else moves",
                        Gifts.opened() == opened0 + 1 && left.is(Gifts.GIFT.get()) && left.getCount() == 1 && stone == 35 * 64 && dropped > 0,
                        "gifts left " + left.getCount() + ", " + dropped + " items dropped, stone " + stone);
                pickUp(at, 4);
            }
        });
        p.step(new CsFullCycle.Step("H5b: a player breaks the tree's free gift pile and crate", 200, true) {
            final Map<Item, Integer> made = new LinkedHashMap<>();
            DecorWork.Outcome back;

            @Override
            void start() {
                final FakePlayer w = player("breaker", b);
                for (final Predicate<BlockState> what : List.<Predicate<BlockState>>of(s -> s.getBlock() instanceof GiftPileBlock,
                        s -> s.getBlock() instanceof FireworkCrateBlock)) {
                    final BlockPos pos = firstOf(bId, DecorLedger.Owner.square(markerB), what);
                    if (pos != null) {
                        final boolean paid = ledger().at(pos) != null && ledger().at(pos).entry().paid();
                        level().destroyBlock(pos, true, w);
                        for (final Map.Entry<Item, Integer> e : pickUp(pos, 2).entrySet()) {
                            made.merge(e.getKey(), e.getValue(), Integer::sum);
                        }
                        p.check("H5b: the broken " + CsFullCycle.shortName(SeasonsBlocks.GIFT_PILE.get().defaultBlockState()) + "/crate cell was free (unpaid) and is forgotten",
                                !paid && ledger().at(pos) == null, pos.toShortString());
                    }
                }
            }

            @Override
            boolean tick() {
                if (t % 5 == 0) {
                    back = DecorWork.tend(level(), bId, squareOf(markerB), 3, new Counting(i -> false), day() + t / 5, ledger());
                }
                return back != null && firstOf(bId, DecorLedger.Owner.square(markerB), s -> s.getBlock() instanceof GiftPileBlock) != null;
            }

            @Override
            void end(final boolean done) {
                final int items = made.values().stream().mapToInt(Integer::intValue).sum();
                p.check("H5b: tending puts the free blocks back without stock", done, back == null ? "-" : back.status());
                // S10d, the owner's answer of 20 Nov 2026 (was the finding H5b): the tree's gift pile and crate drop nothing
                p.check("H5b: the broken gift pile and crate dropped nothing (the owner's answer of 20 Nov 2026)", items == 0,
                        items + " items (" + CsFullCycle.text(made) + ")");
            }
        });
        p.step(new CsFullCycle.Step("H2: a pile a player crafted on a tree cell that went (a stale entry)", 1) {
            @Override
            void start() {
                // a vanilla spruce leaf of the tree (the trunk is our own block since S10c and tells the ledger itself)
                final BlockPos log = firstOf(p.colonyId, p.square(), s -> s.is(Blocks.SPRUCE_LEAVES));
                if (log == null) {
                    p.check("H2: a listed leaf of the tree", false, "none");
                    return;
                }
                level().setBlock(log, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                p.check("H2: the leaf went and its entry stays (vanilla blocks never tell the ledger)", ledger().at(log) != null, log.toShortString());
                level().setBlock(log, SeasonsBlocks.GIFT_PILE.get().defaultBlockState(), Block.UPDATE_ALL);
                final FakePlayer y = player("crafter", p.colony);
                final Gifts.Outcome out = Gifts.take(level(), log, y);
                p.checkEq("H2: the player's own pile on that cell hands out nothing", out, Gifts.Outcome.NOT_DECORATORS);
                level().setBlock(log, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        });
        p.step(new CsFullCycle.Step("H4, H5: take-down three ways in one tick, a paid wreath broken", 1) {
            @Override
            void start() {
                // H4 a: the Decorator, then the sweep, then decor remove, on A's tree
                int paid = paidStanding(p.colonyId, p.square());
                Counting sink = new Counting(i -> false);
                int hut0 = hutItems(p.hut);
                DecorWork.takeDown(level(), p.colonyId, p.square(), sink, day(), ledger());
                ledger().markRemoving(p.colonyId, p.square());
                DecorEvents.sweep(level());
                command("colonyseasons decor remove " + p.colonyId);
                int got = sink.backAll() + hutItems(p.hut) - hut0;
                p.check("H4: the Decorator's take-down, the sweep and decor remove on one group in one tick hand back each paid item once",
                        got == paid && size(p.colonyId, p.square()) == 0, got + " handed back, " + paid + " paid entries, " + size(p.colonyId, p.square()) + " left");
                // H4 b: the sweep first, then the Decorator, then decor remove, on B's tree (no Decorator's hut: lost, counted)
                final DecorLedger.Owner sb = DecorLedger.Owner.square(markerB);
                paid = paidStanding(bId, sb);
                sink = new Counting(i -> false);
                final int lost0 = DecorEvents.lostItems();
                ledger().markRemoving(bId, sb);
                DecorEvents.sweep(level());
                DecorWork.takeDown(level(), bId, sb, sink, day(), ledger());
                command("colonyseasons decor remove " + bId);
                got = sink.backAll() + DecorEvents.lostItems() - lost0;
                p.check("H4: the sweep, the Decorator and decor remove (the other order): each paid item counted once", got == paid && size(bId, sb) == 0,
                        got + " handed back or lost, " + paid + " paid entries");

                // H5: a paid wreath broken by a player
                final DecorWork.Target t1 = DecorWork.Target.building(h1);
                final Counting stock = new Counting(i -> true);
                DecorWork.decorate(level(), p.colonyId, t1, 3, stock, day(), ledger());
                final BlockPos wreath = firstOf(p.colonyId, t1.owner(), s -> s.is(SeasonsBlocks.WREATH.get()));
                if (wreath == null) {
                    p.check("H5: a paid wreath on the house", false, "none");
                    return;
                }
                final FakePlayer w = player("wreath", p.colony);
                level().destroyBlock(wreath, true, w);
                final int dropped = drops(wreath, 2, s -> s.is(SeasonsBlocks.WREATH_ITEM.get()));
                final Counting sink2 = new Counting(i -> false);
                DecorWork.takeDown(level(), p.colonyId, t1.owner(), sink2, day(), ledger());
                p.check("H5: a paid wreath a player breaks drops to him, its entry is forgotten, the take-down hands back the rest: moved, not made",
                        dropped == 1 && sink2.backAll() + dropped == stock.takenAll() && size(p.colonyId, t1.owner()) == 0,
                        stock.takenAll() + " taken, " + sink2.backAll() + " handed back, " + dropped + " dropped");
                pickUp(wreath, 2);
            }
        });
        p.step(new CsFullCycle.Step("H6: no cycle through the kits and the recipes", 1) {
            @Override
            void start() {
                final Map<Item, Set<Item>> edges = new HashMap<>();
                int kits = 0;
                for (final Kits.Kit kit : Kits.all()) {
                    kits++;
                    for (final Kits.Ingredient ing : kit.ingredients()) {
                        for (final Item in : expand(ing)) {
                            edges.computeIfAbsent(in, k -> new HashSet<>()).add(kit.output());
                        }
                    }
                }
                int recipes = 0;
                for (final RecipeHolder<?> holder : p.server.getRecipeManager().getRecipes()) {
                    final ItemStack out = holder.value().getResultItem(level().registryAccess());
                    if (out.isEmpty() || !"colony_seasons".equals(BuiltInRegistries.ITEM.getKey(out.getItem()).getNamespace())) {
                        continue;
                    }
                    recipes++;
                    for (final Ingredient ing : holder.value().getIngredients()) {
                        for (final ItemStack s : ing.getItems()) {
                            edges.computeIfAbsent(s.getItem(), k -> new HashSet<>()).add(out.getItem());
                        }
                    }
                }
                final String cycle = cycle(edges);
                p.check("H6: no item comes back into itself through the kits and our recipes (tags expanded)", cycle == null,
                        kits + " kits, " + recipes + " recipes" + (cycle == null ? "" : ", cycle " + cycle));
            }
        });
    }

    private List<Item> expand(final Kits.Ingredient ing) {
        if (ing.item() != null) {
            return List.of(ing.item());
        }
        final List<Item> out = new ArrayList<>();
        BuiltInRegistries.ITEM.getTag(ing.tag()).ifPresent(set -> {
            for (final Holder<Item> h : set) {
                out.add(h.value());
            }
        });
        return out;
    }

    private static String cycle(final Map<Item, Set<Item>> edges) {
        final Map<Item, Integer> state = new HashMap<>();
        for (final Item start : edges.keySet()) {
            final String found = dfs(start, edges, state, new ArrayList<>());
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static String dfs(final Item at, final Map<Item, Set<Item>> edges, final Map<Item, Integer> state, final List<Item> path) {
        final Integer s = state.get(at);
        if (s != null && s == 2) {
            return null;
        }
        if (s != null && s == 1) {
            return path.stream().map(i -> BuiltInRegistries.ITEM.getKey(i).getPath()).toList() + " -> " + BuiltInRegistries.ITEM.getKey(at).getPath();
        }
        state.put(at, 1);
        path.add(at);
        for (final Item next : edges.getOrDefault(at, Set.of())) {
            final String found = dfs(next, edges, state, path);
            if (found != null) {
                return found;
            }
        }
        path.remove(path.size() - 1);
        state.put(at, 2);
        return null;
    }

    private int command(final String line) {
        try {
            return p.server.getCommands().getDispatcher().execute(line, p.server.createCommandSourceStack().withSuppressedOutput());
        } catch (final com.mojang.brigadier.exceptions.CommandSyntaxException refused) {
            return -1;
        }
    }

    // ================================================================ hostile-free

    private void planFree() {
        final Map<Item, Integer> made = new LinkedHashMap<>();
        p.step(new CsFullCycle.Step("H7: decorations up, free (decorator.needsMaterials = false)", 600, true) {
            DecorWork.Outcome sq;

            @Override
            void start() {
                p.check("the config says decorations are free", !SeasonsConfig.needsMaterials(), "");
                for (final IBuilding bd : p.buildings) {
                    DecorWork.decorate(level(), p.colonyId, DecorWork.Target.building(bd), 3, DecorPlacer.Stock.FREE, day(), ledger());
                }
            }

            @Override
            boolean tick() {
                if (t % 5 == 0 && (sq == null || !sq.complete())) {
                    sq = DecorWork.decorate(level(), p.colonyId, squareOf(p.marker), 3, DecorPlacer.Stock.FREE, day(), ledger());
                }
                return sq != null && sq.complete();
            }

            @Override
            void end(final boolean done) {
                int paid = 0;
                for (final DecorLedger.Entry e : ledger().entries(p.colonyId).values()) {
                    paid += e.paid() ? 1 : 0;
                }
                p.check("H7: everything up, nothing paid", done && paid == 0 && ledger().count(p.colonyId) > 0, ledger().count(p.colonyId) + " entries, " + paid + " paid");
            }
        });
        for (int dayN = 1; dayN <= 3; dayN++) {
            final int n = dayN;
            p.step(new CsFullCycle.Step("H7: game day " + n + ": a player breaks the wreaths and three logs, tending puts them back", 200, true) {
                @Override
                void start() {
                    p.newGameDay(CsFullCycle.MORNING);
                    final FakePlayer w = player("free" + n, p.colony);
                    final List<BlockPos> targets = new ArrayList<>();
                    for (final DecorLedger.GroupView g : ledger().groups(p.colonyId)) {
                        for (final Map.Entry<BlockPos, DecorLedger.Entry> e : g.entries().entrySet()) {
                            if (e.getValue().state().is(SeasonsBlocks.WREATH.get())) {
                                targets.add(e.getKey());
                            }
                        }
                    }
                    int logs = 0;
                    for (final Map.Entry<BlockPos, DecorLedger.Entry> e : ledger().group(p.colonyId, p.square()).entries().entrySet()) {
                        if (logs < 3 && e.getValue().state().is(SeasonsBlocks.FESTIVAL_TRUNK.get()) && e.getKey().getY() > p.marker.getY() + 1) {
                            targets.add(e.getKey());
                            logs++;
                        }
                    }
                    for (final BlockPos pos : targets) {
                        level().destroyBlock(pos, true, w);
                        for (final Map.Entry<Item, Integer> e : pickUp(pos, 2).entrySet()) {
                            made.merge(e.getKey(), e.getValue(), Integer::sum);
                        }
                    }
                    for (final DecorLedger.GroupView g : ledger().groups(p.colonyId)) {
                        final DecorWork.Target target = g.owner().kind() == DecorLedger.Kind.SQUARE ? squareOf(p.marker)
                                : DecorWork.Target.building(p.colony.getServerBuildingManager().getBuilding(g.owner().pos()));
                        DecorWork.tend(level(), p.colonyId, target, 3, DecorPlacer.Stock.FREE, day(), ledger());
                    }
                }

                @Override
                boolean tick() {
                    return t >= 20;
                }

                @Override
                void end(final boolean done) {
                    final String[] first = {""};
                    p.check("H7: game day " + n + ": everything is back and every entry equals the world", entriesEqualWorld(p.colonyId, first)
                            && firstOf(p.colonyId, p.square(), s -> s.is(SeasonsBlocks.FESTIVAL_TRUNK.get())) != null, first[0]);
                }
            });
        }
        p.step(new CsFullCycle.Step("H7: the count", 1) {
            @Override
            void start() {
                // S10d, the owner's answer of 20 Nov 2026 (was the finding H7): what he put up for free drops nothing
                final int items = made.values().stream().mapToInt(Integer::intValue).sum();
                p.check("H7: free decorations broken every game day dropped nothing (the owner's answer of 20 Nov 2026)", items == 0,
                        items + " items in 3 game days (" + CsFullCycle.text(made) + ")");
            }
        });
    }

    // ================================================================ hostile-world

    private void planWorld() {
        planVersion();
        planGrandTree();
        planTenCells();
        planChunks();
        planYears();
        planSecondMarker();
        planUninstall();
        planHostingHut();
        planDeletedColony();
        planNeighbours();
    }

    /** H19: a ledger tag of a newer version with a group kind this version does not know. */
    private void planVersion() {
        p.step(new CsFullCycle.Step("H19: a ledger file of a newer version", 1) {
            @Override
            void start() {
                final CompoundTag root = new CompoundTag();
                root.putInt("version", 99);
                final ListTag colonies = new ListTag();
                final CompoundTag one = new CompoundTag();
                one.putInt("id", 7);
                final ListTag groups = new ListTag();
                groups.add(group("BUILDING", new BlockPos(1, 2, 3), Blocks.SPRUCE_LOG.defaultBlockState()));
                groups.add(group("LANTERN_POST", new BlockPos(4, 5, 6), Blocks.SPRUCE_LOG.defaultBlockState()));
                one.put("groups", groups);
                colonies.add(one);
                root.put("colonies", colonies);
                final int warned0 = newerWarnings();
                final DecorLedger read = DecorLedger.load(root, level().registryAccess());
                p.check("H19: the entries of known kinds load, the unknown kind is skipped", read.count(7) == 1 && read.at(new BlockPos(1, 2, 3)) != null
                        && read.at(new BlockPos(4, 5, 6)) == null, read.count(7) + " entries");
                p.check("H19: a file of a newer version is read with one warning naming both versions", warned0 >= 0 && newerWarnings() == warned0 + 1,
                        warned0 < 0 ? "no version check in DecorLedger.load" : (newerWarnings() - warned0) + " warnings");
            }
        });
    }

    private static CompoundTag group(final String kind, final BlockPos pos, final BlockState state) {
        final CompoundTag g = new CompoundTag();
        g.putString("kind", kind);
        g.putLong("pos", pos.asLong());
        final ListTag palette = new ListTag();
        palette.add(NbtUtils.writeBlockState(state));
        g.put("palette", palette);
        g.putLongArray("positions", new long[]{pos.asLong()});
        g.putIntArray("states", new int[]{0});
        g.putByteArray("paid", new byte[]{1});
        return g;
    }

    /** DecorLedger's count of newer-version warnings, -1 when it has none (read reflectively: the probe also runs on older code). */
    private static int newerWarnings() {
        try {
            return (int) DecorLedger.class.getMethod("newerVersionWarnings").invoke(null);
        } catch (final ReflectiveOperationException none) {
            return -1;
        }
    }

    /** H8, H9, H9b: the grand tree with empty and partial stock, its take-down in slices, the sweep's slices. */
    private void planGrandTree() {
        final DecorPlacer.Stock[] stock = new DecorPlacer.Stock[1];
        p.step(new CsFullCycle.Step("H8: the grand tree (level 5) with no stock", 400, true) {
            DecorWork.Outcome last;

            @Override
            boolean tick() {
                if (t % 5 == 0) {
                    last = DecorWork.decorate(level(), p.colonyId, squareOf(p.marker), 5, new Counting(i -> false), day(), ledger());
                }
                return last != null && !last.status().startsWith("loading");
            }

            @Override
            void end(final boolean done) {
                p.check("H8: with no stock at all nothing of the tree is placed (no gift pile, crate, star or tinsel on their own)",
                        done && size(p.colonyId, p.square()) == 0, size(p.colonyId, p.square()) + " placed: " + kinds(p.colonyId, p.square()));
                DecorPlacer.remove(level(), ledger(), p.colonyId, p.square(), DecorPlacer.Stock.FREE);
            }
        });
        p.step(new CsFullCycle.Step("H8: the grand tree with logs only", 1) {
            @Override
            void start() {
                final Counting logs = new Counting(i -> i == Items.SPRUCE_LOG);
                DecorWork.decorate(level(), p.colonyId, squareOf(p.marker), 5, logs, day(), ledger());
                final String placed = kinds(p.colonyId, p.square());
                final boolean free = firstOf(p.colonyId, p.square(), s -> !PieceLoader.costsItem(s)) != null;
                p.check("H8: with logs only the trunk goes up and nothing free floats before the leaves (no tinsel, no large star, no gift pile, no crate)",
                        !free && logs.takenAll() > 0, placed);
                stock[0] = DecorPlacer.Stock.FREE;
            }
        });
        p.step(new CsFullCycle.Step("H9: the grand tree completed", 600, true) {
            DecorWork.Outcome last;

            @Override
            boolean tick() {
                if (t % 5 == 0) {
                    last = DecorWork.decorate(level(), p.colonyId, squareOf(p.marker), 5, DecorPlacer.Stock.FREE, day(), ledger());
                }
                return last != null && last.complete();
            }

            @Override
            void end(final boolean done) {
                p.check("H9: the grand tree is complete", done, size(p.colonyId, p.square()) + " cells, " + (last == null ? "-" : last.status()));
            }
        });
        p.step(new CsFullCycle.Step("H9: the Decorator takes the grand tree down, a visit every 40 ticks", 1000, true) {
            int cells;
            int paid;
            final Counting sink = new Counting(i -> false);
            final List<Integer> perCall = new ArrayList<>();

            @Override
            void start() {
                cells = size(p.colonyId, p.square());
                paid = paidStanding(p.colonyId, p.square());
            }

            @Override
            boolean tick() {
                if (t % 40 == 1) {
                    perCall.add(DecorWork.takeDown(level(), p.colonyId, p.square(), sink, day(), ledger()).touched());
                }
                return ledger().group(p.colonyId, p.square()) == null && t % 40 == 20 || perCall.size() >= 20;
            }

            @Override
            void end(final boolean done) {
                final int max = perCall.stream().mapToInt(Integer::intValue).max().orElse(0);
                p.check("H9: a take-down visit clears at most " + DecorWork.SQUARE_SLICE + " cells (the S4 hand-off) and the rest follows on later visits",
                        cells > DecorWork.SQUARE_SLICE && max <= DecorWork.SQUARE_SLICE && perCall.size() >= 2 && size(p.colonyId, p.square()) == 0,
                        cells + " cells, per visit " + perCall);
                p.check("H9: every paid item handed back once", sink.backAll() == paid, sink.backAll() + " of " + paid);
                p.check("H9: the square equals its snapshot after the sliced take-down", areaDiffCount(area) == 0, areaDiff(area));
            }
        });
        p.step(new CsFullCycle.Step("H9b: the grand tree once more", 600, true) {
            DecorWork.Outcome last;

            @Override
            boolean tick() {
                if (t % 5 == 0) {
                    last = DecorWork.decorate(level(), p.colonyId, squareOf(p.marker), 5, DecorPlacer.Stock.FREE, day(), ledger());
                }
                return last != null && last.complete();
            }

            @Override
            void end(final boolean done) {
                p.check("H9b: the grand tree is up again", done, last == null ? "-" : last.status());
            }
        });
        p.step(new CsFullCycle.Step("H9b: the server sweep takes it down in slices, a pass every 100 ticks", 2400, true) {
            int passes;

            @Override
            void start() {
                ledger().markRemoving(p.colonyId, p.square());
            }

            @Override
            boolean tick() {
                if (t % 100 == 1) {
                    DecorEvents.sweep(level());
                    passes++;
                }
                return ledger().group(p.colonyId, p.square()) == null && t % 100 == 50 || passes >= 20;
            }

            @Override
            void end(final boolean done) {
                p.check("H9b: the sweep's slices leave the square as it was (no leaf of the tree left behind)", passes >= 2
                        && ledger().group(p.colonyId, p.square()) == null && areaDiffCount(area) == 0, passes + " passes, " + areaDiff(area));
            }
        });
        // H9c (review): a slice may end anywhere (the sweep's budget is shared by every group of the pass), so here every cell
        // is a slice of its own and the server ticks between them: a leaf beside a log of an earlier slice re-reads its
        // distance before its own turn, and the take-down must still find it as listed
        final Counting paidStock = new Counting(i -> true);
        p.step(new CsFullCycle.Step("H9c: the grand tree once more, paid", 600, true) {
            DecorWork.Outcome last;

            @Override
            boolean tick() {
                if (t % 5 == 0) {
                    last = DecorWork.decorate(level(), p.colonyId, squareOf(p.marker), 5, paidStock, day(), ledger());
                }
                return last != null && last.complete();
            }

            @Override
            void end(final boolean done) {
                p.check("H9c: the grand tree is up again", done, last == null ? "-" : last.status());
            }
        });
        p.step(new CsFullCycle.Step("H9c: taken down one cell a slice, the neighbours' ticks between", 2400, true) {
            int paid;
            int calls;
            int lying;
            final Counting sink = new Counting(i -> false);

            @Override
            void start() {
                paid = paidStanding(p.colonyId, p.square());
                lying = drops(p.marker, 24, s -> true);
            }

            @Override
            boolean tick() {
                if (t % 3 == 1 && ledger().group(p.colonyId, p.square()) != null) {
                    DecorPlacer.remove(level(), ledger(), p.colonyId, p.square(), sink, 1);
                    calls++;
                }
                return ledger().group(p.colonyId, p.square()) == null && t % 3 == 2;
            }

            @Override
            void end(final boolean done) {
                final int dropped = drops(p.marker, 24, s -> true) - lying;
                p.check("H9c: a slice boundary anywhere leaves the square as it was, hands back every paid item once and drops nothing",
                        done && areaDiffCount(area) == 0 && sink.backAll() == paid && dropped == 0,
                        calls + " slices, " + sink.backAll() + " of " + paid + " paid items back, " + dropped + " items dropped, " + areaDiff(area));
            }
        });
    }

    private String kinds(final int colony, final DecorLedger.Owner owner) {
        final Map<String, Integer> n = new LinkedHashMap<>();
        final DecorLedger.GroupView g = ledger().group(colony, owner);
        if (g != null) {
            for (final DecorLedger.Entry e : g.entries().values()) {
                n.merge(CsFullCycle.shortName(e.state()), 1, Integer::sum);
            }
        }
        return n.toString();
    }

    /** H17: a player breaks ten cells of the lit tree; tending refills them and the upkeep lights them again. */
    private void planTenCells() {
        final List<BlockPos> broken = new ArrayList<>();
        p.step(new CsFullCycle.Step("H17: the village tree, lit", 600, true) {
            DecorWork.Outcome last;

            @Override
            boolean tick() {
                if (t % 5 == 0 && (last == null || !last.complete())) {
                    last = DecorWork.decorate(level(), p.colonyId, squareOf(p.marker), 3, DecorPlacer.Stock.FREE, day(), ledger());
                }
                return last != null && last.complete();
            }

            @Override
            void end(final boolean done) {
                TreeLighting.light(level(), p.colony, TreeLighting.BY_PLAYER, null);
                final FakePlayer w = player("ten", p.colony);
                final List<Predicate<BlockState>> kinds = List.of(s -> s.is(SeasonsBlocks.FESTIVAL_TRUNK.get()), s -> s.is(Blocks.SPRUCE_LEAVES),
                        s -> s.is(SeasonsBlocks.ORNAMENT_LEAVES.get()), s -> s.is(SeasonsBlocks.TREE_STAR.get()));
                final List<List<BlockPos>> byKind = new ArrayList<>();
                for (final Predicate<BlockState> kind : kinds) {
                    final List<BlockPos> list = new ArrayList<>();
                    for (final Map.Entry<BlockPos, DecorLedger.Entry> e : ledger().group(p.colonyId, p.square()).entries().entrySet()) {
                        if (kind.test(e.getValue().state()) && e.getKey().getY() > p.marker.getY() + 1) {
                            list.add(e.getKey());
                        }
                    }
                    byKind.add(list);
                }
                for (int k = 0; broken.size() < 10 && k < 40; k++) {
                    final List<BlockPos> list = byKind.get(k % byKind.size());
                    if (!list.isEmpty()) {
                        final BlockPos pos = list.remove(list.size() - 1);
                        level().destroyBlock(pos, true, w);
                        broken.add(pos);
                        pickUp(pos, 3);
                    }
                }
                p.check("H17: ten cells of the lit tree broken by a player, nothing thrown", broken.size() == 10, broken.size() + " broken");
            }
        });
        p.step(new CsFullCycle.Step("H17: the next tending refills them and the upkeep lights them", 200, true) {
            @Override
            void start() {
                p.newGameDay(CsFullCycle.MORNING);
                DecorWork.tend(level(), p.colonyId, squareOf(p.marker), 3, DecorPlacer.Stock.FREE, day(), ledger());
            }

            @Override
            boolean tick() {
                if (t % 20 == 0) {
                    FestivalDirector.poll(p.server);
                }
                return t >= 120;
            }

            @Override
            void end(final boolean done) {
                int back = 0;
                int unlit = 0;
                for (final BlockPos pos : broken) {
                    final DecorLedger.Located at = ledger().at(pos);
                    if (at != null && level().getBlockState(pos) == at.entry().state()) {
                        back++;
                        if (TreeLighting.isLight(at.entry().state()) && !at.entry().state().getValue(Lights.LIT)) {
                            unlit++;
                        }
                    }
                }
                p.check("H17: all ten are back and the lights among them lit again", back == 10 && unlit == 0, back + " back, " + unlit + " unlit");
                command("colonyseasons decor remove " + p.colonyId);
                p.check("H17: after decor remove the square equals its snapshot", areaDiffCount(area) == 0, areaDiff(area));
            }
        });
    }

    /** H10: chunks that unload between choosing a target and arriving, and in the middle of a take-down; a group whose chunks never load. */
    private void planChunks() {
        final BlockPos far = new BlockPos(3000, 0, 8);
        final DecorLedger.Owner farOwner = DecorLedger.Owner.building(far);
        final int[] paid = new int[1];
        final Counting sink = new Counting(i -> false);
        p.step(new CsFullCycle.Step("H10: a group across a far chunk", 200, true) {
            @Override
            void start() {
                level().setChunkForced(far.getX() >> 4, far.getZ() >> 4, true);
            }

            @Override
            boolean tick() {
                return level().hasChunkAt(far) && t >= 20;
            }

            @Override
            void end(final boolean done) {
                final int y = level().getHeight(Heightmap.Types.MOTION_BLOCKING, far.getX(), far.getZ());
                // four logs in the far chunk and two near the colony, all one group of colony A (as if put there before the chunk unloaded)
                final List<BlockPos> cells = new ArrayList<>(List.of(new BlockPos(3000, y, 8), new BlockPos(3001, y, 8), new BlockPos(3002, y, 8),
                        new BlockPos(3003, y, 8), new BlockPos(-30, p.ground + 1, 40), new BlockPos(-31, p.ground + 1, 40)));
                for (final BlockPos c : cells) {
                    final BlockState log = Blocks.SPRUCE_LOG.defaultBlockState();
                    ledger().record(p.colonyId, farOwner, SeasonsConfig.WINTER_FESTIVAL, 3, c, log, true);
                    level().setBlock(c, log, Block.UPDATE_ALL);
                    paid[0]++;
                }
                level().setChunkForced(far.getX() >> 4, far.getZ() >> 4, false);
            }
        });
        p.step(new CsFullCycle.Step("H10: the far chunk unloads", 1200, true) {
            @Override
            boolean tick() {
                return !level().hasChunkAt(far);
            }

            @Override
            void end(final boolean done) {
                p.check("H10: the far chunk unloaded (the rig lets it go)", done, "after " + t + " ticks");
                final DecorWork.Outcome visit = DecorWork.decorate(level(), p.colonyId, DecorWork.Target.box(new DoorFinder.Box(far.offset(-3, 0, -3),
                        far.offset(3, 6, 3), far)), 3, DecorPlacer.Stock.FREE, day(), ledger());
                p.check("H10: a visit to a building whose chunk unloaded on the way places nothing and does not load it", visit.placed() == 0
                        && !level().hasChunkAt(far), visit.status());
                final DecorPlacer.Removed r = DecorWork.takeDown(level(), p.colonyId, farOwner, sink, day(), ledger());
                final DecorLedger.GroupView g = ledger().group(p.colonyId, farOwner);
                p.check("H10: a take-down with part of the group unloaded removes the loaded cells, keeps the rest listed and waiting, loads nothing",
                        r.removed() == 2 && r.waiting() == 4 && g != null && g.size() == 4 && g.removing() && !level().hasChunkAt(far),
                        r + ", " + (g == null ? "no group" : g.size() + " left, removing " + g.removing()));
                // with only unloaded cells left, the Decorator has nothing to walk to: WAITING_CHUNKS
                final DecorWork.Target next = DecorWork.nextToTakeDown(level(), p.colony, p.hut.getPosition(), ledger());
                p.check("H10: a group whose chunks never load: no target for the Decorator (he says he waits for chunks), entries kept",
                        next == null || !next.owner().equals(farOwner), String.valueOf(next == null ? null : next.owner()));
                finding("H10", "fyi", "a group whose chunks never load again stays in the ledger and the Decorator's status says he waits for chunks; it goes "
                        + "when the chunk loads (the sweep or his next visit) or with decor remove");
                level().setChunkForced(far.getX() >> 4, far.getZ() >> 4, true);
            }
        });
        p.step(new CsFullCycle.Step("H10: the far chunk loads again", 400, true) {
            @Override
            boolean tick() {
                return level().hasChunkAt(far) && t >= 10;
            }

            @Override
            void end(final boolean done) {
                DecorWork.takeDown(level(), p.colonyId, farOwner, sink, day(), ledger());
                p.check("H10: once loaded the rest comes down, each paid item handed back once", ledger().group(p.colonyId, farOwner) == null
                        && sink.backAll() == paid[0], sink.backAll() + " of " + paid[0]);
                level().setChunkForced(far.getX() >> 4, far.getZ() >> 4, false);
            }
        });
    }

    /** H11: five compressed years: the ledger is empty after each and neither its colonies nor its file grow. */
    private void planYears() {
        final long[] sizes = new long[5];
        final int[] colonies = new int[5];
        for (int y = 0; y < 5; y++) {
            final int year = y;
            p.step(new CsFullCycle.Step("H11: year " + (year + 1), 600, true) {
                DecorWork.Outcome sq;

                @Override
                void start() {
                    p.setDate(DECOR_DAY);
                    p.newGameDay(CsFullCycle.MORNING);
                    for (final IBuilding bd : p.buildings) {
                        DecorWork.decorate(level(), p.colonyId, DecorWork.Target.building(bd), 3, DecorPlacer.Stock.FREE, day(), ledger());
                    }
                }

                @Override
                boolean tick() {
                    if (t % 5 == 0 && (sq == null || !sq.complete())) {
                        sq = DecorWork.decorate(level(), p.colonyId, squareOf(p.marker), 3, DecorPlacer.Stock.FREE, day(), ledger());
                    }
                    return sq != null && sq.complete();
                }

                @Override
                void end(final boolean done) {
                    final int up = ledger().count(p.colonyId);
                    p.setDate(AFTER);
                    for (int visit = 0; visit < 40 && ledger().hasEntries(p.colonyId); visit++) {
                        p.newGameDay(CsFullCycle.MORNING);
                        final DecorWork.Target next = DecorWork.nextToTakeDown(level(), p.colony, p.hut.getPosition(), ledger());
                        if (next != null) {
                            DecorWork.takeDown(level(), p.colonyId, next.owner(), DecorPlacer.Stock.FREE, day(), ledger());
                        }
                    }
                    ledger().dropEmptyGroups(p.colonyId);
                    ledger().setDirty();
                    ledger().flush(level());
                    final Path file = level().getServer().getWorldPath(LevelResource.ROOT).resolve("data").resolve(DecorLedger.FILE + ".dat");
                    try {
                        sizes[year] = Files.size(file);
                    } catch (final java.io.IOException unreadable) {
                        sizes[year] = -1;
                    }
                    colonies[year] = ledger().colonies().size();
                    p.check("H11: year " + (year + 1) + ": " + up + " entries up, then all down", done && ledger().count() == 0 && ledger().groups(p.colonyId).isEmpty(),
                            ledger().count() + " entries left, file " + sizes[year] + " bytes, " + colonies[year] + " colony records");
                    if (year == 4) {
                        p.check("H11: five years later the ledger has as many colony records and its file the same size as after the first",
                                colonies[4] == colonies[0] && Math.abs(sizes[4] - sizes[0]) <= 16, java.util.Arrays.toString(sizes) + " bytes, records "
                                        + java.util.Arrays.toString(colonies));
                    }
                    p.setDate(DECOR_DAY);
                }
            });
        }
    }

    /** H14: a second marker in one colony. */
    private void planSecondMarker() {
        p.step(new CsFullCycle.Step("H14: a second marker", 40, true) {
            BlockPos second;

            @Override
            void start() {
                second = new BlockPos(-20, p.ground + 1, 34);
                final BlockState m = SeasonsBlocks.FESTIVAL_MARKER.get().defaultBlockState().setValue(FestivalMarkerBlock.FACING, Direction.NORTH);
                level().setBlock(second, m, Block.UPDATE_ALL);
                SeasonsBlocks.FESTIVAL_MARKER.get().setPlacedBy(level(), second, m, p.owner, new ItemStack(SeasonsBlocks.FESTIVAL_MARKER_ITEM.get()));
                p.checkEq("H14: the second marker is not registered; the first stays the square", ledger().marker(p.colonyId), p.marker);
                level().setBlock(p.marker, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }

            @Override
            boolean tick() {
                return t >= 20;
            }

            @Override
            void end(final boolean done) {
                p.check("H14: the first broken: the colony has no square, the second marker is not promoted, nothing thrown",
                        ledger().marker(p.colonyId) == null && DecorWork.squareTarget(level(), ledger(), p.colonyId) == null, "");
                finding("H14", "ask", "a second marker in a colony stays a plain block (nobody is told) and is not promoted when the first is broken; "
                        + "the player has to break and place it again");
                level().setBlock(second, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                p.placeMarker();
                p.checkEq("H14: placed again, the marker is the square", ledger().marker(p.colonyId), p.marker);
            }
        });
    }

    /** H15 and H18: in the gathering hours the marker is a leisure site; decor remove in the middle of it; the marker broken. */
    private void planUninstall() {
        final int[] hut0 = new int[1];
        p.step(new CsFullCycle.Step("H18: the festival day, the tree from the hut's stock", 600, true) {
            DecorWork.Outcome last;

            @Override
            void start() {
                p.setDate(FESTIVAL_DAY);
                p.newGameDay(9000);
                final net.neoforged.neoforge.items.IItemHandler store = p.hut.getItemHandlerCap();
                for (final Item item : List.of(Items.SPRUCE_LOG, Items.SPRUCE_LEAVES, Items.SPRUCE_LEAVES, SeasonsBlocks.ORNAMENT_LEAVES_ITEM.get(),
                        SeasonsBlocks.TREE_STAR_ITEM.get())) {
                    ItemHandlerHelper.insertItemStacked(store, new ItemStack(item, item == SeasonsBlocks.TREE_STAR_ITEM.get() ? 2 : 64), false);
                }
                hut0[0] = hutItems(p.hut);
            }

            @Override
            boolean tick() {
                if (t % 5 == 0 && (last == null || !last.complete())) {
                    last = DecorWork.decorate(level(), p.colonyId, squareOf(p.marker), 3, DecorWork.stockOf(p.hut, null), day(), ledger());
                }
                if (t % 20 == 0) {
                    FestivalDirector.poll(p.server);
                }
                return last != null && last.complete() && t >= 40;
            }

            @Override
            void end(final boolean done) {
                p.check("H18: the tree is up from the hut's stock", done, last == null ? "-" : last.status());
                p.check("H15: in the gathering hours the marker is one of the colony's leisure sites",
                        p.colony.getServerBuildingManager().getLeisureSites().contains(p.marker), String.valueOf(p.colony.getServerBuildingManager().getLeisureSites()));
                int vanilla = 0;
                for (final DecorLedger.Entry e : ledger().entries(p.colonyId).values()) {
                    vanilla += "minecraft".equals(BuiltInRegistries.BLOCK.getKey(e.state().getBlock()).getNamespace()) ? 1 : 0;
                }
                command("colonyseasons decor remove " + p.colonyId);
                p.check("H18: decor remove in the middle of the festival: nothing listed, the square as it was, every paid item back in the hut",
                        ledger().count(p.colonyId) == 0 && areaDiffCount(area) == 0 && hutItems(p.hut) == hut0[0],
                        ledger().count(p.colonyId) + " entries, " + areaDiff(area) + ", hut " + hutItems(p.hut) + " of " + hut0[0] + " items");
                finding("H18", "fyi", "without decor remove first, uninstalling leaves the tree's {} vanilla logs and leaves standing (our blocks vanish "
                        + "with the mod); the README's sentence holds", vanilla);
            }
        });
        p.step(new CsFullCycle.Step("H15: the marker broken in the gathering hours", 2600, true) {
            int goneAt = -1;

            @Override
            void start() {
                level().setBlock(p.marker, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }

            @Override
            boolean tick() {
                if (t % 20 == 0) {
                    FestivalDirector.poll(p.server);
                }
                if (goneAt < 0 && !p.colony.getServerBuildingManager().getLeisureSites().contains(p.marker)) {
                    goneAt = t;
                }
                return goneAt >= 0;
            }

            @Override
            void end(final boolean done) {
                p.check("H15: the broken marker leaves MineColonies' leisure sites at once (nobody strolls round an empty spot)", done && goneAt <= 1,
                        goneAt < 0 ? "still listed after " + t + " ticks" : "gone after " + goneAt + " ticks");
                p.placeMarker();
            }
        });
    }

    /** H16: the Decorator's hut destroyed while he hosts; a new hut at level 1 in the window. */
    private void planHostingHut() {
        final int[] lost0 = new int[1];
        final int[] paidNoHut = new int[1];
        p.step(new CsFullCycle.Step("H16: the Decorator hosts at the tree", 3000, true) {
            DecorWork.Outcome last;

            @Override
            void start() {
                p.setDate(FESTIVAL_DAY);
                p.newGameDay(8000);
                p.decorator = p.spawn(p.hut.getPosition().offset(0, 0, 4));
                p.check("H16: a Decorator is hired", p.hut.getModule(DecoratorModules.WORK).assignCitizen(p.decorator), "");
                p.spawn(new BlockPos(2, p.ground + 1, 12));
                for (final IBuilding bd : List.of(p.townHall, h1, h2)) {
                    DecorWork.decorate(level(), p.colonyId, DecorWork.Target.building(bd), 3, new Counting(i -> true), day(), ledger());
                }
            }

            @Override
            boolean tick() {
                if (t % 5 == 0 && (last == null || !last.complete())) {
                    last = DecorWork.decorate(level(), p.colonyId, squareOf(p.marker), 3, new Counting(i -> true), day(), ledger());
                }
                final JobDecorator job = p.job();
                return job != null && p.timeOfDay() > 9000 && job.phase() == me.lovkar.seasons.decor.DecorPhase.HOSTING
                        && job.visits(EntityAIWorkDecorator.DecoratorState.HOSTING) > 0;
            }

            @Override
            void end(final boolean done) {
                p.check("H16: he is hosting", done, p.job() == null ? "-" : p.job().status());
                paidNoHut[0] = 0;
                for (final DecorLedger.GroupView g : ledger().groups(p.colonyId)) {
                    if (!g.owner().equals(DecorLedger.Owner.building(p.hut.getPosition()))) {
                        paidNoHut[0] += paidStanding(p.colonyId, g.owner());
                    }
                }
                lost0[0] = DecorEvents.lostItems();
                p.hut.destroy();
                DecorEvents.drain(p.server);
                final String[] first = {""};
                p.check("H16: the hut destroyed while he hosts: nothing thrown, every entry equals the world", entriesEqualWorld(p.colonyId, first), first[0]);
            }
        });
        p.step(new CsFullCycle.Step("H16: a game of the festival without the hut, then the window ends", 1600, true) {
            @Override
            void start() {
                p.newGameDay(CsFullCycle.MORNING);
            }

            @Override
            boolean tick() {
                if (t == 600) {
                    final int[] total = new int[1];
                    houses.diff(level(), pos -> ledger().at(pos) == null, 1, total);
                    p.check("H16: without the hut in the window the rest stays up and nothing unlisted changed", ledger().count(p.colonyId) > 0 && total[0] == 0,
                            ledger().count(p.colonyId) + " entries, " + total[0] + " unlisted cells changed");
                    p.setDate(AFTER);
                }
                return t > 600 && !ledger().hasEntries(p.colonyId);
            }

            @Override
            void end(final boolean done) {
                final int lost = DecorEvents.lostItems() - lost0[0];
                p.check("H16: after the window the sweep clears everything; the paid items with no hut to go to are counted as lost", done && lost == paidNoHut[0],
                        lost + " lost, " + paidNoHut[0] + " paid entries had no hut");
                finding("H16", "fyi", "when the Decorator's hut is destroyed and no other stands, the sweep after the window takes everything down and "
                        + "its {} paid items are lost (decor remove says how many)", lost);
                p.setDate(DECOR_DAY);
            }
        });
        p.step(new CsFullCycle.Step("H16: a new hut at level 1 in the window takes the old tree down and puts a small one up", 600, true) {
            BuildingDecorator newHut;
            DecorWork.Outcome last;
            int visits;

            @Override
            void start() {
                // the big tree of level 3 first, as the old hut had it
                for (int i = 0; i < 4; i++) {
                    last = DecorWork.decorate(level(), p.colonyId, squareOf(p.marker), 3, DecorPlacer.Stock.FREE, day(), ledger());
                }
                final IBuilding d = p.register(p.colony, BuiltInRegistries.BLOCK.getKey(DecoratorSetup.HUT.get()).toString(), p.house(30, -1, 9, 7, 34), 1);
                newHut = d instanceof BuildingDecorator bd ? bd : null;
                p.hut = newHut;
            }

            @Override
            boolean tick() {
                if (t % 5 == 0) {
                    last = DecorWork.decorate(level(), p.colonyId, squareOf(p.marker), 1, DecorPlacer.Stock.FREE, day(), ledger());
                    visits++;
                }
                final DecorLedger.GroupView g = ledger().group(p.colonyId, p.square());
                return g != null && g.piece().equals(PieceLoader.pathFor(1)) && last != null && last.complete();
            }

            @Override
            void end(final boolean done) {
                p.check("H16: rebuilt at level 1: the old tree comes down and the small one goes up, nothing thrown", done && newHut != null,
                        visits + " visits, " + (last == null ? "-" : last.status()));
                command("colonyseasons decor remove " + p.colonyId);
            }
        });
    }

    /** H12: a deleted colony's record, marker and festival state; a living colony whose chunks are not loaded is kept. */
    private void planDeletedColony() {
        final int[] c2 = {-1};
        final BlockPos[] m2 = new BlockPos[1];
        p.step(new CsFullCycle.Step("H12: colony C2 with a marker and decorations", 60, true) {
            @Override
            void start() {
                for (int cx = 22; cx <= 28; cx++) {
                    for (int cz = -3; cz <= 3; cz++) {
                        level().setChunkForced(cx, cz, true);
                    }
                }
                p.setDate(DECOR_DAY);
                final FakePlayer o = player("ownerc2");
                final IColony c = IColonyManager.getInstance().createColony(level(), new BlockPos(400, p.ground + 1, 2), o, "Seasons Hostile C2", "Colonial");
                p.check("H12: colony C2 was made", c != null, c == null ? "-" : "id " + c.getID());
                if (c == null) {
                    return;
                }
                c2[0] = c.getID();
                final IBuilding hall = p.register(c, "minecolonies:blockhuttownhall", p.house(395, -1, 11, 7, 400), 1);
                DecorWork.decorate(level(), c2[0], DecorWork.Target.building(hall), 3, DecorPlacer.Stock.FREE, day(), ledger());
                m2[0] = new BlockPos(400, p.ground + 1, 30);
                final BlockState m = SeasonsBlocks.FESTIVAL_MARKER.get().defaultBlockState().setValue(FestivalMarkerBlock.FACING, Direction.NORTH);
                level().setBlock(m2[0], m, Block.UPDATE_ALL);
                SeasonsBlocks.FESTIVAL_MARKER.get().setPlacedBy(level(), m2[0], m, o, new ItemStack(SeasonsBlocks.FESTIVAL_MARKER_ITEM.get()));
                FestivalData.of(level()).setTreeReady(c2[0], day(), "x");
                p.check("H12: C2 has its marker, entries and a festival state", ledger().marker(c2[0]) != null && ledger().count(c2[0]) > 0
                        && FestivalData.of(level()).peek(c2[0]) != null, ledger().count(c2[0]) + " entries");
                IColonyManager.getInstance().deleteColonyByWorld(c2[0], false, level());
            }

            @Override
            boolean tick() {
                return t >= 40;
            }

            @Override
            void end(final boolean done) {
                p.check("H12: C2 is deleted", c2[0] >= 0 && IColonyManager.getInstance().getColonyByWorld(c2[0], level()) == null, "");
            }
        });
        p.step(new CsFullCycle.Step("H12: the sweep in the window, then after it", 400, true) {
            @Override
            void start() {
                DecorEvents.sweep(level());
                p.check("H12: in the window the sweep takes a deleted colony's marker off at once", ledger().marker(c2[0]) == null
                        && ledger().markerColony(m2[0]) == -1, "marker " + ledger().marker(c2[0]) + ", markerColony " + ledger().markerColony(m2[0]));
                p.setDate(AFTER);
            }

            @Override
            boolean tick() {
                if (t % 20 == 0) {
                    DecorEvents.sweep(level());
                }
                return t >= 200 && !ledger().colonies().contains(c2[0]);
            }

            @Override
            void end(final boolean done) {
                p.check("H12: after the window its entries are gone and its record is dropped from the ledger and the festival data",
                        ledger().count(c2[0]) == 0 && !ledger().colonies().contains(c2[0]) && FestivalData.of(level()).peek(c2[0]) == null,
                        ledger().count(c2[0]) + " entries, record " + ledger().colonies().contains(c2[0]) + ", festival state "
                                + (FestivalData.of(level()).peek(c2[0]) != null));
                p.setDate(DECOR_DAY);
                final FakePlayer o = player("ownerc3");
                final IColony c3 = IColonyManager.getInstance().createColony(level(), new BlockPos(400, p.ground + 1, 2), o, "Seasons Hostile C3", "Colonial");
                if (c3 != null) {
                    p.check("H12: a new colony" + (c3.getID() == c2[0] ? " with the deleted one's id" : "") + " inherits no marker",
                            ledger().marker(c3.getID()) == null, "id " + c3.getID());
                    ledger().registerMarker(c3.getID(), new BlockPos(400, p.ground + 1, 30));
                    for (int cx = 22; cx <= 28; cx++) {
                        for (int cz = -3; cz <= 3; cz++) {
                            level().setChunkForced(cx, cz, false);
                        }
                    }
                    c3id = c3.getID();
                }
            }
        });
        p.step(new CsFullCycle.Step("H12: a living colony whose chunks are not loaded is never pruned", 400, true) {
            @Override
            boolean tick() {
                if (t % 20 == 0) {
                    DecorEvents.sweep(level());
                }
                return t >= 300;
            }

            @Override
            void end(final boolean done) {
                p.check("H12: C3's marker stays registered while its chunks are unloaded", c3id >= 0 && ledger().marker(c3id) != null, "id " + c3id);
            }
        });
    }

    private int c3id = -1;

    /** H13: two colonies with touching claims, a Decorator's stock each. */
    private void planNeighbours() {
        p.step(new CsFullCycle.Step("H13: colony B next door, claims touching", 120, true) {
            IColony nb;

            @Override
            void start() {
                p.setDate(DECOR_DAY);
                for (int cx = 4; cx <= 14; cx++) {
                    for (int cz = -6; cz <= 6; cz++) {
                        level().setChunkForced(cx, cz, true);
                    }
                }
                final FakePlayer o = player("ownernb");
                nb = IColonyManager.getInstance().createColony(level(), new BlockPos(146, p.ground + 1, 2), o, "Seasons Hostile Next Door", "Colonial");
                p.check("H13: colony B was made next door", nb != null, nb == null ? "-" : "id " + nb.getID());
                if (nb == null) {
                    return;
                }
                b = nb;
                bId = nb.getID();
                final IBuilding hall = p.register(nb, "minecolonies:blockhuttownhall", p.house(141, -1, 11, 7, 146), 1);
                final IBuilding edge = p.register(nb, "minecolonies:blockhutlumberjack", p.house(81, 16, 11, 7, 84), 1);
                final IBuilding mine = p.register(p.colony, "minecolonies:blockhutstonemason", p.house(66, 16, 11, 7, 72), 1);
                neighbours.addAll(List.of(hall, edge));
                edgeA = mine;
                int aBorder = Integer.MIN_VALUE;
                int bBorder = Integer.MAX_VALUE;
                for (int x = 60; x <= 100; x++) {
                    final int at = DecorPlacer.colonyAt(level(), new BlockPos(x, p.ground + 1, 20));
                    if (at == p.colonyId) {
                        aBorder = Math.max(aBorder, x);
                    }
                    if (at == bId) {
                        bBorder = Math.min(bBorder, x);
                    }
                }
                p.check("H13: the two claims touch", bBorder == aBorder + 1, "A to x " + aBorder + ", B from x " + bBorder);
            }

            @Override
            boolean tick() {
                return t >= 60 && !level().getChunkSource().getLightEngine().hasLightWork();
            }

            @Override
            void end(final boolean done) {
                if (b == null) {
                    return;
                }
                final Snap before = Snap.take(level(), new BlockPos(60, p.ground - 1, 10), new BlockPos(100, p.ground + 10, 30));
                for (final IBuilding bd : neighbours) {
                    DecorWork.decorate(level(), bId, DecorWork.Target.building(bd), 5, new Counting(i -> true), day(), ledger());
                }
                for (final IBuilding bd : List.of(p.townHall, h1, h2, edgeA)) {
                    DecorWork.decorate(level(), p.colonyId, DecorWork.Target.building(bd), 5, new Counting(i -> true), day(), ledger());
                }
                int outA = 0;
                int outB = 0;
                for (final BlockPos pos : ledger().entries(p.colonyId).keySet()) {
                    outA += DecorPlacer.colonyAt(level(), pos) != p.colonyId ? 1 : 0;
                }
                for (final BlockPos pos : ledger().entries(bId).keySet()) {
                    outB += DecorPlacer.colonyAt(level(), pos) != bId ? 1 : 0;
                }
                p.check("H13: each colony's decorations stay in its own claim (I4 for both)", outA == 0 && outB == 0 && ledger().count(bId) > 0
                        && ledger().count(p.colonyId) > 0, ledger().count(p.colonyId) + " and " + ledger().count(bId) + " entries, " + outA + " and " + outB + " outside");
                p.check("H13: the budgets are the colony's own", ledger().budgetUsed(bId, day()) == neighbours.size()
                        && ledger().budgetUsed(p.colonyId, day()) >= 4, ledger().budgetUsed(p.colonyId, day()) + " and " + ledger().budgetUsed(bId, day()));
                final Map<BlockPos, DecorLedger.Entry> bEntries = new LinkedHashMap<>(ledger().entries(bId));
                for (final DecorLedger.GroupView g : ledger().groups(p.colonyId)) {
                    DecorWork.takeDown(level(), p.colonyId, g.owner(), DecorPlacer.Stock.FREE, day(), ledger());
                }
                int changed = 0;
                for (final Map.Entry<BlockPos, DecorLedger.Entry> e : bEntries.entrySet()) {
                    changed += level().getBlockState(e.getKey()) != e.getValue().state() ? 1 : 0;
                }
                p.check("H13: A's take-down leaves B's decorations exactly as they were", changed == 0 && ledger().entries(bId).equals(bEntries),
                        changed + " of " + bEntries.size() + " changed");
                command("colonyseasons decor remove " + bId);
                final int[] total = new int[1];
                before.diff(level(), x -> true, 1, total);
                p.check("H13: both taken down, the border equals its snapshot", total[0] == 0, total[0] + " cells differ");
            }
        });
    }

    private final List<IBuilding> neighbours = new ArrayList<>();
    private IBuilding edgeA;
}
