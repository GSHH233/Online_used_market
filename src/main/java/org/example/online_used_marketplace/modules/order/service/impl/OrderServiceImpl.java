package org.example.online_used_marketplace.modules.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.order.dto.OrderCreateDTO;
import org.example.online_used_marketplace.modules.order.dto.OrderQueryDTO;
import org.example.online_used_marketplace.modules.order.entity.Order;
import org.example.online_used_marketplace.modules.order.entity.OrderItem;
import org.example.online_used_marketplace.modules.order.enums.OrderRoleType;
import org.example.online_used_marketplace.modules.order.enums.OrderStatus;
import org.example.online_used_marketplace.modules.order.mapper.OrderItemMapper;
import org.example.online_used_marketplace.modules.order.mapper.OrderMapper;
import org.example.online_used_marketplace.modules.order.service.OrderService;
import org.example.online_used_marketplace.modules.order.vo.OrderCreateVO;
import org.example.online_used_marketplace.modules.order.vo.OrderDetailVO;
import org.example.online_used_marketplace.modules.order.vo.OrderListItemVO;
import org.example.online_used_marketplace.modules.product.entity.Product;
import org.example.online_used_marketplace.modules.product.entity.ProductImage;
import org.example.online_used_marketplace.modules.product.mapper.ProductImageMapper;
import org.example.online_used_marketplace.modules.product.mapper.ProductMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Slf4j
@Transactional
@RequiredArgsConstructor
@Service
public class OrderServiceImpl implements OrderService{
    private final ProductMapper productMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final ProductImageMapper productImageMapper;

    @Override
    public OrderCreateVO createOrder(OrderCreateDTO dto, Long currentUserId) {
        Product product = productMapper.selectById(dto.getProductId());
        if (product == null || product.getStatus() == null || !product.getStatus().equals(1)) {
            log.warn("商品不可下单, productId={}", dto.getProductId());
            throw new BusinessException(ResultCode.BAD_REQUEST, "商品已售尽或已下架");
        }
        if (product.getSellerId() != null && product.getSellerId().equals(currentUserId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "不能购买自己的商品");
        }

        String epochMilli = String.valueOf(Instant.now().toEpochMilli());
        int random = ThreadLocalRandom.current().nextInt(100000, 1000000);
        String orderNo = epochMilli + random;

        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setBuyerId(currentUserId);
        order.setSellerId(product.getSellerId());
        order.setProductId(product.getId());
        order.setOrderAmount(product.getPrice());
        order.setStatus(OrderStatus.PENDING_PAY.getCode());
        order.setRemark(dto.getRemark());
        order.setCreatedTime(LocalDateTime.now());

        if (orderMapper.insert(order) != 1 || order.getId() == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "订单生成失败");
        }

        ProductImage productImage = productImageMapper.selectOne(new LambdaQueryWrapper<ProductImage>()
                .eq(ProductImage::getProductId, dto.getProductId())
                .eq(ProductImage::getIsMain, 1)
                .last("limit 1"));

        OrderItem orderItem = new OrderItem();
        orderItem.setOrderId(order.getId());
        orderItem.setProductId(product.getId());
        orderItem.setProductTitle(product.getTitle());
        orderItem.setProductPrice(product.getPrice());
        orderItem.setConditionLevel(product.getConditionLevel());
        orderItem.setProductMainImage(productImage != null ? productImage.getImageUrl() : null);
        orderItem.setCreatedTime(LocalDateTime.now());

