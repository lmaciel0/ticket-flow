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
}
