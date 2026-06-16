package org.example.online_used_marketplace.modules.ai.agent.harness;

import org.example.online_used_marketplace.modules.ai.vo.ToolQueryResultVO;

import java.time.Duration;
import java.util.Optional;

public interface ToolExecutionSnapshotStore {

    void save(String key, ToolQueryResultVO observation, Duration ttl);

    Optional<ToolQueryResultVO> find(String key);

    void delete(String key);
}
