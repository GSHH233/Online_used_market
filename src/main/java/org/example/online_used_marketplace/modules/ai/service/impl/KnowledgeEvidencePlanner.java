package org.example.online_used_marketplace.modules.ai.service.impl;

import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Converts user language into stable business intents and an explicit evidence plan.
 */
final class KnowledgeEvidencePlanner {

    private KnowledgeEvidencePlanner() {
    }

    static Plan plan(String query) {
        String raw = StringUtils.hasText(query) ? query.toLowerCase(Locale.ROOT) : "";
        String compact = raw.replaceAll("[\\p{P}\\p{S}\\s]+", "");
        Set<String> concepts = new LinkedHashSet<>(KnowledgeQueryConcepts.detect(raw));
        normalizeTransactionIntent(compact, concepts);
        normalizePricingIntent(compact, concepts);
        normalizeConditionIntent(compact, concepts);
        normalizeAccessoryIntent(compact, concepts);
        normalizeGroundingIntent(compact, concepts);
        normalizePolicyVersionIntent(compact, concepts);
        normalizeCompositeEvidence(concepts);

        Map<String, Requirement> requirements = new LinkedHashMap<>();
        addConceptRequirement(requirements, concepts, "@off_platform",
                requirement("TRANSACTION_CHANNEL", 3.0D, "@off_platform"));
        addConceptRequirement(requirements, concepts, "@account",
                requirement("ACCOUNT_OWNERSHIP", 3.0D, "@account"));
        addConceptRequirement(requirements, concepts, "@sim_card",
                requirement("SIM_CARD_COMPLIANCE", 3.0D, "@sim_card"));

        addConceptRequirement(requirements, concepts, "@phone",
                requirement("PRODUCT_PHONE", 1.5D, "@phone"));
        addConceptRequirement(requirements, concepts, "@gpu",
                requirement("PRODUCT_GPU", 1.5D, "@gpu"));
        addConceptRequirement(requirements, concepts, "@laptop",
                requirement("PRODUCT_LAPTOP", 1.5D, "@laptop"));
        addConceptRequirement(requirements, concepts, "@camera",
                requirement("PRODUCT_CAMERA", 1.5D, "@camera"));
        addConceptRequirement(requirements, concepts, "@drone",
                requirement("PRODUCT_DRONE", 1.5D, "@drone"));

        addConceptRequirement(requirements, concepts, "@condition",
                conditionRequirement(concepts));
        addConceptRequirement(requirements, concepts, "@repair",
                requirement("REPAIR_HISTORY", 2.0D, "@repair"));
        addConceptRequirement(requirements, concepts, "@usage_history",
                requirement("USAGE_HISTORY", 2.5D, "@usage_history"));
        if (concepts.contains("@accessory") && containsAny(compact,
                "缺少原装", "缺配件", "缺电池", "没有原装", "没有配件", "没有包装", "没有充电",
                "没有附件", "没有电池", "没带", "无原", "只有机身", "仅机身", "仅主机",
                "附件", "配件不全", "配件缺失", "缺包装", "无包装", "缺保修", "无保修",
                "保修缺失", "箱说不全", "缺盒", "缺线", "缺说明书", "替代成本", "丢失", "丢了",
                "少充电器", "少配件", "不带充电器", "不是原装", "非原装", "配件不齐", "没有箱说",
                "无箱说", "缺一块原装电池", "少一块原装电池")) {
            requirements.put("ACCESSORY_COMPLETENESS",
                    concepts.contains("@packaging_missing")
                            ? requirement("ACCESSORY_COMPLETENESS", 2.0D, "@packaging_missing")
                            : concepts.contains("@accessory_pricing")
                            ? requirement("ACCESSORY_COMPLETENESS", 2.0D, "@accessory_pricing")
                            : requirement("ACCESSORY_COMPLETENESS", 2.0D, "@accessory"));
        }
        if (concepts.contains("@evidence")
                && (concepts.contains("@phone") || concepts.contains("@authenticity"))) {
            requirements.put("PROVENANCE", requirement("PROVENANCE", 2.5D, "@evidence"));
        }

        addConceptRequirement(requirements, concepts, "@price",
                pricingRequirement(concepts));
        addConceptRequirement(requirements, concepts, "@market_evidence",
                requirement("MARKET_EVIDENCE", 2.5D, "@market_evidence"));
        addConceptRequirement(requirements, concepts, "@price_boundary",
                requirement("PRICE_BOUNDARY", 3.0D, "@price_boundary"));
        addConceptRequirement(requirements, concepts, "@condition_price_gap",
                requirement("CONDITION_PRICE_GAP", 2.5D, "@condition_price_gap"));
        addConceptRequirement(requirements, concepts, "@citation",
                requirement("SOURCE_CITATION", 3.0D, "@citation"));
        addConceptRequirement(requirements, concepts, "@listing_identity",
                requirement("LISTING_IDENTITY", 3.0D, "@listing_identity"));
        addConceptRequirement(requirements, concepts, "@listing_permission",
                requirement("LISTING_PERMISSION_BOUNDARY", 3.0D, "@listing_permission"));
        boolean knownProduct = concepts.stream().anyMatch(KnowledgeEvidencePlanner::isProductConcept);
        boolean genericGrounding = concepts.contains("@insufficient")
                && !knownProduct
                && !concepts.contains("@listing_identity")
                && !concepts.contains("@price");
        if (concepts.contains("@policy_grounding") || genericGrounding) {
            requirements.put("GROUNDING_BOUNDARY",
                    requirement("GROUNDING_BOUNDARY", 3.0D, "@policy_grounding", "@insufficient"));
        }

        return new Plan(
                Collections.unmodifiableSet(new LinkedHashSet<>(concepts)),
                List.copyOf(requirements.values()),
                KnowledgeQueryConcepts.keywordExpansions(concepts));
    }

