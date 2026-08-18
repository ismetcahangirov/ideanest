#!/usr/bin/env bash
#
# The rehearsal §19.4 requires, as something that runs rather than something
# that is described.
#
# It builds a real cluster from this repository's own migrations, archives its
# write-ahead log the way production is configured to, takes a base backup,
# writes rows on both sides of a chosen instant, **destroys the source**, and
# then recovers to that instant from nothing but the archive and verifies the
# result. Every step uses the scripts in ops/backup that a real recovery would
# use; nothing here is a drill-only shortcut.
#
#   ./ops/backup/drill/run-drill.sh                        the rehearsal
#   ./ops/backup/drill/run-drill.sh --negative late-target a restore that must fail
#
# Negative cases exist because a verification nobody has seen fail is a
# verification nobody has tested:
#
#   late-target     recover to an instant *after* a row that must not be there.
#                   The recovery succeeds; the verification must not.
#   schema-history  remove a row from flyway_schema_history after recovery, the
#                   shape of a base backup taken against a different release.
#   require-ledger  demand the ledger tables §7.2 specifies and no migration
#                   creates yet. This is the drill's own exception, asserted.
#
# Requirements: docker, and network access the first time (two images).
# Everything else — PostgreSQL, age, Flyway — is in a container.

set -euo pipefail

# Git Bash on Windows rewrites arguments that look like absolute paths before
# docker sees them, which turns `container:/flyway/sql` into a local directory.
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKUP_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$BACKUP_DIR/../.." && pwd)"
MIGRATION_DIR="$REPO_ROOT/apps/api/src/main/resources/db/migration"

NEGATIVE=""
KEEP=0
REPORT_OUT="${IDEANEST_DRILL_REPORT:-}"

while [ $# -gt 0 ]; do
  case "$1" in
    --negative) NEGATIVE="${2:?--negative needs a case}"; shift 2 ;;
    --keep)     KEEP=1; shift ;;
    --report)   REPORT_OUT="${2:?}"; shift 2 ;;
    -h|--help)  sed -n '2,30p' "$0"; exit 0 ;;
    *) printf 'unknown argument: %s\n' "$1" >&2; exit 2 ;;
  esac
done

case "$NEGATIVE" in
  ''|late-target|schema-history|require-ledger) ;;
  *) printf 'unknown negative case: %s\n' "$NEGATIVE" >&2; exit 2 ;;
esac

# Path conversion is off for the whole process (above), which is right for the
# container side of every argument and wrong for the host side: docker on Windows
# wants a Windows path for a build context or a `docker cp` source. Converted
# explicitly, here, rather than by leaving the automatic rewriting on and hoping
# it guesses correctly.
hostpath() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

say() { printf '\n\033[1m>> %s\033[0m\n' "$*" >&2; }
info() { printf '   %s\n' "$*" >&2; }
fail() { printf '\nDRILL FAILED: %s\n' "$*" >&2; exit 1; }

command -v docker >/dev/null 2>&1 || fail "docker is not installed"
[ -d "$MIGRATION_DIR" ] || fail "no migrations at $MIGRATION_DIR"

RUN_ID="$(date -u +%Y%m%d%H%M%S)-$$"
IMAGE="ideanest-restore-drill:local"
NETWORK="ideanest-drill-net-$RUN_ID"
SOURCE="ideanest-drill-source-$RUN_ID"
TARGET="ideanest-drill-target-$RUN_ID"
FLYWAY="ideanest-drill-flyway-$RUN_ID"
ARCHIVE_VOLUME="ideanest-drill-archive-$RUN_ID"
KEYDIR="$(mktemp -d)"

# Local to this container, thrown away with it, and never the shape of a
# deployed credential. Deployed environments take DB_PASSWORD from the secret
# store; see apps/api/src/main/resources/application-local.yml.
DB_USER="ideanest"
DB_PASSWORD="drill-local-only"
DB_NAME="ideanest"

cleanup() {
  STATUS=$?
  if [ "$KEEP" -eq 1 ]; then
    printf '\n--keep: leaving %s, %s, %s, volume %s\n' "$SOURCE" "$TARGET" "$NETWORK" "$ARCHIVE_VOLUME" >&2
  else
    docker rm -f "$SOURCE" "$TARGET" "$FLYWAY" >/dev/null 2>&1 || true
    docker volume rm "$ARCHIVE_VOLUME" >/dev/null 2>&1 || true
    docker network rm "$NETWORK" >/dev/null 2>&1 || true
  fi
  rm -rf "$KEYDIR"
  exit $STATUS
}
trap cleanup EXIT INT TERM

