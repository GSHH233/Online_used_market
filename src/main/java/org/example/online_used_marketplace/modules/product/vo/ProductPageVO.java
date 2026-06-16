package org.example.online_used_marketplace.modules.product.vo;

import lombok.Data;

import java.util.List;

@Data
public class ProductPageVO {

    private Long pageNum;

    private Long pageSize;

    private Long total;

    private Long totalPages;

    private List<ProductListItemVO> records;
}
