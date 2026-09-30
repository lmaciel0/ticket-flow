package com.ticketflow.sla;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketflow.ticket.Priority;
import com.ticketflow.ticket.TicketStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SlaCalculatorTest {

    static final Instant NOW = Instant.parse("2026-01-10T12:00:00Z");

    static final SlaProperties PROPERTIES = new SlaProperties(Map.of(
            Priority.CRITICAL, Duration.ofHours(4),
            Priority.HIGH, Duration.ofHours(8),
            Priority.MEDIUM, Duration.ofHours(24),
            Priority.LOW, Duration.ofHours(72)));

    final SlaCalculator calculator = new SlaCalculator(PROPERTIES, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void dueAtIsCreationPlusDeadlinePlusPausedTime() {
        Instant createdAt = Instant.parse("2026-01-10T08:00:00Z");

        assertThat(calculator.dueAt(createdAt, Priority.CRITICAL, 0))
                .isEqualTo(Instant.parse("2026-01-10T12:00:00Z"));
        assertThat(calculator.dueAt(createdAt, Priority.CRITICAL, Duration.ofMinutes(90).toSeconds()))
                .isEqualTo(Instant.parse("2026-01-10T13:30:00Z"));
    }

    @Test
    void onTrackWhenAtLeast25PercentIsLeft() {
        // HIGH = 8h, 25% = 2h. Exactly 2h left is still on track.
        assertThat(calculator.indicator(TicketStatus.IN_PROGRESS, Priority.HIGH, NOW.plus(Duration.ofHours(2)), null))
                .isEqualTo(SlaIndicator.ON_TRACK);
    }

    @Test
    void atRiskWhenLessThan25PercentIsLeft() {
        assertThat(calculator.indicator(TicketStatus.OPEN, Priority.HIGH, NOW.plus(Duration.ofMinutes(119)), null))
                .isEqualTo(SlaIndicator.AT_RISK);
    }

    @Test
    void overdueFromTheDueInstantOn() {
        assertThat(calculator.indicator(TicketStatus.OPEN, Priority.LOW, NOW, null))
                .isEqualTo(SlaIndicator.OVERDUE);
        assertThat(calculator.indicator(TicketStatus.IN_PROGRESS, Priority.LOW, NOW.minusSeconds(1), null))
                .isEqualTo(SlaIndicator.OVERDUE);
    }

    @Test
    void pausedWhileWaitingForRequesterEvenIfPastDue() {
        assertThat(calculator.indicator(TicketStatus.WAITING_REQUESTER, Priority.LOW, NOW.minusSeconds(1), null))
                .isEqualTo(SlaIndicator.PAUSED);
    }

    @Test
    void finishedTicketsShowTheRecordedResult() {
        assertThat(calculator.indicator(TicketStatus.RESOLVED, Priority.LOW, NOW, false)).isEqualTo(SlaIndicator.MET);
        assertThat(calculator.indicator(TicketStatus.CLOSED, Priority.LOW, NOW, true))
                .isEqualTo(SlaIndicator.BREACHED);
    }

    @Test
    void propertiesRequireEveryPriority() {
        assertThatThrownBy(() -> new SlaProperties(Map.of(Priority.LOW, Duration.ofHours(1))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Nested
    class WithBusinessHours {

        // Friday 2026-01-09 17:00 in Sao Paulo = 20:00Z.
        static final Instant FRIDAY_17H = Instant.parse("2026-01-09T20:00:00Z");

        final SlaCalculator businessSla = new SlaCalculator(PROPERTIES, Clock.fixed(FRIDAY_17H, ZoneOffset.UTC),
                BusinessCalendarTest.calendar(java.util.Set.of()));

        @Test
        void criticalTicketOpenedFridayAfternoonIsDueMondayMorning() {
            assertThat(businessSla.dueAt(FRIDAY_17H, Priority.CRITICAL, 0))
                    .isEqualTo(Instant.parse("2026-01-12T14:00:00Z")); // Monday 11:00
        }

        @Test
        void pausedSecondsExtendTheDeadlineInWorkingTime() {
            // Paused for 1 working hour: 4h + 1h = 5h -> 1h Friday + 4h Monday = Monday 12:00.
            assertThat(businessSla.dueAt(FRIDAY_17H, Priority.CRITICAL, 3600))
                    .isEqualTo(Instant.parse("2026-01-12T15:00:00Z"));
        }

        @Test
        void elapsedIgnoresNightsAndWeekends() {
            assertThat(businessSla.elapsed(FRIDAY_17H, Instant.parse("2026-01-12T14:00:00Z")))
                    .isEqualTo(Duration.ofHours(4));
        }

        @Test
        void riskIsMeasuredInWorkingTimeNotWallTime() {
            // Due Monday 09:00: the wall clock says ~63h left, but only 2h of working time remain.
            // CRITICAL's risk window is 1h, so it is still on track...
            Instant dueMonday9h = Instant.parse("2026-01-12T12:00:00Z");
            assertThat(businessSla.indicator(TicketStatus.IN_PROGRESS, Priority.CRITICAL, dueMonday9h, null))
                    .isEqualTo(SlaIndicator.ON_TRACK);
            // ...while HIGH (window 2h) due Monday 08:30 has 1h30 of working time left: at risk.
            Instant dueMonday8h30 = Instant.parse("2026-01-12T11:30:00Z");
            assertThat(businessSla.indicator(TicketStatus.IN_PROGRESS, Priority.HIGH, dueMonday8h30, null))
                    .isEqualTo(SlaIndicator.AT_RISK);
        }

        @Test
        void atRiskThresholdMatchesTheIndicator() {
            // Friday 17:00 + 1h of working time (CRITICAL window) is Friday 18:00.
            assertThat(businessSla.atRiskBefore(FRIDAY_17H, Priority.CRITICAL))
                    .isEqualTo(Instant.parse("2026-01-09T21:00:00Z"));
        }
    }
}
