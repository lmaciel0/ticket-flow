# Refresh token em cookie HttpOnly e access token curto (roadmap 3.2)

- **Data:** 2026-10-01
- **Status:** em revisão
- **Autor:** Roberto Lucas
- **Origem:** item 3.2 de `docs/roadmap-de-evolucao.md`

## 1. Objetivo

Hoje o login devolve um JWT de 8 horas que o frontend guarda no `localStorage`. Qualquer script que rode na página (um XSS) consegue ler esse token e usar a sessão por horas, de qualquer lugar. O objetivo é que o JavaScript da página só veja um access token curto, de 15 minutos, guardado em memória, e que a sessão longa fique num refresh token em cookie `HttpOnly`, que nenhum script lê.

**Critérios de sucesso:**

1. O access token dura 15 minutos e nunca é gravado no `localStorage` nem em outro armazenamento persistente.
2. O refresh token vive num cookie `HttpOnly; Secure; SameSite=Strict` do domínio do site e dura 7 dias.
3. Recarregar a página, ou voltar no dia seguinte, mantém o usuário logado sem digitar a senha de novo, em todos os navegadores atuais, inclusive o Safari.
4. Cada uso do refresh token o troca por outro. Usar de novo um token já trocado revoga a sessão inteira (sinal de roubo), com uma tolerância curta para duas abas que renovam ao mesmo tempo.
5. Sair encerra a sessão no servidor, e não só no navegador.
6. O rate limiting do login e do cadastro continua vendo o IP real do visitante.
7. Várias abas continuam se acompanhando: sair numa aba sai em todas; entrar com outro usuário numa aba faz as outras seguirem esse usuário.

**Fora do escopo:** tela de sessões ativas, "lembrar de mim", limpeza periódica de tokens vencidos (o reset diário da demo e o próprio login cuidam disso), renovação proativa antes de o access token vencer.

## 2. Contexto: por que o login não passa pelo site

O site (`ticket-flow-web.onrender.com`) e a API (`ticket-flow-api-a15n.onrender.com`) são subdomínios de `onrender.com`, que está na Public Suffix List. Para o navegador eles são **sites diferentes**, então um cookie `SameSite=Strict` (ou `Lax`) gravado pela API nunca seria enviado pelo frontend.

O #23 fez o site repassar `/api/*` para a API (regra de *rewrite* do Render), o que deixa a API na mesma origem do frontend. Mas os testes em produção (#24, #25) mostraram que, pelo repasse, a API recebe como IP do cliente o servidor do Render que faz o repasse (`CF-Connecting-IP = 74.220.48.216`), igual para todos os visitantes. O IP real só aparece numa posição fixa do `X-Forwarded-For`, que depende de detalhes internos do Render e pode ser forjada por quem hospedar o próprio repasse no Render. Se o login passasse pelo repasse, o limite de 5 tentativas por minuto valeria para todos os visitantes juntos.

Por isso o desenho separa os dois caminhos: **login e cadastro vão direto à API** (IP certo para o rate limiting) e **só as rotas que leem ou gravam o cookie passam pelo site**. Nenhuma dessas rotas precisa de limite por IP, porque todas exigem um segredo aleatório de 256 bits.

## 3. Decisões

| Decisão | Escolha | Motivo |
|---|---|---|
| Como o cookie chega ao domínio do site | Login direto devolve um código de uso único; o frontend o troca pelo cookie em `POST /api/auth/session`, pelo site | Escolha do autor entre três opções. Funciona em todos os navegadores e mantém o IP real no rate limiting. As outras: tudo pelo repasse lendo o `X-Forwarded-For` (frágil e forjável) e cookie entre sites com `SameSite=None; Partitioned` (o Safari perde a sessão a cada recarga). |
| Validade do access token | 15 minutos (`app.jwt.ttl: 15m`) | Pedido no roadmap. Um token vazado vale pouco tempo. |
| Validade do refresh token | 7 dias, contados do login (a rotação não estende) | Uma semana sem digitar a senha é razoável para um help desk; a demo apaga os usuários a cada 24 h de qualquer jeito. |
| Formato do refresh token e do código | 32 bytes aleatórios (`SecureRandom`) em Base64 URL, guardados só como hash SHA-256 | Um vazamento do banco não entrega sessões. SHA-256 sem sal basta: o valor tem 256 bits de entropia, não é uma senha. |
| Rotação | Cada refresh grava um token novo e marca o anterior como usado | Um token roubado e usado deixa rastro: a próxima tentativa com o mesmo token denuncia o reuso. |
| Reuso | Token já usado há mais de 30 s revoga a família inteira (todas as rotações daquele login) | Detecção de roubo clássica. Os 30 s cobrem duas abas que renovam juntas com o mesmo cookie: a segunda recebe um access token e não mexe no cookie. |
| Validade do código de troca | 60 segundos, uso único | Só precisa durar a ida e volta entre o login e `/session`. |
| Cookie | `tf_refresh`, `HttpOnly; Secure; SameSite=Strict; Path=/api/auth; Max-Age` igual ao tempo que falta para a família vencer | `Path=/api/auth`: o cookie só viaja para as rotas que o usam. `Strict` impede CSRF. |
| `Secure` em desenvolvimento | Configurável (`app.auth.refresh-cookie.secure`, padrão `true`); o docker compose usa `false` | Chrome e Firefox aceitam `Secure` em `http://localhost`, o Safari não. |
| Rate limiting das rotas de cookie | Nenhum | Pelo repasse, o IP é o mesmo para todos (seção 2). Adivinhar um segredo de 256 bits é inviável. |
| Usuário desativado ou com papel trocado | O refresh falha (401) e revoga a família; o access token novo sai sempre com o papel atual | Mesma regra que o `SecurityConfig` já aplica a cada requisição. |
| Armazenamento | Duas tabelas novas: `refresh_tokens` e `session_handoffs` (migration V10), com `ON DELETE CASCADE` para `users` | O reset da demo (`TRUNCATE users CASCADE`) leva as sessões junto. |
| Refresh ao mesmo tempo no frontend | Uma única chamada de refresh por aba (promessa compartilhada) e uma por vez entre abas (`navigator.locks`) | Evita que várias chamadas com 401 disparem vários refreshes e caiam no reuso. |
| Sincronização entre abas | `BroadcastChannel('ticketflow-auth')` com mensagens `login` e `logout` | O token saiu do `localStorage`, então o evento `storage` usado hoje deixa de existir. |
| Desenvolvimento local | O Vite (`server.proxy`) e o nginx do docker compose repassam `/api` para o backend | As rotas de cookie ficam na mesma origem também na máquina do desenvolvedor, como em produção. |

