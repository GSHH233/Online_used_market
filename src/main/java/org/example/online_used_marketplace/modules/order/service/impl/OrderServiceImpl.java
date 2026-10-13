package org.example.online_used_marketplace.modules.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
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
import org.example.online_used_marketplace.modules.product.enums.ProductStatus;
import org.example.online_used_marketplace.modules.product.mapper.ProductImageMapper;
import org.example.online_used_marketplace.modules.product.mapper.ProductMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Transactional(transactionManager = "mysqlTransactionManager", rollbackFor = Exception.class)
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
        if (product == null || !Objects.equals(product.getStatus(), ProductStatus.ON_SALE.getCode())) {
            log.warn("商品不可下单, productId={}", dto.getProductId());
            throw new BusinessException(ResultCode.BAD_REQUEST, "商品已售尽或已下架");
        }
        if (product.getSellerId() != null && product.getSellerId().equals(currentUserId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "不能购买自己的商品");
        }

        updateProductStatus(product.getId(), ProductStatus.ON_SALE, ProductStatus.LOCKED);
        String orderNo = IdWorker.getIdStr();

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

        return new OrderCreateVO(order.getId(), orderNo, order.getStatus());
    }

    @Override
    public IPage<OrderListItemVO> listOrders(OrderQueryDTO dto, Long currentUserId) {
        if (dto == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "查询参数不能为空");
        }
        if (dto.getPageNum() == null || dto.getPageNum() < 1) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "页码必须大于等于1");
        }
        if (dto.getPageSize() == null || dto.getPageSize() < 1 || dto.getPageSize() > 100) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "每页数量必须在1-100之间");
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

        Page<OrderListItemVO> voPage = new Page<>(orderPage.getCurrent(), orderPage.getSize(), orderPage.getTotal());
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
        transitionOrder(order, OrderStatus.PAID);
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
        transitionOrder(order, OrderStatus.DELIVERED);
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
        transitionOrder(order, OrderStatus.FINISHED);
        updateProductStatus(order.getProductId(), ProductStatus.LOCKED, ProductStatus.SOLD);
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
        transitionOrder(order, OrderStatus.CANCELLED);
        // Only the winning order CAS may release the product for another buyer.
        updateProductStatus(order.getProductId(), ProductStatus.LOCKED, ProductStatus.ON_SALE);
    }

    private void transitionOrder(Order order, OrderStatus target) {
        OrderStatus from = OrderStatus.fromCode(order.getStatus());
        if (from == null || !from.canTransitionTo(target)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单状态不允许此操作");
        }
        LocalDateTime now = LocalDateTime.now();
        Order changes = new Order();
        changes.setStatus(target.getCode());
        changes.setUpdatedTime(now);
        switch (target) {
            case PAID -> changes.setPayTime(now);
            case DELIVERED -> changes.setDeliverTime(now);
            case FINISHED -> changes.setFinishTime(now);
            case CANCELLED -> changes.setCancelTime(now);
            default -> throw new BusinessException(ResultCode.BAD_REQUEST, "非法订单状态转换");
        }
        if (orderMapper.update(changes, new LambdaUpdateWrapper<Order>()
                .eq(Order::getId, order.getId())
                .eq(Order::getStatus, from.getCode())) != 1) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单状态已变更，请刷新后重试");
        }
    }

    private void updateProductStatus(Long productId, ProductStatus from, ProductStatus target) {
        if (productMapper.update(null, new LambdaUpdateWrapper<Product>()
                .eq(Product::getId, productId)
                .eq(Product::getStatus, from.getCode())
                .set(Product::getStatus, target.getCode())
                .set(Product::getUpdatedTime, LocalDateTime.now())) != 1) {
            String message = from == ProductStatus.ON_SALE && target == ProductStatus.LOCKED
                    ? "商品已被他人下单，请刷新" : "商品状态已变更，操作失败";
            throw new BusinessException(ResultCode.BAD_REQUEST, message);
        }
    }
}
