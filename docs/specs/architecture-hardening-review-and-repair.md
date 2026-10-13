# 架构修复清单复核与实施记录

日期：2026-10-13。基于当前工作区源码复核用户提供的《回血集市架构优化：具体修改方案》。

## 1. 复核结论

分页、订单并发、匿名图片上传、WS Origin 和公开监控确有需要修复的部分。不能照搬的内容如下：

- 聊天 `CHAT_START` 已验证 JWT，`MemoryScope` 已按用户与会话隔离。匿名连接不等于匿名能调用模型。
- L3 的主存储已有 MySQL slot revision/CAS 和精确恢复；不能改成 Redis Hash 而丢弃这些能力。
- 商品提交后再关联 AI 反馈可行，但无效 assistId、他人记录与非法采纳参数仍需发布前拒绝。
- `AFTER_COMMIT` 中写数据库必须另起事务；仅加事件监听而继续使用已结束事务可能丢写入。
- 换管理端口不能代替监控鉴权。默认保持现有端口，支持环境变量主动切换，并使用只读抓取凭证。
- 原清单的 `props.getConfiguration()` 不能直接传入 factory：当前 MP 3.5.11 为 CoreConfiguration，必须先 `applyTo(MybatisConfiguration)`。
- 当前 L2 主数据源是 MySQL，`l2_memory_pg16_init.sql` 已标记历史用途；不能纳入 PG 默认迁移。
- 原清单建议的克隆位置、分支名、提交等属于附件中的工作方案，本轮未执行 Git 操作。

## 2. 实施追踪表

`TESTED` 表示源码已实现且本地自动测试通过；不等于真实数据库与浏览器全量验收完成。

| 编号 | 内容 | 当前处理 | 状态 |
| --- | --- | --- | --- |
| HARD-01 | 分页失效 | 补 jsqlparser 与 MYSQL 分页拦截器；上限 100；读取 MP 配置和 GlobalConfig；复用 selectPage.total | TESTED |
| HARD-02 | 并发抢单 | 先 ON_SALE→LOCKED 条件更新，再创建订单与快照；同一 MySQL 事务；失败回滚 | TESTED |
| HARD-03 | 订单状态覆盖 | pay/deliver/finish/cancel 使用 id+原状态 CAS；合法流转集中于 OrderStatus；商品释放/售出也条件更新 | TESTED |
| HARD-04 | WS 身份与 Origin | 保留 CHAT_START JWT；可选 Authorization 握手验证；握手身份与消息身份必须一致；共享 Origin 配置 | TESTED |
| HARD-05 | 模型调用限流 | Redis Lua 原子计数、60 秒窗口、默认每用户 10 次；超限 429，Redis 故障不放行 | TESTED |
| HARD-06 | 图片上传 | 登录、5MiB 实际字节上限、扩展名白名单、Tika 实际 MIME 匹配；bucket 成功后缓存初始化结果 | TESTED |
| HARD-07 | 监控边界 | health 公共；metrics/info 内部角色；独立凭证仅 GET prometheus；可选独立管理端口 | TESTED |
| HARD-08 | 发布关联解耦 | 商品侧校验扩展接口和不可变事件；AI 侧 AFTER_COMMIT→REQUIRES_NEW 关联；失败只留审计日志 | TESTED |
| HARD-09 | L1 最近对话外置 | Redis List 原子追加 Q/A、裁剪、TTL；键包含用户+会话隔离；显式 memory 配置可用原实现 | TESTED |
| HARD-10 | L3 摘要存储 | 复用已完成的 MySQL revision/CAS 恢复，不迁移为无版本 Redis Hash | REUSE_EXISTING |
| HARD-11 | 声明式权限 | 启用 Method Security；内部工具/AIOps 路由保护；审批/执行新增 ADMIN 注解；保留对象级与服务内 Guard | TESTED |
| HARD-12 | Flyway | 不直接拼接旧 init 脚本；先做 schema 盘点、baseline 与既有库升级演练，再切换初始化责任 | DEFERRED |
| HARD-13 | 一键容器启动 | 需独立开发环境端口、volume 和网络设计；本轮未替换既有 Windows/VM 服务配置 | DEFERRED |
| HARD-14 | RAG 大类拆分 | 需纯重构 Spec 和同数据、同模型行为对照，不能以编译成功代替 RAG 回归 | DEFERRED |
| HARD-15 | 全模块分包 | 影响 Spring Scan、MyBatis Mapper、脚本与文档；独立实施 | DEFERRED |
| HARD-16 | 删除/搬迁个人材料与 evaluation | 现有文档和脚本有路径引用；本轮未删除、移动或覆盖这些材料 | DEFERRED |

没有新增 `@Version` 列，因此不额外接 OptimisticLocker 插件；本轮交易并发保护由 SQL 条件更新实现。

## 3. 本次验证

