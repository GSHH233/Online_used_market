#!/usr/bin/env bash
set -Eeuo pipefail

# Restart the Docker Compose services used by Online_used_marketplace on the VM.
# This script recreates compose networks without deleting volumes.
# Do NOT add "-v" to docker compose down unless you intentionally want data loss.

BASE_HOME="${BASE_HOME:-$HOME}"
RESTART_DOCKER=0
PRUNE_NETWORKS=0
ONLY=""

usage() {
  cat <<'EOF'
Usage:
  bash restart_vm_containers.sh [options]

Options:
  --restart-docker   Restart Docker daemon first. Useful when Docker NAT is dirty.
  --prune-networks   Run docker network prune -f before compose up. Safe for unused networks only.
  --only NAME        Restart one stack: pg16 | es | milvus | prometheus | myredis
  -h, --help         Show help.

Environment:
  BASE_HOME=/home/your-user   Override compose folder base path.

Examples:
  bash restart_vm_containers.sh
  bash restart_vm_containers.sh --restart-docker
  bash restart_vm_containers.sh --only milvus
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --restart-docker)
      RESTART_DOCKER=1
      shift
      ;;
    --prune-networks)
      PRUNE_NETWORKS=1
      shift
      ;;
    --only)
      ONLY="${2:-}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "[ERROR] Unknown option: $1" >&2
      usage
      exit 2
      ;;
  esac
done

if docker compose version >/dev/null 2>&1; then
  COMPOSE=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE=(docker-compose)
else
  echo "[ERROR] docker compose / docker-compose not found." >&2
  exit 1
fi

log() {
  printf '\n[%s] %s\n' "$(date '+%F %T')" "$*"
}

run_stack() {
  local name="$1"
  local dir="$2"
  local file="$3"

  if [[ -n "$ONLY" && "$ONLY" != "$name" ]]; then
    return 0
  fi

  if [[ ! -d "$dir" ]]; then
    log "SKIP $name: directory not found: $dir"
    return 0
  fi
  if [[ ! -f "$dir/$file" ]]; then
    log "SKIP $name: compose file not found: $dir/$file"
    return 0
  fi

  log "Restarting $name ($dir/$file)"
  (
    cd "$dir"
    "${COMPOSE[@]}" -f "$file" down --remove-orphans
    "${COMPOSE[@]}" -f "$file" up -d
    "${COMPOSE[@]}" -f "$file" ps
  )
}

if [[ "$RESTART_DOCKER" == "1" ]]; then
  log "Restarting Docker daemon"
  sudo systemctl restart docker
  sleep 3
fi

if [[ "$PRUNE_NETWORKS" == "1" ]]; then
  log "Pruning unused Docker networks"
  docker network prune -f
fi

log "Compose command: ${COMPOSE[*]}"
log "Base home: $BASE_HOME"

run_stack "pg16"       "$BASE_HOME/pg16"              "docker-compose.yml"
run_stack "es"         "$BASE_HOME/es"                "docker-compose.yml"
run_stack "milvus"     "$BASE_HOME/milvus-standalone" "docker-compose.yml"
run_stack "prometheus" "$BASE_HOME/Prometheus"        "docker-compose.prometheus.yml"

# Optional stack. It is skipped automatically if the folder or compose file does not exist.
run_stack "myredis"    "$BASE_HOME/myredis"           "docker-compose.yml"

log "Container status"
docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'

if command -v nc >/dev/null 2>&1; then
  log "Port checks"
  for item in \
    "PostgreSQL:127.0.0.1:5432" \
    "Elasticsearch:127.0.0.1:9200" \
    "Milvus:127.0.0.1:19530" \
    "Prometheus:127.0.0.1:9090"; do
    name="${item%%:*}"
    host_port="${item#*:}"
    host="${host_port%:*}"
    port="${host_port##*:}"
    if nc -z "$host" "$port" >/dev/null 2>&1; then
      echo "[OK]   $name $host:$port"
    else
      echo "[WARN] $name $host:$port not reachable yet"
    fi
  done
else
  log "nc not found, skip port checks"
fi

log "Done"
