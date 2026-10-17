#!/usr/bin/env sh
# Loads local test data (trainer, 12 members, a training in progress today). LOCAL DEVELOPMENT ONLY.
# Password for every test account: trening-lokalno. See scripts/dev-seed.sql.
set -eu
cd "$(dirname "$0")/.."
docker compose exec -T db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1' < scripts/dev-seed.sql
