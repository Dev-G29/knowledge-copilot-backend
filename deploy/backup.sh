#!/usr/bin/env bash
# Dumps the database every BACKUP_INTERVAL_SECONDS and prunes old dumps.
set -euo pipefail

retention_days="${BACKUP_RETENTION_DAYS:-14}"
interval="${BACKUP_INTERVAL_SECONDS:-86400}"

mkdir -p /backups

while true; do
  file="/backups/${PGDATABASE}-$(date -u +%Y%m%dT%H%M%SZ).dump"
  echo "[backup] writing ${file}"
  if pg_dump --format=custom --no-owner --file="${file}.partial"; then
    mv "${file}.partial" "${file}"
    echo "[backup] done ($(du -h "${file}" | cut -f1))"
  else
    echo "[backup] pg_dump failed" >&2
    rm -f "${file}.partial"
  fi
  find /backups -name '*.dump' -mtime +"${retention_days}" -print -delete
  sleep "${interval}"
done
