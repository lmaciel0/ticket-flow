# SLA de primeira resposta — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Dar a cada chamado um prazo de primeira resposta, registrar quando ela acontece (assumir/atribuir ou comentário público da equipe) e mostrar o resultado na API, no detalhe do chamado e na demo.

**Architecture:** O `SlaCalculator` ganha uma segunda tabela de prazos (`app.sla.first-response`) e o indicador `FirstResponseIndicator`. O `Ticket` guarda `firstResponseDueAt` e `firstRespondedAt` (migration V9) e concentra a regra em `recordFirstResponse(User by, Instant at)`, chamada por `assign` e pelo `CommentService`. O `TicketResponse` expõe os dois campos e o indicador; o frontend mostra um `FirstResponseBadge` no `TicketSummary`; o `DemoDataSeeder` posiciona a atribuição no relógio do SLA.

**Tech Stack:** Spring Boot, JPA/Hibernate, Flyway, PostgreSQL 17, JUnit 5 + AssertJ + MockMvc + Testcontainers; React + Mantine + Vitest.

**Spec:** `docs/specs/2026-10-01-first-response-sla-design.md`

## Global Constraints

- Prazos padrão: CRITICAL `30m`, HIGH `1h`, MEDIUM `4h`, LOW `8h`, em `app.sla.first-response`.
- Indicador: exatamente `PENDING`, `OVERDUE`, `MET`, `BREACHED`, ou `null` ("não se aplica").
- "Fora do prazo" quando `firstRespondedAt >= firstResponseDueAt` (o mesmo critério de `slaBreached`); não é gravado.
- Quem age (quem atribui ou quem comenta) nunca conta se for o solicitante do chamado.
- Migration `V9__add_first_response_sla.sql` (a última existente é a V8); as duas colunas aceitam `NULL`.
- Campos novos no JSON: `firstResponseDueAt`, `firstRespondedAt`, `firstResponse`.
- Textos do selo: "Pendente", "Atrasada", "No prazo", "Fora do prazo"; linha "1ª resposta" no `TicketSummary`.
- Branch `feat/first-response-sla`. Commits em inglês terminando com `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Descrição do PR em português.
- Backend: `./mvnw` dentro de `backend/`, com o Docker Desktop ligado (Testcontainers); os resultados ficam em `target/surefire-reports/*.txt`. Frontend: comandos dentro de `frontend/`.

## Review Focus

1. **Comentários depois da 1ª resposta não podem mudar o ETag.** Se cada comentário incrementasse a `version`, qualquer edição aberta em outra aba levaria 412. Só o primeiro comentário que conta altera o chamado. Teste em Task 3 (`aPublicTeamCommentIsTheFirstResponseAndChangesTheETagOnlyOnce`).
2. **Gestor que abriu o próprio chamado e o atribui a um atendente.** O ator é o solicitante, então não conta; a resposta vem quando o atendente comentar. Teste em Task 3 (`aManagerAssigningTheirOwnTicketIsNotAnAnswer`).
3. **Chamado antigo (colunas `NULL`) recebendo atribuição e mudança de prioridade.** Tem que continuar `null`, sem erro e sem ganhar um prazo calculado a partir de uma data antiga. Teste em Task 3 (`ticketsFromBeforeTheMetricHaveNoIndicatorAndStaySo`).
4. **Chamado do próprio atendente, resolvido sem resposta de outra pessoa.** Deve vir com indicador `null`, e não "Atrasada" num chamado encerrado. Testes em Task 1 (calculadora) e Task 3 (API).
5. **Demo recriada de madrugada com horário comercial.** Os quatro estados ainda precisam aparecer. Teste em Task 4 (`DemoDataSeederBusinessHoursTest`).

---

### Task 1: Prazos de 1ª resposta e indicador no `SlaCalculator`

**Files:**
- Create: `backend/src/main/java/com/ticketflow/sla/FirstResponseIndicator.java`
- Modify: `backend/src/main/java/com/ticketflow/sla/SlaProperties.java`
- Modify: `backend/src/main/java/com/ticketflow/sla/SlaCalculator.java`
- Modify: `backend/src/main/resources/application.yml` (bloco `app.sla`)
- Test: `backend/src/test/java/com/ticketflow/sla/SlaCalculatorTest.java`
- Test: `backend/src/test/java/com/ticketflow/ticket/TicketSlaTest.java` (só a construção de `SlaProperties`)

**Interfaces:**
- Produces:
  - `record SlaProperties(Map<Priority, Duration> deadlines, Map<Priority, Duration> firstResponse)` com `Duration firstResponseFor(Priority)`.
  - `SlaCalculator.firstResponseDeadlineFor(Priority): Duration`
  - `SlaCalculator.firstResponseDueAt(Instant createdAt, Priority): Instant`
  - `SlaCalculator.after(Instant start, Duration amount): Instant` (público; substitui o `advance` privado)
  - `SlaCalculator.firstResponseIndicator(TicketStatus status, @Nullable Instant dueAt, @Nullable Instant respondedAt): @Nullable FirstResponseIndicator`
  - `enum FirstResponseIndicator { PENDING, OVERDUE, MET, BREACHED }`

- [ ] **Step 1: Branch e documentos**

```bash
git switch main
git pull
git switch -c feat/first-response-sla
git add docs/specs/2026-10-01-first-response-sla-design.md docs/plans/2026-10-01-first-response-sla.md
git commit -m "docs: add the first-response SLA spec and plan

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 2: Testes que falham em `SlaCalculatorTest`**

Trocar o campo `PROPERTIES` (linhas 20-24) por:

```java
    static final Map<Priority, Duration> DEADLINES = Map.of(
            Priority.CRITICAL, Duration.ofHours(4),
            Priority.HIGH, Duration.ofHours(8),
            Priority.MEDIUM, Duration.ofHours(24),
            Priority.LOW, Duration.ofHours(72));

    static final Map<Priority, Duration> FIRST_RESPONSE = Map.of(
            Priority.CRITICAL, Duration.ofMinutes(30),
            Priority.HIGH, Duration.ofHours(1),
            Priority.MEDIUM, Duration.ofHours(4),
            Priority.LOW, Duration.ofHours(8));

    static final SlaProperties PROPERTIES = new SlaProperties(DEADLINES, FIRST_RESPONSE);
```

Trocar o teste `propertiesRequireEveryPriority` por estes dois:

```java
    @Test
    void propertiesRequireEveryPriority() {
        assertThatThrownBy(() -> new SlaProperties(Map.of(Priority.LOW, Duration.ofHours(1)), FIRST_RESPONSE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SLA deadline");
    }

    @Test
    void propertiesRequireEveryPriorityForTheFirstResponse() {
        assertThatThrownBy(() -> new SlaProperties(DEADLINES, Map.of(Priority.LOW, Duration.ofHours(1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("first response");
    }
```

Acrescentar, antes do `@Nested class WithBusinessHours`:

```java
    @Test
    void firstResponseDeadlineComesFromItsOwnTable() {
        Instant createdAt = Instant.parse("2026-01-10T08:00:00Z");

        assertThat(calculator.firstResponseDueAt(createdAt, Priority.CRITICAL))
                .isEqualTo(Instant.parse("2026-01-10T08:30:00Z"));
        assertThat(calculator.firstResponseDueAt(createdAt, Priority.LOW))
                .isEqualTo(Instant.parse("2026-01-10T16:00:00Z"));
    }

    @Test
    void firstResponseIndicatorFollowsTheClockUntilSomeoneAnswers() {
        assertThat(calculator.firstResponseIndicator(TicketStatus.OPEN, NOW.plusSeconds(1), null))
                .isEqualTo(FirstResponseIndicator.PENDING);
        assertThat(calculator.firstResponseIndicator(TicketStatus.OPEN, NOW, null))
                .isEqualTo(FirstResponseIndicator.OVERDUE);
        assertThat(calculator.firstResponseIndicator(TicketStatus.IN_PROGRESS, NOW, NOW.minusSeconds(1)))
                .isEqualTo(FirstResponseIndicator.MET);
        assertThat(calculator.firstResponseIndicator(TicketStatus.IN_PROGRESS, NOW, NOW))
                .isEqualTo(FirstResponseIndicator.BREACHED);
        assertThat(calculator.firstResponseIndicator(TicketStatus.CLOSED, NOW, NOW.minusSeconds(1)))
                .isEqualTo(FirstResponseIndicator.MET);
    }

    @Test
    void firstResponseIndicatorIsNullWhenTheMetricDoesNotApply() {
        // Created before the metric existed.
        assertThat(calculator.firstResponseIndicator(TicketStatus.OPEN, null, null)).isNull();
        // Finished with nobody but the requester acting on it.
        assertThat(calculator.firstResponseIndicator(TicketStatus.RESOLVED, NOW.minusSeconds(1), null)).isNull();
        assertThat(calculator.firstResponseIndicator(TicketStatus.CLOSED, NOW.plusSeconds(60), null)).isNull();
    }

    @Test
    void afterWithoutCalendarIsPlainAddition() {
        assertThat(calculator.after(NOW, Duration.ofHours(2))).isEqualTo(NOW.plus(Duration.ofHours(2)));
    }
```

Acrescentar dentro de `WithBusinessHours`:

```java
        @Test
        void firstResponseDeadlineSkipsTheWeekend() {
            // MEDIUM = 4h: 1h on Friday + 3h on Monday -> Monday 11:00.
            assertThat(businessSla.firstResponseDueAt(FRIDAY_17H, Priority.MEDIUM))
                    .isEqualTo(Instant.parse("2026-01-12T14:00:00Z"));
        }

        @Test
        void afterCountsWorkingTimeOnly() {
            // 2h of working time from Friday 17:00: 1h on Friday + 1h on Monday -> Monday 09:00.
            assertThat(businessSla.after(FRIDAY_17H, Duration.ofHours(2)))
                    .isEqualTo(Instant.parse("2026-01-12T12:00:00Z"));
        }
```

Em `TicketSlaTest`, trocar a construção do campo `sla` (linhas 30-34) por:

```java
    static final SlaProperties PROPERTIES = new SlaProperties(Map.of(
            Priority.CRITICAL, Duration.ofHours(4),
            Priority.HIGH, Duration.ofHours(8),
            Priority.MEDIUM, Duration.ofHours(24),
            Priority.LOW, Duration.ofHours(72)), Map.of(
            Priority.CRITICAL, Duration.ofMinutes(30),
            Priority.HIGH, Duration.ofHours(1),
            Priority.MEDIUM, Duration.ofHours(4),
            Priority.LOW, Duration.ofHours(8)));

    final SlaCalculator sla = new SlaCalculator(PROPERTIES, Clock.fixed(T0, ZoneOffset.UTC));
```

e, em `pauseOnlyPushesTheDeadlineByWorkingTimeWhenBusinessHoursAreOn`, trocar `new SlaCalculator(new SlaProperties(Map.of(...)), Clock.fixed(...),` por `new SlaCalculator(PROPERTIES, Clock.fixed(T0, ZoneOffset.UTC),` (o resto da chamada, com o `BusinessCalendar`, fica igual).

- [ ] **Step 3: Rodar e ver falhar**

Run: `./mvnw test -Dtest='SlaCalculatorTest,TicketSlaTest'`
Expected: erro de compilação (`SlaProperties` com dois argumentos, `FirstResponseIndicator`, `firstResponseDueAt`, `after` e `firstResponseIndicator` não existem).

- [ ] **Step 4: Implementação**

`backend/src/main/java/com/ticketflow/sla/FirstResponseIndicator.java`:

```java
package com.ticketflow.sla;

/** Where a ticket stands on its first-response deadline; null in the API means "does not apply". */
public enum FirstResponseIndicator {
    /** Nobody answered yet and there is still time. */
    PENDING,
    /** Nobody answered yet and the deadline has passed. */
    OVERDUE,
    /** Answered before the deadline. */
    MET,
    /** Answered at or after the deadline. */
    BREACHED
}
```

`SlaProperties.java` inteiro:

```java
package com.ticketflow.sla;

