# Eventos de domínio para o histórico — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fazer `TicketService`, `CommentService` e `AttachmentService` publicarem eventos de domínio em vez de chamarem `HistoryRecorder`, com o histórico virando apenas um ouvinte.

**Architecture:** Uma `sealed interface HistoryEvent` com 7 records em `com.ticketflow.history.event`. Os services publicam com `ApplicationEventPublisher`. `HistoryEventListener` escuta com `@TransactionalEventListener(phase = BEFORE_COMMIT)` e delega ao `HistoryRecorder` existente, mantendo o histórico na mesma transação da mudança.

**Tech Stack:** Java 21, Spring Boot (eventos + `@TransactionalEventListener`), JUnit 5, AssertJ, Testcontainers (PostgreSQL), Maven Wrapper.

**Spec:** `docs/specs/2026-10-01-domain-events-design.md`

## Global Constraints

- Código, identificadores e nomes de testes em **inglês**; mensagens da API e explicações em **português**.
- A API não muda: os testes existentes (`TicketWorkflowApiTest`, `TicketUpdateApiTest`, `CommentApiTest`, `AttachmentApiTest`) passam **sem nenhuma alteração**.
- `HistoryRecorder`, `TicketHistory`, `HistoryEventType` e `DemoDataSeeder` **não mudam** (só o Javadoc do `HistoryRecorder`, na Tarefa 3).
- O histórico continua na **mesma transação** da mudança: `phase = BEFORE_COMMIT`, nunca `AFTER_COMMIT`.
- Nota interna de comentário **não** publica evento (o histórico é visível ao solicitante).
- Commits pequenos, um por tarefa, mensagens no estilo do repositório (`feat:`, `refactor:`, `docs:`) terminando com a linha `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.
- Comandos Maven rodam dentro de `backend/` com `./mvnw` (não há Maven instalado). Docker precisa estar rodando (Testcontainers).

## Review Focus

1. Evento publicado fora de transação é descartado em silêncio pelo `@TransactionalEventListener`. Todos os pontos de publicação são `@Transactional`; o teste da Tarefa 2 confere que o chamado criado pela API realmente deixa a linha `CREATED` no histórico.
2. Rollback deve levar a entrada de histórico junto (teste de atomicidade, Tarefa 1).
3. Nota interna não deixa rastro: nenhum evento e nenhuma linha de histórico (Tarefa 3).
4. Atribuir a quem já é o responsável, ou editar com valores iguais aos atuais, não publica evento nenhum (Tarefa 2).
5. Ordem das entradas: `ASSIGNED` antes de `STATUS_CHANGED` na atribuição, e `COMMENT_ADDED` antes de `STATUS_CHANGED` quando a resposta do solicitante retoma o chamado (Tarefas 2 e 3).

## Mapa de arquivos

| Arquivo | Ação | Responsabilidade |
|---|---|---|
| `backend/src/main/java/com/ticketflow/history/event/HistoryEvent.java` | criar | Interface selada: `ticket()`, `actor()`, `at()` |
| `backend/src/main/java/com/ticketflow/history/event/TicketCreated.java` e mais 6 | criar | Um record por fato de domínio |
| `backend/src/main/java/com/ticketflow/history/HistoryEventListener.java` | criar | Traduz cada evento em `HistoryRecorder.record(...)` |
| `backend/src/main/java/com/ticketflow/history/HistoryRecorder.java` | alterar (Javadoc) | Documentação passa a refletir o novo fluxo |
| `backend/src/main/java/com/ticketflow/ticket/TicketService.java` | alterar | Publica eventos |
| `backend/src/main/java/com/ticketflow/comment/CommentService.java` | alterar | Publica eventos |
| `backend/src/main/java/com/ticketflow/attachment/AttachmentService.java` | alterar | Publica eventos |
| `backend/src/test/java/com/ticketflow/history/HistoryEventListenerTest.java` | criar | Mapeamento evento → histórico e atomicidade |
| `backend/src/test/java/com/ticketflow/history/DomainEventsApiTest.java` | criar | Services publicam; um ouvinte novo recebe sem alterar os services |
| `docs/roadmap-de-evolucao.md` | alterar | Marca 1.1 como implementado |

---

### Task 1: Eventos, listener e testes de mapeamento/atomicidade

**Files:**
- Create: `backend/src/main/java/com/ticketflow/history/event/HistoryEvent.java`
- Create: `backend/src/main/java/com/ticketflow/history/event/TicketCreated.java`
- Create: `backend/src/main/java/com/ticketflow/history/event/TicketAssigned.java`
- Create: `backend/src/main/java/com/ticketflow/history/event/TicketStatusChanged.java`
- Create: `backend/src/main/java/com/ticketflow/history/event/TicketPriorityChanged.java`
- Create: `backend/src/main/java/com/ticketflow/history/event/TicketCategoryChanged.java`
- Create: `backend/src/main/java/com/ticketflow/history/event/CommentAdded.java`
- Create: `backend/src/main/java/com/ticketflow/history/event/AttachmentAdded.java`
- Create: `backend/src/main/java/com/ticketflow/history/HistoryEventListener.java`
- Test: `backend/src/test/java/com/ticketflow/history/HistoryEventListenerTest.java`

**Interfaces:**
- Consumes: `HistoryRecorder.record(Ticket, User, HistoryEventType, Instant)` e `HistoryRecorder.record(Ticket, User, HistoryEventType, String field, String oldValue, String newValue, Instant at)` (já existem).
- Produces (usado pelas Tarefas 2 e 3), todos em `com.ticketflow.history.event`, todos com `Ticket ticket, User actor, Instant at` como **primeiros três** componentes:
  - `TicketCreated(Ticket ticket, User actor, Instant at)`
  - `TicketAssigned(Ticket ticket, User actor, Instant at, String oldAssignee, String newAssignee)`
  - `TicketStatusChanged(Ticket ticket, User actor, Instant at, TicketStatus oldStatus, TicketStatus newStatus)`
  - `TicketPriorityChanged(Ticket ticket, User actor, Instant at, Priority oldPriority, Priority newPriority)`
  - `TicketCategoryChanged(Ticket ticket, User actor, Instant at, String oldCategory, String newCategory)`
  - `CommentAdded(Ticket ticket, User actor, Instant at)`
  - `AttachmentAdded(Ticket ticket, User actor, Instant at, String filename)`

- [ ] **Step 1: Criar a branch e commitar os documentos**

```bash
git switch -c feat/domain-events
git add docs/roadmap-de-evolucao.md docs/specs/2026-10-01-domain-events-design.md docs/plans/2026-10-01-domain-events.md
git commit -m "docs: add the evolution roadmap and the domain-events spec and plan

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 2: Escrever o teste que falha**

