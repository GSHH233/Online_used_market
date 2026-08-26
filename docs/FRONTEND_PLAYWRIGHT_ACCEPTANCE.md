# 前端 Playwright 验收报告

## 验收范围

- 用户前端：`http://localhost:5173`
- 用户当前 IDEA 后端：`http://localhost:8080`
- 最新代码临时验证后端：`http://localhost:8081`
- 浏览器：Microsoft Edge，由 Playwright CLI 驱动
- 最近验收日期：2026-08-08

验收使用临时登录态，报告不保存手机号、短信验证码或 JWT。

## 结论

普通用户商城、发布与 RAG 入口可用；INTERNAL 的智能排查页能够根据问题自动选择 DIRECT、REACT 或 DAG；ADMIN 能看到受控操作审批与执行入口。角色菜单、前端路由和最新后端权限边界均通过验收。

当前 IDEA 进程仍加载旧版异常处理类，USER 直接请求内部接口时表现为 HTTP 200、业务码 403。最新代码已在 8081 验证为 HTTP 403、业务码 403；重启 IDEA 后 8080 才会加载该修复。

## 4.0 角色与智能排查验收

| 场景 | 结果 | 观察 |
|---|---|---|
| INTERNAL 登录与菜单 | PASS | 可见“运营后台 / 智能排查 / 运行记录”，可进入 `/ai/investigate` |
| DIRECT：查询当前接口 P95 | PASS | 只调用 `prometheus_query`，展示路由原因、Trace ID 和 Observation |
| REACT：先查 P95 再定位原因 | PASS | 完成工具调用、观察和回答；证据不足时没有编造根因 |
| DAG：并行查指标、日志和知识规则 | PASS_WITH_RISK | 多工具结果被汇总；知识检索超时后由 Harness 返回降级 Observation，总耗时约 10.8 秒 |
| USER 菜单隔离 | PASS | 不显示内部菜单，直接访问 `/ai/investigate` 被重定向到 `/market` |
| USER 后端权限 | PASS_AFTER_RESTART | 最新代码返回真实 HTTP 403；当前 8080 需重启 IDEA |
| ADMIN 操作权限 | PASS | 可见待审批计划的批准、拒绝和执行控件 |

## 普通用户主链路

| 场景 | 结果 | 说明 |
|---|---|---|
| 注册与登录 | PASS | 登录后进入 `/market` |
| 商品浏览与详情 | PASS | 桌面与移动视口可用 |
| AI 辅助发布 | PASS | 发布表单与 AI 检查链路可用 |
| RAG 流式问答 | PASS | 能展示回答状态和来源 |
| USER 访问内部页面 | PASS | 被路由守卫重定向到商城 |
| 历史调试路由 | PASS | 已收口，不再向普通用户暴露独立 ReAct/DAG 页面 |

## 待优化项

1. DAG 场景 P95 风险较明显，知识检索一次超时约 9.1 秒；应继续检查 Query Rewrite、Embedding 和向量检索的超时预算。
2. 页面中的“并行节点”目前统计全部 Graph 节点，不等于实际并行工具节点，建议调整标签或统计口径。
3. 前端路由守卫只负责体验，真正权限边界必须继续由后端角色校验保证。

## 验收证据

- `.playwright-cli/page-2026-08-08T13-06-13-308Z.png`：DIRECT
- `.playwright-cli/page-2026-08-08T13-14-55-269Z.png`：DAG
- `output/playwright/frontend-acceptance/market-desktop.png`
- `output/playwright/frontend-acceptance/market-mobile.png`
- `output/playwright/frontend-acceptance/publish-ai-desktop.png`
- `output/playwright/frontend-acceptance/rag-chat-desktop.png`
- `output/playwright/frontend-acceptance/rag-chat-mobile.png`
