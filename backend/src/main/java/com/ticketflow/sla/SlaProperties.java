package com.ticketflow.sla;

import com.ticketflow.ticket.Priority;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** SLA deadlines per priority, read from app.sla.deadlines in application.yml. */
@ConfigurationProperties("app.sla")
public record SlaProperties(Map<Priority, Duration> deadlines) {

    public SlaProperties {
        for (Priority priority : Priority.values()) {
            if (deadlines == null || !deadlines.containsKey(priority)) {
                throw new IllegalStateException("Missing SLA deadline for priority " + priority);
            }
        }
        deadlines = Map.copyOf(deadlines);
    }

    public Duration deadlineFor(Priority priority) {
        return deadlines.get(priority);
    }
}
