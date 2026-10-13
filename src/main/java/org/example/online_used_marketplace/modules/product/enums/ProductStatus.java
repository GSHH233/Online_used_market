package org.example.online_used_marketplace.modules.product.enums;

public enum ProductStatus {
    ON_SALE(1),
    LOCKED(2),
    SOLD(3);

    private final Integer code;

    ProductStatus(Integer code) {
        this.code = code;
    }

    public Integer getCode() {
        return code;
    }
}
