package me.lovkar.seasons.decor;

import me.lovkar.seasons.ColonySeasons;
import me.lovkar.seasons.block.FireworkCrateBlock;
import me.lovkar.seasons.block.GiftPileBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

import java.util.List;

/**
 * What a broken decoration drops (the owner's answer of 20 Nov 2026, S10d): a block the Decorator put up for free (with
 * {@code decorator.needsMaterials} false, or any piece he took no item for) drops nothing, and neither do the gift pile
 * and the firework crate of the tree; one he paid an item for drops its item as before. The ledger knows which were
 * paid. A block that is not in the ledger (a player placed it, or the festival marker) drops as it always did.
 *
 * <p>Our own blocks ask {@link #filter} from {@code getDrops}, which every way of breaking goes through (a player, a
 * pop-off when the wall goes, a piston, an explosion). A player's break computes the drops after the block is gone, by
 * when {@code onRemove} has made the ledger forget the entry; {@link DecorLedger#freedNow} still has it for that tick.
 * The vanilla blocks of a piece (the spruce leaves of the tree) never tell the ledger, so their entry is still listed
 * when they drop: {@link #init} cancels their {@link BlockDropsEvent} (a player, a pop-off, a citizen breaking them).
 * An explosion reads the drops of a vanilla block without that event, so {@link #onDetonate} clears a free piece's
 * vanilla block in its way first, with no drops (the grand tree's bricks, podzol and lanterns would drop whole).</p>
 *
 * <p>The Decorator's own take-down, the sweep and {@code decor remove} clear with no drops at all and hand back what
 * was paid ({@link DecorPlacer#remove}); nothing here changes them.</p>
 */
public final class DecorDrops {

    private static volatile int suppressed;

    private DecorDrops() {
    }

    /** Hooks the drop event of vanilla blocks and the explosions; called once from the mod constructor. */
    public static void init() {
        NeoForge.EVENT_BUS.addListener(DecorDrops::onBlockDrops);
        // last, so the list holds only what the explosion will break (MineColonies keeps a colony's blocks out of it by default)
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, DecorDrops::onDetonate);
    }

    /** Drops that were taken away since the server started (the probe and the log count them). */
    public static int suppressed() {
        return suppressed;
    }

    /**
     * True when the block {@code broken} at {@code pos} is one the Decorator put up for free, or the tree's gift pile or
     * firework crate: its drops are taken away. The ledger's entry there (still listed, or forgotten this tick) must be
     * of the same block; with no ledger in this level the answer is always false.
     */
    public static boolean dropsNothing(final ServerLevel level, final BlockPos pos, final BlockState broken) {
        if (level == null || pos == null || broken == null) {
            return false;
        }
        final DecorLedger ledger = DecorLedger.peek(level);
        if (ledger == null) {
            return false;
        }
        DecorLedger.Entry entry = null;
        final DecorLedger.Located live = ledger.at(pos);
        if (live != null && live.entry().state().getBlock() == broken.getBlock()) {
            entry = live.entry();
        }
        if (entry == null) {
            final DecorLedger.Entry freed = ledger.freedNow(pos, level.getGameTime());
            if (freed != null && freed.state().getBlock() == broken.getBlock()) {
                entry = freed;
            }
        }
        if (entry == null) {
            return false;
        }
        final Block block = entry.state().getBlock();
        return !entry.paid() || block instanceof GiftPileBlock || block instanceof FireworkCrateBlock;
    }

    /**
     * The drops of one of our decoration blocks, from its {@code getDrops}: empty when {@link #dropsNothing}, else
     * {@code drops} unchanged (also when the loot parameters carry no position).
     */
    public static List<ItemStack> filter(final BlockState state, final LootParams.Builder params, final List<ItemStack> drops) {
        if (drops == null || drops.isEmpty() || params == null) {
            return drops;
        }
        final Vec3 origin = params.getOptionalParameter(LootContextParams.ORIGIN);
        if (origin == null) {
            return drops;
        }
        if (dropsNothing(params.getLevel(), BlockPos.containing(origin), state)) {
            suppressed++;
            return List.of();
        }
        return drops;
    }

    /**
     * An explosion that destroys blocks: a vanilla block of a free piece in its way (its entry still listed) is set to
     * air first, with no drops, as the explosion would have done; the explosion then finds air there. It runs after every
     * other listener, so a block another mod took out of the list (MineColonies' protection of a colony's blocks, on by
     * default) is left alone. Our own blocks filter their drops themselves, and an explosion that keeps blocks or only
     * triggers them changes nothing.
     */
    private static void onDetonate(final ExplosionEvent.Detonate event) {
        final Explosion.BlockInteraction how = event.getExplosion().getBlockInteraction();
        if (!(event.getLevel() instanceof ServerLevel level)
                || (how != Explosion.BlockInteraction.DESTROY && how != Explosion.BlockInteraction.DESTROY_WITH_DECAY)) {
            return;
        }
        for (final BlockPos pos : event.getAffectedBlocks()) {
            final BlockState state = level.getBlockState(pos);
            if (!state.isAir() && !ours(state) && dropsNothing(level, pos, state)) {
                suppressed++;
                DecorPlacer.clearBlown(level, pos);
            }
        }
    }

    private static boolean ours(final BlockState state) {
        return ColonySeasons.MODID.equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace());
    }

    /** A vanilla block of a piece broken (its entry still listed): no drops when it was free. Our own blocks use {@link #filter}. */
    private static void onBlockDrops(final BlockDropsEvent event) {
        final BlockState state = event.getState();
        if (state == null || ours(state)) {
            return;
        }
        if (dropsNothing(event.getLevel(), event.getPos(), state)) {
            suppressed++;
            event.setCanceled(true);
        }
    }
}
