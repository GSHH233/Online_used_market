# Query Rewrite A/B Eval Report

- generatedAt: `2026-08-01T03:17:25`
- result: `PASS`
- cases: `12/12`
- baseline: `{"recall@5": 0.8333333333333334, "mrr": 0.6527777777777778, "ndcg@5": 0.6993991050595311}`
- enhanced: `{"recall@5": 1.0, "mrr": 0.7430555555555557, "ndcg@5": 0.8077113387680258}`
- noHitRecovery: `2`
- entityRetentionRate: `100.00%`
- negationRetentionRate: `100.00%`
- fallbackCount: `0`
- shadowModeCount: `0`
- enhanced P50/P95: `5589.1ms / 7615.9ms`

| Case | Base rank | Rewrite rank | Plan | Applied | Fallback | Shadow | Branches | Faithful | Latency | Result | Detail |
| --- | ---: | ---: | --- | --- | --- | --- | ---: | --- | ---: | --- | --- |
| QR-001 | 1 | 1 | False | False | False | False | 0 | True | 373.0ms | OK |  |
| QR-002 | 2 | 2 | False | False | False | False | 0 | True | 343.6ms | OK |  |
| QR-003 | 1 | 1 | True | True | False | False | 2 | True | 5385.1ms | OK |  |
| QR-004 | - | 3 | True | True | False | False | 2 | True | 7104.5ms | OK |  |
| QR-005 | 3 | 1 | True | True | False | False | 2 | True | 6183.5ms | OK |  |
| QR-006 | 1 | 1 | False | False | False | False | 0 | True | 201.1ms | OK |  |
| QR-007 | - | 4 | True | True | False | False | 2 | True | 7615.9ms | OK |  |
| QR-008 | 1 | 1 | True | True | False | False | 2 | True | 6432.0ms | OK |  |
| QR-009 | 1 | 1 | False | False | False | False | 0 | True | 279.7ms | OK |  |
| QR-010 | 1 | 1 | True | True | False | False | 2 | True | 6909.6ms | OK |  |
| QR-011 | 2 | 3 | True | True | False | False | 2 | True | 5793.0ms | OK |  |
| QR-012 | 2 | 2 | False | False | False | False | 0 | True | 151.0ms | OK |  |
