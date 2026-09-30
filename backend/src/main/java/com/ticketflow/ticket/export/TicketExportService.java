package com.ticketflow.ticket.export;

import com.ticketflow.auth.AuthUser;
import com.ticketflow.common.PageResponse;
import com.ticketflow.ticket.TicketDtos.TicketResponse;
import com.ticketflow.ticket.TicketFilter;
import com.ticketflow.ticket.TicketService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * Exports the tickets the caller is allowed to see, with the same filters as the list. It goes through
 * {@link TicketService#search}, so the visibility rules (a requester only sees their own tickets) are the
 * same ones and cannot be bypassed here.
 */
@Service
public class TicketExportService {

    /** The file is built in memory on a small server: beyond this many rows the export is cut short. */
    public static final int MAX_ROWS = 5000;

    private final TicketService tickets;
    private final TicketExporter exporter;
    private final Clock clock;
    private final ZoneId zone;

    public TicketExportService(TicketService tickets, TicketExporter exporter, Clock clock,
            @Value("${app.zone}") ZoneId zone) {
        this.tickets = tickets;
        this.exporter = exporter;
        this.clock = clock;
        this.zone = zone;
    }

    /** The file plus whether more tickets matched than fit in it. */
    public record TicketExport(byte[] content, String filename, boolean truncated) {
    }

    public TicketExport export(TicketFilter filter, Sort sort, ExportFormat format, AuthUser authUser) {
        PageResponse<TicketResponse> page = tickets.search(filter, PageRequest.of(0, MAX_ROWS, sort), authUser);
        Instant now = clock.instant();
        byte[] content = exporter.write(format, page.content(), now);
        String filename = "chamados-" + LocalDate.ofInstant(now, zone) + "." + format.extension();
        return new TicketExport(content, filename, page.totalElements() > page.content().size());
    }
}
