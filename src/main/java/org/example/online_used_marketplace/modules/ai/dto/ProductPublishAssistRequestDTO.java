package org.example.online_used_marketplace.modules.ai.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * @author Admin
 * @ClassName: ProductPublishAssistRequestDTO
 * @Description: TODO(这里用一句话描述这个类的作用)
 * @date 2026-04-21  19:32
 * @Version:
 */
@Data
public class ProductPublishAssistRequestDTO {
    @NotBlank(message = "标题不能为空")
    @Size(min = 3, max = 80, message = "标题长度需在3-80之间")
    private String title;

    @NotBlank(message = "描述不能为空")
    @Size(min = 10, max = 2000, message = "描述长度需在10-2000之间")
    private String description;

    @Size(max = 40, message = "品牌长度不能超过40")
    private String brand;

    @Size(max = 30, message = "成色描述长度不能超过30")
    private String conditionText;

    @Size(max = 30, message = "分类名长度不能超过30")
    private String categoryName;

    @DecimalMin(value = "0.0", inclusive = true, message = "期望价格不能小于0")
    private BigDecimal expectedPrice;
}
