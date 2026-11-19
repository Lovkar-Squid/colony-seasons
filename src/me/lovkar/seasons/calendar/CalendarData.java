package me.lovkar.seasons.calendar;

import me.lovkar.seasons.SeasonsConfig;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.time.LocalDate;

/**
 * What the calendar keeps in the world: the offset of {@link SeasonCalendar} and the festival that is on by
 * force. One per world (stored with the overworld), shared by every colony, saved with the world.
 *
 * <p>The offset is applied once, when the data is created for a world that has none: it puts the
 * world in {@code calendar.startSeason} ({@code auto}: the season of the real date). After that only
 * {@code /colonyseasons season set} changes it.</p>
 */
public final class CalendarData extends SavedData {
    /** The data file name: {@code data/colony_seasons_calendar.dat} in the world. */
    public static final String NAME = "colony_seasons_calendar";

    private static final String OFFSET = "offset";
    private static final String FORCED = "forcedFestival";

    private int offset;
    /** The festival id that is on by force, "" for none. */
    private String forcedFestival;

    public CalendarData(final int offset, final String forcedFestival) {
        this.offset = offset;
        this.forcedFestival = forcedFestival == null ? "" : forcedFestival;
    }

    /** The data of a new world: its offset puts it in the start season, see the class comment. */
    public static CalendarData fresh(final long dayTime, final int yearLength, final String startSeason, final LocalDate today) {
        final Season chosen = Season.fromId(startSeason);
        final Season season = chosen != null ? chosen : Season.ofDate(today);
        final CalendarData data = new CalendarData(SeasonCalendar.offsetFor(season, dayTime, yearLength), "");
        data.setDirty();
        return data;
    }

    /** The calendar data of the world this overworld belongs to; created (and marked for saving) on first use. */
    public static CalendarData get(final ServerLevel overworld, final DateSource dates) {
        return overworld.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(
                        () -> fresh(overworld.getDayTime(), SeasonsConfig.yearLengthDays(), SeasonsConfig.startSeason(), dates.today()),
                        CalendarData::load),
                NAME);
    }

    public static CalendarData load(final CompoundTag tag, final HolderLookup.Provider registries) {
        return new CalendarData(tag.getInt(OFFSET), tag.getString(FORCED));
    }

    @Override
    public CompoundTag save(final CompoundTag tag, final HolderLookup.Provider registries) {
        tag.putInt(OFFSET, offset);
        tag.putString(FORCED, forcedFestival);
        return tag;
    }

    public int offset() {
        return offset;
    }

    public void setOffset(final int offset) {
        this.offset = offset;
        setDirty();
    }

    /** The festival on by force, or "" for none. */
    public String forcedFestival() {
        return forcedFestival;
    }

    public void setForcedFestival(final String id) {
        this.forcedFestival = id == null ? "" : id;
        setDirty();
    }
}
