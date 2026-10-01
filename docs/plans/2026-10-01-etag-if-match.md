# ETag e If-Match — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Trocar o campo `version` no corpo das mutações de chamado pelo padrão HTTP: `ETag` nas respostas, `If-Match` nas mutações, com 428, 400 e 412.

**Architecture:** O helper `TicketETag` converte entre `long` e o ETag forte `"N"`. O `TicketController` lê `If-Match` (ausente → 428; malformado → 400) e passa a versão esperada ao `TicketService`, que responde 412 quando ela diverge. Todas as respostas de chamado único levam `ETag`. O CORS permite `If-Match` e expõe `ETag`. O frontend envia `If-Match` a partir da `version` em cache.

**Tech Stack:** Java 21, Spring Boot (Spring MVC `ResponseEntity.eTag`, `@RequestHeader`), JUnit 5, AssertJ, MockMvc, Testcontainers; React, TanStack Query, Vitest.

**Spec:** `docs/specs/2026-10-01-etag-if-match-design.md`

## Global Constraints

- **Pré-requisito:** o PR #15 (roadmap 1.1) precisa estar no `main` antes da Tarefa 1, porque ambos mexem no `TicketService`.
- Código e identificadores em **inglês**; mensagens da API em **português**, exatamente:
  - 428: `Envie o cabeçalho If-Match com o ETag do chamado.`
  - 400: `If-Match inválido. Envie o ETag recebido, por exemplo "3".`
  - 412: a constante existente `ApiExceptionHandler.STALE_VERSION`.
