package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;
import org.example.online_used_marketplace.modules.ai.enums.RiskLevel;

/**
 * @author Admin
 * @ClassName: RiskEvidenceItemVO
 * @Description: TODO(这里用一句话描述这个类的作用)
 * @date 2026-04-23  14:58
 * @Version:
 */
@Data
public class RiskEvidenceItemVO {
    private String ruleCode;
    private String hitText;
    private String sourceField;
    private RiskLevel riskLevel;
    private Integer deductScore;
    private String message;
}
