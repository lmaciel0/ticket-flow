package com.ticketflow.dashboard;

import com.ticketflow.common.ApiException;
import com.ticketflow.dashboard.DashboardResponse.AgentStats;
import com.ticketflow.dashboard.DashboardResponse.CategoryCount;
import com.ticketflow.dashboard.DashboardResponse.DailyCount;
import com.ticketflow.ticket.TicketStatus;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Report queries in plain SQL: aggregations are what SQL does best. */
@Service
@Transactional(readOnly = true)
public class DashboardService {

    static final int DEFAULT_DAYS = 30;
    static final int MAX_DAYS = 90;

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    private final ZoneId zone;

    public DashboardService(NamedParameterJdbcTemplate jdbc, Clock clock, @Value("${app.zone}") ZoneId zone) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.zone = zone;
    }

    public DashboardResponse build(LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.ofInstant(clock.instant(), zone);
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_DAYS - 1);
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (days < 1 || days > MAX_DAYS) {
            throw ApiException.badRequest("Período inválido: use de 1 a " + MAX_DAYS + " dias, com início antes do fim.");
        }

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("from", toTimestamp(start.atStartOfDay(zone).toInstant()))
                .addValue("to", toTimestamp(end.plusDays(1).atStartOfDay(zone).toInstant()))
                .addValue("now", toTimestamp(clock.instant()))
                .addValue("zone", zone.getId());

        Map<String, Object> resolved = jdbc.queryForMap("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE NOT sla_breached) AS met,
                       avg(extract(EPOCH FROM (resolved_at - created_at))) / 3600 AS avg_hours
                FROM tickets
                WHERE resolved_at >= :from AND resolved_at < :to
                """, params);
        long resolvedTotal = ((Number) resolved.get("total")).longValue();
        long resolvedMet = ((Number) resolved.get("met")).longValue();
        Number avgHours = (Number) resolved.get("avg_hours");

        return new DashboardResponse(
                start,
                end,
                ticketsByStatus(),
                jdbc.queryForObject("""
                        SELECT count(*) FROM tickets
                        WHERE status IN ('OPEN', 'IN_PROGRESS') AND due_at <= :now
                        """, params, Long.class),
                resolvedTotal,
                resolvedTotal == 0 ? null : round(100.0 * resolvedMet / resolvedTotal),
                avgHours == null ? null : round(avgHours.doubleValue()),
                openedByCategory(params),
                agents(params),
                daily(params, start, end));
    }

    private Map<TicketStatus, Long> ticketsByStatus() {
        Map<TicketStatus, Long> counts = new EnumMap<>(TicketStatus.class);
        for (TicketStatus status : TicketStatus.values()) {
            counts.put(status, 0L);
        }
        jdbc.query("SELECT status, count(*) AS total FROM tickets GROUP BY status", rs -> {
            counts.put(TicketStatus.valueOf(rs.getString("status")), rs.getLong("total"));
        });
        return counts;
    }

    private List<CategoryCount> openedByCategory(MapSqlParameterSource params) {
        return jdbc.query("""
                SELECT c.name, count(t.id) AS total
                FROM categories c
                LEFT JOIN tickets t ON t.category_id = c.id AND t.created_at >= :from AND t.created_at < :to
                GROUP BY c.id, c.name
                ORDER BY c.id
                """, params, (rs, row) -> new CategoryCount(rs.getString("name"), rs.getLong("total")));
    }

    private List<AgentStats> agents(MapSqlParameterSource params) {
        return jdbc.query("""
                SELECT u.id, u.name,
                       count(t.id) FILTER (WHERE t.status IN ('OPEN', 'IN_PROGRESS', 'WAITING_REQUESTER')) AS active,
                       count(t.id) FILTER (WHERE t.resolved_at >= :from AND t.resolved_at < :to) AS resolved
                FROM users u
                LEFT JOIN tickets t ON t.assignee_id = u.id
                WHERE u.active AND u.role IN ('AGENT', 'MANAGER')
                GROUP BY u.id, u.name
                ORDER BY u.name
                """, params, (rs, row) -> new AgentStats(
                rs.getLong("id"), rs.getString("name"), rs.getLong("active"), rs.getLong("resolved")));
    }

    /** One entry per day of the period, including days with zero tickets. */
    private List<DailyCount> daily(MapSqlParameterSource params, LocalDate start, LocalDate end) {
        Map<LocalDate, Long> opened = countPerDay("created_at", params);
        Map<LocalDate, Long> resolved = countPerDay("resolved_at", params);
        List<DailyCount> days = new ArrayList<>();
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            days.add(new DailyCount(day, opened.getOrDefault(day, 0L), resolved.getOrDefault(day, 0L)));
        }
        return days;
    }

    private Map<LocalDate, Long> countPerDay(String column, MapSqlParameterSource params) {
        // column is one of two constants above, never user input.
        String sql = """
                SELECT CAST(%1$s AT TIME ZONE :zone AS DATE) AS day, count(*) AS total
                FROM tickets
                WHERE %1$s >= :from AND %1$s < :to
                GROUP BY day
                """.formatted(column);
        Map<LocalDate, Long> counts = new HashMap<>();
        jdbc.query(sql, params, rs -> {
            counts.put(rs.getObject("day", LocalDate.class), rs.getLong("total"));
        });
        return counts;
    }

    private static Timestamp toTimestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
