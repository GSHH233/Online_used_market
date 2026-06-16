package org.example.online_used_marketplace.modules.ai.service.impl;

import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.ai.service.EmbeddingService;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 向量生成服务实现
 */
@Service
@RequiredArgsConstructor
public class EmbeddingServiceImpl implements EmbeddingService {

    private final EmbeddingModel embeddingModel;

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> normalized = normalizeTexts(texts);
        if (normalized.isEmpty()) {
            return Collections.emptyList();
        }

        List<float[]> vectors;
        try {
            vectors = embeddingModel.embed(normalized);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "向量模型调用失败");
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
