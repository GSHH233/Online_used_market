package org.example.online_used_marketplace.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import jakarta.validation.Validator;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.ai.memory.MemoryScope;
import org.example.online_used_marketplace.modules.ai.service.RagChatService;
import org.example.online_used_marketplace.modules.ai.vo.WsChatDoneVO;
import org.example.online_used_marketplace.utils.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Date;
import java.util.HashMap;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RagChatWebSocketIdentityTest {

    @Test
    void missingTokenCannotStartChat() throws Exception {
        RagChatService chat = mock(RagChatService.class);
        RagChatWebSocketHandler handler = new RagChatWebSocketHandler(new ObjectMapper(), chat,
                validator(), mock(JwtUtil.class), limiter());
        WebSocketSession session = session();

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"CHAT_START\",\"question\":\"手机\"}"));

        verifyNoInteractions(chat);
        var sent = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(sent.capture());
        assertThat(sent.getValue().getPayload()).contains("401", "CHAT_ERROR");
    }

    @Test
    void signedUserIdIsUsedAsMemoryOwner() throws Exception {
        RagChatService chat = mock(RagChatService.class);
        JwtUtil jwt = mock(JwtUtil.class);
        Claims claims = mock(Claims.class);
        when(claims.get("userId")).thenReturn(42L);
        when(claims.getExpiration()).thenReturn(new Date(System.currentTimeMillis() + 60_000));
        when(jwt.parseToken("valid-token")).thenReturn(claims);
        WsChatDoneVO answer = new WsChatDoneVO();
        answer.setAnswer("可以披露机况后发布");
        when(chat.chat(any(MemoryScope.class), eq("手机"), any(), any(), any())).thenReturn(answer);
        RagChatWebSocketHandler handler = new RagChatWebSocketHandler(new ObjectMapper(), chat, validator(), jwt, limiter());

        handler.handleTextMessage(session(), new TextMessage(
                "{\"type\":\"CHAT_START\",\"question\":\"手机\",\"token\":\"valid-token\"}"));

        verify(chat).chat(eq(new MemoryScope("42", "ws-session")), eq("手机"), any(), any(), any());
    }

    @Test
    void stableConversationIdRemainsScopedToSignedUser() throws Exception {
        RagChatService chat = mock(RagChatService.class);
        JwtUtil jwt = mock(JwtUtil.class);
        Claims claims = mock(Claims.class);
        when(claims.get("userId")).thenReturn(42L);
        when(claims.getExpiration()).thenReturn(new Date(System.currentTimeMillis() + 60_000));
        when(jwt.parseToken("valid-token")).thenReturn(claims);
        when(chat.chat(any(MemoryScope.class), eq("手机"), any(), any(), any())).thenReturn(new WsChatDoneVO());
        RagChatWebSocketHandler handler = new RagChatWebSocketHandler(new ObjectMapper(), chat, validator(), jwt, limiter());

        handler.handleTextMessage(session(), new TextMessage(
                "{\"type\":\"CHAT_START\",\"question\":\"手机\",\"token\":\"valid-token\",\"conversationId\":\"conversation-1\"}"));

        verify(chat).chat(eq(new MemoryScope("42", "conversation-1")), eq("手机"), any(), any(), any());
    }

    @Test
    void expiredTokenCannotReachRateLimiterOrChat() throws Exception {
        RagChatService chat = mock(RagChatService.class);
        RagChatRateLimiter limiter = mock(RagChatRateLimiter.class);
        JwtUtil jwt = jwt(42L);
        when(jwt.parseToken("valid-token").getExpiration()).thenReturn(new Date(0));
        WebSocketSession session = session();
        handler(chat, jwt, limiter).handleTextMessage(session, start("conversation-1"));

        verifyNoInteractions(chat, limiter);
        assertError(session, 401);
    }

    @Test
    void jwtIsRevalidatedOnEveryStartEvenAfterSuccessfulChat() throws Exception {
        RagChatService chat = mock(RagChatService.class);
        JwtUtil jwt = jwt(42L);
        when(chat.chat(any(), any(), any(), any(), any())).thenReturn(new WsChatDoneVO());
        RagChatWebSocketHandler handler = handler(chat, jwt, limiter());
        WebSocketSession session = session();
        handler.handleTextMessage(session, start("conversation-1"));
        when(jwt.parseToken("valid-token")).thenThrow(new IllegalArgumentException("expired"));

        handler.handleTextMessage(session, start("conversation-1"));

        verify(jwt, times(2)).parseToken("valid-token");
        verify(chat, times(1)).chat(any(), any(), any(), any(), any());
    }

    @Test
    void messageUserMustMatchOptionalHandshakeUser() throws Exception {
        RagChatService chat = mock(RagChatService.class);
        RagChatRateLimiter limiter = mock(RagChatRateLimiter.class);
        WebSocketSession session = session();
        session.getAttributes().put(JwtHandshakeInterceptor.USER_ID_ATTRIBUTE, "7");

        handler(chat, jwt(42L), limiter).handleTextMessage(session, start("conversation-1"));

        verifyNoInteractions(chat, limiter);
        assertError(session, 401);
    }

    @Test
    void matchingHandshakeUserStillNeedsMessageToken() throws Exception {
        RagChatService chat = mock(RagChatService.class);
        RagChatRateLimiter limiter = mock(RagChatRateLimiter.class);
        WebSocketSession session = session();
        session.getAttributes().put(JwtHandshakeInterceptor.USER_ID_ATTRIBUTE, "42");

        handler(chat, jwt(42L), limiter).handleTextMessage(session,
                new TextMessage("{\"type\":\"CHAT_START\",\"question\":\"phone\"}"));

        verifyNoInteractions(chat, limiter);
        assertError(session, 401);
    }

    @Test
    void matchingHandshakeAndMessageIdentityCanStartChat() throws Exception {
        RagChatService chat = mock(RagChatService.class);
        WebSocketSession session = session();
        session.getAttributes().put(JwtHandshakeInterceptor.USER_ID_ATTRIBUTE, "42");
        when(chat.chat(any(), any(), any(), any(), any())).thenReturn(new WsChatDoneVO());

        handler(chat, jwt(42L), limiter()).handleTextMessage(session, start("conversation-1"));

        verify(chat).chat(eq(new MemoryScope("42", "conversation-1")), any(), any(), any(), any());
    }

    @Test
    void rateLimitDenialReturns429WithoutAckOrBusinessCall() throws Exception {
        RagChatService chat = mock(RagChatService.class);
        RagChatRateLimiter limiter = mock(RagChatRateLimiter.class);
        when(limiter.tryAcquire("42")).thenReturn(false);
        WebSocketSession session = session();

        handler(chat, jwt(42L), limiter).handleTextMessage(session, start("conversation-1"));

        verifyNoInteractions(chat);
        assertError(session, 429);
    }

    @Test
    void redisFailureIsReportedWithoutAckOrBusinessCall() throws Exception {
        RagChatService chat = mock(RagChatService.class);
        RagChatRateLimiter limiter = mock(RagChatRateLimiter.class);
        when(limiter.tryAcquire("42")).thenThrow(new BusinessException(ResultCode.INTERNAL_ERROR));
        WebSocketSession session = session();

        handler(chat, jwt(42L), limiter).handleTextMessage(session, start("conversation-1"));

        verifyNoInteractions(chat);
        assertError(session, 500);
    }

    @Test
    void sameUserSharesLimiterAcrossConnectionsAndConversations() throws Exception {
        RagChatService chat = mock(RagChatService.class);
        RagChatRateLimiter limiter = mock(RagChatRateLimiter.class);
        when(limiter.tryAcquire("42")).thenReturn(true, false);
        when(chat.chat(any(), any(), any(), any(), any())).thenReturn(new WsChatDoneVO());
        RagChatWebSocketHandler handler = handler(chat, jwt(42L), limiter);
        WebSocketSession first = session();
        WebSocketSession second = session();
        when(second.getId()).thenReturn("second-connection");

        handler.handleTextMessage(first, start("conversation-1"));
        handler.handleTextMessage(second, start("conversation-2"));

        verify(limiter, times(2)).tryAcquire("42");
        verify(chat, times(1)).chat(any(), any(), any(), any(), any());
        assertError(second, 429);
    }

    @Test
    void pingDoesNotConsumeChatQuota() throws Exception {
        RagChatService chat = mock(RagChatService.class);
        RagChatRateLimiter limiter = mock(RagChatRateLimiter.class);
        WebSocketSession session = session();

        handler(chat, mock(JwtUtil.class), limiter).handleTextMessage(session,
                new TextMessage("{\"type\":\"CHAT_PING\",\"requestId\":\"ping\"}"));

        verifyNoInteractions(chat, limiter);
        var sent = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(sent.capture());
        assertThat(sent.getValue().getPayload()).contains("CHAT_PONG");
    }

    private TextMessage start(String conversationId) {
        return new TextMessage("{\"type\":\"CHAT_START\",\"question\":\"phone\",\"requestId\":\"test\","
                + "\"token\":\"valid-token\",\"conversationId\":\"" + conversationId + "\"}");
    }

    private RagChatWebSocketHandler handler(RagChatService chat, JwtUtil jwt, RagChatRateLimiter limiter) {
        return new RagChatWebSocketHandler(new ObjectMapper(), chat, validator(), jwt, limiter);
    }

    private JwtUtil jwt(long userId) {
        JwtUtil jwt = mock(JwtUtil.class);
        Claims claims = mock(Claims.class);
        when(claims.get("userId")).thenReturn(userId);
        when(claims.getExpiration()).thenReturn(new Date(System.currentTimeMillis() + 60_000));
        when(jwt.parseToken("valid-token")).thenReturn(claims);
        return jwt;
    }

    private RagChatRateLimiter limiter() {
        RagChatRateLimiter limiter = mock(RagChatRateLimiter.class);
        when(limiter.tryAcquire(any())).thenReturn(true);
        return limiter;
    }

    private void assertError(WebSocketSession session, int code) throws Exception {
        var sent = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(sent.capture());
        var envelope = new ObjectMapper().readTree(sent.getValue().getPayload());
        assertThat(envelope.path("type").asText()).isEqualTo("CHAT_ERROR");
        assertThat(envelope.path("payload").path("code").asInt()).isEqualTo(code);
        assertThat(sent.getValue().getPayload()).doesNotContain("valid-token");
    }

    @SuppressWarnings("unchecked")
    private Validator validator() {
        Validator validator = mock(Validator.class);
        when(validator.validate(any())).thenReturn(Set.of());
        return validator;
    }

    private WebSocketSession session() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("ws-session");
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(new HashMap<>());
        return session;
    }
}
