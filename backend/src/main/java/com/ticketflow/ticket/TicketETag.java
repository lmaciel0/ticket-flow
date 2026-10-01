package com.ticketflow.ticket;

import com.ticketflow.common.ApiException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The ticket's version as an HTTP entity tag. GET answers {@code ETag: "3"}; a change sends it back
 * in {@code If-Match: "3"}, meaning "only if the ticket is still version 3". Only one strong tag is
 * accepted: If-Match compares strongly, so a weak tag (W/"3") could never match.
 */
public final class TicketETag {

    public static final String MISSING_IF_MATCH = "Envie o cabeçalho If-Match com o ETag do chamado.";
    public static final String INVALID_IF_MATCH = "If-Match inválido. Envie o ETag recebido, por exemplo \"3\".";

    /** Up to 18 digits always fits in a long. */
    private static final Pattern STRONG_NUMERIC = Pattern.compile("\"(\\d{1,18})\"");

    private TicketETag() {
    }

    public static String format(long version) {
        return "\"" + version + "\"";
    }

    public static long parse(String ifMatch) {
        Matcher matcher = STRONG_NUMERIC.matcher(ifMatch.strip());
        if (!matcher.matches()) {
            throw ApiException.badRequest(INVALID_IF_MATCH);
        }
        return Long.parseLong(matcher.group(1));
    }
}
