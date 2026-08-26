#!/usr/bin/env bash
set -euo pipefail

# This helper is the only process allowed to translate a logical runbook target
# into Docker commands. The Java service never accepts container names, images,
# ports, or arbitrary shell fragments from an API request.

ACTION="${1:-}"
TARGET_ID="${2:-}"

STATE_DIR="${RUNBOOK_STATE_DIR:-/var/lib/used-market-runbook}"
LOCK_FILE="${RUNBOOK_LOCK_FILE:-/var/lock/used-market-runbook.lock}"

case "$TARGET_ID" in
  marketplace-demo-web)
    CONTAINER_NAME="used-market-runbook-demo"
    NETWORK_NAME="used-market-runbook-net"
    ROLLBACK_IMAGE="nginx:1.27-alpine"
    HOST_PORT="18080"
    ;;
  *)
    echo "target is not allowlisted" >&2
    exit 64
    ;;
esac

mkdir -p "$STATE_DIR"
exec 9>"$LOCK_FILE"
if ! flock -w 15 9; then
  echo "another runbook operation is in progress" >&2
  exit 75
fi

snapshot_file="$STATE_DIR/${TARGET_ID}-latest.snapshot"

ensure_network() {
  if ! docker network inspect "$NETWORK_NAME" >/dev/null 2>&1; then
    docker network create \
      --driver bridge \
      --label "used-market.runbook-target=$TARGET_ID" \
      "$NETWORK_NAME" >/dev/null
  fi
}

case "$ACTION" in
  bootstrap)
    docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true
    ensure_network
    docker run -d \
      --name "$CONTAINER_NAME" \
      --restart unless-stopped \
      --network "$NETWORK_NAME" \
      --label "used-market.runbook-target=$TARGET_ID" \
      -p "${HOST_PORT}:80" \
      "$ROLLBACK_IMAGE"
    ;;
  restart)
    current_image="$(docker inspect --format '{{.Config.Image}}' "$CONTAINER_NAME")"
    printf 'target=%s\ncontainer=%s\noriginal_image=%s\nsnapshot_at=%s\n' \
      "$TARGET_ID" "$CONTAINER_NAME" "$current_image" "$(date -u +%FT%TZ)" >"$snapshot_file"
    docker restart --time 10 "$CONTAINER_NAME"
    ;;
  remove)
    if [[ ! -f "$snapshot_file" ]]; then
      echo "operation snapshot not found" >&2
      exit 66
    fi
    docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true
    ;;
  recreate)
    if [[ ! -f "$snapshot_file" ]]; then
      echo "operation snapshot not found" >&2
      exit 66
    fi
    ensure_network
    docker run -d \
      --name "$CONTAINER_NAME" \
      --restart unless-stopped \
      --network "$NETWORK_NAME" \
      --label "used-market.runbook-target=$TARGET_ID" \
      --label "used-market.rollback-source=trusted-image" \
      -p "${HOST_PORT}:80" \
      "$ROLLBACK_IMAGE"
    ;;
  inspect)
    docker inspect --format \
      'container={{.Name}} image={{.Config.Image}} status={{.State.Status}} started_at={{.State.StartedAt}} networks={{json .NetworkSettings.Networks}}' \
      "$CONTAINER_NAME"
    ;;
  cleanup)
    docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true
    docker network rm "$NETWORK_NAME" >/dev/null 2>&1 || true
    rm -f "$snapshot_file"
    ;;
  *)
    echo "action is not allowlisted" >&2
    exit 64
    ;;
esac
