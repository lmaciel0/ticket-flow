# ticket-flow — Plano 1: API backend

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** entregar a API REST completa do ticket-flow (autenticação, chamados com SLA, comentários, anexos, gestão de usuários, painel e dados de demonstração), testada contra PostgreSQL real e rodando em Docker.

**Architecture:** monólito Spring Boot organizado por funcionalidade (`auth`, `user`, `category`, `sla`, `ticket`, `history`, `comment`, `attachment`, `dashboard`, `demo`, `common`), com controller → service → repository dentro de cada pacote. As regras de domínio ficam na entidade `Ticket` e no `SlaCalculator` (puros, testados sem Spring). As permissões são checadas em dois níveis: `@PreAuthorize` por perfil e checagens no service por objeto. Os relatórios usam SQL direto.

**Tech Stack:** Java 21, Spring Boot 4.1.1 (Web MVC, Data JPA, Security + OAuth2 Resource Server/JWT, Validation, Flyway, Actuator), PostgreSQL 17, springdoc-openapi 3.1.1, JUnit 5 + MockMvc + Testcontainers 2, Docker e GitHub Actions.

**Spec:** [docs/specs/2026-09-28-ticket-flow-mvp-design.md](../specs/2026-09-28-ticket-flow-mvp-design.md)

## Como este plano se encaixa

A spec cobre três subsistemas independentes, então ela vira **três planos**, cada um entregando software funcionando:

1. **Plano 1 — API backend** (este documento).
2. **Plano 2 — Frontend** (Vite + React + TypeScript + Mantine). Consome a API pronta. Será escrito depois que o Plano 1 estiver implementado, usando o contrato real (Swagger).
3. **Plano 3 — Deploy e acabamento** (Render + Neon, CORS de produção, README final com link, contas demo e trade-offs).

Todo o código deste plano foi **compilado e testado antes de ser escrito aqui**, numa cópia descartável fora do repositório (91 testes verdes, imagem Docker construída e API respondendo). O plano também foi reexecutado tarefa por tarefa, do zero, para garantir que cada etapa intermediária compila e passa nos testes.

## Como executar (ambiente do Roberto)

- Windows 11 com **Git Bash**. Todos os comandos rodam a partir da raiz do repositório (`C:\Users\lucas\repositorios_git\ticket-flow`).
- **Docker Desktop aberto** antes de rodar testes: os testes de integração sobem um PostgreSQL de verdade.
- `./mvnw` roda de dentro de `backend/` (no PowerShell o equivalente é `.\mvnw.cmd`).
- A cada tarefa: explicar em linguagem simples o "porquê" dos conceitos listados (pontos de entrevista), rodar os testes, mostrar o resultado e fazer **um commit pequeno**. Push e Pull Request só quando o Roberto pedir.

## Global Constraints

- Java **21**; Spring Boot **4.1.1** (parent do `pom.xml`); springdoc-openapi **3.1.1**; imagem `postgres:17-alpine` (compose e Testcontainers).
- Pacote raiz `com.ticketflow`, organizado por funcionalidade; nenhuma camada global `controllers/`, `services/`.
- Código, identificadores, API e banco em **inglês**; mensagens para o usuário (campo `detail` dos erros, mensagens de validação) em **português**.
- DTOs são `record`; entidades JPA nunca saem da camada de service.
- Todo erro é **RFC 9457 ProblemDetail** (400 com `errors` por campo, 401, 403, 404, 409, 413).
- Instantes em UTC (`Instant`/`timestamptz`); agrupamento por dia no fuso `app.zone` = `America/Sao_Paulo`.
- Schema **só** via Flyway (`ddl-auto: validate`); migrations nunca são editadas depois de commitadas: mudança = nova migration.
- JWT HS256 com segredo de no mínimo 32 bytes (`JWT_SECRET`), validade de 8h, claims `sub` (id) e `role`.
- SLA: CRITICAL 4h, HIGH 8h, MEDIUM 24h, LOW 72h; "em risco" = menos de 25% do prazo restante.
- Anexos: até 5 MB; PDF, PNG, JPEG, TXT, DOCX.
- Nada de código de produção sem teste falhando antes (TDD); commits pequenos e temáticos, em inglês, no padrão `feat:`/`chore:`.
- `ticket-flow-contexto.md` nunca é versionado (fica no `.gitignore`).

## Review Focus

Situações que a spec implica e que mais provavelmente afetariam alguém usando o sistema. Cada uma tem teste na tarefa dona do código:

1. **Token de usuário que não existe mais** (a demo apaga os usuários a cada reset) deve dar 401 "Sessão inválida", nunca 500, e o id antigo nunca pode ser reaproveitado por outra pessoa. Testes: `AuthApiTest.tokenOfDeletedUserIsRejectedWith401` (Task 2) e `DemoDataSeederTest.resetNeverReusesIdsSoOldTokensStopWorking` (Task 12).
2. **Edições em sequência na mesma tela**: cada resposta precisa trazer a `version` nova, senão a segunda ação dá 409 falso. Teste: `TicketWorkflowApiTest.fullLifecycleWithPauseAndReopen` encadeia 7 mudanças usando sempre a versão devolvida (Task 6).
3. **Busca com `%` ou `_`** ("100%") deve procurar o texto literal. Teste: `TicketSearchApiTest.searchesTitleAndDescriptionCaseInsensitivelyAndLiterally` (Task 7).
4. **Arquivos do mundo real**: nome com acento, caminho completo do Windows, arquivo renomeado (`virus.exe` → `.pdf`), arquivo vazio, arquivo acima de 5 MB. Testes: `AttachmentApiTest` e `AllowedFileTypeTest` (Task 9).
5. **Virada do dia**: entre 21h e meia-noite em São Paulo já é o dia seguinte em UTC; o painel deve contar no dia de São Paulo. Teste: `DashboardApiTest.countsDaysInTheConfiguredTimeZone` (Task 11). O CI roda com `TZ=America/Sao_Paulo` para não esconder bugs de fuso.

---

### Task 1: Higiene do repositório, esqueleto do backend e CI

**Objetivo:** repositório com `.gitignore` e `.gitattributes` corretos, projeto Spring Boot compilando, PostgreSQL local no docker-compose e CI rodando `./mvnw verify` a cada push.

**Conceitos (para explicar em entrevista):** Maven Wrapper (build reprodutível sem instalar Maven), profiles de configuração por variáveis de ambiente (`${DB_URL:padrão}`), Testcontainers (teste com PostgreSQL de verdade em vez de H2), `Clock` como bean (tempo injetável = testável), por que `.gitattributes` com `eol=lf` importa num time com Windows (o `mvnw` com CRLF quebra no Linux do CI e do Docker).

**Files:**
- Modify: `.gitignore` (substituir todo o conteúdo)
- Create: `.gitattributes`, `docker-compose.yml`, `.github/workflows/ci.yml`
- Create (via Spring Initializr): `backend/mvnw`, `backend/mvnw.cmd`, `backend/.mvn/wrapper/maven-wrapper.properties`
- Create: `backend/pom.xml`, `backend/src/main/resources/application.yml`, `backend/src/main/java/com/ticketflow/TicketFlowApplication.java`, `backend/src/main/java/com/ticketflow/common/ClockConfig.java`
- Test: `backend/src/test/java/com/ticketflow/support/TestcontainersConfiguration.java`, `backend/src/test/java/com/ticketflow/ApplicationSmokeTest.java`

**Interfaces:**
- Produces: pacote raiz `com.ticketflow`; bean `java.time.Clock` (UTC) injetável em qualquer serviço; `TestcontainersConfiguration` (test) com `@ServiceConnection` para PostgreSQL `postgres:17-alpine`.

- [ ] **Step 1: Criar a branch de trabalho**

```bash
git switch -c feat/backend
```

- [ ] **Step 2: Substituir o `.gitignore` e criar o `.gitattributes` na raiz**

`.gitignore`

```text
# Java / Maven
target/
*.class
*.log
*.jar
hs_err_pid*
replay_pid*

# Node / Vite
node_modules/
dist/

# Environment files (secrets never go to Git; .env.example does)
.env
.env.*
!.env.example

# IDEs and OS
.idea/
*.iml
.vscode/
.DS_Store
Thumbs.db

# Local session notes (not versioned)
ticket-flow-contexto.md
```

`.gitattributes`

```text
# Text files are stored with LF. Shell scripts such as mvnw break in Linux (CI, Docker) with CRLF.
* text=auto eol=lf

# Windows scripts keep CRLF.
*.cmd text eol=crlf
*.bat text eol=crlf

# Binary files are never converted.
*.png binary
*.jpg binary
*.jpeg binary
*.pdf binary
*.docx binary
```

- [ ] **Step 3: Gerar o wrapper do Maven pelo Spring Initializr e limpar o que não usaremos**

Rodar na raiz do repositório (Git Bash). O `pom.xml` gerado será substituído no passo seguinte; só aproveitamos `mvnw`, `mvnw.cmd` e `.mvn/`.

```bash
curl -sf https://start.spring.io/starter.zip -d type=maven-project -d language=java -d javaVersion=21 -d groupId=com.ticketflow -d artifactId=backend -d packageName=com.ticketflow -d dependencies=web -o backend.zip
python -m zipfile -e backend.zip backend
rm backend.zip backend/HELP.md backend/.gitignore backend/.gitattributes backend/src/main/resources/application.properties
rm -r backend/src/main/java/com/ticketflow/*.java backend/src/test/java/com/ticketflow/*.java
cat backend/.mvn/wrapper/maven-wrapper.properties
```

Expected: o `.properties` mostra `distributionType=only-script` (não há `maven-wrapper.jar` para versionar).

- [ ] **Step 4: Escrever `pom.xml`, configuração e classe principal**

Versões fixadas: Spring Boot **4.1.1** (parent) e springdoc **3.1.1** (linha compatível com Boot 4).

`backend/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
	xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
	<modelVersion>4.0.0</modelVersion>
	<parent>
		<groupId>org.springframework.boot</groupId>
		<artifactId>spring-boot-starter-parent</artifactId>
		<version>4.1.1</version>
		<relativePath/> <!-- lookup parent from repository -->
	</parent>
	<groupId>com.ticketflow</groupId>
	<artifactId>backend</artifactId>
	<version>0.0.1-SNAPSHOT</version>
	<name>ticket-flow-backend</name>
	<description>API do ticket-flow: gestão de chamados com SLA</description>
	<properties>
		<java.version>21</java.version>
		<springdoc.version>3.1.1</springdoc.version>
	</properties>
	<dependencies>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-actuator</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-data-jpa</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-flyway</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security-oauth2-resource-server</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-validation</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-webmvc</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springdoc</groupId>
			<artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
			<version>${springdoc.version}</version>
		</dependency>
		<dependency>
			<groupId>org.flywaydb</groupId>
			<artifactId>flyway-database-postgresql</artifactId>
		</dependency>

		<dependency>
			<groupId>org.postgresql</groupId>
			<artifactId>postgresql</artifactId>
			<scope>runtime</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-actuator-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-data-jpa-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-flyway-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security-oauth2-resource-server-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-validation-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-webmvc-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-testcontainers</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.testcontainers</groupId>
			<artifactId>testcontainers-junit-jupiter</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.testcontainers</groupId>
			<artifactId>testcontainers-postgresql</artifactId>
			<scope>test</scope>
		</dependency>
	</dependencies>

	<build>
		<plugins>
			<plugin>
				<groupId>org.springframework.boot</groupId>
				<artifactId>spring-boot-maven-plugin</artifactId>
			</plugin>
		</plugins>
	</build>

</project>
```

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

`backend/src/main/java/com/ticketflow/TicketFlowApplication.java`

```java
package com.ticketflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TicketFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(TicketFlowApplication.class, args);
    }
}
```

`backend/src/main/java/com/ticketflow/common/ClockConfig.java`

```java
package com.ticketflow.common;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
```

- [ ] **Step 5: Escrever o teste de fumaça (sobe o contexto inteiro com PostgreSQL real)**

`backend/src/test/java/com/ticketflow/support/TestcontainersConfiguration.java`

```java
package com.ticketflow.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Starts a real PostgreSQL in Docker; @ServiceConnection points the datasource at it. */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
    }
}
```

`backend/src/test/java/com/ticketflow/ApplicationSmokeTest.java`

```java
package com.ticketflow;

import com.ticketflow.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Temporary: proves the whole context starts against a real PostgreSQL. Replaced in Task 2. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ApplicationSmokeTest {

    @Test
    void contextLoads() {
    }
}
```

- [ ] **Step 6: Rodar o teste** (Docker Desktop precisa estar aberto)

```bash
cd backend && ./mvnw test; cd ..
```

Expected: `Tests run: 1, Failures: 0, Errors: 0` e `BUILD SUCCESS`. A primeira execução baixa o Maven, as dependências e a imagem do PostgreSQL (alguns minutos).

- [ ] **Step 7: docker-compose do banco local e workflow de CI**

O CI roda com `TZ=America/Sao_Paulo` de propósito: assim a JVM do CI tem o mesmo fuso das máquinas do time e bugs de fuso aparecem lá também.

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

volumes:
  db-data:
```

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
```

- [ ] **Step 8: Conferir o banco local**

```bash
docker compose up -d db && docker compose ps
```

Expected: serviço `db` com status `healthy` em alguns segundos. (Se a porta 5432 estiver ocupada por um PostgreSQL instalado no Windows, pare esse serviço ou troque a porta publicada.)

- [ ] **Step 9: Commit** (o `update-index` grava o `mvnw` como executável; no Windows o Git não percebe isso sozinho, e sem ele o CI falha com "Permission denied")

```bash
git add .gitignore .gitattributes docker-compose.yml .github backend
git update-index --chmod=+x backend/mvnw
git commit -m "chore: scaffold Spring Boot backend, local database and CI"
```

---

### Task 2: Usuários, autenticação JWT e erros padronizados

**Objetivo:** cadastro aberto (sempre REQUESTER), login devolvendo JWT de 8h, `GET /api/auth/me`, rotas protegidas por padrão e todos os erros no formato ProblemDetail (RFC 9457).

**Conceitos:** hash de senha com BCrypt (nunca guardar senha), JWT stateless com o suporte nativo do Spring Security (`oauth2-resource-server` + `NimbusJwtEncoder/Decoder` HMAC), claim `sub` = id e `role` → authority `ROLE_…`, `@RestControllerAdvice` centralizando erros, mesma mensagem para "e-mail inexistente" e "senha errada" (não revelar cadastro), CSRF desligado porque não há cookie de sessão.

**Files:**
- Create: `backend/src/main/resources/db/migration/V1__create_users.sql`
- Create: `user/Role.java`, `user/User.java`, `user/UserRepository.java`, `user/UserResponse.java`, `user/UserSummary.java`
- Create: `auth/JwtProperties.java`, `auth/AuthUser.java`, `auth/TokenService.java`, `auth/AuthDtos.java`, `auth/AuthService.java`, `auth/AuthController.java`
- Create: `common/ApiException.java`, `common/ApiExceptionHandler.java`, `common/ProblemAuthenticationEntryPoint.java`, `common/SecurityConfig.java`
- Test: `support/MutableClock.java`, `support/IntegrationTest.java`, `auth/AuthApiTest.java`
- Delete: `backend/src/test/java/com/ticketflow/ApplicationSmokeTest.java` (substituído pela classe base de integração)

(Caminhos Java relativos a `backend/src/main/java/com/ticketflow/` e `backend/src/test/java/com/ticketflow/`.)

**Interfaces:**
- Consumes: `Clock` (Task 1).
- Produces:
  - `record AuthUser(Long id, Role role)` com `static AuthUser from(Jwt)`, `isRequester()`, `isManager()` — todo controller converte `@AuthenticationPrincipal Jwt jwt` com `AuthUser.from(jwt)`.
  - `ApiException` com fábricas `badRequest/unauthorized/forbidden/notFound/conflict/payloadTooLarge(String detail)`; `ApiExceptionHandler.STALE_VERSION` (mensagem de versão desatualizada).
  - `User` (`getId/getName/getEmail/getRole/setRole/isActive/setActive/isDemo/markAsDemo/canBeAssigned`), `UserRepository.getCurrent(AuthUser)` (401 se o usuário do token não existe mais), `UserResponse.from(User)`, `UserSummary.from(User)` (null-safe).
  - `TokenService.issue(User): String`.
  - Testes: `IntegrationTest` com `mvc`, `jdbc`, `clock` (`MutableClock`: `reset()`, `advance(Duration)`), `userRepository`, `createUser(String name, Role role)` (e-mail `nome@test.com`, senha `password123`) e `bearer(User)`.

- [ ] **Step 1: Escrever a infraestrutura de teste e os testes de autenticação**

A classe base limpa o banco antes de cada teste (menos `categories` e o histórico do Flyway) e troca o `Clock` real por um relógio controlável.

`backend/src/test/java/com/ticketflow/support/MutableClock.java`

```java
package com.ticketflow.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/** A clock the tests control: it stands still until a test moves it forward. */
public class MutableClock extends Clock {

    private volatile Instant instant;

    public MutableClock() {
        reset();
    }

    /** Back to the real "now", truncated to microseconds (PostgreSQL's precision). */
    public void reset() {
        instant = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public void advance(Duration duration) {
        instant = instant.plus(duration);
    }

    @Override
    public Instant instant() {
        return instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    /** A snapshot of the current instant in the requested zone. */
    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(instant, zone);
    }
}
```

`backend/src/test/java/com/ticketflow/support/IntegrationTest.java`

```java
package com.ticketflow.support;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import com.ticketflow.auth.TokenService;
import com.ticketflow.user.Role;
import com.ticketflow.user.User;
import com.ticketflow.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Base class for API tests: real Spring context + real PostgreSQL (Testcontainers) + MockMvc.
 * Every test starts with an empty database (categories and Flyway's history are kept).
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, IntegrationTest.ClockConfiguration.class})
public abstract class IntegrationTest {

    protected static final String PASSWORD = "password123";

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TokenService tokenService;

    protected MockMvc mvc;

    @BeforeEach
    void setUpIntegrationTest() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .defaultResponseCharacterEncoding(StandardCharsets.UTF_8)
                .build();
        clock.reset();
        cleanDatabase();
    }

    private void cleanDatabase() {
        List<String> tables = jdbc.queryForList("""
                SELECT tablename FROM pg_tables
                WHERE schemaname = 'public' AND tablename NOT IN ('flyway_schema_history', 'categories')
                """, String.class);
        if (!tables.isEmpty()) {
            jdbc.execute("TRUNCATE " + String.join(", ", tables) + " RESTART IDENTITY CASCADE");
        }
    }

    protected User createUser(String name, Role role) {
        String email = name.toLowerCase(Locale.ROOT).replace(' ', '.') + "@test.com";
        return userRepository.save(new User(name, email, passwordEncoder.encode(PASSWORD), role, clock.instant()));
    }

    protected String bearer(User user) {
        return "Bearer " + tokenService.issue(user);
    }
}
```

`backend/src/test/java/com/ticketflow/auth/AuthApiTest.java`

```java
package com.ticketflow.auth;

import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.support.IntegrationTest;
import com.ticketflow.user.Role;
import com.ticketflow.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class AuthApiTest extends IntegrationTest {

    @Test
    void registerAlwaysCreatesRequesterAndReturnsToken() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "Ana", "email": "Ana@Example.com", "password": "password123", "role": "MANAGER"}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token", notNullValue()))
                .andExpect(jsonPath("$.user.email").value("ana@example.com"))
                .andExpect(jsonPath("$.user.role").value("REQUESTER"));
    }

    @Test
    void registerRejectsDuplicateEmail() throws Exception {
        createUser("Ana", Role.REQUESTER);

        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "Outra Ana", "email": "ANA@test.com", "password": "password123"}
                        """))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void registerValidatesFields() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "", "email": "not-an-email", "password": "short"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasKey("name")))
                .andExpect(jsonPath("$.errors", hasKey("email")))
                .andExpect(jsonPath("$.errors", hasKey("password")));
    }

    @Test
    void loginReturnsTokenThatAuthenticatesMe() throws Exception {
        createUser("Bruno", Role.AGENT);

        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email": "bruno@test.com", "password": "password123"}
                        """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = com.jayway.jsonpath.JsonPath.read(body, "$.token");

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Bruno"))
                .andExpect(jsonPath("$.role").value("AGENT"));
    }

    @Test
    void loginFailsWithSameMessageForWrongPasswordAndInactiveUser() throws Exception {
        User inactive = createUser("Carla", Role.AGENT);
        inactive.setActive(false);
        userRepository.save(inactive);
        createUser("Diego", Role.AGENT);

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email": "carla@test.com", "password": "password123"}
                        """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("E-mail ou senha inválidos."));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email": "diego@test.com", "password": "wrong-password"}
                        """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("E-mail ou senha inválidos."));
    }

    @Test
    void protectedEndpointsRequireValidToken() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenOfDeletedUserIsRejectedWith401() throws Exception {
        User ghost = createUser("Ghost", Role.REQUESTER);
        String token = bearer(ghost);
        userRepository.delete(ghost);

        mvc.perform(get("/api/auth/me").header("Authorization", token))
                .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: Rodar e ver falhar**

```bash
cd backend && ./mvnw test -Dtest=AuthApiTest; cd ..
```

Expected: FALHA de compilação (`cannot find symbol` para `TokenService`, `User`, `Role`...).

- [ ] **Step 3: Migration e pacote `user`**

`backend/src/main/resources/db/migration/V1__create_users.sql`

```sql
CREATE TABLE users (
    id            BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    name          VARCHAR(100) NOT NULL,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    demo          BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ  NOT NULL
);
```

`backend/src/main/java/com/ticketflow/user/Role.java`

```java
package com.ticketflow.user;

public enum Role {
    REQUESTER,
    AGENT,
    MANAGER
}
```

`backend/src/main/java/com/ticketflow/user/User.java`

```java
package com.ticketflow.user;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private String email;

    private String passwordHash;

    @Enumerated(EnumType.STRING)
    private Role role;

    private boolean active;

    private boolean demo;

    private Instant createdAt;

    protected User() {
        // required by JPA
    }

    public User(String name, String email, String passwordHash, Role role, Instant createdAt) {
        this.name = name;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.active = true;
        this.createdAt = createdAt;
    }

    /** Only active agents and managers can be responsible for a ticket. */
    public boolean canBeAssigned() {
        return active && (role == Role.AGENT || role == Role.MANAGER);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isDemo() {
        return demo;
    }

    public void markAsDemo() {
        this.demo = true;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
```

`backend/src/main/java/com/ticketflow/user/UserRepository.java`

```java
package com.ticketflow.user;

import com.ticketflow.auth.AuthUser;
import com.ticketflow.common.ApiException;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * Loads the caller. The token may outlive its user (the demo reset deletes users),
     * so a missing user is a 401, not a 500.
     */
    default User getCurrent(AuthUser authUser) {
        return findById(authUser.id())
                .orElseThrow(() -> ApiException.unauthorized("Sessão inválida. Entre novamente."));
    }
}
```

`backend/src/main/java/com/ticketflow/user/UserResponse.java`

```java
package com.ticketflow.user;

public record UserResponse(Long id, String name, String email, Role role, boolean active, boolean demo) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(), user.getName(), user.getEmail(), user.getRole(), user.isActive(), user.isDemo());
    }
}
```

`backend/src/main/java/com/ticketflow/user/UserSummary.java`

```java
package com.ticketflow.user;

