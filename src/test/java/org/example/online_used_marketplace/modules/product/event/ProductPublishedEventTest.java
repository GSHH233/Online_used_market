package org.example.online_used_marketplace.modules.product.event;

import org.example.online_used_marketplace.modules.product.dto.ProductCreateDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductPublishedEventTest {

    @Test
    void snapshotDoesNotRetainTheDtoOrEitherMutableList() {
        ProductCreateDTO product = new ProductCreateDTO();
        product.setTitle("Original title");
        product.setDescription("Original description");
        product.setPrice(new BigDecimal("19.99"));
        product.setOriginalPrice(new BigDecimal("39.99"));
        product.setConditionLevel(8);
        product.setCategoryId(3L);
        product.setLocationText("Campus");
        product.setImageUrls(new ArrayList<>(List.of("image-1")));
        product.setAssistId("assist-1");
        product.setAssistAdoptionType("PARTIAL");
        product.setAssistAdoptedFields(new ArrayList<>(List.of("title")));

        ProductPublishedEvent event = ProductPublishedEvent.snapshot(42L, 7L, product);
        product.setTitle("Changed title");
        product.setPrice(BigDecimal.ONE);
        product.setAssistId("changed-assist");
        product.getImageUrls().clear();
        product.getAssistAdoptedFields().add("description");

        assertEquals(42L, event.productId());
        assertEquals(7L, event.sellerId());
        assertEquals("Original title", event.title());
        assertEquals(new BigDecimal("19.99"), event.price());
        assertEquals("assist-1", event.assistId());
        assertEquals(List.of("image-1"), event.imageUrls());
        assertEquals(List.of("title"), event.assistAdoptedFields());
        assertThrows(UnsupportedOperationException.class, () -> event.imageUrls().add("image-2"));
        assertThrows(UnsupportedOperationException.class, () -> event.assistAdoptedFields().clear());

        ProductCreateDTO copy = event.toProductCreateDTO();
        assertNotSame(product, copy);
        assertEquals(event.title(), copy.getTitle());
        assertEquals(event.description(), copy.getDescription());
        assertEquals(event.price(), copy.getPrice());
        assertEquals(event.originalPrice(), copy.getOriginalPrice());
        assertEquals(event.conditionLevel(), copy.getConditionLevel());
        assertEquals(event.categoryId(), copy.getCategoryId());
        assertEquals(event.locationText(), copy.getLocationText());
        assertEquals(event.assistId(), copy.getAssistId());
        assertEquals(event.assistAdoptionType(), copy.getAssistAdoptionType());
        copy.setTitle("Listener mutation");
        copy.getImageUrls().clear();
        copy.getAssistAdoptedFields().clear();
        ProductCreateDTO secondCopy = event.toProductCreateDTO();
        assertNotSame(copy, secondCopy);
        assertEquals("Original title", secondCopy.getTitle());
        assertEquals(List.of("image-1"), secondCopy.getImageUrls());
        assertEquals(List.of("title"), secondCopy.getAssistAdoptedFields());
    }
}
