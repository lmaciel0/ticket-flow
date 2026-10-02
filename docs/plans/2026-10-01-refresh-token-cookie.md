# Refresh token em cookie HttpOnly — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Trocar o JWT de 8 h no `localStorage` por um access token de 15 min em memória e um refresh token rotativo em cookie `HttpOnly; Secure; SameSite=Strict` no domínio do site, mantendo o login direto na API (IP real no rate limiting).

**Architecture:** O login e o cadastro continuam diretos na API e passam a devolver um `sessionCode` de uso único. O frontend o troca em `POST /api/auth/session` pela própria origem (o Render repassa `/api/*` à API), e a resposta grava o cookie `tf_refresh`. `POST /api/auth/refresh` e `/logout` também vão pela própria origem. O backend guarda só hashes (tabelas `refresh_tokens` e `session_handoffs`, migration V10) num `RefreshTokenService` com rotação e detecção de reuso. O frontend guarda o access token em memória, renova uma vez num 401 e sincroniza as abas com `BroadcastChannel`.

**Tech Stack:** Spring Boot 4.1, Spring Security, JPA/Hibernate, Flyway, PostgreSQL 17, JUnit 5 + AssertJ + MockMvc + Testcontainers; React 19 + TanStack Query + Mantine + Vitest.

**Spec:** `docs/specs/2026-10-01-refresh-token-cookie-design.md`

## Global Constraints

