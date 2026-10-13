package org.example.online_used_marketplace.modules.product.event;

import org.example.online_used_marketplace.modules.product.dto.ProductCreateDTO;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public record ProductPublishedEvent(
        Long productId,
        Long sellerId,
        String title,
        String description,
        BigDecimal price,
        BigDecimal originalPrice,
        Integer conditionLevel,
        Long categoryId,
        String locationText,
        List<String> imageUrls,
        String assistId,
        String assistAdoptionType,
        List<String> assistAdoptedFields) {

    public ProductPublishedEvent {
        imageUrls = imageUrls == null ? List.of() : List.copyOf(imageUrls);
        assistAdoptedFields = assistAdoptedFields == null ? List.of() : List.copyOf(assistAdoptedFields);
    }

    public static ProductPublishedEvent snapshot(Long productId, Long sellerId, ProductCreateDTO product) {
        return new ProductPublishedEvent(productId, sellerId, product.getTitle(), product.getDescription(),
                product.getPrice(), product.getOriginalPrice(), product.getConditionLevel(), product.getCategoryId(),
                product.getLocationText(), product.getImageUrls(), product.getAssistId(),
                product.getAssistAdoptionType(), product.getAssistAdoptedFields());
    }

    public ProductCreateDTO toProductCreateDTO() {
        ProductCreateDTO product = new ProductCreateDTO();
        product.setTitle(title);
        product.setDescription(description);
        product.setPrice(price);
        product.setOriginalPrice(originalPrice);
        product.setConditionLevel(conditionLevel);
        product.setCategoryId(categoryId);
        product.setLocationText(locationText);
        product.setImageUrls(new ArrayList<>(imageUrls));
        product.setAssistId(assistId);
        product.setAssistAdoptionType(assistAdoptionType);
        product.setAssistAdoptedFields(new ArrayList<>(assistAdoptedFields));
        return product;
    }
}
