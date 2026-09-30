package com.ticketflow.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.support.IntegrationTest;
import com.ticketflow.user.Role;
import com.ticketflow.user.User;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class TraceIdApiTest extends IntegrationTest {

    @Test
    void everyResponseCarriesAGeneratedTraceId() throws Exception {
        User ana = createUser("Ana", Role.REQUESTER);

        MvcResult result = mvc.perform(get("/api/auth/me").header("Authorization", bearer(ana)))
                .andExpect(status().isOk())
                .andReturn();

        String traceId = result.getResponse().getHeader(TraceIdFilter.HEADER);
        assertThat(traceId).isNotNull();
        assertThat(UUID.fromString(traceId)).isNotNull();
    }

    @Test
    void aSafeRequestIdFromTheCallerIsKept() throws Exception {
        User ana = createUser("Ana", Role.REQUESTER);

        mvc.perform(get("/api/auth/me").header("Authorization", bearer(ana)).header(TraceIdFilter.HEADER, "web-7f3a9c21"))
                .andExpect(header().string(TraceIdFilter.HEADER, "web-7f3a9c21"));
    }

    @Test
    void anUnsafeRequestIdIsReplaced() throws Exception {
        User ana = createUser("Ana", Role.REQUESTER);

        mvc.perform(get("/api/auth/me").header("Authorization", bearer(ana))
                        .header(TraceIdFilter.HEADER, "x\nFAKE LOG LINE"))
                .andExpect(header().string(TraceIdFilter.HEADER, not("x\nFAKE LOG LINE")));
    }

    @Test
    void errorBodiesRepeatTheTraceId() throws Exception {
        User ana = createUser("Ana", Role.REQUESTER);

        MvcResult validation = mvc.perform(post("/api/tickets").header("Authorization", bearer(ana))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(validation.getResponse().getContentAsString())
                .contains("\"traceId\":\"" + validation.getResponse().getHeader(TraceIdFilter.HEADER) + "\"");

        mvc.perform(get("/api/tickets/{id}", 999_999).header("Authorization", bearer(ana)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void unauthenticatedResponsesAreTraceableToo() throws Exception {
        MvcResult result = mvc.perform(get("/api/auth/me").header(TraceIdFilter.HEADER, "client-req-0001"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.traceId").value("client-req-0001"))
                .andReturn();

        assertThat(result.getResponse().getHeader(TraceIdFilter.HEADER)).isEqualTo("client-req-0001");
    }
}
