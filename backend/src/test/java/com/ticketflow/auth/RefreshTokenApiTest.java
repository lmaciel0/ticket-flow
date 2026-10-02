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
