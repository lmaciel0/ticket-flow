# Adapter S3/MinIO para anexos — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Adicionar um segundo adapter de `AttachmentStorage`, para S3 e MinIO, escolhido por `app.attachments.storage`, sem mudar o padrão (banco).

**Architecture:** `@ConditionalOnProperty` registra exatamente um adapter. `S3StorageConfig` monta o `S3Client` (AWS SDK v2) a partir de `S3StorageProperties`. `S3AttachmentStorage` grava em `attachments/{id}` e registra uma `TransactionSynchronization` que apaga o objeto se a transação for revertida. Os testes usam MinIO real via Testcontainers. O compose ganha um MinIO opcional (profile `s3` + override).

**Tech Stack:** Java 21, Spring Boot 4.1, AWS SDK for Java v2 (BOM 2.55.9), Testcontainers 2.0.5 (`testcontainers-minio`), Docker Compose.

**Spec:** `docs/specs/2026-10-01-s3-attachment-storage-design.md`

## Global Constraints

- `AttachmentService`, `AttachmentStorage` e o schema do banco **não mudam**.
- Sem configuração nova, o adapter ativo é o `DatabaseAttachmentStorage`, e os testes atuais passam **sem alteração**.
- Propriedades exatamente como na spec 3.1 (`app.attachments.storage`, `app.attachments.s3.*` e as variáveis `ATTACHMENTS_STORAGE`, `S3_*`).
- Chave do objeto: `attachments/{attachmentId}`.
- Objeto ausente no `load` vira `ApiException.notFound("Anexo não encontrado.")`, com log de erro.
- Falha na compensação (o `deleteObject` no rollback) só gera log de aviso, nunca exceção.
- Código em inglês; textos do README em português. Commits com `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Maven em `backend/` com `./mvnw` e Docker ligado.

## Review Focus

1. Rollback depois do `store`: o objeto precisa sumir. Coberto em `S3AttachmentStorageTest` (Tarefa 2).
2. `store` fora de transação (sem sincronização ativa) não pode lançar exceção ao registrar a compensação. Coberto no teste de ida e volta, que roda sem transação (Tarefa 2).
3. Linha de anexo sem objeto no bucket deve dar 404, nunca 500. Coberto em `S3AttachmentStorageTest` (Tarefa 2).
4. Propriedade ausente, `database` ou `s3` precisam escolher o adapter certo, inclusive sem nenhuma variável de ambiente. Coberto em `AttachmentStorageSelectionTest` (Tarefas 1 e 2).
5. O compose padrão não pode mudar: sem `--profile s3`, nada de MinIO. Coberto pelo `docker compose config` da Tarefa 3.

## Mapa de arquivos

| Arquivo | Ação | Responsabilidade |
|---|---|---|
| `backend/pom.xml` | alterar | BOM do AWS SDK, `s3`, `testcontainers-minio` |
| `backend/src/main/resources/application.yml` | alterar | bloco `app.attachments` |
| `backend/src/main/java/com/ticketflow/attachment/DatabaseAttachmentStorage.java` | alterar | `@ConditionalOnProperty` (padrão) |
| `backend/src/main/java/com/ticketflow/attachment/S3StorageProperties.java` | criar | propriedades `app.attachments.s3` |
| `backend/src/main/java/com/ticketflow/attachment/S3StorageConfig.java` | criar | `S3Client` e criação do bucket |
| `backend/src/main/java/com/ticketflow/attachment/S3AttachmentStorage.java` | criar | adapter S3 com compensação |
| `backend/src/test/java/com/ticketflow/attachment/AttachmentStorageSelectionTest.java` | criar | escolha do adapter |
| `backend/src/test/java/com/ticketflow/support/S3IntegrationTest.java` | criar | base com MinIO no Testcontainers |
| `backend/src/test/java/com/ticketflow/attachment/S3AttachmentStorageTest.java` | criar | adapter contra MinIO real |
| `backend/src/test/java/com/ticketflow/attachment/AttachmentS3ApiTest.java` | criar | fluxo de API no modo S3 |
| `docker-compose.yml` | alterar | serviço `minio` com profile `s3` |
| `docker-compose.s3.yml` | criar | override do backend para o modo S3 |
| `README.md` | alterar | decisão 7 e comando com MinIO |
| `docs/roadmap-de-evolucao.md` | alterar | marca 1.3 como implementado |

---

### Task 1: Seleção do adapter por configuração

**Files:**
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/java/com/ticketflow/attachment/DatabaseAttachmentStorage.java`
- Test: `backend/src/test/java/com/ticketflow/attachment/AttachmentStorageSelectionTest.java`

