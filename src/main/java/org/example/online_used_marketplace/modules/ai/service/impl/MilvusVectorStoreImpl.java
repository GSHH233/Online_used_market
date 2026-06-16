package org.example.online_used_marketplace.modules.ai.service.impl;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.index.request.CreateIndexReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.BaseVector;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.DeleteResp;
import io.milvus.v2.service.vector.response.QueryResp;
import io.milvus.v2.service.vector.response.SearchResp;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.modules.ai.config.AiVectorStoreProperties;
import org.example.online_used_marketplace.modules.ai.service.VectorStore;
import org.example.online_used_marketplace.modules.ai.vo.RagSourceVO;
import org.example.online_used_marketplace.modules.ai.vo.VectorRecordVO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;

/**
 * Milvus 向量存储骨架实现（Docker 部署）
 *
 * 说明：
 * - 当前仅提供连接与初始化骨架，便于后续接入真实 SDK 调用。
 * - 当前 search/upsert/delete 仍为占位返回，避免影响现有联调。
 */
@Slf4j
@Service
@RequiredArgsConstructor
//@ConditionalOnClass(name = "io.milvus.v2.client.MilvusClientV2")
public class MilvusVectorStoreImpl implements VectorStore {

    private final AiVectorStoreProperties properties;
    private volatile MilvusClientV2 client;
    private volatile boolean milvusReady = false;

    @PostConstruct
    public void init() {
        AiVectorStoreProperties.Milvus milvus = properties.getMilvus();
        log.info("[MilvusSkeleton] provider={}, target={}:{}, collection={}, dim={}, autoInit={}",
                properties.getProvider(),
                milvus.getHost(),
                milvus.getPort(),
                milvus.getCollectionName(),
                milvus.getDimension(),
                milvus.getAutoInitCollection());

        if (properties.getProvider() == null || !"milvus".equalsIgnoreCase(properties.getProvider().trim())) {
            log.info("[MilvusSkeleton] provider is not milvus, skip init.");
            return;
        }

        try {
            initClient();
            if (Boolean.TRUE.equals(milvus.getAutoInitCollection())) {
                ensureCollection();
                ensureIndexSafe();
            }
            ensureLoaded();
            milvusReady = true;
            log.info("[Milvus] init success, collection={}", milvus.getCollectionName());
        } catch (Exception e) {
            milvusReady = false;
            log.error("[Milvus] init failed, fallback to empty-results mode. reason={}", e.getMessage(), e);
        }
    }

    @Override
    public void upsert(List<VectorRecordVO> records) {
        if (!isReady()) {
            log.warn("[Milvus][upsert] skipped: milvus not ready");
            return;
        }
        if (records == null || records.isEmpty()) {
            return;
        }

        int dim = properties.getMilvus().getDimension();
        List<JsonObject> rows = new ArrayList<>();

        for (VectorRecordVO record : records) {
            if (record == null) {
                log.warn("[Milvus][upsert] skip record: record is null");
                continue;
            }
            if (record.getDocId() == null || record.getChunkId() == null) {
                log.warn("[Milvus][upsert] skip record: docId/chunkId is null, docId={}, chunkId={}",
                        record.getDocId(), record.getChunkId());
                continue;
            }
            if (record.getVector() == null) {
                log.warn("[Milvus][upsert] skip record: vector is null, docId={}, chunkId={}",
                        record.getDocId(), record.getChunkId());
                continue;
            }
            if (record.getVector().length != dim) {
                log.warn("[Milvus][upsert] skip record: vector dim mismatch, docId={}, chunkId={}, vectorDim={}, expectedDim={}",
                        record.getDocId(), record.getChunkId(), record.getVector().length, dim);
                continue;
            }

            JsonObject row = new JsonObject();
            // 与 ensureCollection 中字段名严格一致
            row.addProperty("id", String.valueOf(record.getDocId()+"_"+record.getChunkId()));
            row.addProperty("doc_id", record.getDocId());
            row.addProperty("chunk_id", record.getChunkId());
            if (record.getContent() == null) {
                row.addProperty("content", "");
            } else if (record.getContent().length() > 4000) {
                row.addProperty("content", record.getContent().substring(0, 4000));
                log.warn("[Milvus][upsert] content truncated to 4000, docId={}, chunkId={}, originalLen={}",
                        record.getDocId(), record.getChunkId(), record.getContent().length());
            } else {
                row.addProperty("content", record.getContent());
            }

            String sourceType = (record.getMetadata() == null || record.getMetadata().getSourceType() == null)
                    ? ""
                    : record.getMetadata().getSourceType();
            String category = (record.getMetadata() == null || record.getMetadata().getCategory() == null)
                    ? ""
                    : record.getMetadata().getCategory();
            String riskLevel = (record.getMetadata() == null || record.getMetadata().getRiskLevel() == null)
                    ? ""
                    : record.getMetadata().getRiskLevel();

            row.addProperty("source_type", sourceType);
            row.addProperty("category", category);
            row.addProperty("risk_level", riskLevel);
            String metadataJson="{}";
            if (record.getMetadata()!=null){
                metadataJson=new Gson().toJson(record.getMetadata());
            }
            row.addProperty("metadata_json",metadataJson);

            JsonArray embedding = new JsonArray();
            for (float v : record.getVector()) {
                embedding.add(v);
            }


            row.add("embedding", embedding);

            rows.add(row);
        }

        if (rows.isEmpty()) {
            log.warn("[Milvus] upsert skipped, no valid rows. inputSize={}", records.size());
            return;
        }

        try {
            UpsertReq req = UpsertReq.builder()
                    .collectionName(properties.getMilvus().getCollectionName())
                    .data(rows)
                    .build();
            client.upsert(req);
            log.info("[Milvus] upsert done. inputSize={}, upsertedSize={}", records.size(), rows.size());
        } catch (Exception e) {
            log.error("[Milvus][upsert] failed, inputSize={}, validSize={}, reason={}", records.size(), rows.size(), e.getMessage(), e);
        }
    }

