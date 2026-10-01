# Busca com trigramas — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fazer a busca `q=` da lista de chamados usar índices GIN de trigramas, sem mudar o comportamento nem o Java.

**Architecture:** Uma migration Flyway cria a extensão `pg_trgm` e dois índices GIN nas expressões `lower(title)` e `lower(description)`, as mesmas que o `TicketSpecifications` já usa. Um teste de integração prova pelo `EXPLAIN` que a consulta usa os índices.

**Tech Stack:** PostgreSQL 17 (`pg_trgm`), Flyway, Spring Boot, JUnit 5, Testcontainers.

**Spec:** `docs/specs/2026-10-01-trigram-search-design.md`

## Global Constraints

- Nenhuma mudança em Java de produção nem na API.
- Os nomes dos índices são exatamente `idx_tickets_title_trgm` e `idx_tickets_description_trgm`.
- A migration é a `V8__add_trigram_search_indexes.sql` (a última existente é a V7).
- Commits com `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Maven em `backend/` com `./mvnw` e Docker ligado.

## Review Focus

1. A expressão indexada precisa ser idêntica à do SQL do Hibernate (`lower(t.title)`). Coberto pelo `EXPLAIN` do teste.
2. O `LIKE ... ESCAPE '\'` com parâmetro de JDBC precisa continuar usando o índice. O teste usa parâmetros, como o Hibernate.
3. O `SET` do teste não pode vazar para outros testes pelo pool de conexões. Por isso o teste usa `SET LOCAL` dentro de uma transação.
4. A busca continua igual: `searchesTitleAndDescriptionCaseInsensitivelyAndLiterally` passa sem alteração.
5. A migration precisa rodar num banco vazio e num banco com dados. O `verify` cobre o banco vazio; os índices GIN são construídos sobre os dados existentes.

---

### Task 1: Índices de trigramas para a busca

**Files:**
- Create: `backend/src/main/resources/db/migration/V8__add_trigram_search_indexes.sql`
- Test: `backend/src/test/java/com/ticketflow/ticket/TicketSearchIndexTest.java`

**Interfaces:**
- Produces: os índices `idx_tickets_title_trgm` e `idx_tickets_description_trgm` na tabela `tickets`.

- [ ] **Step 1: Branch e documentos**

```bash
git switch main
git pull
git switch -c feat/trigram-search
git add docs/specs/2026-10-01-trigram-search-design.md docs/plans/2026-10-01-trigram-search.md
git commit -m "docs: add the trigram search spec and plan

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 2: Teste que falha**

`backend/src/test/java/com/ticketflow/ticket/TicketSearchIndexTest.java`:

```java
package com.ticketflow.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketflow.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The text search (q=) must use the trigram indexes instead of reading the whole table. The condition
 * below is the one TicketSpecifications generates through Hibernate: lower(column) LIKE ? ESCAPE '\'.
 */
class TicketSearchIndexTest extends IntegrationTest {

    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void theTextSearchUsesTheTrigramIndexes() {
        List<String> plan = new TransactionTemplate(transactionManager).execute(tx -> {
            // With an almost empty table the planner always prefers a full scan; this forbids it,
            // so the plan shows whether an index exists that can answer the query. LOCAL: undone at the end.
            jdbc.execute("SET LOCAL enable_seqscan = off");
            return jdbc.queryForList("""
                    EXPLAIN SELECT id FROM tickets
                    WHERE lower(title) LIKE ? ESCAPE '\\' OR lower(description) LIKE ? ESCAPE '\\'
                    """, String.class, "%impressora%", "%impressora%");
        });

        assertThat(String.join("\n", plan))
                .contains("idx_tickets_title_trgm")
                .contains("idx_tickets_description_trgm");
    }
}
```

> Se o PostgreSQL recusar parâmetros dentro do `EXPLAIN` ("could not determine data type" ou erro de sintaxe), use o padrão como literal (`'%impressora%'`) e registre um *Ruling*. O `like_escape` de uma constante é resolvido no planejamento da mesma forma.

- [ ] **Step 3: Rodar e ver falhar**

Run (em `backend/`): `./mvnw test -Dtest=TicketSearchIndexTest`
Expected: FAIL. O plano mostra `Seq Scan on tickets` (desabilitada, mas sem alternativa), sem os nomes dos índices.

- [ ] **Step 4: Migration**

`backend/src/main/resources/db/migration/V8__add_trigram_search_indexes.sql`:

```sql
-- Text search (q=) runs lower(title) LIKE '%term%' OR lower(description) LIKE '%term%'.
-- A leading % cannot use a B-tree index; trigram (GIN) indexes can. The indexed expressions must be
-- exactly the ones in the query (lower(...)), or PostgreSQL ignores the index.
-- No CONCURRENTLY: Flyway runs each migration in a transaction, and the table is small. On a large
-- production table, build them CONCURRENTLY in a non-transactional migration instead.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX idx_tickets_title_trgm ON tickets USING gin (lower(title) gin_trgm_ops);
CREATE INDEX idx_tickets_description_trgm ON tickets USING gin (lower(description) gin_trgm_ops);
```

- [ ] **Step 5: Rodar e ver passar**

Run (em `backend/`): `./mvnw test -Dtest="TicketSearchIndexTest,TicketSearchApiTest"`
Expected: PASS em todos. `searchesTitleAndDescriptionCaseInsensitivelyAndLiterally` continua passando sem alteração.

- [ ] **Step 6: Roadmap**

Em `docs/roadmap-de-evolucao.md`, logo abaixo de `### 2.1. Busca Textual Otimizada com \`pg_trgm\` (Trigramas)`, acrescente:

```markdown
- **Status**: ✅ Implementado (spec: `docs/specs/2026-10-01-trigram-search-design.md`). Os índices são sobre as expressões `lower(title)` e `lower(description)`, as mesmas da consulta; um índice nas colunas cruas, como no exemplo abaixo, não seria usado.
```

- [ ] **Step 7: Verificação completa**

Run (em `backend/`): `./mvnw verify`
Expected: BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
git add backend/src docs/roadmap-de-evolucao.md
git commit -m "perf: index the ticket text search with pg_trgm

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

## Self-review (spec × plano)

- Critério 1 (plano usa os índices): `theTextSearchUsesTheTrigramIndexes`.
- Critério 2 (busca igual): `searchesTitleAndDescriptionCaseInsensitivelyAndLiterally`, sem alteração.
- Critério 3 (sem Java/API): só a migration e um teste.
- Nomes conferidos: `idx_tickets_title_trgm`, `idx_tickets_description_trgm`, `V8`.
