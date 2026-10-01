# Rate limiting nas rotas públicas de autenticação — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Limitar `POST /api/auth/login` a 5 por minuto e `POST /api/auth/register` a 3 por hora por IP, respondendo 429 com `Retry-After` e ProblemDetail em português.

**Architecture:** Um `LoginRateLimitFilter` (pacote `common`, sem ser bean) entra na cadeia do Spring Security logo depois do `CorsFilter`. Ele guarda um bucket do Bucket4j por IP num cache Caffeine por rota; o tempo do Bucket4j vem do `Clock` da aplicação (`ClockTimeMeter`). Os limites ficam em `RateLimitProperties` (`app.rate-limit`), e `server.forward-headers-strategy: native` faz `getRemoteAddr()` devolver o IP do cliente atrás do proxy do Render. O frontend só ganha uma mensagem padrão para o 429.

**Tech Stack:** Spring Boot 4.1, Spring Security, Bucket4j 8.14.0 (`com.bucket4j:bucket4j_jdk17-core`), Caffeine (versão gerenciada pelo Spring Boot), JUnit 5 + AssertJ + MockMvc + Testcontainers; React + Vitest.

**Spec:** `docs/specs/2026-10-01-rate-limiting-design.md`

## Global Constraints

- Chave do limite: só o IP (`request.getRemoteAddr()`). Nada de limite por e-mail.
- Login: `capacity: 5`, `period: 1m`. Cadastro: `capacity: 3`, `period: 1h`. Recarga gradual (`refillGreedy(capacity, period)`), limites independentes.
- Só `POST /api/auth/login` e `POST /api/auth/register` são limitados; toda requisição a elas conta, com sucesso ou não.
- Resposta: status 429, header `Retry-After` em segundos (arredondado para cima, mínimo 1), corpo `application/problem+json` com `"title":"Too Many Requests"`, `"status":429`, `"detail":"Muitas tentativas. Tente de novo em N s."` e `traceId`.
- O filtro roda depois do `CorsFilter` e não é bean do Spring.
- Cache Caffeine por rota: `expireAfterAccess(period)`, `maximumSize(100_000)`.
- `server.forward-headers-strategy: native`; `Retry-After` exposto no CORS.
- Testes de integração: limite desligado na base `IntegrationTest`; só o teste do limite o liga.
- Mensagem padrão do frontend para o 429: `Muitas tentativas. Tente de novo em instantes.`
- Branch `feat/rate-limiting`. Commits em inglês terminando com `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Descrição do PR em português.
- Backend: `./mvnw` dentro de `backend/`, com o Docker Desktop ligado (Testcontainers); os resultados ficam em `target/surefire-reports/*.txt` (a saída do Maven é ruidosa). Frontend: comandos dentro de `frontend/`.

## Review Focus

1. **Preflight do navegador.** Antes de cada `POST` entre origens o navegador manda `OPTIONS /api/auth/login`; ele não pode gastar fichas nem receber 429. Teste em Task 2 (`preflightDoesNotSpendAttempts`).
2. **Tentativas com senha errada ou corpo inválido também contam.** Um atacante não pode contornar o limite mandando requisições que falham na validação (400) ou na senha (401). Teste em Task 2 (`theSixthLoginFromTheSameIpIsRejected` mistura 200, 401 e 400).
3. **O 429 precisa chegar ao frontend.** Sem `Access-Control-Allow-Origin` o navegador esconde a resposta e a tela diz "servidor inacessível". Teste em Task 2 (`theRejectionCarriesTheCorsHeaders`).
4. **Outro IP e outra rota não são afetados.** Esgotar o login de um IP não bloqueia outro IP nem o cadastro do mesmo IP. Testes em Task 2.
5. **Os testes existentes não podem passar a falhar por excesso de logins.** Eles fazem muitos logins de `127.0.0.1`; o limite fica desligado neles. Coberto pela suíte completa em Task 1 e Task 4.

---

### Task 1: Dependências, `RateLimitProperties` e configuração

**Files:**
- Modify: `backend/pom.xml` (bloco `<dependencies>`)
- Create: `backend/src/main/java/com/ticketflow/common/RateLimitProperties.java`
- Modify: `backend/src/main/resources/application.yml` (blocos `server:` e `app:`)
- Modify: `backend/src/test/java/com/ticketflow/support/IntegrationTest.java` (anotações da classe)
- Test: `backend/src/test/java/com/ticketflow/common/RateLimitConfigTest.java`

**Interfaces:**
- Produces: `record RateLimitProperties(boolean enabled, Limit login, Limit register)` com `record Limit(int capacity, Duration period)` aninhado; lido de `app.rate-limit` (o `@ConfigurationPropertiesScan` de `TicketFlowApplication` já o registra).

- [ ] **Step 1: Write the failing test**

Criar `backend/src/test/java/com/ticketflow/common/RateLimitConfigTest.java`:

```java
package com.ticketflow.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.config.ConfigDataApplicationContextInitializer;

/** The limits and the proxy setting come from application.yml, so they are checked as the real app loads them. */
class RateLimitConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void limitsLoginAndSignUpByDefault() {
        runner.run(context -> {
            RateLimitProperties properties = Binder.get(context.getEnvironment())
                    .bind("app.rate-limit", RateLimitProperties.class).get();
            assertThat(properties.enabled()).isTrue();
            assertThat(properties.login()).isEqualTo(new RateLimitProperties.Limit(5, Duration.ofMinutes(1)));
            assertThat(properties.register()).isEqualTo(new RateLimitProperties.Limit(3, Duration.ofHours(1)));
        });
    }

    @Test
    void readsTheClientIpThatTheProxyRecorded() {
        runner.run(context -> assertThat(context.getEnvironment().getProperty("server.forward-headers-strategy"))
                .isEqualTo("native"));
    }

    @Test
    void refusesALimitThatWouldBlockEverything() {
        assertThatThrownBy(() -> new RateLimitProperties.Limit(0, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new RateLimitProperties.Limit(5, Duration.ZERO))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new RateLimitProperties.Limit(5, null))
                .isInstanceOf(IllegalStateException.class);
    }
}
```

O import de `ConfigDataApplicationContextInitializer` é o mesmo do `ServerPortConfigTest` vizinho; se o pacote mudar nesta versão do Boot, copie o import de lá.

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q test -Dtest=RateLimitConfigTest` (dentro de `backend/`)
Expected: FAIL na compilação, `cannot find symbol: class RateLimitProperties`.

