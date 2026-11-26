package me.lovkar.seasons.decor;

import me.lovkar.seasons.block.LanternPostBlock;
import me.lovkar.seasons.block.SeasonsBlocks;
import me.lovkar.seasons.block.SnowmanBlock;
import me.lovkar.seasons.block.StreetGarlandBlock;
import me.lovkar.seasons.calendar.Season;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * What stands on a mark of the Decorator's wand (0.2.0, Q11), as cells and slots; pure, it reads and writes no world.
 *
 * <ul>
 *   <li>a snowman (A or B) and a planter: the mark's cell;</li>
 *   <li>the festive lantern post: the mark's cell (the lower half) and the one above it (the upper half, the crook and
 *       its lantern), both facing the mark's way, unlit (the tree's lighting or a hand switches them on);</li>
 *   <li>a garland: the line of cells from the mark's first to its last span cell, at the posts' y + 1;</li>
 *   <li>the planter: the in-game season's block, the same table as the Town Square's planters
 *       (tools/square_hut.py TOUCHES): flowering azalea leaves in spring, azalea leaves in summer, a pumpkin in autumn,
 *       spruce leaves in winter, the leaves persistent; no season known yet, nothing.</li>
 * </ul>
 * Every piece is free (the Decorator takes no item for it), as the 0.1.0 decorations that are no kit's.
 */
public final class StreetPieces {

    private StreetPieces() {
    }

    /** The cells of a mark, its first cell first. */
    public static List<BlockPos> cells(final StreetMarks.Mark mark) {
        return switch (mark.type()) {
            case SNOWMAN_A, SNOWMAN_B, PLANTER -> List.of(mark.pos());
            case POST -> List.of(mark.pos(), mark.pos().above());
            case GARLAND -> line(mark);
        };
    }

    private static List<BlockPos> line(final StreetMarks.Mark mark) {
        final List<BlockPos> out = new ArrayList<>();
        final BlockPos to = mark.to() == null ? mark.pos() : mark.to();
        BlockPos at = mark.pos();
        out.add(at);
        for (int i = 0; i < StreetMarks.GARLAND_MAX + 1 && !at.equals(to); i++) {
            at = at.relative(mark.facing());
            out.add(at);
        }
        return List.copyOf(out);
    }

    /** The slots of a mark in placing order (a post's lower half first); none for a planter while no season is known. */
    public static List<DecorPlacer.Slot> slots(final StreetMarks.Mark mark, final @Nullable Season season) {
        final List<DecorPlacer.Slot> out = new ArrayList<>();
        switch (mark.type()) {
            case SNOWMAN_A -> out.add(new DecorPlacer.Slot(mark.pos(), snowman(SeasonsBlocks.SNOWMAN.get().defaultBlockState(), mark.facing()), false));
            case SNOWMAN_B -> out.add(new DecorPlacer.Slot(mark.pos(), snowman(SeasonsBlocks.SNOWMAN_B.get().defaultBlockState(), mark.facing()), false));
            case POST -> {
                final BlockState lower = SeasonsBlocks.LANTERN_POST.get().defaultBlockState().setValue(LanternPostBlock.FACING, mark.facing())
                        .setValue(LanternPostBlock.HALF, DoubleBlockHalf.LOWER);
                out.add(new DecorPlacer.Slot(mark.pos(), lower, false));
                out.add(new DecorPlacer.Slot(mark.pos().above(), lower.setValue(LanternPostBlock.HALF, DoubleBlockHalf.UPPER), false));
            }
            case GARLAND -> {
                final BlockState garland = SeasonsBlocks.STREET_GARLAND.get().defaultBlockState()
                        .setValue(StreetGarlandBlock.AXIS, mark.facing().getAxis());
                for (final BlockPos cell : line(mark)) {
                    out.add(new DecorPlacer.Slot(cell, garland, false));
                }
            }
            case PLANTER -> {
                final BlockState planter = planter(season);
                if (planter != null) {
                    out.add(new DecorPlacer.Slot(mark.pos(), planter, false));
                }
            }
        }
        return List.copyOf(out);
    }

    private static BlockState snowman(final BlockState state, final Direction facing) {
        return state.setValue(SnowmanBlock.FACING, facing);
    }

    /** The planter's block in a season (the Town Square's touches), or {@code null} without a season. */
    public static @Nullable BlockState planter(final @Nullable Season season) {
        if (season == null) {
            return null;
        }
        final BlockState state = switch (season) {
            case SPRING -> Blocks.FLOWERING_AZALEA_LEAVES.defaultBlockState();
            case SUMMER -> Blocks.AZALEA_LEAVES.defaultBlockState();
            case AUTUMN -> Blocks.PUMPKIN.defaultBlockState();
            case WINTER -> Blocks.SPRUCE_LEAVES.defaultBlockState();
        };
        BlockState s = state;
        if (s.hasProperty(LeavesBlock.PERSISTENT)) {
            s = s.setValue(LeavesBlock.PERSISTENT, true);
        }
        if (s.hasProperty(BlockStateProperties.WATERLOGGED)) {
            s = s.setValue(BlockStateProperties.WATERLOGGED, false);
        }
        return s;
    }

    /**
     * What the mark's group is planned for: the type's id, and for a planter the season's too, so a new season swaps only
     * the planters (the old piece comes down first, as on the square).
     */
    public static String pieceKey(final StreetMarks.Mark mark, final @Nullable Season season) {
        if (mark.type() == StreetMarks.Type.PLANTER) {
            return mark.type().id() + "_" + (season == null ? "none" : season.id());
        }
        return mark.type().id();
    }
}
