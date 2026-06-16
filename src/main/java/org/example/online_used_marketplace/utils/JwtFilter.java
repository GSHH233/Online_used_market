package org.example.online_used_marketplace.utils;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.modules.user.entity.UserPrincipal;
import org.example.online_used_marketplace.modules.user.enums.UserRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtFilter extends OncePerRequestFilter {
    private final JwtUtil jwtUtil;

    @Value("${jwt.token-header}")
    private String tokenHeader;

    @Value("${jwt.token-prefix}")
    private String tokenPrefix;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(tokenHeader);
        if (StringUtils.hasText(header) && header.startsWith(tokenPrefix + " ")) {
            String token = header.substring(tokenPrefix.length() + 1);
            try {
                Long userId = jwtUtil.getUserId(token);
                String username = jwtUtil.getUsername(token);
                String role = UserRole.normalize(jwtUtil.getRole(token));
                if (jwtUtil.isTokenExpired(token) || userId == null || !StringUtils.hasText(username)) {
                    writeUnauthorized(response, "token已过期，请重新登录");
                    return;
                }

                UserPrincipal userPrincipal = new UserPrincipal(userId, username, role);
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        userPrincipal,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))
                );
                if (SecurityContextHolder.getContext().getAuthentication() == null) {
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            } catch (Exception e) {
                log.warn("JWT校验失败, uri={}, reason={}", request.getRequestURI(), e.getMessage());
                writeUnauthorized(response, "token已过期，请重新登录");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    public void writeUnauthorized(HttpServletResponse response, String message) {
        String json = "{"
                + "\"code\":401,"
                + "\"message\":\"" + message + "\","
                + "\"data\":null"
                + "}";
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        try {
            response.getWriter().write(json);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