/** Minimal user data embedded in other responses (ticket requester, comment author...). */
public record UserSummary(Long id, String name) {

    public static UserSummary from(User user) {
        return user == null ? null : new UserSummary(user.getId(), user.getName());
    }
}
```

- [ ] **Step 4: Erros padronizados e configuração de segurança**

`backend/src/main/java/com/ticketflow/common/ApiException.java`

```java
package com.ticketflow.common;

import org.springframework.http.HttpStatus;

/** Business error that becomes a ProblemDetail response with the given status. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    private ApiException(HttpStatus status, String detail) {
        super(detail);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static ApiException badRequest(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, detail);
    }

    public static ApiException unauthorized(String detail) {
        return new ApiException(HttpStatus.UNAUTHORIZED, detail);
    }

    public static ApiException forbidden(String detail) {
        return new ApiException(HttpStatus.FORBIDDEN, detail);
    }

    public static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, detail);
    }

    public static ApiException conflict(String detail) {
        return new ApiException(HttpStatus.CONFLICT, detail);
    }

    public static ApiException payloadTooLarge(String detail) {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, detail);
    }
}
```

`backend/src/main/java/com/ticketflow/common/ApiExceptionHandler.java`

```java
package com.ticketflow.common;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every error into an RFC 9457 ProblemDetail. The parent class already handles
 * Spring MVC's own exceptions (malformed JSON, wrong parameter type, upload too large...).
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    public static final String STALE_VERSION =
            "O chamado foi alterado por outra pessoa. Recarregue e tente novamente.";

    @ExceptionHandler(ApiException.class)
    ProblemDetail handleApiException(ApiException ex) {
        return ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail handleOptimisticLock(OptimisticLockingFailureException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, STALE_VERSION);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Você não tem permissão para esta ação.");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Dados inválidos.");
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }
}
```

`backend/src/main/java/com/ticketflow/common/ProblemAuthenticationEntryPoint.java`

```java
package com.ticketflow.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/** Answers 401 (missing, invalid or expired token) with a ProblemDetail body, like every other API error. */
@Component
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final String BODY =
            "{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,"
                    + "\"detail\":\"Autenticação necessária.\"}";

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(BODY);
    }
}
```

`backend/src/main/java/com/ticketflow/common/SecurityConfig.java`

```java
package com.ticketflow.common;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.ticketflow.auth.JwtProperties;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemAuthenticationEntryPoint entryPoint)
            throws Exception {
        http
                // Stateless API with a Bearer token: there is no session cookie for CSRF to abuse.
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(entryPoint))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint));
        return http.build();
    }

    @Bean
    JwtEncoder jwtEncoder(JwtProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey(properties)));
    }

    @Bean
    JwtDecoder jwtDecoder(JwtProperties properties) {
        return NimbusJwtDecoder.withSecretKey(secretKey(properties)).macAlgorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.allowed-origins}") List<String> origins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setExposedHeaders(List.of("Content-Disposition"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    /** Turns the "role" claim (e.g. MANAGER) into the authority ROLE_MANAGER used by @PreAuthorize. */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("role");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    private static SecretKey secretKey(JwtProperties properties) {
        return new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
```

- [ ] **Step 5: Pacote `auth`**

`backend/src/main/java/com/ticketflow/auth/JwtProperties.java`

```java
package com.ticketflow.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.jwt")
public record JwtProperties(String secret, Duration ttl) {

    public JwtProperties {
        if (secret == null || secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("app.jwt.secret must have at least 32 bytes");
        }
    }
}
```

`backend/src/main/java/com/ticketflow/auth/AuthUser.java`

```java
package com.ticketflow.auth;

import com.ticketflow.user.Role;
import org.springframework.security.oauth2.jwt.Jwt;

/** Who is calling, read from the JWT: "sub" holds the user id and "role" the profile. */
public record AuthUser(Long id, Role role) {

    public static AuthUser from(Jwt jwt) {
        return new AuthUser(Long.valueOf(jwt.getSubject()), Role.valueOf(jwt.getClaimAsString("role")));
    }

    public boolean isRequester() {
        return role == Role.REQUESTER;
    }

    public boolean isManager() {
        return role == Role.MANAGER;
    }
}
```

`backend/src/main/java/com/ticketflow/auth/TokenService.java`

```java
package com.ticketflow.auth;

import com.ticketflow.user.User;
import java.time.Clock;
import java.time.Instant;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class TokenService {

    private final JwtEncoder encoder;
    private final JwtProperties properties;
    private final Clock clock;

    public TokenService(JwtEncoder encoder, JwtProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    public String issue(User user) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("ticket-flow")
                .issuedAt(now)
                .expiresAt(now.plus(properties.ttl()))
                .subject(user.getId().toString())
                .claim("role", user.getRole().name())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
```

`backend/src/main/java/com/ticketflow/auth/AuthDtos.java`

```java
package com.ticketflow.auth;

import com.ticketflow.user.UserResponse;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank(message = "Informe o nome.") @Size(max = 100, message = "Nome muito longo.") String name,
            @NotBlank(message = "Informe o e-mail.") @Email(message = "E-mail inválido.")
            @Size(max = 255, message = "E-mail muito longo.") String email,
            @NotBlank(message = "Informe a senha.")
            @Size(min = 8, max = 64, message = "A senha deve ter entre 8 e 64 caracteres.") String password) {
    }

    public record LoginRequest(
            @NotBlank(message = "Informe o e-mail.") String email,
            @NotBlank(message = "Informe a senha.") String password) {
    }

    public record AuthResponse(String token, UserResponse user) {
    }
}
```

`backend/src/main/java/com/ticketflow/auth/AuthService.java`

```java
package com.ticketflow.auth;

import com.ticketflow.auth.AuthDtos.AuthResponse;
import com.ticketflow.auth.AuthDtos.LoginRequest;
import com.ticketflow.auth.AuthDtos.RegisterRequest;
import com.ticketflow.common.ApiException;
import com.ticketflow.user.Role;
import com.ticketflow.user.User;
import com.ticketflow.user.UserRepository;
import com.ticketflow.user.UserResponse;
import java.time.Clock;
import java.util.Locale;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final Clock clock;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, TokenService tokenService, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.clock = clock;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = normalize(request.email());
        if (users.existsByEmail(email)) {
            throw ApiException.conflict("E-mail já cadastrado.");
        }
        // Open sign-up always creates a REQUESTER; only a manager can promote someone later.
        User user = users.save(new User(request.name().strip(), email,
                passwordEncoder.encode(request.password()), Role.REQUESTER, clock.instant()));
        return new AuthResponse(tokenService.issue(user), UserResponse.from(user));
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        // Same message for "unknown e-mail", "wrong password" and "inactive user":
        // the API must not reveal which e-mails are registered.
        User user = users.findByEmail(normalize(request.email()))
                .filter(User::isActive)
                .filter(found -> passwordEncoder.matches(request.password(), found.getPasswordHash()))
                .orElseThrow(() -> ApiException.unauthorized("E-mail ou senha inválidos."));
        return new AuthResponse(tokenService.issue(user), UserResponse.from(user));
    }

    @Transactional(readOnly = true)
    public UserResponse me(AuthUser authUser) {
        return UserResponse.from(users.getCurrent(authUser));
    }

    private static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
```

`backend/src/main/java/com/ticketflow/auth/AuthController.java`

```java
package com.ticketflow.auth;

import com.ticketflow.auth.AuthDtos.AuthResponse;
import com.ticketflow.auth.AuthDtos.LoginRequest;
import com.ticketflow.auth.AuthDtos.RegisterRequest;
import com.ticketflow.user.UserResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return authService.me(AuthUser.from(jwt));
    }
}
```

- [ ] **Step 6: Apagar o teste de fumaça e rodar tudo**

```bash
rm backend/src/test/java/com/ticketflow/ApplicationSmokeTest.java
cd backend && ./mvnw test; cd ..
```

Expected: `Tests run: 7, Failures: 0, Errors: 0` e `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git add backend
git commit -m "feat: add users, JWT authentication and ProblemDetail errors"
```

---

### Task 3: Categorias

**Objetivo:** categorias fixas criadas pela migration e `GET /api/categories` (autenticado).

**Conceitos:** Flyway versiona o schema *e* dados de referência (seed); `ddl-auto: validate` faz o Hibernate só conferir o schema (quem cria tabela é a migration); DTO `record` em vez de expor a entidade.

**Files:**
- Create: `backend/src/main/resources/db/migration/V2__create_categories.sql`
- Create: `category/Category.java`, `category/CategoryRepository.java`, `category/CategoryResponse.java`, `category/CategoryController.java`
- Test: `category/CategoryApiTest.java`

**Interfaces:**
- Produces: `Category` (`getId()`, `getName()`, construtor `Category(String name)`), `CategoryRepository.findAllByOrderByIdAsc()`, `record CategoryResponse(Long id, String name)` com `from(Category)`. Categorias: Acesso, Hardware, Software, Financeiro, Outros (nessa ordem de id).

- [ ] **Step 1: Escrever o teste**

`backend/src/test/java/com/ticketflow/category/CategoryApiTest.java`

```java
package com.ticketflow.category;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.support.IntegrationTest;
import com.ticketflow.user.Role;
import org.junit.jupiter.api.Test;

class CategoryApiTest extends IntegrationTest {

