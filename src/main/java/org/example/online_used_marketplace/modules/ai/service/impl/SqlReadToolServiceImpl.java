package org.example.online_used_marketplace.modules.ai.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.ai.config.SqlToolProperties;
import org.example.online_used_marketplace.modules.ai.dto.SqlReadRequestDTO;
import org.example.online_used_marketplace.modules.ai.mapper.SqlToolMapper;
import org.example.online_used_marketplace.modules.ai.service.SqlReadToolService;
import org.example.online_used_marketplace.modules.ai.vo.ToolQueryResultVO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MySQL 只读工具服务（MVP骨架）
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SqlReadToolServiceImpl implements SqlReadToolService {

    private final SqlToolMapper sqlToolMapper;
    private final SqlToolProperties sqlToolProperties;

    private static final List<String> FORBIDDEN_KEYWORDS = Arrays.asList(
            " insert ", " update ", " delete ", " drop ", " alter ", " truncate ",
            " create ", " replace ", " grant ", " revoke ", " commit ", " rollback "
    );


    @Override
    public ToolQueryResultVO executeReadOnly(SqlReadRequestDTO request) {
        long start = System.currentTimeMillis();

        String normalizedSql = normalizeSql(request.getSql());
        validateReadOnlySql(normalizedSql);
        validateTableWhitelist(normalizedSql);

        int limit = normalizeLimit(request.getLimit());
        String executableSql = appendLimitIfMissing(normalizedSql, limit);
        executableSql = appendTimeoutHint(executableSql, sqlToolProperties.getTimeoutMs());

        List<Map<String, Object>> rows;
        try {
            rows = sqlToolMapper.queryByRawSql(executableSql);
        } catch (Exception e) {
            log.error("sql read tool execute failed, sql={}", executableSql, e);
            throw new BusinessException(ResultCode.BAD_REQUEST, "SQL执行失败，请检查语句或白名单");
        }

        boolean truncated = rows.size() >= limit;
        List<String> columns = extractColumns(rows);

        ToolQueryResultVO vo = new ToolQueryResultVO();
        vo.setTool("mysql_read");
        vo.setTarget("sql");
        vo.setLimit(limit);
        vo.setTotal((long) rows.size());
        vo.setTruncated(truncated);
        vo.setElapsedMs(System.currentTimeMillis() - start);
        vo.setColumns(columns);
        vo.setRows(rows);
        vo.setSummary("mysql只读查询完成");
        return vo;
    }

    private String normalizeSql(String sql) {
        if (!StringUtils.hasText(sql)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "SQL不能为空");
        }
        return sql.trim();
    }

    private void validateReadOnlySql(String sql) {
        String lowered = " " + sql.toLowerCase(Locale.ROOT) + " ";
        if (!lowered.trim().startsWith("select")) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "仅允许执行SELECT语句");
        }
        if (lowered.contains(";")) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "不允许多语句执行");
        }
        for (String keyword : FORBIDDEN_KEYWORDS) {
            if (lowered.contains(keyword)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "检测到非只读关键词，已拒绝执行");
            }
        }
    }
    private String appendTimeoutHint(String sql, Integer timeoutMs) {
        int effectiveTimeout = (timeoutMs == null || timeoutMs <= 0) ? 3000 : timeoutMs;
        String lowered = sql.toLowerCase(Locale.ROOT);
        if (!lowered.startsWith("select")) {
            return sql;
        }
        if (lowered.contains("max_execution_time(")) {
            return sql;
        }
        return "SELECT /*+ MAX_EXECUTION_TIME(" + effectiveTimeout + ") */ " + sql.substring(6).trim();
    }

    private void validateTableWhitelist(String sql) {
        String lowered = sql.toLowerCase(Locale.ROOT);
        List<String> whitelist = sqlToolProperties.getTableWhitelist();
        if (whitelist == null || whitelist.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "SQL表白名单未配置");
        }
        boolean matched = whitelist.stream()
                .filter(StringUtils::hasText)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .anyMatch(lowered::contains);
        if (!matched) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "SQL未命中允许访问的数据表白名单");
        }
    }

    private int normalizeLimit(Integer limit) {
        Integer defaultLimit = sqlToolProperties.getDefaultLimit();
        Integer maxLimit = sqlToolProperties.getMaxLimit();
        int d = (defaultLimit == null || defaultLimit <= 0) ? 100 : defaultLimit;
        int m = (maxLimit == null || maxLimit <= 0) ? 500 : maxLimit;
        if (limit == null || limit <= 0) {
            return d;
        }
        return Math.min(limit, m);
    }

    private String appendLimitIfMissing(String sql, int limit) {
        String lowered = sql.toLowerCase(Locale.ROOT);
        if (lowered.contains(" limit ")) {
            return sql;
        }
        return sql + " limit " + limit;
    }

    private List<String> extractColumns(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        Map<String, Object> first = rows.get(0);
        return new ArrayList<>(first.keySet());
    }
}
