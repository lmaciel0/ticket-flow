package com.ticketflow.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketflow.category.Category;
import com.ticketflow.common.ApiException;
import com.ticketflow.sla.SlaCalculator;
import com.ticketflow.sla.SlaProperties;
import com.ticketflow.user.Role;
import com.ticketflow.user.User;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests (no Spring, no database) for how the ticket lifecycle moves the SLA clock. */
class TicketSlaTest {

    static final Instant T0 = Instant.parse("2026-01-10T08:00:00Z");

    final SlaCalculator sla = new SlaCalculator(new SlaProperties(Map.of(
            Priority.CRITICAL, Duration.ofHours(4),
            Priority.HIGH, Duration.ofHours(8),
            Priority.MEDIUM, Duration.ofHours(24),
            Priority.LOW, Duration.ofHours(72))), Clock.fixed(T0, ZoneOffset.UTC));

    final User requester = new User("Ana", "ana@test.com", "hash", Role.REQUESTER, T0);
    final User agent = new User("Bruno", "bruno@test.com", "hash", Role.AGENT, T0);

    Ticket newTicket(Priority priority) {
        return new Ticket("  Impressora  ", " Não imprime ", priority, new Category("Hardware"), requester, T0, sla);
    }

    static Instant at(int hours, int minutes) {
        return T0.plus(Duration.ofHours(hours).plusMinutes(minutes));
    }

    @Test
    void newTicketIsOpenWithDeadlineFromPriority() {
        Ticket ticket = newTicket(Priority.HIGH);

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.OPEN);
        assertThat(ticket.getTitle()).isEqualTo("Impressora");
        assertThat(ticket.getDueAt()).isEqualTo(at(8, 0));
    }

    @Test
    void assigningAnOpenTicketStartsWork() {
        Ticket ticket = newTicket(Priority.HIGH);

        ticket.assign(agent, at(0, 10), sla);

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(ticket.getAssignee()).isSameAs(agent);
        assertThat(ticket.getDueAt()).isEqualTo(at(8, 0));
    }

    @Test
    void waitingForRequesterPausesTheClockAndResumingPushesTheDeadline() {
        Ticket ticket = newTicket(Priority.HIGH);
        ticket.assign(agent, at(1, 0), sla);

        ticket.changeStatus(TicketStatus.WAITING_REQUESTER, at(2, 0), sla);
        assertThat(ticket.getPausedAt()).isEqualTo(at(2, 0));

        ticket.changeStatus(TicketStatus.IN_PROGRESS, at(5, 30), sla);
        assertThat(ticket.getPausedAt()).isNull();
        assertThat(ticket.getPausedTotalSeconds()).isEqualTo(Duration.ofMinutes(210).toSeconds());
        assertThat(ticket.getDueAt()).isEqualTo(at(11, 30));
    }

    @Test
    void resolvingRecordsWhetherTheSlaWasMet() {
        Ticket onTime = newTicket(Priority.CRITICAL);
        onTime.assign(agent, at(0, 5), sla);
        onTime.changeStatus(TicketStatus.RESOLVED, at(3, 59), sla);
        assertThat(onTime.getResolvedAt()).isEqualTo(at(3, 59));
        assertThat(onTime.getSlaBreached()).isFalse();

        Ticket late = newTicket(Priority.CRITICAL);
        late.assign(agent, at(0, 5), sla);
        late.changeStatus(TicketStatus.RESOLVED, at(4, 0), sla);
        assertThat(late.getSlaBreached()).isTrue();
    }

    @Test
    void reopeningClearsTheResultAndCountsResolvedTimeAsPause() {
        Ticket ticket = newTicket(Priority.CRITICAL);
        ticket.assign(agent, at(0, 5), sla);
        ticket.changeStatus(TicketStatus.RESOLVED, at(1, 0), sla);

        ticket.changeStatus(TicketStatus.IN_PROGRESS, at(3, 0), sla);

        assertThat(ticket.getResolvedAt()).isNull();
        assertThat(ticket.getSlaBreached()).isNull();
        assertThat(ticket.getDueAt()).isEqualTo(at(6, 0));
    }

    @Test
    void closingKeepsTheResolutionResult() {
        Ticket ticket = newTicket(Priority.CRITICAL);
        ticket.assign(agent, at(0, 5), sla);
        ticket.changeStatus(TicketStatus.RESOLVED, at(1, 0), sla);

        ticket.changeStatus(TicketStatus.CLOSED, at(2, 0), sla);

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.CLOSED);
        assertThat(ticket.getResolvedAt()).isEqualTo(at(1, 0));
        assertThat(ticket.getSlaBreached()).isFalse();
    }

    @Test
    void changingPriorityRecalculatesTheDeadlineKeepingPausedTime() {
        Ticket ticket = newTicket(Priority.LOW);
        ticket.assign(agent, at(0, 0), sla);
        ticket.changeStatus(TicketStatus.WAITING_REQUESTER, at(1, 0), sla);
        ticket.changeStatus(TicketStatus.IN_PROGRESS, at(2, 0), sla);

        ticket.changePriority(Priority.CRITICAL, sla);

        assertThat(ticket.getDueAt()).isEqualTo(at(5, 0));
    }

    @Test
    void invalidTransitionIsRejected() {
        Ticket ticket = newTicket(Priority.LOW);

        assertThatThrownBy(() -> ticket.changeStatus(TicketStatus.RESOLVED, at(1, 0), sla))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("OPEN");
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.OPEN);
    }
}
