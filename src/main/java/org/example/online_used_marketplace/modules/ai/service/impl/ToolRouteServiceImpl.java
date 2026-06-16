package org.example.online_used_marketplace.modules.ai.service.impl;

import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.modules.ai.dto.LogQueryRequestDTO;
import org.example.online_used_marketplace.modules.ai.dto.SqlReadRequestDTO;
import org.example.online_used_marketplace.modules.ai.dto.VectorDebugQueryRequestDTO;
import org.example.online_used_marketplace.modules.ai.enums.ToolRouteMode;
import org.example.online_used_marketplace.modules.ai.service.LogToolService;
import org.example.online_used_marketplace.modules.ai.service.SqlReadToolService;
import org.example.online_used_marketplace.modules.ai.service.ToolRouteService;
import org.example.online_used_marketplace.modules.ai.service.VectorDebugToolService;
import org.example.online_used_marketplace.modules.ai.vo.ToolQueryResultVO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * 工具路由服务（MVP规则路由）
 */
@Service
@RequiredArgsConstructor
public class ToolRouteServiceImpl implements ToolRouteService {

    private final LogToolService logToolService;
    private final SqlReadToolService sqlReadToolService;
    private final VectorDebugToolService vectorDebugToolService;

    @Override
    public ToolQueryResultVO route(String question, String traceId, ToolRouteMode mode) {
        ToolRouteMode effectiveMode = (mode == null) ? ToolRouteMode.USER : mode;
        String q = normalize(question);

        if (effectiveMode == ToolRouteMode.USER) {
            ToolQueryResultVO denied = new ToolQueryResultVO();
            denied.setTool("none");
            denied.setTarget(question);
            denied.setTotal(0L);
            denied.setTruncated(false);
            denied.setSummary("当前模式不允许调用内部工具");
            return denied;
        }

        if (isLogIntent(q)) {
            LogQueryRequestDTO req = new LogQueryRequestDTO();
            req.setTraceId(traceId);
            req.setLimit(50);
            req.setWindowMinutes(60);
            return logToolService.queryByTraceId(req);
        }

        if (isSqlIntent(q)) {
            SqlReadRequestDTO req = new SqlReadRequestDTO();
            req.setSql("select id, title, status, created_time from ai_knowledge_doc order by id desc");
            req.setLimit(20);
            return sqlReadToolService.executeReadOnly(req);
        }

        if (isVectorIntent(q)) {
            VectorDebugQueryRequestDTO req = new VectorDebugQueryRequestDTO();
            req.setQuestion(question);
            req.setTopK(5);
            return vectorDebugToolService.searchDebug(req);
        }

        ToolQueryResultVO fallback = new ToolQueryResultVO();
        fallback.setTool("none");
        fallback.setTarget(question);
        fallback.setSummary("未匹配到工具路由规则");
        fallback.setTotal(0L);
        fallback.setTruncated(false);
        return fallback;
    }

    private String normalize(String question) {
        if (!StringUtils.hasText(question)) {
            return "";
        }
        return question.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isLogIntent(String q) {
        return q.contains("日志") || q.contains("trace") || q.contains("报错") || q.contains("error");
    }

    private boolean isSqlIntent(String q) {
        return q.contains("数据库") || q.contains("mysql") || q.contains("文档表") || q.contains("chunk") || q.contains("索引任务");
    }

    private boolean isVectorIntent(String q) {
        return q.contains("向量") || q.contains("召回") || q.contains("命中") || q.contains("检索") || q.contains("source");
    }
}
