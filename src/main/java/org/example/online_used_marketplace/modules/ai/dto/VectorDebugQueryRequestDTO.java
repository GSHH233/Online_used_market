package org.example.online_used_marketplace.modules.ai.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 向量检索调试请求
 */
@Data
public class VectorDebugQueryRequestDTO {

    @NotBlank(message = "问题不能为空")
    private String question;

    @Min(value = 1, message = "topK最小为1")
    @Max(value = 20, message = "topK最大为20")
    private Integer topK = 5;

    private KnowledgeSearchFilterDTO filter;

    /**
     * USER / INTERNAL. Direct vector debug is only allowed in INTERNAL mode.
     */
    private String mode = "USER";
}
