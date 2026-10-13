package org.example.online_used_marketplace.modules.product.service;

import org.example.online_used_marketplace.modules.product.dto.ProductCreateDTO;

@FunctionalInterface
public interface ProductPublicationValidator {

    // Business validation must finish before any product rows are inserted.
    void validate(ProductCreateDTO product, Long currentUserId);
}
