package org.example.online_used_marketplace.modules.order.enums;

public enum OrderStatus {

    PENDING_PAY(0, "待支付"),
    PAID(1, "已支付"),
    DELIVERED(2, "已发货"),
    FINISHED(3, "已完成"),
    CANCELLED(4, "已取消");

    private final Integer code;
    private final String desc;

    OrderStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public Integer getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public boolean isFinalStatus() {
        return this == FINISHED || this == CANCELLED;
    }

    public boolean canTransitionTo(OrderStatus target) {
        return switch (this) {
            case PENDING_PAY -> target == PAID || target == CANCELLED;
            case PAID -> target == DELIVERED;
            case DELIVERED -> target == FINISHED;
            case FINISHED, CANCELLED -> false;
        };
    }

    public static OrderStatus fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (OrderStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        return null;
    }
}