- O ETag é **forte e numérico**: `"N"`. `W/"N"`, `*`, listas e valores não numéricos são recusados com 400.
- Ordem das checagens: formato do header no controller (428/400) → existência e permissão no service (404/403) → versão (412).
- `OptimisticLockingFailureException` **continua 409** (`ApiExceptionHandler` não muda).
- O campo `version` sai de `UpdateTicketRequest`, `AssignRequest` e `ChangeStatusRequest`; `TicketResponse.version` **fica**.
- Commits com a linha `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Maven em `backend/` com `./mvnw` (Docker ligado). Frontend em `frontend/` com `npm test`, `npm run lint` e `npm run build`.

## Review Focus

1. Preflight CORS do navegador com `If-Match`: sem `allowedHeaders`, a produção quebra e o MockMvc sem `Origin` não percebe. Coberto em `CorsApiTest` (Tarefa 2).
2. `If-Match` com espaços nas pontas ou número enorme (`"99999999999999999999"`) deve dar, respectivamente, sucesso e 400, nunca 500. Coberto em `TicketETagTest` (Tarefa 1).
3. Um `PATCH` que não muda nada devolve o **mesmo** ETag, não um incrementado. Coberto em `TicketConditionalRequestApiTest` (Tarefa 2).
4. O `GET` com `ETag` faz o Spring responder 304 sozinho a um `If-None-Match` igual (o cache do navegador pode mandá-lo). O teste fixa esse comportamento para ninguém se surpreender (Tarefa 2).
5. Um 412 não pode alterar o chamado nem gravar histórico. Coberto em `TicketConditionalRequestApiTest` (Tarefa 2).

## Mapa de arquivos

| Arquivo | Ação | Responsabilidade |
|---|---|---|
| `backend/src/main/java/com/ticketflow/ticket/TicketETag.java` | criar | `format(long)` e `parse(String)` do ETag forte |
| `backend/src/main/java/com/ticketflow/common/ApiException.java` | alterar | `preconditionFailed` (412) e `preconditionRequired` (428) |
| `backend/src/main/java/com/ticketflow/ticket/TicketDtos.java` | alterar | `version` sai dos 3 requests |
| `backend/src/main/java/com/ticketflow/ticket/TicketService.java` | alterar | recebe `expectedVersion`; 412 |
| `backend/src/main/java/com/ticketflow/ticket/TicketController.java` | alterar | lê `If-Match`, devolve `ETag` |
| `backend/src/main/java/com/ticketflow/common/SecurityConfig.java` | alterar | CORS: `If-Match` permitido, `ETag` exposto |
| `backend/src/test/java/com/ticketflow/ticket/TicketETagTest.java` | criar | unidade |
| `backend/src/test/java/com/ticketflow/ticket/TicketConditionalRequestApiTest.java` | criar | contrato HTTP |
| `backend/src/test/java/com/ticketflow/support/IntegrationTest.java` | alterar | helper `etag(long)` |
| 8 testes de API existentes | alterar | `version` do corpo → header `If-Match` |
| `backend/src/test/java/com/ticketflow/common/CorsApiTest.java` | alterar | preflight com `if-match` e `ETag` exposto |
| `frontend/src/api/client.ts` | alterar | headers opcionais em `post`/`patch` |
| `frontend/src/tickets/api.ts` | alterar | `If-Match`; recarrega em 409 e 412 |
| `frontend/src/test/render.tsx` | alterar | helper `sentHeaders` |
| `frontend/src/tickets/api.test.tsx` | alterar | header em vez de corpo; caso 412 |
| `docs/roadmap-de-evolucao.md` | alterar | marca 1.2 como implementado |

---

### Task 1: `TicketETag` e os novos `ApiException`

**Files:**
- Create: `backend/src/main/java/com/ticketflow/ticket/TicketETag.java`
- Modify: `backend/src/main/java/com/ticketflow/common/ApiException.java`
- Test: `backend/src/test/java/com/ticketflow/ticket/TicketETagTest.java`

**Interfaces:**
- Produces:
  - `TicketETag.format(long version) → String` (ex.: `3` → `"3"` com aspas)
  - `TicketETag.parse(String ifMatch) → long` (lança `ApiException` 400)
  - `TicketETag.INVALID_IF_MATCH` e `TicketETag.MISSING_IF_MATCH` (`public static final String`)
  - `ApiException.preconditionFailed(String) → ApiException` (412)
  - `ApiException.preconditionRequired(String) → ApiException` (428)

- [ ] **Step 1: Branch e documentos**

Confirme que o PR #15 foi mergeado (`gh pr view 15 --json state -q .state` deve dizer `MERGED`). Se não foi, **pare e avise**. Depois:

```bash
git switch main
git pull
git switch -c feat/etag-if-match
git add docs/specs/2026-10-01-etag-if-match-design.md docs/plans/2026-10-01-etag-if-match.md
git commit -m "docs: add the ETag/If-Match spec and plan

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 2: Escrever o teste que falha**

`backend/src/test/java/com/ticketflow/ticket/TicketETagTest.java`:

```java
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
```

> Se `junit-jupiter-params` não estiver no classpath (ele vem com `spring-boot-starter-test`), troque o `@ParameterizedTest` por um `@Test` com laço sobre o mesmo array e registre um *Ruling*.

- [ ] **Step 3: Rodar e ver falhar**

Run (em `backend/`): `./mvnw test -Dtest=TicketETagTest`
Expected: FAIL na compilação (`cannot find symbol ... TicketETag` / `preconditionFailed`).

- [ ] **Step 4: Implementar**

`backend/src/main/java/com/ticketflow/ticket/TicketETag.java`:

```java
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
```

Em `ApiException.java`, depois de `conflict(...)`:

```java
    public static ApiException preconditionFailed(String detail) {
        return new ApiException(HttpStatus.PRECONDITION_FAILED, detail);
    }

    public static ApiException preconditionRequired(String detail) {
        return new ApiException(HttpStatus.PRECONDITION_REQUIRED, detail);
    }
```

- [ ] **Step 5: Rodar e ver passar**

Run (em `backend/`): `./mvnw test -Dtest=TicketETagTest`
Expected: PASS (4 testes; o parametrizado conta 8 casos).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/ticketflow/ticket/TicketETag.java backend/src/main/java/com/ticketflow/common/ApiException.java backend/src/test/java/com/ticketflow/ticket/TicketETagTest.java
git commit -m "feat: add the ticket ETag helper and the 412/428 API errors

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Contrato HTTP no backend (ETag, If-Match, CORS)

