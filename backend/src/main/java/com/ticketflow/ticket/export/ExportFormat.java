package com.ticketflow.ticket.export;

import org.springframework.http.MediaType;

/** File formats of GET /api/tickets/export. */
public enum ExportFormat {
    CSV("csv", MediaType.parseMediaType("text/csv;charset=UTF-8")),
    XLSX("xlsx", MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")),
    PDF("pdf", MediaType.APPLICATION_PDF);

    private final String extension;
    private final MediaType mediaType;

    ExportFormat(String extension, MediaType mediaType) {
        this.extension = extension;
        this.mediaType = mediaType;
    }

    public String extension() {
        return extension;
    }

    public MediaType mediaType() {
        return mediaType;
    }
}
