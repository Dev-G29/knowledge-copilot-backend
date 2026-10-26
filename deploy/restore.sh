#!/usr/bin/env bash
# Restores a pg_dump file into the running stack's database, then restarts the backend
# so Flyway migrates it (e.g. converts an old Render database to pgvector).
#
#   ./restore.sh backups/knowledge_copilot-20261025T030000Z.dump
#   ./restore.sh ~/render-backup.dump
set -euo pipefail

dump="${1:?usage: ./restore.sh <file.dump>}"
cd "$(dirname "$0")"
set -a; source .env; set +a
db="${POSTGRES_DB:-knowledge_copilot}"
user="${POSTGRES_USER:-copilot}"

echo "This replaces ALL data in database '${db}' with ${dump}."
read -r -p "Type 'restore' to continue: " answer
[ "${answer}" = "restore" ] || { echo "Aborted."; exit 1; }

docker compose stop backend
docker compose exec -T postgres psql -U "${user}" -d postgres -v ON_ERROR_STOP=1 \
  -c "drop database if exists \"${db}\" with (force);" \
  -c "create database \"${db}\" owner \"${user}\";"
docker compose exec -T postgres pg_restore -U "${user}" -d "${db}" --no-owner --no-privileges < "${dump}"
docker compose start backend
echo "Restored. Follow the migration with: docker compose logs -f backend"