**Interfaces:**
- Produces: a propriedade `app.attachments.storage` (`database` | `s3`, padrão `database`), usada pelos `@ConditionalOnProperty` da Tarefa 2.

- [ ] **Step 1: Branch e documentos**

```bash
git switch main
git pull
git switch -c feat/s3-attachment-storage
git add docs/specs/2026-10-01-s3-attachment-storage-design.md docs/plans/2026-10-01-s3-attachment-storage.md
git commit -m "docs: add the S3 attachment storage spec and plan

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 2: Teste que falha**

`backend/src/test/java/com/ticketflow/attachment/AttachmentStorageSelectionTest.java`:

```java
package com.ticketflow.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

/** Exactly one AttachmentStorage adapter is active, chosen by app.attachments.storage. */
class AttachmentStorageSelectionTest {

    /** Loads application.yml like the real application, with only the database adapter registered. */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withBean(JdbcTemplate.class, JdbcTemplate::new)
            .withUserConfiguration(DatabaseAttachmentStorage.class);

    @Test
    void usesTheDatabaseByDefault() {
        runner.run(context -> assertThat(context).hasSingleBean(DatabaseAttachmentStorage.class));
    }

    @Test
    void usesTheDatabaseWhenAskedExplicitly() {
        runner.withPropertyValues("app.attachments.storage=database")
                .run(context -> assertThat(context).hasSingleBean(DatabaseAttachmentStorage.class));
    }

    @Test
    void stepsAsideWhenS3IsChosen() {
        runner.withPropertyValues("app.attachments.storage=s3")
                .run(context -> assertThat(context).doesNotHaveBean(DatabaseAttachmentStorage.class));
    }

    @Test
    void theEnvironmentVariableChoosesTheAdapter() {
        runner.withPropertyValues("ATTACHMENTS_STORAGE=s3")
                .run(context -> assertThat(context).doesNotHaveBean(DatabaseAttachmentStorage.class));
    }
}
```

- [ ] **Step 3: Rodar e ver falhar**

Run (em `backend/`): `./mvnw test -Dtest=AttachmentStorageSelectionTest`
Expected: FAIL em `stepsAsideWhenS3IsChosen` e `theEnvironmentVariableChoosesTheAdapter` (o bean continua existindo). Os dois testes de "banco" passam; é esperado.

- [ ] **Step 4: Implementar**

Em `application.yml`, dentro de `app:` (depois do bloco `sla:`):

```yaml
  attachments:
    # database (default): bytes in PostgreSQL. s3: bytes in an S3-compatible bucket (AWS S3, MinIO...).
    storage: ${ATTACHMENTS_STORAGE:database}
    s3:
      endpoint: ${S3_ENDPOINT:}          # empty = real AWS S3; MinIO e.g. http://localhost:9000
      region: ${S3_REGION:us-east-1}
      bucket: ${S3_BUCKET:ticket-flow-attachments}
      access-key: ${S3_ACCESS_KEY:}
      secret-key: ${S3_SECRET_KEY:}
      path-style: ${S3_PATH_STYLE:false} # true for MinIO
      create-bucket: ${S3_CREATE_BUCKET:false}
