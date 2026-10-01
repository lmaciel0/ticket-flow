package com.ticketflow.ticket.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.support.IntegrationTest;
import com.ticketflow.user.Role;
import com.ticketflow.user.User;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;

class TicketExportApiTest extends IntegrationTest {

    User ana;
    User eva;
    User bruno;

    @BeforeEach
    void setUp() throws Exception {
        ana = createUser("Ana", Role.REQUESTER);
        eva = createUser("Eva", Role.REQUESTER);
        bruno = createUser("Bruno", Role.AGENT);
        createTicket(ana, "Impressora sem papel", "LOW");
        createTicket(ana, "=HYPERLINK(\\\"http://evil.test\\\")", "HIGH");
        createTicket(eva, "Teclado quebrado", "CRITICAL");
    }

    MvcResult export(User caller, String query) throws Exception {
        return mvc.perform(get("/api/tickets/export?" + query).header("Authorization", bearer(caller)))
                .andExpect(status().isOk())
                .andReturn();
    }

    static String body(MvcResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    void csvHasBomHeaderLabelsAndOneLinePerTicket() throws Exception {
        MvcResult result = export(bruno, "format=CSV");

        assertThat(result.getResponse().getContentType()).startsWith("text/csv");
        assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .startsWith("attachment; filename=\"chamados-")
                .endsWith(".csv\"");
        String csv = body(result);
        assertThat(csv).startsWith("﻿Nº;Título;Status;Prioridade;Categoria;Solicitante;Responsável;");
        assertThat(csv).contains("Impressora sem papel;Aberto;Baixa;Hardware;Ana;").contains("Teclado quebrado");
        assertThat(csv.lines().count()).isEqualTo(4); // header + 3 tickets
    }

    @Test
    void csvShowsTheFirstResponseIndicator() throws Exception {
        String csv = body(export(bruno, "format=CSV"));

        assertThat(csv.lines().findFirst().orElseThrow()).endsWith(";SLA;1ª resposta");
        // Nobody took the new tickets yet, so the first response is still pending.
        assertThat(csv.lines().filter(line -> line.contains("Impressora sem papel")).findFirst().orElseThrow())
                .endsWith(";Pendente");
    }

    @Test
    void requesterOnlyExportsTheirOwnTickets() throws Exception {
        String csv = body(export(ana, "format=CSV"));

        assertThat(csv).contains("Impressora sem papel").doesNotContain("Teclado quebrado");
    }

    @Test
    void exportAppliesTheSameFiltersAsTheList() throws Exception {
        String csv = body(export(bruno, "format=CSV&priority=CRITICAL"));

        assertThat(csv).contains("Teclado quebrado").doesNotContain("Impressora sem papel");
    }

    @Test
    void titlesThatLookLikeFormulasAreNeutralizedInCsv() throws Exception {
        String csv = body(export(bruno, "format=CSV"));

        assertThat(csv).contains("\"'=HYPERLINK(").doesNotContain(";=HYPERLINK");
    }

    @Test
    void xlsxIsAWorkbookWithTheTicketsAsTextNotFormulas() throws Exception {
        MvcResult result = export(bruno, "format=XLSX");

        assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION)).endsWith(".xlsx\"");
        String sheet = readZipEntry(result.getResponse().getContentAsByteArray(), "xl/worksheets/sheet1.xml");
        String strings = readZipEntry(result.getResponse().getContentAsByteArray(), "xl/sharedStrings.xml");
        assertThat(sheet + strings).contains("Teclado quebrado").contains("Impressora sem papel");
        assertThat(sheet).doesNotContain("<f>");
    }

    @Test
    void pdfIsAPdfDocument() throws Exception {
        MvcResult result = export(bruno, "format=PDF");

        assertThat(result.getResponse().getContentType()).isEqualTo("application/pdf");
        byte[] pdf = result.getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(pdf.length).isGreaterThan(1000);
    }

    @Test
    void unknownOrMissingFormatIsRejected() throws Exception {
        mvc.perform(get("/api/tickets/export?format=DOCX").header("Authorization", bearer(bruno)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/tickets/export").header("Authorization", bearer(bruno)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exportRequiresLogin() throws Exception {
        mvc.perform(get("/api/tickets/export?format=CSV")).andExpect(status().isUnauthorized());
    }

    /** ZipFile (central directory) instead of ZipInputStream: the workbook is written as a stream. */
    private static String readZipEntry(byte[] zip, String name) throws IOException {
        Path file = Files.createTempFile("export", ".xlsx");
        try {
            Files.write(file, zip);
            try (ZipFile archive = new ZipFile(file.toFile())) {
                ZipEntry entry = archive.getEntry(name);
                return entry == null ? "" : new String(archive.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
            }
        } finally {
            Files.delete(file);
        }
    }
}
