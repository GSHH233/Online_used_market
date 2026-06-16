package org.example.online_used_marketplace.modules.product.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ProductQueryDTO {

    /**
     * 关键词（匹配标题）
     */
    private String keyword;

    /**
     * 分类ID
     */
    private Long categoryId;

    /**
     * 最低价格
     */
    private BigDecimal minPrice;

    /**
     * 最高价格
     */
    private BigDecimal maxPrice;

    /**
     * 商品状态：默认在售(1)
     */
    private Integer status = 1;

    /**
     * 当前页
     */
    @Min(value = 1, message = "页码最小为1")
    private Long pageNum = 1L;

    /**
     * 每页数量
     */
    @Min(value = 1, message = "每页数量最小为1")
    @Max(value = 50, message = "每页数量最大为50")
    private Long pageSize = 10L;

    /**
     * 排序字段：publishTime / price / createdTime
     */
    private String sortBy = "publishTime";

    /**
     * 排序方式：asc / desc
     */
    private String sortDirection = "desc";
}