- [ ] **Step 3: Add the dependencies**

Em `backend/pom.xml`, junto das outras dependências de runtime (por exemplo, logo depois de `openpdf`):

```xml
		<dependency>
			<groupId>com.bucket4j</groupId>
			<artifactId>bucket4j_jdk17-core</artifactId>
			<version>8.14.0</version>
		</dependency>
		<dependency>
			<groupId>com.github.ben-manes.caffeine</groupId>
			<artifactId>caffeine</artifactId>
		</dependency>
```

(Caffeine sem `<version>`: o Spring Boot gerencia.)

- [ ] **Step 4: Write `RateLimitProperties`**

```java
package com.ticketflow.common;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Attempts allowed per client IP on the public auth routes, read from app.rate-limit in application.yml. */
@ConfigurationProperties("app.rate-limit")
public record RateLimitProperties(boolean enabled, Limit login, Limit register) {

    public RateLimitProperties {
        if (login == null || register == null) {
            throw new IllegalStateException("Missing app.rate-limit.login or app.rate-limit.register");
        }
    }

    /** {@code capacity} attempts per {@code period}, given back gradually (one every period / capacity). */
    public record Limit(int capacity, Duration period) {

        public Limit {
            if (capacity < 1) {
                throw new IllegalStateException("Rate limit capacity must be at least 1");
            }
            if (period == null || period.isZero() || period.isNegative()) {
                throw new IllegalStateException("Rate limit period must be positive");
            }
        }
    }
}
```

- [ ] **Step 5: Configure `application.yml`**

No bloco `server:` existente, depois de `port`:

```yaml
  # Render's proxy forwards the client IP in X-Forwarded-For; Tomcat trusts it only from internal addresses.
  forward-headers-strategy: native
```

