package org.example.online_used_marketplace.modules.ai.service;

import java.util.List;

/**
 * @author Admin
 * @ClassName: EmbeddingService
 * @Description: TODO(这里用一句话描述这个类的作用)
 * @date 2026-04-27  23:32
 * @Version:
 */
public interface EmbeddingService {
    List<float[]> embed(List<String> texts);
}
