package org.example.online_used_marketplace.modules.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.example.online_used_marketplace.modules.user.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
