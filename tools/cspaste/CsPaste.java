package me.lovkar.seasons.cspaste;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ldtteam.structurize.api.RotationMirror;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.blueprints.v1.BlueprintTagUtils;
import com.ldtteam.structurize.management.Manager;
import com.ldtteam.structurize.operations.PlaceStructureOperation;
import com.ldtteam.structurize.placement.StructurePlacer;
import com.ldtteam.structurize.placement.structure.CreativeStructureHandler;
import com.ldtteam.structurize.storage.StructurePackMeta;
import com.ldtteam.structurize.storage.StructurePacks;
import com.ldtteam.structurize.util.BlockInfo;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Headless check of the Colony Seasons blueprint pack (chunk S5): every file of the pack is loaded by Structurize
 * itself, judged as Structurize read it, pasted into the world through Structurize's own placer, and the world is
 * read back against what tools/build_pack.py wrote (res/cspaste/manifest.json, made by the same build).
 *
 * <p>The files are the Decorator's hut, the Town Square (0.2.0, one placeholder level in Q1) and the tree pieces;
 * a hut's and the square's building type, folder and anchor block come from their manifest entry.</p>
 *
 * <p>For every file: the pack is there with its name, description, authors and icon; the blueprint loads with its
 * size and anchor (primary offset); every palette entry is the state the build wrote (an unknown block id or a bad
 * property would have become air or a default); the blocks per id; the tags as Structurize returns them
 * ({@code BlueprintTagUtils.getBlueprintTags}, where the Decorator's code reads them too), the pieces invisible and the
 * hut not; the anchor block and its block entity data. After the paste: per block id the count in the world (nothing
 * turned to air, nothing popped off: a lantern or a wreath that has nothing to hold it is gone after the placement's
 * block updates), every block of ours in its exact state (unlit), the hut block with its block entity, a piece's
 * marker cell still air. Substitution cells are left out: the world decides what stands there.</p>
 *
 * <p>One file every 40 ticks from the first tick the pack is loaded, 32 blocks apart at y = 150 (anchor), chunks
 * forced; the world is read 200 ticks after the last paste and again every 40 ticks while it still differs (the
 * placer works through its queue a few hundred blocks a tick). Prints "[cspaste] CHECK ...: OK|FAIL" lines and
 * "[cspaste] RESULT cspaste: PASS|FAIL", then halts. The words "error" and "exception" stay out of the details: the
 * rig takes such a line for a failure.</p>
 */
@Mod("colonyseasonspaste")
public class CsPaste {
    private static final Logger LOGGER = LoggerFactory.getLogger("cspaste");
    private static final int SPACING = 32;
    private static final int Y = 150;
    private static final int GIVE_UP_LOADING = 1200;
    private static final int SETTLE = 200;
    private static final int RETRIES = 10;
    private static final int WATCHDOG = 4000;

    private int ticks;
    private boolean failed;
    private boolean finished;
    private JsonObject manifest;
    private final List<Job> jobs = new ArrayList<>();
    private int next = -1;
    private int lastPaste;
    private int retries;

    private record Job(JsonObject spec, Blueprint blueprint, BlockPos anchor) {
    }

    public CsPaste() {
        NeoForge.EVENT_BUS.addListener(this::onTick);
    }

    private void check(final String what, final boolean ok, final String detail) {
        LOGGER.info("[cspaste] CHECK {}: {} {}", what, ok ? "OK" : "FAIL", detail);
        failed |= !ok;
    }

    private void finish(final MinecraftServer server) {
        finished = true;
        LOGGER.info("[cspaste] RESULT cspaste: {}", failed ? "FAIL" : "PASS");
        server.halt(false);
    }

    private void onTick(final ServerTickEvent.Post event) {
        if (finished) {
            return;
        }
        ticks++;
        final MinecraftServer server = event.getServer();
        try {
            if (ticks > WATCHDOG) {
                check("finished in time", false, "still at file " + next + " after " + WATCHDOG + " ticks");
                finish(server);
                return;
            }
            if (next < 0) {
                if (ticks < 40) {
                    return;
                }
                final String pack = manifest().get("pack").getAsString();
                if (StructurePacks.getStructurePack(pack) == null) {
                    if (ticks > GIVE_UP_LOADING) {
                        check("pack loaded", false, pack + " not among " + StructurePacks.getPackMetas().size() + " packs");
                        finish(server);
                    }
                    return;
                }
                setup(server.overworld(), pack);
                next = 0;
                return;
            }
            if (next < jobs.size()) {
                if (ticks % 40 == 0) {
                    paste(server.overworld(), jobs.get(next));
                    next++;
                    lastPaste = ticks;
                }
                return;
            }
            if (ticks - lastPaste < SETTLE || (ticks - lastPaste) % 40 != 0) {
                return;
            }
            final boolean last = retries >= RETRIES;
            final List<String> report = new ArrayList<>();
            boolean all = true;
            for (final Job job : jobs) {
                all &= readBack(server.overworld(), job, report);
            }
            if (all || last) {
                for (final String line : report) {
                    LOGGER.info(line);
                }
                if (!all) {
                    failed = true;
                }
                check("world read back", all, jobs.size() + " files, " + (ticks - lastPaste) + " ticks after the last paste");
                finish(server);
            } else {
                retries++;
            }
        } catch (final Throwable t) {
            check("probe ran to the end", false, "threw " + t.getClass().getSimpleName().replace("Exception", "Exc") + ": " + t.getMessage());
            finish(server);
        }
    }

    private JsonObject manifest() throws java.io.IOException {
        if (manifest == null) {
            try (InputStream in = CsPaste.class.getResourceAsStream("/cspaste/manifest.json")) {
                if (in == null) {
                    throw new java.io.IOException("no cspaste/manifest.json in the probe jar");
                }
                manifest = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            }
        }
        return manifest;
    }

    // ------------------------------------------------------------------------------------------- before the paste
    private void setup(final ServerLevel level, final String pack) throws java.io.IOException {
        final StructurePackMeta meta = StructurePacks.getStructurePack(pack);
        check("pack meta", "Works with every colony style".equals(meta.getDesc())
                        && List.of("Lovkar", "Claude").equals(meta.getAuthors())
                        && meta.getModList().contains("colony_seasons")
                        && String.valueOf(meta.getIconPath()).endsWith("colony_seasons.png"),
                "name " + meta.getName() + ", desc '" + meta.getDesc() + "', authors " + meta.getAuthors()
                        + ", mods " + meta.getModList() + ", icon " + meta.getIconPath() + ", owner " + meta.getOwner());
        int i = 0;
        for (final JsonElement e : manifest().getAsJsonArray("files")) {
            final JsonObject spec = e.getAsJsonObject();
            final String path = spec.get("path").getAsString();
            final Blueprint bp = StructurePacks.getBlueprint(pack, path, level.registryAccess());
            if (bp == null) {
                check(path + " loads", false, "Structurize found no blueprint");
                i++;
                continue;
            }
            judgeBlueprint(level, path, spec, bp);
            final BlockPos anchor = new BlockPos(i * SPACING, Y, 0);
            for (int cx = (anchor.getX() - 24) >> 4; cx <= (anchor.getX() + 24) >> 4; cx++) {
                for (int cz = -2; cz <= 2; cz++) {
                    level.setChunkForced(cx, cz, true);
                }
            }
            jobs.add(new Job(spec, bp, anchor));
            i++;
        }
        check("every file loads", jobs.size() == manifest().getAsJsonArray("files").size(),
                jobs.size() + " of " + manifest().getAsJsonArray("files").size());
    }

    private void judgeBlueprint(final ServerLevel level, final String path, final JsonObject spec, final Blueprint bp) {
        final int[] size = ints(spec.getAsJsonArray("size"));
        final int[] anchor = ints(spec.getAsJsonArray("anchor"));
        final BlockPos po = bp.getPrimaryBlockOffset();
        check(path + " size and anchor",
                bp.getSizeX() == size[0] && bp.getSizeY() == size[1] && bp.getSizeZ() == size[2]
                        && po.equals(new BlockPos(anchor[0], anchor[1], anchor[2])),
                bp.getSizeX() + "x" + bp.getSizeY() + "x" + bp.getSizeZ() + ", primary offset " + po.toShortString());

        // the palette as Structurize read it is the palette the build wrote, state for state
        final Set<BlockState> want = new HashSet<>();
        final List<String> unreadable = new ArrayList<>();
        for (final JsonElement s : spec.getAsJsonArray("palette")) {
            final BlockState st = parse(s.getAsString());
            if (st == null) {
                unreadable.add(s.getAsString());
            } else {
                want.add(st);
            }
        }
        final Set<BlockState> got = new HashSet<>();
        for (final BlockState st : bp.getPalette()) {
            if (!st.isAir()) {
                got.add(st);
            }
        }
        final Set<String> missing = new TreeSet<>();
        for (final BlockState st : want) {
            if (!got.contains(st)) {
                missing.add(st.toString());
            }
        }
        final Set<String> extra = new TreeSet<>();
        for (final BlockState st : got) {
            if (!want.contains(st)) {
                extra.add(st.toString());
            }
        }
        check(path + " palette", unreadable.isEmpty() && missing.isEmpty() && extra.isEmpty(),
                want.size() + " states; not a block state here: " + unreadable + "; not read as written: " + first(missing)
                        + "; read but not written: " + first(extra));

        // blocks per id, as loaded
        final Map<String, Integer> counts = new TreeMap<>();
        for (final BlockInfo info : bp.getBlockInfoAsList()) {
            final BlockState st = info.getState();
            if (!st.isAir()) {
                final String id = BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
                if (!id.startsWith("structurize:")) {
                    counts.merge(id, 1, Integer::sum);
                }
            }
        }
        final Map<String, Integer> expected = countsOf(spec);
        check(path + " blocks per id", counts.equals(expected), diff(expected, counts));

        // the tags, as Structurize hands them to code (relative to the anchor)
        final Map<BlockPos, List<String>> tags = BlueprintTagUtils.getBlueprintTags(bp);
        final Set<String> gotTags = new TreeSet<>();
        tags.forEach((pos, names) -> names.forEach(n -> gotTags.add(pos.getX() + "," + pos.getY() + "," + pos.getZ() + " " + n)));
        final Set<String> wantTags = new TreeSet<>();
        for (final JsonElement t : spec.getAsJsonArray("tags")) {
            final JsonArray a = t.getAsJsonArray();
            wantTags.add(a.get(0).getAsInt() + "," + a.get(1).getAsInt() + "," + a.get(2).getAsInt() + " " + a.get(3).getAsString());
        }
        final boolean piece = "piece".equals(spec.get("kind").getAsString());
        check(path + " tags", gotTags.equals(wantTags) && BlueprintTagUtils.isInvisible(bp) == piece,
                gotTags.size() + " tags " + first(gotTags) + ", invisible " + BlueprintTagUtils.isInvisible(bp));

        // the anchor: the hut block with its building, or the marker's air cell with the tag data
        final BlockInfo at = bp.getBlockInfoAsMap().get(po);
        final String anchorId = at == null ? "none" : BuiltInRegistries.BLOCK.getKey(at.getState().getBlock()).toString();
        final CompoundTag te = at == null ? null : at.getTileEntityData();
        final String teId = te == null ? "none" : te.getString("id");
        boolean anchorOk = anchorId.equals(idOf(spec.get("anchorBlock").getAsString()))
                && teId.equals(spec.get("anchorEntity").getAsString());
        String extraInfo = "";
        if (!piece && te != null) {
            // a hut or the Town Square (0.2.0): the building type and the folder come from the manifest entry
            final String type = spec.has("buildingType") ? spec.get("buildingType").getAsString() : "colony_seasons:decorator";
            final String folder = spec.has("folder") ? spec.get("folder").getAsString() : "decorator";
            anchorOk &= type.equals(te.getString("type"))
                    && (folder + "/" + te.getCompound("blueprintDataProvider").getString("schematicName") + ".blueprint").equals(path);
            extraInfo = ", type " + te.getString("type") + ", schematic " + te.getCompound("blueprintDataProvider").getString("schematicName");
        }
        check(path + " anchor", anchorOk, anchorId + " with block entity " + teId + extraInfo);
        if (piece) {
            judgeTurned(level, path, spec);
        }
    }

    /**
     * The Decorator turns a piece to the marker's facing ({@code setRotationMirror}): the tags must turn with the
     * blocks, so a turned gift_pile tag still points at a gift pile and the turned anchor is still the marker's air cell.
     */
    private void judgeTurned(final ServerLevel level, final String path, final JsonObject spec) {
        final String pack = manifest.get("pack").getAsString();
        final Blueprint turned = StructurePacks.getBlueprint(pack, path, level.registryAccess());
        if (turned == null) {
            check(path + " turned", false, "no second copy");
            return;
        }
        turned.setRotationMirror(RotationMirror.R90, level);
        final BlockPos po = turned.getPrimaryBlockOffset();
        final Map<BlockPos, BlockInfo> map = turned.getBlockInfoAsMap();
        final List<String> bad = new ArrayList<>();
        int n = 0;
        for (final Map.Entry<BlockPos, List<String>> e : BlueprintTagUtils.getBlueprintTags(turned).entrySet()) {
            for (final String tag : e.getValue()) {
                final BlockInfo info = map.get(po.offset(e.getKey()));
                final String id = info == null ? "outside" : BuiltInRegistries.BLOCK.getKey(info.getState().getBlock()).toString();
                final String want = switch (tag) {
                    case "gift_pile" -> "colony_seasons:gift_pile";
                    case "firework_crate" -> "colony_seasons:firework_crate";
                    case "star" -> id.equals("colony_seasons:large_star") ? id : "colony_seasons:tree_star";
                    default -> "minecraft:air";       // invisible, on the marker's cell
                };
                n++;
                if (!want.equals(id)) {
                    bad.add(tag + " at " + e.getKey().toShortString() + " is " + id);
                }
            }
        }
        final BlockInfo at = map.get(po);
        final boolean anchorOk = at != null && at.getState().isAir() && at.getTileEntityData() != null;
        check(path + " turned a quarter", bad.isEmpty() && anchorOk && n == spec.getAsJsonArray("tags").size(),
                n + " tags on their blocks after the turn" + (bad.isEmpty() ? "" : ", not: " + bad) + ", anchor air with the tag data " + anchorOk);
        turned.setRotationMirror(RotationMirror.NONE, level);
    }

    private void paste(final ServerLevel level, final Job job) {
        final Blueprint bp = job.blueprint();
        bp.setRotationMirror(RotationMirror.NONE, level);
        final CreativeStructureHandler handler = new CreativeStructureHandler(level, job.anchor(), bp, RotationMirror.NONE, true);
        Manager.addToQueue(new PlaceStructureOperation(new StructurePlacer(handler), FakePlayerFactory.getMinecraft(level)));
        LOGGER.info("[cspaste] placing {} ({}x{}x{}) with its anchor at {}", job.spec().get("path").getAsString(),
                bp.getSizeX(), bp.getSizeY(), bp.getSizeZ(), job.anchor().toShortString());
    }

    // ------------------------------------------------------------------------------------------- after the paste
    private boolean readBack(final ServerLevel level, final Job job, final List<String> out) {
        final JsonObject spec = job.spec();
        final String path = spec.get("path").getAsString();
        final int[] size = ints(spec.getAsJsonArray("size"));
        final int[] a = ints(spec.getAsJsonArray("anchor"));
        final BlockPos origin = job.anchor().offset(-a[0], -a[1], -a[2]);
        final Set<BlockPos> subst = new HashSet<>();
        for (final JsonElement e : spec.getAsJsonArray("subst")) {
            final int[] r = ints(e.getAsJsonArray());
            subst.add(job.anchor().offset(r[0], r[1], r[2]));
        }
        final Map<String, Integer> counts = new TreeMap<>();
        int air = 0;
        for (int x = 0; x < size[0]; x++) {
            for (int y = 0; y < size[1]; y++) {
                for (int z = 0; z < size[2]; z++) {
                    final BlockPos p = origin.offset(x, y, z);
                    if (subst.contains(p)) {
                        continue;
                    }
                    final BlockState st = level.getBlockState(p);
                    if (st.isAir()) {
                        air++;
                    } else {
                        counts.merge(BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString(), 1, Integer::sum);
                    }
                }
            }
        }
        final Map<String, Integer> expected = countsOf(spec);
        final int volume = size[0] * size[1] * size[2] - subst.size();
        final int expectedAir = volume - expected.values().stream().mapToInt(Integer::intValue).sum();
        final boolean countsOk = counts.equals(expected) && air == expectedAir;

        // our blocks, state for state (unlit, facing as drawn)
        final List<String> wrong = new ArrayList<>();
        int ours = 0;
        for (final JsonElement e : spec.getAsJsonArray("ours")) {
            final JsonArray r = e.getAsJsonArray();
            final BlockPos p = job.anchor().offset(r.get(0).getAsInt(), r.get(1).getAsInt(), r.get(2).getAsInt());
            final BlockState want = parse(r.get(3).getAsString());
            final BlockState got = level.getBlockState(p);
            ours++;
            if (want == null || !want.equals(got)) {
                wrong.add(p.toShortString() + " " + got);
            }
        }
        final boolean piece = "piece".equals(spec.get("kind").getAsString());
        final BlockState anchorState = level.getBlockState(job.anchor());
        String anchorInfo;
        boolean anchorOk;
        if (piece) {
            anchorOk = anchorState.isAir();
            anchorInfo = "marker cell " + (anchorOk ? "air" : anchorState.toString());
        } else {
            final BlockEntity be = level.getBlockEntity(job.anchor());
            final String type = be == null ? "none" : String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()));
            anchorOk = BuiltInRegistries.BLOCK.getKey(anchorState.getBlock()).toString().equals(idOf(spec.get("anchorBlock").getAsString()))
                    && "colony_seasons:colonybuilding".equals(type);
            anchorInfo = "hut block " + anchorState + " with " + type;
        }
        final boolean ok = countsOk && wrong.isEmpty() && anchorOk;
        out.add("[cspaste] CHECK " + path + " in the world: " + (ok ? "OK" : "FAIL") + " " + counts.size() + " block ids, "
                + air + " air (" + expectedAir + " drawn), " + ours + " of our blocks"
                + (wrong.isEmpty() ? " in their exact state" : ", not as drawn: " + wrong.subList(0, Math.min(3, wrong.size())))
                + ", " + anchorInfo + (countsOk ? "" : "; " + diff(expected, counts)));
        return ok;
    }

    // ------------------------------------------------------------------------------------------- helpers
    private static BlockState parse(final String state) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), state, false).blockState();
        } catch (final Throwable t) {
            return null;
        }
    }

    private static String idOf(final String state) {
        final int b = state.indexOf('[');
        return b < 0 ? state : state.substring(0, b);
    }

    private static Map<String, Integer> countsOf(final JsonObject spec) {
        final Map<String, Integer> m = new TreeMap<>();
        for (final Map.Entry<String, JsonElement> e : spec.getAsJsonObject("counts").entrySet()) {
            m.put(e.getKey(), e.getValue().getAsInt());
        }
        return m;
    }

    private static String diff(final Map<String, Integer> want, final Map<String, Integer> got) {
        if (want.equals(got)) {
            return want.size() + " ids, " + want.values().stream().mapToInt(Integer::intValue).sum() + " blocks";
        }
        final Map<String, String> d = new TreeMap<>();
        final Set<String> keys = new TreeSet<>(want.keySet());
        keys.addAll(got.keySet());
        for (final String k : keys) {
            final int w = want.getOrDefault(k, 0);
            final int g = got.getOrDefault(k, 0);
            if (w != g) {
                d.put(k, g + " (drawn " + w + ")");
            }
        }
        return "differs: " + d;
    }

    private static String first(final Set<String> s) {
        final List<String> l = new ArrayList<>(s);
        return l.size() <= 4 ? l.toString() : l.subList(0, 4) + " and " + (l.size() - 4) + " more";
    }

    private static int[] ints(final JsonArray a) {
        final int[] r = new int[a.size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = a.get(i).getAsInt();
        }
        return r;
    }
}
