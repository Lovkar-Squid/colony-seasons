package me.lovkar.seasons.calendar;

import me.lovkar.seasons.SeasonsConfig;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which festivals are on for a real date. The windows come from the config ({@link #fromConfig()}) or,
 * in a test, from a list of {@link Festival}s. Festivals keep the order they were given in, so the
 * payload and the status line are the same on every run.
 */
public final class FestivalCalendar {

    /**
     * What is on for one date.
     *
     * @param decor ids whose decoration window is open
     * @param days  ids whose festival days are on (they need not lie inside the decoration window)
     */
    public record Active(List<String> decor, List<String> days) {
        public static final Active NONE = new Active(List.of(), List.of());

        public Active {
            decor = List.copyOf(decor);
            days = List.copyOf(days);
        }

        /**
         * The ids that count as active for the clients (the payload): a festival is active while its decoration
         * window or its festival days are open, so from the first day of the decorations to the last.
         */
        public List<String> ids() {
            final Set<String> all = new LinkedHashSet<>(decor);
            all.addAll(days);
            return List.copyOf(all);
        }

        /** The same, plus one festival that is on by force whatever the date says. */
        public Active with(final String forcedId) {
            if (forcedId == null || forcedId.isEmpty()) {
                return this;
            }
            final List<String> newDecor = new ArrayList<>(decor);
            final List<String> newDays = new ArrayList<>(days);
            if (!newDecor.contains(forcedId)) {
                newDecor.add(forcedId);
            }
            if (!newDays.contains(forcedId)) {
                newDays.add(forcedId);
            }
            return new Active(newDecor, newDays);
        }
    }

    private final List<Festival> festivals;

    public FestivalCalendar(final List<Festival> festivals) {
        this.festivals = List.copyOf(festivals);
    }

    /** The festivals of {@link SeasonsConfig#FESTIVAL_IDS} with the dates the config holds right now. */
    public static FestivalCalendar fromConfig() {
        final List<Festival> list = new ArrayList<>();
        for (final String id : SeasonsConfig.FESTIVAL_IDS) {
            list.add(new Festival(id, SeasonsConfig.festivalEnabled(id),
                    Festival.parse(SeasonsConfig.decorFrom(id)), Festival.parse(SeasonsConfig.decorUntil(id)),
                    Festival.parse(SeasonsConfig.daysFrom(id)), Festival.parse(SeasonsConfig.daysUntil(id))));
        }
        return new FestivalCalendar(list);
    }

    public List<Festival> festivals() {
        return festivals;
    }

    /** The festival with this id, or {@code null}. */
    public Festival byId(final String id) {
        for (final Festival festival : festivals) {
            if (festival.id().equals(id)) {
                return festival;
            }
        }
        return null;
    }

    /** What is on for this date. */
    public Active at(final LocalDate date) {
        final List<String> decor = new ArrayList<>();
        final List<String> days = new ArrayList<>();
        for (final Festival festival : festivals) {
            if (festival.decorOpen(date)) {
                decor.add(festival.id());
            }
            if (festival.daysOpen(date)) {
                days.add(festival.id());
            }
        }
        return new Active(decor, days);
    }
}
