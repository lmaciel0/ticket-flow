# ticket-flow — Plano 3: Deploy e acabamento

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** publicar o ticket-flow com custo zero (API no Render, banco no Neon, frontend como site estático no Render), com o primeiro acesso em cerca de um minuto, e fechar o README com o link da demo, as decisões técnicas e os trade-offs.

**Architecture:** a infraestrutura fica descrita como código num Render Blueprint (`render.yaml`), validado contra o JSON Schema oficial do Render. A API continua sendo a imagem Docker do Plano 1, agora com CDS e flags de JVM para o plano grátis (512 MB, 0,1 CPU), e escuta na porta que o Render define (`PORT`). O frontend passa a tolerar URLs coladas com barra final, explica a espera enquanto o servidor acorda e recusa o build no Render se faltar a URL da API. Segredos e URLs nunca entram no repositório: são digitados no painel do Render.

**Tech Stack:** Render (Blueprint, web service Docker e static site, plano Free), Neon (PostgreSQL 17, plano Free), Java 21 com CDS, Spring Boot, Vite, GitHub Actions.

**Spec:** [docs/specs/2026-09-28-ticket-flow-mvp-design.md](../specs/2026-09-28-ticket-flow-mvp-design.md) (seções 1, 10 e 11). Planos anteriores: [backend](2026-09-29-ticket-flow-backend.md) e [frontend](2026-09-29-ticket-flow-frontend.md).

## Como este plano se encaixa