# --------------------------------------------------------------------------
# What the repository says the restored schema should look like
# --------------------------------------------------------------------------
#
# Read from the migrations rather than written down here. An expectation that is
# maintained by hand drifts, and a drill that verifies a stale expectation is a
# drill that passes while the restore is wrong.

EXPECT_MIGRATIONS="$(find "$MIGRATION_DIR" -name 'V*__*.sql' | wc -l | tr -d ' ')"
EXPECT_VERSION="$(find "$MIGRATION_DIR" -name 'V*__*.sql' | sed 's|.*/V\([0-9][0-9]*\)__.*|\1|' | sort -n | tail -1)"
EXPECT_RELATIONS="$(grep -h '^CREATE TABLE ' "$MIGRATION_DIR"/*.sql \
  | sed 's/^CREATE TABLE \([A-Za-z_][A-Za-z0-9_]*\).*/\1/' | sort -u | paste -sd, -)"

say "drill $RUN_ID${NEGATIVE:+ (negative case: $NEGATIVE)}"
info "repository is at V$EXPECT_VERSION with $EXPECT_MIGRATIONS migrations"

# --------------------------------------------------------------------------
say "building the drill image"
# --------------------------------------------------------------------------

docker build --quiet -t "$IMAGE" -f "$(hostpath "$SCRIPT_DIR/Dockerfile")" "$(hostpath "$BACKUP_DIR")" >/dev/null

# --------------------------------------------------------------------------
say "generating an archive key pair"
# --------------------------------------------------------------------------
#
# The source host is given the **public** key and never sees the private one,
# which is the property that matters: a database host that is compromised can
# add to the archive and cannot read it. In a deployed environment the identity
# is in the secret store and is fetched by the person doing the restore.

docker run --rm "$IMAGE" age-keygen >"$KEYDIR/identity.txt" 2>/dev/null
chmod 0600 "$KEYDIR/identity.txt"
RECIPIENT="$(sed -n 's/^# public key: //p' "$KEYDIR/identity.txt")"
[ -n "$RECIPIENT" ] || fail "could not read the public key out of age-keygen"
info "recipient $RECIPIENT"

# --------------------------------------------------------------------------
say "starting the source cluster with continuous archiving"
# --------------------------------------------------------------------------

docker network create "$NETWORK" >/dev/null
docker volume create "$ARCHIVE_VOLUME" >/dev/null
docker run --rm -v "$ARCHIVE_VOLUME:/archive" "$IMAGE" \
  sh -c 'mkdir -p /archive/wal /archive/base && chown -R postgres:postgres /archive'

docker run -d --name "$SOURCE" --network "$NETWORK" \
  -v "$ARCHIVE_VOLUME:/archive" \
  -e POSTGRES_DB="$DB_NAME" \
  -e POSTGRES_USER="$DB_USER" \
  -e POSTGRES_PASSWORD="$DB_PASSWORD" \
  -e POSTGRES_INITDB_ARGS='--data-checksums --locale-provider=icu --icu-locale=en-US --encoding=UTF8' \
  -e IDEANEST_BACKUP_ARCHIVE_DIR=/archive \
  -e IDEANEST_BACKUP_ENCRYPTION=age \
  -e IDEANEST_BACKUP_AGE_RECIPIENT="$RECIPIENT" \
  "$IMAGE" \
  postgres \
    -c wal_level=replica \
    -c archive_mode=on \
    -c 'archive_command=/opt/ideanest/archive-wal.sh %p %f' \
    -c archive_timeout=30 \
    -c max_wal_senders=4 \
    -c log_min_messages=warning \
  >/dev/null

for _ in $(seq 1 60); do
  if docker exec "$SOURCE" pg_isready -U "$DB_USER" -d "$DB_NAME" >/dev/null 2>&1; then break; fi
  sleep 1
done
docker exec "$SOURCE" pg_isready -U "$DB_USER" -d "$DB_NAME" >/dev/null 2>&1 \
  || { docker logs "$SOURCE" | tail -30 >&2; fail "the source cluster never became ready"; }

