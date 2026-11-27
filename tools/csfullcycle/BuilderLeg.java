package me.lovkar.seasons.csfullcycle;

import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.storage.StructurePacks;
import com.ldtteam.structurize.util.BlockInfo;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.modules.IAssignsJob;
import com.minecolonies.core.colony.workorders.WorkOrderBuilding;
import me.lovkar.seasons.calendar.CalendarManager;
import me.lovkar.seasons.colony.BuildingDecorator;
import me.lovkar.seasons.colony.DecoratorModules;
import me.lovkar.seasons.colony.DecoratorSetup;
import me.lovkar.seasons.colony.JobDecorator;
import me.lovkar.seasons.decor.DecorEvents;
import me.lovkar.seasons.decor.DecorLedger;
import me.lovkar.seasons.decor.PieceLoader;
import me.lovkar.seasons.decor.Squares;
import me.lovkar.seasons.festival.FestivalData;
import me.lovkar.seasons.festival.Gifts;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.items.ItemHandlerHelper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Q7 mode builder-over-tree: MineColonies' own Builder upgrades the Town Square from level 1 to 2 while its tree stands.
 * A light colony (a town hall, the Workshop at level 3 with its Decorator, a Builder's hut at level 2 with a builder, two
 * colonists), the level 1 square as a finished build and a registered festival marker as the decoy; the Decorator's own AI
 * puts the tree up, lights it, and a player takes the day's gift. Then the hut window's upgrade
 * ({@code requestUpgrade}), worked by the Builder's own AI (the recipe of cssquarelife's mode builder: the rig's colony is
 * never active, so the probe runs MineColonies' work manager at its own rate). Measured in game ticks from the order: both
 * of the square's groups empty, his first stage on the square, his CLEAR_NON_SOLIDS, his first change in the box, the order
 * done. Checked: the groups were down within 2 ticks and before all of his; no listed block changed while listed; every
 * paid item back in the Workshop and nothing lost; while the order is open nothing goes up on the square and nothing at the
 * marker; afterwards the square is level 2 with its mark and anchor as they were, the plaza is the level 2 blueprint's (I8),
 * the set is back as the level 2 piece and I3 is exact.
 */
final class BuilderLeg {
    private final CsFullCycle p;
    private final SquareCycle sc;
    /** MineColonies' work manager runs every 20 ticks of an active colony. */
    private static final int WORK_MANAGER_TICK = 20;
    private static final int UPGRADE_TICKS = 9000;
    /** The tick rate of the Builder's stretch: his paths are found on other threads (a full sprint outruns them). */
    private static final float BUILDER_TICK_RATE = 100f;
    private IBuilding builderHut;
    private ICitizenData builder;
    private BlockPos decoy;

    BuilderLeg(final CsFullCycle probe, final SquareCycle square) {
        this.p = probe;
        this.sc = square;
    }

