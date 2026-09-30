package com.ticketflow.sla;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;

/**
 * Working-time arithmetic: only the minutes inside the service window (for example Monday to
 * Friday, 08:00-18:00, minus holidays) count. Pure and immutable, so it is tested without Spring.
 */
public class BusinessCalendar {

    private final ZoneId zone;
    private final LocalTime opensAt;
    private final LocalTime closesAt;
    private final Set<DayOfWeek> workDays;
    private final Set<LocalDate> holidays;

    public BusinessCalendar(ZoneId zone, LocalTime opensAt, LocalTime closesAt, Set<DayOfWeek> workDays,
            Set<LocalDate> holidays) {
        if (!opensAt.isBefore(closesAt)) {
            throw new IllegalArgumentException("Business hours must open before they close.");
        }
        if (workDays.isEmpty()) {
            throw new IllegalArgumentException("At least one working day is required.");
        }
        this.zone = zone;
        this.opensAt = opensAt;
        this.closesAt = closesAt;
        this.workDays = Set.copyOf(workDays);
        this.holidays = Set.copyOf(holidays);
    }

    /**
     * The instant reached after spending {@code amount} of working time starting at {@code start}.
     * A start outside the window waits for the next opening; a deadline that ends exactly at
     * closing time is the closing instant itself.
     */
    public Instant plus(Instant start, Duration amount) {
        Instant cursor = nextOpen(start);
        Duration remaining = amount;
        while (true) {
            Instant windowEnd = closeOf(cursor.atZone(zone).toLocalDate());
            Duration available = Duration.between(cursor, windowEnd);
            if (remaining.compareTo(available) <= 0) {
                return cursor.plus(remaining);
            }
            remaining = remaining.minus(available);
            cursor = nextOpen(windowEnd);
        }
    }

    /** Working time between two instants; zero when {@code to} is not after {@code from}. */
    public Duration between(Instant from, Instant to) {
        Duration total = Duration.ZERO;
        if (!to.isAfter(from)) {
            return total;
        }
        LocalDate last = to.atZone(zone).toLocalDate();
        for (LocalDate day = from.atZone(zone).toLocalDate(); !day.isAfter(last); day = day.plusDays(1)) {
            if (!isWorkingDay(day)) {
                continue;
            }
            Instant start = max(from, openOf(day));
            Instant end = min(to, closeOf(day));
            if (start.isBefore(end)) {
                total = total.plus(Duration.between(start, end));
            }
        }
        return total;
    }

    /** {@code instant} itself when the service is open, otherwise the next opening. */
    private Instant nextOpen(Instant instant) {
        LocalDate day = instant.atZone(zone).toLocalDate();
        Instant cursor = instant;
        while (true) {
            if (isWorkingDay(day)) {
                Instant open = openOf(day);
                if (cursor.isBefore(open)) {
                    return open;
                }
                if (cursor.isBefore(closeOf(day))) {
                    return cursor;
                }
            }
            day = day.plusDays(1);
            cursor = openOf(day);
        }
    }

    private boolean isWorkingDay(LocalDate day) {
        return workDays.contains(day.getDayOfWeek()) && !holidays.contains(day);
    }

    private Instant openOf(LocalDate day) {
        return ZonedDateTime.of(day, opensAt, zone).toInstant();
    }

    private Instant closeOf(LocalDate day) {
        return ZonedDateTime.of(day, closesAt, zone).toInstant();
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