- `mvn -B -o test`：310 条，308 通过、2 跳过、0 失败。
- 跳过项：默认完整应用启动测试、需显式 datasets 参数的诊断质量评测。
- `npm run build`：vue-tsc 与 Vite 构建通过。
- MySqlDataSourceConfigTest 验证配置生效、LIMIT 参数绑定和分页上限。
- OrderServiceImplTest 验证并发抢单赢家唯一、CAS 失败不继续写、事务代理回滚、旧取消请求不释放新订单。
- SecurityBoundaryTest 通过实际 Spring Security FilterChain/MockMvc 验证匿名 401、USER 403、内部角色与监控凭证范围、CORS。
- RedisConversationMemoryServiceImplTest 验证键隔离、共享服务实例读取、脚本参数、禁用与失败语义；Redis 操作为 mock，未冒充真实 Redis 重启恢复。
- ProductPublicationTransactionTest 使用真实 Spring 事务代理与同步回调、mock JDBC connection 验证 AFTER_COMMIT、REQUIRES_NEW、回滚和提交失败。
- 图片/握手/WS 身份/限流各有非法输入、边界及失败测试。

原始 Maven 结果：`target/surefire-reports/`；本轮日志：`tmp/architecture-hardening-maven-test.log`。

## 4. 运行配置与注意事项

已有数据库、Redis、MinIO、Milvus、ES、RabbitMQ 的连接配置未替换。重启后端使新代码生效。

| 环境变量 | 默认 | 用途 |
| --- | --- | --- |
| AI_CHAT_MEMORY_STORE | redis | L1 使用 Redis；memory 仅作为显式单机调试选项 |
| AI_CHAT_RATE_LIMIT_PER_MINUTE | 10 | 每用户首次请求起 60 秒计数窗口 |
| APP_CORS_ALLOWED_ORIGINS | http://localhost:*,http://127.0.0.1:* | HTTP 与 WS 共用；远程前端需显式配置允许的 Origin |
| MANAGEMENT_PORT | 业务端口 | 可主动改为 8081 并配合防火墙和抓取端口调整 |
| PROMETHEUS_SCRAPE_TOKEN | 空 | 为空时关闭独立抓取凭证；内部 JWT 仍可读监控 |

Prometheus 使用独立随机凭证：应用环境变量配置 `PROMETHEUS_SCRAPE_TOKEN`，将相同值写入部署端只读 secret 文件，挂载为 `/etc/prometheus/secrets/app-token`。仓库中的抓取 YAML 已配置 `authorization.credentials_file`；缺少该挂载时抓取配置不可用。凭证文件不进入 Git。管理端口变化时同步修改 target。

WS 沿用 `CHAT_START.token` 和 `conversationId`，不把 JWT 放进 URL。浏览器匿名握手可以建立连接与 PING，但 CHAT_START 无合法 token 返回 401，不调用模型。此策略兼容当前客户端；若要完全拒绝匿名握手，需另做短期 WS ticket 或双方 subprotocol 认证协议。

## 5. 真实服务联调步骤（待执行）

以下是重启应用并准备测试账号后的补充验收，不属于本轮已通过的自动测试。

1. `GET /api/products/queryProduct?pageNum=1&pageSize=5`：带用户 JWT，确认 records≤5、total 为总数；第二页无重复；size=51 拒绝。
2. 新建独立测试商品，两名测试买家并发 `POST /api/orders`，body `{"productId":<测试商品ID>}`：只一人成功，数据库只有一份有效订单；取消后商品重新在售，重复取消不得释放新订单。
3. `POST /api/files/upload` multipart：匿名 401；登录后合法图片成功；exe 改名 jpg、无后缀和超 5MiB 文件拒绝。
4. WS：无 token CHAT_START 返回 401；合法 token 可聊；同用户第 11 次在 60 秒内被 429 拒绝；其他用户有独立计数。握手来自未允许 Origin 时拒绝。
5. `GET /actuator/prometheus`：匿名 401、USER 403；正确 scrape token 返回指标；同 token 调业务工具 401。配置管理端口为 8081 时验证 Prometheus 正常抓取。
6. 发布关联：他人/无效 assistId 与非法采纳类型拒绝；本人合法记录关联成功；仅反馈写入失败时商品成功且记录失败日志，按日志中的 assistId 补交反馈。
7. L1：同一账号、同 conversationId 聊完后重启应用继续，验证 Q/A 恢复；另一账号同 conversationId 不能读取；TTL 后不召回。

## 6. 剩余边界

- 本地提交后事件没有 outbox，进程在商品提交后崩溃可能丢失关联；监听同步执行，尚未隔离响应耗时。
- 使用 assistId 发布时归属校验仍依赖反馈读取。这是保留安全性的取舍；无 assistId 的普通发布不读取 AI 反馈表。
- 图片 MIME 检测不等于完整解码/病毒扫描；已有 bucket 被外部删除后需要恢复或重启使缓存失效。
- WS 当前生成在消息线程中同步执行，CHAT_CANCEL 不能可靠打断模型生成。
- 历史重复有效订单未自动清理；条件更新保护新操作，多实例雪花节点也需部署唯一性配置。
- Flyway、根 Compose、RAG 类拆分与目录搬迁应独立评审验收。本轮未把它们标记完成。