**Files:**
- Create: `backend/src/test/java/com/ticketflow/ticket/TicketConditionalRequestApiTest.java`
- Modify: `backend/src/test/java/com/ticketflow/support/IntegrationTest.java`
- Modify: `backend/src/test/java/com/ticketflow/common/CorsApiTest.java`
- Modify: `backend/src/main/java/com/ticketflow/ticket/TicketDtos.java`
- Modify: `backend/src/main/java/com/ticketflow/ticket/TicketService.java`
- Modify: `backend/src/main/java/com/ticketflow/ticket/TicketController.java`
- Modify: `backend/src/main/java/com/ticketflow/common/SecurityConfig.java`
- Modify (testes existentes): `TicketWorkflowApiTest`, `TicketUpdateApiTest`, `CommentApiTest`, `AttachmentApiTest`, `DashboardApiTest`, `TicketSearchApiTest`, `UserApiTest`, `DomainEventsApiTest`

**Interfaces:**
- Consumes: `TicketETag.format/parse/MISSING_IF_MATCH`, `ApiException.preconditionFailed/preconditionRequired` (Tarefa 1).
- Produces:
  - `TicketService.update(Long id, UpdateTicketRequest request, long expectedVersion, AuthUser authUser)`
  - `TicketService.assign(Long id, AssignRequest request, long expectedVersion, AuthUser authUser)`
  - `TicketService.changeStatus(Long id, ChangeStatusRequest request, long expectedVersion, AuthUser authUser)`
  - `IntegrationTest.etag(long version) → String` (helper protegido e estático)
  - Contrato HTTP consumido pelo frontend na Tarefa 3: `If-Match: "N"` nas mutações, `ETag` nas respostas, 412 na versão velha.

- [ ] **Step 1: Helper de teste**

Em `IntegrationTest.java`, depois de `readLong(...)`:

```java
    /** The If-Match value for a ticket version, as the API's ETag header writes it: "3". */
    protected static String etag(long version) {
        return "\"" + version + "\"";
    }
```

- [ ] **Step 2: Escrever o teste de contrato que falha**

`backend/src/test/java/com/ticketflow/ticket/TicketConditionalRequestApiTest.java`:

```java
package com.ticketflow.ticket;

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
    void readingWithAMatchingIfNoneMatchIsNotModified() throws Exception {
        // Not something we built: Spring MVC answers 304 by itself once the response has an ETag.
        mvc.perform(get("/api/tickets/{id}", ticketId)
                        .header("Authorization", bearer(ana))
                        .header(HttpHeaders.IF_NONE_MATCH, etag(0)))
                .andExpect(status().isNotModified());
    }
}
```

> Os corpos desses testes são sempre válidos de propósito. O Spring desserializa e valida o `@RequestBody` antes de executar o método do controller, então um corpo inválido responderia 400 antes de o 428 ser avaliado.

- [ ] **Step 3: Teste de CORS que falha**

Em `CorsApiTest.java`, acrescente os imports `import static org.hamcrest.Matchers.containsStringIgnoringCase;` e `import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;`, e os testes:

```java
    @Test
    void letsTheFrontendSendIfMatch() throws Exception {
        mvc.perform(options("/api/tickets/1/status")
                        .header("Origin", FRONTEND)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization, content-type, if-match"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Headers", containsStringIgnoringCase("if-match")));
    }

    @Test
    void letsTheFrontendReadTheETag() throws Exception {
        mvc.perform(get("/api/categories").header("Origin", FRONTEND))
                .andExpect(header().string("Access-Control-Expose-Headers", containsStringIgnoringCase("ETag")));
    }
```

> Se `GET /api/categories` exigir autenticação e responder 401 sem os headers de CORS, adicione `.header("Authorization", bearer(createUser("Ana", Role.REQUESTER)))` (imports de `Role`) e registre um *Ruling*.

