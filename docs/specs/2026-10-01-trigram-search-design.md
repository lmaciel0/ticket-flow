# Busca textual com índices de trigramas (roadmap 2.1)

- **Data:** 2026-10-01
- **Status:** aguardando revisão
- **Autor:** Roberto Lucas
- **Origem:** item 2.1 de `docs/roadmap-de-evolucao.md`

## 1. Objetivo

A busca da lista de chamados (`q=`) filtra com `lower(title) LIKE '%termo%' OR lower(description) LIKE '%termo%'` (`TicketSpecifications`). Um `LIKE` com `%` no começo não usa índice B-tree, então o Postgres varre a tabela inteira. O objetivo é que essa consulta use índices GIN de trigramas (`pg_trgm`) **sem mudar o comportamento da busca**.

**Critérios de sucesso:**

1. O plano de execução da consulta de busca usa os índices de trigramas de título e de descrição.
2. A busca continua igual para o usuário: encontra trechos no título ou na descrição, não diferencia maiúsculas e trata `%` e `_` como texto.
3. Nenhuma mudança no Java nem na API.

**Fora do escopo:** ignorar acentos (`unaccent`), busca por palavras com radical (`tsvector`) e ranking de relevância.

## 2. Decisões

| Decisão | Escolha | Motivo |
|---|---|---|
| Tipo de busca | Trigramas (`pg_trgm`) | Mantém a semântica atual de "contém o trecho". `tsvector` casaria palavras com radical, e "impre" deixaria de achar "impressora". |
| O que indexar | As **expressões** `lower(title)` e `lower(description)` | O Postgres só usa um índice quando a expressão da consulta é idêntica à indexada. Um índice nas colunas cruas, como o roadmap sugeria, não seria usado pela consulta, que aplica `lower()`. |
| Quantos índices | Dois, um por coluna | O `OR` vira um `BitmapOr` dos dois índices. É mais simples de explicar que um índice GIN de várias colunas. |
| `CONCURRENTLY` | Não | O Flyway roda cada migration numa transação, e `CREATE INDEX CONCURRENTLY` não pode rodar em transação. A tabela da demo é pequena. Numa tabela grande em produção, o certo seria `CONCURRENTLY` numa migration fora de transação. |

## 3. Design

Migration `backend/src/main/resources/db/migration/V8__add_trigram_search_indexes.sql`:

```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX idx_tickets_title_trgm ON tickets USING gin (lower(title) gin_trgm_ops);
CREATE INDEX idx_tickets_description_trgm ON tickets USING gin (lower(description) gin_trgm_ops);
```

Nenhuma mudança em Java: o Hibernate já gera `lower(title) like ? escape '\'`.

## 4. Testes

- **`TicketSearchIndexTest` (novo):** roda `EXPLAIN` da mesma condição gerada pelo `TicketSpecifications` com `SET LOCAL enable_seqscan = off`, porque numa tabela quase vazia o planner sempre prefere a varredura. Confere que o plano cita `idx_tickets_title_trgm` e `idx_tickets_description_trgm`. Falha antes da migration.
- **Rede de segurança:** `TicketSearchApiTest.searchesTitleAndDescriptionCaseInsensitivelyAndLiterally` já cobre maiúsculas, título, descrição e `%` literal. Ele passa antes e depois, sem alteração.

## 5. Riscos

- **Neon:** a `pg_trgm` é suportada e "trusted" (o dono do banco pode criá-la). Se a criação falhar, o deploy da API falha na migration e o Flyway não marca a versão como aplicada.
- **Termos curtos:** termos com menos de 3 caracteres não têm trigramas úteis. O resultado continua correto, mas não fica mais rápido.

## 6. Pontos de entrevista

- Por que `LIKE '%x%'` não usa B-tree, e como trigramas resolvem isso.
- Índice de expressão: a consulta e o índice precisam usar a mesma expressão.
- Como provar que um índice é usado: `EXPLAIN` com `enable_seqscan = off` em teste.
- `CREATE INDEX CONCURRENTLY` e por que ele não cabe numa migration transacional.
