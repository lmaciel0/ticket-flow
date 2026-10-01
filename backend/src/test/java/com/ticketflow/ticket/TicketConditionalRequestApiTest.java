package com.ticketflow.ticket;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.common.ApiExceptionHandler;
import com.ticketflow.support.IntegrationTest;
import com.ticketflow.user.Role;
import com.ticketflow.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The HTTP way of optimistic locking: ETag on the way out, If-Match on the way back in. */
class TicketConditionalRequestApiTest extends IntegrationTest {

    User ana;
    User eva;
    User bruno;
    long ticketId;

    @BeforeEach
    void setUp() throws Exception {
        ana = createUser("Ana", Role.REQUESTER);
        eva = createUser("Eva", Role.REQUESTER);
        bruno = createUser("Bruno", Role.AGENT);
        ticketId = createTicket(ana, "Impressora", "LOW");
    }

    /** Sends the request as the user, with If-Match only when a value is given. */
    ResultActions send(MockHttpServletRequestBuilder request, User actor, String ifMatch, String json)
            throws Exception {
        request.header("Authorization", bearer(actor)).contentType(MediaType.APPLICATION_JSON).content(json);
        if (ifMatch != null) {
            request.header(HttpHeaders.IF_MATCH, ifMatch);
        }
        return mvc.perform(request);
    }

    ResultActions assign(User actor, String ifMatch) throws Exception {
        return send(post("/api/tickets/{id}/assign", ticketId), actor, ifMatch,
                "{\"assigneeId\": %d}".formatted(bruno.getId()));
    }

    ResultActions changeStatus(User actor, String ifMatch, String status) throws Exception {
        return send(post("/api/tickets/{id}/status", ticketId), actor, ifMatch,
                "{\"status\": \"%s\"}".formatted(status));
    }

    ResultActions patchPriority(User actor, String ifMatch, String priority) throws Exception {
        return send(patch("/api/tickets/{id}", ticketId), actor, ifMatch,
                "{\"priority\": \"%s\"}".formatted(priority));
    }

    @Test
    void creatingAndReadingAnswerWithTheVersionAsETag() throws Exception {
        mvc.perform(post("/api/tickets")
                        .header("Authorization", bearer(ana))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Teclado", "description": "Teclas falhando", "priority": "LOW",
                                 "categoryId": %d}
                                """.formatted(categoryId("Hardware"))))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""));

        mvc.perform(get("/api/tickets/{id}", ticketId).header("Authorization", bearer(ana)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""));
    }

    @Test
    void everyChangeAnswersWithTheNewETag() throws Exception {
        assign(bruno, etag(0)).andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ETAG, "\"1\""));
        patchPriority(bruno, etag(1), "HIGH")
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ETAG, "\"2\""));
        changeStatus(bruno, etag(2), "RESOLVED")
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ETAG, "\"3\""));
    }

    @Test
    void aChangeThatChangesNothingKeepsTheSameETag() throws Exception {
        assign(bruno, etag(0)).andExpect(status().isOk());

        patchPriority(bruno, etag(1), "LOW")
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ETAG, "\"1\""));
    }

    @Test
    void changesWithoutIfMatchArePreconditionRequired() throws Exception {
        assign(bruno, null)
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.detail").value(TicketETag.MISSING_IF_MATCH));
        changeStatus(bruno, null, "RESOLVED").andExpect(status().isPreconditionRequired());
        patchPriority(bruno, null, "HIGH").andExpect(status().isPreconditionRequired());
    }

    @Test
    void malformedIfMatchIsBadRequest() throws Exception {
        for (String invalid : new String[] {"0", "W/\"0\"", "*", "\"0\", \"1\""}) {
            assign(bruno, invalid)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(TicketETag.INVALID_IF_MATCH));
        }
    }

    @Test
    void staleIfMatchIsPreconditionFailedAndChangesNothing() throws Exception {
        assign(bruno, etag(0)).andExpect(status().isOk());

        changeStatus(bruno, etag(0), "RESOLVED")
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.detail").value(ApiExceptionHandler.STALE_VERSION));

        mvc.perform(get("/api/tickets/{id}", ticketId).header("Authorization", bearer(bruno)))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""));
        mvc.perform(get("/api/tickets/{id}/history", ticketId).header("Authorization", bearer(bruno)))
                .andExpect(jsonPath("$.length()").value(3)); // CREATED, ASSIGNED, STATUS_CHANGED
    }

    @Test
    void aRequesterWithoutAccessGets404EvenWithAStaleIfMatch() throws Exception {
        changeStatus(eva, etag(9), "CLOSED").andExpect(status().isNotFound());
    }

    @Test
    void theApiDocsSayIfMatchIsRequiredAndList412And428() throws Exception {
        String[][] mutations = {{"/api/tickets/{id}", "patch"}, {"/api/tickets/{id}/assign", "post"},
                {"/api/tickets/{id}/status", "post"}};
        for (String[] mutation : mutations) {
            String operation = "$.paths['%s'].%s".formatted(mutation[0], mutation[1]);
            mvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(operation + ".parameters[?(@.name == 'If-Match')].required", hasItem(true)))
                    .andExpect(jsonPath(operation + ".responses['200']").exists())
                    .andExpect(jsonPath(operation + ".responses['412']").exists())
                    .andExpect(jsonPath(operation + ".responses['428']").exists());
        }
    }

    @Test
    void readingWithAMatchingIfNoneMatchIsNotModified() throws Exception {
        // Not something we built: Spring MVC answers 304 by itself once the response has an ETag.
        mvc.perform(get("/api/tickets/{id}", ticketId)
                        .header("Authorization", bearer(ana))
                        .header(HttpHeaders.IF_NONE_MATCH, etag(0)))
                .andExpect(status().isNotModified());
    }
}
