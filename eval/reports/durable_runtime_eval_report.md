# Durable Runtime Eval Report

- Cases: 15
- Passed: 15
- Pass rate: 100.00%
- Resume success rate: 100.00%
- Duplicate side-effect count: 0
- P95 case latency: 9560.6 ms

| Case | Requirements | Scenario | Status | Latency(ms) | Details |
| --- | --- | --- | --- | ---: | --- |
| RUNTIME-001 | RUNTIME-AC-005 | start_normal | PASS | 136.3 |  |
| RUNTIME-002 | RUNTIME-AC-005 | normal_completed | PASS | 9275.8 |  |
| RUNTIME-003 | RUNTIME-AC-005 | checkpoint_complete | PASS | 4.5 |  |
| RUNTIME-004 | RUNTIME-AC-005 | list_contains_run | PASS | 23.6 |  |
| RUNTIME-005 | RUNTIME-AC-003 | user_start_denied | PASS | 5.4 |  |
| RUNTIME-006 | RUNTIME-AC-003 | user_detail_denied | PASS | 3.4 |  |
| RUNTIME-007 | RUNTIME-AC-003 | internal_cancel_denied | PASS | 4.1 |  |
| RUNTIME-008 | RUNTIME-AC-003 | pause_running | PASS | 12.6 |  |
| RUNTIME-009 | RUNTIME-AC-001 | resume_paused | PASS | 268.6 |  |
| RUNTIME-010 | RUNTIME-AC-001 | fault_after_node | PASS | 521.9 |  |
| RUNTIME-011 | RUNTIME-AC-001,RUNTIME-AC-002 | concurrent_resume_without_duplicate | PASS | 6919.9 |  |
| RUNTIME-012 | RUNTIME-AC-004 | snapshot_replay | PASS | 3334.7 |  |
| RUNTIME-013 | RUNTIME-AC-004 | snapshot_replay_no_tool_calls | PASS | 4.4 |  |
| RUNTIME-014 | RUNTIME-AC-004 | live_readonly_replay | PASS | 10225.1 |  |
| RUNTIME-015 | RUNTIME-AC-004 | live_replay_calls_tools | PASS | 3.4 |  |
