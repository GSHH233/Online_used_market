# Hybrid Planner Evaluation Report

- Generated at: 2026-08-01 03:23:20
- Cases: 8
- Passed: 8
- Pass rate: 100.00%
- P50 latency ms: 892.2
- P95 latency ms: 11289.4

| Case | OK | Planner | Replan rounds | Tools | Denied | Latency ms | Details |
|---|---:|---|---:|---|---|---:|---|
| PLANNER-001 | true | RULE_FAST_PATH | 0 | ['knowledge_search'] | [] | 11289.4 | `` |
| PLANNER-002 | true | RULE_FAST_PATH | 0 | ['mysql_read'] | ['mysql_read'] | 1457.5 | `` |
| PLANNER-003 | true | RULE_FAST_PATH | 0 | ['log_query', 'knowledge_search'] | [] | 6459.5 | `` |
| PLANNER-004 | true | RULE_FAST_PATH | 0 | ['log_query', 'prometheus_query'] | [] | 21.5 | `` |
| PLANNER-005 | true | RULE_FAST_PATH | 0 | ['knowledge_search', 'mysql_read'] | [] | 326.9 | `` |
| PLANNER-006 | true | RULE_FAST_PATH | 0 | ['prometheus_query'] | ['prometheus_query'] | 11.1 | `` |
| PLANNER-007 | true | RULE_FAST_PATH | 0 | ['knowledge_search', 'vector_debug'] | [] | 289.7 | `` |
| PLANNER-008 | true | RULE_FAST_PATH | 0 | ['profile', 'knowledge_search'] | [] | 3569.6 | `` |
