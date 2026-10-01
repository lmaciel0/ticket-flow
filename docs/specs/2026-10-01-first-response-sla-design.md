# SLA de primeira resposta (roadmap 2.2)

- **Data:** 2026-10-01
- **Status:** aprovada
- **Autor:** Roberto Lucas
- **Origem:** item 2.2 de `docs/roadmap-de-evolucao.md`

## 1. Objetivo

Hoje o chamado tem só o SLA de **resolução** (`dueAt`, `slaBreached`). O objetivo é acrescentar a métrica clássica de help desk, o *First Response Time*. Cada chamado ganha um prazo para a primeira resposta (`firstResponseDueAt`) e o registro de quando ela aconteceu (`firstRespondedAt`). O cliente quer saber rápido que alguém viu o chamado, mesmo que a solução demore.

**Critérios de sucesso:**

1. Todo chamado novo nasce com `firstResponseDueAt`, calculado pela prioridade, com o mesmo calendário (24/7 ou horário comercial) do SLA de resolução.
2. `firstRespondedAt` é gravado uma única vez, no que vier primeiro: alguém da equipe que não seja o solicitante assumir o chamado (ou atribuí-lo), ou um comentário público de atendente ou gestor que não seja o solicitante.
3. A API expõe o prazo, o momento da resposta e um indicador (`PENDING`, `OVERDUE`, `MET`, `BREACHED`).
4. O detalhe do chamado mostra um selo "1ª resposta", com contagem regressiva ao vivo enquanto está pendente.
5. A demo mostra os quatro estados do indicador, com a primeira resposta calculada no relógio do SLA.

**Fora do escopo:** selo na lista, métrica no painel, filtro por primeira resposta, evento no histórico e preenchimento de chamados antigos.

## 2. Decisões

| Decisão | Escolha | Motivo |
|---|---|---|
| O que conta como 1ª resposta | O que vier primeiro: assumir/atribuir o chamado ou um comentário público da equipe | Escolha do autor. É uma métrica "otimista": assumir não avisa o cliente, mas mostra que o chamado saiu da fila. |
| Quem conta | Quem age (quem atribui ou quem comenta) não pode ser o solicitante do chamado; no comentário, só atendente ou gestor, e nota interna não conta | O solicitante não responde a si mesmo, nem quando é da equipe. A regra vale igual para os dois caminhos. Nota interna não chega ao cliente. |
| Onde fica a regra | No domínio: `Ticket.recordFirstResponse(User by, Instant at)` ignora o solicitante | Um só lugar para a regra, chamado de `assign` e do comentário. |
| Prazos padrão | CRITICAL 30 min, HIGH 1 h, MEDIUM 4 h, LOW 8 h (`app.sla.first-response`) | Cerca de 1/8 do prazo de resolução, uma proporção comum. Ficam configuráveis como os de resolução. |
| Armazenamento | Duas colunas no chamado | Leitura barata e dado consultável no futuro. Calcular a partir dos comentários e do histórico custaria consultas extras a cada leitura. |
| "Violado" | Calculado (`firstRespondedAt >= firstResponseDueAt`) e não gravado | As duas datas bastam; uma terceira coluna poderia divergir delas. |
| Pausa | Não se aplica | Um chamado só sai de `OPEN` sendo atribuído. Enquanto a resposta está pendente o status costuma ser `OPEN`, e nele o relógio corre. |
| Mudança de prioridade | Recalcula o prazo só enquanto não houve resposta | Depois da resposta, o resultado está fechado. |
| Chamados antigos | Colunas aceitam nulo; o indicador vem `null` ("não se aplica") | O prazo com horário comercial não cabe em SQL, e a demo recria os chamados a cada 24 h. |
| Comentário e ETag | O primeiro comentário público da equipe altera o chamado, o que incrementa a `version` e muda o ETag | Mesmo precedente da volta automática de `WAITING_REQUESTER`. O frontend já recarrega o detalhe depois de comentar. Uma edição concorrente pode fazer esse comentário receber 409, como qualquer outra escrita no chamado. |

## 3. Design

### 3.1 Configuração