No bloco `app:`, depois de `jwt:` (mesma indentação de `jwt:`):

```yaml
  rate-limit:
    enabled: true
    login:
      capacity: 5
      period: 1m
    register:
      capacity: 3
      period: 1h
```

- [ ] **Step 6: Turn the limit off in the existing integration tests**

Em `IntegrationTest`, logo abaixo de `@SpringBootTest`, acrescentar (com o import `org.springframework.test.context.TestPropertySource`):

```java
// The API tests log in many times from 127.0.0.1; LoginRateLimitApiTest turns the limit back on.
@TestPropertySource(properties = "app.rate-limit.enabled=false")
```

Use `@TestPropertySource`, e não `@SpringBootTest(properties = ...)`: as propriedades de `@TestPropertySource` de uma subclasse sobrepõem as da classe base, o que a Task 2 usa para religar o limite.

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./mvnw -q test -Dtest=RateLimitConfigTest`
Expected: PASS (3 testes). Depois, a suíte completa: `./mvnw -q test` e conferir `target/surefire-reports/*.txt`: 0 falhas, 0 erros.

- [ ] **Step 8: Commit**

```bash
git add backend/pom.xml backend/src/main/java/com/ticketflow/common/RateLimitProperties.java backend/src/main/resources/application.yml backend/src/test/java/com/ticketflow/support/IntegrationTest.java backend/src/test/java/com/ticketflow/common/RateLimitConfigTest.java
git commit -m "feat: add rate limit settings and trust the proxy's client IP" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: `LoginRateLimitFilter` na cadeia do Spring Security

**Files:**
- Create: `backend/src/main/java/com/ticketflow/common/ClockTimeMeter.java`
- Create: `backend/src/main/java/com/ticketflow/common/LoginRateLimitFilter.java`
- Modify: `backend/src/main/java/com/ticketflow/common/SecurityConfig.java` (método `securityFilterChain` e `corsConfigurationSource`)
- Test: `backend/src/test/java/com/ticketflow/common/LoginRateLimitApiTest.java`

**Interfaces:**
- Consumes: `RateLimitProperties` e `RateLimitProperties.Limit` (Task 1); `TraceIdFilter.MDC_KEY`; o bean `Clock` (em testes, o `MutableClock` com `advance(Duration)`).
- Produces: `new LoginRateLimitFilter(RateLimitProperties properties, Clock clock)`; `new ClockTimeMeter(Clock clock)` implementando `io.github.bucket4j.TimeMeter`.

- [ ] **Step 1: Write the failing test**

Criar `backend/src/test/java/com/ticketflow/common/LoginRateLimitApiTest.java`:

```java
package com.ticketflow.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.support.IntegrationTest;
import com.ticketflow.user.Role;
import com.ticketflow.user.User;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

/** Login: 5 per minute per IP. Sign-up: 3 per hour per IP. Every attempt counts, whatever the answer. */
@TestPropertySource(properties = "app.rate-limit.enabled=true")
class LoginRateLimitApiTest extends IntegrationTest {

    private static final String FRONTEND = "http://localhost:5173";

    private User ana;
    /** A fresh IP per test: the limiter is a singleton and keeps its buckets between tests. */
    private String ip;
    private static int nextIp = 1;

    @BeforeEach
    void setUp() {
        ana = createUser("Ana", Role.REQUESTER);
        ip = "203.0.113." + nextIp++;
    }

    private ResultActions login(String fromIp, String password) throws Exception {
        return mvc.perform(post("/api/auth/login")
                .with(request -> {
                    request.setRemoteAddr(fromIp);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "%s"}
                        """.formatted(ana.getEmail(), password)));
    }

    private ResultActions register(String fromIp, String email) throws Exception {
        return mvc.perform(post("/api/auth/register")
                .with(request -> {
                    request.setRemoteAddr(fromIp);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "Visitante", "email": "%s", "password": "password123"}
                        """.formatted(email)));
    }