    @Override
    public List<RagSourceVO> search(float[] queryVector, int topK, Map<String, Object> filters) {
        if (!isReady()) {
            log.warn("[Milvus][search] skipped: milvus not ready");
            return Collections.emptyList();
        }
        if (queryVector==null||queryVector.length!=properties.getMilvus().getDimension()){
            return Collections.emptyList();
        }
        topK=(topK<=0)?5:Math.min(topK,50);
        List<Float> oneQuery=new ArrayList<>(queryVector.length);
        for (float v:queryVector){
            oneQuery.add(v);
        }
        BaseVector vec=new FloatVec(oneQuery);
        List<BaseVector> data=Collections.singletonList(vec);
        String s = buildFilterExpr(filters);
        SearchReq.SearchReqBuilder reqBuilder = SearchReq.builder()
                .collectionName(properties.getMilvus().getCollectionName())
                .data(data)
                .annsField("embedding")
                .topK(topK)
                .outputFields(List.of("id", "doc_id", "chunk_id", "content", "source_type", "category", "risk_level", "metadata_json"));

        if (StringUtils.hasText(s)) {
            reqBuilder.filter(s);
        }

        try {
            SearchReq req = reqBuilder.build();
            SearchResp resp=client.search(req);

            log.debug("[MilvusSkeleton] search called, topK={}, filters={}", topK, filters);
            List<RagSourceVO> ragSourceVOS = mapSearchResp(resp);
            return dedupSources(ragSourceVOS,topK);
        } catch (Exception e) {
            log.error("[Milvus][search] failed, topK={}, filters={}, reason={}", topK, filters, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @Override
    public void deleteByDocId(Long docId) {
        if (!isReady()) {
            log.warn("[Milvus][deleteByDocId] skipped: milvus not ready");
            return;
        }
        if (docId==null){
            return;
        }
        try {
            DeleteReq req=DeleteReq.builder()
                    .collectionName(properties.getMilvus().getCollectionName())
                    .filter("doc_id == " + docId)
                    .build();
            DeleteResp delete = client.delete(req);
            log.info("[Milvus][deleteByDocId] deleted={}, docId={}", delete.getDeleteCnt(), docId);
            log.debug("[MilvusSkeleton] deleteByDocId called, docId={}", docId);
        } catch (Exception e) {
            log.error("[Milvus][deleteByDocId] failed, docId={}, reason={}", docId, e.getMessage(), e);
        }
    }

    @Override
    public List<Map<String, Object>> debugQueryByDocId(Long docId, Integer limit) {
        if (!isReady() || docId == null) {
            return Collections.emptyList();
        }
        try {
            QueryReq req=QueryReq.builder()
                    .collectionName(properties.getMilvus().getCollectionName())
                    .filter("doc_id == " + docId)
                    .outputFields(List.of("id", "doc_id", "chunk_id", "content", "source_type", "category", "risk_level", "metadata_json"))
                    .limit(limit == null || limit <= 0 ? 20 : limit)
                    .build();
            QueryResp resp = client.query(req);
            if (resp == null || resp.getQueryResults() == null) {
                return Collections.emptyList();
            }
            List<Map<String, Object>> list=new ArrayList<>();
            for (QueryResp.QueryResult oneQueryHits: resp.getQueryResults()){
                list.add(oneQueryHits.getEntity());
            }

            return list;
        } catch (Exception e) {
            log.error("[Milvus][debugQueryByDocId] failed, docId={}, reason={}", docId, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    private void initClient(){
        AiVectorStoreProperties.Milvus milvus = properties.getMilvus();
        ConnectConfig config=ConnectConfig.builder()
                .uri("http://"+milvus.getHost()+":"+milvus.getPort())
                .build();
        client=new MilvusClientV2(config);
        log.info("[Milvus] client connected, uri=http://{}:{}", milvus.getHost(), milvus.getPort());
    }

    private boolean isReady() {
        return milvusReady && client != null;
    }
    private void ensureCollection(){
        String collection=properties.getMilvus().getCollectionName();
        Integer dim=properties.getMilvus().getDimension();
        Boolean exists=client.hasCollection(
                HasCollectionReq.builder()
                        .collectionName(collection)
                        .build()
        );
        if (Boolean.TRUE.equals(exists)){

            log.info("Milvus collection already exists: {}", collection);
            return;
        }
        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder().build();

        schema.addField(AddFieldReq.builder()
                .fieldName("id")
                .dataType(DataType.VarChar)
                .isPrimaryKey(true)
                .maxLength(128)
                .build());

        schema.addField(AddFieldReq.builder()
                .fieldName("doc_id")
                .dataType(DataType.Int64)
                .build());

        schema.addField(AddFieldReq.builder()
                .fieldName("chunk_id")
                .dataType(DataType.Int64)
                .build());

        schema.addField(AddFieldReq.builder()
                .fieldName("content")
                .dataType(DataType.VarChar)
                .maxLength(4096)
                .build());

        schema.addField(AddFieldReq.builder()
                .fieldName("source_type")
                .dataType(DataType.VarChar)
                .maxLength(64)
                .build());

        schema.addField(AddFieldReq.builder()
                .fieldName("category")
                .dataType(DataType.VarChar)
                .maxLength(128)
                .build());

        schema.addField(AddFieldReq.builder()
                .fieldName("risk_level")
                .dataType(DataType.VarChar)
                .maxLength(64)
                .build());

        schema.addField(AddFieldReq.builder()
                .fieldName("metadata_json")
                .dataType(DataType.VarChar)
                .maxLength(8192)
                .build());

        schema.addField(AddFieldReq.builder()
                .fieldName("embedding")
                .dataType(DataType.FloatVector)
                .dimension(dim)
                .build());

        client.createCollection(
                CreateCollectionReq.builder()
                        .collectionName(collection)
                        .collectionSchema(schema)
                        .build()
        );
    }

    private void ensureIndexSafe(){
        String collectionName = properties.getMilvus().getCollectionName();
        HasCollectionReq hasCollectionReq = HasCollectionReq.builder().collectionName(collectionName).build();
        Boolean exists = client.hasCollection(hasCollectionReq);
        if (!Boolean.TRUE.equals(exists)) {
            log.warn("[Milvus] collection not found before createIndex, skip. collection={}", collectionName);
            return;
        }

        try {
            IndexParam indexParam=IndexParam.builder()
                    .fieldName("embedding")
                    .indexName("idx_embedding")
                    .indexType(IndexParam.IndexType.AUTOINDEX)
                    .metricType(IndexParam.MetricType.COSINE)
                    .build();
            List<IndexParam> list=new ArrayList<>();
            list.add(indexParam);
            CreateIndexReq req=CreateIndexReq.builder()
                    .collectionName(collectionName)
                    .indexParams(list)
                    .build();
            client.createIndex(req);
            log.info("[Milvus] create index success, collection={}", collectionName);
        } catch (Exception e) {
            log.warn("[Milvus] create index failed, continue. collection={}, reason={}", collectionName, e.getMessage());
        }
    }
    private void ensureLoaded(){
        String collectionName = properties.getMilvus().getCollectionName();
        LoadCollectionReq req = LoadCollectionReq.builder().collectionName(collectionName).build();
        HasCollectionReq hasCollectionReq=HasCollectionReq.builder().collectionName(collectionName).build();
        Boolean exists = client.hasCollection(hasCollectionReq);
        if (!Boolean.TRUE.equals(exists)) {
            log.warn("[Milvus] collection not exists, skip load, collection={}", collectionName);
            return;
        }
        try {
            client.loadCollection(req);
            log.info("[Milvus] collection loaded, collection={}", collectionName);
        } catch (Exception e) {
            log.warn("[Milvus] load collection failed, collection={}, reason={}", collectionName, e.getMessage());
        }
    }
    private String buildFilterExpr(Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return null;
        }
        List<String> clauses = new ArrayList<>();
        Object docId = filters.get("docId");
        if (docId != null) {
            clauses.add("doc_id == " + docId);
        }
        Object sourceType = filters.get("sourceType");
        if (sourceType != null && !sourceType.toString().isBlank()) {
            clauses.add("source_type == \"" + escape(sourceType.toString()) + "\"");
        }
        Object category = filters.get("category");
        if (category != null && !category.toString().isBlank()) {
            clauses.add("category == \"" + escape(category.toString()) + "\"");
        }
        Object riskLevel = filters.get("riskLevel");
        if (riskLevel != null && !riskLevel.toString().isBlank()) {
            clauses.add("risk_level == \"" + escape(riskLevel.toString()) + "\"");
        }
        return clauses.isEmpty() ? null : String.join(" && ", clauses);
    }
    private String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
    private List<RagSourceVO> mapSearchResp(SearchResp resp) {
        List<RagSourceVO> out = new ArrayList<>();
        if (resp == null || resp.getSearchResults() == null) {
            return out;
        }
        for (List<SearchResp.SearchResult> oneQueryHits : resp.getSearchResults()) {
            if (oneQueryHits == null) {
                continue;
            }
            for (SearchResp.SearchResult hit : oneQueryHits) {
                if (hit == null) {
                    continue;
                }
                Map<String, Object> entity = hit.getEntity(); // 你的版本若方法名不同，用IDE替换
                if (entity == null) {
                    continue;
                }
                RagSourceVO vo = new RagSourceVO();
                vo.setDocId(toLong(entity.get("doc_id")));
                vo.setChunkId(toLong(entity.get("chunk_id")));
                vo.setSnippet(truncate(toStr(entity.get("content")), 300));
                vo.setSourceType(toStr(entity.get("source_type")));
                vo.setScore(toDouble(hit.getScore())); // 若你的版本是 distance，用 distance
                // docTitle/sourceUri 从 metadata_json 解析
                String metadataJson = toStr(entity.get("metadata_json"));
                if (metadataJson != null && !metadataJson.isBlank()) {
                    try {
                        JsonObject meta = com.google.gson.JsonParser.parseString(metadataJson).getAsJsonObject();
                        vo.setDocTitle(getAsString(meta, "docTitle"));
                        vo.setSourceUri(getAsString(meta, "sourceUri"));
                        vo.setSourceTrustLevel(getAsString(meta, "sourceTrustLevel"));
                        vo.setDocVersion(getAsString(meta, "docVersion"));
                        vo.setEffectiveFrom(getAsString(meta, "effectiveFrom"));
                        vo.setEffectiveTo(getAsString(meta, "effectiveTo"));
                        vo.setDocStatus(getAsString(meta, "docStatus"));
                        vo.setDisabledReason(getAsString(meta, "disabledReason"));
                    } catch (Exception ignore) {
                        // metadata_json 解析失败不影响主流程
                    }
                }
                out.add(vo);
            }
        }
        return out;
    }

    private Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try { return Long.valueOf(String.valueOf(v)); } catch (Exception e) { return null; }
    }
    private Double toDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.valueOf(String.valueOf(v)); } catch (Exception e) { return null; }
    }
    private String toStr(Object v) {
        return v == null ? null : String.valueOf(v);
    }
    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
    private String getAsString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return null;
        return obj.get(key).getAsString();
    }
    private List<RagSourceVO> dedupSources(List<RagSourceVO> sources, int limit) {
        if (sources == null || sources.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String,RagSourceVO> byChunkKey=new LinkedHashMap<>();
        for (RagSourceVO s:sources){
            if (s==null){
                continue;
            }
            String key=buildChunkKey(s);
            RagSourceVO old=byChunkKey.get(key);
            if (old == null || scoreOf(s) > scoreOf(old)) {
                byChunkKey.put(key, s);
            }
        }
        Map<String,RagSourceVO> bySnippetKey=new HashMap<>();
        for (RagSourceVO s:byChunkKey.values()){
            String textKey=normalizeSnippet(s.getSnippet());
            if (textKey.isEmpty()) {
                textKey = "EMPTY_SNIPPET#" + buildChunkKey(s);
            }

            RagSourceVO old=bySnippetKey.get(textKey);
            if (old == null || scoreOf(s) > scoreOf(old)) {
                bySnippetKey.put(textKey, s);
            }
        }
        List<RagSourceVO> result = new ArrayList<>(bySnippetKey.values());
        result.sort((a, b) -> Double.compare(scoreOf(b), scoreOf(a)));

        int safeLimit = (limit <= 0) ? result.size() : Math.min(limit, result.size());
        return new ArrayList<>(result.subList(0, safeLimit));

    }
    private String buildChunkKey(RagSourceVO s) {
        String doc = s.getDocId() == null ? "null" : String.valueOf(s.getDocId());
        String chunk = s.getChunkId() == null ? "null" : String.valueOf(s.getChunkId());
        return doc + "#" + chunk;
    }

    private double scoreOf(RagSourceVO s) {
        return s.getScore() == null ? Double.NEGATIVE_INFINITY : s.getScore();
    }

    private String normalizeSnippet(String snippet) {
        if (snippet == null) {
            return "";
        }
        return snippet.trim()
                .toLowerCase()
                .replaceAll("\\s+", " ");
    }
}