- Access token: `app.jwt.ttl: 15m`, só em memória no frontend; a chave antiga `ticketflow.token` é apagada do `localStorage`.
- Refresh token e código: 32 bytes de `SecureRandom` em Base64 URL sem padding; no banco só o SHA-256 em hexadecimal minúsculo (64 caracteres).
- `app.auth`: `refresh-ttl: 7d`, `handoff-ttl: 60s`, `reuse-grace: 30s`, `refresh-cookie.secure: true`.
- Cookie: nome `tf_refresh`, `HttpOnly`, `Secure` (configurável), `SameSite=Strict`, `Path=/api/auth`, `Max-Age` = tempo até a expiração da família.
- A rotação não estende a família: o sucessor herda o `expires_at`.
- Reuso de token já usado: até 30 s depois do uso → 200 com access token novo e **sem** `Set-Cookie`; depois disso → revoga a família e 401.
- Usuário inativo → revoga a família e 401. Papel trocado → 200 com o papel atual.
- Rotas pela própria origem (sem `API_URL`): `/auth/session`, `/auth/refresh`, `/auth/logout`. Login e cadastro continuam pelo `API_URL`.
- 401 do refresh: ProblemDetail `"Sessão expirada. Entre novamente."` e cookie apagado (`Max-Age=0`).
- Migration `V10__create_refresh_tokens.sql` (a última é a V9). Colunas de hash em `VARCHAR(64)` (o Hibernate valida o esquema: `ddl-auto: validate`).
- `BroadcastChannel('ticketflow-auth')` com mensagens `{ type: 'login' }` e `{ type: 'logout' }`; lock `ticketflow-refresh` do `navigator.locks` quando existir.
- Variável do docker compose: `APP_AUTH_REFRESHCOOKIE_SECURE: "false"`.
- Branch `feat/refresh-token-cookie`. Commits em inglês terminando com `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Descrição do PR em português.
- Backend: `./mvnw` dentro de `backend/`, com o Docker Desktop ligado; resultados em `target/surefire-reports/*.txt` (a saída do Maven é ruidosa; contar pelos XML). Frontend: comandos dentro de `frontend/`.

## Review Focus

1. **A revogação por reuso não pode ser desfeita por rollback.** Se o serviço lançasse exceção depois de `revokeFamily`, a transação voltaria e a família seguiria viva. Por isso `refresh` devolve `Optional.empty()` em vez de lançar. Teste em Task 2 (`reusingATokenAfterTheGraceRevokesTheWholeFamily`).
2. **Usuário carregado de forma preguiçosa fora da transação.** `open-in-view` está desligado; o controller não pode tocar em `RefreshToken.getUser()`. O serviço devolve `UserResponse` já montado. Teste em Task 2 (`refreshReturnsANewAccessTokenAndRotatesTheCookie` lê `$.user.name`).
3. **Login em transação só de leitura gravando o código.** `AuthService.login` era `@Transactional(readOnly = true)`; com o `createHandoff` ele precisa gravar. Teste em Task 2 (`loginReturnsASessionCodeThatBecomesTheCookie`).
4. **API inalcançável ao abrir a página** (cold start, repasse fora do ar). O refresh com erro de rede deve levar à tela de login, sem quebrar a página. Teste em Task 4 (`treats an unreachable server as no session`).
5. **Muitas chamadas com 401 ao mesmo tempo.** Precisam virar um só refresh; vários refreshes com o mesmo cookie cairiam no reuso. Teste em Task 4 (`renews only once when many requests fail together`).

---

### Task 1: Banco, propriedades e `RefreshTokenService`

**Files:**
- Modify: `backend/src/main/java/com/ticketflow/common/LoginRateLimitFilter.java` (tirar o log temporário do #25)
- Create: `backend/src/main/resources/db/migration/V10__create_refresh_tokens.sql`
- Create: `backend/src/main/java/com/ticketflow/auth/AuthProperties.java`
- Create: `backend/src/main/java/com/ticketflow/auth/RefreshToken.java`
- Create: `backend/src/main/java/com/ticketflow/auth/RefreshTokenRepository.java`
- Create: `backend/src/main/java/com/ticketflow/auth/SessionHandoff.java`
- Create: `backend/src/main/java/com/ticketflow/auth/SessionHandoffRepository.java`
- Create: `backend/src/main/java/com/ticketflow/auth/RefreshTokenService.java`
- Modify: `backend/src/main/resources/application.yml` (bloco `app:`)
- Test: `backend/src/test/java/com/ticketflow/auth/RefreshTokenServiceTest.java`
- Test: `backend/src/test/java/com/ticketflow/auth/AuthConfigTest.java`

**Interfaces:**
- Produces:
  - `record AuthProperties(Duration refreshTtl, Duration handoffTtl, Duration reuseGrace, RefreshCookie refreshCookie)` com `record RefreshCookie(boolean secure)` aninhado (`app.auth`).
  - `RefreshTokenService`:
    - `String createHandoff(User user)`
    - `IssuedRefresh redeemHandoff(String code)` (lança `ApiException.unauthorized` se inválido ou vencido)
    - `Optional<RefreshResult> refresh(String token)`
    - `void logout(String token)`
    - `static String hash(String value)`
    - `record IssuedRefresh(String token, Instant expiresAt)`
    - `record RefreshResult(String accessToken, UserResponse user, IssuedRefresh rotated)` (`rotated` é `null` dentro da tolerância)

- [ ] **Step 1: Remove the temporary header log**

Em `LoginRateLimitFilter.doFilterInternal`, trocar o bloco que começa em `// TEMPORARY (roadmap 3.2 probe)` e termina em `headerForLog(request, "Forwarded"));` por:

```java
        log.warn("Too many attempts on {} from {}", path, ip);
```

e apagar o método `headerForLog` inteiro (o Javadoc `/** A header value safe for one log line ... */` e o corpo).

- [ ] **Step 2: Write the failing config test**

`backend/src/test/java/com/ticketflow/auth/AuthConfigTest.java`:

```java
package com.ticketflow.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Session lifetimes come from application.yml, so they are checked as the real app loads them. */
class AuthConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void accessTokensAreShortAndSessionsLastAWeek() {
        runner.run(context -> {
            Binder binder = Binder.get(context.getEnvironment());
            assertThat(binder.bind("app.jwt.ttl", Duration.class).get()).isEqualTo(Duration.ofMinutes(15));
            AuthProperties auth = binder.bind("app.auth", AuthProperties.class).get();
            assertThat(auth.refreshTtl()).isEqualTo(Duration.ofDays(7));
            assertThat(auth.handoffTtl()).isEqualTo(Duration.ofSeconds(60));
            assertThat(auth.reuseGrace()).isEqualTo(Duration.ofSeconds(30));
            assertThat(auth.refreshCookie().secure()).isTrue();
        });
    }
}
```

- [ ] **Step 3: Write the failing service test**

`backend/src/test/java/com/ticketflow/auth/RefreshTokenServiceTest.java`:

```java
package com.ticketflow.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketflow.common.ApiException;
import com.ticketflow.support.IntegrationTest;
import com.ticketflow.user.Role;
import com.ticketflow.user.User;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RefreshTokenServiceTest extends IntegrationTest {

    @Autowired RefreshTokenService refreshTokens;

    @Test
    void storesOnlyHashesNeverTheSecrets() {
        User ana = createUser("Ana", Role.REQUESTER);
        String code = refreshTokens.createHandoff(ana);
        RefreshTokenService.IssuedRefresh issued = refreshTokens.redeemHandoff(code);

        assertThat(code).hasSize(43); // 32 bytes in Base64 URL, no padding
        assertThat(jdbc.queryForList("SELECT code_hash FROM session_handoffs", String.class)).doesNotContain(code);
        assertThat(jdbc.queryForList("SELECT token_hash FROM refresh_tokens", String.class))
                .containsExactly(RefreshTokenService.hash(issued.token()))
                .doesNotContain(issued.token());
        assertThat(RefreshTokenService.hash(issued.token())).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void aSessionCodeWorksOnceAndOnlyForAMinute() {
        User ana = createUser("Ana", Role.REQUESTER);
        String code = refreshTokens.createHandoff(ana);
        refreshTokens.redeemHandoff(code);
        assertThatThrownBy(() -> refreshTokens.redeemHandoff(code)).isInstanceOf(ApiException.class);

        String late = refreshTokens.createHandoff(ana);
        clock.advance(Duration.ofSeconds(61));
        assertThatThrownBy(() -> refreshTokens.redeemHandoff(late)).isInstanceOf(ApiException.class);
    }

    @Test
    void theSessionLastsSevenDaysFromLoginEvenWhenRotated() {
        User ana = createUser("Ana", Role.REQUESTER);
        RefreshTokenService.IssuedRefresh first = refreshTokens.redeemHandoff(refreshTokens.createHandoff(ana));
        clock.advance(Duration.ofDays(3));
        RefreshTokenService.IssuedRefresh second = refreshTokens.refresh(first.token()).orElseThrow().rotated();

        assertThat(second.expiresAt()).isEqualTo(first.expiresAt());
        clock.advance(Duration.ofDays(4).plusSeconds(1));
        assertThat(refreshTokens.refresh(second.token())).isEmpty();
    }
}
```

- [ ] **Step 4: Run the tests to verify they fail**

Run: `./mvnw -q test -Dtest='AuthConfigTest,RefreshTokenServiceTest'`
Expected: FAIL na compilação (`cannot find symbol: class AuthProperties`, `RefreshTokenService`).

- [ ] **Step 5: Write the migration**

`backend/src/main/resources/db/migration/V10__create_refresh_tokens.sql`:

```sql
-- Sessions that outlive the 15-minute access token. Only SHA-256 hashes are stored: a leaked table gives no
-- session away. Deleting a user (the demo reset truncates users) takes their sessions along.
CREATE TABLE refresh_tokens (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    family_id   UUID        NOT NULL,
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    revoked_at  TIMESTAMPTZ
);
CREATE INDEX refresh_tokens_family_idx ON refresh_tokens (family_id);
CREATE INDEX refresh_tokens_user_idx ON refresh_tokens (user_id);

-- One-time codes that carry a fresh login from the API's domain to the site's, where the cookie lives.
CREATE TABLE session_handoffs (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    code_hash   VARCHAR(64) NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX session_handoffs_user_idx ON session_handoffs (user_id);
```

- [ ] **Step 6: Write `AuthProperties` and the configuration**

`backend/src/main/java/com/ticketflow/auth/AuthProperties.java`:

```java
package com.ticketflow.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Session lifetimes and the refresh cookie, read from app.auth in application.yml. */
@ConfigurationProperties("app.auth")
public record AuthProperties(Duration refreshTtl, Duration handoffTtl, Duration reuseGrace, RefreshCookie refreshCookie) {

    /** {@code secure: false} only for plain http outside localhost-aware browsers (Safari on http://localhost). */
    public record RefreshCookie(boolean secure) {
    }
}
```

Em `application.yml`, no bloco `app.jwt`, trocar `ttl: 8h` por `ttl: 15m`, e logo depois do bloco `jwt:` (mesma indentação de `jwt:`):

```yaml
  auth:
    # The refresh token keeps the session for a week; each use replaces it (rotation) without extending it.
    refresh-ttl: 7d
    # One-time code that carries a login from the API's domain to the site's, where the cookie lives.
    handoff-ttl: 60s
    # Two tabs refreshing with the same cookie at the same time must not look like a stolen token.
    reuse-grace: 30s
    refresh-cookie:
      secure: true
```

- [ ] **Step 7: Write the entities and repositories**

`RefreshToken.java`:

```java
package com.ticketflow.auth;

import com.ticketflow.user.User;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One refresh token of a session. A family is every token rotated from the same login. */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    private UUID familyId;

    private String tokenHash;

    private Instant createdAt;

    private Instant expiresAt;

    private Instant usedAt;

    private Instant revokedAt;

    protected RefreshToken() {
        // required by JPA
    }

    RefreshToken(User user, UUID familyId, String tokenHash, Instant createdAt, Instant expiresAt) {
        this.user = user;
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    void markUsed(Instant at) {
        this.usedAt = at;
    }

    User getUser() {
        return user;
    }

    UUID getFamilyId() {
        return familyId;
    }

    Instant getExpiresAt() {
        return expiresAt;
    }

    Instant getUsedAt() {
        return usedAt;
    }

    Instant getRevokedAt() {
        return revokedAt;
    }
}
```

`RefreshTokenRepository.java`:

```java
package com.ticketflow.auth;

import com.ticketflow.user.User;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /** Locks the row: two refreshes of the same token wait for each other instead of both rotating it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("UPDATE RefreshToken t SET t.revokedAt = :at WHERE t.familyId = :familyId AND t.revokedAt IS NULL")
    void revokeFamily(UUID familyId, Instant at);

    @Modifying
    @Query("DELETE FROM RefreshToken t WHERE t.user = :user AND t.expiresAt < :now")
    void deleteExpired(User user, Instant now);
}
```

`SessionHandoff.java`:

```java
package com.ticketflow.auth;

import com.ticketflow.user.User;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/** A one-time code that turns a fresh login into a refresh cookie on the site's own domain. */
@Entity
@Table(name = "session_handoffs")
public class SessionHandoff {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    private String codeHash;

    private Instant expiresAt;

    protected SessionHandoff() {
        // required by JPA
    }

    SessionHandoff(User user, String codeHash, Instant expiresAt) {
        this.user = user;
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
    }

    User getUser() {
        return user;
    }

    Instant getExpiresAt() {
        return expiresAt;
    }
}
```

`SessionHandoffRepository.java`:

```java
package com.ticketflow.auth;

import com.ticketflow.user.User;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface SessionHandoffRepository extends JpaRepository<SessionHandoff, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<SessionHandoff> findByCodeHash(String codeHash);

    @Modifying
    @Query("DELETE FROM SessionHandoff h WHERE h.user = :user AND h.expiresAt < :now")
    void deleteExpired(User user, Instant now);
}
```

- [ ] **Step 8: Write `RefreshTokenService`**

```java
package com.ticketflow.auth;

import com.ticketflow.common.ApiException;
import com.ticketflow.user.User;
import com.ticketflow.user.UserResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Long sessions behind the short access token. Every secret is 256 random bits and only its SHA-256 is stored.
 * A refresh token works once: using it gives a successor in the same family (same expiry). Using it again
 * means two parties hold it, so the whole family is revoked, except within a short grace for two tabs that
 * refreshed at the same moment.
 */
@Service
public class RefreshTokenService {

    public record IssuedRefresh(String token, Instant expiresAt) {
    }

    /** {@code rotated} is null within the reuse grace: the other tab already got the new cookie. */
    public record RefreshResult(String accessToken, UserResponse user, IssuedRefresh rotated) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository tokens;
    private final SessionHandoffRepository handoffs;
    private final TokenService tokenService;
    private final AuthProperties properties;
    private final Clock clock;

    public RefreshTokenService(RefreshTokenRepository tokens, SessionHandoffRepository handoffs,
            TokenService tokenService, AuthProperties properties, Clock clock) {
        this.tokens = tokens;
        this.handoffs = handoffs;
        this.tokenService = tokenService;
        this.properties = properties;
        this.clock = clock;
    }

    /** A code for POST /api/auth/session; it also drops this user's expired codes and sessions. */
    @Transactional
    public String createHandoff(User user) {
        Instant now = clock.instant();
        handoffs.deleteExpired(user, now);
        tokens.deleteExpired(user, now);
        String code = newSecret();
        handoffs.save(new SessionHandoff(user, hash(code), now.plus(properties.handoffTtl())));
        return code;
    }

    /** Spends the code and starts a new session family. */
    @Transactional
    public IssuedRefresh redeemHandoff(String code) {
        Instant now = clock.instant();
        SessionHandoff handoff = handoffs.findByCodeHash(hash(code))
                .filter(found -> found.getExpiresAt().isAfter(now))
                .filter(found -> found.getUser().isActive())
                .orElseThrow(() -> ApiException.unauthorized("Código de sessão inválido ou vencido."));
        handoffs.delete(handoff);
        return issue(handoff.getUser(), UUID.randomUUID(), now, now.plus(properties.refreshTtl()));
    }

    /**
     * Empty means "no session": the caller answers 401 and clears the cookie. It never throws for that, so a
     * family revoked here is committed (an exception would roll the revocation back).
     */
    @Transactional
    public Optional<RefreshResult> refresh(String token) {
        Instant now = clock.instant();
        Optional<RefreshToken> found = tokens.findByTokenHash(hash(token));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        RefreshToken current = found.get();
        if (current.getRevokedAt() != null || !current.getExpiresAt().isAfter(now)) {
            return Optional.empty();
        }
        User user = current.getUser();
        if (!user.isActive()) {
            tokens.revokeFamily(current.getFamilyId(), now);
            return Optional.empty();
        }
        if (current.getUsedAt() != null) {
            if (now.isAfter(current.getUsedAt().plus(properties.reuseGrace()))) {
                tokens.revokeFamily(current.getFamilyId(), now);
                return Optional.empty();
            }
            return Optional.of(new RefreshResult(tokenService.issue(user), UserResponse.from(user), null));
        }
        current.markUsed(now);
        IssuedRefresh successor = issue(user, current.getFamilyId(), now, current.getExpiresAt());
        return Optional.of(new RefreshResult(tokenService.issue(user), UserResponse.from(user), successor));
    }

    /** Ends the session of this token everywhere. An unknown token is ignored: logging out is always safe. */
    @Transactional
    public void logout(String token) {
        tokens.findByTokenHash(hash(token)).ifPresent(found -> tokens.revokeFamily(found.getFamilyId(), clock.instant()));
    }

    private IssuedRefresh issue(User user, UUID familyId, Instant now, Instant expiresAt) {
        String value = newSecret();
        tokens.save(new RefreshToken(user, familyId, hash(value), now, expiresAt));
        return new IssuedRefresh(value, expiresAt);
    }

    static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available in the JDK", e);
        }
    }

    private static String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