        if (orderItemMapper.insert(orderItem) != 1) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "订单快照保存失败");
        }

        product.setStatus(2);
        if (productMapper.updateById(product) != 1) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "更新商品信息失败");
        }

        return new OrderCreateVO(order.getId(), orderNo, order.getStatus());
    }

    @Override
    public IPage<OrderListItemVO> listOrders(OrderQueryDTO dto, Long currentUserId) {
        if (dto.getPageNum() < 1 || dto.getPageSize() < 1) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "页数必须大于等于1");
        }
        if (dto.getRole() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "角色不能为空");
        }

        Page<Order> page = new Page<>(dto.getPageNum(), dto.getPageSize());
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        if (dto.getRole() == OrderRoleType.BUYER) {
            wrapper.eq(Order::getBuyerId, currentUserId);
        } else if (dto.getRole() == OrderRoleType.SELLER) {
            wrapper.eq(Order::getSellerId, currentUserId);
        } else {
            throw new BusinessException(ResultCode.BAD_REQUEST, "角色不对");
        }

        if (dto.getStatus() != null) {
            wrapper.eq(Order::getStatus, dto.getStatus());
        }
        wrapper.orderByDesc(Order::getCreatedTime);
        long total = orderMapper.selectCount(wrapper);
        IPage<Order> orderPage = orderMapper.selectPage(page, wrapper);

        List<Order> orders = orderPage.getRecords();
        List<Long> orderIds = new ArrayList<>();
        for (Order order : orders) {
            orderIds.add(order.getId());
        }

        List<OrderItem> orderItems = orderIds.isEmpty()
                ? new ArrayList<>()
                : orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .in(OrderItem::getOrderId, orderIds));

        Map<Long, OrderItem> itemMap = orderItems.stream()
                .collect(Collectors.toMap(OrderItem::getOrderId, item -> item, (a, b) -> a));

        List<OrderListItemVO> voList = new ArrayList<>();
        for (Order order : orders) {
            OrderListItemVO vo = new OrderListItemVO();
            BeanUtils.copyProperties(order, vo);
            OrderItem item = itemMap.get(order.getId());
            if (item != null) {
                vo.setProductTitle(item.getProductTitle());
                vo.setProductMainImage(item.getProductMainImage());
            }
            voList.add(vo);
        }

        Page<OrderListItemVO> voPage = new Page<>(orderPage.getCurrent(), orderPage.getSize(), total);
        voPage.setRecords(voList);
        return voPage;
    }

    @Override
    public OrderDetailVO getOrderDetail(Long orderId, Long currentUserId) {
        Order order = orderMapper.selectById(orderId);
        if (order==null){
            throw new BusinessException(ResultCode.NOT_FOUND,"订单不存在");
        }
        boolean isBuyer = Objects.equals(order.getBuyerId(), currentUserId);
        boolean isSeller = Objects.equals(order.getSellerId(), currentUserId);
        if (!isBuyer && !isSeller){
            throw new BusinessException(ResultCode.FORBIDDEN,"角色错误");
        }
        LambdaQueryWrapper<OrderItem> wrapper=new LambdaQueryWrapper<>();
        wrapper.eq(OrderItem::getOrderId,orderId);
        wrapper.last("limit 1");
        OrderItem orderItem = orderItemMapper.selectOne(wrapper);
        OrderDetailVO vo=new OrderDetailVO();
        BeanUtils.copyProperties(order,vo);
        if (orderItem!=null){
            vo.setProductTitle(orderItem.getProductTitle());
            vo.setProductPrice(orderItem.getProductPrice());
            vo.setProductMainImage(orderItem.getProductMainImage());
            vo.setConditionLevel(orderItem.getConditionLevel());
        }
        return vo;
    }

    @Override
    public void payOrder(Long orderId, Long currentUserId) {
        Order order = orderMapper.selectById(orderId);
        if (order==null){
            throw new BusinessException(ResultCode.NOT_FOUND,"订单不存在");
        }
        if (!Objects.equals(order.getBuyerId(), currentUserId)){
            throw new BusinessException(ResultCode.FORBIDDEN,"角色错误");
        }
        if (!Objects.equals(order.getStatus(), OrderStatus.PENDING_PAY.getCode())){
            throw new BusinessException(ResultCode.BAD_REQUEST,"订单状态错误");
        }
        order.setStatus(OrderStatus.PAID.getCode());
        order.setPayTime(LocalDateTime.now());
        order.setUpdatedTime(LocalDateTime.now());
        if (orderMapper.updateById(order) != 1) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "支付状态更新失败");
        }

    }

    @Override
    public void deliverOrder(Long orderId, Long currentUserId) {
        Order order = orderMapper.selectById(orderId);
        if (order==null){
            throw new BusinessException(ResultCode.NOT_FOUND,"订单不存在");
        }
        if (!Objects.equals(order.getSellerId(), currentUserId)){
            throw new BusinessException(ResultCode.FORBIDDEN,"角色错误");
        }
        if (!Objects.equals(order.getStatus(), OrderStatus.PAID.getCode())){
            throw new BusinessException(ResultCode.BAD_REQUEST,"订单状态错误");
        }
        order.setStatus(OrderStatus.DELIVERED.getCode());
        order.setDeliverTime(LocalDateTime.now());
        order.setUpdatedTime(LocalDateTime.now());
        if (orderMapper.updateById(order) != 1) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "发货状态更新失败");
        }
    }

    @Override
    public void finishOrder(Long orderId, Long currentUserId) {
        Order order = orderMapper.selectById(orderId);
        if (order==null){
            throw new BusinessException(ResultCode.NOT_FOUND,"订单不存在");
        }
        if (!Objects.equals(order.getBuyerId(), currentUserId)){
            throw new BusinessException(ResultCode.FORBIDDEN,"角色错误");
        }
        if (!Objects.equals(order.getStatus(), OrderStatus.DELIVERED.getCode())){
            throw new BusinessException(ResultCode.BAD_REQUEST,"订单状态错误");
        }
        order.setStatus(OrderStatus.FINISHED.getCode());
        order.setFinishTime(LocalDateTime.now());
        order.setUpdatedTime(LocalDateTime.now());
        if (orderMapper.updateById(order) != 1) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "完成状态更新失败");
        }

        Product product = productMapper.selectById(order.getProductId());
        if (product == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "商品不存在");
        }
        product.setStatus(3);
        if (productMapper.updateById(product) != 1) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "商品状态更新失败");
        }
    }

    @Override
    public void cancelOrder(Long orderId, Long currentUserId) {
        Order order = orderMapper.selectById(orderId);
        if (order==null){
            throw new BusinessException(ResultCode.NOT_FOUND,"订单不存在");
        }
        if (!Objects.equals(order.getBuyerId(), currentUserId)){
            throw new BusinessException(ResultCode.FORBIDDEN,"卖家不允许取消");
        }
        if (!Objects.equals(order.getStatus(), OrderStatus.PENDING_PAY.getCode())){
            throw new BusinessException(ResultCode.BAD_REQUEST,"无法取消");
        }
        order.setStatus(OrderStatus.CANCELLED.getCode());
        order.setCancelTime(LocalDateTime.now());
        order.setUpdatedTime(LocalDateTime.now());
        if (orderMapper.updateById(order) != 1){
            throw new BusinessException(ResultCode.INTERNAL_ERROR,"状态更新失败");
        }

        Product product = productMapper.selectById(order.getProductId());
        if (product != null && Objects.equals(product.getStatus(), 2)){
            product.setStatus(1);
            productMapper.updateById(product);
        }
    }
}
