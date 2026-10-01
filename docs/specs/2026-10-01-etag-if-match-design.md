# Concorrência otimista com ETag e If-Match (roadmap 1.2)

- **Data:** 2026-10-01
- **Status:** aguardando revisão
- **Autor:** Roberto Lucas
- **Origem:** item 1.2 de `docs/roadmap-de-evolucao.md`

## 1. Objetivo

Hoje o controle de concorrência otimista usa o campo `version` no corpo JSON das mutações. O objetivo é seguir o padrão HTTP de requisições condicionais (RFC 9110, que substituiu a RFC 7232, e a RFC 6585 para o 428):

- o servidor informa a versão do chamado no header `ETag`;
- o cliente devolve essa versão no header `If-Match`;
- uma versão divergente vira `412 Precondition Failed`.

**Critérios de sucesso:**

1. `GET /api/tickets/{id}`, `POST /api/tickets` e as três mutações respondem com `ETag: "N"`, onde N é a versão do chamado.
2. As três mutações exigem `If-Match`. Sem ele, respondem 428. Com valor inválido, 400. Com versão divergente, 412.
3. O campo `version` não existe mais nos corpos de requisição. Continua existindo no corpo de resposta.
4. O frontend em produção (outro domínio) consegue enviar `If-Match` e ler `ETag`: o CORS permite um e expõe o outro.
5. O frontend envia `If-Match` e recarrega o chamado em 409 ou 412. O comportamento visível ao usuário não muda.

**Fora do escopo:**

- `If-None-Match` e respostas 304 (cache de `GET`);
- ETag na listagem e em comentários, anexos ou histórico;
- período de transição aceitando `version` no corpo.

## 2. Decisões

| Decisão | Escolha | Motivo |
|---|---|---|
| Campo `version` | Sai das requisições, fica na resposta | Uma única fonte de controle na entrada. O frontend monta o `If-Match` a partir do chamado em cache, sem ler headers. |
| Sem `If-Match` | `428 Precondition Required` | Status criado para isso (RFC 6585). Separa "faltou a pré-condição" de "dados inválidos". |
| `If-Match` malformado | `400 Bad Request` | Aceitamos só um ETag forte numérico (`"3"`). Formato fraco (`W/"3"`), `*` e listas são recusados explicitamente, com mensagem clara. |
| Versão divergente | `412 Precondition Failed` | Semântica exata do `If-Match` na RFC 9110. |
| Conflito detectado no flush | Continua `409 Conflict` | A `OptimisticLockingFailureException` do Hibernate também pode ocorrer em `POST /comments`, que não tem pré-condição. 412 só vale quando a pré-condição explícita falha. |
| Onde tratar os headers | No controller, com um helper `TicketETag` | Explícito e fácil de explicar. Descartados: `WebRequest.checkNotModified`, que exige carregar o chamado no controller e não tem 428, e `ShallowEtagHeaderFilter`, que só serve para cache de `GET`. |

## 3. Design

### 3.1 Contrato da API

| Endpoint | Requisição | Resposta de sucesso |
|---|---|---|
| `POST /api/tickets` | sem mudança | `201` + `ETag: "0"` |
| `GET /api/tickets/{id}` | sem mudança | `200` + `ETag: "N"` |
| `PATCH /api/tickets/{id}` | `If-Match: "N"`; corpo `{priority?, categoryId?}` | `200` + `ETag: "N+1"` (ou `"N"` se nada mudou) |
| `POST /api/tickets/{id}/status` | `If-Match: "N"`; corpo `{status}` | `200` + novo `ETag` |
| `POST /api/tickets/{id}/assign` | `If-Match: "N"`; corpo `{assigneeId}` | `200` + novo `ETag` |

Os erros seguem o formato ProblemDetail já usado no projeto:

| Situação | Status | `detail` |
|---|---|---|
| Sem header `If-Match` | 428 | "Envie o cabeçalho If-Match com o ETag do chamado." |
| `If-Match` fora do formato `"<número>"` | 400 | "If-Match inválido. Envie o ETag recebido, por exemplo \"3\"." |
| Versão diferente da atual | 412 | "O chamado foi alterado por outra pessoa. Recarregue e tente novamente." (mensagem atual) |
| Conflito detectado no flush | 409 | mesma mensagem (comportamento atual mantido) |

Ordem das checagens:

1. **Formato da requisição** (no controller, antes de carregar o chamado): header ausente → 428; header malformado → 400. Funciona como a validação do corpo e não revela se o chamado existe.
2. **Existência e permissão** (no service, como hoje).
3. **Versão** (no service): divergente → 412. Por isso um solicitante sem acesso recebe 404, mesmo com uma versão errada.

