package org.example.online_used_marketplace.config;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.ai.dto.KnowledgeSearchFilterDTO;
import org.example.online_used_marketplace.modules.ai.dto.WsChatCancelRequestDTO;
import org.example.online_used_marketplace.modules.ai.dto.WsChatStartRequestDTO;
import org.example.online_used_marketplace.modules.ai.enums.WsMessageType;
import org.example.online_used_marketplace.modules.ai.service.RagChatService;
import org.example.online_used_marketplace.modules.ai.vo.*;
import org.springframework.stereotype.Component;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Admin
 * @ClassName: RagChatWebSocketHandler
 * @Description: TODO(这里用一句话描述这个类的作用)
 * @date 2026-05-05  22:01
 * @Version:
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RagChatWebSocketHandler extends TextWebSocketHandler {
    private final ObjectMapper mapper;
    private final RagChatService ragChatService;
    private final Validator validator;
    private final Map<String, SessionChatState> sessionStateMap = new ConcurrentHashMap<>();
    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        WsChatStartRequestDTO req = null;
        String traceId = UUID.randomUUID().toString();
        String rawRequestId = null;
        try {
            String payload = message.getPayload();
            JsonNode root = mapper.readTree(payload);

            JsonNode rid = root.get("requestId");
            if (rid != null && !rid.isNull() && !rid.asText().isBlank()) {
                rawRequestId = rid.asText();
            }

            JsonNode typeNode = root.get("type");
            if (typeNode == null || typeNode.isNull()) {
                sendError(session, rawRequestId, ResultCode.BAD_REQUEST.getCode(), traceId, "缺少type字段");
                return;
            }

            WsMessageType type;
            try {
                type = WsMessageType.valueOf(typeNode.asText());
            } catch (IllegalArgumentException ex) {
                sendError(session, rawRequestId, ResultCode.BAD_REQUEST.getCode(), traceId, "不支持的type: " + typeNode.asText());
                return;
            }
            if (type==WsMessageType.CHAT_PING){
                sendPong(session,rawRequestId,traceId);
                return;
            }

            if (type == WsMessageType.CHAT_START) {
                req = mapper.treeToValue(root, WsChatStartRequestDTO.class);
                Set<ConstraintViolation<WsChatStartRequestDTO>> violations = validator.validate(req);
                if (!violations.isEmpty()) {
                    sendError(session, req, ResultCode.BAD_REQUEST.getCode(), traceId, violations.iterator().next().getMessage());
                    return;
                }
                handleStart(session, req, traceId);
                return;
            }

            if (type == WsMessageType.CHAT_CANCEL) {
                WsChatCancelRequestDTO cancelReq = mapper.treeToValue(root, WsChatCancelRequestDTO.class);
                handleCancel(session, cancelReq, traceId);
                return;
            }

            sendError(session, rawRequestId, ResultCode.BAD_REQUEST.getCode(), traceId, "当前仅支持CHAT_START/CHAT_CANCEL消息");
        } catch (BusinessException e) {
            int code = (e.getCode() == null) ? ResultCode.INTERNAL_ERROR.getCode() : e.getCode();
            sendError(session, req, code, traceId, e.getMessage());
        } catch (Exception e) {
            sendError(session, rawRequestId, ResultCode.INTERNAL_ERROR.getCode(), traceId, "服务器内部错误");
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        String sessionId = session.getId();
        SessionChatState state = sessionStateMap.get(sessionId);
        String traceId = (state != null ? state.traceId : UUID.randomUUID().toString());
        String requestId = (state != null ? state.requestId : null);
        log.warn("ws transport error, sessionId={}, requestId={}, traceId={}", sessionId, requestId, traceId, exception);
        if (session.isOpen()){
            WsChatErrorVO err=new WsChatErrorVO();
            err.setTraceId(traceId);
            err.setRequestId(requestId);
            err.setCode(ResultCode.BAD_REQUEST.getCode());
            err.setMessage("连接异常，请重试");
            writeEnvelope(session,WsChatEnvelopeVO.of(WsMessageType.CHAT_ERROR,requestId,err));
        }
        sessionStateMap.remove(sessionId);
        if (session.isOpen()){
            session.close(CloseStatus.SERVER_ERROR);
        }
        super.handleTransportError(session, exception);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String sessionId = session.getId();
        SessionChatState state = sessionStateMap.get(sessionId);
        String traceId = (state != null ? state.traceId : null);
        String requestId = (state != null ? state.requestId : null);
        log.info("ws closed, sessionId={}, requestId={}, traceId={}, code={}, reason={}",
                sessionId, requestId, traceId, status.getCode(), status.getReason());
        sessionStateMap.remove(sessionId);
        super.afterConnectionClosed(session, status);
    }

    private void sendAck(WebSocketSession session, WsChatStartRequestDTO req, String traceId) throws IOException {
        WsChatAckVO ack = new WsChatAckVO();
        ack.setRequestId(req == null ? null : req.getRequestId());
        ack.setMessage("请求已接收，正在处理");
        ack.setTraceId(traceId);
        writeEnvelope(session, WsChatEnvelopeVO.of(WsMessageType.CHAT_ACK, req == null ? null : req.getRequestId(), ack));
    }
    private void sendCancel(WebSocketSession session, String requestId, String traceId) throws IOException {
        WsChatCancelVO cancel=new WsChatCancelVO();
        cancel.setCancelled(true);
        cancel.setMessage("请求已取消");
        cancel.setRequestId(requestId);
        cancel.setTraceId(traceId);
        writeEnvelope(session, WsChatEnvelopeVO.of(WsMessageType.CHAT_CANCEL, requestId, cancel));
    }
    private void sendPong(WebSocketSession session,String requestId,String traceId) throws IOException {
        WsChatPongVO vo=new WsChatPongVO();
        vo.setRequestId(requestId);
        vo.setTraceId(traceId);
        vo.setTs(System.currentTimeMillis());
        vo.setMessage("pong");
        writeEnvelope(session,WsChatEnvelopeVO.of(WsMessageType.CHAT_PONG,requestId,vo));
    }
    private void sendDelta(WebSocketSession session, String requestId, String traceId, String delta, Boolean done) throws IOException {
        WsChatDeltaVO vo=new WsChatDeltaVO();
        vo.setRequestId(requestId);
        vo.setDelta(delta);
        vo.setTraceId(traceId);
        vo.setDone(done);
        writeEnvelope(session,WsChatEnvelopeVO.of(WsMessageType.CHAT_DELTA,requestId,vo));
    }
    private void sendSource(WebSocketSession session, String requestId, String traceId, List<RagSourceVO> sources) throws IOException {
        WsChatSourceVO vo=new WsChatSourceVO();
        vo.setSources(sources);
        vo.setRequestId(requestId);
        vo.setTraceId(traceId);
        writeEnvelope(session,WsChatEnvelopeVO.of(WsMessageType.CHAT_SOURCE,requestId,vo));
    }

    private void handleStart(WebSocketSession session, WsChatStartRequestDTO req, String traceId) throws IOException {
        SessionChatState state = new SessionChatState();
        state.setCancelled(false);
        state.setTerminalSent(false);
        state.setRequestId(req.getRequestId());
        state.setTraceId(traceId);
        state.setStartAt(LocalDateTime.now());
        sessionStateMap.put(session.getId(), state);

        sendAck(session, req, traceId);
        KnowledgeSearchFilterDTO filter = req.getFilter();
        WsChatDoneVO done = ragChatService.chat(session.getId(), req.getQuestion(), traceId, req.getTopK(), filter);
        if (done == null) {
            if (tryMarkTerminal(state)) {
                sendError(session, req, ResultCode.INTERNAL_ERROR.getCode(), traceId, "服务器内部错误");
            }
            sessionStateMap.remove(session.getId());
            return;
        }

        String answer = safe(done.getAnswer());
        List<RagSourceVO> sources = done.getSources() == null ? List.of() : done.getSources();
        List<String> chunks = splitAnswer(answer, 40);
        for (int i = 0; i < chunks.size(); i++) {
            SessionChatState currentState = sessionStateMap.get(session.getId());
            if (currentState == null || currentState.isCancelled()) {
                if (tryMarkTerminal(state)) {
                    sendDoneCancelled(session, req.getRequestId(), traceId);
                }
                sessionStateMap.remove(session.getId());
                return;
            }
            String chunk = chunks.get(i);
            boolean isLast = (i == chunks.size() - 1);
            sendDelta(session, req.getRequestId(), traceId, chunk, isLast);
        }
        if (chunks.isEmpty()) {
            sendDelta(session, req.getRequestId(), traceId, "", true);
        }

        sendSource(session, req.getRequestId(), traceId, sources);
        if (tryMarkTerminal(state)) {
            sendDone(session, req, traceId, done);
        }
        sessionStateMap.remove(session.getId());
    }

    private void handleCancel(WebSocketSession session, WsChatCancelRequestDTO cancelReq, String traceId) throws IOException {
        Set<ConstraintViolation<WsChatCancelRequestDTO>> violations = validator.validate(cancelReq);
        if (!violations.isEmpty()) {
            sendError(session, cancelReq.getRequestId(), ResultCode.BAD_REQUEST.getCode(), traceId, violations.iterator().next().getMessage());
            return;
        }


        SessionChatState state = sessionStateMap.get(session.getId());
        if (state == null) {
            sendError(session, cancelReq.getRequestId(), ResultCode.BAD_REQUEST.getCode(), traceId, "当前没有可取消的进行中请求");
            sessionStateMap.remove(session.getId());
            return;
        }

        if (!Objects.equals(state.getRequestId(), cancelReq.getRequestId())) {
            sendError(session, cancelReq.getRequestId(), ResultCode.BAD_REQUEST.getCode(), traceId, "requestId与当前进行中请求不匹配");
            sessionStateMap.remove(session.getId());
            return;
        }

        state.setCancelled(true);
        sendCancel(session, cancelReq.getRequestId(), state.getTraceId());
        if (tryMarkTerminal(state)) {
            sendDoneCancelled(session, state.getRequestId(), state.getTraceId());
        }
        sessionStateMap.remove(session.getId());
    }

    private boolean tryMarkTerminal(SessionChatState state) {
        if (state == null) {
            return false;
        }
        synchronized (state) {
            if (state.isTerminalSent()) {
                return false;
            }
            state.setTerminalSent(true);
            return true;
        }
    }

    private void sendDone(WebSocketSession session, WsChatStartRequestDTO req,String traceId, WsChatDoneVO done) throws IOException {
        if (done == null) {
            sendError(session, req, ResultCode.INTERNAL_ERROR.getCode(), traceId, "服务器内部错误");
            return;
        }
        if (done.getRequestId() == null && req != null) {
            done.setRequestId(req.getRequestId());
        }
        done.setTraceId(traceId);
        writeEnvelope(session, WsChatEnvelopeVO.of(WsMessageType.CHAT_DONE, req == null ? null : req.getRequestId(), done));
    }

    private void sendError(WebSocketSession session, WsChatStartRequestDTO req, Integer code, String traceId, String message) throws IOException {
        sendError(session, req == null ? null : req.getRequestId(), code, traceId, message);
    }
    private void sendDoneCancelled(WebSocketSession session, String requestId, String traceId) throws IOException {
        WsChatDoneVO done = new WsChatDoneVO();
        done.setLowConfidence(true);
        done.setSources(Collections.emptyList());
        done.setAnswer("已取消");
        done.setRequestId(requestId);
        done.setTraceId(traceId);
        done.setCancelled(true);
        writeEnvelope(session, WsChatEnvelopeVO.of(WsMessageType.CHAT_DONE, requestId, done));
    }

    private void sendError(WebSocketSession session, String requestId, Integer code, String traceId, String message) throws IOException {
        WsChatErrorVO error = new WsChatErrorVO();
        error.setRequestId(requestId);
        error.setCode(code);
        error.setMessage(message);
        error.setTraceId(traceId);
        writeEnvelope(session, WsChatEnvelopeVO.of(WsMessageType.CHAT_ERROR, requestId, error));
    }

    private void writeEnvelope(WebSocketSession session, WsChatEnvelopeVO<?> envelope) throws IOException {
        String json = mapper.writeValueAsString(envelope);
        session.sendMessage(new TextMessage(json));
    }
    private List<String> splitAnswer(String answer, int chunkSize) {
        if (answer == null || answer.isBlank()) {
            return Collections.emptyList();
        }
        int size = Math.max(chunkSize, 1);
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < answer.length(); i += size) {
            int end = Math.min(i + size, answer.length());
            chunks.add(answer.substring(i, end));
        }
        return chunks;
    }
    private String safe(String s) {
        return s == null ? "" : s;
    }
    @Data
    public static class SessionChatState {
        String requestId;
        String traceId;
        LocalDateTime startAt;
        volatile boolean cancelled;
        volatile boolean terminalSent;
    }

}
