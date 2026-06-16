package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class MemoryGraphExtractResponseVO {

    private String userId;

    private String sessionId;

    private Integer nodeCount;

    private Integer edgeCount;

    private List<MemoryGraphNodeVO> nodes = new ArrayList<>();

    private List<MemoryGraphEdgeVO> edges = new ArrayList<>();
}
