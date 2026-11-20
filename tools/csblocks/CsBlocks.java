package me.lovkar.seasons.csblocks;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.lovkar.seasons.block.FestivalMarkerBlockEntity;
import me.lovkar.seasons.block.GiftPileBlockEntity;
import me.lovkar.seasons.block.LanternStringBlock;
import me.lovkar.seasons.block.LargeStarBlock;
import me.lovkar.seasons.block.Lights;
import me.lovkar.seasons.block.SeasonsBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Headless check of the decoration blocks (chunk S2) on the dedicated test server:
 * <ul>
 *   <li>every block and its item are registered, the creative tab lists all eleven, every block state has a
 *       variant in its blockstate file (and every model it names is in the jar), a crafting recipe makes each one;</li>
 *   <li>placed by hand (a fake player using the item on a block face): the wall blocks in all four facings, the gift
 *       pile and the marker facing each of four players, the rest once; the large star as two halves;</li>
 *   <li>the wall blocks have no collision, break at once and pop off (dropping their item) when the wall behind
 *       them is removed; the leaves and the crate have collision; the leaves are in the leaves and hoe tags, never
 *       random-tick and have no distance/persistent property;</li>
 *   <li>each block drops its own item through its loot table (the large star only from its lower half);</li>
 *   <li>{@code lit} gives exactly the block's light level and none when off, in the state and in the world's block
 *       light; an empty hand switches it, a full hand does not;</li>
 *   <li>the gift pile's and the marker's block entity fields survive an NBT round trip.</li>
 * </ul>
 * Prints "[csblocks] CHECK ..." lines and "[csblocks] RESULT csblocks: PASS|FAIL", then halts. The words "error"
 * and "exception" stay out of the details: the rig takes such a line for a failure.
 */
@Mod("colonyseasonsblocks")
public class CsBlocks {
    private static final Logger LOGGER = LoggerFactory.getLogger("csblocks");
    private static final String NS = "colony_seasons";
    private static final int START = 100;
    private static final int LIGHT_ON_CHECK = START + 50;
    private static final int LIGHT_OFF_CHECK = START + 100;

    private int ticks = 0;
    private boolean failed = false;
    private boolean done = false;
    private ServerLevel level;
    private FakePlayer player;
    private BlockPos origin;
    /** Lit blocks set out for the world light check, and the light each should give. */
    private final Map<BlockPos, Integer> lightSpots = new LinkedHashMap<>();

    public CsBlocks() {
        NeoForge.EVENT_BUS.addListener(this::onTick);
    }

    private void check(final String what, final boolean ok, final String detail) {
        LOGGER.info("[csblocks] CHECK {}: {} {}", what, ok ? "OK" : "FAIL", detail);
        failed |= !ok;
    }

    private void checkEq(final String what, final Object actual, final Object expected) {
        check(what, Objects.equals(actual, expected), "got " + actual + (Objects.equals(actual, expected) ? "" : ", wanted " + expected));
    }

    private void onTick(final ServerTickEvent.Post event) {
        if (done) {
            return;
        }
        ticks++;
        final MinecraftServer server = event.getServer();
        try {
            if (ticks == START) {
                level = server.overworld();
                player = FakePlayerFactory.getMinecraft(level);
                final BlockPos spawn = level.getSharedSpawnPos();
                origin = new BlockPos(spawn.getX() - 20, level.getHeight(Heightmap.Types.MOTION_BLOCKING, spawn.getX(), spawn.getZ()),
                        spawn.getZ() - 20);
                player.setPos(origin.getX(), origin.getY(), origin.getZ());
                registration(server);
                blockstateFiles();
                wallBlocks();
                floorBlocks();
                otherBlocks();
                largeStar();
                drops();
                blockEntities();
                lightInState();
                setOutLights();
            } else if (ticks == LIGHT_ON_CHECK) {
                lightsOnThenSwitchOff();
            } else if (ticks == LIGHT_OFF_CHECK) {
                lightsOff();
                finish(server);
            }
        } catch (final Throwable t) {
            check("probe ran to the end", false, t.toString());
            finish(server);
        }
    }

