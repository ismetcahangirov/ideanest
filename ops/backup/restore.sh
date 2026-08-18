#!/usr/bin/env bash
#
# Restore a base backup and replay the write-ahead log to a chosen instant.
#
# This is the point-in-time recovery itself. It runs on the machine that will
# hold the recovered cluster, as the operating-system user PostgreSQL runs as,
# and it produces a *promoted* cluster on a new timeline — never a copy that is
# still following the archive.
#
# Three decisions worth knowing before it is run at 03:00:
#
#   * **`archive_mode` is forced off in the recovered copy.** It inherits the
#     primary's configuration file, and a recovered cluster left archiving would
#     write its own timeline's segments into the archive the primary is still
#     using. That is not a slow leak; it is the recovery point of the *live*
#     system being corrupted by the attempt to recover from it.
#
#   * **`recovery_target_action = promote`.** Recovery stops at the target and
#     the cluster comes up read-write on a new timeline. A paused cluster looks
#     identical to a working one to anything that only checks that the port
#     answers, which is exactly the mistake §19.4's rehearsal exists to catch.
#
#   * **The manifest is verified before a single segment is replayed.** An hour
#     of replay onto a truncated base backup ends with a failure that says
#     nothing about the cause.
#
# Usage:
#   restore.sh --backup <archive dir> --pgdata <empty dir> \
#              [--target-time '2026-08-18 12:34:56+00'] [--port 5432]
#
# With no --target-time, recovery replays everything the archive has, which is
# the shape of a recovery from hardware loss. With one, it stops there, which is
# the shape of a recovery from a bad migration or a mistaken DELETE.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
. "$SCRIPT_DIR/lib/common.sh"
# shellcheck source=lib/crypt.sh
. "$SCRIPT_DIR/lib/crypt.sh"

BACKUP_DIR=""
PGDATA_DIR=""
TARGET_TIME=""
PORT="${PGPORT:-5432}"
START_TIMEOUT="${IDEANEST_BACKUP_RECOVERY_TIMEOUT:-900}"
SOCKET_DIR="${IDEANEST_BACKUP_SOCKET_DIR:-/tmp}"
# Only used to ask the recovering cluster whether it has finished. libpq's own
# defaults are the operating-system user and a database of the same name, which
# is right on a workstation and wrong on a cluster whose superuser is `ideanest`.
DBNAME="${PGDATABASE:-postgres}"
DBUSER="${PGUSER:-postgres}"

while [ $# -gt 0 ]; do
  case "$1" in
    --backup)      BACKUP_DIR="${2:?}"; shift 2 ;;
    --pgdata)      PGDATA_DIR="${2:?}"; shift 2 ;;
    --target-time) TARGET_TIME="${2:?}"; shift 2 ;;
    --port)        PORT="${2:?}"; shift 2 ;;
    -h|--help)     sed -n '2,35p' "$0"; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

[ -n "$BACKUP_DIR" ] || die "--backup is required"
[ -n "$PGDATA_DIR" ] || die "--pgdata is required"
[ -d "$BACKUP_DIR" ] || die "no such backup: $BACKUP_DIR"
[ -f "$BACKUP_DIR/meta" ] || die "$BACKUP_DIR has no meta file; it is not a finished backup"

require_cmd pg_ctl postgres psql tar sha256sum
crypt_require_identity

SUFFIX="$(crypt_suffix)"

# --------------------------------------------------------------------------
# 1. The archive object is intact
# --------------------------------------------------------------------------

log "verifying the checksums of $BACKUP_DIR"
( cd "$BACKUP_DIR" && sha256sum -c SHA256SUMS >/dev/null ) \
  || die "the archived objects do not match SHA256SUMS; this backup is damaged"

# --------------------------------------------------------------------------
# 2. Unpack
# --------------------------------------------------------------------------

if [ -e "$PGDATA_DIR" ]; then
  # Refused rather than emptied. A restore aimed at a live data directory by a
  # copy-paste at 03:00 must not be the thing that destroys the last copy.
  [ -z "$(ls -A "$PGDATA_DIR" 2>/dev/null)" ] || die "$PGDATA_DIR is not empty; refusing to restore over it"
else
  mkdir -p "$PGDATA_DIR"
fi
chmod 0700 "$PGDATA_DIR"

