package org.example.online_used_marketplace.config;


import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * @author Admin
 * @ClassName: WebSocketConfig
 * @Description: TODO(这里用一句话描述这个类的作用)
 * @date 2026-05-05  21:56
 * @Version:
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {
    private final RagChatWebSocketHandler ragChatWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry
                .addHandler(ragChatWebSocketHandler,"/ws/ai/rag-chat")
                .setAllowedOriginPatterns("*");

    }
}
