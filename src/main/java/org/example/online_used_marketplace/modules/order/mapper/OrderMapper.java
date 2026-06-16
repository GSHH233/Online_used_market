package org.example.online_used_marketplace.modules.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.online_used_marketplace.modules.order.entity.Order;

/**
 * @author Admin
 * @ClassName: OrderMapper
 * @Description: TODO(这里用一句话描述这个类的作用)
 * @date 2026-04-08  19:14
 * @Version:
 */
@Mapper
public interface OrderMapper extends BaseMapper<Order> {
}
