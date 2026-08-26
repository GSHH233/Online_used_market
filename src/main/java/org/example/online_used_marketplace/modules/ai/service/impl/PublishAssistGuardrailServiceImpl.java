package org.example.online_used_marketplace.modules.ai.service.impl;

import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.modules.ai.dto.KnowledgeSearchFilterDTO;
import org.example.online_used_marketplace.modules.ai.dto.ProductPublishAssistRequestDTO;
import org.example.online_used_marketplace.modules.ai.enums.AssistStepStatus;
import org.example.online_used_marketplace.modules.ai.enums.KnowledgeSourceType;
import org.example.online_used_marketplace.modules.ai.enums.PublishAssistGuardrailRuleCode;
import org.example.online_used_marketplace.modules.ai.enums.RetrievalMode;
import org.example.online_used_marketplace.modules.ai.pipeline.PublishAssistRetrievalTraceHelper;
import org.example.online_used_marketplace.modules.ai.pipeline.RagSourceMergeHelper;
import org.example.online_used_marketplace.modules.ai.service.KnowledgeSearchService;
import org.example.online_used_marketplace.modules.ai.service.PublishAssistGuardrailService;
import org.example.online_used_marketplace.modules.ai.vo.AssistStepTraceVO;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeSearchHitVO;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeSearchResponseVO;
import org.example.online_used_marketplace.modules.ai.vo.ProductOptimizeResponseVO;
import org.example.online_used_marketplace.modules.ai.vo.ProductPublishAssistVO;
import org.example.online_used_marketplace.modules.ai.vo.PublishAssistRetrievalVO;
import org.example.online_used_marketplace.modules.ai.vo.RagSourceVO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 将入口澄清规则、规则证据检索和兜底证据统一收口。
 */
@Service
@RequiredArgsConstructor
public class PublishAssistGuardrailServiceImpl implements PublishAssistGuardrailService {

    private final KnowledgeSearchService knowledgeSearchService;
    private final RagSourceMergeHelper ragSourceMergeHelper;
    private final PublishAssistRetrievalTraceHelper retrievalTraceHelper;

