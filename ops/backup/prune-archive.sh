#!/usr/bin/env bash
#
# Apply the retention policy to the archive.
#
# Retention here is not housekeeping. §17.4 says backups hold personal data and
# payment metadata, and a copy of a shipping address kept past the period the
# platform can justify is the same disclosure as never having deleted it —
# §17.4's anonymisation reaches the live database and nothing else. So the
# archive has to age out on its own, and it has to do it in an order that never
# leaves a base backup without the write-ahead log that follows it.
#
# The rule, in order:
#
#   1. A base backup is deleted when it is older than the retention period.
#   2. Except that the newest N are always kept, whatever their age. A cluster
#      whose backup job has been broken for a month must not have its last good
#      backup deleted by the cleaner; "no backups at all" is a worse state than
#      "backups older than policy", and only one of the two is recoverable.
#   3. A write-ahead log segment is deleted only when it precedes the start
#      segment of the *oldest surviving* base backup, on the same timeline.
#      Timeline history files are never deleted — they are bytes each, and
#      without them a restore cannot follow a timeline switch at all.
#
# Nothing is deleted without --apply. A retention script that defaults to
# deleting is a retention script that runs once with the wrong archive path.
#
# Usage:
#   prune-archive.sh [--retention-days N] [--keep-at-least N] [--apply]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
. "$SCRIPT_DIR/lib/common.sh"

RETENTION_DAYS="${IDEANEST_BACKUP_RETENTION_DAYS:-35}"
KEEP_AT_LEAST="${IDEANEST_BACKUP_MIN_BASE_BACKUPS:-2}"
APPLY=0

while [ $# -gt 0 ]; do
  case "$1" in
    --retention-days) RETENTION_DAYS="${2:?}"; shift 2 ;;
    --keep-at-least)  KEEP_AT_LEAST="${2:?}"; shift 2 ;;
    --apply)          APPLY=1; shift ;;
    -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

BASE_DIR="$(backup_base_dir)"
WAL_DIR="$(backup_wal_dir)"
[ -d "$BASE_DIR" ] || die "no base backup directory at $BASE_DIR"

# Oldest first, by name: `base-YYYYmmddTHHMMSSZ` sorts chronologically, which is
# the reason for that shape.
# `sed` rather than `find -printf`, which is GNU-only: this runs on the backup
# host and inside the drill's Alpine image, and busybox has no -printf.
mapfile -t BACKUPS < <(find "$BASE_DIR" -mindepth 1 -maxdepth 1 -type d -name 'base-*' | sed 's|^.*/||' | sort)
TOTAL=${#BACKUPS[@]}
[ "$TOTAL" -gt 0 ] || die "no base backups found in $BASE_DIR"

CUTOFF_EPOCH=$(( $(date -u +%s) - RETENTION_DAYS * 86400 ))

DELETE=()
SURVIVORS=()
INDEX=0
for NAME in "${BACKUPS[@]}"; do
  INDEX=$(( INDEX + 1 ))
  REMAINING=$(( TOTAL - INDEX + 1 ))
  MODIFIED=$(stat -c %Y "$BASE_DIR/$NAME")
  if [ "$MODIFIED" -lt "$CUTOFF_EPOCH" ] && [ "$REMAINING" -gt "$KEEP_AT_LEAST" ]; then
    DELETE+=("$NAME")
  else
    SURVIVORS+=("$NAME")
  fi
done

[ "${#SURVIVORS[@]}" -gt 0 ] || die "the policy would delete every base backup; refusing"

OLDEST_SURVIVOR="${SURVIVORS[0]}"
# shellcheck disable=SC2002
START_SEGMENT="$(sed -n 's/^start_segment=//p' "$BASE_DIR/$OLDEST_SURVIVOR/meta")"
[ -n "$START_SEGMENT" ] || die "$OLDEST_SURVIVOR has no start_segment in its meta file"
TIMELINE="${START_SEGMENT:0:8}"

log "retention ${RETENTION_DAYS}d, keeping at least $KEEP_AT_LEAST"
log "oldest surviving base backup: $OLDEST_SURVIVOR (starts at $START_SEGMENT)"

WAL_DELETE=()
if [ -d "$WAL_DIR" ]; then
  while IFS= read -r FILE; do
    SEGMENT="${FILE%%.*}"
    # 24 hex characters or it is not a segment: history files and anything else
    # in the directory are left alone.
    [ "${#SEGMENT}" -eq 24 ] || continue
    [ "${SEGMENT:0:8}" = "$TIMELINE" ] || continue
    [[ "$SEGMENT" < "$START_SEGMENT" ]] && WAL_DELETE+=("$FILE")
  done < <(find "$WAL_DIR" -mindepth 1 -maxdepth 1 -type f | sed 's|^.*/||' | sort)
fi

log "base backups to remove: ${#DELETE[@]}; write-ahead log segments to remove: ${#WAL_DELETE[@]}"

if [ "$APPLY" -ne 1 ]; then
  for NAME in "${DELETE[@]:-}";     do [ -n "$NAME" ] && log "would remove base backup $NAME"; done
  for FILE in "${WAL_DELETE[@]:-}"; do [ -n "$FILE" ] && log "would remove segment $FILE"; done
  log "dry run; pass --apply to delete"
  exit 0
fi

# Segments first. If the process dies half way, the surviving state is a base
# backup whose tail has been trimmed only below the point anything needs — the
# other order would leave segments belonging to a backup that no longer exists,
# which is retention failing open on exactly the personal data it is here to
# remove.
for FILE in "${WAL_DELETE[@]:-}"; do
  [ -n "$FILE" ] || continue
  rm -f -- "$WAL_DIR/$FILE"
done
for NAME in "${DELETE[@]:-}"; do
  [ -n "$NAME" ] || continue
  rm -rf -- "${BASE_DIR:?}/$NAME"
  log "removed base backup $NAME"
done
sync
log "retention applied"
