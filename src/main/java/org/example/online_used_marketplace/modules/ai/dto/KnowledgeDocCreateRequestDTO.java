package org.example.online_used_marketplace.modules.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.example.online_used_marketplace.modules.ai.enums.KnowledgeSourceType;

import java.time.LocalDateTime;

/**
 * 创建知识文档请求
 */
@Data
public class KnowledgeDocCreateRequestDTO {

    @NotBlank(message = "文档标题不能为空")
    @Size(max = 120, message = "文档标题不能超过120个字符")
    private String title;

    @NotBlank(message = "文档内容不能为空")
    @Size(max = 20000, message = "文档内容不能超过20000个字符")
    private String content;

    @NotNull(message = "知识来源类型不能为空")
    private KnowledgeSourceType sourceType;

    @Size(max = 500, message = "来源地址不能超过500个字符")
    private String sourceUri;

    @Size(max = 500, message = "标签不能超过500个字符")
    private String tags;

    private String sourceTrustLevel = "MEDIUM";

    private LocalDateTime effectiveFrom;

    private LocalDateTime effectiveTo;

    private String docVersion = "v1";
}