```

Em `DatabaseAttachmentStorage.java`:

```java
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
```

```java
/** Stores the bytes in the attachment_content table (bytea column). The default adapter. */
@Component
@ConditionalOnProperty(name = "app.attachments.storage", havingValue = "database", matchIfMissing = true)
public class DatabaseAttachmentStorage implements AttachmentStorage {
```

> No Spring Boot 4, `ConditionalOnProperty` continua em `org.springframework.boot.autoconfigure.condition`. Se a compilação não o encontrar, procure a classe no classpath (`spring-boot-autoconfigure`) e registre um *Ruling* com o pacote correto.

- [ ] **Step 5: Rodar e ver passar; suíte completa**

Run (em `backend/`): `./mvnw test -Dtest=AttachmentStorageSelectionTest`
Expected: PASS (4 testes).

Run (em `backend/`): `./mvnw verify`
Expected: BUILD SUCCESS, os testes atuais sem alteração.

- [ ] **Step 6: Commit**

```bash
git add backend/src
git commit -m "feat: choose the attachment storage adapter with app.attachments.storage

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Adapter S3 com compensação no rollback

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/src/main/java/com/ticketflow/attachment/S3StorageProperties.java`
- Create: `backend/src/main/java/com/ticketflow/attachment/S3StorageConfig.java`
- Create: `backend/src/main/java/com/ticketflow/attachment/S3AttachmentStorage.java`
- Create: `backend/src/test/java/com/ticketflow/support/S3IntegrationTest.java`
- Test: `backend/src/test/java/com/ticketflow/attachment/S3AttachmentStorageTest.java`
- Test: `backend/src/test/java/com/ticketflow/attachment/AttachmentS3ApiTest.java`

**Interfaces:**
- Consumes: `app.attachments.storage` (Tarefa 1); `IntegrationTest` (`mvc`, `createUser`, `createTicket`, `bearer`, `readLong`).
- Produces: `S3StorageProperties(String endpoint, String region, String bucket, String accessKey, String secretKey, boolean pathStyle, boolean createBucket)`; bean `S3Client`; `S3AttachmentStorage.key(Long) → String` (`"attachments/" + id`); `S3IntegrationTest.MINIO`.

- [ ] **Step 1: Dependências**

Em `backend/pom.xml`:

1. Em `<properties>`: `<aws-sdk.version>2.55.9</aws-sdk.version>`
2. Antes de `<dependencies>` (depois de `</properties>`):

```xml
	<dependencyManagement>
		<dependencies>
			<dependency>
				<groupId>software.amazon.awssdk</groupId>
				<artifactId>bom</artifactId>
				<version>${aws-sdk.version}</version>
				<type>pom</type>
				<scope>import</scope>
			</dependency>
		</dependencies>
	</dependencyManagement>
```

3. Em `<dependencies>` (depois do `openpdf`):

```xml
		<dependency>
			<groupId>software.amazon.awssdk</groupId>
			<artifactId>s3</artifactId>
		</dependency>
```

4. Junto das dependências de teste do Testcontainers (depois de `testcontainers-postgresql`), sem versão (o Spring Boot gerencia o BOM do Testcontainers):

```xml
		<dependency>
			<groupId>org.testcontainers</groupId>
			<artifactId>testcontainers-minio</artifactId>
			<scope>test</scope>
		</dependency>
```

- [ ] **Step 2: Base de teste com MinIO**

`backend/src/test/java/com/ticketflow/support/S3IntegrationTest.java`:

```java
package com.ticketflow.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * API tests in S3 mode: a real MinIO in Docker, shared by every subclass (one container, one context).
 * The properties are set before the context starts, so @ConditionalOnProperty already sees "s3".
 */
public abstract class S3IntegrationTest extends IntegrationTest {

    protected static final MinIOContainer MINIO = new MinIOContainer(DockerImageName.parse("minio/minio:latest"));

    static {
        MINIO.start();
    }