```

`UserResponse` está em `com.ticketflow.user` (é o que `AuthService` já usa); confira o import com `grep -rn "record UserResponse" backend/src/main/java`.

- [ ] **Step 9: Run the tests to verify they pass**

Run: `./mvnw -q test -Dtest='AuthConfigTest,RefreshTokenServiceTest,LoginRateLimitApiTest'`
Expected: PASS (1 + 3 + 9). Se o Flyway ou o `ddl-auto: validate` reclamar de tipo de coluna, compare a migration com as entidades antes de mexer em qualquer outra coisa.

- [ ] **Step 10: Run the whole backend suite and commit**

Run: `./mvnw -q test` e contar pelos XML: 0 falhas, 0 erros.

```bash
git add backend/src/main/java/com/ticketflow/common/LoginRateLimitFilter.java backend/src/main/resources/db/migration/V10__create_refresh_tokens.sql backend/src/main/java/com/ticketflow/auth backend/src/main/resources/application.yml backend/src/test/java/com/ticketflow/auth/RefreshTokenServiceTest.java backend/src/test/java/com/ticketflow/auth/AuthConfigTest.java
git commit -m "feat: store rotating refresh tokens and one-time session codes" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

(O `app.jwt.ttl: 15m` entra neste commit junto com o bloco `app.auth`, porque o `AuthConfigTest` cobre os dois.)

---

### Task 2: Rotas `/session`, `/refresh`, `/logout` e `sessionCode` no login

**Files:**
- Create: `backend/src/main/java/com/ticketflow/auth/RefreshCookie.java`
- Modify: `backend/src/main/java/com/ticketflow/auth/AuthDtos.java`
- Modify: `backend/src/main/java/com/ticketflow/auth/AuthService.java`
- Modify: `backend/src/main/java/com/ticketflow/auth/AuthController.java`
- Modify: `backend/src/main/java/com/ticketflow/common/SecurityConfig.java` (linha do `permitAll`)
- Test: `backend/src/test/java/com/ticketflow/auth/RefreshTokenApiTest.java`

