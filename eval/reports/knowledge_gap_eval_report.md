# Knowledge Gap Eval Report

- Cases: 26
- Passed: 26
- Capture/Dedup/Governance pass rate: 100.00%
- P95 latency ms: 5348.5

| Case | Requirements | Scenario | OK | Latency ms | Details |
|---|---|---|---:|---:|---|
| GAP-001 | GAP-AC-001 | user_management_denied | true | 10.7 | `` |
| GAP-002 | GAP-AC-001 | internal_list | true | 4.5 | `` |
| GAP-003 | GAP-AC-001 | no_retrieval_capture | true | 4864.5 | `` |
| GAP-004 | GAP-AC-001 | no_retrieval_fields | true | 3.5 | `` |
| GAP-005 | GAP-AC-002 | repeat_normalized_query | true | 5509.9 | `` |
| GAP-006 | GAP-AC-002 | dedup_occurrence | true | 3.4 | `` |
| GAP-007 | GAP-AC-003 | missing_fields_capture | true | 4020.6 | `` |
| GAP-008 | GAP-AC-003 | missing_fields_reason | true | 3.5 | `` |
| GAP-009 | GAP-AC-003 | missing_fields_link_rejected | true | 3.5 | `` |
| GAP-010 | GAP-AC-001 | user_unresolved_capture | true | 4.1 | `` |
| GAP-011 | GAP-AC-002 | user_unresolved_dedup | true | 3.3 | `` |
| GAP-012 | GAP-AC-001 | phone_redaction | true | 4.3 | `` |
| GAP-013 | GAP-AC-001 | email_token_redaction | true | 3.4 | `` |
| GAP-014 | GAP-AC-001 | internal_triage | true | 3.7 | `` |
| GAP-015 | GAP-AC-001 | priority_update | true | 2.8 | `` |
| GAP-016 | GAP-AC-003 | internal_dismiss_denied | true | 2.4 | `` |
| GAP-017 | GAP-AC-003 | admin_dismiss | true | 3.6 | `` |
| GAP-018 | GAP-AC-004 | create_regression_fixture | true | 3.0 | `` |
| GAP-019 | GAP-AC-004 | link_verified_doc | true | 4.9 | `` |
| GAP-020 | GAP-AC-004 | regression_success | true | 3969.8 | `` |
| GAP-021 | GAP-AC-004 | regression_persisted | true | 4.4 | `` |
| GAP-022 | GAP-AC-002 | resolved_recurrence | true | 3.7 | `` |
| GAP-023 | GAP-AC-005 | create_failure_fixture | true | 7.9 | `` |
| GAP-024 | GAP-AC-005 | regression_failure | true | 7839.3 | `` |
| GAP-025 | GAP-AC-005 | failed_gap_not_closed | true | 6.9 | `` |
| GAP-026 | GAP-AC-001,GAP-AC-002 | governance_metrics | true | 4.3 | `` |
