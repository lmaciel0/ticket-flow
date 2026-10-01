# Roadmap de Evolução e Backlog Técnico: ticket-flow

Documento permanente com o catálogo de melhorias arquiteturais, segurança, banco de dados e backend para futuras iterações do **ticket-flow**.

> Este documento registra as melhorias propostas para referência futura e para demonstrar a visão arquitetural do projeto no GitHub.

---

## 1. Arquitetura Backend & Clean Code

### 1.1. Desacoplamento via Spring Application Events (`@DomainEvents`)
- **Status**: ✅ Implementado (spec: `docs/specs/2026-10-01-domain-events-design.md`). Usa `ApplicationEventPublisher` e `@TransactionalEventListener(BEFORE_COMMIT)`, pois `@DomainEvents` só dispara em `repository.save(...)`.
- **Situação atual**: `TicketService` invoca diretamente `history.record(...)` em múltiplos métodos para registrar eventos na linha do tempo.
- **Proposta**:
  - Publicar eventos de domínio: `TicketCreatedEvent`, `TicketStatusChangedEvent`, `TicketAssignedEvent`.
  - Criar um `@TransactionalEventListener` em `HistoryEventListener` para escutar e persistir o histórico.
  - Benefício: Permite plugar novos comportamentos futuros (ex: envio de e-mails, webhooks ou integrações) sem alterar a classe `TicketService` (Princípio Aberto/Fechado - OCP).

### 1.2. Padrão HTTP RFC 7232 (`ETag` e `If-Match`)
- **Status**: ✅ Implementado (spec: `docs/specs/2026-10-01-etag-if-match-design.md`). Segue a RFC 9110, que substituiu a 7232: `ETag` nas respostas, `If-Match` obrigatório nas três mutações (428 se ausente, 400 se malformado, 412 se desatualizado).
- **Situação atual**: O frontend envia o campo `version` dentro do corpo JSON das requisições para controle de concorrência otimista.
- **Proposta**:
  - No `GET /api/tickets/{id}`, responder com o header HTTP `ETag: "2"`.
  - Nas mutações (`PATCH /api/tickets/{id}` e `POST /api/tickets/{id}/status`), exigir o header `If-Match: "2"`.
  - Retornar `412 Precondition Failed` caso a versão no banco divirja da enviada.
  - Benefício: Alinhamento com especificações HTTP enterprise adotadas por APIs como GitHub e Stripe.

### 1.3. Adapter S3 / MinIO para `AttachmentStorage`
- **Situação atual**: A interface `AttachmentStorage` possui apenas a implementação `DatabaseAttachmentStorage` (`bytea` no PostgreSQL).
- **Proposta**:
  - Criar `S3AttachmentStorage` usando AWS SDK v2 ou MinIO.
  - Ativar via `@ConditionalOnProperty(name = "app.attachments.storage", havingValue = "s3")`.
  - Benefício: Demonstração prática da arquitetura hexagonal/ports-and-adapters sem onerar a implantação padrão.

---

## 2. Banco de Dados & Performance

### 2.1. Busca Textual Otimizada com `pg_trgm` (Trigramas)
- **Situação atual**: `TicketSpecifications` utiliza `LIKE %termo%` com `lower(title)` e `lower(description)`, forçando *Sequential Scan* no banco.
- **Proposta**:
  - Migration Flyway habilitando a extensão `pg_trgm` e criando índice GIN:
    ```sql
    CREATE EXTENSION IF NOT EXISTS pg_trgm;
    CREATE INDEX idx_tickets_title_desc_trgm ON tickets USING gin (title gin_trgm_ops, description gin_trgm_ops);
    ```
  - Benefício: Consultas textuais instantâneas mesmo com dezenas de milhares de chamados.

### 2.2. Métrica de Primeira Resposta (First Response Time SLA)
- **Situação atual**: Há apenas SLA de resolução final (`dueAt` e `slaBreached`).
- **Proposta**:
  - Adicionar campos `firstResponseDueAt` e `firstRespondedAt` no ticket, gravados quando o atendente insere o primeiro comentário público ou assume a demanda.

---

## 3. Segurança Avançada

### 3.1. Rate Limiting em Rotas Públicas (`/api/auth/*`)
- **Situação atual**: Endpoints de login e registro não possuem limitação de taxa por IP.
- **Proposta**:
  - Interceptor com Bucket4j limitando a 5 tentativas de login por minuto por endereço IP para prevenção de brute-force e cred-stuffing.

### 3.2. Refresh Token em Cookie HttpOnly + Access Token Curto
- **Situação atual**: JWT único de 8 horas armazenado no `localStorage`.
- **Proposta**:
  - Access Token em memória (15 minutos de validade) e Refresh Token em cookie seguro (`HttpOnly`, `SameSite=Strict`, `Secure`) com rota `POST /api/auth/refresh`.

---

## 4. Observabilidade & Métricas

### 4.1. Métricas Customizadas com Micrometer / Actuator
- **Proposta**:
  - Criar contadores e gauges customizados em `/actuator/metrics`: taxa de abertura de chamados, violações de SLA em tempo real e tempo de fila.
