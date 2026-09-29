# ticket-flow — Plano 2: Frontend

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** entregar a interface web do ticket-flow (login com botões de demonstração, cadastro, lista de chamados com filtros e SLA, abertura de chamado, detalhe com ações por perfil, comentários, anexos e histórico, gestão de usuários e painel do gestor), consumindo a API do Plano 1, testada com Vitest, rodando no CI e em Docker.

**Architecture:** SPA em React + TypeScript criada com Vite e organizada por funcionalidade (`auth`, `tickets`, `users`, `dashboard`), como o backend. Um único cliente HTTP (`src/api/client.ts`) fala com a API, anexa o token e transforma os ProblemDetail em `ApiError`. O estado que vem do servidor fica no TanStack Query (cache, recarga e invalidação); o único estado global próprio é a sessão, num Context do React. As regras de tela (quem vê qual botão, filtros na URL, validação de anexo) são funções puras testadas sem navegador.

**Tech Stack:** Node.js 24+, Vite 8.3, React 19.3, TypeScript 6.0, Mantine 9.6 (componentes, formulários, notificações e gráficos com Recharts 3.10), React Router 8.4, TanStack Query 5.104, Vitest 5.0 + Testing Library + jsdom, oxlint, Docker (nginx 1.30) e GitHub Actions.

**Spec:** [docs/specs/2026-09-28-ticket-flow-mvp-design.md](../specs/2026-09-28-ticket-flow-mvp-design.md) (seção 9; a API está nas seções 5 a 8). Padrão seguido: [docs/plans/2026-09-29-ticket-flow-backend.md](2026-09-29-ticket-flow-backend.md).

## Como este plano se encaixa

1. **Plano 1 — API backend:** pronto, no PR #1 (branch `feat/backend`).
2. **Plano 2 — Frontend** (este documento).
3. **Plano 3 — Deploy e acabamento** (Render + Neon, CORS de produção, README final com link e trade-offs).

Todo o código deste plano foi **escrito e testado antes, numa cópia descartável fora do repositório**: 55 testes verdes, lint e checagem de tipos limpos, build de produção, imagem Docker construída, `docker compose up` com banco + API + frontend, e os fluxos principais conferidos no navegador contra a API real (login pelos botões de demonstração, lista com SLA, ações em sequência no mesmo chamado, anexo com nome acentuado, download, erro 413, usuários e painel). Depois, o plano foi **reexecutado tarefa por tarefa, do zero**, para garantir que cada etapa intermediária compila, passa no lint e nos testes.

## Como executar (ambiente do Roberto)

- Windows 11 com **Git Bash**. Os comandos rodam a partir da raiz do repositório (`C:\Users\lucas\repositorios_git\ticket-flow`), salvo quando o passo diz `cd frontend`.
- **Execução nativa**: o Claude implementa as tarefas nesta mesma sessão, na ordem, sem subagentes por tarefa. **Uma revisão de código só no fim** (Task 10), sobre a branch inteira.
- Para ver as telas funcionando, a API precisa estar rodando com os dados de demonstração: `docker compose up -d --build` (Docker Desktop aberto). A partir da Task 10, quando o compose também sobe o frontend na porta 5173, use `docker compose up -d --build db backend` enquanto estiver rodando `npm run dev`.
- A cada tarefa: explicar em linguagem simples os conceitos listados (pontos de entrevista), rodar os testes, mostrar o resultado, conferir a tela no navegador quando houver tela e fazer **um commit pequeno**. Push e Pull Request só quando o Roberto pedir.

## Global Constraints

- Node.js **24 ou mais recente** (CI e Docker usam Node 24; a máquina do Roberto tem Node 26). Versões instaladas: Vite **8.3**, React **19.3**, TypeScript **~6.0** (a versão que o `create-vite` fixa), Mantine **9.6.3** (`core`, `hooks`, `form`, `notifications`, `charts`), Recharts **3.10.1**, React Router **8.4.0**, TanStack Query **5.104.0**, Vitest **5.0.2**, jsdom **30.1.1**, Testing Library (`react` 16.3.3, `user-event` 14.6.7, `jest-dom` 7.0.1). O `package-lock.json` é versionado e o CI usa `npm ci`.
- Código, identificadores e comentários em **inglês**; tudo o que aparece na tela em **português**.
- A URL da API vem de `VITE_API_URL` (padrão `http://localhost:8080`). O prefixo `/api` é colocado só pelo cliente HTTP. Nenhuma tela chama `fetch` diretamente.
- O token JWT fica no `localStorage`, na chave `ticketflow.token` (trade-off da seção 11 da spec).
- Os tipos em `src/api/types.ts` espelham os DTOs do backend (nomes de campos idênticos). Enums são **unions de strings**, nunca `enum` do TypeScript (o `tsconfig` gerado usa `erasableSyntaxOnly`, que proíbe `enum`).
- Estado do servidor só via TanStack Query; nada de store global (Redux e afins).
- O frontend **só esconde** o que o usuário não pode usar. Quem autoriza de verdade é o backend (403/404/409).
- Um 409 mostra a mensagem do servidor e recarrega o chamado. Toda alteração de chamado envia a `version` que está na tela.
- Anexos: até **5 MB**; PDF, PNG, JPEG, TXT e DOCX. Checados no navegador **antes** do envio.
- Testes com Vitest, no fuso `America/Sao_Paulo` (fixado no `vite.config.ts`). **TDD** para toda a lógica (utilitários, cliente HTTP, hooks de mutação, permissões) e para os componentes testados (`LoginPage`, `NewTicketPage`, `SlaBadge`). As demais telas são conferidas no navegador ao fim de cada tarefa (a spec pede testes de frontend mínimos; E2E com Playwright fica fora do MVP).
- Commits pequenos e temáticos, em inglês, no padrão `feat:`/`chore:`. `ticket-flow-contexto.md` nunca é versionado.

## Review Focus

Situações que a spec implica e que mais provavelmente afetariam alguém usando o sistema. Cada uma tem teste na tarefa dona do código:

1. **Senha errada × sessão expirada.** Os dois voltam como 401. Senha errada (sem token) deve mostrar "E-mail ou senha inválidos." e ficar na tela; token vencido ou apagado pelo reset diário da demo (com token) deve limpar a sessão e levar ao login. Testes: `client.test.ts` › `drops the session when a request WITH a token gets 401` e `does not treat a wrong password...` (Task 2), `LoginPage.test.tsx` › `shows the API message on a wrong password` (Task 3).
2. **Ações em sequência no mesmo chamado, e o comentário que retoma o chamado.** Cada ação precisa enviar a versão nova, senão a segunda dá 409 falso. Um comentário do solicitante em "Aguardando solicitante" muda status e versão sem devolver o chamado, então a tela precisa recarregá-lo. Testes: `tickets/api.test.tsx` › `stores the ticket answered by the API...` e `reloads the ticket on 409...` (Task 6) e `reloads the ticket after a comment...` (Task 7).
3. **Anexos do mundo real:** arquivo com mais de 5 MB, exatamente 5 MB, vazio, extensão em maiúsculas, `.exe`, sem extensão. E o 413 do limite de upload do Spring vem com texto em inglês ("Maximum upload size exceeded"), então o cliente traduz qualquer 413. Testes: `attachments.test.ts` (Task 7) e `client.test.ts` › `uses a Portuguese message for 413` (Task 2).
4. **Link colado ou URL editada à mão** (`?status=FOO&page=-3&categoryId=abc`) não pode quebrar a lista; e "todos os status" (seleção vazia) precisa ser diferente de "padrão". Teste: `filters.test.ts` (Task 4).
5. **Virada do dia:** entre 21h e meia-noite em São Paulo já é o dia seguinte em UTC. Datas na tela usam o fuso do navegador, e o período do painel usa a data local (não `toISOString()`, que usa UTC). Testes: `format.test.ts` › `formatDateTime` (Task 1) e `period.test.ts` (Task 9).

## Descobertas da verificação (para não tropeçar)

Coisas que só apareceram rodando o código de verdade e que diferem de muitos tutoriais:

- `create-vite` 9 gera o projeto com **oxlint** (não ESLint) e **TypeScript 6**, que já liga o `strict` por padrão e usa `erasableSyntaxOnly` (sem `enum` e sem `constructor(readonly x)`).
- Mantine 9: `Grid` usa `gap` (não `gutter`); `Select` e `MultiSelect` aceitam valores tipados (`Select<Priority>`, `Select<number>`); nos testes, o `MantineProvider` precisa de `env="test"` (desliga animações e portais, senão o dropdown fica com `display: none` no jsdom). O jsdom não tem `matchMedia`, `ResizeObserver` nem `document.fonts`, que o Mantine usa: o `setup.ts` cria versões falsas.
- React 19: o próprio Context é o provider (`<AuthContext value={...}>`).
- Os efeitos do React rodam do filho para o pai, então a ordem das chamadas `fetch` numa tela não é previsível. Por isso o mock de API dos testes responde por **método + rota**, não pela ordem das chamadas.
- O bundle principal (Mantine + React) tem ~700 kB (~220 kB com gzip); os gráficos ficam num pedaço separado, baixado só quando o gestor abre o painel.

## Estrutura de arquivos

```
frontend/
├── Dockerfile, nginx.conf, .dockerignore      imagem de produção (Task 10)
├── .env.example                                VITE_API_URL
├── index.html, vite.config.ts, package.json    Vite, Vitest e scripts
└── src/
    ├── main.tsx                 providers (Mantine, notificações, TanStack Query, sessão, rotas)
    ├── router.tsx               todas as rotas e quem pode acessar cada uma
    ├── api/                     cliente HTTP, QueryClient e tipos dos DTOs
    ├── shared/                  rótulos em português, cores e formatação
    ├── auth/                    sessão (Context), guardas de rota, login e cadastro
    ├── layout/                  moldura das telas logadas (cabeçalho e menu)
    ├── tickets/                 lista, filtros, novo chamado, detalhe, ações, comentários, anexos, histórico
    ├── users/                   gestão de usuários (gestor)
    ├── dashboard/               painel (gestor)
    └── test/                    configuração do Vitest e helpers de renderização
```

---

### Task 1: Esqueleto do frontend, ferramentas de teste e CI

**Objetivo:** projeto Vite + React + TypeScript em `frontend/`, com Mantine, Vitest configurado no fuso de São Paulo, tipos da API, rótulos e formatação testados, e um job de frontend no CI rodando lint, testes e build.

**Conceitos (para explicar em entrevista):** o que é uma SPA e o papel do Vite (servidor de desenvolvimento com recarga instantânea; build que gera arquivos estáticos); TypeScript e tipagem estrutural; union de strings × `enum`; `Record<Union, string>` obriga a ter um rótulo por valor (esquecer um é erro de compilação); variáveis `VITE_*` são embutidas no JavaScript entregue ao navegador (não servem para segredos); `Intl` para datas e números em pt-BR no fuso do navegador; por que fixar o fuso nos testes; `package-lock.json` + `npm ci` = instalação reproduzível.

**Files:**
- Create (via `create-vite`): `frontend/package.json`, `frontend/package-lock.json`, `frontend/tsconfig.json`, `frontend/tsconfig.app.json`, `frontend/tsconfig.node.json`, `frontend/.oxlintrc.json`, `frontend/public/favicon.svg`
- Create: `frontend/index.html`, `frontend/vite.config.ts`, `frontend/.env.example`, `frontend/src/vite-env.d.ts`, `frontend/src/main.tsx`, `frontend/src/test/setup.ts`, `frontend/src/api/types.ts`, `frontend/src/shared/labels.ts`, `frontend/src/shared/format.ts`
- Modify (substituir o arquivo inteiro): `.github/workflows/ci.yml`
- Test: `frontend/src/shared/format.test.ts`

**Interfaces:**
- Produces: tipos `Role`, `TicketStatus`, `Priority`, `SlaIndicator`, `SlaFilter`, `HistoryEventType`, `User`, `UserSummary`, `AuthResponse`, `Category`, `Ticket`, `Page<T>`, `TicketComment`, `Attachment`, `HistoryEntry`, `Dashboard` (`src/api/types.ts`); `STATUS_LABELS`, `STATUS_COLORS`, `PRIORITY_LABELS`, `PRIORITY_COLORS`, `SLA_LABELS`, `SLA_COLORS`, `ROLE_LABELS`, `STATUSES`, `PRIORITIES`, `ROLES`, `ACTIVE_STATUSES` (`src/shared/labels.ts`); `formatDateTime(iso)`, `formatDuration(ms)`, `formatDueIn(dueAt, now?)`, `formatPercent(value | null)`, `formatHours(value | null)`, `formatFileSize(bytes)` (`src/shared/format.ts`). Scripts `npm run dev | build | lint | test | test:watch`.

- [ ] **Step 1: Criar a branch de trabalho**

O frontend depende do backend do PR #1. Se o PR #1 **já foi mergeado**:

```bash
git switch main
git pull
git switch -c feat/frontend
```

Se o PR #1 **ainda está aberto**, parta da branch do backend (o PR do frontend terá `feat/backend` como base, ou será rebaseado em `main` depois do merge):

```bash
git switch feat/backend
git pull
git switch -c feat/frontend
```

- [ ] **Step 2: Gerar o projeto com o `create-vite` e limpar o exemplo**

```bash
npm create vite@9.2.1 frontend -- --template react-ts --no-interactive
rm frontend/.gitignore frontend/README.md frontend/src/App.css frontend/src/App.tsx frontend/src/index.css frontend/public/icons.svg
rm -r frontend/src/assets
```

O `.gitignore` da raiz já ignora `node_modules/`, `dist/` e `.env`, então o `frontend/.gitignore` gerado não é necessário.

- [ ] **Step 3: Instalar as dependências com versões fixas e ajustar os scripts**

```bash
cd frontend
npm install
npm install @mantine/core@9.6.3 @mantine/hooks@9.6.3 @mantine/form@9.6.3 @mantine/notifications@9.6.3 @mantine/charts@9.6.3 recharts@3.10.1 react-router@8.4.0 @tanstack/react-query@5.104.0
npm install -D vitest@5.0.2 jsdom@30.1.1 @testing-library/react@16.3.3 @testing-library/user-event@14.6.7 @testing-library/jest-dom@7.0.1
npm pkg set name=ticket-flow-frontend scripts.lint="oxlint src" scripts.test="vitest run" scripts.test:watch="vitest"
cd ..
```

O `frontend/package.json` deve ficar assim:

`frontend/package.json`

```json
{
  "name": "ticket-flow-frontend",
  "private": true,
  "version": "0.0.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "tsc -b && vite build",
    "lint": "oxlint src",
    "preview": "vite preview",
    "test": "vitest run",
    "test:watch": "vitest"
  },
  "dependencies": {
    "@mantine/charts": "^9.6.3",
    "@mantine/core": "^9.6.3",
    "@mantine/form": "^9.6.3",
    "@mantine/hooks": "^9.6.3",
    "@mantine/notifications": "^9.6.3",
    "@tanstack/react-query": "^5.104.0",
    "react": "^19.2.8",
    "react-dom": "^19.2.8",
    "react-router": "^8.4.0",
    "recharts": "^3.10.1"
  },
  "devDependencies": {
    "@testing-library/jest-dom": "^7.0.1",
    "@testing-library/react": "^16.3.3",
    "@testing-library/user-event": "^14.6.7",
    "@types/node": "^24.13.3",
    "@types/react": "^19.2.18",
    "@types/react-dom": "^19.2.7",
    "@vitejs/plugin-react": "^6.1.1",
    "jsdom": "^30.1.1",
    "oxlint": "^1.81.0",
    "typescript": "~6.0.2",
    "vite": "^8.3.0",
    "vitest": "^5.0.2"
  }
}
```

(O `lint` roda só em `src`: sem isso, o oxlint também analisaria `node_modules`.)

- [ ] **Step 4: Configuração do Vite, do Vitest e da página**

`frontend/vite.config.ts`

```ts
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    // Fixed time zone, so date tests give the same result on any machine (and match the CI).
    env: { TZ: 'America/Sao_Paulo' },
  },
})
```

`frontend/index.html`

```html
<!doctype html>
<html lang="pt-BR">
  <head>
    <meta charset="UTF-8" />
    <link rel="icon" type="image/svg+xml" href="/favicon.svg" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>ticket-flow</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

`frontend/.env.example`

```text
# Base URL of the backend API (without /api). Read at build time by Vite.
VITE_API_URL=http://localhost:8080
```

`frontend/src/vite-env.d.ts`

```ts
/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Base URL of the backend (without /api). Embedded in the bundle at build time: never put secrets here. */
  readonly VITE_API_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
```

`frontend/src/test/setup.ts`

```ts
import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, vi } from 'vitest'

// Without Vitest globals, Testing Library cannot register its own cleanup: we do it here.
afterEach(() => {
  cleanup()
  localStorage.clear()
})

// jsdom does not implement these browser APIs, and Mantine components use them.
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: vi.fn().mockImplementation((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    addListener: vi.fn(),
    removeListener: vi.fn(),
    dispatchEvent: vi.fn(),
  })),
})

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}
window.ResizeObserver = ResizeObserverStub

// Textarea with autosize listens to font loading.
Object.defineProperty(document, 'fonts', {
  value: { addEventListener: vi.fn(), removeEventListener: vi.fn(), ready: Promise.resolve() },
})
```

Tela provisória (a Task 3 troca pelos providers e rotas):

`frontend/src/main.tsx`

```tsx
import '@mantine/core/styles.css'

import { MantineProvider, Title } from '@mantine/core'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'

// Temporary screen: Task 3 replaces it with the providers and the routes.
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <MantineProvider>
      <Title p="xl">ticket-flow</Title>
    </MantineProvider>
  </StrictMode>,
)
```

- [ ] **Step 5: Tipos da API e rótulos**

Os tipos são a cópia fiel dos `record` do backend (`TicketDtos`, `UserResponse`, `DashboardResponse`...). Se o backend mudar um campo, é aqui que o compilador vai apontar cada tela afetada.

`frontend/src/api/types.ts`

```ts
// TypeScript mirrors of the backend DTOs (see the Swagger UI at /swagger-ui).
// Enums are string unions: the JSON carries the enum name ("OPEN"), and a union
// gives autocomplete and compile errors without generating any JavaScript.

export type Role = 'REQUESTER' | 'AGENT' | 'MANAGER'

export type TicketStatus = 'OPEN' | 'IN_PROGRESS' | 'WAITING_REQUESTER' | 'RESOLVED' | 'CLOSED'

export type Priority = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'

export type SlaIndicator = 'ON_TRACK' | 'AT_RISK' | 'OVERDUE' | 'PAUSED' | 'MET' | 'BREACHED'

export type SlaFilter = 'OVERDUE' | 'AT_RISK'

export type HistoryEventType =
  | 'CREATED'
  | 'STATUS_CHANGED'
  | 'ASSIGNED'
  | 'PRIORITY_CHANGED'
  | 'CATEGORY_CHANGED'
  | 'COMMENT_ADDED'
  | 'ATTACHMENT_ADDED'

export interface User {
  id: number
  name: string
  email: string
  role: Role
  active: boolean
  demo: boolean
}

export interface UserSummary {
  id: number
  name: string
}

export interface AuthResponse {
  token: string
  user: User
}

export interface Category {
  id: number
  name: string
}

/** Instants arrive as ISO-8601 strings in UTC ("2026-09-29T13:00:00Z"). */
export interface Ticket {
  id: number
  title: string
  description: string
  priority: Priority
  status: TicketStatus
  category: Category
  requester: UserSummary
  assignee: UserSummary | null
  createdAt: string
  dueAt: string
  resolvedAt: string | null
  slaBreached: boolean | null
  sla: SlaIndicator
  version: number
}

export interface Page<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

/** Named TicketComment because the DOM already has a global type called Comment. */
export interface TicketComment {
  id: number
  text: string
  author: UserSummary
  createdAt: string
}

export interface Attachment {
  id: number
  filename: string
  contentType: string
  size: number
  uploadedBy: UserSummary
  createdAt: string
}

export interface HistoryEntry {
  id: number
  eventType: HistoryEventType
  field: string | null
  oldValue: string | null
  newValue: string | null
  actor: UserSummary
  occurredAt: string
}

export interface Dashboard {
  from: string
  to: string
  ticketsByStatus: Record<TicketStatus, number>
  overdueNow: number
  resolvedInPeriod: number
  slaMetPercentage: number | null
  averageResolutionHours: number | null
  openedByCategory: { category: string; count: number }[]
  agents: { id: number; name: string; activeAssigned: number; resolvedInPeriod: number }[]
  daily: { date: string; opened: number; resolved: number }[]
}
```

`frontend/src/shared/labels.ts`

```ts
import type { Priority, Role, SlaIndicator, TicketStatus } from '../api/types'

// Record<Union, string> forces one entry per value: adding a status to the union
// without a label here is a compile error.

export const STATUS_LABELS: Record<TicketStatus, string> = {
  OPEN: 'Aberto',
  IN_PROGRESS: 'Em atendimento',
  WAITING_REQUESTER: 'Aguardando solicitante',
  RESOLVED: 'Resolvido',
  CLOSED: 'Fechado',
}

export const STATUS_COLORS: Record<TicketStatus, string> = {
  OPEN: 'blue',
  IN_PROGRESS: 'indigo',
  WAITING_REQUESTER: 'yellow',
  RESOLVED: 'teal',
  CLOSED: 'gray',
}

export const PRIORITY_LABELS: Record<Priority, string> = {
  LOW: 'Baixa',
  MEDIUM: 'Média',
  HIGH: 'Alta',
  CRITICAL: 'Crítica',
}

