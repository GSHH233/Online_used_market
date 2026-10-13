package org.example.online_used_marketplace.modules.ai.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.ai.dto.KnowledgeSearchFilterDTO;
import org.example.online_used_marketplace.modules.ai.event.RagChatOutcomeEvent;
import org.example.online_used_marketplace.modules.ai.memory.L2MemoryWriteCommand;
import org.example.online_used_marketplace.modules.ai.memory.MemoryScope;
import org.example.online_used_marketplace.modules.ai.service.ChatGenerationService;
import org.example.online_used_marketplace.modules.ai.service.ConversationMemoryService;
import org.example.online_used_marketplace.modules.ai.service.KnowledgeSearchService;
import org.example.online_used_marketplace.modules.ai.service.L2MemoryService;
import org.example.online_used_marketplace.modules.ai.service.PreferenceAutofillService;
import org.example.online_used_marketplace.modules.ai.service.RagChatService;
import org.example.online_used_marketplace.modules.ai.service.SessionSummaryService;
import org.example.online_used_marketplace.modules.ai.service.TokenGovernanceService;
import org.example.online_used_marketplace.modules.ai.service.UserProfileService;
import org.example.online_used_marketplace.modules.ai.token.TokenBudgetResult;
import org.example.online_used_marketplace.modules.ai.token.TokenGenerationRequest;
import org.example.online_used_marketplace.modules.ai.token.TokenGenerationResult;
import org.example.online_used_marketplace.modules.ai.token.TokenPromptSection;
import org.example.online_used_marketplace.modules.ai.token.TokenScene;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeSearchHitVO;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeSearchResponseVO;
import org.example.online_used_marketplace.modules.ai.vo.RagSourceVO;
import org.example.online_used_marketplace.modules.ai.vo.UserProfileVO;
import org.example.online_used_marketplace.modules.ai.vo.WsChatDoneVO;
import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Admin
 * @ClassName: RagChatServiceImpl
 * @Description: TODO(这里用一句话描述这个类的作用)
 * @date 2026-05-05  22:24
 * @Version:
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RagChatServiceImpl implements RagChatService {
    private final KnowledgeSearchService knowledgeSearchService;
    private final ChatGenerationService chatGenerationService;
    private final ConversationMemoryService conversationMemoryService;
    private final SessionSummaryService sessionSummaryService;
    private final L2MemoryService l2MemoryService;
    private final PreferenceAutofillService preferenceAutofillService;
    private final UserProfileService userProfileService;
    private final TokenGovernanceService tokenGovernanceService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    private static final int TOP_N_FOR_AVG = 3;
    private static final double HIGH_CONFIDENCE_SCORE = 0.68D;
    private static final double MEDIUM_CONFIDENCE_SCORE = 0.48D;
    private static final Pattern UNSUPPORTED_QUANTIFIED_ADVICE_PATTERN = Pattern.compile(
            "((\\d+(?:\\.\\d+)?\\s*[%-－—~～至到]\\s*\\d+(?:\\.\\d+)?\\s*(?:%|折|倍|元|块)?)|(\\d+(?:\\.\\d+)?\\s*(?:%|折|倍|元|块)))"
    );
    private static final List<String> SENSITIVE_UNGROUNDED_TERMS = List.of(
            "规避监管", "监管", "诈骗", "欺诈", "封号", "封禁", "处罚",
            "违法", "法律", "合法", "所有权", "产权", "归属", "赔付", "赔偿",
            "保障", "维权", "权益", "官方渠道", "联系客服", "责任划分", "付款责任",
            "平台认定", "平台判定", "违反", "违规", "极高风险", "严重风险"
    );
    private static final List<String> UNCERTAINTY_MARKERS = List.of(
            "无法确定", "不能确定", "无法判断", "不能判断", "证据不足", "证据未",
            "未说明", "未提供", "缺少", "尚不能", "还不能"
    );

    @Override
    public WsChatDoneVO chat(MemoryScope scope, String question, String traceId, Integer topK, KnowledgeSearchFilterDTO filter) {
        if (scope == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "私有记忆需要已认证用户");
        }
        return chatInternal(scope, scope.sessionId(), question, traceId, topK, filter, false);
    }

    @Override
    public WsChatDoneVO chatForEvaluation(String sessionId, String question, String traceId,
                                          Integer topK, KnowledgeSearchFilterDTO filter) {
        return chatInternal(null, sessionId, question, traceId, topK, filter, true);
    }

    private WsChatDoneVO chatInternal(MemoryScope scope, String sessionId, String question, String traceId,
                                      Integer topK, KnowledgeSearchFilterDTO filter,
                                      boolean evaluationMode) {
        long requestStartedNanos = System.nanoTime();
        if (!StringUtils.hasText(question)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "问题不能为空");
        }

        String normalizedQuestion = question.trim();
        Integer normalizedTopK = ((topK == null || topK <= 0) ? 5 : topK);
        String memoryUserId = evaluationMode ? "eval" : scope.userId();

        Map<String, String> explicitPreferences = evaluationMode
                ? Collections.emptyMap()
                : extractPreferencesByRule(normalizedQuestion);
        Map<String, String> llmPreferences = evaluationMode
                ? Collections.emptyMap()
                : extractPreferencesByLlm(normalizedQuestion, traceId);
        Map<String, String> preferenceCandidates = mergePreferences(explicitPreferences, llmPreferences);

        String sessionSummary = evaluationMode ? "" : shouldIgnoreL3ForCurrentTurn(normalizedQuestion)
                ? "（本轮用户已切换当前商品，旧L3摘要不注入）"
                : sessionSummaryService.getSummary(scope);
        Map<String, String> l3AutofillParams = evaluationMode || shouldIgnoreL3ForCurrentTurn(normalizedQuestion)
                ? Collections.emptyMap()
                : sessionSummaryService.getAutofillParams(scope);
        UserProfileVO historicalProfile = evaluationMode
                ? new UserProfileVO()
                : userProfileService.buildProfile(memoryUserId, sessionId);
        Map<String, String> historicalPreferences = evaluationMode
                ? Collections.emptyMap()
                : userProfileService.toPreferenceMap(historicalProfile);
        Map<String, String> autofillBase = mergePreferences(l3AutofillParams, historicalPreferences);
        PreferenceAutofillService.AutofillResult autofillResult = preferenceAutofillService.autofill(explicitPreferences, autofillBase);

        Map<String, String> extractedPreferences = mergePreferences(autofillResult.getMergedParams(), preferenceCandidates);
        if (!evaluationMode) {
            persistPreferencesToL2(memoryUserId, sessionId, preferenceCandidates, traceId);
        }
        UserProfileVO effectiveProfile = evaluationMode
                ? historicalProfile
                : userProfileService.mergeCurrentTurn(historicalProfile, extractedPreferences);
        String preferenceAutofillDebug = "autofilledFields=" + autofillResult.getAutofilledFields()
                + ",explicit=" + explicitPreferences
                + ",historicalProfile=" + historicalPreferences
                + ",l3Autofill=" + l3AutofillParams
                + ",final=" + extractedPreferences
                + ",profile=" + userProfileService.toPreferenceMap(effectiveProfile);

        List<String> recentContext = evaluationMode
                ? Collections.emptyList()
                : conversationMemoryService.getRecentMessages(scope, 10);
        List<String> l2MemoryContext = evaluationMode
                ? Collections.emptyList()
                : l2MemoryService.retrieve(memoryUserId, sessionId, normalizedQuestion, 3, 0.4D)
                        .stream()
                        .map(one -> "[" + one.getMemoryType() + "] " + one.getContent())
                        .toList();
        String preferencePromptBlock = evaluationMode ? "" : userProfileService.toPromptBlock(effectiveProfile);
        String memoryAssemblyDebug = buildMemoryAssemblyDebug(
                normalizedQuestion,
                sessionId,
                sessionSummary,
                preferencePromptBlock,
                historicalPreferences,
                l3AutofillParams,
                l2MemoryContext,
                recentContext,
                preferenceAutofillDebug
        );
        long contextAssemblyElapsedMs = elapsedMs(requestStartedNanos);

        long retrievalStartedNanos = System.nanoTime();
        KnowledgeSearchResponseVO searchResp = knowledgeSearchService.searchHybrid(normalizedQuestion, normalizedTopK, filter);
        long retrievalElapsedMs = searchResp != null && searchResp.getRetrievalElapsedMs() != null
                ? searchResp.getRetrievalElapsedMs()
                : elapsedMs(retrievalStartedNanos);
        List<KnowledgeSearchHitVO> hits = (searchResp == null || searchResp.getHits() == null) ? Collections.emptyList() : searchResp.getHits();
        if (hits.isEmpty()) {
            WsChatDoneVO vo = buildEmptyResponse(traceId, searchResp);
            vo.setMemoryAssemblyDebug(memoryAssemblyDebug);
            return finishTurn(vo, normalizedQuestion, memoryUserId, sessionId, normalizedTopK,
                    classifyGapReason(normalizedQuestion, "NO_RETRIEVAL"), false,
                    evaluationMode, contextAssemblyElapsedMs, retrievalElapsedMs, 0L, requestStartedNanos);
        }

        List<Double> scores = hits.stream()
                .map(hit -> hit.getRetrievalScore() == null ? hit.getScore() : hit.getRetrievalScore())
                .filter(Objects::nonNull)
                .sorted(Comparator.reverseOrder())
                .toList();
        double top1Score = scores.isEmpty() ? 0D : scores.get(0);
        double top2Score = scores.size() > 1 ? scores.get(1) : 0D;
        double top3AvgScore = scores.stream()
                .limit(TOP_N_FOR_AVG)
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0D);

        double lexicalCoverage = calculateLexicalCoverage(normalizedQuestion, hits);
        int multiSourceCount = countDistinctSources(hits);
        int vectorHitCount = searchResp == null || searchResp.getVectorHitCount() == null ? 0 : searchResp.getVectorHitCount();
        int keywordHitCount = searchResp == null || searchResp.getKeywordHitCount() == null ? 0 : searchResp.getKeywordHitCount();
        String confidenceLevel = calcConfidenceLevel(
                top1Score, top2Score, top3AvgScore, lexicalCoverage,
                multiSourceCount, vectorHitCount, keywordHitCount, hits);
        boolean missingRequiredFields = isMissingFieldsQuestion(normalizedQuestion);
        boolean lowConfidence = "LOW".equals(confidenceLevel) || missingRequiredFields;
        if (missingRequiredFields) {
            confidenceLevel = "LOW";
        }

        List<String> snippets = new ArrayList<>();
        List<RagSourceVO> sources = new ArrayList<>();
        Set<String> dedup = new HashSet<>();
        for (KnowledgeSearchHitVO hit : hits) {
            if (hit == null) {
                continue;
            }
            String key = buildSourceKey(hit);
            if (StringUtils.hasText(key) && !dedup.add(key)) {
                continue;
            }
            if (StringUtils.hasText(hit.getSnippet())) {
                String title = StringUtils.hasText(hit.getDocTitle()) ? hit.getDocTitle().trim() : "未命名规则";
                snippets.add("[证据" + (snippets.size() + 1) + "|" + title + "]\n" + hit.getSnippet().trim());
            }
            RagSourceVO vo = new RagSourceVO();
            BeanUtils.copyProperties(hit, vo);
            sources.add(vo);
        }

        if (lowConfidence) {
            WsChatDoneVO vo = new WsChatDoneVO();
            vo.setTraceId(traceId);
            vo.setAnswer(buildEvidenceAwareInsufficientAnswer(normalizedQuestion, sources));
            vo.setSources(sources);
            vo.setLowConfidence(true);
            vo.setConfidenceLevel(confidenceLevel);
            vo.setRetrievalMode(searchResp.getRetrievalMode());
            vo.setRetrievalDebug(searchResp.getDebugInfo());
            vo.setConfidenceDebug(buildConfidenceDebug(
                    top1Score, top2Score, top3AvgScore, lexicalCoverage,
                    multiSourceCount, vectorHitCount, keywordHitCount, hits, confidenceLevel));
            vo.setPreferenceAutofillDebug(preferenceAutofillDebug);
            vo.setMemoryAssemblyDebug(memoryAssemblyDebug);
            vo.setGenerationMode("EVIDENCE_REFUSAL");
            vo.setFallbackUsed(false);
            vo.setFallbackReason("");
            return finishTurn(vo, normalizedQuestion, memoryUserId, sessionId, normalizedTopK,
                    classifyGapReason(normalizedQuestion, "LOW_CONFIDENCE"), true,
                    evaluationMode, contextAssemblyElapsedMs, retrievalElapsedMs, 0L, requestStartedNanos);
        }

        String context = String.join("\n---\n", snippets);
        String memoryContext = recentContext.isEmpty() ? "（无）" : String.join("\n", recentContext);
        String summaryContext = StringUtils.hasText(sessionSummary) ? sessionSummary : "（无）";
        String l2Context = l2MemoryContext.isEmpty() ? "（无）" : String.join("\n", l2MemoryContext);
        String answerRules = """
                你是风控与发布助手。只能基于给定证据回答。
                输出规则:
                1. 先直接回答用户问题，保持 2-4 句。
                2. 若证据不足，明确说“无法确定”具体结论或价格，同时说明已有风险点和还缺的信息。
                3. 严禁编造证据外的金额、百分比、折扣、倍数、价格区间、平台规则或检测结果。
                4. 用户已给出的商品、配件、缺失项、状态和检测结果必须在回答中复述并纳入建议。
                5. 证据要求补图、实拍样张或检测截图时，必须明确提醒。
                6. Profile 只用于默认参数，不得覆盖本轮事实、RAG证据和风险规则。
                7. 每个风险结论必须引用对应的[证据N]；不得用“通常、一般、平台可能规定”等外部常识补齐证据。
                8. 用户场景与证据属于同一风险类型时，应给出证据支持的定性结论；只有关键事实确实缺失时才说无法确定。
                9. 除非证据原文明确出现，不得扩展“规避监管、诈骗、封号、平台赔付或保障范围、法律所有权、联系客服”等结论或建议。
                """;
        String ragSystemPrompt = """
                你是二手交易知识库问答助手。证据块是唯一事实来源。
                只允许复述、归纳或应用证据中明确存在的规则，不得引入常识、经验、平台外规则或未经证据支持的建议。
                回答内容只能由三部分组成：用户已经给出的事实、证据明确写出的规则、该规则对用户事实的直接适用结果。
                回答必须简洁，并用[证据N]标明关键结论依据；证据不足时明确指出缺少的事实。
                """;
        List<TokenPromptSection> promptSections = new ArrayList<>();
        promptSections.add(TokenPromptSection.of("回答约束", answerRules, 100, true));
        promptSections.add(TokenPromptSection.of("本轮问题", normalizedQuestion, 100, true));
        promptSections.add(TokenPromptSection.of("RAG证据", context, 95, true));
        if (!evaluationMode) {
            addOptionalPromptSection(promptSections, "L3会话任务摘要", summaryContext, 70);
            addOptionalPromptSection(promptSections, "用户结构化偏好", preferencePromptBlock, 60);
            addOptionalPromptSection(promptSections, "L2长期事实与偏好", l2Context, 50);
            addOptionalPromptSection(promptSections, "最近会话上下文", memoryContext, 40);
        }
        TokenBudgetResult tokenBudget = tokenGovernanceService.assemble(TokenScene.RAG_CHAT, promptSections);
        String prompt = tokenBudget.getPrompt();

        String answer;
        TokenGenerationResult generationResult = null;
        String generationMode = "LLM";
        boolean fallbackUsed = false;
        String fallbackReason = "";
        long generationStartedNanos = System.nanoTime();
        try {
            generationResult = chatGenerationService.generate(TokenGenerationRequest.builder()
                    .scene(TokenScene.RAG_CHAT)
                    .traceId(traceId)
                    .systemPrompt(ragSystemPrompt)
                    .prompt(prompt)
                    .budgetResult(tokenBudget)
                    .build());
            answer = generationResult.getContent();
        } catch (BusinessException ex) {
            log.warn("RAG chat LLM generation failed, fallback to evidence answer, traceId={}, message={}", traceId, ex.getMessage());
            answer = buildEvidenceFallbackAnswer(normalizedQuestion, sources);
            generationMode = "EVIDENCE_FALLBACK";
            fallbackUsed = true;
            fallbackReason = "回答模型暂不可用，已根据检索证据生成降级回答";
        }
        long generationElapsedMs = generationResult != null && generationResult.getElapsedMs() > 0
                ? generationResult.getElapsedMs()
                : elapsedMs(generationStartedNanos);
        answer = improveWeakInsufficientAnswer(answer, normalizedQuestion, sources);
        answer = enforceEvidenceFaithfulness(answer, normalizedQuestion, sessionSummary, sources);

        WsChatDoneVO vo = new WsChatDoneVO();
        vo.setTraceId(traceId);
        vo.setAnswer(answer);
        vo.setSources(sources);
        vo.setLowConfidence(false);
        vo.setConfidenceLevel(confidenceLevel);
        vo.setRetrievalMode(searchResp.getRetrievalMode());
        vo.setRetrievalDebug(searchResp.getDebugInfo());
        vo.setConfidenceDebug(buildConfidenceDebug(
                top1Score, top2Score, top3AvgScore, lexicalCoverage,
                multiSourceCount, vectorHitCount, keywordHitCount, hits, confidenceLevel));
        vo.setPreferenceAutofillDebug(preferenceAutofillDebug);
        vo.setPromptDebug(prompt);
        vo.setMemoryAssemblyDebug(memoryAssemblyDebug);
        vo.setGenerationMode(generationMode);
        vo.setFallbackUsed(fallbackUsed);
        vo.setFallbackReason(fallbackReason);
        vo.setTokenInputBudget(tokenBudget.getInputBudget());
        vo.setTokenOutputBudget(tokenBudget.getOutputBudget());
        vo.setOriginalPromptTokens(tokenBudget.getOriginalPromptTokens());
        vo.setTrimmedTokens(tokenBudget.getTrimmedTokens());
        if (generationResult != null) {
            vo.setPromptTokens(generationResult.getPromptTokens());
            vo.setCompletionTokens(generationResult.getCompletionTokens());
            vo.setTotalTokens(generationResult.getTotalTokens());
            vo.setTokenUsageEstimated(generationResult.isUsageEstimated());
        }
        return finishTurn(vo, normalizedQuestion, memoryUserId, sessionId, normalizedTopK,
                "", true, evaluationMode, contextAssemblyElapsedMs, retrievalElapsedMs,
                generationElapsedMs, requestStartedNanos);
    }

    private void addOptionalPromptSection(List<TokenPromptSection> sections, String name,
                                          String content, int priority) {
        if (!StringUtils.hasText(content) || "（无）".equals(content.trim())) {
            return;
        }
        sections.add(TokenPromptSection.of(name, content, priority, false));
    }

    private WsChatDoneVO finishTurn(WsChatDoneVO response,
                                    String question,
                                    String memoryUserId,
                                    String sessionId,
                                    Integer topK,
                                    String reasonCode,
                                    boolean persistMemory,
                                    boolean evaluationMode,
                                    long contextAssemblyElapsedMs,
                                    long retrievalElapsedMs,
                                    long generationElapsedMs,
                                    long requestStartedNanos) {
        response.setEvaluationMode(evaluationMode);
        if (!evaluationMode && persistMemory) {
            MemoryScope scope = new MemoryScope(memoryUserId, sessionId);
            conversationMemoryService.appendTurn(scope, question, response.getAnswer());
            sessionSummaryService.refreshSummaryIfNeeded(scope, question, response.getAnswer());
            l2MemoryService.saveMemory(memoryUserId, sessionId, "FACT",
                    question + " -> " + response.getAnswer(), 0.3D);
        }
        WsChatDoneVO completed = evaluationMode
                ? response
                : publishOutcome(response, question, sessionId, topK, reasonCode);
        applyStageTimings(completed, contextAssemblyElapsedMs, retrievalElapsedMs,
                generationElapsedMs, requestStartedNanos);
        return completed;
    }

    private void applyStageTimings(WsChatDoneVO response,
                                   long contextAssemblyElapsedMs,
                                   long retrievalElapsedMs,
                                   long generationElapsedMs,
                                   long requestStartedNanos) {
        response.setContextAssemblyElapsedMs(Math.max(0L, contextAssemblyElapsedMs));
        response.setRetrievalElapsedMs(Math.max(0L, retrievalElapsedMs));
        response.setGenerationElapsedMs(Math.max(0L, generationElapsedMs));
        response.setTotalElapsedMs(elapsedMs(requestStartedNanos));
    }

    private long elapsedMs(long startedNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private WsChatDoneVO publishOutcome(WsChatDoneVO response, String query, String sessionId,
                                        Integer topK, String reasonCode) {
        try {
            eventPublisher.publishEvent(new RagChatOutcomeEvent(
                    query,
                    sessionId,
                    response.getTraceId(),
                    topK,
                    response.getRetrievalMode(),
                    response.getRetrievalDebug(),
                    response.getLowConfidence(),
                    response.getAnswer(),
                    response.getSources(),
                    reasonCode
            ));
        } catch (Exception ex) {
            log.warn("RAG outcome event publish failed, traceId={}, reason={}", response.getTraceId(), ex.getMessage());
        }
        return response;
    }

    private String classifyGapReason(String question, String fallback) {
        if (!StringUtils.hasText(question)) {
            return fallback;
        }
        if (isMissingFieldsQuestion(question)) {
            return "MISSING_FIELDS";
        }
        return fallback;
    }

    private boolean isMissingFieldsQuestion(String question) {
        if (!StringUtils.hasText(question)) {
            return false;
        }
        String compact = question.replaceAll("[\\p{P}\\p{S}\\s]+", "");
        boolean vagueObject = compact.contains("这个") || compact.contains("这东西") || compact.contains("该商品");
        boolean vagueIntent = compact.contains("能卖") || compact.contains("能买吗")
                || compact.contains("多少钱") || compact.contains("有风险吗");
        return compact.length() <= 24 && vagueObject && vagueIntent;
    }

    private String buildMemoryAssemblyDebug(
            String query,
            String sessionId,
            String sessionSummary,
            String profilePromptBlock,
            Map<String, String> historicalPreferences,
            Map<String, String> l3AutofillParams,
            List<String> l2MemoryContext,
            List<String> recentContext,
            String preferenceAutofillDebug
    ) {
        try {
            Map<String, Object> debug = new HashMap<>();
            debug.put("query", query);
            debug.put("sessionId", sessionId);
            debug.put("l3Summary", StringUtils.hasText(sessionSummary) ? sessionSummary : "");
            debug.put("profilePromptBlock", StringUtils.hasText(profilePromptBlock) ? profilePromptBlock : "");
            debug.put("historicalPreferences", historicalPreferences == null ? Collections.emptyMap() : historicalPreferences);
            debug.put("l3AutofillParams", l3AutofillParams == null ? Collections.emptyMap() : l3AutofillParams);
            debug.put("l2InjectedMemories", l2MemoryContext == null ? Collections.emptyList() : l2MemoryContext);
            debug.put("recentConversation", recentContext == null ? Collections.emptyList() : recentContext);
            debug.put("preferenceAutofillDebug", preferenceAutofillDebug);
            debug.put("counts", Map.of(
                    "l2Injected", l2MemoryContext == null ? 0 : l2MemoryContext.size(),
                    "recentConversation", recentContext == null ? 0 : recentContext.size(),
                    "historicalPreferenceFields", historicalPreferences == null ? 0 : historicalPreferences.size(),
                    "l3AutofillFields", l3AutofillParams == null ? 0 : l3AutofillParams.size()
            ));
            return objectMapper.writeValueAsString(debug);
        } catch (Exception ex) {
            log.warn("buildMemoryAssemblyDebug failed: {}", ex.getMessage());
            return "{}";
        }
    }

    private String enforceEvidenceFaithfulness(String answer, String question, String sessionSummary, List<RagSourceVO> sources) {
        String sanitized = sanitizeUnsupportedQuantifiedAdvice(answer, question, sources);
        sanitized = sanitizeUnsupportedSensitiveClaims(sanitized, question, sources);
        sanitized = appendMissingQuestionFacts(sanitized, question);
        sanitized = appendMissingSessionFacts(sanitized, sessionSummary);
        sanitized = appendEvidenceRequiredMediaAdvice(sanitized, question, sources);
        return sanitized;
    }

    private String sanitizeUnsupportedSensitiveClaims(String answer, String question, List<RagSourceVO> sources) {
        if (!StringUtils.hasText(answer)) {
            return answer;
        }
        String evidenceCorpus = buildSourceEvidenceCorpus(sources);
        String[] sentences = answer.split("(?<=[。！？!?])|\\n+");
        List<String> kept = new ArrayList<>();
        boolean removed = false;
        for (String sentence : sentences) {
            if (!StringUtils.hasText(sentence)) {
                continue;
            }
            String trimmed = sentence.trim();
            String normalized = normalizeForEvidenceCheck(trimmed);
            boolean hasUnsupportedSensitiveTerm = SENSITIVE_UNGROUNDED_TERMS.stream()
                    .map(this::normalizeForEvidenceCheck)
                    .anyMatch(term -> normalized.contains(term) && !evidenceCorpus.contains(term));
            if (hasUnsupportedSensitiveTerm && !isExplicitUncertainty(normalized)) {
                removed = true;
                continue;
            }
            kept.add(trimmed);
        }
        boolean questionRequestsUnsupportedSensitiveConclusion = SENSITIVE_UNGROUNDED_TERMS.stream()
                .map(this::normalizeForEvidenceCheck)
                .anyMatch(term -> normalizeForEvidenceCheck(question).contains(term) && !evidenceCorpus.contains(term));
        if (!removed && !questionRequestsUnsupportedSensitiveConclusion) {
            return answer;
        }
        String grounded = summarizeEvidenceWithCitations(question, sources, 2);
        if (!StringUtils.hasText(grounded)) {
            return "现有证据不足以支持更具体的风险后果，无法确定。";
        }
        String questionFocus = StringUtils.hasText(question)
                ? question.replaceAll("\\s+", " ").trim()
                : "该问题";
        if (questionFocus.length() > 90) {
            questionFocus = questionFocus.substring(0, 90) + "…";
        }
        return grounded + "对于你问的“" + questionFocus
                + "”，现有证据只覆盖上述规则，未直接说明该具体结论，因此无法确定。";
    }

    private boolean isExplicitUncertainty(String sentence) {
        return UNCERTAINTY_MARKERS.stream().anyMatch(sentence::contains);
    }

    private String buildSourceEvidenceCorpus(List<RagSourceVO> sources) {
        StringBuilder sb = new StringBuilder();
        if (sources != null) {
            for (RagSourceVO source : sources) {
                if (source == null) {
                    continue;
                }
                if (StringUtils.hasText(source.getDocTitle())) {
                    sb.append(source.getDocTitle()).append('\n');
                }
                if (StringUtils.hasText(source.getSnippet())) {
                    sb.append(source.getSnippet()).append('\n');
                }
            }
        }
        return normalizeForEvidenceCheck(sb.toString());
    }

    private String summarizeEvidenceWithCitations(String question, List<RagSourceVO> sources, int limit) {
        if (sources == null || sources.isEmpty()) {
            return "";
        }
        List<String> questionTerms = new ArrayList<>(tokenize(question));
        List<Integer> sourceIndexes = new ArrayList<>();
        for (int index = 0; index < sources.size(); index++) {
            sourceIndexes.add(index);
        }
        sourceIndexes.sort(Comparator
                .comparingInt((Integer index) -> sourceQuestionOverlap(sources.get(index), questionTerms))
                .reversed()
                .thenComparingInt(Integer::intValue));
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Integer index : sourceIndexes) {
            if (out.size() >= limit) {
                break;
            }
            RagSourceVO source = sources.get(index);
            if (source == null || !StringUtils.hasText(source.getSnippet())) {
                continue;
            }
            String sentence = compactSentence(pickBestSentence(source.getSnippet(), questionTerms));
            if (!StringUtils.hasText(sentence) || !seen.add(sentence)) {
                continue;
            }
            out.add("依据[证据" + (index + 1) + "]，" + sentence + "。");
        }
        return String.join("", out);
    }

    private int sourceQuestionOverlap(RagSourceVO source, List<String> questionTerms) {
        if (source == null || !StringUtils.hasText(source.getSnippet())) {
            return 0;
        }
        String corpus = (StringUtils.hasText(source.getDocTitle()) ? source.getDocTitle() + " " : "")
                + source.getSnippet();
        String normalized = normalizeForEvidenceCheck(corpus);
        int overlap = 0;
        for (String term : questionTerms) {
            if (StringUtils.hasText(term) && normalized.contains(normalizeForEvidenceCheck(term))) {
                overlap++;
            }
        }
        return overlap;
    }

    private String sanitizeUnsupportedQuantifiedAdvice(String answer, String question, List<RagSourceVO> sources) {
        if (!StringUtils.hasText(answer)) {
            return answer;
        }
        String evidenceCorpus = buildEvidenceCorpus(question, sources);
        String[] sentences = answer.split("(?<=[。！？!?])");
        List<String> kept = new ArrayList<>();
        for (String sentence : sentences) {
            if (!StringUtils.hasText(sentence)) {
                continue;
            }
            String trimmed = sentence.trim();
            kept.add(replaceUnsupportedQuantifiedAdvice(trimmed, evidenceCorpus));
        }
        if (kept.isEmpty()) {
            return "证据未提供可量化的金额、比例或折扣，因此只能给出价格方向，不能编造具体数值。";
        }
        return String.join("", kept);
    }

    private String replaceUnsupportedQuantifiedAdvice(String sentence, String evidenceCorpus) {
        String normalizedSentence = normalizeForEvidenceCheck(sentence);
        if (!hasPricingAdviceIntent(normalizedSentence)) {
            return sentence;
        }
        Matcher matcher = UNSUPPORTED_QUANTIFIED_ADVICE_PATTERN.matcher(sentence);
        StringBuffer sb = new StringBuffer();
        boolean replaced = false;
        while (matcher.find()) {
            String rawToken = matcher.group();
            String token = normalizeForEvidenceCheck(rawToken);
            if (!StringUtils.hasText(token)) {
                continue;
            }
            if (!evidenceCorpus.contains(token)) {
                matcher.appendReplacement(sb, "具体数值需以证据或近期成交价确认");
                replaced = true;
            }
        }
        if (!replaced) {
            return sentence;
        }
        matcher.appendTail(sb);
        String rewritten = sb.toString();
        if (!normalizeForEvidenceCheck(rewritten).contains("证据未提供可量化")) {
            rewritten = rewritten + "证据未提供可量化的金额、比例或折扣，因此不能编造具体数值。";
        }
        return rewritten;
    }

    private boolean hasPricingAdviceIntent(String sentence) {
        String[] terms = {"报价", "价格", "估价", "定价", "下调", "扣减", "扣除", "压价", "让利", "折让", "市场中位价", "议价空间", "挂高", "降价"};
        for (String term : terms) {
            if (sentence.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private String buildEvidenceCorpus(String question, List<RagSourceVO> sources) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(question)) {
            sb.append(question).append('\n');
        }
        if (sources != null) {
            for (RagSourceVO source : sources) {
                if (source == null) {
                    continue;
                }
                if (StringUtils.hasText(source.getDocTitle())) {
                    sb.append(source.getDocTitle()).append('\n');
                }
                if (StringUtils.hasText(source.getSnippet())) {
                    sb.append(source.getSnippet()).append('\n');
                }
            }
        }
        return normalizeForEvidenceCheck(sb.toString());
    }

    private String normalizeForEvidenceCheck(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT)
                .replace("％", "%")
                .replace("－", "-")
                .replace("—", "-")
                .replace("～", "~")
                .replaceAll("\\s+", "");
    }

    private String appendMissingQuestionFacts(String answer, String question) {
        if (!StringUtils.hasText(answer) || !StringUtils.hasText(question)) {
            return answer;
        }
        String normalizedQuestion = normalizeForEvidenceCheck(question);
        boolean publicationContentQuestion = normalizedQuestion.contains("标题")
                || normalizedQuestion.contains("文案")
                || normalizedQuestion.contains("详情")
                || normalizedQuestion.contains("发布")
                || normalizedQuestion.contains("上架")
                || normalizedQuestion.contains("描述")
                || normalizedQuestion.contains("怎么写");
        if (!publicationContentQuestion) {
            return answer;
        }
        List<String> facts = extractQuestionFacts(question);
        if (facts.isEmpty()) {
            return answer;
        }
        String normalizedAnswer = normalizeForEvidenceCheck(answer);
        List<String> missing = new ArrayList<>();
        for (String fact : facts) {
            if (!normalizedAnswer.contains(normalizeForEvidenceCheck(fact))) {
                missing.add(fact);
            }
        }
        if (missing.isEmpty()) {
            return answer;
        }
        return answer + " 已知用户明确提供的信息还包括：" + String.join("、", missing) + "，这些也应写入标题或详情。";
    }

    private String appendMissingSessionFacts(String answer, String sessionSummary) {
        if (!StringUtils.hasText(answer) || !StringUtils.hasText(sessionSummary) || "（无）".equals(sessionSummary.trim())) {
            return answer;
        }
        if (sessionSummary.contains("旧L3摘要不注入")) {
            return answer;
        }
        List<String> facts = extractSessionFacts(sessionSummary);
        if (facts.isEmpty()) {
            return answer;
        }
        String normalizedAnswer = normalizeForEvidenceCheck(answer);
        List<String> missing = new ArrayList<>();
        for (String fact : facts) {
            if (!normalizedAnswer.contains(normalizeForEvidenceCheck(fact))) {
                missing.add(fact);
            }
        }
        if (missing.isEmpty()) {
            return answer;
        }
        return answer + " 结合当前会话任务，仍需保留这些已确认事实：" + String.join("、", missing) + "。";
    }

    private List<String> extractSessionFacts(String sessionSummary) {
        List<String> facts = new ArrayList<>();
        for (String line : sessionSummary.split("\\n")) {
            String trimmed = line.trim();
            if (!StringUtils.hasText(trimmed) || trimmed.startsWith("约束")) {
                continue;
            }
            if (trimmed.startsWith("productName=") || trimmed.startsWith("category=") || trimmed.startsWith("model=")) {
                String value = trimmed.substring(trimmed.indexOf('=') + 1).trim();
                if (StringUtils.hasText(value)) {
                    facts.add(value);
                }
            }
            if (trimmed.startsWith("knownFacts=")) {
                String value = trimmed.substring("knownFacts=".length());
                for (String part : value.split("、|,|，")) {
                    if (StringUtils.hasText(part)) {
                        facts.add(part.trim());
                    }
                }
            }
        }
        return facts.stream().filter(StringUtils::hasText).distinct().limit(6).toList();
    }

    private boolean shouldIgnoreL3ForCurrentTurn(String question) {
        if (!StringUtils.hasText(question)) {
            return false;
        }
        String lower = question.toLowerCase(Locale.ROOT);
        boolean hasSwitchMarker = lower.contains("不是")
                || lower.contains("改成")
                || lower.contains("换成")
                || lower.contains("当前商品")
                || lower.contains("现在商品");
        boolean hasConcreteProduct = lower.contains("rtx")
                || lower.contains("显卡")
                || lower.contains("相机")
                || lower.contains("无人机")
                || lower.contains("手机")
                || lower.contains("iphone")
                || lower.contains("macbook");
        return hasSwitchMarker && hasConcreteProduct;
    }

    private List<String> extractQuestionFacts(String question) {
        List<String> facts = new ArrayList<>();
        String lower = question.toLowerCase(Locale.ROOT);
        addFactIfPresent(facts, lower, "rtx3060", "RTX3060");
        addFactIfPresent(facts, lower, "rtx 3060", "RTX3060");
        addFactIfPresent(facts, lower, "显卡", "显卡");
        addFactIfPresent(facts, lower, "相机", "相机");
        addFactIfPresent(facts, lower, "无人机", "无人机");
        addFactIfPresent(facts, lower, "跑分", "跑分");
        addFactIfPresent(facts, lower, "温度正常", "温度正常");
        addFactIfPresent(facts, lower, "温度偏高", "温度偏高");
        addFactIfPresent(facts, lower, "无原盒", "无原盒");
        addFactIfPresent(facts, lower, "没有原盒", "无原盒");
        addFactIfPresent(facts, lower, "盒子", "盒子");
        addFactIfPresent(facts, lower, "仅主机", "仅主机");
        addFactIfPresent(facts, lower, "只剩主机", "仅主机");
        addFactIfPresent(facts, lower, "充电线", "充电线");
        addFactIfPresent(facts, lower, "充电器", "充电器");
        addFactIfPresent(facts, lower, "遥控器", "遥控器");
        addFactIfPresent(facts, lower, "桨叶", "桨叶");
        addFactIfPresent(facts, lower, "收纳包", "收纳包");
        addFactIfPresent(facts, lower, "少原装电池", "缺原装电池");
        addFactIfPresent(facts, lower, "少一块电池", "缺电池");
        addFactIfPresent(facts, lower, "缺电池", "缺电池");
        addFactIfPresent(facts, lower, "电池健康", "电池健康");
        addFactIfPresent(facts, lower, "快门", "快门次数");
        addFactIfPresent(facts, lower, "无维修", "无维修");
        addFactIfPresent(facts, lower, "没有发票", "无发票");
        addFactIfPresent(facts, lower, "无发票", "无发票");
        return facts.stream().distinct().toList();
    }

    private void addFactIfPresent(List<String> facts, String lowerQuestion, String needle, String fact) {
        if (lowerQuestion.contains(needle.toLowerCase(Locale.ROOT))) {
            facts.add(fact);
        }
    }

    private String appendEvidenceRequiredMediaAdvice(String answer, String question, List<RagSourceVO> sources) {
        if (!StringUtils.hasText(answer)) {
            return answer;
        }
        String corpus = buildEvidenceCorpus(question, sources);
        String normalizedAnswer = normalizeForEvidenceCheck(answer);
        if (corpus.contains("配件见图") && !normalizedAnswer.contains("见图")) {
            return answer + " 配件建议按“配件见图”逐项展示。";
        }
        if ((corpus.contains("提供照片") || corpus.contains("实拍样张") || corpus.contains("检测截图"))
                && !(normalizedAnswer.contains("照片") || normalizedAnswer.contains("实拍") || normalizedAnswer.contains("截图") || normalizedAnswer.contains("见图"))) {
            return answer + " 同时应补充照片、实拍样张或检测截图作为证据。";
        }
        return answer;
    }

    private String buildEvidenceAwareInsufficientAnswer(String question, List<RagSourceVO> sources) {
        String evidenceSummary = summarizeEvidence(question, sources, 2);
        if (StringUtils.hasText(evidenceSummary)) {
            return "检索到的证据相关性较低，无法给出确定结论。可参考已召回证据中的要点：" + evidenceSummary
                    + " 请补充商品品类、品牌型号、成色、维修史、配件、凭证或近期成交参考后再判断。";
        }
        return "当前证据不足，无法确定。请补充商品品类、品牌型号、成色、维修史、配件、凭证或近期成交参考后再判断。";
    }

    private String buildEvidenceFallbackAnswer(String question, List<RagSourceVO> sources) {
        String evidenceSummary = summarizeEvidence(question, sources, 3);
        if (!StringUtils.hasText(evidenceSummary)) {
            return "AI服务暂时不可用，且当前证据不足，无法确定。请补充商品品类、品牌型号、成色、维修史、配件、凭证或近期成交参考。";
        }
        String missingInfo = inferMissingInfo(question);
        return "AI服务暂时不可用，先基于已召回证据给出保守结论：" + evidenceSummary
                + " 因此当前不能编造具体结论或价格；" + missingInfo;
    }

    private String improveWeakInsufficientAnswer(String answer, String question, List<RagSourceVO> sources) {
        if (!StringUtils.hasText(answer)) {
            return buildEvidenceFallbackAnswer(question, sources);
        }
        String trimmed = answer.trim();
        String evidenceSummary = summarizeEvidence(question, sources, 2);
        if (!StringUtils.hasText(evidenceSummary)) {
            return trimmed;
        }
        String normalized = trimmed.replaceAll("\\s+", "");
        boolean weakInsufficient = normalized.length() <= 35
                && (normalized.contains("无法确定") || normalized.contains("证据不足") || normalized.contains("不能确定"));
        if (!weakInsufficient) {
            return trimmed;
        }
        return trimmed + " 已召回证据提示：" + evidenceSummary + " " + inferMissingInfo(question);
    }

    private String summarizeEvidence(String question, List<RagSourceVO> sources, int limit) {
        if (sources == null || sources.isEmpty()) {
            return "";
        }
        List<String> questionTerms = new ArrayList<>(tokenize(question));
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (RagSourceVO source : sources) {
            if (source == null || !StringUtils.hasText(source.getSnippet())) {
                continue;
            }
            String sentence = pickBestSentence(source.getSnippet(), questionTerms);
            sentence = compactSentence(sentence);
            if (!StringUtils.hasText(sentence) || !seen.add(sentence)) {
                continue;
            }
            out.add(sentence);
            if (out.size() >= limit) {
                break;
            }
        }
        return String.join("；", out);
    }

    private String pickBestSentence(String snippet, List<String> questionTerms) {
        String normalizedSnippet = snippet.replaceAll("\\s+", " ").trim();
        String[] parts = normalizedSnippet.split("[。！？!?；;]");
        String best = "";
        int bestScore = -1;
        for (String part : parts) {
            if (!StringUtils.hasText(part)) {
                continue;
            }
            String candidate = part.trim();
            int score = 0;
            String lower = candidate.toLowerCase(Locale.ROOT);
            for (String term : questionTerms) {
                if (StringUtils.hasText(term) && lower.contains(term.toLowerCase(Locale.ROOT))) {
                    score++;
                }
            }
            score += evidenceKeywordScore(lower);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return StringUtils.hasText(best) ? best : snippet;
    }

    private int evidenceKeywordScore(String text) {
        int score = 0;
        String[] keywords = {"电池", "健康", "下调", "维修", "配件", "保修", "凭证", "发票", "风险", "禁售", "补充", "型号", "成色", "价格"};
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                score++;
            }
        }
        return score;
    }

    private String compactSentence(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String compact = text.replaceAll("\\s+", " ").trim();
        int maxLength = 90;
        if (compact.length() <= maxLength) {
            return compact;
        }
        return compact.substring(0, maxLength) + "...";
    }

    private String inferMissingInfo(String question) {
        String lower = question == null ? "" : question.toLowerCase(Locale.ROOT);
        if (lower.contains("价") || lower.contains("钱") || lower.contains("估")) {
            return "请补充品牌型号、配置/容量、电池健康、成色、维修史、配件、保修和近期成交参考。";
        }
        if (lower.contains("风险") || lower.contains("能不能") || lower.contains("可以") || lower.contains("发布") || lower.contains("上架")) {
            return "请补充商品具体信息、标题/详情原文、凭证和交易承诺内容。";
        }
        return "请补充商品品类、品牌型号、成色、维修史、配件和凭证。";
    }

    private String calcConfidenceLevel(double top1Score, double top2Score, double top3AvgScore,
                                       double lexicalCoverage, int multiSourceCount,
                                       int vectorHitCount, int keywordHitCount,
                                       List<KnowledgeSearchHitVO> hits) {
        if (hits == null || hits.isEmpty()) {
            return "LOW";
        }

        // RRF分很小，先归一化到相对区间，再做综合评分
        double normTop1 = normalizeByRrfTop1(top1Score);
        double normTop3 = normalizeByRrfTop1(top3AvgScore);
        double margin = Math.max(0D, top1Score - top2Score);
        double sourceDiversity = Math.min(multiSourceCount / 3.0D, 1.0D);
        double keywordSignal = Math.min(keywordHitCount / 4.0D, 1.0D);
        double marginBonus = Math.min(margin / 0.0020D, 1.0D);

        // RRF只表达排序，不表达相关性的绝对强弱。向量分支不可用时，少量通用词造成的
        // BM25命中不能单独支撑可靠回答，否则“火星量子烤箱保修规则”也可能命中通用保修文档。
        if (vectorHitCount <= 0 && keywordHitCount > 0 && lexicalCoverage < 0.20D) {
            return "LOW";
        }

        double confidenceScore =
                0.35D * normTop1
                        + 0.15D * normTop3
                        + 0.20D * lexicalCoverage
                        + 0.15D * sourceDiversity
                        + 0.10D * keywordSignal
                        + 0.05D * marginBonus;

        if (confidenceScore >= 0.72D) {
            return "HIGH";
        }
        if (confidenceScore >= 0.55D) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private WsChatDoneVO buildEmptyResponse(String traceId, KnowledgeSearchResponseVO searchResp) {
        WsChatDoneVO vo = new WsChatDoneVO();
        vo.setTraceId(traceId);
        vo.setAnswer("当前没有召回到可用证据，无法确定。请补充商品品类、品牌型号、配置/容量、成色、维修史、配件、凭证和你想判断的问题。");
        vo.setSources(Collections.emptyList());
        vo.setLowConfidence(true);
        vo.setConfidenceLevel("LOW");
        if (searchResp != null) {
            vo.setRetrievalMode(searchResp.getRetrievalMode());
            vo.setRetrievalDebug(searchResp.getDebugInfo());
        }
        vo.setConfidenceDebug("hits=0");
        vo.setGenerationMode("NO_EVIDENCE");
        vo.setFallbackUsed(false);
        vo.setFallbackReason("");
        return vo;
    }

    private String buildConfidenceDebug(double top1Score, double top2Score, double top3AvgScore,
                                        double lexicalCoverage, int multiSourceCount,
                                        int vectorHitCount, int keywordHitCount,
                                        List<KnowledgeSearchHitVO> hits, String confidenceLevel) {
        int hitSize = hits == null ? 0 : hits.size();
        double normTop1 = normalizeByRrfTop1(top1Score);
        double normTop3 = normalizeByRrfTop1(top3AvgScore);
        double margin = Math.max(0D, top1Score - top2Score);
        double sourceDiversity = Math.min(multiSourceCount / 3.0D, 1.0D);
        double keywordSignal = Math.min(keywordHitCount / 4.0D, 1.0D);
        double marginBonus = Math.min(margin / 0.0020D, 1.0D);
        double confidenceScore =
                0.35D * normTop1
                        + 0.15D * normTop3
                        + 0.20D * lexicalCoverage
                        + 0.15D * sourceDiversity
                        + 0.10D * keywordSignal
                        + 0.05D * marginBonus;

        return "level=" + confidenceLevel
                + ",score=" + formatDouble(confidenceScore)
                + ",top1=" + formatDouble(top1Score)
                + ",top2=" + formatDouble(top2Score)
                + ",top3Avg=" + formatDouble(top3AvgScore)
                + ",normTop1=" + formatDouble(normTop1)
                + ",normTop3=" + formatDouble(normTop3)
                + ",margin=" + formatDouble(margin)
                + ",lexicalCoverage=" + formatDouble(lexicalCoverage)
                + ",sourceDiversity=" + formatDouble(sourceDiversity)
                + ",vectorHitCount=" + vectorHitCount
                + ",keywordSignal=" + formatDouble(keywordSignal)
                + ",multiSourceCount=" + multiSourceCount
                + ",keywordHitCount=" + keywordHitCount
                + ",keywordOnlyWeakEvidence="
                + (vectorHitCount <= 0 && keywordHitCount > 0 && lexicalCoverage < 0.20D)
                + ",hitSize=" + hitSize;
    }

    private String formatDouble(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private double normalizeByRrfTop1(double score) {
        // RRF常见top1约在 1/(k+1) ~ 1/(k+3)，这里按k=60归一化到近似[0,1]
        double base = 1.0D / 61.0D;
        if (base <= 0D) {
            return 0D;
        }
        double normalized = score / base;
        if (normalized < 0D) {
            return 0D;
        }
        return Math.min(normalized, 1.0D);
    }

    private double calculateLexicalCoverage(String question, List<KnowledgeSearchHitVO> hits) {
        if (!StringUtils.hasText(question) || hits == null || hits.isEmpty()) {
            return 0D;
        }
        Set<String> terms = tokenize(question);
        if (terms.isEmpty()) {
            return 0D;
        }
        int matched = 0;
        for (String term : terms) {
            for (KnowledgeSearchHitVO hit : hits) {
                String text = ((hit.getDocTitle() == null ? "" : hit.getDocTitle()) + " " + (hit.getSnippet() == null ? "" : hit.getSnippet())).toLowerCase(Locale.ROOT);
                if (text.contains(term)) {
                    matched++;
                    break;
                }
            }
        }
        return matched * 1.0D / terms.size();
    }

    private int countDistinctSources(List<KnowledgeSearchHitVO> hits) {
        if (hits == null || hits.isEmpty()) {
            return 0;
        }
        Set<String> keys = new HashSet<>();
        for (KnowledgeSearchHitVO hit : hits) {
            if (hit == null) {
                continue;
            }
            keys.add(buildSourceKey(hit));
        }
        return keys.size();
    }

    private Set<String> tokenize(String question) {
        Set<String> tokens = new HashSet<>();
        for (String part : question.toLowerCase(Locale.ROOT).split("\\s+|，|。|,|;|；|:|：|/|\\\\|\\(|\\)|\\[|\\]|\"|\'")) {
            if (!StringUtils.hasText(part)) {
                continue;
            }
            String normalized = part.trim();
            StringBuilder cjk = new StringBuilder();
            StringBuilder alphaNumeric = new StringBuilder();
            for (int i = 0; i < normalized.length(); i++) {
                char ch = normalized.charAt(i);
                if (isCjk(ch)) {
                    flushAlphaNumeric(tokens, alphaNumeric);
                    cjk.append(ch);
                } else {
                    flushCjkBigrams(tokens, cjk);
                    if (Character.isLetterOrDigit(ch) || ch == '-' || ch == '_' || ch == '.') {
                        alphaNumeric.append(ch);
                    } else {
                        flushAlphaNumeric(tokens, alphaNumeric);
                    }
                }
            }
            flushCjkBigrams(tokens, cjk);
            flushAlphaNumeric(tokens, alphaNumeric);
        }
        return tokens;
    }

    private void flushCjkBigrams(Set<String> tokens, StringBuilder buffer) {
        if (buffer.length() == 1) {
            tokens.add(buffer.toString());
        } else {
            for (int i = 0; i < buffer.length() - 1; i++) {
                tokens.add(buffer.substring(i, i + 2));
            }
        }
        buffer.setLength(0);
    }

    private void flushAlphaNumeric(Set<String> tokens, StringBuilder buffer) {
        if (buffer.length() >= 2) {
            tokens.add(buffer.toString());
        }
        buffer.setLength(0);
    }

    private boolean isCjk(char ch) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS_SUPPLEMENT;
    }

    private String buildSourceKey(KnowledgeSearchHitVO hit) {
        if (hit.getDocId() != null && hit.getChunkId() != null) {
            return hit.getDocId() + "#" + hit.getChunkId();
        }
        if (StringUtils.hasText(hit.getSnippet())) {
            return hit.getSnippet().trim();
        }
        return "";
    }

    private Map<String, String> extractPreferencesByRule(String text) {
        Map<String, String> out = new HashMap<>();
        if (!StringUtils.hasText(text)) {
            return out;
        }

        String lower = text.toLowerCase(Locale.ROOT);
        boolean preferenceIntent = hasPreferenceIntent(lower);
        if (!preferenceIntent) {
            return out;
        }

        String brand = detectBrand(lower);
        if (StringUtils.hasText(brand)) {
            out.put("brand", brand);
        }

        String priceRange = detectPriceRange(lower);
        if (StringUtils.hasText(priceRange)) {
            out.put("priceRange", priceRange);
        }

        String condition = detectCondition(lower);
        if (StringUtils.hasText(condition)) {
            out.put("condition", condition);
        }
        String category = detectCategory(lower);
        if (StringUtils.hasText(category)) {
            out.put("category", category);
        }
        String transaction = detectTransaction(lower);
        if (StringUtils.hasText(transaction)) {
            out.put("transaction", transaction);
        }
        String riskTolerance = detectRiskTolerance(lower);
        if (StringUtils.hasText(riskTolerance)) {
            out.put("riskTolerance", riskTolerance);
        }
        String writingStyle = detectWritingStyle(lower);
        if (StringUtils.hasText(writingStyle)) {
            out.put("writingStyle", writingStyle);
        }
        return out;
    }

    private Map<String, String> extractPreferencesByLlm(String question, String traceId) {
        if (!StringUtils.hasText(question)) {
            return Collections.emptyMap();
        }
        if (!hasPreferenceIntent(question.toLowerCase(Locale.ROOT))) {
            return Collections.emptyMap();
        }
        try {
            String prompt = """
                    你是偏好信息抽取器。只抽取用户明确表达的长期偏好、默认选择、预算习惯或文案风格；不要把本轮商品事实抽成偏好。请返回严格JSON对象：
                    brand: 品牌偏好（如 Apple/Huawei/Xiaomi/Samsung）
                    category: 常卖/关注品类（如 手机/相机/显卡/奢侈品）
                    priceRange: 预算偏好（如 2000-3000/偏低价/偏高价）
                    condition: 成色偏好（如 95新/99新/关注成色）
                    transaction: 交易方式偏好（如 平台交易/当面验货/可快递）
                    riskTolerance: 风险偏好（如 保守/中性/愿意承担较高风险）
                    writingStyle: 文案风格偏好（如 简洁/详细/强调验货）
                    若某字段无法确定，返回空字符串。
                    只返回JSON，不要输出解释。
                    用户问题：%s
                    """.formatted(question);
            String raw = chatGenerationService.generate(TokenGenerationRequest.builder()
                    .scene(TokenScene.PREFERENCE_EXTRACTION)
                    .traceId(traceId)
                    .prompt(prompt)
                    .build()).getContent();
            if (!StringUtils.hasText(raw)) {
                return Collections.emptyMap();
            }
            String json = sanitizeJson(raw);
            Map<String, String> parsed = objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
            Map<String, String> normalized = new HashMap<>();
            putIfText(normalized, "brand", parsed.get("brand"));
            putIfText(normalized, "category", parsed.get("category"));
            putIfText(normalized, "priceRange", parsed.get("priceRange"));
            putIfText(normalized, "condition", parsed.get("condition"));
            putIfText(normalized, "transaction", parsed.get("transaction"));
            putIfText(normalized, "riskTolerance", parsed.get("riskTolerance"));
            putIfText(normalized, "writingStyle", parsed.get("writingStyle"));
            return normalized;
        } catch (Exception ex) {
            log.warn("extractPreferencesByLlm failed: {}", ex.getMessage());
            return Collections.emptyMap();
        }
    }

    private Map<String, String> mergePreferences(Map<String, String> rulePrefs, Map<String, String> llmPrefs) {
        Map<String, String> merged = new HashMap<>();
        if (llmPrefs != null) {
            merged.putAll(llmPrefs);
        }
        if (rulePrefs != null) {
            // 规则优先，覆盖LLM
            for (Map.Entry<String, String> e : rulePrefs.entrySet()) {
                putIfText(merged, e.getKey(), e.getValue());
            }
        }
        return merged;
    }

    private void putIfText(Map<String, String> target, String key, String value) {
        if (target == null || !StringUtils.hasText(key) || !StringUtils.hasText(value)) {
            return;
        }
        target.put(key, value.trim());
    }

    private String sanitizeJson(String raw) {
        String text = raw.trim();
        if (text.startsWith("```") && text.endsWith("```")) {
            text = text.replaceAll("^```(?:json)?", "").replaceAll("```$", "").trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private void persistPreferencesToL2(String userId, String sessionId, Map<String, String> prefs, String traceId) {
        if (prefs == null || prefs.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : prefs.entrySet()) {
            String metadataJson = "{}";
            try {
                Map<String, String> metadata = new HashMap<>();
                metadata.put("schema", "user_preference_v1");
                metadata.put("key", entry.getKey());
                metadata.put("value", entry.getValue());
                metadata.put("source", "rule_llm_merge");
                metadataJson = objectMapper.writeValueAsString(metadata);
            } catch (Exception ex) {
                log.warn("persistPreferencesToL2 metadata serialize failed: {}", ex.getMessage());
            }
            try {
                String content = "PREFERENCE|" + entry.getKey() + "=" + entry.getValue();
                String eventSeed = StringUtils.hasText(traceId) ? traceId.trim() : UUID.randomUUID().toString();
                String sourceEventId = eventSeed.length() <= 128
                        ? eventSeed
                        : "rag-preference:" + UUID.nameUUIDFromBytes(eventSeed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                l2MemoryService.upsertSlotMemory(new L2MemoryWriteCommand(
                        userId, sessionId, "PREFERENCE", content, 0.3D, metadataJson,
                        sourceEventId, "RULE_LLM_MERGE", "INFERRED",
                        "USER", "USER", entry.getKey()));
            } catch (Exception ex) {
                log.warn("persistPreferencesToL2 lifecycle write failed, key={}, reason={}",
                        entry.getKey(), ex.getMessage());
            }
        }
    }

    private String detectBrand(String lower) {
        Map<String, String> brandDict = Map.ofEntries(
                Map.entry("苹果", "Apple"),
                Map.entry("iphone", "Apple"),
                Map.entry("apple", "Apple"),
                Map.entry("华为", "Huawei"),
                Map.entry("huawei", "Huawei"),
                Map.entry("小米", "Xiaomi"),
                Map.entry("xiaomi", "Xiaomi"),
                Map.entry("荣耀", "Honor"),
                Map.entry("oppo", "OPPO"),
                Map.entry("vivo", "vivo"),
                Map.entry("三星", "Samsung"),
                Map.entry("samsung", "Samsung")
        );
        for (Map.Entry<String, String> e : brandDict.entrySet()) {
            if (lower.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return "";
    }

    private boolean hasPreferenceIntent(String lower) {
        return lower.contains("偏好")
                || lower.contains("喜欢")
                || lower.contains("常卖")
                || lower.contains("经常卖")
                || lower.contains("以后")
                || lower.contains("默认")
                || lower.contains("习惯")
                || lower.contains("预算")
                || lower.contains("价位")
                || lower.contains("风格")
                || lower.contains("保守")
                || lower.contains("稳妥");
    }

    private String detectPriceRange(String lower) {
        if (lower.contains("预算") || lower.contains("价位") || lower.contains("价格") || lower.contains("元")) {
            if (lower.matches(".*([1-9]\\d{2,4}).*")) {
                return "用户提及具体预算";
            }
            if (lower.contains("便宜") || lower.contains("性价比")) {
                return "偏低价";
            }
            if (lower.contains("高端") || lower.contains("旗舰")) {
                return "偏高价";
            }
            return "用户关注预算";
        }
        return "";
    }

    private String detectCondition(String lower) {
        if (lower.contains("95新") || lower.contains("9成新")) {
            return "95新";
        }
        if (lower.contains("99新") || lower.contains("几乎全新")) {
            return "99新";
        }
        if (lower.contains("成色") || lower.contains("外观") || lower.contains("瑕疵")) {
            return "关注成色";
        }
        return "";
    }

    private String detectCategory(String lower) {
        Map<String, String> categoryDict = Map.ofEntries(
                Map.entry("手机", "手机"),
                Map.entry("iphone", "手机"),
                Map.entry("相机", "相机"),
                Map.entry("镜头", "镜头"),
                Map.entry("显卡", "显卡"),
                Map.entry("游戏本", "游戏本"),
                Map.entry("笔记本", "笔记本"),
                Map.entry("耳机", "耳机"),
                Map.entry("手表", "手表"),
                Map.entry("奢侈品", "奢侈品")
        );
        for (Map.Entry<String, String> e : categoryDict.entrySet()) {
            if (lower.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return "";
    }

    private String detectTransaction(String lower) {
        if (lower.contains("当面验货") || lower.contains("面交")) {
            return "偏好当面验货";
        }
        if (lower.contains("平台交易") || lower.contains("平台担保")) {
            return "偏好平台交易";
        }
        if (lower.contains("快递") || lower.contains("邮寄")) {
            return "可接受快递";
        }
        return "";
    }

    private String detectRiskTolerance(String lower) {
        if (lower.contains("保守") || lower.contains("稳妥") || lower.contains("少风险") || lower.contains("避免纠纷")) {
            return "保守";
        }
        if (lower.contains("高风险") || lower.contains("愿意承担") || lower.contains("激进")) {
            return "较高";
        }
        return "";
    }

    private String detectWritingStyle(String lower) {
        if (lower.contains("简洁") || lower.contains("短一点")) {
            return "简洁";
        }
        if (lower.contains("详细") || lower.contains("写清楚") || lower.contains("避免纠纷")) {
            return "详细";
        }
        if (lower.contains("强调验货") || lower.contains("验货")) {
            return "强调验货";
        }
        return "";
    }
}
