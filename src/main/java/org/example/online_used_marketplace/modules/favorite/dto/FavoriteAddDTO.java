package org.example.online_used_marketplace.modules.favorite.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class FavoriteAddDTO {

    @NotNull(message = "商品ID不能为空")
    private Long productId;
}
