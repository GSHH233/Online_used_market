# PowerShell Development Workflow

## Purpose

This repository is developed on Windows with PowerShell 7. Maven and pyenv are
implemented through `.cmd` or `.bat` wrappers, so commands must use PowerShell
syntax consistently.

## Start a shell

Use a project virtual environment explicitly. Dot-source the initialization
script so its environment variables remain in the current PowerShell session.

```powershell
. .\scripts\Initialize-ProjectShell.ps1 -PythonEnvironment ragas64
& $env:PROJECT_PYTHON .\tools\ragas_rag_eval.py --help
```

Available environments are `system`, `deepeval`, `ragas`, and `ragas64`.

For a one-off command, use the wrapper instead:

```powershell
.\scripts\Invoke-ProjectPython.ps1 -PythonEnvironment ragas64 .\tools\ragas_rag_eval.py --help
```

## Command rules

- Use PowerShell commands only. Do not copy CMD line continuation `^` or Bash
  continuation `\` into PowerShell.
- Prefer one command per line. For long argument lists, use a PowerShell array
  instead of a trailing backtick.
- Invoke a virtual-environment Python executable through `PROJECT_PYTHON` or
  `Invoke-ProjectPython.ps1`; do not rely on whichever `python` happens to be
  first on `PATH`.
- Use `curl.exe` when a command expects native curl behavior.
- Start and stop the long-running Spring Boot service from the IntelliJ run
  configuration where possible. `mvn.cmd` is a Windows batch wrapper, so a
  terminal Ctrl+C may legitimately show `Terminate batch job (Y/N)?`.

## Encoding

`.mvn/jvm.config` forces Maven to run with UTF-8. The PowerShell initialization
script also sets console and Python output encoding to UTF-8. Do not add a
machine-wide encoding override to the PowerShell profile for this project.

## Stable long commands

```powershell
$cliArgs = @(
    '.\tools\ragas_rag_eval.py',
    '--base-url', 'http://localhost:8081',
    '--dataset', '.\eval\datasets\ragas_development_v5.json'
)

.\scripts\Invoke-ProjectPython.ps1 -PythonEnvironment ragas64 @cliArgs
```
