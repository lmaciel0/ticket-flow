# ticket-flow — Especificação do MVP

- **Data:** 2026-09-28
- **Status:** aguardando revisão
- **Autor:** Roberto Lucas

Itens marcados com **[NOVO]** não estavam no design aprovado na conversa. Eles fecham lacunas encontradas ao escrever esta spec e precisam de revisão.

## 1. Objetivo e critérios de sucesso

Sistema de gestão de chamados (help desk) feito para **portfólio de vaga backend Java júnior/estágio**. O backend é o ponto mais caprichado (regras de SLA, segurança, testes). O frontend é funcional e limpo.

O MVP está pronto quando:

1. Um visitante abre o link do README, entra com um botão de demo (Solicitante, Atendente ou Gestor) e percorre o fluxo completo de um chamado.
2. O SLA é calculado corretamente, inclusive com pausas, e aparece como indicador na lista e no detalhe.
3. As permissões por perfil e por objeto são cobertas por testes de integração com PostgreSQL real (Testcontainers).
4. O CI (GitHub Actions) roda `./mvnw verify` e o build do frontend a cada push, e o badge fica verde no README.
5. O README explica as decisões técnicas e os trade-offs (seção 11).

**Fora do MVP:** cancelamento de chamado, notas internas, SLA de primeira resposta, fechamento automático, e-mail, horário comercial no SLA, CRUD de categorias, edição ou exclusão de comentários e anexos, refresh token, testes E2E (Playwright).

## 2. Stack e convenções

- **Backend:** Java 21, Spring Boot (versão estável mais recente, fixada no plano), Maven Wrapper (`mvnw`), Spring Web, Spring Data JPA, Spring Security com `oauth2-resource-server` (JWT), Bean Validation, Flyway, PostgreSQL, springdoc-openapi e Actuator.
- **Frontend:** Vite, React, TypeScript, Mantine (componentes e gráficos), React Router e TanStack Query.
- **Infra:** Docker, docker-compose, GitHub Actions, Render (backend e frontend) e Neon (banco).
- **Idioma:** código, identificadores, API e banco em **inglês**; textos de interface em **português**.
- **Tempo:** todos os instantes são gravados em UTC (`Instant` / `timestamptz`). Agrupamentos por dia no painel usam o fuso configurável `app.zone` (padrão `America/Sao_Paulo`). **[NOVO]**

## 3. Arquitetura do backend

Monólito organizado **por funcionalidade** (package-by-feature). Cada pacote tem suas próprias camadas: controller → service → repository.

```
com.ticketflow
├── auth/        login, cadastro, emissão de JWT, /auth/me
├── user/        User, Role, gestão de usuários
├── category/    Category (somente leitura)
├── ticket/      Ticket, TicketStatus, regras de transição, filtros (Specifications)
├── sla/         SlaProperties, SlaCalculator, SlaIndicator
├── comment/     Comment
├── attachment/  Attachment, AttachmentStorage (interface) + DatabaseAttachmentStorage
├── history/     TicketHistory, HistoryRecorder
├── dashboard/   consultas agregadas do painel
├── demo/        DemoDataSeeder (somente profile "demo")
└── common/      tratamento de erros (ProblemDetail), paginação, config de segurança/CORS
```

Regras gerais:

- Controllers recebem e devolvem DTOs (`record`). Entidades nunca saem da camada de service.
- Regras de negócio e checagens de permissão por objeto ficam nos **services**.
- A arquitetura hexagonal aparece só em um ponto: `AttachmentStorage`, que permite trocar o banco por disco ou S3 sem mexer no restante.
- `java.time.Clock` é um bean injetado onde há cálculo de tempo, para que os testes controlem o "agora".

## 4. Modelo de dados

| Entidade | Campos |
|---|---|
| **User** | id, name, email (único, minúsculo), passwordHash (BCrypt), role (`REQUESTER`/`AGENT`/`MANAGER`), active, demo (boolean) **[NOVO]**, createdAt |
| **Category** | id, name (único). Seed via migration: Acesso, Hardware, Software, Financeiro, Outros |
| **Ticket** | id, title (≤ 120), description (≤ 5000), priority (`LOW`/`MEDIUM`/`HIGH`/`CRITICAL`), status, category, requester, assignee (nullable), createdAt, dueAt, pausedAt (nullable), pausedTotal (segundos), resolvedAt (nullable), slaBreached (nullable), version |
| **Comment** | id, ticket, author, text (≤ 5000), createdAt |
| **Attachment** | id, ticket, uploadedBy, filename, contentType, size, createdAt. Os bytes ficam em `attachment_content` (1:1), para que listar anexos não carregue os arquivos |
| **TicketHistory** | id, ticket, actor, occurredAt, eventType, field (nullable), oldValue (nullable), newValue (nullable) |

