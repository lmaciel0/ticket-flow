package com.ticketflow.ticket.export;

import com.ticketflow.ticket.TicketDtos.TicketResponse;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Writes a list of tickets as CSV, XLSX or PDF. The bytes are built in memory, so the caller caps the list. */
@Component
public class TicketExporter {

    /** Excel in Portuguese (Brazil) splits columns on a semicolon, not on a comma. */
    private static final char SEPARATOR = ';';
    /** The PDF leaves some columns out to fit the page. */
    private static final int[] PDF_COLUMNS = {0, 1, 2, 3, 4, 6, 8, 10};
    private static final float[] PDF_WIDTHS = {0.6f, 3.4f, 1.6f, 1.1f, 1.4f, 1.5f, 1.1f, 1.1f};
    private static final Color HEADER_BACKGROUND = new Color(0xD0, 0xEB, 0xFF);

    private final ZoneId zone;

    public TicketExporter(@Value("${app.zone}") ZoneId zone) {
        this.zone = zone;
    }

    public byte[] write(ExportFormat format, List<TicketResponse> tickets, Instant generatedAt) {
        return switch (format) {
            case CSV -> csv(tickets);
            case XLSX -> xlsx(tickets);
            case PDF -> pdf(tickets, generatedAt);
        };
    }

    private byte[] csv(List<TicketResponse> tickets) {
        // The BOM makes Excel read the accents as UTF-8 instead of Latin-1.
        StringBuilder out = new StringBuilder("﻿");
        appendCsvLine(out, TicketExportRows.HEADERS);
        for (TicketResponse ticket : tickets) {
            appendCsvLine(out, TicketExportRows.row(ticket, zone));
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendCsvLine(StringBuilder out, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                out.append(SEPARATOR);
            }
            out.append(csvCell(cells.get(i)));
        }
        out.append("\r\n");
    }

    /**
     * Quotes the cell when needed and defuses spreadsheet formulas: a title such as "=HYPERLINK(...)" typed by
     * a requester would otherwise run when a manager opens the file in Excel (CSV injection).
     */
    static String csvCell(String value) {
        String safe = isFormulaStart(value) ? "'" + value : value;
        boolean needsQuotes = safe.indexOf(SEPARATOR) >= 0 || safe.indexOf('"') >= 0
                || safe.indexOf('\n') >= 0 || safe.indexOf('\r') >= 0;
        return needsQuotes ? '"' + safe.replace("\"", "\"\"") + '"' : safe;
    }

    private static boolean isFormulaStart(String value) {
        return !value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0;
    }

    private byte[] xlsx(List<TicketResponse> tickets) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (Workbook workbook = new Workbook(bytes, "ticket-flow", null)) {
            Worksheet sheet = workbook.newWorksheet("Chamados");
            List<String> headers = TicketExportRows.HEADERS;
            for (int column = 0; column < headers.size(); column++) {
                sheet.value(0, column, headers.get(column));
                sheet.style(0, column).bold().fillColor("D0EBFF").set();
            }
            int row = 1;
            for (TicketResponse ticket : tickets) {
                List<String> cells = TicketExportRows.row(ticket, zone);
                sheet.value(row, 0, ticket.id());
                for (int column = 1; column < cells.size(); column++) {
                    // Always a text cell: a title starting with "=" is stored as text, never as a formula.
                    sheet.value(row, column, cells.get(column));
                }
                row++;
            }
            sheet.width(0, 8);
            sheet.width(1, 50);
            for (int column = 2; column < headers.size(); column++) {
                sheet.width(column, 22);
            }
            sheet.freezePane(0, 1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private byte[] pdf(List<TicketResponse> tickets, Instant generatedAt) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4.rotate(), 28, 28, 28, 28);
        try {
            PdfWriter.getInstance(document, bytes);
            document.open();
            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14);
            Font smallFont = FontFactory.getFont(FontFactory.HELVETICA, 8);
            Font headerFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8);

            document.add(new Paragraph("Chamados", titleFont));
            Paragraph subtitle = new Paragraph(tickets.size() + " chamado(s) · gerado em "
                    + TicketExportRows.dateTime(generatedAt, zone), smallFont);
            subtitle.setSpacingAfter(8);
            document.add(subtitle);

            PdfPTable table = new PdfPTable(PDF_WIDTHS);
            table.setWidthPercentage(100);
            table.setHeaderRows(1);
            for (int column : PDF_COLUMNS) {
                PdfPCell header = new PdfPCell(new Phrase(TicketExportRows.HEADERS.get(column), headerFont));
                header.setBackgroundColor(HEADER_BACKGROUND);
                table.addCell(header);
            }
            for (TicketResponse ticket : tickets) {
                List<String> cells = TicketExportRows.row(ticket, zone);
                for (int column : PDF_COLUMNS) {
                    table.addCell(new PdfPCell(new Phrase(cells.get(column), smallFont)));
                }
            }
            document.add(table);
        } catch (DocumentException e) {
            throw new IllegalStateException("Could not build the PDF", e);
        } finally {
            document.close();
        }
        return bytes.toByteArray();
    }
}
