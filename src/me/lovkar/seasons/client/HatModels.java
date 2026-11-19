package me.lovkar.seasons.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The wool hat allow-list, {@code assets/colony_seasons/clothes/hat_models.json}: the MineColonies citizen models
 * (and genders) whose heads have room for a hat. Many job models already wear a cap, a hood, a sailor or a
 * baker's hat, or goggles on the forehead, and a wool hat on top of those would clip; they are simply not listed.
 *
 * <pre>
 * { "models": { "minecolonies:settler": ["male", "female"], "minecolonies:child": ["female"] } }
 * </pre>
 *
 * <p>It is a resource, so a resource pack can replace it. No Minecraft class in here (only Gson), so
 * {@code tools/clothescheck.sh} reads the shipped file with this very parser.</p>
 */
public final class HatModels {
    public static final String MALE = "male";
    public static final String FEMALE = "female";
    /** Allows nothing: before the file is read, and when it cannot be read. */
    public static final HatModels NONE = new HatModels(Set.of());

    /** Entries {@code <model id>/<gender>}. */
    private final Set<String> allowed;

    private HatModels(final Set<String> allowed) {
        this.allowed = Set.copyOf(allowed);
    }

    /**
     * Reads the file. A malformed entry is an error, not something to skip quietly: the static check and the
     * client log both name it.
     *
     * @throws IllegalArgumentException when the file does not have the shape above
     */
    public static HatModels parse(final Reader reader) {
        final JsonElement root = JsonParser.parseReader(reader);
        if (!root.isJsonObject() || !root.getAsJsonObject().has("models") || !root.getAsJsonObject().get("models").isJsonObject()) {
            throw new IllegalArgumentException("hat_models.json needs an object \"models\"");
        }
        final Set<String> allowed = new HashSet<>();
        for (final Map.Entry<String, JsonElement> entry : root.getAsJsonObject().getAsJsonObject("models").entrySet()) {
            final String model = entry.getKey().toLowerCase(Locale.ROOT);
            if (!model.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                throw new IllegalArgumentException("not a model id: '" + entry.getKey() + "'");
            }
            if (!entry.getValue().isJsonArray()) {
                throw new IllegalArgumentException(model + ": needs a list of genders");
            }
            final JsonArray genders = entry.getValue().getAsJsonArray();
            for (final JsonElement gender : genders) {
                final String g = gender.getAsString();
                if (!MALE.equals(g) && !FEMALE.equals(g)) {
                    throw new IllegalArgumentException(model + ": gender '" + g + "' is not male or female");
                }
                allowed.add(model + "/" + g);
            }
        }
        return new HatModels(allowed);
    }

    /** True when this model id ({@code namespace:path}) in this gender may wear the wool hat. */
    public boolean allows(final String modelId, final boolean female) {
        return modelId != null && allowed.contains(modelId + "/" + (female ? FEMALE : MALE));
    }

    /** The entries, {@code <model id>/<gender>}. */
    public Set<String> entries() {
        return allowed;
    }
}
