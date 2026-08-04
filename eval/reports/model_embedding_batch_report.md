# Model Connectivity Report

- generatedAt: `2026-08-01T02:31:17`
- baseUrl: `https://dashscope.aliyuncs.com/compatible-mode`
- result: `RISK`
- apiKey: `REDACTED`

| Probe | Model | HTTP | Latency | Result | Detail |
| --- | --- | ---: | ---: | --- | --- |
| embedding | text-embedding-v3 | 400 | 10584.1ms | RISK | <400> InternalError.Algo.InvalidParameter: Value error, batch size is invalid, it should not be larger than 10.: input.contents |
