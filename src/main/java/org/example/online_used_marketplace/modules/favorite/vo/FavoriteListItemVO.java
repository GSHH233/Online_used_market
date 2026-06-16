package org.example.online_used_marketplace.modules.favorite.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class FavoriteListItemVO {

    private Long id;

    private Long productId;

    private String productTitle;

    private String productMainImage;

    private BigDecimal price;

    private Integer status;

    private LocalDateTime createdTime;
}