**Interfaces:**
- Consumes: `RefreshTokenService` e seus records (Task 1); `AuthProperties.refreshCookie().secure()`.
- Produces:
  - `AuthResponse(String token, UserResponse user, String sessionCode)` (`sessionCode` nulo no refresh)
  - `record SessionRequest(@NotBlank String code)`
  - `RefreshCookie.NAME = "tf_refresh"`, `String issue(IssuedRefresh)` e `String clear()` (valores de `Set-Cookie`)
  - Rotas: `POST /api/auth/session` (204), `POST /api/auth/refresh` (200 `{token, user, sessionCode: null}` ou 401), `POST /api/auth/logout` (204)

- [ ] **Step 1: Write the failing API test**

`backend/src/test/java/com/ticketflow/auth/RefreshTokenApiTest.java`:

```java
package com.ticketflow.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ticketflow.support.IntegrationTest;
import com.ticketflow.user.Role;
import com.ticketflow.user.User;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MvcResult;

class RefreshTokenApiTest extends IntegrationTest {

    @Autowired JwtDecoder jwtDecoder;

    private User ana;

    @BeforeEach
    void setUp() {
        ana = createUser("Ana", Role.REQUESTER);
    }

    private String login() throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email": "ana@test.com", "password": "%s"}
                        """.formatted(PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.sessionCode");
    }

    private MvcResult session(String code) throws Exception {
        return mvc.perform(post("/api/auth/session").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"%s\"}".formatted(code)))
                .andReturn();
    }

    private Cookie loggedInCookie() throws Exception {
        MvcResult result = session(login());
        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        return result.getResponse().getCookie(RefreshCookie.NAME);
    }

    private MvcResult refresh(Cookie cookie) throws Exception {
        return mvc.perform(post("/api/auth/refresh").cookie(cookie)).andReturn();
    }

    private static String setCookie(MvcResult result) {
        return result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
    }

    @Test
    void loginReturnsASessionCodeThatBecomesTheCookie() throws Exception {
        MvcResult result = session(login());

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(setCookie(result))
                .startsWith("tf_refresh=")
                .contains("Path=/api/auth", "Max-Age=604800", "Secure", "HttpOnly", "SameSite=Strict");
    }

    @Test
    void registerAlsoReturnsASessionCode() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "Bia", "email": "bia@test.com", "password": "password123"}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sessionCode").isString());
    }

    @Test
    void aSessionCodeWorksOnlyOnce() throws Exception {
        String code = login();
        session(code);

        MvcResult again = session(code);

        assertThat(again.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void refreshReturnsANewAccessTokenAndRotatesTheCookie() throws Exception {
        Cookie cookie = loggedInCookie();

        MvcResult result = refresh(cookie);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(body, "$.user.name")).isEqualTo("Ana");
        String token = JsonPath.read(body, "$.token");
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        Cookie rotated = result.getResponse().getCookie(RefreshCookie.NAME);
        assertThat(rotated.getValue()).isNotEqualTo(cookie.getValue());
        assertThat(refresh(rotated).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void theAccessTokenLastsFifteenMinutes() throws Exception {
        String token = JsonPath.read(refresh(loggedInCookie()).getResponse().getContentAsString(), "$.token");

        // The decoder checks expiry against the real clock, so the lifetime is read from the claims.
        Jwt jwt = jwtDecoder.decode(token);
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void refreshWithoutACookieOrWithAnUnknownOneIsRejectedAndClearsTheCookie() throws Exception {
        MvcResult missing = mvc.perform(post("/api/auth/refresh")).andReturn();
        MvcResult unknown = refresh(new Cookie(RefreshCookie.NAME, "not-a-session"));

        for (MvcResult result : new MvcResult[] {missing, unknown}) {
            assertThat(result.getResponse().getStatus()).isEqualTo(401);
            assertThat(result.getResponse().getContentAsString()).contains("Sessão expirada. Entre novamente.");
            assertThat(setCookie(result)).startsWith("tf_refresh=").contains("Max-Age=0");
        }
    }

    @Test
    void twoTabsRefreshingTogetherKeepTheSession() throws Exception {
        Cookie cookie = loggedInCookie();
        refresh(cookie);
        clock.advance(Duration.ofSeconds(10));

        MvcResult sameCookieAgain = refresh(cookie);

        assertThat(sameCookieAgain.getResponse().getStatus()).isEqualTo(200);
        assertThat(setCookie(sameCookieAgain)).isNull();
    }

    @Test
    void reusingATokenAfterTheGraceRevokesTheWholeFamily() throws Exception {
        Cookie stolen = loggedInCookie();
        Cookie rotated = refresh(stolen).getResponse().getCookie(RefreshCookie.NAME);
        clock.advance(Duration.ofSeconds(31));

        assertThat(refresh(stolen).getResponse().getStatus()).isEqualTo(401);
        assertThat(refresh(rotated).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void theSessionEndsSevenDaysAfterLogin() throws Exception {
        Cookie cookie = loggedInCookie();
        clock.advance(Duration.ofDays(7).plusSeconds(1));

        assertThat(refresh(cookie).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void aDeactivatedUserCannotRefresh() throws Exception {
        Cookie cookie = loggedInCookie();
        ana.setActive(false);
        userRepository.save(ana);

        assertThat(refresh(cookie).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void aNewRoleComesInTheNextAccessToken() throws Exception {
        Cookie cookie = loggedInCookie();
        ana.setRole(Role.AGENT);
        userRepository.save(ana);

        MvcResult result = refresh(cookie);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String token = JsonPath.read(result.getResponse().getContentAsString(), "$.token");
        assertThat(jwtDecoder.decode(token).getClaimAsString("role")).isEqualTo("AGENT");
    }

    @Test
    void logoutEndsTheSessionAndClearsTheCookie() throws Exception {
        Cookie cookie = loggedInCookie();

        MvcResult result = mvc.perform(post("/api/auth/logout").cookie(cookie)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(setCookie(result)).contains("Max-Age=0");
        assertThat(refresh(cookie).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void logoutWithoutASessionIsFine() throws Exception {
        mvc.perform(post("/api/auth/logout")).andExpect(status().isNoContent());
    }
}
```

O `setActive(boolean)` e o `setRole(Role)` já existem em `User`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q test -Dtest=RefreshTokenApiTest`
Expected: FAIL. `loginReturnsASessionCodeThatBecomesTheCookie` falha porque `$.sessionCode` não existe (`PathNotFoundException`); as rotas novas respondem 401 do Spring Security (não estão no `permitAll`).

- [ ] **Step 3: Add `sessionCode` and `SessionRequest` to `AuthDtos`**

Trocar o `record AuthResponse` por:

```java
    /** {@code sessionCode} is set on login and sign-up only: exchange it at POST /api/auth/session. */
    public record AuthResponse(String token, UserResponse user, String sessionCode) {
    }

    public record SessionRequest(@NotBlank(message = "Informe o código.") String code) {
    }