    void plan() {
        final LocalDate dec1 = SquareCycle.DEC_1;
        p.step(new CsFullCycle.Step("builder-over-tree setup: the colony, the Builder, the level 1 square, a marker as the decoy", 120, true) {
            @Override
            void start() {
                p.setDate(dec1);
                p.quietWorld();
                p.ground = p.level.getHeight(Heightmap.Types.MOTION_BLOCKING, 2, 2) - 1;
                final BlockPos center = new BlockPos(2, p.ground + 1, 2);
                p.forceChunks(5);
                p.owner = FakePlayerFactory.getMinecraft(p.level);
                p.colony = IColonyManager.getInstance().createColony(p.level, center, p.owner, "Seasons Builder Over Tree", "Medieval Oak");
                p.colonyId = p.colony == null ? -1 : p.colony.getID();
                p.check("builder-over-tree: a colony was made", p.colony != null, "id " + p.colonyId);
                if (p.colony == null) {
                    p.finish();
                    return;
                }
                p.townHall = p.register(p.colony, "minecolonies:blockhuttownhall", p.house(-3, -1, 11, 7, 2), 1);
                final IBuilding d = p.register(p.colony, BuiltInRegistries.BLOCK.getKey(DecoratorSetup.HUT.get()).toString(),
                        p.house(12, -1, 11, 7, 17), CsFullCycle.HUT_LEVEL);
                p.hut = d instanceof BuildingDecorator b ? b : null;
                builderHut = p.register(p.colony, "minecolonies:blockhutbuilder", p.house(-20, -1, 11, 7, -15), 2);
                p.buildings.addAll(List.of(p.townHall, p.hut, builderHut));
                p.decorator = p.spawn(p.hut.getPosition().offset(0, 0, 4));
                p.check("builder-over-tree: a citizen is hired as the Decorator", p.hut.getModule(DecoratorModules.WORK).assignCitizen(p.decorator)
                        && p.decorator.getJob() instanceof JobDecorator, "");
                builder = p.colony.getCitizenManager().createAndRegisterCivilianData();
                builder.setIsChild(false);
                IAssignsJob module = null;
                for (final IAssignsJob m : builderHut.getModulesByType(IAssignsJob.class)) {
                    if (m.getJobEntry().getKey().getPath().equals("builder")) {
                        module = m;
                    }
                }
                p.check("builder-over-tree: a builder works at the Builder's hut (level 2)", module != null && module.assignCitizen(builder)
                        && builder.getWorkBuilding() == builderHut, "");
                p.spawn(center.offset(-2, 0, 11));
                p.spawn(center.offset(2, 0, 11));
                decoy = new BlockPos(20, p.ground + 1, 34);
                p.marker = decoy;
                p.placeMarker();
                // the level 1 square as a finished build
                sc.squareLevel = 1;
                sc.plot = SquareCycle.PLOT.offset(0, p.ground, 0);
                final BlockPos hutAt = sc.paste(sc.plot, 1);
                sc.square = hutAt == null ? null : sc.register(hutAt, sc.plot, 1);
                if (sc.square == null) {
                    p.finish();
                    return;
                }
                p.players();
                p.setTimeOfDay(CsFullCycle.MORNING);
            }

            @Override
            boolean tick() {
                return t >= 60 && sc.locate() && PieceLoader.request(p.level, PieceLoader.PACK, PieceLoader.pathFor(1), Direction.NORTH) != null
                        && !p.level.getChunkSource().getLightEngine().hasLightWork();
            }

            @Override
            void end(final boolean done) {
                p.check("builder-over-tree: the square is the main square, the marker only the fallback", done && sc.anchor != null
                        && decoy.equals(p.ledger.marker(p.colonyId)) && Squares.of(p.level, p.colony, p.ledger).source() == Squares.Source.BUILDING, "");
                if (!done) {
                    p.finish();
                    return;
                }
                final Map<Item, Integer> need = new LinkedHashMap<>();
                sc.addNeeds(need, dec1, dec1);
                for (final Item item : List.of(Items.SPRUCE_LOG, Items.SPRUCE_LEAVES)) {
                    p.start.put(item, 0);
                }
                // the level 2 piece too (the same small spruce: its need is the same)
                for (final Map.Entry<Item, Integer> e : need.entrySet()) {
                    int left = e.getValue();
                    while (left > 0) {
                        final int n = Math.min(left, e.getKey().getDefaultMaxStackSize());
                        ItemHandlerHelper.insertItemStacked(p.hut.getItemHandlerCap(), new ItemStack(e.getKey(), n), false);
                        left -= n;
                    }
                    p.start.merge(e.getKey(), e.getValue(), Integer::sum);
                }
                sc.excluded = builder;
                p.first = Snap.take(p.level, new BlockPos(-24, p.ground - 1, -18), new BlockPos(26, p.ground + 24, 46));
                p.daily("builder-over-tree setup");
            }
        });
        // the Decorator's own AI: the tree up, lit within two game days, a gift taken
        for (int i = 0; i < 4; i++) {
            final int n = i;
            p.step(p.day(dec1.plusDays(i), "builder-over-tree: " + dec1.plusDays(i), CsFullCycle.EVENING, p.new DayHook() {
                @Override
                void end(final boolean done) {
                    if (n == 3) {
                        final DecorLedger.GroupView g = sc.set();
                        final FestivalData.ColonyState s = FestivalData.of(p.level).peek(p.colonyId);
                        p.check("builder-over-tree: the tree stands complete on the square and is lit (by the Decorator)", g != null && g.decoratedDay() >= 0
                                && s != null && s.treeLit(), g == null ? "no group" : g.entries().size() + " entries, lit " + (s != null && s.treeLit()));
                        p.giftOnce(dec1.plusDays(n).toString(), Gifts.DAILY);
                    }
                }
            }));
        }
        upgrade();
        p.step(new CsFullCycle.Step("builder-over-tree: after the order, the snapshot re-read for the square's box", 1) {
            @Override
            void start() {
                p.server.tickRateManager().setTickRate(20);
                final DecorLedger.SquareMark mark = p.ledger.squareMark(p.colonyId);
                p.check("builder-over-tree: the square is level 2, its mark and anchor as they were", sc.square.getBuildingLevel() == 2 && mark != null
                        && mark.building().equals(sc.square.getPosition()) && mark.anchor().equals(sc.anchor) && sc.anchor.equals(Squares.treeCell(sc.square)),
                        "level " + sc.square.getBuildingLevel() + ", " + mark);
                try {
                    sc.blueprint = StructurePacks.getBlueprintFuture(SquareCycle.PACK, "square/townsquare2.blueprint", p.level.registryAccess())
                            .get(60, java.util.concurrent.TimeUnit.SECONDS);
                } catch (final Exception unreadable) {
                    sc.blueprint = null;
                }
                final List<BlockPos> plaza = sc.plazaDiff();
                p.check("builder-over-tree: the plaza the Builder left is the level 2 blueprint's (I8)", sc.blueprint != null && plaza.isEmpty(),
                        plaza.size() + " differ" + (plaza.isEmpty() ? "" : ", e.g. " + plaza.get(0).toShortString() + " "
                                + CsFullCycle.shortName(p.level.getBlockState(plaza.get(0)))));
                sc.squareLevel = 2;
                p.check("builder-over-tree: the square's box lists no entry when the snapshot is re-read", sc.entriesIn(sc.square) == 0, "");
                p.first.reread(p.level, sc.square.getCorners().getA().offset(-2, -1, -2), sc.square.getCorners().getB().offset(2, 2, 2));
                p.daily("builder-over-tree after the order");
            }
        });
        for (int i = 4; i < 6; i++) {
            final int n = i;
            p.step(p.day(dec1.plusDays(i), "builder-over-tree: " + dec1.plusDays(i) + " (the set back)", CsFullCycle.EVENING, p.new DayHook() {
                @Override
                boolean done() {
                    final DecorLedger.GroupView g = sc.set();
                    return p.t() % 20 == 0 && g != null && g.decoratedDay() >= 0 && g.level() == 2;
                }

                @Override
                void end(final boolean done) {
                    if (n == 5) {
                        final DecorLedger.GroupView g = sc.set();
                        p.check("builder-over-tree: the set is back as the level 2 piece", g != null && g.decoratedDay() >= 0 && g.level() == 2
                                && g.piece().equals(PieceLoader.pathFor(2)), g == null ? "no group" : g.piece() + " level " + g.level());
                    }
                }
            }));
        }
        p.step(new CsFullCycle.Step("builder-over-tree: the end", 1) {
            @Override
            void start() {
                final Map<Item, Integer> now = p.conservation();
                p.check("builder-over-tree: every tracked item is where it should be (I3 exact)", now.equals(p.start), "now " + CsFullCycle.text(now)
                        + "; at the start " + CsFullCycle.text(p.start));
            }
        });
    }

