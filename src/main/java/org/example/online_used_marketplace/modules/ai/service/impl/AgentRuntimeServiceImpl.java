package org.example.online_used_marketplace.modules.ai.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.ai.agent.runtime.GraphExecutionObserver;
import org.example.online_used_marketplace.modules.ai.agent.runtime.GraphExecutionOptions;
import org.example.online_used_marketplace.modules.ai.agent.runtime.AgentExecutionIdentity;
import org.example.online_used_marketplace.modules.ai.dto.AgentRuntimeStartRequestDTO;
import org.example.online_used_marketplace.modules.ai.dto.GraphAgentRequestDTO;
import org.example.online_used_marketplace.modules.ai.service.AgentRuntimeService;
import org.example.online_used_marketplace.modules.ai.service.GraphReactAgentService;
import org.example.online_used_marketplace.modules.ai.vo.AgentGraphNodeVO;
import org.example.online_used_marketplace.modules.ai.vo.AgentRuntimeNodeVO;
import org.example.online_used_marketplace.modules.ai.vo.AgentRuntimeAccessAuditVO;
import org.example.online_used_marketplace.modules.ai.vo.AgentRuntimeCheckpointVO;
import org.example.online_used_marketplace.modules.ai.vo.AgentRuntimeRunVO;
import org.example.online_used_marketplace.modules.ai.vo.GraphAgentResponseVO;
import org.example.online_used_marketplace.modules.ai.vo.ToolQueryResultVO;
import org.example.online_used_marketplace.modules.user.enums.UserRole;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;

import jakarta.annotation.PreDestroy;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@Slf4j
public class AgentRuntimeServiceImpl implements AgentRuntimeService {

    private static final Set<String> RESUMABLE = Set.of("FAILED", "PAUSED");
    private static final Set<String> TERMINAL = Set.of("COMPLETED", "CANCELLED");
    private final JdbcTemplate jdbcTemplate;
    private final JdbcTemplate mysqlJdbcTemplate;
    private final ObjectMapper objectMapper;
    private final GraphReactAgentService graphAgentService;
    private final String workerId = "runtime-" + UUID.randomUUID();
    private final ExecutorService runtimeExecutor = Executors.newFixedThreadPool(4);
    @Value("${ai.agent.runtime.fault-injection-enabled:false}")
    private boolean faultInjectionEnabled;

    public AgentRuntimeServiceImpl(@Qualifier("memoryGraphJdbcTemplate") JdbcTemplate jdbcTemplate,
                                   @Qualifier("mysqlJdbcTemplate") JdbcTemplate mysqlJdbcTemplate,
                                   ObjectMapper objectMapper,
                                   GraphReactAgentService graphAgentService) {
        this.jdbcTemplate = jdbcTemplate;
        this.mysqlJdbcTemplate = mysqlJdbcTemplate;
        this.objectMapper = objectMapper;
        this.graphAgentService = graphAgentService;
    }