```

- [ ] **Step 4: Issue the code on login and sign-up**

Em `AuthService`:
- acrescentar `RefreshTokenService refreshTokens` ao construtor e a um campo;
- em `register`, trocar o retorno por `return new AuthResponse(tokenService.issue(user), UserResponse.from(user), refreshTokens.createHandoff(user));`;
- em `login`, trocar `@Transactional(readOnly = true)` por `@Transactional` (agora grava o código) e o retorno por `return new AuthResponse(tokenService.issue(user), UserResponse.from(user), refreshTokens.createHandoff(user));`.

- [ ] **Step 5: Write `RefreshCookie`**

```java
package com.ticketflow.auth;

import java.time.Clock;
import java.time.Duration;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * The refresh token cookie. HttpOnly keeps it away from page scripts; SameSite=Strict keeps other sites from
 * using it; Path=/api/auth sends it only to the routes that read it.
 */
@Component
public class RefreshCookie {

    public static final String NAME = "tf_refresh";

    private final AuthProperties properties;
    private final Clock clock;

    public RefreshCookie(AuthProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** The Set-Cookie value for a session that ends at {@code refresh.expiresAt()}. */
    public String issue(RefreshTokenService.IssuedRefresh refresh) {
        return base(refresh.token()).maxAge(Duration.between(clock.instant(), refresh.expiresAt())).build().toString();
    }

    /** The Set-Cookie value that deletes the cookie. */
    public String clear() {
        return base("").maxAge(0).build().toString();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(properties.refreshCookie().secure())
                .sameSite("Strict")
                .path("/api/auth");
    }
}
```

- [ ] **Step 6: Add the routes to `AuthController`**

Acrescentar ao construtor `RefreshTokenService refreshTokens` e `RefreshCookie refreshCookie` (com campos), e os métodos:

```java
    /** Called through the site (same origin), so the cookie lands on the site's domain. */
    @PostMapping("/session")
    public ResponseEntity<Void> session(@Valid @RequestBody SessionRequest request) {
        RefreshTokenService.IssuedRefresh refresh = refreshTokens.redeemHandoff(request.code());
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, refreshCookie.issue(refresh)).build();
    }

    @PostMapping("/refresh")
    public ResponseEntity<Object> refresh(@CookieValue(name = RefreshCookie.NAME, required = false) String token) {
        Optional<RefreshTokenService.RefreshResult> result = token == null || token.isBlank()
                ? Optional.empty()
                : refreshTokens.refresh(token);
        if (result.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                    "Sessão expirada. Entre novamente.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.SET_COOKIE, refreshCookie.clear())
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(problem);
        }
        RefreshTokenService.RefreshResult session = result.get();
        ResponseEntity.BodyBuilder ok = ResponseEntity.ok();
        if (session.rotated() != null) {
            ok.header(HttpHeaders.SET_COOKIE, refreshCookie.issue(session.rotated()));
        }
        return ok.body(new AuthResponse(session.accessToken(), session.user(), null));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = RefreshCookie.NAME, required = false) String token) {
        if (token != null && !token.isBlank()) {
            refreshTokens.logout(token);
        }
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, refreshCookie.clear()).build();
    }
```

Imports novos: `com.ticketflow.auth.AuthDtos.SessionRequest`, `java.util.Optional`, `org.springframework.http.HttpHeaders`, `org.springframework.http.MediaType`, `org.springframework.http.ProblemDetail`, `org.springframework.http.ResponseEntity`, `org.springframework.web.bind.annotation.CookieValue`.

- [ ] **Step 7: Open the routes in `SecurityConfig`**

Trocar:

```java
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
```

por:

```java
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login", "/api/auth/session",
                                "/api/auth/refresh", "/api/auth/logout").permitAll()
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./mvnw -q test -Dtest='RefreshTokenApiTest,AuthApiTest,RefreshTokenServiceTest'`
Expected: PASS. Se `getCookie(RefreshCookie.NAME)` vier nulo, o `MockHttpServletResponse` não converteu o header `Set-Cookie` em cookie: leia o valor de `setCookie(result)` (o texto até o primeiro `;`, depois de `tf_refresh=`) num helper e crie o `Cookie` a partir dele.

- [ ] **Step 9: Run the whole backend suite and commit**

Run: `./mvnw -q test` (0 falhas, 0 erros pelos XML).

```bash
git add backend/src/main/java/com/ticketflow/auth backend/src/main/java/com/ticketflow/common/SecurityConfig.java backend/src/test/java/com/ticketflow/auth/RefreshTokenApiTest.java
git commit -m "feat: keep sessions in a rotating HttpOnly refresh cookie" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Repasse de `/api` no desenvolvimento local

**Files:**
- Modify: `frontend/vite.config.ts` (bloco `server`)
- Modify: `frontend/nginx.conf`
- Modify: `docker-compose.yml` (environment do `backend`)
- Modify: `render.yaml` (comentário da regra `/api/*`)

**Interfaces:**
- Produces: em qualquer ambiente, `/api/...` na origem do frontend chega ao backend.

- [ ] **Step 1: Vite proxy**

Em `frontend/vite.config.ts`, dentro de `server`:

```ts
  server: {
    port: 5173,
    strictPort: true,
    // Same as Render's /api rewrite: the refresh cookie routes are called on the site's own origin.
    proxy: { '/api': 'http://localhost:8080' },
  },
```

- [ ] **Step 2: nginx proxy**

Em `frontend/nginx.conf`, antes de `location / {`:

```nginx
    # Same as Render's /api rewrite: the refresh cookie routes are called on the site's own origin.
    location /api/ {
        proxy_pass http://backend:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }

```

- [ ] **Step 3: docker compose and render.yaml**

Em `docker-compose.yml`, no `environment` do `backend`, depois de `SPRING_PROFILES_ACTIVE: demo`:

```yaml
      # Plain http on localhost: Safari drops Secure cookies there (Chrome and Firefox accept them).
      APP_AUTH_REFRESHCOOKIE_SECURE: "false"
```

Em `render.yaml`, trocar as duas linhas de comentário acima da regra `/api/*` por:

```yaml
      # The site forwards /api/* to the API so the refresh cookie lives on the site's domain (onrender.com is
      # on the Public Suffix List: site and API are different sites). Only the cookie routes use it; the rest
      # goes straight to the API (VITE_API_URL), which keeps the visitor's IP for the login rate limit.
```

- [ ] **Step 4: Verify the builds**

Run (em `frontend/`): `npm run build`
Expected: build verde. Depois, na raiz: `docker compose config --quiet` (sem erro) e `docker compose up -d --build` seguido de `curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:5173/api/auth/refresh`
Expected: `401` (a rota chega ao backend pelo nginx).

- [ ] **Step 5: Commit**

