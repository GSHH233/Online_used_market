package org.example.online_used_marketplace.modules.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * @author Admin
 * @ClassName: ProductOptimizeRequestDTO
 * @Description: TODO(这里用一句话描述这个类的作用)
 * @date 2026-04-20  20:21
 * @Version:
 */
@Data
public class ProductOptimizeRequestDTO {
    @NotBlank(message = "标题不能为空")
    private String title;
    @NotBlank(message = "描述不能为空")
    private String description;
    private String brand;
    private String conditionText;
    private String categoryName;

}
