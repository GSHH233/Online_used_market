package org.example.online_used_marketplace.modules.ai.token;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TokenPromptSection {

    private String name;
    private String content;
    private int priority;
    private boolean protectedSection;

    public static TokenPromptSection of(String name, String content, int priority, boolean protectedSection) {
        return new TokenPromptSection(name, content, priority, protectedSection);
    }
}
