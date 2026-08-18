# shellcheck shell=bash
# Shared helpers for the backup and recovery scripts.
#
# Sourced, never executed. Every caller sets `set -euo pipefail` itself, so that
# a script remains readable on its own and does not inherit its strictness from
# somewhere else.
#
# Nothing here reads a secret. Credentials reach these scripts the way libpq
# already expects them — PGHOST, PGUSER, PGPASSWORD, or a .pgpass file supplied
# by the secret store — and no value is ever echoed.

# --------------------------------------------------------------------------
# Output
# --------------------------------------------------------------------------
#
# Everything diagnostic goes to stderr. stdout belongs to the one value a script
# was asked to produce, so that `BACKUP=$(base-backup.sh ...)` works and a drill
# can capture it.

log()  { printf '%s  %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$*" >&2; }
warn() { printf '%s  WARN  %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$*" >&2; }
die()  { printf '%s  FATAL %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$*" >&2; exit 1; }

# --------------------------------------------------------------------------
# Preconditions
# --------------------------------------------------------------------------

require_cmd() {
  for _cmd in "$@"; do
    command -v "$_cmd" >/dev/null 2>&1 || die "required command not found: $_cmd"
  done
}

# Refuses an empty or unset variable by name, without printing its value: these
# are called on things like PGPASSWORD.
require_env() {
  for _name in "$@"; do
    eval "_value=\${$_name:-}"
    [ -n "$_value" ] || die "required environment variable is not set: $_name"
  done
}

# --------------------------------------------------------------------------
# Paths
# --------------------------------------------------------------------------
#
# One archive root with two children. They are separate variables because in a
# deployed environment they are separate buckets with different lifecycle rules:
# a WAL segment is worthless once the base backup that precedes it has aged out,
# and a base backup is worthless without the segments that follow it.

backup_archive_root() { printf '%s' "${IDEANEST_BACKUP_ARCHIVE_DIR:-/var/lib/ideanest/archive}"; }
backup_wal_dir()      { printf '%s' "${IDEANEST_BACKUP_WAL_DIR:-$(backup_archive_root)/wal}"; }
backup_base_dir()     { printf '%s' "${IDEANEST_BACKUP_BASE_DIR:-$(backup_archive_root)/base}"; }