- [ ] **Step 4: Rodar e ver falhar**

Run (em `backend/`): `./mvnw test -Dtest="TicketConditionalRequestApiTest,CorsApiTest"`
Expected: FAIL. Faltam os headers `ETag`; as mutações sem `version` no corpo respondem 400 ("Informe a versão.") em vez de 200/428; o preflight com `if-match` é recusado (403); `Access-Control-Expose-Headers` não contém `ETag`.

- [ ] **Step 5: DTOs sem `version`**

Em `TicketDtos.java`, substitua os três records:

```java
    /** Fields left null are not changed. The expected version travels in the If-Match header. */
    public record UpdateTicketRequest(Priority priority, Long categoryId) {
    }

    public record AssignRequest(@NotNull(message = "Informe o responsável.") Long assigneeId) {
    }

    public record ChangeStatusRequest(@NotNull(message = "Informe o status.") TicketStatus status) {
    }
```

- [ ] **Step 6: `TicketService` recebe a versão esperada e responde 412**

Em `TicketService.java`:

1. O import estático de `STALE_VERSION` continua como está.
2. Assinaturas e chamadas:

```java
    public TicketResponse assign(Long id, AssignRequest request, long expectedVersion, AuthUser authUser) {
```
e dentro dele `requireVersion(ticket, request.version());` → `requireVersion(ticket, expectedVersion);`

```java
    public TicketResponse changeStatus(Long id, ChangeStatusRequest request, long expectedVersion,
            AuthUser authUser) {
```
e `requireVersion(ticket, request.version());` → `requireVersion(ticket, expectedVersion);`

```java
    public TicketResponse update(Long id, UpdateTicketRequest request, long expectedVersion, AuthUser authUser) {
```
e `requireVersion(ticket, request.version());` → `requireVersion(ticket, expectedVersion);`

3. O método `requireVersion`:

```java
    /** The client sends, in If-Match, the version it read; if someone changed the ticket since, we refuse. */
    private static void requireVersion(Ticket ticket, long expectedVersion) {
        if (ticket.getVersion() != expectedVersion) {
            throw ApiException.preconditionFailed(STALE_VERSION);
        }
    }
```

- [ ] **Step 7: `TicketController` lê `If-Match` e devolve `ETag`**

Em `TicketController.java`:

1. Imports: adicione `org.springframework.web.bind.annotation.RequestHeader`; remova `org.springframework.web.bind.annotation.ResponseStatus` (não será mais usado).
2. Substitua `create`, `get`, `update`, `assign` e `changeStatus` por:

```java
    @PostMapping
    public ResponseEntity<TicketResponse> create(@Valid @RequestBody CreateTicketRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return withETag(ResponseEntity.status(HttpStatus.CREATED), tickets.create(request, AuthUser.from(jwt)));
    }
```

```java
    @GetMapping("/{id}")
    public ResponseEntity<TicketResponse> get(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return withETag(ResponseEntity.ok(), tickets.get(id, AuthUser.from(jwt)));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('AGENT', 'MANAGER')")
    public ResponseEntity<TicketResponse> update(@PathVariable Long id, @Valid @RequestBody UpdateTicketRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @AuthenticationPrincipal Jwt jwt) {
        return withETag(ResponseEntity.ok(),
                tickets.update(id, request, expectedVersion(ifMatch), AuthUser.from(jwt)));
    }

    @PostMapping("/{id}/assign")
    @PreAuthorize("hasAnyRole('AGENT', 'MANAGER')")
    public ResponseEntity<TicketResponse> assign(@PathVariable Long id, @Valid @RequestBody AssignRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @AuthenticationPrincipal Jwt jwt) {
        return withETag(ResponseEntity.ok(),
                tickets.assign(id, request, expectedVersion(ifMatch), AuthUser.from(jwt)));
    }

    @PostMapping("/{id}/status")
    public ResponseEntity<TicketResponse> changeStatus(@PathVariable Long id,
            @Valid @RequestBody ChangeStatusRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @AuthenticationPrincipal Jwt jwt) {
        return withETag(ResponseEntity.ok(),
                tickets.changeStatus(id, request, expectedVersion(ifMatch), AuthUser.from(jwt)));
    }

    /**
     * If-Match is required on every change: without it the API cannot tell whether the client saw the
     * latest version. Missing is 428 (RFC 6585); malformed is 400; a stale version is 412, in the service.
     */
    private static long expectedVersion(String ifMatch) {
        if (ifMatch == null) {
            throw ApiException.preconditionRequired(TicketETag.MISSING_IF_MATCH);
        }
        return TicketETag.parse(ifMatch);
    }

    private static ResponseEntity<TicketResponse> withETag(ResponseEntity.BodyBuilder response, TicketResponse ticket) {
        return response.eTag(TicketETag.format(ticket.version())).body(ticket);
    }
```