    @DynamicPropertySource
    static void s3Properties(DynamicPropertyRegistry registry) {
        registry.add("app.attachments.storage", () -> "s3");
        registry.add("app.attachments.s3.endpoint", MINIO::getS3URL);
        registry.add("app.attachments.s3.access-key", MINIO::getUserName);
        registry.add("app.attachments.s3.secret-key", MINIO::getPassword);
        registry.add("app.attachments.s3.path-style", () -> "true");
        registry.add("app.attachments.s3.create-bucket", () -> "true");
    }
}
```

> `MinIOContainer` está em `org.testcontainers.containers` no módulo 2.0.5 (conferido no jar). A tag `latest` evita fixar uma versão que pode não existir; se quiser reprodutibilidade, fixe uma tag `RELEASE.*` existente e registre um *Ruling*.

- [ ] **Step 3: Testes que falham**

`backend/src/test/java/com/ticketflow/attachment/S3AttachmentStorageTest.java`:

```java
package com.ticketflow.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketflow.common.ApiException;
import com.ticketflow.support.S3IntegrationTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

class S3AttachmentStorageTest extends S3IntegrationTest {

    static final byte[] DATA = "%PDF-1.7 conteudo".getBytes(StandardCharsets.US_ASCII);

    @Autowired AttachmentStorage storage;
    @Autowired S3Client s3;
    @Autowired S3StorageProperties properties;
    @Autowired PlatformTransactionManager transactionManager;

    boolean objectExists(long attachmentId) {
        try {
            s3.headObject(b -> b.bucket(properties.bucket()).key(S3AttachmentStorage.key(attachmentId)));
            return true;
        } catch (NoSuchKeyException missing) {
            return false;
        }
    }

    @Test
    void theS3AdapterIsTheActiveOne() {
        assertThat(storage).isInstanceOf(S3AttachmentStorage.class);
    }

    @Test
    void storesAndLoadsTheSameBytes() {
        storage.store(9001L, DATA); // no transaction: nothing to compensate, must not fail

        assertThat(storage.load(9001L)).isEqualTo(DATA);
        assertThat(objectExists(9001L)).isTrue();
    }

    @Test
    void aCommittedTransactionKeepsTheObject() {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> storage.store(9002L, DATA));

        assertThat(objectExists(9002L)).isTrue();
    }

