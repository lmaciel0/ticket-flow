package com.ticketflow.ticket;

import com.ticketflow.auth.AuthUser;
import com.ticketflow.common.ApiException;
import com.ticketflow.common.PageResponse;
import com.ticketflow.ticket.TicketDtos.AssignRequest;
import com.ticketflow.ticket.TicketDtos.ChangeStatusRequest;
import com.ticketflow.ticket.TicketDtos.CreateTicketRequest;
import com.ticketflow.ticket.TicketDtos.TicketResponse;
import com.ticketflow.ticket.TicketDtos.UpdateTicketRequest;
import com.ticketflow.ticket.export.ExportFormat;
import com.ticketflow.ticket.export.TicketExportService;
import com.ticketflow.ticket.export.TicketExportService.TicketExport;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private static final Set<String> SORTABLE_FIELDS = Set.of("dueAt", "createdAt");
    private static final int MAX_PAGE_SIZE = 100;
    /** Tells the client that more tickets matched than fit in the exported file. */
    public static final String TRUNCATED_HEADER = "X-Export-Truncated";

    private final TicketService tickets;
    private final TicketExportService exports;

    public TicketController(TicketService tickets, TicketExportService exports) {
        this.tickets = tickets;
        this.exports = exports;
    }

    @PostMapping
    public ResponseEntity<TicketResponse> create(@Valid @RequestBody CreateTicketRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return withETag(ResponseEntity.status(HttpStatus.CREATED), tickets.create(request, AuthUser.from(jwt)));
    }

    @GetMapping
    public PageResponse<TicketResponse> search(
            @RequestParam(required = false) List<TicketStatus> status,
            @RequestParam(required = false) Priority priority,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long assigneeId,
            @RequestParam(required = false) TicketFilter.SlaFilter sla,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean mine,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "dueAt,asc") String sort,
            @AuthenticationPrincipal Jwt jwt) {
        TicketFilter filter = new TicketFilter(status, priority, categoryId, assigneeId, sla, q, mine);
        return tickets.search(filter, pageable(page, size, sort), AuthUser.from(jwt));
    }

    /** Same filters as the list, but every match (up to a cap) in one file: csv, xlsx or pdf. */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam ExportFormat format,
            @RequestParam(required = false) List<TicketStatus> status,
            @RequestParam(required = false) Priority priority,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long assigneeId,
            @RequestParam(required = false) TicketFilter.SlaFilter sla,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean mine,
            @RequestParam(defaultValue = "dueAt,asc") String sort,
            @AuthenticationPrincipal Jwt jwt) {
        TicketFilter filter = new TicketFilter(status, priority, categoryId, assigneeId, sla, q, mine);
        TicketExport file = exports.export(filter, sortOf(sort), format, AuthUser.from(jwt));
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(format.mediaType())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.filename()).build().toString());
        if (file.truncated()) {
            response.header(TRUNCATED_HEADER, "true");
        }
        return response.body(file.content());
    }

    @GetMapping("/{id}")
    public ResponseEntity<TicketResponse> get(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return withETag(ResponseEntity.ok(), tickets.get(id, AuthUser.from(jwt)));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('AGENT', 'MANAGER')")
    public ResponseEntity<TicketResponse> update(@PathVariable Long id, @Valid @RequestBody UpdateTicketRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @AuthenticationPrincipal Jwt jwt) {
        return withETag(ResponseEntity.ok(),
                tickets.update(id, request, expectedVersion(ifMatch), AuthUser.from(jwt)));
    }

    @PostMapping("/{id}/assign")
    @PreAuthorize("hasAnyRole('AGENT', 'MANAGER')")
    public ResponseEntity<TicketResponse> assign(@PathVariable Long id, @Valid @RequestBody AssignRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @AuthenticationPrincipal Jwt jwt) {
        return withETag(ResponseEntity.ok(),
                tickets.assign(id, request, expectedVersion(ifMatch), AuthUser.from(jwt)));
    }

    @PostMapping("/{id}/status")
    public ResponseEntity<TicketResponse> changeStatus(@PathVariable Long id,
            @Valid @RequestBody ChangeStatusRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @AuthenticationPrincipal Jwt jwt) {
        return withETag(ResponseEntity.ok(),
                tickets.changeStatus(id, request, expectedVersion(ifMatch), AuthUser.from(jwt)));
    }

    /**
     * If-Match is required on every change: without it the API cannot tell whether the client saw the
     * latest version. Missing is 428 (RFC 6585); malformed is 400; a stale version is 412, in the service.
     * {@code required = false} is deliberate: with true, Spring would answer 400 on its own.
     */
    private static long expectedVersion(String ifMatch) {
        if (ifMatch == null) {
            throw ApiException.preconditionRequired(TicketETag.MISSING_IF_MATCH);
        }
        return TicketETag.parse(ifMatch);
    }

    private static ResponseEntity<TicketResponse> withETag(ResponseEntity.BodyBuilder response, TicketResponse ticket) {
        return response.eTag(TicketETag.format(ticket.version())).body(ticket);
    }

    /** Only whitelisted fields can be sorted; the id is a tie-breaker so pages never overlap. */
    private static Pageable pageable(int page, int size, String sort) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        return PageRequest.of(safePage, safeSize, sortOf(sort));
    }

    private static Sort sortOf(String sort) {
        String[] parts = sort.split(",");
        String field = parts[0].strip();
        if (!SORTABLE_FIELDS.contains(field)) {
            throw ApiException.badRequest("Ordenação inválida. Use dueAt ou createdAt.");
        }
        Sort.Direction direction = parts.length > 1
                ? Sort.Direction.fromOptionalString(parts[1].strip())
                        .orElseThrow(() -> ApiException.badRequest("Direção de ordenação inválida. Use asc ou desc."))
                : Sort.Direction.ASC;
        return Sort.by(direction, field).and(Sort.by("id"));
    }
}
