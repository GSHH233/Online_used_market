package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.vo.ChunkPayloadVO;
import org.example.online_used_marketplace.modules.ai.entity.KnowledgeDoc;

import java.util.List;

/**
 * @author Admin
 * @ClassName: ChunkingService
 * @Description: TODO(这里用一句话描述这个类的作用)
 * @date 2026-04-27  21:30
 * @Version:
 */
public interface ChunkingService {
    List<ChunkPayloadVO> split(Long docId, String content, int chunkSize, int overlap);

    List<ChunkPayloadVO> split(KnowledgeDoc doc, int chunkSize, int overlap);
}
