package org.example.online_used_marketplace.modules.favorite.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class FavoriteQueryDTO {

    @Min(value = 1, message = "页码最小为1")
    private Long pageNum = 1L;

    @Min(value = 1, message = "每页最小为1")
    @Max(value = 50, message = "每页最大为50")
    private Long pageSize = 10L;
}