Crie `backend/src/test/java/com/ticketflow/history/HistoryEventListenerTest.java`:

```java
package com.ticketflow.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.ticketflow.history.event.AttachmentAdded;
import com.ticketflow.history.event.CommentAdded;
import com.ticketflow.history.event.TicketAssigned;
import com.ticketflow.history.event.TicketCategoryChanged;
import com.ticketflow.history.event.TicketCreated;
import com.ticketflow.history.event.TicketPriorityChanged;
import com.ticketflow.history.event.TicketStatusChanged;
import com.ticketflow.support.IntegrationTest;
import com.ticketflow.ticket.Priority;
import com.ticketflow.ticket.Ticket;
import com.ticketflow.ticket.TicketRepository;
import com.ticketflow.ticket.TicketStatus;
import com.ticketflow.user.Role;
import com.ticketflow.user.User;
import java.time.Instant;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class HistoryEventListenerTest extends IntegrationTest {

    @Autowired ApplicationEventPublisher events;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired TicketRepository ticketRepository;

    long ticketId;
    long actorId;

    @BeforeEach
    void setUp() throws Exception {
        User ana = createUser("Ana", Role.REQUESTER);
        User bruno = createUser("Bruno", Role.AGENT);
        actorId = bruno.getId();
        ticketId = createTicket(ana, "Impressora", "LOW");
        // Creating the ticket already wrote a CREATED entry; start every test from an empty history.
        jdbc.update("DELETE FROM ticket_history");
    }

    /** Runs the work inside one transaction, with the ticket and the actor loaded in that transaction. */
    private void inTransaction(BiConsumer<Ticket, User> work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> work.accept(
                ticketRepository.findById(ticketId).orElseThrow(),
                userRepository.findById(actorId).orElseThrow()));
    }

    @Test
    void eachEventBecomesItsHistoryEntryInPublicationOrder() {
        Instant at = clock.instant();

        inTransaction((ticket, actor) -> {
            events.publishEvent(new TicketCreated(ticket, actor, at));
            events.publishEvent(new TicketAssigned(ticket, actor, at, null, "Bruno"));
            events.publishEvent(new TicketStatusChanged(ticket, actor, at, TicketStatus.OPEN,
                    TicketStatus.IN_PROGRESS));
            events.publishEvent(new TicketPriorityChanged(ticket, actor, at, Priority.LOW, Priority.HIGH));
            events.publishEvent(new TicketCategoryChanged(ticket, actor, at, "Hardware", "Software"));
            events.publishEvent(new CommentAdded(ticket, actor, at));
            events.publishEvent(new AttachmentAdded(ticket, actor, at, "relatorio.pdf"));
        });

        assertThat(jdbc.queryForList(
                "SELECT event_type, field, old_value, new_value FROM ticket_history ORDER BY id"))
                .extracting(row -> row.get("event_type"), row -> row.get("field"),
                        row -> row.get("old_value"), row -> row.get("new_value"))
                .containsExactly(
                        tuple("CREATED", null, null, null),
                        tuple("ASSIGNED", "assignee", null, "Bruno"),
                        tuple("STATUS_CHANGED", "status", "OPEN", "IN_PROGRESS"),
                        tuple("PRIORITY_CHANGED", "priority", "LOW", "HIGH"),
                        tuple("CATEGORY_CHANGED", "category", "Hardware", "Software"),
                        tuple("COMMENT_ADDED", null, null, null),
                        tuple("ATTACHMENT_ADDED", "attachment", null, "relatorio.pdf"));
    }

    @Test
    void rollbackTakesTheHistoryEntryWithIt() {
        assertThatThrownBy(() -> inTransaction((ticket, actor) -> {
            events.publishEvent(new CommentAdded(ticket, actor, clock.instant()));
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_history", Long.class)).isZero();
    }
}
```

