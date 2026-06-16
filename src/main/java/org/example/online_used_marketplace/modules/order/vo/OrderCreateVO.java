package org.example.online_used_marketplace.modules.order.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderCreateVO {

    private Long orderId;

    private String orderNo;

    private Integer status;
}
