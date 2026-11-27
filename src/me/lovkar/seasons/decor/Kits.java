package me.lovkar.seasons.decor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.lovkar.seasons.ColonySeasons;
import me.lovkar.seasons.colony.BuildingDecorator;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.items.IItemHandler;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.ToIntFunction;

/**
 * The Decorator's kits: what one batch of a decoration takes and makes. A kit is a JSON file in a datapack under
 * {@code data/<namespace>/decorator_kits/<name>.json}, its id is {@code <namespace>:<name>}:
 * <pre>
 * {"output": {"item": "colony_seasons:wreath", "count": 2},
 *  "ingredients": [{"tag": "minecraft:leaves", "count": 4}, {"item": "minecraft:string", "count": 1}]}
 * </pre>
 * Counts are 1 to 64, a kit has 1 to 9 ingredients, each names exactly one of {@code item} or {@code tag}. A kit may
 * also say {@code "cap": 24}: its own stock cap, lowered below the hut's shared one (a kit without it keeps the
 * hut's cap, exactly as before).
 *
 * <p>Our own folder, our own reload listener: MineColonies throws its whole datapack away over a single unknown
 * item id, so the kits never go near it. A kit that cannot be used (a file that is not JSON, the wrong shape, an
 * item or tag that does not exist, say because the mod that adds it is not installed) is skipped with ONE warning
 * line per kit and reload, and nothing else happens. Items resolve through {@code getOptional} only (a plain
 * {@code get} turns a missing id into air).</p>
 *
 * <p>The loaded list is immutable and swapped in with one assignment, so the server thread always sees a whole
 * set. Tags are bound only after all reload listeners ran, so tag ingredients are checked on
 * {@link TagsUpdatedEvent} (the server's data load) and a kit with an unknown or empty tag is moved to
 * {@link #skipped()} then.</p>
 */
public final class Kits {

    /** The datapack folder the kits are read from, in every namespace. */
    public static final String FOLDER = "decorator_kits";
    public static final int MAX_COUNT = 64;
    public static final int MAX_INGREDIENTS = 9;
    /** The largest "cap" a kit file may name (the hut's own cap is 48 at level 5, so a bigger one never counts). */
    public static final int MAX_CAP = 999;
    static final String REASON_NOT_JSON = "the file is not valid JSON";

    /** One ingredient: an item or an item tag, and how many of it one batch takes. */
    public record Ingredient(Item item, TagKey<Item> tag, int count) {

        public static Ingredient of(final Item item, final int count) {
            return new Ingredient(item, null, count);
        }

        public static Ingredient of(final TagKey<Item> tag, final int count) {
            return new Ingredient(null, tag, count);
        }

        public boolean matches(final ItemStack stack) {
            if (stack == null || stack.isEmpty()) {
                return false;
            }
            return tag != null ? stack.is(tag) : stack.is(item);
        }

        /** "item:minecraft:string" or "tag:minecraft:leaves": the same key for the same ingredient in any kit. */
        public String key() {
            return tag != null ? "tag:" + tag.location() : "item:" + BuiltInRegistries.ITEM.getKey(item);
        }

        @Override
        public String toString() {
            return count + " x " + (tag != null ? "#" + tag.location() : String.valueOf(BuiltInRegistries.ITEM.getKey(item)));
        }
    }

    /**
     * One kit: one batch takes the ingredients and makes {@code count} of {@code output}. {@code cap} is the kit's own
     * stock cap (0: none, the hut's cap alone), see {@link #limit}.
     */
    public record Kit(ResourceLocation id, Item output, int count, List<Ingredient> ingredients, int cap) {
        public Kit {
            ingredients = List.copyOf(ingredients);
        }

        public Kit(final ResourceLocation id, final Item output, final int count, final List<Ingredient> ingredients) {
            this(id, output, count, ingredients, 0);
        }
    }

    /** A kit file as read, before its ids are looked up. */
    private record Parsed(ResourceLocation output, int count, List<RawIngredient> ingredients, int cap) {
    }

    private record RawIngredient(ResourceLocation id, boolean tag, int count) {
    }

    /** What prepare() found for one file: a parsed kit, or the reason it is unusable. */
    private record Read(Parsed kit, String problem) {
    }