- [ ] **Step 3: Rodar o teste e ver falhar**

Run (em `backend/`): `./mvnw test -Dtest=HistoryEventListenerTest`
Expected: FAIL na compilação, com `package com.ticketflow.history.event does not exist`.

- [ ] **Step 4: Criar a interface e os 7 eventos**

`backend/src/main/java/com/ticketflow/history/event/HistoryEvent.java`:

```java
package com.ticketflow.history.event;

import com.ticketflow.ticket.Ticket;
import com.ticketflow.user.User;
import java.time.Instant;

/**
 * Something that happened to a ticket and that the system may react to (today: write the history;
 * tomorrow: send an e-mail, call a webhook). Sealed, so the full list of events is visible in one place.
 */
public sealed interface HistoryEvent
        permits TicketCreated, TicketAssigned, TicketStatusChanged, TicketPriorityChanged,
                TicketCategoryChanged, CommentAdded, AttachmentAdded {

    Ticket ticket();

    User actor();

    Instant at();
}
```

`TicketCreated.java`:

```java
package com.ticketflow.history.event;

import com.ticketflow.ticket.Ticket;
import com.ticketflow.user.User;
import java.time.Instant;

public record TicketCreated(Ticket ticket, User actor, Instant at) implements HistoryEvent {
}
```

`TicketAssigned.java` (`oldAssignee` é `null` quando o chamado ainda não tinha responsável):

```java
package com.ticketflow.history.event;

import com.ticketflow.ticket.Ticket;
import com.ticketflow.user.User;
import java.time.Instant;

public record TicketAssigned(Ticket ticket, User actor, Instant at, String oldAssignee, String newAssignee)
        implements HistoryEvent {
}
```

`TicketStatusChanged.java`:

```java
package com.ticketflow.history.event;

import com.ticketflow.ticket.Ticket;
import com.ticketflow.ticket.TicketStatus;
import com.ticketflow.user.User;
import java.time.Instant;

public record TicketStatusChanged(Ticket ticket, User actor, Instant at, TicketStatus oldStatus,
        TicketStatus newStatus) implements HistoryEvent {
}
```

`TicketPriorityChanged.java`:

```java
package com.ticketflow.history.event;

import com.ticketflow.ticket.Priority;
import com.ticketflow.ticket.Ticket;
import com.ticketflow.user.User;
import java.time.Instant;

public record TicketPriorityChanged(Ticket ticket, User actor, Instant at, Priority oldPriority,
        Priority newPriority) implements HistoryEvent {
}
```

`TicketCategoryChanged.java`:

```java
package com.ticketflow.history.event;

import com.ticketflow.ticket.Ticket;
import com.ticketflow.user.User;
import java.time.Instant;

public record TicketCategoryChanged(Ticket ticket, User actor, Instant at, String oldCategory,
        String newCategory) implements HistoryEvent {
}
```

`CommentAdded.java`:

```java
package com.ticketflow.history.event;

import com.ticketflow.ticket.Ticket;
import com.ticketflow.user.User;
import java.time.Instant;

/** Only public comments publish this: an internal note must leave no trace the requester can see. */
public record CommentAdded(Ticket ticket, User actor, Instant at) implements HistoryEvent {
}
```

`AttachmentAdded.java`:

```java
package com.ticketflow.history.event;

import com.ticketflow.ticket.Ticket;
import com.ticketflow.user.User;
import java.time.Instant;

public record AttachmentAdded(Ticket ticket, User actor, Instant at, String filename) implements HistoryEvent {
}
```

- [ ] **Step 5: Criar o listener**

`backend/src/main/java/com/ticketflow/history/HistoryEventListener.java`:

