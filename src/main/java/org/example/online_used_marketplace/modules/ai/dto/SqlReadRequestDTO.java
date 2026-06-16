package org.example.online_used_marketplace.modules.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * MySQL 只读工具请求
 */
@Data
public class SqlReadRequestDTO {

    @NotBlank(message = "SQL不能为空")
    @Size(max = 2000, message = "SQL长度不能超过2000")
    private String sql;

    /**
     * 最大返回行数，默认100，最大500
     */
    private Integer limit;

    /**
     * USER / INTERNAL. Direct database read is only allowed in INTERNAL mode.
     */
    private String mode = "USER";
}
