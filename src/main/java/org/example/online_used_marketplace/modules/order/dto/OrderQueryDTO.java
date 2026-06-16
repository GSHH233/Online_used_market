package org.example.online_used_marketplace.modules.order.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.example.online_used_marketplace.modules.order.enums.OrderRoleType;

@Data
public class OrderQueryDTO {

    /**
     * 视角：BUYER / SELLER
     */
    private OrderRoleType role = OrderRoleType.BUYER;

    /**
     * 订单状态，可选
     */
    private Integer status;

    @Min(value = 1, message = "页码最小为1")
    private Long pageNum = 1L;

    @Min(value = 1, message = "每页最小为1")
    @Max(value = 50, message = "每页最大为50")
    private Long pageSize = 10L;
}
