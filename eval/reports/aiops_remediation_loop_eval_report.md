# AIOps Remediation Loop Eval Report

- Run ID: `20260727024720`
- Cases: 16
- Passed: 16
- Closure pass rate: 100.00%
- P95 latency ms: 124.4

| Case | Requirements | Scenario | OK | Latency ms | Details |
|---|---|---|---:|---:|---|
| AIOPS-LOOP-001 | AIOPS-AC-001 | admin_approve | true | 24.3 | `` |
| AIOPS-LOOP-002 | AIOPS-AC-002 | internal_approve_denied | true | 9.1 | `` |
| AIOPS-LOOP-003 | AIOPS-AC-002 | user_approve_denied | true | 24.0 | `` |
| AIOPS-LOOP-004 | AIOPS-AC-001 | admin_reject | true | 11.5 | `` |
| AIOPS-LOOP-005 | AIOPS-AC-002 | internal_reject_denied | true | 9.4 | `` |
| AIOPS-LOOP-006 | AIOPS-AC-002 | direct_resolved_denied | true | 10.2 | `` |
| AIOPS-LOOP-007 | AIOPS-AC-001 | unapproved_execute_conflict | true | 11.8 | `` |
| AIOPS-LOOP-008 | AIOPS-AC-002 | internal_dry_run | true | 3.3 | `` |
| AIOPS-LOOP-009 | AIOPS-AC-001 | repair_and_verify | true | 56.0 | `` |
| AIOPS-LOOP-010 | AIOPS-AC-004 | idempotent_replay | true | 5.5 | `` |
| AIOPS-LOOP-011 | AIOPS-AC-003 | execution_failure_escalates | true | 30.5 | `` |
| AIOPS-LOOP-012 | AIOPS-AC-003 | escalation_audit_fields | true | 3.6 | `` |
| AIOPS-LOOP-013 | AIOPS-AC-001 | manual_override | true | 24.0 | `` |
| AIOPS-LOOP-014 | AIOPS-AC-005 | mttr_average | true | 329.6 | `` |
| AIOPS-LOOP-015 | AIOPS-AC-005 | mttr_p50 | true | 0.0 | `` |
| AIOPS-LOOP-016 | AIOPS-AC-005 | mttr_p95 | true | 0.0 | `` |