    private void finish(final MinecraftServer server) {
        done = true;
        LOGGER.info("[csblocks] RESULT csblocks: {}", failed ? "FAIL" : "PASS");
        server.halt(false);
    }

    private static ResourceLocation id(final String path) {
        return ResourceLocation.fromNamespaceAndPath(NS, path);
    }

    private static String name(final DeferredBlock<? extends Block> block) {
        return block.getId().getPath();
    }

    // ------------------------------------------------------------------ registration, tab, recipes
    private void registration(final MinecraftServer server) {
        checkEq("eleven decoration blocks", SeasonsBlocks.ALL.size(), 11);
        for (final DeferredBlock<? extends Block> block : SeasonsBlocks.ALL) {
            final ResourceLocation rl = block.getId();
            final Item item = BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
            check("registered " + rl, BuiltInRegistries.BLOCK.containsKey(rl) && item instanceof BlockItem bi && bi.getBlock() == block.get(),
                    "block and its block item");
            final var recipe = server.getRecipeManager().byKey(rl);
            check("recipe " + rl, recipe.isPresent() && recipe.get().value().getResultItem(server.registryAccess()).is(item),
                    recipe.map(r -> "makes " + r.value().getResultItem(server.registryAccess())).orElse("missing"));
        }
        final CreativeModeTab tab = BuiltInRegistries.CREATIVE_MODE_TAB.get(id("main"));
        check("creative tab colony_seasons:main", tab != null, String.valueOf(tab == null ? null : tab.getDisplayName().getString()));
        if (tab != null) {
            tab.buildContents(new CreativeModeTab.ItemDisplayParameters(level.enabledFeatures(), false, level.registryAccess()));
            final List<String> missing = new ArrayList<>();
            for (final var item : SeasonsBlocks.ITEMS) {
                if (tab.getDisplayItems().stream().noneMatch(s -> s.is(item.get()))) {
                    missing.add(item.getId().getPath());
                }
            }
            // the decorations, and since S7 the gift (festival/Gifts puts it into this tab); nothing else
            final boolean gift = tab.getDisplayItems().stream().anyMatch(s -> s.is(me.lovkar.seasons.festival.Gifts.GIFT.get()));
            check("creative tab lists every decoration and the gift", missing.isEmpty() && gift
                            && tab.getDisplayItems().size() == SeasonsBlocks.ITEMS.size() + 1,
                    tab.getDisplayItems().size() + " items" + (missing.isEmpty() ? "" : ", missing " + missing) + (gift ? "" : ", no gift"));
        }
    }

    /** Every state of every block matches a variant of its blockstate file, and every model it names is in the jar. */
    private void blockstateFiles() throws java.io.IOException {
        final var file = ModList.get().getModFileById(NS).getFile();
        for (final DeferredBlock<? extends Block> block : SeasonsBlocks.ALL) {
            final Path path = file.findResource("assets", NS, "blockstates", name(block) + ".json");
            if (!Files.exists(path)) {
                check("blockstate file " + name(block), false, "not in the jar");
                continue;
            }
            final JsonObject variants = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("variants");
            int uncovered = 0;
            String firstUncovered = "";
            for (final BlockState state : block.get().getStateDefinition().getPossibleStates()) {
                boolean covered = false;
                for (final String key : variants.keySet()) {
                    if (matches(state, key)) {
                        covered = true;
                        break;
                    }
                }
                if (!covered) {
                    uncovered++;
                    firstUncovered = state.toString();
                }
            }
            int badKeys = 0;
            int missingModels = 0;
            for (final Map.Entry<String, JsonElement> e : variants.entrySet()) {
                final boolean any = block.get().getStateDefinition().getPossibleStates().stream().anyMatch(s -> matches(s, e.getKey()));
                badKeys += any ? 0 : 1;
                final List<JsonElement> models = new ArrayList<>();
                if (e.getValue().isJsonArray()) {
                    e.getValue().getAsJsonArray().forEach(models::add);
                } else {
                    models.add(e.getValue());
                }
                for (final JsonElement m : models) {
                    final String ref = m.getAsJsonObject().get("model").getAsString();
                    final String modelPath = ref.substring(ref.indexOf(':') + 1);
                    if (!Files.exists(file.findResource(("assets/" + NS + "/models/" + modelPath + ".json").split("/")))) {
                        missingModels++;
                    }
                }
            }
            check("blockstate file " + name(block), uncovered == 0 && badKeys == 0 && missingModels == 0,
                    block.get().getStateDefinition().getPossibleStates().size() + " states, " + variants.size() + " variants"
                            + (uncovered == 0 ? "" : ", " + uncovered + " states without a variant, e.g. " + firstUncovered)
                            + (badKeys == 0 ? "" : ", " + badKeys + " variants match no state")
                            + (missingModels == 0 ? "" : ", " + missingModels + " models missing"));
        }
    }