import com.ticketflow.ticket.Priority;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** SLA deadlines per priority, read from app.sla.deadlines and app.sla.first-response in application.yml. */
@ConfigurationProperties("app.sla")
public record SlaProperties(Map<Priority, Duration> deadlines, Map<Priority, Duration> firstResponse) {

    public SlaProperties {
        deadlines = complete(deadlines, "SLA deadline");
        firstResponse = complete(firstResponse, "first response deadline");
    }

    private static Map<Priority, Duration> complete(Map<Priority, Duration> table, String what) {
        for (Priority priority : Priority.values()) {
            if (table == null || !table.containsKey(priority)) {
                throw new IllegalStateException("Missing " + what + " for priority " + priority);
            }
        }
        return Map.copyOf(table);
    }

    public Duration deadlineFor(Priority priority) {
        return deadlines.get(priority);
    }

    public Duration firstResponseFor(Priority priority) {
        return firstResponse.get(priority);
    }
}
```

`SlaCalculator.java`:
- renomear o método privado `advance` para um público `after`, com este Javadoc, e trocar as três chamadas `advance(` (em `dueAt`, `atRiskBefore` e na própria declaração) por `after(`:

```java
    /** The instant that is {@code amount} of SLA-clock time after {@code start}; the inverse of {@link #ago}. */
    public Instant after(Instant start, Duration amount) {
        return calendar == null ? start.plus(amount) : calendar.plus(start, amount);
    }
```

- acrescentar, depois de `deadlineFor`:

```java
    public Duration firstResponseDeadlineFor(Priority priority) {
        return properties.firstResponseFor(priority);
    }

    /** Same calendar as the resolution deadline; never paused (a ticket only leaves OPEN by being assigned). */
    public Instant firstResponseDueAt(Instant createdAt, Priority priority) {
        return after(createdAt, firstResponseDeadlineFor(priority));
    }
```

- acrescentar no fim da classe:

```java
    /**
     * {@code null} means the metric does not apply: the ticket predates it (no deadline), or it was
     * finished with nobody but the requester acting on it.
     */
    @Nullable
    public FirstResponseIndicator firstResponseIndicator(TicketStatus status, @Nullable Instant dueAt,
            @Nullable Instant respondedAt) {
        if (dueAt == null) {
            return null;
        }
        if (respondedAt != null) {
            return respondedAt.isBefore(dueAt) ? FirstResponseIndicator.MET : FirstResponseIndicator.BREACHED;
        }
        if (!status.isActive()) {
            return null;
        }
        return clock.instant().isBefore(dueAt) ? FirstResponseIndicator.PENDING : FirstResponseIndicator.OVERDUE;
    }
```

`application.yml`, logo depois do bloco `deadlines:` (mesma indentação de `deadlines`):

```yaml
    first-response:
      CRITICAL: 30m
      HIGH: 1h
      MEDIUM: 4h
      LOW: 8h
```

- [ ] **Step 5: Rodar e ver passar**

Run: `./mvnw test -Dtest='SlaCalculatorTest,TicketSlaTest'`
Expected: todos passam.

- [ ] **Step 6: Suíte inteira**

Run: `./mvnw verify`
Expected: BUILD SUCCESS (a aplicação sobe nos testes de integração porque o `application.yml` já tem as quatro prioridades em `first-response`).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/ticketflow/sla backend/src/main/resources/application.yml backend/src/test/java/com/ticketflow/sla/SlaCalculatorTest.java backend/src/test/java/com/ticketflow/ticket/TicketSlaTest.java
git commit -m "feat: add first-response deadlines and indicator to the SLA calculator

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: O chamado registra a 1ª resposta

**Files:**
- Create: `backend/src/main/resources/db/migration/V9__add_first_response_sla.sql`
- Modify: `backend/src/main/java/com/ticketflow/ticket/Ticket.java`
- Modify: `backend/src/main/java/com/ticketflow/ticket/TicketService.java:97` (chamada de `assign`)
- Modify: `backend/src/main/java/com/ticketflow/demo/DemoDataSeeder.java:169` (chamada de `assign`)
- Test: `backend/src/test/java/com/ticketflow/ticket/TicketSlaTest.java`

**Interfaces:**
- Consumes: `SlaCalculator.firstResponseDueAt(Instant, Priority)` (Task 1).
- Produces:
  - `Ticket.assign(User newAssignee, User actor, Instant now, SlaCalculator sla)` (novo parâmetro `actor`)
  - `Ticket.recordFirstResponse(User by, Instant at)`
  - `Ticket.getFirstResponseDueAt(): Instant` (pode ser `null`), `Ticket.getFirstRespondedAt(): Instant` (pode ser `null`)
  - colunas `tickets.first_response_due_at` e `tickets.first_responded_at`

- [ ] **Step 1: Testes que falham em `TicketSlaTest`**

Acrescentar dois usuários depois de `agent`:

```java
    final User otherAgent = new User("Diego", "diego@test.com", "hash", Role.AGENT, T0);
    final User manager = new User("Carla", "carla@test.com", "hash", Role.MANAGER, T0);
```

Trocar todas as chamadas `assign(agent, ` por `assign(agent, agent, ` (são 8, incluindo `onTime.assign`, `late.assign` e a do teste de horário comercial):

```bash
sed -i 's/\.assign(agent, /.assign(agent, agent, /' backend/src/test/java/com/ticketflow/ticket/TicketSlaTest.java
```

Acrescentar os testes:

```java
    @Test
    void newTicketHasAFirstResponseDeadlineAndNoAnswerYet() {
        Ticket ticket = newTicket(Priority.HIGH);

        assertThat(ticket.getFirstResponseDueAt()).isEqualTo(at(1, 0));
        assertThat(ticket.getFirstRespondedAt()).isNull();
    }

    @Test
    void takingTheTicketIsTheFirstResponseAndOnlyTheFirstOneCounts() {
        Ticket ticket = newTicket(Priority.HIGH);

        ticket.assign(agent, agent, at(0, 20), sla);
        ticket.assign(otherAgent, manager, at(0, 40), sla);
        ticket.recordFirstResponse(agent, at(0, 50));

        assertThat(ticket.getFirstRespondedAt()).isEqualTo(at(0, 20));
    }

    @Test
    void theRequesterNeverAnswersTheirOwnTicket() {
        Ticket ownTicket = new Ticket("VPN", "Cai toda hora", Priority.HIGH, new Category("Acesso"), agent, T0, sla);

        ownTicket.recordFirstResponse(agent, at(0, 10));
        ownTicket.assign(agent, agent, at(0, 20), sla);
        assertThat(ownTicket.getFirstRespondedAt()).isNull();
        assertThat(ownTicket.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);

        ownTicket.recordFirstResponse(otherAgent, at(0, 30));
        assertThat(ownTicket.getFirstRespondedAt()).isEqualTo(at(0, 30));
    }

    @Test
    void priorityChangeMovesTheFirstResponseDeadlineOnlyBeforeTheAnswer() {
        Ticket ticket = newTicket(Priority.HIGH);

        ticket.changePriority(Priority.CRITICAL, sla);
        assertThat(ticket.getFirstResponseDueAt()).isEqualTo(at(0, 30));

        ticket.recordFirstResponse(agent, at(0, 10));
        ticket.changePriority(Priority.LOW, sla);
        assertThat(ticket.getFirstResponseDueAt()).isEqualTo(at(0, 30));
    }
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `./mvnw test -Dtest=TicketSlaTest`
Expected: erro de compilação (`assign` com 4 argumentos, `recordFirstResponse`, `getFirstResponseDueAt`, `getFirstRespondedAt`).

- [ ] **Step 3: Migration**

`backend/src/main/resources/db/migration/V9__add_first_response_sla.sql`:

```sql
-- First-response SLA. Nullable: tickets created before this metric have no deadline and show no indicator.
ALTER TABLE tickets ADD COLUMN first_response_due_at TIMESTAMPTZ;
ALTER TABLE tickets ADD COLUMN first_responded_at TIMESTAMPTZ;
```

- [ ] **Step 4: `Ticket`**

Campos, depois de `slaBreached`:

```java
    /** Null for tickets created before the first-response metric existed. */
    private Instant firstResponseDueAt;

    private Instant firstRespondedAt;
```

No construtor, depois de `this.dueAt = ...`:

```java
        this.firstResponseDueAt = sla.firstResponseDueAt(createdAt, priority);
```

Trocar `assign` por:

```java
    /** Sets the responsible person. An OPEN ticket starts being worked on. */
    public void assign(User newAssignee, User actor, Instant now, SlaCalculator sla) {
        this.assignee = newAssignee;
        recordFirstResponse(actor, now);
        if (status == TicketStatus.OPEN) {
            changeStatus(TicketStatus.IN_PROGRESS, now, sla);
        }
    }

    /**
     * The first time someone other than the requester takes or answers the ticket. Later calls are
     * ignored, and so are tickets without a deadline (created before the metric existed).
     */
    public void recordFirstResponse(User by, Instant at) {
        if (firstRespondedAt != null || firstResponseDueAt == null || isRequester(by)) {
            return;
        }
        firstRespondedAt = at;
    }
```

Em `changePriority`, depois da linha do `dueAt`:

```java
        if (firstRespondedAt == null && firstResponseDueAt != null) {
            // Once answered, the first-response result is final.
            this.firstResponseDueAt = sla.firstResponseDueAt(createdAt, newPriority);
        }
```

Depois de `isAssignedTo`:

```java
    /** Same id for entities loaded separately; same instance in unit tests, where nothing has an id yet. */
    private boolean isRequester(User user) {
        return user == requester || (user.getId() != null && user.getId().equals(requester.getId()));
    }
```

Getters, depois de `getSlaBreached`:

```java
    public Instant getFirstResponseDueAt() {
        return firstResponseDueAt;
    }

    public Instant getFirstRespondedAt() {
        return firstRespondedAt;
    }
```

- [ ] **Step 5: Ajustar quem chama `assign`**

`TicketService.java:97`: `ticket.assign(assignee, now, sla);` vira `ticket.assign(assignee, actor, now, sla);` (`actor` já existe duas linhas acima).

`DemoDataSeeder.java:169`: `ticket.assign(agent, assignedAt, sla);` vira `ticket.assign(agent, agent, assignedAt, sla);` (a Task 4 reescreve o resto do seeder).

- [ ] **Step 6: Rodar e ver passar**

Run: `./mvnw test -Dtest=TicketSlaTest`
Expected: todos passam.

- [ ] **Step 7: Suíte inteira**

Run: `./mvnw verify`
Expected: BUILD SUCCESS (o Flyway aplica a V9 no Testcontainers).

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/resources/db/migration/V9__add_first_response_sla.sql backend/src/main/java/com/ticketflow/ticket/Ticket.java backend/src/main/java/com/ticketflow/ticket/TicketService.java backend/src/main/java/com/ticketflow/demo/DemoDataSeeder.java backend/src/test/java/com/ticketflow/ticket/TicketSlaTest.java
git commit -m "feat: record the first response on the ticket

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: API — comentário da equipe conta e a resposta expõe o indicador

**Files:**
- Modify: `backend/src/main/java/com/ticketflow/ticket/TicketDtos.java` (`TicketResponse`)
- Modify: `backend/src/main/java/com/ticketflow/ticket/TicketService.java` (`toResponse`)
- Modify: `backend/src/main/java/com/ticketflow/comment/CommentService.java` (`add`)
- Test: `backend/src/test/java/com/ticketflow/sla/FirstResponseSlaApiTest.java`

**Interfaces:**
- Consumes: `Ticket.recordFirstResponse(User, Instant)`, getters de Task 2; `SlaCalculator.firstResponseIndicator(...)` de Task 1.
- Produces: JSON do chamado (detalhe, lista e respostas de escrita) com `firstResponseDueAt`, `firstRespondedAt` (ISO-8601 ou `null`) e `firstResponse` (`PENDING`/`OVERDUE`/`MET`/`BREACHED` ou `null`).

- [ ] **Step 1: Teste que falha**

`backend/src/test/java/com/ticketflow/sla/FirstResponseSlaApiTest.java`:

```java
package com.ticketflow.sla;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ticketflow.support.IntegrationTest;
import com.ticketflow.user.Role;
import com.ticketflow.user.User;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;

/** First-response SLA through the API: what counts as an answer, who counts, and what the indicator says. */
class FirstResponseSlaApiTest extends IntegrationTest {

    User ana;
    User bruno;
    User diego;
    User carla;

    @BeforeEach
    void createUsers() {
        ana = createUser("Ana", Role.REQUESTER);
        bruno = createUser("Bruno", Role.AGENT);
        diego = createUser("Diego", Role.AGENT);
        carla = createUser("Carla", Role.MANAGER);
    }

    MockHttpServletResponse read(long id) throws Exception {
        return mvc.perform(get("/api/tickets/{id}", id).header("Authorization", bearer(carla)))
                .andExpect(status().isOk())
                .andReturn().getResponse();
    }

    long version(long id) throws Exception {
        return readLong(read(id).getContentAsString(), "$.version");
    }

    String field(long id, String path) throws Exception {
        return JsonPath.read(read(id).getContentAsString(), path);
    }

    Instant respondedAt(long id) throws Exception {
        String value = field(id, "$.firstRespondedAt");
        return value == null ? null : Instant.parse(value);
    }

    ResultActions assign(User actor, long id, User assignee) throws Exception {
        return mvc.perform(post("/api/tickets/{id}/assign", id)
                        .header("Authorization", bearer(actor))
                        .header("If-Match", etag(version(id)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\": %d}".formatted(assignee.getId())))
                .andExpect(status().isOk());
    }

    void comment(User author, long id, boolean internal) throws Exception {
        mvc.perform(post("/api/tickets/{id}/comments", id)
                        .header("Authorization", bearer(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"Estou verificando\", \"internal\": %b}".formatted(internal)))
                .andExpect(status().isCreated());
    }

    @Test
    void newTicketIsPendingWithTheDeadlineOfItsPriority() throws Exception {
        Instant createdAt = clock.instant();
        long id = createTicket(ana, "VPN fora", "HIGH");

        assertThat(Instant.parse(field(id, "$.firstResponseDueAt"))).isEqualTo(createdAt.plus(Duration.ofHours(1)));
        assertThat(respondedAt(id)).isNull();
        assertThat(field(id, "$.firstResponse")).isEqualTo("PENDING");
    }

    @Test
    void takingTheTicketInTimeIsMet() throws Exception {
        long id = createTicket(ana, "VPN fora", "HIGH");
        clock.advance(Duration.ofMinutes(10));

        assign(bruno, id, bruno).andExpect(jsonPath("$.firstResponse").value("MET"));

        assertThat(respondedAt(id)).isEqualTo(clock.instant());
    }

    @Test
    void reassigningKeepsTheFirstMoment() throws Exception {
        long id = createTicket(ana, "VPN fora", "HIGH");
        clock.advance(Duration.ofMinutes(10));
        assign(bruno, id, bruno);
        Instant first = clock.instant();

        clock.advance(Duration.ofHours(2));
        assign(carla, id, diego).andExpect(jsonPath("$.firstResponse").value("MET"));

        assertThat(respondedAt(id)).isEqualTo(first);
    }

    @Test
    void answeringAfterTheDeadlineIsBreached() throws Exception {
        long id = createTicket(ana, "VPN fora", "HIGH");
        clock.advance(Duration.ofHours(2));

        assign(bruno, id, bruno).andExpect(jsonPath("$.firstResponse").value("BREACHED"));
    }

    @Test
    void noAnswerAfterTheDeadlineIsOverdue() throws Exception {
        long id = createTicket(ana, "VPN fora", "HIGH");
        clock.advance(Duration.ofMinutes(61));

        assertThat(field(id, "$.firstResponse")).isEqualTo("OVERDUE");
    }

    @Test
    void aPublicTeamCommentIsTheFirstResponseAndChangesTheETagOnlyOnce() throws Exception {
        long id = createTicket(ana, "VPN fora", "HIGH");
        String before = read(id).getHeader("ETag");
        clock.advance(Duration.ofMinutes(5));

        comment(bruno, id, false);
        MockHttpServletResponse afterFirst = read(id);
        assertThat(respondedAt(id)).isEqualTo(clock.instant());
        assertThat((String) JsonPath.read(afterFirst.getContentAsString(), "$.status")).isEqualTo("OPEN");
        assertThat(afterFirst.getHeader("ETag")).isNotEqualTo(before);

        // Later comments do not touch the ticket, so an edit open in another tab keeps working.
        comment(diego, id, false);
        assertThat(read(id).getHeader("ETag")).isEqualTo(afterFirst.getHeader("ETag"));
    }

    @Test
    void internalNotesAndTheRequesterDoNotCount() throws Exception {
        long id = createTicket(ana, "VPN fora", "HIGH");

        comment(bruno, id, true);
        comment(ana, id, false);

        assertThat(respondedAt(id)).isNull();
        assertThat(field(id, "$.firstResponse")).isEqualTo("PENDING");
    }

    @Test
    void anAgentNeverAnswersTheirOwnTicket() throws Exception {
        long id = createTicket(bruno, "Meu monitor", "HIGH");

        comment(bruno, id, false);
        assign(bruno, id, bruno)
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.firstResponse").value("PENDING"));
        assertThat(respondedAt(id)).isNull();

        comment(diego, id, false);
        assertThat(respondedAt(id)).isEqualTo(clock.instant());
    }

    @Test
    void aManagerAssigningTheirOwnTicketIsNotAnAnswer() throws Exception {
        long id = createTicket(carla, "Projetor da sala", "HIGH");

        assign(carla, id, bruno);
        assertThat(respondedAt(id)).isNull();

        comment(bruno, id, false);
        assertThat(respondedAt(id)).isEqualTo(clock.instant());
    }

    @Test
    void anAgentsOwnTicketResolvedWithoutAnAnswerHasNoIndicator() throws Exception {
        long id = createTicket(bruno, "Meu monitor", "HIGH");
        assign(bruno, id, bruno);

        mvc.perform(post("/api/tickets/{id}/status", id)
                        .header("Authorization", bearer(bruno))
                        .header("If-Match", etag(version(id)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"RESOLVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstResponse").value(nullValue()));
    }

    @Test
    void ticketsFromBeforeTheMetricHaveNoIndicatorAndStaySo() throws Exception {
        long id = createTicket(ana, "VPN fora", "HIGH");
        jdbc.update("UPDATE tickets SET first_response_due_at = NULL WHERE id = ?", id);

        assign(bruno, id, bruno).andExpect(jsonPath("$.firstResponse").value(nullValue()));
        mvc.perform(patch("/api/tickets/{id}", id)
                        .header("Authorization", bearer(carla))
                        .header("If-Match", etag(version(id)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"priority\": \"CRITICAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstResponseDueAt").value(nullValue()))
                .andExpect(jsonPath("$.firstRespondedAt").value(nullValue()))
                .andExpect(jsonPath("$.firstResponse").value(nullValue()));
    }
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `./mvnw test -Dtest=FirstResponseSlaApiTest`
Expected: FAIL — `$.firstResponseDueAt`, `$.firstRespondedAt` e `$.firstResponse` não existem no JSON (`PathNotFoundException`), e os testes de comentário falham porque ainda não registram nada.

- [ ] **Step 3: `TicketResponse`**

Em `TicketDtos.java`, acrescentar `import com.ticketflow.sla.FirstResponseIndicator;` e trocar o record por:

```java
    public record TicketResponse(
            Long id,
            String title,
            String description,
            Priority priority,
            TicketStatus status,
            CategoryResponse category,
            UserSummary requester,
            UserSummary assignee,
            Instant createdAt,
            Instant dueAt,
            Instant resolvedAt,
            Boolean slaBreached,
            SlaIndicator sla,
            Instant firstResponseDueAt,
            Instant firstRespondedAt,
            FirstResponseIndicator firstResponse,
            long version) {

        public static TicketResponse from(Ticket ticket, SlaIndicator sla, FirstResponseIndicator firstResponse) {
            return new TicketResponse(ticket.getId(), ticket.getTitle(), ticket.getDescription(),
                    ticket.getPriority(), ticket.getStatus(), CategoryResponse.from(ticket.getCategory()),
                    UserSummary.from(ticket.getRequester()), UserSummary.from(ticket.getAssignee()),
                    ticket.getCreatedAt(), ticket.getDueAt(), ticket.getResolvedAt(), ticket.getSlaBreached(),
                    sla, ticket.getFirstResponseDueAt(), ticket.getFirstRespondedAt(), firstResponse,
                    ticket.getVersion());
        }
    }
```

- [ ] **Step 4: `TicketService.toResponse`**

```java
    public TicketResponse toResponse(Ticket ticket) {
        return TicketResponse.from(ticket,
                sla.indicator(ticket.getStatus(), ticket.getPriority(), ticket.getDueAt(), ticket.getSlaBreached()),
                sla.firstResponseIndicator(ticket.getStatus(), ticket.getFirstResponseDueAt(),
                        ticket.getFirstRespondedAt()));
    }
```

- [ ] **Step 5: `CommentService.add`**

Depois de `events.publishEvent(new CommentAdded(ticket, author, now));`:

```java
        if (author.canBeAssigned()) {
            // A public answer from the team; the ticket itself ignores it when the author is the requester.
            ticket.recordFirstResponse(author, now);
        }
```

- [ ] **Step 6: Rodar e ver passar**

Run: `./mvnw test -Dtest=FirstResponseSlaApiTest`
Expected: 11 testes passam.

- [ ] **Step 7: Suíte inteira**

Run: `./mvnw verify`
Expected: BUILD SUCCESS. Se algum teste existente comparar o JSON inteiro do chamado, ele precisa dos três campos novos; ajustar só a expectativa.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/ticketflow/ticket/TicketDtos.java backend/src/main/java/com/ticketflow/ticket/TicketService.java backend/src/main/java/com/ticketflow/comment/CommentService.java backend/src/test/java/com/ticketflow/sla/FirstResponseSlaApiTest.java
git commit -m "feat: expose the first-response SLA and count public team comments

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Demo com os quatro estados

**Files:**
- Modify: `backend/src/main/java/com/ticketflow/demo/DemoDataSeeder.java` (constantes, idade dos chamados em `seed`, `play`)
- Test: `backend/src/test/java/com/ticketflow/demo/DemoDataSeederTest.java`
- Test: `backend/src/test/java/com/ticketflow/demo/DemoDataSeederBusinessHoursTest.java`

**Interfaces:**
- Consumes: `SlaCalculator.after`, `SlaCalculator.firstResponseDeadlineFor` (Task 1); `Ticket.assign(User, User, Instant, SlaCalculator)` (Task 2); `$.content[*].firstResponse` na lista (Task 3).

- [ ] **Step 1: Testes que falham**

Em `DemoDataSeederTest.seedsDemoUsersAndTicketsInEverySlaSituation`, acrescentar no fim:

```java
        String all = mvc.perform(get("/api/tickets").param("size", "100").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        java.util.List<String> firstResponses = com.jayway.jsonpath.JsonPath.read(all, "$.content[*].firstResponse");
        assertThat(firstResponses).contains("PENDING", "OVERDUE", "MET", "BREACHED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM tickets WHERE assignee_id IS NOT NULL AND first_responded_at IS NULL",
                Long.class)).isZero();
```

Em `DemoDataSeederBusinessHoursTest`, acrescentar no fim do teste (o nome passa a `demoResetInTheMiddleOfTheNightStillShowsEverySituation`):

```java
        String all = mvc.perform(get("/api/tickets").param("size", "100").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        java.util.List<String> firstResponses = JsonPath.read(all, "$.content[*].firstResponse");
        assertThat(firstResponses).contains("PENDING", "OVERDUE", "MET", "BREACHED");
```

e atualizar o Javadoc da classe para: `/** With business hours on, resetting the demo at night must still produce every SLA situation. */`

- [ ] **Step 2: Rodar e ver falhar**

Run: `./mvnw test -Dtest='DemoDataSeederTest,DemoDataSeederBusinessHoursTest'`
Expected: FAIL — falta `PENDING` (todos os `OPEN` estão atrasados na 1ª resposta).

- [ ] **Step 3: Seeder**

Depois de `RUNNING_AGE`:

```java
    /** Fraction of the first-response deadline used before the agent took the ticket: two on time, one late. */
    private static final double[] FIRST_RESPONSE_PACE = {0.4, 0.7, 1.4};
```

Em `seed`, trocar o cálculo de `age` por:

```java
            double running = RUNNING_AGE[i % RUNNING_AGE.length];
            boolean newcomer = (i / Scenario.values().length) % 2 == 0;
            Duration age = switch (scenario) {
                // Half the open tickets are new (first response pending or overdue); the other half and the
                // in-progress ones keep the on-track / at-risk / overdue mix of the resolution SLA.
                case OPEN -> scale(newcomer ? sla.firstResponseDeadlineFor(priority) : sla.deadlineFor(priority),
                        running);
                case IN_PROGRESS -> scale(sla.deadlineFor(priority), running);
                default -> Duration.ofHours(6 + random.nextInt(24 * 6));
            };
```

e a chamada de `play` por:

```java
                play(ticket, scenario, agent, requester, createdAt, age,
                        FIRST_RESPONSE_PACE[i % FIRST_RESPONSE_PACE.length]);
```

Em `play`, acrescentar o parâmetro `double firstResponsePace` e trocar a linha de `assignedAt` por:

```java
        // The first response is placed on the SLA clock, so on time / late does not depend on the hour of the
        // reset. It never comes after 30% of the age, so it stays before the next steps; capping only moves it
        // earlier, so it never turns an on-time answer into a late one.
        Instant firstResponse = sla.after(createdAt,
                scale(sla.firstResponseDeadlineFor(ticket.getPriority()), firstResponsePace));
        Instant latest = createdAt.plus(scale(age, 0.3));
        Instant assignedAt = firstResponse.isBefore(latest) ? firstResponse : latest;
```

(`secondStep` e `thirdStep` continuam em 40% e 70% da idade.)

- [ ] **Step 4: Rodar e ver passar**

Run: `./mvnw test -Dtest='DemoDataSeederTest,DemoDataSeederBusinessHoursTest'`
Expected: passam, incluindo as checagens antigas de `OVERDUE`/`AT_RISK` do SLA de resolução.

- [ ] **Step 5: Suíte inteira**

Run: `./mvnw verify`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/ticketflow/demo/DemoDataSeeder.java backend/src/test/java/com/ticketflow/demo
git commit -m "feat: place the demo first responses on the SLA clock

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Frontend — selo "1ª resposta" no detalhe

**Files:**
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/shared/labels.ts`
- Create: `frontend/src/tickets/FirstResponseBadge.tsx`
- Modify: `frontend/src/tickets/TicketSummary.tsx`
- Modify: `frontend/src/tickets/permissions.test.ts`, `frontend/src/tickets/api.test.tsx` (fixtures de `Ticket`)
- Test: `frontend/src/tickets/FirstResponseBadge.test.tsx`

**Interfaces:**
- Consumes: JSON de Task 3.
- Produces: `type FirstResponseIndicator = 'PENDING' | 'OVERDUE' | 'MET' | 'BREACHED'`; `Ticket.firstResponseDueAt: string | null`, `Ticket.firstRespondedAt: string | null`, `Ticket.firstResponse: FirstResponseIndicator | null`; `<FirstResponseBadge indicator dueAt now? />`.

- [ ] **Step 1: Teste que falha**

`frontend/src/tickets/FirstResponseBadge.test.tsx`:

```tsx
import { screen } from '@testing-library/react'
import { act } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { renderUi } from '../test/render'
import { FirstResponseBadge } from './FirstResponseBadge'

const now = new Date('2026-09-29T12:00:00Z')

describe('FirstResponseBadge', () => {
  it('shows how much time is left while nobody has answered', () => {
    renderUi(<FirstResponseBadge indicator="PENDING" dueAt="2026-09-29T12:25:00Z" now={now} />)

    expect(screen.getByText('Pendente')).toBeInTheDocument()
    expect(screen.getByText('vence em 25 min')).toBeInTheDocument()
  })

  it('shows how late the answer is', () => {
    renderUi(<FirstResponseBadge indicator="OVERDUE" dueAt="2026-09-29T10:00:00Z" now={now} />)

    expect(screen.getByText('Atrasada')).toBeInTheDocument()
    expect(screen.getByText('venceu há 2 h')).toBeInTheDocument()
  })

  it('shows the result without a countdown once someone answered', () => {
    const { unmount } = renderUi(
      <FirstResponseBadge indicator="MET" dueAt="2026-09-29T10:00:00Z" now={now} />,
    )
    expect(screen.getByText('No prazo')).toBeInTheDocument()
    expect(screen.queryByText(/vence/)).not.toBeInTheDocument()
    unmount()

    renderUi(<FirstResponseBadge indicator="BREACHED" dueAt="2026-09-29T10:00:00Z" now={now} />)
    expect(screen.getByText('Fora do prazo')).toBeInTheDocument()
    expect(screen.queryByText(/vence/)).not.toBeInTheDocument()
  })

  it('renders nothing when the metric does not apply', () => {
    renderUi(<FirstResponseBadge indicator={null} dueAt={null} now={now} />)

    for (const label of ['Pendente', 'Atrasada', 'No prazo', 'Fora do prazo']) {
      expect(screen.queryByText(label)).not.toBeInTheDocument()
    }
  })

  describe('with the real clock', () => {
    afterEach(() => vi.useRealTimers())

    it('turns late on its own when the deadline passes while the page is open', () => {
      vi.useFakeTimers()
      vi.setSystemTime(now)
      renderUi(<FirstResponseBadge indicator="PENDING" dueAt="2026-09-29T12:10:00Z" />)
      expect(screen.getByText('Pendente')).toBeInTheDocument()

      act(() => {
        vi.advanceTimersByTime(11 * 60_000)
      })

      expect(screen.getByText('Atrasada')).toBeInTheDocument()
      expect(screen.getByText('venceu há 1 min')).toBeInTheDocument()
    })
  })
})
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `npx vitest run src/tickets/FirstResponseBadge.test.tsx`
Expected: FAIL — `./FirstResponseBadge` não existe.

- [ ] **Step 3: Tipos e rótulos**

`frontend/src/api/types.ts`, depois de `SlaIndicator`:

```ts
/** null in a Ticket means the metric does not apply (old ticket, or nobody but the requester acted on it). */
export type FirstResponseIndicator = 'PENDING' | 'OVERDUE' | 'MET' | 'BREACHED'
```

e no `interface Ticket`, depois de `sla: SlaIndicator`:

```ts
  firstResponseDueAt: string | null
  firstRespondedAt: string | null
  firstResponse: FirstResponseIndicator | null
```

`frontend/src/shared/labels.ts`: incluir `FirstResponseIndicator` no `import type` e acrescentar depois de `SLA_COLORS`:

```ts
export const FIRST_RESPONSE_LABELS: Record<FirstResponseIndicator, string> = {
  PENDING: 'Pendente',
  OVERDUE: 'Atrasada',
  MET: 'No prazo',
  BREACHED: 'Fora do prazo',
}

export const FIRST_RESPONSE_COLORS: Record<FirstResponseIndicator, string> = {
  PENDING: 'blue',
  OVERDUE: 'red',
  MET: 'teal',
  BREACHED: 'red',
}
```

Nas fixtures `ticket` de `permissions.test.ts` e `api.test.tsx`, depois de `sla: 'ON_TRACK',`:

```ts
    firstResponseDueAt: null,
    firstRespondedAt: null,
    firstResponse: null,
```

- [ ] **Step 4: Componente**

`frontend/src/tickets/FirstResponseBadge.tsx`:

```tsx
import { Badge, Stack, Text } from '@mantine/core'
import type { FirstResponseIndicator } from '../api/types'
import { formatDueIn } from '../shared/format'
import { FIRST_RESPONSE_COLORS, FIRST_RESPONSE_LABELS } from '../shared/labels'
import { useNow } from '../shared/useNow'

interface FirstResponseBadgeProps {
  indicator: FirstResponseIndicator | null
  dueAt: string | null
  /** Only tests pass it; the screen uses a clock that ticks. */
  now?: Date
}

/**
 * First-response result computed by the backend. While nobody has answered, a countdown keeps ticking
 * and a deadline that passes with the page open flips "Pendente" to "Atrasada" without a reload.
 */
export function FirstResponseBadge({ indicator, dueAt, now }: FirstResponseBadgeProps) {
  const liveNow = useNow()
  if (indicator === null || dueAt === null) {
    return null
  }
  const current = now ?? liveNow
  const waiting = indicator === 'PENDING' || indicator === 'OVERDUE'
  const shown: FirstResponseIndicator =
    indicator === 'PENDING' && current.getTime() >= new Date(dueAt).getTime() ? 'OVERDUE' : indicator
  return (
    <Stack gap={2} align="flex-start">
      <Badge color={FIRST_RESPONSE_COLORS[shown]} variant={shown === 'OVERDUE' ? 'filled' : 'light'}>
        {FIRST_RESPONSE_LABELS[shown]}
      </Badge>
      {waiting && (
        <Text size="xs" c="dimmed">
          {formatDueIn(dueAt, current)}
        </Text>
      )}
    </Stack>
  )
}
```

`frontend/src/tickets/TicketSummary.tsx`: importar `FirstResponseBadge` e acrescentar logo depois do `Field` "SLA":

```tsx
        {ticket.firstResponse && (
          <Field label="1ª resposta">
            <FirstResponseBadge indicator={ticket.firstResponse} dueAt={ticket.firstResponseDueAt} />
          </Field>
        )}
```

- [ ] **Step 5: Rodar e ver passar**

Run: `npx vitest run src/tickets/FirstResponseBadge.test.tsx`
Expected: 5 testes passam.

- [ ] **Step 6: Checagens completas do frontend**

Run: `npx vitest run && npx tsc --noEmit -p tsconfig.app.json && npm run lint && npm run build`
Expected: tudo verde. Se o `tsc` apontar outra fixture de `Ticket` sem os campos novos, acrescentar os três campos com `null` nela.

- [ ] **Step 7: Commit**

```bash
git add frontend/src
git commit -m "feat: show the first-response SLA on the ticket detail

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Verificação final e PR

- [ ] **Step 1: Tudo de novo, do zero**

Run (em `backend/`): `./mvnw verify` — Expected: BUILD SUCCESS.
Run (em `frontend/`): `npx vitest run && npx tsc --noEmit -p tsconfig.app.json && npm run lint && npm run build` — Expected: verde.

- [ ] **Step 2: Conferência no navegador (stack local)**

`docker compose up -d --build` na raiz; recriar a demo (`update demo_state set last_reset_at = now() - interval '25 hours'` no container do banco e `docker compose restart backend`). Entrar como Gestor e abrir um chamado de cada estado: "Pendente" com contagem, "Atrasada", "No prazo", "Fora do prazo". Como Atendente, comentar num chamado pendente e ver o selo virar "No prazo" sem erro 412 depois de editar a prioridade.

- [ ] **Step 3: Push e PR (só com autorização explícita do usuário)**

```bash
git push -u origin feat/first-response-sla
gh pr create --title "feat: add the first-response SLA" --body-file <arquivo com a descrição em português>
```

A descrição resume as decisões da spec (o que conta como resposta, quem não conta, indicador `null`, ETag) e termina com:

```
🤖 Generated with [Claude Code](https://claude.com/claude-code)
```