```yaml
app:
  sla:
    first-response:
      CRITICAL: 30m
      HIGH: 1h
      MEDIUM: 4h
      LOW: 8h
```

`SlaProperties` (`@ConfigurationProperties("app.sla")`) ganha o componente `Map<Priority, Duration> firstResponse`, com a mesma validação de `deadlines`: todas as prioridades são obrigatórias, senão a aplicação não sobe. Os testes que constroem `new SlaProperties(...)` diretamente passam a informar os dois mapas.

### 3.2 Domínio

- **`SlaCalculator`:**
  - `firstResponseDeadlineFor(Priority)`;
  - `firstResponseDueAt(Instant createdAt, Priority)`, que avança pelo mesmo calendário de `dueAt`;
  - `after(Instant start, Duration amount)`, o inverso público de `ago`, para a demo posicionar eventos no relógio do SLA;
  - `firstResponseIndicator(TicketStatus status, Instant dueAt, Instant respondedAt)`, que devolve `null` quando `dueAt` é `null`, `MET` ou `BREACHED` quando há resposta, `null` quando não há resposta e o chamado já foi resolvido ou fechado (ver abaixo), e `OVERDUE` ou `PENDING` pelo relógio nos demais casos.
  - O caso "resolvido sem resposta" só acontece quando um atendente abre e atende o próprio chamado: ninguém além do solicitante agiu, então a métrica não se aplica, em vez de mostrar "atrasada" num chamado encerrado.
- **`FirstResponseIndicator`** (enum novo em `com.ticketflow.sla`): `PENDING`, `OVERDUE`, `MET`, `BREACHED`.
- **`Ticket`:**
  - novos campos `firstResponseDueAt` e `firstRespondedAt`;
  - o construtor calcula o prazo;
  - `recordFirstResponse(User by, Instant at)` só grava se ainda não houver resposta, se houver prazo (chamados antigos com `NULL` ficam como estão) e se `by` não for o solicitante;
  - `assign(User newAssignee, User actor, Instant now, SlaCalculator sla)` chama `recordFirstResponse(actor, now)`; o ator é quem atribui (o próprio atendente ao assumir, ou o gestor);
  - `changePriority(...)` recalcula o prazo se ainda não houve resposta;
  - getters novos.
- **`CommentService.add`:** depois de salvar um comentário **público**, chama `ticket.recordFirstResponse(author, now)` se o autor é da equipe (`User.canBeAssigned()`: atendente ou gestor ativo). A exclusão do solicitante fica no `Ticket`.

### 3.3 Persistência

Migration `V9__add_first_response_sla.sql`:

```sql
ALTER TABLE tickets ADD COLUMN first_response_due_at TIMESTAMPTZ;
ALTER TABLE tickets ADD COLUMN first_responded_at TIMESTAMPTZ;
```

### 3.4 API

`TicketResponse` ganha `firstResponseDueAt`, `firstRespondedAt` e `firstResponse` (o indicador, ou `null`). Valem para o detalhe e para a lista. Como são só campos novos, o contrato continua compatível.

### 3.5 Frontend

- **`api/types.ts`:** os três campos novos e o tipo `FirstResponseIndicator`.
- **`FirstResponseBadge`** (novo, em `tickets/`), no mesmo formato do `SlaBadge` (selo + contagem embaixo, com `useNow` e `formatDueIn`):
  - `PENDING`: selo "Pendente" e "vence em X", virando "Atrasada" sozinho quando o prazo passa, sem recarregar;
  - `OVERDUE`: selo "Atrasada" e "venceu há X";
  - `MET`: selo "No prazo";
  - `BREACHED`: selo "Fora do prazo";
  - não renderiza nada quando o indicador é `null`.
- **`TicketSummary`:** uma linha "1ª resposta" logo abaixo da linha "SLA", só quando o indicador não é `null`.

### 3.6 Demo

Hoje o `DemoDataSeeder` envelhece os chamados em andamento por uma fração do prazo de **resolução** (`RUNNING_AGE = {0.3, 0.85, 1.5}`) e atribui em `createdAt + 10% da idade`, somado no relógio comum. Com o prazo de 1ª resposta em 1/8 do de resolução, isso deixaria todos os `OPEN` atrasados e quase todos os resolvidos de CRITICAL/HIGH fora do prazo. A demo passa a:

