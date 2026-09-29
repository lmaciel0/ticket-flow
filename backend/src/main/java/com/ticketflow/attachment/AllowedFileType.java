package com.ticketflow.attachment;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Accepted file types. A file is accepted only if its extension is allowed AND its first bytes
 * ("magic bytes") match that type — renaming virus.exe to virus.pdf is not enough.
 */
public enum AllowedFileType {

    PDF(Set.of("pdf"), "application/pdf", new byte[] {'%', 'P', 'D', 'F'}),
    PNG(Set.of("png"), "image/png", new byte[] {(byte) 0x89, 'P', 'N', 'G'}),
    JPEG(Set.of("jpg", "jpeg"), "image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),
    DOCX(Set.of("docx"), "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            new byte[] {'P', 'K', 3, 4}),
    TXT(Set.of("txt"), "text/plain", new byte[0]);

    private final Set<String> extensions;
    private final String contentType;
    private final byte[] signature;

    AllowedFileType(Set<String> extensions, String contentType, byte[] signature) {
        this.extensions = extensions;
        this.contentType = contentType;
        this.signature = signature;
    }

    public String contentType() {
        return contentType;
    }

    public static Optional<AllowedFileType> detect(String filename, byte[] data) {
        String extension = extensionOf(filename);
        return Arrays.stream(values())
                .filter(type -> type.extensions.contains(extension))
                .filter(type -> type.matches(data))
                .findFirst();
    }

    private boolean matches(byte[] data) {
        if (data.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (data[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
