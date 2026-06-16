package org.example.online_used_marketplace.modules.order.service;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.example.online_used_marketplace.modules.order.dto.OrderCreateDTO;
import org.example.online_used_marketplace.modules.order.dto.OrderQueryDTO;
import org.example.online_used_marketplace.modules.order.entity.Order;
import org.example.online_used_marketplace.modules.order.vo.OrderCreateVO;
import org.example.online_used_marketplace.modules.order.vo.OrderDetailVO;
import org.example.online_used_marketplace.modules.order.vo.OrderListItemVO;

public interface OrderService{
    OrderCreateVO createOrder(OrderCreateDTO dto, Long currentUserId);

    IPage<OrderListItemVO> listOrders(OrderQueryDTO dto, Long currentUserId);

    OrderDetailVO getOrderDetail(Long orderId, Long currentUserId);

    void payOrder(Long orderId, Long currentUserId);

    void deliverOrder(Long orderId, Long currentUserId);

    void finishOrder(Long orderId, Long currentUserId);

    void cancelOrder(Long orderId, Long currentUserId);
}