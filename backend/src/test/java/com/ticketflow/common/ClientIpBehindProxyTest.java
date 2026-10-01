package com.ticketflow.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketflow.support.IntegrationTest;
import com.ticketflow.user.Role;
import com.ticketflow.user.User;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/**
 * On Render the API is reached through Cloudflare and Render's proxy: the connection comes from a 10.x address
 * and X-Forwarded-For ends with a Cloudflare edge address that changes on every request, so it cannot identify
 * the visitor. Cloudflare writes the visitor's address in CF-Connecting-IP (and overwrites any value the client
 * sent). Tomcat's RemoteIpValve only runs in a real server, so this test talks to one over HTTP; 127.0.0.1 is an
 * internal address, like Render's proxy.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "app.rate-limit.enabled=true")
class ClientIpBehindProxyTest extends IntegrationTest {

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private int login(User user, String clientIp, String forwardedFor) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .header("CF-Connecting-IP", clientIp)
                .header("X-Forwarded-For", forwardedFor)
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"email": "%s", "password": "%s"}
                        """.formatted(user.getEmail(), PASSWORD)))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void limitsTheVisitorThatCloudflareReportsNotTheEdgeServer() throws Exception {
        User ana = createUser("Ana", Role.REQUESTER);

        // A new Cloudflare edge address on every request, as seen in production.
        for (int i = 1; i <= 5; i++) {
            assertThat(login(ana, "198.51.100.1", "198.51.100.1, 172.70.0." + i)).isEqualTo(200);
        }
        assertThat(login(ana, "198.51.100.1", "198.51.100.1, 172.70.0.6")).isEqualTo(429);

        // Another visitor behind the same edge server keeps their own limit.
        assertThat(login(ana, "198.51.100.2", "198.51.100.2, 172.70.0.6")).isEqualTo(200);
    }
}