- Enums são gravados como texto (`EnumType.STRING`).
- **Índices:** `ticket(status, due_at)`, `ticket(requester_id)`, `ticket(assignee_id)` e `ticket_history(ticket_id, occurred_at)`.
- **Tipos de evento no histórico:** `CREATED`, `STATUS_CHANGED`, `ASSIGNED`, `PRIORITY_CHANGED`, `CATEGORY_CHANGED`, `COMMENT_ADDED`, `ATTACHMENT_ADDED`. O histórico é gravado explicitamente pelo `HistoryRecorder`, chamado nos services dentro da mesma transação da alteração (não usamos Hibernate Envers).

## 5. Ciclo de vida do chamado

O enum `TicketStatus` conhece as próprias transições válidas (`canTransitionTo`). Uma transição inválida retorna **409**.

| De → Para | Como acontece | Quem pode |
|---|---|---|
| (novo) → `OPEN` | `POST /tickets` | REQUESTER; AGENT e MANAGER também podem abrir **[NOVO]** |
| `OPEN` → `IN_PROGRESS` | **só** via atribuição (`/assign`) | AGENT (assumir para si), MANAGER (atribuir a qualquer um) |
| `IN_PROGRESS` → `WAITING_REQUESTER` | `/status` | responsável ou MANAGER |
| `WAITING_REQUESTER` → `IN_PROGRESS` | `/status`, ou **automático** quando o solicitante comenta | responsável ou MANAGER; solicitante via comentário |
| `IN_PROGRESS` → `RESOLVED` | `/status` | responsável ou MANAGER |
| `RESOLVED` → `CLOSED` | `/status` (final) | solicitante dono ou MANAGER |
| `RESOLVED` → `IN_PROGRESS` | `/status` (reabrir) | solicitante dono ou MANAGER |

Rótulos na interface: Aberto, Em atendimento, Aguardando solicitante, Resolvido, Fechado.

Regras complementares **[NOVO]**:

- `POST /status` pedindo `OPEN` → `IN_PROGRESS` retorna 409, porque essa transição só acontece por atribuição.
- Um MANAGER pode reatribuir um chamado em `IN_PROGRESS` ou `WAITING_REQUESTER` sem mudar o status. Um AGENT só assume chamados `OPEN` sem responsável.
- Só podem ser responsáveis usuários **ativos** com papel AGENT ou MANAGER.
- Chamados `CLOSED` não aceitam comentários, anexos, nem mudanças de prioridade ou categoria (409).
- Um comentário que dispara a retomada automática gera dois eventos de histórico: `COMMENT_ADDED` e `STATUS_CHANGED`, ambos com o solicitante como ator.

## 6. SLA

**Prazos por prioridade** em `application.yml`, lidos por `@ConfigurationProperties` (`SlaProperties`): `CRITICAL` 4h, `HIGH` 8h, `MEDIUM` 24h, `LOW` 72h.

**Relógio:** corre em `OPEN` e `IN_PROGRESS`; fica pausado em `WAITING_REQUESTER` e `RESOLVED`.

- Ao **entrar** em um status pausado, grava `pausedAt = agora`.
- Ao **sair** de um status pausado, faz `pausedTotal += agora − pausedAt`, zera `pausedAt` e recalcula `dueAt`.
- Fórmula: `dueAt = createdAt + prazo(priority) + pausedTotal`. O valor é persistido, para que filtros e painel consultem pelo banco.
- Mudar a prioridade recalcula `dueAt` com o novo prazo. Isso vale até durante uma pausa, usando o `pausedTotal` acumulado até então.
- **Resolução:** grava `resolvedAt = agora` e `slaBreached = resolvedAt > dueAt`.
- **Reabertura** (`RESOLVED` → `IN_PROGRESS`) **[NOVO]**: o tempo em `RESOLVED` conta como pausa, e `resolvedAt` e `slaBreached` voltam a `null`. Numa nova resolução, os dois são recalculados. Assim o painel sempre reflete a última resolução.