    private static void normalizeTransactionIntent(String text, Set<String> concepts) {
        boolean platformAnchor = containsAny(text,
                "平台", "订单", "链接", "站内", "线上", "担保", "交易凭据");
        boolean payment = concepts.contains("@payment_safety") || containsAny(text,
                "付款", "货款", "付现", "现金", "汇款", "打款", "结算", "补款", "尾款",
                "差价", "押金", "保证金", "银行卡", "个人卡", "个人账户", "二维码", "红包", "pos机");
        boolean externalChannel = containsAny(text,
                "现金", "付现", "汇款", "银行卡", "个人卡", "个人账户", "二维码", "红包", "pos机",
                "线下", "站外", "私下", "离开站内", "号码", "电话", "当面", "见面", "另行结算");
        boolean splitSettlement = containsAny(text,
                "尾款", "差价", "补款", "补偿款", "押金", "保证金", "运费", "寄送费用", "部分",
                "剩余", "另行", "小定", "小额", "免费赠送", "空盒", "置换");
        boolean contactExit = containsAny(text, "号码", "电话", "语音", "当面", "见面", "离开站内")
                && containsAny(text, "联系", "沟通", "交接", "换绑", "付款", "交易", "结算");

        if ((payment && externalChannel)
                || (payment && platformAnchor && splitSettlement)
                || contactExit) {
            concepts.add("@payment_safety");
            concepts.add("@off_platform");
        }
        boolean accountAnchor = containsAny(text,
                "账号", "账户", "角色转让", "换绑", "原持有人", "找回", "申诉", "封禁",
                "本人使用", "所有权", "充值凭证");
        boolean accountIdentity = containsAny(text, "实名", "绑定")
                && containsAny(text, "账号", "账户", "持有人", "换绑", "找回");
        if (accountAnchor || accountIdentity) {
            concepts.add("@account");
        }
    }

    private static void normalizePricingIntent(String text, Set<String> concepts) {
        boolean pricing = concepts.contains("@price") || containsAny(text,
                "报价", "估值", "估价", "售价", "减价", "涨幅", "折算", "回收价", "成交金额",
                "最低成交", "成交承诺", "价格边界", "折价", "固定金额", "下调", "降价", "调价",
                "固定减", "固定扣", "统一减");
        if (pricing) {
            concepts.add("@price");
        }
        if (pricing && containsAny(text,
                "固定", "统一", "相同减价", "直接换算", "乘固定系数", "一律", "精确到元",
                "最低成交金额", "最低成交承诺", "成交承诺", "预测最低", "确定报价", "精确报价",
                "同一百分比", "相同百分比", "同一比例", "价格边界", "固定金额", "统一下调")) {
            concepts.add("@price_boundary");
            concepts.add("@quantified_claim");
        }
        boolean missingPricingEvidence = concepts.contains("@insufficient")
                || (containsAny(text, "没", "缺", "无", "未知", "不清楚", "不知道")
                && containsAny(text,
                "电池", "维修", "配置", "内存", "硬盘", "配件", "成色", "外观",
                "快门", "数据", "资料", "信息", "成交"));
        boolean unsupportedPriceDecision = containsAny(text,
                "直接估价", "直接报价", "直接定价", "精确价格", "精确报价", "确定价格", "确定报价")
                || (containsAny(text, "先补", "补哪些", "补充哪些", "补齐")
                && containsAny(text, "再估价", "再报价", "再定价"));
        if (pricing && missingPricingEvidence && unsupportedPriceDecision) {
            concepts.add("@insufficient");
            concepts.add("@price_boundary");
        }
        if (containsAny(text,
                "实际成交", "成交记录", "成交资料", "回收网站", "在售价", "市场样本", "成交样本")) {
            concepts.add("@market_evidence");
        }
    }

