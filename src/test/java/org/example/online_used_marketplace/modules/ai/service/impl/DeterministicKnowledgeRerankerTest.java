package org.example.online_used_marketplace.modules.ai.service.impl;

import org.example.online_used_marketplace.modules.ai.config.KnowledgeIngestionProperties;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeRerankResult;
import org.example.online_used_marketplace.modules.ai.vo.RagSourceVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicKnowledgeRerankerTest {

    @Test
    void promotesLexicallyRelevantEvidenceAndExposesBothScores() {
        KnowledgeIngestionProperties properties = new KnowledgeIngestionProperties();
        properties.getRetrieval().setMinResults(1);
        properties.getRetrieval().setRelativeScoreThreshold(0.70);
        DeterministicKnowledgeReranker reranker = new DeterministicKnowledgeReranker(properties);

        RagSourceVO unrelated = source(1L, "显示器坏点规则",
                "显示器需要说明坏点位置和颜色", 0.033, "rule:monitor");
        RagSourceVO relevant = source(2L, "相机镜头霉斑与成色规则",
                "镜头存在霉斑时不能只写准新，应提供实拍并说明镜头状态", 0.030, "rule:camera-condition");
        RagSourceVO weak = source(3L, "手机边框描述",
                "手机边框磕碰需要如实描述", 0.031, "rule:phone-frame");

        KnowledgeRerankResult result = reranker.rerank(
                "相机镜头有霉斑能写准新吗", List.of(unrelated, relevant, weak), 3);

        assertEquals(2L, result.sources().get(0).getDocId());
        assertNotNull(result.sources().get(0).getRetrievalScore());
        assertNotNull(result.sources().get(0).getRerankScore());
        assertTrue(result.sources().get(0).getRerankReason().contains("content="));
        assertTrue(result.sources().size() <= 3);
    }

    @Test
    void disabledRerankPreservesRrfOrder() {
        KnowledgeIngestionProperties properties = new KnowledgeIngestionProperties();
        properties.getRetrieval().setRerankEnabled(false);
        DeterministicKnowledgeReranker reranker = new DeterministicKnowledgeReranker(properties);

        KnowledgeRerankResult result = reranker.rerank("相机", List.of(
                source(1L, "第一条", "无关", 0.04, "one"),
                source(2L, "相机规则", "相机证据", 0.03, "two")), 1);

        assertEquals(1L, result.sources().get(0).getDocId());
        assertTrue(!result.rerankUsed());
    }

    @Test
    void removesGenericRuleNoiseButKeepsComplementaryPriceEvidence() {
        KnowledgeIngestionProperties properties = new KnowledgeIngestionProperties();
        DeterministicKnowledgeReranker reranker = new DeterministicKnowledgeReranker(properties);

        RagSourceVO fidelity = source(10L, "模糊价格咨询拒答规则",
                "缺少成交证据时不能编造折扣比例或精确报价", 0.032, "rule:price-fidelity");
        RagSourceVO factors = source(11L, "二手商品定价参考",
                "价格建议需要成色、配件、购入时间和成交记录", 0.029, "rule:price-factors");
        RagSourceVO generic = source(12L, "模糊商品能否售卖拒答规则",
                "信息不足时需要用户补充说明", 0.031, "rule:generic-refusal");

        KnowledgeRerankResult result = reranker.rerank(
                "没有成交记录能不能直接建议便宜10%出", List.of(generic, fidelity, factors), 5);

        assertTrue(result.sources().stream().anyMatch(source -> source.getDocId().equals(10L)));
        assertTrue(result.sources().stream().anyMatch(source -> source.getDocId().equals(11L)));
        assertTrue(result.sources().stream().noneMatch(source -> source.getDocId().equals(12L)),
                () -> "selected=" + result.sources().stream()
                        .map(source -> source.getDocId() + ":" + source.getRerankScore())
                        .toList());
        assertTrue(result.dynamicTopKApplied());
    }

    @Test
    void normalizesColloquialOffPlatformAndDroneTerms() {
        KnowledgeIngestionProperties properties = new KnowledgeIngestionProperties();
        DeterministicKnowledgeReranker reranker = new DeterministicKnowledgeReranker(properties);

        RagSourceVO generic = source(1L, "平台通用风险规则",
                "发布商品需要遵守平台规则", 0.040, "rule:generic");
        RagSourceVO genericForDrone = source(1L, "平台通用风险规则",
                "发布商品需要遵守平台规则", 0.040, "rule:generic");
        RagSourceVO offPlatform = source(2L, "站外交易风险规则",
                "不得公开私人电话号码或要求买家离开平台直接转账", 0.022, "rule:off-platform");
        RagSourceVO drone = source(3L, "无人机维修披露",
                "无人机炸机碰撞或维修后应披露维修范围和飞控测试", 0.021, "rule:drone-repair");

        KnowledgeRerankResult tradeResult = reranker.rerank(
                "留vx私聊再私下付款行吗", List.of(generic, offPlatform), 2);
        KnowledgeRerankResult droneResult = reranker.rerank(
                "飞机炸过修好了只说功能正常成吗", List.of(genericForDrone, drone), 2);

        assertEquals(2L, tradeResult.sources().get(0).getDocId());
        assertEquals(3L, droneResult.sources().get(0).getDocId(),
                () -> droneResult.sources().stream()
                        .map(source -> source.getDocId() + ":" + source.getRerankReason())
                        .toList().toString());
    }

    @Test
    void keepsComplementaryEvidenceForMultiIntentQuestion() {
        KnowledgeIngestionProperties properties = new KnowledgeIngestionProperties();
        properties.getRetrieval().setRelativeScoreThreshold(0.90);
        DeterministicKnowledgeReranker reranker = new DeterministicKnowledgeReranker(properties);

        RagSourceVO camera = source(1L, "相机快门次数折价规则",
                "相机快门次数需结合传感器和机型寿命定价", 0.040, "rule:camera-price");
        RagSourceVO accessory = source(2L, "缺少原装配件定价影响规则",
                "只有机身缺少原装配件时应计算配件替代成本", 0.024, "rule:accessory-price");
        RagSourceVO generic = source(3L, "普通价格规则",
                "商品价格可以参考市场", 0.030, "rule:generic-price");

        KnowledgeRerankResult result = reranker.rerank(
                "相机快门三万次，只有机身没有配件，折价要看哪些因素？",
                List.of(camera, generic, accessory), 5);

        assertTrue(result.sources().stream().anyMatch(source -> source.getDocId().equals(1L)));
        assertTrue(result.sources().stream().anyMatch(source -> source.getDocId().equals(2L)));
        assertTrue(result.sources().size() >= 2);
    }

    @Test
    void promotesEvidenceCitationRule() {
        KnowledgeIngestionProperties properties = new KnowledgeIngestionProperties();
        DeterministicKnowledgeReranker reranker = new DeterministicKnowledgeReranker(properties);

        RagSourceVO generic = source(1L, "平台高风险规则",
                "高风险商品需要拦截", 0.040, "rule:generic");
        RagSourceVO citation = source(2L, "风险证据回传",
                "风险结论应附带来源文档与命中片段", 0.021, "rule:citation");

        KnowledgeRerankResult result = reranker.rerank(
                "判断高风险时必须返回哪份规则和命中片段吗", List.of(generic, citation), 2);

        assertEquals(2L, result.sources().get(0).getDocId());
    }

    @Test
    void promotesSpecificComplianceConceptsOverGenericDisclosure() {
        KnowledgeIngestionProperties properties = new KnowledgeIngestionProperties();
        DeterministicKnowledgeReranker reranker = new DeterministicKnowledgeReranker(properties);

        KnowledgeRerankResult identifier = reranker.rerank(
                "有人提供修改手机 IMEI 的服务，这类信息能发布吗？",
                List.of(
                        source(1L, "普通手机发布规则", "手机信息应如实说明", 0.040, "rule:phone"),
                        source(2L, "改串号与设备标识修改禁售规则", "修改手机 IMEI 或绕过设备锁属于禁售服务", 0.021, "rule:identifier")),
                2);
        KnowledgeRerankResult tobacco = reranker.rerank(
                "不写电子烟，只写雾化弹和烟油口味，是否能避开限制？",
                List.of(
                        source(3L, "普通披露规则", "不应隐藏商品信息", 0.040, "rule:disclosure"),
                        source(4L, "电子烟与烟弹禁售规则", "雾化弹、烟油和电子烟均属于禁售范围", 0.021, "rule:tobacco")),
                2);
        KnowledgeRerankResult knife = reranker.rerank(
                "标题不写管制刀具，改成户外绝版摆件就可以卖了吗？",
                List.of(
                        source(5L, "普通披露规则", "标题不得隐藏真实品类", 0.040, "rule:disclosure"),
                        source(6L, "管制刀具和军刀收藏品发布规则", "开刃刀具不得以收藏品名义发布", 0.021, "rule:knife")),
                2);

        assertEquals(2L, identifier.sources().get(0).getDocId());
        assertEquals(4L, tobacco.sources().get(0).getDocId());
        assertEquals(6L, knife.sources().get(0).getDocId());
    }

    @Test
    void promotesPolicyGroundingRefusalOverPriceRefusal() {
        KnowledgeIngestionProperties properties = new KnowledgeIngestionProperties();
        DeterministicKnowledgeReranker reranker = new DeterministicKnowledgeReranker(properties);

        KnowledgeRerankResult result = reranker.rerank(
                "知识库没写这种商品，助手能参考常识编一条平台规则吗？",
                List.of(
                        source(1L, "模糊价格咨询拒答规则", "缺少价格证据时不得编造报价", 0.040, "rule:price-refusal"),
                        source(2L, "知识问答拒答规则", "知识库没有依据时不得凭常识编造平台规则", 0.021, "rule:policy-grounding")),
                2);

        assertEquals(2L, result.sources().get(0).getDocId());
    }

    @Test
    void keepsLowScoreEvidenceWhenItCompletesARequiredFacet() {
        KnowledgeIngestionProperties properties = new KnowledgeIngestionProperties();
        properties.getRetrieval().setRelativeScoreThreshold(0.95D);
        properties.getRetrieval().setFacetCoverageEnabled(true);
        properties.getRetrieval().setMinFacetCoverage(0.80D);
        DeterministicKnowledgeReranker reranker = new DeterministicKnowledgeReranker(properties);

        RagSourceVO seasonal = source(1L, "热门游戏本淡旺季价差规则",
                "开学季挂牌价可能上涨，但应参考近期市场成交样本", 0.040, "rule:seasonal-price");
        RagSourceVO generic = source(2L, "普通价格说明",
                "商品价格可以参考市场行情", 0.038, "rule:generic-price");
        RagSourceVO fidelity = source(3L, "价格忠实度规则",
                "不得对所有商品使用固定百分比或统一加价", 0.018, "rule:price-fidelity");

        KnowledgeRerankResult result = reranker.rerank(
                "开学季游戏本挂牌价上涨，能统一加价15%吗？",
                List.of(generic, seasonal, fidelity), 5);

        assertTrue(result.sources().stream().anyMatch(source -> source.getDocId().equals(1L)));
        assertTrue(result.sources().stream().anyMatch(source -> source.getDocId().equals(3L)));
        assertTrue(result.queryFacets().contains("@seasonality"));
        assertTrue(result.queryFacets().contains("@quantified_claim"));
        assertTrue(result.queryFacets().contains("@price_boundary"));
        assertTrue(result.coveredFacets().contains("@price_boundary"));
        assertTrue(result.facetCoverage() >= 0.80D,
                () -> "facets=" + result.queryFacets() + ", covered=" + result.coveredFacets());
    }

    @Test
    void keepsSeparateEvidenceForAccountOwnershipAndPaymentChannel() {
        KnowledgeIngestionProperties properties = new KnowledgeIngestionProperties();
        properties.getRetrieval().setRelativeScoreThreshold(0.95D);
        properties.getRetrieval().setEvidencePlanEnabled(true);
        properties.getRetrieval().setMinEvidenceCoverage(0.90D);
        DeterministicKnowledgeReranker reranker = new DeterministicKnowledgeReranker(properties);

        RagSourceVO account = source(1L, "账号交易与找回风险规则",
                "账号初始实名、充值凭证和申诉找回能力决定所有权风险", 0.040, "rule:account");
        RagSourceVO generic = source(2L, "普通平台交易说明",
                "买卖双方应保留沟通记录", 0.038, "rule:generic");
        RagSourceVO offPlatform = source(3L, "站外交易与付款风险规则",
                "押金汇到个人账户不受平台担保，应使用平台内支付", 0.018, "rule:off-platform");

        KnowledgeRerankResult result = reranker.rerank(
                "可申诉找回的账户在平台成交，同时要求把押金汇到个人账户，需要分别提示哪些风险？",
                List.of(generic, account, offPlatform), 5);

        assertTrue(result.sources().stream().anyMatch(source -> source.getDocId().equals(1L)));
        assertTrue(result.sources().stream().anyMatch(source -> source.getDocId().equals(3L)));
        assertTrue(result.evidencePlan().containsAll(
                List.of("ACCOUNT_OWNERSHIP", "TRANSACTION_CHANNEL")));
        assertTrue(result.coveredEvidence().containsAll(
                List.of("ACCOUNT_OWNERSHIP", "TRANSACTION_CHANNEL")));
        assertEquals(1.0D, result.evidenceCoverage(), 0.0001D);
    }

    @Test
    void searchesPastHigherScoringRedundantCandidatesForMissingEvidence() {
        KnowledgeIngestionProperties properties = new KnowledgeIngestionProperties();
        properties.getRetrieval().setRelativeScoreThreshold(0.99D);
        properties.getRetrieval().setFacetCoverageEnabled(false);
        properties.getRetrieval().setEvidencePlanEnabled(true);
        properties.getRetrieval().setEvidencePlanWeight(0.0D);
        properties.getRetrieval().setMinEvidenceCoverage(0.90D);
        DeterministicKnowledgeReranker reranker = new DeterministicKnowledgeReranker(properties);

        RagSourceVO account = source(1L, "账号交易与找回风险规则",
                "账号初始实名和申诉找回能力决定所有权风险", 0.040, "rule:account");
        RagSourceVO genericOne = source(2L, "普通交易提醒",
                "买卖双方应保留沟通记录", 0.039, "rule:generic-one");
        RagSourceVO genericTwo = source(3L, "普通交易流程",
                "成交前应再次确认商品信息", 0.037, "rule:generic-two");
        RagSourceVO offPlatform = source(4L, "站外交易与付款风险规则",
                "押金汇到个人账户不受平台担保，应使用平台内支付", 0.001, "rule:off-platform");

        KnowledgeRerankResult result = reranker.rerank(
                "可申诉找回的账户要求把押金汇到个人账户，需要分别提示哪些风险？",
                List.of(account, genericOne, genericTwo, offPlatform), 4);

        assertTrue(result.sources().stream().anyMatch(source -> source.getDocId().equals(1L)));
        assertTrue(result.sources().stream().anyMatch(source -> source.getDocId().equals(4L)));
        assertTrue(result.coveredEvidence().containsAll(
                List.of("ACCOUNT_OWNERSHIP", "TRANSACTION_CHANNEL")));
        assertEquals(1.0D, result.evidenceCoverage(), 0.0001D);
    }

    @Test
    void penalizesCrossCategoryNoiseAndPrefersSpecificRiskFacet() {
        KnowledgeIngestionProperties properties = new KnowledgeIngestionProperties();
        DeterministicKnowledgeReranker reranker = new DeterministicKnowledgeReranker(properties);

        RagSourceVO phonePricing = source(1L, "iPhone 13 128G 95新价格区间",
                "手机估价要结合电池健康和屏幕维修史", 0.040, "rule:phone-price");
        RagSourceVO laptopNoise = source(2L, "95新笔记本电脑描述模板",
                "笔记本需要说明电池和维修史", 0.035, "rule:laptop-condition");
        RagSourceVO priceFidelity = source(3L, "模糊价格咨询拒答规则",
                "信息不足时不得编造精确价格，应要求补充关键信息", 0.025, "rule:price-fidelity");

        KnowledgeRerankResult result = reranker.rerank(
                "iPhone 13写95新，但不知道电池和维修史，能直接估精确价格吗",
                List.of(phonePricing, laptopNoise, priceFidelity), 5);

        assertTrue(result.sources().stream().anyMatch(source -> source.getDocId().equals(1L)));
        assertTrue(result.sources().stream().anyMatch(source -> source.getDocId().equals(3L)));
        assertTrue(result.sources().stream().noneMatch(source -> source.getDocId().equals(2L)));
    }

    @Test
    void collapsesLegacyAndGovernedCopiesOfTheSameStrongRuleFamily() {
        KnowledgeIngestionProperties properties = new KnowledgeIngestionProperties();
        properties.getRetrieval().setRelativeScoreThreshold(0.90D);
        DeterministicKnowledgeReranker reranker = new DeterministicKnowledgeReranker(properties);

        RagSourceVO governed = source(1L, "站外交易风险规则",
                "不得要求买家私下转账，应保留平台内支付记录", 0.040D,
                "rule_off_platform_trade");
        RagSourceVO legacy = source(2L, "交易方式与平台外引流规则",
                "二维码私下付款无法获得平台担保", 0.039D, "legacy_trade_rule");
        RagSourceVO noise = source(3L, "普通沟通说明",
                "买卖双方可以在订单中留言", 0.010D, "generic_message");

        KnowledgeRerankResult result = reranker.rerank(
                "订单保留着但扫码私下结算还能受平台保护吗？",
                List.of(governed, legacy, noise), 5);

        long duplicateFamilyCount = result.sources().stream()
                .filter(source -> source.getDocId().equals(1L) || source.getDocId().equals(2L))
                .count();
        assertEquals(1L, duplicateFamilyCount);
    }

    @Test
    void keepsConditionAccessoryAndPricingEvidenceForParaphrasedMultiHopQuery() {
        KnowledgeIngestionProperties properties = new KnowledgeIngestionProperties();
        properties.getRetrieval().setRelativeScoreThreshold(0.95D);
        properties.getRetrieval().setEvidencePlanEnabled(true);
        DeterministicKnowledgeReranker reranker = new DeterministicKnowledgeReranker(properties);

        KnowledgeRerankResult result = reranker.rerank(
                "相机机身有凹陷又少充电器，发布说明和估价分别需要哪些依据？",
                List.of(
                        source(1L, "相机成色规则", "相机磕碰和凹陷应在正文披露并提供实拍", 0.035D, "camera-condition"),
                        source(2L, "相机定价参考", "相机价格应结合外观、传感器、维修和快门次数", 0.033D, "camera-pricing"),
                        source(3L, "缺少原装配件定价影响规则", "少充电器时应列出缺失配件并计算替代成本", 0.020D, "accessory-pricing"),
                        source(4L, "普通商品说明", "商品应如实发布", 0.040D, "generic")),
                5);

        assertTrue(result.sources().stream().map(RagSourceVO::getDocId).toList()
                .containsAll(List.of(1L, 2L, 3L)));
        assertTrue(result.coveredEvidence().containsAll(List.of(
                "CONDITION_DISCLOSURE", "ACCESSORY_COMPLETENESS", "PRICING_FACTORS")),
                () -> "covered=" + result.coveredEvidence());
    }

    private RagSourceVO source(Long docId, String title, String snippet,
                               double score, String logicalKnowledgeId) {
        RagSourceVO source = new RagSourceVO();
        source.setDocId(docId);
        source.setChunkId(docId * 10);
        source.setDocTitle(title);
        source.setSnippet(snippet);
        source.setScore(score);
        source.setSourceTrustLevel("MEDIUM");
        source.setLogicalKnowledgeId(logicalKnowledgeId);
        return source;
    }
}
