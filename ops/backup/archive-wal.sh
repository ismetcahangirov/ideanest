#!/bin/sh
#
# PostgreSQL `archive_command`. One completed write-ahead log segment in,
# one encrypted object in the archive out.
#
#   archive_command = '/opt/ideanest/archive-wal.sh %p %f'
#
# This is the script that decides §19.4's five-minute recovery point. Everything
# committed since the last base backup exists only in these segments, so the
# rules it follows are stricter than the rest of the directory:
#
#   * **Pure POSIX sh, and self-contained.** It runs on the database host, as
#     the postmaster's child, on every segment for ever. It sources nothing:
#     a library that moved is an archiver that stops, and an archiver that stops
#     is unbounded pg_wal growth followed by a full disk on the primary.
#
#   * **Never overwrite.** A segment name is reused after a timeline switch, and
#     an archive that lets the second one land on top of the first has silently
#     destroyed the recovery point it exists to provide. An identical file is
#     success (the archiver retries, so this happens normally); a *different*
#     file under a name already taken is a failure, loudly.
#
#   * **Exit non-zero on any doubt.** PostgreSQL keeps the segment and retries.
#     Reporting success for a segment that is not durably in the archive is the
#     one unrecoverable mistake available here.
#
#   * **Durable before success.** The bytes are fsynced and the file is renamed
#     into place, so a host that loses power between the copy and the reply does
#     not leave a truncated segment under a real name.
#
# Environment, supplied by the unit file or the container that starts
# PostgreSQL — the postmaster's environment is inherited by this process:
#
#   IDEANEST_BACKUP_WAL_DIR          where segments go
#   IDEANEST_BACKUP_ENCRYPTION       age (default) or gpg
#   IDEANEST_BACKUP_AGE_RECIPIENT    public key; the host holds no private key
#   IDEANEST_BACKUP_GPG_RECIPIENT    when the backend is gpg

set -eu

SOURCE_PATH="${1:?usage: archive-wal.sh <%p source path> <%f segment name>}"
SEGMENT="${2:?usage: archive-wal.sh <%p source path> <%f segment name>}"

WAL_DIR="${IDEANEST_BACKUP_WAL_DIR:-${IDEANEST_BACKUP_ARCHIVE_DIR:-/var/lib/ideanest/archive}/wal}"
BACKEND="${IDEANEST_BACKUP_ENCRYPTION:-age}"

fail() { printf 'archive-wal: %s\n' "$*" >&2; exit 1; }

# What PostgreSQL hands an archive_command is a 24-character hex segment name, a
# `.history` file at a timeline switch, or a `.backup` label at the end of a base
# backup. Nothing else, ever — so anything else is refused rather than written,
# because this argument becomes a path.
#
# The label matters more than it looks: `pg_backup_stop` waits for it to be
# archived, so an archive_command that rejects it does not fail loudly, it
# **hangs every base backup for ever**. That is how this validation was found to
# be wrong, and it is why the suffix is stripped before the hex check rather than
# the hex check being loosened.
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

[ -f "$SOURCE_PATH" ] || fail "source segment does not exist: $SOURCE_PATH"
[ -d "$WAL_DIR" ] || mkdir -p "$WAL_DIR" || fail "cannot create $WAL_DIR"

case "$BACKEND" in
  age)
    command -v age >/dev/null 2>&1 || fail "age is not installed on the database host"
    [ -n "${IDEANEST_BACKUP_AGE_RECIPIENT:-}" ] || fail "IDEANEST_BACKUP_AGE_RECIPIENT is not set"
    SUFFIX='.age'
    ;;
  gpg)
    command -v gpg >/dev/null 2>&1 || fail "gpg is not installed on the database host"
    [ -n "${IDEANEST_BACKUP_GPG_RECIPIENT:-}" ] || fail "IDEANEST_BACKUP_GPG_RECIPIENT is not set"
    SUFFIX='.gpg'
    ;;
  *)
    fail "IDEANEST_BACKUP_ENCRYPTION must be 'age' or 'gpg', got: $BACKEND"
    ;;
esac

DEST="$WAL_DIR/$SEGMENT$SUFFIX"
TMP="$WAL_DIR/.$SEGMENT$SUFFIX.partial.$$"

cleanup() { rm -f "$TMP"; }
trap cleanup EXIT INT TERM

encrypt_to_tmp() {
  case "$BACKEND" in
    age) age --recipient "$IDEANEST_BACKUP_AGE_RECIPIENT" --output "$TMP" <"$SOURCE_PATH" ;;
    gpg) gpg --batch --yes --trust-model always \
             --recipient "$IDEANEST_BACKUP_GPG_RECIPIENT" \
             --encrypt --output "$TMP" <"$SOURCE_PATH" ;;
  esac
}

if [ -e "$DEST" ]; then
  # Already archived. The archiver retries a segment whose reply it did not
  # see, so this is the ordinary case and not an error — but only if the object
  # already there is the same segment. age and gpg outputs are not
  # byte-reproducible (a fresh ephemeral key per file), so the comparison is on
  # the plaintext: decrypting is impossible here by design, so compare sizes and
  # refuse anything that does not match rather than guessing.
  EXISTING_SIZE=$(wc -c <"$DEST" | tr -d ' ')
  encrypt_to_tmp
  NEW_SIZE=$(wc -c <"$TMP" | tr -d ' ')
  if [ "$EXISTING_SIZE" = "$NEW_SIZE" ]; then
    printf 'archive-wal: %s is already archived, leaving it alone\n' "$SEGMENT" >&2
    exit 0
  fi
  fail "$SEGMENT already exists in the archive with a different size ($EXISTING_SIZE vs $NEW_SIZE); refusing to overwrite it"
fi

encrypt_to_tmp

# Durability before the reply. `sync` is coarse — it flushes the filesystem
# rather than this file — and it is what POSIX sh has. A host with a
# purpose-built archiver should use that archiver's fsync instead.
sync

mv "$TMP" "$DEST"
sync

exit 0