src_sql() { docker exec -i "$SOURCE" psql -qAtX -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB_NAME" -c "$1"; }

info "data checksums: $(src_sql 'SHOW data_checksums')"

# --------------------------------------------------------------------------
say "applying the repository's migrations with Flyway"
# --------------------------------------------------------------------------
#
# The real Flyway, on the real migrations, so that `flyway_schema_history` in
# the restored copy is the thing the application would have written and not a
# table this script made up to satisfy its own check.

docker create --name "$FLYWAY" --network "$NETWORK" flyway/flyway:11-alpine \
  "-url=jdbc:postgresql://$SOURCE:5432/$DB_NAME" \
  "-user=$DB_USER" \
  "-password=$DB_PASSWORD" \
  -connectRetries=30 \
  -outOfOrder=false \
  migrate >/dev/null
docker cp "$(hostpath "$MIGRATION_DIR")/." "$FLYWAY:/flyway/sql/"
docker start -a "$FLYWAY" >/dev/null 2>&1 || { docker logs "$FLYWAY" | tail -40 >&2; fail "flyway migrate failed"; }
docker rm -f "$FLYWAY" >/dev/null 2>&1 || true

APPLIED="$(src_sql 'SELECT count(*) FROM flyway_schema_history WHERE version IS NOT NULL')"
info "$APPLIED migrations applied"
[ "$APPLIED" = "$EXPECT_MIGRATIONS" ] || fail "flyway applied $APPLIED of $EXPECT_MIGRATIONS migrations"

# --------------------------------------------------------------------------
say "taking a base backup"
# --------------------------------------------------------------------------

BACKUP_PATH="$(docker exec -u postgres -e PGUSER="$DB_USER" -e PGDATABASE="$DB_NAME" "$SOURCE" \
  /opt/ideanest/base-backup.sh --label "base-drill-$RUN_ID" | tail -1)"
[ -n "$BACKUP_PATH" ] || fail "base-backup.sh produced no path"
info "base backup at $BACKUP_PATH"
docker exec "$SOURCE" ls -l "$BACKUP_PATH" >&2

# --------------------------------------------------------------------------
say "writing the rows the verification will look for"
# --------------------------------------------------------------------------
#
# Everything below happens *after* the base backup, so none of it is in the
# backup and all of it has to come out of the write-ahead log. A drill whose
# witnesses were already in the base backup would pass without replaying
# anything.

WITNESS_BEFORE="$(src_sql "
  INSERT INTO audit_logs (id, actor_type, actor_id, action, entity_type, entity_id, outcome, detail)
  VALUES (gen_random_uuid(), 'SYSTEM', NULL, 'drill.witness.before', 'drill', gen_random_uuid(),
          'SUCCEEDED', 'restore drill: committed before the recovery target')
  RETURNING id")"
info "before-target witness $WITNESS_BEFORE"

# One transaction, two tables. §8.3's outbox exists because a commit and its
# event must not diverge; if a recovery could produce one without the other,
# that guarantee would end at the restore.
ATOMIC_WITNESS="$(src_sql 'SELECT gen_random_uuid()')"
docker exec -i "$SOURCE" psql -qAtX -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB_NAME" >/dev/null <<SQL
BEGIN;
INSERT INTO audit_logs (id, actor_type, actor_id, action, entity_type, entity_id, outcome, request_id, detail)
VALUES (gen_random_uuid(), 'SYSTEM', NULL, 'drill.witness.atomic', 'drill', '$ATOMIC_WITNESS'::uuid,
        'SUCCEEDED', '$ATOMIC_WITNESS', 'restore drill: one half of an atomic pair');
INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, next_attempt_at)
VALUES (gen_random_uuid(), 'drill', '$ATOMIC_WITNESS'::uuid, 'drill.witness.atomic',
        '{"drill":"$RUN_ID"}', now());
COMMIT;
SQL
info "atomic pair witness $ATOMIC_WITNESS"

sleep 1
TARGET_TIME="$(src_sql 'SELECT clock_timestamp()')"
info "recovery target $TARGET_TIME"
sleep 1