export const PRIORITY_COLORS: Record<Priority, string> = {
  LOW: 'gray',
  MEDIUM: 'blue',
  HIGH: 'orange',
  CRITICAL: 'red',
}

export const SLA_LABELS: Record<SlaIndicator, string> = {
  ON_TRACK: 'No prazo',
  AT_RISK: 'Em risco',
  OVERDUE: 'Vencido',
  PAUSED: 'Pausado',
  MET: 'Cumprido',
  BREACHED: 'Violado',
}

export const SLA_COLORS: Record<SlaIndicator, string> = {
  ON_TRACK: 'green',
  AT_RISK: 'yellow',
  OVERDUE: 'red',
  PAUSED: 'gray',
  MET: 'teal',
  BREACHED: 'red',
}

export const ROLE_LABELS: Record<Role, string> = {
  REQUESTER: 'Solicitante',
  AGENT: 'Atendente',
  MANAGER: 'Gestor',
}

/** Same order as the backend enums, for selects and charts. */
export const STATUSES: TicketStatus[] = ['OPEN', 'IN_PROGRESS', 'WAITING_REQUESTER', 'RESOLVED', 'CLOSED']
export const PRIORITIES: Priority[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']
export const ROLES: Role[] = ['REQUESTER', 'AGENT', 'MANAGER']

/** Statuses of tickets still being worked on: the list shows only these by default. */
export const ACTIVE_STATUSES: TicketStatus[] = ['OPEN', 'IN_PROGRESS', 'WAITING_REQUESTER']
```

- [ ] **Step 6: Escrever o teste da formatação (falhando)**

`frontend/src/shared/format.test.ts`

```ts
import { describe, expect, it } from 'vitest'
import { formatDateTime, formatDueIn, formatDuration, formatFileSize, formatHours, formatPercent } from './format'

const MINUTE = 60_000
const HOUR = 60 * MINUTE

describe('formatDuration', () => {
  it('uses minutes below one hour', () => {
    expect(formatDuration(0)).toBe('0 min')
    expect(formatDuration(59 * MINUTE)).toBe('59 min')
  })

  it('uses whole hours below one day', () => {
    expect(formatDuration(HOUR)).toBe('1 h')
    expect(formatDuration(23 * HOUR + 59 * MINUTE)).toBe('23 h')
  })

  it('uses days and the remaining hours from one day on', () => {
    expect(formatDuration(24 * HOUR)).toBe('1 d')
    expect(formatDuration(50 * HOUR)).toBe('2 d 2 h')
  })
})

describe('formatDueIn', () => {
  const now = new Date('2026-09-29T12:00:00Z')

  it('says how long until the deadline', () => {
    expect(formatDueIn('2026-09-29T15:00:00Z', now)).toBe('vence em 3 h')
  })

  it('says how long ago the deadline passed', () => {
    expect(formatDueIn('2026-09-29T11:30:00Z', now)).toBe('venceu há 30 min')
  })

  it('treats the exact deadline as already passed (like the backend: now >= dueAt)', () => {
    expect(formatDueIn('2026-09-29T12:00:00Z', now)).toBe('venceu há 0 min')
  })
})

describe('formatDateTime', () => {
  it('shows the instant in the browser time zone (tests run in America/Sao_Paulo)', () => {
    // 01:30 UTC is still the previous day, 22:30, in São Paulo.
    expect(formatDateTime('2026-09-30T01:30:00Z')).toBe('29/09/2026, 22:30')
  })
})

describe('numbers that may be missing', () => {
  it('shows a dash when there is no value (e.g. nothing resolved in the period)', () => {
    expect(formatPercent(null)).toBe('—')
    expect(formatHours(null)).toBe('—')
  })

  it('uses the Brazilian decimal comma', () => {
    expect(formatPercent(87.5)).toBe('87,5%')
    expect(formatHours(12.25)).toBe('12,3 h')
  })
})

describe('formatFileSize', () => {
  it('picks a readable unit', () => {
    expect(formatFileSize(512)).toBe('512 B')
    expect(formatFileSize(2048)).toBe('2 KB')
    expect(formatFileSize(5 * 1024 * 1024)).toBe('5 MB')
    expect(formatFileSize(1.5 * 1024 * 1024)).toBe('1,5 MB')
  })
})
```

- [ ] **Step 7: Rodar e ver falhar**

```bash
cd frontend && npm test; cd ..
```

Expected: FAIL — `Failed to resolve import "./format" from "src/shared/format.test.ts"`.

- [ ] **Step 8: Implementar a formatação**

`frontend/src/shared/format.ts`

```ts
const MINUTE = 60_000

const dateTimeFormat = new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short', timeStyle: 'short' })
const decimalFormat = new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 1 })

/** "29/09/2026, 22:30" in the browser's time zone (the API always sends UTC). */
export function formatDateTime(iso: string): string {
  return dateTimeFormat.format(new Date(iso))
}

/** A positive duration in the largest useful unit: "45 min", "3 h", "2 d 4 h". */
export function formatDuration(ms: number): string {
  const minutes = Math.floor(ms / MINUTE)
  if (minutes < 60) {
    return `${minutes} min`
  }
  const hours = Math.floor(minutes / 60)
  if (hours < 24) {
    return `${hours} h`
  }
  const days = Math.floor(hours / 24)
  const restHours = hours % 24
  return restHours === 0 ? `${days} d` : `${days} d ${restHours} h`
}

/** "vence em 3 h" or "venceu há 30 min". The deadline itself already counts as overdue. */
export function formatDueIn(dueAt: string, now: Date = new Date()): string {
  const diff = new Date(dueAt).getTime() - now.getTime()
  return diff > 0 ? `vence em ${formatDuration(diff)}` : `venceu há ${formatDuration(-diff)}`
}

export function formatPercent(value: number | null): string {
  return value === null ? '—' : `${decimalFormat.format(value)}%`
}

export function formatHours(value: number | null): string {
  return value === null ? '—' : `${decimalFormat.format(value)} h`
}

export function formatFileSize(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`
  }
  if (bytes < 1024 * 1024) {
    return `${decimalFormat.format(bytes / 1024)} KB`
  }
  return `${decimalFormat.format(bytes / (1024 * 1024))} MB`
}
```

- [ ] **Step 9: Rodar testes, lint e build**

```bash
cd frontend && npm test && npm run lint && npm run build; cd ..
```

Expected: `Tests  10 passed (10)`, o oxlint não imprime nada (nenhum aviso) e o build termina com `✓ built`. Abrir `npm run dev` (dentro de `frontend/`) e conferir `http://localhost:5173`: aparece o título "ticket-flow".

- [ ] **Step 10: Job de frontend no CI**

`.github/workflows/ci.yml`

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:

jobs:
  backend:
    name: Backend (build + tests)
    runs-on: ubuntu-latest
    env:
      # Same time zone as the team machines, so time zone bugs show up here too.
      TZ: America/Sao_Paulo
    defaults:
      run:
        working-directory: backend
    steps:
      - uses: actions/checkout@v5
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: '21'
          cache: maven
      - name: Build and test
        run: ./mvnw -B verify

  frontend:
    name: Frontend (lint + tests + build)
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: frontend
    steps:
      - uses: actions/checkout@v5
      - uses: actions/setup-node@v5
        with:
          node-version: '24'
          cache: npm
          cache-dependency-path: frontend/package-lock.json
      - name: Install dependencies
        run: npm ci
      - name: Lint
        run: npm run lint
      - name: Test
        run: npm test
      - name: Build
        run: npm run build
```

(O fuso dos testes já está fixado no `vite.config.ts`, então o job do frontend não precisa de `TZ`.)

- [ ] **Step 11: Commit**

```bash
git add frontend .github/workflows/ci.yml
git commit -m "chore: scaffold React frontend with Mantine, Vitest and CI"
```

---

### Task 2: Cliente HTTP e QueryClient

**Objetivo:** um único ponto de acesso à API: prefixo `/api`, token no cabeçalho, JSON ou multipart, ProblemDetail → `ApiError` com mensagem e erros por campo, 401 com token derruba a sessão. E o `QueryClient` com política de nova tentativa e notificação de erro centralizada.

**Conceitos:** `fetch`, `async`/`await` e Promises; classe de erro própria (`instanceof ApiError`); ProblemDetail (RFC 9457) do lado do cliente; cabeçalho `Authorization: Bearer`; por que não definir `Content-Type` num `FormData` (o navegador escreve o `boundary`); CORS e a requisição `OPTIONS` de preflight; estado do servidor × estado da tela (por que TanStack Query em vez de `useEffect` + `useState`); política de retry (não repetir 4xx); tratamento global de erro no `MutationCache` com exceção por `meta`.

**Files:**
- Create: `frontend/src/api/client.ts`, `frontend/src/api/queryClient.ts`
- Test: `frontend/src/api/client.test.ts`

**Interfaces:**
- Consumes: nada além do `fetch` do navegador.
- Produces: `class ApiError extends Error { status: number; fieldErrors: Record<string, string> }` (status 0 = servidor inalcançável); `tokenStorage.get(): string | null`, `.set(token)`, `.clear()`; `setUnauthorizedHandler(handler: () => void)`; `api.get<T>(path)`, `api.post<T>(path, body?)` (body JSON ou `FormData`), `api.patch<T>(path, body)`, `api.blob(path): Promise<Blob>`; `showError(error: unknown)`; `createQueryClient(): QueryClient` (mutations com `meta: { inlineError: true }` não geram notificação).

- [ ] **Step 1: Escrever os testes do cliente (falhando)**

`frontend/src/api/client.test.ts`

```ts
import { afterEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError, setUnauthorizedHandler, tokenStorage } from './client'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function mockFetch(response: Response) {
  const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(response)
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

afterEach(() => {
  vi.unstubAllGlobals()
  setUnauthorizedHandler(() => {})
})

describe('api client', () => {
  it('prefixes /api and sends the stored token as a Bearer header', async () => {
    tokenStorage.set('abc.def.ghi')
    const fetchMock = mockFetch(jsonResponse(200, { id: 1 }))

    const result = await api.get<{ id: number }>('/tickets/1')

    expect(result).toEqual({ id: 1 })
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('http://localhost:8080/api/tickets/1')
    expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer abc.def.ghi')
  })

  it('sends JSON bodies with the JSON content type', async () => {
    const fetchMock = mockFetch(jsonResponse(201, { id: 7 }))

    await api.post('/tickets', { title: 'Sem acesso' })

    const [, init] = fetchMock.mock.calls[0]
    expect(init?.method).toBe('POST')
    expect(init?.body).toBe('{"title":"Sem acesso"}')
    expect(new Headers(init?.headers).get('Content-Type')).toBe('application/json')
  })

  it('lets the browser set the multipart content type for uploads', async () => {
    const fetchMock = mockFetch(jsonResponse(201, { id: 3 }))
    const form = new FormData()
    form.append('file', new File(['hello'], 'nota.txt'))

    await api.post('/tickets/1/attachments', form)

    const [, init] = fetchMock.mock.calls[0]
    expect(init?.body).toBe(form)
    expect(new Headers(init?.headers).has('Content-Type')).toBe(false)
  })

  it('turns a ProblemDetail into an ApiError with the message and the field errors', async () => {
    mockFetch(jsonResponse(400, {
      status: 400,
      detail: 'Dados inválidos.',
      errors: { title: 'Informe o título.' },
    }))

    const error = await api.post('/tickets', {}).catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 400, message: 'Dados inválidos.', fieldErrors: { title: 'Informe o título.' } })
  })

  it('uses a Portuguese message for 413, whatever the server wrote', async () => {
    mockFetch(jsonResponse(413, { status: 413, detail: 'Maximum upload size exceeded' }))

    const error = await api.post('/tickets/1/attachments', new FormData()).catch((e: unknown) => e)

    expect(error).toMatchObject({ status: 413, message: 'O arquivo passa do limite de 5 MB.' })
  })

  it('explains when the server cannot be reached', async () => {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockRejectedValue(new TypeError('Failed to fetch')))

    const error = await api.get('/tickets').catch((e: unknown) => e)

    expect(error).toMatchObject({ status: 0, message: 'Não foi possível falar com o servidor. Tente de novo em instantes.' })
  })

  it('drops the session when a request WITH a token gets 401 (expired token, demo reset)', async () => {
    tokenStorage.set('expired')
    const onUnauthorized = vi.fn()
    setUnauthorizedHandler(onUnauthorized)
    mockFetch(jsonResponse(401, { status: 401, detail: 'Autenticação necessária.' }))

    await expect(api.get('/tickets')).rejects.toBeInstanceOf(ApiError)

    expect(tokenStorage.get()).toBeNull()
    expect(onUnauthorized).toHaveBeenCalledOnce()
  })

  it('does not treat a wrong password (401 without token) as an expired session', async () => {
    const onUnauthorized = vi.fn()
    setUnauthorizedHandler(onUnauthorized)
    mockFetch(jsonResponse(401, { status: 401, detail: 'E-mail ou senha inválidos.' }))

    const error = await api.post('/auth/login', { email: 'a@b.c', password: 'errada' }).catch((e: unknown) => e)

    expect(error).toMatchObject({ status: 401, message: 'E-mail ou senha inválidos.' })
    expect(onUnauthorized).not.toHaveBeenCalled()
  })
})
```

- [ ] **Step 2: Rodar e ver falhar**

```bash
cd frontend && npm test -- src/api; cd ..
```

Expected: FAIL — `Failed to resolve import "./client"`.

- [ ] **Step 3: Implementar o cliente**

`frontend/src/api/client.ts`

```ts
const API_URL = import.meta.env.VITE_API_URL ?? 'http://localhost:8080'
const TOKEN_KEY = 'ticketflow.token'

const FALLBACK_MESSAGES: Record<number, string> = {
  0: 'Não foi possível falar com o servidor. Tente de novo em instantes.',
  403: 'Você não tem permissão para esta ação.',
  404: 'Não encontrado.',
  413: 'O arquivo passa do limite de 5 MB.',
}

/** An error answered by the API (RFC 9457 ProblemDetail), or status 0 when the server was unreachable. */
export class ApiError extends Error {
  readonly status: number
  readonly fieldErrors: Record<string, string>

  constructor(status: number, message: string, fieldErrors: Record<string, string> = {}) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.fieldErrors = fieldErrors
  }
}

/** The JWT lives in localStorage (trade-off documented in the README: simple, but readable by XSS). */
export const tokenStorage = {
  get: (): string | null => localStorage.getItem(TOKEN_KEY),
  set: (token: string) => localStorage.setItem(TOKEN_KEY, token),
  clear: () => localStorage.removeItem(TOKEN_KEY),
}

let onUnauthorized: () => void = () => {}

/** The auth layer registers here what to do when the session dies (clear state, go to login). */
export function setUnauthorizedHandler(handler: () => void) {
  onUnauthorized = handler
}

async function toApiError(response: Response): Promise<ApiError> {
  let detail: string | undefined
  let fieldErrors: Record<string, string> = {}
  try {
    const problem = (await response.json()) as { detail?: string; errors?: Record<string, string> }
    detail = problem.detail
    fieldErrors = problem.errors ?? {}
  } catch {
    // Not JSON (e.g. a proxy error page): fall back to a generic message.
  }
  const message =
    response.status === 413
      ? FALLBACK_MESSAGES[413]
      : (detail ?? FALLBACK_MESSAGES[response.status] ?? 'Algo deu errado. Tente de novo.')
  return new ApiError(response.status, message, fieldErrors)
}

async function send(method: string, path: string, body?: unknown): Promise<Response> {
  const token = tokenStorage.get()
  const headers = new Headers()
  if (token) {
    headers.set('Authorization', `Bearer ${token}`)
  }
  let payload: BodyInit | undefined
  if (body instanceof FormData) {
    payload = body // the browser writes the multipart Content-Type with its boundary
  } else if (body !== undefined) {
    headers.set('Content-Type', 'application/json')
    payload = JSON.stringify(body)
  }

  let response: Response
  try {
    response = await fetch(`${API_URL}/api${path}`, { method, headers, body: payload })
  } catch {
    throw new ApiError(0, FALLBACK_MESSAGES[0])
  }

  // 401 with a token = the session is over (expired, or the demo reset deleted the user).
  // 401 without a token is just a wrong password on the login form.
  if (response.status === 401 && token) {
    tokenStorage.clear()
    onUnauthorized()
  }
  if (!response.ok) {
    throw await toApiError(response)
  }
  return response
}

async function json<T>(method: string, path: string, body?: unknown): Promise<T> {
  const response = await send(method, path, body)
  return (await response.json()) as T
}

export const api = {
  get: <T>(path: string) => json<T>('GET', path),
  post: <T>(path: string, body?: unknown) => json<T>('POST', path, body),
  patch: <T>(path: string, body: unknown) => json<T>('PATCH', path, body),
  /** Binary responses (attachment downloads). */
  blob: async (path: string): Promise<Blob> => (await send('GET', path)).blob(),
}
```

- [ ] **Step 4: QueryClient com erro centralizado**

`frontend/src/api/queryClient.ts`

```ts
import { notifications } from '@mantine/notifications'
import { MutationCache, QueryClient } from '@tanstack/react-query'
import { ApiError } from './client'

export function showError(error: unknown) {
  notifications.show({
    color: 'red',
    title: 'Não foi possível concluir',
    message: error instanceof Error ? error.message : 'Algo deu errado. Tente de novo.',
  })
}

export function createQueryClient() {
  return new QueryClient({
    // Every failed action shows a notification, unless the screen shows the error itself
    // (forms mark their mutation with meta: { inlineError: true }).
    mutationCache: new MutationCache({
      onError: (error, _variables, _context, mutation) => {
        if (!mutation.meta?.inlineError) {
          showError(error)
        }
      },
    }),
    defaultOptions: {
      queries: {
        // 4xx answers will not change by asking again; network errors and 5xx get one retry.
        retry: (failureCount, error) =>
          !(error instanceof ApiError && error.status >= 400 && error.status < 500) && failureCount < 1,
      },
    },
  })
}
```

- [ ] **Step 5: Rodar testes e lint**

```bash
cd frontend && npm test && npm run lint; cd ..
```

Expected: `Tests  18 passed (18)`.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/api
git commit -m "feat: add API client with ProblemDetail errors and session expiry"
```

---

### Task 3: Sessão, login, cadastro, rotas protegidas e layout

**Objetivo:** entrar com um clique pelos botões de demonstração ou com e-mail e senha, criar conta, sair, voltar ao login quando a sessão expira, e ver o layout com o menu de acordo com o perfil.

**Conceitos:** componentes e props; hooks (`useState`, `useEffect`, `useMemo`, `useCallback`) e as regras dos hooks; Context para estado compartilhado sem passar props por toda a árvore (e por que só a sessão vai para um Context); hook próprio (`useAuth`) que falha cedo fora do provider; React Router em modo de dados (`createBrowserRouter`), rotas de layout com `<Outlet />`; rota protegida é UX, não segurança; formulário controlado com `@mantine/form` e erros do servidor por campo; testar como o usuário usa (buscar por papel e rótulo, não por classe CSS); mock de `fetch` por rota.

**Files:**
- Create: `frontend/src/auth/authContext.ts`, `frontend/src/auth/AuthProvider.tsx`, `frontend/src/auth/guards.tsx`, `frontend/src/auth/AuthCard.tsx`, `frontend/src/auth/LoginPage.tsx`, `frontend/src/auth/RegisterPage.tsx`, `frontend/src/layout/AppLayout.tsx`, `frontend/src/router.tsx`, `frontend/src/test/render.tsx`
- Modify (substituir o arquivo inteiro): `frontend/src/main.tsx`
- Test: `frontend/src/auth/LoginPage.test.tsx`

**Interfaces:**
- Consumes: `api`, `ApiError`, `tokenStorage`, `setUnauthorizedHandler` (Task 2); `createQueryClient` (Task 2); `User`, `AuthResponse`, `Role` (Task 1); `ROLE_LABELS` (Task 1).
- Produces: `useAuth(): { user: User | null; loading: boolean; login(response: AuthResponse): void; logout(): void }`; `useCurrentUser(): User` (só em telas atrás de `<RequireAuth>`); `<AuthProvider>`; `<RequireAuth roles?: Role[]>` e `<GuestOnly>` (rotas de layout); `<AppLayout>`; `routes: RouteObject[]` (`src/router.tsx`); helpers de teste `renderUi(ui)`, `renderRoutes(routes, path) → { user, router, queryClient }`, `jsonResponse(status, body)`, `mockApi({ 'METHOD /path': [status, body] })`, `sentBody(fetchMock, 'METHOD /path')`.

- [ ] **Step 1: Sessão (Context, provider e guardas de rota)**

`frontend/src/auth/authContext.ts`

```ts
import { createContext, useContext } from 'react'
import type { AuthResponse, User } from '../api/types'

export interface AuthContextValue {
  /** The logged-in user, or null when nobody is logged in. */
  user: User | null
  /** True while a stored token is being checked against GET /auth/me. */
  loading: boolean
  login: (response: AuthResponse) => void
  logout: () => void
}

export const AuthContext = createContext<AuthContextValue | null>(null)

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext)
  if (value === null) {
    throw new Error('useAuth must be used inside <AuthProvider>')
  }
  return value
}

/** Same as useAuth().user, for screens that are only reachable when logged in. */
export function useCurrentUser(): User {
  const { user } = useAuth()
  if (user === null) {
    throw new Error('useCurrentUser used on a screen without <RequireAuth>')
  }
  return user
}
```

`frontend/src/auth/AuthProvider.tsx`

