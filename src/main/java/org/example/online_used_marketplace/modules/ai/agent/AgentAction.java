package org.example.online_used_marketplace.modules.ai.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentAction {

    private String tool;

    private String thought;

    private Map<String, Object> input = new HashMap<>();
}