```bash
git add frontend/vite.config.ts frontend/nginx.conf docker-compose.yml render.yaml
git commit -m "chore: forward /api to the backend in local development" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Cliente da API com token em memória e renovação

**Files:**
- Modify: `frontend/src/api/client.ts`
- Modify: `frontend/src/api/types.ts` (`AuthResponse`)
- Modify: `frontend/src/test/render.tsx` (`requestKey`, `mockApi`)
- Modify: `frontend/src/test/setup.ts`
- Test: `frontend/src/api/client.test.ts`

**Interfaces:**
- Consumes: as rotas da Task 2.
- Produces:
  - `tokenStorage.get/set/clear` (mesma forma de hoje, agora em memória)
  - `refreshSession(): Promise<AuthResponse | null>`
  - `api.post` devolve `undefined` para 204
  - `AuthResponse.sessionCode?: string`
  - `mockApi` responde por padrão `POST /auth/refresh` → 401, `POST /auth/session` → 204, `POST /auth/logout` → 204

- [ ] **Step 1: Write the failing tests**

Em `frontend/src/api/client.test.ts`, trocar o import da linha 2 por:

```ts
import { api, ApiError, refreshSession, setUnauthorizedHandler, tokenStorage } from './client'
```

e acrescentar no fim do `describe('api client', ...)`:

```ts
  it('calls the cookie routes on the site itself, never with the access token', async () => {
    tokenStorage.set('abc.def.ghi')
    const fetchMock = mockFetch(new Response(null, { status: 204 }))

    const result = await api.post('/auth/logout')

    expect(result).toBeUndefined()
    expect(fetchMock.mock.calls[0][0]).toBe('/api/auth/logout')
    expect(new Headers(fetchMock.mock.calls[0][1]?.headers).get('Authorization')).toBeNull()
  })

  it('renews an expired access token once and repeats the request', async () => {
    tokenStorage.set('old')
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse(401, { status: 401 }))
      .mockResolvedValueOnce(jsonResponse(200, { token: 'new', user: { id: 1 } }))
      .mockResolvedValueOnce(jsonResponse(200, [{ id: 7 }]))
    vi.stubGlobal('fetch', fetchMock)

    const result = await api.get('/tickets')

    expect(result).toEqual([{ id: 7 }])
    expect(fetchMock.mock.calls[1][0]).toBe('/api/auth/refresh')
    expect(new Headers(fetchMock.mock.calls[2][1]?.headers).get('Authorization')).toBe('Bearer new')
    expect(tokenStorage.get()).toBe('new')
  })

  it('renews only once when many requests fail together', async () => {
    tokenStorage.set('old')
    const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
      if (String(input) === '/api/auth/refresh') {
        return jsonResponse(200, { token: 'new', user: { id: 1 } })
      }
      const auth = new Headers(init?.headers).get('Authorization')
      return auth === 'Bearer new' ? jsonResponse(200, {}) : jsonResponse(401, { status: 401 })
    })
    vi.stubGlobal('fetch', fetchMock)

    await Promise.all([api.get('/tickets'), api.get('/categories'), api.get('/users')])

    expect(fetchMock.mock.calls.filter(([input]) => String(input) === '/api/auth/refresh')).toHaveLength(1)
  })

  it('ends the session when the renewal is refused', async () => {
    tokenStorage.set('old')
    const onUnauthorized = vi.fn()
    setUnauthorizedHandler(onUnauthorized)
    vi.stubGlobal('fetch', vi.fn<typeof fetch>(async () => jsonResponse(401, { status: 401 })))

    await expect(api.get('/tickets')).rejects.toMatchObject({ status: 401 })

    expect(onUnauthorized).toHaveBeenCalledOnce()
    expect(tokenStorage.get()).toBeNull()
  })

  it('treats an unreachable server as no session', async () => {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockRejectedValue(new TypeError('Failed to fetch')))

    expect(await refreshSession()).toBeNull()
  })

  it('forgets the token an older version kept in localStorage', async () => {
    localStorage.setItem('ticketflow.token', 'from-last-week')
    vi.resetModules()

    await import('./client')

    expect(localStorage.getItem('ticketflow.token')).toBeNull()
  })
```

No mesmo arquivo, o teste existente `drops the session when a request WITH a token gets 401 (expired token, demo reset)` continua válido (o refresh também recebe 401). Se ele conferir o número de chamadas do `fetch`, ajuste para 2 (a chamada e o refresh).

- [ ] **Step 2: Run the tests to verify they fail**

Run (em `frontend/`): `npx vitest run src/api/client.test.ts`
Expected: FAIL. `refreshSession` não existe; `/auth/logout` vai para `http://localhost:8080/api/auth/logout`; 204 quebra no `response.json()`.

- [ ] **Step 3: Change the client**

Em `frontend/src/api/client.ts`:

1. Trocar `export const TOKEN_KEY = 'ticketflow.token'` por:

```ts
// Older versions kept the token in localStorage (readable by any script on the page): drop it.
try {
  localStorage.removeItem('ticketflow.token')
} catch {
  // storage blocked (private mode): nothing was stored either
}
```

2. Trocar o bloco `tokenStorage` por:

```ts
/**
 * The access token lives only in memory: it lasts 15 minutes and a page script cannot carry a long session
 * away. The session itself is the HttpOnly refresh cookie, which no script can read.
 */
let accessToken: string | null = null
export const tokenStorage = {
  get: (): string | null => accessToken,
  set: (token: string) => {
    accessToken = token
  },
  clear: () => {
    accessToken = null
  },
}

/**
 * Routes that read or write the refresh cookie. They are called on the site's own origin (Render forwards
 * /api to the API) so the cookie belongs to the site; everything else goes straight to the API.
 */
const COOKIE_PATHS = ['/auth/session', '/auth/refresh', '/auth/logout']
```

3. Trocar `const PUBLIC_PATHS = ['/auth/login', '/auth/register']` por:

```ts
const PUBLIC_PATHS = ['/auth/login', '/auth/register', ...COOKIE_PATHS]

function urlFor(path: string): string {
  return COOKIE_PATHS.includes(path) ? `/api${path}` : `${API_URL}/api${path}`
}
```

4. Em `send`, acrescentar o parâmetro `retried = false` depois de `extraHeaders`, trocar `` fetch(`${API_URL}/api${path}`, ...) `` por `fetch(urlFor(path), ...)` e trocar o bloco do 401 por:

```ts
  // 401 with a token: the 15-minute access token expired (or the demo reset deleted the user). Renew it once
  // from the refresh cookie and repeat the request; if that fails too, the session is over.
  // 401 without a token is just a wrong password on the login form.
  if (response.status === 401 && token) {
    if (!retried && (await refreshSession()) !== null) {
      return send(method, path, body, extraHeaders, true)
    }
    tokenStorage.clear()
    onUnauthorized()
  }
```

5. Trocar `json` por:

```ts
async function json<T>(method: string, path: string, body?: unknown, headers?: Record<string, string>): Promise<T> {
  const response = await send(method, path, body, headers)
  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}
```

6. Acrescentar, depois de `setUnauthorizedHandler`:

```ts
let refreshing: Promise<AuthResponse | null> | null = null

/**
 * Gets a new access token from the refresh cookie. Requests that fail together share one renewal, and tabs
 * take turns (Web Locks): two renewals with the same cookie would look like a stolen token to the API.
 * Null means there is no session (no cookie, expired, or the server could not be reached).
 */
export function refreshSession(): Promise<AuthResponse | null> {
  refreshing ??= withRefreshLock(renew).finally(() => {
    refreshing = null
  })
  return refreshing
}

function withRefreshLock<T>(task: () => Promise<T>): Promise<T> {
  return 'locks' in navigator ? navigator.locks.request('ticketflow-refresh', task) : task()
}

async function renew(): Promise<AuthResponse | null> {
  let response: Response
  try {
    response = await fetch(urlFor('/auth/refresh'), { method: 'POST' })
  } catch {
    return null
  }
  if (!response.ok) {
    tokenStorage.clear()
    return null
  }
  const session = (await response.json()) as AuthResponse
  tokenStorage.set(session.token)
  return session
}
```

