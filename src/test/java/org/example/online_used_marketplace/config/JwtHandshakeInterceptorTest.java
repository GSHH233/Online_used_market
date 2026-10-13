package org.example.online_used_marketplace.config;

import io.jsonwebtoken.Claims;
import org.example.online_used_marketplace.utils.JwtUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class JwtHandshakeInterceptorTest {
    private final JwtUtil jwt = mock(JwtUtil.class);
    private final JwtHandshakeInterceptor interceptor = new JwtHandshakeInterceptor(jwt);
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final Map<String, Object> attributes = new HashMap<>();

    @Test
    void browserHandshakeWithoutAuthorizationRemainsCompatible() {
        assertThat(handshake()).isTrue();
        assertThat(attributes).isEmpty();
        verifyNoInteractions(jwt);
    }

    @Test
    void urlAndSubprotocolTokensAreNotAuthenticationSources() {
        request.setQueryString("token=do-not-use");
        request.addHeader("Sec-WebSocket-Protocol", "do-not-use");

        assertThat(handshake()).isTrue();
        assertThat(attributes).isEmpty();
        verifyNoInteractions(jwt);
    }

    @Test
    void validAuthorizationStoresOnlyVerifiedIdentity() {
        request.addHeader(HttpHeaders.AUTHORIZATION, "bEaReR valid-token");
        claims(42L, new Date(System.currentTimeMillis() + 60_000));

        assertThat(handshake()).isTrue();
        assertThat(attributes).containsOnlyKeys(JwtHandshakeInterceptor.USER_ID_ATTRIBUTE);
        assertThat(attributes.get(JwtHandshakeInterceptor.USER_ID_ATTRIBUTE)).isEqualTo("42");
        assertThat(attributes.values()).doesNotContain("valid-token");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Basic abc", "Bearer ", "Bearer invalid-token"})
    void suppliedInvalidCredentialsAreRejected(String header) {
        request.addHeader(HttpHeaders.AUTHORIZATION, header);
        when(jwt.parseToken("invalid-token")).thenThrow(new IllegalArgumentException("bad signature"));

        assertRejected();
    }

    @Test
    void duplicateAuthorizationIsRejected() {
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer first");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer second");
        assertRejected();
        verifyNoInteractions(jwt);
    }

    @Test
    void oversizedTokenIsRejectedBeforeParsing() {
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + "x".repeat(4097));
        assertRejected();
        verifyNoInteractions(jwt);
    }

    @Test
    void expiredTokenIsRejected() {
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer valid-token");
        claims(42L, new Date(0));
        assertRejected();
    }

    @Test
    void missingExpirationIsRejected() {
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer valid-token");
        claims(42L, null);
        assertRejected();
    }

    @Test
    void missingIdentityIsRejected() {
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer valid-token");
        claims(null, new Date(System.currentTimeMillis() + 60_000));
        assertRejected();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1.5", "not-a-number"})
    void invalidIdentityIsRejected(String userId) {
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer valid-token");
        claims(userId, new Date(System.currentTimeMillis() + 60_000));
        assertRejected();
    }

    private void claims(Object userId, Date expiration) {
        Claims claims = mock(Claims.class);
        when(claims.get("userId")).thenReturn(userId);
        when(claims.getExpiration()).thenReturn(expiration);
        when(jwt.parseToken("valid-token")).thenReturn(claims);
    }

    private boolean handshake() {
        return interceptor.beforeHandshake(new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(response), mock(WebSocketHandler.class), attributes);
    }

    private void assertRejected() {
        assertThat(handshake()).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(attributes).isEmpty();
        assertThat(response.getContentAsByteArray()).isEmpty();
    }
}
