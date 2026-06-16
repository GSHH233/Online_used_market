package org.example.online_used_marketplace.modules.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class MemoryGraphExtractRequestDTO {

    @NotBlank(message = "userId不能为空")
    private String userId;

    private String sessionId;

    @NotBlank(message = "content不能为空")
    @Size(max = 5000, message = "content长度不能超过5000")
    private String content;

    private String source = "debug";
}