    private static volatile List<Kit> loaded = List.of();
    private static volatile Map<ResourceLocation, String> skipped = Map.of();
    private static volatile int reloads;

    private Kits() {
    }

    /**
     * Every usable kit, sorted by id, except that a kit which takes another kit's output as an ingredient comes after
     * the kit that makes it (the crate after the rockets): the order he plans and assembles in.
     */
    public static List<Kit> all() {
        return loaded;
    }

    /** The usable kit with this id, or {@code null}. */
    public static Kit byId(final ResourceLocation id) {
        for (final Kit kit : loaded) {
            if (kit.id().equals(id)) {
                return kit;
            }
        }
        return null;
    }

    /** The kits of the last reload that are not used, with the reason (the same text as their warning). */
    public static Map<ResourceLocation, String> skipped() {
        return skipped;
    }

    /** How many times the kits were loaded since the game started (a probe waits for the first). */
    public static int reloads() {
        return reloads;
    }

    // ---------------------------------------------------------------- counting

    /** How many items matching {@code ingredient} one inventory holds, summed over mixed stacks. */
    public static int count(final IItemHandler handler, final Ingredient ingredient) {
        if (handler == null) {
            return 0;
        }
        int total = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            final ItemStack stack = handler.getStackInSlot(slot);
            if (ingredient.matches(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * Whether a kit is worth a batch at this stock and cap: while one batch still fits under the cap, and also when a
     * single batch is larger than the whole cap (count 32 at a cap of 16), as long as the stock is below the cap, so
     * such a kit is made once rather than never. The one rule for asking and for assembling.
     */
    public static boolean wanted(final int stock, final int count, final int cap) {
        return stock + count <= cap || count > cap && stock < cap;
    }

    /**
     * The stock limit of one kit's output: the hut's shared cap, or the kit's own {@code cap} when that is lower. A kit
     * without a {@code cap} keeps the shared one.
     */
    public static int limit(final Kit kit, final int shared) {
        return kit.cap() > 0 ? Math.min(shared, kit.cap()) : shared;
    }

    /** True when {@code ingredient} takes {@code item} (an item, or a tag the item is in). */
    private static boolean takes(final Ingredient ingredient, final Item item) {
        return ingredient.matches(new ItemStack(item));
    }

    /**
     * How much of {@code kit}'s output the other kits that take it still want right now, one batch each: for every
     * other kit that is {@link #wanted} (counting {@code own}, what it has and has on its way) and has an ingredient
     * that {@code kit}'s output satisfies, that ingredient's count. Zero for a kit nobody takes the output of, which
     * is every one of the six 0.1.0 kits.
     */
    public static int demand(final Kit kit, final List<Kit> kits, final int shared, final ToIntFunction<Item> own) {
        int total = 0;
        for (final Kit other : kits) {
            if (other.id().equals(kit.id()) || !wanted(own.applyAsInt(other.output()), other.count(), limit(other, shared))) {
                continue;
            }
            for (final Ingredient ingredient : other.ingredients()) {
                if (takes(ingredient, kit.output())) {
                    total += ingredient.count();
                }
            }
        }
        return total;
    }

    /**
     * Whether {@code kit} is worth a batch now, at this stock ({@code own}: what he has and has on its way): the plain
     * rule at its {@link #limit} ({@link #wanted}), or, while another kit that takes its output is wanted, until the
     * stock has risen {@link #demand} above the limit (the rockets above the show's reserve, for a crate) without ever
     * passing the hut's shared cap. A batch that overshoots the target by less than itself is made, so a stock that is
     * not a whole number of batches (a show took 16 rockets) still gets there.
     */
    public static boolean wantedNow(final Kit kit, final List<Kit> kits, final int shared, final ToIntFunction<Item> own) {
        final int stock = own.applyAsInt(kit.output());
        final int limit = limit(kit, shared);
        if (wanted(stock, kit.count(), limit)) {
            return true;
        }
        final int demand = demand(kit, kits, shared, own);
        return demand > 0 && stock < limit + demand && stock + kit.count() <= shared;
    }

    /**
     * The most of {@code kit}'s output that is worth keeping in the hut (what couriers must leave there): its limit,
     * and the room the kits that take it need above that (one batch's worth of each, and a batch's overshoot), never
     * more than the hut's shared cap.
     */
    public static int keep(final Kit kit, final List<Kit> kits, final int shared) {
        final int limit = limit(kit, shared);
        int extra = 0;
        for (final Kit other : kits) {
            if (other.id().equals(kit.id())) {
                continue;
            }
            for (final Ingredient ingredient : other.ingredients()) {
                if (takes(ingredient, kit.output())) {
                    extra += ingredient.count();
                }
            }
        }
        return extra == 0 ? limit : Math.min(shared, Math.max(limit, limit + extra + kit.count() - 1));
    }

    /**
     * Whether {@code kit} may take its batch of ingredients now. An ingredient that is another kit's output is a made
     * one: it may only be taken while that kit's stock stays at or above its own {@link #limit} after the batch, so a
     * kit that makes use of a stock never eats the reserve that stock is for (a crate never takes the New Year show's
     * rockets). {@code have} is what is in his hands now (stock, not what is on its way).
     */
    public static boolean mayTake(final Kit kit, final List<Kit> kits, final int shared, final ToIntFunction<Item> have) {
        for (final Ingredient ingredient : kit.ingredients()) {
            for (final Kit maker : kits) {
                if (maker.id().equals(kit.id()) || !takes(ingredient, maker.output())) {
                    continue;
                }
                if (have.applyAsInt(maker.output()) - ingredient.count() < limit(maker, shared)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** True when the inventory holds one whole batch of every ingredient of the kit. */
    public static boolean hasBatch(final IItemHandler handler, final Kit kit) {
        for (final Ingredient ingredient : kit.ingredients()) {
            if (count(handler, ingredient) < ingredient.count()) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- loading

    /** Game bus: our reader joins every server data load (start and /reload). */
    public static void onAddReloadListeners(final AddReloadListenerEvent event) {
        event.addListener(new Listener());
    }

    /** Game bus: once the server's tags are bound, every tag ingredient is checked. */
    public static void onTagsUpdated(final TagsUpdatedEvent event) {
        if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) {
            checkTags(event.getRegistryAccess().registryOrThrow(Registries.ITEM));
        }
    }

    /** Moves every kit whose tag is unknown or empty to the skipped list, with one warning each. */
    static void checkTags(final Registry<Item> items) {
        final List<Kit> keep = new ArrayList<>();
        final Map<ResourceLocation, String> skip = new TreeMap<>(skipped);
        for (final Kit kit : loaded) {
            final List<String> bad = new ArrayList<>();
            for (final Ingredient ingredient : kit.ingredients()) {
                if (ingredient.tag() == null) {
                    continue;
                }
                final Optional<HolderSet.Named<Item>> set = items.getTag(ingredient.tag());
                if (set.isEmpty() || set.get().size() == 0) {
                    bad.add("#" + ingredient.tag().location());
                }
            }
            if (bad.isEmpty()) {
                keep.add(kit);
            } else {
                final String reason = "unknown or empty tag " + String.join(", ", bad);
                ColonySeasons.LOGGER.warn("Decorator kit {} skipped: {}", kit.id(), reason);
                skip.put(kit.id(), reason);
            }
        }
        if (keep.size() != loaded.size()) {
            skipped = Collections.unmodifiableMap(new LinkedHashMap<>(skip));
            loaded = List.copyOf(keep);
        }
    }

    /**
     * The reader. A {@code SimplePreparableReloadListener} of our own and not vanilla's JSON listener: that one
     * logs a broken file at error level with its stack trace, and a kit file somebody got wrong is worth a warning,
     * not an error.
     */
    static final class Listener extends SimplePreparableReloadListener<Map<ResourceLocation, Read>> {

        @Override
        protected Map<ResourceLocation, Read> prepare(final ResourceManager manager, final ProfilerFiller profiler) {
            final FileToIdConverter files = FileToIdConverter.json(FOLDER);
            final Map<ResourceLocation, Read> found = new TreeMap<>();
            for (final Map.Entry<ResourceLocation, Resource> entry : files.listMatchingResources(manager).entrySet()) {
                final ResourceLocation id = files.fileToId(entry.getKey());
                found.put(id, read(entry.getValue()));
            }
            return found;
        }

        @Override
        protected void apply(final Map<ResourceLocation, Read> found, final ResourceManager manager, final ProfilerFiller profiler) {
            final List<Kit> kits = new ArrayList<>();
            final Map<ResourceLocation, String> skip = new TreeMap<>();
            for (final Map.Entry<ResourceLocation, Read> entry : found.entrySet()) {
                final ResourceLocation id = entry.getKey();
                final Read read = entry.getValue();
                Kit kit = null;
                String reason = read.problem();
                if (reason == null) {
                    final List<String> missing = new ArrayList<>();
                    kit = resolve(id, read.kit(), missing);
                    if (kit == null) {
                        reason = "unknown item " + String.join(", ", missing);
                    }
                }
                if (reason != null) {
                    ColonySeasons.LOGGER.warn("Decorator kit {} skipped: {}", id, reason);
                    skip.put(id, reason);
                } else {
                    kits.add(kit);
                }
            }
            skipped = Collections.unmodifiableMap(new LinkedHashMap<>(skip));
            loaded = inMakingOrder(kits);
            reloads++;
            final int firstCap = BuildingDecorator.STOCK_CAP_BASE + BuildingDecorator.STOCK_CAP_PER_LEVEL;
            for (final Kit kit : kits) {
                final int first = limit(kit, firstCap);
                if (kit.count() > first) {
                    ColonySeasons.LOGGER.warn("Decorator kit {} makes {} at a time, more than a level-1 hut keeps ({}): "
                            + "it is made one batch at a time while the stock is below the cap", kit.id(), kit.count(), first);
                }
            }
            ColonySeasons.LOGGER.info("Decorator kits: {} loaded, {} skipped", kits.size(), skip.size());
        }
    }

    /** Reads one file; any problem becomes a fixed reason, never a stack trace. */
    private static Read read(final Resource resource) {
        final JsonElement json;
        try (Reader reader = resource.openAsReader()) {
            json = JsonParser.parseReader(reader);
        } catch (final Exception unreadable) {
            return new Read(null, REASON_NOT_JSON);
        }
        try {
            return parse(json);
        } catch (final RuntimeException wrongShape) {
            return new Read(null, "the file does not have the shape of a kit");
        }
    }

    private static Read parse(final JsonElement json) {
        if (!json.isJsonObject()) {
            return new Read(null, "the file does not have the shape of a kit");
        }
        final JsonObject root = json.getAsJsonObject();
        if (!root.has("output") || !root.get("output").isJsonObject()) {
            return new Read(null, "no \"output\" object");
        }
        final JsonObject output = root.getAsJsonObject("output");
        final ResourceLocation outputId = idOf(output, "item");
        if (outputId == null) {
            return new Read(null, "the output has no valid \"item\" id");
        }
        final int outputCount = countOf(output);
        if (outputCount < 1) {
            return new Read(null, "the output count must be 1 to " + MAX_COUNT);
        }
        if (!root.has("ingredients") || !root.get("ingredients").isJsonArray()) {
            return new Read(null, "no \"ingredients\" list");
        }
        final JsonArray list = root.getAsJsonArray("ingredients");
        if (list.isEmpty() || list.size() > MAX_INGREDIENTS) {
            return new Read(null, "a kit takes 1 to " + MAX_INGREDIENTS + " ingredients");
        }
        final List<RawIngredient> ingredients = new ArrayList<>();
        for (final JsonElement element : list) {
            if (!element.isJsonObject()) {
                return new Read(null, "an ingredient is not an object");
            }
            final JsonObject one = element.getAsJsonObject();
            final boolean hasItem = one.has("item");
            final boolean hasTag = one.has("tag");
            if (hasItem == hasTag) {
                return new Read(null, "an ingredient must name exactly one of \"item\" or \"tag\"");
            }
            final ResourceLocation id = idOf(one, hasTag ? "tag" : "item");
            if (id == null) {
                return new Read(null, "an ingredient has no valid id");
            }
            final int count = countOf(one);
            if (count < 1) {
                return new Read(null, "an ingredient count must be 1 to " + MAX_COUNT);
            }
            ingredients.add(new RawIngredient(id, hasTag, count));
        }
        int cap = 0;
        if (root.has("cap")) {
            cap = capOf(root.get("cap"));
            if (cap < 1) {
                return new Read(null, "the cap must be a whole number from 1 to " + MAX_CAP);
            }
        }
        return new Read(new Parsed(outputId, outputCount, ingredients, cap), null);
    }

    /** The "cap" of a kit, or 0 when it is not a whole number from 1 to {@link #MAX_CAP}. */
    private static int capOf(final JsonElement cap) {
        if (!cap.isJsonPrimitive() || !cap.getAsJsonPrimitive().isNumber()) {
            return 0;
        }
        final double value = cap.getAsDouble();
        if (value != Math.rint(value) || value < 1 || value > MAX_CAP) {
            return 0;
        }
        return (int) value;
    }

    /**
     * Sorted by id, except that a kit goes after every kit whose output it takes as an ingredient (level by level; a
     * datapack with a loop of kits is not looped on: the passes stop at the number of kits).
     */
    static List<Kit> inMakingOrder(final List<Kit> kits) {
        final List<Kit> sorted = new ArrayList<>(kits);
        sorted.sort(java.util.Comparator.comparing(Kit::id));
        final Map<ResourceLocation, Integer> depth = new LinkedHashMap<>();
        for (final Kit kit : sorted) {
            depth.put(kit.id(), 0);
        }
        for (int pass = 0; pass < sorted.size(); pass++) {
            boolean changed = false;
            for (final Kit kit : sorted) {
                for (final Kit maker : sorted) {
                    if (maker.id().equals(kit.id())) {
                        continue;
                    }
                    for (final Ingredient ingredient : kit.ingredients()) {
                        if (takes(ingredient, maker.output()) && depth.get(kit.id()) <= depth.get(maker.id())
                                && depth.get(maker.id()) < sorted.size()) {
                            depth.put(kit.id(), depth.get(maker.id()) + 1);
                            changed = true;
                        }
                    }
                }
            }
            if (!changed) {
                break;
            }
        }
        sorted.sort(java.util.Comparator.<Kit>comparingInt(kit -> depth.get(kit.id())).thenComparing(Kit::id));
        return List.copyOf(sorted);
    }

    private static ResourceLocation idOf(final JsonObject object, final String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive() || !object.get(key).getAsJsonPrimitive().isString()) {
            return null;
        }
        String text = object.get(key).getAsString();
        if (text.startsWith("#")) {
            text = text.substring(1);
        }
        return ResourceLocation.tryParse(text);
    }

    /** The "count" of an output or ingredient (1 when left out), or 0 when it is not a whole number from 1 to 64. */
    private static int countOf(final JsonObject object) {
        if (!object.has("count")) {
            return 1;
        }
        final JsonElement count = object.get("count");
        if (!count.isJsonPrimitive() || !count.getAsJsonPrimitive().isNumber()) {
            return 0;
        }
        final double value = count.getAsDouble();
        if (value != Math.rint(value) || value < 1 || value > MAX_COUNT) {
            return 0;
        }
        return (int) value;
    }

    /** The kit with its ids looked up, or {@code null} with every unknown item id added to {@code missing}. */
    private static Kit resolve(final ResourceLocation id, final Parsed parsed, final List<String> missing) {
        final Item output = item(parsed.output());
        if (output == null) {
            missing.add(parsed.output().toString());
        }
        final List<Ingredient> ingredients = new ArrayList<>();
        for (final RawIngredient raw : parsed.ingredients()) {
            if (raw.tag()) {
                ingredients.add(Ingredient.of(TagKey.create(Registries.ITEM, raw.id()), raw.count()));
                continue;
            }
            final Item item = item(raw.id());
            if (item == null) {
                missing.add(raw.id().toString());
            } else {
                ingredients.add(Ingredient.of(item, raw.count()));
            }
        }
        if (!missing.isEmpty()) {
            return null;
        }
        return new Kit(id, output, parsed.count(), ingredients, parsed.cap());
    }

    /** The item with this id, or {@code null} (never air for a missing id). */
    private static Item item(final ResourceLocation id) {
        final Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        return item == null || item == Items.AIR ? null : item;
    }
}
