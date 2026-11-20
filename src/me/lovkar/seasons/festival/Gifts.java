package me.lovkar.seasons.festival;

import me.lovkar.seasons.ColonySeasons;
import me.lovkar.seasons.SeasonsConfig;
import me.lovkar.seasons.block.SeasonsBlocks;
import me.lovkar.seasons.calendar.CalendarManager;
import me.lovkar.seasons.decor.DecorLedger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.SeededContainerLoot;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;

import java.util.ArrayList;
import java.util.List;

/**
 * The gift a day (design 5): a player takes one from the Decorator's gift pile at the colony's tree, per real day
 * ({@code events.giftsPerPlayerPerDay}), while a festival's decoration window or its days are on. The gift is an
 * item that opens into a roll of a loot table, so pack makers can change what is in it:
 * {@code colony_seasons:gifts/winter_daily} on ordinary days of the window, {@code gifts/winter_festival} on the
 * Winter Festival days and {@code gifts/new_year} (a few festival rockets and a small gift) on the New Year days.
 *
 * <p>The claim is written to {@link FestivalData} (saved with the world, one store for the whole server) before the
 * item is handed over, so a restart, a re-placed pile or a second colony's pile never gives a second gift. Only the
 * pile the Decorator put at the tree (a square entry of the decoration ledger) hands gifts out; a pile a player
 * crafted and placed is a decoration. Gifts cost nothing in 0.1.0.</p>
 */
public final class Gifts {
    public static final ResourceKey<LootTable> DAILY = table("gifts/winter_daily");
    public static final ResourceKey<LootTable> FESTIVAL = table("gifts/winter_festival");
    public static final ResourceKey<LootTable> NEW_YEAR = table("gifts/new_year");

    public static final DeferredItem<GiftItem> GIFT = ColonySeasons.ITEMS.register("gift",
            () -> new GiftItem(new Item.Properties().stacksTo(16)));

    /** What a click on the gift pile did. */
    public enum Outcome { GIVEN, ALREADY, CLOSED, OFF, NOT_DECORATORS }

    private static int given;
    private static int opened;

    private Gifts() {
    }

    /** Called once from the mod constructor: loads the item above and puts it in the mod's creative tab. */
    public static void init(final IEventBus modBus) {
        modBus.addListener(Gifts::onTabContents);
    }

    private static void onTabContents(final BuildCreativeModeTabContentsEvent event) {
        if (SeasonsBlocks.TAB.getKey().equals(event.getTabKey())) {
            event.accept(GIFT.get());
        }
    }

