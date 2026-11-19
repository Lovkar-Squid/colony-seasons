package me.lovkar.seasons.festival;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.util.MessageUtils;
import me.lovkar.seasons.ColonySeasons;
import me.lovkar.seasons.block.FireworkCrateBlock;
import me.lovkar.seasons.block.GiftPileBlock;
import me.lovkar.seasons.block.Lights;
import me.lovkar.seasons.calendar.CalendarManager;
import me.lovkar.seasons.decor.DecorLedger;
import me.lovkar.seasons.decor.DecorPhase;
import me.lovkar.seasons.decor.DecorWork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Lighting the tree (design 5, winter season). When the Decorator finishes the tree at the festival marker, the
 * colony's players are told once; a right-click on any block of the tree (not the gift pile, which hands out gifts,
 * nor the firework crate) lights every light the decoration ledger lists for that colony, the ornaments, the star
 * and the strings on the buildings, with a chime and a few rockets. If nobody does within
 * {@link #DECORATOR_AFTER_DAYS} game days, the Decorator lights it. While the window lasts, lights the Decorator puts
 * back later (tending, a new building) are switched on too.
 *
 * <p>Every switch keeps the ledger equal to the world (design 3.3, the S4 rule: removal only on an exact state
 * match): a light is switched only where the world still holds exactly the recorded state; our blocks tell the ledger
 * from {@code onRemove}, and the entry is checked once more after each switch. The two-day timer and the lit state
 * live in {@link FestivalData}, never in the AI.</p>
 */
public final class TreeLighting {
    /** Game days after the tree is finished before the Decorator lights it himself. */
    public static final int DECORATOR_AFTER_DAYS = 2;
    public static final String BY_PLAYER = "player";
    public static final String BY_DECORATOR = "decorator";

    private static int notices;
    private static int lightings;

    private TreeLighting() {
    }

    /** "The tree is finished" notices sent since the server started. */
    public static int notices() {
        return notices;
    }

    /** Times a tree was lit since the server started. */
    public static int lightings() {
        return lightings;
    }

    /** A light of ours: one of the mod's blocks with {@code lit}. */
    public static boolean isLight(final BlockState state) {
        return state.hasProperty(Lights.LIT)
                && ColonySeasons.MODID.equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace());
    }

    /** The colony's finished tree: the square group at its registered marker, complete and not empty; else {@code null}. */
    public static DecorLedger.GroupView tree(final DecorLedger ledger, final int colony) {
        final BlockPos marker = ledger.marker(colony);
        if (marker == null) {
            return null;
        }
        final DecorLedger.GroupView group = ledger.group(colony, DecorLedger.Owner.square(marker));
        if (group == null || group.entries().isEmpty() || group.decoratedDay() < 0 || group.piece().isEmpty()
                || ledger.squareProblem(colony)) {
            return null;
        }
        return group;
    }

    /** One look at one colony (FestivalDirector's poll): the notice, the Decorator's lighting, the upkeep. */
    static void tick(final ServerLevel level, final IColony colony, final FestivalData data, final CalendarManager.Snapshot snapshot,
                     final long gameDay, final boolean upkeep) {
        final int id = colony.getID();
        final DecorLedger ledger = DecorLedger.peek(level);
        final DecorLedger.GroupView tree = ledger == null ? null : tree(ledger, id);
        final FestivalData.ColonyState state = data.peek(id);
        if (tree == null) {
            if (state != null) {
                data.resetTree(id);                   // taken down, its marker gone, or a new piece going up
            }
            return;
        }
        if (!DecorPhase.windowOpen(snapshot)) {
            return;                                   // after the window the tree comes down: nothing to light
        }
        if (state == null || state.treeReadyDay() < 0) {
            data.setTreeReady(id, gameDay, tree.piece());
            if (!data.colony(id).treeLit()) {
                tellReady(colony);
            }
            return;
        }
        if (!state.treeLit()) {
            if (gameDay >= state.treeReadyDay() + DECORATOR_AFTER_DAYS && DecorWork.staffed(colony)) {
                light(level, colony, BY_DECORATOR, null);
            }
            return;
        }
        if (upkeep && switchOn(level, ledger, id) > 0) {
            ledger.flush(level);
        }
    }

    private static void tellReady(final IColony colony) {
        notices++;
        try {
            MessageUtils.format("com.colony_seasons.festival.tree.ready", colony.getName()).sendTo(colony).forAllPlayers();
        } catch (final RuntimeException unavailable) {
            ColonySeasons.LOGGER.debug("Tree notice not sent: {}", unavailable.toString());
        }
    }

    /**
     * A right-click on {@code pos}: true (and the tree lit) when it is a block of a colony's finished, unlit tree in
     * its window. Anything else is left to the block (a lit tree's lights switch one by one as usual).
     */
    public static boolean onRightClick(final ServerLevel level, final Player player, final BlockPos pos) {
        final DecorLedger ledger = DecorLedger.peek(level);
        final DecorLedger.Located at = ledger == null ? null : ledger.at(pos);
        if (at == null || at.owner().kind() != DecorLedger.Kind.SQUARE) {
            return false;
        }
        final BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof GiftPileBlock || state.getBlock() instanceof FireworkCrateBlock) {
            return false;
        }
        final DecorLedger.GroupView tree = tree(ledger, at.colony());
        if (tree == null || !tree.owner().equals(at.owner()) || !DecorPhase.windowOpen(CalendarManager.current())) {
            return false;
        }
        final FestivalData data = FestivalData.get(level.getServer());
        final FestivalData.ColonyState s = data.peek(at.colony());
        if (s != null && s.treeLit()) {
            return false;
        }
        final IColony colony = IColonyManager.getInstance().getColonyByWorld(at.colony(), level);
        if (colony == null) {
            return false;
        }
        if (s == null || s.treeReadyDay() < 0) {
            data.setTreeReady(at.colony(), DecorWork.day(level), tree.piece());   // clicked before the poll saw it
        }
        light(level, colony, BY_PLAYER, player);
        return true;
    }

    /**
     * Lights every light of the colony in loaded chunks, plays the chime, sends a few rockets up from the square and
     * tells the colony's players. Returns how many blocks were switched.
     */
    public static int light(final ServerLevel level, final IColony colony, final String by, final @Nullable Player player) {
        final int id = colony.getID();
        final DecorLedger ledger = DecorLedger.peek(level);
        if (ledger == null) {
            return 0;
        }
        final int switched = switchOn(level, ledger, id);
        ledger.flush(level);
        FestivalData.get(level.getServer()).setTreeLit(id, by);
        final BlockPos marker = ledger.marker(id);
        final BlockPos at = marker != null ? marker : colony.getCenter();
        level.playSound(null, at, FestivalSounds.CHIME.get(), SoundSource.BLOCKS, 2.0F, 1.0F);
        Fireworks.celebrate(level, id, Fireworks.LIGHTING_ROCKETS);
        lightings++;
        try {
            if (player != null) {
                MessageUtils.format("com.colony_seasons.festival.tree.lit.player", player.getName().getString(), colony.getName())
                        .sendTo(colony).forAllPlayers();
            } else {
                MessageUtils.format("com.colony_seasons.festival.tree.lit.decorator", colony.getName()).sendTo(colony).forAllPlayers();
            }
        } catch (final RuntimeException unavailable) {
            ColonySeasons.LOGGER.debug("Tree lit message not sent: {}", unavailable.toString());
        }
        return switched;
    }

    /**
     * Switches on every unlit light the ledger lists for the colony whose chunk is loaded and whose block is still
     * exactly the recorded one. The block tells the ledger from {@code onRemove}; if the entry still differs from the
     * world afterwards, it is made to follow (a pure LIT change) or forgotten, so the ledger always equals the world.
     */
    static int switchOn(final ServerLevel level, final DecorLedger ledger, final int colony) {
        int switched = 0;
        for (final Map.Entry<BlockPos, DecorLedger.Entry> e : ledger.entries(colony).entrySet()) {
            final BlockPos pos = e.getKey();
            final BlockState recorded = e.getValue().state();
            if (!isLight(recorded) || recorded.getValue(Lights.LIT) || !level.hasChunkAt(pos) || level.getBlockState(pos) != recorded) {
                continue;
            }
            // the clients are told and the light engine runs, but no neighbour is re-shaped: a vanilla leaf of the tree
            // never re-reads its distance (it is not ours to tell the ledger), and both halves of the large star are
            // entries of their own, switched in this same loop
            level.setBlock(pos, recorded.setValue(Lights.LIT, true), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            final BlockState after = level.getBlockState(pos);
            final DecorLedger.Located listed = ledger.at(pos);
            if (listed != null && listed.entry().state() != after) {
                DecorLedger.blockChanged(level, pos, listed.entry().state(), after);
            }
            switched++;
        }
        return switched;
    }
}