    private static void normalizeConditionIntent(String text, Set<String> concepts) {
        boolean conditionSignal = concepts.contains("@condition") || containsAny(text,
                "磕碰", "凹陷", "裂纹", "裂痕", "亮斑", "亮点", "暗点", "坏点", "掉色", "褪色",
                "打油", "接触不良", "耗电异常", "耗电明显", "续航异常", "破损", "变形", "漏液");
        if (!conditionSignal) {
            return;
        }
        concepts.add("@condition");
        if (containsAny(text,
                "标题", "正文", "详情", "发布", "上架", "描述", "说明", "写", "披露", "省略", "隐瞒",
                "充分", "完整", "近照", "实拍", "标成色", "成色标注")) {
            concepts.add("@disclosure");
        }
        if (concepts.contains("@camera") && containsAny(text,
                "镜头灰尘", "镜头有灰", "镜头霉", "传感器灰点", "传感器有灰点",
                "传感器污点", "传感器有污点", "影响成像", "实拍样张")) {
            concepts.add("@camera_condition");
        }
        boolean hasConditionQualifier = concepts.stream().anyMatch(KnowledgeEvidencePlanner::isConditionQualifier);
        boolean hasProductQualifier = concepts.stream().anyMatch(KnowledgeEvidencePlanner::isProductConcept);
        if (!hasConditionQualifier && !hasProductQualifier) {
            concepts.add("@condition_general");
        }
    }

    private static void normalizeAccessoryIntent(String text, Set<String> concepts) {
        boolean missingOrReplacement = containsAny(text,
                "丢失原盒", "原盒丢失", "原盒已经丢失", "原盒丢了", "少充电器", "少配件", "缺充电器",
                "不带充电器", "不是原装", "非原装", "配件不齐", "充电线", "缺包装", "无原盒");
        if (missingOrReplacement) {
            concepts.add("@accessory");
        }
        if (containsAny(text, "缺一块原装电池", "少一块原装电池", "没有箱说", "无箱说")) {
            concepts.add("@accessory");
        }
        if (containsAny(text,
                "包装缺失", "无原盒", "没原盒", "丢失原盒", "原盒丢失", "原盒已经丢失", "原盒丢了",
                "实际配件清单", "误解为全套", "误认为全套")) {
            concepts.add("@packaging_missing");
        }
    }

    private static void normalizeGroundingIntent(String text, Set<String> concepts) {
        if (containsAny(text,
                "知识范围之外", "没有对应条款", "没有任何上下文", "没有商品上下文", "没有任何商品上下文", "身份无法确定",
                "全部未知", "已过期", "过期的旧规则", "无法确认", "无法确定", "没有用途", "没有品名")) {
            concepts.add("@insufficient");
        }
        if (containsAny(text,
                "没有任何商品上下文", "没有商品上下文", "身份无法确定", "身份都无法确定",
                "没有用途", "没有品名", "没有商品名称", "只给出电子配件", "只有品类")) {
            concepts.add("@listing_identity");
            concepts.add("@listing_permission");
        }
        if (containsAny(text,
                "能不能发", "能否发布", "允许发布", "允许出售", "能不能卖", "是否准售", "确认是否准售",
                "判断准售", "准售结论", "确定的准售")) {
            concepts.add("@listing_permission");
        }
        if (containsAny(text,
                "根据其他平台", "推测本平台", "沿用旧结论", "知识范围之外", "没有对应条款",
                "从未出现", "外部常识", "借用常识", "知识未覆盖", "知识中没有", "新设备")) {
            concepts.add("@policy_grounding");
            concepts.add("@insufficient");
        }
        if (containsAny(text,
                "规则不一致", "结论不一致", "相反要求", "命中依据", "相关原文", "文档版本", "省略来源",
                "无法指出", "引用", "哪几类信息", "哪些事实与来源", "可追溯依据", "追溯依据",
                "引用证据", "展示证据", "有效性", "新旧规范", "新旧规则", "未标来源", "没有标来源",
                "来源不明", "无来源", "未注明出处", "没有注明出处", "哪段原文")) {
            concepts.add("@citation");
        }
    }

