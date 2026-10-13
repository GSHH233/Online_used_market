package org.example.online_used_marketplace.config;

import org.example.online_used_marketplace.utils.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.support.OriginHandshakeInterceptor;
import org.springframework.web.socket.server.support.WebSocketHandlerMapping;
import org.springframework.web.socket.server.support.WebSocketHttpRequestHandler;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WebSocketConfigTest {
    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withUserConfiguration(WebSocketConfig.class)
            .withBean(RagChatWebSocketHandler.class, () -> mock(RagChatWebSocketHandler.class))
            .withBean(JwtHandshakeInterceptor.class, () -> new JwtHandshakeInterceptor(mock(JwtUtil.class)));

    @Test
    void defaultOriginsMatchSharedLoopbackPolicyAndHandshakeIsRegistered() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            WebSocketHandlerMapping mapping = context.getBean(WebSocketHandlerMapping.class);
            WebSocketHttpRequestHandler handler = (WebSocketHttpRequestHandler)
                    mapping.getUrlMap().get("/ws/ai/rag-chat");
            assertThat(handler.getHandshakeInterceptors()).anyMatch(JwtHandshakeInterceptor.class::isInstance);
            OriginHandshakeInterceptor origin = originInterceptor(handler);
            assertThat(origin.getAllowedOriginPatterns())
                    .containsExactly("http://localhost:*", "http://127.0.0.1:*");
            assertThat(checkOrigin(origin, handler, "http://localhost:5173")).isTrue();
            assertThat(checkOrigin(origin, handler, "http://127.0.0.1:8080")).isTrue();
            assertThat(checkOrigin(origin, handler, "http://192.168.1.2:5173")).isFalse();
            assertThat(checkOrigin(origin, handler, "https://untrusted.example")).isFalse();
            assertThat(checkOrigin(origin, handler, "null")).isFalse();
        });
    }

    @Test
    void sharedCorsPropertyOverridesDefaultsAndWhitespaceIsTrimmed() {
        contextRunner.withPropertyValues("app.cors.allowed-origins=https://market.example, https://admin.example")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    WebSocketHttpRequestHandler handler = (WebSocketHttpRequestHandler)
                            context.getBean(WebSocketHandlerMapping.class).getUrlMap().get("/ws/ai/rag-chat");
                    OriginHandshakeInterceptor origin = originInterceptor(handler);
                    assertThat(origin.getAllowedOriginPatterns())
                            .containsExactly("https://market.example", "https://admin.example");
                    assertThat(checkOrigin(origin, handler, "https://market.example")).isTrue();
                    assertThat(checkOrigin(origin, handler, "http://localhost:5173")).isFalse();
                    assertThat(checkOrigin(origin, handler, "https://market.example.evil.test")).isFalse();
                });
    }

    @Test
    void emptyOriginPolicyFailsConfigurationInsteadOfOpeningToAllOrigins() {
        contextRunner.withPropertyValues("app.cors.allowed-origins= , ").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("allowed origins must not be empty");
        });
    }

    private OriginHandshakeInterceptor originInterceptor(WebSocketHttpRequestHandler handler) {
        return handler.getHandshakeInterceptors().stream()
                .filter(OriginHandshakeInterceptor.class::isInstance)
                .map(OriginHandshakeInterceptor.class::cast).findFirst().orElseThrow();
    }

    private boolean checkOrigin(HandshakeInterceptor interceptor, WebSocketHttpRequestHandler handler,
                                String origin) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws/ai/rag-chat");
        request.setServerName("api.example");
        request.addHeader(HttpHeaders.ORIGIN, origin);
        return interceptor.beforeHandshake(new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(new MockHttpServletResponse()), handler.getWebSocketHandler(),
                new HashMap<>());
    }
}