> `required = false` é deliberado: com `required = true` o Spring responderia 400 por conta própria, e queremos 428 com a nossa mensagem.

- [ ] **Step 8: CORS**

Em `SecurityConfig.java`, no `corsConfigurationSource`:

```java
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", HttpHeaders.IF_MATCH));
        config.setExposedHeaders(List.of("Content-Disposition", TraceIdFilter.HEADER, "X-Export-Truncated",
                HttpHeaders.ETAG));
```

Adicione `import org.springframework.http.HttpHeaders;` se ainda não existir.

- [ ] **Step 9: Rodar os testes novos e ver passar**

Run (em `backend/`): `./mvnw test -Dtest="TicketConditionalRequestApiTest,CorsApiTest,TicketETagTest"`
Expected: PASS em todos.

- [ ] **Step 10: Migrar os testes existentes para `If-Match`**

Regra única, aplicada em cada linha listada: **retire `, \"version\": X` do JSON e acrescente `.header("If-Match", etag(X))` na mesma requisição** (logo depois do `.header("Authorization", ...)`). Quando X é um `%d`, passe a mesma variável para `etag(...)` e retire-a do `.formatted(...)`. Arquivos e pontos exatos:

- `ticket/TicketWorkflowApiTest.java`:
  - helper `assign(User actor, long ticketId, long assigneeId, long version)`:

    ```java
        ResultActions assign(User actor, long ticketId, long assigneeId, long version) throws Exception {
            return mvc.perform(post("/api/tickets/{id}/assign", ticketId)
                    .header("Authorization", bearer(actor))
                    .header("If-Match", etag(version))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"assigneeId\": %d}".formatted(assigneeId)));
        }
    ```
  - helper `changeStatus(User actor, long ticketId, String status, long version)`:

    ```java
        ResultActions changeStatus(User actor, long ticketId, String status, long version) throws Exception {
            return mvc.perform(post("/api/tickets/{id}/status", ticketId)
                    .header("Authorization", bearer(actor))
                    .header("If-Match", etag(version))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"status\": \"%s\"}".formatted(status)));
        }
    ```
  - o teste `staleVersionIsConflict` vira:

    ```java
        @Test
        void staleVersionIsPreconditionFailed() throws Exception {
            long id = createTicket(ana, "Impressora", "LOW");
            versionAfter(assign(bruno, id, bruno.getId(), 0));

            changeStatus(bruno, id, "RESOLVED", 0)
                    .andExpect(status().isPreconditionFailed())
                    .andExpect(jsonPath("$.detail").value(
                            "O chamado foi alterado por outra pessoa. Recarregue e tente novamente."));
        }
    ```
