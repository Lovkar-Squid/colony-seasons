package me.lovkar.seasons.festival;

import com.ldtteam.structurize.blockentities.interfaces.IBlueprintDataProviderBE;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.jobs.IJob;
import com.minecolonies.api.entity.ai.statemachine.states.CitizenAIState;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import com.minecolonies.core.entity.pathfinding.navigation.EntityNavigationUtils;
import me.lovkar.seasons.ColonySeasons;
import me.lovkar.seasons.SeasonsConfig;
import me.lovkar.seasons.block.FestivalMarkerBlock;
import me.lovkar.seasons.calendar.CalendarManager;
import me.lovkar.seasons.colony.JobDecorator;
import me.lovkar.seasons.decor.DecorLedger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The gathering at the tree (design 5, Winter Festival days). In the festival hours ({@code events.gatherFromTick}
 * to {@code events.gatherUntilTick}, the afternoon: colonists go to bed once {@code dayTime % 24000 > 10600}) the
 * colony's off-duty colonists (citizen AI state IDLE) are sent walking to a free spot round the tree, the festival
 * marker's square (without a marker, the town hall: design 6). A short song plays at the start and every
 * {@link #SONG_TICKS} ticks while anyone is there.
 *
 * <p>Who comes ([B], the owner, 19 Nov 2026; {@code events.whoComes}): with {@code everyone_once_a_day} every
 * colonist comes on the first festival game day of each real day, so work stops for that day's festival hours, and
 * only off-duty colonists on the other game days; {@code off_duty} only ever the off-duty ones; {@code everyone}
 * everyone on every festival game day. Working colonists are brought by the mixin at the head of
 * {@code CitizenAI.calculateNextState} ({@link #bringWorker}); it is written to fail soft, and without it only the
 * off-duty colonists come. Which game day was a colony's "everyone" day is saved in {@link FestivalData}.</p>
 *
 * <p>[V] The marker's block entity is a Structurize blueprint-data provider, so during the hours the marker is also
 * one of MineColonies' leisure sites (MineColonies' own idle walk then sometimes chooses it and strolls inside a box
 * round the tree); outside the hours it is taken off the list again.</p>
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

    /** Colony ids whose working colonists come right now (read by the mixin on every AI decision). */
    private static volatile Set<Integer> everyone = Set.of();
    private static volatile int mixinCalls;
    private static volatile int broughtWorkers;
    private static int pulls;
    private static int songs;
    private static boolean warned;
    private static final Map<Integer, Long> lastSong = new HashMap<>();

    private Gathering() {
    }

    // ---------------------------------------------------------------- the hours

    /** The hours as configured, guarded: {@code until} at most {@link #LATEST_UNTIL}, later than {@code from}, else the defaults. */
    public static int[] hours() {
        int from = SeasonsConfig.gatherFromTick();
        int until = Math.min(SeasonsConfig.gatherUntilTick(), LATEST_UNTIL);
        if (until <= from) {
            if (!warned) {
                warned = true;
                ColonySeasons.LOGGER.warn("events.gatherUntilTick ({}) must be later than events.gatherFromTick ({}) and below {}; "
                        + "the gathering uses {} to {}", SeasonsConfig.gatherUntilTick(), SeasonsConfig.gatherFromTick(), BEDTIME,
                        SeasonsConfig.DEFAULT_GATHER_FROM_TICK, SeasonsConfig.DEFAULT_GATHER_UNTIL_TICK);
            }
            from = SeasonsConfig.DEFAULT_GATHER_FROM_TICK;
            until = SeasonsConfig.DEFAULT_GATHER_UNTIL_TICK;
        }
        return new int[] {from, until};
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

    /** The radius the tree takes round the site (the square's entries), at least 1. */
    private static int treeRadius(final ServerLevel level, final IColony colony, final BlockPos site) {
        final DecorLedger ledger = DecorLedger.peek(level);
        final DecorLedger.GroupView square = ledger == null ? null : ledger.group(colony.getID(), DecorLedger.Owner.square(site));
        int r = 1;
        if (square != null) {
            for (final BlockPos pos : square.entries().keySet()) {
                r = Math.max(r, Math.max(Math.abs(pos.getX() - site.getX()), Math.abs(pos.getZ() - site.getZ())));
            }
        }
        return Math.min(r, KEEP_RADIUS - 1);
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
        final String who = SeasonsConfig.whoComes();
        if (WHO_OFF_DUTY.equals(who)) {
            return false;
        }
        if (WHO_EVERYONE.equals(who)) {
            return true;
        }
        final long realDay = snapshot.date().toEpochDay();
        final FestivalData.ColonyState state = data.peek(colony.getID());
        if (state == null || state.everyoneRealDay() != realDay) {
            data.setEveryone(colony.getID(), realDay, gameDay);   // the first festival game day of this real day
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
            if (!there || entity.getNavigation().isDone() && Math.floorMod(tick + i * 7L, MILL_TICKS) < PULL_TICKS) {
                EntityNavigationUtils.walkToPos(entity, spot, 1, true);
                pulls++;
            }
            near += there ? 1 : 0;
        }
        final Long sung = lastSong.get(colony.getID());
        if (near > 0 && (sung == null || tick - sung >= SONG_TICKS || tick < sung)) {
            lastSong.put(colony.getID(), tick);
            level.playSound(null, site, FestivalSounds.SONG.get(), SoundSource.RECORDS, 2.0F, 1.0F);
            songs++;
        }
        return everyoneComes;
    }

    /** Off duty: in MineColonies' IDLE state, awake, not the Decorator (he hosts). */
    static boolean offDuty(final EntityCitizen entity) {
        if (entity.getCitizenAI() == null || entity.getCitizenAI().getState() != CitizenAIState.IDLE) {
            return false;
        }
        if (entity.getCitizenSleepHandler() != null && entity.getCitizenSleepHandler().isAsleep()) {
            return false;
        }
        return !(entity.getCitizenData() != null && entity.getCitizenData().getJob() instanceof JobDecorator);
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

    /** Publishes the colonies whose working colonists come now (the mixin reads it). */
    static void publish(final Set<Integer> ids) {
        everyone = Set.copyOf(ids);
    }

    // ---------------------------------------------------------------- the mixin's question

    /**
     * Asked by the mixin at the head of {@code CitizenAI.calculateNextState} on every AI decision: true when this
     * working colonist should be off duty now, because his colony gathers everyone. Cheap: a set lookup and a few
     * fields. Everything MineColonies would rightly stop work for (sickness, a raid, rain, mourning, sleep) still
     * comes first; hunger is asked by the mixin itself. The Decorator hosts and is never brought.
     */
    public static boolean bringWorker(final EntityCitizen citizen) {
        mixinCalls++;
        final Set<Integer> ids = everyone;
        if (ids.isEmpty() || citizen == null) {
            return false;
        }
        final ICitizenData data = citizen.getCitizenData();
        if (data == null || data.getColony() == null || !ids.contains(data.getColony().getID())) {
            return false;
        }
        final IJob<?> job = data.getJob();
        if (job == null || job instanceof JobDecorator || citizen.isBaby() || !inHours(citizen.level()) || citizen.level().isRaining()) {
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

    public static int songs() {
        return songs;
    }

    /** The colony ids whose working colonists come now. */
    public static Set<Integer> everyone() {
        return everyone;
    }

    static void reset() {
        everyone = Set.of();
        lastSong.clear();
        warned = false;
    }
}
