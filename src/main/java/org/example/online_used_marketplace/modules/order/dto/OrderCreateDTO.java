package org.example.online_used_marketplace.modules.order.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class OrderCreateDTO {

    @NotNull(message = "商品ID不能为空")
    private Long productId;

    @Size(max = 255, message = "备注长度不能超过255")
    private String remark;
}
