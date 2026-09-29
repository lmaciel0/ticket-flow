package com.ticketflow.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AllowedFileTypeTest {

    static final byte[] PDF_BYTES = "%PDF-1.7 conteudo".getBytes(StandardCharsets.US_ASCII);
    static final byte[] PNG_BYTES = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A};

    @Test
    void acceptsMatchingExtensionAndSignature() {
        assertThat(AllowedFileType.detect("relatorio.PDF", PDF_BYTES)).contains(AllowedFileType.PDF);
        assertThat(AllowedFileType.detect("tela.png", PNG_BYTES)).contains(AllowedFileType.PNG);
        assertThat(AllowedFileType.detect("foto.jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1}))
                .contains(AllowedFileType.JPEG);
        assertThat(AllowedFileType.detect("doc.docx", new byte[] {'P', 'K', 3, 4, 20})).contains(AllowedFileType.DOCX);
        assertThat(AllowedFileType.detect("log.txt", "qualquer texto".getBytes(StandardCharsets.UTF_8)))
                .contains(AllowedFileType.TXT);
    }

    @Test
    void rejectsRenamedFiles() {
        assertThat(AllowedFileType.detect("virus.pdf", "MZ executable".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(AllowedFileType.detect("tela.png", PDF_BYTES)).isEmpty();
    }

    @Test
    void rejectsUnknownOrMissingExtension() {
        assertThat(AllowedFileType.detect("script.exe", PDF_BYTES)).isEmpty();
        assertThat(AllowedFileType.detect("sem-extensao", PDF_BYTES)).isEmpty();
    }

    @Test
    void rejectsFilesShorterThanTheSignature() {
        assertThat(AllowedFileType.detect("a.pdf", new byte[] {'%', 'P'})).isEmpty();
    }
}
