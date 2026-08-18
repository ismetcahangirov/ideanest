#!/usr/bin/env bash
#
# Take a base backup and put it in the archive, encrypted.
#
# A base backup is the floor that write-ahead log replay starts from. Without a
# recent one the recovery *time* objective is unreachable even though the
# recovery *point* objective is met: every segment since the beginning of the
# cluster would have to be replayed, and §19.4 allows an hour, not a weekend.
#
# What this does, in order:
#
#   1. Records the segment the backup starts at, so retention can tell which
#      write-ahead log is still needed and which is dead weight.
#   2. Streams a tar-format base backup with `--wal-method=none`, which is
#      deliberate: including the WAL in the backup would hide a broken
#      `archive_command` until the day it mattered. If the archive cannot
#      produce the segments, this design wants to find out on a Tuesday.
#   3. Encrypts the stream on its way past. The plaintext is never written to
#      disk on the backup host.
#   4. Writes the manifest beside it, encrypted, and a plaintext metadata file
#      naming the label, the start segment, the cluster version and the
#      encryption backend — the four facts a restore needs before it can decrypt
#      anything.
#   5. Writes SHA-256 sums over the encrypted objects, which is how a restore
#      detects a truncated download before it spends an hour replaying.
#
# Usage:
#   base-backup.sh [--label <label>]
#
# Connection comes from libpq's environment (PGHOST, PGPORT, PGUSER, PGPASSWORD
# or ~/.pgpass). Nothing is read from the repository.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
. "$SCRIPT_DIR/lib/common.sh"
# shellcheck source=lib/crypt.sh
. "$SCRIPT_DIR/lib/crypt.sh"

LABEL=""
while [ $# -gt 0 ]; do
  case "$1" in
    --label) LABEL="${2:?--label needs a value}"; shift 2 ;;
    -h|--help) sed -n '2,40p' "$0"; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

require_cmd pg_basebackup psql tar sha256sum
crypt_require

[ -n "$LABEL" ] || LABEL="base-$(date -u '+%Y%m%dT%H%M%SZ')"
case "$LABEL" in
  *[!0-9A-Za-z._-]*) die "label may only contain [0-9A-Za-z._-]: $LABEL" ;;
esac

BASE_DIR="$(backup_base_dir)"
DEST="$BASE_DIR/$LABEL"
[ ! -e "$DEST" ] || die "a backup called $LABEL already exists at $DEST"

# Staged inside the archive rather than in /tmp, so the final `mv` is a rename
# within one filesystem and therefore atomic. A cross-filesystem `mv` is a copy,
# and a copy can be observed half-done by anything that lists the directory.
mkdir -p "$BASE_DIR"
STAGING="$BASE_DIR/.staging-$LABEL.$$"
mkdir "$STAGING"
cleanup() { rm -rf "$STAGING"; }
trap cleanup EXIT INT TERM

log "starting base backup $LABEL"

# The segment the backup begins at. Read before the backup starts, so it is
# never later than the true start: retention keeps one segment too many rather
# than one too few, and one too few is a backup that cannot be restored.
START_SEGMENT="$(psql -qAtX -c "SELECT pg_walfile_name(pg_current_wal_lsn())")"
PG_VERSION="$(psql -qAtX -c "SHOW server_version_num")"
log "start segment $START_SEGMENT, server_version_num $PG_VERSION"

# `-D -` streams the tar to stdout, so the plaintext of the cluster never
# touches the backup host's disk. `--wal-method=none` is what makes the
# archive's health part of this test rather than an assumption.
pg_basebackup \
  --pgdata=- \
  --format=tar \
  --wal-method=none \
  --checkpoint=fast \
  --label="ideanest:$LABEL" \
  --no-password \
  | crypt_encrypt_stdin_to "$STAGING/base.tar$(crypt_suffix)"

# No separate copy of `backup_manifest` is kept beside the tar, and the first
# draft of this script kept one. It could not: pulling the manifest back out
# would mean decrypting, and **this host holds no private key** — which is the
# property that makes a compromised database host unable to read its own
# archive. The manifest is inside base.tar where pg_basebackup put it, and
# restore.sh runs `pg_verifybackup` against it after extraction. What guards the
# archived object itself before then is SHA256SUMS, below.

cat >"$STAGING/meta" <<META
label=$LABEL
started_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
start_segment=$START_SEGMENT
server_version_num=$PG_VERSION
encryption=$(crypt_backend)
suffix=$(crypt_suffix)
META

( cd "$STAGING" && sha256sum -- *"$(crypt_suffix)" >SHA256SUMS )

# Assembled in staging and moved in one operation, so a reader never finds a
# backup directory that exists but is not finished. `prune-archive.sh` and every
# restore treat the presence of the directory as the claim that it is complete.
mv "$STAGING" "$DEST"
trap - EXIT INT TERM
sync

log "base backup $LABEL complete: $(du -sh "$DEST" | cut -f1) at $DEST"
printf '%s\n' "$DEST"
