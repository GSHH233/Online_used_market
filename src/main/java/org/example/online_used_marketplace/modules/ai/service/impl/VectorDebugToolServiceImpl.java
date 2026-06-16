package org.example.online_used_marketplace.modules.ai.service.impl;

import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.ai.dto.VectorDebugQueryRequestDTO;
import org.example.online_used_marketplace.modules.ai.service.KnowledgeSearchService;
import org.example.online_used_marketplace.modules.ai.service.VectorDebugToolService;
import org.example.online_used_marketplace.modules.ai.service.VectorStore;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeSearchResponseVO;
import org.example.online_used_marketplace.modules.ai.vo.ToolQueryResultVO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 向量检索调试工具（MVP骨架）
 */
@Service
@RequiredArgsConstructor
public class VectorDebugToolServiceImpl implements VectorDebugToolService {

    private final VectorStore vectorStore;
    private final KnowledgeSearchService knowledgeSearchService;

    @Override
    public ToolQueryResultVO debugByDocId(Long docId, Integer limit) {
        if (docId == null || docId <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "docId不能为空且必须大于0");
        }

        long start = System.currentTimeMillis();
        int normalizedLimit = normalizeLimit(limit);
        List<Map<String, Object>> rows = vectorStore.debugQueryByDocId(docId, normalizedLimit);
        boolean truncated = rows.size() >= normalizedLimit;

        ToolQueryResultVO vo = new ToolQueryResultVO();
        vo.setTool("vector_debug");
        vo.setTarget("docId=" + docId);
        vo.setLimit(normalizedLimit);
        vo.setTotal((long) rows.size());
        vo.setTruncated(truncated);
        vo.setElapsedMs(System.currentTimeMillis() - start);
        vo.setRows(rows);
        vo.setColumns(extractColumns(rows));
        vo.setSummary("向量库按docId调试查询完成");
        return vo;
    }

    @Override
    public ToolQueryResultVO searchDebug(VectorDebugQueryRequestDTO request) {
        if (request == null || !StringUtils.hasText(request.getQuestion())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "问题不能为空");
        }

        long start = System.currentTimeMillis();
        int topK = normalizeTopK(request.getTopK());
        KnowledgeSearchResponseVO resp = knowledgeSearchService.search(request.getQuestion().trim(), topK, request.getFilter());

        List<Map<String, Object>> rows = new ArrayList<>();
        if (resp != null && resp.getHits() != null) {
            for (var hit : resp.getHits()) {
                rows.add(Map.of(
                        "docId", hit.getDocId(),
                        "chunkId", hit.getChunkId(),
                        "score", hit.getScore(),
                        "sourceType", hit.getSourceType(),
                        "docTitle", hit.getDocTitle(),
                        "sourceUri", hit.getSourceUri(),
                        "snippet", hit.getSnippet()
                ));
            }
        }

        ToolQueryResultVO vo = new ToolQueryResultVO();
        vo.setTool("vector_debug");
        vo.setTarget(request.getQuestion());
        vo.setLimit(topK);
        vo.setTotal((long) rows.size());
        vo.setTruncated(false);
        vo.setElapsedMs(System.currentTimeMillis() - start);
        vo.setRows(rows);
        vo.setColumns(extractColumns(rows));
        vo.setSummary("向量检索调试完成");
        return vo;
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return 100;
        }
        return Math.min(limit, 500);
    }

    private int normalizeTopK(Integer topK) {
        if (topK == null || topK <= 0) {
            return 5;
        }
        return Math.min(topK, 20);
    }

    private List<String> extractColumns(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(rows.get(0).keySet());
    }
}