    @Test
    void theSixthLoginFromTheSameIpIsRejected() throws Exception {
        login(ip, PASSWORD).andExpect(status().isOk());
        login(ip, "wrong-password").andExpect(status().isUnauthorized());
        login(ip, "").andExpect(status().isBadRequest());
        login(ip, PASSWORD).andExpect(status().isOk());
        login(ip, "wrong-password").andExpect(status().isUnauthorized());

        login(ip, PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "12"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.detail").value("Muitas tentativas. Tente de novo em 12 s."))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void oneMoreAttemptComesBackEveryTwelveSeconds() throws Exception {
        for (int i = 0; i < 5; i++) {
            login(ip, PASSWORD).andExpect(status().isOk());
        }
        login(ip, PASSWORD).andExpect(status().isTooManyRequests());

        clock.advance(Duration.ofSeconds(12));

        login(ip, PASSWORD).andExpect(status().isOk());
        login(ip, PASSWORD).andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "12"));
    }

    @Test
    void anotherIpHasItsOwnLimit() throws Exception {
        for (int i = 0; i < 5; i++) {
            login(ip, PASSWORD).andExpect(status().isOk());
        }
        login(ip, PASSWORD).andExpect(status().isTooManyRequests());

        login(ip + "0", PASSWORD).andExpect(status().isOk());
    }

    @Test
    void signUpHasItsOwnHourlyLimit() throws Exception {
        for (int i = 0; i < 5; i++) {
            login(ip, PASSWORD).andExpect(status().isOk());
        }
        login(ip, PASSWORD).andExpect(status().isTooManyRequests());

        register(ip, "um@test.com").andExpect(status().isCreated());
        register(ip, "dois@test.com").andExpect(status().isCreated());
        register(ip, "tres@test.com").andExpect(status().isCreated());
        register(ip, "quatro@test.com")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1200"));
    }

    @Test
    void authenticatedRoutesAreNotLimited() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(get("/api/tickets")
                            .with(request -> {
                                request.setRemoteAddr(ip);
                                return request;
                            })
                            .header("Authorization", bearer(ana)))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void preflightDoesNotSpendAttempts() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(options("/api/auth/login")
                            .with(request -> {
                                request.setRemoteAddr(ip);
                                return request;
                            })
                            .header("Origin", FRONTEND)
                            .header("Access-Control-Request-Method", "POST"))
                    .andExpect(status().isOk());
        }
        login(ip, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void theRejectionCarriesTheCorsHeaders() throws Exception {
        for (int i = 0; i < 5; i++) {
            login(ip, PASSWORD).andExpect(status().isOk());
        }
        String exposed = mvc.perform(post("/api/auth/login")
                        .with(request -> {
                            request.setRemoteAddr(ip);
                            return request;
                        })
                        .header("Origin", FRONTEND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"x@test.com\", \"password\": \"x\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Access-Control-Allow-Origin", FRONTEND))
                .andReturn().getResponse().getHeader("Access-Control-Expose-Headers");
        assertThat(exposed).contains("Retry-After");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q test -Dtest=LoginRateLimitApiTest`
Expected: FAIL. `theSixthLoginFromTheSameIpIsRejected` espera 429 e recebe 200; `preflightDoesNotSpendAttempts` e `authenticatedRoutesAreNotLimited` podem passar já (sem limite nada é bloqueado).

- [ ] **Step 3: Write `ClockTimeMeter`**

```java
package com.ticketflow.common;

import io.github.bucket4j.TimeMeter;
import java.time.Clock;
import java.time.Instant;

/** Bucket4j's notion of "now", read from the application Clock so tests can move time instead of sleeping. */
class ClockTimeMeter implements TimeMeter {

    private final Clock clock;

    ClockTimeMeter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public long currentTimeNanos() {
        Instant now = clock.instant();
        return now.getEpochSecond() * 1_000_000_000L + now.getNano();
    }

    @Override
    public boolean isWallClockBased() {
        return true;
    }
}
```

- [ ] **Step 4: Write `LoginRateLimitFilter`**

```java
package com.ticketflow.common;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limits login and sign-up attempts per client IP (brute force, credential stuffing, mass sign-up). It sits in
 * the Spring Security chain right after CORS, so a 429 still carries the CORS headers the browser needs, and it
 * answers before the controller, so a blocked attempt never pays for a BCrypt hash. Not a Spring bean on
 * purpose: Spring Boot would also register a bean filter in front of the whole chain, before CORS.
 */
public class LoginRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimitFilter.class);

    private static final String BODY =
            "{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,"
                    + "\"detail\":\"Muitas tentativas. Tente de novo em %d s.\"%s}";

    private final boolean enabled;
    private final ClockTimeMeter timeMeter;
    /** Path → (IP → bucket). */
    private final Map<String, RouteLimiter> routes;

    public LoginRateLimitFilter(RateLimitProperties properties, Clock clock) {
        this.enabled = properties.enabled();
        this.timeMeter = new ClockTimeMeter(clock);
        this.routes = Map.of(
                "/api/auth/login", new RouteLimiter(properties.login()),
                "/api/auth/register", new RouteLimiter(properties.register()));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || !HttpMethod.POST.matches(request.getMethod()) || !routes.containsKey(path(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = path(request);
        String ip = request.getRemoteAddr();
        ConsumptionProbe probe = routes.get(path).bucketFor(ip).tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            chain.doFilter(request, response);
            return;
        }
        long retryAfter = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill() + 999_999_999L));
        log.warn("Too many attempts on {} from {}", path, ip);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        // Runs outside Spring MVC, so the trace id is added here instead of by TraceIdProblemAdvice.
        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        response.getWriter().write(BODY.formatted(retryAfter,
                traceId == null ? "" : ",\"traceId\":\"" + traceId + "\""));
    }

    private static String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    /** The buckets of one route. An idle IP is forgotten once its bucket would be full again. */
    private final class RouteLimiter {

        private final RateLimitProperties.Limit limit;
        private final Cache<String, Bucket> buckets;

        RouteLimiter(RateLimitProperties.Limit limit) {
            this.limit = limit;
            this.buckets = Caffeine.newBuilder()
                    .expireAfterAccess(limit.period())
                    .maximumSize(100_000)
                    .build();
        }

        Bucket bucketFor(String ip) {
            return buckets.get(ip, key -> Bucket.builder()
                    .addLimit(bandwidth -> bandwidth.capacity(limit.capacity())
                            .refillGreedy(limit.capacity(), limit.period()))
                    .withCustomTimePrecision(timeMeter)
                    .build());
        }
    }
}
```

O arredondamento: `getNanosToWaitForRefill()` é 12 000 000 000 ns depois de 5 logins seguidos (60 s / 5); somar 999 999 999 antes de converter arredonda para cima sem alterar um valor exato (12 s continua 12).

- [ ] **Step 5: Wire it into `SecurityConfig`**

Em `SecurityConfig.securityFilterChain`, acrescentar os parâmetros `RateLimitProperties rateLimit, Clock clock` e, logo depois de `http`, antes de `.csrf(...)`, a linha do filtro. A assinatura e o começo ficam:

```java
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemAuthenticationEntryPoint entryPoint,
            UserRepository users, RateLimitProperties rateLimit, Clock clock) throws Exception {
        http
                // After CORS, so a 429 still reaches the frontend; before the controller, so no BCrypt is spent.
                .addFilterAfter(new LoginRateLimitFilter(rateLimit, clock), CorsFilter.class)
                // Stateless API with a Bearer token: there is no session cookie for CSRF to abuse.
                .csrf(csrf -> csrf.disable())
```

Imports novos: `java.time.Clock` e `org.springframework.web.filter.CorsFilter`.

Em `corsConfigurationSource`, acrescentar `HttpHeaders.RETRY_AFTER` aos headers expostos:

```java
        config.setExposedHeaders(List.of("Content-Disposition", TraceIdFilter.HEADER, "X-Export-Truncated",
                HttpHeaders.ETAG, HttpHeaders.RETRY_AFTER));
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw -q test -Dtest='LoginRateLimitApiTest,CorsApiTest,AuthApiTest'`
Expected: PASS em todos. Se `theSixthLoginFromTheSameIpIsRejected` ainda receber 200, conferir se o `@TestPropertySource` da classe religou o limite (o valor efetivo pode ser lido com `@Value("${app.rate-limit.enabled}")` num teste rápido).

- [ ] **Step 7: Run the whole backend suite**

Run: `./mvnw -q test`
Expected: 0 falhas e 0 erros em `target/surefire-reports/*.txt` (conte pelos XML: `grep -h "<testsuite " target/surefire-reports/*.xml`).

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/ticketflow/common/ClockTimeMeter.java backend/src/main/java/com/ticketflow/common/LoginRateLimitFilter.java backend/src/main/java/com/ticketflow/common/SecurityConfig.java backend/src/test/java/com/ticketflow/common/LoginRateLimitApiTest.java
git commit -m "feat: limit login and sign-up attempts per IP" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Mensagem padrão do 429 no frontend

**Files:**
- Modify: `frontend/src/api/client.ts` (constante `FALLBACK_MESSAGES`, linhas 6-11)
- Test: `frontend/src/api/client.test.ts`

**Interfaces:**
- Consumes: a resposta 429 da Task 2 (com `detail`, que o cliente já usa quando existe).
- Produces: nada novo; `ApiError.message` para um 429 sem `detail`.

- [ ] **Step 1: Write the failing test**

Em `frontend/src/api/client.test.ts`, depois do teste `uses a Portuguese message for 413, whatever the server wrote`:

```ts
  it('shows the wait the server asked for after too many attempts', async () => {
    mockFetch(jsonResponse(429, { status: 429, detail: 'Muitas tentativas. Tente de novo em 12 s.' }))

    const error = await api.post('/auth/login', {}).catch((e: unknown) => e)

    expect(error).toMatchObject({ status: 429, message: 'Muitas tentativas. Tente de novo em 12 s.' })
  })

  it('still explains a 429 that comes without a message', async () => {
    mockFetch(new Response('', { status: 429 }))

    const error = await api.post('/auth/login', {}).catch((e: unknown) => e)

    expect(error).toMatchObject({ status: 429, message: 'Muitas tentativas. Tente de novo em instantes.' })
  })
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run src/api/client.test.ts` (dentro de `frontend/`)
Expected: o primeiro passa (o `detail` já é usado); o segundo FALHA com a mensagem `Algo deu errado. Tente de novo.`

- [ ] **Step 3: Add the fallback message**

Em `frontend/src/api/client.ts`:

```ts
const FALLBACK_MESSAGES: Record<number, string> = {
  0: 'Não foi possível falar com o servidor. Tente de novo em instantes.',
  403: 'Você não tem permissão para esta ação.',
  404: 'Não encontrado.',
  413: 'O arquivo passa do limite de 5 MB.',
  429: 'Muitas tentativas. Tente de novo em instantes.',
}
```

- [ ] **Step 4: Run the frontend checks**

Run (dentro de `frontend/`): `npx vitest run`, `npx tsc --noEmit -p tsconfig.app.json`, `npm run lint`, `npm run build`
Expected: tudo verde.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/api/client.ts frontend/src/api/client.test.ts
git commit -m "feat: explain a 429 on the login and sign-up screens" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Fechamento

**Files:**
- Modify: `docs/specs/2026-10-01-rate-limiting-design.md` (linha `**Status:**`)

- [ ] **Step 1: Mark the spec as approved**

Trocar `- **Status:** em revisão` por `- **Status:** aprovada`.

- [ ] **Step 2: Run everything once more**

Backend: `./mvnw -q test` (0 falhas, 0 erros). Frontend: `npx vitest run`, `npm run lint`, `npm run build`.

- [ ] **Step 3: Commit**

```bash
git add docs/specs/2026-10-01-rate-limiting-design.md docs/plans/2026-10-01-rate-limiting.md
git commit -m "docs: add the rate limiting plan and approve the spec" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 4: Push and PR only with the user's OK**

Push da branch `feat/rate-limiting` e PR com descrição em português só depois de o usuário autorizar. Depois do deploy, conferir na demo: 6 logins seguidos dão 429 na tela, e o log `WARN Too many attempts on /api/auth/login from <ip>` no Render mostra um IP público, e não um endereço interno.
