package org.example.online_used_marketplace.modules.product.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class ProductCreateDTO {

    @NotBlank(message = "商品标题不能为空")
    @Size(max = 120, message = "商品标题长度不能超过120")
    private String title;

    @Size(max = 5000, message = "商品描述过长")
    private String description;

    @NotNull(message = "商品价格不能为空")
    @DecimalMin(value = "0.01", message = "商品价格必须大于0")
    private BigDecimal price;

    @DecimalMin(value = "0.00", message = "原价不能小于0")
    private BigDecimal originalPrice;

    @NotNull(message = "成色不能为空")
    @Min(value = 1, message = "成色最小为1")
    @Max(value = 10, message = "成色最大为10")
    private Integer conditionLevel;

    private Long categoryId;

    @Size(max = 64, message = "交易地点长度不能超过64")
    private String locationText;

    @NotEmpty(message = "商品图片至少上传一张")
    private List<@NotBlank(message = "图片地址不能为空") String> imageUrls;

    @Size(max = 64, message = "assistId长度不能超过64")
    private String assistId;

    private String assistAdoptionType;

    private List<String> assistAdoptedFields;
}