e o import `import type { AuthResponse } from './types'` no topo.

Em `frontend/src/api/types.ts`:

```ts
export interface AuthResponse {
  token: string
  user: User
  /** Login and sign-up only: exchanged at POST /auth/session for the refresh cookie. */
  sessionCode?: string | null
}
```

- [ ] **Step 4: Update the test helpers**

Em `frontend/src/test/render.tsx`:

```ts
export function jsonResponse(status: number, body: unknown): Response {
  if (status === 204) {
    return new Response(null, { status })
  }
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}
```

```ts
function requestKey(input: RequestInfo | URL, init?: RequestInit): string {
  // Cookie routes use a relative URL (the site's own origin).
  const path = new URL(String(input), 'http://localhost').pathname.replace(/^\/api/, '')
  return `${init?.method ?? 'GET'} ${path}`
}

/** What every screen meets on its own: no refresh cookie, and cookie routes that just work. */
const DEFAULT_REPLIES: Record<string, Reply> = {
  'POST /auth/refresh': [401, { status: 401, detail: 'Sessão expirada. Entre novamente.' }],
  'POST /auth/session': [204, null],
  'POST /auth/logout': [204, null],
}
```

e, dentro de `mockApi`, trocar `const reply = replies[requestKey(input, init)]` por `const reply = replies[requestKey(input, init)] ?? DEFAULT_REPLIES[requestKey(input, init)]`.

Em `frontend/src/test/setup.ts`, acrescentar o import `import { tokenStorage } from '../api/client'` e, no `afterEach`, `tokenStorage.clear()` depois de `localStorage.clear()`.

- [ ] **Step 5: Run the client tests**

Run: `npx vitest run src/api/client.test.ts`
Expected: PASS em todos.

- [ ] **Step 6: Run the whole frontend checks**

Run: `npx vitest run`, `npx tsc --noEmit -p tsconfig.app.json`, `npm run lint`
Expected: `AuthProvider.tsx` e `AuthProvider.test.tsx` ainda importam `TOKEN_KEY`, que não existe mais: o `tsc` falha neles. Para manter este commit compilando, em `AuthProvider.tsx` troque `TOKEN_KEY` pela string `'ticketflow.token'` no `onStorage` (o bloco inteiro sai na Task 5) e, em `AuthProvider.test.tsx`, defina `const TOKEN_KEY = 'ticketflow.token'` no topo do arquivo no lugar do import. Rode de novo: tudo verde, exceto os dois testes de abas do `AuthProvider.test.tsx`, que podem falhar porque o token não está mais no `localStorage` — marque-os com `it.skip` e um comentário `// rewritten in Task 5` se falharem.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/api frontend/src/test frontend/src/auth/AuthProvider.tsx frontend/src/auth/AuthProvider.test.tsx
git commit -m "feat: keep the access token in memory and renew it from the refresh cookie" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: `AuthProvider` com sessão restaurada, troca do código e abas sincronizadas

**Files:**
- Modify: `frontend/src/auth/AuthProvider.tsx`
- Modify: `frontend/src/auth/authContext.ts` (comentário do `loading`)
- Test: `frontend/src/auth/AuthProvider.test.tsx`
- Test: `frontend/src/auth/LoginPage.test.tsx`

**Interfaces:**
- Consumes: `tokenStorage`, `refreshSession`, `api.post('/auth/session', { code })`, `api.post('/auth/logout')` (Task 4).
- Produces: `AuthContextValue` com a mesma forma (`user`, `loading`, `login(response)`, `logout()`).

- [ ] **Step 1: Rewrite the provider tests (failing)**

Substituir `frontend/src/auth/AuthProvider.test.tsx` por:

```tsx
import { screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { tokenStorage } from '../api/client'
import { mockApi, renderRoutes } from '../test/render'
import { useAuth } from './authContext'
import { RequireAuth } from './guards'

const ana = { id: 1, name: 'Ana', email: 'ana@x.com', role: 'REQUESTER', active: true, demo: true }
const bruno = { id: 2, name: 'Bruno', email: 'bruno@x.com', role: 'AGENT', active: true, demo: true }

function WhoAmI() {
  const { user, logout } = useAuth()
  return (
    <>
      <p>Logado como {user?.name}</p>
      <button onClick={logout}>Sair</button>
    </>
  )
}

const routes = [
  { element: <RequireAuth />, children: [{ path: '/tickets', element: <WhoAmI /> }] },
  { path: '/login', element: <p>Tela de login</p> },
]

/** What another tab of the app sends on the shared channel. */
function anotherTabSays(type: 'login' | 'logout') {
  const channel = new BroadcastChannel('ticketflow-auth')
  channel.postMessage({ type })
  channel.close()
}

afterEach(() => vi.unstubAllGlobals())

describe('AuthProvider', () => {
  it('restores the session from the refresh cookie when the page opens', async () => {
    mockApi({ 'POST /auth/refresh': [200, { token: 'token-ana', user: ana }] })

    renderRoutes(routes, '/tickets')

    expect(await screen.findByText('Logado como Ana')).toBeInTheDocument()
    expect(tokenStorage.get()).toBe('token-ana')
  })

  it('shows the login page when there is no session to restore', async () => {
    mockApi({})

    renderRoutes(routes, '/tickets')

    expect(await screen.findByText('Tela de login')).toBeInTheDocument()
  })

  it('ends the session on the server when logging out', async () => {
    const fetchMock = mockApi({ 'POST /auth/refresh': [200, { token: 'token-ana', user: ana }] })
    const { user } = renderRoutes(routes, '/tickets')
    await user.click(await screen.findByRole('button', { name: 'Sair' }))

    expect(await screen.findByText('Tela de login')).toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([input]) => String(input) === '/api/auth/logout')).toBe(true)
    expect(tokenStorage.get()).toBeNull()
  })

  it('follows a login with another account in another tab', async () => {
    mockApi({ 'POST /auth/refresh': [200, { token: 'token-ana', user: ana }] })
    renderRoutes(routes, '/tickets')
    expect(await screen.findByText('Logado como Ana')).toBeInTheDocument()

    mockApi({ 'POST /auth/refresh': [200, { token: 'token-bruno', user: bruno }] })
    anotherTabSays('login')

    expect(await screen.findByText('Logado como Bruno')).toBeInTheDocument()
  })

  it('follows a logout in another tab', async () => {
    mockApi({ 'POST /auth/refresh': [200, { token: 'token-ana', user: ana }] })
    renderRoutes(routes, '/tickets')
    expect(await screen.findByText('Logado como Ana')).toBeInTheDocument()

    anotherTabSays('logout')

    expect(await screen.findByText('Tela de login')).toBeInTheDocument()
  })
})
```

Em `frontend/src/auth/LoginPage.test.tsx`, acrescentar o import de `sentBody` (de `'../test/render'`, se ainda não houver) e, no `describe`:

