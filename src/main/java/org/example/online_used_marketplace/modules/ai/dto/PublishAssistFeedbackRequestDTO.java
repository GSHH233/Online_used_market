package org.example.online_used_marketplace.modules.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class PublishAssistFeedbackRequestDTO {

    @NotBlank(message = "adoptionType不能为空")
    private String adoptionType;

    private List<String> adoptedFields;

    private Long productId;

    @Size(max = 500, message = "反馈原因不能超过500字")
    private String reason;
}