    @Test
    void listsSeededCategoriesInCreationOrder() throws Exception {
        String token = bearer(createUser("Ana", Role.REQUESTER));

        mvc.perform(get("/api/categories").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].name").value("Acesso"))
                .andExpect(jsonPath("$[4].name").value("Outros"));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/api/categories")).andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: Rodar e ver falhar**

```bash
cd backend && ./mvnw test -Dtest=CategoryApiTest; cd ..
```

Expected: `listsSeededCategoriesInCreationOrder` FALHA com `Status expected:<200> but was:<404>` (o endpoint ainda não existe); `requiresAuthentication` já passa.

- [ ] **Step 3: Implementar**

`backend/src/main/resources/db/migration/V2__create_categories.sql`

```sql
CREATE TABLE categories (
    id   BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE
);

INSERT INTO categories (name) VALUES
    ('Acesso'),
    ('Hardware'),
    ('Software'),
    ('Financeiro'),
    ('Outros');
```

`backend/src/main/java/com/ticketflow/category/Category.java`

```java
package com.ticketflow.category;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "categories")
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    protected Category() {
        // required by JPA
    }

    public Category(String name) {
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }
}
```

`backend/src/main/java/com/ticketflow/category/CategoryRepository.java`

```java
package com.ticketflow.category;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findAllByOrderByIdAsc();
}
```

`backend/src/main/java/com/ticketflow/category/CategoryResponse.java`

```java
package com.ticketflow.category;

public record CategoryResponse(Long id, String name) {

    public static CategoryResponse from(Category category) {
        return new CategoryResponse(category.getId(), category.getName());
    }
}
```

`backend/src/main/java/com/ticketflow/category/CategoryController.java`

```java
package com.ticketflow.category;

import java.util.List;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final CategoryRepository categories;

    public CategoryController(CategoryRepository categories) {
        this.categories = categories;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<CategoryResponse> list() {
        return categories.findAllByOrderByIdAsc().stream().map(CategoryResponse::from).toList();
    }
}
```

- [ ] **Step 4: Rodar e ver passar**

```bash
cd backend && ./mvnw test -Dtest=CategoryApiTest; cd ..
```

Expected: `Tests run: 2, Failures: 0` e `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat: add seeded categories endpoint"
```

---

### Task 4: Regras de SLA e máquina de estados (testes unitários, sem Spring)

**Objetivo:** prazos por prioridade vindos do `application.yml`, cálculo de `dueAt` e do indicador (No prazo / Em risco / Vencido / Pausado / Cumprido / Violado) e as transições válidas de status — tudo testado sem subir Spring nem banco.

**Conceitos:** `@ConfigurationProperties` com `record` (configuração tipada e validada na subida), lógica de domínio pura e testável, `Clock` fixo em teste, enum que conhece as próprias transições (`canTransitionTo`) em vez de `if` espalhado pelos services.

**Files:**
- Create: `ticket/Priority.java`, `ticket/TicketStatus.java`, `sla/SlaProperties.java`, `sla/SlaIndicator.java`, `sla/SlaCalculator.java`
- Test: `sla/SlaCalculatorTest.java`, `ticket/TicketStatusTest.java`

**Interfaces:**
- Produces:
  - `enum Priority { LOW, MEDIUM, HIGH, CRITICAL }`
  - `enum TicketStatus { OPEN, IN_PROGRESS, WAITING_REQUESTER, RESOLVED, CLOSED }` com `canTransitionTo(TicketStatus)`, `isActive()`, `isClockRunning()`, constantes `ACTIVE` e `CLOCK_RUNNING` (`Set<TicketStatus>`).
  - `enum SlaIndicator { ON_TRACK, AT_RISK, OVERDUE, PAUSED, MET, BREACHED }`
  - `SlaCalculator(SlaProperties, Clock)`: `Duration deadlineFor(Priority)`, `Duration riskWindow(Priority)` (25% do prazo), `Instant dueAt(Instant createdAt, Priority, long pausedSeconds)`, `SlaIndicator indicator(TicketStatus, Priority, Instant dueAt, Boolean slaBreached)`.

- [ ] **Step 1: Escrever os testes**

`backend/src/test/java/com/ticketflow/sla/SlaCalculatorTest.java`

```java
package com.ticketflow.sla;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketflow.ticket.Priority;
import com.ticketflow.ticket.TicketStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SlaCalculatorTest {

    static final Instant NOW = Instant.parse("2026-01-10T12:00:00Z");

    static final SlaProperties PROPERTIES = new SlaProperties(Map.of(
            Priority.CRITICAL, Duration.ofHours(4),
            Priority.HIGH, Duration.ofHours(8),
            Priority.MEDIUM, Duration.ofHours(24),
            Priority.LOW, Duration.ofHours(72)));

    final SlaCalculator calculator = new SlaCalculator(PROPERTIES, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void dueAtIsCreationPlusDeadlinePlusPausedTime() {
        Instant createdAt = Instant.parse("2026-01-10T08:00:00Z");

        assertThat(calculator.dueAt(createdAt, Priority.CRITICAL, 0))
                .isEqualTo(Instant.parse("2026-01-10T12:00:00Z"));
        assertThat(calculator.dueAt(createdAt, Priority.CRITICAL, Duration.ofMinutes(90).toSeconds()))
                .isEqualTo(Instant.parse("2026-01-10T13:30:00Z"));
    }

    @Test
    void onTrackWhenAtLeast25PercentIsLeft() {
        // HIGH = 8h, 25% = 2h. Exactly 2h left is still on track.
        assertThat(calculator.indicator(TicketStatus.IN_PROGRESS, Priority.HIGH, NOW.plus(Duration.ofHours(2)), null))
                .isEqualTo(SlaIndicator.ON_TRACK);
    }

    @Test
    void atRiskWhenLessThan25PercentIsLeft() {
        assertThat(calculator.indicator(TicketStatus.OPEN, Priority.HIGH, NOW.plus(Duration.ofMinutes(119)), null))
                .isEqualTo(SlaIndicator.AT_RISK);
    }

    @Test
    void overdueFromTheDueInstantOn() {
        assertThat(calculator.indicator(TicketStatus.OPEN, Priority.LOW, NOW, null))
                .isEqualTo(SlaIndicator.OVERDUE);
        assertThat(calculator.indicator(TicketStatus.IN_PROGRESS, Priority.LOW, NOW.minusSeconds(1), null))
                .isEqualTo(SlaIndicator.OVERDUE);
    }

    @Test
    void pausedWhileWaitingForRequesterEvenIfPastDue() {
        assertThat(calculator.indicator(TicketStatus.WAITING_REQUESTER, Priority.LOW, NOW.minusSeconds(1), null))
                .isEqualTo(SlaIndicator.PAUSED);
    }

    @Test
    void finishedTicketsShowTheRecordedResult() {
        assertThat(calculator.indicator(TicketStatus.RESOLVED, Priority.LOW, NOW, false)).isEqualTo(SlaIndicator.MET);
        assertThat(calculator.indicator(TicketStatus.CLOSED, Priority.LOW, NOW, true))
                .isEqualTo(SlaIndicator.BREACHED);
    }

    @Test
    void propertiesRequireEveryPriority() {
        assertThatThrownBy(() -> new SlaProperties(Map.of(Priority.LOW, Duration.ofHours(1))))
                .isInstanceOf(IllegalStateException.class);
    }
}
```

`backend/src/test/java/com/ticketflow/ticket/TicketStatusTest.java`

```java
package com.ticketflow.ticket;

import static com.ticketflow.ticket.TicketStatus.CLOSED;
import static com.ticketflow.ticket.TicketStatus.IN_PROGRESS;
import static com.ticketflow.ticket.TicketStatus.OPEN;
import static com.ticketflow.ticket.TicketStatus.RESOLVED;
import static com.ticketflow.ticket.TicketStatus.WAITING_REQUESTER;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class TicketStatusTest {

    @Test
    void allowsOnlyTheDocumentedTransitions() {
        assertAllowed(OPEN, Set.of(IN_PROGRESS));
        assertAllowed(IN_PROGRESS, Set.of(WAITING_REQUESTER, RESOLVED));
        assertAllowed(WAITING_REQUESTER, Set.of(IN_PROGRESS));
        assertAllowed(RESOLVED, Set.of(CLOSED, IN_PROGRESS));
        assertAllowed(CLOSED, Set.of());
    }

    @ParameterizedTest
    @EnumSource(TicketStatus.class)
    void neverTransitionsToItself(TicketStatus status) {
        assertThat(status.canTransitionTo(status)).isFalse();
    }

    @Test
    void clockRunsOnlyWhileOpenOrInProgress() {
        assertThat(EnumSet.allOf(TicketStatus.class).stream().filter(TicketStatus::isClockRunning))
                .containsExactlyInAnyOrder(OPEN, IN_PROGRESS);
    }

    private static void assertAllowed(TicketStatus from, Set<TicketStatus> expected) {
        for (TicketStatus target : TicketStatus.values()) {
            assertThat(from.canTransitionTo(target))
                    .as("%s -> %s", from, target)
                    .isEqualTo(expected.contains(target));
        }
    }
}
```

- [ ] **Step 2: Rodar e ver falhar**

```bash
cd backend && ./mvnw test -Dtest='SlaCalculatorTest,TicketStatusTest'; cd ..
```

Expected: FALHA de compilação (`SlaCalculator`, `TicketStatus` não existem).

- [ ] **Step 3: Implementar**

`backend/src/main/java/com/ticketflow/ticket/Priority.java`

```java
package com.ticketflow.ticket;

public enum Priority {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
```

`backend/src/main/java/com/ticketflow/ticket/TicketStatus.java`

```java
package com.ticketflow.ticket;

import java.util.Set;

/** Ticket lifecycle. The enum itself knows which transitions are valid. */
public enum TicketStatus {
    OPEN,
    IN_PROGRESS,
    WAITING_REQUESTER,
    RESOLVED,
    CLOSED;

    /** Statuses of tickets still being worked on ("não finalizados"). */
    public static final Set<TicketStatus> ACTIVE = Set.of(OPEN, IN_PROGRESS, WAITING_REQUESTER);

    /** Statuses in which the SLA clock runs. */
    public static final Set<TicketStatus> CLOCK_RUNNING = Set.of(OPEN, IN_PROGRESS);

    public boolean canTransitionTo(TicketStatus target) {
        return switch (this) {
            case OPEN -> target == IN_PROGRESS;
            case IN_PROGRESS -> target == WAITING_REQUESTER || target == RESOLVED;
            case WAITING_REQUESTER -> target == IN_PROGRESS;
            case RESOLVED -> target == CLOSED || target == IN_PROGRESS;
            case CLOSED -> false;
        };
    }

    public boolean isActive() {
        return ACTIVE.contains(this);
    }

    public boolean isClockRunning() {
        return CLOCK_RUNNING.contains(this);
    }
}
```

`backend/src/main/java/com/ticketflow/sla/SlaProperties.java`

```java
package com.ticketflow.sla;

import com.ticketflow.ticket.Priority;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** SLA deadlines per priority, read from app.sla.deadlines in application.yml. */
@ConfigurationProperties("app.sla")
public record SlaProperties(Map<Priority, Duration> deadlines) {

    public SlaProperties {
        for (Priority priority : Priority.values()) {
            if (deadlines == null || !deadlines.containsKey(priority)) {
                throw new IllegalStateException("Missing SLA deadline for priority " + priority);
            }
        }
        deadlines = Map.copyOf(deadlines);
    }

    public Duration deadlineFor(Priority priority) {
        return deadlines.get(priority);
    }
}
```

`backend/src/main/java/com/ticketflow/sla/SlaIndicator.java`

```java
package com.ticketflow.sla;

public enum SlaIndicator {
    ON_TRACK,
    AT_RISK,
    OVERDUE,
    PAUSED,
    MET,
    BREACHED
}
```

`backend/src/main/java/com/ticketflow/sla/SlaCalculator.java`

```java
package com.ticketflow.sla;

import com.ticketflow.ticket.Priority;
import com.ticketflow.ticket.TicketStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Pure SLA math. It receives a Clock instead of calling Instant.now(),
 * so tests can decide what "now" is.
 */
@Component
public class SlaCalculator {

    private final SlaProperties properties;
    private final Clock clock;

    public SlaCalculator(SlaProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public Duration deadlineFor(Priority priority) {
        return properties.deadlineFor(priority);
    }

    /** A ticket is "at risk" when less than 25% of its deadline is left. */
    public Duration riskWindow(Priority priority) {
        return deadlineFor(priority).dividedBy(4);
    }

    public Instant dueAt(Instant createdAt, Priority priority, long pausedSeconds) {
        return createdAt.plus(deadlineFor(priority)).plusSeconds(pausedSeconds);
    }

    public SlaIndicator indicator(TicketStatus status, Priority priority, Instant dueAt, Boolean slaBreached) {
        if (status == TicketStatus.WAITING_REQUESTER) {
            return SlaIndicator.PAUSED;
        }
        if (status == TicketStatus.RESOLVED || status == TicketStatus.CLOSED) {
            return Boolean.TRUE.equals(slaBreached) ? SlaIndicator.BREACHED : SlaIndicator.MET;
        }
        Instant now = clock.instant();
        if (!now.isBefore(dueAt)) {
            return SlaIndicator.OVERDUE;
        }
        if (Duration.between(now, dueAt).compareTo(riskWindow(priority)) < 0) {
            return SlaIndicator.AT_RISK;
        }
        return SlaIndicator.ON_TRACK;
    }
}
```

- [ ] **Step 4: Rodar os testes unitários e depois a suíte toda** (a suíte confirma que o Spring consegue ler `app.sla` do `application.yml`)

```bash
cd backend && ./mvnw test -Dtest='SlaCalculatorTest,TicketStatusTest' && ./mvnw test; cd ..
```

Expected: primeiro `Tests run: 14, Failures: 0`; depois `Tests run: 23, Failures: 0` e `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat: add SLA calculator and ticket status transitions"
```

---

### Task 5: Entidade Ticket: ciclo de vida e relógio do SLA

**Objetivo:** a entidade `Ticket` aplica as transições e mantém o relógio do SLA (pausa, retomada, resolução, reabertura, mudança de prioridade). A migration cria `tickets` e `ticket_history`.

**Conceitos:** entidade com comportamento (não só getters/setters — "modelo rico"), `@Version` para optimistic locking, `FetchType.LAZY` em `@ManyToOne`, `dueAt` persistido para o banco conseguir filtrar vencidos, testes unitários do domínio sem banco.

**Files:**
- Create: `backend/src/main/resources/db/migration/V3__create_tickets_and_history.sql`
- Create: `ticket/Ticket.java`
- Test: `ticket/TicketSlaTest.java`

**Interfaces:**
- Consumes: `Category`, `User`, `SlaCalculator`, `TicketStatus`, `Priority`, `ApiException`.
- Produces: `Ticket(String title, String description, Priority, Category, User requester, Instant createdAt, SlaCalculator)`; métodos `assign(User, Instant now, SlaCalculator)`, `changeStatus(TicketStatus, Instant now, SlaCalculator)` (lança `ApiException` 409 em transição inválida), `changePriority(Priority, SlaCalculator)`, `changeCategory(Category)`, `isRequestedBy(Long userId)`, `isAssignedTo(Long userId)` e getters (`getVersion()` retorna `long`).

- [ ] **Step 1: Escrever o teste**

`backend/src/test/java/com/ticketflow/ticket/TicketSlaTest.java`

```java
package com.ticketflow.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketflow.category.Category;
import com.ticketflow.common.ApiException;
import com.ticketflow.sla.SlaCalculator;
import com.ticketflow.sla.SlaProperties;
import com.ticketflow.user.Role;
import com.ticketflow.user.User;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests (no Spring, no database) for how the ticket lifecycle moves the SLA clock. */
class TicketSlaTest {

    static final Instant T0 = Instant.parse("2026-01-10T08:00:00Z");

    final SlaCalculator sla = new SlaCalculator(new SlaProperties(Map.of(
            Priority.CRITICAL, Duration.ofHours(4),
            Priority.HIGH, Duration.ofHours(8),
            Priority.MEDIUM, Duration.ofHours(24),
            Priority.LOW, Duration.ofHours(72))), Clock.fixed(T0, ZoneOffset.UTC));

    final User requester = new User("Ana", "ana@test.com", "hash", Role.REQUESTER, T0);
    final User agent = new User("Bruno", "bruno@test.com", "hash", Role.AGENT, T0);

    Ticket newTicket(Priority priority) {
        return new Ticket("  Impressora  ", " Não imprime ", priority, new Category("Hardware"), requester, T0, sla);
    }

    static Instant at(int hours, int minutes) {
        return T0.plus(Duration.ofHours(hours).plusMinutes(minutes));
    }

    @Test
    void newTicketIsOpenWithDeadlineFromPriority() {
        Ticket ticket = newTicket(Priority.HIGH);

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.OPEN);
        assertThat(ticket.getTitle()).isEqualTo("Impressora");
        assertThat(ticket.getDueAt()).isEqualTo(at(8, 0));
    }

    @Test
    void assigningAnOpenTicketStartsWork() {
        Ticket ticket = newTicket(Priority.HIGH);

        ticket.assign(agent, at(0, 10), sla);

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(ticket.getAssignee()).isSameAs(agent);
        assertThat(ticket.getDueAt()).isEqualTo(at(8, 0));
    }

    @Test
    void waitingForRequesterPausesTheClockAndResumingPushesTheDeadline() {
        Ticket ticket = newTicket(Priority.HIGH);
        ticket.assign(agent, at(1, 0), sla);

        ticket.changeStatus(TicketStatus.WAITING_REQUESTER, at(2, 0), sla);
        assertThat(ticket.getPausedAt()).isEqualTo(at(2, 0));

        ticket.changeStatus(TicketStatus.IN_PROGRESS, at(5, 30), sla);
        assertThat(ticket.getPausedAt()).isNull();
        assertThat(ticket.getPausedTotalSeconds()).isEqualTo(Duration.ofMinutes(210).toSeconds());
        assertThat(ticket.getDueAt()).isEqualTo(at(11, 30));
    }

    @Test
    void resolvingRecordsWhetherTheSlaWasMet() {
        Ticket onTime = newTicket(Priority.CRITICAL);
        onTime.assign(agent, at(0, 5), sla);
        onTime.changeStatus(TicketStatus.RESOLVED, at(3, 59), sla);
        assertThat(onTime.getResolvedAt()).isEqualTo(at(3, 59));
        assertThat(onTime.getSlaBreached()).isFalse();

        Ticket late = newTicket(Priority.CRITICAL);
        late.assign(agent, at(0, 5), sla);
        late.changeStatus(TicketStatus.RESOLVED, at(4, 0), sla);
        assertThat(late.getSlaBreached()).isTrue();
    }

    @Test
    void reopeningClearsTheResultAndCountsResolvedTimeAsPause() {
        Ticket ticket = newTicket(Priority.CRITICAL);
        ticket.assign(agent, at(0, 5), sla);
        ticket.changeStatus(TicketStatus.RESOLVED, at(1, 0), sla);

        ticket.changeStatus(TicketStatus.IN_PROGRESS, at(3, 0), sla);

        assertThat(ticket.getResolvedAt()).isNull();
        assertThat(ticket.getSlaBreached()).isNull();
        assertThat(ticket.getDueAt()).isEqualTo(at(6, 0));
    }

    @Test
    void closingKeepsTheResolutionResult() {
        Ticket ticket = newTicket(Priority.CRITICAL);
        ticket.assign(agent, at(0, 5), sla);
        ticket.changeStatus(TicketStatus.RESOLVED, at(1, 0), sla);

        ticket.changeStatus(TicketStatus.CLOSED, at(2, 0), sla);

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.CLOSED);
        assertThat(ticket.getResolvedAt()).isEqualTo(at(1, 0));
        assertThat(ticket.getSlaBreached()).isFalse();
    }

    @Test
    void changingPriorityRecalculatesTheDeadlineKeepingPausedTime() {
        Ticket ticket = newTicket(Priority.LOW);
        ticket.assign(agent, at(0, 0), sla);
        ticket.changeStatus(TicketStatus.WAITING_REQUESTER, at(1, 0), sla);
        ticket.changeStatus(TicketStatus.IN_PROGRESS, at(2, 0), sla);

        ticket.changePriority(Priority.CRITICAL, sla);

        assertThat(ticket.getDueAt()).isEqualTo(at(5, 0));
    }

    @Test
    void invalidTransitionIsRejected() {
        Ticket ticket = newTicket(Priority.LOW);

        assertThatThrownBy(() -> ticket.changeStatus(TicketStatus.RESOLVED, at(1, 0), sla))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("OPEN");
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.OPEN);
    }
}
```

- [ ] **Step 2: Rodar e ver falhar**

```bash
cd backend && ./mvnw test -Dtest=TicketSlaTest; cd ..
```

Expected: FALHA de compilação (`Ticket` não existe).

- [ ] **Step 3: Implementar a migration e a entidade**

`backend/src/main/resources/db/migration/V3__create_tickets_and_history.sql`

```sql
CREATE TABLE tickets (
    id                   BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    title                VARCHAR(120)  NOT NULL,
    description          VARCHAR(5000) NOT NULL,
    priority             VARCHAR(20)   NOT NULL,
    status               VARCHAR(30)   NOT NULL,
    category_id          BIGINT        NOT NULL REFERENCES categories (id),
    requester_id         BIGINT        NOT NULL REFERENCES users (id),
    assignee_id          BIGINT        REFERENCES users (id),
    created_at           TIMESTAMPTZ   NOT NULL,
    due_at               TIMESTAMPTZ   NOT NULL,
    paused_at            TIMESTAMPTZ,
    paused_total_seconds BIGINT        NOT NULL DEFAULT 0,
    resolved_at          TIMESTAMPTZ,
    sla_breached         BOOLEAN,
    version              BIGINT        NOT NULL DEFAULT 0
);

CREATE INDEX idx_tickets_status_due_at ON tickets (status, due_at);
CREATE INDEX idx_tickets_requester ON tickets (requester_id);
CREATE INDEX idx_tickets_assignee ON tickets (assignee_id);

CREATE TABLE ticket_history (
    id          BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    ticket_id   BIGINT       NOT NULL REFERENCES tickets (id) ON DELETE CASCADE,
    actor_id    BIGINT       NOT NULL REFERENCES users (id),
    occurred_at TIMESTAMPTZ  NOT NULL,
    event_type  VARCHAR(30)  NOT NULL,
    field       VARCHAR(30),
    old_value   VARCHAR(255),
    new_value   VARCHAR(255)
);

CREATE INDEX idx_ticket_history_ticket ON ticket_history (ticket_id, occurred_at);
```

`backend/src/main/java/com/ticketflow/ticket/Ticket.java`

```java
package com.ticketflow.ticket;

import com.ticketflow.category.Category;
import com.ticketflow.common.ApiException;
import com.ticketflow.sla.SlaCalculator;
import com.ticketflow.user.User;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;

@Entity
@Table(name = "tickets")
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;

    private String description;

    @Enumerated(EnumType.STRING)
    private Priority priority;

    @Enumerated(EnumType.STRING)
    private TicketStatus status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User requester;

    @ManyToOne(fetch = FetchType.LAZY)
    private User assignee;

    private Instant createdAt;

    private Instant dueAt;

    private Instant pausedAt;

    private long pausedTotalSeconds;

    private Instant resolvedAt;

    private Boolean slaBreached;

    /** Optimistic locking: Hibernate increments it on every update and rejects stale writes. */
    @Version
    private long version;

    protected Ticket() {
        // required by JPA
    }

    public Ticket(String title, String description, Priority priority, Category category, User requester,
            Instant createdAt, SlaCalculator sla) {
        this.title = title.strip();
        this.description = description.strip();
        this.priority = priority;
        this.category = category;
        this.requester = requester;
        this.status = TicketStatus.OPEN;
        this.createdAt = createdAt;
        this.dueAt = sla.dueAt(createdAt, priority, 0);
    }

    /** Sets the responsible person. An OPEN ticket starts being worked on. */
    public void assign(User newAssignee, Instant now, SlaCalculator sla) {
        this.assignee = newAssignee;
        if (status == TicketStatus.OPEN) {
            changeStatus(TicketStatus.IN_PROGRESS, now, sla);
        }
    }

    /** Moves the ticket through its lifecycle and keeps the SLA clock in sync. */
    public void changeStatus(TicketStatus target, Instant now, SlaCalculator sla) {
        if (!status.canTransitionTo(target)) {
            throw ApiException.conflict("Transição de status inválida: " + status + " → " + target + ".");
        }
        if (!status.isClockRunning() && target.isClockRunning()) {
            // Leaving a pause (WAITING_REQUESTER or RESOLVED): the paused time pushes the deadline.
            pausedTotalSeconds += Duration.between(pausedAt, now).toSeconds();
            pausedAt = null;
            dueAt = sla.dueAt(createdAt, priority, pausedTotalSeconds);
        } else if (status.isClockRunning() && !target.isClockRunning()) {
            pausedAt = now;
        }
        if (target == TicketStatus.RESOLVED) {
            resolvedAt = now;
            slaBreached = !now.isBefore(dueAt);
        } else if (status == TicketStatus.RESOLVED && target == TicketStatus.IN_PROGRESS) {
            // Reopened: the SLA result is recalculated on the next resolution.
            resolvedAt = null;
            slaBreached = null;
        }
        status = target;
    }

    /** A new priority means a new deadline; time already paused still counts. */
    public void changePriority(Priority newPriority, SlaCalculator sla) {
        this.priority = newPriority;
        this.dueAt = sla.dueAt(createdAt, newPriority, pausedTotalSeconds);
    }

    public void changeCategory(Category newCategory) {
        this.category = newCategory;
    }

    public boolean isRequestedBy(Long userId) {
        return requester.getId().equals(userId);
    }

    public boolean isAssignedTo(Long userId) {
        return assignee != null && assignee.getId().equals(userId);
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public Priority getPriority() {
        return priority;
    }

    public TicketStatus getStatus() {
        return status;
    }

    public Category getCategory() {
        return category;
    }

    public User getRequester() {
        return requester;
    }

    public User getAssignee() {
        return assignee;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public Instant getPausedAt() {
        return pausedAt;
    }

    public long getPausedTotalSeconds() {
        return pausedTotalSeconds;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public Boolean getSlaBreached() {
        return slaBreached;
    }

    public long getVersion() {
        return version;
    }
}
```

- [ ] **Step 4: Rodar o teste unitário e a suíte toda** (a suíte confirma que o Hibernate valida a entidade contra a tabela criada pelo Flyway)

```bash
cd backend && ./mvnw test -Dtest=TicketSlaTest && ./mvnw test; cd ..
```

Expected: `Tests run: 8, Failures: 0`; depois `Tests run: 31, Failures: 0` e `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat: add ticket entity with SLA clock"
```

---

### Task 6: API de chamados: abrir, consultar, atribuir, mudar status, editar e histórico

**Objetivo:** `POST /api/tickets`, `GET /api/tickets/{id}`, `POST /{id}/assign`, `POST /{id}/status`, `PATCH /{id}` e `GET /{id}/history`, com permissões por perfil (`@PreAuthorize`) e por objeto (no service), 404 para chamado alheio, 409 para versão desatualizada ou transição inválida e histórico gravado na mesma transação.

**Conceitos:** dois níveis de autorização; por que 404 e não 403 para o solicitante; optimistic locking de ponta a ponta (o cliente manda a `version` que leu, e o `flush()` antes de montar a resposta devolve a versão nova); `@Transactional` no service; problema N+1 e por que usamos `@EntityGraph` nos repositórios de histórico.

**Files:**
- Create: `history/HistoryEventType.java`, `history/TicketHistory.java`, `history/TicketHistoryRepository.java`, `history/HistoryRecorder.java`, `history/HistoryResponse.java`, `history/HistoryController.java`
- Create: `ticket/TicketDtos.java`, `ticket/TicketRepository.java`, `ticket/TicketService.java`, `ticket/TicketController.java`
- Modify: `support/IntegrationTest.java` (substituir o arquivo: ganha `categoryId`, `createTicket` e `readLong`)
- Test: `ticket/TicketApiTest.java`, `ticket/TicketWorkflowApiTest.java`, `ticket/TicketUpdateApiTest.java`

**Interfaces:**
- Consumes: `Ticket`, `SlaCalculator`, `UserRepository.getCurrent`, `CategoryRepository`, `AuthUser`, `ApiException`, `ApiExceptionHandler.STALE_VERSION`.
- Produces:
  - `HistoryRecorder.record(Ticket, User actor, HistoryEventType, Instant at)` e `record(Ticket, User actor, HistoryEventType, String field, String oldValue, String newValue, Instant at)`.
  - `TicketService.findVisible(Long id, AuthUser): Ticket` (404 se não existe ou se é de outro solicitante) e `toResponse(Ticket): TicketResponse` — usados por comentários e anexos.
  - DTOs em `TicketDtos`: `CreateTicketRequest`, `UpdateTicketRequest(priority, categoryId, version)`, `AssignRequest(assigneeId, version)`, `ChangeStatusRequest(status, version)`, `TicketResponse`.
  - Testes: `createTicket(User requester, String title, String priority): long`, `categoryId(String name): long`, `readLong(String json, String path): long`.

- [ ] **Step 1: Atualizar a classe base de testes e escrever os testes da API**

`backend/src/test/java/com/ticketflow/support/IntegrationTest.java`

```java
package com.ticketflow.support;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ticketflow.auth.TokenService;
import com.ticketflow.user.Role;
import com.ticketflow.user.User;
import com.ticketflow.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Base class for API tests: real Spring context + real PostgreSQL (Testcontainers) + MockMvc.
 * Every test starts with an empty database (categories and Flyway's history are kept).
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, IntegrationTest.ClockConfiguration.class})
public abstract class IntegrationTest {

    protected static final String PASSWORD = "password123";

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TokenService tokenService;

    protected MockMvc mvc;

    @BeforeEach
    void setUpIntegrationTest() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .defaultResponseCharacterEncoding(StandardCharsets.UTF_8)
                .build();
        clock.reset();
        cleanDatabase();
    }

    private void cleanDatabase() {
        List<String> tables = jdbc.queryForList("""
                SELECT tablename FROM pg_tables
                WHERE schemaname = 'public' AND tablename NOT IN ('flyway_schema_history', 'categories')
                """, String.class);
        if (!tables.isEmpty()) {
            jdbc.execute("TRUNCATE " + String.join(", ", tables) + " RESTART IDENTITY CASCADE");
        }
    }

    protected User createUser(String name, Role role) {
        String email = name.toLowerCase(Locale.ROOT).replace(' ', '.') + "@test.com";
        return userRepository.save(new User(name, email, passwordEncoder.encode(PASSWORD), role, clock.instant()));
    }

    protected String bearer(User user) {
        return "Bearer " + tokenService.issue(user);
    }

    protected long categoryId(String name) {
        return jdbc.queryForObject("SELECT id FROM categories WHERE name = ?", Long.class, name);
    }

    /** Creates a ticket through the API, as the given user, and returns its id. */
    protected long createTicket(User requester, String title, String priority) throws Exception {
        String body = mvc.perform(post("/api/tickets")
                        .header("Authorization", bearer(requester))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "%s", "description": "Detalhes do problema", "priority": "%s",
                                 "categoryId": %d}
                                """.formatted(title, priority, categoryId("Hardware"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    /** Reads a numeric field (id, version...) from a JSON response body. */
    protected static long readLong(String json, String path) {
        return ((Number) JsonPath.read(json, path)).longValue();
    }
}
```

`backend/src/test/java/com/ticketflow/ticket/TicketApiTest.java`

```java
package com.ticketflow.ticket;

import static org.hamcrest.Matchers.hasKey;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.support.IntegrationTest;
import com.ticketflow.user.Role;
import com.ticketflow.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class TicketApiTest extends IntegrationTest {

    @Test
    void requesterCreatesTicketWithDeadlineFromPriority() throws Exception {
        User ana = createUser("Ana", Role.REQUESTER);

        mvc.perform(post("/api/tickets")
                        .header("Authorization", bearer(ana))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "  Sem acesso à VPN ", "description": "Erro 809", "priority": "CRITICAL",
                                 "categoryId": %d}
                                """.formatted(categoryId("Acesso"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Sem acesso à VPN"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.category.name").value("Acesso"))
                .andExpect(jsonPath("$.requester.name").value("Ana"))
                .andExpect(jsonPath("$.assignee").doesNotExist())
                .andExpect(jsonPath("$.dueAt").value(clock.instant().plusSeconds(4 * 3600).toString()))
                .andExpect(jsonPath("$.sla").value("ON_TRACK"))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void createValidatesFieldsAndCategory() throws Exception {
        User ana = createUser("Ana", Role.REQUESTER);

        mvc.perform(post("/api/tickets")
                        .header("Authorization", bearer(ana))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "", "description": "x", "priority": null, "categoryId": 1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasKey("title")))
                .andExpect(jsonPath("$.errors", hasKey("priority")));
        mvc.perform(post("/api/tickets")
                        .header("Authorization", bearer(ana))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "T", "description": "x", "priority": "LOW", "categoryId": 999}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Categoria inválida."));
    }

    @Test
    void unknownPriorityIsBadRequestNotServerError() throws Exception {
        User ana = createUser("Ana", Role.REQUESTER);

        mvc.perform(post("/api/tickets")
                        .header("Authorization", bearer(ana))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "T", "description": "x", "priority": "URGENTE", "categoryId": 1}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requesterCannotSeeSomeoneElsesTicket() throws Exception {
        User ana = createUser("Ana", Role.REQUESTER);
        User eva = createUser("Eva", Role.REQUESTER);
        long ticketId = createTicket(ana, "Impressora", "LOW");

        mvc.perform(get("/api/tickets/{id}", ticketId).header("Authorization", bearer(ana)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/tickets/{id}", ticketId).header("Authorization", bearer(eva)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/tickets/{id}/history", ticketId).header("Authorization", bearer(eva)))
                .andExpect(status().isNotFound());
    }

    @Test
    void agentsAndManagersSeeAnyTicket() throws Exception {
        User ana = createUser("Ana", Role.REQUESTER);
        long ticketId = createTicket(ana, "Impressora", "LOW");

        mvc.perform(get("/api/tickets/{id}", ticketId).header("Authorization", bearer(createUser("Bruno", Role.AGENT))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/tickets/{id}", ticketId).header("Authorization", bearer(createUser("Carla", Role.MANAGER))))
                .andExpect(status().isOk());
    }

    @Test
    void missingTicketIs404() throws Exception {
        User bruno = createUser("Bruno", Role.AGENT);

        mvc.perform(get("/api/tickets/{id}", 999).header("Authorization", bearer(bruno)))
                .andExpect(status().isNotFound());
    }

    @Test
    void creationIsRecordedInHistory() throws Exception {
        User ana = createUser("Ana", Role.REQUESTER);
        long ticketId = createTicket(ana, "Impressora", "LOW");

        mvc.perform(get("/api/tickets/{id}/history", ticketId).header("Authorization", bearer(ana)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].eventType").value("CREATED"))
                .andExpect(jsonPath("$[0].actor.name").value("Ana"));
    }
}
```

`backend/src/test/java/com/ticketflow/ticket/TicketWorkflowApiTest.java`

```java
package com.ticketflow.ticket;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.support.IntegrationTest;
import com.ticketflow.user.Role;
import com.ticketflow.user.User;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class TicketWorkflowApiTest extends IntegrationTest {

    User ana;
    User eva;
    User bruno;
    User diego;
    User carla;

    @BeforeEach
    void users() {
        ana = createUser("Ana", Role.REQUESTER);
        eva = createUser("Eva", Role.REQUESTER);
        bruno = createUser("Bruno", Role.AGENT);
        diego = createUser("Diego", Role.AGENT);
        carla = createUser("Carla", Role.MANAGER);
    }

    ResultActions assign(User actor, long ticketId, long assigneeId, long version) throws Exception {
        return mvc.perform(post("/api/tickets/{id}/assign", ticketId)
                .header("Authorization", bearer(actor))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"assigneeId\": %d, \"version\": %d}".formatted(assigneeId, version)));
    }

    ResultActions changeStatus(User actor, long ticketId, String status, long version) throws Exception {
        return mvc.perform(post("/api/tickets/{id}/status", ticketId)
                .header("Authorization", bearer(actor))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\": \"%s\", \"version\": %d}".formatted(status, version)));
    }

    long versionAfter(ResultActions result) throws Exception {
        return readLong(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                "$.version");
    }

    @Test
    void fullLifecycleWithPauseAndReopen() throws Exception {
        long id = createTicket(ana, "Impressora", "HIGH");
        String originalDueAt = clock.instant().plus(Duration.ofHours(8)).toString();

        long v = versionAfter(assign(bruno, id, bruno.getId(), 0)
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.assignee.name").value("Bruno"))
                .andExpect(jsonPath("$.version").value(1)));

        v = versionAfter(changeStatus(bruno, id, "WAITING_REQUESTER", v)
                .andExpect(jsonPath("$.sla").value("PAUSED")));
        clock.advance(Duration.ofHours(2));
        v = versionAfter(changeStatus(bruno, id, "IN_PROGRESS", v)
                .andExpect(jsonPath("$.dueAt").value(
                        java.time.Instant.parse(originalDueAt).plus(Duration.ofHours(2)).toString())));

        v = versionAfter(changeStatus(bruno, id, "RESOLVED", v)
                .andExpect(jsonPath("$.slaBreached").value(false))
                .andExpect(jsonPath("$.sla").value("MET")));
        v = versionAfter(changeStatus(ana, id, "IN_PROGRESS", v)
                .andExpect(jsonPath("$.resolvedAt").doesNotExist())
                .andExpect(jsonPath("$.slaBreached").doesNotExist()));
        v = versionAfter(changeStatus(bruno, id, "RESOLVED", v));
        changeStatus(ana, id, "CLOSED", v)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        mvc.perform(get("/api/tickets/{id}/history", id).header("Authorization", bearer(ana)))
                .andExpect(jsonPath("$.length()").value(9))
                .andExpect(jsonPath("$[1].eventType").value("ASSIGNED"))
                .andExpect(jsonPath("$[1].newValue").value("Bruno"))
                .andExpect(jsonPath("$[2].eventType").value("STATUS_CHANGED"))
                .andExpect(jsonPath("$[2].oldValue").value("OPEN"))
                .andExpect(jsonPath("$[2].newValue").value("IN_PROGRESS"));
    }

    @Test
    void requesterCannotAssign() throws Exception {
        long id = createTicket(ana, "Impressora", "LOW");

        assign(ana, id, bruno.getId(), 0).andExpect(status().isForbidden());
    }

    @Test
    void agentAssignsOnlyToHimselfAndOnlyOpenTickets() throws Exception {
        long id = createTicket(ana, "Impressora", "LOW");

        assign(bruno, id, diego.getId(), 0).andExpect(status().isForbidden());
        assign(bruno, id, bruno.getId(), 0).andExpect(status().isOk());
        assign(diego, id, diego.getId(), 1).andExpect(status().isConflict());
    }

    @Test
    void managerReassignsWithoutChangingStatus() throws Exception {
        long id = createTicket(ana, "Impressora", "LOW");
        long v = versionAfter(assign(bruno, id, bruno.getId(), 0));
        v = versionAfter(changeStatus(bruno, id, "WAITING_REQUESTER", v));

        assign(carla, id, diego.getId(), v)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignee.name").value("Diego"))
                .andExpect(jsonPath("$.status").value("WAITING_REQUESTER"));
    }

    @Test
    void assigneeMustBeActiveAgentOrManager() throws Exception {
        long id = createTicket(ana, "Impressora", "LOW");

        assign(carla, id, eva.getId(), 0).andExpect(status().isBadRequest());
        diego.setActive(false);
        userRepository.save(diego);
        assign(carla, id, diego.getId(), 0).andExpect(status().isBadRequest());
    }

    @Test
    void openToInProgressOnlyHappensThroughAssignment() throws Exception {
        long id = createTicket(ana, "Impressora", "LOW");

        changeStatus(carla, id, "IN_PROGRESS", 0).andExpect(status().isConflict());
    }

    @Test
    void invalidTransitionIsConflict() throws Exception {
        long id = createTicket(ana, "Impressora", "LOW");
        long v = versionAfter(assign(bruno, id, bruno.getId(), 0));

        changeStatus(bruno, id, "CLOSED", v).andExpect(status().isConflict());
    }

    @Test
    void onlyTheAssigneeOrManagerWorksTheTicket() throws Exception {
        long id = createTicket(ana, "Impressora", "LOW");
        long v = versionAfter(assign(bruno, id, bruno.getId(), 0));

        changeStatus(diego, id, "RESOLVED", v).andExpect(status().isForbidden());
        changeStatus(ana, id, "RESOLVED", v).andExpect(status().isForbidden());
        changeStatus(carla, id, "RESOLVED", v).andExpect(status().isOk());
    }

    @Test
    void onlyTheRequesterOrManagerClosesOrReopens() throws Exception {
        long id = createTicket(ana, "Impressora", "LOW");
        long v = versionAfter(assign(bruno, id, bruno.getId(), 0));
        v = versionAfter(changeStatus(bruno, id, "RESOLVED", v));

        changeStatus(bruno, id, "CLOSED", v).andExpect(status().isForbidden());
        changeStatus(eva, id, "CLOSED", v).andExpect(status().isNotFound());
        changeStatus(ana, id, "CLOSED", v).andExpect(status().isOk());
    }

    @Test
    void staleVersionIsConflict() throws Exception {
        long id = createTicket(ana, "Impressora", "LOW");
        versionAfter(assign(bruno, id, bruno.getId(), 0));

        changeStatus(bruno, id, "RESOLVED", 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        "O chamado foi alterado por outra pessoa. Recarregue e tente novamente."));
    }

    @Test
    void resolvingLateRecordsBreach() throws Exception {
        long id = createTicket(ana, "Impressora", "CRITICAL");
        long v = versionAfter(assign(bruno, id, bruno.getId(), 0));
        clock.advance(Duration.ofHours(5));

        changeStatus(bruno, id, "RESOLVED", v)
                .andExpect(jsonPath("$.slaBreached").value(true))
                .andExpect(jsonPath("$.sla").value("BREACHED"));
    }
}
```

`backend/src/test/java/com/ticketflow/ticket/TicketUpdateApiTest.java`

```java
package com.ticketflow.ticket;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.support.IntegrationTest;
import com.ticketflow.user.Role;
import com.ticketflow.user.User;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class TicketUpdateApiTest extends IntegrationTest {

    User ana;
    User bruno;
    User diego;
    User carla;
    long ticketId;

    @BeforeEach
    void setUp() throws Exception {
        ana = createUser("Ana", Role.REQUESTER);
        bruno = createUser("Bruno", Role.AGENT);
        diego = createUser("Diego", Role.AGENT);
        carla = createUser("Carla", Role.MANAGER);
        ticketId = createTicket(ana, "Impressora", "LOW");
        mvc.perform(post("/api/tickets/{id}/assign", ticketId)
                        .header("Authorization", bearer(bruno))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\": %d, \"version\": 0}".formatted(bruno.getId())))
                .andExpect(status().isOk());
    }

    ResultActions patchTicket(User actor, String json) throws Exception {
        return mvc.perform(patch("/api/tickets/{id}", ticketId)
                .header("Authorization", bearer(actor))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    @Test
    void assigneeChangesPriorityAndDeadlineFollows() throws Exception {
        patchTicket(bruno, "{\"priority\": \"CRITICAL\", \"version\": 1}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priority").value("CRITICAL"))
                .andExpect(jsonPath("$.dueAt").value(clock.instant().plus(Duration.ofHours(4)).toString()))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void managerChangesCategoryAndHistoryRecordsNames() throws Exception {
        patchTicket(carla, "{\"categoryId\": %d, \"version\": 1}".formatted(categoryId("Software")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category.name").value("Software"));

        mvc.perform(get("/api/tickets/{id}/history", ticketId).header("Authorization", bearer(carla)))
                .andExpect(jsonPath("$[3].eventType").value("CATEGORY_CHANGED"))
                .andExpect(jsonPath("$[3].oldValue").value("Hardware"))
                .andExpect(jsonPath("$[3].newValue").value("Software"));
    }

    @Test
    void otherAgentsAndRequestersCannotChangeTheTicket() throws Exception {
        patchTicket(diego, "{\"priority\": \"HIGH\", \"version\": 1}").andExpect(status().isForbidden());
        patchTicket(ana, "{\"priority\": \"HIGH\", \"version\": 1}").andExpect(status().isForbidden());
    }

    @Test
    void staleVersionIsConflict() throws Exception {
        patchTicket(bruno, "{\"priority\": \"HIGH\", \"version\": 0}").andExpect(status().isConflict());
    }

    @Test
    void closedTicketCannotBeChanged() throws Exception {
        long v = readLong(mvc.perform(post("/api/tickets/{id}/status", ticketId)
                        .header("Authorization", bearer(bruno))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"RESOLVED\", \"version\": 1}"))
                .andReturn().getResponse().getContentAsString(), "$.version");
        v = readLong(mvc.perform(post("/api/tickets/{id}/status", ticketId)
                        .header("Authorization", bearer(ana))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CLOSED\", \"version\": %d}".formatted(v)))
                .andReturn().getResponse().getContentAsString(), "$.version");

        patchTicket(carla, "{\"priority\": \"HIGH\", \"version\": %d}".formatted(v))
                .andExpect(status().isConflict());
    }
}
```

- [ ] **Step 2: Rodar e ver falhar**

```bash
cd backend && ./mvnw test -Dtest='TicketApiTest,TicketWorkflowApiTest,TicketUpdateApiTest'; cd ..
```

Expected: FALHA (a API de chamados ainda não existe: `404` onde se espera `201`).

- [ ] **Step 3: Pacote `history`**

`backend/src/main/java/com/ticketflow/history/HistoryEventType.java`

```java
package com.ticketflow.history;

public enum HistoryEventType {
    CREATED,
    STATUS_CHANGED,
    ASSIGNED,
    PRIORITY_CHANGED,
    CATEGORY_CHANGED,
    COMMENT_ADDED,
    ATTACHMENT_ADDED
}
```

`backend/src/main/java/com/ticketflow/history/TicketHistory.java`

```java
package com.ticketflow.history;

import com.ticketflow.ticket.Ticket;
import com.ticketflow.user.User;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "ticket_history")
public class TicketHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Ticket ticket;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User actor;

    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    private HistoryEventType eventType;

    private String field;

    private String oldValue;

    private String newValue;

    protected TicketHistory() {
        // required by JPA
    }

    public TicketHistory(Ticket ticket, User actor, Instant occurredAt, HistoryEventType eventType,
            String field, String oldValue, String newValue) {
        this.ticket = ticket;
        this.actor = actor;
        this.occurredAt = occurredAt;
        this.eventType = eventType;
        this.field = field;
        this.oldValue = oldValue;
        this.newValue = newValue;
    }

    public Long getId() {
        return id;
    }

    public User getActor() {
        return actor;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public HistoryEventType getEventType() {
        return eventType;
    }

    public String getField() {
        return field;
    }

    public String getOldValue() {
        return oldValue;
    }

    public String getNewValue() {
        return newValue;
    }
}
```

`backend/src/main/java/com/ticketflow/history/TicketHistoryRepository.java`

```java
package com.ticketflow.history;

import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketHistoryRepository extends JpaRepository<TicketHistory, Long> {

    @EntityGraph(attributePaths = "actor")
    List<TicketHistory> findByTicketIdOrderByOccurredAtAscIdAsc(Long ticketId);
}
```

`backend/src/main/java/com/ticketflow/history/HistoryRecorder.java`

```java
package com.ticketflow.history;

import com.ticketflow.ticket.Ticket;
import com.ticketflow.user.User;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Writes history explicitly from the services, inside the same transaction as the change:
 * if the change is rolled back, so is its history entry.
 */
@Component
public class HistoryRecorder {

    private final TicketHistoryRepository history;

    public HistoryRecorder(TicketHistoryRepository history) {
        this.history = history;
    }

    public void record(Ticket ticket, User actor, HistoryEventType type, Instant at) {
        record(ticket, actor, type, null, null, null, at);
    }

    public void record(Ticket ticket, User actor, HistoryEventType type, String field, String oldValue,
            String newValue, Instant at) {
        history.save(new TicketHistory(ticket, actor, at, type, field, oldValue, newValue));
    }
}
```

`backend/src/main/java/com/ticketflow/history/HistoryResponse.java`

```java
package com.ticketflow.history;

import com.ticketflow.user.UserSummary;
import java.time.Instant;

public record HistoryResponse(Long id, HistoryEventType eventType, String field, String oldValue, String newValue,
        UserSummary actor, Instant occurredAt) {

    public static HistoryResponse from(TicketHistory entry) {
        return new HistoryResponse(entry.getId(), entry.getEventType(), entry.getField(), entry.getOldValue(),
                entry.getNewValue(), UserSummary.from(entry.getActor()), entry.getOccurredAt());
    }
}
```

`backend/src/main/java/com/ticketflow/history/HistoryController.java`

```java
package com.ticketflow.history;

import com.ticketflow.auth.AuthUser;
import com.ticketflow.ticket.TicketService;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HistoryController {

    private final TicketService tickets;
    private final TicketHistoryRepository history;

    public HistoryController(TicketService tickets, TicketHistoryRepository history) {
        this.tickets = tickets;
        this.history = history;
    }

    @GetMapping("/api/tickets/{ticketId}/history")
    @Transactional(readOnly = true)
    public List<HistoryResponse> list(@PathVariable Long ticketId, @AuthenticationPrincipal Jwt jwt) {
        tickets.findVisible(ticketId, AuthUser.from(jwt));
        return history.findByTicketIdOrderByOccurredAtAscIdAsc(ticketId).stream()
                .map(HistoryResponse::from)
                .toList();
    }
}
```

- [ ] **Step 4: DTOs, repositório, service e controller de chamados**

`backend/src/main/java/com/ticketflow/ticket/TicketDtos.java`

```java
package com.ticketflow.ticket;

import com.ticketflow.category.CategoryResponse;
import com.ticketflow.sla.SlaIndicator;
import com.ticketflow.user.UserSummary;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class TicketDtos {

    private TicketDtos() {
    }

    public record CreateTicketRequest(
            @NotBlank(message = "Informe o título.") @Size(max = 120, message = "Título muito longo.") String title,
            @NotBlank(message = "Descreva o problema.")
            @Size(max = 5000, message = "Descrição muito longa.") String description,
            @NotNull(message = "Informe a prioridade.") Priority priority,
            @NotNull(message = "Informe a categoria.") Long categoryId) {
    }

    /** Fields left null are not changed. */
    public record UpdateTicketRequest(
            Priority priority,
            Long categoryId,
            @NotNull(message = "Informe a versão.") Long version) {
    }

    public record AssignRequest(
            @NotNull(message = "Informe o responsável.") Long assigneeId,
            @NotNull(message = "Informe a versão.") Long version) {
    }

    public record ChangeStatusRequest(
            @NotNull(message = "Informe o status.") TicketStatus status,
            @NotNull(message = "Informe a versão.") Long version) {
    }

    public record TicketResponse(
            Long id,
            String title,
            String description,
            Priority priority,
            TicketStatus status,
            CategoryResponse category,
            UserSummary requester,
            UserSummary assignee,
            Instant createdAt,
            Instant dueAt,
            Instant resolvedAt,
            Boolean slaBreached,
            SlaIndicator sla,
            long version) {

        public static TicketResponse from(Ticket ticket, SlaIndicator sla) {
            return new TicketResponse(ticket.getId(), ticket.getTitle(), ticket.getDescription(),
                    ticket.getPriority(), ticket.getStatus(), CategoryResponse.from(ticket.getCategory()),
                    UserSummary.from(ticket.getRequester()), UserSummary.from(ticket.getAssignee()),
                    ticket.getCreatedAt(), ticket.getDueAt(), ticket.getResolvedAt(), ticket.getSlaBreached(),
                    sla, ticket.getVersion());
        }
    }
}
```

`backend/src/main/java/com/ticketflow/ticket/TicketRepository.java`

```java
package com.ticketflow.ticket;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketRepository extends JpaRepository<Ticket, Long> {
}
```

`backend/src/main/java/com/ticketflow/ticket/TicketService.java`

```java
package com.ticketflow.ticket;

import static com.ticketflow.common.ApiExceptionHandler.STALE_VERSION;

import com.ticketflow.auth.AuthUser;
import com.ticketflow.category.Category;
import com.ticketflow.category.CategoryRepository;
import com.ticketflow.common.ApiException;
import com.ticketflow.history.HistoryEventType;
import com.ticketflow.history.HistoryRecorder;
import com.ticketflow.sla.SlaCalculator;
import com.ticketflow.ticket.TicketDtos.AssignRequest;
import com.ticketflow.ticket.TicketDtos.ChangeStatusRequest;
import com.ticketflow.ticket.TicketDtos.CreateTicketRequest;
import com.ticketflow.ticket.TicketDtos.TicketResponse;
import com.ticketflow.ticket.TicketDtos.UpdateTicketRequest;
import com.ticketflow.user.User;
import com.ticketflow.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class TicketService {

    private final TicketRepository tickets;
    private final CategoryRepository categories;
    private final UserRepository users;
    private final HistoryRecorder history;
    private final SlaCalculator sla;
    private final Clock clock;

    public TicketService(TicketRepository tickets, CategoryRepository categories, UserRepository users,
            HistoryRecorder history, SlaCalculator sla, Clock clock) {
        this.tickets = tickets;
        this.categories = categories;
        this.users = users;
        this.history = history;
        this.sla = sla;
        this.clock = clock;
    }

    public TicketResponse create(CreateTicketRequest request, AuthUser authUser) {
        User requester = users.getCurrent(authUser);
        Category category = findCategory(request.categoryId());
        Instant now = clock.instant();
        Ticket ticket = tickets.save(new Ticket(request.title(), request.description(), request.priority(),
                category, requester, now, sla));
        history.record(ticket, requester, HistoryEventType.CREATED, now);
        return toResponse(ticket);
    }

    @Transactional(readOnly = true)
    public TicketResponse get(Long id, AuthUser authUser) {
        return toResponse(findVisible(id, authUser));
    }

    public TicketResponse assign(Long id, AssignRequest request, AuthUser authUser) {
        Ticket ticket = findVisible(id, authUser);
        if (!authUser.isManager()) {
            if (!authUser.id().equals(request.assigneeId())) {
                throw ApiException.forbidden("Atendentes só podem assumir chamados para si mesmos.");
            }
            if (ticket.getStatus() != TicketStatus.OPEN) {
                throw ApiException.conflict("Este chamado já foi assumido.");
            }
        }
        if (!ticket.getStatus().isActive()) {
            throw ApiException.conflict("Chamados resolvidos ou fechados não podem ser atribuídos.");
        }
        requireVersion(ticket, request.version());
        User assignee = users.findById(request.assigneeId())
                .filter(User::canBeAssigned)
                .orElseThrow(() -> ApiException.badRequest("Responsável inválido."));
        if (ticket.isAssignedTo(assignee.getId())) {
            return toResponse(ticket);
        }

        User actor = users.getCurrent(authUser);
        Instant now = clock.instant();
        TicketStatus oldStatus = ticket.getStatus();
        String oldAssignee = ticket.getAssignee() == null ? null : ticket.getAssignee().getName();
        ticket.assign(assignee, now, sla);
        history.record(ticket, actor, HistoryEventType.ASSIGNED, "assignee", oldAssignee, assignee.getName(), now);
        if (ticket.getStatus() != oldStatus) {
            history.record(ticket, actor, HistoryEventType.STATUS_CHANGED, "status", oldStatus.name(),
                    ticket.getStatus().name(), now);
        }
        return flushAndMap(ticket);
    }

    public TicketResponse changeStatus(Long id, ChangeStatusRequest request, AuthUser authUser) {
        Ticket ticket = findVisible(id, authUser);
        TicketStatus target = request.status();
        if (ticket.getStatus() == TicketStatus.OPEN && target == TicketStatus.IN_PROGRESS) {
            throw ApiException.conflict("Para iniciar o atendimento, atribua o chamado a alguém.");
        }
        requireStatusPermission(ticket, authUser);
        requireVersion(ticket, request.version());

        Instant now = clock.instant();
        TicketStatus oldStatus = ticket.getStatus();
        ticket.changeStatus(target, now, sla);
        history.record(ticket, users.getCurrent(authUser), HistoryEventType.STATUS_CHANGED, "status",
                oldStatus.name(), target.name(), now);
        return flushAndMap(ticket);
    }

    public TicketResponse update(Long id, UpdateTicketRequest request, AuthUser authUser) {
        Ticket ticket = findVisible(id, authUser);
        if (!authUser.isManager() && !ticket.isAssignedTo(authUser.id())) {
            throw ApiException.forbidden("Só o responsável pelo chamado ou um gestor pode alterá-lo.");
        }
        if (ticket.getStatus() == TicketStatus.CLOSED) {
            throw ApiException.conflict("Chamados fechados não podem ser alterados.");
        }
        requireVersion(ticket, request.version());

        User actor = users.getCurrent(authUser);
        Instant now = clock.instant();
        if (request.priority() != null && request.priority() != ticket.getPriority()) {
            String oldPriority = ticket.getPriority().name();
            ticket.changePriority(request.priority(), sla);
            history.record(ticket, actor, HistoryEventType.PRIORITY_CHANGED, "priority", oldPriority,
                    request.priority().name(), now);
        }
        if (request.categoryId() != null && !request.categoryId().equals(ticket.getCategory().getId())) {
            Category category = findCategory(request.categoryId());
            String oldCategory = ticket.getCategory().getName();
            ticket.changeCategory(category);
            history.record(ticket, actor, HistoryEventType.CATEGORY_CHANGED, "category", oldCategory,
                    category.getName(), now);
        }
        return flushAndMap(ticket);
    }

    /**
     * Loads a ticket the caller may see. A requester asking for someone else's ticket gets 404,
     * not 403: the API does not even confirm that the ticket exists.
     */
    public Ticket findVisible(Long id, AuthUser authUser) {
        Ticket ticket = tickets.findById(id).orElseThrow(() -> ApiException.notFound("Chamado não encontrado."));
        if (authUser.isRequester() && !ticket.isRequestedBy(authUser.id())) {
            throw ApiException.notFound("Chamado não encontrado.");
        }
        return ticket;
    }

    public TicketResponse toResponse(Ticket ticket) {
        return TicketResponse.from(ticket, sla.indicator(ticket.getStatus(), ticket.getPriority(),
                ticket.getDueAt(), ticket.getSlaBreached()));
    }

    /** Closing or reopening a RESOLVED ticket is the requester's call; everything else is the assignee's. */
    private void requireStatusPermission(Ticket ticket, AuthUser authUser) {
        if (authUser.isManager()) {
            return;
        }
        boolean allowed = ticket.getStatus() == TicketStatus.RESOLVED
                ? ticket.isRequestedBy(authUser.id())
                : ticket.isAssignedTo(authUser.id());
        if (!allowed) {
            throw ApiException.forbidden("Você não pode mudar o status deste chamado.");
        }
    }

    /** The client sends the version it read; if someone changed the ticket since, we refuse. */
    private static void requireVersion(Ticket ticket, long expectedVersion) {
        if (ticket.getVersion() != expectedVersion) {
            throw ApiException.conflict(STALE_VERSION);
        }
    }

    /** Flushes first so the response carries the new version (Hibernate bumps it on flush). */
    private TicketResponse flushAndMap(Ticket ticket) {
        tickets.flush();
        return toResponse(ticket);
    }

    private Category findCategory(Long id) {
        return categories.findById(id).orElseThrow(() -> ApiException.badRequest("Categoria inválida."));
    }
}
```

`backend/src/main/java/com/ticketflow/ticket/TicketController.java`

```java
package com.ticketflow.ticket;

import com.ticketflow.auth.AuthUser;
import com.ticketflow.ticket.TicketDtos.AssignRequest;
import com.ticketflow.ticket.TicketDtos.ChangeStatusRequest;
import com.ticketflow.ticket.TicketDtos.CreateTicketRequest;
import com.ticketflow.ticket.TicketDtos.TicketResponse;
import com.ticketflow.ticket.TicketDtos.UpdateTicketRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final TicketService tickets;

    public TicketController(TicketService tickets) {
        this.tickets = tickets;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TicketResponse create(@Valid @RequestBody CreateTicketRequest request, @AuthenticationPrincipal Jwt jwt) {
        return tickets.create(request, AuthUser.from(jwt));
    }

    @GetMapping("/{id}")
    public TicketResponse get(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return tickets.get(id, AuthUser.from(jwt));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('AGENT', 'MANAGER')")
    public TicketResponse update(@PathVariable Long id, @Valid @RequestBody UpdateTicketRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return tickets.update(id, request, AuthUser.from(jwt));
    }

    @PostMapping("/{id}/assign")
    @PreAuthorize("hasAnyRole('AGENT', 'MANAGER')")
    public TicketResponse assign(@PathVariable Long id, @Valid @RequestBody AssignRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return tickets.assign(id, request, AuthUser.from(jwt));
    }

    @PostMapping("/{id}/status")
    public TicketResponse changeStatus(@PathVariable Long id, @Valid @RequestBody ChangeStatusRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return tickets.changeStatus(id, request, AuthUser.from(jwt));
    }
}
```

- [ ] **Step 5: Rodar e ver passar**

```bash
cd backend && ./mvnw test -Dtest='TicketApiTest,TicketWorkflowApiTest,TicketUpdateApiTest'; cd ..
```

Expected: `Tests run: 23, Failures: 0` e `BUILD SUCCESS`.

- [ ] **Step 6: Rodar a suíte toda**

```bash
cd backend && ./mvnw test; cd ..
```

Expected: `Tests run: 54, Failures: 0`.

- [ ] **Step 7: Commit**

```bash
git add backend
git commit -m "feat: add ticket API with assignment, status workflow and history"
```

---

### Task 7: Lista de chamados: filtros, busca, SLA e paginação

**Objetivo:** `GET /api/tickets` com filtros (`status` múltiplo, `priority`, `categoryId`, `assigneeId`, `sla=OVERDUE|AT_RISK`, `q`, `mine`), paginação (`page`, `size` até 100), ordenação só por `dueAt`/`createdAt` (padrão `dueAt,asc`, desempate por `id`) e resposta em `PageResponse`.

**Conceitos:** JPA Specifications (Criteria API) para montar o `WHERE` só com os filtros enviados; escapar `%` e `_` no `LIKE`; filtro de SLA feito no banco (por isso `dueAt` é persistido); whitelist de ordenação; desempate estável para páginas não repetirem itens; `@EntityGraph` para evitar N+1 na listagem.

**Files:**
- Create: `common/PageResponse.java`, `ticket/TicketFilter.java`, `ticket/TicketSpecifications.java`
- Modify (substituir arquivo inteiro): `ticket/TicketRepository.java`, `ticket/TicketService.java` (ganha `search`), `ticket/TicketController.java` (ganha `GET /api/tickets`)
- Test: `ticket/TicketSearchApiTest.java`

**Interfaces:**
- Produces: `record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages)` com `from(Page<T>)`; `record TicketFilter(List<TicketStatus> statuses, Priority priority, Long categoryId, Long assigneeId, SlaFilter sla, String query, boolean mine)`; `TicketService.search(TicketFilter, Pageable, AuthUser): PageResponse<TicketResponse>`.

- [ ] **Step 1: Escrever o teste**

`backend/src/test/java/com/ticketflow/ticket/TicketSearchApiTest.java`

```java
package com.ticketflow.ticket;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.support.IntegrationTest;
import com.ticketflow.user.Role;
import com.ticketflow.user.User;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class TicketSearchApiTest extends IntegrationTest {

    User ana;
    User eva;
    User bruno;
    User carla;

    @BeforeEach
    void users() {
        ana = createUser("Ana", Role.REQUESTER);
        eva = createUser("Eva", Role.REQUESTER);
        bruno = createUser("Bruno", Role.AGENT);
        carla = createUser("Carla", Role.MANAGER);
    }

    ResultActions search(User actor, String query) throws Exception {
        return mvc.perform(get("/api/tickets?" + query).header("Authorization", bearer(actor)))
                .andExpect(status().isOk());
    }

    void assignToBruno(long ticketId) throws Exception {
        mvc.perform(post("/api/tickets/{id}/assign", ticketId)
                        .header("Authorization", bearer(bruno))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\": %d, \"version\": 0}".formatted(bruno.getId())))
                .andExpect(status().isOk());
    }

    @Test
    void requesterOnlyListsOwnTickets() throws Exception {
        createTicket(ana, "Da Ana", "LOW");
        createTicket(eva, "Da Eva", "LOW");

        search(ana, "").andExpect(jsonPath("$.content[*].title", contains("Da Ana")));
        search(carla, "").andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void defaultOrderIsDueAtAscending() throws Exception {
        createTicket(ana, "Baixa", "LOW");
        clock.advance(Duration.ofMinutes(1));
        createTicket(ana, "Critica", "CRITICAL");
        clock.advance(Duration.ofMinutes(1));
        createTicket(ana, "Media", "MEDIUM");

        search(carla, "").andExpect(jsonPath("$.content[*].title", contains("Critica", "Media", "Baixa")));
        search(carla, "sort=createdAt,desc")
                .andExpect(jsonPath("$.content[*].title", contains("Media", "Critica", "Baixa")));
    }

    @Test
    void filtersByStatusPriorityAssigneeAndMine() throws Exception {
        long assigned = createTicket(ana, "Atribuido", "HIGH");
        createTicket(ana, "Aberto", "LOW");
        assignToBruno(assigned);

        search(carla, "status=OPEN").andExpect(jsonPath("$.content[*].title", contains("Aberto")));
        search(carla, "status=OPEN&status=IN_PROGRESS").andExpect(jsonPath("$.totalElements").value(2));
        search(carla, "priority=HIGH").andExpect(jsonPath("$.content[*].title", contains("Atribuido")));
        search(carla, "assigneeId=" + bruno.getId()).andExpect(jsonPath("$.totalElements").value(1));
        search(bruno, "mine=true").andExpect(jsonPath("$.content[*].title", contains("Atribuido")));
    }

    @Test
    void searchesTitleAndDescriptionCaseInsensitivelyAndLiterally() throws Exception {
        createTicket(ana, "Impressora travada", "LOW");
        createTicket(ana, "Desconto de 100% no boleto", "LOW");
        createTicket(ana, "Desconto de 1000 reais", "LOW");

        search(carla, "q=IMPRESSORA").andExpect(jsonPath("$.content[*].title", contains("Impressora travada")));
        mvc.perform(get("/api/tickets").param("q", "100%").header("Authorization", bearer(carla)))
                .andExpect(jsonPath("$.content[*].title", contains("Desconto de 100% no boleto")));
        search(carla, "q=detalhes").andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void filtersBySlaSituation() throws Exception {
        createTicket(ana, "Critico", "CRITICAL");
        createTicket(ana, "Alto", "HIGH");
        createTicket(ana, "Baixo", "LOW");
        clock.advance(Duration.ofMinutes(6 * 60 + 30));
        // Now: CRITICAL (4h) is overdue, HIGH (8h) has 1h30 left (< 2h: at risk), LOW (72h) is on track.

        search(carla, "sla=OVERDUE").andExpect(jsonPath("$.content[*].title", contains("Critico")));
        search(carla, "sla=AT_RISK").andExpect(jsonPath("$.content[*].title", contains("Alto")));
        search(carla, "").andExpect(jsonPath("$.content[*].sla", containsInAnyOrder("OVERDUE", "AT_RISK", "ON_TRACK")));
    }

    @Test
    void paginatesWithStableOrderAndCapsPageSize() throws Exception {
        for (int i = 1; i <= 3; i++) {
            createTicket(ana, "Chamado " + i, "LOW");
        }

        search(carla, "size=2&page=0")
                .andExpect(jsonPath("$.content[*].title", contains("Chamado 1", "Chamado 2")))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
        search(carla, "size=2&page=1").andExpect(jsonPath("$.content[*].title", contains("Chamado 3")));
        search(carla, "size=1000").andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void rejectsUnknownSortFieldAndInvalidEnum() throws Exception {
        mvc.perform(get("/api/tickets?sort=title,asc").header("Authorization", bearer(carla)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/tickets?status=DONE").header("Authorization", bearer(carla)))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: Rodar e ver falhar**

```bash
cd backend && ./mvnw test -Dtest=TicketSearchApiTest; cd ..
```

Expected: FALHA (`GET /api/tickets` ainda não existe: 405/404).

- [ ] **Step 3: Implementar**

`backend/src/main/java/com/ticketflow/common/PageResponse.java`

```java
package com.ticketflow.common;

import java.util.List;
import org.springframework.data.domain.Page;

/** Stable JSON shape for paginated lists (we never serialize Spring's Page directly). */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
```

`backend/src/main/java/com/ticketflow/ticket/TicketFilter.java`

```java
package com.ticketflow.ticket;

import java.util.List;

/** Optional filters of GET /api/tickets. Null (or empty) means "do not filter by this". */
public record TicketFilter(
        List<TicketStatus> statuses,
        Priority priority,
        Long categoryId,
        Long assigneeId,
        SlaFilter sla,
        String query,
        boolean mine) {

    public enum SlaFilter {
        OVERDUE,
        AT_RISK
    }
}
```

`backend/src/main/java/com/ticketflow/ticket/TicketSpecifications.java`

```java
package com.ticketflow.ticket;

import com.ticketflow.auth.AuthUser;
import com.ticketflow.sla.SlaCalculator;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

/** Builds the WHERE clause of the ticket list from the optional filters (JPA Criteria API). */
public final class TicketSpecifications {

    private TicketSpecifications() {
    }

    public static Specification<Ticket> matching(TicketFilter filter, AuthUser authUser, Instant now,
            SlaCalculator sla) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (authUser.isRequester()) {
                predicates.add(cb.equal(root.get("requester").get("id"), authUser.id()));
            } else if (filter.mine()) {
                predicates.add(cb.equal(root.get("assignee").get("id"), authUser.id()));
            }
            if (filter.statuses() != null && !filter.statuses().isEmpty()) {
                predicates.add(root.get("status").in(filter.statuses()));
            }
            if (filter.priority() != null) {
                predicates.add(cb.equal(root.get("priority"), filter.priority()));
            }
            if (filter.categoryId() != null) {
                predicates.add(cb.equal(root.get("category").get("id"), filter.categoryId()));
            }
            if (filter.assigneeId() != null) {
                predicates.add(cb.equal(root.get("assignee").get("id"), filter.assigneeId()));
            }
            if (filter.query() != null && !filter.query().isBlank()) {
                String pattern = "%" + escapeLike(filter.query().strip().toLowerCase(Locale.ROOT)) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("title")), pattern, '\\'),
                        cb.like(cb.lower(root.get("description")), pattern, '\\')));
            }
            if (filter.sla() != null) {
                predicates.add(slaPredicate(filter.sla(), root, cb, now, sla));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    /** Same rules as SlaCalculator.indicator, written as SQL so the database does the filtering. */
    private static Predicate slaPredicate(TicketFilter.SlaFilter slaFilter, Root<Ticket> root, CriteriaBuilder cb,
            Instant now, SlaCalculator sla) {
        Predicate running = root.get("status").in(TicketStatus.CLOCK_RUNNING);
        if (slaFilter == TicketFilter.SlaFilter.OVERDUE) {
            return cb.and(running, cb.lessThanOrEqualTo(root.get("dueAt"), now));
        }
        // AT_RISK: not overdue yet, but less than 25% of *this priority's* deadline is left.
        List<Predicate> perPriority = new ArrayList<>();
        for (Priority priority : Priority.values()) {
            perPriority.add(cb.and(
                    cb.equal(root.get("priority"), priority),
                    cb.lessThan(root.get("dueAt"), now.plus(sla.riskWindow(priority)))));
        }
        return cb.and(running, cb.greaterThan(root.get("dueAt"), now),
                cb.or(perPriority.toArray(Predicate[]::new)));
    }

    /** "100%" must search for the text "100%", not "100 followed by anything". */
    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
```

`backend/src/main/java/com/ticketflow/ticket/TicketRepository.java`

```java
package com.ticketflow.ticket;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface TicketRepository extends JpaRepository<Ticket, Long>, JpaSpecificationExecutor<Ticket> {

    /** Loads category, requester and assignee in the same query (avoids the N+1 problem in lists). */
    @Override
    @EntityGraph(attributePaths = {"category", "requester", "assignee"})
    Page<Ticket> findAll(Specification<Ticket> spec, Pageable pageable);
}
```

`backend/src/main/java/com/ticketflow/ticket/TicketService.java`

```java
package com.ticketflow.ticket;

import static com.ticketflow.common.ApiExceptionHandler.STALE_VERSION;

import com.ticketflow.auth.AuthUser;
import com.ticketflow.category.Category;
import com.ticketflow.category.CategoryRepository;
import com.ticketflow.common.ApiException;
import com.ticketflow.common.PageResponse;
import com.ticketflow.history.HistoryEventType;
import com.ticketflow.history.HistoryRecorder;
import com.ticketflow.sla.SlaCalculator;
import com.ticketflow.ticket.TicketDtos.AssignRequest;
import com.ticketflow.ticket.TicketDtos.ChangeStatusRequest;
import com.ticketflow.ticket.TicketDtos.CreateTicketRequest;
import com.ticketflow.ticket.TicketDtos.TicketResponse;
import com.ticketflow.ticket.TicketDtos.UpdateTicketRequest;
import com.ticketflow.user.User;
import com.ticketflow.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class TicketService {

    private final TicketRepository tickets;
    private final CategoryRepository categories;
    private final UserRepository users;
    private final HistoryRecorder history;
    private final SlaCalculator sla;
    private final Clock clock;

    public TicketService(TicketRepository tickets, CategoryRepository categories, UserRepository users,
            HistoryRecorder history, SlaCalculator sla, Clock clock) {
        this.tickets = tickets;
        this.categories = categories;
        this.users = users;
        this.history = history;
        this.sla = sla;
        this.clock = clock;
    }

    public TicketResponse create(CreateTicketRequest request, AuthUser authUser) {
        User requester = users.getCurrent(authUser);
        Category category = findCategory(request.categoryId());
        Instant now = clock.instant();
        Ticket ticket = tickets.save(new Ticket(request.title(), request.description(), request.priority(),
                category, requester, now, sla));
        history.record(ticket, requester, HistoryEventType.CREATED, now);
        return toResponse(ticket);
    }

    @Transactional(readOnly = true)
    public TicketResponse get(Long id, AuthUser authUser) {
        return toResponse(findVisible(id, authUser));
    }

    @Transactional(readOnly = true)
    public PageResponse<TicketResponse> search(TicketFilter filter, Pageable pageable, AuthUser authUser) {
        var spec = TicketSpecifications.matching(filter, authUser, clock.instant(), sla);
        return PageResponse.from(tickets.findAll(spec, pageable).map(this::toResponse));
    }

    public TicketResponse assign(Long id, AssignRequest request, AuthUser authUser) {
        Ticket ticket = findVisible(id, authUser);
        if (!authUser.isManager()) {
            if (!authUser.id().equals(request.assigneeId())) {
                throw ApiException.forbidden("Atendentes só podem assumir chamados para si mesmos.");
            }
            if (ticket.getStatus() != TicketStatus.OPEN) {
                throw ApiException.conflict("Este chamado já foi assumido.");
            }
        }
        if (!ticket.getStatus().isActive()) {
            throw ApiException.conflict("Chamados resolvidos ou fechados não podem ser atribuídos.");
        }
        requireVersion(ticket, request.version());
        User assignee = users.findById(request.assigneeId())
                .filter(User::canBeAssigned)
                .orElseThrow(() -> ApiException.badRequest("Responsável inválido."));
        if (ticket.isAssignedTo(assignee.getId())) {
            return toResponse(ticket);
        }

        User actor = users.getCurrent(authUser);
        Instant now = clock.instant();
        TicketStatus oldStatus = ticket.getStatus();
        String oldAssignee = ticket.getAssignee() == null ? null : ticket.getAssignee().getName();
        ticket.assign(assignee, now, sla);
        history.record(ticket, actor, HistoryEventType.ASSIGNED, "assignee", oldAssignee, assignee.getName(), now);
        if (ticket.getStatus() != oldStatus) {
            history.record(ticket, actor, HistoryEventType.STATUS_CHANGED, "status", oldStatus.name(),
                    ticket.getStatus().name(), now);
        }
        return flushAndMap(ticket);
    }

    public TicketResponse changeStatus(Long id, ChangeStatusRequest request, AuthUser authUser) {
        Ticket ticket = findVisible(id, authUser);
        TicketStatus target = request.status();
        if (ticket.getStatus() == TicketStatus.OPEN && target == TicketStatus.IN_PROGRESS) {
            throw ApiException.conflict("Para iniciar o atendimento, atribua o chamado a alguém.");
        }
        requireStatusPermission(ticket, authUser);
        requireVersion(ticket, request.version());

        Instant now = clock.instant();
        TicketStatus oldStatus = ticket.getStatus();
        ticket.changeStatus(target, now, sla);
        history.record(ticket, users.getCurrent(authUser), HistoryEventType.STATUS_CHANGED, "status",
                oldStatus.name(), target.name(), now);
        return flushAndMap(ticket);
    }

    public TicketResponse update(Long id, UpdateTicketRequest request, AuthUser authUser) {
        Ticket ticket = findVisible(id, authUser);
        if (!authUser.isManager() && !ticket.isAssignedTo(authUser.id())) {
            throw ApiException.forbidden("Só o responsável pelo chamado ou um gestor pode alterá-lo.");
        }
        if (ticket.getStatus() == TicketStatus.CLOSED) {
            throw ApiException.conflict("Chamados fechados não podem ser alterados.");
        }
        requireVersion(ticket, request.version());

        User actor = users.getCurrent(authUser);
        Instant now = clock.instant();
        if (request.priority() != null && request.priority() != ticket.getPriority()) {
            String oldPriority = ticket.getPriority().name();
            ticket.changePriority(request.priority(), sla);
            history.record(ticket, actor, HistoryEventType.PRIORITY_CHANGED, "priority", oldPriority,
                    request.priority().name(), now);
        }
        if (request.categoryId() != null && !request.categoryId().equals(ticket.getCategory().getId())) {
            Category category = findCategory(request.categoryId());
            String oldCategory = ticket.getCategory().getName();
            ticket.changeCategory(category);
            history.record(ticket, actor, HistoryEventType.CATEGORY_CHANGED, "category", oldCategory,
                    category.getName(), now);
        }
        return flushAndMap(ticket);
    }

    /**
     * Loads a ticket the caller may see. A requester asking for someone else's ticket gets 404,
     * not 403: the API does not even confirm that the ticket exists.
     */
    public Ticket findVisible(Long id, AuthUser authUser) {
        Ticket ticket = tickets.findById(id).orElseThrow(() -> ApiException.notFound("Chamado não encontrado."));
        if (authUser.isRequester() && !ticket.isRequestedBy(authUser.id())) {
            throw ApiException.notFound("Chamado não encontrado.");
        }
        return ticket;
    }

    public TicketResponse toResponse(Ticket ticket) {
        return TicketResponse.from(ticket, sla.indicator(ticket.getStatus(), ticket.getPriority(),
                ticket.getDueAt(), ticket.getSlaBreached()));
    }

    /** Closing or reopening a RESOLVED ticket is the requester's call; everything else is the assignee's. */
    private void requireStatusPermission(Ticket ticket, AuthUser authUser) {
        if (authUser.isManager()) {
            return;
        }
        boolean allowed = ticket.getStatus() == TicketStatus.RESOLVED
                ? ticket.isRequestedBy(authUser.id())
                : ticket.isAssignedTo(authUser.id());
        if (!allowed) {
            throw ApiException.forbidden("Você não pode mudar o status deste chamado.");
        }
    }

    /** The client sends the version it read; if someone changed the ticket since, we refuse. */
    private static void requireVersion(Ticket ticket, long expectedVersion) {
        if (ticket.getVersion() != expectedVersion) {
            throw ApiException.conflict(STALE_VERSION);
        }
    }

    /** Flushes first so the response carries the new version (Hibernate bumps it on flush). */
    private TicketResponse flushAndMap(Ticket ticket) {
        tickets.flush();
        return toResponse(ticket);
    }

    private Category findCategory(Long id) {
        return categories.findById(id).orElseThrow(() -> ApiException.badRequest("Categoria inválida."));
    }
}
```

`backend/src/main/java/com/ticketflow/ticket/TicketController.java`

```java
package com.ticketflow.ticket;

import com.ticketflow.auth.AuthUser;
import com.ticketflow.common.ApiException;
import com.ticketflow.common.PageResponse;
import com.ticketflow.ticket.TicketDtos.AssignRequest;
import com.ticketflow.ticket.TicketDtos.ChangeStatusRequest;
import com.ticketflow.ticket.TicketDtos.CreateTicketRequest;
import com.ticketflow.ticket.TicketDtos.TicketResponse;
import com.ticketflow.ticket.TicketDtos.UpdateTicketRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private static final Set<String> SORTABLE_FIELDS = Set.of("dueAt", "createdAt");
    private static final int MAX_PAGE_SIZE = 100;

    private final TicketService tickets;

    public TicketController(TicketService tickets) {
        this.tickets = tickets;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TicketResponse create(@Valid @RequestBody CreateTicketRequest request, @AuthenticationPrincipal Jwt jwt) {
        return tickets.create(request, AuthUser.from(jwt));
    }

    @GetMapping
    public PageResponse<TicketResponse> search(
            @RequestParam(required = false) List<TicketStatus> status,
            @RequestParam(required = false) Priority priority,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long assigneeId,
            @RequestParam(required = false) TicketFilter.SlaFilter sla,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean mine,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "dueAt,asc") String sort,
            @AuthenticationPrincipal Jwt jwt) {
        TicketFilter filter = new TicketFilter(status, priority, categoryId, assigneeId, sla, q, mine);
        return tickets.search(filter, pageable(page, size, sort), AuthUser.from(jwt));
    }

    @GetMapping("/{id}")
    public TicketResponse get(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return tickets.get(id, AuthUser.from(jwt));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('AGENT', 'MANAGER')")
    public TicketResponse update(@PathVariable Long id, @Valid @RequestBody UpdateTicketRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return tickets.update(id, request, AuthUser.from(jwt));
    }

    @PostMapping("/{id}/assign")
    @PreAuthorize("hasAnyRole('AGENT', 'MANAGER')")
    public TicketResponse assign(@PathVariable Long id, @Valid @RequestBody AssignRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return tickets.assign(id, request, AuthUser.from(jwt));
    }

    @PostMapping("/{id}/status")
    public TicketResponse changeStatus(@PathVariable Long id, @Valid @RequestBody ChangeStatusRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return tickets.changeStatus(id, request, AuthUser.from(jwt));
    }

    /** Only whitelisted fields can be sorted; the id is a tie-breaker so pages never overlap. */
    private static Pageable pageable(int page, int size, String sort) {
        String[] parts = sort.split(",");
        String field = parts[0].strip();
        if (!SORTABLE_FIELDS.contains(field)) {
            throw ApiException.badRequest("Ordenação inválida. Use dueAt ou createdAt.");
        }
        Sort.Direction direction = parts.length > 1
                ? Sort.Direction.fromOptionalString(parts[1].strip())
                        .orElseThrow(() -> ApiException.badRequest("Direção de ordenação inválida. Use asc ou desc."))
                : Sort.Direction.ASC;
        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        return PageRequest.of(safePage, safeSize, Sort.by(direction, field).and(Sort.by("id")));
    }
}
```

- [ ] **Step 4: Rodar e ver passar**

```bash
cd backend && ./mvnw test -Dtest=TicketSearchApiTest && ./mvnw test; cd ..
```

Expected: `Tests run: 7, Failures: 0`; depois `Tests run: 61, Failures: 0` e `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat: add ticket search with filters, SLA filters and pagination"
```

---

### Task 8: Comentários (com retomada automática do chamado)

**Objetivo:** `GET` e `POST /api/tickets/{id}/comments`. Comentário do solicitante dono num chamado em `WAITING_REQUESTER` devolve o chamado para `IN_PROGRESS` (e o relógio do SLA volta a correr). Chamado fechado não aceita comentário (409).

**Conceitos:** regra de negócio que atravessa agregados (comentário muda o status do chamado) numa única transação; reuso de `findVisible` para herdar as mesmas regras de visibilidade.

**Files:**
- Create: `backend/src/main/resources/db/migration/V4__create_comments.sql`
- Create: `comment/Comment.java`, `comment/CommentRepository.java`, `comment/CommentDtos.java`, `comment/CommentService.java`, `comment/CommentController.java`
- Test: `comment/CommentApiTest.java`

**Interfaces:**
- Consumes: `TicketService.findVisible`, `Ticket.changeStatus`, `HistoryRecorder`.
- Produces: `Comment(Ticket, User author, String text, Instant createdAt)` e `CommentRepository` (usados pelo seeder da demo na Task 12).

- [ ] **Step 1: Escrever o teste**

`backend/src/test/java/com/ticketflow/comment/CommentApiTest.java`

```java
package com.ticketflow.comment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.support.IntegrationTest;
import com.ticketflow.user.Role;
import com.ticketflow.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class CommentApiTest extends IntegrationTest {

    User ana;
    User eva;
    User bruno;
    long ticketId;

    @BeforeEach
    void setUp() throws Exception {
        ana = createUser("Ana", Role.REQUESTER);
        eva = createUser("Eva", Role.REQUESTER);
        bruno = createUser("Bruno", Role.AGENT);
        ticketId = createTicket(ana, "Impressora", "LOW");
    }

    ResultActions comment(User author, String text) throws Exception {
        return mvc.perform(post("/api/tickets/{id}/comments", ticketId)
                .header("Authorization", bearer(author))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\": \"%s\"}".formatted(text)));
    }

    ResultActions changeStatus(User actor, String status, long version) throws Exception {
        return mvc.perform(post("/api/tickets/{id}/status", ticketId)
                .header("Authorization", bearer(actor))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\": \"%s\", \"version\": %d}".formatted(status, version)));
    }

    void assignToBrunoAndAskRequester() throws Exception {
        mvc.perform(post("/api/tickets/{id}/assign", ticketId)
                        .header("Authorization", bearer(bruno))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\": %d, \"version\": 0}".formatted(bruno.getId())))
                .andExpect(status().isOk());
        changeStatus(bruno, "WAITING_REQUESTER", 1).andExpect(status().isOk());
    }

    @Test
    void requesterAndAgentCommentAndBothSeeTheThread() throws Exception {
        comment(ana, "Olá").andExpect(status().isCreated()).andExpect(jsonPath("$.author.name").value("Ana"));
        comment(bruno, "Vou verificar").andExpect(status().isCreated());

        mvc.perform(get("/api/tickets/{id}/comments", ticketId).header("Authorization", bearer(ana)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].text").value("Vou verificar"));
    }

    @Test
    void otherRequestersGet404() throws Exception {
        comment(eva, "Intrometida").andExpect(status().isNotFound());
        mvc.perform(get("/api/tickets/{id}/comments", ticketId).header("Authorization", bearer(eva)))
                .andExpect(status().isNotFound());
    }

    @Test
    void blankCommentIsRejected() throws Exception {
        comment(ana, "   ").andExpect(status().isBadRequest());
    }

    @Test
    void requesterCommentResumesTicketWaitingForHer() throws Exception {
        assignToBrunoAndAskRequester();

        comment(ana, "Segue o print").andExpect(status().isCreated());

        mvc.perform(get("/api/tickets/{id}", ticketId).header("Authorization", bearer(ana)))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        mvc.perform(get("/api/tickets/{id}/history", ticketId).header("Authorization", bearer(ana)))
                .andExpect(jsonPath("$[-1].eventType").value("STATUS_CHANGED"))
                .andExpect(jsonPath("$[-1].actor.name").value("Ana"))
                .andExpect(jsonPath("$[-2].eventType").value("COMMENT_ADDED"));
    }

    @Test
    void agentCommentDoesNotResumeTheTicket() throws Exception {
        assignToBrunoAndAskRequester();

        comment(bruno, "Lembrete: preciso do print").andExpect(status().isCreated());

        mvc.perform(get("/api/tickets/{id}", ticketId).header("Authorization", bearer(ana)))
                .andExpect(jsonPath("$.status").value("WAITING_REQUESTER"));
    }

    @Test
    void closedTicketRejectsComments() throws Exception {
        mvc.perform(post("/api/tickets/{id}/assign", ticketId)
                        .header("Authorization", bearer(bruno))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\": %d, \"version\": 0}".formatted(bruno.getId())))
                .andExpect(status().isOk());
        changeStatus(bruno, "RESOLVED", 1).andExpect(status().isOk());
        changeStatus(ana, "CLOSED", 2).andExpect(status().isOk());

        comment(ana, "Mais uma coisa").andExpect(status().isConflict());
    }
}
```

- [ ] **Step 2: Rodar e ver falhar**

```bash
cd backend && ./mvnw test -Dtest=CommentApiTest; cd ..
```

Expected: FALHA (endpoint inexistente).

- [ ] **Step 3: Implementar**

`backend/src/main/resources/db/migration/V4__create_comments.sql`

```sql
CREATE TABLE comments (
    id         BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    ticket_id  BIGINT        NOT NULL REFERENCES tickets (id) ON DELETE CASCADE,
    author_id  BIGINT        NOT NULL REFERENCES users (id),
    text       VARCHAR(5000) NOT NULL,
    created_at TIMESTAMPTZ   NOT NULL
);

