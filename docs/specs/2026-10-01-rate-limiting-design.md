# Rate limiting nas rotas públicas de autenticação (roadmap 3.1)

- **Data:** 2026-10-01
- **Status:** aprovada
- **Autor:** Roberto Lucas
- **Origem:** item 3.1 de `docs/roadmap-de-evolucao.md`

## 1. Objetivo

Hoje `POST /api/auth/login` e `POST /api/auth/register` aceitam qualquer quantidade de requisições. Um atacante pode testar senhas sem parar (força bruta, *credential stuffing*) ou criar usuários em massa, e cada login custa um hash BCrypt (cerca de 100 ms de CPU) na instância gratuita do Render. O objetivo é limitar as tentativas por endereço IP.

**Critérios de sucesso:**

1. Cada IP faz no máximo 5 logins por minuto. A 6ª tentativa recebe `429 Too Many Requests` antes de chegar ao BCrypt.
2. Cada IP faz no máximo 3 cadastros por hora, num limite separado do login.
3. A resposta 429 traz o header `Retry-After` (segundos) e um corpo ProblemDetail com `detail` em português ("Muitas tentativas. Tente de novo em 12 s.") e `traceId`, como os outros erros da API.
4. A tela de login e a de cadastro mostram essa mensagem, e não "servidor inacessível".
5. No Render, o IP usado é o do cliente, e não o do proxy; um `X-Forwarded-For` forjado pelo cliente não escapa do limite.
6. Os botões de login da demo continuam funcionando normalmente para um visitante.

**Fora do escopo:** limite por e-mail ou por conta, limite nas rotas autenticadas, contadores compartilhados entre instâncias (Redis), lista de IPs bloqueados e captcha.

## 2. Decisões

| Decisão | Escolha | Motivo |
|---|---|---|
| Chave do limite | Só o IP | Escolha do autor. Um limite por e-mail pegaria ataques distribuídos, mas deixaria qualquer um bloquear o login de outra pessoa, inclusive as contas compartilhadas da demo. |
| Biblioteca | Bucket4j (`bucket4j_jdk17-core`) | Pedido no roadmap. Token bucket pronto e testado, sem o estouro na virada da janela de um contador por minuto. |
| Onde guardar os buckets | Um cache Caffeine por rota, em memória | Hoje roda uma instância só. O cache expira os IPs ociosos e tem teto de tamanho, então trocar de IP a cada tentativa não faz a memória crescer sem limite. |
| Onde aplicar | Filtro de servlet dentro da cadeia do Spring Security, logo depois do `CorsFilter` | Antes do CORS, a resposta 429 sairia sem `Access-Control-Allow-Origin` e o navegador a esconderia do frontend. Ainda assim roda antes do controller e do BCrypt. |
| Limites | Login 5 por minuto, cadastro 3 por hora, configuráveis em `app.rate-limit` | Login: o número do roadmap, de sobra para quem digita a senha errada algumas vezes. Cadastro: cada um cria um usuário real no banco. |
| Recarga | Gradual (*greedy*): 1 tentativa de login a cada 12 s, 1 cadastro a cada 20 min | Quem é bloqueado espera pouco para a próxima tentativa, em vez de esperar o minuto inteiro. |
| O que conta | Toda requisição às duas rotas, com sucesso ou não | Simples, e não exige olhar a resposta. 5 por minuto sobra para logins de verdade. |
| IP real atrás do proxy | `server.forward-headers-strategy: native` com `server.tomcat.remoteip.remote-ip-header: CF-Connecting-IP` | O Tomcat (`RemoteIpValve`) só lê o header quando a conexão vem de uma rede interna, como o proxy do Render. No Render, o `X-Forwarded-For` termina com um servidor do Cloudflare que muda a cada requisição (visto em produção: nenhum login era bloqueado); o IP do visitante vem no `CF-Connecting-IP`, que o Cloudflare sempre sobrescreve, então o cliente não consegue falsificá-lo. |
| Relógio | O `TimeMeter` do Bucket4j lê o `Clock` da aplicação | Os testes avançam o `MutableClock` em vez de esperar de verdade. |
| Testes existentes | Limite desligado por padrão nos testes de integração (`app.rate-limit.enabled=false`) | Eles fazem muitos logins seguidos do mesmo IP. O teste do limite liga a chave. |

## 3. Design

### 3.1. Configuração

```yaml
server:
  forward-headers-strategy: native

app:
  rate-limit:
    enabled: true
    login:
      capacity: 5
      period: 1m
    register:
      capacity: 3
      period: 1h
```

`RateLimitProperties` (`@ConfigurationProperties("app.rate-limit")`, record no pacote `common`) com `enabled` e um `Limit(int capacity, Duration period)` para cada rota. O construtor compacto recusa capacidade menor que 1 e período nulo ou não positivo, no estilo do `SlaProperties`.

### 3.2. `LoginRateLimitFilter`

`OncePerRequestFilter` no pacote `common`. Ele **não** é um bean: o `SecurityConfig` o cria (`new LoginRateLimitFilter(properties, clock)`) e o registra só na cadeia do Spring Security (`http.addFilterAfter(filter, CorsFilter.class)`). Como bean, o Spring Boot o registraria também como filtro de servlet comum, que rodaria antes do CORS.