    private static boolean matches(final BlockState state, final String key) {
        if (key.isEmpty()) {
            return true;
        }
        for (final String kv : key.split(",")) {
            final String[] p = kv.split("=");
            final Property<?> property = state.getBlock().getStateDefinition().getProperty(p[0]);
            if (property == null || !valueName(state, property).equals(p[1])) {
                return false;
            }
        }
        return true;
    }

    private static <T extends Comparable<T>> String valueName(final BlockState state, final Property<T> property) {
        return property.getName(state.getValue(property));
    }

    // ------------------------------------------------------------------ placing by hand
    private InteractionResult use(final Item item, final BlockPos against, final Direction face, final float yaw, final float pitch) {
        player.setYRot(yaw);
        player.yRotO = yaw;
        player.setYHeadRot(yaw);
        player.setXRot(pitch);
        player.xRotO = pitch;
        final ItemStack stack = new ItemStack(item, 16);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        final Vec3 hit = Vec3.atCenterOf(against).relative(face, 0.5);
        final InteractionResult result = stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(hit, face, against, false)));
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        return result;
    }

    /** An interaction result in words that the rig's failure match does not mistake for a failed check. */
    private static String said(final InteractionResult r) {
        return r.consumesAction() ? "placed" : "refused";
    }

    /** A block's collision in words: "no collision" or its bounds. */
    private String shape(final BlockState state, final BlockPos pos) {
        final var c = state.getCollisionShape(level, pos);
        return c.isEmpty() ? "no collision" : "collides " + c.bounds();
    }

    private BlockPos spot(final int dx, final int dz) {
        return origin.offset(dx, 0, dz);
    }

    private int dropsNear(final BlockPos pos, final Item item) {
        int n = 0;
        for (final ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(1.5))) {
            if (e.getItem().is(item)) {
                n += e.getItem().getCount();
            }
        }
        return n;
    }

    private void clearDrops(final BlockPos pos) {
        level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(3)).forEach(ItemEntity::discard);
    }

    private void wallBlocks() {
        int k = 0;
        for (final DeferredBlock<? extends Block> block : SeasonsBlocks.WALL) {
            int f = 0;
            for (final Direction facing : Direction.Plane.HORIZONTAL) {
                final BlockPos wall = spot(4 * k, 4 * f).above();
                final BlockPos at = wall.relative(facing);
                level.setBlock(wall, Blocks.STONE_BRICKS.defaultBlockState(), Block.UPDATE_ALL);
                // the player stands in front and looks at the wall: towards -facing
                final InteractionResult r = use(block.get().asItem(), wall, facing, facing.getOpposite().toYRot(), 0);
                final BlockState placed = level.getBlockState(at);
                final String where = name(block) + " facing " + facing;
                check("placed by hand: " + where, r.consumesAction() && placed.is(block.get())
                        && placed.getValue(HorizontalDirectionalBlock.FACING) == facing, r + " -> " + placed);
                if (!placed.is(block.get())) {
                    f++;
                    continue;
                }
                if (placed.hasProperty(Lights.LIT)) {
                    check("comes on when placed: " + where, placed.getValue(Lights.LIT), placed.toString());
                }
                if (placed.hasProperty(LanternStringBlock.VARIANT)) {
                    checkEq("colour order along the wall: " + where, placed.getValue(LanternStringBlock.VARIANT),
                            LanternStringBlock.variantAt(at, facing));
                }
                check("no collision: " + where, placed.getCollisionShape(level, at).isEmpty(), shape(placed, at));
                checkEq("breaks at once: " + where, placed.getDestroySpeed(level, at), 0.0F);
                // the wall goes: the decoration pops off and drops as an item
                level.setBlock(wall, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                check("pops off without its wall: " + where, level.getBlockState(at).isAir(), String.valueOf(level.getBlockState(at)));
                checkEq("drops its item when it pops off: " + where, dropsNear(at, block.get().asItem()), 1);
                clearDrops(at);
                f++;
            }
            k++;
        }
        // three lantern strings hung side by side by hand show the three colour orders, along an x and a z wall
        for (final Direction facing : List.of(Direction.NORTH, Direction.EAST)) {
            final List<Integer> orders = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                final BlockPos wall = (facing == Direction.NORTH ? spot(i, -4) : spot(-4, i)).above();
                level.setBlock(wall, Blocks.STONE_BRICKS.defaultBlockState(), Block.UPDATE_ALL);
                use(SeasonsBlocks.LANTERN_STRING_ITEM.get(), wall, facing, facing.getOpposite().toYRot(), 0);
                final BlockState hung = level.getBlockState(wall.relative(facing));
                orders.add(hung.is(SeasonsBlocks.LANTERN_STRING.get()) ? hung.getValue(LanternStringBlock.VARIANT) : -1);
                level.setBlock(wall.relative(facing), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                level.setBlock(wall, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
            check("lantern string colour orders cycle along a wall facing " + facing,
                    !orders.contains(-1) && new HashSet<>(orders).size() == 3, "variants " + orders);
        }
        // a wall block will not go where there is nothing to hang on: clicked on the ground, in the open
        final BlockPos open = spot(20, 0);
        final InteractionResult r = use(SeasonsBlocks.WREATH_ITEM.get(), open.below(), Direction.UP, 0, 90);
        check("no wreath without a wall behind", !r.consumesAction() && level.getBlockState(open).isAir(),
                said(r) + " -> " + level.getBlockState(open));
        level.setBlock(open, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    private void floorBlocks() {
        int k = 0;
        for (final DeferredBlock<? extends Block> block : List.of(SeasonsBlocks.GIFT_PILE, SeasonsBlocks.FESTIVAL_MARKER)) {
            int f = 0;
            for (final Direction look : Direction.Plane.HORIZONTAL) {
                final BlockPos at = spot(4 * k, 20 + 3 * f);
                final InteractionResult r = use(block.get().asItem(), at.below(), Direction.UP, look.toYRot(), 60);
                final BlockState placed = level.getBlockState(at);
                final String where = name(block) + " placed by a player looking " + look;
                check("placed by hand: " + where, r.consumesAction() && placed.is(block.get())
                        && placed.getValue(HorizontalDirectionalBlock.FACING) == look.getOpposite(), r + " -> " + placed);
                if (block == SeasonsBlocks.FESTIVAL_MARKER) {
                    check("no collision: " + where, placed.getCollisionShape(level, at).isEmpty(), shape(placed, at));
                    final BlockEntity be = level.getBlockEntity(at);
                    check("marker keeps who placed it: " + where, be instanceof FestivalMarkerBlockEntity m
                            && player.getUUID().equals(m.getPlacedBy()) && m.getColonyId() == FestivalMarkerBlockEntity.NO_COLONY,
                            be instanceof FestivalMarkerBlockEntity m ? m.getPlacedBy() + ", colony " + m.getColonyId() : String.valueOf(be));
                } else {
                    check("collision: " + where, !placed.getCollisionShape(level, at).isEmpty(), "the boxes are solid");
                    check("block entity: " + where, level.getBlockEntity(at) instanceof GiftPileBlockEntity, String.valueOf(level.getBlockEntity(at)));
                }
                level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                f++;
            }
            k++;
        }
    }

    private void otherBlocks() {
        int k = 0;
        for (final DeferredBlock<? extends Block> block : List.of(SeasonsBlocks.ORNAMENT_LEAVES, SeasonsBlocks.TINSEL_LEAVES,
                SeasonsBlocks.TREE_STAR, SeasonsBlocks.FIREWORK_CRATE)) {
            final BlockPos at = spot(12 + 3 * k, 20);
            final InteractionResult r = use(block.get().asItem(), at.below(), Direction.UP, 0, 60);
            final BlockState placed = level.getBlockState(at);
            check("placed by hand: " + name(block), r.consumesAction() && placed.is(block.get()), r + " -> " + placed);
            if (placed.hasProperty(Lights.LIT)) {
                check("comes on when placed: " + name(block), placed.getValue(Lights.LIT), placed.toString());
            }
            k++;
            if (block == SeasonsBlocks.TREE_STAR) {
                check("no collision: tree_star", placed.getCollisionShape(level, at).isEmpty(), shape(placed, at));
            } else {
                check("collision: " + name(block), !placed.getCollisionShape(level, at).isEmpty(), "solid");
            }
            if (block == SeasonsBlocks.FIREWORK_CRATE) {
                check("mineable with an axe: firework_crate", placed.is(BlockTags.MINEABLE_WITH_AXE), "tag");
            }
            if (block == SeasonsBlocks.ORNAMENT_LEAVES || block == SeasonsBlocks.TINSEL_LEAVES) {
                check("full leaf cube: " + name(block), placed.isCollisionShapeFullBlock(level, at), "collision is the whole block");
                check("leaves tags: " + name(block), placed.is(BlockTags.LEAVES) && placed.is(BlockTags.MINEABLE_WITH_HOE),
                        "#minecraft:leaves and #minecraft:mineable/hoe");
                check("never decays: " + name(block), !placed.isRandomlyTicking() && !placed.hasProperty(BlockStateProperties.DISTANCE)
                        && !placed.hasProperty(BlockStateProperties.PERSISTENT) && !placed.hasProperty(BlockStateProperties.WATERLOGGED),
                        "no random tick, no distance, persistent or waterlogged property: " + placed.getProperties());
            }
            level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    private void largeStar() {
        final LargeStarBlock star = SeasonsBlocks.LARGE_STAR.get();
        final BlockPos at = spot(12, 26);
        InteractionResult r = use(star.asItem(), at.below(), Direction.UP, 0, 60);
        final BlockState lower = level.getBlockState(at);
        final BlockState upper = level.getBlockState(at.above());
        check("placed by hand: large_star, two halves", r.consumesAction() && lower.is(star) && upper.is(star)
                && lower.getValue(LargeStarBlock.HALF) == DoubleBlockHalf.LOWER && upper.getValue(LargeStarBlock.HALF) == DoubleBlockHalf.UPPER
                && lower.getValue(Lights.LIT) && upper.getValue(Lights.LIT), lower + " / " + upper);
        check("no collision: large_star", lower.getCollisionShape(level, at).isEmpty() && upper.getCollisionShape(level, at.above()).isEmpty(),
                shape(lower, at) + " / " + shape(upper, at.above()));
        // switching one half switches both
        final InteractionResult sw = level.getBlockState(at.above()).useWithoutItem(level, player,
                new BlockHitResult(Vec3.atCenterOf(at.above()), Direction.NORTH, at.above(), false));
        check("large_star: one half switches both", sw.consumesAction() && !level.getBlockState(at).getValue(Lights.LIT)
                && !level.getBlockState(at.above()).getValue(Lights.LIT), level.getBlockState(at) + " / " + level.getBlockState(at.above()));
        // the top goes: the bottom goes with it and drops the one item
        level.destroyBlock(at.above(), true);
        check("large_star: no lower half without the upper", level.getBlockState(at).isAir(), String.valueOf(level.getBlockState(at)));
        checkEq("large_star: one item for two halves", dropsNear(at, star.asItem()), 1);
        clearDrops(at);
        // and the other way round
        use(star.asItem(), at.below(), Direction.UP, 0, 60);
        level.destroyBlock(at, true);
        check("large_star: no upper half without the lower", level.getBlockState(at.above()).isAir(), String.valueOf(level.getBlockState(at.above())));
        checkEq("large_star: one item again", dropsNear(at, star.asItem()), 1);
        clearDrops(at);
        // no room above: no star
        level.setBlock(at.above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        r = use(star.asItem(), at.below(), Direction.UP, 0, 60);
        check("large_star needs room for its top", !level.getBlockState(at).is(star), said(r) + " -> " + level.getBlockState(at));
        level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(at.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    // ------------------------------------------------------------------ drops through the loot tables
    private void drops() {
        final BlockPos at = spot(30, 30);
        for (final DeferredBlock<? extends Block> block : SeasonsBlocks.ALL) {
            final List<ItemStack> drops = Block.getDrops(block.get().defaultBlockState(), level, at, null);
            check("drops itself: " + name(block), drops.size() == 1 && drops.get(0).is(block.get().asItem()) && drops.get(0).getCount() == 1,
                    drops.toString());
        }
        final List<ItemStack> top = Block.getDrops(SeasonsBlocks.LARGE_STAR.get().defaultBlockState()
                .setValue(LargeStarBlock.HALF, DoubleBlockHalf.UPPER), level, at, null);
        check("large_star upper half drops nothing", top.isEmpty(), top.toString());
    }

    // ------------------------------------------------------------------ block entities
    private void blockEntities() {
        final var registries = level.registryAccess();
        final BlockPos pile = spot(24, 20);
        use(SeasonsBlocks.GIFT_PILE_ITEM.get(), pile.below(), Direction.UP, 0, 60);
        final BlockState pileState = level.getBlockState(pile);
        check("gift_pile block entity type lists the block", SeasonsBlocks.GIFT_PILE_ENTITY.get().isValid(pileState)
                && !SeasonsBlocks.GIFT_PILE_ENTITY.get().isValid(SeasonsBlocks.FESTIVAL_MARKER.get().defaultBlockState()), "own type");
        if (level.getBlockEntity(pile) instanceof GiftPileBlockEntity be) {
            checkEq("new gift pile is empty", be.getStock() + " " + be.takenOn(), "0 {}");
            final UUID a = UUID.fromString("00000000-0000-4000-8000-00000000000a");
            final UUID b = UUID.fromString("00000000-0000-4000-8000-00000000000b");
            be.setStock(7);
            be.markTaken(a, 20811L);
            be.markTaken(b, 20812L);
            final CompoundTag tag = be.saveWithFullMetadata(registries);
            final BlockEntity back = BlockEntity.loadStatic(pile, pileState, tag, registries);
            check("gift_pile NBT round trip", back instanceof GiftPileBlockEntity g && g.getStock() == 7
                    && Long.valueOf(20811L).equals(g.dayTakenBy(a)) && Long.valueOf(20812L).equals(g.dayTakenBy(b)) && g.takenOn().size() == 2,
                    back instanceof GiftPileBlockEntity g ? "stock " + g.getStock() + ", taken " + g.takenOn() : String.valueOf(back));
            checkEq("gift_pile saved id", tag.getString("id"), NS + ":gift_pile");
        } else {
            check("gift_pile block entity", false, String.valueOf(level.getBlockEntity(pile)));
        }
        level.setBlock(pile, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        final BlockPos mark = spot(27, 20);
        use(SeasonsBlocks.FESTIVAL_MARKER_ITEM.get(), mark.below(), Direction.UP, 0, 60);
        final BlockState markState = level.getBlockState(mark);
        if (level.getBlockEntity(mark) instanceof FestivalMarkerBlockEntity be) {
            be.setColonyId(42);
            final CompoundTag tag = be.saveWithFullMetadata(registries);
            final BlockEntity back = BlockEntity.loadStatic(mark, markState, tag, registries);
            check("festival_marker NBT round trip", back instanceof FestivalMarkerBlockEntity m && m.getColonyId() == 42
                    && player.getUUID().equals(m.getPlacedBy()),
                    back instanceof FestivalMarkerBlockEntity m ? m.getPlacedBy() + ", colony " + m.getColonyId() : String.valueOf(back));
            checkEq("festival_marker saved id", tag.getString("id"), NS + ":festival_marker");
            final CompoundTag empty = new FestivalMarkerBlockEntity(mark, markState).saveWithFullMetadata(registries);
            final BlockEntity none = BlockEntity.loadStatic(mark, markState, empty, registries);
            check("festival_marker without a placer reads back", none instanceof FestivalMarkerBlockEntity m && m.getPlacedBy() == null
                    && m.getColonyId() == FestivalMarkerBlockEntity.NO_COLONY, String.valueOf(empty));
        } else {
            check("festival_marker block entity", false, String.valueOf(level.getBlockEntity(mark)));
        }
        level.setBlock(mark, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    // ------------------------------------------------------------------ light
    private static Map<DeferredBlock<? extends Block>, Integer> lights() {
        final Map<DeferredBlock<? extends Block>, Integer> m = new LinkedHashMap<>();
        m.put(SeasonsBlocks.LANTERN_STRING, Lights.LANTERN_STRING);
        m.put(SeasonsBlocks.FAIRY_LIGHTS, Lights.FAIRY_LIGHTS);
        m.put(SeasonsBlocks.ORNAMENT_LEAVES, Lights.ORNAMENT_LEAVES);
        m.put(SeasonsBlocks.TINSEL_LEAVES, Lights.TINSEL_LEAVES);
        m.put(SeasonsBlocks.TREE_STAR, Lights.TREE_STAR);
        m.put(SeasonsBlocks.LARGE_STAR, Lights.LARGE_STAR);
        return m;
    }

    private void lightInState() {
        for (final var e : lights().entrySet()) {
            final BlockState off = e.getKey().get().defaultBlockState();
            check("off by default, no light: " + name(e.getKey()), !off.getValue(Lights.LIT) && off.getLightEmission() == 0,
                    "light " + off.getLightEmission());
            checkEq("light when lit: " + name(e.getKey()), off.setValue(Lights.LIT, true).getLightEmission(), e.getValue());
        }
        for (final DeferredBlock<? extends Block> b : List.of(SeasonsBlocks.WREATH, SeasonsBlocks.GARLAND, SeasonsBlocks.GIFT_PILE,
                SeasonsBlocks.FIREWORK_CRATE, SeasonsBlocks.FESTIVAL_MARKER)) {
            check("no light and no lit property: " + name(b), !b.get().defaultBlockState().hasProperty(Lights.LIT)
                    && b.get().defaultBlockState().getLightEmission() == 0, b.get().defaultBlockState().toString());
        }
    }

    /** Puts each light out lit, 12 blocks apart (so no other light reaches one at its own level), on a wall where needed. */
    private void setOutLights() {
        int i = 0;
        for (final var e : lights().entrySet()) {
            final BlockPos at = origin.offset(4 + 12 * (i % 3), 1, 40 + 12 * (i / 3));
            BlockState state = e.getKey().get().defaultBlockState().setValue(Lights.LIT, true);
            if (state.hasProperty(HorizontalDirectionalBlock.FACING)) {
                level.setBlock(at.south(), Blocks.STONE_BRICKS.defaultBlockState(), Block.UPDATE_ALL);
                state = state.setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH);
            }
            level.setBlock(at, state, Block.UPDATE_ALL);
            if (state.hasProperty(LargeStarBlock.HALF)) {
                level.setBlock(at.above(), state.setValue(LargeStarBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
            }
            lightSpots.put(at, e.getValue());
            i++;
        }
    }

    private void lightsOnThenSwitchOff() {
        for (final var e : lightSpots.entrySet()) {
            final BlockPos at = e.getKey();
            final BlockState state = level.getBlockState(at);
            final String what = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
            checkEq("world block light when lit: " + what, level.getBrightness(LightLayer.BLOCK, at), e.getValue());
            final BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(at), Direction.NORTH, at, false);
            // something in the hand: no switch (that click places or uses the item)
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(state.getBlock().asItem()));
            final InteractionResult full = state.useWithoutItem(level, player, hit);
            check("a full hand does not switch: " + what, !full.consumesAction() && level.getBlockState(at).getValue(Lights.LIT), full.toString());
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            final InteractionResult empty = level.getBlockState(at).useWithoutItem(level, player, hit);
            check("an empty hand switches it off: " + what, empty.consumesAction() && !level.getBlockState(at).getValue(Lights.LIT),
                    empty + " -> " + level.getBlockState(at));
        }
    }

    private void lightsOff() {
        for (final var e : lightSpots.entrySet()) {
            final BlockPos at = e.getKey();
            final String what = BuiltInRegistries.BLOCK.getKey(level.getBlockState(at).getBlock()).getPath();
            checkEq("world block light when off: " + what, level.getBrightness(LightLayer.BLOCK, at), 0);
        }
    }
}
