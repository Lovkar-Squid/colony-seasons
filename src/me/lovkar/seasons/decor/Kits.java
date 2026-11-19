package me.lovkar.seasons.decor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.lovkar.seasons.ColonySeasons;
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

/**
 * The Decorator's kits: what one batch of a decoration takes and makes. A kit is a JSON file in a datapack under
 * {@code data/<namespace>/decorator_kits/<name>.json}, its id is {@code <namespace>:<name>}:
 * <pre>
 * {"output": {"item": "colony_seasons:wreath", "count": 2},
 *  "ingredients": [{"tag": "minecraft:leaves", "count": 4}, {"item": "minecraft:string", "count": 1}]}
 * </pre>
 * Counts are 1 to 64, a kit has 1 to 9 ingredients, each names exactly one of {@code item} or {@code tag}.
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

    /** One kit: one batch takes the ingredients and makes {@code count} of {@code output}. */
    public record Kit(ResourceLocation id, Item output, int count, List<Ingredient> ingredients) {
        public Kit {
            ingredients = List.copyOf(ingredients);
        }
    }

    /** A kit file as read, before its ids are looked up. */
    private record Parsed(ResourceLocation output, int count, List<RawIngredient> ingredients) {
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

    /** Every usable kit, sorted by id. */
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
            loaded = List.copyOf(kits);
            reloads++;
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
        return new Read(new Parsed(outputId, outputCount, ingredients), null);
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
        return new Kit(id, output, parsed.count(), ingredients);
    }

    /** The item with this id, or {@code null} (never air for a missing id). */
    private static Item item(final ResourceLocation id) {
        final Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        return item == null || item == Items.AIR ? null : item;
    }
}
