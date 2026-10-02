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
