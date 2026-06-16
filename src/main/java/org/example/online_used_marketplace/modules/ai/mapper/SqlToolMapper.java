package org.example.online_used_marketplace.modules.ai.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * MySQL 工具查询 Mapper（MVP）
 *
 * 说明：
 * - 这里使用 ${sql} 为了支持动态只读查询。
 * - 必须在 service 层做严格的 SQL 安全校验（仅 SELECT、表白名单、禁多语句）。
 */
@Mapper
public interface SqlToolMapper {

    @Select("${sql}")
    List<Map<String, Object>> queryByRawSql(String sql);
}
