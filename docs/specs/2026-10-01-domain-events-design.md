# Eventos de domínio para o histórico (roadmap 1.1)

- **Data:** 2026-10-01
- **Status:** aguardando revisão
- **Autor:** Roberto Lucas
- **Origem:** item 1.1 de `docs/roadmap-de-evolucao.md`

## 1. Objetivo

Hoje `TicketService`, `CommentService` e `AttachmentService` chamam `HistoryRecorder.record(...)` diretamente. Cada service conhece o histórico e precisa ser editado para qualquer comportamento novo (e-mail, webhook, integração).

O objetivo é fazer os services **publicarem fatos de domínio** ("chamado criado", "status mudou") sem saber quem escuta. O histórico passa a ser apenas um ouvinte. Novos ouvintes poderão ser plugados sem alterar os services (Princípio Aberto/Fechado).

**Critérios de sucesso:**

1. Nenhum service de negócio depende de `HistoryRecorder`; só o listener (e o `DemoDataSeeder`) o usa.
2. O comportamento externo da API não muda. Os testes de API existentes passam sem alteração.
3. A garantia atual continua valendo: se a mudança sofre rollback, a entrada de histórico também.
4. Um teste prova que um listener novo recebe os eventos sem tocar em nenhum service.

**Fora do escopo:** e-mail, webhooks, processamento assíncrono, outbox, e qualquer mudança em `HistoryRecorder`, `TicketHistory` ou no formato da API de histórico.

## 2. Decisões

| Decisão | Escolha | Motivo |
|---|---|---|
| Escopo | Todos os pontos que gravam histórico (ticket, comentário e anexo) | Um único jeito de gravar histórico; mais simples de explicar e manter. |
| Granularidade | Um evento por fato de domínio (7 records) | Cada evento carrega só o que importa; um listener futuro não precisa interpretar strings. |
| Publicação | `ApplicationEventPublisher` no service | `@DomainEvents` do Spring Data só dispara em `repository.save(...)`. Nossos métodos (`ticket.assign(...)`) alteram uma entidade já gerenciada, sem `save`, então o evento não seria publicado. |
| Fase do listener | `@TransactionalEventListener(phase = BEFORE_COMMIT)` | Mantém o histórico na mesma transação da mudança. Com `AFTER_COMMIT` o histórico sairia da transação e poderia divergir do ticket. |

## 3. Design

### 3.1 Eventos

Pacote novo `com.ticketflow.history.event`, com uma `sealed interface HistoryEvent` e 7 `record`s que a implementam. Todos carregam `Ticket ticket`, `User actor` e `Instant at`:

| Evento | Dados extras | Vira `HistoryEventType` |
|---|---|---|
| `TicketCreated` | nenhum | `CREATED` |
| `TicketAssigned` | `String oldAssignee`, `String newAssignee` | `ASSIGNED` |
| `TicketStatusChanged` | `TicketStatus oldStatus`, `TicketStatus newStatus` | `STATUS_CHANGED` |
| `TicketPriorityChanged` | `Priority oldPriority`, `Priority newPriority` | `PRIORITY_CHANGED` |
| `TicketCategoryChanged` | `String oldCategory`, `String newCategory` | `CATEGORY_CHANGED` |
| `CommentAdded` | nenhum | `COMMENT_ADDED` |
| `AttachmentAdded` | `String filename` | `ATTACHMENT_ADDED` |

### 3.2 Publicação

Os três services recebem `ApplicationEventPublisher` e trocam `history.record(...)` por `events.publishEvent(new ...)`. As regras de negócio, as validações e a ordem das operações permanecem como estão. Os services deixam de injetar `HistoryRecorder`.

### 3.3 Listener

`HistoryEventListener` (em `com.ticketflow.history`) tem um método por evento, anotado com `@TransactionalEventListener(phase = BEFORE_COMMIT)`. Cada método traduz o evento para `HistoryRecorder.record(...)` com os mesmos `field`, `oldValue` e `newValue` de hoje (por exemplo, `"status"`, `"OPEN"`, `"IN_PROGRESS"`).

`HistoryRecorder` e o `DemoDataSeeder` não mudam. O seeder continua gravando histórico direto, porque precisa de datas retroativas.

### 3.4 Comportamento preservado

- **Notas internas** continuam sem rastro no histórico: `CommentService` simplesmente não publica `CommentAdded` para elas.
- **Ordem das entradas:** `ASSIGNED` continua antes de `STATUS_CHANGED` quando uma atribuição inicia o atendimento. Os eventos são entregues na ordem de publicação.
- **Atomicidade:** a gravação ocorre antes do commit, na mesma transação. Rollback desfaz ticket e histórico juntos.

## 4. Testes

- **Rede de segurança:** `TicketWorkflowApiTest`, `CommentApiTest`, `AttachmentApiTest` e `TicketUpdateApiTest` devem passar sem nenhuma alteração.
- **Extensibilidade (novo):** um `@TestConfiguration` registra um listener de teste que coleta eventos; o teste cria um chamado pela API e verifica que `TicketCreated` chegou ao listener, sem tocar em `TicketService`.
- **Atomicidade (novo):** teste de integração que publica um evento dentro de uma transação e a reverte, verificando que nenhuma linha de histórico foi gravada.

## 5. Plano de entrega (commits pequenos)

1. Eventos, `HistoryEventListener` e os testes novos.
2. Migrar `TicketService` para publicar eventos.
3. Migrar `CommentService` e `AttachmentService`.

A cada etapa: `./mvnw verify` verde antes do commit.

## 6. Pontos de entrevista

- Por que `ApplicationEventPublisher` e não `@DomainEvents`.
- Por que `BEFORE_COMMIT` e não `AFTER_COMMIT` (consistência transacional do histórico).
- Como isso aplica o Princípio Aberto/Fechado e inverte a dependência (o service não conhece o histórico).
- Limite assumido: eventos síncronos, dentro da mesma transação. Para integrações externas (e-mail, webhook) o próximo passo seria um padrão outbox, para não bloquear nem perder mensagens.
