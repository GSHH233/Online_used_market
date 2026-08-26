package org.example.online_used_marketplace.modules.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.example.online_used_marketplace.modules.ai.entity.KnowledgeChunk;

/**
 * @author Admin
 * @ClassName: KnowledgeChunkMapper
 * @Description: TODO(这里用一句话描述这个类的作用)
 * @date 2026-04-27  22:39
 * @Version:
 */
@Mapper
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunk> {

    /**
     * Retention cleanup is the only lifecycle path that physically removes chunk rows.
     */
    @Delete("DELETE FROM ai_knowledge_chunk WHERE doc_id = #{docId}")
    int hardDeleteByDocId(@Param("docId") Long docId);
}