WITNESS_AFTER="$(src_sql "
  INSERT INTO audit_logs (id, actor_type, actor_id, action, entity_type, entity_id, outcome, detail)
  VALUES (gen_random_uuid(), 'SYSTEM', NULL, 'drill.witness.after', 'drill', gen_random_uuid(),
          'SUCCEEDED', 'restore drill: committed AFTER the recovery target and must not survive')
  RETURNING id")"
info "after-target witness $WITNESS_AFTER"

AFTER_TIME="$(src_sql 'SELECT clock_timestamp()')"

# One more commit, after the instant the `late-target` case aims at.
#
# Without it that case does not test what it means to test. `recovery_target_time`
# is compared against **commit records**, so a target past the last commit in the
# archive is a target recovery can never reach: PostgreSQL replays everything and
# then refuses to promote — "recovery ended before configured recovery target was
# reached" — which is a restore that *failed*. The negative case is about a
# restore that *succeeds and is wrong*, and only a later commit makes that
# reachable. Cheap here, and worth a paragraph because the failure looks like a
# broken drill rather than a misplaced target.
src_sql "
  INSERT INTO audit_logs (id, actor_type, actor_id, action, entity_type, entity_id, outcome, detail)
  VALUES (gen_random_uuid(), 'SYSTEM', NULL, 'drill.witness.trailing', 'drill', gen_random_uuid(),
          'SUCCEEDED', 'restore drill: a commit after every target, so every target is reachable')
  " >/dev/null

# --------------------------------------------------------------------------
say "flushing the write-ahead log into the archive"
# --------------------------------------------------------------------------

LAST_SEGMENT="$(src_sql 'SELECT pg_walfile_name(pg_current_wal_lsn())')"
src_sql 'SELECT pg_switch_wal()' >/dev/null
src_sql 'CHECKPOINT' >/dev/null

for _ in $(seq 1 60); do
  ARCHIVED="$(src_sql "SELECT coalesce(last_archived_wal, '') >= '$LAST_SEGMENT' FROM pg_stat_archiver")"
  [ "$ARCHIVED" = "t" ] && break
  sleep 1
done
[ "$ARCHIVED" = "t" ] || fail "segment $LAST_SEGMENT was never archived"

ARCHIVE_FAILURES="$(src_sql 'SELECT failed_count FROM pg_stat_archiver')"
[ "$ARCHIVE_FAILURES" = "0" ] || fail "the archiver reported $ARCHIVE_FAILURES failures; archive-wal.sh is not working"
info "archived through $LAST_SEGMENT, $ARCHIVE_FAILURES archiver failures"
info "$(docker exec "$SOURCE" sh -c 'ls /archive/wal | wc -l') segments in the archive, all encrypted:"
docker exec "$SOURCE" sh -c 'ls /archive/wal | head -3' >&2

# The archive is unreadable without the identity — asserted rather than assumed,
# because "encrypted at rest" is the claim §17.4 makes on behalf of every
# shipping address in these files.
if docker exec "$SOURCE" sh -c 'head -c 8 "/archive/wal/$(ls /archive/wal | head -1)"' | grep -q 'PG'; then
  fail "an archived segment starts with a PostgreSQL magic number; it is not encrypted"
fi
info "spot check: archived segments do not contain plaintext WAL headers"

# --------------------------------------------------------------------------
say "destroying the source cluster"
# --------------------------------------------------------------------------
#
# Not stopped — removed, with its storage. Everything from here on comes out of
# the archive, which is the only way to find out whether the archive is enough.

docker rm -f "$SOURCE" >/dev/null
info "source cluster and its volume are gone"

# --------------------------------------------------------------------------
say "recovering onto a new cluster"
# --------------------------------------------------------------------------

RECOVERY_TARGET="$TARGET_TIME"
if [ "$NEGATIVE" = "late-target" ]; then
  # The wrong instant, deliberately. Recovery will succeed and bring back a row
  # that the operator asked not to have.
  RECOVERY_TARGET="$AFTER_TIME"
  info "negative case: recovering to $RECOVERY_TARGET, which is after the row that must not survive"
fi

docker run -d --name "$TARGET" --network "$NETWORK" \
  -v "$ARCHIVE_VOLUME:/archive" \
  -e IDEANEST_BACKUP_ARCHIVE_DIR=/archive \
  -e IDEANEST_BACKUP_ENCRYPTION=age \
  -e IDEANEST_BACKUP_AGE_IDENTITY_FILE=/keys/identity.txt \
  -e IDEANEST_BACKUP_SOCKET_DIR=/tmp \
  -e IDEANEST_BACKUP_RECOVERY_TIMEOUT=300 \
  -e PGUSER="$DB_USER" \
  -e PGDATABASE="$DB_NAME" \
  --entrypoint sleep "$IMAGE" 3600 >/dev/null