**Indicador** (calculado pelo `SlaCalculator`, que recebe um `Clock`):

| Indicador | Condição |
|---|---|
| No prazo | status que corre, e restante ≥ 25% do prazo da prioridade |
| Em risco | status que corre, e 0 < restante < 25% do prazo da prioridade |
| Vencido | status que corre, e `agora ≥ dueAt` |
| Pausado **[NOVO]** | `WAITING_REQUESTER` |
| Cumprido / Violado | `RESOLVED` ou `CLOSED`, conforme `slaBreached` |

Nesta tabela, "restante" é `dueAt − agora`. Os filtros `sla=OVERDUE` e `sla=AT_RISK` da API usam essas mesmas regras e só consideram chamados em `OPEN` ou `IN_PROGRESS`.

## 7. Segurança

**Perfis:**

- **REQUESTER:** abre chamados escolhendo a prioridade inicial. Vê, comenta e anexa apenas nos **próprios** chamados. Fecha ou reabre os próprios chamados em `RESOLVED`.
- **AGENT:** vê todos os chamados, assume da fila, comenta e anexa em todos. Muda status, prioridade e categoria só dos chamados em que é o **responsável**.
- **MANAGER:** pode tudo o que os outros perfis podem, sobre qualquer chamado. Atribui chamados, gerencia usuários e acessa o painel.

**Dois níveis de checagem:**

- Por perfil, com `@PreAuthorize` nos endpoints.
- Por objeto, no service.

Quando um solicitante tenta acessar um chamado de outra pessoa (ou anexo dele), a resposta é **404**, para não revelar que o chamado existe. Já um AGENT que tenta alterar um chamado do qual não é responsável recebe **403**, porque ele pode ver o chamado.

**Autenticação:**

- JWT com o suporte nativo do Spring Security: `NimbusJwtEncoder`/`NimbusJwtDecoder` com segredo HMAC (`JWT_SECRET`, mínimo de 32 bytes). Não usamos jjwt nem filtro manual.
- Stateless, com validade de **8h**.
- Claims **[NOVO]**: `sub` = id do usuário, `role` = papel (mapeado para authority `ROLE_…`).
- Login com usuário inativo ou senha errada retorna **401** com a mesma mensagem, para não revelar quais e-mails estão cadastrados.
- O **cadastro é aberto** e sempre cria um REQUESTER. Exige nome, e-mail válido e senha com no mínimo 8 caracteres. E-mail já cadastrado retorna **409**.
- O token fica no `localStorage`. Os trade-offs estão na seção 11.

**Gestão de usuários** (somente MANAGER) **[NOVO]**:

- Contas com `demo = true` não podem ter papel nem status alterados (409). Isso impede que um visitante quebre a demo.
- O gestor não pode alterar o próprio papel ou status (409).
- Um usuário que é responsável por chamados não finalizados não pode ser rebaixado a REQUESTER nem desativado (409 com a mensagem "reatribua os chamados antes").

## 8. API REST

Todas as rotas têm prefixo `/api`. A documentação fica no Swagger UI, em `/swagger-ui`, e o health check em `/actuator/health`.

| Método e rota | Perfis | Observação |
|---|---|---|
| `POST /auth/register` | público | cria REQUESTER |
| `POST /auth/login` | público | retorna `{ token, user }` |
| `GET /auth/me` | autenticado | |
| `GET /tickets` | autenticado | REQUESTER só vê os próprios |
| `POST /tickets` | autenticado | |
| `GET /tickets/{id}` | autenticado | inclui indicador de SLA e `version` |
| `PATCH /tickets/{id}` | AGENT responsável, MANAGER | `{ priority?, categoryId?, version }` |
| `POST /tickets/{id}/assign` | AGENT (a si mesmo), MANAGER | `{ assigneeId, version }` |
| `POST /tickets/{id}/status` | conforme a seção 5 | `{ status, version }` |
| `GET /tickets/{id}/history` | quem vê o chamado | ordem cronológica |
| `GET` e `POST /tickets/{id}/comments` | quem vê o chamado | |
| `GET` e `POST /tickets/{id}/attachments` | quem vê o chamado | upload em multipart |
| `GET /attachments/{id}` | quem vê o chamado | download |
| `GET /categories` | autenticado | |
| `GET /users/assignable` | AGENT, MANAGER | ativos com papel AGENT ou MANAGER |
| `GET /users` | MANAGER | paginado |
| `PATCH /users/{id}` | MANAGER | `{ role?, active? }` |
| `GET /dashboard?from=&to=` | MANAGER | |

