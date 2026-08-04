package org.example.online_used_marketplace.modules.ai.service.impl;

import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.modules.ai.config.TokenGovernanceProperties;
import org.example.online_used_marketplace.modules.ai.service.TokenCounterService;
import org.example.online_used_marketplace.modules.ai.service.TokenGovernanceService;
import org.example.online_used_marketplace.modules.ai.token.TokenBudgetResult;
import org.example.online_used_marketplace.modules.ai.token.TokenPromptSection;
import org.example.online_used_marketplace.modules.ai.token.TokenScene;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class TokenGovernanceServiceImpl implements TokenGovernanceService {

    private final TokenCounterService tokenCounterService;
    private final TokenGovernanceProperties properties;

    @Override
    public TokenBudgetResult assemble(TokenScene scene, List<TokenPromptSection> sections) {
        TokenScene normalizedScene = scene == null ? TokenScene.GENERAL : scene;
        int inputBudget = Math.max(128, properties.inputBudget(normalizedScene));
        int outputBudget = Math.max(32, properties.outputBudget(normalizedScene));
        List<MutableSection> mutableSections = normalize(sections);
        String originalPrompt = render(mutableSections);
        int originalTokens = tokenCounterService.count(originalPrompt);
        Set<String> trimmedSections = new LinkedHashSet<>();

        if (properties.isEnabled() && originalTokens > inputBudget) {
            trimToBudget(mutableSections, inputBudget, trimmedSections);
        }

        String prompt = render(mutableSections);
        int promptTokens = tokenCounterService.count(prompt);
        return TokenBudgetResult.builder()
                .scene(normalizedScene)
                .prompt(prompt)
                .inputBudget(inputBudget)
                .outputBudget(outputBudget)
                .originalPromptTokens(originalTokens)
                .promptTokens(promptTokens)
                .trimmedTokens(Math.max(0, originalTokens - promptTokens))
                .trimmed(promptTokens < originalTokens)
                .trimmedSections(new ArrayList<>(trimmedSections))
                .build();
    }

    @Override
    public TokenBudgetResult fit(TokenScene scene, String prompt) {
        return assemble(scene, List.of(TokenPromptSection.of("request", prompt, 100, true)));
    }

    private void trimToBudget(List<MutableSection> sections, int budget, Set<String> trimmedSections) {
        int guard = 0;
        while (tokenCounterService.count(render(sections)) > budget && guard++ < 10_000) {
            MutableSection candidate = sections.stream()
                    .filter(section -> StringUtils.hasText(section.content()))
                    .min(Comparator
                            .comparing(MutableSection::protectedSection)
                            .thenComparingInt(MutableSection::priority)
                            .thenComparing((MutableSection section) -> tokenCounterService.count(section.content()),
                                    Comparator.reverseOrder()))
                    .orElse(null);
            if (candidate == null) {
                break;
            }

            int totalTokens = tokenCounterService.count(render(sections));
            int sectionTokens = tokenCounterService.count(candidate.content());
            int removeTokens = Math.max(16, totalTokens - budget);
            int targetTokens = Math.max(0, sectionTokens - removeTokens);
            String trimmed = truncateToTokens(candidate.content(), targetTokens);
            if (trimmed.equals(candidate.content())) {
                trimmed = "";
            }
            candidate.content(trimmed);
            trimmedSections.add(candidate.name());
        }
    }

    private String truncateToTokens(String text, int maxTokens) {
        if (!StringUtils.hasText(text) || maxTokens <= 0) {
            return "";
        }
        if (tokenCounterService.count(text) <= maxTokens) {
            return text;
        }
        int low = 0;
        int high = text.length();
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (tokenCounterService.count(text.substring(0, mid)) <= maxTokens) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        String result = text.substring(0, low).stripTrailing();
        return result.isEmpty() ? "" : result + "\n[上下文已按 Token 预算裁剪]";
    }

    private List<MutableSection> normalize(List<TokenPromptSection> sections) {
        List<MutableSection> result = new ArrayList<>();
        if (sections == null) {
            return result;
        }
        int index = 0;
        for (TokenPromptSection section : sections) {
            if (section == null || !StringUtils.hasText(section.getContent())) {
                continue;
            }
            String name = StringUtils.hasText(section.getName()) ? section.getName().trim() : "section_" + index;
            result.add(new MutableSection(index++, name, section.getContent().trim(),
                    section.getPriority(), section.isProtectedSection()));
        }
        return result;
    }

    private String render(List<MutableSection> sections) {
        return sections.stream()
                .filter(section -> StringUtils.hasText(section.content()))
                .sorted(Comparator.comparingInt(MutableSection::index))
                .map(section -> "## " + section.name() + "\n" + section.content())
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");
    }

    private static final class MutableSection {
        private final int index;
        private final String name;
        private String content;
        private final int priority;
        private final boolean protectedSection;

        private MutableSection(int index, String name, String content, int priority, boolean protectedSection) {
            this.index = index;
            this.name = name;
            this.content = content;
            this.priority = priority;
            this.protectedSection = protectedSection;
        }

        private int index() { return index; }
        private String name() { return name; }
        private String content() { return content; }
        private void content(String content) { this.content = content; }
        private int priority() { return priority; }
        private boolean protectedSection() { return protectedSection; }
    }
}
