package me.lovkar.seasons.festival;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * What the festival events must remember across a restart, saved with the overworld as
 * {@code data/colony_seasons_festival.dat} (design 4 [B]: nothing that must survive lives in AI fields).
 *
 * <ul>
 *   <li>per colony: the game day its tree was found finished (the two-day timer for the Decorator's lighting), the
 *       piece it was, whether it is lit and by whom; the real day and game day on which everyone came to the
 *       gathering; the real day of its last big dusk show and the game day of its last dusk fireworks;</li>
 *   <li>per player: the real day of their last gift and how many they took that day (one store for the whole
 *       server, so a second gift pile, a re-placed one or another colony's never hands out a second gift);</li>
 *   <li>the season the colony's players were last told about, and the real day whose midnight had its show.</li>
 * </ul>
 */
public final class FestivalData extends SavedData {
    public static final String FILE = "colony_seasons_festival";
    static final int VERSION = 1;

    /** One colony's festival state. Fields are read by the festival classes and set through the setters below. */
    public static final class ColonyState {
        long treeReadyDay = -1;
        String treePiece = "";
        boolean treeLit;
        String litBy = "";
        long everyoneRealDay = Long.MIN_VALUE;
        long everyoneGameDay = -1;
        long bigShowRealDay = Long.MIN_VALUE;
        long lastDuskGameDay = -1;

        public long treeReadyDay() {
            return treeReadyDay;
        }

        public String treePiece() {
            return treePiece;
        }

        public boolean treeLit() {
            return treeLit;
        }

        /** "player", "decorator" or "" when not lit. */
        public String litBy() {
            return litBy;
        }

        public long everyoneRealDay() {
            return everyoneRealDay;
        }

        public long everyoneGameDay() {
            return everyoneGameDay;
        }

        public long bigShowRealDay() {
            return bigShowRealDay;
        }

        public long lastDuskGameDay() {
            return lastDuskGameDay;
        }
    }

    /** A player's gifts: the real day (epoch day) of the last one and how many that day. */
    public record Claim(long day, int count) {
    }

    private final Map<Integer, ColonyState> colonies = new TreeMap<>();
    private final Map<UUID, Claim> claims = new LinkedHashMap<>();
    private String lastSeason = "";
    private long midnightShownDay = Long.MIN_VALUE;

    public static final SavedData.Factory<FestivalData> FACTORY = new SavedData.Factory<>(FestivalData::new, FestivalData::load);

    public FestivalData() {
    }

    /** The server's festival data (kept with the overworld; made on first use). */
    public static FestivalData get(final MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE);
    }

    // ---------------------------------------------------------------- colonies

    /** The colony's state (made on first use; an untouched one writes nothing). */
    public ColonyState colony(final int id) {
        return colonies.computeIfAbsent(id, k -> new ColonyState());
    }

    /** The colony's state if it has one, else {@code null}. */
    public ColonyState peek(final int id) {
        return colonies.get(id);
    }

    public void setTreeReady(final int id, final long day, final String piece) {
        final ColonyState state = colony(id);
        state.treeReadyDay = day;
        state.treePiece = piece == null ? "" : piece;
        setDirty();
    }

    public void setTreeLit(final int id, final String by) {
        final ColonyState state = colony(id);
        state.treeLit = true;
        state.litBy = by == null ? "" : by;
        setDirty();
    }

    /** The colony has no finished tree (any more): ready and lit are forgotten. */
    public void resetTree(final int id) {
        final ColonyState state = colonies.get(id);
        if (state != null && (state.treeReadyDay != -1 || state.treeLit || !state.treePiece.isEmpty())) {
            state.treeReadyDay = -1;
            state.treePiece = "";
            state.treeLit = false;
            state.litBy = "";
            setDirty();
        }
    }

    public void setEveryone(final int id, final long realDay, final long gameDay) {
        final ColonyState state = colony(id);
        state.everyoneRealDay = realDay;
        state.everyoneGameDay = gameDay;
        setDirty();
    }

    public void setBigShow(final int id, final long realDay) {
        colony(id).bigShowRealDay = realDay;
        setDirty();
    }

    public void setDusk(final int id, final long gameDay) {
        colony(id).lastDuskGameDay = gameDay;
        setDirty();
    }

    // ---------------------------------------------------------------- gifts

    /** Gifts this player took on real day {@code day} (0 on any other day). */
    public int giftsOn(final UUID player, final long day) {
        final Claim claim = claims.get(player);
        return claim == null || claim.day() != day ? 0 : claim.count();
    }

    /** One more gift for this player on real day {@code day}. */
    public void claim(final UUID player, final long day) {
        claims.put(player, new Claim(day, giftsOn(player, day) + 1));
        setDirty();
    }

    /** Every player's last claim, read only (the probe and a later hut window look at it). */
    public Map<UUID, Claim> claims() {
        return Map.copyOf(claims);
    }

    // ---------------------------------------------------------------- the season line and the midnight show

    public String lastSeason() {
        return lastSeason;
    }

    public void setLastSeason(final String season) {
        if (!lastSeason.equals(season)) {
            lastSeason = season == null ? "" : season;
            setDirty();
        }
    }

    /** The real day (epoch day of the new date) whose midnight had its show. */
    public long midnightShownDay() {
        return midnightShownDay;
    }

    public void setMidnightShown(final long day) {
        midnightShownDay = day;
        setDirty();
    }

    // ---------------------------------------------------------------- saving

    @Override
    public CompoundTag save(final CompoundTag tag, final HolderLookup.Provider provider) {
        tag.putInt("version", VERSION);
        tag.putString("lastSeason", lastSeason);
        tag.putLong("midnightShownDay", midnightShownDay);
        final ListTag all = new ListTag();
        for (final Map.Entry<Integer, ColonyState> e : colonies.entrySet()) {
            final ColonyState s = e.getValue();
            final CompoundTag one = new CompoundTag();
            one.putInt("id", e.getKey());
            one.putLong("treeReadyDay", s.treeReadyDay);
            one.putString("treePiece", s.treePiece);
            one.putBoolean("treeLit", s.treeLit);
            one.putString("litBy", s.litBy);
            one.putLong("everyoneRealDay", s.everyoneRealDay);
            one.putLong("everyoneGameDay", s.everyoneGameDay);
            one.putLong("bigShowRealDay", s.bigShowRealDay);
            one.putLong("lastDuskGameDay", s.lastDuskGameDay);
            all.add(one);
        }
        tag.put("colonies", all);
        final ListTag gifts = new ListTag();
        for (final Map.Entry<UUID, Claim> e : claims.entrySet()) {
            final CompoundTag one = new CompoundTag();
            one.put("player", NbtUtils.createUUID(e.getKey()));
            one.putLong("day", e.getValue().day());
            one.putInt("count", e.getValue().count());
            gifts.add(one);
        }
        tag.put("gifts", gifts);
        return tag;
    }

    public static FestivalData load(final CompoundTag tag, final HolderLookup.Provider provider) {
        final FestivalData data = new FestivalData();
        data.lastSeason = tag.getString("lastSeason");
        data.midnightShownDay = tag.contains("midnightShownDay") ? tag.getLong("midnightShownDay") : Long.MIN_VALUE;
        for (final Tag element : tag.getList("colonies", Tag.TAG_COMPOUND)) {
            final CompoundTag one = (CompoundTag) element;
            final ColonyState s = new ColonyState();
            s.treeReadyDay = one.getLong("treeReadyDay");
            s.treePiece = one.getString("treePiece");
            s.treeLit = one.getBoolean("treeLit");
            s.litBy = one.getString("litBy");
            s.everyoneRealDay = one.getLong("everyoneRealDay");
            s.everyoneGameDay = one.getLong("everyoneGameDay");
            s.bigShowRealDay = one.getLong("bigShowRealDay");
            s.lastDuskGameDay = one.getLong("lastDuskGameDay");
            data.colonies.put(one.getInt("id"), s);
        }
        final Map<UUID, Claim> read = new HashMap<>();
        for (final Tag element : tag.getList("gifts", Tag.TAG_COMPOUND)) {
            final CompoundTag one = (CompoundTag) element;
            final Tag id = one.get("player");
            if (id != null) {
                read.put(NbtUtils.loadUUID(id), new Claim(one.getLong("day"), one.getInt("count")));
            }
        }
        data.claims.putAll(read);
        return data;
    }
}