**Listagem de chamados:**

- **Filtros**, implementados com JPA Specifications:
  - `status`, que aceita vários valores **[NOVO]**
  - `priority`, `categoryId`, `assigneeId`
  - `sla=OVERDUE|AT_RISK`
  - `q`: busca sem diferenciar maiúsculas em título e descrição
  - `mine=true`: para AGENT e MANAGER, chamados em que é responsável; para REQUESTER não muda nada
- **Paginação:** `page` (a partir de 0) e `size` (padrão 20, máximo 100).
- **Ordenação** **[NOVO]**: `sort` aceita só `dueAt` ou `createdAt`, com `asc` ou `desc`. O padrão é `dueAt,asc`. A lista fica restrita a esses campos porque a prioridade, gravada como texto, seria ordenada alfabeticamente.
- **Resposta:** DTO próprio `PageResponse<T>` com `content`, `page`, `size`, `totalElements` e `totalPages`. Nunca serializamos o `Page` do Spring diretamente.
- Por padrão, o frontend filtra os chamados não finalizados (`OPEN`, `IN_PROGRESS`, `WAITING_REQUESTER`).

**Concorrência** **[NOVO]**: as operações que alteram o chamado recebem o `version` que o cliente leu. Se ele for diferente do atual (alguém alterou antes), a resposta é **409**. O `@Version` do JPA protege também contra duas transações simultâneas. A evolução seria usar `ETag` com `If-Match`.

**Anexos:**

- **Limites:** 5 MB por arquivo, configurado em `spring.servlet.multipart.max-file-size`. Acima disso, retorna **413**.
- **Tipos aceitos:** PDF, PNG, JPEG, TXT e DOCX.
- **Validação** **[NOVO]:** extensão na lista permitida **e** assinatura do arquivo (magic bytes) compatível. PDF começa com `%PDF`, PNG com `89 50 4E 47`, JPEG com `FF D8 FF` e DOCX com `PK` (zip). TXT só passa pela checagem de extensão. Tipo inválido retorna 400.
- **Download:** `Content-Disposition: attachment`, com o `contentType` validado e `X-Content-Type-Options: nosniff`.

**Erros:** todos no formato **RFC 9457 ProblemDetail**, centralizados em um `@RestControllerAdvice`:

| Código | Quando |
|---|---|
| 400 | validação, com a lista de campos inválidos |
| 401 | não autenticado |
| 403 | sem permissão |
| 404 | não encontrado ou não visível |
| 409 | transição inválida, versão desatualizada ou regra de negócio |
| 413 | arquivo grande demais |

**Painel** (`from` e `to` são datas; padrão: últimos 30 dias; período máximo de 90 dias):

- **Estado atual (ignora o período):**
  - chamados por status
  - vencidos agora
  - carga por atendente: chamados não finalizados atribuídos a cada um
- **No período (chamados com `resolvedAt` no intervalo):**
  - % de SLA cumprido
  - tempo médio de resolução: `resolvedAt − createdAt`, em horas corridas, contando as pausas
  - resolvidos por atendente
  - chamados por categoria
- **Série diária:** abertos × resolvidos por dia no período, agrupados no fuso `app.zone`.

## 9. Frontend

**Telas:**

- **Login:** três botões de demo e um formulário.
- **Cadastro.**
- **Lista de chamados:** filtros, paginação e badge de SLA.
- **Novo chamado.**
- **Detalhe do chamado:** ações conforme o perfil, comentários, anexos e histórico em timeline.
- **Usuários:** só para o gestor.
- **Painel:** só para o gestor, com gráficos do Mantine.

**Comportamento:**

- A URL da API vem de `VITE_API_URL`.
- O cliente HTTP anexa o token a cada requisição. Uma resposta 401 leva ao login.
- Um 409 de versão mostra a mensagem "o chamado foi alterado por outra pessoa" e recarrega os dados.
- As rotas são protegidas por perfil. A autorização de verdade está no backend; o frontend só esconde o que o usuário não pode usar.
- A tela de login avisa que os dados da demo são reiniciados diariamente.

**Testes:** mínimos, com Vitest. Cobrem utilitários como o formato do SLA e um ou dois componentes.