    @Override
    public Optional<ProductPublishAssistVO> apply(ProductPublishAssistRequestDTO request, List<AssistStepTraceVO> trace) {
        Optional<RuleSpec> matchedRule = matchRule(request);
        if (matchedRule.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(buildClarificationResult(matchedRule.get(), trace));
    }

    private Optional<RuleSpec> matchRule(ProductPublishAssistRequestDTO request) {
        if (!isMissingProductIdentity(request)) {
            return Optional.empty();
        }

        String text = joinText(request);
        if (containsAny(text, "多少钱", "价格", "估价", "定价", "报价")) {
            return Optional.of(ambiguousPriceRule());
        }
        if (containsAny(text, "能不能卖", "能卖吗", "能不能发布", "能发布吗", "判断")) {
            return Optional.of(ambiguousCanSellRule());
        }
        return Optional.empty();
    }

    private ProductPublishAssistVO buildClarificationResult(RuleSpec rule, List<AssistStepTraceVO> trace) {
        appendRoundPlan(trace, 1);
        markPlannedStepsSuccess(trace, "命中入口防护规则：" + rule.ruleCode().name());

        ProductOptimizeResponseVO optimize = new ProductOptimizeResponseVO();
        optimize.setOptimizedTitle(rule.title());
        optimize.setOptimizedDescription(rule.description());
        optimize.setTags(List.of("补充信息", "商品信息不足", "平台交易"));
        optimize.setRiskWarnings(List.of("商品信息不足，无法完成可靠的发布风险或价格判断"));

        ProductPublishAssistVO vo = new ProductPublishAssistVO();
        vo.setOptimizeResult(optimize);
        vo.setRiskScore(70);
        vo.setRiskWarnings(List.of("商品信息不足，无法完成可靠判断；请补充关键信息后再发布"));
        vo.setPriceSuggestion(rule.priceSuggestion());
        vo.setCanPublish(true);
        GuardrailRetrievalOutcome retrievalOutcome = searchRuleSources(rule);
        vo.setTrace(trace);
        vo.setSources(retrievalOutcome.sources());
        vo.setRetrievals(List.of(retrievalOutcome.retrieval()));
        vo.setGenerationMode("RULE_GUARDRAIL");
        vo.setFallbackUsed(false);
        vo.setFallbackReason("");
        return vo;
    }

    private GuardrailRetrievalOutcome searchRuleSources(RuleSpec rule) {
        KnowledgeSearchFilterDTO filter = new KnowledgeSearchFilterDTO();
        filter.setSourceType(rule.sourceType());
        KnowledgeSearchResponseVO response = knowledgeSearchService.search(rule.sourceQuestion(), 5, filter, RetrievalMode.KEYWORD_ONLY);
        List<KnowledgeSearchHitVO> hits = (response == null || response.getHits() == null) ? Collections.emptyList() : response.getHits();
        List<RagSourceVO> sources = ragSourceMergeHelper.toRagSources(hits);
        boolean fallbackSourceUsed = sources.isEmpty();
        if (fallbackSourceUsed) {
            sources = fallbackSources(rule);
        }
        PublishAssistRetrievalVO retrieval = retrievalTraceHelper.toRetrievalTrace(
                "guardrail",
                "入口防护规则检索",
                1,
                rule.sourceType(),
                rule.sourceQuestion(),
                5,
                response,
                fallbackSourceUsed
        );
        return new GuardrailRetrievalOutcome(sources, retrieval);
    }

    private List<RagSourceVO> fallbackSources(RuleSpec rule) {
        if (rule.fallbackDocId() == null) {
            return Collections.emptyList();
        }
        return List.of(newSource(
                rule.fallbackDocId(),
                rule.fallbackDocTitle(),
                rule.fallbackSnippet(),
                rule.sourceType(),
                rule.fallbackSourceUri()
        ));
    }

    private RagSourceVO newSource(Long docId, String title, String snippet, KnowledgeSourceType sourceType, String sourceUri) {
        RagSourceVO source = new RagSourceVO();
        source.setDocId(docId);
        source.setDocTitle(title);
        source.setChunkId(docId);
        source.setSnippet(snippet);
        source.setScore(1.0D);
        source.setSourceType(sourceType.name());
        source.setSourceUri(sourceUri);
        return source;
    }

    private RuleSpec ambiguousCanSellRule() {
        return new RuleSpec(
                PublishAssistGuardrailRuleCode.AMBIGUOUS_CAN_SELL,
                KnowledgeSourceType.RULE,
                "商品信息不足，无法判断能否售卖",
                "当前没有商品名称、品类、用途、品牌或具体描述，证据不足，无法直接判断是否允许发布。请先补充商品信息，再进行规则检索和风险判断。",
                "请补充商品名称、品类、用途、品牌、型号、来源和实物状态后再判断；在信息不足前无法确认能否售卖。",
                "这个东西能卖吗 没有提供商品名称 品类 用途 品牌 具体描述 证据不足 不能直接判断 是否允许发布 要求补充商品信息 规则检索 风险判断",
                47L,
                "模糊商品能否售卖拒答规则",
                "当用户只问“这个东西能卖吗”但没有提供商品名称、品类、用途、品牌或具体描述时，证据不足，不能直接判断是否允许发布。应要求用户补充商品信息，再进行规则检索和风险判断。",
                "rule://ambiguous/can-sell"
        );
    }

    private RuleSpec ambiguousPriceRule() {
        return new RuleSpec(
                PublishAssistGuardrailRuleCode.AMBIGUOUS_PRICE,
                KnowledgeSourceType.MARKET,
                "商品信息不足，无法估价",
                "当前缺少商品名称、型号、配置、配件、维修史或地区行情等关键信息，无法给出可靠价格判断。请先补充型号、配置、成色细节、配件清单和可核验凭证，再调用定价规则或行情知识。",
                "请补充商品名称、型号、配置、配件、维修史、购买凭证和地区行情后再估价；在证据不足前不能编造具体价格或折扣。",
                "模糊 价格 咨询 商品型号 成色 配置 配件 维修史 地区行情 无法 估价 补充关键参数",
                50L,
                "模糊价格咨询拒答规则",
                "当用户只说“帮我看看价格”但没有提供商品型号、成色、配置、配件、维修史或地区行情时，无法给出可靠估价。应提示补充关键参数，再调用定价规则或行情知识。",
                "market://ambiguous/price"
        );
    }

    private void appendRoundPlan(List<AssistStepTraceVO> trace, Integer round) {
        trace.add(newStep("optimize#" + round, "文案优化"));
        trace.add(newStep("risk#" + round, "风险评估"));
        trace.add(newStep("pricing#" + round, "价格建议"));
        trace.add(newStep("decision#" + round, "发布决策"));
    }

    private AssistStepTraceVO newStep(String step, String label) {
        AssistStepTraceVO vo = new AssistStepTraceVO();
        vo.setStep(step);
        vo.setLabel(label);
        vo.setStatus(AssistStepStatus.PENDING);
        vo.setMessage("待执行");
        return vo;
    }

    private void markPlannedStepsSuccess(List<AssistStepTraceVO> trace, String message) {
        for (AssistStepTraceVO item : trace) {
            item.setStatus(AssistStepStatus.SUCCESS);
            item.setMessage(message);
        }
    }

    private boolean isMissingProductIdentity(ProductPublishAssistRequestDTO request) {
        if (StringUtils.hasText(request.getCategoryName()) || StringUtils.hasText(request.getBrand())) {
            return false;
        }
        String text = joinText(request);
        return containsAny(text, "没写商品名称", "商品名称", "这个东西", "这个", "没想好怎么描述", "没写型号", "没写配置");
    }

    private boolean containsAny(String text, String... keywords) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String joinText(ProductPublishAssistRequestDTO request) {
        return (safeText(request.getTitle()) + " " + safeText(request.getDescription()) + " "
                + safeText(request.getConditionText()) + " " + safeText(request.getCategoryName())).trim();
    }

    private String safeText(String text) {
        return StringUtils.hasText(text) ? text.trim() : "";
    }

    private record GuardrailRetrievalOutcome(List<RagSourceVO> sources, PublishAssistRetrievalVO retrieval) {
    }

    private record RuleSpec(
            PublishAssistGuardrailRuleCode ruleCode,
            KnowledgeSourceType sourceType,
            String title,
            String description,
            String priceSuggestion,
            String sourceQuestion,
            Long fallbackDocId,
            String fallbackDocTitle,
            String fallbackSnippet,
            String fallbackSourceUri
    ) {
    }
}