    @Override
    public AgentRuntimeRunVO start(AgentRuntimeStartRequestDTO request) {
        if (request == null || !StringUtils.hasText(request.getQuestion())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "问题不能为空");
        }
        String runId = UUID.randomUUID().toString();
        String traceId = UUID.randomUUID().toString();
        AgentExecutionIdentity identity = AgentExecutionIdentity.current();
        if (!identity.authenticated()) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "Runtime启动缺少可信身份");
        }
        identity = requireCurrentOwnerAuthorization(identity.userId(), "START");
        int maxSteps = clampSteps(request.getMaxSteps());
        int failAfter = request.getFailAfterNodeCount() == null ? 0 : request.getFailAfterNodeCount();
        if (failAfter > 0 && !faultInjectionEnabled) {
            throw new BusinessException(ResultCode.FORBIDDEN, "Runtime故障注入未启用");
        }
        jdbcTemplate.update("""
                INSERT INTO agent_runtime_run(run_id,trace_id,session_id,owner_user_id,owner_role,strategy,status,question,max_steps,fault_after_nodes)
                VALUES (?,?,?,?,?,?, 'CREATED', ?,?,?)
                """, runId, traceId, safe(request.getSessionId(), 128),
                identity.userId(), identity.role(), "DAG", request.getQuestion().trim(), maxSteps, failAfter);
        submit(runId, Map.of(), "RUNNING", nextLeaseOwner());
        return get(runId);
    }

    @Override
    public AgentRuntimeRunVO get(String runId) {
        String id = requireRunId(runId);
        List<AgentRuntimeRunVO> rows = jdbcTemplate.query("""
                SELECT run_id,trace_id,owner_user_id,owner_role,strategy,status,question,answer,error_msg,max_steps,
                       created_time,updated_time,finished_at
                FROM agent_runtime_run WHERE run_id=?
                """, (rs, rowNum) -> {
            AgentRuntimeRunVO vo = new AgentRuntimeRunVO();
            vo.setRunId(rs.getString("run_id"));
            vo.setTraceId(rs.getString("trace_id"));
            vo.setOwnerUserId(rs.getString("owner_user_id"));
            vo.setOwnerRole(rs.getString("owner_role"));
            vo.setStrategy(rs.getString("strategy"));
            vo.setStatus(rs.getString("status"));
            vo.setQuestion(rs.getString("question"));
            vo.setAnswer(rs.getString("answer"));
            vo.setErrorMsg(rs.getString("error_msg"));
            vo.setMaxSteps(rs.getInt("max_steps"));
            vo.setCreatedTime(time(rs.getTimestamp("created_time")));
            vo.setUpdatedTime(time(rs.getTimestamp("updated_time")));
            vo.setFinishedAt(time(rs.getTimestamp("finished_at")));
            return vo;
        }, id);
        if (rows.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "Runtime Run不存在");
        }
        AgentRuntimeRunVO out = rows.get(0);
        out.setNodes(loadNodes(id));
        out.setTotalNodeCount(out.getNodes().size());
        out.setCompletedNodeCount((int) out.getNodes().stream().filter(one -> "SUCCEEDED".equals(one.getStatus())).count());
        List<AgentRuntimeCheckpointVO> checkpoints = checkpoints(id);
        out.setLatestCheckpoint(checkpoints.isEmpty() ? null : checkpoints.get(0));
        return out;
    }

    @Override
    public List<AgentRuntimeRunVO> list(Integer limit) {
        int size = Math.max(1, Math.min(limit == null ? 20 : limit, 100));
        List<String> ids = jdbcTemplate.query("SELECT run_id FROM agent_runtime_run ORDER BY id DESC LIMIT ?",
                (rs, rowNum) -> rs.getString(1), size);
        return ids.stream().map(this::get).toList();
    }

    @Override
    public AgentRuntimeRunVO resume(String runId) {
        AgentRuntimeRunVO current = get(runId);
        requireCurrentOwnerAuthorization(current.getOwnerUserId(), "RESUME", current.getRunId());
        if (!RESUMABLE.contains(current.getStatus())) {
            throw new BusinessException(ResultCode.CONFLICT, "当前状态不可恢复：" + current.getStatus());
        }
        String leaseOwner = nextLeaseOwner();
        int locked = jdbcTemplate.update("""
                UPDATE agent_runtime_run SET status='RESUMING',lease_owner=?,lease_until=CURRENT_TIMESTAMP+INTERVAL '120 seconds',version=version+1
                WHERE run_id=? AND status IN ('FAILED','PAUSED') AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP)
                """, leaseOwner, runId);
        if (locked == 0) {
            throw new BusinessException(ResultCode.CONFLICT, "Run正在被其他实例恢复");
        }
        submit(runId, completedObservations(runId), "RESUMING", leaseOwner);
        return get(runId);
    }

    @Override
    public AgentRuntimeRunVO pause(String runId) {
        changeState(runId, Set.of("CREATED", "PLANNING", "RUNNING", "RESUMING"), "PAUSED");
        checkpoint(runId, "PAUSED");
        return get(runId);
    }

    @Override
    public AgentRuntimeRunVO cancel(String runId) {
        changeState(runId, Set.of("CREATED", "PLANNING", "RUNNING", "RESUMING", "PAUSED", "FAILED"), "CANCELLED");
        jdbcTemplate.update("UPDATE agent_runtime_run SET finished_at=CURRENT_TIMESTAMP WHERE run_id=?", runId);
        checkpoint(runId, "CANCELLED");
        return get(runId);
    }

    @Override
    public AgentRuntimeRunVO replay(String runId, boolean liveReadonly) {
        AgentRuntimeRunVO source = get(runId);
        AgentExecutionIdentity currentOwner = requireCurrentOwnerAuthorization(
                source.getOwnerUserId(), "REPLAY", source.getRunId());
        if (!TERMINAL.contains(source.getStatus()) && !"FAILED".equals(source.getStatus())) {
            throw new BusinessException(ResultCode.CONFLICT, "仅已完成、已取消或失败的 Run 可重放");
        }
        Map<String, Object> base = jdbcTemplate.queryForMap(
                "SELECT session_id,owner_user_id,owner_role,question,max_steps FROM agent_runtime_run WHERE run_id=?", runId);
        String nextRunId = UUID.randomUUID().toString();
        String traceId = UUID.randomUUID().toString();
        String strategy = liveReadonly ? "REPLAY_LIVE_READONLY" : "REPLAY_WITH_SNAPSHOTS";
        jdbcTemplate.update("""
                INSERT INTO agent_runtime_run(run_id,trace_id,session_id,owner_user_id,owner_role,strategy,status,question,max_steps)
                VALUES (?,?,?,?,?,?, 'CREATED', ?,?)
                """, nextRunId, traceId, String.valueOf(base.getOrDefault("session_id", "")),
                String.valueOf(base.getOrDefault("owner_user_id", "")),
                currentOwner.role(), strategy,
                String.valueOf(base.get("question")), ((Number) base.get("max_steps")).intValue());
        submit(nextRunId, liveReadonly ? Map.of() : completedObservations(runId),
                "RUNNING", nextLeaseOwner());
        return get(nextRunId);
    }

    @Override
    public List<AgentRuntimeCheckpointVO> checkpoints(String runId) {
        String id = requireRunId(runId);
        return jdbcTemplate.query("""
                SELECT checkpoint_seq,reason,state_json::text,state_hash,created_time
                FROM agent_runtime_checkpoint WHERE run_id=? ORDER BY checkpoint_seq DESC
                """, (rs, rowNum) -> {
            AgentRuntimeCheckpointVO vo = new AgentRuntimeCheckpointVO();
            vo.setCheckpointSeq(rs.getInt("checkpoint_seq"));
            vo.setReason(rs.getString("reason"));
            vo.setStateJson(rs.getString("state_json"));
            vo.setStateHash(rs.getString("state_hash"));
            vo.setCreatedTime(time(rs.getTimestamp("created_time")));
            return vo;
        }, id);
    }

    @Override
    public void recordAccessAudit(String runId, String action, String decision, String reason) {
        String id = requireRunId(runId);
        AgentExecutionIdentity actor = AgentExecutionIdentity.current();
        insertAccessAudit(id, actor.userId(), actor.role(), action, decision, reason);
    }

    @Override
    public List<AgentRuntimeAccessAuditVO> accessAudits(String runId, Integer limit) {
        String id = requireRunId(runId);
        int size = Math.max(1, Math.min(limit == null ? 50 : limit, 200));
        return jdbcTemplate.query("""
                SELECT id,run_id,trace_id,actor_user_id,actor_role,action,decision,reason,created_time
                FROM agent_runtime_access_audit
                WHERE run_id=? ORDER BY id DESC LIMIT ?
                """, (rs, rowNum) -> {
            AgentRuntimeAccessAuditVO vo = new AgentRuntimeAccessAuditVO();
            vo.setId(rs.getLong("id"));
            vo.setRunId(rs.getString("run_id"));
            vo.setTraceId(rs.getString("trace_id"));
            vo.setActorUserId(rs.getString("actor_user_id"));
            vo.setActorRole(rs.getString("actor_role"));
            vo.setAction(rs.getString("action"));
            vo.setDecision(rs.getString("decision"));
            vo.setReason(rs.getString("reason"));
            vo.setCreatedTime(time(rs.getTimestamp("created_time")));
            return vo;
        }, id, size);
    }

    @Scheduled(fixedDelayString = "${ai.agent.runtime.recovery-interval-ms:30000}")
    public void recoverExpiredRuns() {
        List<String> stale = jdbcTemplate.query("""
                SELECT run_id FROM agent_runtime_run
                WHERE status IN ('RUNNING','RESUMING') AND lease_until IS NOT NULL AND lease_until<CURRENT_TIMESTAMP
                ORDER BY updated_time ASC LIMIT 10
                """, (rs, rowNum) -> rs.getString(1));
        for (String runId : stale) {
            int claimed = jdbcTemplate.update("""
                    UPDATE agent_runtime_run SET status='FAILED',error_msg='worker lease expired; awaiting recovery',
                        lease_owner='',lease_until=NULL,version=version+1
                    WHERE run_id=? AND status IN ('RUNNING','RESUMING') AND lease_until<CURRENT_TIMESTAMP
                    """, runId);
            if (claimed == 1) {
                try { resume(runId); } catch (RuntimeException ignored) { }
            }
        }
    }

    @PreDestroy
    public void shutdownExecutor() {
        runtimeExecutor.shutdownNow();
    }

    private void submit(String runId, Map<String, ToolQueryResultVO> restored,
                        String startStatus, String leaseOwner) {
        CompletableFuture.runAsync(() -> execute(runId, restored, startStatus, leaseOwner), runtimeExecutor);
    }

    private void execute(String runId, Map<String, ToolQueryResultVO> restored,
                         String startStatus, String leaseOwner) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT trace_id,session_id,owner_user_id,owner_role,question,max_steps,status FROM agent_runtime_run WHERE run_id=?", runId);
        if ("CANCELLED".equals(row.get("status")) || "PAUSED".equals(row.get("status"))) {
            return;
        }
        AgentExecutionIdentity identity;
        try {
            identity = requireCurrentOwnerAuthorization(
                    String.valueOf(row.getOrDefault("owner_user_id", "")), "EXECUTE");
        } catch (BusinessException ex) {
            jdbcTemplate.update("""
                    UPDATE agent_runtime_run
                    SET status='FAILED',error_msg=?,finished_at=CURRENT_TIMESTAMP,
                        lease_owner='',lease_until=NULL,version=version+1
                    WHERE run_id=? AND status NOT IN ('COMPLETED','CANCELLED')
                    """, safe(ex.getMessage(), 4000), runId);
            insertAccessAudit(runId, "SYSTEM", "SYSTEM", "EXECUTE", "DENY", ex.getMessage());
            checkpoint(runId, "AUTHORIZATION_REVOKED");
            return;
        }
        int claimed = jdbcTemplate.update("""
                UPDATE agent_runtime_run SET status=?,lease_owner=?,lease_until=CURRENT_TIMESTAMP+INTERVAL '120 seconds',error_msg=''
                WHERE run_id=? AND status NOT IN ('PAUSED','CANCELLED')
                  AND (lease_owner IS NULL OR lease_owner='' OR lease_owner=?)
                """, startStatus, leaseOwner, runId, leaseOwner);
        if (claimed == 0) {
            return;
        }
        GraphAgentRequestDTO request = new GraphAgentRequestDTO();
        request.setTraceId(String.valueOf(row.get("trace_id")));
        request.setSessionId(String.valueOf(row.getOrDefault("session_id", "")));
        request.setQuestion(String.valueOf(row.get("question")));
        request.setMaxSteps(((Number) row.get("max_steps")).intValue());
        request.setMode(identity.effectiveMode("INTERNAL"));
        try {
            GraphAgentResponseVO response = graphAgentService.run(request,
                    new GraphExecutionOptions(new RuntimeObserver(runId, leaseOwner), restored, identity));
            String state = currentStatus(runId);
            if ("PAUSED".equals(state) || "CANCELLED".equals(state)) {
                return;
            }
            boolean success = Boolean.TRUE.equals(response.getSuccess());
            int completed = jdbcTemplate.update("""
                    UPDATE agent_runtime_run SET status=?,answer=?,error_msg=?,finished_at=CURRENT_TIMESTAMP,
                        lease_owner='',lease_until=NULL,version=version+1
                    WHERE run_id=? AND lease_owner=? AND status NOT IN ('PAUSED','CANCELLED')
                    """, success ? "COMPLETED" : "FAILED", safe(response.getAnswer(), 8000),
                    success ? "" : "DAG存在失败节点", runId, leaseOwner);
            if (completed == 1) {
                checkpoint(runId, success ? "COMPLETED" : "FAILED");
            }
        } catch (RuntimeException ex) {
            String state = currentStatus(runId);
            if (!"PAUSED".equals(state) && !"CANCELLED".equals(state) && ownsLease(runId, leaseOwner)) {
                int failed = jdbcTemplate.update("""
                        UPDATE agent_runtime_run SET status='FAILED',error_msg=?,finished_at=CURRENT_TIMESTAMP,
                            lease_owner='',lease_until=NULL,version=version+1
                        WHERE run_id=? AND lease_owner=?
                        """, safe(ex.getMessage(), 4000), runId, leaseOwner);
                if (failed == 1) {
                    checkpoint(runId, "FAILED");
                }
            }
        }
    }

    private class RuntimeObserver implements GraphExecutionObserver {
        private final String runId;
        private final String leaseOwner;

        RuntimeObserver(String runId, String leaseOwner) {
            this.runId = runId;
            this.leaseOwner = leaseOwner;
        }

        @Override
        public void onPlan(String traceId, List<AgentGraphNodeVO> nodes) {
            requireActiveLease(runId, leaseOwner);
            int planned = jdbcTemplate.update("""
                    UPDATE agent_runtime_run
                    SET status=CASE WHEN status='RESUMING' THEN status ELSE 'PLANNING' END,
                        plan_json=?::jsonb
                    WHERE run_id=? AND lease_owner=? AND status NOT IN ('PAUSED','CANCELLED')
                    """, json(nodes, "[]"), runId, leaseOwner);
            if (planned == 0) {
                throw new BusinessException(ResultCode.CONFLICT, "Runtime执行租约已失效");
            }
            for (AgentGraphNodeVO node : nodes) {
                requireActiveLease(runId, leaseOwner);
                jdbcTemplate.update("""
                        INSERT INTO agent_runtime_node(run_id,node_id,tool_name,node_type,dependencies_json,input_json,status)
                        VALUES (?,?,?,?,?::jsonb,?::jsonb,'PENDING')
                        ON CONFLICT(run_id,node_id) DO UPDATE SET tool_name=EXCLUDED.tool_name,node_type=EXCLUDED.node_type,
                          dependencies_json=EXCLUDED.dependencies_json,input_json=EXCLUDED.input_json
                        """, runId, node.getNodeId(), safe(node.getTool(), 128), safe(node.getType(), 32),
                        json(node.getDependsOn(), "[]"), json(node.getInput(), "{}"));
            }
            int running = jdbcTemplate.update("""
                    UPDATE agent_runtime_run SET status='RUNNING'
                    WHERE run_id=? AND lease_owner=? AND status NOT IN ('PAUSED','CANCELLED')
                    """, runId, leaseOwner);
            if (running == 0) {
                throw new BusinessException(ResultCode.CONFLICT, "Runtime执行租约已失效");
            }
            checkpoint(runId, "PLAN_READY");
        }

        @Override
        public void onNodeStarted(AgentGraphNodeVO node) {
            requireActiveLease(runId, leaseOwner);
            jdbcTemplate.update("""
                    UPDATE agent_runtime_node SET status='RUNNING',attempt_count=attempt_count+1,started_at=CURRENT_TIMESTAMP,error_msg=''
                    WHERE run_id=? AND node_id=?
                    """, runId, node.getNodeId());
            jdbcTemplate.update("UPDATE agent_runtime_run SET lease_until=CURRENT_TIMESTAMP+INTERVAL '120 seconds' WHERE run_id=?", runId);
        }

        @Override
        public void onNodeFinished(AgentGraphNodeVO node, ToolQueryResultVO observation) {
            requireActiveLease(runId, leaseOwner);
            String status = "SUCCESS".equals(node.getStatus()) ? "SUCCEEDED" : node.getStatus();
            jdbcTemplate.update("""
                    UPDATE agent_runtime_node SET status=?,observation_json=?::jsonb,elapsed_ms=?,error_msg=?,finished_at=CURRENT_TIMESTAMP
                    WHERE run_id=? AND node_id=?
                    """, status, json(observation, "{}"), node.getElapsedMs(),
                    "FAILED".equals(status) ? safe(node.getSummary(), 4000) : "", runId, node.getNodeId());
            checkpoint(runId, "NODE_" + status);
            Integer faultAfter = jdbcTemplate.queryForObject(
                    "SELECT fault_after_nodes FROM agent_runtime_run WHERE run_id=?", Integer.class, runId);
            if (faultAfter != null && faultAfter > 0 && "SUCCEEDED".equals(status)) {
                Integer succeeded = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM agent_runtime_node WHERE run_id=? AND status='SUCCEEDED'", Integer.class, runId);
                if (succeeded != null && succeeded >= faultAfter) {
                    jdbcTemplate.update("UPDATE agent_runtime_run SET fault_after_nodes=0 WHERE run_id=?", runId);
                    throw new BusinessException(ResultCode.INTERNAL_ERROR, "runtime fault injection after " + succeeded + " nodes");
                }
            }
        }
    }

    private synchronized void checkpoint(String runId, String reason) {
        List<Map<String, Object>> nodes = jdbcTemplate.queryForList(
                "SELECT node_id,status,attempt_count FROM agent_runtime_node WHERE run_id=? ORDER BY id", runId);
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("runStatus", currentStatus(runId));
        state.put("nodes", nodes);
        String payload = json(state, "{}");
        Integer seq = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(checkpoint_seq),0)+1 FROM agent_runtime_checkpoint WHERE run_id=?", Integer.class, runId);
        jdbcTemplate.update("""
                INSERT INTO agent_runtime_checkpoint(run_id,checkpoint_seq,reason,state_json,state_hash)
                VALUES (?,?,?,?::jsonb,?)
                """, runId, seq, safe(reason, 64), payload, sha256(payload));
    }

    private Map<String, ToolQueryResultVO> completedObservations(String runId) {
        Map<String, ToolQueryResultVO> out = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT node_id,observation_json::text FROM agent_runtime_node
                WHERE run_id=? AND node_type='TOOL' AND status='SUCCEEDED'
                """, rs -> {
            String raw = rs.getString("observation_json");
            if (StringUtils.hasText(raw) && !"{}".equals(raw)) {
                try { out.put(rs.getString("node_id"), objectMapper.readValue(raw, ToolQueryResultVO.class)); }
                catch (Exception ignored) { }
            }
        }, runId);
        return out;
    }

    private List<AgentRuntimeNodeVO> loadNodes(String runId) {
        return jdbcTemplate.query("""
                SELECT node_id,tool_name,node_type,dependencies_json::text,status,attempt_count,elapsed_ms,
                       observation_json::text,error_msg FROM agent_runtime_node WHERE run_id=? ORDER BY id
                """, (rs, rowNum) -> {
            AgentRuntimeNodeVO vo = new AgentRuntimeNodeVO();
            vo.setNodeId(rs.getString("node_id"));
            vo.setToolName(rs.getString("tool_name"));
            vo.setNodeType(rs.getString("node_type"));
            vo.setDependencies(readList(rs.getString("dependencies_json")));
            vo.setStatus(rs.getString("status"));
            vo.setAttemptCount(rs.getInt("attempt_count"));
            long elapsed = rs.getLong("elapsed_ms");
            vo.setElapsedMs(rs.wasNull() ? null : elapsed);
            vo.setObservationJson(rs.getString("observation_json"));
            vo.setErrorMsg(rs.getString("error_msg"));
            return vo;
        }, runId);
    }

    private void changeState(String runId, Set<String> allowed, String target) {
        String placeholders = String.join(",", allowed.stream().map(ignored -> "?").toList());
        List<Object> args = new ArrayList<>();
        args.add(target);
        args.add(runId);
        args.addAll(allowed);
        String leaseUpdate = Set.of("PAUSED", "CANCELLED").contains(target)
                ? ",lease_owner='',lease_until=NULL"
                : "";
        String sql = "UPDATE agent_runtime_run "
                + "SET status=?,version=version+1"
                + leaseUpdate
                + " WHERE run_id=? AND status IN (" + placeholders + ")";
        int changed = jdbcTemplate.update(sql, args.toArray());
        if (changed == 0) {
            String current = currentStatus(runId);
            throw new BusinessException(ResultCode.CONFLICT, "状态 " + current + " 不允许变更为 " + target);
        }
    }

    private String currentStatus(String runId) {
        return jdbcTemplate.queryForObject("SELECT status FROM agent_runtime_run WHERE run_id=?", String.class, runId);
    }

    private boolean ownsLease(String runId, String leaseOwner) {
        String current = jdbcTemplate.queryForObject(
                "SELECT COALESCE(lease_owner,'') FROM agent_runtime_run WHERE run_id=?",
                String.class, runId);
        return leaseOwner.equals(current);
    }

    private void requireActiveLease(String runId, String leaseOwner) {
        Map<String, Object> state = jdbcTemplate.queryForMap(
                "SELECT status,COALESCE(lease_owner,'') AS lease_owner FROM agent_runtime_run WHERE run_id=?",
                runId);
        String status = String.valueOf(state.get("status"));
        String currentLease = String.valueOf(state.get("lease_owner"));
        if ("PAUSED".equals(status) || "CANCELLED".equals(status) || !leaseOwner.equals(currentLease)) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Runtime执行已暂停、取消或租约失效，status=" + status);
        }
    }

    private String nextLeaseOwner() {
        return workerId + "-" + UUID.randomUUID();
    }

    private AgentExecutionIdentity requireCurrentOwnerAuthorization(String ownerUserId, String action) {
        return requireCurrentOwnerAuthorization(ownerUserId, action, null);
    }

    private AgentExecutionIdentity requireCurrentOwnerAuthorization(
            String ownerUserId,
            String action,
            String auditRunId) {
        String owner = safe(ownerUserId, 64);
        String denial = "";
        String currentRole = UserRole.USER.name();
        try {
            long numericOwner = Long.parseLong(owner);
            List<Map<String, Object>> users = mysqlJdbcTemplate.queryForList("""
                    SELECT status,role
                    FROM ums_user
                    WHERE id=? AND is_deleted=0
                    LIMIT 1
                    """, numericOwner);
            if (users.isEmpty()) {
                denial = "Runtime owner does not exist";
            } else {
                Map<String, Object> user = users.get(0);
                Number status = (Number) user.get("status");
                currentRole = UserRole.normalize(String.valueOf(user.getOrDefault("role", "USER")));
                if (status == null || status.intValue() != 1) {
                    denial = "Runtime owner is disabled";
                } else if (!UserRole.canUseInternal(currentRole)) {
                    denial = "Runtime owner no longer has INTERNAL permission";
                }
            }
        } catch (NumberFormatException ex) {
            denial = "Runtime owner identity is invalid";
        } catch (RuntimeException ex) {
            denial = "Runtime owner authorization lookup failed";
        }
        if (StringUtils.hasText(denial)) {
            if (StringUtils.hasText(auditRunId)) {
                AgentExecutionIdentity actor = AgentExecutionIdentity.current();
                insertAccessAudit(auditRunId, actor.userId(), actor.role(), action, "DENY", denial);
            }
            throw new BusinessException(ResultCode.FORBIDDEN, denial);
        }
        return new AgentExecutionIdentity(owner, currentRole);
    }

    private void insertAccessAudit(
            String runId,
            String actorUserId,
            String actorRole,
            String action,
            String decision,
            String reason) {
        try {
            List<String> traces = jdbcTemplate.query(
                    "SELECT trace_id FROM agent_runtime_run WHERE run_id=? LIMIT 1",
                    (rs, rowNum) -> rs.getString(1), runId);
            String traceId = traces.isEmpty() ? "" : traces.get(0);
            jdbcTemplate.update("""
                    INSERT INTO agent_runtime_access_audit(
                        run_id,trace_id,actor_user_id,actor_role,action,decision,reason)
                    VALUES (?,?,?,?,?,?,?)
                    """, safe(runId, 128), safe(traceId, 128), safe(actorUserId, 64),
                    UserRole.normalize(actorRole), safe(action, 64), safe(decision, 16), safe(reason, 500));
        } catch (RuntimeException ex) {
            log.warn("Runtime access audit write failed, runId={}, action={}, decision={}, reason={}",
                    runId, action, decision, ex.getMessage());
        }
    }

    private String requireRunId(String runId) {
        if (!StringUtils.hasText(runId)) throw new BusinessException(ResultCode.BAD_REQUEST, "runId不能为空");
        return runId.trim();
    }

    private int clampSteps(Integer value) { return Math.max(1, Math.min(value == null ? 6 : value, 8)); }
    private LocalDateTime time(Timestamp value) { return value == null ? null : value.toLocalDateTime(); }
    private String safe(String value, int max) {
        if (!StringUtils.hasText(value)) return "";
        String text = value.trim(); return text.length() <= max ? text : text.substring(0, max);
    }
    private String json(Object value, String fallback) {
        try { return objectMapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception ex) { return fallback; }
    }
    private List<String> readList(String value) {
        try { return objectMapper.readValue(value, new TypeReference<ArrayList<String>>() { }); }
        catch (Exception ex) { return List.of(); }
    }
    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { return ""; }
    }
}
