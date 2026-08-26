# Isolated Docker Runbook Target

This directory provisions one disposable container named
`used-market-runbook-demo`. The application API accepts only the logical target
`marketplace-demo-web`; container names, images, ports, and shell commands stay
inside the root-owned helper.

## Install on the Docker host

```bash
cd /path/to/Online_used_marketplace/deploy/runbook
sudo RUNBOOK_USER=gshhh ./install-helper.sh
sudo /usr/local/sbin/used-market-docker-runbook bootstrap marketplace-demo-web
curl --fail http://127.0.0.1:18080/
```

Use SSH public-key authentication from the Spring Boot host. Do not place an
SSH password or private-key content in `application.yml`.

The helper creates a dedicated bridge network named `used-market-runbook-net`;
it does not attach existing project containers to that network. The normal
path snapshots the current image, restarts the container, and lets
the Java executor perform an HTTP health check. If that check fails, the Java
executor calls the fixed `remove` and `recreate` actions. They recreate only the
disposable container from the pinned `nginx:1.27-alpine` image before a second
health check.

After live verification, remove only the disposable target and its dedicated
network:

```bash
sudo /usr/local/sbin/used-market-docker-runbook cleanup marketplace-demo-web
```

## Enable the Spring Boot adapter

The feature is disabled by default and does not require edits to
`application.yml`. Configure it in the IDEA run configuration or the process
environment:

```powershell
$env:AI_AIOPS_DOCKER_RUNBOOK_ENABLED = "true"
$env:AI_AIOPS_DOCKER_RUNBOOK_TRANSPORT = "ssh-helper"
$env:AI_AIOPS_DOCKER_RUNBOOK_TARGET_ID = "marketplace-demo-web"
$env:AI_AIOPS_DOCKER_RUNBOOK_HEALTH_URL = "http://192.168.152.128:18080/"
$env:AI_AIOPS_DOCKER_RUNBOOK_SSH_HOST = "192.168.152.128"
$env:AI_AIOPS_DOCKER_RUNBOOK_SSH_PORT = "22"
$env:AI_AIOPS_DOCKER_RUNBOOK_SSH_USER = "gshhh"
$env:AI_AIOPS_DOCKER_RUNBOOK_SSH_KEY_PATH = "$HOME\.ssh\id_ed25519"
```

Before starting Spring Boot, verify non-interactive access:

```powershell
ssh -o BatchMode=yes gshhh@192.168.152.128 `
  "sudo /usr/local/sbin/used-market-docker-runbook inspect marketplace-demo-web"
```

This target is intentionally separate from PostgreSQL, Elasticsearch, Milvus,
Redis, RabbitMQ, Prometheus, and the marketplace application itself.
