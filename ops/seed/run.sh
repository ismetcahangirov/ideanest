#!/usr/bin/env bash
#
# Loads the local demo seed. Development only -- see README.md in this
# directory for what it creates and why none of it belongs anywhere else.
#
#   ops/seed/run.sh                      # against the compose database
#   PGPORT=5433 ops/seed/run.sh          # when 5432 is taken by a local server
#   SEED_VIA_DOCKER=0 ops/seed/run.sh    # use a psql on PATH instead of the container
#
# The migrations have to have run first: this file writes rows, it does not
# create tables. Start the service once (`./gradlew bootRun` in apps/api) and
# let Flyway finish before running it.

set -euo pipefail

cd "$(dirname "$0")"

PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5432}"
PGUSER="${PGUSER:-ideanest}"
PGPASSWORD="${PGPASSWORD:-ideanest}"
PGDATABASE="${PGDATABASE:-ideanest}"
SEED_VIA_DOCKER="${SEED_VIA_DOCKER:-1}"
SEED_CONTAINER="${SEED_CONTAINER:-ideanest-postgres}"

# Order matters. Later files reference rows earlier files created, and the
# helper definitions in 00 are dropped again by 09, so 00 is prepended to every
# invocation rather than run once.
FILES=(
    01_accounts.sql
    02_projects.sql
    02b_more_campaigns.sql
    03_rewards.sql
    04_backers.sql
    05_pledges.sql
    06_community.sql
    07_operations.sql
    08_recompute.sql
    09_cleanup.sql
)

run_sql() {
    if [ "$SEED_VIA_DOCKER" = "1" ]; then
        docker exec -i -e PGCLIENTENCODING=UTF8 "$SEED_CONTAINER" \
            psql -U "$PGUSER" -d "$PGDATABASE" -v ON_ERROR_STOP=1 -q
    else
        PGPASSWORD="$PGPASSWORD" PGCLIENTENCODING=UTF8 \
            psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -v ON_ERROR_STOP=1 -q
    fi
}

for file in "${FILES[@]}"; do
    printf '  %s ... ' "$file"
    # 00 first, every time: 09 drops the helpers, and a partial re-run would
    # otherwise fail on a function that no longer exists.
    #
    # THE OUTPUT IS CAPTURED RATHER THAN PIPED. psql's exit code is lost through
    # a pipe, and a seed that reports success over a constraint violation is
    # worse than one that fails loudly.
    if output=$(cat 00_helpers.sql "$file" | run_sql 2>&1); then
        if grep -qi 'ERROR' <<<"$output"; then
            printf 'failed\n\n%s\n' "$output" >&2
            exit 1
        fi
        printf 'ok\n'
    else
        printf 'failed\n\n%s\n' "$output" >&2
        exit 1
    fi
done

printf '\nSeeded. Every account signs in with: IdeaNest2026!\n'
