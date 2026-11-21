package me.lovkar.seasons.decor;

import com.minecolonies.api.colony.buildings.IBuilding;
import me.lovkar.seasons.block.SeasonsBlocks;
import me.lovkar.seasons.block.StorageBoxBlock;
import me.lovkar.seasons.block.StorageCoilBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The spots of the Decorator's storeroom in his Workshop (S10g, docs/storage-look-design.md 3.5): Structurize position
 * tags in the five Workshop blueprints (written by tools/decorator_hut.py, checked by tools/build_pack.py), not offsets
 * in code, so every level and every later look carries its own.
 *
 * <p>A tag {@code cs_pack_<set>_<piece>} marks a packed piece standing on a barrel: a box ({@code box_wreath},
 * {@code box_garland}, {@code box_lights}, {@code box_lanterns}, {@code box_gifts}) turns to its nearly empty state in
 * the festival, a coil ({@code coil_garland}, {@code coil_lights}) is taken away. A tag {@code cs_hang_<set>_<piece>}
 * marks a festival piece hung on a wall face ({@code garland}, {@code wreath}, {@code lights}: the fairy lights). The
 * set id is part of every name (3.6); 0.1.0 has one set, {@link #WINTER}.</p>
 *
 * <p>{@link #read} asks MineColonies, {@code IBuilding.getLocationsFromTag}: the hut block's position plus the offset
 * the placed blueprint wrote into the hut block's data. The csspots probe proved on the rig that those come back turned
 * and mirrored with the building, and that a completed upgrade work order replaces them with the new level's (3.5
 * [V]). The block entity must be loaded (the Decorator reads them at his hut); otherwise the list is empty.</p>
 *
 * <p>The piece's facing is not stored; the placing code works it out (tools/decorator_hut.spot_facing: away from the
 * first whole wall block beside the spot, looking north, east, west, south in the building's own frame; a standing
 * piece with none faces south). Nothing here places a block: {@link StoreroomWork} (S10g2) works the facing out and
 * places through {@link DecorPlacer}.</p>
 */
public final class StoreroomSpots {
    public static final String PACK_PREFIX = "cs_pack_";
    public static final String HANG_PREFIX = "cs_hang_";
    /** The set of 0.1.0: the Winter Festival and New Year share the winter decorations, inside as outside. */
    public static final String WINTER = "winter";
    public static final List<String> SETS = List.of(WINTER);
    /**
     * Festival id to set id (storage-look 3.6), next to the festival list of {@link me.lovkar.seasons.SeasonsConfig}:
     * a later festival with its own room dressing adds its line here and its set to {@link #SETS}.
     */
    private static final Map<String, String> SET_OF = Map.of(
            me.lovkar.seasons.SeasonsConfig.WINTER_FESTIVAL, WINTER,
            me.lovkar.seasons.SeasonsConfig.NEW_YEAR, WINTER);
    /** The packed pieces a tag may name, in the order {@link #read} lists them (tools/decorator_hut.PACKED_PIECES). */
    public static final List<String> PACKED_PIECES = List.of("box_wreath", "box_garland", "box_lights", "box_lanterns",
            "box_gifts", "coil_garland", "coil_lights");
    /** The hung pieces a tag may name (tools/decorator_hut.HUNG_PIECES). */
    public static final List<String> HUNG_PIECES = List.of("garland", "wreath", "lights");

    /** Packed (standing on a barrel all year) or hung (in the festival only). */
    public enum Kind {
        PACK, HANG
    }

    /** One spot: what goes there and where, in world positions. */
    public record Spot(Kind kind, String set, String piece, BlockPos pos) {
        public String tag() {
            return StoreroomSpots.tag(kind, set, piece);
        }
    }

    private StoreroomSpots() {
    }

    /**
     * The set the room shows for a festival (storage-look 3.6): both festivals of 0.1.0 share the winter set, and so do
     * {@code ""} and any id the map does not know (the room of 0.1.0 has no other set to show).
     */
    public static String setOf(final String festivalId) {
        return festivalId == null ? WINTER : SET_OF.getOrDefault(festivalId, WINTER);
    }

    public static String tag(final Kind kind, final String set, final String piece) {
        return (kind == Kind.PACK ? PACK_PREFIX : HANG_PREFIX) + set + "_" + piece;
    }

    /** The spot a tag names at {@code pos}, or null when the tag is not one of ours (an unknown set or piece included). */
    public static @Nullable Spot parse(final String tag, final BlockPos pos) {
        for (final Kind kind : Kind.values()) {
            final String prefix = kind == Kind.PACK ? PACK_PREFIX : HANG_PREFIX;
            if (!tag.startsWith(prefix)) {
                continue;
            }
            for (final String set : SETS) {
                for (final String piece : kind == Kind.PACK ? PACKED_PIECES : HUNG_PIECES) {
                    if (tag.equals(tag(kind, set, piece))) {
                        return new Spot(kind, set, piece, pos.immutable());
                    }
                }
            }
        }
        return null;
    }

    /**
     * Every spot of {@code set} in the building, packed pieces first, each kind in the order of its list, positions
     * sorted within a piece. Empty when the hut block's entity is not loaded.
     */
    public static List<Spot> read(final IBuilding building, final String set) {
        final List<Spot> out = new ArrayList<>();
        for (final Kind kind : Kind.values()) {
            for (final String piece : kind == Kind.PACK ? PACKED_PIECES : HUNG_PIECES) {
                final List<BlockPos> at = new ArrayList<>(building.getLocationsFromTag(tag(kind, set, piece)));
                at.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
                for (final BlockPos pos : at) {
                    out.add(new Spot(kind, set, piece, pos.immutable()));
                }
            }
        }
        return out;
    }

    /**
     * The block the Decorator puts at a spot, facing north (the placing code turns it): packed ({@code festival}
     * false) or in the festival. Null when the spot is empty in that state: a hung piece outside the festival, a coil
     * in it (it is out on the buildings).
     */
    public static @Nullable BlockState state(final Spot spot, final boolean festival) {
        if (spot.kind() == Kind.HANG) {
            if (!festival) {
                return null;
            }
            return switch (spot.piece()) {
                case "garland" -> SeasonsBlocks.GARLAND.get().defaultBlockState();
                case "wreath" -> SeasonsBlocks.WREATH.get().defaultBlockState();
                case "lights" -> SeasonsBlocks.FAIRY_LIGHTS.get().defaultBlockState();
                default -> null;
            };
        }
        final String piece = spot.piece();
        if (piece.startsWith("coil_")) {
            if (festival) {
                return null;
            }
            for (final StorageCoilBlock.Kind kind : StorageCoilBlock.Kind.values()) {
                if (piece.equals("coil_" + kind.getSerializedName())) {
                    return SeasonsBlocks.STORAGE_COIL.get().defaultBlockState().setValue(StorageCoilBlock.KIND, kind);
                }
            }
            return null;
        }
        for (final StorageBoxBlock.Content content : StorageBoxBlock.Content.values()) {
            if (piece.equals("box_" + content.getSerializedName())) {
                return SeasonsBlocks.STORAGE_BOX.get().defaultBlockState().setValue(StorageBoxBlock.CONTENT, content)
                        .setValue(StorageBoxBlock.FILL, festival ? StorageBoxBlock.Fill.LOW : StorageBoxBlock.Fill.FULL);
            }
        }
        return null;
    }
}
