package org.example.online_used_marketplace.modules.ai.service.impl;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeEvidencePlannerTest {

    @Test
    void normalizesSplitSettlementAsOffPlatformTransaction() {
        KnowledgeEvidencePlanner.Plan plan = KnowledgeEvidencePlanner.plan(
                "链接里付个小定，尾款打到卖家卡上，东西照常发，这样也算走平台吗？");

        assertTrue(plan.normalizedConcepts().contains("@off_platform"));
        assertTrue(requirementIds(plan).contains("TRANSACTION_CHANNEL"));
    }

    @Test
    void plansAccountOwnershipAndTransactionChannelSeparately() {
        KnowledgeEvidencePlanner.Plan plan = KnowledgeEvidencePlanner.plan(
                "可申诉找回的账户在平台成交，同时要求把押金汇到个人账户，需要分别提示哪些风险？");

        assertTrue(requirementIds(plan).containsAll(
                Set.of("ACCOUNT_OWNERSHIP", "TRANSACTION_CHANNEL")));
        assertTrue(plan.multiHop());
    }

    @Test
    void doesNotTreatSimCardRealNameWordingAsAccountOwnership() {
        KnowledgeEvidencePlanner.Plan plan = KnowledgeEvidencePlanner.plan(
                "流量卡永久不降速、不实名，随便用，这种标题能发吗？");

        assertTrue(!plan.normalizedConcepts().contains("@account"));
        assertTrue(!requirementIds(plan).contains("ACCOUNT_OWNERSHIP"));
        assertTrue(requirementIds(plan).contains("SIM_CARD_COMPLIANCE"));
    }

    @Test
    void decomposesProductConditionAccessoryPricingAndCitationEvidence() {
        KnowledgeEvidencePlanner.Plan plan = KnowledgeEvidencePlanner.plan(
                "相机传感器有灰点且没有原装充电配件，判断成色和价格时应引用哪些信息？");

        assertTrue(requirementIds(plan).containsAll(Set.of(
                "PRODUCT_CAMERA", "CONDITION_DISCLOSURE", "ACCESSORY_COMPLETENESS",
                "PRICING_FACTORS", "SOURCE_CITATION")));
    }

    @Test
    void createsGroundingBoundaryForMissingContext() {
        KnowledgeEvidencePlanner.Plan plan = KnowledgeEvidencePlanner.plan(
                "会话里没有任何商品上下文，用户突然问它能不能发，系统应先补什么？");

        assertEquals(Set.of("LISTING_IDENTITY", "LISTING_PERMISSION_BOUNDARY"), requirementIds(plan));
    }

    @Test
    void requiresCategorySpecificDisclosureInsteadOfGenericPricingEvidence() {
        KnowledgeEvidencePlanner.Plan plan = KnowledgeEvidencePlanner.plan(
                "相机传感器有灰点，判断成色时应说明哪些问题？");
        KnowledgeEvidencePlanner.Requirement condition = plan.requirements().stream()
                .filter(requirement -> "CONDITION_DISCLOSURE".equals(requirement.id()))
                .findFirst()
                .orElseThrow();

        assertTrue(condition.matches(Set.of("@camera", "@camera_condition", "@disclosure")));
        assertTrue(!condition.matches(Set.of("@camera", "@condition", "@price")));

        Set<String> cameraConditionDocument = KnowledgeQueryConcepts.detect(
                "相机镜头轻微灰尘标题写法。描述中说明灰尘位置、是否有霉斑划痕，并提供实拍样张。");
        Set<String> cameraPricingDocument = KnowledgeQueryConcepts.detect(
                "相机快门次数是定价因素，需要结合外观和传感器，并提供检测截图。");
        assertTrue(condition.matches(cameraConditionDocument));
        assertTrue(!condition.matches(cameraPricingDocument));
    }

    @Test
    void treatsRequestedMinimumAmountAsAPriceBoundary() {
        KnowledgeEvidencePlanner.Plan plan = KnowledgeEvidencePlanner.plan(
                "商品身份都无法确定，用户又要求预测最低成交金额，应该怎么处理？");

        assertTrue(requirementIds(plan).containsAll(Set.of(
                "LISTING_IDENTITY", "LISTING_PERMISSION_BOUNDARY", "PRICE_BOUNDARY")));
    }

    @Test
    void expandsImplicitAccessoryAndPhonePricingEvidence() {
        KnowledgeEvidencePlanner.Plan gpu = KnowledgeEvidencePlanner.plan(
                "显卡没有矿史证明，只测了待机温度，还缺包装与保修，评估稳定性和报价要看什么？");
        KnowledgeEvidencePlanner.Plan phone = KnowledgeEvidencePlanner.plan(
                "手机无购买凭证且维修情况未知，仍要求给出最低成交承诺。");

        assertTrue(requirementIds(gpu).containsAll(Set.of(
                "PRODUCT_GPU", "USAGE_HISTORY", "ACCESSORY_COMPLETENESS", "PRICING_FACTORS")));
        assertTrue(requirementIds(phone).containsAll(Set.of(
                "PRODUCT_PHONE", "REPAIR_HISTORY", "PROVENANCE", "PRICING_FACTORS", "PRICE_BOUNDARY")));
    }

    @Test
    void recognizesPriceBoundaryAndTraceableCitationParaphrases() {
        KnowledgeEvidencePlanner.Plan price = KnowledgeEvidencePlanner.plan(
                "把所有轻微磨损都标成九五新，再按同一百分比折价是否可靠？");
        KnowledgeEvidencePlanner.Plan citation = KnowledgeEvidencePlanner.plan(
                "相机镜头存在划痕，给出高风险结论时还应返回什么可追溯依据？");

        assertTrue(requirementIds(price).containsAll(Set.of(
                "CONDITION_DISCLOSURE", "PRICING_FACTORS", "PRICE_BOUNDARY")));
        assertTrue(requirementIds(citation).containsAll(Set.of(
                "PRODUCT_CAMERA", "CONDITION_DISCLOSURE", "SOURCE_CITATION")));
    }

    @Test
    void treatsUniformFixedAmountAdjustmentAsPriceBoundary() {
        KnowledgeEvidencePlanner.Plan plan = KnowledgeEvidencePlanner.plan(
                "缺盒缺线缺说明书时，统一下调一个固定金额，就不用考虑各配件替代成本了吗？");

        assertTrue(requirementIds(plan).containsAll(Set.of(
                "ACCESSORY_COMPLETENESS", "PRICING_FACTORS", "PRICE_BOUNDARY")),
                () -> "requirements=" + requirementIds(plan));
    }

    @Test
    void treatsPolicyVersionComparisonAsCitationInsteadOfProductCondition() {
        KnowledgeEvidencePlanner.Plan plan = KnowledgeEvidencePlanner.plan(
                "新旧规范对同一商品给出相反结论，如何处理有效性并展示引用证据？");

        assertTrue(requirementIds(plan).contains("SOURCE_CITATION"));
        assertTrue(!requirementIds(plan).contains("CONDITION_DISCLOSURE"));
    }

    @Test
    void createsGroundingBoundaryForUnseenDeviceAndExternalCommonSense() {
        KnowledgeEvidencePlanner.Plan plan = KnowledgeEvidencePlanner.plan(
                "平台知识中从未出现过这种新设备，可以借用外部常识推断平台结论吗？");

        assertTrue(requirementIds(plan).contains("GROUNDING_BOUNDARY"));
        assertTrue(plan.normalizedConcepts().containsAll(Set.of("@policy_grounding", "@insufficient")));
    }

    @Test
    void normalizesDamageAccessoryLossAndPricingIntoSeparateEvidence() {
        KnowledgeEvidencePlanner.Plan plan = KnowledgeEvidencePlanner.plan(
                "相机机身有凹陷又少充电器，发布说明和估价分别需要哪些依据？");

        assertTrue(requirementIds(plan).containsAll(Set.of(
                "PRODUCT_CAMERA", "CONDITION_DISCLOSURE", "ACCESSORY_COMPLETENESS", "PRICING_FACTORS")),
                () -> "requirements=" + requirementIds(plan));
    }

    @Test
    void normalizesUnattributedSummaryIntoCitationAndGroundingEvidence() {
        KnowledgeEvidencePlanner.Plan plan = KnowledgeEvidencePlanner.plan(
                "检索只剩来源不明的摘要且规则库没有对应条款，能直接判断准售吗？");

        assertTrue(requirementIds(plan).containsAll(Set.of(
                "SOURCE_CITATION", "GROUNDING_BOUNDARY", "LISTING_PERMISSION_BOUNDARY")),
                () -> "requirements=" + requirementIds(plan));
    }

    @Test
    void recognizesUniformConditionDiscountAsACompositeBoundary() {
        KnowledgeEvidencePlanner.Plan plan = KnowledgeEvidencePlanner.plan(
                "把不同商品按外观等级统一折价，再叠加旺季挂牌价是否合理？");

        assertTrue(requirementIds(plan).containsAll(Set.of(
                "CONDITION_DISCLOSURE", "CONDITION_PRICE_GAP", "PRICE_BOUNDARY")),
                () -> "requirements=" + requirementIds(plan));
    }

    @Test
    void separatesMonitorKeyboardAndPackagingDisclosureEvidence() {
        KnowledgeEvidencePlanner.Plan monitor = KnowledgeEvidencePlanner.plan(
                "显示器在纯色背景出现暗点，详情能省略吗？");
        KnowledgeEvidencePlanner.Plan keyboard = KnowledgeEvidencePlanner.plan(
                "键盘空格键打油且键帽掉色，成色标注要怎么写？");
        KnowledgeEvidencePlanner.Plan packaging = KnowledgeEvidencePlanner.plan(
                "本体九成新但原盒丢失，怎样列实际配件避免误认为全套？");

        assertTrue(requirement(monitor, "CONDITION_DISCLOSURE")
                .matches(KnowledgeQueryConcepts.detect("显示器坏点描述写法：注明暗点位置并提供纯色背景照片。")));
        assertTrue(requirement(keyboard, "CONDITION_DISCLOSURE")
                .matches(KnowledgeQueryConcepts.detect("键盘按键掉色成色判定：单独披露键帽磨损和输入功能。")));
        assertTrue(requirement(packaging, "ACCESSORY_COMPLETENESS")
                .matches(KnowledgeQueryConcepts.detect("包装缺失商品描述写法：注明无原盒并列出实际配件清单。")));
    }

    @Test
    void productPricingRequiresProductFactorEvidenceNotSeasonalityAlone() {
        KnowledgeEvidencePlanner.Plan plan = KnowledgeEvidencePlanner.plan(
                "游戏本电池循环未知，还要求按同款均价直接报价，需要哪些依据？");
        KnowledgeEvidencePlanner.Requirement pricing = requirement(plan, "PRICING_FACTORS");

        assertTrue(pricing.matches(KnowledgeQueryConcepts.detect(
                "MacBook 估价规则：笔记本估价需结合配置、电池循环和维修史。")));
        assertTrue(!pricing.matches(KnowledgeQueryConcepts.detect(
                "热门游戏本淡旺季价差规则：开学季应参考近期成交。")));
    }

    @Test
    void requiresPriceBoundaryWhenPricingEvidenceIsExplicitlyMissing() {
        for (String query : List.of(
                "手机电池健康和屏幕维修史不知道，能直接估价吗？",
                "笔记本缺内存、硬盘和维修资料，能报精确价格吗？",
                "笔记本没电池、维修和配件数据，应先补哪些字段再估价？")) {
            KnowledgeEvidencePlanner.Plan plan = KnowledgeEvidencePlanner.plan(query);
            assertTrue(requirementIds(plan).containsAll(Set.of("PRICING_FACTORS", "PRICE_BOUNDARY")),
                    () -> "query=" + query + ", requirements=" + requirementIds(plan));
        }

        KnowledgeEvidencePlanner.Plan evidenceOnly = KnowledgeEvidencePlanner.plan(
                "相机传感器有灰点、快门次数未知，还少充电器，折价依据需要哪几类证据？");
        assertTrue(!requirementIds(evidenceOnly).contains("PRICE_BOUNDARY"),
                () -> "requirements=" + requirementIds(evidenceOnly));
    }

    @Test
    void separatesCameraConditionFromGenericMissingInformation() {
        KnowledgeEvidencePlanner.Plan plan = KnowledgeEvidencePlanner.plan(
                "相机传感器有灰点且快门次数未知，风险说明需要哪些证据？");
        KnowledgeEvidencePlanner.Requirement condition = requirement(plan, "CONDITION_DISCLOSURE");

        Set<String> cameraConditionEvidence = KnowledgeQueryConcepts.detect(
                "相机镜头轻微灰尘：描述中说明灰尘位置并提供实拍样张。");
        assertTrue(condition.matches(cameraConditionEvidence),
                () -> "requirement=" + condition + ", evidence=" + cameraConditionEvidence);
        assertTrue(!condition.matches(KnowledgeQueryConcepts.detect(
                "模糊价格咨询拒答：缺少成色和快门次数时应补充关键参数。")));
    }

    private KnowledgeEvidencePlanner.Requirement requirement(KnowledgeEvidencePlanner.Plan plan, String id) {
        return plan.requirements().stream()
                .filter(value -> id.equals(value.id()))
                .findFirst()
                .orElseThrow();
    }

    private Set<String> requirementIds(KnowledgeEvidencePlanner.Plan plan) {
        return plan.requirements().stream()
                .map(KnowledgeEvidencePlanner.Requirement::id)
                .collect(java.util.stream.Collectors.toSet());
    }
}
