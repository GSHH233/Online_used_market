package org.example.online_used_marketplace.modules.ai.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.example.online_used_marketplace.modules.ai.enums.RetrievalMode;

/**
 * 知识检索请求
 */
@Data
public class KnowledgeSearchRequestDTO {

    @NotBlank(message = "检索问题不能为空")
    @Size(max = 500, message = "检索问题长度不能超过500个字符")
    private String question;

    @NotNull(message = "topK不能为空")
    @Min(value = 1, message = "topK最小为1")
    @Max(value = 20, message = "topK最大为20")
    private Integer topK = 5;

    @Valid
    private KnowledgeSearchFilterDTO filter;

    private RetrievalMode mode = RetrievalMode.HYBRID;
}
