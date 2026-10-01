package com.ticketflow.sla;

import com.ticketflow.ticket.Priority;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** SLA deadlines per priority, read from app.sla.deadlines and app.sla.first-response in application.yml. */
@ConfigurationProperties("app.sla")
public record SlaProperties(Map<Priority, Duration> deadlines, Map<Priority, Duration> firstResponse) {

    public SlaProperties {
        deadlines = complete(deadlines, "SLA deadline");
        firstResponse = complete(firstResponse, "first response deadline");
    }

    private static Map<Priority, Duration> complete(Map<Priority, Duration> table, String what) {
        for (Priority priority : Priority.values()) {
            if (table == null || !table.containsKey(priority)) {
                throw new IllegalStateException("Missing " + what + " for priority " + priority);
            }
        }
        return Map.copyOf(table);
    }

    public Duration deadlineFor(Priority priority) {
        return deadlines.get(priority);
    }

    public Duration firstResponseFor(Priority priority) {
        return firstResponse.get(priority);
    }
}
