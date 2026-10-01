# Adapter S3/MinIO para anexos (roadmap 1.3)

- **Data:** 2026-10-01
- **Status:** aguardando revisão
- **Autor:** Roberto Lucas
- **Origem:** item 1.3 de `docs/roadmap-de-evolucao.md`

## 1. Objetivo

`AttachmentStorage` é a porta de saída para os bytes dos anexos. Hoje há um único adapter, `DatabaseAttachmentStorage` (tabela `attachment_content`, coluna `bytea`). O objetivo é demonstrar portas e adaptadores (arquitetura hexagonal) com um segundo adapter real, `S3AttachmentStorage`, que funciona com AWS S3 e MinIO. Ele é ativado por configuração, **sem mudar o padrão**: a demo pública continua usando o banco, com custo zero.

**Critérios de sucesso:**

1. Sem configuração nova, nada muda: o adapter ativo é o de banco e todos os testes atuais passam sem alteração.
2. Com `app.attachments.storage=s3`, upload e download funcionam contra um S3 compatível (MinIO), sem nenhuma mudança no `AttachmentService`.
3. Se a transação do upload sofrer rollback depois do envio, o objeto é apagado do bucket (não fica órfão).
4. Localmente, um comando de docker compose sobe a aplicação inteira com MinIO.

**Fora do escopo:**

- migrar anexos entre adapters (trocar o modo deixa os anexos antigos inacessíveis, o que fica documentado);
- apagar objetos quando o reset da demo trunca o banco (a demo usa o modo `database`);
- streaming de arquivos (o limite continua 5 MB e a interface continua com `byte[]`);
- URLs pré-assinadas;
- configurar S3 no Render.

## 2. Decisões

| Decisão | Escolha | Motivo |
|---|---|---|
| Cliente | AWS SDK v2 (`software.amazon.awssdk:s3`, versão pelo BOM `software.amazon.awssdk:bom`) | Padrão de mercado; funciona com S3, MinIO e R2 trocando só o endpoint. |
| Ativação | `app.attachments.storage` = `database` (padrão) ou `s3`, via `@ConditionalOnProperty` | Exatamente um adapter registrado como bean. O padrão vale mesmo sem a propriedade (`matchIfMissing = true` no adapter de banco). |
| Rollback | Ação compensatória: o `store` registra uma `TransactionSynchronization` que apaga o objeto em `afterCompletion(STATUS_ROLLED_BACK)` | O S3 não participa da transação do banco. Enviar só depois do commit (alternativa) poderia deixar uma linha de anexo sem arquivo quando o envio falha. Isso é pior que um órfão, porque o download quebra e o usuário já recebeu 201. |
| MinIO local | Serviço `minio` com `profiles: [s3]` mais o override `docker-compose.s3.yml` para o backend | O `docker compose up` normal não muda. Um profile só liga ou desliga serviços; as variáveis do backend vêm do arquivo de override. |
| Bucket | Criado na subida quando `create-bucket=true` e ele não existe | Sem passo manual no ambiente local e nos testes. Em produção fica `false` (bucket gerenciado por fora). |

## 3. Design

### 3.1 Configuração (`application.yml`)