```tsx
import { notifications } from '@mantine/notifications'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { type ReactNode, useCallback, useEffect, useMemo, useState } from 'react'
import { api, setUnauthorizedHandler, tokenStorage } from '../api/client'
import type { AuthResponse, User } from '../api/types'
import { AuthContext, type AuthContextValue } from './authContext'

const ME_KEY = ['me'] as const

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient()
  const [token, setToken] = useState(tokenStorage.get)

  // With a stored token, ask the API who we are. The query only runs while there is a token.
  const me = useQuery({
    queryKey: ME_KEY,
    queryFn: () => api.get<User>('/auth/me'),
    enabled: token !== null,
    staleTime: Infinity,
  })

  const login = useCallback(
    (response: AuthResponse) => {
      tokenStorage.set(response.token)
      queryClient.setQueryData(ME_KEY, response.user)
      setToken(response.token)
    },
    [queryClient],
  )

  const logout = useCallback(() => {
    tokenStorage.clear()
    setToken(null)
    queryClient.clear() // no data from this user may be shown to the next one
  }, [queryClient])

  useEffect(() => {
    setUnauthorizedHandler(() => {
      logout()
      notifications.show({ color: 'yellow', message: 'Sua sessão expirou. Entre novamente.' })
    })
  }, [logout])

  const value = useMemo<AuthContextValue>(
    () => ({
      user: token !== null ? (me.data ?? null) : null,
      loading: token !== null && me.isPending,
      login,
      logout,
    }),
    [token, me.data, me.isPending, login, logout],
  )

  return <AuthContext value={value}>{children}</AuthContext>
}
```

`frontend/src/auth/guards.tsx`

```tsx
import { Center, Loader } from '@mantine/core'
import { Navigate, Outlet, useLocation } from 'react-router'
import type { Role } from '../api/types'
import { useAuth } from './authContext'

/**
 * Only lets logged-in users (optionally with one of the given roles) through.
 * This is UX only: the real authorization is in the backend, which checks every request.
 */
export function RequireAuth({ roles }: { roles?: Role[] }) {
  const { user, loading } = useAuth()
  const location = useLocation()

  if (loading) {
    return (
      <Center h="100vh">
        <Loader />
      </Center>
    )
  }
  if (user === null) {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />
  }
  if (roles && !roles.includes(user.role)) {
    return <Navigate to="/tickets" replace />
  }
  return <Outlet />
}

/** Login and sign-up pages: a logged-in user goes back to where they came from (or to the list). */
export function GuestOnly() {
  const { user, loading } = useAuth()
  const location = useLocation()
  const from = (location.state as { from?: string } | null)?.from ?? '/tickets'

  if (loading) {
    return null
  }
  return user === null ? <Outlet /> : <Navigate to={from} replace />
}
```

- [ ] **Step 2: Helpers de teste**

`frontend/src/test/render.tsx`

```tsx
import { MantineProvider } from '@mantine/core'
import { QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { createMemoryRouter, type RouteObject, RouterProvider } from 'react-router'
import { vi } from 'vitest'
import { createQueryClient } from '../api/queryClient'
import { AuthProvider } from '../auth/AuthProvider'

/** Renders a single component that only needs Mantine (no API, no router). env="test" turns off animations and portals. */
export function renderUi(ui: ReactNode) {
  return render(<MantineProvider env="test">{ui}</MantineProvider>)
}

/** Renders routes with the same providers as main.tsx, starting at `path`. */
export function renderRoutes(routes: RouteObject[], path: string) {
  const queryClient = createQueryClient()
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  const user = userEvent.setup()
  render(
    <MantineProvider env="test">
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <RouterProvider router={router} />
        </AuthProvider>
      </QueryClientProvider>
    </MantineProvider>,
  )
  return { user, router, queryClient }
}

export function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

type Reply = [status: number, body: unknown]

function requestKey(input: RequestInfo | URL, init?: RequestInit): string {
  const path = new URL(String(input)).pathname.replace(/^\/api/, '')
  return `${init?.method ?? 'GET'} ${path}`
}

/**
 * Replaces fetch with a fake API that answers by "METHOD /path" (query string ignored),
 * e.g. { 'GET /categories': [200, [...]] }. Requests run in parallel, so matching by route
 * is safer than answering in call order.
 */
export function mockApi(replies: Record<string, Reply>) {
  const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
    const reply = replies[requestKey(input, init)]
    if (!reply) {
      throw new Error(`Unexpected request: ${requestKey(input, init)}`)
    }
    return jsonResponse(...reply)
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

/** The JSON body of the (first) request sent to "METHOD /path". */
export function sentBody(fetchMock: ReturnType<typeof mockApi>, key: string): unknown {
  const call = fetchMock.mock.calls.find(([input, init]) => requestKey(input, init) === key)
  if (!call) {
    throw new Error(`No request to ${key}`)
  }
  return JSON.parse(call[1]?.body as string)
}
```

- [ ] **Step 3: Escrever o teste do login (falhando)**

`frontend/src/auth/LoginPage.test.tsx`

```tsx
import { screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { tokenStorage } from '../api/client'
import { mockApi, renderRoutes, sentBody } from '../test/render'
import { GuestOnly } from './guards'
import { LoginPage } from './LoginPage'

const routes = [
  { element: <GuestOnly />, children: [{ path: '/login', element: <LoginPage /> }] },
  { path: '/tickets', element: <p>Lista de chamados</p> },
]

const manager = { id: 5, name: 'Carla', email: 'gestor@ticketflow.demo', role: 'MANAGER', active: true, demo: true }

afterEach(() => vi.unstubAllGlobals())

describe('LoginPage', () => {
  it('logs in with a demo account in one click', async () => {
    const fetchMock = mockApi({ 'POST /auth/login': [200, { token: 'jwt-token', user: manager }] })
    const { user } = renderRoutes(routes, '/login')

    await user.click(screen.getByRole('button', { name: 'Gestor' }))

    expect(await screen.findByText('Lista de chamados')).toBeInTheDocument()
    expect(sentBody(fetchMock, 'POST /auth/login')).toEqual({ email: 'gestor@ticketflow.demo', password: 'demo1234' })
    expect(tokenStorage.get()).toBe('jwt-token')
  })

  it('warns that the demo data is reset every day', () => {
    renderRoutes(routes, '/login')

    expect(screen.getByText(/reiniciados diariamente/)).toBeInTheDocument()
  })

  it('shows the API message on a wrong password and stays on the page', async () => {
    mockApi({ 'POST /auth/login': [401, { status: 401, detail: 'E-mail ou senha inválidos.' }] })
    const { user } = renderRoutes(routes, '/login')

    await user.type(screen.getByLabelText('E-mail'), 'ana@empresa.com')
    await user.type(screen.getByLabelText('Senha'), 'senha-errada')
    await user.click(screen.getByRole('button', { name: 'Entrar' }))

    expect(await screen.findByText('E-mail ou senha inválidos.')).toBeInTheDocument()
    expect(screen.queryByText('Lista de chamados')).not.toBeInTheDocument()
    expect(tokenStorage.get()).toBeNull()
  })
})
```

- [ ] **Step 4: Rodar e ver falhar**

```bash
cd frontend && npm test -- src/auth; cd ..
```

Expected: FAIL — `Failed to resolve import "./LoginPage"`.

- [ ] **Step 5: Telas de login e cadastro**

`frontend/src/auth/AuthCard.tsx`

```tsx
import { Container, Paper, Text, Title } from '@mantine/core'
import type { ReactNode } from 'react'

/** Centered card shared by the login and sign-up pages. */
export function AuthCard({ title, children }: { title: string; children: ReactNode }) {
  return (
    <Container size={440} py={60}>
      <Title ta="center">ticket-flow</Title>
      <Text ta="center" c="dimmed" mt={4}>
        Gestão de chamados com SLA
      </Text>
      <Paper withBorder shadow="sm" p="xl" mt="xl" radius="md">
        <Title order={3} mb="md">
          {title}
        </Title>
        {children}
      </Paper>
    </Container>
  )
}
```

`frontend/src/auth/LoginPage.tsx`

```tsx
import { Alert, Anchor, Button, Divider, PasswordInput, SimpleGrid, Stack, Text, TextInput } from '@mantine/core'
import { isEmail, isNotEmpty, useForm } from '@mantine/form'
import { useMutation } from '@tanstack/react-query'
import { Link } from 'react-router'
import { api } from '../api/client'
import type { AuthResponse, Role } from '../api/types'
import { ROLE_LABELS } from '../shared/labels'
import { AuthCard } from './AuthCard'
import { useAuth } from './authContext'

const DEMO_PASSWORD = 'demo1234'

const DEMO_ACCOUNTS: { role: Role; email: string }[] = [
  { role: 'REQUESTER', email: 'solicitante@ticketflow.demo' },
  { role: 'AGENT', email: 'atendente@ticketflow.demo' },
  { role: 'MANAGER', email: 'gestor@ticketflow.demo' },
]

interface Credentials {
  email: string
  password: string
}

export function LoginPage() {
  const { login } = useAuth()
  const form = useForm<Credentials>({
    initialValues: { email: '', password: '' },
    validate: { email: isEmail('E-mail inválido.'), password: isNotEmpty('Informe a senha.') },
  })
  const mutation = useMutation({
    mutationFn: (credentials: Credentials) => api.post<AuthResponse>('/auth/login', credentials),
    onSuccess: login, // <GuestOnly> notices the user and leaves this page
    meta: { inlineError: true },
  })

  return (
    <AuthCard title="Entrar">
      <Stack>
        <Text size="sm">Explore com uma conta de demonstração:</Text>
        <SimpleGrid cols={{ base: 1, xs: 3 }}>
          {DEMO_ACCOUNTS.map((account) => (
            <Button
              key={account.role}
              variant="light"
              loading={mutation.isPending && mutation.variables?.email === account.email}
              disabled={mutation.isPending}
              onClick={() => mutation.mutate({ email: account.email, password: DEMO_PASSWORD })}
            >
              {ROLE_LABELS[account.role]}
            </Button>
          ))}
        </SimpleGrid>
        <Text size="xs" c="dimmed">
          Os dados da demonstração são reiniciados diariamente. O primeiro acesso do dia pode levar até um minuto,
          enquanto o servidor acorda.
        </Text>

        <Divider label="ou entre com e-mail e senha" labelPosition="center" />

        <form onSubmit={form.onSubmit((values) => mutation.mutate(values))}>
          <Stack>
            {mutation.error && <Alert color="red">{mutation.error.message}</Alert>}
            <TextInput label="E-mail" type="email" autoComplete="email" {...form.getInputProps('email')} />
            <PasswordInput label="Senha" autoComplete="current-password" {...form.getInputProps('password')} />
            <Button type="submit" loading={mutation.isPending}>
              Entrar
            </Button>
          </Stack>
        </form>

        <Text size="sm" ta="center">
          Não tem conta?{' '}
          <Anchor component={Link} to="/register">
            Cadastre-se
          </Anchor>
        </Text>
      </Stack>
    </AuthCard>
  )
}
```

`frontend/src/auth/RegisterPage.tsx`

```tsx
import { Alert, Anchor, Button, PasswordInput, Stack, Text, TextInput } from '@mantine/core'
import { hasLength, isEmail, isNotEmpty, useForm } from '@mantine/form'
import { useMutation } from '@tanstack/react-query'
import { Link } from 'react-router'
import { api, ApiError } from '../api/client'
import type { AuthResponse } from '../api/types'
import { AuthCard } from './AuthCard'
import { useAuth } from './authContext'

interface RegisterForm {
  name: string
  email: string
  password: string
}

export function RegisterPage() {
  const { login } = useAuth()
  const form = useForm<RegisterForm>({
    initialValues: { name: '', email: '', password: '' },
    validate: {
      name: isNotEmpty('Informe o nome.'),
      email: isEmail('E-mail inválido.'),
      password: hasLength({ min: 8, max: 64 }, 'A senha deve ter entre 8 e 64 caracteres.'),
    },
  })
  const mutation = useMutation({
    mutationFn: (values: RegisterForm) => api.post<AuthResponse>('/auth/register', values),
    onSuccess: login,
    // A 400 from the API brings one message per field: show each one under its input.
    onError: (error) => {
      if (error instanceof ApiError) {
        form.setErrors(error.fieldErrors)
      }
    },
    meta: { inlineError: true },
  })

  return (
    <AuthCard title="Criar conta">
      <form onSubmit={form.onSubmit((values) => mutation.mutate(values))}>
        <Stack>
          <Text size="sm" c="dimmed">
            Toda conta nova é de solicitante. Um gestor pode promovê-la depois.
          </Text>
          {mutation.error && <Alert color="red">{mutation.error.message}</Alert>}
          <TextInput label="Nome" autoComplete="name" {...form.getInputProps('name')} />
          <TextInput label="E-mail" type="email" autoComplete="email" {...form.getInputProps('email')} />
          <PasswordInput
            label="Senha"
            description="No mínimo 8 caracteres."
            autoComplete="new-password"
            {...form.getInputProps('password')}
          />
          <Button type="submit" loading={mutation.isPending}>
            Criar conta
          </Button>
          <Text size="sm" ta="center">
            Já tem conta?{' '}
            <Anchor component={Link} to="/login">
              Entrar
            </Anchor>
          </Text>
        </Stack>
      </form>
    </AuthCard>
  )
}
```

- [ ] **Step 6: Layout, rotas e providers**

`frontend/src/layout/AppLayout.tsx`

```tsx
import { AppShell, Badge, Burger, Button, Group, NavLink, Text } from '@mantine/core'
import { useDisclosure } from '@mantine/hooks'
import { Link, Outlet, useLocation } from 'react-router'
import { useAuth, useCurrentUser } from '../auth/authContext'
import { ROLE_LABELS } from '../shared/labels'

interface MenuLink {
  to: string
  label: string
  active: (pathname: string) => boolean
}

const TICKET_LINKS: MenuLink[] = [
  { to: '/tickets', label: 'Chamados', active: (path) => path === '/tickets' || /^\/tickets\/\d+/.test(path) },
  { to: '/tickets/new', label: 'Novo chamado', active: (path) => path === '/tickets/new' },
]

const MANAGER_LINKS: MenuLink[] = [
  { to: '/users', label: 'Usuários', active: (path) => path === '/users' },
  { to: '/dashboard', label: 'Painel', active: (path) => path === '/dashboard' },
]

/** Frame of every logged-in screen: header with the user, side menu by role, page in <Outlet />. */
export function AppLayout() {
  const user = useCurrentUser()
  const { logout } = useAuth()
  const { pathname } = useLocation()
  const [menuOpened, menu] = useDisclosure()
  const links = user.role === 'MANAGER' ? [...TICKET_LINKS, ...MANAGER_LINKS] : TICKET_LINKS

  return (
    <AppShell
      header={{ height: 56 }}
      navbar={{ width: 220, breakpoint: 'sm', collapsed: { mobile: !menuOpened } }}
      padding="md"
    >
      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between" wrap="nowrap">
          <Group gap="sm" wrap="nowrap">
            <Burger opened={menuOpened} onClick={menu.toggle} hiddenFrom="sm" size="sm" aria-label="Menu" />
            <Text fw={700}>ticket-flow</Text>
          </Group>
          <Group gap="sm" wrap="nowrap">
            <Text size="sm" visibleFrom="xs">
              {user.name}
            </Text>
            <Badge variant="light">{ROLE_LABELS[user.role]}</Badge>
            <Button variant="subtle" size="xs" onClick={logout}>
              Sair
            </Button>
          </Group>
        </Group>
      </AppShell.Header>

      <AppShell.Navbar p="xs">
        {links.map((link) => (
          <NavLink
            key={link.to}
            component={Link}
            to={link.to}
            label={link.label}
            active={link.active(pathname)}
            onClick={menu.close}
          />
        ))}
      </AppShell.Navbar>

      <AppShell.Main>
        <Outlet />
      </AppShell.Main>
    </AppShell>
  )
}
```

`frontend/src/router.tsx`

```tsx
import { Navigate, type RouteObject } from 'react-router'
import { GuestOnly, RequireAuth } from './auth/guards'
import { LoginPage } from './auth/LoginPage'
import { RegisterPage } from './auth/RegisterPage'
import { AppLayout } from './layout/AppLayout'

export const routes: RouteObject[] = [
  {
    element: <GuestOnly />,
    children: [
      { path: '/login', element: <LoginPage /> },
      { path: '/register', element: <RegisterPage /> },
    ],
  },
  {
    element: <RequireAuth />,
    children: [
      {
        element: <AppLayout />,
        children: [
          // Temporary: Task 4 puts the ticket list here.
          { path: '/tickets', element: <p>Lista de chamados em construção.</p> },
        ],
      },
    ],
  },
  { path: '*', element: <Navigate to="/tickets" replace /> },
]
```

`frontend/src/main.tsx`

```tsx
import '@mantine/core/styles.css'
import '@mantine/charts/styles.css'
import '@mantine/notifications/styles.css'

import { createTheme, MantineProvider } from '@mantine/core'
import { Notifications } from '@mantine/notifications'
import { QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { createBrowserRouter, RouterProvider } from 'react-router'
import { createQueryClient } from './api/queryClient'
import { AuthProvider } from './auth/AuthProvider'
import { routes } from './router'

const theme = createTheme({ primaryColor: 'indigo' })
const queryClient = createQueryClient()
const router = createBrowserRouter(routes)

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <MantineProvider theme={theme}>
      <Notifications position="top-right" />
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <RouterProvider router={router} />
        </AuthProvider>
      </QueryClientProvider>
    </MantineProvider>
  </StrictMode>,
)
```

- [ ] **Step 7: Rodar testes e lint**

```bash
cd frontend && npm test && npm run lint && npx tsc -b; cd ..
```

Expected: `Tests  21 passed (21)`, sem erros de lint nem de tipos.

- [ ] **Step 8: Conferir no navegador**

```bash
docker compose up -d --build
cd frontend && npm run dev
```

Em `http://localhost:5173`: abrir `/tickets` sem estar logado leva ao login; os três botões entram como Solicitante, Atendente e Gestor (o gestor vê também "Usuários" e "Painel" no menu, que por enquanto voltam para a lista); "Sair" volta ao login; senha errada mostra "E-mail ou senha inválidos."; "Cadastre-se" cria uma conta de solicitante e já entra. Em tela estreita, o menu vira um botão.

- [ ] **Step 9: Commit**

```bash
git add frontend/src
git commit -m "feat: add login, sign-up, protected routes and app layout"
```

---

### Task 4: Lista de chamados com filtros na URL, paginação e SLA

**Objetivo:** a lista de chamados com busca, filtros (status múltiplo, prioridade, categoria, SLA, "atribuídos a mim"), ordenação e paginação, tudo guardado na URL, e o indicador de SLA com contagem regressiva.

**Conceitos:** a URL como estado (`useSearchParams`): recarregar, voltar e compartilhar o link mantêm os filtros; validar o que vem da URL como qualquer entrada externa; narrowing de tipos; query keys e cache por combinação de filtros; `keepPreviousData` para a tabela não piscar ao trocar de página; página 1-based na tela × 0-based na API; `key` em listas; componente puro com o "agora" injetável (`now`), a mesma ideia do `Clock` no backend.

**Files:**
- Create: `frontend/src/tickets/filters.ts`, `frontend/src/tickets/api.ts`, `frontend/src/tickets/SlaBadge.tsx`, `frontend/src/tickets/TicketFiltersBar.tsx`, `frontend/src/tickets/TicketListPage.tsx`
- Modify (substituir o arquivo inteiro): `frontend/src/router.tsx`
- Test: `frontend/src/tickets/filters.test.ts`, `frontend/src/tickets/SlaBadge.test.tsx`

**Interfaces:**
- Consumes: `api` (Task 2); `useCurrentUser` (Task 3); `renderUi` (Task 3); tipos e rótulos (Task 1); `formatDueIn` (Task 1).
- Produces: `type TicketSort = 'dueAt,asc' | 'createdAt,desc'`; `interface TicketFilters { status: TicketStatus[]; priority: Priority | null; categoryId: number | null; sla: SlaFilter | null; q: string; mine: boolean; sort: TicketSort; page: number }`; `DEFAULT_FILTERS`, `PAGE_SIZE = 20`, `parseFilters(params: URLSearchParams): TicketFilters`, `toSearchParams(filters): URLSearchParams`, `toApiQuery(filters): string`; `ticketKeys.lists() | list(query) | detail(id) | comments(id) | attachments(id) | history(id)`; `useTickets(filters)`, `useCategories()`; `<SlaBadge sla dueAt now? />`.

- [ ] **Step 1: Escrever os testes dos filtros e do indicador de SLA (falhando)**

`frontend/src/tickets/filters.test.ts`

