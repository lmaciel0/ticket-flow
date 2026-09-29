# ticket-flow

[![CI](https://github.com/lmaciel0/ticket-flow/actions/workflows/ci.yml/badge.svg)](https://github.com/lmaciel0/ticket-flow/actions/workflows/ci.yml)

Sistema de gestão de chamados (help desk) com Spring Boot, PostgreSQL e React: perfis de acesso, SLA com pausa, anexos, histórico e painel do gestor.

**Demo:** https://ticket-flow-web.onrender.com

- Entre com um dos botões de demonstração: Solicitante, Atendente ou Gestor.
- O servidor gratuito dorme depois de 15 minutos sem uso. **O primeiro acesso pode levar cerca de um minuto**; depois disso, fica rápido.
- Os dados da demonstração são reiniciados diariamente.
- Para usar dois perfis ao mesmo tempo, abra o segundo numa **janela anônima**. Abas da mesma janela compartilham o login.

Contas de demonstração (senha `demo1234`):

| Perfil | E-mail |
|---|---|
| Solicitante | `solicitante@ticketflow.demo` |
| Atendente | `atendente@ticketflow.demo` |
| Gestor | `gestor@ticketflow.demo` |

## O que o sistema faz

- **Solicitante** abre chamados, acompanha, comenta, anexa arquivos e confirma ou reabre a solução.
- **Atendente** assume chamados da fila, pede informações ao solicitante, muda prioridade e categoria, e resolve.
- **Gestor** atribui chamados, gerencia usuários e acompanha o painel (SLA cumprido, tempo médio de resolução, carga por atendente).
- **SLA por prioridade** (Crítica 4h, Alta 8h, Média 24h, Baixa 72h), em horas corridas. O relógio pausa enquanto o chamado aguarda o solicitante e depois de resolvido. A lista mostra "no prazo", "em risco", "vencido" ou "pausado".
- **Histórico** de tudo o que aconteceu em cada chamado.

## Stack

- **Backend:** Java 21, Spring Boot 4 (Web MVC, Data JPA, Security com JWT, Validation, Actuator), PostgreSQL 17, Flyway, springdoc-openapi (Swagger).
- **Frontend:** React 19, TypeScript, Vite, Mantine (componentes e gráficos), React Router, TanStack Query.
- **Testes:** JUnit 5, MockMvc e Testcontainers (PostgreSQL real) no backend; Vitest e Testing Library no frontend.
- **Infra:** Docker, docker-compose, GitHub Actions, Render (API e site) e Neon (banco).

## Decisões técnicas e trade-offs

1. **Monólito organizado por funcionalidade** (`ticket`, `sla`, `comment`, `attachment`...), cada pacote com controller, service e repository. O que muda junto fica junto. A arquitetura hexagonal aparece só onde há troca real de implementação: `AttachmentStorage`.
2. **Regras de negócio testáveis sem Spring.** As transições de status ficam no enum `TicketStatus`, e o cálculo do SLA fica no `SlaCalculator`, que recebe um `Clock`. Os testes controlam o "agora".
3. **Permissões em dois níveis:** por perfil, com `@PreAuthorize`, e por objeto, no service. Um solicitante que pede o chamado de outra pessoa recebe **404** (a API não confirma que ele existe). Um atendente que tenta alterar um chamado do qual não é responsável recebe **403**.
4. **Concorrência otimista:** toda alteração envia a `version` que o cliente leu. Se alguém mudou antes, a resposta é **409** e a tela recarrega o chamado. A evolução seria `ETag` com `If-Match`.
5. **Token JWT no `localStorage`:** é simples, mas fica exposto a ataques XSS (o React escapa todo texto de usuário, o que reduz o risco). A evolução é um refresh token em cookie `httpOnly`.
6. **Token de usuário rebaixado ou desativado vale até expirar (8h).** A evolução é consultar o usuário a cada requisição ou manter uma lista de tokens revogados.
7. **Anexos no PostgreSQL (`bytea`):** evita custo e infraestrutura extra, com limite de 5 MB e validação da assinatura do arquivo (não só da extensão). A interface `AttachmentStorage` permite migrar para S3.
8. **O solicitante escolhe a prioridade inicial:** pode exagerar a urgência. O atendente responsável ou o gestor corrige, e a correção fica no histórico.
9. **SLA em horas corridas:** horário comercial fica como evolução.
10. **Histórico gravado explicitamente em vez de Envers:** dá controle sobre quais eventos importam e deixa o código legível.
11. **Hospedagem de custo zero:**
    - A API roda no plano grátis do Render (512 MB, 0,1 CPU, dorme após 15 minutos). Para o primeiro acesso ficar em cerca de um minuto, a imagem Docker usa **CDS** (Class Data Sharing) e flags de JVM para pouca CPU. A subida medida caiu de ~190 s para ~60 s.
    - O banco fica no **Neon**, porque o PostgreSQL grátis do Render expira em 30 dias.
    - O reset diário da demo roda quando a aplicação sobe, e não com `@Scheduled`: um servidor que dorme não executa tarefas agendadas.

## Rodando tudo com Docker

Pré-requisito: Docker Desktop em execução.

```bash
docker compose up --build
```

Sobe o banco, a API com os dados de demonstração e o frontend. Abra `http://localhost:5173`. Para desligar, use `docker compose down`; o `-v` apagaria também o banco.

## Rodando o backend localmente

Pré-requisitos: Java 21 e Docker Desktop em execução.

```bash
docker compose up -d db
cd backend
./mvnw spring-boot:run
```

A API sobe em `http://localhost:8080` e a documentação interativa (Swagger UI) fica em `http://localhost:8080/swagger-ui`.

Para subir com os dados de demonstração, ative o profile `demo`:

- Git Bash: `SPRING_PROFILES_ACTIVE=demo ./mvnw spring-boot:run`
- PowerShell: `$env:SPRING_PROFILES_ACTIVE="demo"; .\mvnw.cmd spring-boot:run`

As variáveis de ambiente da API estão descritas em [.env.example](.env.example).

## Rodando o frontend localmente

Pré-requisitos: Node.js 24 ou mais recente e a API rodando (por exemplo, `docker compose up -d db backend`).

```bash
cd frontend
npm install
npm run dev
```

O frontend abre em `http://localhost:5173`, com recarga automática a cada alteração. A URL da API vem da variável `VITE_API_URL` (padrão `http://localhost:8080`; veja `frontend/.env.example`).

## Testes

```bash
cd backend
./mvnw verify
```

Os testes de integração do backend sobem um PostgreSQL real com Testcontainers, então o Docker precisa estar rodando.

```bash
cd frontend
npm run lint
npm test
```

## Deploy

A infraestrutura está descrita em [render.yaml](render.yaml) (Render Blueprint): a API em Docker e o frontend como site estático. Os dois são publicados a cada push na `main`, **depois que o CI passa**.

Para reproduzir:

1. No [Neon](https://neon.tech), crie um projeto com PostgreSQL 17 na região AWS US East (N. Virginia). Anote o host (sem `-pooler`), o banco, o usuário e a senha.
2. No [Render](https://render.com), crie um Blueprint apontando para este repositório e preencha as variáveis pedidas:
   - `DB_URL`: `jdbc:postgresql://<host>/<banco>?sslmode=require`
   - `DB_USER` e `DB_PASSWORD`: do Neon.
   - `CORS_ALLOWED_ORIGINS`: a URL do site (`https://ticket-flow-web.onrender.com`).
   - `VITE_API_URL`: a URL da API (`https://ticket-flow-api.onrender.com`).
3. Se o Render atribuir URLs diferentes (quando o nome já existe, ele acrescenta um sufixo), corrija `CORS_ALLOWED_ORIGINS` e `VITE_API_URL` no painel e faça um novo deploy.

O `JWT_SECRET` é gerado pelo próprio Render. Nenhum segredo fica no repositório.

## Documentação

- Especificação do MVP: [docs/specs/2026-09-28-ticket-flow-mvp-design.md](docs/specs/2026-09-28-ticket-flow-mvp-design.md)
- Planos de implementação: [docs/plans/](docs/plans/)