- `shouldNotFilter`: deixa passar tudo que não for `POST /api/auth/login` ou `POST /api/auth/register`, e tudo quando `enabled` é `false`. As rotas são reconhecidas com `PathPatternRequestMatcher`, que compara o caminho já decodificado, como o Spring Security e o Spring MVC fazem; comparar o `getRequestURI()` cru deixaria `/api/auth/logi%6E` chegar ao login sem limite.
- Para cada rota há um `Cache<String, Bucket>` do Caffeine com `expireAfterAccess(period)` (depois desse tempo sem uso o bucket estaria cheio de novo, então esquecê-lo não muda nada) e `maximumSize(20_000)`; o `Bandwidth` (imutável) é criado uma vez por rota e compartilhado pelos buckets.
- A chave é `request.getRemoteAddr()`, que com a estratégia `native` já é o IP do cliente. Um endereço IPv6 conta pelo seu /64 inteiro: um assinante costuma ter um /64 e poderia trocar de endereço a cada tentativa.
- O bucket tem `capacity` fichas e recarga gradual de `capacity` fichas por `period`.
- `tryConsumeAndReturnRemaining(1)`: se consumiu, segue a cadeia. Se não, responde 429 e não chama o resto da cadeia.

### 3.3. Resposta 429

- `Retry-After`: os nanossegundos até a próxima ficha, convertidos em segundos e arredondados para cima (no mínimo 1).
- Corpo `application/problem+json`, escrito à mão como no `ProblemAuthenticationEntryPoint` (o filtro roda fora do Spring MVC):

```json
{"type":"about:blank","title":"Too Many Requests","status":429,
 "detail":"Muitas tentativas. Tente de novo em 12 s.","traceId":"..."}
```

A partir de um minuto o `detail` mostra minutos, arredondados para cima ("Tente de novo em 20 min."); o `Retry-After` continua em segundos.

- Uma linha `WARN` no log com a rota e o IP, para conferir no Render que o IP é o do cliente.
- `SecurityConfig`: `Retry-After` entra nos headers expostos pelo CORS.

### 3.4. Frontend

O `ApiError` já usa o `detail` do ProblemDetail, e as telas de login e cadastro já mostram `mutation.error.message`. Muda só `FALLBACK_MESSAGES` em `api/client.ts`, com uma mensagem para o 429 caso o corpo venha sem `detail`, e um teste.

## 4. Testes

- **`LoginRateLimitApiTest`** (integração, com `@TestPropertySource(properties = "app.rate-limit.enabled=true")`):
  - 5 logins do mesmo IP passam (com senha certa ou errada) e o 6º recebe 429 com `Retry-After: 12`, `detail` e `traceId`;
  - depois de avançar o relógio 12 s, entra mais um login, e o seguinte volta a dar 429;
  - outro IP (definido com `remoteAddr` na requisição do MockMvc) tem o próprio limite;
  - esgotar o login não afeta o cadastro, e o 4º cadastro do mesmo IP em uma hora recebe 429;
  - uma rota autenticada (`GET /api/tickets`) não é limitada;
  - a resposta 429 a uma requisição com `Origin` permitida traz `Access-Control-Allow-Origin`.
- **`RateLimitProperties`**: capacidade zero ou período não positivo derrubam a inicialização.
- **Testes existentes**: `IntegrationTest` passa a usar `@TestPropertySource(properties = "app.rate-limit.enabled=false")` (o `@TestPropertySource` de uma subclasse sobrepõe o da base, o que deixa o teste do limite religá-lo); todos continuam verdes.
- **Frontend**: `client.test.ts` cobre a mensagem padrão do 429.
- **Em produção, depois do deploy**: 6 logins seguidos na demo dão 429, e o IP do log `WARN` é **igual ao IP público de quem testou** (conferido em dois provedores diferentes, por exemplo a rede de casa e o celular). Um IP público qualquer não basta: se for um IP do Cloudflare ou do balanceador do Render, todos os visitantes dividem o mesmo limite. Nesse caso, ler o header `CF-Connecting-IP` ou configurar `server.tomcat.remoteip.internal-proxies`.

O `RemoteIpValve` do Tomcat não roda no MockMvc, então o `forward-headers-strategy` só é verificado em produção; os testes definem o IP direto no `remoteAddr`.

## 5. Plano de entrega (commits pequenos)

1. Spec e plano.
2. Dependências (`bucket4j_jdk17-core`, `caffeine`), `RateLimitProperties` e configuração, com o limite desligado nos testes.
3. `LoginRateLimitFilter` na cadeia do Spring Security, resposta 429 e testes de integração.
4. `forward-headers-strategy` e `Retry-After` exposto no CORS.
5. Frontend: mensagem padrão do 429.

## 6. Destaques técnicos

- Token bucket com recarga gradual em vez de janela fixa: não deixa passar o dobro do limite na virada do minuto.
- O 429 sai depois do CORS e antes do BCrypt: o navegador recebe a mensagem e a CPU não paga pelo ataque.
- Cache com expiração e teto: o limite não vira ele mesmo um vetor de consumo de memória.
- IP real atrás de proxy sem confiar no que o cliente escreve em `X-Forwarded-For`.
- Escolha consciente de não limitar por conta, para não permitir que alguém bloqueie o login de outra pessoa.
