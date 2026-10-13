package org.example.online_used_marketplace.modules.ai.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.online_used_marketplace.modules.ai.config.MemoryGraphPgProperties;
import org.example.online_used_marketplace.modules.ai.vo.MemoryGraphCleanupVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryGraphServiceImplTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void deleteUserGraphDeletesEdgesBeforeNodesWithinOwnerScope() {
        MemoryGraphServiceImpl service = new MemoryGraphServiceImpl(
                jdbcTemplate, new MemoryGraphPgProperties(), new ObjectMapper());
        when(jdbcTemplate.update("DELETE FROM ai_memory_edge WHERE user_id = ?", "42")).thenReturn(3);
        when(jdbcTemplate.update("DELETE FROM ai_memory_node WHERE user_id = ?", "42")).thenReturn(2);

        MemoryGraphCleanupVO result = service.deleteUserGraph(" 42 ");

        assertThat(result.deletedEdges()).isEqualTo(3);
        assertThat(result.deletedNodes()).isEqualTo(2);
        InOrder order = inOrder(jdbcTemplate);
        order.verify(jdbcTemplate).update("DELETE FROM ai_memory_edge WHERE user_id = ?", "42");
        order.verify(jdbcTemplate).update("DELETE FROM ai_memory_node WHERE user_id = ?", "42");
    }
}
