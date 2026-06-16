package org.example.online_used_marketplace.modules.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.example.online_used_marketplace.modules.ai.entity.KnowledgeIndexTask;

import java.time.LocalDateTime;

/**
 * @author Admin
 * @ClassName: KnowledgeIndexTaskMapper
 * @Description: TODO(这里用一句话描述这个类的作用)
 * @date 2026-04-26  0:34
 * @Version:
 */
@Mapper
public interface KnowledgeIndexTaskMapper extends BaseMapper<KnowledgeIndexTask> {

    @Update("""
            UPDATE ai_knowledge_index_task
            SET status = 'QUEUED',
                retry_count = #{retryCount},
                finished_at = NULL,
                updated_time = CURRENT_TIMESTAMP
            WHERE id = #{taskId}
              AND status IN ('PENDING', 'FAILED')
            """)
    int markQueued(@Param("taskId") Long taskId, @Param("retryCount") int retryCount);

    @Update("""
            UPDATE ai_knowledge_index_task
            SET status = 'RUNNING',
                stage = 'CONSISTENCY_CHECK',
                started_at = COALESCE(started_at, CURRENT_TIMESTAMP),
                finished_at = NULL,
                error_msg = NULL,
                updated_time = CURRENT_TIMESTAMP
            WHERE id = #{taskId}
              AND retry_count = #{retryCount}
              AND status IN ('PENDING', 'QUEUED', 'FAILED')
            """)
    int claimForExecution(@Param("taskId") Long taskId, @Param("retryCount") int retryCount);

    @Update("""
            UPDATE ai_knowledge_index_task
            SET status = 'PENDING',
                error_msg = 'stale RUNNING task recovered for redelivery',
                updated_time = CURRENT_TIMESTAMP
            WHERE async_task = 1
              AND status = 'RUNNING'
              AND updated_time < #{staleBefore}
            """)
    int recoverStaleRunning(@Param("staleBefore") LocalDateTime staleBefore);

    @Update("""
            UPDATE ai_knowledge_index_task
            SET updated_time = CURRENT_TIMESTAMP
            WHERE id = #{taskId}
              AND status = 'QUEUED'
            """)
    int touchQueued(@Param("taskId") Long taskId);

    @Update("""
            UPDATE ai_knowledge_index_task
            SET status = 'DEAD_LETTERED',
                stage = 'FINISHED',
                error_msg = CONCAT(COALESCE(error_msg, 'knowledge index task failed'), '; retries exhausted'),
                finished_at = COALESCE(finished_at, CURRENT_TIMESTAMP),
                updated_time = CURRENT_TIMESTAMP
            WHERE id = #{taskId}
              AND status = 'FAILED'
              AND retry_count = #{retryCount}
            """)
    int markDeadLettered(@Param("taskId") Long taskId, @Param("retryCount") int retryCount);
}