- `ticket/TicketUpdateApiTest.java`:
  - `setUp` (linha ~36): `.content("{\"assigneeId\": %d}".formatted(bruno.getId()))` + `.header("If-Match", etag(0))`.
  - o helper `patchTicket(User actor, String json)` ganha a versão:

    ```java
        ResultActions patchTicket(User actor, long version, String json) throws Exception {
            return mvc.perform(patch("/api/tickets/{id}", ticketId)
                    .header("Authorization", bearer(actor))
                    .header("If-Match", etag(version))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json));
        }
    ```
  - chamadas: `patchTicket(bruno, "{\"priority\": \"CRITICAL\", \"version\": 1}")` → `patchTicket(bruno, 1, "{\"priority\": \"CRITICAL\"}")`; `patchTicket(carla, "{\"categoryId\": %d, \"version\": 1}".formatted(...))` → `patchTicket(carla, 1, "{\"categoryId\": %d}".formatted(...))`; as duas de `otherAgentsAndRequestersCannotChangeTheTicket` → `patchTicket(diego, 1, "{\"priority\": \"HIGH\"}")` e `patchTicket(ana, 1, "{\"priority\": \"HIGH\"}")`.
  - `staleVersionIsConflict` vira `staleVersionIsPreconditionFailed`, com `patchTicket(bruno, 0, "{\"priority\": \"HIGH\"}").andExpect(status().isPreconditionFailed());`.
  - `closedTicketCannotBeChanged`: as duas mudanças de status recebem `.header("If-Match", etag(1))` e `.header("If-Match", etag(v))` com corpos `{"status": "RESOLVED"}` e `{"status": "CLOSED"}`; o patch final vira `patchTicket(carla, v, "{\"priority\": \"HIGH\"}").andExpect(status().isConflict());` (409 continua: é a regra "fechado não muda", não a versão).
- `comment/CommentApiTest.java`: helper `changeStatus(User actor, String status, long version)` (corpo `{"status": "%s"}` + `etag(version)`); `assignToBrunoAndAskRequester` e `closedTicketRejectsComments` (assign com `etag(0)`).
- `attachment/AttachmentApiTest.java` (`closedTicketRejectsAttachments`): assign `etag(0)`, RESOLVED `etag(1)`, CLOSED `etag(2)`.
- `dashboard/DashboardApiTest.java` (`assignAndResolve`): assign `etag(0)`, RESOLVED `etag(1)`.
- `ticket/TicketSearchApiTest.java` (`assignToBruno`): assign `etag(0)`.
- `user/UserApiTest.java` (`cannotDemoteOrDeactivateSomeoneWithTicketsInProgress`): assign `etag(0)`.
- `history/DomainEventsApiTest.java`: helpers `assign(..., long version)` e `changeStatus(..., long version)` como no `TicketWorkflowApiTest`; o helper `patchTicket(User actor, long ticketId, String json)` vira `patchTicket(User actor, long ticketId, long version, String json)` com `.header("If-Match", etag(version))`, e as duas chamadas viram `patchTicket(carla, id, 0, "{\"priority\": \"HIGH\", \"categoryId\": %d}".formatted(...))` e `patchTicket(carla, id, 0, "{\"priority\": \"LOW\", \"categoryId\": %d}".formatted(...))`; em `changingStatusPublishesStatusChanged`, a requisição inline recebe `.header("If-Match", etag(1))` e o corpo `{"status": "RESOLVED"}`.

Confira que não sobrou nada:

Run (na raiz): `git grep -n '\\"version\\"' -- backend/src/test`
Expected: nenhuma linha.

- [ ] **Step 11: Verificação completa**

Run (em `backend/`): `./mvnw verify`
Expected: BUILD SUCCESS, 0 falhas.

- [ ] **Step 12: Commit**

```bash
git add backend/src
git commit -m "feat: use ETag and If-Match for ticket optimistic locking

Mutations now require If-Match (428 when missing, 400 when malformed,
412 when stale) and every single-ticket response carries an ETag.
CORS allows If-Match and exposes ETag to the frontend.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Frontend envia `If-Match` e recarrega em 412

**Files:**
- Modify: `frontend/src/test/render.tsx`
- Modify: `frontend/src/tickets/api.test.tsx`
- Modify: `frontend/src/api/client.ts`
- Modify: `frontend/src/tickets/api.ts`
- Modify: `docs/roadmap-de-evolucao.md`

**Interfaces:**
- Consumes: o contrato HTTP da Tarefa 2.
- Produces: `api.post<T>(path, body?, headers?)`, `api.patch<T>(path, body, headers?)`, `sentHeaders(fetchMock, key) → Headers`.

- [ ] **Step 1: Helper `sentHeaders`**

Em `frontend/src/test/render.tsx`, substitua `sentBody` por:

```ts
function sentRequest(fetchMock: ReturnType<typeof mockApi>, key: string): RequestInit | undefined {
  const call = fetchMock.mock.calls.find(([input, init]) => requestKey(input, init) === key)
  if (!call) {
    throw new Error(`No request to ${key}`)
  }
  return call[1]
}

