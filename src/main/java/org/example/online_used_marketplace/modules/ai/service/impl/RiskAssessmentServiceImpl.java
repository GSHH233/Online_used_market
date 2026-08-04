package org.example.online_used_marketplace.modules.ai.service.impl;

import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.modules.ai.dto.ProductPublishAssistRequestDTO;
import org.example.online_used_marketplace.modules.ai.enums.RiskLevel;
import org.example.online_used_marketplace.modules.ai.service.RiskAssessmentService;
import org.example.online_used_marketplace.modules.ai.vo.ProductOptimizeResponseVO;
import org.example.online_used_marketplace.modules.ai.vo.RiskEvidenceItemVO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;

/**
 * @author Admin
 * @ClassName: RiskAssessmentServiceImpl
 * @Description: TODO(这里用一句话描述这个类的作用)
 * @date 2026-04-23  14:35
 * @Version:
 */
@Service
@RequiredArgsConstructor
public class RiskAssessmentServiceImpl implements RiskAssessmentService {
    /**
     * Step 3: 规则源（先写死，后续可迁移到 yml / db / milvus）
     */

     @Override
     public List<String> buildRiskWarnings(ProductOptimizeResponseVO optimizeResult, Integer riskScore) {
        List<String> baseWarnings = optimizeResult == null || optimizeResult.getRiskWarnings() == null
                ? Collections.emptyList()
                : optimizeResult.getRiskWarnings();

        List<String> allWarnings = new ArrayList<>(baseWarnings);

        if (riskScore < 60) {
            allWarnings.add("当前文本存在较高交易风险，建议修改后再发布");
        } else if (riskScore < 80) {
            allWarnings.add("存在轻度风险提示，建议保留平台内交易与验货凭证");
        }

        return allWarnings.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .limit(5)
                .toList();
    }

    @Override
    public List<RiskEvidenceItemVO> buildRiskEvidence(ProductPublishAssistRequestDTO request, ProductOptimizeResponseVO optimizeResult) {
        List<RiskEvidenceItemVO> evidences = new ArrayList<>();
        if (request == null) {
            return Collections.emptyList();
        }

        List<String> warnings = optimizeResult == null || optimizeResult.getRiskWarnings() == null
                ? Collections.emptyList()
                : optimizeResult.getRiskWarnings();

        String title = safeText(request.getTitle()).toLowerCase();
        String description = safeText(request.getDescription()).toLowerCase();

        for (RiskRule rule:allRules()){
            String source;
            if ("title".equals(rule.sourceField())){
                source=title;
            } else if ("description".equals(rule.sourceField())) {
                source=description;
            }else {
                source=description;
            }

            if (hasActiveMatch(source, rule)) {
                RiskEvidenceItemVO item=new RiskEvidenceItemVO();
                item.setRiskLevel(rule.deductScore() >= 20 ? RiskLevel.HIGH : RiskLevel.MEDIUM);
                item.setDeductScore(rule.deductScore());
                item.setMessage(rule.message());
                item.setHitText(rule.keyword());
                item.setRuleCode(rule.ruleCode());
                item.setSourceField(rule.sourceField());
                evidences.add(item);
            }
        }
        for (String warning : warnings) {
            RiskEvidenceItemVO vo = new RiskEvidenceItemVO();
            vo.setRuleCode("AI_WARNING");
            vo.setSourceField("aiRiskWarning");
            vo.setDeductScore(3);
            vo.setHitText(warning);
            vo.setMessage(warning);
            vo.setRiskLevel(RiskLevel.LOW);
            evidences.add(vo);
        }
        List<RiskEvidenceItemVO> cleaned=new ArrayList<>();
        Set<String> seen=new HashSet<>();
        for (RiskEvidenceItemVO item:evidences){
            if (item == null || !StringUtils.hasText(item.getHitText()) || !StringUtils.hasText(item.getMessage())) {
                continue;
            }
           String compositeKey=safeText(item.getRuleCode()).toLowerCase()+"|"+
                   safeText(item.getSourceField()).toLowerCase();
            if ("AI_WARNING".equals(item.getRuleCode())) {
                compositeKey += "|" + safeText(item.getHitText()).toLowerCase();
            }
            if (seen.add(compositeKey)){
                cleaned.add(item);
            }
            if (cleaned.size()>=10){
                break;
            }
        }


        return cleaned.isEmpty()?Collections.emptyList():cleaned;
    }