    @Test
    void aRolledBackTransactionDeletesTheObject() {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            storage.store(9003L, DATA);
            assertThat(objectExists(9003L)).isTrue(); // already uploaded: S3 is not part of the transaction
            tx.setRollbackOnly();
        });

        assertThat(objectExists(9003L)).isFalse();
    }

    @Test
    void aMissingObjectIsNotFound() {
        assertThatThrownBy(() -> storage.load(424242L))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
```

> `headObject` responde 404 sem corpo, e o SDK traduz isso em `NoSuchKeyException`, que é subclasse de `S3Exception`. Se a versão do SDK lançar um `S3Exception` genérico com `statusCode() == 404`, capture `S3Exception` e confira o status. Registre um *Ruling*.

`backend/src/test/java/com/ticketflow/attachment/AttachmentS3ApiTest.java`:

```java
package com.ticketflow.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.support.S3IntegrationTest;
import com.ticketflow.user.Role;
import com.ticketflow.user.User;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.services.s3.S3Client;

/** Same API as AttachmentApiTest, other adapter: AttachmentService did not change to make this work. */
class AttachmentS3ApiTest extends S3IntegrationTest {

    static final byte[] PDF = "%PDF-1.7 conteudo do relatorio".getBytes(StandardCharsets.US_ASCII);

    @Autowired S3Client s3;
    @Autowired S3StorageProperties properties;

    @Test
    void uploadAndDownloadGoThroughTheBucket() throws Exception {
        User ana = createUser("Ana", Role.REQUESTER);
        long ticketId = createTicket(ana, "Impressora", "LOW");
        MockMultipartFile file = new MockMultipartFile("file", "relatorio.pdf", "application/octet-stream", PDF);

        String body = mvc.perform(multipart("/api/tickets/{id}/attachments", ticketId)
                        .file(file).header("Authorization", bearer(ana)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long attachmentId = readLong(body, "$.id");

        byte[] stored = s3.getObjectAsBytes(b -> b.bucket(properties.bucket())
                .key(S3AttachmentStorage.key(attachmentId))).asByteArray();
        assertThat(stored).isEqualTo(PDF);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attachment_content", Long.class)).isZero();

        mvc.perform(get("/api/tickets/{id}/attachments", ticketId).header("Authorization", bearer(ana)))
                .andExpect(jsonPath("$[0].filename").value("relatorio.pdf"));
        mvc.perform(get("/api/attachments/{id}", attachmentId).header("Authorization", bearer(ana)))
                .andExpect(status().isOk())
                .andExpect(content().bytes(PDF));
    }
}
```

- [ ] **Step 4: Rodar e ver falhar**

Run (em `backend/`): `./mvnw test -Dtest="S3AttachmentStorageTest,AttachmentS3ApiTest"`
Expected: FAIL na compilação (`cannot find symbol ... S3AttachmentStorage` / `S3StorageProperties`).

- [ ] **Step 5: Propriedades**

`backend/src/main/java/com/ticketflow/attachment/S3StorageProperties.java`:

```java
package com.ticketflow.attachment;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Where the S3 adapter keeps the files (app.attachments.s3 in application.yml). */
@ConfigurationProperties("app.attachments.s3")
public record S3StorageProperties(
        /** Empty for real AWS S3; the MinIO address otherwise, e.g. http://localhost:9000. */
        String endpoint,
        String region,
        String bucket,
        /** Empty to use the default AWS credentials chain (environment, instance role...). */
        String accessKey,
        String secretKey,
        /** MinIO serves buckets as a path (host/bucket/key) instead of a subdomain. */
        boolean pathStyle,
        boolean createBucket) {
}
```

- [ ] **Step 6: Configuração do cliente**

`backend/src/main/java/com/ticketflow/attachment/S3StorageConfig.java`:

```java
package com.ticketflow.attachment;

import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

/** Builds the S3 client only when app.attachments.storage=s3; the default (database) needs none of this. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.attachments.storage", havingValue = "s3")
@EnableConfigurationProperties(S3StorageProperties.class)
public class S3StorageConfig {

    private static final Logger log = LoggerFactory.getLogger(S3StorageConfig.class);

    @Bean(destroyMethod = "close")
    S3Client s3Client(S3StorageProperties properties) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(properties.region()))
                .forcePathStyle(properties.pathStyle());
        if (StringUtils.hasText(properties.accessKey())) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())));
        }
        if (StringUtils.hasText(properties.endpoint())) {
            builder.endpointOverride(URI.create(properties.endpoint()));
        }
        S3Client client = builder.build();
        if (properties.createBucket()) {
            createBucketIfMissing(client, properties.bucket());
        }
        return client;
    }

    private static void createBucketIfMissing(S3Client client, String bucket) {
        try {
            client.headBucket(b -> b.bucket(bucket));
        } catch (NoSuchBucketException missing) {
            client.createBucket(b -> b.bucket(bucket));
            log.info("Created attachments bucket {}", bucket);
        }
    }
}
```

> Se o MinIO recusar uploads com erro de checksum (versões novas do SDK calculam CRC por padrão), adicione `.requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)` ao builder (`software.amazon.awssdk.core.checksums.RequestChecksumCalculation`) e registre um *Ruling*.

- [ ] **Step 7: O adapter**

`backend/src/main/java/com/ticketflow/attachment/S3AttachmentStorage.java`:

```java
package com.ticketflow.attachment;

import com.ticketflow.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * Stores the bytes in an S3-compatible bucket (AWS S3, MinIO, R2...), under attachments/{id}.
 *
 * <p>S3 is not part of the database transaction: the upload happens at once. If the transaction is
 * then rolled back (for example the history insert fails), the attachment row disappears but the object
 * would stay. So the upload registers a compensating action: on rollback, delete the object.
 */
@Component
@ConditionalOnProperty(name = "app.attachments.storage", havingValue = "s3")
public class S3AttachmentStorage implements AttachmentStorage {

    private static final Logger log = LoggerFactory.getLogger(S3AttachmentStorage.class);

    private final S3Client s3;
    private final String bucket;

    public S3AttachmentStorage(S3Client s3, S3StorageProperties properties) {
        this.s3 = s3;
        this.bucket = properties.bucket();
    }

    static String key(Long attachmentId) {
        return "attachments/" + attachmentId;
    }

    @Override
    public void store(Long attachmentId, byte[] data) {
        String key = key(attachmentId);
        s3.putObject(b -> b.bucket(bucket).key(key), RequestBody.fromBytes(data));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        deleteQuietly(key);
                    }
                }
            });
        }
    }

    @Override
    public byte[] load(Long attachmentId) {
        try {
            return s3.getObjectAsBytes(b -> b.bucket(bucket).key(key(attachmentId))).asByteArray();
        } catch (NoSuchKeyException missing) {
            log.error("Attachment {} has no object {} in bucket {}", attachmentId, key(attachmentId), bucket);
            throw ApiException.notFound("Anexo não encontrado.");
        }
    }

    /** The transaction is already over: a failure here can only be logged (the object is orphaned). */
    private void deleteQuietly(String key) {
        try {
            s3.deleteObject(b -> b.bucket(bucket).key(key));
        } catch (SdkException e) {
            log.warn("Could not delete orphaned object {} from bucket {} after a rollback", key, bucket, e);
        }
    }
}
```

- [ ] **Step 8: Rodar e ver passar**

Run (em `backend/`): `./mvnw test -Dtest="S3AttachmentStorageTest,AttachmentS3ApiTest,AttachmentStorageSelectionTest,AttachmentApiTest"`
Expected: PASS em todos. O `AttachmentApiTest` continua no modo banco, porque não estende `S3IntegrationTest`.

- [ ] **Step 9: Verificação completa**

Run (em `backend/`): `./mvnw verify`
Expected: BUILD SUCCESS.

- [ ] **Step 10: Commit**

```bash
git add backend/pom.xml backend/src
git commit -m "feat: add an S3/MinIO attachment storage adapter with rollback compensation

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: MinIO no compose, README e roadmap

