package com.ticketflow.sla;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Optional working-hours mode for the SLA, read from app.sla.business-hours. Disabled by default:
 * the clock then runs around the clock.
 */
@ConfigurationProperties("app.sla.business-hours")
public record BusinessHoursProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("08:00") LocalTime start,
        @DefaultValue("18:00") LocalTime end,
        @DefaultValue("MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY") Set<DayOfWeek> days,
        @DefaultValue Set<LocalDate> holidays) {
}
