package com.ticketflow.category;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.support.IntegrationTest;
import com.ticketflow.user.Role;
import org.junit.jupiter.api.Test;

class CategoryApiTest extends IntegrationTest {

    @Test
    void listsSeededCategoriesInCreationOrder() throws Exception {
        String token = bearer(createUser("Ana", Role.REQUESTER));

        mvc.perform(get("/api/categories").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].name").value("Acesso"))
                .andExpect(jsonPath("$[4].name").value("Outros"));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/api/categories")).andExpect(status().isUnauthorized());
    }
}
