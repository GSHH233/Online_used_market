package org.example.online_used_marketplace.utils;


import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletResponse;
import org.example.online_used_marketplace.common.Result;
import org.example.online_used_marketplace.modules.user.enums.UserRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.util.Date;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * @author Admin
 * @ClassName: JwtUtil
 * @Description: TODO(这里用一句话描述这个类的作用)
 * @date 2026-03-26  19:36
 * @Version:
 */
@Component
public class JwtUtil  {
    @Value("${jwt.secret}")
    private String secret;
    @Value("${jwt.expiration}")
    private long expiration;
    private SecretKey getKey(){
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
    public String generateToken(Long userId,String username){
        return generateToken(userId, username, UserRole.USER.name());
    }

    public String generateToken(Long userId, String username, String role){
        Date now =new Date();
        Date expireAt=new Date(now.getTime()+expiration);
        return Jwts.builder()
                .claims(Map.of(
                        "userId",userId,
                        "username",username,
                        "role", UserRole.normalize(role)
                ))
                .issuedAt(now)
                .expiration(expireAt)
                .signWith(getKey())
                .compact();
    }
    public Claims parseToken(String token){
        return Jwts.parser()
                .verifyWith(getKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
    public boolean isTokenExpired(String token){
        Date exp=parseToken(token).getExpiration();
        return exp.before(new Date());
    }
    public Long getUserId(String token){
        Object value=parseToken(token).get("userId");
        return value == null?null:Long.valueOf(value.toString());
    }
    public String getUsername(String token){
        Object value=parseToken(token).get("username");
        return value == null?null:value.toString();
    }

    public String getRole(String token){
        Object value=parseToken(token).get("role");
        return UserRole.normalize(value == null ? null : value.toString());
    }

}
