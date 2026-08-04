# Controlled Operation Eval Report

- Cases: 12
- Passed: 12
- Pass rate: 100.00%
- P95 latency: 38.7 ms

| Case | Requirements | Scenario | Status | Latency(ms) | Details |
| --- | --- | --- | --- | ---: | --- |
| CONTROL-001 | APPROVAL-AC-001 | create_plan | PASS | 25.0 |  |
| CONTROL-002 | APPROVAL-AC-001 | execute_before_approval_denied | PASS | 5.4 |  |
| CONTROL-003 | APPROVAL-AC-001 | internal_approve_denied | PASS | 3.9 |  |
| CONTROL-004 | APPROVAL-AC-001 | admin_approve | PASS | 7.1 |  |
| CONTROL-005 | APPROVAL-AC-002,APPROVAL-AC-003 | execute_approved_snapshot | PASS | 55.4 |  |
| CONTROL-006 | APPROVAL-AC-002 | snapshot_params_unchanged | PASS | 5.4 |  |
| CONTROL-007 | APPROVAL-AC-001 | admin_reject | PASS | 18.1 |  |
| CONTROL-008 | APPROVAL-AC-001 | execute_rejected_denied | PASS | 5.3 |  |
| CONTROL-009 | SECURITY-01 | legacy_repair_blocked | PASS | 3.8 |  |
| CONTROL-010 | SECURITY-01 | legacy_runbook_blocked | PASS | 3.6 |  |
| CONTROL-011 | APPROVAL-AC-001 | user_create_denied | PASS | 3.3 |  |
| CONTROL-012 | APPROVAL-AC-001 | high_risk_batch_manual_only | PASS | 21.4 |  |
