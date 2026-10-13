package org.example.online_used_marketplace.config;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.util.StringUtils;

/**
 * @author Admin
 * @ClassName: WebSocketConfig
 * @Description: TODO(这里用一句话描述这个类的作用)
 * @date 2026-05-05  21:56
 * @Version:
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final RagChatWebSocketHandler ragChatWebSocketHandler;
    private final JwtHandshakeInterceptor jwtHandshakeInterceptor;
    private final String[] allowedOrigins;

    public WebSocketConfig(RagChatWebSocketHandler ragChatWebSocketHandler,
                           JwtHandshakeInterceptor jwtHandshakeInterceptor,
                           @Value("${app.cors.allowed-origins:http://localhost:*,http://127.0.0.1:*}")
                           String allowedOrigins) {
        this.ragChatWebSocketHandler = ragChatWebSocketHandler;
        this.jwtHandshakeInterceptor = jwtHandshakeInterceptor;
        this.allowedOrigins = StringUtils.tokenizeToStringArray(allowedOrigins, ",");
        if (this.allowedOrigins.length == 0) {
            throw new IllegalArgumentException("WebSocket allowed origins must not be empty");
        }
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry
                .addHandler(ragChatWebSocketHandler,"/ws/ai/rag-chat")
                .addInterceptors(jwtHandshakeInterceptor)
                .setAllowedOriginPatterns(allowedOrigins);

    }
}