```java
package com.ticketflow.history;

import com.ticketflow.history.event.AttachmentAdded;
import com.ticketflow.history.event.CommentAdded;
import com.ticketflow.history.event.TicketAssigned;
import com.ticketflow.history.event.TicketCategoryChanged;
import com.ticketflow.history.event.TicketCreated;
import com.ticketflow.history.event.TicketPriorityChanged;
import com.ticketflow.history.event.TicketStatusChanged;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Turns domain events into history entries.
 *
 * <p>BEFORE_COMMIT keeps the entry inside the transaction of the change: if the change is rolled back,
 * so is its history. (With the default AFTER_COMMIT the entry would be written outside it and could
 * diverge from the ticket.) The services that publish the events are all {@code @Transactional}; an event
 * published with no transaction running would be ignored by this listener.
 */
@Component
public class HistoryEventListener {

    private final HistoryRecorder history;

    public HistoryEventListener(HistoryRecorder history) {
        this.history = history;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void on(TicketCreated event) {
        history.record(event.ticket(), event.actor(), HistoryEventType.CREATED, event.at());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void on(TicketAssigned event) {
        history.record(event.ticket(), event.actor(), HistoryEventType.ASSIGNED, "assignee",
                event.oldAssignee(), event.newAssignee(), event.at());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void on(TicketStatusChanged event) {
        history.record(event.ticket(), event.actor(), HistoryEventType.STATUS_CHANGED, "status",
                event.oldStatus().name(), event.newStatus().name(), event.at());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void on(TicketPriorityChanged event) {
        history.record(event.ticket(), event.actor(), HistoryEventType.PRIORITY_CHANGED, "priority",
                event.oldPriority().name(), event.newPriority().name(), event.at());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void on(TicketCategoryChanged event) {
        history.record(event.ticket(), event.actor(), HistoryEventType.CATEGORY_CHANGED, "category",
                event.oldCategory(), event.newCategory(), event.at());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void on(CommentAdded event) {
        history.record(event.ticket(), event.actor(), HistoryEventType.COMMENT_ADDED, event.at());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void on(AttachmentAdded event) {
        history.record(event.ticket(), event.actor(), HistoryEventType.ATTACHMENT_ADDED, "attachment", null,
                event.filename(), event.at());
    }
}
```

- [ ] **Step 6: Rodar o teste e ver passar**

Run (em `backend/`): `./mvnw test -Dtest=HistoryEventListenerTest`
Expected: PASS (2 testes). Se `eachEventBecomesItsHistoryEntryInPublicationOrder` falhar com tabela vazia, o listener não foi chamado: confira que a classe tem `@Component` e que o método publica dentro da transação.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/ticketflow/history backend/src/test/java/com/ticketflow/history
git commit -m "feat: add history domain events and the listener that records them

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Migrar o `TicketService`

**Files:**
- Modify: `backend/src/main/java/com/ticketflow/ticket/TicketService.java`
- Test: `backend/src/test/java/com/ticketflow/history/DomainEventsApiTest.java`

**Interfaces:**
- Consumes: os 7 records da Tarefa 1 (`TicketCreated`, `TicketAssigned`, `TicketStatusChanged`, `TicketPriorityChanged`, `TicketCategoryChanged`).
- Produces: `TicketService` com o construtor `(TicketRepository, CategoryRepository, UserRepository, ApplicationEventPublisher, SlaCalculator, Clock)`; `DomainEventsApiTest` com o `EventRecorder` e os helpers que a Tarefa 3 estende.

- [ ] **Step 1: Escrever o teste que falha**

Crie `backend/src/test/java/com/ticketflow/history/DomainEventsApiTest.java`:

```java
package com.ticketflow.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.history.event.HistoryEvent;
import com.ticketflow.history.event.TicketAssigned;
import com.ticketflow.history.event.TicketCategoryChanged;
import com.ticketflow.history.event.TicketCreated;
import com.ticketflow.history.event.TicketPriorityChanged;
import com.ticketflow.history.event.TicketStatusChanged;
import com.ticketflow.support.IntegrationTest;
import com.ticketflow.ticket.Priority;
import com.ticketflow.ticket.TicketStatus;
import com.ticketflow.user.Role;
import com.ticketflow.user.User;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Proves the decoupling: the services publish events, and a listener that none of them knows about
 * receives them. Adding behavior (e-mail, webhook) is exactly this: one more listener, no service edited.
 */
class DomainEventsApiTest extends IntegrationTest {

    static class EventRecorder {

        final List<HistoryEvent> received = new CopyOnWriteArrayList<>();

        @EventListener
        void on(HistoryEvent event) {
            received.add(event);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RecorderConfiguration {

        @Bean
        EventRecorder eventRecorder() {
            return new EventRecorder();
        }
    }

    @Autowired EventRecorder recorder;

    User ana;
    User bruno;
    User carla;

    @BeforeEach
    void setUp() {
        recorder.received.clear();
        ana = createUser("Ana", Role.REQUESTER);
        bruno = createUser("Bruno", Role.AGENT);
        carla = createUser("Carla", Role.MANAGER);
    }

    ResultActions assign(User actor, long ticketId, long assigneeId, long version) throws Exception {
        return mvc.perform(post("/api/tickets/{id}/assign", ticketId)
                .header("Authorization", bearer(actor))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"assigneeId\": %d, \"version\": %d}".formatted(assigneeId, version)));
    }

    ResultActions patchTicket(User actor, long ticketId, String json) throws Exception {
        return mvc.perform(patch("/api/tickets/{id}", ticketId)
                .header("Authorization", bearer(actor))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    List<String> historyEventTypes(long ticketId) {
        return jdbc.queryForList("SELECT event_type FROM ticket_history WHERE ticket_id = ? ORDER BY id",
                String.class, ticketId);
    }

    @Test
    void creatingATicketPublishesTicketCreatedAndTheHistoryListensToIt() throws Exception {
        long id = createTicket(ana, "Impressora", "LOW");

        assertThat(recorder.received).singleElement().isInstanceOfSatisfying(TicketCreated.class, event -> {
            assertThat(event.ticket().getId()).isEqualTo(id);
            assertThat(event.actor().getId()).isEqualTo(ana.getId());
        });
        assertThat(historyEventTypes(id)).containsExactly("CREATED");
    }

    @Test
    void assigningPublishesAssignedThenStatusChanged() throws Exception {
        long id = createTicket(ana, "Impressora", "LOW");
        recorder.received.clear();

        assign(bruno, id, bruno.getId(), 0).andExpect(status().isOk());

        assertThat(recorder.received).hasSize(2);
        assertThat(recorder.received.get(0)).isInstanceOfSatisfying(TicketAssigned.class, event -> {
            assertThat(event.oldAssignee()).isNull();
            assertThat(event.newAssignee()).isEqualTo("Bruno");
        });
        assertThat(recorder.received.get(1)).isInstanceOfSatisfying(TicketStatusChanged.class, event -> {
            assertThat(event.oldStatus()).isEqualTo(TicketStatus.OPEN);
            assertThat(event.newStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        });
    }

    @Test
    void assigningToTheCurrentAssigneeAgainPublishesNothing() throws Exception {
        long id = createTicket(ana, "Impressora", "LOW");
        assign(bruno, id, bruno.getId(), 0).andExpect(status().isOk());
        recorder.received.clear();

        assign(carla, id, bruno.getId(), 1).andExpect(status().isOk());

        assertThat(recorder.received).isEmpty();
    }

    @Test
    void changingStatusPublishesStatusChanged() throws Exception {
        long id = createTicket(ana, "Impressora", "LOW");
        assign(bruno, id, bruno.getId(), 0).andExpect(status().isOk());
        recorder.received.clear();

        mvc.perform(post("/api/tickets/{id}/status", id)
                        .header("Authorization", bearer(bruno))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"RESOLVED\", \"version\": 1}"))
                .andExpect(status().isOk());

        assertThat(recorder.received).singleElement().isInstanceOfSatisfying(TicketStatusChanged.class, event -> {
            assertThat(event.actor().getId()).isEqualTo(bruno.getId());
            assertThat(event.oldStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
            assertThat(event.newStatus()).isEqualTo(TicketStatus.RESOLVED);
        });
    }

    @Test
    void updatingPriorityAndCategoryPublishesBothInOrder() throws Exception {
        long id = createTicket(ana, "Impressora", "LOW");
        recorder.received.clear();

        patchTicket(carla, id, "{\"priority\": \"HIGH\", \"categoryId\": %d, \"version\": 0}"
                .formatted(categoryId("Software"))).andExpect(status().isOk());

        assertThat(recorder.received).hasSize(2);
        assertThat(recorder.received.get(0)).isInstanceOfSatisfying(TicketPriorityChanged.class, event -> {
            assertThat(event.oldPriority()).isEqualTo(Priority.LOW);
            assertThat(event.newPriority()).isEqualTo(Priority.HIGH);
        });
        assertThat(recorder.received.get(1)).isInstanceOfSatisfying(TicketCategoryChanged.class, event -> {
            assertThat(event.oldCategory()).isEqualTo("Hardware");
            assertThat(event.newCategory()).isEqualTo("Software");
        });
    }

    @Test
    void updatingWithTheCurrentValuesPublishesNothing() throws Exception {
        long id = createTicket(ana, "Impressora", "LOW");
        recorder.received.clear();

        patchTicket(carla, id, "{\"priority\": \"LOW\", \"categoryId\": %d, \"version\": 0}"
                .formatted(categoryId("Hardware"))).andExpect(status().isOk());

        assertThat(recorder.received).isEmpty();
    }
}
```

- [ ] **Step 2: Rodar o teste e ver falhar**

Run (em `backend/`): `./mvnw test -Dtest=DomainEventsApiTest`
Expected: FAIL. Os testes que esperam eventos falham porque `recorder.received` está vazio (o `TicketService` ainda chama `HistoryRecorder`). Os dois testes de "publica nada" passam por enquanto; é esperado.

