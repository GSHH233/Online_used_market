package org.example.online_used_marketplace.config;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.utils.JwtUtil;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Date;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class JwtHandshakeInterceptor implements HandshakeInterceptor {
    public static final String USER_ID_ATTRIBUTE = JwtHandshakeInterceptor.class.getName() + ".userId";
    private final JwtUtil jwtUtil;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        List<String> headers = request.getHeaders().get(HttpHeaders.AUTHORIZATION);
        // Browser clients still authenticate every CHAT_START message.
        if (headers == null) {
            return true;
        }
        try {
            if (headers.size() != 1) {
                throw new IllegalArgumentException("ambiguous authorization");
            }
            String header = headers.get(0);
            if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
                throw new IllegalArgumentException("invalid authorization scheme");
            }
            String token = header.substring(7).trim();
            if (token.isEmpty() || token.length() > 4096) {
                throw new IllegalArgumentException("invalid token length");
            }
            Claims claims = jwtUtil.parseToken(token);
            Date expiration = claims.getExpiration();
            Object claim = claims.get("userId");
            if (claim == null || expiration == null || !expiration.after(new Date())) {
                throw new IllegalArgumentException("missing or expired identity");
            }
            long userId = Long.parseLong(claim.toString());
            if (userId <= 0) {
                throw new IllegalArgumentException("invalid identity");
            }
            attributes.put(USER_ID_ATTRIBUTE, Long.toString(userId));
            return true;
        } catch (Exception ex) {
            // Never echo credentials or log JWT exception details.
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
