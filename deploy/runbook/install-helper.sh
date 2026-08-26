#!/usr/bin/env bash
set -euo pipefail

SOURCE="${1:-./used-market-docker-runbook-helper.sh}"
DESTINATION="/usr/local/sbin/used-market-docker-runbook"
RUNBOOK_USER="${RUNBOOK_USER:-gshhh}"

if [[ ! -f "$SOURCE" ]]; then
  echo "helper script not found: $SOURCE" >&2
  exit 66
fi

install -o root -g root -m 0755 "$SOURCE" "$DESTINATION"
install -d -o root -g root -m 0750 /var/lib/used-market-runbook

cat > /etc/sudoers.d/used-market-runbook <<EOF
${RUNBOOK_USER} ALL=(root) NOPASSWD: ${DESTINATION} restart marketplace-demo-web, ${DESTINATION} remove marketplace-demo-web, ${DESTINATION} recreate marketplace-demo-web, ${DESTINATION} inspect marketplace-demo-web, ${DESTINATION} bootstrap marketplace-demo-web
EOF
chmod 0440 /etc/sudoers.d/used-market-runbook
visudo -cf /etc/sudoers.d/used-market-runbook

echo "installed $DESTINATION for user $RUNBOOK_USER"
