#!/usr/bin/env sh
# Validates Flyway migration naming and ordering (docs/architecture/database-migrations.md):
#   versioned:  V{NNN}__{module}_{description}.sql   (3-digit, unique, no gaps)
#   repeatable: R__{module}_{description}.sql
set -eu

DIR="${1:-backend/src/main/resources/db/migration}"
MODULES="shared|auth|members|belts|scheduling|registrations|attendance|reporting|notifications"
status=0
expected=1

for path in $(ls "$DIR" | sort); do
  case "$path" in
    V*)
      if ! echo "$path" | grep -Eq "^V[0-9]{3}__(${MODULES})_[a-z0-9_]+\.sql$"; then
        echo "ERROR: invalid versioned migration name: $path"; status=1; continue
      fi
      version=$(echo "$path" | sed -E 's/^V0*([0-9]+)__.*/\1/')
      if [ "$version" -ne "$expected" ]; then
        echo "ERROR: $path has version $version, expected $expected (duplicate or gap)"; status=1
      fi
      expected=$((version + 1))
      ;;
    R__*)
      if ! echo "$path" | grep -Eq "^R__(${MODULES})_[a-z0-9_]+\.sql$"; then
        echo "ERROR: invalid repeatable migration name: $path"; status=1
      fi
      ;;
    *)
      echo "ERROR: unexpected file in migration folder: $path"; status=1
      ;;
  esac
done

[ "$status" -eq 0 ] && echo "Flyway migrations OK (next version: V$(printf '%03d' "$expected"))"
exit "$status"