log "decrypting and extracting the base backup into $PGDATA_DIR"
crypt_decrypt_to_stdout "$BACKUP_DIR/base.tar$SUFFIX" | tar -x -C "$PGDATA_DIR" -f -

if command -v pg_verifybackup >/dev/null 2>&1; then
  # --no-parse-wal because the write-ahead log is not here yet: this checks that
  # every file the backup claimed to contain is present with the size and
  # checksum it was taken with, which is the half that can be checked now.
  if pg_verifybackup --no-parse-wal "$PGDATA_DIR" >/dev/null 2>&1; then
    log "pg_verifybackup: the extracted backup matches its manifest"
  else
    die "pg_verifybackup rejected the extracted backup; it is damaged or incomplete"
  fi
else
  warn "pg_verifybackup is not installed; the manifest was not checked"
fi

# --------------------------------------------------------------------------
# 3. Recovery configuration
# --------------------------------------------------------------------------

RESTORE_COMMAND="$SCRIPT_DIR/restore-wal.sh %f %p"

{
  printf '\n# --- written by ops/backup/restore.sh at %s ---\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  printf "restore_command = '%s'\n" "$RESTORE_COMMAND"
  # Never archive from a recovered copy. See the header.
  printf "archive_mode = 'off'\n"
  printf "archive_command = ''\n"
  # A recovered copy must not be reachable from anywhere until somebody has
  # decided it is the truth.
  printf "listen_addresses = 'localhost'\n"
  printf "port = %s\n" "$PORT"
  # Stated rather than inherited: the socket directory compiled into a
  # distribution package is not the one compiled into a container image, and a
  # verification that cannot find the socket looks exactly like a failed restore.
  printf "unix_socket_directories = '%s'\n" "$SOCKET_DIR"
  if [ -n "$TARGET_TIME" ]; then
    printf "recovery_target_time = '%s'\n" "$TARGET_TIME"
    # Inclusive, which is PostgreSQL's default and is stated rather than
    # inherited: the difference between it and `off` is whether the transaction
    # committed exactly at the target survives, and that is the transaction an
    # operator is usually asking about.
    printf "recovery_target_inclusive = on\n"
  fi
  printf "recovery_target_action = 'promote'\n"
} >>"$PGDATA_DIR/postgresql.auto.conf"

touch "$PGDATA_DIR/recovery.signal"

LOGFILE="${IDEANEST_BACKUP_RECOVERY_LOG:-$PGDATA_DIR/recovery.log}"
log "starting recovery${TARGET_TIME:+ to $TARGET_TIME}; server log at $LOGFILE"

# --------------------------------------------------------------------------
# 4. Replay
# --------------------------------------------------------------------------

# `-w` waits for the server to accept connections, which happens as soon as the
# cluster is consistent — long before the target is reached. The wait that
# matters is the one below.
pg_ctl -D "$PGDATA_DIR" -l "$LOGFILE" -w -t "$START_TIMEOUT" start >/dev/null \
  || { warn "pg_ctl start failed; last 40 lines of $LOGFILE:"; tail -n 40 "$LOGFILE" >&2; exit 1; }

DEADLINE=$(( $(date -u +%s) + START_TIMEOUT ))
while :; do
  # Not `-d postgres`: the `postgres` role and the `postgres` database are
  # conveniences of a default installation, and a cluster whose superuser is
  # named after the application has neither. A poll that cannot connect looks
  # exactly like a recovery that never finishes, which is fifteen minutes lost
  # to a database that was ready the whole time.
  IN_RECOVERY="$(psql -qAtX -h "$SOCKET_DIR" -p "$PORT" -d "$DBNAME" -U "$DBUSER" \
                   -c 'SELECT pg_is_in_recovery()' 2>/dev/null || echo 'unknown')"
  case "$IN_RECOVERY" in
    f) break ;;
    t) : ;;
    *) : ;;
  esac
  if [ "$(date -u +%s)" -ge "$DEADLINE" ]; then
    warn "the cluster is still in recovery after ${START_TIMEOUT}s; last 40 lines of $LOGFILE:"
    tail -n 40 "$LOGFILE" >&2
    die "recovery did not reach its target in time"
  fi
  sleep 1
done

log "recovery complete and the cluster is promoted on port $PORT"