1. **Plano 1 — API backend:** na `main` (PR #1).
2. **Plano 2 — Frontend:** na `main` (PR #2).
3. **Plano 3 — Deploy e acabamento** (este documento).

As Tasks 1 a 4 foram **escritas e testadas antes, numa cópia descartável do repositório**:

- **Backend:** 101 testes, 0 falhas.
- **Frontend:** 62 testes, estáveis em 9 execuções seguidas, inclusive rodando junto com a suíte do backend. Lint, tipos e build limpos.
- **Blueprint:** válido pelo schema oficial. O validador também rejeita um arquivo com valores errados.
- **Imagem Docker:** rodou com os limites do plano grátis (`--memory=512m --cpus=0.1`) e `PORT=10000`.

A Task 5 (deploy) depende de contas que só o Roberto pode criar, então os passos dela descrevem o que ele faz no navegador e como o Claude confere o resultado.

## Condições dos planos grátis (reconfirmadas em 2026-09-29)

A spec pede para reconfirmar antes do deploy:

- **Render Free (web service):** dorme depois de 15 minutos sem tráfego e leva cerca de 1 minuto para voltar. São 750 horas por mês por workspace. Sem disco persistente, sem SSH e com uma instância só. O Render define `PORT=10000` e `RENDER=true`.
- **Render (site estático):** grátis e não dorme.
- **Render Postgres Free:** expira 30 dias depois de criado. Por isso o banco fica no Neon.
- **Neon Free:** 0,5 GB por projeto (acima disso as escritas são bloqueadas) e 100 CU-horas por mês. Suspende depois de 5 minutos sem uso, sem opção de desligar. Não pede cartão de crédito.
- **Regiões:** o Render não tem região na América do Sul. API e banco ficam os dois em **Virginia (AWS us-east-1)**, perto um do outro: cada tela faz várias consultas, e ficar perto do banco pesa mais do que ficar perto do usuário.

## Medições que motivaram a Task 1

A imagem do Plano 1 rodou com os limites do plano grátis (`docker run --memory=512m --memory-swap=512m --cpus=0.1`) e o banco local:

| Imagem | Subida (log "Started ... in") | Memória em uso |
|---|---|---|
| Plano 1 (`-XX:MaxRAMPercentage=75`) | 192 s | 329 MB |
| + `-XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xss512k` | 117 s | 221 MB |
| + inicialização preguiçosa do Spring | 109 s | 213 MB (descartada: pouco ganho e primeiras telas mais lentas) |
| **+ CDS (Class Data Sharing), a versão desta task** | **55–58 s** | **~194 MB (214 MB depois de uso)** |

Sem a otimização, cada visitante depois de 15 minutos de inatividade esperaria mais de 3 minutos.

## Como executar (ambiente do Roberto)

- Windows 11 com **Git Bash**, a partir da raiz do repositório. Docker Desktop aberto (testes do backend e imagem).
- **Execução nativa**: o Claude implementa as tarefas nesta sessão, sem subagentes por tarefa, com **uma revisão só no fim** (Task 4, antes do merge).
- A cada tarefa: explicar os conceitos listados (pontos de entrevista), rodar os testes, mostrar o resultado e fazer **um commit pequeno**. Push e PR só com autorização.
- **Na Task 5**, o Roberto cria as contas e digita os segredos no painel. O Claude nunca cria contas nem digita senhas. Ele só confere com `curl` e no navegador.

## Global Constraints

- **Custo zero:** Render Free (API e site estático) e Neon Free. Nada que peça cartão de crédito.
- Variáveis da API: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `JWT_SECRET`, `CORS_ALLOWED_ORIGINS`, `SPRING_PROFILES_ACTIVE` (spec §10), mais `PORT`, que o Render define. Variável do frontend: `VITE_API_URL`.
- **Segredos nunca são versionados.** O `.env.example` versionado só tem valores de desenvolvimento. `DB_*`, `CORS_ALLOWED_ORIGINS` e `VITE_API_URL` usam `sync: false` no Blueprint, e o `JWT_SECRET` é gerado pelo Render (`generateValue`).
- **CORS liberado só para o domínio do frontend** (spec §10).
- A API continua rodando como usuário sem root, com a JVM limitada a 75% da memória (spec §10).
- O profile `demo` fica ligado em produção (dados de demonstração e reset diário, spec §10).
- Código, comentários e mensagens de commit em inglês; tudo o que aparece na tela e o README em português.
- TDD onde há código (Tasks 1 e 2). Configuração de infraestrutura (Tasks 3 e 5) é verificada por comando: validação de schema, `curl` e navegador.
- `ticket-flow-contexto.md` nunca é versionado.

## Review Focus

Situações que o deploy implica e que mais provavelmente afetariam quem usa a demo. Cada uma tem teste ou verificação na tarefa dona:

1. **Primeiro acesso depois de 15 minutos parado:** o servidor leva cerca de um minuto para acordar. Quem volta com sessão salva deve ver uma explicação, não um spinner mudo nem uma tela em branco. Testes: `guards.test.tsx` (Task 2); medição da subida (Task 1).
2. **URL colada com barra no final** no painel do Render (`https://...onrender.com/`), tanto em `VITE_API_URL` quanto em `CORS_ALLOWED_ORIGINS`, e espaços depois da vírgula na lista. Tudo deve funcionar igual. Testes: `client.test.ts` › `ignores a trailing slash in VITE_API_URL` (Task 2) e `CorsApiTest` (Task 1).
3. **`VITE_API_URL` esquecida no Render:** o build deve falhar com uma mensagem clara, e não publicar um site que chama `localhost`. Verificação: Task 2, Step 6.
4. **Porta do Render:** a API precisa escutar em `PORT` (10000), senão o deploy falha. Teste: `ServerPortConfigTest` (Task 1); verificação com a imagem rodando em `PORT=10000` (Task 1, Step 7).
5. **Link direto ou F5 numa rota do site** (`/tickets/42`): o site estático deve devolver o app, não 404. Banco suspenso depois de 5 minutos: a primeira consulta deve funcionar, só um pouco mais lenta. Verificação: Task 5, Steps 5 e 6.

---

### Task 1: API pronta para o Render (porta, CORS e imagem para 0,1 CPU)

**Objetivo:** a API escuta na porta definida pelo Render; o CORS de produção fica protegido por teste contra a configuração digitada à mão; e a imagem Docker sobe em cerca de um minuto no plano grátis.

**Conceitos (para explicar em entrevista):**
- 12-factor: a configuração vem do ambiente (`${PORT:8080}`), e o mesmo artefato roda em qualquer lugar.
- CORS: por que o navegador faz o preflight `OPTIONS` e por que a lista de origens deve ser exata.
- Como a JVM sobe:
  - carregar e verificar classes custa CPU;
  - o CDS guarda esse trabalho num arquivo gerado no build (execução de treino com `spring.context.exit=onRefresh`);
  - C1 × C2: `TieredStopAtLevel=1` compila rápido, mas gera código menos otimizado;
  - Serial GC em máquina com uma fração de CPU.
- Medir antes de otimizar: a tabela acima.
- Teste de regressão × TDD: os testes de CORS passam de primeira porque o Spring já trata barra e espaços. Eles protegem esse comportamento, não guiam código novo.

**Files:**
- Modify (substituir o arquivo inteiro): `backend/src/main/resources/application.yml`, `backend/Dockerfile`
- Test: `backend/src/test/java/com/ticketflow/common/ServerPortConfigTest.java`, `backend/src/test/java/com/ticketflow/common/CorsApiTest.java`

**Interfaces:**
- Produces: `server.port = ${PORT:8080}`; imagem `backend/Dockerfile` que respeita `PORT`, `DB_URL`, `DB_USER`, `DB_PASSWORD`, `JWT_SECRET`, `CORS_ALLOWED_ORIGINS` e `SPRING_PROFILES_ACTIVE`.

- [ ] **Step 1: Criar a branch de trabalho**

```bash
git switch main
git pull
git switch -c feat/deploy
```

- [ ] **Step 2: Escrever o teste da porta (falhando)**

`backend/src/test/java/com/ticketflow/common/ServerPortConfigTest.java`

```java
package com.ticketflow.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Render tells each service which port to listen on through the PORT environment variable
 * (10000 by default). Locally, and in docker compose, the API keeps port 8080.
 */
class ServerPortConfigTest {

    /** Loads application.yml exactly like the real application does. */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void listensOnPortFromTheEnvironment() {
        runner.withPropertyValues("PORT=10000")
                .run(context -> assertThat(context.getEnvironment().getProperty("server.port")).isEqualTo("10000"));
    }

    @Test
    void keepsPort8080WhenPortIsNotSet() {
        runner.run(context -> assertThat(context.getEnvironment().getProperty("server.port")).isEqualTo("8080"));
    }
}
```

- [ ] **Step 3: Rodar e ver falhar**

```bash
cd backend && ./mvnw -B -q test -Dtest=ServerPortConfigTest; cd ..
```

Expected: FAIL. Os dois testes falham com `expected: "10000" but was: null` e `expected: "8080" but was: null`, porque `server.port` ainda não está configurado.

- [ ] **Step 4: Configurar a porta**

O arquivo inteiro. A única mudança é a linha `port` em `server`:

`backend/src/main/resources/application.yml`

```yaml
spring:
  application:
    name: ticket-flow
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/ticketflow}
    username: ${DB_USER:ticketflow}
    password: ${DB_PASSWORD:ticketflow}
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
  servlet:
    multipart:
      max-file-size: 5MB
      max-request-size: 6MB

server:
  # Render sets PORT (10000 by default); locally and in docker compose it stays 8080.
  port: ${PORT:8080}
  tomcat:
    max-swallow-size: 10MB

management:
  endpoints:
    web:
      exposure:
        include: health

springdoc:
  swagger-ui:
    path: /swagger-ui

app:
  zone: America/Sao_Paulo
  cors:
    allowed-origins: ${CORS_ALLOWED_ORIGINS:http://localhost:5173}
  jwt:
    # Local development only. Production must set JWT_SECRET (at least 32 bytes).
    secret: ${JWT_SECRET:local-dev-secret-change-me-0123456789abcdef}
    ttl: 8h
  sla:
    deadlines:
      CRITICAL: 4h
      HIGH: 8h
      MEDIUM: 24h
      LOW: 72h
```

- [ ] **Step 5: Teste de regressão do CORS de produção**

`backend/src/test/java/com/ticketflow/common/CorsApiTest.java`

```java
package com.ticketflow.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The browser only lets the frontend call the API if the API allows the frontend's origin.
 * The value comes from CORS_ALLOWED_ORIGINS, typed by hand in the Render dashboard, so it must
 * survive the usual copy-and-paste noise: a trailing slash and spaces after the comma.
 */
@TestPropertySource(properties = "app.cors.allowed-origins=https://ticket-flow-web.onrender.com/ , http://localhost:5173")
class CorsApiTest extends IntegrationTest {

    private static final String FRONTEND = "https://ticket-flow-web.onrender.com";

    @Test
    void allowsTheConfiguredFrontendEvenWithATrailingSlashInTheSetting() throws Exception {
        mvc.perform(options("/api/tickets")
                        .header("Origin", FRONTEND)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", FRONTEND));
    }

    @Test
    void allowsEveryOriginInTheListEvenWithSpacesAfterTheComma() throws Exception {
        mvc.perform(options("/api/auth/login")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    @Test
    void refusesOtherOrigins() throws Exception {
        mvc.perform(options("/api/tickets")
                        .header("Origin", "https://evil.example.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
```

```bash
cd backend && ./mvnw -B -q test -Dtest='ServerPortConfigTest,CorsApiTest'; cd ..
```

Expected: PASS (5 testes). Os 3 de CORS passam de primeira: o Spring já remove a barra final das origens e os espaços da lista separada por vírgulas. O teste serve para travar esse comportamento, porque a configuração será digitada à mão no Render. Para ver o teste falhar, troque temporariamente `FRONTEND` por outra URL e rode de novo; depois desfaça.

- [ ] **Step 6: Imagem Docker para o plano grátis**

`backend/Dockerfile`

```dockerfile
# Stage 1: build the jar with the Maven Wrapper (JDK image).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q package -DskipTests

# Stage 2: CDS (Class Data Sharing). A training run starts Spring once, without a database,
# and exits right after startup; the JVM saves the classes it loaded into app.jsa. Reading them
# back from that archive cut the startup from ~190 s to ~60 s on Render's free plan (0.1 CPU).
FROM eclipse-temurin:21-jre AS cds
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --destination application
WORKDIR /app/application
# Same JVM flags as the final image, so the archive is always accepted at runtime.
ENV JAVA_TOOL_OPTIONS="-XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xss512k"
RUN java -XX:ArchiveClassesAtExit=app.jsa -Dspring.context.exit=onRefresh \
      -Dspring.flyway.enabled=false -Dspring.jpa.hibernate.ddl-auto=none \
      -Dspring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false \
      -Dspring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect \
      -jar app.jar

# Stage 3: run on a JRE image, as a non-root user.
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app app
COPY --from=cds /app/application ./
USER app
EXPOSE 8080
# Tuned for the free host (512 MB, 0.1 CPU): only the fast C1 compiler (quicker startup),
# Serial GC (no extra GC threads), smaller thread stacks, heap up to 75% of the memory,
# and the CDS archive from stage 2.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xss512k -XX:SharedArchiveFile=app.jsa"
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 7: Medir a imagem com os limites do Render**

Com o banco do compose rodando (`docker compose up -d db`):

```bash
docker build -t ticket-flow-api-render backend
docker run -d --rm --name api-render-check --network ticket-flow_default --memory=512m --memory-swap=512m --cpus=0.1 -e PORT=10000 -e DB_URL=jdbc:postgresql://db:5432/ticketflow -e DB_USER=ticketflow -e DB_PASSWORD=ticketflow -e JWT_SECRET=local-compose-secret-change-me-0123456789 -e SPRING_PROFILES_ACTIVE=demo -e CORS_ALLOWED_ORIGINS=https://ticket-flow-web.onrender.com/ -p 10000:10000 ticket-flow-api-render
```

Espere cerca de um minuto e confira:

```bash
docker logs api-render-check 2>&1 | grep -E "Tomcat started on port|Started TicketFlowApplication"
curl -s localhost:10000/actuator/health
curl -s -D - -o /dev/null -X OPTIONS localhost:10000/api/tickets -H "Origin: https://ticket-flow-web.onrender.com" -H "Access-Control-Request-Method: GET" | grep -i access-control-allow-origin
docker stats --no-stream --format '{{.MemUsage}}' api-render-check
docker stop api-render-check
```

Expected:
- `Tomcat started on port 10000` e `Started TicketFlowApplication in` cerca de 55–65 segundos;
- health `UP`;
- `Access-Control-Allow-Origin: https://ticket-flow-web.onrender.com`;
- memória em torno de 200 MB, abaixo de 512 MB.

(Durante o build aparecem avisos `[cds] Preload Warning: Verification failed for ...` de classes opcionais que o treino não usa: são esperados.)

- [ ] **Step 8: Suíte completa e commit**

```bash
cd backend && ./mvnw -B verify; cd ..
```

Expected: `Tests run: 101, Failures: 0, Errors: 0` e `BUILD SUCCESS`.

```bash
git add backend
git commit -m "feat: run the API on Render's port, pin production CORS and start faster with CDS"
```

---

### Task 2: Frontend pronto para produção

**Objetivo:** o cliente HTTP aceita a URL da API com barra final; quem volta com sessão salva vê uma explicação enquanto o servidor acorda; o build no Render falha se faltar `VITE_API_URL`; e os testes deixam de falhar de forma intermitente por tempo limite.

**Conceitos:**
- `vi.stubEnv` + `vi.resetModules()`: testar código que lê a configuração quando o módulo carrega.
- Feedback de espera como parte da UX (latência de cold start).
- *Fail fast* no build: melhor um deploy falhar com mensagem clara do que publicar um site quebrado.
- Teste intermitente:
  - a causa medida foi 2 s isolado e mais de 5 s com a suíte em paralelo;
  - por que o limite fica num lugar só (`testTimeout` e `asyncUtilTimeout`), e não teste a teste.

**Files:**
- Modify (substituir o arquivo inteiro): `frontend/src/api/client.ts`, `frontend/src/api/client.test.ts`, `frontend/src/auth/guards.tsx`, `frontend/src/test/setup.ts`, `frontend/vite.config.ts`
- Test: `frontend/src/auth/guards.test.tsx`, `frontend/src/api/client.test.ts`

**Interfaces:**
- Consumes: `tokenStorage`, `api` e `mockFetch`/`jsonResponse` locais de `client.test.ts` (Plano 2); `renderRoutes` (Plano 2).
- Produces: `API_URL` sem barra final; `<CheckingSession />` (interno a `guards.tsx`) em `RequireAuth` e `GuestOnly` enquanto `/auth/me` responde.

- [ ] **Step 1: Escrever os testes (falhando)**

`client.test.ts` ganha o teste da barra final e o `vi.unstubAllEnvs()` no `afterEach` (arquivo inteiro):

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
  vi.unstubAllEnvs()
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

  it('ignores a trailing slash in VITE_API_URL (the API firewall refuses a double slash)', async () => {
    vi.stubEnv('VITE_API_URL', 'https://ticket-flow-api.onrender.com/')
    vi.resetModules() // the URL is read when the module loads
    const fresh = await import('./client')
    const fetchMock = mockFetch(jsonResponse(200, []))

    await fresh.api.get('/categories')

    expect(fetchMock.mock.calls[0][0]).toBe('https://ticket-flow-api.onrender.com/api/categories')
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

  it('never sends the stored token to login or sign-up (Spring answers 401 to a dead token even there)', async () => {
    tokenStorage.set('token-from-yesterday')
    const fetchMock = vi.fn<typeof fetch>(async () => jsonResponse(200, { token: 'new', user: {} }))
    vi.stubGlobal('fetch', fetchMock)

    await api.post('/auth/login', { email: 'a@b.c', password: 'demo1234' })
    await api.post('/auth/register', { name: 'A', email: 'a@b.c', password: 'demo1234' })

    for (const [, init] of fetchMock.mock.calls) {
      expect(new Headers(init?.headers).has('Authorization')).toBe(false)
    }
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

`frontend/src/auth/guards.test.tsx`

```tsx
import { screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { tokenStorage } from '../api/client'
import { renderRoutes } from '../test/render'
import { GuestOnly, RequireAuth } from './guards'

const routes = [
  { element: <RequireAuth />, children: [{ path: '/tickets', element: <p>Lista de chamados</p> }] },
  { element: <GuestOnly />, children: [{ path: '/login', element: <p>Tela de login</p> }] },
]

/** The free host sleeps after 15 minutes without traffic: the first request can take a minute. */
function serverStillWakingUp() {
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(() => new Promise<Response>(() => {})))
}

afterEach(() => vi.unstubAllGlobals())

describe('guards while the saved session is being checked', () => {
  it('explain the wait on a protected page instead of showing a bare spinner', () => {
    tokenStorage.set('jwt')
    serverStillWakingUp()

    renderRoutes(routes, '/tickets')

    expect(screen.getByText(/pode levar cerca de um minuto/)).toBeInTheDocument()
  })

  it('explain the wait on the login page too, instead of a blank screen', () => {
    tokenStorage.set('jwt')
    serverStillWakingUp()

    renderRoutes(routes, '/login')

    expect(screen.getByText(/pode levar cerca de um minuto/)).toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Rodar e ver falhar**

```bash
cd frontend && npx vitest run src/api/client.test.ts src/auth/guards.test.tsx; cd ..
```

Expected: 3 falhas:
- `Expected: "https://ticket-flow-api.onrender.com/api/categories"` com `Received: "https://ticket-flow-api.onrender.com//api/categories"`;
- nos dois testes de `guards`, `Unable to find an element with the text: /pode levar cerca de um minuto/`.

- [ ] **Step 3: Implementar**

`frontend/src/api/client.ts`

```ts
// Without the trailing slash, if any: "https://api.example.com/" + "/api" would be a double slash.
const API_URL = (import.meta.env.VITE_API_URL ?? 'http://localhost:8080').replace(/\/+$/, '')
export const TOKEN_KEY = 'ticketflow.token'

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

// Login and sign-up never carry a token: Spring rejects an invalid Bearer header even on public
// routes, so a token left over from yesterday (demo reset) would make the login itself fail.
const PUBLIC_PATHS = ['/auth/login', '/auth/register']

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
  const token = PUBLIC_PATHS.includes(path) ? null : tokenStorage.get()
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

`frontend/src/auth/guards.tsx`

```tsx
import { Center, Loader, Stack, Text } from '@mantine/core'
import { Navigate, Outlet, useLocation } from 'react-router'
import type { Role } from '../api/types'
import { useAuth } from './authContext'

/**
 * Shown while a saved session is checked. On the free host the server sleeps after 15 minutes
 * without traffic, so this first request can take about a minute: say so instead of a bare spinner.
 */
function CheckingSession() {
  return (
    <Center h="100vh" p="md">
      <Stack align="center" gap="sm">
        <Loader />
        <Text size="sm" c="dimmed" ta="center" maw={360}>
          Conectando ao servidor. Se ele estava dormindo, o primeiro acesso pode levar cerca de um minuto.
        </Text>
      </Stack>
    </Center>
  )
}

/**
 * Only lets logged-in users (optionally with one of the given roles) through.
 * This is UX only: the real authorization is in the backend, which checks every request.
 */
export function RequireAuth({ roles }: { roles?: Role[] }) {
  const { user, loading } = useAuth()
  const location = useLocation()

  if (loading) {
    return <CheckingSession />
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
    return <CheckingSession />
  }
  return user === null ? <Outlet /> : <Navigate to={from} replace />
}
```

- [ ] **Step 4: Tempos limite dos testes num lugar só**

Na verificação deste plano, a suíte completa falhou em 4 de 5 execuções: `NewTicketPage › opens the ticket...` levava 2 s sozinho e passava de 5 s (o limite padrão do Vitest) com os 15 arquivos em paralelo. O mesmo vale para os `findBy`/`waitFor` (1 s por padrão). O runner do CI é menor que a máquina do Roberto, então o risco é real lá também.

`frontend/src/test/setup.ts`

```ts
import '@testing-library/jest-dom/vitest'
import { cleanup, configure } from '@testing-library/react'
import { afterEach, vi } from 'vitest'

// findBy*/waitFor give up after 1 s by default: too short when the whole suite runs in parallel.
configure({ asyncUtilTimeout: 5_000 })

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

O `vite.config.ts` ganha o `testTimeout` e a trava do build no Render (arquivo inteiro):

`frontend/vite.config.ts`

```ts
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// Render sets RENDER=true in its builds. There, the API URL must come from VITE_API_URL:
// without it the site would build fine and then send every request to localhost.
if (process.env.RENDER && !process.env.VITE_API_URL) {
  throw new Error('VITE_API_URL is not set. Set it in the Render dashboard (ticket-flow-web > Environment).')
}

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
    // Component tests type into Mantine forms (one re-render per key). With every test file
    // running in parallel, or on a small CI machine, one test can pass 5 s (Vitest's default).
    testTimeout: 15_000,
  },
})
```

- [ ] **Step 5: Rodar a suíte várias vezes**

```bash
cd frontend && for i in 1 2 3 4 5; do npx vitest run 2>&1 | grep -E "Tests "; done; npm run lint && npx tsc -b; cd ..
```

Expected: `Tests  62 passed (62)` nas 5 execuções, sem erros de lint nem de tipos.

- [ ] **Step 6: Conferir a trava do build no Render**

```bash
cd frontend
RENDER=true npm run build; echo "exit $?"
RENDER=true VITE_API_URL=https://ticket-flow-api.onrender.com npm run build; echo "exit $?"
grep -l "https://ticket-flow-api.onrender.com" dist/assets/*.js
npm run build; echo "exit $?"
cd ..
```

Expected:
- o primeiro build falha (`exit 1`) com `VITE_API_URL is not set. Set it in the Render dashboard...`;
- o segundo passa (`exit 0`) e o `grep` encontra a URL dentro de um `index-*.js`;
- o terceiro (build local, igual ao CI) passa.

- [ ] **Step 7: Commit**

```bash
git add frontend
git commit -m "feat: prepare the frontend for production: API URL without trailing slash, wake-up message, Render build guard and stable test timeouts"
```

---

### Task 3: Infraestrutura como código (Render Blueprint) e `.env.example`

**Objetivo:** API e site descritos num `render.yaml` validado contra o schema oficial do Render, com deploy só depois do CI verde; e o `.env.example` da raiz que a spec pede.

**Conceitos:**
- Infraestrutura como código: o deploy é revisado em PR como qualquer código.
- Por que segredos nunca vão no YAML (`sync: false` e `generateValue`).
- `rootDir` em monorepo: cada serviço só é redeployado quando a pasta dele muda.
- `autoDeployTrigger: checksPass`: o CI vira o portão do deploy.
- Rewrite de SPA (`/* → /index.html`) e cache imutável de arquivos com hash.
- Validação por JSON Schema.

**Files:**
- Create: `render.yaml`, `.env.example`

**Interfaces:**
- Produces: serviços `ticket-flow-api` (web, Docker, Free, Virginia, `rootDir: backend`, health `/actuator/health`) e `ticket-flow-web` (static, `rootDir: frontend`, publica `dist`). Variáveis digitadas no painel: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `CORS_ALLOWED_ORIGINS` (API) e `VITE_API_URL` (site).

- [ ] **Step 1: Blueprint**

`render.yaml`

```yaml
# Render Blueprint: the whole deploy described as code (https://render.com/docs/blueprint-spec).
# Secrets and URLs marked "sync: false" are typed in the Render dashboard, never committed.
services:
  # API: Spring Boot in Docker. Free plan: sleeps after 15 min without traffic.
  - type: web
    name: ticket-flow-api
    runtime: docker
    plan: free
    region: virginia
    rootDir: backend
    dockerfilePath: ./Dockerfile
    dockerContext: .
    healthCheckPath: /actuator/health
    autoDeployTrigger: checksPass
    envVars:
      - key: SPRING_PROFILES_ACTIVE
        value: demo
      - key: JWT_SECRET
        generateValue: true
      - key: DB_URL
        sync: false
      - key: DB_USER
        sync: false
      - key: DB_PASSWORD
        sync: false
      - key: CORS_ALLOWED_ORIGINS
        sync: false

  # Frontend: static files built by Vite. Static sites never sleep.
  - type: web
    name: ticket-flow-web
    runtime: static
    rootDir: frontend
    buildCommand: npm ci && npm run build
    staticPublishPath: dist
    autoDeployTrigger: checksPass
    envVars:
      - key: NODE_VERSION
        value: "24"
      - key: VITE_API_URL
        sync: false
    routes:
      - type: rewrite
        source: /*
        destination: /index.html
    headers:
      - path: /assets/*
        name: Cache-Control
        value: public, max-age=31536000, immutable
```

Por que as URLs não são ligadas automaticamente: no Blueprint, `fromService ... property: host` devolve o endereço da **rede privada** do Render, que o navegador não alcança. E as URLs públicas (`RENDER_EXTERNAL_URL`) só existem depois que os serviços são criados. Por isso `VITE_API_URL` e `CORS_ALLOWED_ORIGINS` são digitadas no painel (Task 5).

- [ ] **Step 2: Validar contra o schema oficial**

```bash
curl -sfL https://render.com/schema/render.yaml.json -o /tmp/render-schema.json
npx --yes ajv-cli@5 validate --spec=draft2020 -s /tmp/render-schema.json -d render.yaml --strict=false
```

Expected: `render.yaml valid` (avisos `unknown format "uri" ignored` são do próprio schema e podem ser ignorados). Para ver a validação falhar, troque temporariamente `autoDeployTrigger: checksPass` por `onGreen`: aparece `render.yaml invalid`. Depois desfaça.

- [ ] **Step 3: `.env.example` da raiz**

`.env.example`

```bash
# Environment variables of the API (backend). Copy to .env to use locally; .env is never committed.
# In production (Render) these are set in the dashboard: see render.yaml and the README.

# PostgreSQL. Local: the "db" service of docker-compose. Neon: jdbc:postgresql://<host>/<database>?sslmode=require
DB_URL=jdbc:postgresql://localhost:5432/ticketflow
DB_USER=ticketflow
DB_PASSWORD=ticketflow

# Secret that signs the JWTs: at least 32 characters. The "demo" profile refuses to start without it.
JWT_SECRET=change-me-to-a-random-value-with-at-least-32-chars

# Frontend address allowed to call the API (comma-separated list).
CORS_ALLOWED_ORIGINS=http://localhost:5173

# "demo" loads the demonstration data and resets it every 24 h.
SPRING_PROFILES_ACTIVE=demo
```

- [ ] **Step 4: Conferir que nenhum segredo entrou**

```bash
git status --short
git check-ignore -v .env || echo ".env is not ignored!"
```

Expected: só `render.yaml` e `.env.example` como novos, e `.gitignore:...:.env` confirmando que um `.env` real seria ignorado.

- [ ] **Step 5: Commit**

```bash
git add render.yaml .env.example
git commit -m "chore: describe the Render deploy as a Blueprint and document the API environment"
```

---

### Task 4: README final, verificação completa, revisão e merge

**Objetivo:** README com o link da demo, o que o sistema faz, a stack, as decisões técnicas e os trade-offs (spec §11) e o passo a passo do deploy; a suíte inteira verde; a revisão final; e o PR.

**Conceitos:** como apresentar decisões num portfólio: cada escolha com o motivo e com o preço que se paga por ela.

**Files:**
- Modify (substituir o arquivo inteiro): `README.md`

- [ ] **Step 1: README**

O README usa as URLs previstas pelos nomes dos serviços (`https://ticket-flow-web.onrender.com` e `https://ticket-flow-api.onrender.com`). Se o Render atribuir outras na Task 5, o README é corrigido lá.

`README.md`

````markdown
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
````

- [ ] **Step 2: Verificação final completa**

```bash
cd backend && ./mvnw -B verify; cd ..
cd frontend && npm ci && npm run lint && npm test && npm run build; cd ..
curl -sfL https://render.com/schema/render.yaml.json -o /tmp/render-schema.json && npx --yes ajv-cli@5 validate --spec=draft2020 -s /tmp/render-schema.json -d render.yaml --strict=false
docker compose up -d --build && sleep 60 && curl -s localhost:8080/actuator/health && curl -s -o /dev/null -w "%{http_code}\n" localhost:5173/tickets/42
```

Expected:
- backend: `Tests run: 101, Failures: 0, Errors: 0` e `BUILD SUCCESS`;
- frontend: `Tests  62 passed (62)` e build ok;
- `render.yaml valid`;
- o compose sobe com a nova imagem: health `UP` e `200`.

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: final README with demo link, technical decisions, trade-offs and deploy guide"
```

- [ ] **Step 4: Revisão final da branch (única revisão do plano)**

Uma revisão independente de `feat/deploy` contra a `main` (skill `superpowers:requesting-code-review`), olhando em especial os itens do **Review Focus**. Corrigir o que for confirmado, com teste, em commits `fix:` separados.

- [ ] **Step 5: Push, PR e merge — só com autorização do Roberto**

Perguntar antes. Se autorizado:
1. `git push -u origin feat/deploy`;
2. abrir o PR para `main` e esperar os dois jobs do CI ficarem verdes;
3. fazer o merge com merge commit.

Depois do merge, a `main` tem tudo o que o Render precisa, e a Task 5 pode começar. A partir do Blueprint criado, cada merge na `main` é publicado sozinho quando o CI passa (`autoDeployTrigger: checksPass`).

---

### Task 5: Deploy guiado (Neon + Render) e verificação em produção

**Objetivo:** a demo no ar em `https://ticket-flow-web.onrender.com`, falando com a API em `https://ticket-flow-api.onrender.com` e com o banco no Neon.

**Conceitos:**
- O que é um Blueprint "sincronizado" com o repositório.
- A string de conexão JDBC com `sslmode=require` e por que usar o endpoint direto (sem `-pooler`): o Flyway usa travas de sessão que um pooler em modo transação não garante.
- Cold start de dois níveis: o Render acorda a API e o Neon acorda o banco.
- Verificar em produção com `curl` (health, preflight, rota da SPA) antes de abrir o navegador.

**Files:** nenhum (só configuração nos painéis).

Pré-requisito: a Task 4 concluída, com `feat/deploy` mergeada na `main`, porque o Render lê a `main`.

- [ ] **Step 1: Neon (o Roberto faz no navegador)**

1. Criar conta em https://neon.tech, sem cartão de crédito.
2. Criar um projeto `ticket-flow` com **PostgreSQL 17** na região **AWS US East 1 (N. Virginia)**.
3. Em **Connect**, desligar **Connection pooling** e anotar: host (`ep-...us-east-1.aws.neon.tech`, **sem** `-pooler`), banco (`neondb`), usuário e senha.

- [ ] **Step 2: Render (o Roberto faz no navegador)**

1. Criar conta em https://render.com e conectar o GitHub, liberando o repositório `lmaciel0/ticket-flow`.
2. **New → Blueprint** e escolher o repositório (branch `main`). O Render lê o `render.yaml` e pede as variáveis `sync: false`:
   - `DB_URL`: `jdbc:postgresql://<host do Neon>/neondb?sslmode=require`
   - `DB_USER` e `DB_PASSWORD`: os do Neon.
   - `CORS_ALLOWED_ORIGINS`: `https://ticket-flow-web.onrender.com`
   - `VITE_API_URL`: `https://ticket-flow-api.onrender.com`
3. **Apply** e esperar os dois serviços ficarem **Live** (o primeiro build da API leva alguns minutos).

- [ ] **Step 3: Conferir as URLs reais**

Em cada serviço, a URL aparece no topo da página. Se for diferente da prevista (quando o nome já existe, o Render acrescenta um sufixo), corrigir no painel:
- `CORS_ALLOWED_ORIGINS` (API) = URL real do site;
- `VITE_API_URL` (site) = URL real da API.

Depois, **Manual Deploy → Deploy latest commit** nos dois. O site precisa de um build novo, porque a URL da API fica embutida no JavaScript.

- [ ] **Step 4: Verificar a API (o Claude roda)**

```bash
API=https://ticket-flow-api.onrender.com
WEB=https://ticket-flow-web.onrender.com
time curl -s $API/actuator/health
curl -s -X POST $API/api/auth/login -H 'Content-Type: application/json' -d '{"email":"gestor@ticketflow.demo","password":"demo1234"}' | head -c 120; echo
curl -s -D - -o /dev/null -X OPTIONS $API/api/tickets -H "Origin: $WEB" -H "Access-Control-Request-Method: GET" -H "Access-Control-Request-Headers: authorization" | grep -iE "^HTTP|access-control-allow-origin"
```

Expected:
- health `{"groups":["liveness","readiness"],"status":"UP"}`; se a API estava dormindo, o `time` mostra até cerca de um minuto;
- login com `token` e `"role":"MANAGER"`;
- preflight `HTTP/2 200` com `access-control-allow-origin: https://ticket-flow-web.onrender.com`.

- [ ] **Step 5: Verificar o site (o Claude roda)**

```bash
curl -s -o /dev/null -w "%{http_code}\n" $WEB/tickets/42
curl -s $WEB/ | grep -o '/assets/index-[^"]*\.js' | head -1
```

```bash
JS=$(curl -s $WEB/ | grep -o '/assets/index-[^"]*\.js' | head -1)
curl -s $WEB$JS | grep -o 'https://ticket-flow-api[a-z0-9.-]*' | head -1
```

Expected: `200` (o rewrite devolve o app para qualquer rota), o nome do bundle e a URL da API embutida no JavaScript (`https://ticket-flow-api.onrender.com`).

- [ ] **Step 6: Roteiro no navegador e banco suspenso (o Claude roda, com o Roberto acompanhando)**

1. Abrir o site e conferir o aviso de reset diário e de primeiro acesso.
2. Roteiro do Plano 2 em produção: o solicitante abre um chamado → o atendente assume e pede informação → o solicitante comenta (o chamado volta sozinho para "Em atendimento") e anexa um PDF pequeno → o atendente resolve → o solicitante fecha → o painel do gestor mostra o resolvido.
3. Esperar mais de 5 minutos sem uso (o Neon suspende) e recarregar a lista. Ela deve abrir normalmente, no máximo um pouco mais lenta. Esperar mais de 15 minutos (o Render dorme) e abrir o site com a sessão salva: aparece "Conectando ao servidor..." e, em cerca de um minuto, a lista.
4. No Neon, conferir **Storage** bem abaixo de 0,5 GB. No Render, conferir nos logs da API a linha `Started TicketFlowApplication in ... seconds`.

- [ ] **Step 7: Corrigir o README se as URLs forem outras**

Só se o Render atribuiu URLs diferentes das previstas: trocar `https://ticket-flow-web.onrender.com` e `https://ticket-flow-api.onrender.com` no `README.md` pelas reais e mandar num PR pequeno (`docs: point the README to the real demo URLs`), com autorização do Roberto para push e merge.

---

## Cobertura da spec neste plano

| Seção da spec | Onde |
|---|---|
| 1.1 Visitante abre o link do README, entra com um botão de demo e percorre o fluxo | Task 4 (README) e Task 5 (Step 6) |
| 1.4 CI com badge verde no README | já existente; o README mantém o badge (Task 4), e o CI passa a ser o portão do deploy (Task 3) |
| 1.5 README explica decisões técnicas e trade-offs (§11) | Task 4 (itens 1 a 11 do README, incluindo os 7 trade-offs da §11) |
| 10. `.env.example` versionado; `.env` nunca versionado | Task 3 |
| 10. Deploy de custo zero: Render Static Site, Render Web Service via Docker, Neon; condições reconfirmadas | "Condições dos planos grátis" e Tasks 3 e 5 |
| 10. JVM ajustada para ~512 MB (`MaxRAMPercentage`); README avisa que o primeiro acesso é lento | Task 1 (Dockerfile com CDS e flags) e Task 4 (README); aviso também na tela (Task 2) |
| 10. CORS liberado só para o domínio do frontend | Tasks 1 (teste), 3 e 5 |
| 9. `VITE_API_URL` define a URL da API | Tasks 2, 3 e 5 |

## Depois deste plano

O MVP da spec fica completo. Evoluções possíveis, já registradas:
- os itens menores adiados na revisão do Plano 2 (M1 a M12);
- refresh token em cookie `httpOnly`;
- testes E2E com Playwright;
- horário comercial no SLA;
- armazenamento de anexos em S3.
