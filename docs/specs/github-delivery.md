# GitHub 交付收尾 Spec

状态：`PUBLISHED_PENDING_LIVE_ACCEPTANCE`
范围：README、架构图、启动脚本、演示数据和公开仓库检查

## 1. 目标

让面试官从全新 clone 开始，在不阅读聊天记录和本机配置的情况下理解架构、启动核心服务、运行演示并查看量化评测。

## 2. 交付物

### GH-FR-001 README

根目录新增 `README.md`，内容顺序固定为：

1. 一句话定位和真实业务场景。
2. 核心能力，不堆砌所有技术名词。
3. 架构图与两条闭环。
4. 技术栈与模块目录。
5. 快速启动。
6. 演示账号、请求和页面。
7. 评测指标及其数据集来源。
8. 安全边界和已知限制。

### GH-FR-002 架构图

使用可版本管理的 Mermaid 源文件，至少展示：

```text
Vue -> Spring Security/JWT -> RAG/Agent
RAG -> ES + Milvus + DB
Agent -> Runtime Context + Tool Harness -> Tools
Ingestion -> RabbitMQ -> DB/ES/Milvus -> Consistency Verify
AIOps -> Event -> Approval -> Runbook -> Verify/Escalate
```

架构图必须区分 USER、INTERNAL、ADMIN 边界。

### GH-FR-003 启动脚本

提供：

```text
scripts/check-env.ps1
scripts/start-backend.ps1
scripts/start-frontend.ps1
scripts/smoke-test.ps1
```

- `check-env` 只检查依赖连通性和必需变量，不打印密钥。
- 启动脚本失败时返回非 0 exit code。
- 保留 `.env.example`，真实 `.env`、IDEA run 配置和日志继续被忽略。

### GH-FR-004 演示数据

演示数据必须可重复导入，包含：

- USER、INTERNAL、ADMIN 三种角色的演示账号，密码仅在本地 seed 中以 BCrypt 保存。
- 10 个以上商品。
- docId 19 到 50 的知识文档或明确的导入映射。
- 至少一个存储不一致修复演示、一个 AIOps 事件和一个知识缺口闭环演示。

### GH-FR-005 公开仓库治理

- 密钥扫描不得出现 `sk-*`、真实 JWT secret、真实密码和个人绝对路径。
- 不提交 `target`、`dist`、`node_modules`、`.idea`、`.run`、日志和历史评测垃圾文件。
- README 中的指标必须能追溯到数据集和评测脚本，不写无法复现的收益数字。

## 3. 快速演示路径

README 提供 10 分钟路径：

```text
登录 -> 发布助手 -> 应用建议并发布
-> RAG Chat 查看引用
-> INTERNAL 查看知识缺口
-> ADMIN dry-run/审批/修复
-> 查看 Trace、MTTR 和 Eval Dashboard
```

## 4. 验收标准

### GH-AC-001 新环境可理解

Given 首次访问仓库，When 只阅读 README，Then能说明项目目标、两条闭环、权限边界和核心指标来源。

### GH-AC-002 启动可诊断

Given 缺少 Redis 或 Milvus，When 运行 check-env，Then脚本指出具体服务、地址和失败原因，且不泄露密码。

### GH-AC-003 演示可重复

Given 已导入 seed，When 重复执行导入和 smoke-test，Then不产生重复主数据，核心 API 全部通过。

### GH-AC-004 脱敏通过

Given 准备提交，When 运行 secret scan，Then没有真实 Key、密码、IP 私有配置和个人绝对路径进入 Git。

### GH-AC-005 构建通过

后端 `mvnw -DskipTests package` 与前端 `npm run build` 均成功，smoke-test 输出清晰的通过/失败统计。

## 5. 完成边界

GitHub 收尾不新增业务框架，不引入 LangChain/LangGraph，不迁移现有存储。重点是让现有能力可运行、可解释、可评测、可展示。

## 6. 2026-10-13 发布准备记录

- 清理前创建项目源码/文档/配置快照与完整 Git bundle；11 个个人材料文件移动到项目外备份，前后 SHA-256 一致。
- 本机 IDEA profile 留在原工作区；公开仓库只交付 `application-idea.example.yml`，原 profile 不再跟踪。
- 保留 agent.md、Spec、核心代码解析、演示清单和全部业务评测脚本/数据集；增加 docs/README.md 工程文档入口。
- 原始评测报告、临时文件、运行日志、依赖缓存和构建产物排除；RAG 最新首次 Holdout v6 的 RISK 结论保留在公开摘要。
- 基于远端最新提交建立独立发布目录，不覆盖原工作区配置，也不强制推送。
- 发布目录验证：Java 310 条，308 通过、2 跳过；前端 npm ci 后构建通过；Python 编译与冻结数据集结构校验通过；token-like 扫描 0 命中。
- 已普通推送代码整理提交 `e22cfa9` 至 `GSHH233/Online_used_market` 的 master，保留远端已有历史。后端 JAR 打包通过；真实服务与浏览器验收仍保留待验收状态。