## 4. Design

### 4.1. Banco (migration `V10__create_refresh_tokens.sql`)

```sql
CREATE TABLE refresh_tokens (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    family_id   UUID        NOT NULL,
    token_hash  CHAR(64)    NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    revoked_at  TIMESTAMPTZ
);
CREATE INDEX refresh_tokens_family_idx ON refresh_tokens (family_id);
CREATE INDEX refresh_tokens_user_idx ON refresh_tokens (user_id);

CREATE TABLE session_handoffs (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    code_hash   CHAR(64)    NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL
);
```

O `DemoDataSeeder` não muda: o `TRUNCATE ... users CASCADE` alcança as duas tabelas.

### 4.2. Backend (pacote `auth`)

- **`RefreshTokenService`**:
  - `String createHandoff(User user)`: apaga os códigos vencidos e as famílias vencidas do usuário, grava um código novo e devolve o valor em claro.
  - `IssuedRefresh redeemHandoff(String code)`: acha o código pelo hash; se não existir ou tiver vencido, `401`. Apaga o código (uso único), cria uma família nova e devolve o token em claro e a data de expiração.
  - `RefreshResult refresh(String token)`: acha pelo hash, com bloqueio de linha (`SELECT ... FOR UPDATE`) para que dois refreshes do mesmo token não passem juntos.
    - não existe, vencido ou família revogada → `401`;
    - já usado há mais de 30 s → revoga a família e `401`;
    - já usado há até 30 s → devolve só um access token novo (`rotated = null`), sem novo refresh token;
    - usuário inexistente, inativo → revoga a família e `401`;
    - caso normal → marca `used_at`, grava o sucessor na mesma família com a mesma `expires_at` e devolve o access token e o sucessor.
  - `void logout(String token)`: revoga a família do token, se ele existir; nada acontece se não existir (logout é idempotente).
- **`RefreshCookie`**: monta o `ResponseCookie` (`tf_refresh`, `HttpOnly`, `Secure` configurável, `SameSite=Strict`, `Path=/api/auth`, `Max-Age` até a expiração da família) e o cookie de apagar (`Max-Age=0`).
- **`AuthProperties`** (`app.auth`): `refresh-ttl: 7d`, `handoff-ttl: 60s`, `reuse-grace: 30s`, `refresh-cookie.secure: true`.
- **`AuthController`**:
  - `POST /login` e `POST /register`: `AuthResponse(token, user, sessionCode)`.
  - `POST /session` com `{"code": "..."}`: `204` com `Set-Cookie`.
  - `POST /refresh`: lê `tf_refresh`; `200` com `{"token", "user"}` e, quando houve rotação, o `Set-Cookie` novo; `401` sem cookie ou com cookie inválido, apagando o cookie.
  - `POST /logout`: `204`, revoga a família e apaga o cookie.
- **`SecurityConfig`**: `/api/auth/session`, `/refresh` e `/logout` (POST) entram no `permitAll`.
- **`application.yml`**: `app.jwt.ttl: 15m` e o bloco `app.auth`.
- **`LoginRateLimitFilter`**: sai o log temporário de headers do #25.

O `401` do refresh segue o ProblemDetail da API ("Sessão expirada. Entre novamente.").

### 4.3. Infraestrutura

- **`render.yaml`**: a regra `/api/*` do #23 fica, com o comentário atualizado (agora só as rotas de cookie a usam).
- **`frontend/vite.config.ts`**: `server.proxy` com `/api` → `http://localhost:8080`.
- **`frontend/nginx.conf`**: `location /api/ { proxy_pass http://backend:8080; }` com os headers `Host` e `X-Forwarded-For`.
- **`docker-compose.yml`**: o backend recebe `APP_AUTH_REFRESH_COOKIE_SECURE=false`.