/** The JSON body of the (first) request sent to "METHOD /path". */
export function sentBody(fetchMock: ReturnType<typeof mockApi>, key: string): unknown {
  return JSON.parse(sentRequest(fetchMock, key)?.body as string)
}

/** The headers of the (first) request sent to "METHOD /path". */
export function sentHeaders(fetchMock: ReturnType<typeof mockApi>, key: string): Headers {
  return new Headers(sentRequest(fetchMock, key)?.headers)
}
```

- [ ] **Step 2: Testes que falham**

Em `frontend/src/tickets/api.test.tsx`:

1. Import: `import { mockApi, sentBody, sentHeaders } from '../test/render'`.
2. No primeiro teste, troque a linha do `expect(sentBody(...))` por:

```ts
    expect(sentHeaders(fetchMock, 'POST /tickets/7/status').get('If-Match')).toBe('"3"')
    expect(sentBody(fetchMock, 'POST /tickets/7/status')).toEqual({ status: 'WAITING_REQUESTER' })
```

3. Renomeie o título do `describe` para `'ticket mutations send the version on screen in If-Match'` e acrescente, no fim do `describe`:

```ts
  it('reloads the ticket on 412, when the If-Match version is no longer current', async () => {
    mockApi({ 'POST /tickets/7/status': [412, { status: 412, detail: 'O chamado foi alterado por outra pessoa.' }] })
    const { queryClient, wrapper } = setup()
    const { result } = renderHook(() => useChangeStatus(7), { wrapper })

    await act(() => result.current.mutateAsync({ status: 'RESOLVED', version: 3 }).catch(() => {}))

    expect(queryClient.getQueryState(ticketKeys.detail(7))?.isInvalidated).toBe(true)
  })
