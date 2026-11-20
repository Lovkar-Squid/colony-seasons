package me.lovkar.seasons.csstorage;

import com.ldtteam.common.network.PlayMessageType;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.HiringMode;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.modules.IAssignsCitizen;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.requestable.RequestTag;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.colony.requestsystem.resolver.IRequestResolver;
import com.minecolonies.api.colony.requestsystem.resolver.player.IPlayerRequestResolver;
import com.minecolonies.api.colony.requestsystem.resolver.retrying.IRetryingRequestResolver;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.tileentities.AbstractTileEntityColonyBuilding;
import com.minecolonies.api.util.ItemStackUtils;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.buildings.modules.WarehouseModule;
import com.minecolonies.core.colony.buildings.modules.settings.BoolSetting;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingWareHouse;
import com.minecolonies.core.colony.jobs.JobDeliveryman;
import com.minecolonies.core.colony.requestsystem.resolvers.core.AbstractWarehouseRequestResolver;
import com.minecolonies.core.network.messages.server.colony.building.warehouse.UpgradeWarehouseMessage;
import com.minecolonies.core.tileentities.TileEntityColonyBuilding;
import com.minecolonies.core.tileentities.TileEntityRack;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import me.lovkar.seasons.calendar.CalendarManager;
import me.lovkar.seasons.calendar.DateSource;
import me.lovkar.seasons.colony.BuildingDecorator;
import me.lovkar.seasons.colony.DecoratorModules;
import me.lovkar.seasons.colony.JobDecorator;
import me.lovkar.seasons.decor.DecorLedger;
import me.lovkar.seasons.decor.DecorPhase;
import me.lovkar.seasons.decor.DoorFinder;
import me.lovkar.seasons.decor.Kits;
import me.lovkar.seasons.decor.Supply;
import me.lovkar.seasons.network.UpgradeStoragePayload;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Headless check of S10e on a real colony: a town hall, two houses, the Decorator's Workshop pasted from the shipped
 * level-5 blueprint with the command a player types, a warehouse with four racks, a courier's hut and a courier, the
 * Decorator. Modes (the rig writes the word to probe-mode.txt):
 * <ul>
 *   <li>none: S0 the pasted Workshop's racks are its containers; P the supply rule table; (e) the Upgrade Storage
 *       step through our payload's server handler against MineColonies' own UpgradeWarehouseMessage on the warehouse;
 *       (b) finished wreaths in the warehouse come by courier before any ingredient is asked for, (b2) part of the gap
 *       in the warehouse, (b3) a finished request the player would hold is cancelled, (b4) also while he does not prepare, (c) nothing anywhere: the
 *       ingredient requests as before; (a) his own stock is used before anything is asked for while the window opens
 *       and he decorates; (d) through the decorating and the take-down every decoration is counted everywhere: no
 *       gain, no loss. All along, no request for a finished piece stays with the player or the retrying resolver;</li>
 *   <li>{@code restart-save} then {@code restart-load} (tools/restart.sh): two storage steps (creative, then one
 *       emerald block), every output at the cap; after the restart the step, every rack's size and the stock are back
 *       and he asks for nothing.</li>
 * </ul>
 * The date is fixed through {@link CalendarManager#setDateSource}; the game sprints (real ticks, the AIs really run)
 * and the clock is moved on to the next morning before dusk, so nobody goes to bed. Prints "[csstorage] CHECK ...:
 * OK|FAIL" lines and "[csstorage] RESULT csstorage: PASS|FAIL", then halts. The words "error" and "exception" stay out
 * of the details: the rig takes such a line for a failure.
 */
@Mod("colonyseasonsstorage")
public class CsStorage {
    private static final Logger LOGGER = LoggerFactory.getLogger("csstorage");
    private static final int SETUP_TICK = 60;
    private static final long WATCHDOG_SECONDS = 540;
    private static final BlockPos WORKSHOP_OFFSET = new BlockPos(14, 0, 0);
    private static final BlockPos WAREHOUSE_OFFSET = new BlockPos(-10, 0, -2);
    private static final BlockPos COURIER_OFFSET = new BlockPos(-10, 0, 8);
    private static final LocalDate SUMMER = LocalDate.of(2026, 7, 15);
    private static final LocalDate DECOR_DAY = LocalDate.of(2026, 12, 10);
    private static final LocalDate AFTER = LocalDate.of(2027, 1, 10);
    private static final int LEVEL = 5;
    /** Items the probe hands to MineColonies' warehouse in (b): the cap and 32 more. */
    private static final int WAREHOUSE_EXTRA = 32;
    /** A request for a finished piece may stand with the player or the retrying resolver at most this long. */
    private static final int PLAYER_HELD_LIMIT = 60;

    private int ticks;
    private boolean failed;
    private boolean finished;
    private String mode = "";
    private long setupNanos;

    private MinecraftServer server;
    private ServerLevel level;
    private ServerPlayer owner;
    private IColony colony;
    private BlockPos center;
    private int ground;
    private BlockPos hutPos;
    private BuildingDecorator hut;
    private BuildingWareHouse warehouse;
    private IBuilding courierHut;
    private ICitizenData decorator;
    private ICitizenData courier;
    private Item wreath;
    private final List<IBuilding> houses = new ArrayList<>();

    /** Per item: what the probe put into the colony's world minus what it took out (the "nothing appears or goes" sum). */
    private final Map<Item, Integer> probeNet = new HashMap<>();
    /** Per item: what assembly made while the counting ran (batches times the kit's count). */
    private final Map<Item, Integer> madeNet = new HashMap<>();
    /** Requests for finished pieces seen with the player or the retrying resolver, and since when. */
    private final Map<IToken<?>, Integer> playerHeldSince = new HashMap<>();
    private boolean playerHeldBroken;
    private int playerHeldSeen;
    private boolean counting;
    private boolean countBroken;
    private int countChecks;

    private final List<Step> steps = new ArrayList<>();
    private int stepIndex;

    public CsStorage() {
        NeoForge.EVENT_BUS.addListener(this::onTick);
    }

    // ================================================================ logging

    private void check(final String what, final boolean ok, final String detail) {
        LOGGER.info("[csstorage] CHECK {}: {} {}", what, ok ? "OK" : "FAIL", detail);
        failed |= !ok;
    }

    private void checkEq(final String what, final Object actual, final Object expected) {
        check(what, Objects.equals(actual, expected), "got " + actual + (Objects.equals(actual, expected) ? "" : ", wanted " + expected));
    }

    private static void note(final String text, final Object... args) {
        LOGGER.info("[csstorage] " + text, args);
    }

    // ================================================================ the tick

    private void onTick(final ServerTickEvent.Post event) {
        if (finished) {
            return;
        }
        ticks++;
        server = event.getServer();
        try {
            if (ticks == SETUP_TICK) {
                mode = readMode();
                note("mode '{}'", mode);
                setupNanos = System.nanoTime();
                level = server.overworld();
                plan();
            }
            if (ticks >= SETUP_TICK) {
                if ((System.nanoTime() - setupNanos) / 1_000_000_000L > WATCHDOG_SECONDS) {
                    check("the run finished within " + WATCHDOG_SECONDS + " s of real time", false,
                            "stopped in step " + (stepIndex < steps.size() ? steps.get(stepIndex).name : "-"));
                    finish();
                    return;
                }
                runSteps();
            }
        } catch (final Throwable t) {
            check("the probe ran without a throwable", false, t.toString());
            LOGGER.info("[csstorage] at {}", t.getStackTrace().length > 0 ? t.getStackTrace()[0] : "-");
            finish();
        }
    }

    private void runSteps() {
        if (stepIndex >= steps.size()) {
            finish();
            return;
        }
        final Step step = steps.get(stepIndex);
        if (!step.started) {
            step.started = true;
            step.nanos = System.nanoTime();
            if (step.sprint) {
                server.tickRateManager().requestGameToSprint(step.maxTicks + 200);
            }
            step.start();
        }
        step.t++;
        if (decorator != null && step.t % 200 == 0) {
            keepWell();
        }
        if (decorator != null) {
            keepDay();
            watchPlayerHeld();
            if (counting && step.t % 100 == 0) {
                countAll(false);
            }
        }
        final boolean done = step.tick();
        if (done || step.t >= step.maxTicks) {
            final double seconds = Math.max(1e-3, (System.nanoTime() - step.nanos) / 1e9);
            note("step '{}' ran {} ticks in {} s", step.name, step.t, String.format("%.1f", seconds));
            step.end(done);
            stepIndex++;
        }
    }

    private void finish() {
        if (finished) {
            return;
        }
        finished = true;
        if (server != null) {
            server.tickRateManager().stopSprinting();
        }
        LOGGER.info("[csstorage] RESULT csstorage: {}", failed ? "FAIL" : "PASS");
        if (server != null) {
            server.halt(false);
        }
    }

    private static String readMode() throws Exception {
        final Path file = Path.of("probe-mode.txt");
        return Files.exists(file) ? Files.readString(file, StandardCharsets.US_ASCII).trim() : "";
    }

    /** One stretch of the run: up to {@code maxTicks} server ticks; {@code tick()} returning true ends it early. */
    private abstract static class Step {
        final String name;
        final int maxTicks;
        final boolean sprint;
        boolean started;
        int t;
        long nanos;

        Step(final String name, final int maxTicks) {
            this(name, maxTicks, true);
        }

        Step(final String name, final int maxTicks, final boolean sprint) {
            this.name = name;
            this.maxTicks = maxTicks;
            this.sprint = sprint;
        }

        void start() {
        }

        boolean tick() {
            return false;
        }

        void end(final boolean done) {
        }
    }

    private void step(final Step step) {
        steps.add(step);
    }

    private void now(final String name, final Runnable body) {
        steps.add(new Step(name, 1, false) {
            @Override
            void start() {
                body.run();
            }
        });
    }

    private void plan() {
        switch (mode) {
            case "" -> planDefault();
            case "restart-save" -> planRestartSave();
            case "restart-load" -> planRestartLoad();
            default -> {
                check("known mode", false, "'" + mode + "'");
                finish();
            }
        }
    }

    // ================================================================ the world

    private void setDate(final LocalDate date) {
        CalendarManager.setDateSource(DateSource.fixed(date));
        CalendarManager.refresh(server);
    }

    private void quietWorld() {
        server.setDifficulty(Difficulty.PEACEFUL, true);
        server.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        server.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        level.setWeatherParameters(24000 * 20, 0, false, false);
    }

    /** Before dusk the clock goes on to the next morning: nobody goes to bed, the game days still count up. */
    private void keepDay() {
        final long time = level.getDayTime();
        if (time % 24000L > 11000L) {
            level.setDayTime((time / 24000L + 1) * 24000L + 1000L);
        }
    }

    private void keepWell() {
        for (final ICitizenData c : colony.getCitizenManager().getCitizens()) {
            c.setSaturation(ICitizenData.MAX_SATURATION);
            if (c.getCitizenDiseaseHandler().isSick()) {
                c.getCitizenDiseaseHandler().cure();
            }
        }
    }

    private void set(final BlockPos pos, final BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_ALL);
    }

    private Block block(final String id) {
        return BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(id)).orElse(null);
    }

    /** A small stone house with doors on its south wall (csanswers' helper). */
    private DoorFinder.Box house(final int x0, final int z0, final int w, final int d, final int... doors) {
        final int y0 = ground;
        final BlockState brick = Blocks.STONE_BRICKS.defaultBlockState();
        for (int x = x0; x < x0 + w; x++) {
            for (int z = z0; z < z0 + d; z++) {
                for (int y = y0; y <= y0 + 6; y++) {
                    final boolean shell = y == y0 || y == y0 + 6 || x == x0 || x == x0 + w - 1 || z == z0 || z == z0 + d - 1;
                    set(new BlockPos(x, y, z), shell ? brick : Blocks.AIR.defaultBlockState());
                }
            }
        }
        final int zs = z0 + d - 1;
        for (int i = 0; i < doors.length; i++) {
            final DoorHingeSide hinge = i % 2 == 0 ? DoorHingeSide.LEFT : DoorHingeSide.RIGHT;
            final BlockState lower = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH)
                    .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER).setValue(DoorBlock.HINGE, hinge);
            set(new BlockPos(doors[i], y0 + 1, zs), lower);
            set(new BlockPos(doors[i], y0 + 2, zs), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        }
        return new DoorFinder.Box(new BlockPos(x0, y0, z0), new BlockPos(x0 + w - 1, y0 + 6, z0 + d - 1),
                new BlockPos(x0 + w / 2, y0 + 1, z0 + d / 2));
    }

    /** A MineColonies building on a hut block placed by hand, nobody hired by MineColonies on its own. */
    private IBuilding register(final String hutId, final BlockPos at, final BlockPos min, final BlockPos max, final int buildingLevel) {
        final Block hutBlock = block(hutId);
        if (hutBlock == null) {
            check("hut block " + hutId + " exists", false, "");
            return null;
        }
        set(at, hutBlock.defaultBlockState());
        final BlockEntity be = level.getBlockEntity(at);
        final IBuilding building = be instanceof AbstractTileEntityColonyBuilding te ? colony.getServerBuildingManager().addNewBuilding(te, level) : null;
        if (building == null) {
            check("building " + hutId + " registers", false, "at " + at.toShortString());
            return null;
        }
        building.setCorners(min, max);
        building.setBuildingLevel(buildingLevel);
        building.markDirty();
        for (final IAssignsCitizen module : building.getModulesByType(IAssignsCitizen.class)) {
            module.setHiringMode(HiringMode.MANUAL);
        }
        return building;
    }

    private ICitizenData spawn(final BlockPos at) {
        final ICitizenData data = colony.getCitizenManager().createAndRegisterCivilianData();
        colony.getCitizenManager().spawnOrCreateCitizen(data, level, at);
        return data;
    }

    private void buildColony() {
        ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, 2, 2) - 1;
        center = new BlockPos(2, ground + 1, 2);
        for (int cx = -4; cx <= 4; cx++) {
            for (int cz = -4; cz <= 4; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }
        owner = FakePlayerFactory.getMinecraft(level);
        stand(center);
        colony = IColonyManager.getInstance().createColony(level, center, owner, "Storage Test", "Medieval Oak");
        note("colony {} created at {}", colony == null ? "none" : colony.getID(), center.toShortString());
        final IBuilding townHall = register("minecolonies:blockhuttownhall", center, center, center, 1);
        note("town hall registered: {}", townHall != null);
        for (final DoorFinder.Box box : List.of(house(-6, 14, 7, 6, -3), house(6, 14, 7, 6, 9))) {
            final IBuilding house = register("minecolonies:blockhutcitizen", box.hut(), box.min(), box.max(), 1);
            if (house != null) {
                houses.add(house);
            }
        }
        final BlockPos wh = center.offset(WAREHOUSE_OFFSET);
        final IBuilding w = register("minecolonies:blockhutwarehouse", wh, wh.offset(-5, -1, -2), wh.offset(5, 3, 2), 3);
        warehouse = w instanceof BuildingWareHouse b ? b : null;
        check("a warehouse stands", warehouse != null && colony.getServerBuildingManager().getWareHouses().contains(warehouse),
                w == null ? "none" : w.getClass().getSimpleName());
        final Block rack = block("minecolonies:blockminecoloniesrack");
        if (warehouse != null && rack != null) {
            for (final int dx : new int[] {-4, -2, 2, 4}) {
                final BlockPos at = wh.offset(dx, 0, 0);
                set(at, rack.defaultBlockState());
                warehouse.registerBlockPosition(rack.defaultBlockState(), at, level);
            }
        }
        final BlockPos ch = center.offset(COURIER_OFFSET);
        courierHut = register("minecolonies:blockhutdeliveryman", ch, ch, ch, 1);
        hutPos = center.offset(WORKSHOP_OFFSET);
    }

    private void stand(final BlockPos at) {
        owner.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
    }

    /** The Workshop pasted the way the owner pastes it in game: the command, creative, the bare path. */
    private void pasteWorkshop() {
        owner.setGameMode(GameType.CREATIVE);
        stand(hutPos.offset(0, 0, 8));
        final String line = "structurize paste " + hutPos.getX() + " " + hutPos.getY() + " " + hutPos.getZ() + " Colony.Seasons decorator.workshop" + LEVEL;
        final Replies replies = command(line);
        note("'{}' said {}", line, replies.said);
        check("the paste command is accepted", replies.said.stream().anyMatch(s -> s.contains("paste.success")), String.valueOf(replies.said));
        final IBuilding building = colony.getServerBuildingManager().getBuilding(hutPos);
        hut = building instanceof BuildingDecorator d ? d : null;
        check("the pasted hut block makes a BuildingDecorator", hut != null, building == null ? "none" : building.getClass().getSimpleName());
    }

    private Replies command(final String line) {
        final Replies out = new Replies();
        server.getCommands().performPrefixedCommand(owner.createCommandSourceStack().withSource(out).withPermission(4), line);
        return out;
    }

    private static final class Replies implements CommandSource {
        final List<String> said = new ArrayList<>();

        @Override
        public void sendSystemMessage(final Component component) {
            said.add(component.getContents() instanceof TranslatableContents t ? t.getKey() : component.getString());
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

    // ================================================================ racks

    /** The racks of a building (its containers that are racks, the hut block left out). */
    private List<TileEntityRack> racks(final IBuilding building) {
        final List<TileEntityRack> out = new ArrayList<>();
        for (final BlockPos pos : building.getContainers()) {
            final BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof TileEntityRack rack && !(be instanceof TileEntityColonyBuilding)) {
                out.add(rack);
            }
        }
        return out;
    }

    /** Rack blocks standing inside the building's corners, by position. */
    private Set<BlockPos> rackBlocksInside(final IBuilding building) {
        final Block rack = block("minecolonies:blockminecoloniesrack");
        final Set<BlockPos> out = new TreeSet<>();
        final BlockPos a = building.getCorners().getA();
        final BlockPos b = building.getCorners().getB();
        for (final BlockPos p : BlockPos.betweenClosed(
                new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ())),
                new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ())))) {
            if (level.getBlockState(p).is(rack)) {
                out.add(p.immutable());
            }
        }
        return out;
    }

    private Set<BlockPos> rackContainers(final IBuilding building) {
        final Set<BlockPos> out = new TreeSet<>();
        for (final TileEntityRack rack : racks(building)) {
            out.add(rack.getBlockPos().immutable());
        }
        return out;
    }

    private List<Integer> rackSizes(final IBuilding building) {
        final List<Integer> out = new ArrayList<>();
        for (final TileEntityRack rack : racks(building)) {
            out.add(rack.getInventory().getSlots());
        }
        out.sort(Integer::compare);
        return out;
    }

    // ================================================================ counting

    private List<Item> outputs() {
        final List<Item> out = new ArrayList<>();
        for (final Kits.Kit kit : Kits.all()) {
            if (!out.contains(kit.output())) {
                out.add(kit.output());
            }
        }
        return out;
    }

    private Kits.Kit kitOf(final Item output) {
        for (final Kits.Kit kit : Kits.all()) {
            if (kit.output() == output) {
                return kit;
            }
        }
        return null;
    }

    private int ownStock(final Item item) {
        return hut.stock(item);
    }

    private int inWarehouse(final Item item) {
        return warehouse == null ? 0 : BuildingDecorator.countIn(warehouse.getItemHandlerCap(), s -> s.is(item));
    }

    /** What the colony's couriers carry of an item. */
    private int carried(final Item item) {
        int total = 0;
        for (final ICitizenData c : colony.getCitizenManager().getCitizens()) {
            if (c.getJob() instanceof JobDeliveryman) {
                total += BuildingDecorator.countIn(c.getInventory(), s -> s.is(item));
            }
        }
        return total;
    }

    private int placed(final Item item) {
        int total = 0;
        final DecorLedger ledger = DecorLedger.get(level);
        for (final DecorLedger.GroupView group : ledger.groups(colony.getID())) {
            for (final DecorLedger.Entry entry : group.entries().values()) {
                if (entry.paid() && entry.state().getBlock().asItem() == item) {
                    total++;
                }
            }
        }
        return total;
    }

    /** Everything of one item in the colony: every building's inventory (racks included), every citizen's pack, the placed pieces, the drops. */
    private int everywhere(final Item item) {
        final Predicate<ItemStack> what = s -> s.is(item);
        int total = 0;
        for (final IBuilding b : colony.getServerBuildingManager().getBuildings().values()) {
            total += BuildingDecorator.countIn(b.getItemHandlerCap(), what);
        }
        for (final ICitizenData c : colony.getCitizenManager().getCitizens()) {
            total += BuildingDecorator.countIn(c.getInventory(), what);
        }
        total += placed(item);
        for (final ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, new AABB(center).inflate(80), e -> what.test(e.getItem()))) {
            total += drop.getItem().getCount();
        }
        return total;
    }

    /** (d): for every output, everything counted equals what the probe put in plus what assembly made. */
    private void countAll(final boolean report) {
        countChecks++;
        final StringBuilder out = new StringBuilder();
        boolean ok = true;
        for (final Item item : outputs()) {
            final int have = everywhere(item);
            final int want = probeNet.getOrDefault(item, 0) + madeNet.getOrDefault(item, 0);
            if (have != want) {
                ok = false;
            }
            out.append(id(item)).append(' ').append(have).append('/').append(want).append(", ");
        }
        if (!ok && !countBroken) {
            countBroken = true;
            check("(d) no decoration appears or goes", false, out.toString());
        } else if (report && !countBroken) {
            check("(d) no decoration appears or goes", true, countChecks + " counts; " + out);
        }
    }

    private static String id(final Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }

    private void putInHut(final Item item, final int n) {
        if (n <= 0) {
            return;
        }
        final ItemStack rest = ItemHandlerHelper.insertItemStacked(hut.getItemHandlerCap(), new ItemStack(item, n), false);
        check("the probe's " + n + " " + id(item) + " fit in the Workshop", rest.isEmpty(), rest.getCount() + " left over");
        probeNet.merge(item, n - rest.getCount(), Integer::sum);
    }

    /** Takes up to {@code n} of an item out of a handler; returns how many came out (the probe keeps them out). */
    private int takeOut(final IItemHandler handler, final Item item, final int n) {
        int got = 0;
        if (handler == null) {
            return 0;
        }
        for (int slot = 0; slot < handler.getSlots() && got < n; slot++) {
            final ItemStack stack = handler.getStackInSlot(slot);
            if (!stack.isEmpty() && stack.is(item)) {
                got += handler.extractItem(slot, Math.min(n - got, stack.getCount()), false).getCount();
            }
        }
        probeNet.merge(item, -got, Integer::sum);
        return got;
    }

    /** The Decorator's own stock of an item (hut and pack) set to exactly {@code n}. */
    private void ownTo(final Item item, final int n) {
        final int have = ownStock(item);
        if (have < n) {
            putInHut(item, n - have);
        } else if (have > n) {
            int left = have - n;
            left -= takeOut(decorator.getInventory(), item, left);
            takeOut(hut.getItemHandlerCap(), item, left);
        }
        checkEq("his own stock of " + id(item) + " set", ownStock(item), n);
    }

    /** Every output at the cap in his own storage. */
    private void allAtCap() {
        for (final Item item : outputs()) {
            ownTo(item, hut.stockCap());
        }
    }

    /** The warehouse's racks hold exactly {@code n} of an item. */
    private void warehouseTo(final Item item, final int n) {
        int have = inWarehouse(item);
        if (have > n) {
            takeOut(warehouse.getItemHandlerCap(), item, have - n);
        } else if (have < n) {
            int left = n - have;
            for (final TileEntityRack rack : racks(warehouse)) {
                final ItemStack rest = ItemHandlerHelper.insertItemStacked(rack.getInventory(), new ItemStack(item, left), false);
                probeNet.merge(item, left - rest.getCount(), Integer::sum);
                left = rest.getCount();
                if (left == 0) {
                    break;
                }
            }
        }
        checkEq("the warehouse holds " + id(item), inWarehouse(item), n);
    }

    // ================================================================ requests

    private List<IRequest<?>> openRequests() {
        return new ArrayList<>(hut.getOpenRequests(decorator.getId()));
    }

    private boolean isOutput(final ItemStack stack) {
        for (final Item item : outputs()) {
            if (stack.is(item)) {
                return true;
            }
        }
        return false;
    }

    private List<IRequest<?>> finishedRequests(final Item item) {
        final List<IRequest<?>> out = new ArrayList<>();
        for (final IRequest<?> r : openRequests()) {
            if (r.getRequest() instanceof Stack s && (item == null ? isOutput(s.getStack()) : s.getStack().is(item))) {
                out.add(r);
            }
        }
        return out;
    }

    /** Open requests for an ingredient of the wreath kit: the leaves tag, string, red dye. */
    private List<String> wreathIngredientRequests() {
        final List<String> out = new ArrayList<>();
        for (final IRequest<?> r : openRequests()) {
            if (r.getRequest() instanceof RequestTag t && t.getTag().equals(ItemTags.LEAVES)) {
                out.add("leaves");
            } else if (r.getRequest() instanceof Stack s && (s.getStack().is(Items.STRING) || s.getStack().is(Items.RED_DYE))) {
                out.add(id(s.getStack().getItem()));
            }
        }
        return out;
    }

    private IRequestResolver<?> resolverOf(final IToken<?> token) {
        try {
            return colony.getRequestManager().getResolverForRequest(token);
        } catch (final IllegalArgumentException none) {
            return null;
        }
    }

    private static String name(final Object o) {
        return o == null ? "none" : o.getClass().getSimpleName();
    }

    /** All run long: a request for a finished piece never stays with the player or the retrying resolver. */
    private void watchPlayerHeld() {
        final Set<IToken<?>> now = new LinkedHashSet<>();
        for (final IRequest<?> r : finishedRequests(null)) {
            final IRequestResolver<?> resolver = resolverOf(r.getId());
            if (resolver instanceof IPlayerRequestResolver || resolver instanceof IRetryingRequestResolver) {
                now.add(r.getId());
                final Integer since = playerHeldSince.putIfAbsent(r.getId(), ticks);
                if (since == null) {
                    playerHeldSeen++;
                } else if (ticks - since > PLAYER_HELD_LIMIT && !playerHeldBroken) {
                    playerHeldBroken = true;
                    check("no request for a finished piece stays with the player", false,
                            r.getShortDisplayString().getString() + " held by " + name(resolver) + " for " + (ticks - since) + " ticks");
                }
            }
        }
        playerHeldSince.keySet().retainAll(now);
    }

    private void cancelAll() {
        hut.cancelAllRequestsOfCitizenOrBuilding(decorator);
    }

    private void setPrepareAllYear(final boolean on) {
        final BoolSetting setting = hut.getSetting(BuildingDecorator.PREPARE_ALL_YEAR);
        if (setting.getValue() != on) {
            setting.trigger();
            hut.markDirty();
        }
        checkEq("prepare all year set to " + on, hut.prepareAllYear(), on);
    }

    private JobDecorator job() {
        return decorator.getJob() instanceof JobDecorator j ? j : null;
    }

    // ================================================================ default mode

    private void planDefault() {
        now("setup", () -> {
            setDate(SUMMER);
            quietWorld();
            buildColony();
            pasteWorkshop();
            if (hut == null || warehouse == null) {
                finish();
            }
        });
        waitForPaste();
        now("S0 and the people", () -> {
            s0();
            hirePeople();
            wreath = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("colony_seasons", "wreath"));
            check("the wreath kit is loaded", kitOf(wreath) != null && kitOf(wreath).count() == 2, String.valueOf(kitOf(wreath)));
            planTable();
        });
        upgradeSteps();
        planSupply();
        planCycle();
        now("the end", () -> {
            check("no request for a finished piece stayed with the player or the retrying resolver", !playerHeldBroken,
                    playerHeldSeen + " seen there for a moment (b3 and b4 force one each)");
            note("finished requests made {}, cancelled {}", job().finishedRequests(), job().finishedCancelled());
        });
    }

    private void waitForPaste() {
        step(new Step("the paste", 900, false) {
            @Override
            boolean tick() {
                final AbstractTileEntityColonyBuilding te = hut.getTileEntity();
                return t >= 100 && te != null && !te.getSchematicName().isEmpty() && hut.getBuildingLevel() >= 1;
            }

            @Override
            void end(final boolean done) {
                check("the paste finished", done, "level " + hut.getBuildingLevel() + ", built " + hut.isBuilt()
                        + ", schematic '" + (hut.getTileEntity() == null ? "-" : hut.getTileEntity().getSchematicName()) + "'");
                if (hut.getBuildingLevel() != LEVEL) {
                    note("the pasted level is {}: set to {}", hut.getBuildingLevel(), LEVEL);
                    hut.setBuildingLevel(LEVEL);
                    hut.markDirty();
                }
                owner.setGameMode(GameType.SURVIVAL);
            }
        });
    }

    /** S0 [V]: the racks the blueprint put inside the Workshop are its containers. */
    private void s0() {
        final Set<BlockPos> blocks = rackBlocksInside(hut);
        final Set<BlockPos> containers = rackContainers(hut);
        note("S0: {} rack blocks inside the Workshop, {} rack containers, containers {} (hut {} among them: {})", blocks.size(),
                containers.size(), hut.getContainers().size(), hutPos.toShortString(), hut.getContainers().contains(hutPos));
        check("S0 the pasted Workshop has the level-5 blueprint's 12 racks", blocks.size() == 12, blocks.size() + " rack blocks");
        check("S0 every rack of the pasted Workshop is one of its containers", containers.equals(blocks),
                "containers " + containers.size() + ", missing " + blocks.stream().filter(p -> !containers.contains(p)).count());
        checkEq("S0 the racks start at 27 slots", new TreeSet<>(rackSizes(hut)), new TreeSet<>(List.of(27)));
        note("hut handler {} slots; warehouse racks {}, its containers {}", hut.getItemHandlerCap().getSlots(), rackSizes(warehouse),
                warehouse.getContainers().size());
    }

    private void hirePeople() {
        decorator = spawn(hutPos.offset(0, 0, 6));
        check("a citizen is hired as the Decorator", hut.getModule(DecoratorModules.WORK).assignCitizen(decorator)
                && decorator.getJob() instanceof JobDecorator, decorator.getName());
        courier = spawn(center.offset(COURIER_OFFSET).offset(0, 0, 2));
        final boolean hired = courierHut != null && courierHut.getModule(BuildingModules.COURIER_WORK).assignCitizen(courier);
        final boolean linked = warehouse.getModule(BuildingModules.WAREHOUSE_COURIERS).assignCitizen(courier);
        check("a courier is hired and works for the warehouse", hired && linked && courier.getJob() instanceof JobDeliveryman
                        && ((JobDeliveryman) courier.getJob()).findWareHouse() == warehouse,
                courier.getName() + " -> " + name(courier.getJob()));
        checkEq("the Workshop's level", hut.getBuildingLevel(), LEVEL);
        checkEq("the stock cap at level 5", hut.stockCap(), 48);
    }

    /** P: the supply rule, row by row (pure). */
    private void planTable() {
        row("at the cap: nothing", 16, 0, 100, 2, 16, 0, false);
        row("one short of a batch: nothing", 15, 0, 100, 2, 16, 0, false);
        row("empty, the warehouse holds plenty: the whole gap finished, no ingredients", 0, 0, 100, 2, 16, 16, false);
        row("empty, the warehouse holds part: that part finished, ingredients for the rest", 0, 0, 5, 2, 16, 5, true);
        row("empty, the warehouse holds all but one: no batch fits after it", 0, 0, 15, 2, 16, 15, false);
        row("a finished request is open: no second one, ingredients while a batch still fits", 4, 6, 100, 2, 16, 0, true);
        row("a finished request fills the gap: nothing", 4, 12, 100, 2, 16, 0, false);
        row("no warehouse stock: ingredients as before", 0, 0, 0, 2, 16, 0, true);
        row("a batch larger than the cap, empty: ingredients", 0, 0, 0, 32, 16, 0, true);
        row("a batch larger than the cap, the warehouse holds 10: 10 finished and still ingredients", 0, 0, 10, 32, 16, 10, true);
        row("a batch larger than the cap, at the cap: nothing", 16, 0, 100, 32, 16, 0, false);
        row("a held count below zero counts as none", 0, 0, -5, 2, 16, 0, true);
        check("Kits.wanted edges", Kits.wanted(14, 2, 16) && !Kits.wanted(15, 2, 16) && !Kits.wanted(16, 2, 16)
                && Kits.wanted(15, 32, 16) && !Kits.wanted(16, 32, 16), "");
    }

    private void row(final String what, final int have, final int inflight, final int held, final int count, final int cap,
                     final int finished, final boolean ingredients) {
        final Supply.Plan plan = Supply.plan(have, inflight, held, count, cap);
        checkEq("P " + what, plan.finished() + "/" + plan.ingredients(), finished + "/" + ingredients);
    }

    // ---------------------------------------------------------------- (e) the storage upgrade

    private int emeraldBlocks() {
        int n = 0;
        for (int i = 0; i < owner.getInventory().getContainerSize(); i++) {
            final ItemStack stack = owner.getInventory().getItem(i);
            if (stack.is(Items.EMERALD_BLOCK)) {
                n += stack.getCount();
            }
        }
        return n;
    }

    private void giveEmeralds(final int n) {
        owner.getInventory().add(new ItemStack(Items.EMERALD_BLOCK, n));
    }

    private UpgradeStoragePayload.Result clickUpgrade() {
        return UpgradeStoragePayload.apply(owner, hutPos);
    }

    /** One storage step clicked; the next tick checks the racks (MineColonies rebuilds the hut's combined handler each tick). */
    private void upgradeClick(final int expectStep, final List<Integer> ours) {
        final int[] before = new int[3];
        now("(e) step " + expectStep + ": click", () -> {
            before[0] = emeraldBlocks();
            before[1] = hut.getItemHandlerCap().getSlots();
            before[2] = racks(hut).size();
            final UpgradeStoragePayload.Result r = clickUpgrade();
            checkEq("(e) step " + expectStep + " is taken", r, UpgradeStoragePayload.Result.UPGRADED);
            checkEq("(e) step " + expectStep + " takes exactly one emerald block", before[0] - emeraldBlocks(), 1);
            checkEq("(e) the step count", hut.storageStep(), expectStep);
        });
        now("(e) step " + expectStep + ": the racks", () -> {
            final int size = 27 + 9 * expectStep;
            checkEq("(e) every rack after step " + expectStep, new TreeSet<>(rackSizes(hut)), new TreeSet<>(List.of(size)));
            checkEq("(e) the Workshop's inventory after step " + expectStep + " grew by 9 slots a rack",
                    hut.getItemHandlerCap().getSlots() - before[1], 9 * before[2]);
            ours.add(size);
        });
    }

    private void upgradeSteps() {
        final List<Integer> ours = new ArrayList<>();
        now("(e) refusals", () -> {
            owner.getInventory().clearContent();
            stand(hutPos.offset(0, 0, 6));
            checkEq("(e) the storage step starts at 0", hut.storageStep(), 0);
            checkEq("(e) without an emerald block: refused", clickUpgrade(), UpgradeStoragePayload.Result.NO_PRICE);
            checkEq("(e) nothing changed without a block", hut.storageStep() + " " + new TreeSet<>(rackSizes(hut)), "0 [27]");
            giveEmeralds(5);
            hut.setBuildingLevel(4);
            checkEq("(e) at level 4: refused", clickUpgrade(), UpgradeStoragePayload.Result.NOT_OFFERED);
            checkEq("(e) nothing charged at level 4", emeraldBlocks() + " blocks, step " + hut.storageStep(), "5 blocks, step 0");
            hut.setBuildingLevel(LEVEL);
            hut.markDirty();
            stand(hutPos.offset(0, 0, 20));
            checkEq("(e) from 20 blocks away: refused", clickUpgrade(), UpgradeStoragePayload.Result.TOO_FAR);
            stand(hutPos.offset(0, 0, 6));
            // a position a client made up, far from the player: refused on the range alone, before anything that
            // touches a chunk (MineColonies' colony lookup by position loads or generates the chunk there)
            final BlockPos far = hutPos.offset(40_000, 0, 40_000);
            final boolean farBefore = level.hasChunk(far.getX() >> 4, far.getZ() >> 4);
            checkEq("(e) a hut position 40000 blocks away: refused on the range", UpgradeStoragePayload.apply(owner, far),
                    UpgradeStoragePayload.Result.TOO_FAR);
            final boolean farAfter = level.hasChunk(far.getX() >> 4, far.getZ() >> 4);
            check("(e) the far position's chunk was not loaded by the click", !farBefore && !farAfter,
                    "loaded before " + farBefore + ", after " + farAfter);
            // a player with no rank in the colony, next to the Workshop: refused, nothing charged
            final ServerPlayer stranger = FakePlayerFactory.get(level,
                    new GameProfile(UUID.nameUUIDFromBytes("csstorage_stranger".getBytes(StandardCharsets.UTF_8)), "csstorage_stranger"));
            stranger.setPos(hutPos.getX() + 0.5, hutPos.getY(), hutPos.getZ() + 6.5);
            stranger.getInventory().add(new ItemStack(Items.EMERALD_BLOCK, 1));
            checkEq("(e) a player with no rank: refused", UpgradeStoragePayload.apply(stranger, hutPos),
                    UpgradeStoragePayload.Result.NO_PERMISSION);
            checkEq("(e) nothing taken from him, nothing changed", stranger.getInventory().countItem(Items.EMERALD_BLOCK)
                    + " blocks, step " + hut.storageStep(), "1 blocks, step 0");
            stranger.getInventory().clearContent();
            final BlockPos wh = warehouse.getPosition();
            stand(wh.offset(0, 0, 3));
            checkEq("(e) at the warehouse: refused, not a Workshop", UpgradeStoragePayload.apply(owner, wh),
                    UpgradeStoragePayload.Result.NOT_A_WORKSHOP);
            stand(hutPos.offset(0, 0, 6));
            checkEq("(e) nothing charged by the refusals", emeraldBlocks() + " blocks, step " + hut.storageStep(), "5 blocks, step 0");
        });
        upgradeClick(1, ours);
        upgradeClick(2, ours);
        now("(e) a rack put in after step 2", () -> {
            final Block rack = block("minecolonies:blockminecoloniesrack");
            final BlockPos at = hutPos.offset(-1, 0, -3);   // a free cell on the ground floor's back wall, next to no rack
            set(at, rack.defaultBlockState());
            hut.registerBlockPosition(rack.defaultBlockState(), at, level);
            final BlockEntity be = level.getBlockEntity(at);
            check("(e) a rack registered after step 2 comes in at 45 slots", be instanceof TileEntityRack r && r.getInventory().getSlots() == 45
                    && hut.getContainers().contains(at), be instanceof TileEntityRack r ? r.getInventory().getSlots() + " slots" : name(be));
            check("(e) it is no warehouse rack", be instanceof TileEntityRack r && !inWarehouseFlag(r), "");
        });
        upgradeClick(3, ours);
        now("(e) after the last step", () -> {
            final int blocks = emeraldBlocks();
            checkEq("(e) a fourth step: refused", clickUpgrade(), UpgradeStoragePayload.Result.NOT_OFFERED);
            checkEq("(e) nothing charged for a fourth", emeraldBlocks() + " blocks, step " + hut.storageStep(), blocks + " blocks, step 3");
            check("(e) the hut window would not offer it", !hut.canUpgradeStorage(), "");
        });
        // MineColonies' own Warehouse upgrade, run the way its server handler runs it, as the control
        final List<Integer> theirs = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            final int n = i;
            now("(e) control: MineColonies' Warehouse step " + n, () -> {
                stand(warehouse.getPosition().offset(0, 0, 3));
                owner.getInventory().clearContent();
                giveEmeralds(2);
                final boolean ran = warehouseMessage();
                checkEq("(e) control: the Warehouse step " + n + " takes one emerald block", ran + " " + emeraldBlocks(), "true 1");
                checkEq("(e) control: the Warehouse's step count", warehouse.getFirstModuleOccurance(WarehouseModule.class).getStorageUpgrade(), n);
            });
            now("(e) control: the Warehouse racks after step " + n, () -> {
                final TreeSet<Integer> sizes = new TreeSet<>(rackSizes(warehouse));
                check("(e) control: one size for every Warehouse rack", sizes.size() == 1, String.valueOf(sizes));
                theirs.add(sizes.first());
                stand(hutPos.offset(0, 0, 6));
            });
        }
        now("(e) the same as the Warehouse", () -> checkEq("(e) the Workshop's racks per step equal the Warehouse's", ours, theirs));
    }

    /** MineColonies' rack flag for a warehouse rack (protected, read by reflection). */
    private static boolean inWarehouseFlag(final TileEntityRack rack) {
        try {
            final java.lang.reflect.Field f = com.minecolonies.api.tileentities.AbstractTileEntityRack.class.getDeclaredField("inWarehouse");
            f.setAccessible(true);
            return f.getBoolean(rack);
        } catch (final ReflectiveOperationException unreadable) {
            return true;
        }
    }

    /** MineColonies' UpgradeWarehouseMessage as the server receives it: built from bytes, run through its server handler with the owner as sender. */
    private boolean warehouseMessage() {
        try {
            final RegistryFriendlyByteBuf in = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
            in.writeUtf(level.dimension().location().toString());
            in.writeInt(colony.getID());
            in.writeBlockPos(warehouse.getID());
            final Constructor<UpgradeWarehouseMessage> reader = UpgradeWarehouseMessage.class.getDeclaredConstructor(RegistryFriendlyByteBuf.class, PlayMessageType.class);
            reader.setAccessible(true);
            final UpgradeWarehouseMessage msg = reader.newInstance(in, UpgradeWarehouseMessage.TYPE);
            msg.onExecute(null, owner);
            return true;
        } catch (final ReflectiveOperationException | RuntimeException unbuilt) {
            check("(e) control: MineColonies' upgrade message could be built and run", false, unbuilt.toString());
            return false;
        }
    }

    // ---------------------------------------------------------------- (b), (b2), (b3), (c): the supply order, off season

    private void planSupply() {
        step(new Step("(b) finished wreaths from the warehouse", 9000) {
            int made0;
            boolean asked;
            final List<String> ingredientSeen = new ArrayList<>();
            int delivered = -1;
            int carriedMost;

            @Override
            void start() {
                cancelAll();
                allAtCap();
                ownTo(wreath, 0);
                warehouseTo(wreath, hut.stockCap() + WAREHOUSE_EXTRA);
                made0 = hut.assembled();
                checkEq("(b) Supply.held sees the warehouse's wreaths", Supply.held(colony, wreath), hut.stockCap() + WAREHOUSE_EXTRA);
            }

            @Override
            boolean tick() {
                final List<IRequest<?>> fin = finishedRequests(wreath);
                if (!asked && !fin.isEmpty()) {
                    asked = true;
                    final IRequest<?> r = fin.get(0);
                    final Stack s = (Stack) r.getRequest();
                    final IRequestResolver<?> resolver = resolverOf(r.getId());
                    checkEq("(b) one finished wreath request", fin.size(), 1);
                    checkEq("(b) it asks for the whole gap to the cap", s.getCount(), hut.stockCap());
                    check("(b) the Workshop may not resolve it from its own racks", !s.canBeResolvedByBuilding(), "");
                    check("(b) a warehouse holds it", resolver instanceof AbstractWarehouseRequestResolver, name(resolver));
                }
                carriedMost = Math.max(carriedMost, carried(wreath));
                if (delivered < 0) {
                    ingredientSeen.addAll(wreathIngredientRequests());
                    if (ownStock(wreath) >= hut.stockCap() && finishedRequests(wreath).isEmpty()) {
                        delivered = t;
                    }
                }
                return delivered >= 0 && t >= delivered + 200;
            }

            @Override
            void end(final boolean done) {
                check("(b) the courier delivered", done && carriedMost > 0, "his stock " + ownStock(wreath) + " after " + t
                        + " ticks, the courier carried up to " + carriedMost + " wreaths on the way");
                check("(b) no leaves, string or red dye was asked for before the delivery", asked && ingredientSeen.isEmpty(),
                        "asked " + asked + ", seen " + new LinkedHashSet<>(ingredientSeen));
                checkEq("(b) his stock stops at the cap", ownStock(wreath), hut.stockCap());
                checkEq("(b) the warehouse keeps the rest", inWarehouse(wreath), WAREHOUSE_EXTRA);
                checkEq("(b) nothing was assembled", hut.assembled(), made0);
                check("(b) he counted a finished request", job().finishedRequests() >= 1, job().finishedRequests() + " made");
            }
        });
        step(new Step("(b2) the warehouse holds part of the gap", 9000) {
            int made0;
            int askedFor = -1;
            int maxOwn;

            @Override
            void start() {
                cancelAll();
                allAtCap();
                ownTo(wreath, hut.stockCap() - 6);
                warehouseTo(wreath, 2);
                // one batch of each wreath ingredient twice, in the hut (he fetches them from there himself)
                putInHut(Items.OAK_LEAVES, 8);
                putInHut(Items.STRING, 2);
                putInHut(Items.RED_DYE, 2);
                made0 = hut.assembled();
            }

            @Override
            boolean tick() {
                final List<IRequest<?>> fin = finishedRequests(wreath);
                if (askedFor < 0 && !fin.isEmpty()) {
                    askedFor = ((Stack) fin.get(0).getRequest()).getCount();
                }
                maxOwn = Math.max(maxOwn, ownStock(wreath));
                return askedFor >= 0 && ownStock(wreath) == hut.stockCap() && finishedRequests(wreath).isEmpty()
                        && hut.assembled() - made0 >= 2 && t % 20 == 0;
            }

            @Override
            void end(final boolean done) {
                checkEq("(b2) the finished request asks for what the warehouse holds", askedFor, 2);
                checkEq("(b2) two batches made from ingredients for the rest", hut.assembled() - made0, 2);
                check("(b2) the stock settles at the cap", done && ownStock(wreath) == hut.stockCap(), "stock " + ownStock(wreath) + " after " + t + " ticks");
                check("(b2) never above the cap", maxOwn <= hut.stockCap(), "at most " + maxOwn);
                checkEq("(b2) the warehouse gave its two", inWarehouse(wreath), 0);
                madeNet.merge(wreath, 2 * (hut.assembled() - made0), Integer::sum);
            }
        });
        final IToken<?>[] forced = new IToken<?>[1];
        final int[] marks = new int[3];
        step(new Step("(b3) a finished request the player would hold", 600) {
            @Override
            void start() {
                cancelAll();
                ownTo(wreath, hut.stockCap() - 10);
                warehouseTo(wreath, 0);
                marks[2] = job().decisions();
            }

            @Override
            boolean tick() {
                // forced right after one of his decisions (from the review): (b2) may end just after a batch, and the
                // pause after a batch (up to ASSEMBLY_DELAY) plus a pass is longer than PLAYER_HELD_LIMIT, so forcing it
                // at once measured that pause, not the one pass the rule promises
                if (forced[0] == null) {
                    if (job().decisions() > marks[2]) {
                        marks[0] = job().decisions();
                        marks[1] = job().finishedCancelled();
                        forced[0] = decorator.createRequestAsync(new Stack(new ItemStack(wreath), true, true, ItemStackUtils.EMPTY, 4, 1, false));
                        final IRequestResolver<?> resolver = resolverOf(forced[0]);
                        note("(b3) the forced request went to {}", name(resolver));
                        check("(b3) with an empty warehouse the request lands with the player or the retrying resolver",
                                resolver instanceof IPlayerRequestResolver || resolver instanceof IRetryingRequestResolver, name(resolver));
                    }
                    return false;
                }
                return job().decisions() - marks[0] >= 2;
            }

            @Override
            void end(final boolean done) {
                final boolean open = forced[0] == null
                        || finishedRequests(wreath).stream().anyMatch(r -> r.getId().equals(forced[0]));
                check("(b3) it is cancelled within one pass", done && !open, "open " + open + " after " + t + " ticks, "
                        + (job().decisions() - marks[0]) + " decisions");
                checkEq("(b3) he counted the cancel", job().finishedCancelled() - marks[1], 1);
            }
        });
        // from the review: the same while he does not prepare at all (prepare all year off, the window closed), where a
        // request handed on to the player used to wait until the next window
        final IToken<?>[] forcedIdle = new IToken<?>[1];
        final int[] marksIdle = new int[3];
        step(new Step("(b4) a finished request the player would hold while he does not prepare", 600) {
            @Override
            void start() {
                cancelAll();
                setPrepareAllYear(false);
                warehouseTo(wreath, 0);
                marksIdle[2] = job().decisions();
            }

            @Override
            boolean tick() {
                if (forcedIdle[0] == null) {
                    if (job().decisions() > marksIdle[2]) {          // right after a decision, as in (b3)
                        marksIdle[0] = job().decisions();
                        marksIdle[1] = job().finishedCancelled();
                        forcedIdle[0] = decorator.createRequestAsync(
                                new Stack(new ItemStack(wreath), true, true, ItemStackUtils.EMPTY, 4, 1, false));
                        final IRequestResolver<?> resolver = resolverOf(forcedIdle[0]);
                        check("(b4) the forced request lands with the player or the retrying resolver",
                                resolver instanceof IPlayerRequestResolver || resolver instanceof IRetryingRequestResolver, name(resolver));
                    }
                    return false;
                }
                return job().decisions() - marksIdle[0] >= 2;
            }

            @Override
            void end(final boolean done) {
                final boolean open = forcedIdle[0] == null
                        || finishedRequests(wreath).stream().anyMatch(r -> r.getId().equals(forcedIdle[0]));
                check("(b4) he does not prepare: the window is closed and prepare all year is off",
                        job().status().contains("window_only"), job().status());
                check("(b4) it is cancelled within one decision", done && !open, "open " + open + " after " + t + " ticks, "
                        + (job().decisions() - marksIdle[0]) + " decisions");
                checkEq("(b4) he counted the cancel", job().finishedCancelled() - marksIdle[1], 1);
                setPrepareAllYear(true);
            }
        });
        step(new Step("(c) nothing anywhere: ingredients as before", 3000) {
            int decisions0;

            @Override
            void start() {
                cancelAll();
                ownTo(wreath, 0);
                warehouseTo(wreath, 0);
                decisions0 = job().decisions();
            }

            @Override
            boolean tick() {
                return job().decisions() - decisions0 >= 20 && new LinkedHashSet<>(wreathIngredientRequests()).size() == 3;
            }

            @Override
            void end(final boolean done) {
                checkEq("(c) the ingredient requests appear: the leaves tag, string, red dye",
                        new TreeSet<>(wreathIngredientRequests()), new TreeSet<>(List.of("leaves", "string", "red_dye")));
                checkEq("(c) no finished request", finishedRequests(wreath).size(), 0);
                check("(c) he says he waits for materials", job().status().contains("waiting_materials") || job().status().contains("assembl"),
                        job().status());
            }
        });
    }

    // ---------------------------------------------------------------- (a) and (d): the decorating and the take-down

    private void planCycle() {
        final Map<Item, Integer> own0 = new LinkedHashMap<>();
        step(new Step("(a) own stock first, no request while nothing is wanted", 2000) {
            int decisions0;
            int requestsSeen;

            @Override
            void start() {
                cancelAll();
                // the ingredients left over from (b2), if any, go: nothing may be assembled in (a) and (d)
                for (final Item used : List.of(Items.OAK_LEAVES, Items.STRING, Items.RED_DYE)) {
                    takeOut(hut.getItemHandlerCap(), used, 64);
                    takeOut(decorator.getInventory(), used, 64);
                }
                allAtCap();
                warehouseTo(wreath, 0);
                decisions0 = job().decisions();
                counting = true;
                countAll(false);
            }

            @Override
            boolean tick() {
                requestsSeen += openRequests().size();
                return job().decisions() - decisions0 >= 20;
            }

            @Override
            void end(final boolean done) {
                checkEq("(a) at the cap: no request of any kind over 20 decisions", requestsSeen, 0);
                for (final Item item : outputs()) {
                    own0.put(item, ownStock(item));
                }
            }
        });
        final int[] made0 = new int[1];
        step(new Step("(a) the window opens: he decorates from his storage", 14000) {
            boolean early;
            int firstAt = -1;
            String atFirst = "";
            int lastCount;
            int lastChange;
            int visitsAtChange;

            @Override
            void start() {
                setDate(DECOR_DAY);
                made0[0] = hut.assembled();
            }

            @Override
            boolean tick() {
                boolean anyWanted = false;
                for (final Item item : outputs()) {
                    final Kits.Kit kit = kitOf(item);
                    final int inflight = finishedRequests(item).stream().mapToInt(r -> ((Stack) r.getRequest()).getCount()).sum();
                    if (Kits.wanted(ownStock(item) + inflight, kit.count(), hut.stockCap())) {
                        anyWanted = true;
                    } else if (!finishedRequests(item).isEmpty() && !early) {
                        early = true;
                        check("(a) no finished request for an output that is not wanted", false, id(item));
                    }
                }
                if (!anyWanted && !openRequests().isEmpty() && !early) {
                    early = true;
                    check("(a) no request while no output is wanted", false, openRequests().get(0).getShortDisplayString().getString());
                }
                if (firstAt < 0 && placed(wreath) > 0) {
                    firstAt = t;
                    atFirst = "requests at the first wreath: " + openRequests().size();
                }
                // done once nothing new went up for 2000 ticks and four decorating visits
                final int count = DecorLedger.get(level).count(colony.getID());
                if (count != lastCount) {
                    lastCount = count;
                    lastChange = t;
                    visitsAtChange = job().visits(me.lovkar.seasons.ai.EntityAIWorkDecorator.DecoratorState.DECORATING);
                }
                return count > 0 && t - lastChange >= 2000
                        && job().visits(me.lovkar.seasons.ai.EntityAIWorkDecorator.DecoratorState.DECORATING) - visitsAtChange >= 4;
            }

            @Override
            void end(final boolean done) {
                note("(a) {}; buildings done {}, entries {}, status {}", atFirst, DecorLedger.get(level).buildingsDone(colony.getID()),
                        DecorLedger.get(level).count(colony.getID()), job().status());
                check("(a) he decorated the colony", done, "after " + t + " ticks, buildings done " + DecorLedger.get(level).buildingsDone(colony.getID()));
                check("(a) wreaths went up", placed(wreath) > 0, placed(wreath) + " wreaths");
                checkEq("(a) nothing was assembled", hut.assembled(), made0[0]);
                check("(a) the finished pieces came from his storage only", !early, "");
                for (final Item item : outputs()) {
                    checkEq("(a) his storage lost exactly the " + id(item) + " placed", own0.get(item) - ownStock(item), placed(item));
                }
                checkEq("(a) no finished request with an empty warehouse", finishedRequests(null).size(), 0);
            }
        });
        step(new Step("(d) the warehouse's wreaths come while the window is open", 6000) {
            int carriedMost;

            @Override
            void start() {
                warehouseTo(wreath, WAREHOUSE_EXTRA);
            }

            @Override
            boolean tick() {
                carriedMost = Math.max(carriedMost, carried(wreath));
                return inWarehouse(wreath) < WAREHOUSE_EXTRA && finishedRequests(wreath).isEmpty() && carried(wreath) == 0 && t % 20 == 0;
            }

            @Override
            void end(final boolean done) {
                check("(d) he fetched wreaths from the warehouse to refill his stock", done && carriedMost > 0, "warehouse "
                        + inWarehouse(wreath) + ", his stock " + ownStock(wreath) + " of " + hut.stockCap() + ", the courier carried up to " + carriedMost);
                countAll(false);
            }
        });
        final Map<Item, Integer> held0 = new LinkedHashMap<>();
        final Map<Item, Integer> placed0 = new LinkedHashMap<>();
        step(new Step("(d) taking down after the window", 16000) {
            @Override
            void start() {
                for (final Item item : outputs()) {
                    held0.put(item, ownStock(item) + inWarehouse(item) + carried(item));
                    placed0.put(item, placed(item));
                }
                setDate(AFTER);
            }

            @Override
            boolean tick() {
                return t % 20 == 0 && !DecorLedger.get(level).hasEntries(colony.getID()) && job().phase() == DecorPhase.OFF_SEASON;
            }

            @Override
            void end(final boolean done) {
                check("(d) everything was taken down", done, DecorLedger.get(level).count(colony.getID()) + " entries left after " + t + " ticks");
                countAll(true);
                checkEq("(d) nothing was assembled through the cycle", hut.assembled(), made0[0]);
                for (final Item item : outputs()) {
                    checkEq("(d) every " + id(item) + " taken down is back in his storage (or moved on by the courier)",
                            ownStock(item) + inWarehouse(item) + carried(item) - held0.get(item), placed0.get(item));
                }
                counting = false;
            }
        });
    }

    // ================================================================ restart

    private void planRestartSave() {
        now("setup", () -> {
            setDate(SUMMER);
            quietWorld();
            buildColony();
            pasteWorkshop();
            if (hut == null) {
                finish();
            }
        });
        waitForPaste();
        now("save: hire, two steps, the stock", () -> {
            hirePeople();
            stand(hutPos.offset(0, 0, 6));
            owner.getInventory().clearContent();
            owner.setGameMode(GameType.CREATIVE);
            checkEq("creative: the first step is free", clickUpgrade() + " " + emeraldBlocks(), "UPGRADED 0");
            owner.setGameMode(GameType.SURVIVAL);
            giveEmeralds(1);
            checkEq("survival: the second step takes the block", clickUpgrade() + " " + emeraldBlocks(), "UPGRADED 0");
            allAtCap();
        });
        step(new Step("save: 20 decisions at the cap", 2000) {
            int decisions0;

            @Override
            void start() {
                decisions0 = job().decisions();
            }

            @Override
            boolean tick() {
                return job().decisions() - decisions0 >= 20;
            }

            @Override
            void end(final boolean done) {
                checkEq("save: the racks before the restart", new TreeSet<>(rackSizes(hut)), new TreeSet<>(List.of(45)));
                checkEq("save: no request at the cap", openRequests().size(), 0);
                // MineColonies saves each module's own tag under building_modules, by the module's name
                final CompoundTag modules = hut.serializeNBT(level.registryAccess()).getCompound("building_modules");
                final CompoundTag storage = modules.getCompound("decorator_storage");
                checkEq("save: cs_storage_step in the building's NBT (building_modules/decorator_storage)",
                        storage.contains("cs_storage_step") ? storage.getInt("cs_storage_step") : -1, 2);
                note("module tags saved: {}", modules.getAllKeys());
                final StringBuilder stock = new StringBuilder();
                for (final Item item : outputs()) {
                    stock.append(id(item)).append(' ').append(ownStock(item)).append(", ");
                }
                note("saving: colony {}, hut {}, step {}, {} racks, stock {}", colony.getID(), hutPos.toShortString(), hut.storageStep(),
                        racks(hut).size(), stock);
                server.tickRateManager().stopSprinting();
                check("the world saved", server.saveEverything(false, true, true), "");
            }
        });
    }

    private void planRestartLoad() {
        step(new Step("load: find the colony again", 2400, false) {
            @Override
            boolean tick() {
                final IColony found = IColonyManager.getInstance().getColonyByWorld(1, level);
                if (found == null) {
                    return false;
                }
                for (final IBuilding b : found.getServerBuildingManager().getBuildings().values()) {
                    if (b instanceof BuildingDecorator d) {
                        colony = found;
                        hut = d;
                        return t > 100;
                    }
                }
                return false;
            }

            @Override
            void end(final boolean done) {
                check("the colony and its Workshop are back", done, colony == null ? "no colony" : "colony " + colony.getID());
                if (!done) {
                    finish();
                    return;
                }
                hutPos = hut.getPosition();
                center = colony.getCenter();
                setDate(SUMMER);
                quietWorld();
                owner = FakePlayerFactory.getMinecraft(level);
                colony.getPackageManager().addCloseSubscriber(owner);
                final List<ICitizenData> hired = hut.getModule(DecoratorModules.WORK).getAssignedCitizen();
                decorator = hired.isEmpty() ? null : hired.get(0);
                check("the Decorator is still hired", decorator != null && decorator.getJob() instanceof JobDecorator, "");
                if (decorator == null) {
                    finish();
                    return;
                }
                checkEq("load: the storage step (cs_storage_step)", hut.storageStep(), 2);
                checkEq("load: 12 racks are still the Workshop's", racks(hut).size(), 12);
                checkEq("load: every rack kept its size", new TreeSet<>(rackSizes(hut)), new TreeSet<>(List.of(45)));
                for (final Item item : outputs()) {
                    checkEq("load: his stock of " + id(item), ownStock(item), hut.stockCap());
                }
                check("load: the window offers the third step", hut.canUpgradeStorage(), "level " + hut.getBuildingLevel());
            }
        });
        step(new Step("load: 20 decisions at the cap", 2400) {
            int decisions0;
            int requestsSeen;

            @Override
            void start() {
                decisions0 = job().decisions();
            }

            @Override
            boolean tick() {
                requestsSeen += openRequests().size();
                return job().decisions() - decisions0 >= 20;
            }

            @Override
            void end(final boolean done) {
                check("load: he works after the restart", done, (job().decisions() - decisions0) + " decisions");
                checkEq("load: no new request for an output at its cap", requestsSeen, 0);
            }
        });
        now("load: one more step after the restart", () -> {
            stand(hutPos.offset(0, 0, 6));
            owner.getInventory().clearContent();
            giveEmeralds(1);
            checkEq("load: the third step", clickUpgrade() + " " + emeraldBlocks() + " " + hut.storageStep(), "UPGRADED 0 3");
        });
        now("load: the racks after the third step", () ->
                checkEq("load: every rack at 54 slots", new TreeSet<>(rackSizes(hut)), new TreeSet<>(List.of(54))));
    }
}