### 4.4. Frontend

- **`api/client.ts`**:
  - O access token vira uma variável do módulo (`accessToken.get/set/clear`). Na carga, a chave antiga `ticketflow.token` é apagada do `localStorage`.
  - Rotas de cookie (`/auth/session`, `/auth/refresh`, `/auth/logout`) usam a própria origem (`/api/...`, sem `API_URL`); o resto continua indo à API pelo `API_URL`.
  - `refreshSession()`: uma promessa compartilhada por aba, dentro de `navigator.locks.request('ticketflow-refresh', ...)` quando o navegador tiver a API. Devolve o `AuthResponse` ou `null` (401).
  - Uma chamada autenticada que recebe 401 tenta `refreshSession()` uma vez e repete a chamada com o token novo. Se o refresh devolver `null`, chama o `onUnauthorized` de hoje ("Sua sessão expirou. Entre novamente.").
- **`auth/AuthProvider.tsx`**:
  - Ao montar, chama `refreshSession()`. Enquanto não responde, `loading` fica `true` (a tela de carregamento e o aviso de cold start continuam). Com 401, o usuário vai para o login.
  - `login(response)`: guarda o token, troca o `sessionCode` em `/auth/session` e avisa as outras abas (`login`). Se a troca falhar, a sessão segue até o access token vencer, e uma notificação amarela avisa que ela não ficará salva ao recarregar.
  - `logout()`: chama `/auth/logout`, limpa o token e o cache e avisa as outras abas (`logout`).
  - Mensagem `logout` de outra aba: limpa o token e o cache. Mensagem `login`: limpa o cache e chama `refreshSession()`, que lê o cookie novo (do usuário que acabou de entrar).
- **Tipos**: `AuthResponse` ganha `sessionCode?: string` (o refresh não o devolve).

## 5. Testes

- **`RefreshTokenApiTest`** (integração):
  - login devolve `sessionCode`; `/session` com ele grava `tf_refresh` com `HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/api/auth` e `Max-Age` de 7 dias;
  - o código é de uso único e vence em 60 s;
  - `/refresh` com o cookie devolve um access token que funciona e um cookie novo; o antigo não é mais o mesmo valor;
  - refresh sem cookie ou com cookie desconhecido dá 401 e apaga o cookie;
  - reusar um token rodado há 10 s devolve 200 sem `Set-Cookie`; há 31 s dá 401 e o token mais novo da família também deixa de funcionar;
  - família vencida (relógio 7 dias à frente) dá 401;
  - usuário desativado ou com papel trocado: 401;
  - o access token sai com o papel atual;
  - logout revoga a família e apaga o cookie; logout sem cookie dá 204;
  - o access token vence em 15 minutos (relógio 15 min à frente: 401 em `/api/auth/me`).
- **`RefreshTokenServiceTest`** ou equivalente: o hash guardado não é o valor em claro.
- **`RateLimitConfigTest`** / testes de configuração: `app.jwt.ttl` é 15 min e o bloco `app.auth` tem os valores padrão.
- **Frontend**:
  - `client.test.ts`: rotas de cookie vão à própria origem; 401 → um refresh → repetição; várias chamadas com 401 ao mesmo tempo fazem um só refresh; refresh com 401 chama o `onUnauthorized`; a chave antiga do `localStorage` é apagada.
  - `AuthProvider.test.tsx`: abre logado quando o refresh responde 200; abre na tela de login quando responde 401; o login troca o `sessionCode`; mensagens de outra aba (`login` e `logout`).
  - Testes existentes que usam `tokenStorage`/`TOKEN_KEY` passam a usar o token em memória.
- **Em produção, depois do deploy**: entrar na demo, recarregar a página e continuar logado (Chrome e Safari do celular); sair numa aba e ver a outra sair; 6 logins seguidos continuam dando 429 no último.

## 6. Plano de entrega (commits pequenos)

1. Spec e plano; remoção do log temporário de headers.
2. Migration V10, `AuthProperties`, `RefreshTokenService` e testes.
3. Rotas `/session`, `/refresh`, `/logout`, `sessionCode` no login e cadastro, `SecurityConfig`, access token de 15 min.
4. Repasse local de `/api` (Vite, nginx, docker compose) e comentário do `render.yaml`.
5. Frontend: token em memória, refresh com repetição, rotas de cookie na própria origem.
6. Frontend: `AuthProvider` com refresh ao abrir, troca do código e sincronização entre abas.

## 7. Destaques técnicos

- Access token curto em memória e refresh token `HttpOnly`: um XSS não leva a sessão longa embora.
- Rotação com detecção de reuso e tolerância para abas simultâneas.
- Tokens e códigos guardados só como hash.
- Separação dos caminhos para manter o IP real no rate limiting, decidida com medições em produção (`X-Forwarded-For` e `CF-Connecting-IP` pelo repasse).
- Código de troca de uso único para levar a sessão de um domínio a outro sem cookie entre sites.
