package org.example.online_used_marketplace.modules.product.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductCreateVO {

    private Long productId;

    private String title;

    private Integer status;
}
