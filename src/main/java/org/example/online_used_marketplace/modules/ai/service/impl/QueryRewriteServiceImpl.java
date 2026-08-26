package org.example.online_used_marketplace.modules.ai.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.modules.ai.config.KnowledgeIngestionProperties;
import org.example.online_used_marketplace.modules.ai.service.ChatGenerationService;
import org.example.online_used_marketplace.modules.ai.service.QueryRewriteService;
import org.example.online_used_marketplace.modules.ai.token.TokenGenerationRequest;
import org.example.online_used_marketplace.modules.ai.token.TokenScene;
import org.example.online_used_marketplace.modules.ai.vo.QueryRewritePlanVO;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueryRewriteServiceImpl implements QueryRewriteService {

    private static final String PROMPT_VERSION = "knowledge-query-rewrite-v3";
    private static final Pattern PROTECTED_LITERAL = Pattern.compile(
            "(?i)(?<![a-z0-9])(?:[a-z]{1,12}[-_ ]?\\d+[a-z0-9._-]*|"
                    + "\\d+(?:\\.\\d+)?(?:%|[a-z]{1,8})?)(?![a-z0-9])");
    private static final Pattern SENSITIVE_INFO = Pattern.compile(
            "(?i)(?:1[3-9]\\d{9}|[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}|\\b\\d{17}[\\dXx]\\b)");
    private static final List<String> NEGATIONS = List.of(
            "不", "没", "没有", "不能", "禁止", "无需", "未", "非", "不可");

    private final ChatGenerationService chatGenerationService;
    private final ObjectMapper objectMapper;
    private final KnowledgeIngestionProperties properties;
    private final MeterRegistry meterRegistry;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "query-rewrite");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public boolean shouldRewriteBeforeRetrieval(String query) {
        if (!properties.getQueryRewrite().isEnabled() || !StringUtils.hasText(query)) {
            return false;
        }
        String value = query.toLowerCase(Locale.ROOT);
        int connectorCount = countMatches(value,
                "同时", "并且", "以及", "分别", "然后", "另外", "一方面", "另一方面");
        int conceptCount = KnowledgeQueryConcepts.detect(value).size();
        KnowledgeEvidencePlanner.Plan evidencePlan = KnowledgeEvidencePlanner.plan(value);
        int evidenceCount = evidencePlan.requirements().size();
        boolean unresolvedReference = containsAny(value, "这个", "那个", "上述", "前面", "它", "他们")
                && conceptCount == 0;
        return query.length() >= 60
                || unresolvedReference
                || evidenceCount >= 4
                || (connectorCount >= 1 && evidencePlan.multiHop())
                || (connectorCount >= 2 && conceptCount >= 2)
                || (containsAny(value, "为什么", "原因", "怎么")
                    && query.length() > 32 && conceptCount >= 2);
    }

    @Override
    public QueryRewritePlanVO rewrite(String query, String triggerReason) {
        long started = System.currentTimeMillis();
        if (!properties.getQueryRewrite().isEnabled()) {
            meterRegistry.counter("rag_query_rewrite_total", "trigger", "disabled", "status", "skipped")
                    .increment();
            return originalOnly(query, "disabled");
        }
        boolean cacheable = !SENSITIVE_INFO.matcher(query).find();
        String cacheKey = cacheable ? hash(PROMPT_VERSION + "|"
                + properties.getQueryRewrite().getModelId() + "|"
                + normalizeCacheQuery(query)) : "";
        CacheEntry cached = cacheable ? cache.get(cacheKey) : null;
        if (cached != null && !cached.expired()) {
            QueryRewritePlanVO copy = copy(cached.plan());
            copy.setTriggerReason(triggerReason + ",cacheHit=true");
            copy.setElapsedMs(System.currentTimeMillis() - started);
            recordMetrics(triggerReason, "cache_hit", copy);
            return copy;
        }
        if (cached != null) {
            cache.remove(cacheKey, cached);
        }
        Future<QueryRewritePlanVO> future = executor.submit(() -> callModel(query, triggerReason));
        try {
            QueryRewritePlanVO plan = future.get(
                    properties.getQueryRewrite().getTimeoutMs(), TimeUnit.MILLISECONDS);
            validate(plan);
            plan.setElapsedMs(System.currentTimeMillis() - started);
            if (cacheable) {
                putCache(cacheKey, new CacheEntry(
                        copy(plan),
                        System.currentTimeMillis() + queryCacheTtlMs()));
            }
            recordMetrics(triggerReason, "success", plan);
            return plan;
        } catch (Exception ex) {
            future.cancel(true);
            String reason = ex instanceof TimeoutException
                    ? "exceeded " + properties.getQueryRewrite().getTimeoutMs() + "ms"
                    : safe(ex.getMessage());
            log.warn("[QueryRewrite] fallback to original, reason={}: {}",
                    ex.getClass().getSimpleName(), reason);
            QueryRewritePlanVO fallback = originalOnly(query, triggerReason);
            fallback.setFallback(true);
            fallback.setFallbackReason(ex.getClass().getSimpleName() + ": " + reason);
            fallback.setElapsedMs(System.currentTimeMillis() - started);
            recordMetrics(triggerReason, "fallback", fallback);
            meterRegistry.counter("rag_query_rewrite_fallback_total",
                    "reason", ex.getClass().getSimpleName()).increment();
            return fallback;
        }
    }

    @Override
    public QueryRewritePlanVO originalOnly(String query, String reason) {
        QueryRewritePlanVO plan = new QueryRewritePlanVO();
        plan.setOriginalQuery(query == null ? "" : query.trim());
        plan.setTriggerReason(reason);
        plan.setRewriteUsed(false);
        return plan;
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }

    private QueryRewritePlanVO callModel(String query, String triggerReason) throws Exception {
        String prompt = """
                你是知识库检索Query改写器。一次输出主改写、子查询和同义改写，帮助召回正文中与用户表达不同的规则。
                规则：
                1. originalQuery必须原样返回。
                2. mainQuery只补全语义和规范表达，不新增事实。
                3. 只有问题包含多个独立检索意图时才生成subQueries，最多%d条。
                4. synonymQueries只做术语和表达扩展，最多%d条。
                5. mustKeepTerms必须逐字复制原问题中的连续原文，不得写同义词；至少覆盖商品/品类、口语故障状态、凭证、型号、数字、金额、比例、日期、错误码和规则编号。
                6. negations必须逐字复制原问题中的否定词，不得将“没/没有/不能/不写”等改成其他形式。
                7. mainQuery可以规范化口语，但mustKeepTerms和negations用于结构化保留原词。
                8. 每条subQuery只覆盖一个独立意图，并保留该意图对应的对象和动作；不生成SQL、PromQL、日志表达式或工具参数。
                返回严格JSON：
                {"originalQuery":"","mainQuery":"","subQueries":[],"synonymQueries":[],
                 "mustKeepTerms":[],"negations":[],"rewriteReason":""}
                用户问题：%s
                """.formatted(properties.getQueryRewrite().getMaxSubQueries(),
                properties.getQueryRewrite().getMaxSynonymQueries(), query);
        String raw = chatGenerationService.generate(TokenGenerationRequest.builder()
                .scene(TokenScene.QUERY_REWRITE)
                .systemPrompt("只输出合法JSON，不输出Markdown或解释。")
                .prompt(prompt)
                .build()).getContent();
        JsonNode root = objectMapper.readTree(cleanJson(raw));
        QueryRewritePlanVO plan = new QueryRewritePlanVO();
        plan.setOriginalQuery(query.trim());
        plan.setMainQuery(text(root, "mainQuery"));
        plan.setSubQueries(textList(root.path("subQueries"), properties.getQueryRewrite().getMaxSubQueries()));
        plan.setSynonymQueries(textList(root.path("synonymQueries"),
                properties.getQueryRewrite().getMaxSynonymQueries()));
        plan.setMustKeepTerms(textList(root.path("mustKeepTerms"), 20));
        plan.setNegations(textList(root.path("negations"), 10));
        plan.setRewriteReason(text(root, "rewriteReason"));
        plan.setTriggerReason(triggerReason);
        plan.setRewriteUsed(true);
        addProtectedTerms(query, plan);
        return plan;
    }

    private void validate(QueryRewritePlanVO plan) {
        if (!StringUtils.hasText(plan.getOriginalQuery()) || !StringUtils.hasText(plan.getMainQuery())) {
            throw new IllegalArgumentException("main rewrite is empty");
        }
        List<String> originalLiterals = protectedLiterals(plan.getOriginalQuery());
        rejectNewProtectedLiterals(plan.getMainQuery(), originalLiterals, "main");
        plan.setSubQueries(filterFaithfulBranches(plan.getSubQueries(), originalLiterals));
        plan.setSynonymQueries(filterFaithfulBranches(plan.getSynonymQueries(), originalLiterals));
        deduplicate(plan);
    }

    private List<String> filterFaithfulBranches(List<String> candidates,
                                                List<String> originalLiterals) {
        List<String> out = new ArrayList<>();
        for (String candidate : candidates) {
            if (!StringUtils.hasText(candidate) || SENSITIVE_INFO.matcher(candidate).find()) {
                continue;
            }
            if (!containsNewProtectedLiteral(candidate, originalLiterals)) {
                out.add(candidate);
            }
        }
        return out;
    }

    private void rejectNewProtectedLiterals(String candidate,
                                            List<String> originalLiterals,
                                            String branch) {
        if (containsNewProtectedLiteral(candidate, originalLiterals)) {
            throw new IllegalArgumentException(branch + " rewrite introduced protected literal");
        }
    }

    private boolean containsNewProtectedLiteral(String candidate, List<String> originalLiterals) {
        Set<String> allowed = new LinkedHashSet<>();
        for (String literal : originalLiterals) {
            allowed.add(literal.toLowerCase(Locale.ROOT));
        }
        return protectedLiterals(candidate).stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(value -> !allowed.contains(value));
    }

    private void addProtectedTerms(String query, QueryRewritePlanVO plan) {
        Set<String> terms = new LinkedHashSet<>();
        for (String term : plan.getMustKeepTerms()) {
            if (StringUtils.hasText(term) && query.contains(term)) {
                terms.add(term);
            }
        }
        terms.addAll(protectedLiterals(query));
        plan.setMustKeepTerms(new ArrayList<>(terms));
        Set<String> negations = new LinkedHashSet<>();
        for (String negation : plan.getNegations()) {
            if (StringUtils.hasText(negation) && query.contains(negation)) {
                negations.add(negation);
            }
        }
        for (String value : NEGATIONS) {
            if (query.contains(value)) {
                negations.add(value);
            }
        }
        plan.setNegations(new ArrayList<>(negations));
    }

    private List<String> protectedLiterals(String query) {
        List<String> literals = new ArrayList<>();
        Matcher matcher = PROTECTED_LITERAL.matcher(query);
        while (matcher.find()) {
            literals.add(matcher.group());
        }
        return literals;
    }

    private void deduplicate(QueryRewritePlanVO plan) {
        Set<String> seen = new LinkedHashSet<>();
        seen.add(plan.getOriginalQuery());
        if (!seen.add(plan.getMainQuery())) {
            plan.setMainQuery("");
        }
        plan.setSubQueries(unique(plan.getSubQueries(), seen));
        plan.setSynonymQueries(unique(plan.getSynonymQueries(), seen));
    }

    private List<String> unique(List<String> values, Set<String> seen) {
        List<String> out = new ArrayList<>();
        for (String value : values) {
            String normalized = value == null ? "" : value.trim();
            if (StringUtils.hasText(normalized) && seen.add(normalized)) {
                out.add(normalized);
            }
        }
        return out;
    }

    private List<String> textList(JsonNode node, int limit) {
        List<String> out = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode value : node) {
                if (out.size() >= limit) {
                    break;
                }
                if (StringUtils.hasText(value.asText())) {
                    out.add(value.asText().trim());
                }
            }
        }
        return out;
    }

    private String text(JsonNode root, String name) {
        return root.path(name).asText("").trim();
    }

    private String cleanJson(String raw) {
        String value = raw == null ? "{}" : raw.trim();
        if (value.startsWith("```")) {
            value = value.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        return value;
    }

    private boolean containsAny(String value, String... terms) {
        for (String term : terms) {
            if (value.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private int countMatches(String value, String... terms) {
        int count = 0;
        for (String term : terms) {
            if (value.contains(term)) {
                count++;
            }
        }
        return count;
    }

    private QueryRewritePlanVO copy(QueryRewritePlanVO source) {
        QueryRewritePlanVO out = new QueryRewritePlanVO();
        out.setOriginalQuery(source.getOriginalQuery());
        out.setMainQuery(source.getMainQuery());
        out.setSubQueries(new ArrayList<>(source.getSubQueries()));
        out.setSynonymQueries(new ArrayList<>(source.getSynonymQueries()));
        out.setMustKeepTerms(new ArrayList<>(source.getMustKeepTerms()));
        out.setNegations(new ArrayList<>(source.getNegations()));
        out.setRewriteReason(source.getRewriteReason());
        out.setTriggerReason(source.getTriggerReason());
        out.setRewriteUsed(source.isRewriteUsed());
        out.setFallback(source.isFallback());
        out.setFallbackReason(source.getFallbackReason());
        out.setElapsedMs(source.getElapsedMs());
        return out;
    }

    private String normalizeCacheQuery(String query) {
        return query == null ? "" : query.trim().replaceAll("\\s+", " ");
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private long queryCacheTtlMs() {
        return Math.max(1L, properties.getQueryRewrite().getCacheTtlSeconds()) * 1_000L;
    }

    private void putCache(String key, CacheEntry entry) {
        int maxEntries = Math.max(1, properties.getQueryRewrite().getCacheMaxEntries());
        cache.entrySet().removeIf(existing -> existing.getValue().expired());
        if (cache.size() >= maxEntries) {
            cache.keySet().stream().findFirst().ifPresent(cache::remove);
        }
        cache.put(key, entry);
    }

    private record CacheEntry(QueryRewritePlanVO plan, long expiresAtMs) {
        private boolean expired() {
            return System.currentTimeMillis() >= expiresAtMs;
        }
    }

    private String safe(String message) {
        if (message == null) {
            return "";
        }
        return message.length() <= 300 ? message : message.substring(0, 300);
    }

    private void recordMetrics(String trigger, String status, QueryRewritePlanVO plan) {
        String normalizedTrigger = StringUtils.hasText(trigger) ? trigger.split(",", 2)[0] : "unknown";
        meterRegistry.counter("rag_query_rewrite_total",
                "trigger", normalizedTrigger,
                "status", status).increment();
        Timer.builder("rag_query_rewrite_duration_seconds")
                .register(meterRegistry)
                .record(java.time.Duration.ofMillis(Math.max(0, plan.getElapsedMs())));
        meterRegistry.summary("rag_query_rewrite_branch_count", "type", "sub")
                .record(plan.getSubQueries().size());
        meterRegistry.summary("rag_query_rewrite_branch_count", "type", "synonym")
                .record(plan.getSynonymQueries().size());
    }
}
