package org.example.online_used_marketplace.modules.favorite.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.online_used_marketplace.modules.favorite.entity.Favorite;

@Mapper
public interface FavoriteMapper extends BaseMapper<Favorite> {
}
