package org.example.online_used_marketplace.modules.ai.service.impl;

import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.ai.service.ChunkingService;
import org.example.online_used_marketplace.modules.ai.vo.ChunkPayloadVO;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档切片服务实现
 */
@Service
@RequiredArgsConstructor
public class ChunkingServiceImpl implements ChunkingService {

    @Override
    public List<ChunkPayloadVO> split(Long docId, String content, int chunkSize, int overlap) {
        if (!StringUtils.hasText(content)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文本不存在");
        }
        if (docId == null || docId <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "知识文档ID不合法");
        }
        if (chunkSize <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "切片长度不合法");
        }
        if (overlap < 0 || overlap >= chunkSize) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "重叠切片长度不合法");
        }

        Document doc = new Document(content);
        TokenTextSplitter splitter = new TokenTextSplitter(chunkSize, 0, 5, 10000, true);
        List<Document> documents = splitter.split(doc);

        // 第二层：在每个基础片段上应用自定义滑窗 overlap
        List<ChunkPayloadVO> chunks = new ArrayList<>();
        int idx = 0;
        int step = chunkSize - overlap;

        for (Document document : documents) {
            String baseText = document.getText();
            if (!StringUtils.hasText(baseText)) {
                continue;
            }
            baseText = baseText.trim();

            int start = 0;
            while (start < baseText.length()) {
                int end = Math.min(start + chunkSize, baseText.length());
                String piece = baseText.substring(start, end).trim();

                if (StringUtils.hasText(piece)) {
                    ChunkPayloadVO vo = new ChunkPayloadVO();
                    vo.setChunkIndex(idx++);
                    vo.setContent(piece);
                    vo.setTokenCount(piece.length());
                    vo.setMetadata(buildMetadataJson(docId, vo.getChunkIndex()));
                    chunks.add(vo);
                }

                if (end == baseText.length()) {
                    break;
                }
                start += step;
            }
        }

        return chunks;
    }

    private String buildMetadataJson(Long docId, Integer chunkIndex) {
        return "{\"docId\":" + docId + ",\"chunkIndex\":" + chunkIndex + "}";
    }
}
