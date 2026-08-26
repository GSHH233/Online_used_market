package org.example.online_used_marketplace.modules.ai.service.impl;

import org.example.online_used_marketplace.modules.ai.vo.RagSourceVO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeywordSearchServiceImplTest {

    @Test
    void reservesCandidateSpaceForConceptFocusedLaneAndDeduplicatesOverlap() {
        KeywordSearchServiceImpl service = new KeywordSearchServiceImpl(null, null);
        List<RagSourceVO> lexical = new ArrayList<>();
        for (long id = 1; id <= 20; id++) {
            lexical.add(source(id, "词面候选" + id));
        }
        RagSourceVO conceptOnly = source(55L, "价格忠实度规则");
        RagSourceVO overlap = source(1L, "词面候选1");

        List<RagSourceVO> fused = service.fuseKeywordLanes(
                lexical, List.of(conceptOnly, overlap), 20);

        assertEquals(20, fused.size());
        assertTrue(fused.stream().anyMatch(source -> Long.valueOf(55L).equals(source.getDocId())));
        assertEquals(1L, fused.stream()
                .filter(source -> Long.valueOf(1L).equals(source.getDocId()))
                .count());
    }

    @Test
    void reservesCandidatesFromEachEvidenceRequirementLane() {
        KeywordSearchServiceImpl service = new KeywordSearchServiceImpl(null, null);
        List<RagSourceVO> lexical = new ArrayList<>();
        for (long id = 1; id <= 20; id++) {
            lexical.add(source(id, "词面候选" + id));
        }
        RagSourceVO condition = source(30L, "相机成色规则");
        RagSourceVO pricing = source(42L, "相机快门次数折价规则");
        RagSourceVO citation = source(61L, "风险证据回传");

        List<RagSourceVO> fused = service.fuseKeywordLanes(
                lexical,
                List.of(),
                List.of(List.of(condition), List.of(pricing), List.of(citation)),
                20);

        assertTrue(fused.stream().anyMatch(source -> Long.valueOf(30L).equals(source.getDocId())));
        assertTrue(fused.stream().anyMatch(source -> Long.valueOf(42L).equals(source.getDocId())));
        assertTrue(fused.stream().anyMatch(source -> Long.valueOf(61L).equals(source.getDocId())));
    }

    private RagSourceVO source(Long id, String title) {
        RagSourceVO source = new RagSourceVO();
        source.setDocId(id);
        source.setChunkId(id * 10);
        source.setDocTitle(title);
        source.setSnippet(title);
        source.setScore(1.0D);
        return source;
    }
}
