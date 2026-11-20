package me.lovkar.seasons.festival;

import com.ldtteam.structurize.blockentities.interfaces.IBlueprintDataProviderBE;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.jobs.IJob;
import com.minecolonies.api.entity.ai.statemachine.states.CitizenAIState;
import com.minecolonies.core.entity.ai.minimal.EntityAICitizenWander;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import com.minecolonies.core.entity.pathfinding.navigation.EntityNavigationUtils;
import me.lovkar.seasons.ColonySeasons;
import me.lovkar.seasons.SeasonsConfig;
import me.lovkar.seasons.block.FestivalMarkerBlock;
import me.lovkar.seasons.calendar.CalendarManager;
import me.lovkar.seasons.colony.BuildingDecorator;
import me.lovkar.seasons.colony.JobDecorator;
import me.lovkar.seasons.decor.DecorLedger;
import me.lovkar.seasons.decor.DecorWork;
import me.lovkar.seasons.decor.PieceLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The gathering at the tree (design 5, Winter Festival days). In the festival hours ({@code events.gatherFromTick}
 * to {@code events.gatherUntilTick}, the afternoon: colonists go to bed once {@code dayTime % 24000 > 10600}) the
 * colony's off-duty colonists (citizen AI state IDLE, or on MineColonies' own leisure trip) are sent walking to a
 * free spot round the tree, the festival marker's square (without a marker, the town hall: design 6). A colonist on a
 * leisure trip to another place (the town hall, a tavern) would be walked straight back there by MineColonies' idle
 * walk, so that trip is ended (his citizen AI is set back to its first state, IDLE, which he was in before the trip)
 * and he comes like the others; one strolling round our tree is left to stroll. A short song plays at the start and every
 * {@link #SONG_TICKS} ticks while anyone is there.
 *
 * <p>Who comes ([B], the owner, 19 Nov 2026; {@code events.whoComes}): with {@code everyone_once_a_day} every
 * colonist but the guards comes on the first festival game day of each real day, so work stops for that day's festival hours, and
 * only off-duty colonists on the other game days; {@code off_duty} only ever the off-duty ones; {@code everyone}
 * everyone on every festival game day. Working colonists are brought by the mixin at the head of
 * {@code CitizenAI.calculateNextState} ({@link #bringWorker}); it is written to fail soft, and without it only the
 * off-duty colonists come. Which game day was a colony's "everyone" day is saved in {@link FestivalData}.</p>
 *
 * <p>[V] The marker's block entity is a Structurize blueprint-data provider, so during the hours the marker is also
 * one of MineColonies' leisure sites (MineColonies' own idle walk then sometimes chooses it and strolls inside a box
 * round the tree); outside the hours it is taken off the list again. Proven on the rig (csfestival): MineColonies'
 * slow colony tick drops every leisure site whose block entity is not its own decoration controller, so the marker
 * falls off the list now and then and the next poll (a second later) puts it back; the walking below does not depend
 * on it.</p>
 *
 * <p>The pull runs every {@link #PULL_TICKS} ticks, not every 5 s as design 5 suggests: MineColonies' idle walk
 * starts a random walk of up to 10 blocks whenever a colonist's path is done, so with a slow pull the gathered
 * colonists drift away for seconds at a time. Colonists who arrived are given a short walk to another spot every
 * {@link #MILL_TICKS} ticks, which keeps their path busy and the crowd moving round the tree.</p>
 */
public final class Gathering {
    /** MineColonies sends colonists to bed once the time of day is past this. */
    public static final int BEDTIME = 10600;
    /** The latest end of the hours that still leaves time to stop before bedtime. */
    public static final int LATEST_UNTIL = 10500;
    public static final int PULL_TICKS = 20;
    public static final int MILL_TICKS = 120;
    public static final int SONG_TICKS = 1200;
    /** Colonists further than this from the site are walked back to it; the spots lie within it. */
    public static final int KEEP_RADIUS = 7;
    /** Only the colony's colonists within this distance of the site are called. */
    public static final int CALL_RADIUS = 160;
    /** The festivals whose days have a gathering. */
    public static final List<String> FESTIVALS = List.of(SeasonsConfig.WINTER_FESTIVAL);

    public static final String WHO_EVERYONE_ONCE = "everyone_once_a_day";
    public static final String WHO_OFF_DUTY = "off_duty";
    public static final String WHO_EVERYONE = "everyone";

    /** The colonies whose working colonists come right now (read by the mixin on every AI decision; colony ids are
     * per dimension, so the colonies themselves are kept). */
    private static volatile Set<IColony> everyone = Set.of();
    private static volatile int mixinCalls;
    private static volatile int broughtWorkers;
    private static int pulls;
    private static int tripsEnded;
    private static int songs;
    private static boolean warned;
    /** When the song last played, per colony: by dimension and id, because MineColonies numbers colonies per dimension. */
    private static final Map<SongKey, Long> lastSong = new HashMap<>();

    private record SongKey(ResourceKey<Level> dimension, int colony) {
    }

    private Gathering() {
    }

    // ---------------------------------------------------------------- the hours

    /** The hours as configured, guarded by {@link #hours(int, int)}. */
    public static int[] hours() {
        final int from = SeasonsConfig.gatherFromTick();
        final int until = SeasonsConfig.gatherUntilTick();
        final int[] hours = hours(from, until);
        if ((hours[0] != from || hours[1] != until) && !warned) {
            warned = true;
            ColonySeasons.LOGGER.warn("events.gatherUntilTick ({}) must be later than events.gatherFromTick ({}) and at most {} "
                    + "(colonists go to bed at {}); the gathering uses {} to {}", until, from, LATEST_UNTIL, BEDTIME, hours[0], hours[1]);
        }
        return hours;
    }

    /**
     * The festival hours from the two config values (the S0 hand-off: nothing else enforces their order): {@code until}
     * is cut to {@link #LATEST_UNTIL}, so nobody is called after bedtime; if that leaves {@code until} not later than
     * {@code from}, the defaults are used.
     */
    public static int[] hours(final int from, final int until) {
        final int end = Math.min(until, LATEST_UNTIL);
        if (end <= from) {
            return new int[] {SeasonsConfig.DEFAULT_GATHER_FROM_TICK, SeasonsConfig.DEFAULT_GATHER_UNTIL_TICK};
        }
        return new int[] {from, end};
    }

    /** True inside the festival hours of the game day. */
    public static boolean inHours(final Level level) {
        final int[] hours = hours();
        final long time = level.getDayTime() % 24000L;
        return time >= hours[0] && time < hours[1];
    }

    /** True on the days of a festival that has a gathering. */
    public static boolean gatheringDay(final CalendarManager.Snapshot snapshot) {
        if (snapshot == null || snapshot.festivals() == null) {
            return false;
        }
        for (final String id : FESTIVALS) {
            if (snapshot.festivals().days().contains(id)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- the place

    /** Where the colony gathers: its festival marker when it stands, else the town hall, else the colony's centre. */
    public static BlockPos site(final ServerLevel level, final IColony colony) {
        final DecorLedger ledger = DecorLedger.peek(level);
        final BlockPos marker = ledger == null ? null : ledger.marker(colony.getID());
        if (marker != null && level.hasChunkAt(marker) && level.getBlockState(marker).getBlock() instanceof FestivalMarkerBlock) {
            return marker;
        }
        final IBuilding hall = colony.getServerBuildingManager().getTownHall();
        return hall != null ? hall.getPosition() : colony.getCenter();
    }

    /**
     * The radius the tree takes round the site, at least 1: the square's box (the cells of its piece and the ones its
     * group lists, {@link DecorWork#squareBox}), so the gathering and the Decorator hosting stand outside the tree also
     * before it is up (S10c: it went up round him).
     */
    private static int treeRadius(final ServerLevel level, final IColony colony, final BlockPos site) {
        final DecorLedger ledger = DecorLedger.peek(level);
        AABB box = null;
        if (ledger != null && level.hasChunkAt(site) && level.getBlockState(site).getBlock() instanceof FestivalMarkerBlock) {
            final Direction facing = level.getBlockState(site).getValue(FestivalMarkerBlock.FACING);
            box = DecorWork.squareBox(level, ledger, colony.getID(), site, facing, PieceLoader.PACK,
                    DecorWork.squarePath(ledger, colony.getID(), site, decoratorLevel(colony)));
        }
        int r = 1;
        if (box != null) {
            r = Math.max(r, Math.max(Math.max(site.getX() - (int) Math.floor(box.minX), (int) Math.ceil(box.maxX) - 1 - site.getX()),
                    Math.max(site.getZ() - (int) Math.floor(box.minZ), (int) Math.ceil(box.maxZ) - 1 - site.getZ())));
        }
        return Math.min(r, KEEP_RADIUS - 1);
    }

    /** The level of the colony's Decorator hut (the piece he would put up), or 0 without one. */
    private static int decoratorLevel(final IColony colony) {
        for (final IBuilding b : colony.getServerBuildingManager().getBuildings().values()) {
            if (b instanceof BuildingDecorator hut) {
                return hut.getBuildingLevel();
            }
        }
        return 0;
    }

    /** Standing places round the site, nearest ring first: a solid floor and room for feet and head, not in the tree. */
    public static List<BlockPos> spots(final ServerLevel level, final IColony colony, final BlockPos site) {
        final int inner = treeRadius(level, colony, site) + 1;
        final List<BlockPos> found = new ArrayList<>();
        for (int r = inner; r <= KEEP_RADIUS && found.size() < 24; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r || (dx + dz) % 2 != 0
                            || dx * dx + dz * dz > (KEEP_RADIUS + 0.5) * (KEEP_RADIUS + 0.5)) {
                        continue;                     // the ring only, every other cell (room between colonists), not too far
                    }
                    final BlockPos stand = standable(level, site.offset(dx, 0, dz));
                    if (stand != null) {
                        found.add(stand);
                    }
                }
            }
        }
        found.sort(Comparator.comparingDouble((BlockPos p) -> p.distSqr(site)).thenComparing(p -> p));
        return found;
    }

    /** A cell near {@code at} (3 up to 3 down) a colonist can stand in, or {@code null}; never loads a chunk. */
    public static BlockPos standable(final ServerLevel level, final BlockPos at) {
        if (!level.hasChunkAt(at)) {
            return null;
        }
        for (int dy = 3; dy >= -3; dy--) {
            final BlockPos feet = at.above(dy);
            final BlockState floor = level.getBlockState(feet.below());
            if (!floor.isFaceSturdy(level, feet.below(), net.minecraft.core.Direction.UP) || !floor.getFluidState().isEmpty()) {
                continue;
            }
            if (level.getBlockState(feet).getCollisionShape(level, feet).isEmpty() && level.getBlockState(feet).getFluidState().isEmpty()
                    && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()) {
                return feet.immutable();
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- one colony, one poll

    /** Who comes in this colony right now; also picks today's "everyone" game day (saved). */
    static boolean everyoneNow(final ServerLevel level, final IColony colony, final FestivalData data, final CalendarManager.Snapshot snapshot,
                               final long gameDay) {
        return everyoneNow(SeasonsConfig.whoComes(), colony.getID(), data, snapshot.date().toEpochDay(), gameDay);
    }

    /**
     * Whether the working colonists of colony {@code id} come on game day {@code gameDay} of real day {@code realDay},
     * by the {@code events.whoComes} value {@code who}; the first festival game day of each real day is saved as the
     * colony's "everyone" day (public for the probe).
     */
    public static boolean everyoneNow(final String who, final int id, final FestivalData data, final long realDay, final long gameDay) {
        if (WHO_OFF_DUTY.equals(who)) {
            return false;
        }
        if (WHO_EVERYONE.equals(who)) {
            return true;
        }
        final FestivalData.ColonyState state = data.peek(id);
        if (state == null || state.everyoneRealDay() != realDay) {
            data.setEveryone(id, realDay, gameDay);           // the first festival game day of this real day
            return true;
        }
        return state.everyoneGameDay() == gameDay;
    }

    /**
     * One poll of one colony: inside the hours of a gathering day, the off-duty colonists are walked to the site and
     * kept there; the leisure site is put on (and off) MineColonies' list. Returns true when the colony's working
     * colonists come now.
     */
    static boolean tick(final ServerLevel level, final IColony colony, final FestivalData data, final CalendarManager.Snapshot snapshot,
                        final long gameDay, final long tick) {
        final BlockPos site = site(level, colony);
        final boolean on = gatheringDay(snapshot) && inHours(level) && !level.isRaining();
        leisureSite(level, colony, on);
        if (!on || site == null) {
            return false;
        }
        final boolean everyoneComes = everyoneNow(level, colony, data, snapshot, gameDay);
        final List<BlockPos> spots = spots(level, colony, site);
        if (spots.isEmpty()) {
            return everyoneComes;
        }
        final List<EntityCitizen> come = new ArrayList<>();
        for (final ICitizenData citizen : colony.getCitizenManager().getCitizens()) {
            if (citizen.getEntity().orElse(null) instanceof EntityCitizen entity && offDuty(entity)
                    && entity.blockPosition().distSqr(site) <= (double) CALL_RADIUS * CALL_RADIUS) {
                come.add(entity);
            }
        }
        come.sort(Comparator.comparingInt(e -> e.getCitizenData().getId()));
        final int turn = (int) (tick / MILL_TICKS);
        int near = 0;
        for (int i = 0; i < come.size(); i++) {
            final EntityCitizen entity = come.get(i);
            final BlockPos spot = spots.get(Math.floorMod(i + turn, spots.size()));
            final boolean there = entity.blockPosition().distSqr(site) <= (double) KEEP_RADIUS * KEEP_RADIUS;
            near += there ? 1 : 0;
            if (onLeisureTrip(entity)) {
                if (there) {
                    continue;                         // strolling round our tree (the leisure site): MineColonies walks him
                }
                // on MineColonies' own leisure trip to another place (the town hall, a tavern): his idle walk would
                // take him straight back there, so the trip ends here and he is idle again, then walked like the rest
                if (entity.getCitizenAI().getState() == EntityAICitizenWander.WanderState.READ_A_BOOK
                        && entity.getMainHandItem().is(Items.BOOK)) {
                    entity.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);   // the book his reading put in his hand
                }
                entity.getCitizenAI().reset();
                tripsEnded++;
            }
            if (!there || entity.getNavigation().isDone() && Math.floorMod(tick + i * 7L, MILL_TICKS) < PULL_TICKS) {
                EntityNavigationUtils.walkToPos(entity, spot, 1, true);
                pulls++;
            }
        }
        final SongKey key = new SongKey(level.dimension(), colony.getID());
        final Long sung = lastSong.get(key);
        if (near > 0 && (sung == null || tick - sung >= SONG_TICKS || tick < sung)) {
            lastSong.put(key, tick);
            level.playSound(null, site, FestivalSounds.SONG.get(), SoundSource.RECORDS, 2.0F, 1.0F);
            songs++;
        }
        return everyoneComes;
    }

    /**
     * Off duty: in MineColonies' IDLE state or one of its idle walk's leisure states (on his way to a leisure site,
     * strolling there, reading a book), awake, not the Decorator (he hosts).
     */
    static boolean offDuty(final EntityCitizen entity) {
        if (entity.getCitizenAI() == null
                || entity.getCitizenAI().getState() != CitizenAIState.IDLE && !onLeisureTrip(entity)) {
            return false;
        }
        if (entity.getCitizenSleepHandler() != null && entity.getCitizenSleepHandler().isAsleep()) {
            return false;
        }
        return !(entity.getCitizenData() != null && entity.getCitizenData().getJob() instanceof JobDecorator);
    }

    /** True while MineColonies' idle walk has him on a leisure trip (its states, still off duty). */
    static boolean onLeisureTrip(final EntityCitizen entity) {
        return entity.getCitizenAI() != null && entity.getCitizenAI().getState() instanceof EntityAICitizenWander.WanderState;
    }

    /** During the hours the marker is one of the colony's leisure sites ([V]: only where its block entity is a blueprint-data provider). */
    private static void leisureSite(final ServerLevel level, final IColony colony, final boolean on) {
        final DecorLedger ledger = DecorLedger.peek(level);
        final BlockPos marker = ledger == null ? null : ledger.marker(colony.getID());
        if (marker == null || !level.hasChunkAt(marker)) {
            return;
        }
        final boolean listed = colony.getServerBuildingManager().getLeisureSites().contains(marker);
        if (on && !listed && level.getBlockEntity(marker) instanceof IBlueprintDataProviderBE) {
            colony.getServerBuildingManager().addLeisureSite(marker);
        } else if (!on && listed) {
            colony.getServerBuildingManager().removeLeisureSite(marker);
        }
    }

    /**
     * A colony's marker went (broken, replaced): it leaves MineColonies' leisure sites at once. The hourly upkeep above
     * only looks at the registered marker, so without this a site broken in the gathering hours stayed on the list (S9,
     * H15). Never loads a chunk or a colony that is not there.
     */
    public static void markerGone(final ServerLevel level, final int colonyId, final BlockPos pos) {
        try {
            final IColony colony = com.minecolonies.api.colony.IColonyManager.getInstance().getColonyByWorld(colonyId, level);
            if (colony != null && colony.getServerBuildingManager().getLeisureSites().contains(pos)) {
                colony.getServerBuildingManager().removeLeisureSite(pos);
            }
        } catch (final RuntimeException unavailable) {
            ColonySeasons.LOGGER.debug("Leisure site not taken off: {}", unavailable.toString());
        }
    }

    /** Publishes the colonies whose working colonists come now (the mixin reads it). */
    static void publish(final Set<IColony> colonies) {
        everyone = Set.copyOf(colonies);
    }

    // ---------------------------------------------------------------- the mixin's question

    /**
     * Asked by the mixin at the head of {@code CitizenAI.calculateNextState} on every AI decision: true when this
     * working colonist should be off duty now, because his colony gathers everyone. Cheap: a set lookup and a few
     * fields. Everything MineColonies would rightly stop work for (sickness, a raid, rain, mourning, sleep) still
     * comes first; hunger is asked by the mixin itself. The Decorator hosts and is never brought, and neither is a
     * guard (the owner's answer of 20 Nov 2026: on the everyone day the guards stay at their posts, the other workers
     * come); every value of {@code events.whoComes} asks this, so no mode brings a guard.
     */
    public static boolean bringWorker(final EntityCitizen citizen) {
        mixinCalls++;
        final Set<IColony> colonies = everyone;
        if (colonies.isEmpty() || citizen == null) {
            return false;
        }
        final ICitizenData data = citizen.getCitizenData();
        if (data == null || data.getColony() == null || !colonies.contains(data.getColony())) {
            return false;
        }
        final IJob<?> job = data.getJob();
        if (job == null || job instanceof JobDecorator || job.isGuard() || citizen.isBaby() || !inHours(citizen.level())
                || citizen.level().isRaining()) {
            return false;
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
        if (citizen.getCitizenSleepHandler() != null && citizen.getCitizenSleepHandler().isAsleep()) {
            return false;
        }
        broughtWorkers++;
        return true;
    }

    // ---------------------------------------------------------------- for the probe

    /** How often the mixin asked (0 when it is not applied). */
    public static int mixinCalls() {
        return mixinCalls;
    }

    /** How often the mixin made a working colonist come. */
    public static int broughtWorkers() {
        return broughtWorkers;
    }

    public static int pulls() {
        return pulls;
    }

    /** Leisure trips to other places that were ended so the colonist could come. */
    public static int tripsEnded() {
        return tripsEnded;
    }

    public static int songs() {
        return songs;
    }

    /** The colonies whose working colonists come now. */
    public static Set<IColony> everyone() {
        return everyone;
    }

    static void reset() {
        everyone = Set.of();
        lastSong.clear();
        warned = false;
    }
}
