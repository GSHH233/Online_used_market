package org.example.online_used_marketplace.modules.ai.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.example.online_used_marketplace.modules.ai.enums.KnowledgeSourceType;

import java.util.List;

/**
 * RAG问答请求
 */
@Data
public class RagChatRequestDTO {

    @NotBlank(message = "问题不能为空")
    @Size(max = 500, message = "问题长度不能超过500个字符")
    private String question;

    @Min(value = 1, message = "topK最小为1")
    @Max(value = 20, message = "topK最大为20")
    private Integer topK = 5;

    private List<KnowledgeSourceType> sourceTypes;

    @Size(max = 200, message = "分类名称不能超过200个字符")
    private String categoryName;
}
