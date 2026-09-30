package com.ticketflow.sla;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Monday to Friday, 08:00-18:00 in Sao Paulo (UTC-3, no daylight saving time since 2019). */
class BusinessCalendarTest {

    static final Set<DayOfWeek> WEEKDAYS = Set.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);

    final BusinessCalendar calendar = calendar(Set.of());

    static BusinessCalendar calendar(Set<LocalDate> holidays) {
        return new BusinessCalendar(ZoneId.of("America/Sao_Paulo"), LocalTime.of(8, 0), LocalTime.of(18, 0),
                WEEKDAYS, holidays);
    }

    /** Local Sao Paulo time, written as it would be read on the wall: 2026-01-09 is a Friday. */
    static Instant local(String dateTime) {
        return LocalDateTime.parse(dateTime).atZone(ZoneId.of("America/Sao_Paulo")).toInstant();
    }

    @Test
    void insideTheWindowItBehavesLikeNormalTime() {
        assertThat(calendar.plus(local("2026-01-07T09:00:00"), Duration.ofHours(4)))
                .isEqualTo(local("2026-01-07T13:00:00"));
    }

    @Test
    void fridayAfternoonDeadlineRollsOverTheWeekend() {
        // Friday 17:00 + 4h: 1h left on Friday, 3h on Monday morning.
        assertThat(calendar.plus(local("2026-01-09T17:00:00"), Duration.ofHours(4)))
                .isEqualTo(local("2026-01-12T11:00:00"));
    }

    @Test
    void startingOutsideTheWindowWaitsForTheNextOpening() {
        // Saturday noon and Friday 22:00 both start counting Monday/next day at 08:00.
        assertThat(calendar.plus(local("2026-01-10T12:00:00"), Duration.ofHours(2)))
                .isEqualTo(local("2026-01-12T10:00:00"));
        assertThat(calendar.plus(local("2026-01-08T22:00:00"), Duration.ofHours(2)))
                .isEqualTo(local("2026-01-09T10:00:00"));
        assertThat(calendar.plus(local("2026-01-07T05:00:00"), Duration.ofHours(2)))
                .isEqualTo(local("2026-01-07T10:00:00"));
    }

    @Test
    void deadlineThatEndsExactlyAtClosingTimeStaysOnThatDay() {
        assertThat(calendar.plus(local("2026-01-07T14:00:00"), Duration.ofHours(4)))
                .isEqualTo(local("2026-01-07T18:00:00"));
    }

    @Test
    void longDeadlinesSpanSeveralDays() {
        // 24 working hours from Monday 08:00 = 2 full days + 4h: Wednesday 12:00.
        assertThat(calendar.plus(local("2026-01-05T08:00:00"), Duration.ofHours(24)))
                .isEqualTo(local("2026-01-07T12:00:00"));
    }

    @Test
    void holidaysDoNotCount() {
        BusinessCalendar withHoliday = calendar(Set.of(LocalDate.of(2026, 1, 12)));

        // Friday 17:00 + 4h: Monday is a holiday, so the remaining 3h happen on Tuesday.
        assertThat(withHoliday.plus(local("2026-01-09T17:00:00"), Duration.ofHours(4)))
                .isEqualTo(local("2026-01-13T11:00:00"));
    }

    @Test
    void betweenCountsOnlyWorkingTime() {
        // Friday 16:00 to Monday 10:00 = 2h on Friday + 2h on Monday.
        assertThat(calendar.between(local("2026-01-09T16:00:00"), local("2026-01-12T10:00:00")))
                .isEqualTo(Duration.ofHours(4));
        assertThat(calendar.between(local("2026-01-10T09:00:00"), local("2026-01-11T17:00:00")))
                .isEqualTo(Duration.ZERO);
        assertThat(calendar.between(local("2026-01-07T12:00:00"), local("2026-01-07T11:00:00")))
                .isEqualTo(Duration.ZERO);
    }

    @Test
    void betweenIsTheInverseOfPlus() {
        Instant start = local("2026-01-09T15:30:00");
        Duration amount = Duration.ofHours(13).plusMinutes(10);

        assertThat(calendar.between(start, calendar.plus(start, amount))).isEqualTo(amount);
    }

    @Test
    void minusGoesBackInWorkingTime() {
        // Monday 10:00 minus 4h: 2h on Monday, 2h on Friday afternoon (16:00).
        assertThat(calendar.minus(local("2026-01-12T10:00:00"), Duration.ofHours(4)))
                .isEqualTo(local("2026-01-09T16:00:00"));
        // From Saturday noon it counts from Friday closing time.
        assertThat(calendar.minus(local("2026-01-10T12:00:00"), Duration.ofHours(2)))
                .isEqualTo(local("2026-01-09T16:00:00"));
        // Exactly one full day of work ends at that day opening time.
        assertThat(calendar.minus(local("2026-01-07T18:00:00"), Duration.ofHours(10)))
                .isEqualTo(local("2026-01-07T08:00:00"));
    }

    @Test
    void minusIsTheInverseOfBetween() {
        Instant end = local("2026-01-11T22:15:00"); // Sunday night
        Duration amount = Duration.ofHours(27).plusMinutes(5);

        assertThat(calendar.between(calendar.minus(end, amount), end)).isEqualTo(amount);
    }

    @Test
    void rejectsImpossibleConfiguration() {
        ZoneId zone = ZoneId.of("America/Sao_Paulo");
        assertThatThrownBy(() -> new BusinessCalendar(zone, LocalTime.of(18, 0), LocalTime.of(8, 0), WEEKDAYS, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BusinessCalendar(zone, LocalTime.of(8, 0), LocalTime.of(18, 0), Set.of(), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
