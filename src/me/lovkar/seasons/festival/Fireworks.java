package me.lovkar.seasons.festival;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import it.unimi.dsi.fastutil.ints.IntList;
import me.lovkar.seasons.SeasonsConfig;
import me.lovkar.seasons.block.FestivalMarkerBlock;
import me.lovkar.seasons.block.SeasonsBlocks;
import me.lovkar.seasons.calendar.CalendarManager;
import me.lovkar.seasons.calendar.FestivalCalendar;
import me.lovkar.seasons.colony.BuildingDecorator;
import me.lovkar.seasons.colony.DecoratorTexts;
import me.lovkar.seasons.colony.JobDecorator;
import me.lovkar.seasons.decor.DecorLedger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.items.IItemHandler;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * New Year's fireworks (design 5). On the New Year days, at the first dusk of each real day with a player in the
 * colony, the big show goes up from the square (the firework crate, else beside the tree: {@link #site}); on the other dusks
 * a smaller sparkle, so the rockets last. If the server runs through real midnight inside the New Year days
 * ({@code events.midnightShow}), the colony's players see a countdown on the action bar and the big show starts at
 * 00:00. Every show uses the firework rockets in the Decorator's stock (his pack, then his hut; a rocket with its
 * own explosions flies as it is, a plain one gets the festival's colours); with {@code decorator.needsMaterials}
 * off they are free. The rockets of a lit tree are a free greeting.
 *
 * <p>The dusk is after the colonists' bedtime, so the server launches the show from the crate; the Decorator's
 * Launching state gets the rockets in beforehand. What a show did is noted in {@link FestivalData} (real day of the
 * big show, game day of the last dusk, the midnight), so a restart never repeats one.</p>
 */
public final class Fireworks {
    public static final int SHOW_ROCKETS = 16;
    public static final int SPARKLE_ROCKETS = 3;
    public static final int LIGHTING_ROCKETS = 3;
    public static final int MIDNIGHT_ROCKETS = 24;
    /** Dusk on the game clock: the sky is dark enough from 12500, and the window closes at 13800. */
    public static final int DUSK_FROM = 12500;
    public static final int DUSK_UNTIL = 13800;
    public static final int COUNTDOWN_SECONDS = 10;
    /** Ticks between two rockets of one show. */
    public static final int LAUNCH_GAP_TICKS = 6;
    private static final int[] COLOURS = {0xD8322E, 0xF0C030, 0x3FA34D, 0xF4F1EA, 0x3B6FD0, 0xC75BC2};

    private record Pending(ResourceKey<Level> dimension, BlockPos at, ItemStack rocket, int delay) {
    }

    /** The kinds of show, each with the Decorator's status line when it went up and when the stock was empty. */
    public enum Show {
        DUSK(DecoratorTexts.LAUNCHED_DUSK, DecoratorTexts.NO_ROCKETS_DUSK),
        SPARKLE(DecoratorTexts.LAUNCHED_SPARKLE, DecoratorTexts.NO_ROCKETS_SPARKLE),
        MIDNIGHT(DecoratorTexts.LAUNCHED_MIDNIGHT, DecoratorTexts.NO_ROCKETS_MIDNIGHT);

        private final String launched;
        private final String empty;

        Show(final String launched, final String empty) {
            this.launched = launched;
            this.empty = empty;
        }
    }

    private static final List<Pending> QUEUE = new ArrayList<>();
    private static LocalDateTime lastNow;
    private static long lastCountdown = -1;
    private static int shows;
    private static int rockets;
    private static int countdowns;
    private static int midnightShows;
    /** Tests replace this ("is a player in the colony?"); null asks the level's real players. */
    private static BiPredicate<ServerLevel, IColony> presence;

    private Fireworks() {
    }

    // ---------------------------------------------------------------- for the probe

    public static int shows() {
        return shows;
    }

    /** Rockets that took off since the server started. */
    public static int rockets() {
        return rockets;
    }

    public static int countdowns() {
        return countdowns;
    }

    public static int midnightShows() {
        return midnightShows;
    }

    /** Replaces the "a player is in the colony" test (the probe's fake players are not in the level's player list). */
    public static void setPresence(final BiPredicate<ServerLevel, IColony> test) {
        presence = test;
    }

    static void reset() {
        QUEUE.clear();
        lastNow = null;
        lastCountdown = -1;
    }

    // ---------------------------------------------------------------- the clock

    /** Every server tick: the rockets of running shows take off one by one. */
    static void tickQueue(final MinecraftServer server) {
        if (QUEUE.isEmpty()) {
            return;
        }
        final List<Pending> next = new ArrayList<>();
        final Iterator<Pending> it = QUEUE.iterator();
        while (it.hasNext()) {
            final Pending p = it.next();
            if (p.delay() > 0) {
                next.add(new Pending(p.dimension(), p.at(), p.rocket(), p.delay() - 1));
                continue;
            }
            final ServerLevel level = server.getLevel(p.dimension());
            if (level != null && level.hasChunkAt(p.at())) {
                final RandomSource random = level.getRandom();
                final FireworkRocketEntity entity = new FireworkRocketEntity(level, p.at().getX() + 0.5 + (random.nextDouble() - 0.5) * 1.5,
                        p.at().getY() + 1.1, p.at().getZ() + 0.5 + (random.nextDouble() - 0.5) * 1.5, p.rocket());
                level.addFreshEntity(entity);
                rockets++;
            }
        }
        QUEUE.clear();
        QUEUE.addAll(next);
    }

    /** True when a player is in the colony's claim (or the test says so). */
    static boolean playerIn(final ServerLevel level, final IColony colony) {
        if (presence != null) {
            return presence.test(level, colony);
        }
        for (final ServerPlayer player : level.players()) {
            if (colony.isCoordInColony(level, player.blockPosition())) {
                return true;
            }
        }
        return false;
    }

    /** One colony at one poll: the dusk show of the New Year days. */
    static void dusk(final ServerLevel level, final IColony colony, final FestivalData data, final CalendarManager.Snapshot snapshot,
                     final long gameDay) {
        if (snapshot.festivals() == null || !snapshot.festivals().days().contains(SeasonsConfig.NEW_YEAR)) {
            return;
        }
        final long time = level.getDayTime() % 24000L;
        if (time < DUSK_FROM || time >= DUSK_UNTIL) {
            return;
        }
        final int id = colony.getID();
        final FestivalData.ColonyState state = data.peek(id);
        if (state != null && state.lastDuskGameDay() == gameDay) {
            return;
        }
        if (!playerIn(level, colony)) {
            return;                                   // nobody to see it: the rockets wait
        }
        final long realDay = snapshot.date().toEpochDay();
        final boolean big = state == null || state.bigShowRealDay() != realDay;
        if (show(level, colony, big ? SHOW_ROCKETS : SPARKLE_ROCKETS, big ? Show.DUSK : Show.SPARKLE) < 0) {
            return;                                   // no square or no Decorator: nothing is noted, the next dusk tries
        }
        data.setDusk(id, gameDay);
        if (big) {
            data.setBigShow(id, realDay);
        }
    }

    /** Every poll: the countdown in the last seconds before a New Year midnight, and the show at 00:00. */
    static void midnight(final MinecraftServer server, final FestivalData data, final CalendarManager.Snapshot snapshot) {
        final LocalDateTime now = CalendarManager.now();
        final LocalDateTime before = lastNow;
        lastNow = now;
        if (!SeasonsConfig.midnightShow()) {
            return;
        }
        final String forced = snapshot.forced();
        final LocalDate today = now.toLocalDate();
        final LocalDate tomorrow = today.plusDays(1);
        final long left = Duration.between(now, tomorrow.atStartOfDay()).getSeconds();
        if (left >= 1 && left <= COUNTDOWN_SECONDS && left != lastCountdown && newYearOn(today, forced) && newYearOn(tomorrow, forced)) {
            lastCountdown = left;
            countdowns++;
            tell(server, Component.translatable("com.colony_seasons.festival.countdown", left));
        }
        if (before == null || !today.isAfter(before.toLocalDate()) || data.midnightShownDay() == today.toEpochDay()) {
            return;
        }
        if (!newYearOn(before.toLocalDate(), forced) || !newYearOn(today, forced)) {
            return;
        }
        data.setMidnightShown(today.toEpochDay());
        lastCountdown = -1;
        midnightShows++;
        tell(server, Component.translatable("com.colony_seasons.festival.happy_new_year"));
        for (final IColony colony : IColonyManager.getInstance().getAllColonies()) {
            if (colony.getWorld() instanceof ServerLevel level) {
                show(level, colony, MIDNIGHT_ROCKETS, Show.MIDNIGHT);
            }
        }
    }

    private static boolean newYearOn(final LocalDate date, final String forced) {
        return FestivalCalendar.fromConfig().at(date).with(forced).days().contains(SeasonsConfig.NEW_YEAR);
    }

    /** The action bar of every online player of a colony that has a show (members, and players standing in it). */
    private static void tell(final MinecraftServer server, final Component line) {
        final Map<UUID, Player> to = new LinkedHashMap<>();
        for (final IColony colony : IColonyManager.getInstance().getAllColonies()) {
            if (!(colony.getWorld() instanceof ServerLevel level) || site(level, colony) == null || decorator(colony) == null) {
                continue;
            }
            for (final Player player : colony.getMessagePlayerEntities()) {
                to.put(player.getUUID(), player);
            }
            for (final ServerPlayer player : level.players()) {
                if (colony.isCoordInColony(level, player.blockPosition())) {
                    to.put(player.getUUID(), player);
                }
            }
        }
        for (final Player player : to.values()) {
            player.displayClientMessage(line, true);
        }
    }

    // ---------------------------------------------------------------- shows

    /**
     * Where the colony's rockets go up (they leave from the top of this block): its firework crate; without one (hut
     * levels 1 and 2 have none) the marker when nothing stands over it, else the floor of the nearest standing place
     * round the tree with open sky above, because the tree's trunk stands on the marker and a rocket started inside it
     * bursts at once; {@code null} without a square.
     */
    public static BlockPos site(final ServerLevel level, final IColony colony) {
        final DecorLedger ledger = DecorLedger.peek(level);
        final BlockPos marker = ledger == null ? null : ledger.marker(colony.getID());
        if (marker == null || !level.hasChunkAt(marker) || !(level.getBlockState(marker).getBlock() instanceof FestivalMarkerBlock)) {
            return null;
        }
        final DecorLedger.GroupView square = ledger.group(colony.getID(), DecorLedger.Owner.square(marker));
        if (square != null) {
            for (final Map.Entry<BlockPos, DecorLedger.Entry> e : square.entries().entrySet()) {
                if (e.getValue().state().is(SeasonsBlocks.FIREWORK_CRATE.get()) && level.getBlockState(e.getKey()) == e.getValue().state()) {
                    return e.getKey();
                }
            }
        }
        if (openSky(level, marker.above())) {
            return marker;
        }
        for (final BlockPos spot : Gathering.spots(level, colony, marker)) {
            if (openSky(level, spot)) {
                return spot.below();
            }
        }
        return marker;
    }

    /** True when nothing that stops a rocket stands at or above {@code pos} (the motion-blocking heightmap). */
    public static boolean openSky(final ServerLevel level, final BlockPos pos) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ()) <= pos.getY();
    }

    /** The colony's staffed Decorator hut, or {@code null}. */
    static BuildingDecorator decorator(final IColony colony) {
        for (final IBuilding b : colony.getServerBuildingManager().getBuildings().values()) {
            if (b instanceof BuildingDecorator hut && !hut.getAllAssignedCitizen().isEmpty()) {
                return hut;
            }
        }
        return null;
    }

    /**
     * A show of up to {@code count} rockets from the colony's square, taken from the Decorator's stock (free with
     * {@code decorator.needsMaterials} off). Returns the rockets queued, or -1 when the colony has no square or no
     * Decorator.
     */
    public static int show(final ServerLevel level, final IColony colony, final int count, final Show what) {
        final BlockPos site = site(level, colony);
        final BuildingDecorator hut = decorator(colony);
        if (site == null || hut == null) {
            return -1;
        }
        IItemHandler pack = null;
        JobDecorator job = null;
        for (final ICitizenData citizen : hut.getAllAssignedCitizen()) {
            if (citizen != null) {
                pack = citizen.getInventory();
                job = citizen.getJob() instanceof JobDecorator j ? j : null;
                break;
            }
        }
        final IItemHandler store = hut.getItemHandlerCap();
        final boolean free = !SeasonsConfig.needsMaterials();
        int queued = 0;
        for (int i = 0; i < count; i++) {
            ItemStack rocket = free ? ItemStack.EMPTY : takeRocket(pack);
            if (!free && rocket.isEmpty()) {
                rocket = takeRocket(store);
            }
            if (!free && rocket.isEmpty()) {
                break;
            }
            queue(level, site, festive(rocket, level.getRandom()), i * LAUNCH_GAP_TICKS);
            queued++;
        }
        shows++;
        if (job != null) {
            if (queued > 0) {
                job.setStatus(what.launched, queued);
            } else {
                job.setStatus(what.empty);
            }
        }
        return queued;
    }

    /** A few free rockets from the colony's square (the lit tree's greeting); nothing without a square. */
    static void celebrate(final ServerLevel level, final int colonyId, final int count) {
        final IColony colony = IColonyManager.getInstance().getColonyByWorld(colonyId, level);
        final BlockPos site = colony == null ? null : site(level, colony);
        if (site == null) {
            return;
        }
        for (int i = 0; i < count; i++) {
            queue(level, site, festive(ItemStack.EMPTY, level.getRandom()), i * LAUNCH_GAP_TICKS * 2);
        }
    }

    private static void queue(final ServerLevel level, final BlockPos at, final ItemStack rocket, final int delay) {
        QUEUE.add(new Pending(level.dimension(), at.immutable(), rocket, delay));
    }

    /** One rocket out of a handler, with its components (a rocket somebody made with stars keeps them). */
    private static ItemStack takeRocket(final IItemHandler handler) {
        if (handler == null) {
            return ItemStack.EMPTY;
        }
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            if (handler.getStackInSlot(slot).is(Items.FIREWORK_ROCKET)) {
                final ItemStack one = handler.extractItem(slot, 1, false);
                if (!one.isEmpty()) {
                    return one;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    /** The rocket as it flies: its own explosions if it has any, else one in the festival's colours. */
    static ItemStack festive(final ItemStack from, final RandomSource random) {
        final net.minecraft.world.item.component.Fireworks own = from.isEmpty() ? null : from.get(DataComponents.FIREWORKS);
        if (own != null && !own.explosions().isEmpty()) {
            return from.copyWithCount(1);
        }
        final FireworkExplosion.Shape[] shapes = {FireworkExplosion.Shape.LARGE_BALL, FireworkExplosion.Shape.SMALL_BALL,
                FireworkExplosion.Shape.STAR, FireworkExplosion.Shape.BURST};
        final int a = COLOURS[random.nextInt(COLOURS.length)];
        final int b = COLOURS[random.nextInt(COLOURS.length)];
        final FireworkExplosion explosion = new FireworkExplosion(shapes[random.nextInt(shapes.length)], IntList.of(a, b),
                IntList.of(0xFFF6D8), random.nextBoolean(), random.nextInt(3) == 0);
        final ItemStack rocket = new ItemStack(Items.FIREWORK_ROCKET);
        rocket.set(DataComponents.FIREWORKS, new net.minecraft.world.item.component.Fireworks(1 + random.nextInt(2), List.of(explosion)));
        return rocket;
    }
}