```ts
import { describe, expect, it } from 'vitest'
import { DEFAULT_FILTERS, parseFilters, type TicketFilters, toApiQuery, toSearchParams } from './filters'

describe('ticket list filters in the URL', () => {
  it('starts with the unfinished tickets, soonest deadline first, page 1', () => {
    expect(parseFilters(new URLSearchParams())).toEqual(DEFAULT_FILTERS)
    expect(DEFAULT_FILTERS.status).toEqual(['OPEN', 'IN_PROGRESS', 'WAITING_REQUESTER'])
  })

  it('keeps the URL clean when the filters are the defaults', () => {
    expect(toSearchParams(DEFAULT_FILTERS).toString()).toBe('')
  })

  it('survives a round trip through the URL', () => {
    const filters: TicketFilters = {
      status: ['RESOLVED'],
      priority: 'HIGH',
      categoryId: 3,
      sla: 'OVERDUE',
      q: 'impressora',
      mine: true,
      sort: 'createdAt,desc',
      page: 2,
    }

    expect(parseFilters(toSearchParams(filters))).toEqual(filters)
  })

  it('remembers "all statuses" (an empty selection) as different from the default', () => {
    const params = toSearchParams({ ...DEFAULT_FILTERS, status: [] })

    expect(params.toString()).toBe('status=')
    expect(parseFilters(params).status).toEqual([])
  })

  it('ignores garbage typed or pasted into the URL instead of breaking the page', () => {
    const params = new URLSearchParams(
      'status=FOO&status=OPEN&priority=URGENT&categoryId=abc&sla=LATE&sort=title&page=-3',
    )

    expect(parseFilters(params)).toEqual({ ...DEFAULT_FILTERS, status: ['OPEN'] })
  })
})

describe('toApiQuery', () => {
  it('repeats status, trims the search, sends a 0-based page and a fixed page size', () => {
    const query = toApiQuery({ ...DEFAULT_FILTERS, page: 3, q: '  vpn  ' })

    expect(query).toBe(
      'status=OPEN&status=IN_PROGRESS&status=WAITING_REQUESTER&q=vpn&sort=dueAt%2Casc&page=2&size=20',
    )
  })

  it('sends no status at all when every status is wanted', () => {
    expect(toApiQuery({ ...DEFAULT_FILTERS, status: [] })).toBe('sort=dueAt%2Casc&page=0&size=20')
  })
})
```

`frontend/src/tickets/SlaBadge.test.tsx`

```tsx
import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { renderUi } from '../test/render'
import { SlaBadge } from './SlaBadge'

const now = new Date('2026-09-29T12:00:00Z')

describe('SlaBadge', () => {
  it('shows the indicator and how late an overdue ticket is', () => {
    renderUi(<SlaBadge sla="OVERDUE" dueAt="2026-09-29T10:00:00Z" now={now} />)

    expect(screen.getByText('Vencido')).toBeInTheDocument()
    expect(screen.getByText('venceu há 2 h')).toBeInTheDocument()
  })

  it('shows how much time is left while the clock runs', () => {
    renderUi(<SlaBadge sla="AT_RISK" dueAt="2026-09-29T12:45:00Z" now={now} />)

    expect(screen.getByText('Em risco')).toBeInTheDocument()
    expect(screen.getByText('vence em 45 min')).toBeInTheDocument()
  })

  it('shows no countdown when the clock is stopped', () => {
    renderUi(<SlaBadge sla="PAUSED" dueAt="2026-09-29T10:00:00Z" now={now} />)

    expect(screen.getByText('Pausado')).toBeInTheDocument()
    expect(screen.queryByText(/vence/)).not.toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Rodar e ver falhar**

```bash
cd frontend && npm test -- src/tickets; cd ..
```

Expected: FAIL — `Failed to resolve import "./filters"` e `Failed to resolve import "./SlaBadge"`.

- [ ] **Step 3: Implementar filtros e indicador**

`frontend/src/tickets/filters.ts`

```ts
import type { Priority, SlaFilter, TicketStatus } from '../api/types'
import { ACTIVE_STATUSES, PRIORITIES, STATUSES } from '../shared/labels'

export type TicketSort = 'dueAt,asc' | 'createdAt,desc'

/** The list filters. They live in the URL, so reload, back/forward and shared links keep them. */
export interface TicketFilters {
  /** Empty means "every status". */
  status: TicketStatus[]
  priority: Priority | null
  categoryId: number | null
  sla: SlaFilter | null
  q: string
  mine: boolean
  sort: TicketSort
  /** 1-based, as shown on screen (the API counts from 0). */
  page: number
}

export const PAGE_SIZE = 20

const SLA_FILTERS: SlaFilter[] = ['OVERDUE', 'AT_RISK']
const SORTS: TicketSort[] = ['dueAt,asc', 'createdAt,desc']

export const DEFAULT_FILTERS: TicketFilters = {
  status: ACTIVE_STATUSES,
  priority: null,
  categoryId: null,
  sla: null,
  q: '',
  mine: false,
  sort: 'dueAt,asc',
  page: 1,
}

/** Narrows a free string from the URL to one of the allowed values, or null. */
function oneOf<T extends string>(value: string | null, allowed: readonly T[]): T | null {
  return allowed.find((option) => option === value) ?? null
}

function positiveInt(value: string | null): number | null {
  const number = Number(value)
  return Number.isInteger(number) && number > 0 ? number : null
}

export function parseFilters(params: URLSearchParams): TicketFilters {
  const status = params.has('status')
    ? STATUSES.filter((status) => params.getAll('status').includes(status))
    : DEFAULT_FILTERS.status
  return {
    status,
    priority: oneOf(params.get('priority'), PRIORITIES),
    categoryId: positiveInt(params.get('categoryId')),
    sla: oneOf(params.get('sla'), SLA_FILTERS),
    q: params.get('q') ?? '',
    mine: params.get('mine') === 'true',
    sort: oneOf(params.get('sort'), SORTS) ?? DEFAULT_FILTERS.sort,
    page: positiveInt(params.get('page')) ?? 1,
  }
}

function sameStatuses(a: TicketStatus[], b: TicketStatus[]): boolean {
  return a.length === b.length && a.every((status) => b.includes(status))
}

/** The filters every query has, in the same order, for the URL and for the API. */
function appendFilters(params: URLSearchParams, filters: TicketFilters) {
  if (filters.priority) params.set('priority', filters.priority)
  if (filters.categoryId) params.set('categoryId', String(filters.categoryId))
  if (filters.sla) params.set('sla', filters.sla)
  if (filters.q.trim()) params.set('q', filters.q.trim())
  if (filters.mine) params.set('mine', 'true')
}

/** Only what differs from the defaults goes to the URL. */
export function toSearchParams(filters: TicketFilters): URLSearchParams {
  const params = new URLSearchParams()
  if (filters.status.length === 0) {
    params.set('status', '')
  } else if (!sameStatuses(filters.status, DEFAULT_FILTERS.status)) {
    filters.status.forEach((status) => params.append('status', status))
  }
  appendFilters(params, filters)
  if (filters.sort !== DEFAULT_FILTERS.sort) params.set('sort', filters.sort)
  if (filters.page > 1) params.set('page', String(filters.page))
  return params
}

/** Query string for GET /api/tickets. */
export function toApiQuery(filters: TicketFilters): string {
  const params = new URLSearchParams()
  filters.status.forEach((status) => params.append('status', status))
  appendFilters(params, filters)
  params.set('sort', filters.sort)
  params.set('page', String(filters.page - 1))
  params.set('size', String(PAGE_SIZE))
  return params.toString()
}
```

`frontend/src/tickets/SlaBadge.tsx`

```tsx
import { Badge, Stack, Text } from '@mantine/core'
import type { SlaIndicator } from '../api/types'
import { formatDueIn } from '../shared/format'
import { SLA_COLORS, SLA_LABELS } from '../shared/labels'

const CLOCK_RUNNING: SlaIndicator[] = ['ON_TRACK', 'AT_RISK', 'OVERDUE']

interface SlaBadgeProps {
  sla: SlaIndicator
  dueAt: string
  /** Only tests pass it; the screen uses the current time. */
  now?: Date
}

/** SLA indicator computed by the backend, plus the countdown while the clock runs. */
export function SlaBadge({ sla, dueAt, now }: SlaBadgeProps) {
  return (
    <Stack gap={2} align="flex-start">
      <Badge color={SLA_COLORS[sla]} variant={sla === 'OVERDUE' ? 'filled' : 'light'}>
        {SLA_LABELS[sla]}
      </Badge>
      {CLOCK_RUNNING.includes(sla) && (
        <Text size="xs" c="dimmed">
          {formatDueIn(dueAt, now)}
        </Text>
      )}
    </Stack>
  )
}
```

- [ ] **Step 4: Rodar os testes**

```bash
cd frontend && npm test; cd ..
```

Expected: `Tests  31 passed (31)`.

- [ ] **Step 5: Hooks de dados e a tela**

`frontend/src/tickets/api.ts`

```ts
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import type { Category, Page, Ticket } from '../api/types'
import { type TicketFilters, toApiQuery } from './filters'

/**
 * Query keys in one place. They are hierarchical: invalidating ticketKeys.detail(7)
 * also invalidates its comments, attachments and history, which start with the same prefix.
 */
export const ticketKeys = {
  lists: () => ['tickets', 'list'] as const,
  list: (query: string) => ['tickets', 'list', query] as const,
  detail: (id: number) => ['tickets', id] as const,
  comments: (id: number) => ['tickets', id, 'comments'] as const,
  attachments: (id: number) => ['tickets', id, 'attachments'] as const,
  history: (id: number) => ['tickets', id, 'history'] as const,
}

export function useTickets(filters: TicketFilters) {
  const query = toApiQuery(filters)
  return useQuery({
    queryKey: ticketKeys.list(query),
    queryFn: () => api.get<Page<Ticket>>(`/tickets?${query}`),
    placeholderData: keepPreviousData, // keeps the old page on screen while the next one loads
  })
}

export function useCategories() {
  return useQuery({
    queryKey: ['categories'],
    queryFn: () => api.get<Category[]>('/categories'),
    staleTime: Infinity, // seeded by a migration: they do not change while the app runs
  })
}
```

`frontend/src/tickets/TicketFiltersBar.tsx`

```tsx
import { Checkbox, Group, MultiSelect, Select, TextInput } from '@mantine/core'
import type { Priority, SlaFilter, TicketStatus } from '../api/types'
import { PRIORITIES, PRIORITY_LABELS, STATUS_LABELS, STATUSES } from '../shared/labels'
import { useCategories } from './api'
import type { TicketFilters, TicketSort } from './filters'

const STATUS_OPTIONS = STATUSES.map((status) => ({ value: status, label: STATUS_LABELS[status] }))
const PRIORITY_OPTIONS = PRIORITIES.map((priority) => ({ value: priority, label: PRIORITY_LABELS[priority] }))
const SLA_OPTIONS: { value: SlaFilter; label: string }[] = [
  { value: 'OVERDUE', label: 'Vencidos' },
  { value: 'AT_RISK', label: 'Em risco' },
]
const SORT_OPTIONS: { value: TicketSort; label: string }[] = [
  { value: 'dueAt,asc', label: 'Prazo mais próximo' },
  { value: 'createdAt,desc', label: 'Mais recentes' },
]

interface TicketFiltersBarProps {
  filters: TicketFilters
  onChange: (changes: Partial<TicketFilters>) => void
  /** "Assigned to me" only makes sense for agents and managers. */
  showMine: boolean
}

export function TicketFiltersBar({ filters, onChange, showMine }: TicketFiltersBarProps) {
  const categories = useCategories()
  const categoryOptions = (categories.data ?? []).map((category) => ({ value: category.id, label: category.name }))

  return (
    <Group align="flex-end" gap="sm">
      {/* key: when the URL changes (back button), the input restarts with the new text. */}
      <form
        key={filters.q}
        onSubmit={(event) => {
          event.preventDefault()
          onChange({ q: String(new FormData(event.currentTarget).get('q') ?? '') })
        }}
      >
        <TextInput
          name="q"
          label="Buscar"
          placeholder="Título ou descrição + Enter"
          defaultValue={filters.q}
          w={240}
        />
      </form>
      <MultiSelect<TicketStatus>
        label="Status"
        placeholder={filters.status.length === 0 ? 'Todos' : undefined}
        data={STATUS_OPTIONS}
        value={filters.status}
        onChange={(status) => onChange({ status })}
        w={300}
      />
      <Select<Priority>
        label="Prioridade"
        placeholder="Todas"
        data={PRIORITY_OPTIONS}
        value={filters.priority}
        onChange={(priority) => onChange({ priority })}
        clearable
        w={140}
      />
      <Select<number>
        label="Categoria"
        placeholder="Todas"
        data={categoryOptions}
        value={filters.categoryId}
        onChange={(categoryId) => onChange({ categoryId })}
        clearable
        w={150}
      />
      <Select<SlaFilter>
        label="SLA"
        placeholder="Todos"
        data={SLA_OPTIONS}
        value={filters.sla}
        onChange={(sla) => onChange({ sla })}
        clearable
        w={130}
      />
      <Select<TicketSort>
        label="Ordenar por"
        data={SORT_OPTIONS}
        value={filters.sort}
        onChange={(sort) => sort && onChange({ sort })}
        allowDeselect={false}
        w={180}
      />
      {showMine && (
        <Checkbox
          label="Atribuídos a mim"
          checked={filters.mine}
          onChange={(event) => onChange({ mine: event.currentTarget.checked })}
          mb={8}
        />
      )}
    </Group>
  )
}
```

`frontend/src/tickets/TicketListPage.tsx`

```tsx
import { Alert, Anchor, Badge, Button, Center, Group, Loader, Pagination, Stack, Table, Text, Title } from '@mantine/core'
import { Link, useSearchParams } from 'react-router'
import { useCurrentUser } from '../auth/authContext'
import { PRIORITY_COLORS, PRIORITY_LABELS, STATUS_COLORS, STATUS_LABELS } from '../shared/labels'
import { useTickets } from './api'
import { parseFilters, type TicketFilters, toSearchParams } from './filters'
import { SlaBadge } from './SlaBadge'
import { TicketFiltersBar } from './TicketFiltersBar'

