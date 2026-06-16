package org.example.online_used_marketplace.modules.favorite.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.example.online_used_marketplace.modules.favorite.dto.FavoriteQueryDTO;
import org.example.online_used_marketplace.modules.favorite.vo.FavoriteListItemVO;

public interface FavoriteService {

    void addFavorite(Long productId, Long currentUserId);

    void removeFavorite(Long productId, Long currentUserId);

    IPage<FavoriteListItemVO> listFavorites(FavoriteQueryDTO dto, Long currentUserId);
}
