package org.example.online_used_marketplace.modules.order.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrderStatusTest {
    @Test
    void onlyExplicitBusinessTransitionsAreAllowed() {
        for (OrderStatus from : OrderStatus.values()) {
            for (OrderStatus target : OrderStatus.values()) {
                boolean allowed = (from == OrderStatus.PENDING_PAY
                        && (target == OrderStatus.PAID || target == OrderStatus.CANCELLED))
                        || (from == OrderStatus.PAID && target == OrderStatus.DELIVERED)
                        || (from == OrderStatus.DELIVERED && target == OrderStatus.FINISHED);
                assertEquals(allowed, from.canTransitionTo(target), from + " -> " + target);
            }
            assertFalse(from.canTransitionTo(null));
        }
    }

    @Test
    void statusCodesRemainCompatibleAndUnknownCodesAreRejected() {
        for (OrderStatus status : OrderStatus.values()) {
            assertSame(status, OrderStatus.fromCode(status.getCode()));
        }
        assertEquals(0, OrderStatus.PENDING_PAY.getCode());
        assertEquals(1, OrderStatus.PAID.getCode());
        assertEquals(2, OrderStatus.DELIVERED.getCode());
        assertEquals(3, OrderStatus.FINISHED.getCode());
        assertEquals(4, OrderStatus.CANCELLED.getCode());
        assertNull(OrderStatus.fromCode(null));
        assertNull(OrderStatus.fromCode(5));
        assertNull(OrderStatus.fromCode(-1));
        assertTrue(OrderStatus.FINISHED.isFinalStatus());
        assertTrue(OrderStatus.CANCELLED.isFinalStatus());
    }
}
