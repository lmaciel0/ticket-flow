package com.ticketflow.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketflow.common.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

class TicketETagTest {

    @Test
    void formatsTheVersionAsAStrongETag() {
        assertThat(TicketETag.format(3)).isEqualTo("\"3\"");
    }

    @Test
    void parsesAStrongETagEvenWithSurroundingSpaces() {
        assertThat(TicketETag.parse("\"3\"")).isEqualTo(3);
        assertThat(TicketETag.parse("  \"12\"  ")).isEqualTo(12);
    }

    @ParameterizedTest
    @ValueSource(strings = {"3", "W/\"3\"", "*", "\"3\", \"4\"", "\"abc\"", "\"\"", "", "\"99999999999999999999\""})
    void refusesAnythingButOneStrongNumericETag(String ifMatch) {
        assertThatThrownBy(() -> TicketETag.parse(ifMatch))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getMessage()).isEqualTo(TicketETag.INVALID_IF_MATCH);
                });
    }

    @Test
    void preconditionErrorsCarryTheirHttpStatus() {
        assertThat(ApiException.preconditionFailed("x").getStatus()).isEqualTo(HttpStatus.PRECONDITION_FAILED);
        assertThat(ApiException.preconditionRequired("x").getStatus()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
    }
}