- [ ] **Step 3: Migrar o `TicketService`**

Em `backend/src/main/java/com/ticketflow/ticket/TicketService.java`:

1. Nos imports, **remova** `com.ticketflow.history.HistoryEventType` e `com.ticketflow.history.HistoryRecorder`. **Adicione**, em ordem alfabética:

```java
import com.ticketflow.history.event.TicketAssigned;
import com.ticketflow.history.event.TicketCategoryChanged;
import com.ticketflow.history.event.TicketCreated;
import com.ticketflow.history.event.TicketPriorityChanged;
import com.ticketflow.history.event.TicketStatusChanged;
```

e, junto dos imports `org.springframework`, `import org.springframework.context.ApplicationEventPublisher;` (antes de `org.springframework.data.domain.Pageable`).

2. Troque o campo e o construtor:

```java
    private final TicketRepository tickets;
    private final CategoryRepository categories;
    private final UserRepository users;
    private final ApplicationEventPublisher events;
    private final SlaCalculator sla;
    private final Clock clock;

    public TicketService(TicketRepository tickets, CategoryRepository categories, UserRepository users,
            ApplicationEventPublisher events, SlaCalculator sla, Clock clock) {
        this.tickets = tickets;
        this.categories = categories;
        this.users = users;
        this.events = events;
        this.sla = sla;
        this.clock = clock;
    }
```

3. Em `create`, troque a linha do histórico:

```java
        events.publishEvent(new TicketCreated(ticket, requester, now));
```

4. Em `assign`, troque as chamadas de histórico (depois de `ticket.assign(assignee, now, sla);`):

```java
        events.publishEvent(new TicketAssigned(ticket, actor, now, oldAssignee, assignee.getName()));
        if (ticket.getStatus() != oldStatus) {
            events.publishEvent(new TicketStatusChanged(ticket, actor, now, oldStatus, ticket.getStatus()));
        }
```

5. Em `changeStatus`, troque a chamada de histórico (depois de `ticket.changeStatus(target, now, sla);`):

```java
        events.publishEvent(new TicketStatusChanged(ticket, users.getCurrent(authUser), now, oldStatus, target));
```

6. Em `update`, troque os dois blocos para:

```java
        if (request.priority() != null && request.priority() != ticket.getPriority()) {
            Priority oldPriority = ticket.getPriority();
            ticket.changePriority(request.priority(), sla);
            events.publishEvent(new TicketPriorityChanged(ticket, actor, now, oldPriority, request.priority()));
        }
        if (request.categoryId() != null && !request.categoryId().equals(ticket.getCategory().getId())) {
            Category category = findCategory(request.categoryId());
            String oldCategory = ticket.getCategory().getName();
            ticket.changeCategory(category);
            events.publishEvent(new TicketCategoryChanged(ticket, actor, now, oldCategory, category.getName()));
        }
```

- [ ] **Step 4: Rodar o teste novo e a rede de segurança**

Run (em `backend/`): `./mvnw test -Dtest="DomainEventsApiTest,HistoryEventListenerTest,TicketWorkflowApiTest,TicketUpdateApiTest,TicketApiTest,TicketSlaTest,TicketSearchApiTest"`
Expected: PASS em todos, sem editar nenhum teste existente.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/ticketflow/ticket/TicketService.java backend/src/test/java/com/ticketflow/history/DomainEventsApiTest.java
git commit -m "refactor: publish domain events from TicketService instead of recording history

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Migrar `CommentService` e `AttachmentService`, fechar a documentação

**Files:**
- Modify: `backend/src/main/java/com/ticketflow/comment/CommentService.java`
- Modify: `backend/src/main/java/com/ticketflow/attachment/AttachmentService.java`
- Modify: `backend/src/main/java/com/ticketflow/history/HistoryRecorder.java` (Javadoc)
- Modify: `docs/roadmap-de-evolucao.md`
- Test: `backend/src/test/java/com/ticketflow/history/DomainEventsApiTest.java`

**Interfaces:**
- Consumes: `CommentAdded`, `AttachmentAdded`, `TicketStatusChanged` (Tarefa 1); `EventRecorder` e os helpers `assign`, `historyEventTypes` do `DomainEventsApiTest` (Tarefa 2).
- Produces: `CommentService(CommentRepository, TicketService, UserRepository, ApplicationEventPublisher, SlaCalculator, Clock)` e `AttachmentService(AttachmentRepository, AttachmentStorage, TicketService, UserRepository, ApplicationEventPublisher, Clock)`.

- [ ] **Step 1: Escrever os testes que falham**

Em `DomainEventsApiTest.java`, acrescente os imports:

```java
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.ticketflow.history.event.AttachmentAdded;
import com.ticketflow.history.event.CommentAdded;
import java.nio.charset.StandardCharsets;
import org.springframework.mock.web.MockMultipartFile;
```