    // ================================================================ the upgrade with MineColonies' Builder

    private WorkOrderBuilding orderAt() {
        for (final WorkOrderBuilding wo : p.colony.getWorkManager().getWorkOrdersOfType(WorkOrderBuilding.class)) {
            if (wo.getLocation().equals(sc.square.getID())) {
                return wo;
            }
        }
        return null;
    }

    private static boolean structurize(final BlockState state) {
        return "structurize".equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace());
    }

    private static boolean tape(final BlockState state) {
        final ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return "minecolonies".equals(id.getNamespace()) && id.getPath().contains("tape");
    }

    private static boolean leafDistanceOnly(final BlockState listed, final BlockState now) {
        return listed.getBlock() == now.getBlock() && listed.getBlock() instanceof LeavesBlock
                && now.setValue(LeavesBlock.DISTANCE, listed.getValue(LeavesBlock.DISTANCE)) == listed;
    }

    /** What the Builder needs to build {@code bp} over the world (cssquarelife's needFor): per item, the differing blocks plus a few spare. */
    private Map<Item, Integer> needFor(final Blueprint bp) {
        final Map<Item, Integer> need = new LinkedHashMap<>();
        final BlockPos origin = sc.square.getPosition().subtract(bp.getPrimaryBlockOffset());
        for (final BlockInfo info : bp.getBlockInfoAsList()) {
            final BlockState state = info.getState();
            final BlockPos at = origin.offset(info.getPos());
            if (state != null && structurize(state) && BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().equals("blocksolidsubstitution")) {
                need.merge(Items.DIRT, 1, Integer::sum);
                continue;
            }
            if (state == null || state.isAir() || structurize(state) || at.equals(sc.square.getPosition()) || p.level.getBlockState(at) == state) {
                continue;
            }
            need.merge(state.getBlock().asItem(), 1, Integer::sum);
        }
        need.replaceAll((item, n) -> Math.min(n + 16, 128));
        return need;
    }

    private int giveBuilder(final Map<Item, Integer> items) {
        final com.minecolonies.api.inventory.InventoryCitizen inv = builder.getInventory();
        int given = 0;
        for (final Map.Entry<Item, Integer> e : items.entrySet()) {
            int left = e.getValue();
            while (left > 0) {
                final int n = Math.min(left, e.getKey().getDefaultMaxStackSize());
                final ItemStack rest = ItemHandlerHelper.insertItemStacked(inv, new ItemStack(e.getKey(), n), false);
                given += n - rest.getCount();
                left -= n;
                if (!rest.isEmpty()) {
                    break;
                }
            }
        }
        return given;
    }

    /** The stage the Builder's structure handler is in (BUILD_SOLID, CLEAR_NON_SOLIDS, ...), or null without one. */
    private Object builderStage() {
        final Object ai = builder.getJob() == null ? null : builder.getJob().getWorkerAI();
        if (!(ai instanceof com.minecolonies.core.entity.ai.workers.AbstractEntityAIStructure<?, ?>)) {
            return null;
        }
        try {
            final java.lang.reflect.Field f = com.minecolonies.core.entity.ai.workers.AbstractEntityAIStructure.class.getDeclaredField("structurePlacer");
            f.setAccessible(true);
            final Object placer = f.get(ai);
            if (placer instanceof com.minecolonies.api.util.Tuple<?, ?> t
                    && t.getB() instanceof com.minecolonies.core.entity.ai.workers.util.BuildingStructureHandler<?, ?> handler) {
                return handler.getStage();
            }
        } catch (final ReflectiveOperationException unreachable) {
            return "stage unknown (" + unreachable.getClass().getSimpleName() + ")";
        }
        return null;
    }

    private String builderState() {
        final Object ai = builder.getJob() == null ? null : builder.getJob().getWorkerAI();
        final String state = ai instanceof com.minecolonies.api.entity.ai.ITickingStateAI t ? String.valueOf(t.getState()) : "no AI";
        final Object stage = builderStage();
        return state + (stage == null ? "" : " " + stage);
    }

    /** Every item in the Workshop and in its Decorator's pack (his own AI moves stock between the two). */
    private int workshopItems() {
        return BuildingDecorator.countIn(p.hut.getItemHandlerCap(), s -> true) + BuildingDecorator.countIn(p.decorator.getInventory(), s -> true);
    }

    private void upgrade() {
        p.step(new CsFullCycle.Step("builder-over-tree: the Builder settles at his hut", 200, true) {
            @Override
            void start() {
                p.setDate(SquareCycle.DEC_1.plusDays(3));
                p.newGameDay(CsFullCycle.MORNING);
                builder.setSaturation(ICitizenData.MAX_SATURATION);
                p.colony.getCitizenManager().spawnOrCreateCitizen(builder, p.level, builderHut.getPosition().offset(0, 0, 2));
            }

            @Override
            void end(final boolean done) {
                p.check("builder-over-tree: the builder has an entity and the Builder's AI", builder.getEntity().isPresent() && builder.getJob() != null
                        && builder.getJob().getWorkerAI() != null, builderState());
            }
        });
        p.step(new CsFullCycle.Step("builder-over-tree: UPGRADE 1 to 2 from the hut window, worked by the Builder's own AI", UPGRADE_TICKS) {
            Map<BlockPos, BlockState> ours;
            final Map<BlockPos, BlockState> box = new LinkedHashMap<>();
            long made;
            int down = -1;
            int onSquare = -1;
            int clearNonSolids = -1;
            int firstChange = -1;
            String firstWhat = "-";
            int completed = -1;
            final List<String> oursTouched = new ArrayList<>();
            final List<String> whileOpen = new ArrayList<>();
            int before;
            int lost;
            int paid;
            boolean toppedUp;
            final List<String> stages = new ArrayList<>();

            @Override
            void start() {
                p.server.tickRateManager().setTickRate(BUILDER_TICK_RATE);
                final Blueprint target = StructurePacks.getBlueprint(SquareCycle.PACK, "square/townsquare2.blueprint", p.level.registryAccess());
                p.check("builder-over-tree: the level 2 blueprint loads", target != null, "");
                if (target == null) {
                    p.finish();
                    return;
                }
                final Map<Item, Integer> need = needFor(target);
                final int given = giveBuilder(need);
                p.check("builder-over-tree: the Builder is given what the level's blocks need", given == need.values().stream().mapToInt(Integer::intValue).sum(),
                        given + " items of " + need.size() + " kinds");
                ours = new LinkedHashMap<>();
                for (final DecorLedger.Owner owner : List.of(DecorLedger.Owner.square(sc.anchor), DecorLedger.Owner.touches(sc.anchor))) {
                    final DecorLedger.GroupView g = p.ledger.group(p.colonyId, owner);
                    if (g != null) {
                        for (final Map.Entry<BlockPos, DecorLedger.Entry> e : g.entries().entrySet()) {
                            ours.put(e.getKey(), e.getValue().state());
                            paid += e.getValue().paid() && p.level.getBlockState(e.getKey()) == e.getValue().state() ? 1 : 0;
                        }
                    }
                }
                final net.minecraft.util.Tuple<BlockPos, BlockPos> corners = sc.square.getCorners();
                for (final BlockPos pos : BlockPos.betweenClosed(corners.getA(), corners.getB())) {
                    if (!ours.containsKey(pos) && !pos.equals(sc.square.getPosition())) {
                        box.put(pos.immutable(), p.level.getBlockState(pos));
                    }
                }
                before = workshopItems();
                lost = DecorEvents.lostItems();
                made = p.level.getGameTime();
                sc.square.requestUpgrade(p.owner, builderHut.getPosition());
                final WorkOrderBuilding wo = orderAt();
                p.check("builder-over-tree: the order is an UPGRADE to level 2", wo != null && wo.getTargetLevel() == 2 && sc.square.isPendingConstruction(),
                        wo == null ? "no order" : wo.getWorkOrderType() + " " + wo.getTargetLevel());
            }

            @Override
            boolean tick() {
                final int at = (int) (p.level.getGameTime() - made);
                builder.setSaturation(ICitizenData.MAX_SATURATION);
                if (p.level.getGameTime() % WORK_MANAGER_TICK == 0) {
                    p.colony.getWorkManager().onColonyTick(p.colony);
                }
                final int listed = SquareCycle.size(sc.set()) + SquareCycle.size(sc.touches());
                if (down < 0 && listed == 0) {
                    down = at;
                }
                if (down >= 0 && listed > 0 && orderAt() != null && whileOpen.size() < 3) {
                    whileOpen.add("square " + listed + " at " + at);
                }
                if (orderAt() != null && p.ledger.group(p.colonyId, DecorLedger.Owner.square(decoy)) != null && whileOpen.size() < 3) {
                    whileOpen.add("marker at " + at);
                }
                for (final DecorLedger.Owner owner : List.of(DecorLedger.Owner.square(sc.anchor), DecorLedger.Owner.touches(sc.anchor))) {
                    final DecorLedger.GroupView g = p.ledger.group(p.colonyId, owner);
                    if (g != null) {
                        for (final Map.Entry<BlockPos, DecorLedger.Entry> e : g.entries().entrySet()) {
                            final BlockState now = p.level.getBlockState(e.getKey());
                            if (now != e.getValue().state() && !leafDistanceOnly(e.getValue().state(), now) && oursTouched.size() < 5) {
                                oursTouched.add(e.getKey().toShortString() + " at " + at + ": " + CsFullCycle.shortName(now));
                            }
                        }
                    }
                }
                final Object stage = builderStage();
                if (stage != null) {
                    if (onSquare < 0) {
                        onSquare = at;
                    }
                    final String s = String.valueOf(stage);
                    if (stages.isEmpty() || !stages.get(stages.size() - 1).startsWith(s + " ")) {
                        stages.add(s + " " + at);
                    }
                    if (clearNonSolids < 0 && "CLEAR_NON_SOLIDS".equals(s)) {
                        clearNonSolids = at;
                    }
                }
                if (firstChange < 0 && t % 5 == 0) {
                    for (final Map.Entry<BlockPos, BlockState> e : box.entrySet()) {
                        final BlockState now = p.level.getBlockState(e.getKey());
                        if (now != e.getValue() && !tape(now) && !tape(e.getValue())) {
                            firstChange = at;
                            firstWhat = e.getKey().toShortString() + " " + CsFullCycle.shortName(e.getValue()) + " to " + CsFullCycle.shortName(now);
                            break;
                        }
                    }
                }
                if (orderAt() == null && completed < 0) {
                    completed = at;
                }
                if (!toppedUp && builderHut instanceof com.minecolonies.core.colony.buildings.AbstractBuildingStructureBuilder sb
                        && !sb.getNeededResources().isEmpty() && builderState().startsWith("NEEDS_ITEM")) {
                    final Map<Item, Integer> lacking = new LinkedHashMap<>();
                    for (final com.minecolonies.core.colony.buildings.utils.BuildingBuilderResource r : sb.getNeededResources().values()) {
                        if (r.getAvailable() < r.getAmount()) {
                            lacking.merge(r.getItemStack().getItem(), r.getAmount() - r.getAvailable() + 8, Integer::sum);
                        }
                    }
                    giveBuilder(lacking);
                    toppedUp = true;
                    CsFullCycle.note("builder-over-tree: at {} ticks the Builder lacked {}", at, CsFullCycle.text(lacking));
                }
                if (t % 1000 == 0) {
                    CsFullCycle.note("builder-over-tree: at {} ticks the builder is {}", at, builderState());
                }
                return completed >= 0;
            }

            @Override
            void end(final boolean done) {
                p.server.tickRateManager().setTickRate(20);
                final String times = "group down " + down + ", his first stage " + onSquare + ", CLEAR_NON_SOLIDS " + clearNonSolids + ", first change in the box "
                        + firstChange + " (" + firstWhat + "), done " + completed + "; stages " + stages;
                CsFullCycle.note("builder-over-tree: ticks from the order: {}", times);
                p.check("builder-over-tree: the Builder completed the upgrade with his own AI", done && completed >= 0, "after " + t + " ticks, " + builderState());
                p.check("builder-over-tree: both of the square's groups were down within 2 ticks of the order and before his first stage, his CLEAR_NON_SOLIDS"
                                + " and his first change in the box", down >= 0 && down <= 2 && onSquare > down && clearNonSolids > down
                                && (firstChange < 0 || firstChange > down), times);
                p.check("builder-over-tree: no block of ours changed while it was listed", oursTouched.isEmpty(), oursTouched.toString());
                p.check("builder-over-tree: while the order was open nothing went up on the square and nothing at the marker", whileOpen.isEmpty(),
                        whileOpen.toString());
                p.checkEq("builder-over-tree: every paid item came back to the Workshop", workshopItems() - before, paid);
                p.checkEq("builder-over-tree: nothing was lost", DecorEvents.lostItems() - lost, 0);
                DecorEvents.drain(p.server);
            }
        });
    }
}