### 3.2 Backend

- **`TicketETag`** (novo, `com.ticketflow.ticket`): `static String format(long version)` devolve `"\"3\""`. `static long parse(String ifMatch)` aceita espaços nas pontas e exige o formato `"<dígitos>"`; caso contrário lança `ApiException` 400.
- **`ApiException`**: novos `preconditionFailed(String)` (412) e `preconditionRequired(String)` (428).
- **`TicketDtos`**: o campo `version` sai de `UpdateTicketRequest`, `ChangeStatusRequest` e `AssignRequest`. `TicketResponse.version` permanece.
- **`TicketService`**: `update`, `changeStatus` e `assign` recebem `long expectedVersion` como parâmetro. `requireVersion` lança 412 em vez de 409.
- **`TicketController`**: os três endpoints de mutação leem `@RequestHeader(value = "If-Match", required = false)`. Se o header faltar, respondem 428; se existir, `TicketETag.parse`. `create`, `get` e as mutações devolvem `ResponseEntity` com `.eTag(TicketETag.format(response.version()))`.
- **`SecurityConfig`** (CORS): `If-Match` entra em `allowedHeaders` e `ETag` em `exposedHeaders`.
- **`ApiExceptionHandler`**: sem mudança. O 409 da `OptimisticLockingFailureException` permanece.

### 3.3 Frontend

- **`api/client.ts`**: `api.post` e `api.patch` aceitam um terceiro parâmetro opcional, `headers?: Record<string, string>`, repassado ao `fetch`.
- **`tickets/api.ts`**: `useChangeStatus`, `useAssignTicket` e `useUpdateTicket` recebem `{ ..., version }` como hoje. A `version` deixa de ir no corpo e passa a ir em `If-Match: "${version}"`. O `onError` recarrega o chamado quando o status é 409 **ou** 412.
- **`TicketActions.tsx`**: sem mudança (continua passando a `version` do chamado na tela).

## 4. Testes

**Backend**
- **`TicketETagTest`** (unidade): `format`; `parse` de `"3"` e `  "3"  `; recusa de `3` sem aspas, `W/"3"`, `*`, `"3", "4"`, `"abc"` e vazio.
- **Teste de API novo, `TicketConditionalRequestApiTest`:**
  - `ETag` no `GET` e na criação;
  - nova `ETag` após cada uma das três mutações;
  - 428 sem `If-Match`;
  - 400 com `If-Match` malformado;
  - 412 com versão velha, sem alterar o chamado;
  - 404 para solicitante alheio, mesmo com `If-Match` errado.
- **Testes de API existentes** que enviam `version` no corpo (`TicketWorkflowApiTest`, `TicketUpdateApiTest`, `CommentApiTest`, `DomainEventsApiTest` e outros) passam a enviar `If-Match`. O teste `staleVersionIsConflict` passa a esperar 412. Aqui o contrato muda de propósito.
- **`CorsApiTest`**: o preflight com `Access-Control-Request-Headers: if-match` é aceito, e a resposta expõe `ETag`.

**Frontend**
- **`api.test.tsx`**: verifica que a mutação envia `If-Match: "3"` e um corpo sem `version`; verifica que um 412 recarrega o chamado, assim como o 409.

## 5. Plano de entrega (commits pequenos)

1. `TicketETag` e os novos `ApiException`, com testes de unidade.
2. Backend: contrato com ETag, If-Match, 428, 400 e 412, CORS, e os testes de API novos e atualizados.
3. Frontend: envio de `If-Match` e recarga em 412.

A cada etapa, `./mvnw verify` (backend) ou `npm test` e `npm run build` (frontend) ficam verdes antes do commit.

**Pré-requisito:** o PR #15 (item 1.1) está no `main`, porque ambos alteram o `TicketService`.

## 6. Pontos de entrevista

- Por que ETag/If-Match em vez de `version` no corpo: é o mecanismo padrão do HTTP para requisições condicionais, e proxies, SDKs e outros clientes já entendem.
- Diferença entre 412 (a pré-condição enviada falhou), 428 (a pré-condição é obrigatória e não veio) e 409 (conflito detectado depois, na gravação).
- ETag forte (`"3"`) versus fraco (`W/"3"`): o `If-Match` usa comparação forte, por isso o formato fraco é recusado.
- Por que o CORS precisa permitir `If-Match` e expor `ETag`: sem isso o navegador bloqueia o preflight ou esconde o header do JavaScript, e os testes com MockMvc não detectam.
- Limite assumido: entre a checagem e o flush ainda existe uma janela mínima de corrida, coberta pelo `@Version` do JPA (409).
