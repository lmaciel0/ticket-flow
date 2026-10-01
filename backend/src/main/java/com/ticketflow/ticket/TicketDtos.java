package com.ticketflow.ticket;

import com.ticketflow.category.CategoryResponse;
import com.ticketflow.sla.SlaIndicator;
import com.ticketflow.user.UserSummary;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class TicketDtos {

    private TicketDtos() {
    }

    public record CreateTicketRequest(
            @NotBlank(message = "Informe o título.") @Size(max = 120, message = "Título muito longo.") String title,
            @NotBlank(message = "Descreva o problema.")
            @Size(max = 5000, message = "Descrição muito longa.") String description,
            @NotNull(message = "Informe a prioridade.") Priority priority,
            @NotNull(message = "Informe a categoria.") Long categoryId) {
    }

    /** Fields left null are not changed. The expected version travels in the If-Match header. */
    public record UpdateTicketRequest(Priority priority, Long categoryId) {
    }

    public record AssignRequest(@NotNull(message = "Informe o responsável.") Long assigneeId) {
    }

    public record ChangeStatusRequest(@NotNull(message = "Informe o status.") TicketStatus status) {
    }

    public record TicketResponse(
            Long id,
            String title,
            String description,
            Priority priority,
            TicketStatus status,
            CategoryResponse category,
            UserSummary requester,
            UserSummary assignee,
            Instant createdAt,
            Instant dueAt,
            Instant resolvedAt,
            Boolean slaBreached,
            SlaIndicator sla,
            long version) {

        public static TicketResponse from(Ticket ticket, SlaIndicator sla) {
            return new TicketResponse(ticket.getId(), ticket.getTitle(), ticket.getDescription(),
                    ticket.getPriority(), ticket.getStatus(), CategoryResponse.from(ticket.getCategory()),
                    UserSummary.from(ticket.getRequester()), UserSummary.from(ticket.getAssignee()),
                    ticket.getCreatedAt(), ticket.getDueAt(), ticket.getResolvedAt(), ticket.getSlaBreached(),
                    sla, ticket.getVersion());
        }
    }
}
