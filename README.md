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
