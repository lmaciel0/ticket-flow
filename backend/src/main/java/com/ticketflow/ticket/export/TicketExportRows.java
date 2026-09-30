package com.ticketflow.ticket.export;

import com.ticketflow.sla.SlaIndicator;
import com.ticketflow.ticket.Priority;
import com.ticketflow.ticket.TicketDtos.TicketResponse;
import com.ticketflow.ticket.TicketStatus;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** What every export format shows for a ticket: the same Portuguese labels the screen uses. */
final class TicketExportRows {

    static final List<String> HEADERS = List.of("Nº", "Título", "Status", "Prioridade", "Categoria", "Solicitante",
            "Responsável", "Aberto em", "Prazo", "Resolvido em", "SLA");

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private TicketExportRows() {
    }

    static List<String> row(TicketResponse ticket, ZoneId zone) {
        return List.of(
                String.valueOf(ticket.id()),
                ticket.title(),
                statusLabel(ticket.status()),
                priorityLabel(ticket.priority()),
                ticket.category().name(),
                ticket.requester().name(),
                ticket.assignee() == null ? "" : ticket.assignee().name(),
                dateTime(ticket.createdAt(), zone),
                dateTime(ticket.dueAt(), zone),
                dateTime(ticket.resolvedAt(), zone),
                slaLabel(ticket.sla()));
    }

    static String dateTime(Instant instant, ZoneId zone) {
        return instant == null ? "" : DATE_TIME.format(instant.atZone(zone));
    }

    static String statusLabel(TicketStatus status) {
        return switch (status) {
            case OPEN -> "Aberto";
            case IN_PROGRESS -> "Em atendimento";
            case WAITING_REQUESTER -> "Aguardando solicitante";
            case RESOLVED -> "Resolvido";
            case CLOSED -> "Fechado";
        };
    }

    static String priorityLabel(Priority priority) {
        return switch (priority) {
            case LOW -> "Baixa";
            case MEDIUM -> "Média";
            case HIGH -> "Alta";
            case CRITICAL -> "Crítica";
        };
    }

    static String slaLabel(SlaIndicator sla) {
        return switch (sla) {
            case ON_TRACK -> "No prazo";
            case AT_RISK -> "Em risco";
            case OVERDUE -> "Vencido";
            case PAUSED -> "Pausado";
            case MET -> "Cumprido";
            case BREACHED -> "Violado";
        };
    }
}
