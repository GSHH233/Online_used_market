package org.example.online_used_marketplace.modules.product.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.Result;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.product.dto.ProductCreateDTO;
import org.example.online_used_marketplace.modules.product.dto.ProductQueryDTO;
import org.example.online_used_marketplace.modules.product.service.ProductService;
import org.example.online_used_marketplace.modules.product.vo.ProductCreateVO;
import org.example.online_used_marketplace.modules.product.vo.ProductDetailVO;
import org.example.online_used_marketplace.modules.product.vo.ProductListItemVO;
import org.example.online_used_marketplace.modules.user.entity.UserPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @PostMapping("/createProduct")
    public Result<ProductCreateVO> addProduct(@Valid @RequestBody ProductCreateDTO dto) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal principal)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "未认证或登录已过期");
        }
        return Result.success(productService.addProduct(dto, principal.getUserId()));
    }

    @GetMapping("/queryProduct")
    public Result<IPage<ProductListItemVO>> queryProduct(@Valid @ModelAttribute ProductQueryDTO dto) {
        return Result.success(productService.listProduct(dto));
    }

    @GetMapping("/{productId}")
    public Result<ProductDetailVO> getProductDetail(@PathVariable Long productId) {
        return Result.success(productService.getProductDetail(productId));
    }
}
