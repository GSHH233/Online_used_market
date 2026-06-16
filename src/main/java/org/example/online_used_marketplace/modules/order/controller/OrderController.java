package org.example.online_used_marketplace.modules.order.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.Result;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.order.dto.OrderCreateDTO;
import org.example.online_used_marketplace.modules.order.dto.OrderQueryDTO;
import org.example.online_used_marketplace.modules.order.service.OrderService;
import org.example.online_used_marketplace.modules.order.vo.OrderCreateVO;
import org.example.online_used_marketplace.modules.order.vo.OrderDetailVO;
import org.example.online_used_marketplace.modules.order.vo.OrderListItemVO;
import org.example.online_used_marketplace.modules.user.entity.UserPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;


/**
 * @author Admin
 * @ClassName: OrderController
 * @Description: TODO(这里用一句话描述这个类的作用)
 * @date 2026-04-08  19:13
 * @Version:
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public Result<OrderCreateVO> createOrder(@Valid @RequestBody OrderCreateDTO dto) {
        UserPrincipal principal = getCurrentUser();
        return Result.success(orderService.createOrder(dto, principal.getUserId()));
    }

    @GetMapping
    public Result<IPage<OrderListItemVO>> listOrders(@ModelAttribute @Valid OrderQueryDTO dto) {
        UserPrincipal principal = getCurrentUser();
        return Result.success(orderService.listOrders(dto, principal.getUserId()));
    }

    @GetMapping("/{orderId}")
    public Result<OrderDetailVO> getOrderDetail(@PathVariable Long orderId) {
        UserPrincipal principal = getCurrentUser();
        return Result.success(orderService.getOrderDetail(orderId, principal.getUserId()));
    }

    @PutMapping("/{orderId}/cancel")
    public Result<Void> cancelOrder(@PathVariable Long orderId) {
        UserPrincipal principal = getCurrentUser();
        orderService.cancelOrder(orderId, principal.getUserId());
        return Result.success();
    }
    @PutMapping("/{orderId}/deliver")
    public Result<Void> deliverOrder(@PathVariable Long orderId){
        UserPrincipal principal = getCurrentUser();
        orderService.deliverOrder(orderId,principal.getUserId());
        return Result.success();
    }
    @PutMapping("/{orderId}/finish")
    public Result<Void> finishOrder(@PathVariable Long orderId){
        UserPrincipal principal = getCurrentUser();
        orderService.finishOrder(orderId,principal.getUserId());
        return Result.success();
    }
    @PutMapping("/{orderId}/pay")
    public Result<Void> payOrder(@PathVariable Long orderId){
        UserPrincipal principal = getCurrentUser();
        orderService.payOrder(orderId,principal.getUserId());
        return Result.success();
    }
    private UserPrincipal getCurrentUser(){
        Authentication auth=SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal principal)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "未认证或登录已过期");
        }
        return principal;
    }
}