    @Override
    public Integer calculateRiskScoreByEvidence(List<RiskEvidenceItemVO> evidence) {
        if (evidence==null){
            evidence=Collections.emptyList();
        }
        Integer sum=evidence.stream().mapToInt(RiskEvidenceItemVO::getDeductScore).sum();
        Integer score=100-sum;
        return Math.max(0,Math.min(100,score));
    }

    private List<RiskRule> allRules() {
        List<RiskRule> all = new ArrayList<>(HIGH_RISK_RULES.size() + MEDIUM_RISK_RULES.size());
        all.addAll(HIGH_RISK_RULES);
        all.addAll(MEDIUM_RISK_RULES);
        return all;
    }

    private String safeText(String text) {
        return StringUtils.hasText(text) ? text.trim() : "";
    }

    private boolean isNegated(String source, int matchIndex, RiskRule rule) {
        if (!rule.negationAware()) {
            return false;
        }
        int start = Math.max(0, matchIndex - 12);
        String prefix = source.substring(start, matchIndex)
                .replaceAll("[\\s，。；、,:：;\"'“”‘’]", "");
        return NEGATION_PREFIXES.stream().anyMatch(prefix::endsWith);
    }

    private boolean hasActiveMatch(String source, RiskRule rule) {
        String keyword = rule.keyword().toLowerCase();
        int fromIndex = 0;
        while (fromIndex < source.length()) {
            int matchIndex = source.indexOf(keyword, fromIndex);
            if (matchIndex < 0) {
                return false;
            }
            if (!isNegated(source, matchIndex, rule)) {
                return true;
            }
            fromIndex = matchIndex + keyword.length();
        }
        return false;
    }

    private record RiskRule(
            String ruleCode,
            String keyword,
            String sourceField,
            int deductScore,
            String message,
            boolean negationAware
    ) {
    }

    private static RiskRule highRule(String ruleCode, String keyword, String message) {
        return new RiskRule(ruleCode, keyword, "description", 20, message, true);
    }

    private static RiskRule literalHighRule(String ruleCode, String keyword, String message) {
        return new RiskRule(ruleCode, keyword, "description", 20, message, false);
    }

    private static RiskRule mediumTitleRule(String ruleCode, String keyword, String message) {
        return new RiskRule(ruleCode, keyword, "title", 5, message, false);
    }

    private static final List<String> NEGATION_PREFIXES = List.of(
            "不", "不接受", "不支持", "拒绝", "禁止", "严禁", "不得", "不是", "并非", "非",
            "平台规则禁止", "平台明确禁止"
    );

    private static final List<RiskRule> HIGH_RISK_RULES = List.of(
            // 私下交易类
            highRule("HIGH_PRIVATE_TRADE", "私下交易", "疑似引导脱离平台交易"),
            highRule("HIGH_OFFLINE_TRADE", "线下交易", "疑似引导线下交易"),
            highRule("HIGH_BYPASS_PLATFORM", "绕过平台", "疑似绕过平台担保"),

            // 支付风险类
            highRule("HIGH_PREPAY", "先付款", "疑似高风险预付场景"),
            highRule("HIGH_WECHAT_PAY", "微信转账", "疑似私下转账"),
            highRule("HIGH_ALIPAY_PAY", "支付宝转账", "疑似私下转账"),

            // 售后违规类
            literalHighRule("HIGH_NO_AFTER_SALE", "无售后", "售后保障不足"),
            literalHighRule("HIGH_NO_AFTER_SALE", "没有任何售后", "售后保障不足"),
            literalHighRule("HIGH_NO_RETURN", "不退不换", "退换条款风险"),

            // 设备篡改/违规商品
            highRule("HIGH_MODIFIED_IMEI", "改码", "疑似设备标识篡改"),
            highRule("HIGH_STOLEN_DEVICE", "盗抢机", "疑似异常来源设备"),
            highRule("HIGH_LOCKED_DEVICE", "有锁机", "设备可能存在锁定风险"),

            // 违规引流联系方式
            highRule("HIGH_CONTACT_VX", "vx", "疑似引流到站外联系方式"),
            highRule("HIGH_CONTACT_QQ", "qq", "疑似引流到站外联系方式")
    );

    private static final List<RiskRule> MEDIUM_RISK_RULES = List.of(
            mediumTitleRule("MEDIUM_URGENT_SELL", "急出", "紧急售卖话术，建议补充真实性信息"),
            mediumTitleRule("MEDIUM_CLEARANCE", "清仓", "清仓话术，建议补充价格依据"),
            mediumTitleRule("MEDIUM_QUICK_SELL", "秒出", "快速成交话术，建议增强可信描述")
    );
}