Acrescente os helpers (junto dos outros helpers):

```java
    ResultActions comment(User author, long ticketId, String text, boolean internal) throws Exception {
        return mvc.perform(post("/api/tickets/{id}/comments", ticketId)
                .header("Authorization", bearer(author))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\": \"%s\", \"internal\": %s}".formatted(text, internal)));
    }

    ResultActions changeStatus(User actor, long ticketId, String status, long version) throws Exception {
        return mvc.perform(post("/api/tickets/{id}/status", ticketId)
                .header("Authorization", bearer(actor))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\": \"%s\", \"version\": %d}".formatted(status, version)));
    }
```

E os testes:

```java
    @Test
    void publicCommentPublishesCommentAdded() throws Exception {
        long id = createTicket(ana, "Impressora", "LOW");
        recorder.received.clear();

        comment(ana, id, "Olá", false).andExpect(status().isCreated());

        assertThat(recorder.received).singleElement().isInstanceOfSatisfying(CommentAdded.class,
                event -> assertThat(event.actor().getId()).isEqualTo(ana.getId()));
        assertThat(historyEventTypes(id)).containsExactly("CREATED", "COMMENT_ADDED");
    }

    @Test
    void internalNotePublishesNothingAndLeavesNoHistory() throws Exception {
        long id = createTicket(ana, "Impressora", "LOW");
        recorder.received.clear();

        comment(bruno, id, "Suspeito do cabo", true).andExpect(status().isCreated());

        assertThat(recorder.received).isEmpty();
        assertThat(historyEventTypes(id)).containsExactly("CREATED");
    }

    @Test
    void requesterAnswerPublishesCommentAddedThenStatusChanged() throws Exception {
        long id = createTicket(ana, "Impressora", "LOW");
        assign(bruno, id, bruno.getId(), 0).andExpect(status().isOk());
        changeStatus(bruno, id, "WAITING_REQUESTER", 1).andExpect(status().isOk());
        recorder.received.clear();

        comment(ana, id, "Segue o print", false).andExpect(status().isCreated());

        assertThat(recorder.received).hasSize(2);
        assertThat(recorder.received.get(0)).isInstanceOf(CommentAdded.class);
        assertThat(recorder.received.get(1)).isInstanceOfSatisfying(TicketStatusChanged.class, event -> {
            assertThat(event.actor().getId()).isEqualTo(ana.getId());
            assertThat(event.oldStatus()).isEqualTo(TicketStatus.WAITING_REQUESTER);
            assertThat(event.newStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        });
    }

    @Test
    void uploadPublishesAttachmentAdded() throws Exception {
        long id = createTicket(ana, "Impressora", "LOW");
        recorder.received.clear();
        MockMultipartFile file = new MockMultipartFile("file", "relatório.pdf", "application/octet-stream",
                "%PDF-1.7 conteudo do relatorio".getBytes(StandardCharsets.US_ASCII));

        mvc.perform(multipart("/api/tickets/{id}/attachments", id).file(file).header("Authorization", bearer(ana)))
                .andExpect(status().isCreated());

        assertThat(recorder.received).singleElement().isInstanceOfSatisfying(AttachmentAdded.class, event -> {
            assertThat(event.actor().getId()).isEqualTo(ana.getId());
            assertThat(event.filename()).isEqualTo("relatório.pdf");
        });
    }
```

- [ ] **Step 2: Rodar o teste e ver falhar**

Run (em `backend/`): `./mvnw test -Dtest=DomainEventsApiTest`
Expected: FAIL em `publicCommentPublishesCommentAdded`, `requesterAnswerPublishesCommentAddedThenStatusChanged` e `uploadPublishesAttachmentAdded` (nenhum evento recebido). `internalNotePublishesNothingAndLeavesNoHistory` já passa; é esperado.

- [ ] **Step 3: Migrar o `CommentService`**

Em `backend/src/main/java/com/ticketflow/comment/CommentService.java`:

1. Imports: remova `com.ticketflow.history.HistoryEventType` e `com.ticketflow.history.HistoryRecorder`; adicione `com.ticketflow.history.event.CommentAdded` e `com.ticketflow.history.event.TicketStatusChanged` (depois de `com.ticketflow.common.ApiException`), e `org.springframework.context.ApplicationEventPublisher` (antes de `org.springframework.stereotype.Service`).
2. Campo e construtor:

```java
    private final CommentRepository comments;
    private final TicketService tickets;
    private final UserRepository users;
    private final ApplicationEventPublisher events;
    private final SlaCalculator sla;
    private final Clock clock;

    public CommentService(CommentRepository comments, TicketService tickets, UserRepository users,
            ApplicationEventPublisher events, SlaCalculator sla, Clock clock) {
        this.comments = comments;
        this.tickets = tickets;
        this.users = users;
        this.events = events;
        this.sla = sla;
        this.clock = clock;
    }
```

