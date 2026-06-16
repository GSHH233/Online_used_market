package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.vo.VectorRecordVO;
import java.util.List;
import org.example.online_used_marketplace.modules.ai.vo.RagSourceVO;
import java.util.Map;

/**
 * @author Admin
 * @ClassName: VectorStore
 * @Description: TODO(这里用一句话描述这个类的作用)
 * @date 2026-04-27  23:32
 * @Version:
 */
public interface VectorStore {
    void upsert(List<VectorRecordVO> records);
    List<RagSourceVO> search(float[] queryVector, int topK, Map<String,Object> filters);
    void deleteByDocId(Long docId);
    List<Map<String, Object>> debugQueryByDocId(Long docId, Integer limit);
}