CREATE INDEX idx_comments_ticket ON comments (ticket_id, created_at);
```

`backend/src/main/java/com/ticketflow/comment/Comment.java`

```java
package com.ticketflow.comment;

import com.ticketflow.ticket.Ticket;
import com.ticketflow.user.User;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "comments")
public class Comment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Ticket ticket;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User author;

    private String text;

    private Instant createdAt;

    protected Comment() {
        // required by JPA
    }

    public Comment(Ticket ticket, User author, String text, Instant createdAt) {
        this.ticket = ticket;
        this.author = author;
        this.text = text.strip();
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public User getAuthor() {
        return author;
    }

    public String getText() {
        return text;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
```

`backend/src/main/java/com/ticketflow/comment/CommentRepository.java`

```java
package com.ticketflow.comment;

import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    @EntityGraph(attributePaths = "author")
    List<Comment> findByTicketIdOrderByCreatedAtAscIdAsc(Long ticketId);
}
```

`backend/src/main/java/com/ticketflow/comment/CommentDtos.java`

```java
package com.ticketflow.comment;

import com.ticketflow.user.UserSummary;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class CommentDtos {

    private CommentDtos() {
    }

    public record CommentRequest(
            @NotBlank(message = "Escreva o comentário.")
            @Size(max = 5000, message = "Comentário muito longo.") String text) {
    }

    public record CommentResponse(Long id, String text, UserSummary author, Instant createdAt) {

        public static CommentResponse from(Comment comment) {
            return new CommentResponse(comment.getId(), comment.getText(), UserSummary.from(comment.getAuthor()),
                    comment.getCreatedAt());
        }
    }
}
```

`backend/src/main/java/com/ticketflow/comment/CommentService.java`

```java
package com.ticketflow.comment;

import com.ticketflow.auth.AuthUser;
import com.ticketflow.comment.CommentDtos.CommentRequest;
import com.ticketflow.comment.CommentDtos.CommentResponse;
import com.ticketflow.common.ApiException;
import com.ticketflow.history.HistoryEventType;
import com.ticketflow.history.HistoryRecorder;
import com.ticketflow.sla.SlaCalculator;
import com.ticketflow.ticket.Ticket;
import com.ticketflow.ticket.TicketService;
import com.ticketflow.ticket.TicketStatus;
import com.ticketflow.user.User;
import com.ticketflow.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CommentService {

    private final CommentRepository comments;
    private final TicketService tickets;
    private final UserRepository users;
    private final HistoryRecorder history;
    private final SlaCalculator sla;
    private final Clock clock;

    public CommentService(CommentRepository comments, TicketService tickets, UserRepository users,
            HistoryRecorder history, SlaCalculator sla, Clock clock) {
        this.comments = comments;
        this.tickets = tickets;
        this.users = users;
        this.history = history;
        this.sla = sla;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<CommentResponse> list(Long ticketId, AuthUser authUser) {
        tickets.findVisible(ticketId, authUser);
        return comments.findByTicketIdOrderByCreatedAtAscIdAsc(ticketId).stream()
                .map(CommentResponse::from)
                .toList();
    }

    public CommentResponse add(Long ticketId, CommentRequest request, AuthUser authUser) {
        Ticket ticket = tickets.findVisible(ticketId, authUser);
        if (ticket.getStatus() == TicketStatus.CLOSED) {
            throw ApiException.conflict("Chamados fechados não aceitam comentários.");
        }
        User author = users.getCurrent(authUser);
        Instant now = clock.instant();
        Comment comment = comments.save(new Comment(ticket, author, request.text(), now));
        history.record(ticket, author, HistoryEventType.COMMENT_ADDED, now);

        // The requester answered what the agent asked: the ticket goes back to work automatically.
        if (ticket.getStatus() == TicketStatus.WAITING_REQUESTER && ticket.isRequestedBy(author.getId())) {
            ticket.changeStatus(TicketStatus.IN_PROGRESS, now, sla);
            history.record(ticket, author, HistoryEventType.STATUS_CHANGED, "status",
                    TicketStatus.WAITING_REQUESTER.name(), TicketStatus.IN_PROGRESS.name(), now);
        }
        return CommentResponse.from(comment);
    }
}
```

`backend/src/main/java/com/ticketflow/comment/CommentController.java`

```java
package com.ticketflow.comment;

import com.ticketflow.auth.AuthUser;
import com.ticketflow.comment.CommentDtos.CommentRequest;
import com.ticketflow.comment.CommentDtos.CommentResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tickets/{ticketId}/comments")
public class CommentController {

    private final CommentService comments;

    public CommentController(CommentService comments) {
        this.comments = comments;
    }

    @GetMapping
    public List<CommentResponse> list(@PathVariable Long ticketId, @AuthenticationPrincipal Jwt jwt) {
        return comments.list(ticketId, AuthUser.from(jwt));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CommentResponse add(@PathVariable Long ticketId, @Valid @RequestBody CommentRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return comments.add(ticketId, request, AuthUser.from(jwt));
    }
}
```

- [ ] **Step 4: Rodar e ver passar**

```bash
cd backend && ./mvnw test -Dtest=CommentApiTest; cd ..
```

Expected: `Tests run: 6, Failures: 0` e `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat: add comments that resume tickets waiting for the requester"
```

---

### Task 9: Anexos (upload, validação por assinatura e download)

**Objetivo:** `GET`/`POST /api/tickets/{id}/attachments` (multipart, campo `file`) e `GET /api/attachments/{id}`. Limite de 5 MB (413), tipos PDF/PNG/JPEG/TXT/DOCX validados pela extensão **e** pelos primeiros bytes, nome de arquivo higienizado, download sempre como `attachment` com `nosniff`.

**Conceitos:** porta/adaptador pontual (`AttachmentStorage` + `DatabaseAttachmentStorage`: trocar por S3 sem mexer no resto); bytes numa tabela separada para listar sem carregar arquivos; "magic bytes" (não confiar no `Content-Type` do navegador); `Content-Disposition` com nome UTF-8; por que `nosniff`.

**Files:**
- Create: `backend/src/main/resources/db/migration/V5__create_attachments.sql`
- Create: `attachment/Attachment.java`, `attachment/AttachmentRepository.java`, `attachment/AttachmentStorage.java`, `attachment/DatabaseAttachmentStorage.java`, `attachment/AllowedFileType.java`, `attachment/AttachmentService.java`, `attachment/AttachmentController.java`
- Test: `attachment/AllowedFileTypeTest.java`, `attachment/AttachmentApiTest.java`

**Interfaces:**
- Produces: `interface AttachmentStorage { void store(Long attachmentId, byte[] data); byte[] load(Long attachmentId); }`; `AllowedFileType.detect(String filename, byte[] data): Optional<AllowedFileType>`.

- [ ] **Step 1: Escrever os testes**

`backend/src/test/java/com/ticketflow/attachment/AllowedFileTypeTest.java`

```java
package com.ticketflow.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AllowedFileTypeTest {

    static final byte[] PDF_BYTES = "%PDF-1.7 conteudo".getBytes(StandardCharsets.US_ASCII);
    static final byte[] PNG_BYTES = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A};

    @Test
    void acceptsMatchingExtensionAndSignature() {
        assertThat(AllowedFileType.detect("relatorio.PDF", PDF_BYTES)).contains(AllowedFileType.PDF);
        assertThat(AllowedFileType.detect("tela.png", PNG_BYTES)).contains(AllowedFileType.PNG);
        assertThat(AllowedFileType.detect("foto.jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1}))
                .contains(AllowedFileType.JPEG);
        assertThat(AllowedFileType.detect("doc.docx", new byte[] {'P', 'K', 3, 4, 20})).contains(AllowedFileType.DOCX);
        assertThat(AllowedFileType.detect("log.txt", "qualquer texto".getBytes(StandardCharsets.UTF_8)))
                .contains(AllowedFileType.TXT);
    }

    @Test
    void rejectsRenamedFiles() {
        assertThat(AllowedFileType.detect("virus.pdf", "MZ executable".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(AllowedFileType.detect("tela.png", PDF_BYTES)).isEmpty();
    }

    @Test
    void rejectsUnknownOrMissingExtension() {
        assertThat(AllowedFileType.detect("script.exe", PDF_BYTES)).isEmpty();
        assertThat(AllowedFileType.detect("sem-extensao", PDF_BYTES)).isEmpty();
    }

    @Test
    void rejectsFilesShorterThanTheSignature() {
        assertThat(AllowedFileType.detect("a.pdf", new byte[] {'%', 'P'})).isEmpty();
    }
}
```

`backend/src/test/java/com/ticketflow/attachment/AttachmentApiTest.java`

```java
package com.ticketflow.attachment;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.support.IntegrationTest;
import com.ticketflow.user.Role;
import com.ticketflow.user.User;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

class AttachmentApiTest extends IntegrationTest {

    static final byte[] PDF = "%PDF-1.7 conteudo do relatorio".getBytes(StandardCharsets.US_ASCII);

    User ana;
    User eva;
    User bruno;
    long ticketId;

    @BeforeEach
    void setUp() throws Exception {
        ana = createUser("Ana", Role.REQUESTER);
        eva = createUser("Eva", Role.REQUESTER);
        bruno = createUser("Bruno", Role.AGENT);
        ticketId = createTicket(ana, "Impressora", "LOW");
    }

    ResultActions upload(User user, String filename, byte[] data) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", filename, "application/octet-stream", data);
        return mvc.perform(multipart("/api/tickets/{id}/attachments", ticketId)
                .file(file)
                .header("Authorization", bearer(user)));
    }

    @Test
    void uploadListAndDownloadRoundTrip() throws Exception {
        String body = upload(ana, "relatório.pdf", PDF)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.filename").value("relatório.pdf"))
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.size").value(PDF.length))
                .andReturn().getResponse().getContentAsString();
        long attachmentId = readLong(body, "$.id");

        mvc.perform(get("/api/tickets/{id}/attachments", ticketId).header("Authorization", bearer(bruno)))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].uploadedBy.name").value("Ana"));
        mvc.perform(get("/api/attachments/{id}", attachmentId).header("Authorization", bearer(bruno)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(PDF))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Disposition", containsString("UTF-8''relat%C3%B3rio.pdf")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mvc.perform(get("/api/tickets/{id}/history", ticketId).header("Authorization", bearer(ana)))
                .andExpect(jsonPath("$[-1].eventType").value("ATTACHMENT_ADDED"))
                .andExpect(jsonPath("$[-1].newValue").value("relatório.pdf"));
    }

    @Test
    void otherRequestersCannotUploadOrDownload() throws Exception {
        long attachmentId = readLong(upload(ana, "a.pdf", PDF).andReturn().getResponse().getContentAsString(), "$.id");

        upload(eva, "b.pdf", PDF).andExpect(status().isNotFound());
        mvc.perform(get("/api/attachments/{id}", attachmentId).header("Authorization", bearer(eva)))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsDisguisedAndEmptyFiles() throws Exception {
        upload(ana, "virus.pdf", "MZ executable".getBytes(StandardCharsets.US_ASCII))
                .andExpect(status().isBadRequest());
        upload(ana, "vazio.pdf", new byte[0]).andExpect(status().isBadRequest());
    }

    @Test
    void rejectsFilesOver5MbWith413() throws Exception {
        byte[] big = Arrays.copyOf(PDF, 5 * 1024 * 1024 + 1);

        upload(ana, "grande.pdf", big).andExpect(status().isPayloadTooLarge());
    }

    @Test
    void keepsOnlyTheFileNameFromAFullPath() throws Exception {
        upload(ana, "C:\\Users\\ana\\Desktop\\nota.txt", "texto".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.filename").value("nota.txt"));
    }

    @Test
    void closedTicketRejectsAttachments() throws Exception {
        mvc.perform(post("/api/tickets/{id}/assign", ticketId)
                        .header("Authorization", bearer(bruno))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\": %d, \"version\": 0}".formatted(bruno.getId())))
                .andExpect(status().isOk());
        mvc.perform(post("/api/tickets/{id}/status", ticketId)
                        .header("Authorization", bearer(bruno))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"RESOLVED\", \"version\": 1}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/tickets/{id}/status", ticketId)
                        .header("Authorization", bearer(ana))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CLOSED\", \"version\": 2}"))
                .andExpect(status().isOk());

        upload(ana, "tarde.pdf", PDF).andExpect(status().isConflict());
    }
}
```

- [ ] **Step 2: Rodar e ver falhar**

```bash
cd backend && ./mvnw test -Dtest='AllowedFileTypeTest,AttachmentApiTest'; cd ..
```

Expected: FALHA de compilação (`AllowedFileType` não existe).

- [ ] **Step 3: Implementar**

`backend/src/main/resources/db/migration/V5__create_attachments.sql`

```sql
CREATE TABLE attachments (
    id             BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    ticket_id      BIGINT       NOT NULL REFERENCES tickets (id) ON DELETE CASCADE,
    uploaded_by_id BIGINT       NOT NULL REFERENCES users (id),
    filename       VARCHAR(255) NOT NULL,
    content_type   VARCHAR(100) NOT NULL,
    size           BIGINT       NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_attachments_ticket ON attachments (ticket_id);

-- The bytes live in a separate table so listing attachments never loads the files.
CREATE TABLE attachment_content (
    attachment_id BIGINT PRIMARY KEY REFERENCES attachments (id) ON DELETE CASCADE,
    data          BYTEA NOT NULL
);
```

`backend/src/main/java/com/ticketflow/attachment/Attachment.java`

```java
package com.ticketflow.attachment;

import com.ticketflow.ticket.Ticket;
import com.ticketflow.user.User;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/** Attachment metadata. The bytes are kept by an AttachmentStorage. */
@Entity
@Table(name = "attachments")
public class Attachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Ticket ticket;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User uploadedBy;

    private String filename;

    private String contentType;

    private long size;

    private Instant createdAt;

    protected Attachment() {
        // required by JPA
    }

    public Attachment(Ticket ticket, User uploadedBy, String filename, String contentType, long size,
            Instant createdAt) {
        this.ticket = ticket;
        this.uploadedBy = uploadedBy;
        this.filename = filename;
        this.contentType = contentType;
        this.size = size;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Ticket getTicket() {
        return ticket;
    }

    public User getUploadedBy() {
        return uploadedBy;
    }

    public String getFilename() {
        return filename;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSize() {
        return size;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
```

`backend/src/main/java/com/ticketflow/attachment/AttachmentRepository.java`

```java
package com.ticketflow.attachment;

import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    @EntityGraph(attributePaths = "uploadedBy")
    List<Attachment> findByTicketIdOrderByCreatedAtAscIdAsc(Long ticketId);
}
```

`backend/src/main/java/com/ticketflow/attachment/AttachmentStorage.java`

```java
package com.ticketflow.attachment;

/**
 * Where attachment bytes live. Today: a PostgreSQL table (zero cost).
 * Tomorrow: disk or S3 — only a new implementation of this interface is needed.
 */
public interface AttachmentStorage {

    void store(Long attachmentId, byte[] data);

    byte[] load(Long attachmentId);
}
```

`backend/src/main/java/com/ticketflow/attachment/DatabaseAttachmentStorage.java`

```java
package com.ticketflow.attachment;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Stores the bytes in the attachment_content table (bytea column). */
@Component
public class DatabaseAttachmentStorage implements AttachmentStorage {

    private final JdbcTemplate jdbc;

    public DatabaseAttachmentStorage(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void store(Long attachmentId, byte[] data) {
        jdbc.update("INSERT INTO attachment_content (attachment_id, data) VALUES (?, ?)", attachmentId, data);
    }

    @Override
    public byte[] load(Long attachmentId) {
        return jdbc.queryForObject("SELECT data FROM attachment_content WHERE attachment_id = ?", byte[].class,
                attachmentId);
    }
}
```

`backend/src/main/java/com/ticketflow/attachment/AllowedFileType.java`

```java
package com.ticketflow.attachment;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Accepted file types. A file is accepted only if its extension is allowed AND its first bytes
 * ("magic bytes") match that type — renaming virus.exe to virus.pdf is not enough.
 */
public enum AllowedFileType {

    PDF(Set.of("pdf"), "application/pdf", new byte[] {'%', 'P', 'D', 'F'}),
    PNG(Set.of("png"), "image/png", new byte[] {(byte) 0x89, 'P', 'N', 'G'}),
    JPEG(Set.of("jpg", "jpeg"), "image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),
    DOCX(Set.of("docx"), "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            new byte[] {'P', 'K', 3, 4}),
    TXT(Set.of("txt"), "text/plain", new byte[0]);

    private final Set<String> extensions;
    private final String contentType;
    private final byte[] signature;

    AllowedFileType(Set<String> extensions, String contentType, byte[] signature) {
        this.extensions = extensions;
        this.contentType = contentType;
        this.signature = signature;
    }

    public String contentType() {
        return contentType;
    }

    public static Optional<AllowedFileType> detect(String filename, byte[] data) {
        String extension = extensionOf(filename);
        return Arrays.stream(values())
                .filter(type -> type.extensions.contains(extension))
                .filter(type -> type.matches(data))
                .findFirst();
    }

    private boolean matches(byte[] data) {
        if (data.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (data[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
```

`backend/src/main/java/com/ticketflow/attachment/AttachmentService.java`

```java
package com.ticketflow.attachment;

import com.ticketflow.auth.AuthUser;
import com.ticketflow.common.ApiException;
import com.ticketflow.history.HistoryEventType;
import com.ticketflow.history.HistoryRecorder;
import com.ticketflow.ticket.Ticket;
import com.ticketflow.ticket.TicketService;
import com.ticketflow.ticket.TicketStatus;
import com.ticketflow.user.User;
import com.ticketflow.user.UserRepository;
import com.ticketflow.user.UserSummary;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@Transactional
public class AttachmentService {

    public static final long MAX_SIZE_BYTES = 5L * 1024 * 1024;

    public record AttachmentResponse(Long id, String filename, String contentType, long size,
            UserSummary uploadedBy, Instant createdAt) {

        static AttachmentResponse from(Attachment attachment) {
            return new AttachmentResponse(attachment.getId(), attachment.getFilename(), attachment.getContentType(),
                    attachment.getSize(), UserSummary.from(attachment.getUploadedBy()), attachment.getCreatedAt());
        }
    }

    public record AttachmentFile(String filename, String contentType, byte[] data) {
    }

    private final AttachmentRepository attachments;
    private final AttachmentStorage storage;
    private final TicketService tickets;
    private final UserRepository users;
    private final HistoryRecorder history;
    private final Clock clock;

    public AttachmentService(AttachmentRepository attachments, AttachmentStorage storage, TicketService tickets,
            UserRepository users, HistoryRecorder history, Clock clock) {
        this.attachments = attachments;
        this.storage = storage;
        this.tickets = tickets;
        this.users = users;
        this.history = history;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AttachmentResponse> list(Long ticketId, AuthUser authUser) {
        tickets.findVisible(ticketId, authUser);
        return attachments.findByTicketIdOrderByCreatedAtAscIdAsc(ticketId).stream()
                .map(AttachmentResponse::from)
                .toList();
    }

    public AttachmentResponse upload(Long ticketId, MultipartFile file, AuthUser authUser) {
        Ticket ticket = tickets.findVisible(ticketId, authUser);
        if (ticket.getStatus() == TicketStatus.CLOSED) {
            throw ApiException.conflict("Chamados fechados não aceitam anexos.");
        }
        if (file.isEmpty()) {
            throw ApiException.badRequest("O arquivo está vazio.");
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw ApiException.payloadTooLarge("O arquivo passa do limite de 5 MB.");
        }
        String filename = sanitize(file.getOriginalFilename());
        byte[] data = readBytes(file);
        AllowedFileType type = AllowedFileType.detect(filename, data)
                .orElseThrow(() -> ApiException.badRequest("Tipo de arquivo não permitido. Envie PDF, PNG, JPEG, TXT ou DOCX."));

        User uploader = users.getCurrent(authUser);
        Instant now = clock.instant();
        Attachment attachment = attachments.save(
                new Attachment(ticket, uploader, filename, type.contentType(), data.length, now));
        storage.store(attachment.getId(), data);
        history.record(ticket, uploader, HistoryEventType.ATTACHMENT_ADDED, "attachment", null, filename, now);
        return AttachmentResponse.from(attachment);
    }

    @Transactional(readOnly = true)
    public AttachmentFile download(Long attachmentId, AuthUser authUser) {
        Attachment attachment = attachments.findById(attachmentId)
                .orElseThrow(() -> ApiException.notFound("Anexo não encontrado."));
        try {
            tickets.findVisible(attachment.getTicket().getId(), authUser);
        } catch (ApiException notVisible) {
            throw ApiException.notFound("Anexo não encontrado.");
        }
        return new AttachmentFile(attachment.getFilename(), attachment.getContentType(),
                storage.load(attachment.getId()));
    }

    /** Keeps only the name (browsers may send a full path) and drops control characters. */
    static String sanitize(String originalFilename) {
        String name = originalFilename == null ? "" : originalFilename;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = name.replaceAll("\\p{Cntrl}", "").strip();
        if (name.isEmpty()) {
            throw ApiException.badRequest("Nome de arquivo inválido.");
        }
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```

`backend/src/main/java/com/ticketflow/attachment/AttachmentController.java`

```java
package com.ticketflow.attachment;

import com.ticketflow.attachment.AttachmentService.AttachmentFile;
import com.ticketflow.attachment.AttachmentService.AttachmentResponse;
import com.ticketflow.auth.AuthUser;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class AttachmentController {

    private final AttachmentService attachments;

    public AttachmentController(AttachmentService attachments) {
        this.attachments = attachments;
    }

    @GetMapping("/api/tickets/{ticketId}/attachments")
    public List<AttachmentResponse> list(@PathVariable Long ticketId, @AuthenticationPrincipal Jwt jwt) {
        return attachments.list(ticketId, AuthUser.from(jwt));
    }

    @PostMapping(path = "/api/tickets/{ticketId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public AttachmentResponse upload(@PathVariable Long ticketId, @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal Jwt jwt) {
        return attachments.upload(ticketId, file, AuthUser.from(jwt));
    }

    /** Always downloaded (never rendered inline) and with nosniff, so a file cannot run as a web page. */
    @GetMapping("/api/attachments/{id}")
    public ResponseEntity<byte[]> download(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        AttachmentFile file = attachments.download(id, AuthUser.from(jwt));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.filename(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(file.data());
    }
}
```

- [ ] **Step 4: Rodar e ver passar**

```bash
cd backend && ./mvnw test -Dtest='AllowedFileTypeTest,AttachmentApiTest'; cd ..
```

Expected: `Tests run: 10, Failures: 0` e `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat: add attachments stored in PostgreSQL with signature validation"
```

---

### Task 10: Gestão de usuários (gestor)

**Objetivo:** `GET /api/users` (gestor, paginado), `GET /api/users/assignable` (atendente/gestor) e `PATCH /api/users/{id}` (gestor: `role`, `active`), com as proteções: conta demo e a própria conta não mudam (409); quem tem chamados em andamento não pode ser rebaixado a REQUESTER nem desativado (409).

**Conceitos:** regras de integridade que o banco sozinho não garante; derived queries do Spring Data (`existsByAssigneeIdAndStatusIn`); `@PreAuthorize` com `hasRole`/`hasAnyRole`.

**Files:**
- Modify (substituir arquivo inteiro): `user/UserRepository.java`, `ticket/TicketRepository.java`
- Create: `user/UserService.java`, `user/UserController.java`
- Test: `user/UserApiTest.java`

**Interfaces:**
- Produces: `UserRepository.findByActiveTrueAndRoleInOrderByNameAsc(Collection<Role>)`, `TicketRepository.existsByAssigneeIdAndStatusIn(Long, Collection<TicketStatus>)`, `record UserService.UpdateUserRequest(Role role, Boolean active)`.

- [ ] **Step 1: Escrever o teste**

`backend/src/test/java/com/ticketflow/user/UserApiTest.java`

```java
package com.ticketflow.user;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class UserApiTest extends IntegrationTest {

    User ana;
    User bruno;
    User carla;

    @BeforeEach
    void users() {
        ana = createUser("Ana", Role.REQUESTER);
        bruno = createUser("Bruno", Role.AGENT);
        carla = createUser("Carla", Role.MANAGER);
    }

    ResultActions update(User actor, User target, String json) throws Exception {
        return mvc.perform(patch("/api/users/{id}", target.getId())
                .header("Authorization", bearer(actor))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    @Test
    void managerListsUsersOthersCannot() throws Exception {
        mvc.perform(get("/api/users").header("Authorization", bearer(carla)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].name", contains("Ana", "Bruno", "Carla")));
        mvc.perform(get("/api/users").header("Authorization", bearer(bruno))).andExpect(status().isForbidden());
    }

    @Test
    void assignableListsActiveAgentsAndManagers() throws Exception {
        User inactive = createUser("Diego", Role.AGENT);
        inactive.setActive(false);
        userRepository.save(inactive);

        mvc.perform(get("/api/users/assignable").header("Authorization", bearer(bruno)))
                .andExpect(jsonPath("$[*].name", contains("Bruno", "Carla")));
        mvc.perform(get("/api/users/assignable").header("Authorization", bearer(ana)))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerPromotesAndDeactivates() throws Exception {
        update(carla, ana, "{\"role\": \"AGENT\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("AGENT"));
        update(carla, ana, "{\"active\": false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        update(bruno, ana, "{\"active\": true}").andExpect(status().isForbidden());
    }

    @Test
    void demoAccountsAndOwnAccountAreProtected() throws Exception {
        bruno.markAsDemo();
        userRepository.save(bruno);

        update(carla, bruno, "{\"active\": false}").andExpect(status().isConflict());
        update(carla, carla, "{\"role\": \"REQUESTER\"}").andExpect(status().isConflict());
    }

    @Test
    void cannotDemoteOrDeactivateSomeoneWithTicketsInProgress() throws Exception {
        long ticketId = createTicket(ana, "Impressora", "LOW");
        mvc.perform(post("/api/tickets/{id}/assign", ticketId)
                        .header("Authorization", bearer(bruno))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\": %d, \"version\": 0}".formatted(bruno.getId())))
                .andExpect(status().isOk());

        update(carla, bruno, "{\"role\": \"REQUESTER\"}").andExpect(status().isConflict());
        update(carla, bruno, "{\"active\": false}").andExpect(status().isConflict());
        update(carla, bruno, "{\"role\": \"MANAGER\"}").andExpect(status().isOk());
    }

    @Test
    void unknownUserIs404() throws Exception {
        mvc.perform(patch("/api/users/{id}", 999)
                        .header("Authorization", bearer(carla))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\": false}"))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: Rodar e ver falhar**

```bash
cd backend && ./mvnw test -Dtest=UserApiTest; cd ..
```

Expected: FALHA (endpoints inexistentes).

- [ ] **Step 3: Implementar**

`backend/src/main/java/com/ticketflow/user/UserRepository.java`

```java
package com.ticketflow.user;

import com.ticketflow.auth.AuthUser;
import com.ticketflow.common.ApiException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    List<User> findByActiveTrueAndRoleInOrderByNameAsc(Collection<Role> roles);

    /**
     * Loads the caller. The token may outlive its user (the demo reset deletes users),
     * so a missing user is a 401, not a 500.
     */
    default User getCurrent(AuthUser authUser) {
        return findById(authUser.id())
                .orElseThrow(() -> ApiException.unauthorized("Sessão inválida. Entre novamente."));
    }
}
```

`backend/src/main/java/com/ticketflow/ticket/TicketRepository.java`

```java
package com.ticketflow.ticket;

import java.util.Collection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface TicketRepository extends JpaRepository<Ticket, Long>, JpaSpecificationExecutor<Ticket> {

    /** Loads category, requester and assignee in the same query (avoids the N+1 problem in lists). */
    @Override
    @EntityGraph(attributePaths = {"category", "requester", "assignee"})
    Page<Ticket> findAll(Specification<Ticket> spec, Pageable pageable);

    boolean existsByAssigneeIdAndStatusIn(Long assigneeId, Collection<TicketStatus> statuses);
}
```

`backend/src/main/java/com/ticketflow/user/UserService.java`

```java
package com.ticketflow.user;

import com.ticketflow.auth.AuthUser;
import com.ticketflow.common.ApiException;
import com.ticketflow.common.PageResponse;
import com.ticketflow.ticket.TicketRepository;
import com.ticketflow.ticket.TicketStatus;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class UserService {

    public record UpdateUserRequest(Role role, Boolean active) {
    }

    private final UserRepository users;
    private final TicketRepository tickets;

    public UserService(UserRepository users, TicketRepository tickets) {
        this.users = users;
        this.tickets = tickets;
    }

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> list(int page, int size) {
        var pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100), Sort.by("name", "id"));
        return PageResponse.from(users.findAll(pageable).map(UserResponse::from));
    }

    @Transactional(readOnly = true)
    public List<UserSummary> assignable() {
        return users.findByActiveTrueAndRoleInOrderByNameAsc(List.of(Role.AGENT, Role.MANAGER)).stream()
                .map(UserSummary::from)
                .toList();
    }

    public UserResponse update(Long id, UpdateUserRequest request, AuthUser authUser) {
        User user = users.findById(id).orElseThrow(() -> ApiException.notFound("Usuário não encontrado."));
        if (user.isDemo()) {
            throw ApiException.conflict("Contas de demonstração não podem ser alteradas.");
        }
        if (user.getId().equals(authUser.id())) {
            throw ApiException.conflict("Você não pode alterar a sua própria conta.");
        }
        boolean losesTickets = (request.role() == Role.REQUESTER && user.getRole() != Role.REQUESTER)
                || (Boolean.FALSE.equals(request.active()) && user.isActive());
        if (losesTickets && tickets.existsByAssigneeIdAndStatusIn(user.getId(), TicketStatus.ACTIVE)) {
            throw ApiException.conflict(
                    "Este usuário é responsável por chamados em andamento. Reatribua os chamados antes.");
        }
        if (request.role() != null) {
            user.setRole(request.role());
        }
        if (request.active() != null) {
            user.setActive(request.active());
        }
        return UserResponse.from(user);
    }
}
```

`backend/src/main/java/com/ticketflow/user/UserController.java`

```java
package com.ticketflow.user;

import com.ticketflow.auth.AuthUser;
import com.ticketflow.common.PageResponse;
import com.ticketflow.user.UserService.UpdateUserRequest;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService users;

    public UserController(UserService users) {
        this.users = users;
    }

    @GetMapping
    @PreAuthorize("hasRole('MANAGER')")
    public PageResponse<UserResponse> list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return users.list(page, size);
    }

    @GetMapping("/assignable")
    @PreAuthorize("hasAnyRole('AGENT', 'MANAGER')")
    public List<UserSummary> assignable() {
        return users.assignable();
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('MANAGER')")
    public UserResponse update(@PathVariable Long id, @RequestBody UpdateUserRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return users.update(id, request, AuthUser.from(jwt));
    }
}
```

- [ ] **Step 4: Rodar e ver passar**

```bash
cd backend && ./mvnw test -Dtest=UserApiTest; cd ..
```

Expected: `Tests run: 6, Failures: 0` e `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat: add user management for managers"
```

---

### Task 11: Painel do gestor

**Objetivo:** `GET /api/dashboard?from=&to=` (gestor; datas ISO inclusivas; padrão últimos 30 dias; máximo 90): chamados por status, vencidos agora, total resolvido, % SLA cumprido, tempo médio de resolução, abertos por categoria, carga e resolvidos por atendente e série diária (abertos × resolvidos) no fuso `app.zone`.

**Conceitos:** relatório com SQL direto (`NamedParameterJdbcTemplate`) em vez de JPA — agregações (`count(*) FILTER`, `avg`, `AT TIME ZONE`) são o forte do SQL; "dia" depende do fuso (23h em São Paulo já é o dia seguinte em UTC); `java.sql.Timestamp` como parâmetro de instante no JDBC.

**Files:**
- Create: `dashboard/DashboardResponse.java`, `dashboard/DashboardService.java`, `dashboard/DashboardController.java`
- Test: `dashboard/DashboardApiTest.java`

**Interfaces:**
- Produces: `DashboardResponse` (campos: `from`, `to`, `ticketsByStatus`, `overdueNow`, `resolvedInPeriod`, `slaMetPercentage`, `averageResolutionHours`, `openedByCategory`, `agents`, `daily`) — contrato que o frontend (Plano 2) vai consumir.

- [ ] **Step 1: Escrever o teste**

`backend/src/test/java/com/ticketflow/dashboard/DashboardApiTest.java`

```java
package com.ticketflow.dashboard;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.support.IntegrationTest;
import com.ticketflow.user.Role;
import com.ticketflow.user.User;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class DashboardApiTest extends IntegrationTest {

    User ana;
    User bruno;
    User carla;

    @BeforeEach
    void users() {
        ana = createUser("Ana", Role.REQUESTER);
        bruno = createUser("Bruno", Role.AGENT);
        carla = createUser("Carla", Role.MANAGER);
    }

    void assignAndResolve(long ticketId, Duration workTime) throws Exception {
        mvc.perform(post("/api/tickets/{id}/assign", ticketId)
                        .header("Authorization", bearer(bruno))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\": %d, \"version\": 0}".formatted(bruno.getId())))
                .andExpect(status().isOk());
        clock.advance(workTime);
        mvc.perform(post("/api/tickets/{id}/status", ticketId)
                        .header("Authorization", bearer(bruno))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"RESOLVED\", \"version\": 1}"))
                .andExpect(status().isOk());
    }

    @Test
    void summarizesCurrentStateAndPeriod() throws Exception {
        long fast = createTicket(ana, "Rapido", "CRITICAL");
        long slow = createTicket(ana, "Lento", "CRITICAL");
        createTicket(ana, "Parado", "CRITICAL");
        assignAndResolve(fast, Duration.ofHours(2));
        assignAndResolve(slow, Duration.ofHours(4));
        // Resolution times: 2h (met) and 6h (breached: 4h deadline). "Parado" is now 6h old: overdue.

        mvc.perform(get("/api/dashboard").header("Authorization", bearer(carla)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketsByStatus.OPEN").value(1))
                .andExpect(jsonPath("$.ticketsByStatus.RESOLVED").value(2))
                .andExpect(jsonPath("$.ticketsByStatus.CLOSED").value(0))
                .andExpect(jsonPath("$.overdueNow").value(1))
                .andExpect(jsonPath("$.resolvedInPeriod").value(2))
                .andExpect(jsonPath("$.slaMetPercentage").value(50.0))
                .andExpect(jsonPath("$.averageResolutionHours").value(4.0))
                .andExpect(jsonPath("$.openedByCategory[1].category").value("Hardware"))
                .andExpect(jsonPath("$.openedByCategory[1].count").value(3))
                .andExpect(jsonPath("$.agents[0].name").value("Bruno"))
                .andExpect(jsonPath("$.agents[0].resolvedInPeriod").value(2))
                .andExpect(jsonPath("$.agents[1].name").value("Carla"))
                .andExpect(jsonPath("$.daily.length()").value(30));
    }

    @Test
    void emptyPeriodHasNullRates() throws Exception {
        mvc.perform(get("/api/dashboard").header("Authorization", bearer(carla)))
                .andExpect(jsonPath("$.slaMetPercentage").doesNotExist())
                .andExpect(jsonPath("$.averageResolutionHours").doesNotExist());
    }

    @Test
    void countsDaysInTheConfiguredTimeZone() throws Exception {
        createTicket(ana, "Hoje", "LOW");
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneId.of("America/Sao_Paulo"));

        mvc.perform(get("/api/dashboard").param("from", today.toString()).param("to", today.toString())
                        .header("Authorization", bearer(carla)))
                .andExpect(jsonPath("$.daily.length()").value(1))
                .andExpect(jsonPath("$.daily[0].date").value(today.toString()))
                .andExpect(jsonPath("$.daily[0].opened").value(1));
    }

    @Test
    void rejectsInvalidPeriods() throws Exception {
        mvc.perform(get("/api/dashboard").param("from", "2026-01-10").param("to", "2026-01-01")
                        .header("Authorization", bearer(carla)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/dashboard").param("from", "2026-01-01").param("to", "2026-06-01")
                        .header("Authorization", bearer(carla)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyManagers() throws Exception {
        mvc.perform(get("/api/dashboard").header("Authorization", bearer(bruno)))
                .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: Rodar e ver falhar**

```bash
cd backend && ./mvnw test -Dtest=DashboardApiTest; cd ..
```

Expected: FALHA — `404` onde se espera `200` (o endpoint ainda não existe).

- [ ] **Step 3: Implementar**

`backend/src/main/java/com/ticketflow/dashboard/DashboardResponse.java`

```java
package com.ticketflow.dashboard;

import com.ticketflow.ticket.TicketStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record DashboardResponse(
        LocalDate from,
        LocalDate to,
        Map<TicketStatus, Long> ticketsByStatus,
        long overdueNow,
        long resolvedInPeriod,
        Double slaMetPercentage,
        Double averageResolutionHours,
        List<CategoryCount> openedByCategory,
        List<AgentStats> agents,
        List<DailyCount> daily) {

    public record CategoryCount(String category, long count) {
    }

    public record AgentStats(Long id, String name, long activeAssigned, long resolvedInPeriod) {
    }

    public record DailyCount(LocalDate date, long opened, long resolved) {
    }
}
```

`backend/src/main/java/com/ticketflow/dashboard/DashboardService.java`

```java
package com.ticketflow.dashboard;

import com.ticketflow.common.ApiException;
import com.ticketflow.dashboard.DashboardResponse.AgentStats;
import com.ticketflow.dashboard.DashboardResponse.CategoryCount;
import com.ticketflow.dashboard.DashboardResponse.DailyCount;
import com.ticketflow.ticket.TicketStatus;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Report queries in plain SQL: aggregations are what SQL does best. */
@Service
@Transactional(readOnly = true)
public class DashboardService {

    static final int DEFAULT_DAYS = 30;
    static final int MAX_DAYS = 90;

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    private final ZoneId zone;

    public DashboardService(NamedParameterJdbcTemplate jdbc, Clock clock, @Value("${app.zone}") ZoneId zone) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.zone = zone;
    }

    public DashboardResponse build(LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.ofInstant(clock.instant(), zone);
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_DAYS - 1);
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (days < 1 || days > MAX_DAYS) {
            throw ApiException.badRequest("Período inválido: use de 1 a " + MAX_DAYS + " dias, com início antes do fim.");
        }

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("from", toTimestamp(start.atStartOfDay(zone).toInstant()))
                .addValue("to", toTimestamp(end.plusDays(1).atStartOfDay(zone).toInstant()))
                .addValue("now", toTimestamp(clock.instant()))
                .addValue("zone", zone.getId());

        Map<String, Object> resolved = jdbc.queryForMap("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE NOT sla_breached) AS met,
                       avg(extract(EPOCH FROM (resolved_at - created_at))) / 3600 AS avg_hours
                FROM tickets
                WHERE resolved_at >= :from AND resolved_at < :to
                """, params);
        long resolvedTotal = ((Number) resolved.get("total")).longValue();
        long resolvedMet = ((Number) resolved.get("met")).longValue();
        Number avgHours = (Number) resolved.get("avg_hours");

        return new DashboardResponse(
                start,
                end,
                ticketsByStatus(),
                jdbc.queryForObject("""
                        SELECT count(*) FROM tickets
                        WHERE status IN ('OPEN', 'IN_PROGRESS') AND due_at <= :now
                        """, params, Long.class),
                resolvedTotal,
                resolvedTotal == 0 ? null : round(100.0 * resolvedMet / resolvedTotal),
                avgHours == null ? null : round(avgHours.doubleValue()),
                openedByCategory(params),
                agents(params),
                daily(params, start, end));
    }

    private Map<TicketStatus, Long> ticketsByStatus() {
        Map<TicketStatus, Long> counts = new EnumMap<>(TicketStatus.class);
        for (TicketStatus status : TicketStatus.values()) {
            counts.put(status, 0L);
        }
        jdbc.query("SELECT status, count(*) AS total FROM tickets GROUP BY status", rs -> {
            counts.put(TicketStatus.valueOf(rs.getString("status")), rs.getLong("total"));
        });
        return counts;
    }

    private List<CategoryCount> openedByCategory(MapSqlParameterSource params) {
        return jdbc.query("""
                SELECT c.name, count(t.id) AS total
                FROM categories c
                LEFT JOIN tickets t ON t.category_id = c.id AND t.created_at >= :from AND t.created_at < :to
                GROUP BY c.id, c.name
                ORDER BY c.id
                """, params, (rs, row) -> new CategoryCount(rs.getString("name"), rs.getLong("total")));
    }

    private List<AgentStats> agents(MapSqlParameterSource params) {
        return jdbc.query("""
                SELECT u.id, u.name,
                       count(t.id) FILTER (WHERE t.status IN ('OPEN', 'IN_PROGRESS', 'WAITING_REQUESTER')) AS active,
                       count(t.id) FILTER (WHERE t.resolved_at >= :from AND t.resolved_at < :to) AS resolved
                FROM users u
                LEFT JOIN tickets t ON t.assignee_id = u.id
                WHERE u.active AND u.role IN ('AGENT', 'MANAGER')
                GROUP BY u.id, u.name
                ORDER BY u.name
                """, params, (rs, row) -> new AgentStats(
                rs.getLong("id"), rs.getString("name"), rs.getLong("active"), rs.getLong("resolved")));
    }

    /** One entry per day of the period, including days with zero tickets. */
    private List<DailyCount> daily(MapSqlParameterSource params, LocalDate start, LocalDate end) {
        Map<LocalDate, Long> opened = countPerDay("created_at", params);
        Map<LocalDate, Long> resolved = countPerDay("resolved_at", params);
        List<DailyCount> days = new ArrayList<>();
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            days.add(new DailyCount(day, opened.getOrDefault(day, 0L), resolved.getOrDefault(day, 0L)));
        }
        return days;
    }

    private Map<LocalDate, Long> countPerDay(String column, MapSqlParameterSource params) {
        // column is one of two constants above, never user input.
        String sql = """
                SELECT CAST(%1$s AT TIME ZONE :zone AS DATE) AS day, count(*) AS total
                FROM tickets
                WHERE %1$s >= :from AND %1$s < :to
                GROUP BY day
                """.formatted(column);
        Map<LocalDate, Long> counts = new HashMap<>();
        jdbc.query(sql, params, rs -> {
            counts.put(rs.getObject("day", LocalDate.class), rs.getLong("total"));
        });
        return counts;
    }

    private static Timestamp toTimestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
```

`backend/src/main/java/com/ticketflow/dashboard/DashboardController.java`

```java
package com.ticketflow.dashboard;

import java.time.LocalDate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DashboardController {

    private final DashboardService dashboard;

    public DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    /** from/to are ISO dates (2026-09-28), both inclusive. Default: the last 30 days. */
    @GetMapping("/api/dashboard")
    @PreAuthorize("hasRole('MANAGER')")
    public DashboardResponse get(@RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        return dashboard.build(from, to);
    }
}
```

- [ ] **Step 4: Rodar e ver passar**

```bash
cd backend && ./mvnw test -Dtest=DashboardApiTest; cd ..
```

Expected: `Tests run: 5, Failures: 0` e `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat: add manager dashboard"
```

---

### Task 12: Dados de demonstração e reset diário

**Objetivo:** no profile `demo`, ao subir a aplicação, criar 5 usuários demo (senha `demo1234`) e 30 chamados com datas relativas a agora, cobrindo todos os status e situações de SLA; recriar tudo se o último reset tiver mais de 24h.

**Conceitos:** `ApplicationRunner` + `@Profile`; armadilha do `@Transactional` em auto-invocação (chamada dentro do mesmo objeto não passa pelo proxy do Spring → usamos `TransactionTemplate`); `TRUNCATE` **sem** `RESTART IDENTITY` para um token antigo nunca apontar para outro usuário; reaproveitar os métodos do domínio para gerar dados coerentes.

**Files:**
- Create: `backend/src/main/resources/db/migration/V6__create_demo_state.sql`
- Create: `demo/DemoDataSeeder.java`
- Test: `demo/DemoDataSeederTest.java`

**Interfaces:**
- Produces: contas `solicitante@ticketflow.demo`, `atendente@ticketflow.demo`, `gestor@ticketflow.demo` (botões de login do frontend) + `elisa@` e `diego@ticketflow.demo`; `DemoDataSeeder.DEMO_PASSWORD = "demo1234"`; `resetIfDue(): boolean`.

- [ ] **Step 1: Escrever o teste**

`backend/src/test/java/com/ticketflow/demo/DemoDataSeederTest.java`

```java
package com.ticketflow.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.category.CategoryRepository;
import com.ticketflow.comment.CommentRepository;
import com.ticketflow.history.HistoryRecorder;
import com.ticketflow.sla.SlaCalculator;
import com.ticketflow.support.IntegrationTest;
import com.ticketflow.ticket.TicketRepository;
import com.ticketflow.user.Role;
import com.ticketflow.user.User;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/** The seeder bean only exists in the "demo" profile, so the test builds it by hand. */
class DemoDataSeederTest extends IntegrationTest {

    @Autowired CategoryRepository categories;
    @Autowired TicketRepository tickets;
    @Autowired CommentRepository comments;
    @Autowired HistoryRecorder history;
    @Autowired SlaCalculator sla;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired TransactionTemplate transaction;

    DemoDataSeeder seeder;

    @BeforeEach
    void createSeeder() {
        seeder = new DemoDataSeeder(jdbc, userRepository, categories, tickets, comments, history, sla,
                passwordEncoder, clock, transaction);
    }

    long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    @Test
    void seedsDemoUsersAndTicketsInEverySlaSituation() throws Exception {
        assertThat(seeder.resetIfDue()).isTrue();

        assertThat(count("users")).isEqualTo(5);
        assertThat(count("tickets")).isEqualTo(DemoDataSeeder.TICKET_COUNT);
        assertThat(userRepository.findAll()).allMatch(User::isDemo);

        String token = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email": "gestor@ticketflow.demo", "password": "demo1234"}
                        """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String bearer = "Bearer " + com.jayway.jsonpath.JsonPath.read(token, "$.token");
        for (String situation : new String[] {"OVERDUE", "AT_RISK"}) {
            String body = mvc.perform(get("/api/tickets").param("sla", situation).header("Authorization", bearer))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(readLong(body, "$.totalElements")).as(situation).isPositive();
        }
        mvc.perform(get("/api/tickets").param("status", "WAITING_REQUESTER").header("Authorization", bearer))
                .andExpect(jsonPath("$.content[0].sla").value("PAUSED"));
    }

    @Test
    void doesNotResetAgainWithin24Hours() {
        seeder.resetIfDue();
        User visitor = createUser("Visitante", Role.REQUESTER);

        clock.advance(Duration.ofHours(23));
        assertThat(seeder.resetIfDue()).isFalse();
        assertThat(userRepository.findById(visitor.getId())).isPresent();

        clock.advance(Duration.ofHours(2));
        assertThat(seeder.resetIfDue()).isTrue();
        assertThat(userRepository.findById(visitor.getId())).isEmpty();
    }

    @Test
    void resetNeverReusesIdsSoOldTokensStopWorking() throws Exception {
        seeder.resetIfDue();
        User visitor = createUser("Visitante", Role.REQUESTER);
        String oldToken = bearer(visitor);

        clock.advance(Duration.ofHours(25));
        seeder.resetIfDue();
        createUser("Outra Pessoa", Role.REQUESTER);

        mvc.perform(get("/api/auth/me").header("Authorization", oldToken)).andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: Rodar e ver falhar**

```bash
cd backend && ./mvnw test -Dtest=DemoDataSeederTest; cd ..
```

Expected: FALHA de compilação (`DemoDataSeeder` não existe).

- [ ] **Step 3: Implementar**

`backend/src/main/resources/db/migration/V6__create_demo_state.sql`

```sql
-- A single row remembering when the demo data was last reset.
CREATE TABLE demo_state (
    id            SMALLINT PRIMARY KEY CHECK (id = 1),
    last_reset_at TIMESTAMPTZ NOT NULL
);
```

`backend/src/main/java/com/ticketflow/demo/DemoDataSeeder.java`

```java
package com.ticketflow.demo;

import com.ticketflow.category.Category;
import com.ticketflow.category.CategoryRepository;
import com.ticketflow.comment.Comment;
import com.ticketflow.comment.CommentRepository;
import com.ticketflow.history.HistoryEventType;
import com.ticketflow.history.HistoryRecorder;
import com.ticketflow.sla.SlaCalculator;
import com.ticketflow.ticket.Priority;
import com.ticketflow.ticket.Ticket;
import com.ticketflow.ticket.TicketRepository;
import com.ticketflow.ticket.TicketStatus;
import com.ticketflow.user.Role;
import com.ticketflow.user.User;
import com.ticketflow.user.UserRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fills the public demo with users and ~30 tickets whose dates are relative to "now", so the
 * list always shows overdue, at-risk and on-track tickets. Runs at startup (profile "demo") and
 * resets everything when the last reset is older than 24h — the free host sleeps a lot, so
 * "at startup" happens often enough and needs no scheduler.
 */
@Component
@Profile("demo")
public class DemoDataSeeder implements ApplicationRunner {

    public static final String DEMO_PASSWORD = "demo1234";
    static final Duration RESET_INTERVAL = Duration.ofHours(24);
    static final int TICKET_COUNT = 30;

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private static final String[][] SAMPLES = {
            {"Sem acesso ao sistema financeiro", "Depois da troca de senha não consigo entrar no sistema."},
            {"Impressora do 2º andar não imprime", "A impressora mostra 'papel atolado', mas não há papel preso."},
            {"Notebook muito lento", "O notebook demora vários minutos para abrir qualquer programa."},
            {"Instalar software de design", "Preciso do editor de imagens instalado para o projeto novo."},
            {"Reembolso de despesa não aparece", "Enviei o reembolso há uma semana e ele não aparece no sistema."},
            {"VPN desconecta toda hora", "A VPN cai a cada 10 minutos quando trabalho de casa."},
            {"Criar acesso para estagiário", "O estagiário começa segunda e precisa de e-mail e acesso à rede."},
            {"Monitor piscando", "O monitor secundário pisca e às vezes apaga."},
            {"Erro ao gerar nota fiscal", "O sistema mostra 'erro 500' ao emitir a nota fiscal."},
            {"Trocar teclado quebrado", "Algumas teclas do teclado pararam de funcionar."},
    };

    /** Fraction of the deadline already used by running tickets: on track, at risk, overdue. */
    private static final double[] RUNNING_AGE = {0.3, 0.85, 1.5};

    private enum Scenario { OPEN, IN_PROGRESS, WAITING, RESOLVED, CLOSED }

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final CategoryRepository categories;
    private final TicketRepository tickets;
    private final CommentRepository comments;
    private final HistoryRecorder history;
    private final SlaCalculator sla;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public DemoDataSeeder(JdbcTemplate jdbc, UserRepository users, CategoryRepository categories,
            TicketRepository tickets, CommentRepository comments, HistoryRecorder history, SlaCalculator sla,
            PasswordEncoder passwordEncoder, Clock clock, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.users = users;
        this.categories = categories;
        this.tickets = tickets;
        this.comments = comments;
        this.history = history;
        this.sla = sla;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.transaction = transaction;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (resetIfDue()) {
            log.info("Demo data reset.");
        }
    }

    /** Returns true when the data was (re)created. */
    public boolean resetIfDue() {
        Instant now = clock.instant();
        List<Timestamp> lastReset = jdbc.queryForList(
                "SELECT last_reset_at FROM demo_state WHERE id = 1", Timestamp.class);
        if (!lastReset.isEmpty() && lastReset.getFirst().toInstant().plus(RESET_INTERVAL).isAfter(now)) {
            return false;
        }
        // TransactionTemplate instead of @Transactional: a method calling another method of the
        // same object bypasses the Spring proxy, so @Transactional there would be silently ignored.
        transaction.executeWithoutResult(status -> {
            wipe();
            seed(now);
            jdbc.update("""
                    INSERT INTO demo_state (id, last_reset_at) VALUES (1, ?)
                    ON CONFLICT (id) DO UPDATE SET last_reset_at = EXCLUDED.last_reset_at
                    """, Timestamp.from(now));
        });
        return true;
    }

    /**
     * No RESTART IDENTITY on purpose: ids keep growing, so an old token (whose "sub" is a deleted
     * user id) can never point to a different, newly created user.
     */
    private void wipe() {
        jdbc.execute("TRUNCATE attachment_content, attachments, comments, ticket_history, tickets, users CASCADE");
    }

    private void seed(Instant now) {
        String hash = passwordEncoder.encode(DEMO_PASSWORD);
        User ana = demoUser("Ana Souza (Solicitante)", "solicitante@ticketflow.demo", Role.REQUESTER, hash, now);
        User elisa = demoUser("Elisa Rocha", "elisa@ticketflow.demo", Role.REQUESTER, hash, now);
        User bruno = demoUser("Bruno Lima (Atendente)", "atendente@ticketflow.demo", Role.AGENT, hash, now);
        User diego = demoUser("Diego Alves", "diego@ticketflow.demo", Role.AGENT, hash, now);
        demoUser("Carla Mendes (Gestora)", "gestor@ticketflow.demo", Role.MANAGER, hash, now);

        List<Category> allCategories = categories.findAllByOrderByIdAsc();
        Random random = new Random(42);
        for (int i = 0; i < TICKET_COUNT; i++) {
            Scenario scenario = Scenario.values()[i % Scenario.values().length];
            Priority priority = Priority.values()[random.nextInt(Priority.values().length)];
            User requester = random.nextBoolean() ? ana : elisa;
            User agent = random.nextBoolean() ? bruno : diego;
            Category category = allCategories.get(random.nextInt(allCategories.size()));
            String[] sample = SAMPLES[i % SAMPLES.length];

            Duration age = scenario == Scenario.OPEN || scenario == Scenario.IN_PROGRESS
                    ? scale(sla.deadlineFor(priority), RUNNING_AGE[i % RUNNING_AGE.length])
                    : Duration.ofHours(6 + random.nextInt(24 * 6));
            Instant createdAt = now.minus(age);
            Ticket ticket = tickets.save(
                    new Ticket(sample[0], sample[1], priority, category, requester, createdAt, sla));
            history.record(ticket, requester, HistoryEventType.CREATED, createdAt);
            if (scenario != Scenario.OPEN) {
                play(ticket, scenario, agent, requester, createdAt, age);
            }
        }
    }

    /** Replays the lifecycle with the real domain methods, at moments between creation and now. */
    private void play(Ticket ticket, Scenario scenario, User agent, User requester, Instant createdAt,
            Duration age) {
        Instant assignedAt = createdAt.plus(scale(age, 0.1));
        Instant secondStep = createdAt.plus(scale(age, 0.4));
        Instant thirdStep = createdAt.plus(scale(age, 0.7));

        ticket.assign(agent, assignedAt, sla);
        history.record(ticket, agent, HistoryEventType.ASSIGNED, "assignee", null, agent.getName(), assignedAt);
        history.record(ticket, agent, HistoryEventType.STATUS_CHANGED, "status", "OPEN", "IN_PROGRESS", assignedAt);

        switch (scenario) {
            case WAITING -> {
                comments.save(new Comment(ticket, agent, "Pode enviar um print da tela com o erro?", secondStep));
                history.record(ticket, agent, HistoryEventType.COMMENT_ADDED, secondStep);
                changeStatus(ticket, TicketStatus.WAITING_REQUESTER, agent, secondStep);
            }
            case RESOLVED -> {
                comments.save(new Comment(ticket, agent, "Resolvido. Pode verificar, por favor?", secondStep));
                history.record(ticket, agent, HistoryEventType.COMMENT_ADDED, secondStep);
                changeStatus(ticket, TicketStatus.RESOLVED, agent, secondStep);
            }
            case CLOSED -> {
                changeStatus(ticket, TicketStatus.RESOLVED, agent, secondStep);
                changeStatus(ticket, TicketStatus.CLOSED, requester, thirdStep);
            }
            default -> {
                // IN_PROGRESS: assigning was enough.
            }
        }
    }

    private void changeStatus(Ticket ticket, TicketStatus target, User actor, Instant at) {
        String old = ticket.getStatus().name();
        ticket.changeStatus(target, at, sla);
        history.record(ticket, actor, HistoryEventType.STATUS_CHANGED, "status", old, target.name(), at);
    }

    private User demoUser(String name, String email, Role role, String passwordHash, Instant now) {
        User user = new User(name, email, passwordHash, role, now);
        user.markAsDemo();
        return users.save(user);
    }

    private static Duration scale(Duration duration, double factor) {
        return Duration.ofSeconds((long) (duration.toSeconds() * factor));
    }
}
```

- [ ] **Step 4: Rodar e ver passar**

```bash
cd backend && ./mvnw test -Dtest=DemoDataSeederTest; cd ..
```

Expected: `Tests run: 3, Failures: 0` e `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat: add demo data seeder with daily reset"
```

---

### Task 13: Imagem Docker, compose completo, README e verificação final

**Objetivo:** imagem Docker do backend (build em dois estágios, usuário sem root, JVM limitada a 75% da memória), `docker compose up --build` subindo banco + API com dados demo, README com instruções de execução e badge do CI, e a suíte inteira verde.

**Conceitos:** multi-stage build (imagem final só com JRE + jar), por que não rodar como root, `-XX:MaxRAMPercentage` em container com pouca memória, `depends_on` com `healthcheck`.

**Files:**
- Create: `backend/Dockerfile`, `backend/.dockerignore`
- Modify (substituir arquivo inteiro): `docker-compose.yml`, `README.md`

- [ ] **Step 1: Dockerfile e compose completo**

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

# Stage 2: run it on a smaller JRE image, as a non-root user.
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app app
COPY --from=build /app/target/*.jar app.jar
USER app
EXPOSE 8080
# Uses at most 75% of the container memory for the heap (the free host has ~512 MB).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-jar", "app.jar"]
```

`backend/.dockerignore`

```text
target/
.idea/
*.iml
```

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

volumes:
  db-data:
```

- [ ] **Step 2: Subir tudo e testar a API de verdade**

```bash
docker compose up -d --build
curl -s localhost:8080/actuator/health
curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' -d '{"email":"gestor@ticketflow.demo","password":"demo1234"}'
```

Expected: `{"groups":["liveness","readiness"],"status":"UP"}` (pode levar ~20s para subir) e um JSON com `token` e `"role":"MANAGER"`. Abrir `http://localhost:8080/swagger-ui` no navegador e conferir os endpoints. Depois: `docker compose down`.

- [ ] **Step 3: README**

`README.md`

````markdown
# ticket-flow

[![CI](https://github.com/lmaciel0/ticket-flow/actions/workflows/ci.yml/badge.svg)](https://github.com/lmaciel0/ticket-flow/actions/workflows/ci.yml)

Sistema de gestão de chamados com Spring Boot, PostgreSQL e React: perfis de acesso, SLA, anexos, histórico e dashboard.

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

Para rodar banco e API em containers, já com os dados de demonstração: `docker compose up --build`.

Contas de demonstração (senha `demo1234`):

| Perfil | E-mail |
|---|---|
| Solicitante | `solicitante@ticketflow.demo` |
| Atendente | `atendente@ticketflow.demo` |
| Gestor | `gestor@ticketflow.demo` |

## Testes

```bash
cd backend
./mvnw verify
```

Os testes de integração sobem um PostgreSQL real com Testcontainers, então o Docker precisa estar rodando.

## Documentação

- Especificação do MVP: [docs/specs/2026-09-28-ticket-flow-mvp-design.md](docs/specs/2026-09-28-ticket-flow-mvp-design.md)
- Planos de implementação: [docs/plans/](docs/plans/)

O link da demo publicada e a seção de decisões técnicas e trade-offs entram na etapa de deploy.
````

- [ ] **Step 4: Verificação final completa**

```bash
cd backend && ./mvnw verify; cd ..
```

Expected: `Tests run: 91, Failures: 0, Errors: 0, Skipped: 0` e `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add backend/Dockerfile backend/.dockerignore docker-compose.yml README.md
git commit -m "chore: add backend Docker image, full compose setup and README"
```

- [ ] **Step 6: Push e Pull Request — só com autorização do usuário**

Perguntar antes. Se autorizado: `git push -u origin feat/backend`, abrir o PR para `main` e conferir se o CI (badge) fica verde.

---

## Cobertura da spec neste plano

| Seção da spec | Onde |
|---|---|
| 2. Stack e convenções | Task 1 (pom, yml, `.gitattributes`), Global Constraints |
| 3. Arquitetura por funcionalidade, `Clock` injetado, `AttachmentStorage` | Tasks 1, 4, 9 |
| 4. Modelo de dados e índices | Tasks 2, 3, 5, 8, 9 (migrations V1–V5) |
| 5. Ciclo de vida e regras complementares | Tasks 4, 5, 6, 8 |
| 6. SLA (pausa, reabertura, prioridade, indicador, filtros) | Tasks 4, 5, 6, 7 |
| 7. Segurança (perfis, 404 x 403, JWT, cadastro, gestão de usuários) | Tasks 2, 6, 10 |
| 8. API (endpoints, filtros, paginação, concorrência, anexos, erros, painel, Swagger) | Tasks 2, 3, 6, 7, 8, 9, 10, 11 (+ Swagger na Task 1/2) |
| 10. Testes, dados demo, reset, repositório, compose, variáveis de ambiente | Tasks 1, 12, 13 |
| 9. Frontend | **Plano 2** |
| 10. Deploy (Render, Neon, CORS de produção) e 11. README de trade-offs | **Plano 3** (a imagem Docker e o compose já saem prontos na Task 13) |

## Depois deste plano

1. Abrir o PR `feat/backend` → `main` e ver o CI verde (com autorização do Roberto).
2. Escrever o **Plano 2 — Frontend**, a partir do Swagger real em `/swagger-ui`.
