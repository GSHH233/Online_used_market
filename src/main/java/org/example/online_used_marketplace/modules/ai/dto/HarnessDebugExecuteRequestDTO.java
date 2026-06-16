package org.example.online_used_marketplace.modules.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class HarnessDebugExecuteRequestDTO {

    @NotBlank(message = "tool不能为空")
    private String tool;

    /**
     * USER / INTERNAL. Debug execution follows the same internal tool boundary.
     */
    private String mode = "INTERNAL";

    private Map<String, Object> input = new LinkedHashMap<>();
}