    private static void normalizePolicyVersionIntent(String text, Set<String> concepts) {
        boolean policyVersionComparison = containsAny(text,
                "新旧规范", "新旧规则", "新旧版本", "规范版本", "规则版本");
        if (!policyVersionComparison) {
            return;
        }
        concepts.add("@citation");
        boolean hasProductConditionSignal = containsAny(text,
                "成色", "划痕", "磨损", "外观", "霉斑", "灰点", "磕碰", "瑕疵");
        if (!hasProductConditionSignal) {
            concepts.remove("@condition");
        }
    }

    private static void normalizeCompositeEvidence(Set<String> concepts) {
        if (concepts.contains("@accessory") && concepts.contains("@price")) {
            concepts.add("@accessory_pricing");
        }
        if (concepts.contains("@condition")
                && concepts.contains("@price")
                && (concepts.contains("@price_boundary") || concepts.contains("@quantified_claim"))) {
            concepts.add("@condition_price_gap");
        }
    }

    private static Requirement conditionRequirement(Set<String> concepts) {
        List<String> productQualifiers = concepts.stream()
                .filter(KnowledgeEvidencePlanner::isProductConcept)
                .toList();
        List<String> required = new java.util.ArrayList<>(productQualifiers);
        for (String qualifier : List.of(
                "@camera_condition", "@monitor_condition", "@keyboard_condition",
                "@earphone_condition", "@watch_condition")) {
            if (concepts.contains(qualifier)) {
                required.add(qualifier);
            }
        }
        required.add("@disclosure");
        String accepted = conditionEvidenceConcept(concepts);
        return qualifiedRequirement("CONDITION_DISCLOSURE", 2.0D, required, accepted);
    }

    private static Requirement pricingRequirement(Set<String> concepts) {
        List<String> productQualifiers = concepts.stream()
                .filter(KnowledgeEvidencePlanner::isProductConcept)
                .toList();
        if (productQualifiers.isEmpty()) {
            return requirement("PRICING_FACTORS", 1.5D, "@pricing_factors");
        }
        return qualifiedRequirement("PRICING_FACTORS", 1.5D, productQualifiers, "@pricing_factors");
    }

    private static String conditionEvidenceConcept(Set<String> concepts) {
        for (String concept : List.of(
                "@physical_damage", "@camera_condition", "@monitor_condition", "@keyboard_condition",
                "@earphone_condition", "@watch_condition", "@condition_general")) {
            if (concepts.contains(concept)) {
                return concept;
            }
        }
        return "@condition";
    }

    private static boolean isConditionQualifier(String concept) {
        return Set.of("@camera_condition", "@physical_damage", "@monitor_condition", "@keyboard_condition",
                "@earphone_condition", "@watch_condition", "@condition_general").contains(concept);
    }

    private static boolean isProductConcept(String concept) {
        return Set.of("@phone", "@gpu", "@laptop", "@camera", "@drone").contains(concept);
    }

    private static void addConceptRequirement(Map<String, Requirement> requirements,
                                              Set<String> concepts,
                                              String concept,
                                              Requirement requirement) {
        if (concepts.contains(concept)) {
            requirements.put(requirement.id(), requirement);
        }
    }

    private static Requirement requirement(String id, double weight, String... acceptedConcepts) {
        return new Requirement(id, List.of(acceptedConcepts), List.of(), weight);
    }

    private static Requirement qualifiedRequirement(String id,
                                                    double weight,
                                                    List<String> requiredConcepts,
                                                    String... acceptedConcepts) {
        return new Requirement(id, List.of(acceptedConcepts), List.copyOf(requiredConcepts), weight);
    }

    private static boolean containsAny(String value, String... terms) {
        for (String term : terms) {
            if (value.contains(term)) {
                return true;
            }
        }
        return false;
    }

    record Plan(Set<String> normalizedConcepts,
                List<Requirement> requirements,
                List<String> keywordExpansions) {
        boolean multiHop() {
            return requirements.size() >= 2;
        }

        Set<String> productConcepts() {
            Set<String> products = new LinkedHashSet<>();
            normalizedConcepts.stream()
                    .filter(KnowledgeEvidencePlanner::isProductConcept)
                    .forEach(products::add);
            return products;
        }
    }

    record Requirement(String id,
                       List<String> acceptedConcepts,
                       List<String> requiredConcepts,
                       double weight) {
        boolean matches(Set<String> candidateConcepts) {
            boolean accepted = acceptedConcepts.isEmpty()
                    || acceptedConcepts.stream().anyMatch(candidateConcepts::contains);
            return accepted && candidateConcepts.containsAll(requiredConcepts);
        }

        Set<String> searchConcepts() {
            Set<String> concepts = new LinkedHashSet<>(acceptedConcepts);
            concepts.addAll(requiredConcepts);
            return concepts;
        }
    }
}
