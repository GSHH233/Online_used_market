package org.example.online_used_marketplace.modules.product.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class ProductDetailVO {

    private Long id;

    private Long sellerId;

    private String sellerNickname;

    private String sellerAvatar;

    private LocalDateTime sellerCreatedTime;

    private Long categoryId;

    private String title;

    private String description;

    private BigDecimal price;

    private BigDecimal originalPrice;

    private Integer conditionLevel;

    private String locationText;

    private Integer viewCount;

    private Integer favoriteCount;

    private Integer status;

    private LocalDateTime publishTime;

    private LocalDateTime createdTime;

    private List<String> imageUrls;
}