## 10. Testes, dados, repositório e deploy

**Testes do backend:**

- **Unitários, sem Spring:**
  - `SlaCalculator`: pausas, reabertura, mudança de prioridade, em risco e vencido.
  - Transições de `TicketStatus`.
  - Validação de assinatura dos anexos.
- **Integração:** `@SpringBootTest` + **Testcontainers PostgreSQL** (um container compartilhado entre os testes) + MockMvc, com o Flyway rodando de verdade. Cobre:
  - a matriz de permissões (incluindo o 404 para chamado alheio)
  - o fluxo completo de um chamado
  - filtros e paginação
  - upload e download
  - conflito de versão
  - as regras de gestão de usuários

**Dados:**

- O **Flyway** cria o schema e as categorias.
- O `DemoDataSeeder` só roda no profile `demo`. Ele cria os três usuários demo (`demo = true`) e cerca de 30 chamados com datas **relativas a agora**, para que sempre existam chamados vencidos, em risco e no prazo.

**Reset diário** **[NOVO]:**

- O Render grátis "dorme" quando fica sem acesso, então uma tarefa com `@Scheduled` não é confiável. Por isso o reset roda **quando a aplicação sobe**.
- Na subida, se o último reset (gravado na tabela `demo_state`) tiver mais de 24h, o seeder apaga todos os dados (inclusive usuários cadastrados) e recria tudo.
- Como o serviço dorme com frequência, o reset acontece na primeira subida depois de passadas 24h, sem nenhuma infraestrutura extra. Se o serviço ficar acordado por mais de 24h, o reset só acontece na próxima subida. Isso é aceitável para uma demo.

**Repositório:**

```
ticket-flow/
├── backend/            Spring Boot (mvnw, pom.xml, Dockerfile)
├── frontend/           Vite + React (Dockerfile com nginx)
├── docs/specs/  docs/plans/
├── .github/workflows/  CI
├── docker-compose.yml  (db, backend, frontend)
└── README.md
```

- **[NOVO]** O `.gitignore` atual é o modelo padrão de Java. Ele será ajustado para ignorar `target/`, `node_modules/`, `dist/`, `.env` e arquivos de IDE. A regra `*.jar` terá uma exceção para `.mvn/wrapper/maven-wrapper.jar`.
- **Dia a dia:** `docker compose up db`, com backend e frontend rodando localmente com hot reload.
- **Tudo junto:** `docker compose up`.

**Configuração por variáveis de ambiente:** `DB_URL`, `DB_USER`, `DB_PASSWORD`, `JWT_SECRET`, `CORS_ALLOWED_ORIGINS` e `SPRING_PROFILES_ACTIVE`. Há um `.env.example` versionado; o `.env` real nunca é versionado.

**Deploy (custo zero):** as condições atuais dos planos gratuitos serão reconfirmadas na etapa de deploy.

- **Frontend:** Render Static Site (não dorme).
- **Backend:** Render Web Service via Docker. Ele dorme quando fica sem uso e tem cerca de 512 MB de memória, então a JVM será ajustada (por exemplo, `-XX:MaxRAMPercentage=75`). O README avisa que o primeiro acesso é lento.
- **Banco:** Neon, com PostgreSQL grátis de cerca de 0,5 GB. O limite de 5 MB por anexo e o reset diário mantêm o uso baixo.
- **CORS:** liberado só para o domínio do frontend.

## 11. Trade-offs a documentar no README

1. **Token no `localStorage`:** é simples, mas fica exposto a ataques XSS. A evolução é um refresh token em cookie `httpOnly`.
2. **Token de usuário rebaixado ou desativado continua valendo até expirar (8h):** a evolução é consultar o usuário a cada requisição ou manter uma lista de tokens revogados.
3. **Anexos no PostgreSQL (`bytea`):** evita custo e infraestrutura extra. A interface `AttachmentStorage` permite migrar para S3 depois.
4. **O solicitante escolhe a prioridade inicial:** pode exagerar a urgência. O atendente responsável ou o gestor corrige, e a correção fica registrada no histórico. **[NOVO]**
5. **SLA em horas corridas:** horário comercial fica como evolução.
6. **Histórico gravado explicitamente em vez de Envers:** dá controle sobre quais eventos importam e deixa o código mais legível.
7. **Package-by-feature em vez de camadas globais:** o que muda junto fica junto. Hexagonal só onde há troca real de implementação.