- **Atribuição:** o momento é `sla.after(createdAt, prazoDe1aResposta × FIRST_RESPONSE_PACE[i])`, com `FIRST_RESPONSE_PACE = {0.4, 0.7, 1.4}` (dois no prazo para cada fora do prazo), limitado a no máximo `createdAt + 30%` da idade, para vir antes dos passos seguintes. O limite só antecipa a atribuição, então nunca transforma um "no prazo" em "fora do prazo".
- **Chamados `OPEN`:** metade é envelhecida por `RUNNING_AGE` × prazo de **1ª resposta** (chamados novos, mistura de pendentes e atrasados) e a outra metade continua por `RUNNING_AGE` × prazo de resolução, para manter os atrasados e em risco do SLA de resolução no painel.

## 4. Testes

- **Unidade:**
  - `SlaCalculator`: prazo de 1ª resposta 24/7 e com horário comercial; `after` com e sem calendário; os quatro valores do indicador e o `null`.
  - `Ticket`: o prazo na criação; `recordFirstResponse` idempotente e ignorando o solicitante; `assign` registra; mudança de prioridade recalcula antes da resposta e não recalcula depois.
- **API** (`FirstResponseSlaApiTest`):
  - um chamado novo vem `PENDING`, com o prazo da prioridade;
  - assumir registra a resposta como `MET`;
  - um atendente que é o solicitante e assume o próprio chamado não registra;
  - comentário público de atendente registra e muda o ETag do chamado;
  - nota interna não registra;
  - comentário do solicitante não registra;
  - um atendente que é o solicitante do próprio chamado não registra ao comentar;
  - a reatribuição não muda o momento já registrado;
  - responder depois do prazo dá `BREACHED`;
  - sem resposta e depois do prazo dá `OVERDUE`;
  - um chamado com as colunas `NULL` (gravado direto no banco) vem com indicador `null`;
  - o chamado de um atendente, atendido e resolvido por ele mesmo, vem com indicador `null`.
- **Configuração:** faltar uma prioridade em `first-response` impede a aplicação de subir.
- **Seeder:** os chamados atribuídos da demo têm `firstRespondedAt` preenchido, e a demo tem pelo menos um chamado em cada estado (`PENDING`, `OVERDUE`, `MET`, `BREACHED`), com e sem horário comercial.
- **Frontend:** `FirstResponseBadge` nos quatro estados, escondido quando é `null`, e a passagem de `PENDING` para atrasado com o relógio avançando (timers falsos).

## 5. Plano de entrega (commits pequenos)

1. Configuração (incluindo o ajuste dos testes que constroem `SlaProperties`), `SlaCalculator` e `FirstResponseIndicator`, com os testes de unidade.
2. Migration, `Ticket`, `CommentService` e `TicketResponse`, com os testes de API.
3. Demo: `DemoDataSeeder` com a atribuição no relógio do SLA e as idades dos `OPEN`, com o teste do seeder.
4. Frontend: tipos, `FirstResponseBadge` e `TicketSummary`.

A cada etapa, `./mvnw verify` (backend) ou `npm test`, `npm run lint` e `npm run build` (frontend) ficam verdes antes do commit.

## 6. Destaques técnicos

- Dois SLAs independentes no mesmo chamado: primeira resposta (o cliente foi ouvido?) e resolução (o problema foi resolvido?).
- Por que o "violado" é calculado e não gravado: evita estado redundante que poderia divergir.
- Regra no domínio (`Ticket.recordFirstResponse`, idempotente e ignorando o solicitante) chamada de dois pontos diferentes (`assign` e comentário).
- Migration compatível: colunas que aceitam nulo e indicador `null` para dados antigos, sem preenchimento impreciso.
- Demo montada no relógio do SLA, para mostrar os quatro estados a qualquer hora do dia em que ela for recriada.
- Limite assumido: assumir o chamado conta como resposta, o que deixa a métrica "otimista".