```yaml
app:
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

`S3StorageProperties` é um `record` com `@ConfigurationProperties("app.attachments.s3")`, registrado só no modo S3.

### 3.2 Componentes (pacote `com.ticketflow.attachment`)

- **`DatabaseAttachmentStorage`**: ganha `@ConditionalOnProperty(name = "app.attachments.storage", havingValue = "database", matchIfMissing = true)`. Nada mais muda.
- **`S3StorageConfig`** (`@Configuration`, mesmo `@ConditionalOnProperty` com `havingValue = "s3"`):
  - habilita `S3StorageProperties`;
  - cria o `S3Client`, com região, credenciais estáticas quando `access-key` estiver preenchido (senão, a cadeia padrão da AWS), `endpointOverride` quando `endpoint` estiver preenchido e `forcePathStyle(path-style)`;
  - se `create-bucket` for true, cria o bucket quando `headBucket` responder `NoSuchBucketException`.
- **`S3AttachmentStorage`** (`@Component`, mesmo `@ConditionalOnProperty` com `havingValue = "s3"`):
  - chave do objeto: `attachments/{attachmentId}`;
  - `store`: `putObject` e, se houver uma sincronização de transação ativa, registra a compensação que chama `deleteObject` no rollback (falhas da compensação só geram log, sem exceção). Se o próprio `putObject` falhar, a exceção sobe e reverte a transação, então a linha do anexo também não fica gravada;
  - `load`: `getObjectAsBytes(...).asByteArray()`; `NoSuchKeyException` vira `ApiException.notFound("Anexo não encontrado.")` com log de erro, porque a linha existe e o arquivo não.

`AttachmentService`, `AttachmentStorage` e o banco **não mudam**.

### 3.3 Infraestrutura local

- **`docker-compose.yml`**: novo serviço `minio` (`minio/minio`, `server /data --console-address :9001`, portas 9000 e 9001, `profiles: [s3]`, healthcheck, volume `minio-data`).
- **`docker-compose.s3.yml`** (novo): sobrescreve o `backend` com `ATTACHMENTS_STORAGE=s3`, `S3_ENDPOINT=http://minio:9000`, `S3_PATH_STYLE=true`, `S3_CREATE_BUCKET=true`, as credenciais do MinIO e `depends_on: minio`.
- Comando: `docker compose -f docker-compose.yml -f docker-compose.s3.yml --profile s3 up --build`.
- **README**: a decisão 7 (anexos no PostgreSQL) cita o adapter S3 e o comando; a variável `ATTACHMENTS_STORAGE` é documentada.

## 4. Testes

- **`S3AttachmentStorageTest`** (integração com MinIO via Testcontainers, módulo `org.testcontainers:testcontainers-minio`; contexto Spring com `app.attachments.storage=s3` apontando para o container):
  - gravar e ler devolve os mesmos bytes;
  - um rollback depois do `store` apaga o objeto do bucket;
  - ler um id sem objeto dá `ApiException` 404;
  - o bean ativo de `AttachmentStorage` é o `S3AttachmentStorage`.
- **`AttachmentS3ApiTest`**: o fluxo de API de upload, listagem e download com `app.attachments.storage=s3` devolve os mesmos bytes, e o objeto existe no bucket. Prova que o `AttachmentService` não precisou mudar.
- **Modo padrão**: um teste confirma que, sem a propriedade, o bean ativo é o `DatabaseAttachmentStorage`. Os testes atuais de anexo seguem passando sem alteração.

## 5. Plano de entrega (commits pequenos)

1. `app.attachments.storage` com o `@ConditionalOnProperty` no adapter de banco, e o teste do modo padrão.
2. `S3AttachmentStorage`, `S3StorageConfig`, `S3StorageProperties`, as dependências e os testes com MinIO.
3. Compose (`minio` e override), README e roadmap.

A cada etapa, `./mvnw verify` fica verde antes do commit.

## 6. Pontos de entrevista

- Portas e adaptadores: o domínio (`AttachmentService`) depende da interface; a escolha do adapter fica na configuração (`@ConditionalOnProperty`).
- Por que o S3 não entra na transação do banco, e como a ação compensatória (`TransactionSynchronization`) evita arquivos órfãos no rollback.
- Por que não enviar só depois do commit: trocaria um órfão inofensivo por um anexo quebrado.
- Teste contra um S3 real (MinIO no Testcontainers) em vez de mock: pega erros de configuração (path-style, endpoint, região) que um mock esconderia.
- Limites assumidos: sem migração entre adapters, sem limpeza no reset da demo, `byte[]` em memória (aceitável com limite de 5 MB).
