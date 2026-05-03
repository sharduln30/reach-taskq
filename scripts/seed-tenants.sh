#!/usr/bin/env bash
# Seeds (or shows) the dev tenant + API key. The app self-seeds on boot via
# DevTenantSeeder when taskq.dev.seed-tenant=true (default in local profile).
# This script is the operator-facing way to retrieve the credentials and to
# verify Postgres has the row.
#
# Outputs:
#   E2E_API_KEY=<key>
#   TENANT_ID=<uuid>
#
# Usage:
#   ./scripts/seed-tenants.sh
#   eval "$(./scripts/seed-tenants.sh)"   # to export into your shell

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ROOT}/.env"
[[ -f "${ENV_FILE}" ]] || ENV_FILE="${ROOT}/.env.example"

# shellcheck disable=SC1090
set -a; source "${ENV_FILE}"; set +a

API_KEY="${TASKQ_DEV_SEED_API_KEY:-demo-api-key-do-not-use-in-prod}"
TENANT_NAME="${TASKQ_DEV_SEED_TENANT_NAME:-demo}"
PGDB="${POSTGRES_DB:-taskq}"
PGUSER="${POSTGRES_USER:-taskq}"
PGPASS="${POSTGRES_PASSWORD:-taskq_dev_pw}"
PG_CONTAINER="${PG_CONTAINER:-taskq-postgres}"

WAIT_SEC="${WAIT_SEC:-60}"
QUERY="SELECT id FROM tenants WHERE name = '${TENANT_NAME}' AND active = TRUE LIMIT 1;"

run_query() {
  if docker ps --format '{{.Names}}' 2>/dev/null | grep -qx "${PG_CONTAINER}"; then
    docker exec -e PGPASSWORD="${PGPASS}" "${PG_CONTAINER}" \
      psql -U "${PGUSER}" -d "${PGDB}" -tAc "${QUERY}" 2>/dev/null || true
    return
  fi
  if command -v psql >/dev/null 2>&1; then
    PGHOST_LOCAL="${POSTGRES_HOST_LOCAL:-localhost}"
    PGPORT_LOCAL="${POSTGRES_PORT:-5432}"
    PGPASSWORD="${PGPASS}" psql -h "${PGHOST_LOCAL}" -p "${PGPORT_LOCAL}" \
      -U "${PGUSER}" -d "${PGDB}" -tAc "${QUERY}" 2>/dev/null || true
    return
  fi
  echo "" # neither path available
}

deadline=$(( $(date +%s) + WAIT_SEC ))
TENANT_ID=""
while [[ -z "${TENANT_ID}" && "$(date +%s)" -lt "${deadline}" ]]; do
  TENANT_ID="$(run_query | tr -d '[:space:]')"
  [[ -n "${TENANT_ID}" ]] && break
  sleep 1
done

if [[ -z "${TENANT_ID}" ]]; then
  echo "ERROR: tenant '${TENANT_NAME}' not found in Postgres after ${WAIT_SEC}s." >&2
  echo "       Checked container '${PG_CONTAINER}' first, then host psql." >&2
  echo "       Verify the app is up and seeded:" >&2
  echo "         docker logs ${PG_CONTAINER%-postgres}-app 2>&1 | grep -i devtenant" >&2
  echo "         docker exec ${PG_CONTAINER} psql -U ${PGUSER} -d ${PGDB} -c 'SELECT name FROM tenants;'" >&2
  exit 1
fi

echo "E2E_API_KEY=${API_KEY}"
echo "TENANT_ID=${TENANT_ID}"
