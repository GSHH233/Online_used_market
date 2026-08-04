# 前端 Playwright 验收报告

验收日期：2026-07-27  
验收环境：`http://127.0.0.1:5173`，后端 `http://127.0.0.1:8080`  
浏览器：Microsoft Edge（Playwright CLI）

## 验收结论

普通用户主链路通过，桌面端与 375×812 移动端未发现阻断性布局问题。商品发布、AI 文案检查、RAG 问答、路由收口和 USER 权限隔离均可用。

本次没有可用的 INTERNAL/ADMIN 测试账号，因此内部工作台、审批和 Runtime 页面只完成了路由与代码审查，没有执行浏览器端完整操作验收。

## 场景结果

| 场景 | 结果 | 说明 |
|---|---|---|
| 注册并登录普通用户 | PASS | 注册后返回登录页，登录后进入 `/market` |
| 商城桌面端 | PASS | 导航、搜索、分类、商品卡片和发布入口正常 |
| 商城移动端 | PASS | 375×812 下无横向溢出，底部主导航可见 |
| 商品发布校验 | PASS | 未上传图片时阻止提交并展示明确错误 |
| AI 发布检查 | PASS | 返回风险评分、优化标题、描述和风险提示，可采用结果 |
| RAG WebSocket 问答 | PASS | 返回完整回答和引用依据，完成后恢复“准备就绪” |
| 历史路由迁移 | PASS | `/ai/publish-assist` 跳转到 `/products/publish?ai=1` |
| USER 访问内部页面 | PASS | `/ai/knowledge` 被重定向到 `/market` |
| 禁用的调试路由 | PASS | `/ai/react-agent` 不再出现空白页，统一返回商城 |
| 控制台错误 | PASS | 修复后重载商城，Playwright 检查为 0 error |

## 本次修复

1. 为未注册路由增加统一回退，避免关闭 AI Debug 后旧地址显示空白页。
2. 商品图片拒绝加载 `file://` 和无效示例域名，加载失败时展示“等待卖家补图”。
3. RAG 顶部状态改为依据当前生成状态，而不是 WebSocket 是否保持连接。
4. 显式配置站点 favicon，消除 `/favicon.ico` 404。

## 观察项

- AI 发布检查收到 `price=4800` 后仍提示“未提供期望价格”。链路可用，但建议核对前端请求字段与后端价格字段的映射。
- 前端路由守卫只能改善体验，权限边界仍必须由后端强制执行。本项目内部接口已使用后端角色检查，但安全报告中列出的调试接口例外需要修复。

## 验收证据

- `output/playwright/frontend-acceptance/market-desktop.png`
- `output/playwright/frontend-acceptance/market-mobile.png`
- `output/playwright/frontend-acceptance/publish-ai-desktop.png`
- `output/playwright/frontend-acceptance/rag-chat-desktop.png`
- `output/playwright/frontend-acceptance/rag-chat-mobile.png`
- `output/playwright/frontend-acceptance/.playwright-cli/traces/trace-1785098659992.trace`