**Files:**
- Modify: `docker-compose.yml`
- Create: `docker-compose.s3.yml`
- Modify: `README.md`
- Modify: `docs/roadmap-de-evolucao.md`

**Interfaces:**
- Consumes: as variáveis `ATTACHMENTS_STORAGE` e `S3_*` (Tarefa 1).

- [ ] **Step 1: Verificação que falha**

Run (na raiz): `docker compose -f docker-compose.yml -f docker-compose.s3.yml --profile s3 config --services`
Expected: FAIL (`docker-compose.s3.yml` não existe).

- [ ] **Step 2: Serviço `minio` no compose**

Em `docker-compose.yml`, depois do serviço `frontend` (antes de `volumes:`):

```yaml
  # Optional S3-compatible storage for attachments. Only starts with --profile s3
  # (see docker-compose.s3.yml, which points the backend at it).
  minio:
    image: minio/minio:latest
    command: server /data --console-address ":9001"
    environment:
      MINIO_ROOT_USER: ticketflow
      MINIO_ROOT_PASSWORD: ticketflow-secret
    ports:
      - "9000:9000"
      - "9001:9001"
    volumes:
      - minio-data:/data
    healthcheck:
      test: ["CMD", "mc", "ready", "local"]
      interval: 5s
      timeout: 3s
      retries: 10
    profiles: [s3]
```

E em `volumes:`, acrescente `minio-data:`.

- [ ] **Step 3: Override do backend**

`docker-compose.s3.yml` (na raiz):

