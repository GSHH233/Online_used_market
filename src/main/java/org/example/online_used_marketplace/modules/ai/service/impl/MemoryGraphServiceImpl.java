package org.example.online_used_marketplace.modules.ai.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.modules.ai.config.MemoryGraphPgProperties;
import org.example.online_used_marketplace.modules.ai.service.MemoryGraphService;
import org.example.online_used_marketplace.modules.ai.vo.MemoryGraphEdgeVO;
import org.example.online_used_marketplace.modules.ai.vo.MemoryGraphExtractResponseVO;
import org.example.online_used_marketplace.modules.ai.vo.MemoryGraphNodeVO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
public class MemoryGraphServiceImpl implements MemoryGraphService {

    private final JdbcTemplate jdbcTemplate;
    private final MemoryGraphPgProperties properties;
    private final ObjectMapper objectMapper;

    public MemoryGraphServiceImpl(
            @Qualifier("memoryGraphJdbcTemplate") JdbcTemplate jdbcTemplate,
            MemoryGraphPgProperties properties,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void initSchema() {
        if (!Boolean.TRUE.equals(properties.getEnabled()) || !Boolean.TRUE.equals(properties.getAutoInit())) {
            return;
        }
        try (InputStream in = new ClassPathResource("sql/memory_graph_pg16_init.sql").getInputStream()) {
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            jdbcTemplate.execute(sql);
            log.info("[MemoryGraph] PG schema initialized.");
        } catch (Exception ex) {
            log.warn("[MemoryGraph] PG schema init skipped/failed: {}", ex.getMessage());
        }
    }

    @Override
    public MemoryGraphExtractResponseVO extractAndSave(String userId, String sessionId, String content, String source) {
        String normalizedUserId = StringUtils.hasText(userId) ? userId.trim() : "anonymous";
        String normalizedSessionId = StringUtils.hasText(sessionId) ? sessionId.trim() : "";
        String normalizedSource = StringUtils.hasText(source) ? source.trim() : "debug";
        List<NodeSpec> nodes = extractNodes(content);
        List<EdgeSpec> edges = extractEdges(nodes, content);

        Map<String, MemoryGraphNodeVO> savedNodes = new LinkedHashMap<>();
        for (NodeSpec node : nodes) {
            MemoryGraphNodeVO saved = upsertNode(normalizedUserId, normalizedSessionId, node, normalizedSource);
            savedNodes.put(node.key(), saved);
        }

        List<MemoryGraphEdgeVO> savedEdges = new ArrayList<>();
        for (EdgeSpec edge : edges) {
            MemoryGraphNodeVO from = savedNodes.get(edge.from().key());
            MemoryGraphNodeVO to = savedNodes.get(edge.to().key());
            if (from == null || to == null || Objects.equals(from.getId(), to.getId())) {
                continue;
            }
            savedEdges.add(upsertEdge(normalizedUserId, normalizedSessionId, from, to, edge, normalizedSource));
        }

        MemoryGraphExtractResponseVO response = new MemoryGraphExtractResponseVO();
        response.setUserId(normalizedUserId);
        response.setSessionId(normalizedSessionId);
        response.setNodes(new ArrayList<>(savedNodes.values()));
        response.setEdges(savedEdges);
        response.setNodeCount(savedNodes.size());
        response.setEdgeCount(savedEdges.size());
        return response;
    }

    @Override
    public List<MemoryGraphNodeVO> listNodes(String userId, String topic, Integer limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id,user_id,session_id,node_type,label,content,topic,importance,confidence,source,metadata_json::text,created_time,updated_time
                FROM ai_memory_node
                WHERE user_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(safeUser(userId));
        if (StringUtils.hasText(topic)) {
            sql.append(" AND topic = ?");
            args.add(topic.trim());
        }
        sql.append(" ORDER BY updated_time DESC LIMIT ?");
        args.add(normalizeLimit(limit));
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            MemoryGraphNodeVO vo = new MemoryGraphNodeVO();
            vo.setId(rs.getLong("id"));
            vo.setUserId(rs.getString("user_id"));
            vo.setSessionId(rs.getString("session_id"));
            vo.setNodeType(rs.getString("node_type"));
            vo.setLabel(rs.getString("label"));
            vo.setContent(rs.getString("content"));
            vo.setTopic(rs.getString("topic"));
            vo.setImportance(rs.getDouble("importance"));
            vo.setConfidence(rs.getDouble("confidence"));
            vo.setSource(rs.getString("source"));
            vo.setMetadata(readMap(rs.getString("metadata_json")));
            vo.setCreatedTime(toLocalDateTime(rs.getTimestamp("created_time")));
            vo.setUpdatedTime(toLocalDateTime(rs.getTimestamp("updated_time")));
            return vo;
        }, args.toArray());
    }

    @Override
    public List<MemoryGraphEdgeVO> listEdges(String userId, String fromLabel, String toLabel, Integer limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT e.id,e.user_id,e.session_id,e.from_node_id,e.to_node_id,fn.label AS from_label,tn.label AS to_label,
                       e.edge_type,e.weight,e.confidence,e.evidence,e.source,e.metadata_json::text,e.created_time,e.updated_time
                FROM ai_memory_edge e
                JOIN ai_memory_node fn ON e.from_node_id = fn.id
                JOIN ai_memory_node tn ON e.to_node_id = tn.id
                WHERE e.user_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(safeUser(userId));
        if (StringUtils.hasText(fromLabel)) {
            sql.append(" AND fn.label ILIKE ?");
            args.add("%" + fromLabel.trim() + "%");
        }
        if (StringUtils.hasText(toLabel)) {
            sql.append(" AND tn.label ILIKE ?");
            args.add("%" + toLabel.trim() + "%");
        }
        sql.append(" ORDER BY e.updated_time DESC LIMIT ?");
        args.add(normalizeLimit(limit));
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            MemoryGraphEdgeVO vo = new MemoryGraphEdgeVO();
            vo.setId(rs.getLong("id"));
            vo.setUserId(rs.getString("user_id"));
            vo.setSessionId(rs.getString("session_id"));
            vo.setFromNodeId(rs.getLong("from_node_id"));
            vo.setToNodeId(rs.getLong("to_node_id"));
            vo.setFromLabel(rs.getString("from_label"));
            vo.setToLabel(rs.getString("to_label"));
            vo.setEdgeType(rs.getString("edge_type"));
            vo.setWeight(rs.getDouble("weight"));
            vo.setConfidence(rs.getDouble("confidence"));
            vo.setEvidence(rs.getString("evidence"));
            vo.setSource(rs.getString("source"));
            vo.setMetadata(readMap(rs.getString("metadata_json")));
            vo.setCreatedTime(toLocalDateTime(rs.getTimestamp("created_time")));
            vo.setUpdatedTime(toLocalDateTime(rs.getTimestamp("updated_time")));
            return vo;
        }, args.toArray());
    }

    private MemoryGraphNodeVO upsertNode(String userId, String sessionId, NodeSpec node, String source) {
        Map<String, Object> metadata = Map.of("extractor", "rule_v1");
        String metadataJson = writeJson(metadata);
        Long id = queryNodeId(userId, node.nodeType(), node.label());
        if (id == null) {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement("""
                        INSERT INTO ai_memory_node(user_id,session_id,node_type,label,content,topic,importance,confidence,source,metadata_json)
                        VALUES (?,?,?,?,?,?,?,?,?,?::jsonb)
                        """, Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, userId);
                ps.setString(2, sessionId);
                ps.setString(3, node.nodeType());
                ps.setString(4, node.label());
                ps.setString(5, node.content());
                ps.setString(6, node.topic());
                ps.setDouble(7, node.importance());
                ps.setDouble(8, node.confidence());
                ps.setString(9, source);
                ps.setString(10, metadataJson);
                return ps;
            }, keyHolder);
            id = generatedId(keyHolder, "id");
            if (id == null) {
                id = queryNodeId(userId, node.nodeType(), node.label());
            }
        } else {
            jdbcTemplate.update("""
                    UPDATE ai_memory_node
                    SET session_id=?,content=?,topic=?,importance=GREATEST(importance,?),confidence=GREATEST(confidence,?),source=?,metadata_json=?::jsonb
                    WHERE id=?
                    """, sessionId, node.content(), node.topic(), node.importance(), node.confidence(), source, metadataJson, id);
        }
        Long nodeId = id;
        return listNodes(userId, null, 500).stream()
                .filter(one -> Objects.equals(one.getId(), nodeId))
                .findFirst()
                .orElseGet(() -> {
                    MemoryGraphNodeVO vo = new MemoryGraphNodeVO();
                    vo.setId(nodeId);
                    vo.setUserId(userId);
                    vo.setSessionId(sessionId);
                    vo.setNodeType(node.nodeType());
                    vo.setLabel(node.label());
                    vo.setContent(node.content());
                    vo.setTopic(node.topic());
                    vo.setImportance(node.importance());
                    vo.setConfidence(node.confidence());
                    vo.setSource(source);
                    vo.setMetadata(metadata);
                    return vo;
                });
    }

    private MemoryGraphEdgeVO upsertEdge(String userId, String sessionId, MemoryGraphNodeVO from, MemoryGraphNodeVO to, EdgeSpec edge, String source) {
        Map<String, Object> metadata = Map.of("extractor", "rule_v1");
        String metadataJson = writeJson(metadata);
        Long id = queryEdgeId(userId, from.getId(), to.getId(), edge.edgeType());
        if (id == null) {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement("""
                        INSERT INTO ai_memory_edge(user_id,session_id,from_node_id,to_node_id,edge_type,weight,confidence,evidence,source,metadata_json)
                        VALUES (?,?,?,?,?,?,?,?,?,?::jsonb)
                        """, Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, userId);
                ps.setString(2, sessionId);
                ps.setLong(3, from.getId());
                ps.setLong(4, to.getId());
                ps.setString(5, edge.edgeType());
                ps.setDouble(6, edge.weight());
                ps.setDouble(7, edge.confidence());
                ps.setString(8, edge.evidence());
                ps.setString(9, source);
                ps.setString(10, metadataJson);
                return ps;
            }, keyHolder);
            id = generatedId(keyHolder, "id");
            if (id == null) {
                id = queryEdgeId(userId, from.getId(), to.getId(), edge.edgeType());
            }
        } else {
            jdbcTemplate.update("""
                    UPDATE ai_memory_edge
                    SET session_id=?,weight=GREATEST(weight,?),confidence=GREATEST(confidence,?),evidence=?,source=?,metadata_json=?::jsonb
                    WHERE id=?
                    """, sessionId, edge.weight(), edge.confidence(), edge.evidence(), source, metadataJson, id);
        }
        MemoryGraphEdgeVO vo = new MemoryGraphEdgeVO();
        vo.setId(id);
        vo.setUserId(userId);
        vo.setSessionId(sessionId);
        vo.setFromNodeId(from.getId());
        vo.setToNodeId(to.getId());
        vo.setFromLabel(from.getLabel());
        vo.setToLabel(to.getLabel());
        vo.setEdgeType(edge.edgeType());
        vo.setWeight(edge.weight());
        vo.setConfidence(edge.confidence());
        vo.setEvidence(edge.evidence());
        vo.setSource(source);
        vo.setMetadata(metadata);
        return vo;
    }

    private List<NodeSpec> extractNodes(String content) {
        if (!StringUtils.hasText(content)) {
            return List.of();
        }
        String lower = content.toLowerCase(Locale.ROOT);
        Map<String, NodeSpec> nodes = new LinkedHashMap<>();
        addIfContains(nodes, lower, "Java", "SKILL", "Java", "java", "tech", 0.7, 0.85);
        addIfContains(nodes, lower, "K8s", "SKILL", "K8s", "kubernetes/k8s", "infra", 0.7, 0.85);
        addIfContains(nodes, lower, "AI Infra", "INTEREST", "AI Infra", "ai infra", "ai_infra", 0.8, 0.85);
        addIfContains(nodes, lower, "动漫", "PREFERENCE", "动漫", "anime", "entertainment", 0.3, 0.8);

        addIfContains(nodes, lower, "无发票", "FACT", "无发票", "缺少发票凭证", "evidence", 0.6, 0.9);
        addIfContains(nodes, lower, "专柜验货", "CLAIM", "专柜验货", "专柜验货承诺", "authentic", 0.6, 0.9);
        addIfContains(nodes, lower, "假一赔十", "CLAIM", "假一赔十", "绝对化赔付承诺", "authentic", 0.6, 0.9);
        addIfContains(nodes, lower, "凭证风险", "RISK", "凭证风险", "凭证不足风险", "risk", 0.8, 0.9);
        addIfContains(nodes, lower, "正品承诺风险", "RISK", "正品承诺风险", "正品承诺/真伪证明风险", "risk", 0.8, 0.9);
        addIfContains(nodes, lower, "绝对化承诺风险", "RISK", "绝对化承诺风险", "假一赔十等绝对化承诺风险", "risk", 0.8, 0.9);

        addIfContains(nodes, lower, "RTX3060", "PRODUCT", "RTX3060", "RTX3060", "gpu", 0.7, 0.9);
        addIfContains(nodes, lower, "显卡", "CATEGORY", "显卡", "显卡品类", "gpu", 0.7, 0.9);
        addIfContains(nodes, lower, "跑分截图", "EVIDENCE", "跑分截图", "跑分截图", "evidence", 0.6, 0.85);
        addIfContains(nodes, lower, "温度偏高", "FACT", "温度偏高", "显卡温度偏高", "gpu", 0.7, 0.9);
        addIfContains(nodes, lower, "矿卡风险", "RISK", "矿卡风险", "矿卡风险", "risk", 0.8, 0.9);
        return new ArrayList<>(nodes.values());
    }

    private List<EdgeSpec> extractEdges(List<NodeSpec> nodes, String content) {
        Map<String, NodeSpec> byLabel = new LinkedHashMap<>();
        for (NodeSpec node : nodes) {
            byLabel.put(node.label(), node);
        }
        List<EdgeSpec> edges = new ArrayList<>();
        addEdgeIfPresent(edges, byLabel, "Java", "K8s", "LEARNING_PATH", content, 0.9, 0.85);
        addEdgeIfPresent(edges, byLabel, "K8s", "AI Infra", "RELATED_TO", content, 0.9, 0.85);

        addEdgeIfPresent(edges, byLabel, "无发票", "凭证风险", "CAUSES_RISK", content, 1.0, 0.9);
        addEdgeIfPresent(edges, byLabel, "专柜验货", "正品承诺风险", "CAUSES_RISK", content, 1.0, 0.9);
        addEdgeIfPresent(edges, byLabel, "假一赔十", "绝对化承诺风险", "CAUSES_RISK", content, 1.0, 0.9);

        addEdgeIfPresent(edges, byLabel, "RTX3060", "显卡", "IS_A", content, 1.0, 0.9);
        addEdgeIfPresent(edges, byLabel, "温度偏高", "矿卡风险", "CAUSES_RISK", content, 1.0, 0.9);
        addEdgeIfPresent(edges, byLabel, "跑分截图", "矿卡风险", "CANNOT_RULE_OUT", content, 0.8, 0.85);
        return edges;
    }

    private void addIfContains(Map<String, NodeSpec> nodes, String lower, String keyword, String nodeType, String label,
                               String content, String topic, double importance, double confidence) {
        if (lower.contains(keyword.toLowerCase(Locale.ROOT))) {
            NodeSpec node = new NodeSpec(nodeType, label, content, topic, importance, confidence);
            nodes.putIfAbsent(node.key(), node);
        }
    }

    private void addEdgeIfPresent(List<EdgeSpec> edges, Map<String, NodeSpec> byLabel, String from, String to, String edgeType,
                                  String evidence, double weight, double confidence) {
        NodeSpec fromNode = byLabel.get(from);
        NodeSpec toNode = byLabel.get(to);
        if (fromNode != null && toNode != null) {
            edges.add(new EdgeSpec(fromNode, toNode, edgeType, weight, confidence, evidence == null ? "" : evidence));
        }
    }

    private Long queryNodeId(String userId, String nodeType, String label) {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM ai_memory_node WHERE user_id=? AND node_type=? AND label=? LIMIT 1",
                Long.class, userId, nodeType, label);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private Long queryEdgeId(String userId, Long fromNodeId, Long toNodeId, String edgeType) {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM ai_memory_edge WHERE user_id=? AND from_node_id=? AND to_node_id=? AND edge_type=? LIMIT 1",
                Long.class, userId, fromNodeId, toNodeId, edgeType);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private Long generatedId(KeyHolder keyHolder, String keyName) {
        if (keyHolder == null) {
            return null;
        }
        try {
            Number key = keyHolder.getKey();
            return key == null ? null : key.longValue();
        } catch (Exception ignore) {
            Map<String, Object> keys = keyHolder.getKeys();
            if (keys == null || keys.isEmpty()) {
                return null;
            }
            Object value = keys.get(keyName);
            if (value instanceof Number number) {
                return number.longValue();
            }
            try {
                return value == null ? null : Long.parseLong(String.valueOf(value));
            } catch (Exception ex) {
                return null;
            }
        }
    }

    private String safeUser(String userId) {
        return StringUtils.hasText(userId) ? userId.trim() : "anonymous";
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return 50;
        }
        return Math.min(limit, 500);
    }

    private LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private Map<String, Object> readMap(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            return Collections.emptyMap();
        }
    }

    private String writeJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map == null ? Collections.emptyMap() : map);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private record NodeSpec(String nodeType, String label, String content, String topic, double importance, double confidence) {
        String key() {
            return nodeType + ":" + label;
        }
    }

    private record EdgeSpec(NodeSpec from, NodeSpec to, String edgeType, double weight, double confidence, String evidence) {
    }
}
