#!/bin/sh
set -eu
# Only a port enters the generated JSON; never interpolate arbitrary environment text.
port=${GRAFANA_PORT:-3000}
case "$port" in ''|*[!0-9]*) echo 'GRAFANA_PORT must be numeric' >&2; exit 1 ;; esac
[ "$port" -ge 1 ] && [ "$port" -le 65535 ]
printf '{"grafanaPort":%s}\n' "$port" > /tmp/wallet-config.json
exec nginx -g 'daemon off;'