```yaml
# Runs the API with attachments in MinIO (S3) instead of PostgreSQL:
#   docker compose -f docker-compose.yml -f docker-compose.s3.yml --profile s3 up --build
# MinIO console: http://localhost:9001 (user ticketflow, password ticketflow-secret).
services:
  backend:
    environment:
      ATTACHMENTS_STORAGE: s3
      S3_ENDPOINT: http://minio:9000
      S3_PATH_STYLE: "true"
      S3_CREATE_BUCKET: "true"
      S3_ACCESS_KEY: ticketflow
      S3_SECRET_KEY: ticketflow-secret
    depends_on:
      minio:
        condition: service_healthy
```

- [ ] **Step 4: Verificar os dois modos do compose**

Run (na raiz): `docker compose config --services`
Expected: `db`, `backend` e `frontend`, **sem** `minio`.

Run (na raiz): `docker compose -f docker-compose.yml -f docker-compose.s3.yml --profile s3 config --services`
Expected: os quatro serviços, incluindo `minio`.

Run (na raiz): `docker compose -f docker-compose.yml -f docker-compose.s3.yml --profile s3 config | grep -E "ATTACHMENTS_STORAGE|S3_ENDPOINT"`
Expected: `ATTACHMENTS_STORAGE: s3` e `S3_ENDPOINT: http://minio:9000`.

- [ ] **Step 5: README**

Em `README.md`:

1. Substitua a decisão 7 por:

```markdown
7. **Anexos no PostgreSQL (`bytea`) por padrão, S3 opcional:** o banco evita custo e infraestrutura extra, com limite de 5 MB e validação da assinatura do arquivo (não só da extensão). A interface `AttachmentStorage` é uma porta com dois adaptadores: `DatabaseAttachmentStorage` (padrão) e `S3AttachmentStorage` (AWS S3, MinIO, R2), escolhidos por `ATTACHMENTS_STORAGE=database|s3`. O S3 não participa da transação do banco, então um upload seguido de rollback apaga o objeto (ação compensatória). Trocar de modo não migra os anexos antigos.
```

2. Na seção "Rodando tudo com Docker", depois do parágrafo que começa com "Sobe o banco, a API...", acrescente:

````markdown
Para guardar os anexos no MinIO (compatível com S3) em vez do PostgreSQL:

```bash
docker compose -f docker-compose.yml -f docker-compose.s3.yml --profile s3 up --build
```

O console do MinIO fica em `http://localhost:9001` (usuário `ticketflow`, senha `ticketflow-secret`).
````

- [ ] **Step 6: Roadmap**

Em `docs/roadmap-de-evolucao.md`, logo abaixo de `### 1.3. Adapter S3 / MinIO para \`AttachmentStorage\``, acrescente:

```markdown
- **Status**: ✅ Implementado (spec: `docs/specs/2026-10-01-s3-attachment-storage-design.md`). AWS SDK v2, `ATTACHMENTS_STORAGE=s3`, compensação no rollback e MinIO opcional no compose (`--profile s3`).
```

- [ ] **Step 7: Commit**

```bash
git add docker-compose.yml docker-compose.s3.yml README.md docs/roadmap-de-evolucao.md
git commit -m "feat: run MinIO locally for S3 attachments and document the adapter

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Depois das tarefas

Branch `feat/s3-attachment-storage` com 4 commits. Push e PR só quando você pedir. O deploy no Render não muda: sem `ATTACHMENTS_STORAGE`, a demo segue no banco.

## Self-review (spec × plano)

- Critério 1 (padrão inalterado): Tarefa 1 (`usesTheDatabaseByDefault`) e `verify` sem editar testes.
- Critério 2 (S3 funciona sem mudar o service): `AttachmentS3ApiTest`; o `AttachmentService` não aparece em nenhuma tarefa.
- Critério 3 (rollback apaga o objeto): `aRolledBackTransactionDeletesTheObject`.
- Critério 4 (compose): Tarefa 3, Passo 4.
- Spec 3.2 (404 para objeto ausente, logs da compensação): `aMissingObjectIsNotFound` e `deleteQuietly`.
- Nomes conferidos entre tarefas: `app.attachments.storage`, `S3StorageProperties`, `S3AttachmentStorage.key`, `S3IntegrationTest.MINIO`.
