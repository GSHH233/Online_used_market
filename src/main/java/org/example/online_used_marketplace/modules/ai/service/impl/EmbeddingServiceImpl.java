package org.example.online_used_marketplace.modules.ai.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.ai.config.EmbeddingBatchProperties;
import org.example.online_used_marketplace.modules.ai.service.EmbeddingService;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 向量生成服务实现。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingServiceImpl implements EmbeddingService {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingBatchProperties batchProperties;

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> normalized = normalizeTexts(texts);
        if (normalized.isEmpty()) {
            return Collections.emptyList();
        }

        int batchSize = Math.max(1, Math.min(batchProperties.getMaxBatchSize(), 10));
        List<float[]> vectors = new ArrayList<>(normalized.size());
        for (int start = 0; start < normalized.size(); start += batchSize) {
            int end = Math.min(start + batchSize, normalized.size());
            List<String> batch = normalized.subList(start, end);
            List<float[]> batchVectors;
            try {
                batchVectors = embeddingModel.embed(batch);
            } catch (Exception e) {
                log.warn("Embedding model batch failed, range=[{},{}), total={}, reason={}",
                        start, end, normalized.size(), e.toString());
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "向量模型调用失败");
            }
            validateEmbeddingResult(batch, batchVectors);
            vectors.addAll(batchVectors);
        }

        validateEmbeddingResult(normalized, vectors);
        return vectors;
    }

    private List<String> normalizeTexts(List<String> texts) {
        List<String> normalized = new ArrayList<>();
        for (String text : texts) {
            if (!StringUtils.hasText(text)) {
                continue;
            }
            normalized.add(text.trim());
        }
        return normalized;
    }

    private void validateEmbeddingResult(List<String> input, List<float[]> output) {
        if (output == null || output.size() != input.size()) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "向量生成失败");
        }
        for (float[] vector : output) {
            if (vector == null || vector.length == 0) {
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "向量生成失败");
            }
        }
    }
}
