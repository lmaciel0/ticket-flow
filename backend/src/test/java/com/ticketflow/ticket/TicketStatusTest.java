package com.ticketflow.ticket;

import static com.ticketflow.ticket.TicketStatus.CLOSED;
import static com.ticketflow.ticket.TicketStatus.IN_PROGRESS;
import static com.ticketflow.ticket.TicketStatus.OPEN;
import static com.ticketflow.ticket.TicketStatus.RESOLVED;
import static com.ticketflow.ticket.TicketStatus.WAITING_REQUESTER;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class TicketStatusTest {

    @Test
    void allowsOnlyTheDocumentedTransitions() {
        assertAllowed(OPEN, Set.of(IN_PROGRESS));
        assertAllowed(IN_PROGRESS, Set.of(WAITING_REQUESTER, RESOLVED));
        assertAllowed(WAITING_REQUESTER, Set.of(IN_PROGRESS));
        assertAllowed(RESOLVED, Set.of(CLOSED, IN_PROGRESS));
        assertAllowed(CLOSED, Set.of());
    }

    @ParameterizedTest
    @EnumSource(TicketStatus.class)
    void neverTransitionsToItself(TicketStatus status) {
        assertThat(status.canTransitionTo(status)).isFalse();
    }

    @Test
    void clockRunsOnlyWhileOpenOrInProgress() {
        assertThat(EnumSet.allOf(TicketStatus.class).stream().filter(TicketStatus::isClockRunning))
                .containsExactlyInAnyOrder(OPEN, IN_PROGRESS);
    }

    private static void assertAllowed(TicketStatus from, Set<TicketStatus> expected) {
        for (TicketStatus target : TicketStatus.values()) {
            assertThat(from.canTransitionTo(target))
                    .as("%s -> %s", from, target)
                    .isEqualTo(expected.contains(target));
        }
    }
}
