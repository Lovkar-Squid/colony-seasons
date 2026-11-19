package me.lovkar.seasons.calendar;

import me.lovkar.seasons.SeasonsConfig;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.function.Supplier;

/**
 * Today's real date, and the real time of day. This is the ONLY place in the mod that reads the real clock
 * (tools/check_clock.py checks it), so a test or a later chunk can replace it with {@link #fixed(LocalDate)} and
 * nothing else has to know. The date is read in the zone of {@code calendar.timeZone}: {@code system} is the
 * server's own zone, anything else a zone id.
 *
 * <p>{@link #now()} is the date and the time of day (the New Year countdown and the midnight show need the hour);
 * a source that only knows a date answers noon of it, so a fixed date never runs into a midnight.</p>
 */
@FunctionalInterface
public interface DateSource {

    LocalDate today();

    /** The real date and time of day; a date-only source says noon of {@link #today()}. */
    default LocalDateTime now() {
        return today().atTime(LocalTime.NOON);
    }

    /** The real date, in the configured zone (read again on every call, so a config change applies at once). */
    static DateSource system() {
        return of(Clock.systemUTC(), SeasonsConfig::timeZone);
    }

    /** A date source over any clock and zone setting; the real one is {@link #system()}, the tests use a fixed clock. */
    static DateSource of(final Clock clock, final Supplier<String> zoneSetting) {
        return new DateSource() {
            @Override
            public LocalDate today() {
                return LocalDate.now(clock.withZone(zone(zoneSetting.get())));
            }

            @Override
            public LocalDateTime now() {
                return LocalDateTime.now(clock.withZone(zone(zoneSetting.get())));
            }
        };
    }

    /** A date that never changes. */
    static DateSource fixed(final LocalDate date) {
        return () -> date;
    }

    /** A date and time that never change (a test steps it by replacing the source). */
    static DateSource fixed(final LocalDateTime time) {
        return new DateSource() {
            @Override
            public LocalDate today() {
                return time.toLocalDate();
            }

            @Override
            public LocalDateTime now() {
                return time;
            }
        };
    }

    /** The zone of a {@code calendar.timeZone} value: {@code system} (or anything unreadable) is the server's own zone. */
    static ZoneId zone(final String setting) {
        if (setting == null || SeasonsConfig.DEFAULT_TIME_ZONE.equals(setting)) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(setting);
        } catch (final DateTimeException unknown) {
            return ZoneId.systemDefault();
        }
    }
}
