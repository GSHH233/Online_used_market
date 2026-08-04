package org.example.online_used_marketplace.modules.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.modules.ai.entity.KnowledgeChunk;
import org.example.online_used_marketplace.modules.ai.entity.KnowledgeDoc;
import org.example.online_used_marketplace.modules.ai.mapper.KnowledgeChunkMapper;
import org.example.online_used_marketplace.modules.ai.mapper.KnowledgeDocMapper;
import org.example.online_used_marketplace.modules.ai.service.KnowledgeStorageConsistencyService;
import org.example.online_used_marketplace.modules.ai.service.VectorStore;
import org.example.online_used_marketplace.modules.ai.vo.StorageConsistencyDocVO;
import org.example.online_used_marketplace.modules.ai.vo.StorageConsistencyReportVO;
import org.example.online_used_marketplace.modules.ai.vo.StorageConsistencySummaryVO;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.Criteria;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class KnowledgeStorageConsistencyServiceImpl implements KnowledgeStorageConsistencyService {

    private final KnowledgeDocMapper knowledgeDocMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final ObjectProvider<ElasticsearchOperations> elasticsearchOperationsProvider;
    private final VectorStore vectorStore;

    @Override
    public StorageConsistencyReportVO inspect(Long docId, Integer sampleDocs, Integer perDocLimit) {
        int limit = normalizeLimit(perDocLimit);
        List<Long> docIds = resolveDocIds(docId, sampleDocs);
        List<StorageConsistencyDocVO> docs = new ArrayList<>();
        int consistentDocs = 0;
        for (Long id : docIds) {
            StorageConsistencyDocVO one = inspectDoc(id, limit);
            docs.add(one);
            if (Boolean.TRUE.equals(one.getConsistent())) {
                consistentDocs++;
            }
        }

        StorageConsistencyReportVO report = new StorageConsistencyReportVO();
        report.setSummary(buildSummary(docIds.size(), consistentDocs));
        report.setDocs(docs);
        return report;
    }

    @Override
    public StorageConsistencyDocVO inspectDoc(Long docId, Integer perDocLimit) {
        int limit = normalizeLimit(perDocLimit);
        List<Long> dbChunkIds = dbChunkIds(docId, limit);
        List<Long> esChunkIds = esChunkIds(docId, limit);
        List<Long> milvusChunkIds = milvusChunkIds(docId, limit);

        Set<Long> db = new LinkedHashSet<>(dbChunkIds);
        Set<Long> es = new LinkedHashSet<>(esChunkIds);
        Set<Long> milvus = new LinkedHashSet<>(milvusChunkIds);

        StorageConsistencyDocVO one = new StorageConsistencyDocVO();
        one.setDocId(docId);
        one.setDbCount(db.size());
        one.setEsCount(es.size());
        one.setMilvusCount(milvus.size());
        one.setDbChunkIds(dbChunkIds);
        one.setEsChunkIds(esChunkIds);
        one.setMilvusChunkIds(milvusChunkIds);
        one.setMissingInEs(diff(db, es));
        one.setMissingInMilvus(diff(db, milvus));
        one.setExtraInEs(diff(es, db));
        one.setExtraInMilvus(diff(milvus, db));
        one.setConsistent(!db.isEmpty() && db.equals(es) && db.equals(milvus));
        return one;
    }

    @Override
    public List<Long> resolveDocIds(Long docId, Integer sampleDocs) {
        if (docId != null && docId > 0) {
            return List.of(docId);
        }
        int sample = sampleDocs == null ? 10 : Math.max(1, Math.min(sampleDocs, 100));
        QueryWrapper<KnowledgeDoc> wrapper = new QueryWrapper<>();
        wrapper.select("id");
        wrapper.orderByDesc("id");
        wrapper.last("limit " + sample);
        return knowledgeDocMapper.selectList(wrapper).stream()
                .map(KnowledgeDoc::getId)
                .filter(id -> id != null && id > 0)
                .toList();
    }

    private int normalizeLimit(Integer perDocLimit) {
        return perDocLimit == null || perDocLimit <= 0 ? 200 : Math.min(perDocLimit, 10_000);
    }

    private List<Long> dbChunkIds(Long docId, int limit) {
        QueryWrapper<KnowledgeChunk> wrapper = new QueryWrapper<>();
        wrapper.select("id");
        wrapper.eq("doc_id", docId);
        wrapper.orderByAsc("id");
        wrapper.last("limit " + limit);
        return knowledgeChunkMapper.selectList(wrapper).stream()
                .map(KnowledgeChunk::getId)
                .filter(id -> id != null && id > 0)
                .sorted()
                .toList();
    }

    private List<Long> esChunkIds(Long docId, int limit) {
        ElasticsearchOperations esOps = elasticsearchOperationsProvider.getIfAvailable();
        if (esOps == null) {
            return List.of();
        }
        try {
            IndexOperations indexOps = esOps.indexOps(KnowledgeChunkEsDoc.class);
            if (!indexOps.exists()) {
                return List.of();
            }
            Criteria criteria = new Criteria("docId").is(docId);
            CriteriaQuery query = new CriteriaQuery(criteria);
            query.setPageable(PageRequest.of(0, limit));
            SearchHits<KnowledgeChunkEsDoc> hits = esOps.search(query, KnowledgeChunkEsDoc.class);
            return hits.stream()
                    .map(SearchHit::getContent)
                    .filter(doc -> doc != null && doc.getChunkId() != null)
                    .map(KnowledgeChunkEsDoc::getChunkId)
                    .sorted()
                    .toList();
        } catch (Exception ex) {
            return List.of();
        }
    }

    private List<Long> milvusChunkIds(Long docId, int limit) {
        try {
            return vectorStore.debugQueryByDocId(docId, limit).stream()
                    .map(row -> row.get("chunk_id"))
                    .map(this::toLong)
                    .filter(id -> id != null && id > 0)
                    .sorted()
                    .toList();
        } catch (Exception ex) {
            return List.of();
        }
    }

    private List<Long> diff(Set<Long> left, Set<Long> right) {
        return left.stream()
                .filter(id -> !right.contains(id))
                .sorted()
                .toList();
    }

    private StorageConsistencySummaryVO buildSummary(int totalDocs, int consistentDocs) {
        StorageConsistencySummaryVO summary = new StorageConsistencySummaryVO();
        summary.setCheckedDocs(totalDocs);
        summary.setConsistentDocs(consistentDocs);
        summary.setInconsistentDocs(Math.max(0, totalDocs - consistentDocs));
        summary.setPassRate(totalDocs == 0 ? 0D : consistentDocs * 1D / totalDocs);
        summary.setStorage(List.of("db", "es", "milvus"));
        return summary;
    }

    private Long toLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
