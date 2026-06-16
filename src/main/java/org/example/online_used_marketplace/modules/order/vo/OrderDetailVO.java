package org.example.online_used_marketplace.modules.order.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class OrderDetailVO {

    private Long id;

    private String orderNo;

    private Long buyerId;

    private Long sellerId;

    private Long productId;

    private BigDecimal orderAmount;

    private Integer status;

    private String remark;

    private LocalDateTime payTime;

    private LocalDateTime deliverTime;

    private LocalDateTime finishTime;

    private LocalDateTime cancelTime;

    private LocalDateTime createdTime;

    private String productTitle;

    private String productMainImage;

    private BigDecimal productPrice;

    private Integer conditionLevel;
}