3. No método `add`, troque as duas chamadas de histórico:

```java
        events.publishEvent(new CommentAdded(ticket, author, now));

        // The requester answered what the agent asked: the ticket goes back to work automatically.
        if (ticket.getStatus() == TicketStatus.WAITING_REQUESTER && ticket.isRequestedBy(author.getId())) {
            ticket.changeStatus(TicketStatus.IN_PROGRESS, now, sla);
            events.publishEvent(new TicketStatusChanged(ticket, author, now, TicketStatus.WAITING_REQUESTER,
                    TicketStatus.IN_PROGRESS));
        }
```

Mantenha o `return` antecipado das notas internas e o comentário sobre o histórico visível ao solicitante.

- [ ] **Step 4: Migrar o `AttachmentService`**

Em `backend/src/main/java/com/ticketflow/attachment/AttachmentService.java`:

1. Imports: remova `com.ticketflow.history.HistoryEventType` e `com.ticketflow.history.HistoryRecorder`; adicione `com.ticketflow.history.event.AttachmentAdded` e `org.springframework.context.ApplicationEventPublisher` (antes de `org.springframework.stereotype.Service`).
2. Campo e construtor:

```java
    private final AttachmentRepository attachments;
    private final AttachmentStorage storage;
    private final TicketService tickets;
    private final UserRepository users;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public AttachmentService(AttachmentRepository attachments, AttachmentStorage storage, TicketService tickets,
            UserRepository users, ApplicationEventPublisher events, Clock clock) {
        this.attachments = attachments;
        this.storage = storage;
        this.tickets = tickets;
        this.users = users;
        this.events = events;
        this.clock = clock;
    }
```

3. Em `upload`, troque a chamada de histórico:

```java
        events.publishEvent(new AttachmentAdded(ticket, uploader, now, filename));
```

- [ ] **Step 5: Atualizar o Javadoc do `HistoryRecorder`**

Em `backend/src/main/java/com/ticketflow/history/HistoryRecorder.java`, troque o Javadoc da classe por:

```java
/**
 * Writes one history row. The domain services do not call it: they publish events and
 * {@link HistoryEventListener} records them here, before the commit, so the entry lives and dies with the
 * change. The demo seeder calls it directly because it needs back-dated entries.
 */
```

- [ ] **Step 6: Marcar o item no roadmap**

Em `docs/roadmap-de-evolucao.md`, logo abaixo do título `### 1.1. Desacoplamento via Spring Application Events (@DomainEvents)`, acrescente a linha:

```markdown
- **Status**: ✅ Implementado (spec: `docs/specs/2026-10-01-domain-events-design.md`). Usa `ApplicationEventPublisher` e `@TransactionalEventListener(BEFORE_COMMIT)`, pois `@DomainEvents` só dispara em `repository.save(...)`.
```

- [ ] **Step 7: Verificação completa**

Run (em `backend/`): `./mvnw verify`
Expected: BUILD SUCCESS, todos os testes passando, nenhum teste existente editado. Confirme também que nenhum service de negócio importa o recorder:

Run (na raiz): `git grep -n "HistoryRecorder" -- backend/src/main`
Expected: só `HistoryRecorder.java`, `HistoryEventListener.java` e `DemoDataSeeder.java`.

- [ ] **Step 8: Commit**

```bash
git add backend/src docs/roadmap-de-evolucao.md
git commit -m "refactor: publish domain events from CommentService and AttachmentService

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

## Depois das tarefas

Branch `feat/domain-events` com 4 commits (docs + 3 de código). Push e Pull Request só quando você pedir, seguindo o padrão do repositório (`feat/...` → PR para `main`).

## Self-review (spec × plano)

- Critério 1 (nenhum service depende de `HistoryRecorder`): Tarefas 2 e 3, verificado por `git grep` no Passo 7 da Tarefa 3.
- Critério 2 (API inalterada): rede de segurança rodada nas Tarefas 2 e 3 e `verify` completo.
- Critério 3 (rollback leva o histórico): `rollbackTakesTheHistoryEntryWithIt`, Tarefa 1.
- Critério 4 (listener novo recebe sem tocar nos services): `DomainEventsApiTest`, Tarefas 2 e 3.
- Seção 3.1 (7 eventos e dados extras): Tarefa 1, Passo 4. Seção 3.3 (mapeamento de campos): Tarefa 1, Passo 5, conferido por `eachEventBecomesItsHistoryEntryInPublicationOrder`.
- Seção 3.4 (nota interna, ordem, atomicidade): Tarefas 1 e 3.
- Seção 5 (3 commits): Tarefas 1, 2 e 3, mais o commit de documentos que a Tarefa 1 faz no início.
- Nomes e assinaturas dos records conferidos entre as Tarefas 1, 2 e 3.