export function TicketListPage() {
  const user = useCurrentUser()
  const [searchParams, setSearchParams] = useSearchParams()
  const filters = parseFilters(searchParams)
  const tickets = useTickets(filters)

  // Changing any filter goes back to page 1; changing the page keeps the filters.
  function changeFilters(changes: Partial<TicketFilters>) {
    setSearchParams(toSearchParams({ ...filters, page: 1, ...changes }))
  }

  return (
    <Stack>
      <Group justify="space-between">
        <Title order={2}>Chamados</Title>
        <Button component={Link} to="/tickets/new">
          Novo chamado
        </Button>
      </Group>

      <TicketFiltersBar filters={filters} onChange={changeFilters} showMine={user.role !== 'REQUESTER'} />

      {tickets.isPending && (
        <Center py="xl">
          <Loader />
        </Center>
      )}
      {tickets.isError && <Alert color="red">{tickets.error.message}</Alert>}
      {tickets.data && tickets.data.content.length === 0 && (
        <Text c="dimmed" py="xl" ta="center">
          Nenhum chamado encontrado com esses filtros.
        </Text>
      )}
      {tickets.data && tickets.data.content.length > 0 && (
        <>
          <Table.ScrollContainer minWidth={860}>
            <Table striped highlightOnHover verticalSpacing="sm">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Nº</Table.Th>
                  <Table.Th>Título</Table.Th>
                  <Table.Th>Status</Table.Th>
                  <Table.Th>Prioridade</Table.Th>
                  <Table.Th>Categoria</Table.Th>
                  <Table.Th>Responsável</Table.Th>
                  <Table.Th>SLA</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {tickets.data.content.map((ticket) => (
                  <Table.Tr key={ticket.id}>
                    <Table.Td>{ticket.id}</Table.Td>
                    <Table.Td>
                      <Anchor component={Link} to={`/tickets/${ticket.id}`}>
                        {ticket.title}
                      </Anchor>
                    </Table.Td>
                    <Table.Td>
                      <Badge color={STATUS_COLORS[ticket.status]} variant="light">
                        {STATUS_LABELS[ticket.status]}
                      </Badge>
                    </Table.Td>
                    <Table.Td>
                      <Badge color={PRIORITY_COLORS[ticket.priority]} variant="outline">
                        {PRIORITY_LABELS[ticket.priority]}
                      </Badge>
                    </Table.Td>
                    <Table.Td>{ticket.category.name}</Table.Td>
                    <Table.Td>{ticket.assignee?.name ?? '—'}</Table.Td>
                    <Table.Td>
                      <SlaBadge sla={ticket.sla} dueAt={ticket.dueAt} />
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Table.ScrollContainer>
          <Group justify="space-between">
            <Text size="sm" c="dimmed">
              {tickets.data.totalElements} chamado(s)
            </Text>
            <Pagination
              total={tickets.data.totalPages}
              value={filters.page}
              onChange={(page) => setSearchParams(toSearchParams({ ...filters, page }))}
            />
          </Group>
        </>
      )}
    </Stack>
  )
}
```

`frontend/src/router.tsx`

```tsx
import { Navigate, type RouteObject } from 'react-router'
import { GuestOnly, RequireAuth } from './auth/guards'
import { LoginPage } from './auth/LoginPage'
import { RegisterPage } from './auth/RegisterPage'
import { AppLayout } from './layout/AppLayout'
import { TicketListPage } from './tickets/TicketListPage'

export const routes: RouteObject[] = [
  {
    element: <GuestOnly />,
    children: [
      { path: '/login', element: <LoginPage /> },
      { path: '/register', element: <RegisterPage /> },
    ],
  },
  {
    element: <RequireAuth />,
    children: [
      {
        element: <AppLayout />,
        children: [{ path: '/tickets', element: <TicketListPage /> }],
      },
    ],
  },
  { path: '*', element: <Navigate to="/tickets" replace /> },
]
```

- [ ] **Step 6: Lint, tipos e navegador**

```bash
cd frontend && npm run lint && npx tsc -b && npm run dev
```

Conferir com a API rodando: a lista abre só com os não finalizados, com vencidos, em risco e no prazo; filtrar por "Vencidos", buscar "impressora" + Enter, trocar de página e **recarregar a página** (os filtros continuam); o botão Voltar desfaz o último filtro; limpar todos os status mostra também resolvidos e fechados; o atendente vê "Atribuídos a mim", o solicitante não (e só vê os próprios chamados).

- [ ] **Step 7: Commit**

```bash
git add frontend/src
git commit -m "feat: add ticket list with URL filters, pagination and SLA badge"
```

---

### Task 5: Abrir chamado

**Objetivo:** formulário de novo chamado com validação no navegador, mensagens do servidor por campo, e ida para a página do chamado criado.

**Conceitos:** `useMutation` e callbacks por chamada (`mutate(values, { onSuccess })`); navegação programática (`useNavigate`); validação no cliente (experiência) × no servidor (a regra de verdade); generics (`Select<Priority>`, `Select<number>`: o valor já chega com o tipo certo, sem converter string); invalidar a lista depois de criar.

**Files:**
- Create: `frontend/src/tickets/NewTicketPage.tsx`
- Modify (substituir o arquivo inteiro): `frontend/src/tickets/api.ts`, `frontend/src/router.tsx`
- Test: `frontend/src/tickets/NewTicketPage.test.tsx`

**Interfaces:**
- Consumes: `useCategories`, `ticketKeys` (Task 4); `ApiError` (Task 2); `mockApi`, `renderRoutes`, `sentBody` (Task 3).
- Produces: `interface NewTicket { title: string; description: string; priority: Priority; categoryId: number }`; `useCreateTicket()`.

- [ ] **Step 1: Escrever o teste da tela (falhando)**

`frontend/src/tickets/NewTicketPage.test.tsx`

```tsx
import { screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mockApi, renderRoutes, sentBody } from '../test/render'
import { NewTicketPage } from './NewTicketPage'

const categories = [
  { id: 1, name: 'Acesso' },
  { id: 2, name: 'Hardware' },
]

const routes = [
  { path: '/tickets/new', element: <NewTicketPage /> },
  { path: '/tickets/:id', element: <p>Detalhe do chamado</p> },
]

afterEach(() => vi.unstubAllGlobals())

describe('NewTicketPage', () => {
  it('opens the ticket with the chosen category and goes to its page', async () => {
    const fetchMock = mockApi({
      'GET /categories': [200, categories],
      'POST /tickets': [201, { id: 42 }],
    })
    const { user, router } = renderRoutes(routes, '/tickets/new')

    await user.type(screen.getByLabelText('Título'), '  Sem acesso ao e-mail ')
    await user.type(screen.getByLabelText('Descrição'), 'Senha expirou')
    await user.click(screen.getByRole('combobox', { name: 'Categoria' }))
    await user.click(await screen.findByRole('option', { name: 'Hardware' }))
    await user.click(screen.getByRole('button', { name: 'Abrir chamado' }))

    expect(await screen.findByText('Detalhe do chamado')).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/tickets/42')
    expect(sentBody(fetchMock, 'POST /tickets')).toEqual({
      title: 'Sem acesso ao e-mail',
      description: 'Senha expirou',
      priority: 'MEDIUM',
      categoryId: 2,
    })
  })

  it('does not call the API while required fields are empty', async () => {
    const fetchMock = mockApi({ 'GET /categories': [200, categories] })
    const { user } = renderRoutes(routes, '/tickets/new')

    await user.click(screen.getByRole('button', { name: 'Abrir chamado' }))

    expect(await screen.findByText('Escolha a categoria.')).toBeInTheDocument()
    expect(fetchMock.mock.calls.map(([input]) => String(input))).toEqual(['http://localhost:8080/api/categories'])
  })

  it('shows the messages the API sends for each field', async () => {
    mockApi({
      'GET /categories': [200, categories],
      'POST /tickets': [400, { status: 400, detail: 'Dados inválidos.', errors: { title: 'Título muito longo.' } }],
    })
    const { user } = renderRoutes(routes, '/tickets/new')

    await user.type(screen.getByLabelText('Título'), 'Qualquer')
    await user.type(screen.getByLabelText('Descrição'), 'Qualquer')
    await user.click(screen.getByRole('combobox', { name: 'Categoria' }))
    await user.click(await screen.findByRole('option', { name: 'Acesso' }))
    await user.click(screen.getByRole('button', { name: 'Abrir chamado' }))

    expect(await screen.findByText('Título muito longo.')).toBeInTheDocument()
    expect(screen.getByText('Dados inválidos.')).toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Rodar e ver falhar**

```bash
cd frontend && npm test -- NewTicketPage; cd ..
```

Expected: FAIL — `Failed to resolve import "./NewTicketPage"`.

- [ ] **Step 3: Mutation de criação, tela e rota**

`frontend/src/tickets/api.ts`

```ts
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { Category, Page, Priority, Ticket } from '../api/types'
import { type TicketFilters, toApiQuery } from './filters'

/**
 * Query keys in one place. They are hierarchical: invalidating ticketKeys.detail(7)
 * also invalidates its comments, attachments and history, which start with the same prefix.
 */
export const ticketKeys = {
  lists: () => ['tickets', 'list'] as const,
  list: (query: string) => ['tickets', 'list', query] as const,
  detail: (id: number) => ['tickets', id] as const,
  comments: (id: number) => ['tickets', id, 'comments'] as const,
  attachments: (id: number) => ['tickets', id, 'attachments'] as const,
  history: (id: number) => ['tickets', id, 'history'] as const,
}

export function useTickets(filters: TicketFilters) {
  const query = toApiQuery(filters)
  return useQuery({
    queryKey: ticketKeys.list(query),
    queryFn: () => api.get<Page<Ticket>>(`/tickets?${query}`),
    placeholderData: keepPreviousData, // keeps the old page on screen while the next one loads
  })
}

export function useCategories() {
  return useQuery({
    queryKey: ['categories'],
    queryFn: () => api.get<Category[]>('/categories'),
    staleTime: Infinity, // seeded by a migration: they do not change while the app runs
  })
}

export interface NewTicket {
  title: string
  description: string
  priority: Priority
  categoryId: number
}

export function useCreateTicket() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (ticket: NewTicket) => api.post<Ticket>('/tickets', ticket),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ticketKeys.lists() }),
    meta: { inlineError: true },
  })
}
```

`frontend/src/tickets/NewTicketPage.tsx`

```tsx
import { Alert, Button, Group, Paper, Select, Stack, Textarea, TextInput, Title } from '@mantine/core'
import { hasLength, isNotEmpty, useForm } from '@mantine/form'
import { notifications } from '@mantine/notifications'
import { useNavigate } from 'react-router'
import { ApiError } from '../api/client'
import type { Priority } from '../api/types'
import { PRIORITIES, PRIORITY_LABELS } from '../shared/labels'
import { useCategories, useCreateTicket } from './api'

const PRIORITY_OPTIONS = PRIORITIES.map((priority) => ({ value: priority, label: PRIORITY_LABELS[priority] }))

interface NewTicketForm {
  title: string
  description: string
  priority: Priority
  categoryId: number | null
}

export function NewTicketPage() {
  const navigate = useNavigate()
  const categories = useCategories()
  const createTicket = useCreateTicket()
  const form = useForm<NewTicketForm>({
    initialValues: { title: '', description: '', priority: 'MEDIUM', categoryId: null },
    validate: {
      title: hasLength({ min: 1, max: 120 }, 'Informe um título de até 120 caracteres.'),
      description: hasLength({ min: 1, max: 5000 }, 'Descreva o problema (até 5000 caracteres).'),
      categoryId: isNotEmpty('Escolha a categoria.'),
    },
  })

  function submit(values: NewTicketForm) {
    createTicket.mutate(
      { ...values, title: values.title.trim(), categoryId: values.categoryId! },
      {
        onSuccess: (ticket) => {
          notifications.show({ color: 'green', message: `Chamado nº ${ticket.id} aberto.` })
          navigate(`/tickets/${ticket.id}`)
        },
        onError: (error) => {
          if (error instanceof ApiError) {
            form.setErrors(error.fieldErrors)
          }
        },
      },
    )
  }

  return (
    <Stack maw={720}>
      <Title order={2}>Novo chamado</Title>
      <Paper withBorder p="lg" radius="md">
        <form onSubmit={form.onSubmit(submit)}>
          <Stack>
            {createTicket.error && <Alert color="red">{createTicket.error.message}</Alert>}
            <TextInput label="Título" placeholder="Resumo do problema" maxLength={120} {...form.getInputProps('title')} />
            <Textarea
              label="Descrição"
              placeholder="O que aconteceu, desde quando, o que já tentou..."
              autosize
              minRows={5}
              maxLength={5000}
              {...form.getInputProps('description')}
            />
            <Group grow align="flex-start">
              <Select<Priority>
                label="Prioridade"
                data={PRIORITY_OPTIONS}
                allowDeselect={false}
                {...form.getInputProps('priority')}
              />
              <Select<number>
                label="Categoria"
                placeholder="Escolha"
                data={(categories.data ?? []).map((category) => ({ value: category.id, label: category.name }))}
                {...form.getInputProps('categoryId')}
              />
            </Group>
            <Group justify="flex-end">
              <Button variant="default" onClick={() => navigate(-1)}>
                Cancelar
              </Button>
              <Button type="submit" loading={createTicket.isPending}>
                Abrir chamado
              </Button>
            </Group>
          </Stack>
        </form>
      </Paper>
    </Stack>
  )
}
```

`frontend/src/router.tsx`

```tsx
import { Navigate, type RouteObject } from 'react-router'
import { GuestOnly, RequireAuth } from './auth/guards'
import { LoginPage } from './auth/LoginPage'
import { RegisterPage } from './auth/RegisterPage'
import { AppLayout } from './layout/AppLayout'
import { NewTicketPage } from './tickets/NewTicketPage'
import { TicketListPage } from './tickets/TicketListPage'

export const routes: RouteObject[] = [
  {
    element: <GuestOnly />,
    children: [
      { path: '/login', element: <LoginPage /> },
      { path: '/register', element: <RegisterPage /> },
    ],
  },
  {
    element: <RequireAuth />,
    children: [
      {
        element: <AppLayout />,
        children: [
          { path: '/tickets', element: <TicketListPage /> },
          { path: '/tickets/new', element: <NewTicketPage /> },
        ],
      },
    ],
  },
  { path: '*', element: <Navigate to="/tickets" replace /> },
]
```

- [ ] **Step 4: Rodar testes, lint e tipos**

```bash
cd frontend && npm test && npm run lint && npx tsc -b; cd ..
```

Expected: `Tests  34 passed (34)`.

- [ ] **Step 5: Conferir no navegador**

Como solicitante: "Novo chamado", enviar vazio (mensagens sob os campos, nenhuma requisição), preencher e abrir. Aparece a notificação "Chamado nº N aberto." e a URL vai para `/tickets/N` (a tela de detalhe chega na Task 6; por enquanto volta para a lista). O chamado novo aparece na lista.

- [ ] **Step 6: Commit**

```bash
git add frontend/src
git commit -m "feat: add new ticket form"
```

---

### Task 6: Detalhe do chamado e ações por perfil

**Objetivo:** a página do chamado com seus dados, o SLA, e as ações que cada perfil pode usar: assumir, atribuir, mudar status, prioridade e categoria. Toda ação envia a versão da tela, guarda a resposta no cache e, em 409, recarrega o chamado.

**Conceitos:** controle de concorrência otimista visto do cliente (`version` em cada alteração, 409 quando alguém mudou antes); `setQueryData` (usar a resposta) × `invalidateQueries` (buscar de novo); permissões derivadas por uma função pura, espelho das regras do backend (que continua sendo a autoridade); `switch` exaustivo sobre uma union; 404 na tela quando o solicitante abre o chamado de outra pessoa.

**Files:**
- Create: `frontend/src/tickets/permissions.ts`, `frontend/src/tickets/TicketSummary.tsx`, `frontend/src/tickets/TicketActions.tsx`, `frontend/src/tickets/TicketDetailPage.tsx`
- Modify (substituir o arquivo inteiro): `frontend/src/tickets/api.ts`, `frontend/src/router.tsx`
- Test: `frontend/src/tickets/permissions.test.ts`, `frontend/src/tickets/api.test.tsx`

**Interfaces:**
- Consumes: `ticketKeys`, `useCategories` (Task 4); `ApiError`, `createQueryClient` (Task 2); `useCurrentUser` (Task 3); `mockApi`, `sentBody` (Task 3); `SlaBadge` (Task 4); `formatDateTime` (Task 1).
- Produces: `interface TicketPermissions { canTake; canAssign; canEdit; canComment: boolean; transitions: TicketStatus[] }`; `ticketPermissions(ticket: Ticket, user: User): TicketPermissions`; `transitionLabel(from, to): string`; `useTicket(id)`, `useAssignableUsers(enabled)`, `useChangeStatus(id)` (`mutate({ status, version })`), `useAssignTicket(id)` (`mutate({ assigneeId, version })`), `useUpdateTicket(id)` (`mutate({ priority?, categoryId?, version })`); `<TicketSummary ticket />`, `<TicketActions ticket permissions />`, `<TicketDetailPage />`.

- [ ] **Step 1: Escrever os testes de permissões e das mutations (falhando)**

`frontend/src/tickets/permissions.test.ts`

```ts
import { describe, expect, it } from 'vitest'
import type { Ticket, TicketStatus, User } from '../api/types'
import { ticketPermissions, transitionLabel } from './permissions'

const requester: User = { id: 1, name: 'Sol', email: 's@x.com', role: 'REQUESTER', active: true, demo: false }
const agent: User = { id: 2, name: 'Ana', email: 'a@x.com', role: 'AGENT', active: true, demo: false }
const otherAgent: User = { id: 3, name: 'Beto', email: 'b@x.com', role: 'AGENT', active: true, demo: false }
const manager: User = { id: 4, name: 'Gil', email: 'g@x.com', role: 'MANAGER', active: true, demo: false }

function ticket(status: TicketStatus, assigneeId: number | null = agent.id): Ticket {
  return {
    id: 10,
    title: 'Sem VPN',
    description: 'Não conecta',
    priority: 'HIGH',
    status,
    category: { id: 1, name: 'Acesso' },
    requester: { id: requester.id, name: requester.name },
    assignee: assigneeId === null ? null : { id: assigneeId, name: 'Ana' },
    createdAt: '2026-09-29T10:00:00Z',
    dueAt: '2026-09-29T18:00:00Z',
    resolvedAt: null,
    slaBreached: null,
    sla: 'ON_TRACK',
    version: 0,
  }
}

describe('ticketPermissions (mirrors the backend rules, which have the final word)', () => {
  it('lets an agent take an open ticket, but not change its status by hand', () => {
    const permissions = ticketPermissions(ticket('OPEN', null), agent)

    expect(permissions.canTake).toBe(true)
    expect(permissions.transitions).toEqual([])
  })

  it('gives status and field changes to the assignee only, not to other agents', () => {
    expect(ticketPermissions(ticket('IN_PROGRESS'), agent)).toMatchObject({
      canEdit: true,
      transitions: ['WAITING_REQUESTER', 'RESOLVED'],
    })
    expect(ticketPermissions(ticket('IN_PROGRESS'), otherAgent)).toMatchObject({
      canTake: false,
      canEdit: false,
      transitions: [],
    })
  })

  it('lets the requester close or reopen a resolved ticket, and nothing else', () => {
    expect(ticketPermissions(ticket('RESOLVED'), requester)).toEqual({
      canTake: false,
      canAssign: false,
      canEdit: false,
      canComment: true,
      transitions: ['CLOSED', 'IN_PROGRESS'],
    })
    expect(ticketPermissions(ticket('RESOLVED'), agent).transitions).toEqual([])
  })

  it('lets the manager assign any unfinished ticket and change any status', () => {
    expect(ticketPermissions(ticket('WAITING_REQUESTER'), manager)).toMatchObject({
      canAssign: true,
      canEdit: true,
      transitions: ['IN_PROGRESS'],
    })
    expect(ticketPermissions(ticket('RESOLVED'), manager).canAssign).toBe(false)
  })

  it('freezes a closed ticket for everyone', () => {
    expect(ticketPermissions(ticket('CLOSED'), manager)).toEqual({
      canTake: false,
      canAssign: false,
      canEdit: false,
      canComment: false,
      transitions: [],
    })
  })
})

describe('transitionLabel', () => {
  it('names the action from the user point of view', () => {
    expect(transitionLabel('IN_PROGRESS', 'WAITING_REQUESTER')).toBe('Pedir informação')
    expect(transitionLabel('IN_PROGRESS', 'RESOLVED')).toBe('Marcar como resolvido')
    expect(transitionLabel('WAITING_REQUESTER', 'IN_PROGRESS')).toBe('Retomar atendimento')
    expect(transitionLabel('RESOLVED', 'IN_PROGRESS')).toBe('Reabrir')
    expect(transitionLabel('RESOLVED', 'CLOSED')).toBe('Confirmar e fechar')
  })
})
```

`frontend/src/tickets/api.test.tsx`

```tsx
import { QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '../api/queryClient'
import type { Ticket } from '../api/types'
import { mockApi, sentBody } from '../test/render'
import { ticketKeys, useChangeStatus } from './api'

const ticket: Ticket = {
  id: 7,
  title: 'Impressora parada',
  description: 'Não imprime',
  priority: 'MEDIUM',
  status: 'IN_PROGRESS',
  category: { id: 2, name: 'Hardware' },
  requester: { id: 1, name: 'Sol' },
  assignee: { id: 2, name: 'Ana' },
  createdAt: '2026-09-29T10:00:00Z',
  dueAt: '2026-09-30T10:00:00Z',
  resolvedAt: null,
  slaBreached: null,
  sla: 'ON_TRACK',
  version: 3,
}

function setup() {
  const queryClient = createQueryClient()
  queryClient.setQueryData(ticketKeys.detail(7), ticket)
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
  return { queryClient, wrapper }
}

afterEach(() => vi.unstubAllGlobals())

describe('ticket mutations keep the screen in sync with the version', () => {
  it('stores the ticket answered by the API, so the next action sends the new version', async () => {
    const fetchMock = mockApi({ 'POST /tickets/7/status': [200, { ...ticket, status: 'WAITING_REQUESTER', version: 4 }] })
    const { queryClient, wrapper } = setup()
    const { result } = renderHook(() => useChangeStatus(7), { wrapper })

    await act(() => result.current.mutateAsync({ status: 'WAITING_REQUESTER', version: 3 }))

    expect(sentBody(fetchMock, 'POST /tickets/7/status')).toEqual({ status: 'WAITING_REQUESTER', version: 3 })
    expect(queryClient.getQueryData<Ticket>(ticketKeys.detail(7))).toMatchObject({
      status: 'WAITING_REQUESTER',
      version: 4,
    })
  })

  it('reloads the ticket on 409, when someone else changed it first', async () => {
    mockApi({ 'POST /tickets/7/status': [409, { status: 409, detail: 'O chamado foi alterado por outra pessoa.' }] })
    const { queryClient, wrapper } = setup()
    const { result } = renderHook(() => useChangeStatus(7), { wrapper })

    await act(() => result.current.mutateAsync({ status: 'RESOLVED', version: 3 }).catch(() => {}))

    expect(queryClient.getQueryState(ticketKeys.detail(7))?.isInvalidated).toBe(true)
  })
})
```

- [ ] **Step 2: Rodar e ver falhar**

```bash
cd frontend && npm test -- src/tickets; cd ..
```

Expected: FAIL — `Failed to resolve import "./permissions"`, e os testes de `api.test.tsx` falham com `... is not a function` (o hook `useChangeStatus` ainda não existe).

- [ ] **Step 3: Permissões e mutations**

`frontend/src/tickets/permissions.ts`

```ts
import type { Ticket, TicketStatus, User } from '../api/types'
import { ACTIVE_STATUSES } from '../shared/labels'

export interface TicketPermissions {
  /** An agent takes an open ticket for themselves. */
  canTake: boolean
  /** A manager picks (or changes) the assignee. */
  canAssign: boolean
  /** Change priority and category. */
  canEdit: boolean
  /** Comment and attach files. */
  canComment: boolean
  /** Statuses this user can move the ticket to with a button. */
  transitions: TicketStatus[]
}

// OPEN -> IN_PROGRESS is not here: it only happens through assignment.
const NEXT_STATUSES: Record<TicketStatus, TicketStatus[]> = {
  OPEN: [],
  IN_PROGRESS: ['WAITING_REQUESTER', 'RESOLVED'],
  WAITING_REQUESTER: ['IN_PROGRESS'],
  RESOLVED: ['CLOSED', 'IN_PROGRESS'],
  CLOSED: [],
}

/**
 * What the screen offers to this user. It only hides buttons: the backend checks
 * every request again and answers 403/409 when the rules do not allow it.
 */
export function ticketPermissions(ticket: Ticket, user: User): TicketPermissions {
  const isManager = user.role === 'MANAGER'
  const isAssignee = ticket.assignee?.id === user.id
  const isRequester = ticket.requester.id === user.id
  // A resolved ticket is confirmed or reopened by whoever asked for it; the rest, by whoever works on it.
  const canMove = isManager || (ticket.status === 'RESOLVED' ? isRequester : isAssignee)

  return {
    canTake: user.role === 'AGENT' && ticket.status === 'OPEN',
    canAssign: isManager && ACTIVE_STATUSES.includes(ticket.status),
    canEdit: (isManager || isAssignee) && ticket.status !== 'CLOSED',
    canComment: ticket.status !== 'CLOSED',
    transitions: canMove ? NEXT_STATUSES[ticket.status] : [],
  }
}

export function transitionLabel(from: TicketStatus, to: TicketStatus): string {
  switch (to) {
    case 'WAITING_REQUESTER':
      return 'Pedir informação'
    case 'RESOLVED':
      return 'Marcar como resolvido'
    case 'CLOSED':
      return 'Confirmar e fechar'
    case 'IN_PROGRESS':
      return from === 'RESOLVED' ? 'Reabrir' : 'Retomar atendimento'
    case 'OPEN':
      return 'Abrir'
  }
}
```

`frontend/src/tickets/api.ts`

```ts
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, ApiError } from '../api/client'
import type { Category, Page, Priority, Ticket, TicketStatus, UserSummary } from '../api/types'
import { type TicketFilters, toApiQuery } from './filters'

/**
 * Query keys in one place. They are hierarchical: invalidating ticketKeys.detail(7)
 * also invalidates its comments, attachments and history, which start with the same prefix.
 */
export const ticketKeys = {
  lists: () => ['tickets', 'list'] as const,
  list: (query: string) => ['tickets', 'list', query] as const,
  detail: (id: number) => ['tickets', id] as const,
  comments: (id: number) => ['tickets', id, 'comments'] as const,
  attachments: (id: number) => ['tickets', id, 'attachments'] as const,
  history: (id: number) => ['tickets', id, 'history'] as const,
}

export function useTickets(filters: TicketFilters) {
  const query = toApiQuery(filters)
  return useQuery({
    queryKey: ticketKeys.list(query),
    queryFn: () => api.get<Page<Ticket>>(`/tickets?${query}`),
    placeholderData: keepPreviousData, // keeps the old page on screen while the next one loads
  })
}

export function useTicket(id: number) {
  return useQuery({ queryKey: ticketKeys.detail(id), queryFn: () => api.get<Ticket>(`/tickets/${id}`) })
}

export function useCategories() {
  return useQuery({
    queryKey: ['categories'],
    queryFn: () => api.get<Category[]>('/categories'),
    staleTime: Infinity, // seeded by a migration: they do not change while the app runs
  })
}

export function useAssignableUsers(enabled: boolean) {
  return useQuery({
    queryKey: ['users', 'assignable'],
    queryFn: () => api.get<UserSummary[]>('/users/assignable'),
    enabled,
  })
}

export interface NewTicket {
  title: string
  description: string
  priority: Priority
  categoryId: number
}

export function useCreateTicket() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (ticket: NewTicket) => api.post<Ticket>('/tickets', ticket),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ticketKeys.lists() }),
    meta: { inlineError: true },
  })
}

/**
 * Every change to a ticket answers with the updated ticket (and its new version). We put it
 * straight into the cache, so the next action on the same screen already sends the new version.
 * On 409 (someone changed it first, or a rule refused it) we reload the ticket.
 */
function useTicketChange<Variables>(ticketId: number, request: (variables: Variables) => Promise<Ticket>) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: request,
    onSuccess: (ticket) => {
      queryClient.setQueryData(ticketKeys.detail(ticketId), ticket)
      void queryClient.invalidateQueries({ queryKey: ticketKeys.history(ticketId) })
      void queryClient.invalidateQueries({ queryKey: ticketKeys.lists() })
    },
    onError: (error) => {
      if (error instanceof ApiError && error.status === 409) {
        void queryClient.invalidateQueries({ queryKey: ticketKeys.detail(ticketId) })
      }
    },
  })
}

export function useChangeStatus(ticketId: number) {
  return useTicketChange(ticketId, (body: { status: TicketStatus; version: number }) =>
    api.post<Ticket>(`/tickets/${ticketId}/status`, body),
  )
}

export function useAssignTicket(ticketId: number) {
  return useTicketChange(ticketId, (body: { assigneeId: number; version: number }) =>
    api.post<Ticket>(`/tickets/${ticketId}/assign`, body),
  )
}

export function useUpdateTicket(ticketId: number) {
  return useTicketChange(ticketId, (body: { priority?: Priority; categoryId?: number; version: number }) =>
    api.patch<Ticket>(`/tickets/${ticketId}`, body),
  )
}
```

- [ ] **Step 4: Rodar os testes**

```bash
cd frontend && npm test; cd ..
```

Expected: `Tests  42 passed (42)`.

- [ ] **Step 5: Telas**

`frontend/src/tickets/TicketSummary.tsx`

```tsx
import { Badge, Group, Paper, Stack, Text } from '@mantine/core'
import type { ReactNode } from 'react'
import type { Ticket } from '../api/types'
import { formatDateTime } from '../shared/format'
import { PRIORITY_COLORS, PRIORITY_LABELS, STATUS_COLORS, STATUS_LABELS } from '../shared/labels'
import { SlaBadge } from './SlaBadge'

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Group justify="space-between" align="flex-start" wrap="nowrap">
      <Text size="sm" c="dimmed">
        {label}
      </Text>
      <div style={{ textAlign: 'right' }}>{children}</div>
    </Group>
  )
}

export function TicketSummary({ ticket }: { ticket: Ticket }) {
  return (
    <Paper withBorder p="md" radius="md">
      <Stack gap="xs">
        <Field label="Status">
          <Badge color={STATUS_COLORS[ticket.status]} variant="light">
            {STATUS_LABELS[ticket.status]}
          </Badge>
        </Field>
        <Field label="Prioridade">
          <Badge color={PRIORITY_COLORS[ticket.priority]} variant="outline">
            {PRIORITY_LABELS[ticket.priority]}
          </Badge>
        </Field>
        <Field label="SLA">
          <SlaBadge sla={ticket.sla} dueAt={ticket.dueAt} />
        </Field>
        <Field label="Prazo">
          <Text size="sm">{formatDateTime(ticket.dueAt)}</Text>
        </Field>
        <Field label="Categoria">
          <Text size="sm">{ticket.category.name}</Text>
        </Field>
        <Field label="Solicitante">
          <Text size="sm">{ticket.requester.name}</Text>
        </Field>
        <Field label="Responsável">
          <Text size="sm">{ticket.assignee?.name ?? '—'}</Text>
        </Field>
        <Field label="Aberto em">
          <Text size="sm">{formatDateTime(ticket.createdAt)}</Text>
        </Field>
        {ticket.resolvedAt && (
          <Field label="Resolvido em">
            <Text size="sm">{formatDateTime(ticket.resolvedAt)}</Text>
          </Field>
        )}
      </Stack>
    </Paper>
  )
}
```

`frontend/src/tickets/TicketActions.tsx`

```tsx
import { Button, Paper, Select, Stack, Text, Title } from '@mantine/core'
import type { Priority, Ticket } from '../api/types'
import { useCurrentUser } from '../auth/authContext'
import { PRIORITIES, PRIORITY_LABELS } from '../shared/labels'
import { useAssignableUsers, useAssignTicket, useCategories, useChangeStatus, useUpdateTicket } from './api'
import { type TicketPermissions, transitionLabel } from './permissions'

const PRIORITY_OPTIONS = PRIORITIES.map((priority) => ({ value: priority, label: PRIORITY_LABELS[priority] }))

interface TicketActionsProps {
  ticket: Ticket
  permissions: TicketPermissions
}

/** Buttons and selects this user may use on this ticket. Every request sends the version on screen. */
export function TicketActions({ ticket, permissions }: TicketActionsProps) {
  const user = useCurrentUser()
  const assign = useAssignTicket(ticket.id)
  const changeStatus = useChangeStatus(ticket.id)
  const update = useUpdateTicket(ticket.id)
  const categories = useCategories()
  const assignable = useAssignableUsers(permissions.canAssign)
  const busy = assign.isPending || changeStatus.isPending || update.isPending
  const { version } = ticket

  const nothingToDo =
    !permissions.canTake && !permissions.canAssign && !permissions.canEdit && permissions.transitions.length === 0

  return (
    <Paper withBorder p="md" radius="md">
      <Stack gap="sm">
        <Title order={5}>Ações</Title>
        {nothingToDo && (
          <Text size="sm" c="dimmed">
            Nenhuma ação disponível para você neste chamado.
          </Text>
        )}

        {permissions.canTake && (
          <Button loading={assign.isPending} onClick={() => assign.mutate({ assigneeId: user.id, version })}>
            Assumir chamado
          </Button>
        )}

        {permissions.transitions.map((status) => (
          <Button
            key={status}
            variant={status === 'IN_PROGRESS' && ticket.status === 'RESOLVED' ? 'default' : 'filled'}
            loading={changeStatus.isPending && changeStatus.variables?.status === status}
            disabled={busy}
            onClick={() => changeStatus.mutate({ status, version })}
          >
            {transitionLabel(ticket.status, status)}
          </Button>
        ))}

        {permissions.canAssign && (
          <Select<number>
            label="Responsável"
            placeholder="Escolha quem atende"
            data={(assignable.data ?? []).map((person) => ({ value: person.id, label: person.name }))}
            value={ticket.assignee?.id ?? null}
            onChange={(assigneeId) => assigneeId !== null && assign.mutate({ assigneeId, version })}
            allowDeselect={false}
            disabled={busy}
          />
        )}

        {permissions.canEdit && (
          <>
            <Select<Priority>
              label="Prioridade"
              data={PRIORITY_OPTIONS}
              value={ticket.priority}
              onChange={(priority) => priority && update.mutate({ priority, version })}
              allowDeselect={false}
              disabled={busy}
            />
            <Select<number>
              label="Categoria"
              data={(categories.data ?? []).map((category) => ({ value: category.id, label: category.name }))}
              value={ticket.category.id}
              onChange={(categoryId) => categoryId !== null && update.mutate({ categoryId, version })}
              allowDeselect={false}
              disabled={busy}
            />
          </>
        )}
      </Stack>
    </Paper>
  )
}
```

`frontend/src/tickets/TicketDetailPage.tsx`

```tsx
import { Alert, Anchor, Center, Grid, Loader, Paper, Stack, Text, Title } from '@mantine/core'
import { Link, useParams } from 'react-router'
import { useCurrentUser } from '../auth/authContext'
import { useTicket } from './api'
import { ticketPermissions } from './permissions'
import { TicketActions } from './TicketActions'
import { TicketSummary } from './TicketSummary'

export function TicketDetailPage() {
  const ticketId = Number(useParams().id)
  const user = useCurrentUser()
  const ticket = useTicket(ticketId)

  if (ticket.isPending) {
    return (
      <Center py="xl">
        <Loader />
      </Center>
    )
  }
  if (ticket.isError) {
    return (
      <Stack>
        <Alert color="red">{ticket.error.message}</Alert>
        <Anchor component={Link} to="/tickets">
          Voltar para a lista
        </Anchor>
      </Stack>
    )
  }

  const current = ticket.data
  const permissions = ticketPermissions(current, user)

  return (
    <Stack>
      <Anchor component={Link} to="/tickets" size="sm">
        ← Chamados
      </Anchor>
      <Title order={2}>
        #{current.id} · {current.title}
      </Title>

      <Grid gap="lg">
        <Grid.Col span={{ base: 12, md: 8 }} order={{ base: 2, md: 1 }}>
          {/* Task 7 adds comments, attachments and history below the description. */}
          <Paper withBorder p="md" radius="md">
            <Text style={{ whiteSpace: 'pre-wrap' }}>{current.description}</Text>
          </Paper>
        </Grid.Col>
        <Grid.Col span={{ base: 12, md: 4 }} order={{ base: 1, md: 2 }}>
          <Stack>
            <TicketSummary ticket={current} />
            <TicketActions ticket={current} permissions={permissions} />
          </Stack>
        </Grid.Col>
      </Grid>
    </Stack>
  )
}
```

`frontend/src/router.tsx`

```tsx
import { Navigate, type RouteObject } from 'react-router'
import { GuestOnly, RequireAuth } from './auth/guards'
import { LoginPage } from './auth/LoginPage'
import { RegisterPage } from './auth/RegisterPage'
import { AppLayout } from './layout/AppLayout'
import { NewTicketPage } from './tickets/NewTicketPage'
import { TicketDetailPage } from './tickets/TicketDetailPage'
import { TicketListPage } from './tickets/TicketListPage'

export const routes: RouteObject[] = [
  {
    element: <GuestOnly />,
    children: [
      { path: '/login', element: <LoginPage /> },
      { path: '/register', element: <RegisterPage /> },
    ],
  },
  {
    element: <RequireAuth />,
    children: [
      {
        element: <AppLayout />,
        children: [
          { path: '/tickets', element: <TicketListPage /> },
          { path: '/tickets/new', element: <NewTicketPage /> },
          { path: '/tickets/:id', element: <TicketDetailPage /> },
        ],
      },
    ],
  },
  { path: '*', element: <Navigate to="/tickets" replace /> },
]
```

- [ ] **Step 6: Lint, tipos e navegador**

```bash
cd frontend && npm run lint && npx tsc -b && npm run dev
```

Conferir: como **atendente**, abrir um chamado "Aberto", "Assumir chamado", depois "Pedir informação", "Retomar atendimento", mudar a prioridade e "Marcar como resolvido", **em sequência, sem recarregar** (nenhum 409). Como **solicitante**, confirmar e fechar (ou reabrir) um chamado resolvido; abrir `/tickets/<id de outra pessoa>` mostra "Chamado não encontrado." (o backend responde 404 com essa mensagem). Como **gestor**, trocar o responsável pelo select. Para ver o 409: abrir o mesmo chamado em duas abas, agir na primeira e depois na segunda. A segunda mostra "O chamado foi alterado por outra pessoa..." e recarrega os dados.

- [ ] **Step 7: Commit**

```bash
git add frontend/src
git commit -m "feat: add ticket detail with role-based actions and version handling"
```

---

### Task 7: Comentários, anexos e histórico

**Objetivo:** abas de comentários, anexos e histórico no detalhe. Um comentário recarrega o chamado (pode ter voltado para "Em atendimento"); um anexo é checado antes do envio (tipo e 5 MB) e baixado com o token; o histórico vira uma linha do tempo em português.

**Conceitos:** invalidação hierárquica de query keys (invalidar `['tickets', 7]` recarrega detalhe, comentários, anexos e histórico); upload multipart com `FormData`; download autenticado com `Blob` + `URL.createObjectURL` (um `<a href>` comum não envia o cabeçalho `Authorization`); validar antes de enviar para não subir megabytes à toa; o React escapa texto por padrão (comentários com `<script>` aparecem como texto; nunca usar `dangerouslySetInnerHTML`); `white-space: pre-wrap` para manter as quebras de linha.

**Files:**
- Create: `frontend/src/tickets/history.ts`, `frontend/src/tickets/attachments.ts`, `frontend/src/tickets/CommentsSection.tsx`, `frontend/src/tickets/AttachmentsSection.tsx`, `frontend/src/tickets/HistoryTimeline.tsx`
- Modify (substituir o arquivo inteiro): `frontend/src/tickets/api.ts`, `frontend/src/tickets/api.test.tsx`, `frontend/src/tickets/TicketDetailPage.tsx`
- Test: `frontend/src/tickets/history.test.ts`, `frontend/src/tickets/attachments.test.ts`, `frontend/src/tickets/api.test.tsx`

**Interfaces:**
- Consumes: `ticketKeys`, `useTicket` (Tasks 4 e 6); `api`, `showError` (Task 2); `ticketPermissions` (Task 6); `formatDateTime`, `formatFileSize`, rótulos (Task 1).
- Produces: `describeEvent(entry: HistoryEntry): string`; `MAX_ATTACHMENT_BYTES`, `ACCEPTED_FILES`, `attachmentProblem(file: File): string | null`, `downloadAttachment(attachment: Attachment): Promise<void>`; `useComments(id)`, `useAddComment(id)` (`mutate(text)`), `useAttachments(id)`, `useUploadAttachment(id)` (`mutate(file)`), `useHistory(id)`; `<CommentsSection ticket canComment />`, `<AttachmentsSection ticketId canAttach />`, `<HistoryTimeline ticketId />`.

- [ ] **Step 1: Escrever os testes (falhando)**

`frontend/src/tickets/history.test.ts`

```ts
import { describe, expect, it } from 'vitest'
import type { HistoryEntry } from '../api/types'
import { describeEvent } from './history'

function entry(changes: Partial<HistoryEntry>): HistoryEntry {
  return {
    id: 1,
    eventType: 'CREATED',
    field: null,
    oldValue: null,
    newValue: null,
    actor: { id: 1, name: 'Ana' },
    occurredAt: '2026-09-29T10:00:00Z',
    ...changes,
  }
}

describe('describeEvent', () => {
  it('describes events without values', () => {
    expect(describeEvent(entry({ eventType: 'CREATED' }))).toBe('abriu o chamado')
    expect(describeEvent(entry({ eventType: 'COMMENT_ADDED' }))).toBe('comentou')
  })

  it('translates status and priority names from the API', () => {
    expect(
      describeEvent(entry({ eventType: 'STATUS_CHANGED', field: 'status', oldValue: 'OPEN', newValue: 'IN_PROGRESS' })),
    ).toBe('mudou o status de Aberto para Em atendimento')
    expect(
      describeEvent(entry({ eventType: 'PRIORITY_CHANGED', field: 'priority', oldValue: 'LOW', newValue: 'CRITICAL' })),
    ).toBe('mudou a prioridade de Baixa para Crítica')
  })

  it('tells a first assignment apart from a reassignment', () => {
    expect(describeEvent(entry({ eventType: 'ASSIGNED', field: 'assignee', newValue: 'Bruno' }))).toBe(
      'atribuiu a Bruno',
    )
    expect(
      describeEvent(entry({ eventType: 'ASSIGNED', field: 'assignee', oldValue: 'Bruno', newValue: 'Carla' })),
    ).toBe('reatribuiu de Bruno para Carla')
  })

  it('shows category names and attachment file names as they are', () => {
    expect(
      describeEvent(entry({ eventType: 'CATEGORY_CHANGED', field: 'category', oldValue: 'Hardware', newValue: 'Acesso' })),
    ).toBe('mudou a categoria de Hardware para Acesso')
    expect(describeEvent(entry({ eventType: 'ATTACHMENT_ADDED', field: 'attachment', newValue: 'log.txt' }))).toBe(
      'anexou log.txt',
    )
  })
})
```

`frontend/src/tickets/attachments.test.ts`

```ts
import { describe, expect, it } from 'vitest'
import { attachmentProblem, MAX_ATTACHMENT_BYTES } from './attachments'

function fileOfSize(name: string, size: number): File {
  return new File([new Uint8Array(size)], name)
}

describe('attachmentProblem (checked before uploading, so a big file is not sent for nothing)', () => {
  it('accepts the allowed types up to exactly 5 MB, whatever the case of the extension', () => {
    expect(attachmentProblem(fileOfSize('relatorio.pdf', MAX_ATTACHMENT_BYTES))).toBeNull()
    expect(attachmentProblem(fileOfSize('FOTO.JPG', 1000))).toBeNull()
    expect(attachmentProblem(fileOfSize('notas.txt', 10))).toBeNull()
  })

  it('refuses one byte over 5 MB', () => {
    expect(attachmentProblem(fileOfSize('video.pdf', MAX_ATTACHMENT_BYTES + 1))).toBe(
      'O arquivo passa do limite de 5 MB.',
    )
  })

  it('refuses empty files and types the backend does not accept', () => {
    expect(attachmentProblem(fileOfSize('vazio.txt', 0))).toBe('O arquivo está vazio.')
    expect(attachmentProblem(fileOfSize('setup.exe', 100))).toBe(
      'Tipo de arquivo não aceito. Envie PDF, PNG, JPEG, TXT ou DOCX.',
    )
    expect(attachmentProblem(fileOfSize('LEIAME', 100))).toBe(
      'Tipo de arquivo não aceito. Envie PDF, PNG, JPEG, TXT ou DOCX.',
    )
  })
})
```

`api.test.tsx` ganha o teste do comentário que retoma o chamado (arquivo inteiro):

`frontend/src/tickets/api.test.tsx`

```tsx
import { QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '../api/queryClient'
import type { Ticket } from '../api/types'
import { mockApi, sentBody } from '../test/render'
import { ticketKeys, useAddComment, useChangeStatus } from './api'

const ticket: Ticket = {
  id: 7,
  title: 'Impressora parada',
  description: 'Não imprime',
  priority: 'MEDIUM',
  status: 'IN_PROGRESS',
  category: { id: 2, name: 'Hardware' },
  requester: { id: 1, name: 'Sol' },
  assignee: { id: 2, name: 'Ana' },
  createdAt: '2026-09-29T10:00:00Z',
  dueAt: '2026-09-30T10:00:00Z',
  resolvedAt: null,
  slaBreached: null,
  sla: 'ON_TRACK',
  version: 3,
}

function setup() {
  const queryClient = createQueryClient()
  queryClient.setQueryData(ticketKeys.detail(7), ticket)
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
  return { queryClient, wrapper }
}

afterEach(() => vi.unstubAllGlobals())

describe('ticket mutations keep the screen in sync with the version', () => {
  it('stores the ticket answered by the API, so the next action sends the new version', async () => {
    const fetchMock = mockApi({ 'POST /tickets/7/status': [200, { ...ticket, status: 'WAITING_REQUESTER', version: 4 }] })
    const { queryClient, wrapper } = setup()
    const { result } = renderHook(() => useChangeStatus(7), { wrapper })

    await act(() => result.current.mutateAsync({ status: 'WAITING_REQUESTER', version: 3 }))

    expect(sentBody(fetchMock, 'POST /tickets/7/status')).toEqual({ status: 'WAITING_REQUESTER', version: 3 })
    expect(queryClient.getQueryData<Ticket>(ticketKeys.detail(7))).toMatchObject({
      status: 'WAITING_REQUESTER',
      version: 4,
    })
  })

  it('reloads the ticket after a comment, because a comment may resume it (new status and version)', async () => {
    mockApi({
      'POST /tickets/7/comments': [201, { id: 1, text: 'Segue o print', author: { id: 1, name: 'Sol' }, createdAt: '' }],
    })
    const { queryClient, wrapper } = setup()
    const { result } = renderHook(() => useAddComment(7), { wrapper })

    await act(() => result.current.mutateAsync('Segue o print'))

    expect(queryClient.getQueryState(ticketKeys.detail(7))?.isInvalidated).toBe(true)
  })

  it('reloads the ticket on 409, when someone else changed it first', async () => {
    mockApi({ 'POST /tickets/7/status': [409, { status: 409, detail: 'O chamado foi alterado por outra pessoa.' }] })
    const { queryClient, wrapper } = setup()
    const { result } = renderHook(() => useChangeStatus(7), { wrapper })

    await act(() => result.current.mutateAsync({ status: 'RESOLVED', version: 3 }).catch(() => {}))

    expect(queryClient.getQueryState(ticketKeys.detail(7))?.isInvalidated).toBe(true)
  })
})
```

- [ ] **Step 2: Rodar e ver falhar**

```bash
cd frontend && npm test -- src/tickets; cd ..
```

Expected: FAIL — `Failed to resolve import "./history"`, `Failed to resolve import "./attachments"`, e o teste novo de `api.test.tsx` falha com `... is not a function` (`useAddComment` ainda não existe).

- [ ] **Step 3: Implementar a lógica**

`frontend/src/tickets/history.ts`

```ts
import type { HistoryEntry, Priority, TicketStatus } from '../api/types'
import { PRIORITY_LABELS, STATUS_LABELS } from '../shared/labels'

/** The history stores enum names ("IN_PROGRESS"); the screen shows the labels. */
function statusLabel(value: string | null): string {
  return STATUS_LABELS[value as TicketStatus] ?? value ?? '—'
}

function priorityLabel(value: string | null): string {
  return PRIORITY_LABELS[value as Priority] ?? value ?? '—'
}

/** One line of the timeline, completing "<actor name> ..." */
export function describeEvent(entry: HistoryEntry): string {
  const { oldValue, newValue } = entry
  switch (entry.eventType) {
    case 'CREATED':
      return 'abriu o chamado'
    case 'STATUS_CHANGED':
      return `mudou o status de ${statusLabel(oldValue)} para ${statusLabel(newValue)}`
    case 'ASSIGNED':
      return oldValue === null ? `atribuiu a ${newValue}` : `reatribuiu de ${oldValue} para ${newValue}`
    case 'PRIORITY_CHANGED':
      return `mudou a prioridade de ${priorityLabel(oldValue)} para ${priorityLabel(newValue)}`
    case 'CATEGORY_CHANGED':
      return `mudou a categoria de ${oldValue} para ${newValue}`
    case 'COMMENT_ADDED':
      return 'comentou'
    case 'ATTACHMENT_ADDED':
      return `anexou ${newValue}`
  }
}
```

`frontend/src/tickets/attachments.ts`

```ts
import { api } from '../api/client'
import type { Attachment } from '../api/types'

/** Same limits as the backend (spring.servlet.multipart.max-file-size and AllowedFileType). */
export const MAX_ATTACHMENT_BYTES = 5 * 1024 * 1024
const ACCEPTED_EXTENSIONS = ['pdf', 'png', 'jpg', 'jpeg', 'txt', 'docx']

/** Value for <input accept>: the file picker already filters these types. */
export const ACCEPTED_FILES = ACCEPTED_EXTENSIONS.map((extension) => `.${extension}`).join(',')

/**
 * Why this file cannot be sent, or null if it can. The backend validates again (including
 * the file signature); checking here avoids uploading megabytes only to get an error back.
 */
export function attachmentProblem(file: File): string | null {
  if (file.size === 0) {
    return 'O arquivo está vazio.'
  }
  if (file.size > MAX_ATTACHMENT_BYTES) {
    return 'O arquivo passa do limite de 5 MB.'
  }
  const dot = file.name.lastIndexOf('.')
  const extension = dot < 0 ? '' : file.name.slice(dot + 1).toLowerCase()
  if (!ACCEPTED_EXTENSIONS.includes(extension)) {
    return 'Tipo de arquivo não aceito. Envie PDF, PNG, JPEG, TXT ou DOCX.'
  }
  return null
}

/**
 * The download needs the Authorization header, so a plain <a href> does not work:
 * we fetch the bytes, wrap them in a temporary object URL and click a hidden link.
 */
export async function downloadAttachment(attachment: Attachment) {
  const blob = await api.blob(`/attachments/${attachment.id}`)
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = attachment.filename
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000) // frees the memory once the browser has the file
}
```

`frontend/src/tickets/api.ts`

```ts
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, ApiError } from '../api/client'
import type {
  Attachment,
  Category,
  HistoryEntry,
  Page,
  Priority,
  Ticket,
  TicketComment,
  TicketStatus,
  UserSummary,
} from '../api/types'
import { type TicketFilters, toApiQuery } from './filters'

/**
 * Query keys in one place. They are hierarchical: invalidating ticketKeys.detail(7)
 * also invalidates its comments, attachments and history, which start with the same prefix.
 */
export const ticketKeys = {
  lists: () => ['tickets', 'list'] as const,
  list: (query: string) => ['tickets', 'list', query] as const,
  detail: (id: number) => ['tickets', id] as const,
  comments: (id: number) => ['tickets', id, 'comments'] as const,
  attachments: (id: number) => ['tickets', id, 'attachments'] as const,
  history: (id: number) => ['tickets', id, 'history'] as const,
}

export function useTickets(filters: TicketFilters) {
  const query = toApiQuery(filters)
  return useQuery({
    queryKey: ticketKeys.list(query),
    queryFn: () => api.get<Page<Ticket>>(`/tickets?${query}`),
    placeholderData: keepPreviousData, // keeps the old page on screen while the next one loads
  })
}

export function useTicket(id: number) {
  return useQuery({ queryKey: ticketKeys.detail(id), queryFn: () => api.get<Ticket>(`/tickets/${id}`) })
}

export function useCategories() {
  return useQuery({
    queryKey: ['categories'],
    queryFn: () => api.get<Category[]>('/categories'),
    staleTime: Infinity, // seeded by a migration: they do not change while the app runs
  })
}

export function useAssignableUsers(enabled: boolean) {
  return useQuery({
    queryKey: ['users', 'assignable'],
    queryFn: () => api.get<UserSummary[]>('/users/assignable'),
    enabled,
  })
}

export interface NewTicket {
  title: string
  description: string
  priority: Priority
  categoryId: number
}

export function useCreateTicket() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (ticket: NewTicket) => api.post<Ticket>('/tickets', ticket),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ticketKeys.lists() }),
    meta: { inlineError: true },
  })
}

/**
 * Every change to a ticket answers with the updated ticket (and its new version). We put it
 * straight into the cache, so the next action on the same screen already sends the new version.
 * On 409 (someone changed it first, or a rule refused it) we reload the ticket.
 */
function useTicketChange<Variables>(ticketId: number, request: (variables: Variables) => Promise<Ticket>) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: request,
    onSuccess: (ticket) => {
      queryClient.setQueryData(ticketKeys.detail(ticketId), ticket)
      void queryClient.invalidateQueries({ queryKey: ticketKeys.history(ticketId) })
      void queryClient.invalidateQueries({ queryKey: ticketKeys.lists() })
    },
    onError: (error) => {
      if (error instanceof ApiError && error.status === 409) {
        void queryClient.invalidateQueries({ queryKey: ticketKeys.detail(ticketId) })
      }
    },
  })
}

export function useChangeStatus(ticketId: number) {
  return useTicketChange(ticketId, (body: { status: TicketStatus; version: number }) =>
    api.post<Ticket>(`/tickets/${ticketId}/status`, body),
  )
}

export function useAssignTicket(ticketId: number) {
  return useTicketChange(ticketId, (body: { assigneeId: number; version: number }) =>
    api.post<Ticket>(`/tickets/${ticketId}/assign`, body),
  )
}

export function useUpdateTicket(ticketId: number) {
  return useTicketChange(ticketId, (body: { priority?: Priority; categoryId?: number; version: number }) =>
    api.patch<Ticket>(`/tickets/${ticketId}`, body),
  )
}

export function useComments(ticketId: number) {
  return useQuery({
    queryKey: ticketKeys.comments(ticketId),
    queryFn: () => api.get<TicketComment[]>(`/tickets/${ticketId}/comments`),
  })
}

/**
 * A comment returns only the comment, but it may have changed the ticket: when the requester
 * answers a ticket waiting for them, the backend moves it back to "in progress" (new status and
 * new version). So we reload the whole ticket: detail, comments and history.
 */
export function useAddComment(ticketId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (text: string) => api.post<TicketComment>(`/tickets/${ticketId}/comments`, { text }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ticketKeys.detail(ticketId) })
      void queryClient.invalidateQueries({ queryKey: ticketKeys.lists() })
    },
  })
}

export function useAttachments(ticketId: number) {
  return useQuery({
    queryKey: ticketKeys.attachments(ticketId),
    queryFn: () => api.get<Attachment[]>(`/tickets/${ticketId}/attachments`),
  })
}

export function useUploadAttachment(ticketId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (file: File) => {
      const form = new FormData()
      form.append('file', file)
      return api.post<Attachment>(`/tickets/${ticketId}/attachments`, form)
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ticketKeys.detail(ticketId) }),
  })
}

export function useHistory(ticketId: number) {
  return useQuery({
    queryKey: ticketKeys.history(ticketId),
    queryFn: () => api.get<HistoryEntry[]>(`/tickets/${ticketId}/history`),
  })
}
```

- [ ] **Step 4: Rodar os testes**

```bash
cd frontend && npm test; cd ..
```

Expected: `Tests  50 passed (50)`.

- [ ] **Step 5: Telas**

`frontend/src/tickets/CommentsSection.tsx`

```tsx
import { Alert, Button, Group, Loader, Paper, Stack, Text, Textarea } from '@mantine/core'
import { useState } from 'react'
import type { Ticket } from '../api/types'
import { useCurrentUser } from '../auth/authContext'
import { formatDateTime } from '../shared/format'
import { useAddComment, useComments } from './api'

interface CommentsSectionProps {
  ticket: Ticket
  canComment: boolean
}

export function CommentsSection({ ticket, canComment }: CommentsSectionProps) {
  const user = useCurrentUser()
  const comments = useComments(ticket.id)
  const addComment = useAddComment(ticket.id)
  const [text, setText] = useState('')
  const waitingForMe = ticket.status === 'WAITING_REQUESTER' && ticket.requester.id === user.id

  return (
    <Stack>
      {comments.isPending && <Loader size="sm" />}
      {comments.data?.length === 0 && (
        <Text size="sm" c="dimmed">
          Nenhum comentário ainda.
        </Text>
      )}
      {comments.data?.map((comment) => (
        <Paper key={comment.id} withBorder p="sm" radius="md">
          <Group justify="space-between" mb={4}>
            <Text size="sm" fw={600}>
              {comment.author.name}
            </Text>
            <Text size="xs" c="dimmed">
              {formatDateTime(comment.createdAt)}
            </Text>
          </Group>
          <Text size="sm" style={{ whiteSpace: 'pre-wrap' }}>
            {comment.text}
          </Text>
        </Paper>
      ))}

      {waitingForMe && (
        <Alert color="yellow">O atendimento precisa de mais informações. Ao comentar, o chamado volta para atendimento.</Alert>
      )}
      {canComment ? (
        <form
          onSubmit={(event) => {
            event.preventDefault()
            addComment.mutate(text.trim(), { onSuccess: () => setText('') })
          }}
        >
          <Stack gap="xs">
            <Textarea
              aria-label="Novo comentário"
              placeholder="Escreva um comentário"
              autosize
              minRows={3}
              maxLength={5000}
              value={text}
              onChange={(event) => setText(event.currentTarget.value)}
            />
            <Group justify="flex-end">
              <Button type="submit" loading={addComment.isPending} disabled={text.trim() === ''}>
                Comentar
              </Button>
            </Group>
          </Stack>
        </form>
      ) : (
        <Text size="sm" c="dimmed">
          Chamados fechados não aceitam comentários.
        </Text>
      )}
    </Stack>
  )
}
```

`frontend/src/tickets/AttachmentsSection.tsx`

```tsx
import { Button, FileButton, Group, Loader, Stack, Table, Text } from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { showError } from '../api/queryClient'
import type { Attachment } from '../api/types'
import { formatDateTime, formatFileSize } from '../shared/format'
import { useAttachments, useUploadAttachment } from './api'
import { ACCEPTED_FILES, attachmentProblem, downloadAttachment } from './attachments'

interface AttachmentsSectionProps {
  ticketId: number
  canAttach: boolean
}

export function AttachmentsSection({ ticketId, canAttach }: AttachmentsSectionProps) {
  const attachments = useAttachments(ticketId)
  const upload = useUploadAttachment(ticketId)

  function send(file: File | null) {
    if (file === null) {
      return
    }
    const problem = attachmentProblem(file)
    if (problem) {
      notifications.show({ color: 'red', title: 'Arquivo não enviado', message: problem })
      return
    }
    upload.mutate(file, {
      onSuccess: () => notifications.show({ color: 'green', message: `${file.name} anexado.` }),
    })
  }

  function download(attachment: Attachment) {
    downloadAttachment(attachment).catch(showError)
  }

  return (
    <Stack>
      {attachments.isPending && <Loader size="sm" />}
      {attachments.data?.length === 0 && (
        <Text size="sm" c="dimmed">
          Nenhum anexo.
        </Text>
      )}
      {attachments.data && attachments.data.length > 0 && (
        <Table.ScrollContainer minWidth={520}>
          <Table verticalSpacing="xs">
            <Table.Tbody>
              {attachments.data.map((attachment) => (
                <Table.Tr key={attachment.id}>
                  <Table.Td>
                    <Button variant="subtle" size="compact-sm" onClick={() => download(attachment)}>
                      {attachment.filename}
                    </Button>
                  </Table.Td>
                  <Table.Td>{formatFileSize(attachment.size)}</Table.Td>
                  <Table.Td>{attachment.uploadedBy.name}</Table.Td>
                  <Table.Td>{formatDateTime(attachment.createdAt)}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
      {canAttach && (
        <Group>
          <FileButton onChange={send} accept={ACCEPTED_FILES}>
            {(props) => (
              <Button {...props} variant="light" loading={upload.isPending}>
                Anexar arquivo
              </Button>
            )}
          </FileButton>
          <Text size="xs" c="dimmed">
            PDF, PNG, JPEG, TXT ou DOCX, até 5 MB.
          </Text>
        </Group>
      )}
    </Stack>
  )
}
```

`frontend/src/tickets/HistoryTimeline.tsx`

```tsx
import { Loader, Text, Timeline } from '@mantine/core'
import { formatDateTime } from '../shared/format'
import { useHistory } from './api'
import { describeEvent } from './history'

export function HistoryTimeline({ ticketId }: { ticketId: number }) {
  const history = useHistory(ticketId)

  if (history.isPending) {
    return <Loader size="sm" />
  }
  if (history.isError) {
    return <Text c="red">{history.error.message}</Text>
  }
  return (
    <Timeline active={history.data.length - 1} bulletSize={14} lineWidth={2}>
      {history.data.map((entry) => (
        <Timeline.Item key={entry.id} title={entry.actor.name}>
          <Text size="sm">{describeEvent(entry)}</Text>
          <Text size="xs" c="dimmed">
            {formatDateTime(entry.occurredAt)}
          </Text>
        </Timeline.Item>
      ))}
    </Timeline>
  )
}
```

`frontend/src/tickets/TicketDetailPage.tsx`

```tsx
import { Alert, Anchor, Center, Grid, Loader, Paper, Stack, Tabs, Text, Title } from '@mantine/core'
import { Link, useParams } from 'react-router'
import { useCurrentUser } from '../auth/authContext'
import { useTicket } from './api'
import { AttachmentsSection } from './AttachmentsSection'
import { CommentsSection } from './CommentsSection'
import { HistoryTimeline } from './HistoryTimeline'
import { ticketPermissions } from './permissions'
import { TicketActions } from './TicketActions'
import { TicketSummary } from './TicketSummary'

export function TicketDetailPage() {
  const ticketId = Number(useParams().id)
  const user = useCurrentUser()
  const ticket = useTicket(ticketId)

  if (ticket.isPending) {
    return (
      <Center py="xl">
        <Loader />
      </Center>
    )
  }
  if (ticket.isError) {
    return (
      <Stack>
        <Alert color="red">{ticket.error.message}</Alert>
        <Anchor component={Link} to="/tickets">
          Voltar para a lista
        </Anchor>
      </Stack>
    )
  }

  const current = ticket.data
  const permissions = ticketPermissions(current, user)

  return (
    <Stack>
      <Anchor component={Link} to="/tickets" size="sm">
        ← Chamados
      </Anchor>
      <Title order={2}>
        #{current.id} · {current.title}
      </Title>

      <Grid gap="lg">
        <Grid.Col span={{ base: 12, md: 8 }} order={{ base: 2, md: 1 }}>
          <Stack>
            <Paper withBorder p="md" radius="md">
              <Text style={{ whiteSpace: 'pre-wrap' }}>{current.description}</Text>
            </Paper>
            <Tabs defaultValue="comments" keepMounted={false}>
              <Tabs.List>
                <Tabs.Tab value="comments">Comentários</Tabs.Tab>
                <Tabs.Tab value="attachments">Anexos</Tabs.Tab>
                <Tabs.Tab value="history">Histórico</Tabs.Tab>
              </Tabs.List>
              <Tabs.Panel value="comments" pt="md">
                <CommentsSection ticket={current} canComment={permissions.canComment} />
              </Tabs.Panel>
              <Tabs.Panel value="attachments" pt="md">
                <AttachmentsSection ticketId={current.id} canAttach={permissions.canComment} />
              </Tabs.Panel>
              <Tabs.Panel value="history" pt="md">
                <HistoryTimeline ticketId={current.id} />
              </Tabs.Panel>
            </Tabs>
          </Stack>
        </Grid.Col>
        <Grid.Col span={{ base: 12, md: 4 }} order={{ base: 1, md: 2 }}>
          <Stack>
            <TicketSummary ticket={current} />
            <TicketActions ticket={current} permissions={permissions} />
          </Stack>
        </Grid.Col>
      </Grid>
    </Stack>
  )
}
```

- [ ] **Step 6: Lint, tipos e navegador**

```bash
cd frontend && npm run lint && npx tsc -b && npm run dev
```

Conferir: como atendente, pedir informação num chamado; como o **solicitante dono**, abrir o chamado (aparece o aviso amarelo), comentar e ver o status voltar para "Em atendimento" **sem recarregar a página**. Anexar um PDF com acento no nome e baixá-lo; tentar um arquivo de mais de 5 MB e um `.exe` (aviso na hora, nenhuma requisição na aba Network do DevTools). Um comentário com `<b>oi</b>` aparece como texto. A aba Histórico mostra "abriu o chamado", "atribuiu a...", "mudou o status de ... para ...", "anexou ...". Num chamado fechado não há campo de comentário nem botão de anexo.

- [ ] **Step 7: Commit**

```bash
git add frontend/src
git commit -m "feat: add comments, attachments and history to the ticket detail"
```

---

### Task 8: Gestão de usuários (gestor)

**Objetivo:** o gestor lista os usuários, muda papel e ativa/desativa contas. Contas de demonstração e a própria conta ficam travadas, com a explicação; as regras que só o backend conhece (atendente com chamados em aberto) aparecem como a mensagem do 409.

**Conceitos:** rota restrita por papel (`<RequireAuth roles={['MANAGER']}>` aninhado); mutation com variáveis (`{ id, role }`); regra espelhada no cliente × regra que só o servidor pode decidir; acessibilidade (`aria-label` em controles sem rótulo visível, `Tooltip` explicando por que está desabilitado).

**Files:**
- Create: `frontend/src/users/userRules.ts`, `frontend/src/users/api.ts`, `frontend/src/users/UsersPage.tsx`
- Modify (substituir o arquivo inteiro): `frontend/src/router.tsx`
- Test: `frontend/src/users/userRules.test.ts`

**Interfaces:**
- Consumes: `api` (Task 2); `useCurrentUser`, `RequireAuth` (Task 3); `ROLE_LABELS`, `ROLES` (Task 1).
- Produces: `whyUserIsLocked(target: User, me: User): string | null`; `useUsers(page)` (1-based), `useUpdateUser()` (`mutate({ id, role? , active? })`); `<UsersPage />`.

- [ ] **Step 1: Escrever o teste (falhando)**

`frontend/src/users/userRules.test.ts`

```ts
import { describe, expect, it } from 'vitest'
import type { User } from '../api/types'
import { whyUserIsLocked } from './userRules'

const manager: User = { id: 1, name: 'Gil', email: 'g@x.com', role: 'MANAGER', active: true, demo: false }

function user(changes: Partial<User>): User {
  return { id: 2, name: 'Ana', email: 'a@x.com', role: 'AGENT', active: true, demo: false, ...changes }
}

describe('whyUserIsLocked (same rules as the backend, which answers 409)', () => {
  it('locks demo accounts, so visitors cannot break the demo', () => {
    expect(whyUserIsLocked(user({ demo: true }), manager)).toBe('Contas de demonstração não podem ser alteradas.')
  })

  it('locks the manager own account', () => {
    expect(whyUserIsLocked(manager, manager)).toBe('Você não pode alterar a sua própria conta.')
  })

  it('leaves other accounts editable', () => {
    expect(whyUserIsLocked(user({}), manager)).toBeNull()
  })
})
```

- [ ] **Step 2: Rodar e ver falhar**

```bash
cd frontend && npm test -- src/users; cd ..
```

Expected: FAIL — `Failed to resolve import "./userRules"`.

- [ ] **Step 3: Implementar regra, hooks, tela e rota**

`frontend/src/users/userRules.ts`

```ts
import type { User } from '../api/types'

/**
 * Why the manager cannot change this account, or null if they can. The rule that depends on
 * data the screen does not have (an agent with unfinished tickets) stays in the backend: it
 * answers 409 "reatribua os chamados antes" and the screen shows that message.
 */
export function whyUserIsLocked(target: User, me: User): string | null {
  if (target.demo) {
    return 'Contas de demonstração não podem ser alteradas.'
  }
  if (target.id === me.id) {
    return 'Você não pode alterar a sua própria conta.'
  }
  return null
}
```

`frontend/src/users/api.ts`

```ts
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { Page, Role, User } from '../api/types'

export function useUsers(page: number) {
  return useQuery({
    queryKey: ['users', 'list', page],
    queryFn: () => api.get<Page<User>>(`/users?page=${page - 1}&size=20`),
    placeholderData: keepPreviousData,
  })
}

export function useUpdateUser() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ...changes }: { id: number; role?: Role; active?: boolean }) =>
      api.patch<User>(`/users/${id}`, changes),
    // Refresh the table and the assignee options (a promoted or deactivated user changes them).
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['users'] }),
  })
}
```

`frontend/src/users/UsersPage.tsx`

```tsx
import { Alert, Badge, Center, Group, Loader, Pagination, Select, Stack, Switch, Table, Text, Title, Tooltip } from '@mantine/core'
import { useState } from 'react'
import type { Role } from '../api/types'
import { useCurrentUser } from '../auth/authContext'
import { ROLE_LABELS, ROLES } from '../shared/labels'
import { useUpdateUser, useUsers } from './api'
import { whyUserIsLocked } from './userRules'

const ROLE_OPTIONS = ROLES.map((role) => ({ value: role, label: ROLE_LABELS[role] }))

export function UsersPage() {
  const me = useCurrentUser()
  const [page, setPage] = useState(1)
  const users = useUsers(page)
  const updateUser = useUpdateUser()

  return (
    <Stack>
      <Title order={2}>Usuários</Title>
      <Text size="sm" c="dimmed">
        Contas de demonstração não podem ser alteradas. Para testar, cadastre um usuário novo e promova-o aqui.
      </Text>

      {users.isPending && (
        <Center py="xl">
          <Loader />
        </Center>
      )}
      {users.isError && <Alert color="red">{users.error.message}</Alert>}
      {users.data && (
        <>
          <Table.ScrollContainer minWidth={720}>
            <Table striped verticalSpacing="sm">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Nome</Table.Th>
                  <Table.Th>E-mail</Table.Th>
                  <Table.Th>Papel</Table.Th>
                  <Table.Th>Ativo</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {users.data.content.map((user) => {
                  const lockReason = whyUserIsLocked(user, me)
                  const busy = updateUser.isPending && updateUser.variables?.id === user.id
                  return (
                    <Table.Tr key={user.id}>
                      <Table.Td>
                        <Group gap="xs">
                          {user.name}
                          {user.demo && (
                            <Badge size="xs" variant="light" color="gray">
                              demo
                            </Badge>
                          )}
                        </Group>
                      </Table.Td>
                      <Table.Td>{user.email}</Table.Td>
                      <Table.Td>
                        <Tooltip label={lockReason} disabled={lockReason === null}>
                          <div>
                            <Select<Role>
                              aria-label={`Papel de ${user.name}`}
                              data={ROLE_OPTIONS}
                              value={user.role}
                              onChange={(role) => role && updateUser.mutate({ id: user.id, role })}
                              allowDeselect={false}
                              disabled={lockReason !== null || busy}
                              w={160}
                            />
                          </div>
                        </Tooltip>
                      </Table.Td>
                      <Table.Td>
                        <Tooltip label={lockReason} disabled={lockReason === null}>
                          <div>
                            <Switch
                              aria-label={`${user.name} ativo`}
                              checked={user.active}
                              onChange={(event) =>
                                updateUser.mutate({ id: user.id, active: event.currentTarget.checked })
                              }
                              disabled={lockReason !== null || busy}
                            />
                          </div>
                        </Tooltip>
                      </Table.Td>
                    </Table.Tr>
                  )
                })}
              </Table.Tbody>
            </Table>
          </Table.ScrollContainer>
          {users.data.totalPages > 1 && <Pagination total={users.data.totalPages} value={page} onChange={setPage} />}
        </>
      )}
    </Stack>
  )
}
```

`frontend/src/router.tsx`

```tsx
import { Navigate, type RouteObject } from 'react-router'
import { GuestOnly, RequireAuth } from './auth/guards'
import { LoginPage } from './auth/LoginPage'
import { RegisterPage } from './auth/RegisterPage'
import { AppLayout } from './layout/AppLayout'
import { NewTicketPage } from './tickets/NewTicketPage'
import { TicketDetailPage } from './tickets/TicketDetailPage'
import { TicketListPage } from './tickets/TicketListPage'
import { UsersPage } from './users/UsersPage'

export const routes: RouteObject[] = [
  {
    element: <GuestOnly />,
    children: [
      { path: '/login', element: <LoginPage /> },
      { path: '/register', element: <RegisterPage /> },
    ],
  },
  {
    element: <RequireAuth />,
    children: [
      {
        element: <AppLayout />,
        children: [
          { path: '/tickets', element: <TicketListPage /> },
          { path: '/tickets/new', element: <NewTicketPage /> },
          { path: '/tickets/:id', element: <TicketDetailPage /> },
          {
            element: <RequireAuth roles={['MANAGER']} />,
            children: [{ path: '/users', element: <UsersPage /> }],
          },
        ],
      },
    ],
  },
  { path: '*', element: <Navigate to="/tickets" replace /> },
]
```

- [ ] **Step 4: Testes, lint, tipos e navegador**

```bash
cd frontend && npm test && npm run lint && npx tsc -b && npm run dev
```

Expected: `Tests  53 passed (53)`. No navegador: como gestor, "Usuários" lista as contas; as de demonstração aparecem com o selo "demo" e os controles desabilitados (o tooltip explica). Cadastrar uma conta nova (em outra janela anônima), promovê-la a Atendente e desativá-la. Como atendente, abrir `/users` volta para a lista de chamados.

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git commit -m "feat: add user management page for managers"
```

---

### Task 9: Painel do gestor

**Objetivo:** o painel com indicadores (vencidos agora, resolvidos, % de SLA cumprido, tempo médio), gráficos (por status, abertos por categoria, abertos × resolvidos por dia) e a tabela por atendente, para os últimos 7, 30 ou 90 dias. A página e a biblioteca de gráficos só são baixadas quando o gestor abre o painel.

**Conceitos:** code splitting com rota `lazy` (import dinâmico → arquivo JavaScript separado); tamanho de bundle e por que importa; data local × UTC ao montar `from`/`to`; valores ausentes (`null` quando nada foi resolvido) viram "—", nunca `NaN`; gráficos declarativos com Mantine Charts.

**Files:**
- Create: `frontend/src/dashboard/period.ts`, `frontend/src/dashboard/DashboardPage.tsx`
- Modify (substituir o arquivo inteiro): `frontend/src/router.tsx`, `frontend/vite.config.ts`
- Test: `frontend/src/dashboard/period.test.ts`

**Interfaces:**
- Consumes: `api` (Task 2); `Dashboard` (Task 1); `formatPercent`, `formatHours`, `STATUS_LABELS`, `STATUSES` (Task 1).
- Produces: `toIsoDate(date: Date): string`, `lastDays(days: number, today?: Date): { from: string; to: string }`; `<DashboardPage />` (exportado com nome, carregado por `lazy`).

- [ ] **Step 1: Escrever o teste do período (falhando)**

`frontend/src/dashboard/period.test.ts`

```ts
import { describe, expect, it } from 'vitest'
import { lastDays, toIsoDate } from './period'

describe('dashboard period (tests run in America/Sao_Paulo)', () => {
  it('uses the local date, not the UTC one: at 22:30 in São Paulo it is still the same day', () => {
    // 22:30 on Sep 29 in São Paulo is 01:30 on Sep 30 in UTC; toISOString() would say "2026-09-30".
    const lateEvening = new Date('2026-09-30T01:30:00Z')

    expect(toIsoDate(lateEvening)).toBe('2026-09-29')
  })

  it('counts today as one of the days, like the backend (both ends inclusive)', () => {
    const today = new Date(2026, 8, 29, 10, 0) // local time; months start at 0

    expect(lastDays(1, today)).toEqual({ from: '2026-09-29', to: '2026-09-29' })
    expect(lastDays(30, today)).toEqual({ from: '2026-08-31', to: '2026-09-29' })
    expect(lastDays(90, today)).toEqual({ from: '2026-07-02', to: '2026-09-29' })
  })
})
```

- [ ] **Step 2: Rodar e ver falhar**

```bash
cd frontend && npm test -- src/dashboard; cd ..
```

Expected: FAIL — `Failed to resolve import "./period"`.

- [ ] **Step 3: Implementar período, tela, rota sob demanda e limite do aviso de bundle**

`frontend/src/dashboard/period.ts`

```ts
/** "2026-09-29" in the browser's time zone. (toISOString() would use UTC and jump a day at night.) */
export function toIsoDate(date: Date): string {
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${date.getFullYear()}-${month}-${day}`
}

/** The last `days` days, today included: the from/to the API expects (both inclusive, at most 90 days). */
export function lastDays(days: number, today: Date = new Date()): { from: string; to: string } {
  const start = new Date(today.getFullYear(), today.getMonth(), today.getDate() - (days - 1))
  return { from: toIsoDate(start), to: toIsoDate(today) }
}
```

`frontend/src/dashboard/DashboardPage.tsx`

```tsx
import { BarChart, LineChart } from '@mantine/charts'
import { Alert, Center, Group, Loader, Paper, SegmentedControl, SimpleGrid, Stack, Table, Text, Title } from '@mantine/core'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { type ReactNode, useState } from 'react'
import { api } from '../api/client'
import type { Dashboard } from '../api/types'
import { formatHours, formatPercent } from '../shared/format'
import { STATUS_LABELS, STATUSES } from '../shared/labels'
import { lastDays } from './period'

const PERIODS = [
  { value: '7', label: '7 dias' },
  { value: '30', label: '30 dias' },
  { value: '90', label: '90 dias' },
]

function Stat({ label, value, color }: { label: string; value: string | number; color?: string }) {
  return (
    <Paper withBorder p="md" radius="md">
      <Text size="xs" c="dimmed" tt="uppercase" fw={600}>
        {label}
      </Text>
      <Text size="xl" fw={700} c={color}>
        {value}
      </Text>
    </Paper>
  )
}

function ChartCard({ title, children }: { title: string; children: ReactNode }) {
  return (
    <Paper withBorder p="md" radius="md">
      <Text fw={600} mb="md">
        {title}
      </Text>
      {children}
    </Paper>
  )
}

/** "2026-09-29" -> "29/09" for the chart axis. */
function shortDate(isoDate: string): string {
  const [, month, day] = isoDate.split('-')
  return `${day}/${month}`
}

export function DashboardPage() {
  const [days, setDays] = useState('30')
  const { from, to } = lastDays(Number(days))
  const dashboard = useQuery({
    queryKey: ['dashboard', from, to],
    queryFn: () => api.get<Dashboard>(`/dashboard?from=${from}&to=${to}`),
    placeholderData: keepPreviousData,
  })

  return (
    <Stack>
      <Group justify="space-between">
        <Title order={2}>Painel</Title>
        <SegmentedControl data={PERIODS} value={days} onChange={setDays} />
      </Group>

      {dashboard.isPending && (
        <Center py="xl">
          <Loader />
        </Center>
      )}
      {dashboard.isError && <Alert color="red">{dashboard.error.message}</Alert>}
      {dashboard.data && <DashboardContent data={dashboard.data} />}
    </Stack>
  )
}

function DashboardContent({ data }: { data: Dashboard }) {
  const byStatus = STATUSES.map((status) => ({ status: STATUS_LABELS[status], total: data.ticketsByStatus[status] }))
  const daily = data.daily.map((day) => ({ ...day, date: shortDate(day.date) }))

  return (
    <Stack>
      <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }}>
        <Stat label="Vencidos agora" value={data.overdueNow} color={data.overdueNow > 0 ? 'red' : undefined} />
        <Stat label="Resolvidos no período" value={data.resolvedInPeriod} />
        <Stat label="SLA cumprido" value={formatPercent(data.slaMetPercentage)} />
        <Stat label="Tempo médio de resolução" value={formatHours(data.averageResolutionHours)} />
      </SimpleGrid>

      <SimpleGrid cols={{ base: 1, md: 2 }}>
        <ChartCard title="Chamados por status (agora)">
          <BarChart h={260} data={byStatus} dataKey="status" series={[{ name: 'total', label: 'Chamados', color: 'indigo.6' }]} />
        </ChartCard>
        <ChartCard title="Abertos no período por categoria">
          <BarChart
            h={260}
            data={data.openedByCategory}
            dataKey="category"
            series={[{ name: 'count', label: 'Abertos', color: 'blue.6' }]}
          />
        </ChartCard>
      </SimpleGrid>

      <ChartCard title="Abertos × resolvidos por dia">
        <LineChart
          h={280}
          data={daily}
          dataKey="date"
          withLegend
          curveType="monotone"
          series={[
            { name: 'opened', label: 'Abertos', color: 'blue.6' },
            { name: 'resolved', label: 'Resolvidos', color: 'teal.6' },
          ]}
        />
      </ChartCard>

      <ChartCard title="Atendentes">
        <Table.ScrollContainer minWidth={420}>
          <Table>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Atendente</Table.Th>
                <Table.Th>Chamados em andamento</Table.Th>
                <Table.Th>Resolvidos no período</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {data.agents.map((agent) => (
                <Table.Tr key={agent.id}>
                  <Table.Td>{agent.name}</Table.Td>
                  <Table.Td>{agent.activeAssigned}</Table.Td>
                  <Table.Td>{agent.resolvedInPeriod}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      </ChartCard>
    </Stack>
  )
}
```

`frontend/src/router.tsx`

```tsx
import { Navigate, type RouteObject } from 'react-router'
import { GuestOnly, RequireAuth } from './auth/guards'
import { LoginPage } from './auth/LoginPage'
import { RegisterPage } from './auth/RegisterPage'
import { AppLayout } from './layout/AppLayout'
import { NewTicketPage } from './tickets/NewTicketPage'
import { TicketDetailPage } from './tickets/TicketDetailPage'
import { TicketListPage } from './tickets/TicketListPage'
import { UsersPage } from './users/UsersPage'

export const routes: RouteObject[] = [
  {
    element: <GuestOnly />,
    children: [
      { path: '/login', element: <LoginPage /> },
      { path: '/register', element: <RegisterPage /> },
    ],
  },
  {
    element: <RequireAuth />,
    children: [
      {
        element: <AppLayout />,
        children: [
          { path: '/tickets', element: <TicketListPage /> },
          { path: '/tickets/new', element: <NewTicketPage /> },
          { path: '/tickets/:id', element: <TicketDetailPage /> },
          {
            element: <RequireAuth roles={['MANAGER']} />,
            children: [
              { path: '/users', element: <UsersPage /> },
              {
                path: '/dashboard',
                // Code splitting: the charts library is only downloaded when a manager opens the dashboard.
                lazy: () => import('./dashboard/DashboardPage').then((module) => ({ Component: module.DashboardPage })),
              },
            ],
          },
        ],
      },
    ],
  },
  { path: '*', element: <Navigate to="/tickets" replace /> },
]
```

`frontend/vite.config.ts`

```ts
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  build: {
    // Mantine + React are ~700 kB (~220 kB gzip) in the main chunk; the charts are split into
    // their own chunk (lazy /dashboard route). The default warning starts at 500 kB.
    chunkSizeWarningLimit: 800,
  },
  server: {
    port: 5173,
    strictPort: true,
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    // Fixed time zone, so date tests give the same result on any machine (and match the CI).
    env: { TZ: 'America/Sao_Paulo' },
  },
})
```

- [ ] **Step 4: Testes, lint e build**

```bash
cd frontend && npm test && npm run lint && npm run build; cd ..
```

Expected: `Tests  55 passed (55)`, e o build lista um arquivo `DashboardPage-*.js` separado do `index-*.js`, sem aviso de tamanho.

- [ ] **Step 5: Conferir no navegador**

Como gestor, abrir "Painel": os quatro indicadores, os três gráficos e a tabela de atendentes aparecem; trocar entre 7, 30 e 90 dias atualiza os números sem piscar. Na aba Network, o `DashboardPage-*.js` só é baixado ao abrir o painel.

- [ ] **Step 6: Commit**

```bash
git add frontend/src frontend/vite.config.ts
git commit -m "feat: add manager dashboard with charts, loaded on demand"
```

---

### Task 10: Imagem Docker, compose completo, README, verificação final e revisão

**Objetivo:** imagem de produção do frontend (build com Node, servida por nginx), `docker compose up --build` subindo banco + API + frontend, README com instruções do frontend, a suíte inteira verde e a revisão final da branch.

**Conceitos:** multi-stage build (a imagem final só tem nginx e os arquivos estáticos); `try_files` para SPA (rotas como `/tickets/42` não existem em disco); cache "para sempre" em arquivos com hash no nome; `ARG` de build × variável de ambiente em tempo de execução (o Vite embute a URL da API no build, então trocar de ambiente exige novo build); `depends_on` no compose.

**Files:**
- Create: `frontend/Dockerfile`, `frontend/nginx.conf`, `frontend/.dockerignore`
- Modify (substituir o arquivo inteiro): `docker-compose.yml`, `README.md`

- [ ] **Step 1: Imagem do frontend**

`frontend/Dockerfile`

```dockerfile
# Stage 1: build the static files with Node.
FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# Vite embeds VITE_* variables in the bundle at build time, so the API URL is a build argument.
ARG VITE_API_URL=http://localhost:8080
ENV VITE_API_URL=$VITE_API_URL
RUN npm run build

# Stage 2: serve them with nginx (no Node in the final image).
FROM nginx:1.30-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
```

`frontend/nginx.conf`

```nginx
server {
    listen 80;
    root /usr/share/nginx/html;
    index index.html;

    # Single-page app: routes such as /tickets/42 have no file on disk,
    # so every unknown path returns index.html and React Router takes over.
    location / {
        try_files $uri $uri/ /index.html;
    }

    # Built files have a content hash in their name, so they can be cached forever.
    location /assets/ {
        add_header Cache-Control "public, max-age=31536000, immutable";
    }
}
```

`frontend/.dockerignore`

```text
node_modules/
dist/
.env
.env.*
!.env.example
```

- [ ] **Step 2: Compose com o frontend**

`docker-compose.yml`

```yaml
services:
  db:
    image: postgres:17-alpine
    environment:
      POSTGRES_DB: ticketflow
      POSTGRES_USER: ticketflow
      POSTGRES_PASSWORD: ticketflow
    ports:
      - "5432:5432"
    volumes:
      - db-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ticketflow -d ticketflow"]
      interval: 5s
      timeout: 3s
      retries: 10

  backend:
    build: ./backend
    environment:
      DB_URL: jdbc:postgresql://db:5432/ticketflow
      DB_USER: ticketflow
      DB_PASSWORD: ticketflow
      JWT_SECRET: ${JWT_SECRET:-local-compose-secret-change-me-0123456789}
      CORS_ALLOWED_ORIGINS: http://localhost:5173
      SPRING_PROFILES_ACTIVE: demo
    ports:
      - "8080:8080"
    depends_on:
      db:
        condition: service_healthy

  frontend:
    build:
      context: ./frontend
      args:
        VITE_API_URL: http://localhost:8080
    ports:
      - "5173:80"
    depends_on:
      - backend

volumes:
  db-data:
```

- [ ] **Step 3: Subir tudo e conferir**

Parar o `npm run dev`, se estiver rodando (a porta 5173 passa a ser do container).

```bash
docker compose up -d --build
curl -s -o /dev/null -w "%{http_code}\n" localhost:5173/tickets/42
```

Expected: `200` (o nginx devolve o `index.html` para qualquer rota). Abrir `http://localhost:5173` e repetir o roteiro abaixo.

- [ ] **Step 4: README**

`README.md`

````markdown
# ticket-flow

[![CI](https://github.com/lmaciel0/ticket-flow/actions/workflows/ci.yml/badge.svg)](https://github.com/lmaciel0/ticket-flow/actions/workflows/ci.yml)

Sistema de gestão de chamados com Spring Boot, PostgreSQL e React: perfis de acesso, SLA, anexos, histórico e dashboard.

## Rodando tudo com Docker

Pré-requisito: Docker Desktop em execução.

```bash
docker compose up --build
```

Sobe o banco, a API com os dados de demonstração e o frontend. Abra `http://localhost:5173` e entre com um dos botões de demonstração.

Contas de demonstração (senha `demo1234`):

| Perfil | E-mail |
|---|---|
| Solicitante | `solicitante@ticketflow.demo` |
| Atendente | `atendente@ticketflow.demo` |
| Gestor | `gestor@ticketflow.demo` |

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

Para rodar só banco e API em containers, já com os dados de demonstração: `docker compose up --build db backend`.

## Rodando o frontend localmente

Pré-requisitos: Node.js 24 ou mais recente e a API rodando (veja acima).

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

## Documentação

- Especificação do MVP: [docs/specs/2026-09-28-ticket-flow-mvp-design.md](docs/specs/2026-09-28-ticket-flow-mvp-design.md)
- Planos de implementação: [docs/plans/](docs/plans/)

O link da demo publicada e a seção de decisões técnicas e trade-offs entram na etapa de deploy.
````

- [ ] **Step 5: Verificação final completa**

```bash
cd frontend && npm ci && npm run lint && npm test && npm run build; cd ..
cd backend && ./mvnw verify; cd ..
```

Expected: lint sem avisos, `Tests  55 passed (55)`, build sem avisos; backend com `BUILD SUCCESS`.

Roteiro no navegador (compose completo, `http://localhost:5173`):

1. Solicitante abre um chamado com prioridade Alta.
2. Atendente o encontra na lista (filtro "Em risco" ou busca pelo título), assume e pede informação.
3. Solicitante comenta (o chamado volta para "Em atendimento") e anexa um PDF.
4. Atendente baixa o anexo e resolve.
5. Solicitante confirma e fecha. O histórico mostra todos os passos.
6. Gestor vê o chamado no painel (resolvidos no período) e na lista com "Fechado".

- [ ] **Step 6: Commit**

```bash
git add frontend/Dockerfile frontend/nginx.conf frontend/.dockerignore docker-compose.yml README.md
git commit -m "chore: add frontend Docker image, compose service and README"
```

- [ ] **Step 7: Revisão final da branch (única revisão do plano)**

Uma revisão de código de `feat/frontend` inteira contra a base (skill `superpowers:requesting-code-review`), olhando em especial os itens do **Review Focus**. Corrigir o que for confirmado, com testes, em commits `fix:` separados.

- [ ] **Step 8: Push e Pull Request — só com autorização do usuário**

Perguntar antes. Se autorizado: `git push -u origin feat/frontend`, abrir o PR (base `main` se o PR #1 já foi mergeado; senão `feat/backend`) e conferir se os dois jobs do CI ficam verdes.

---

## Cobertura da spec neste plano

| Seção da spec | Onde |
|---|---|
| 1.1 Visitante entra por botão de demo e percorre o fluxo completo | Tasks 3 a 7; roteiro da Task 10 |
| 1.2 SLA como indicador na lista e no detalhe | Tasks 4 e 6 (`SlaBadge`) |
| 1.4 CI roda o build do frontend a cada push | Task 1 (job `frontend`) |
| 2. Vite, React, TypeScript, Mantine (componentes e gráficos), React Router, TanStack Query; UI em português | Task 1 e Global Constraints |
| 5. Ações por status e perfil, rótulos dos status | Task 6 (`permissions.ts`, `labels.ts`) |
| 6. Indicadores de SLA (no prazo, em risco, vencido, pausado, cumprido, violado) | Tasks 1 e 4 |
| 7. Perfis; 404 para chamado alheio; cadastro sempre REQUESTER; contas demo e própria conta travadas | Tasks 3, 6 e 8 |
| 8. Filtros (status múltiplo, prioridade, categoria, SLA, busca, "mine"), paginação, ordenação por prazo/criação; padrão: não finalizados | Task 4 (o filtro `assigneeId` da API fica sem controle na tela: "Atribuídos a mim" cobre o uso do atendente, e um seletor de responsável para o gestor é evolução) |
| 8. Concorrência por `version` e 409 | Task 6 |
| 8. Anexos: 5 MB, tipos aceitos, 413 | Tasks 2 e 7 |
| 8. Erros ProblemDetail (400 com campos, 401, 403, 404, 409, 413) | Task 2 (e telas das Tasks 3, 5, 6, 8) |
| 8. Painel com período (padrão 30 dias, máximo 90) | Task 9 |
| 9. Telas: login (três botões e formulário), cadastro, lista, novo chamado, detalhe (ações, comentários, anexos, histórico em timeline), usuários, painel | Tasks 3 a 9 |
| 9. `VITE_API_URL`; token anexado; 401 leva ao login; 409 mostra a mensagem e recarrega; rotas por perfil; aviso do reset diário | Tasks 1, 2, 3, 6 |
| 9. Testes mínimos com Vitest (utilitários e um ou dois componentes) | Todas as tasks (55 testes) |
| 10. `frontend/` com Dockerfile + nginx; compose com db, backend e frontend | Task 10 |
| 10. Deploy (Render Static Site, CORS de produção) e 11. README de trade-offs | **Plano 3** |

## Depois deste plano

1. Abrir o PR `feat/frontend` e ver os dois jobs do CI verdes (com autorização do Roberto).
2. Escrever o **Plano 3 — Deploy e acabamento** (Render, Neon, `VITE_API_URL` e CORS de produção, README com link e trade-offs).