docker exec "$TARGET" sh -c 'mkdir -p /keys /restore && chown postgres:postgres /keys /restore'
docker cp "$(hostpath "$KEYDIR/identity.txt")" "$TARGET:/keys/identity.txt"
docker exec "$TARGET" sh -c 'chown postgres:postgres /keys/identity.txt && chmod 0600 /keys/identity.txt'

RESTORE_STARTED="$(date -u +%s)"
docker exec -u postgres "$TARGET" /opt/ideanest/restore.sh \
  --backup "$BACKUP_PATH" \
  --pgdata /restore/data \
  --target-time "$RECOVERY_TARGET" \
  || { docker exec "$TARGET" tail -40 /restore/data/recovery.log >&2 || true; fail "restore.sh failed"; }
RESTORE_SECONDS=$(( $(date -u +%s) - RESTORE_STARTED ))
info "recovery took ${RESTORE_SECONDS}s"

docker exec "$TARGET" sh -c 'grep -E "starting point-in-time recovery|restored log file|recovery stopping|last completed transaction|database system is ready" /restore/data/recovery.log | tail -8' >&2 || true

# --------------------------------------------------------------------------
if [ "$NEGATIVE" = "schema-history" ]; then
  say "negative case: corrupting the restored schema history"
  docker exec -u postgres "$TARGET" psql -qAtX -h /tmp -d "$DB_NAME" \
    -c "DELETE FROM flyway_schema_history WHERE version = '$EXPECT_VERSION'" >&2
fi

LEDGER_MODE="absent-ok"
if [ "$NEGATIVE" = "require-ledger" ]; then
  say "negative case: demanding the ledger tables"
  LEDGER_MODE="required"
fi

# --------------------------------------------------------------------------
say "verifying the restore"
# --------------------------------------------------------------------------

set +e
docker exec -u postgres -e PGDATABASE="$DB_NAME" "$TARGET" /opt/ideanest/verify-restore.sh \
  --host /tmp \
  --port 5432 \
  --dbname "$DB_NAME" \
  --expect-version "$EXPECT_VERSION" \
  --expect-migrations "$EXPECT_MIGRATIONS" \
  --expect-relations "$EXPECT_RELATIONS" \
  --witness-before "$WITNESS_BEFORE" \
  --witness-after "$WITNESS_AFTER" \
  --atomic-witness "$ATOMIC_WITNESS" \
  --target-time "$TARGET_TIME" \
  --ledger-mode "$LEDGER_MODE" \
  --report /restore/report.txt
VERIFY_STATUS=$?
set -e

if [ -n "$REPORT_OUT" ]; then
  mkdir -p "$(dirname "$REPORT_OUT")"
  docker cp "$TARGET:/restore/report.txt" "$(hostpath "$REPORT_OUT")" >/dev/null 2>&1 || true
  {
    printf 'drill_run_id=%s\n' "$RUN_ID"
    printf 'negative_case=%s\n' "${NEGATIVE:-none}"
    printf 'recovery_seconds=%s\n' "$RESTORE_SECONDS"
    printf 'base_backup=%s\n' "$BACKUP_PATH"
    printf 'recovery_target_time=%s\n' "$RECOVERY_TARGET"
  } >>"$REPORT_OUT"
  info "report at $REPORT_OUT"
fi

# --------------------------------------------------------------------------

if [ -z "$NEGATIVE" ]; then
  [ "$VERIFY_STATUS" -eq 0 ] || fail "the rehearsal did not verify (exit $VERIFY_STATUS)"
  say "DRILL PASSED — recovered to $TARGET_TIME in ${RESTORE_SECONDS}s and verified"
else
  if [ "$VERIFY_STATUS" -eq 0 ]; then
    fail "negative case '$NEGATIVE' verified clean; the verification does not detect it"
  fi
  say "NEGATIVE CASE '$NEGATIVE' CORRECTLY FAILED VERIFICATION (exit $VERIFY_STATUS)"
fi
