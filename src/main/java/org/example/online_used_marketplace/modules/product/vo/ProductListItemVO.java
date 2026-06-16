package org.example.online_used_marketplace.modules.product.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class ProductListItemVO {

    private Long id;

    private Long sellerId;

    private Long categoryId;

    private String title;

    private BigDecimal price;

    private Integer conditionLevel;

    private String locationText;

    private Integer viewCount;

    private Integer favoriteCount;

    private Integer status;

    private LocalDateTime publishTime;

    /**
     * 主图
     */
    private String mainImageUrl;
}