```tsx
  it('saves the session in the refresh cookie right after logging in', async () => {
    const fetchMock = mockApi({
      'POST /auth/login': [200, { token: 'jwt-token', user: manager, sessionCode: 'code-1' }],
    })
    const { user } = renderRoutes(routes, '/login')

    await user.click(screen.getByRole('button', { name: 'Gestor' }))

    expect(await screen.findByText('Lista de chamados')).toBeInTheDocument()
    await vi.waitFor(() => expect(sentBody(fetchMock, 'POST /auth/session')).toEqual({ code: 'code-1' }))
  })
```

(confira no próprio arquivo o nome do botão do gestor e o texto da tela seguinte usados no teste `logs in with a demo account in one click`, e use os mesmos; importe `vi` de `vitest` se faltar).

- [ ] **Step 2: Run the tests to verify they fail**

Run: `npx vitest run src/auth`
Expected: FAIL em `restores the session...` (o provider atual não chama o refresh), nos dois testes de abas e em `saves the session...`.

- [ ] **Step 3: Rewrite the provider**

`frontend/src/auth/AuthProvider.tsx`:

```tsx
import { notifications } from '@mantine/notifications'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { type ReactNode, useCallback, useEffect, useMemo, useState } from 'react'
import { api, refreshSession, setUnauthorizedHandler, tokenStorage } from '../api/client'
import type { AuthResponse, User } from '../api/types'
import { AuthContext, type AuthContextValue } from './authContext'

type AuthMessage = { type: 'login' } | { type: 'logout' }

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient()
  const [token, setToken] = useState(tokenStorage.get)
  // A freshly opened page has no access token in memory: the refresh cookie, if any, brings the session back.
  const [restoring, setRestoring] = useState(() => tokenStorage.get() === null)
  // Tabs no longer share the token, so they tell each other about logins and logouts.
  const [channel] = useState(() =>
    typeof BroadcastChannel === 'undefined' ? null : new BroadcastChannel('ticketflow-auth'),
  )
  useEffect(() => () => channel?.close(), [channel])

  // The token is part of the key: another token is another user.
  const me = useQuery({
    queryKey: ['me', token],
    queryFn: () => api.get<User>('/auth/me'),
    enabled: token !== null,
    staleTime: Infinity,
  })

  const adopt = useCallback(
    (session: AuthResponse) => {
      tokenStorage.set(session.token)
      queryClient.setQueryData(['me', session.token], session.user)
      setToken(session.token)
    },
    [queryClient],
  )

  const restore = useCallback(async () => {
    const session = await refreshSession()
    if (session) {
      adopt(session)
    } else {
      setToken(null)
    }
    setRestoring(false)
  }, [adopt])

  useEffect(() => {
    if (tokenStorage.get() === null) {
      void restore()
    }
  }, [restore])

  const dropSession = useCallback(() => {
    tokenStorage.clear()
    setToken(null)
    queryClient.clear() // no data from this user may be shown to the next one
  }, [queryClient])

  const login = useCallback(
    (response: AuthResponse) => {
      adopt(response)
      if (response.sessionCode) {
        api
          .post('/auth/session', { code: response.sessionCode })
          .then(() => channel?.postMessage({ type: 'login' } satisfies AuthMessage))
          .catch(() =>
            notifications.show({
              color: 'yellow',
              message: 'Não foi possível manter a sessão neste navegador: ao recarregar a página, entre de novo.',
            }),
          )
      }
    },
    [adopt, channel],
  )

  const logout = useCallback(() => {
    dropSession()
    channel?.postMessage({ type: 'logout' } satisfies AuthMessage)
    api.post('/auth/logout').catch(() => {
      // best effort: the cookie is HttpOnly, only the server can end it; without the server it expires anyway
    })
  }, [dropSession, channel])

  useEffect(() => {
    setUnauthorizedHandler(() => {
      dropSession()
      notifications.show({ color: 'yellow', message: 'Sua sessão expirou. Entre novamente.' })
    })
  }, [dropSession])

  // Another tab logged out: follow it. Another tab logged in (maybe as someone else): the shared cookie now
  // holds that session, so renew from it. This tab must never show one user while its requests carry another's.
  useEffect(() => {
    if (!channel) {
      return
    }
    function onMessage(event: MessageEvent<AuthMessage>) {
      queryClient.clear()
      tokenStorage.clear()
      if (event.data.type === 'logout') {
        setToken(null)
      } else {
        setRestoring(true)
        void restore()
      }
    }
    channel.addEventListener('message', onMessage)
    return () => channel.removeEventListener('message', onMessage)
  }, [channel, queryClient, restore])

  const value = useMemo<AuthContextValue>(
    () => ({
      user: token !== null ? (me.data ?? null) : null,
      loading: restoring || (token !== null && me.isPending),
      login,
      logout,
    }),
    [token, me.data, me.isPending, restoring, login, logout],
  )

  return <AuthContext value={value}>{children}</AuthContext>
}
```

Em `frontend/src/auth/authContext.ts`, trocar o comentário de `loading` por `/** True while the session is being restored from the refresh cookie or checked against GET /auth/me. */`.

- [ ] **Step 4: Run the tests**

Run: `npx vitest run src/auth`
Expected: PASS. Se o `BroadcastChannel` não existir no ambiente de teste (o `anotherTabSays` lança `ReferenceError`), o Node do projeto é antigo: confira `node --version` (o projeto usa Node 24, que tem `BroadcastChannel` global).

- [ ] **Step 5: Run the whole frontend checks**

Run: `npx vitest run`, `npx tsc --noEmit -p tsconfig.app.json`, `npm run lint`, `npm run build`
Expected: tudo verde. Se o lint acusar `setState` síncrono dentro de efeito, o único caminho é o `restore()` chamado no efeito de montagem: ele só chama `setToken`/`setRestoring` depois do `await`, o que a regra aceita.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/auth
git commit -m "feat: restore the session from the refresh cookie and keep tabs in sync" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Fechamento

**Files:**
- Modify: `docs/specs/2026-10-01-refresh-token-cookie-design.md` (linha `**Status:**`)

- [ ] **Step 1: Mark the spec as approved**

Trocar `- **Status:** em revisão` por `- **Status:** aprovada`.

- [ ] **Step 2: Run everything**

Backend: `./mvnw -q test` (0 falhas e 0 erros pelos XML). Frontend: `npx vitest run`, `npx tsc --noEmit -p tsconfig.app.json`, `npm run lint`, `npm run build`.

- [ ] **Step 3: Check it in the local stack**

Na raiz: `docker compose up -d --build`. No navegador em `http://localhost:5173`: entrar como Gestor, recarregar a página e continuar logado; abrir uma segunda aba, sair numa e ver a outra ir para o login; no DevTools, `localStorage` sem `ticketflow.token` e o cookie `tf_refresh` com `HttpOnly` em `/api/auth`.

- [ ] **Step 4: Commit**

```bash
git add docs/specs/2026-10-01-refresh-token-cookie-design.md
git commit -m "docs: approve the refresh token cookie spec" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 5: Push and PR only with the user's OK**

Push da branch e PR em português só com autorização. Depois do deploy, conferir na demo: entrar, recarregar e continuar logado (Chrome e Safari do celular); sair numa aba e ver a outra sair; 6 logins diretos seguidos ainda dão 429 no último.
