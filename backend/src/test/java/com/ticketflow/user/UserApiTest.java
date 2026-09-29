package com.ticketflow.user;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class UserApiTest extends IntegrationTest {

    User ana;
    User bruno;
    User carla;

    @BeforeEach
    void users() {
        ana = createUser("Ana", Role.REQUESTER);
        bruno = createUser("Bruno", Role.AGENT);
        carla = createUser("Carla", Role.MANAGER);
    }

    ResultActions update(User actor, User target, String json) throws Exception {
        return mvc.perform(patch("/api/users/{id}", target.getId())
                .header("Authorization", bearer(actor))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    @Test
    void managerListsUsersOthersCannot() throws Exception {
        mvc.perform(get("/api/users").header("Authorization", bearer(carla)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].name", contains("Ana", "Bruno", "Carla")));
        mvc.perform(get("/api/users").header("Authorization", bearer(bruno))).andExpect(status().isForbidden());
    }

    @Test
    void assignableListsActiveAgentsAndManagers() throws Exception {
        User inactive = createUser("Diego", Role.AGENT);
        inactive.setActive(false);
        userRepository.save(inactive);

        mvc.perform(get("/api/users/assignable").header("Authorization", bearer(bruno)))
                .andExpect(jsonPath("$[*].name", contains("Bruno", "Carla")));
        mvc.perform(get("/api/users/assignable").header("Authorization", bearer(ana)))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerPromotesAndDeactivates() throws Exception {
        update(carla, ana, "{\"role\": \"AGENT\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("AGENT"));
        update(carla, ana, "{\"active\": false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        update(bruno, ana, "{\"active\": true}").andExpect(status().isForbidden());
    }

    @Test
    void demoAccountsAndOwnAccountAreProtected() throws Exception {
        bruno.markAsDemo();
        userRepository.save(bruno);

        update(carla, bruno, "{\"active\": false}").andExpect(status().isConflict());
        update(carla, carla, "{\"role\": \"REQUESTER\"}").andExpect(status().isConflict());
    }

    @Test
    void cannotDemoteOrDeactivateSomeoneWithTicketsInProgress() throws Exception {
        long ticketId = createTicket(ana, "Impressora", "LOW");
        mvc.perform(post("/api/tickets/{id}/assign", ticketId)
                        .header("Authorization", bearer(bruno))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\": %d, \"version\": 0}".formatted(bruno.getId())))
                .andExpect(status().isOk());

        update(carla, bruno, "{\"role\": \"REQUESTER\"}").andExpect(status().isConflict());
        update(carla, bruno, "{\"active\": false}").andExpect(status().isConflict());
        update(carla, bruno, "{\"role\": \"MANAGER\"}").andExpect(status().isOk());
    }

    @Test
    void unknownUserIs404() throws Exception {
        mvc.perform(patch("/api/users/{id}", 999)
                        .header("Authorization", bearer(carla))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\": false}"))
                .andExpect(status().isNotFound());
    }
}
