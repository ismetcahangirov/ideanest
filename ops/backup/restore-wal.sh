#!/bin/sh
#
# PostgreSQL `restore_command`. One segment name in, one decrypted segment
# written where the recovering server asked for it.
#
#   restore_command = '/opt/ideanest/restore-wal.sh %f %p'
#
# The mirror of archive-wal.sh, and POSIX sh for the same reason: it runs inside
# a server that is in the middle of recovering, which is the worst possible
# moment to discover a missing interpreter.
#
# **A missing segment is not an error.** Recovery ends by asking for a segment
# that was never written, and PostgreSQL reads a non-zero exit as "the archive
# does not have that one" and moves on. So this script must exit non-zero
# quietly for absence and loudly for anything else — the two must not look the
# same in the log, because "the archive is unreachable" and "recovery finished"
# would otherwise be the same line.
#
# Environment:
#
#   IDEANEST_BACKUP_WAL_DIR              where segments are
#   IDEANEST_BACKUP_ENCRYPTION           age (default) or gpg
#   IDEANEST_BACKUP_AGE_IDENTITY_FILE    the private identity, present only on
#                                        the host performing the restore

set -eu

SEGMENT="${1:?usage: restore-wal.sh <%f segment name> <%p destination path>}"
DEST_PATH="${2:?usage: restore-wal.sh <%f segment name> <%p destination path>}"

WAL_DIR="${IDEANEST_BACKUP_WAL_DIR:-${IDEANEST_BACKUP_ARCHIVE_DIR:-/var/lib/ideanest/archive}/wal}"
BACKEND="${IDEANEST_BACKUP_ENCRYPTION:-age}"

fail() { printf 'restore-wal: %s\n' "$*" >&2; exit 2; }

# The same vocabulary archive-wal.sh accepts: a hex segment name, a `.history`
# file, or a `.backup` label. Recovery asks for history files by name at every
# timeline switch, so refusing them would strand a restore that has to follow one.
case "$SEGMENT" in
  */* | '' ) fail "refusing an implausible segment name: $SEGMENT" ;;
esac
SEGMENT_CORE="$SEGMENT"
case "$SEGMENT" in
  *.backup)  SEGMENT_CORE="${SEGMENT%.backup}" ;;
  *.history) SEGMENT_CORE="${SEGMENT%.history}" ;;
esac
case "$SEGMENT_CORE" in
  *[!0-9A-Fa-f.]* | '' ) fail "refusing an implausible segment name: $SEGMENT" ;;
esac

case "$BACKEND" in
  age)
    command -v age >/dev/null 2>&1 || fail "age is not installed"
    [ -n "${IDEANEST_BACKUP_AGE_IDENTITY_FILE:-}" ] || fail "IDEANEST_BACKUP_AGE_IDENTITY_FILE is not set"
    SUFFIX='.age'
    ;;
  gpg)
    command -v gpg >/dev/null 2>&1 || fail "gpg is not installed"
    SUFFIX='.gpg'
    ;;
  *)
    fail "IDEANEST_BACKUP_ENCRYPTION must be 'age' or 'gpg', got: $BACKEND"
    ;;
esac

SOURCE="$WAL_DIR/$SEGMENT$SUFFIX"

# Absence, and only absence, exits 1 without a message. Everything else above
# and below exits 2 with one, so a genuine archive failure is distinguishable
# from the end of the archive in the server log.
[ -f "$SOURCE" ] || exit 1

TMP="$DEST_PATH.partial.$$"
cleanup() { rm -f "$TMP"; }
trap cleanup EXIT INT TERM

case "$BACKEND" in
  age) age --decrypt --identity "$IDEANEST_BACKUP_AGE_IDENTITY_FILE" --output "$TMP" "$SOURCE" ;;
  gpg) gpg --batch --quiet --decrypt --output "$TMP" "$SOURCE" ;;
esac

# Renamed into place so the recovering server never opens a partial segment: it
# reads the file the instant this process exits, and a truncated segment is
# indistinguishable from the end of the archive.
mv "$TMP" "$DEST_PATH"

exit 0
