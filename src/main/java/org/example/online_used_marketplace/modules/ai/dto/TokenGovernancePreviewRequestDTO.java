package org.example.online_used_marketplace.modules.ai.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class TokenGovernancePreviewRequestDTO {

    private String scene;
    private List<Section> sections = new ArrayList<>();

    @Data
    public static class Section {
        private String name;
        private String content;
        private Integer priority;
        private Boolean protectedSection;
    }
}