    private static ResourceKey<LootTable> table(final String path) {
        return ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.fromNamespaceAndPath(ColonySeasons.MODID, path));
    }

    /** Gifts handed out since the server started (the probe counts them). */
    public static int given() {
        return given;
    }

    /** Gifts opened since the server started. */
    public static int opened() {
        return opened;
    }

    /** The table of today's gift: the Winter Festival's on its days, New Year's on its days, else the daily one. */
    public static ResourceKey<LootTable> tableFor(final CalendarManager.Snapshot snapshot) {
        if (snapshot != null && snapshot.festivals() != null) {
            if (snapshot.festivals().days().contains(SeasonsConfig.WINTER_FESTIVAL)) {
                return FESTIVAL;
            }
            if (snapshot.festivals().days().contains(SeasonsConfig.NEW_YEAR)) {
                return NEW_YEAR;
            }
        }
        return DAILY;
    }

    /** True while gifts can be taken: a decoration window or festival days are on. */
    public static boolean season(final CalendarManager.Snapshot snapshot) {
        return snapshot != null && snapshot.festivals() != null
                && (!snapshot.festivals().decor().isEmpty() || !snapshot.festivals().days().isEmpty());
    }

    /** A wrapped gift that opens into a roll of {@code table}. */
    public static ItemStack gift(final ResourceKey<LootTable> table) {
        final ItemStack stack = new ItemStack(GIFT.get());
        stack.set(DataComponents.CONTAINER_LOOT, new SeededContainerLoot(table, 0L));
        return stack;
    }

    /** The table a gift opens into ({@link #DAILY} for one without a table, as from the creative tab). */
    public static ResourceKey<LootTable> tableOf(final ItemStack stack) {
        final SeededContainerLoot loot = stack.get(DataComponents.CONTAINER_LOOT);
        return loot == null ? DAILY : loot.lootTable();
    }

    /**
     * A player clicks the gift pile at {@code pos}: today's gift if the pile is the Decorator's, the season is on and
     * the player has gifts left today. Says what happened on the action bar.
     */
    public static Outcome take(final ServerLevel level, final BlockPos pos, final Player player) {
        final Outcome outcome = decide(level, pos, player);
        final String key = switch (outcome) {
            case GIVEN -> "com.colony_seasons.festival.gift.taken";
            case ALREADY -> "com.colony_seasons.festival.gift.already";
            case CLOSED -> "com.colony_seasons.festival.gift.closed";
            case OFF -> "com.colony_seasons.festival.gift.off";
            case NOT_DECORATORS -> "com.colony_seasons.festival.gift.not_decorators";
        };
        player.displayClientMessage(Component.translatable(key), true);
        return outcome;
    }

    private static Outcome decide(final ServerLevel level, final BlockPos pos, final Player player) {
        final int perDay = SeasonsConfig.giftsPerPlayerPerDay();
        if (perDay <= 0) {
            return Outcome.OFF;
        }
        final DecorLedger ledger = DecorLedger.peek(level);
        final DecorLedger.Located at = ledger == null ? null : ledger.at(pos);
        // the Decorator's own pile: a square entry AND the world holds exactly what it lists. A vanilla log of the tree
        // never tells the ledger it went, so its entry can outlive it; a pile a player put in that cell is not his (S9, H2)
        if (at == null || at.owner().kind() != DecorLedger.Kind.SQUARE || level.getBlockState(pos) != at.entry().state()) {
            return Outcome.NOT_DECORATORS;
        }
        final CalendarManager.Snapshot snapshot = CalendarManager.current();
        if (!season(snapshot)) {
            return Outcome.CLOSED;
        }
        final FestivalData data = FestivalData.get(level.getServer());
        final long day = snapshot.date().toEpochDay();
        if (data.giftsOn(player.getUUID(), day) >= perDay) {
            return Outcome.ALREADY;
        }
        // the claim first: a crash between the two can at worst lose a gift, never give two
        data.claim(player.getUUID(), day);
        give(player, gift(tableFor(snapshot)));
        level.playSound(null, pos, SoundEvents.BUNDLE_INSERT, SoundSource.BLOCKS, 0.8F, 1.0F);
        given++;
        return Outcome.GIVEN;
    }

    /** Into the player's inventory; what does not fit drops at their feet. */
    static void give(final Player player, final ItemStack stack) {
        player.getInventory().add(stack);
        if (!stack.isEmpty()) {
            player.drop(stack, false);
        }
    }

    /** The items one gift opens into (one roll of its table). */
    public static List<ItemStack> open(final ServerLevel level, final ItemStack gift, final Player player) {
        final LootTable table = level.getServer().reloadableRegistries().getLootTable(tableOf(gift));
        final LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, player.position())
                .withParameter(LootContextParams.THIS_ENTITY, player)
                .withLuck(player.getLuck())
                .create(LootContextParamSets.GIFT);
        opened++;
        return new ArrayList<>(table.getRandomItems(params));
    }

    // ---------------------------------------------------------------- the Decorator hands colonists gifts

    /** The thank-you lines, one picked per colonist by his id. */
    private static final String[] THANKS = {"com.colony_seasons.festival.thanks.kind", "com.colony_seasons.festival.thanks.for_me",
            "com.colony_seasons.festival.thanks.surprise", "com.colony_seasons.festival.thanks.festival"};
    private static int handed;

    /** Gifts the Decorator handed to colonists since the server started. */
    public static int handed() {
        return handed;
    }

    /**
     * The Decorator hands a colonist a gift at the gathering (design 4, Hosting): hearts over the colonist and a
     * thank-you line in his voice to the players nearby. No item moves.
     */
    public static void handTo(final ServerLevel level, final net.minecraft.world.entity.LivingEntity decorator,
                              final com.minecolonies.api.entity.citizen.AbstractEntityCitizen colonist, final BlockPos site) {
        decorator.swing(InteractionHand.MAIN_HAND);
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.HEART, colonist.getX(), colonist.getY() + colonist.getBbHeight() + 0.3,
                colonist.getZ(), 4, 0.35, 0.2, 0.35, 0.02);
        final int id = colonist.getCitizenData() == null ? 0 : colonist.getCitizenData().getId();
        try {
            com.minecolonies.api.util.MessageUtils.forCitizen(colonist, THANKS[Math.floorMod(id, THANKS.length)])
                    .sendToClose(site, 16, new ArrayList<>(level.players()));
        } catch (final RuntimeException unavailable) {
            ColonySeasons.LOGGER.debug("Thank-you line not sent: {}", unavailable.toString());
        }
        handed++;
    }

    /** The wrapped gift: right-click opens it. */
    public static class GiftItem extends Item {
        public GiftItem(final Properties properties) {
            super(properties);
        }

        @Override
        public InteractionResultHolder<ItemStack> use(final Level level, final Player player, final InteractionHand hand) {
            final ItemStack stack = player.getItemInHand(hand);
            if (level instanceof ServerLevel server) {
                for (final ItemStack item : open(server, stack, player)) {
                    give(player, item);
                }
                if (!player.getAbilities().instabuild) {
                    stack.shrink(1);
                }
                level.playSound(null, player.blockPosition(), SoundEvents.BUNDLE_DROP_CONTENTS, SoundSource.PLAYERS, 0.8F, 1.0F);
                player.awardStat(Stats.ITEM_USED.get(this));
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }

        @Override
        public void appendHoverText(final ItemStack stack, final TooltipContext context, final List<Component> tooltip, final TooltipFlag flag) {
            final ResourceKey<LootTable> table = tableOf(stack);
            final String kind = table.equals(FESTIVAL) ? "com.colony_seasons.festival.gift.kind.winter_festival"
                    : table.equals(NEW_YEAR) ? "com.colony_seasons.festival.gift.kind.new_year"
                    : "com.colony_seasons.festival.gift.kind.winter_daily";
            tooltip.add(Component.translatable(kind).withStyle(net.minecraft.ChatFormatting.GRAY));
            tooltip.add(Component.translatable("com.colony_seasons.festival.gift.open").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        }
    }
}