```

- [ ] **Step 3: Rodar e ver falhar**

Run (em `frontend/`): `npm test -- src/tickets/api.test.tsx`
Expected: FAIL. `If-Match` chega `null`, o corpo ainda contém `version` e o caso 412 não invalida o chamado.

- [ ] **Step 4: Headers opcionais no client**

Em `frontend/src/api/client.ts`:

```ts
async function send(method: string, path: string, body?: unknown, extraHeaders?: Record<string, string>): Promise<Response> {
  const token = PUBLIC_PATHS.includes(path) ? null : tokenStorage.get()
  const headers = new Headers(extraHeaders)
```

(o resto de `send` não muda)

```ts
async function json<T>(method: string, path: string, body?: unknown, headers?: Record<string, string>): Promise<T> {
  const response = await send(method, path, body, headers)
  return (await response.json()) as T
}
```

e no objeto `api`:

```ts
  post: <T>(path: string, body?: unknown, headers?: Record<string, string>) => json<T>('POST', path, body, headers),
  patch: <T>(path: string, body: unknown, headers?: Record<string, string>) => json<T>('PATCH', path, body, headers),
```

- [ ] **Step 5: Mutações com `If-Match`**

Em `frontend/src/tickets/api.ts`:

1. Troque o comentário e o `onError` de `useTicketChange`:

```ts
/**
 * Every change to a ticket answers with the updated ticket (and its new version). We put it
 * straight into the cache, so the next action on the same screen already sends the new version.
 * On 409 (a rule refused it, or a concurrent change was caught while saving) or 412 (the version
 * in If-Match is no longer current) we reload the ticket.
 */
```

```ts
    onError: (error) => {
      if (error instanceof ApiError && (error.status === 409 || error.status === 412)) {
        void queryClient.invalidateQueries({ queryKey: ticketKeys.detail(ticketId) })
      }
    },
```

2. Logo depois de `useTicketChange`, o helper e as três mutações:

```ts
/** The version on screen, as the API's ETag writes it: "only change it if it is still this version". */
function ifMatch(version: number): Record<string, string> {
  return { 'If-Match': `"${version}"` }
}

export function useChangeStatus(ticketId: number) {
  return useTicketChange(ticketId, ({ version, ...body }: { status: TicketStatus; version: number }) =>
    api.post<Ticket>(`/tickets/${ticketId}/status`, body, ifMatch(version)),
  )
}

export function useAssignTicket(ticketId: number) {
  return useTicketChange(ticketId, ({ version, ...body }: { assigneeId: number; version: number }) =>
    api.post<Ticket>(`/tickets/${ticketId}/assign`, body, ifMatch(version)),
  )
}

export function useUpdateTicket(ticketId: number) {
  return useTicketChange(
    ticketId,
    ({ version, ...body }: { priority?: Priority; categoryId?: number; version: number }) =>
      api.patch<Ticket>(`/tickets/${ticketId}`, body, ifMatch(version)),
  )
}
```

- [ ] **Step 6: Rodar e ver passar; lint e build**

Run (em `frontend/`): `npm test`
Expected: todos os testes passando.

Run (em `frontend/`): `npm run lint` e depois `npm run build`
Expected: sem erros (o `tsc -b` do build confere os tipos).

- [ ] **Step 7: Marcar o item no roadmap**

Em `docs/roadmap-de-evolucao.md`, logo abaixo de `### 1.2. Padrão HTTP RFC 7232 (\`ETag\` e \`If-Match\`)`, acrescente:

```markdown
- **Status**: ✅ Implementado (spec: `docs/specs/2026-10-01-etag-if-match-design.md`). Segue a RFC 9110, que substituiu a 7232: `ETag` nas respostas, `If-Match` obrigatório nas três mutações (428 se ausente, 400 se malformado, 412 se desatualizado).
```

- [ ] **Step 8: Commit**

```bash
git add frontend/src docs/roadmap-de-evolucao.md
git commit -m "feat: send the ticket version in If-Match from the frontend

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Depois das tarefas

Branch `feat/etag-if-match` com 4 commits (docs + 3 de código). Push e Pull Request só quando você pedir.

**Atenção no deploy:** backend e frontend sobem separados no Render. Entre o deploy de um e do outro, as mutações do frontend antigo recebem 428 (ou o frontend novo manda `If-Match` para um backend que exige `version`). A janela é curta e aceitável para a demo; para não ter janela nenhuma, faça o deploy dos dois juntos.

## Self-review (spec × plano)

- Critério 1 (ETag em criação, GET e mutações): Tarefa 2, `creatingAndReadingAnswerWithTheVersionAsETag` e `everyChangeAnswersWithTheNewETag`.
- Critério 2 (428/400/412): Tarefa 2, três testes dedicados; parse coberto em unidade na Tarefa 1.
- Critério 3 (`version` fora dos requests, presente na resposta): Tarefa 2, Passo 5; `git grep` no Passo 10; `TicketApiTest` continua conferindo `$.version`.
- Critério 4 (CORS): Tarefa 2, Passos 3 e 8.
- Critério 5 (frontend): Tarefa 3.
- Ordem das checagens (spec 3.1): `aRequesterWithoutAccessGets404EvenWithAStaleIfMatch` e o 428/400 resolvidos no controller.
- 409 da corrida no flush mantido: `ApiExceptionHandler` não é tocado.
- Nomes conferidos entre tarefas: `TicketETag.format/parse/MISSING_IF_MATCH/INVALID_IF_MATCH`, `ApiException.preconditionFailed/preconditionRequired`, `etag(long)`, `sentHeaders`.
