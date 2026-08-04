# Runtime 与受控操作评测命令

在项目根目录打开 PowerShell，使用当前登录接口重新获取的 JWT：

```powershell
$BASE_URL = "http://localhost:8080"
$USER_TOKEN = "<USER_JWT>"
$INTERNAL_TOKEN = "<INTERNAL_JWT>"
$ADMIN_TOKEN = "<ADMIN_JWT>"
```

Runtime 故障恢复用例需要在启动 Spring Boot 前设置：

```powershell
$env:AI_AGENT_RUNTIME_FAULT_INJECTION_ENABLED = "true"
```

受控操作评测：

```powershell
python tools/controlled_operation_eval.py `
  --base-url $BASE_URL `
  --doc-id 19 `
  --user-bearer-token $USER_TOKEN `
  --internal-bearer-token $INTERNAL_TOKEN `
  --admin-bearer-token $ADMIN_TOKEN
```

Durable Runtime 评测：

```powershell
python tools/durable_runtime_eval.py `
  --base-url $BASE_URL `
  --user-bearer-token $USER_TOKEN `
  --internal-bearer-token $INTERNAL_TOKEN `
  --admin-bearer-token $ADMIN_TOKEN
```

报告默认生成在项目根目录。测试结束后关闭故障注入并重启服务：

```powershell
Remove-Item Env:AI_AGENT_RUNTIME_FAULT_INJECTION_ENABLED -ErrorAction SilentlyContinue
```
