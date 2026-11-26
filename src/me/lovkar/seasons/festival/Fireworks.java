package me.lovkar.seasons.festival;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.entity.ai.statemachine.states.CitizenAIState;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import it.unimi.dsi.fastutil.ints.IntList;
import me.lovkar.seasons.ColonySeasons;
import me.lovkar.seasons.SeasonsConfig;
import me.lovkar.seasons.block.FestivalMarkerBlock;
import me.lovkar.seasons.block.SeasonsBlocks;
import me.lovkar.seasons.calendar.CalendarManager;
import me.lovkar.seasons.calendar.FestivalCalendar;
import me.lovkar.seasons.colony.BuildingDecorator;
import me.lovkar.seasons.colony.BuildingTownSquare;
import me.lovkar.seasons.colony.DecoratorTexts;
import me.lovkar.seasons.colony.JobDecorator;
import me.lovkar.seasons.decor.DecorLedger;
import me.lovkar.seasons.decor.DecorWork;
import me.lovkar.seasons.decor.Squares;
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
import java.util.Collection;
import java.util.HashMap;
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
 * <p>Who launches the dusk show (design 4, Hosting: he "launches the fireworks"): the dusk is after the colonists'
 * bedtime, so on the New Year evenings the mixin keeps the Decorator at work past bedtime while his colony's show is
 * still ahead ({@link #keepUp}); his Launching state takes him to the square, he launches the show there at dusk
 * ({@link #launchDusk}) and goes to bed. The server's own poll ({@link #dusk}) is the fallback: when he is not up (the
 * mixin is not applied, he is ill, hungry or it rains) it launches the show from the square at once, and when he is
 * up it waits {@link #LAUNCH_GRACE} ticks for him. {@link #DECORATOR_LAUNCHES} switches his part off. The midnight
 * show comes at a real hour, whatever the game clock says, so the server launches it. What a show did is noted in
 * {@link FestivalData} (real day of the big show, game day of the last dusk, the midnight), so a restart never repeats
 * one.</p>
 *
 * <p>The server log tells what the dusk did (S10c: in the third in-game test the clock was sprinted through the dusk,
 * the show went up unseen and nothing said so): one INFO line for every dusk show that goes up (colony, rockets, where
 * from, the time of day, the game day, by the Decorator or by the server), and one per colony and game day whenever
 * the reason a due dusk show has not gone up changes (no player in the colony, waiting for the Decorator, no square,
 * no Decorator, no rockets).</p>
 */
public final class Fireworks {
    public static final int SHOW_ROCKETS = 16;
    public static final int SPARKLE_ROCKETS = 3;
    public static final int LIGHTING_ROCKETS = 3;
    public static final int MIDNIGHT_ROCKETS = 24;
    /** Dusk on the game clock: the sky is dark enough from 12500, and the window closes at 13800. */
    public static final int DUSK_FROM = 12500;
    public static final int DUSK_UNTIL = 13800;
    /**
     * [O] Whether the Decorator launches the dusk show himself (an owner choice of S7): true, he stays up on the New
     * Year evenings until his colony's show has gone up; false, he goes to bed like everybody else and the server
     * launches it from the square.
     */
    public static final boolean DECORATOR_LAUNCHES = true;
    /** Ticks after {@link #DUSK_FROM} the server waits for a Decorator who is up before it launches the show itself. */
    public static final int LAUNCH_GRACE = 600;
    public static final int COUNTDOWN_SECONDS = 10;
    /** Ticks between two rockets of one show. */
    public static final int LAUNCH_GAP_TICKS = 6;
    private static final int[] COLOURS = {0xD8322E, 0xF0C030, 0x3FA34D, 0xF4F1EA, 0x3B6FD0, 0xC75BC2};

    private record Pending(ResourceKey<Level> dimension, BlockPos at, ItemStack rocket, int delay) {
    }

    /**
     * The kinds of show, each with the Decorator's status lines: he launched it, it went up from the square without
     * him (the server), the stock was empty.
     */
    public enum Show {
        DUSK(DecoratorTexts.LAUNCHED_DUSK, DecoratorTexts.WENT_UP_DUSK, DecoratorTexts.NO_ROCKETS_DUSK),
        SPARKLE(DecoratorTexts.LAUNCHED_SPARKLE, DecoratorTexts.WENT_UP_SPARKLE, DecoratorTexts.NO_ROCKETS_SPARKLE),
        MIDNIGHT(DecoratorTexts.WENT_UP_MIDNIGHT, DecoratorTexts.WENT_UP_MIDNIGHT, DecoratorTexts.NO_ROCKETS_MIDNIGHT);   // always the server's

        private final String launched;
        private final String wentUp;
        private final String empty;

        Show(final String launched, final String wentUp, final String empty) {
            this.launched = launched;
            this.wentUp = wentUp;
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
    private static int decoratorShows;
    private static int serverShows;
    private static volatile int keptUp;
    /** Tests replace this ("is a player in the colony?"); null asks the level's real players. */
    private static BiPredicate<ServerLevel, IColony> presence;
    /** Why each colony's due dusk show has not gone up, by dimension and colony id: the game day and the reason last logged. */
    private static final Map<String, String> WAITING = new HashMap<>();

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

    /** Dusk shows the Decorator launched himself. */
    public static int decoratorShows() {
        return decoratorShows;
    }

    /** Dusk shows the server launched without him (the fallback). */
    public static int serverShows() {
        return serverShows;
    }

    /** How often the mixin kept a Decorator up for the show (0 when it is not applied). */
    public static int keptUp() {
        return keptUp;
    }

    /** Replaces the "a player is in the colony" test (the probe's fake players are not in the level's player list). */
    public static void setPresence(final BiPredicate<ServerLevel, IColony> test) {
        presence = test;
    }

    static void reset() {
        QUEUE.clear();
        WAITING.clear();
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
        return playersIn(level, colony, level.players());
    }

    /** True when one of {@code players} stands in this level inside the colony's claim (the probe hands its fake players in here). */
    public static boolean playersIn(final ServerLevel level, final IColony colony, final Collection<? extends Player> players) {
        for (final Player player : players) {
            if (player.level() == level && colony.isCoordInColony(level, player.blockPosition())) {
                return true;
            }
        }
        return false;
    }

    /** True on the New Year days (forced or real). */
    private static boolean newYearDays(final CalendarManager.Snapshot snapshot) {
        return snapshot != null && snapshot.festivals() != null && snapshot.festivals().days().contains(SeasonsConfig.NEW_YEAR);
    }

    /** True in the dusk hours of the game clock, when the show goes up. */
    public static boolean isDusk(final Level level) {
        final long time = level.getDayTime() % 24000L;
        return time >= DUSK_FROM && time < DUSK_UNTIL;
    }

    /**
     * True while the colony's dusk show of this game day is still to come: a New Year day, before the end of dusk, not
     * shown yet this game day, a player in the colony to see it and a square to launch it from.
     */
    public static boolean duskAhead(final ServerLevel level, final IColony colony) {
        if (!newYearDays(CalendarManager.current()) || level.getDayTime() % 24000L >= DUSK_UNTIL) {
            return false;
        }
        final FestivalData.ColonyState state = FestivalData.of(level).peek(colony.getID());
        if (state != null && state.lastDuskGameDay() == DecorWork.day(level)) {
            return false;
        }
        return playerIn(level, colony) && site(level, colony) != null;
    }

    /**
     * One colony at one poll, the server's part of the dusk show of the New Year days: when the Decorator is up for it
     * (he launches it himself, {@link #launchDusk}) it waits {@link #LAUNCH_GRACE} ticks for him; when he is not, or
     * the grace is over, it launches the show from the square itself.
     */
    static void dusk(final ServerLevel level, final IColony colony, final FestivalData data, final CalendarManager.Snapshot snapshot,
                     final long gameDay) {
        if (!newYearDays(snapshot) || !isDusk(level)) {
            return;
        }
        final FestivalData.ColonyState state = data.peek(colony.getID());
        if (state != null && state.lastDuskGameDay() == gameDay) {
            return;
        }
        if (!playerIn(level, colony)) {
            waiting(level, colony, gameDay, "no player in the colony to see it");
            return;                                   // nobody to see it: the rockets wait
        }
        if (DECORATOR_LAUNCHES && level.getDayTime() % 24000L < DUSK_FROM + LAUNCH_GRACE && decoratorUp(colony)) {
            waiting(level, colony, gameDay, "waiting for the Decorator, who is up to launch it");
            return;                                   // he is up for the show: his to launch
        }
        if (site(level, colony) == null) {
            waiting(level, colony, gameDay, "no square (no festival marker)");
            return;
        }
        if (decorator(colony) == null) {
            waiting(level, colony, gameDay, "no Decorator works in the colony");
            return;
        }
        if (launch(level, colony, data, snapshot, gameDay, false) == 0) {
            waiting(level, colony, gameDay, "no firework rockets in the Decorator's stock");
        }
    }

    /** One INFO line per colony and game day whenever the reason its due dusk show has not gone up changes. */
    private static void waiting(final ServerLevel level, final IColony colony, final long gameDay, final String why) {
        final String key = level.dimension().location() + "#" + colony.getID();
        final String now = gameDay + ":" + why;
        if (!now.equals(WAITING.put(key, now))) {
            ColonySeasons.LOGGER.info("New Year dusk of colony {} (game day {}, time {}): the show has not gone up yet: {}", colony.getID(),
                    gameDay, level.getDayTime() % 24000L, why);
        }
    }

    /**
     * The Decorator launches his colony's dusk show from the square, where he stands (his Launching state, on a New
     * Year evening). Returns the rockets that went up (0 with an empty stock: the show is still due, he tries again),
     * or -1 when no show is due now.
     */
    public static int launchDusk(final ServerLevel level, final IColony colony) {
        if (!isDusk(level) || !duskAhead(level, colony)) {
            return -1;
        }
        return launch(level, colony, FestivalData.of(level), CalendarManager.current(), DecorWork.day(level), true);
    }

    /**
     * The dusk show of game day {@code gameDay}: the big show at the first dusk of the real day, else a sparkle. Only a
     * show that sent rockets up is noted (the dusk, and the big show of the real day) and counted: with an empty stock
     * nothing is noted, so the dusk keeps trying until {@link #DUSK_UNTIL} and rockets that come into his stock during
     * it still give that dusk's show (S9, the S7 hand-off). Returns the rockets queued: 0 for an empty stock, -1 without
     * a square or a Decorator (nothing is noted either way, the next poll tries again).
     */
    private static int launch(final ServerLevel level, final IColony colony, final FestivalData data, final CalendarManager.Snapshot snapshot,
                              final long gameDay, final boolean byDecorator) {
        final int id = colony.getID();
        final FestivalData.ColonyState state = data.peek(id);
        final long realDay = snapshot.date().toEpochDay();
        final boolean big = state == null || state.bigShowRealDay() != realDay;
        final int queued = show(level, colony, big ? SHOW_ROCKETS : SPARKLE_ROCKETS, big ? Show.DUSK : Show.SPARKLE, byDecorator);
        if (queued <= 0) {
            return queued;
        }
        ColonySeasons.LOGGER.info("New Year dusk of colony {} (game day {}, time {}): the {} went up, {} rockets from {}, launched by {}",
                id, gameDay, level.getDayTime() % 24000L, big ? "big show" : "sparkle", queued, site(level, colony).toShortString(),
                byDecorator ? "the Decorator" : "the server");
        WAITING.remove(level.dimension().location() + "#" + id);
        data.setDusk(id, gameDay);
        if (big) {
            data.setBigShow(id, realDay);
        }
        if (byDecorator) {
            decoratorShows++;
        } else {
            serverShows++;
        }
        return queued;
    }

    /**
     * True while the colony's Decorator is up for the show: awake and at work (MineColonies' WORK, then WORKING; after
     * bedtime only {@link #keepUp} keeps him so).
     */
    static boolean decoratorUp(final IColony colony) {
        final BuildingDecorator hut = decorator(colony);
        if (hut == null) {
            return false;
        }
        for (final ICitizenData citizen : hut.getAllAssignedCitizen()) {
            if (citizen != null && citizen.getEntity().orElse(null) instanceof EntityCitizen entity && entity.getCitizenAI() != null
                    && (entity.getCitizenAI().getState() == CitizenAIState.WORK || entity.getCitizenAI().getState() == CitizenAIState.WORKING)
                    && (entity.getCitizenSleepHandler() == null || !entity.getCitizenSleepHandler().isAsleep())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Asked by the mixin at the head of {@code CitizenAI.calculateNextState} on every AI decision: true when this
     * colonist is a Decorator who should stay at work past bedtime because his colony's dusk show is still ahead this
     * New Year evening. Only between bedtime and the end of dusk, and never for anybody else (the job is looked at
     * first, so the question is cheap). Everything MineColonies would rightly stop work for still comes first:
     * sickness, mourning, a raid, rain; hunger is asked by the mixin itself.
     */
    public static boolean keepUp(final EntityCitizen citizen) {
        if (!DECORATOR_LAUNCHES || citizen == null) {
            return false;
        }
        final ICitizenData data = citizen.getCitizenData();
        if (data == null || !(data.getJob() instanceof JobDecorator job) || job.getWorkBuilding() == null || data.getColony() == null) {
            return false;
        }
        if (!(citizen.level() instanceof ServerLevel level) || level.isRaining()) {
            return false;
        }
        final long time = level.getDayTime() % 24000L;
        if (time <= Gathering.BEDTIME || time >= DUSK_UNTIL) {
            return false;                             // by day he works anyway; after dusk, bed
        }
        if (data.getCitizenDiseaseHandler() != null && (data.getCitizenDiseaseHandler().isSick() || data.getCitizenDiseaseHandler().isHurt())) {
            return false;
        }
        if (data.getCitizenMournHandler() != null && data.getCitizenMournHandler().isMourning()) {
            return false;
        }
        if (data.getColony().getRaiderManager() != null && data.getColony().getRaiderManager().isRaided()) {
            return false;
        }
        if (!duskAhead(level, data.getColony())) {
            return false;
        }
        keptUp++;
        return true;
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
                show(level, colony, MIDNIGHT_ROCKETS, Show.MIDNIGHT, false);
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
     * levels 1 and 2 have none) on a Town Square the cell of its {@code launch} tag when the sky is open above it (design
     * 4.1: the launch point); then the centrepiece's cell (the marker, or the square's tree cell) when nothing stands over
     * it, else the floor of the nearest standing place round the tree with open sky above, because the tree's trunk
     * stands on the centrepiece and a rocket started inside it bursts at once; {@code null} without a square or marker.
     */
    public static BlockPos site(final ServerLevel level, final IColony colony) {
        final DecorLedger ledger = DecorLedger.peek(level);
        final Squares.Centre centre = Squares.centre(ledger, colony.getID());
        if (centre == null || !level.hasChunkAt(centre.anchor())
                || !centre.onSquare() && !(level.getBlockState(centre.anchor()).getBlock() instanceof FestivalMarkerBlock)) {
            return null;
        }
        final BlockPos marker = centre.anchor();
        final DecorLedger.GroupView square = ledger.group(colony.getID(), DecorLedger.Owner.square(marker));
        if (square != null) {
            for (final Map.Entry<BlockPos, DecorLedger.Entry> e : square.entries().entrySet()) {
                if (e.getValue().state().is(SeasonsBlocks.FIREWORK_CRATE.get()) && level.getBlockState(e.getKey()) == e.getValue().state()) {
                    return e.getKey();
                }
            }
        }
        if (centre.onSquare()) {
            final BlockPos launch = launchBlock(level, Squares.building(colony, ledger));
            if (launch != null) {
                return launch;
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

    /**
     * The block the rockets leave from at a Town Square's {@code launch} tag (the lowest cell should there be several):
     * the cell itself when something solid stands there, else the floor under it; {@code null} while the tag reads
     * empty, its chunk is not loaded or the sky above it is not open.
     */
    static BlockPos launchBlock(final ServerLevel level, final BuildingTownSquare square) {
        final List<BlockPos> cells = square == null ? null : square.getLocationsFromTag(BuildingTownSquare.TAG_LAUNCH);
        if (cells == null || cells.isEmpty()) {
            return null;
        }
        BlockPos cell = cells.get(0);
        for (final BlockPos other : cells) {
            if (other.compareTo(cell) < 0) {
                cell = other;
            }
        }
        if (!level.hasChunkAt(cell)) {
            return null;
        }
        final BlockPos block = level.getBlockState(cell).getCollisionShape(level, cell).isEmpty() ? cell.below() : cell;
        return openSky(level, block.above()) ? block.immutable() : null;
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
     * {@code decorator.needsMaterials} off); {@code byDecorator} says whether he launched it (his status line). Returns
     * the rockets queued (0 when the stock is empty: his status says so, and nothing is counted as a show), or -1 when
     * the colony has no square or no Decorator.
     */
    public static int show(final ServerLevel level, final IColony colony, final int count, final Show what, final boolean byDecorator) {
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
        if (queued > 0) {
            shows++;
        }
        if (job != null) {
            if (queued > 0) {
                job.setStatus(byDecorator ? what.launched : what.wentUp, queued);
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
