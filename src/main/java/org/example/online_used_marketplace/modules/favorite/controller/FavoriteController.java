package org.example.online_used_marketplace.modules.favorite.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.Result;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.favorite.dto.FavoriteQueryDTO;
import org.example.online_used_marketplace.modules.favorite.service.FavoriteService;
import org.example.online_used_marketplace.modules.favorite.vo.FavoriteListItemVO;
import org.example.online_used_marketplace.modules.user.entity.UserPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/favorites")
@RequiredArgsConstructor
public class FavoriteController {

    private final FavoriteService favoriteService;

    @PostMapping("/{productId}")
    public Result<Void> addFavorite(@PathVariable Long productId) {
        UserPrincipal principal = getCurrentUser();
        favoriteService.addFavorite(productId, principal.getUserId());
        return Result.success();
    }

    @DeleteMapping("/{productId}")
    public Result<Void> removeFavorite(@PathVariable Long productId) {
        UserPrincipal principal = getCurrentUser();
        favoriteService.removeFavorite(productId, principal.getUserId());
        return Result.success();
    }

    @GetMapping
    public Result<IPage<FavoriteListItemVO>> listFavorites(@Valid @ModelAttribute FavoriteQueryDTO dto) {
        UserPrincipal principal = getCurrentUser();
        return Result.success(favoriteService.listFavorites(dto, principal.getUserId()));
    }

    private UserPrincipal getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal principal)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "未认证或登录已过期");
        }
        return principal;
    }
}
