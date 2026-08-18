# shellcheck shell=bash
# Encryption for everything that leaves the database host.
#
# §17.4 requires backups to be encrypted. This file is the only place that
# decides how, so that "is the archive encrypted" is a question with one answer
# rather than one answer per script.
#
# **Public-key, not a shared passphrase.** The database host holds a recipient's
# *public* key and nothing else, so a host that is compromised can add to the
# archive and cannot read it. The private identity lives in the secret store and
# is fetched only by the person performing a restore, which is the moment the
# access control in the runbook applies to.
#
# Two backends, because the tool is not the point:
#
#   age  — the default. One binary, one key format, no keyring state, and a
#          recipient is a single line that can sit in configuration.
#   gpg  — for a deployment that already has a key hierarchy and an HSM behind
#          it, and does not want a second one.
#
# There is deliberately no third backend for "none". A backup written in the
# clear is a copy of every shipping address and every payment reference on the
# platform, and the failure mode of an accidental fallback is that nobody
# notices until it is already in object storage.

# Resolves the backend once, refusing anything unknown rather than guessing.
crypt_backend() {
  _backend="${IDEANEST_BACKUP_ENCRYPTION:-age}"
  case "$_backend" in
    age|gpg) printf '%s' "$_backend" ;;
    *) die "IDEANEST_BACKUP_ENCRYPTION must be 'age' or 'gpg', got: $_backend" ;;
  esac
}

crypt_require() {
  case "$(crypt_backend)" in
    age)
      require_cmd age
      require_env IDEANEST_BACKUP_AGE_RECIPIENT
      ;;
    gpg)
      require_cmd gpg
      require_env IDEANEST_BACKUP_GPG_RECIPIENT
      ;;
  esac
}

crypt_require_identity() {
  case "$(crypt_backend)" in
    age)
      require_cmd age
      require_env IDEANEST_BACKUP_AGE_IDENTITY_FILE
      [ -r "$IDEANEST_BACKUP_AGE_IDENTITY_FILE" ] \
        || die "age identity file is not readable: $IDEANEST_BACKUP_AGE_IDENTITY_FILE"
      ;;
    gpg)
      require_cmd gpg
      ;;
  esac
}

# stdin -> encrypted file. The file is written to a temporary name and renamed,
# so a reader can never observe a half-written object: rename within a
# filesystem is atomic, and an object store's PUT is atomic by contract.
crypt_encrypt_stdin_to() {
  _dest="$1"
  _tmp="${_dest}.partial.$$"
  case "$(crypt_backend)" in
    age) age --recipient "$IDEANEST_BACKUP_AGE_RECIPIENT" --output "$_tmp" ;;
    gpg) gpg --batch --yes --trust-model always \
             --recipient "$IDEANEST_BACKUP_GPG_RECIPIENT" \
             --encrypt --output "$_tmp" ;;
  esac
  sync
  mv "$_tmp" "$_dest"
}

# encrypted file -> stdout.
crypt_decrypt_to_stdout() {
  _src="$1"
  case "$(crypt_backend)" in
    age) age --decrypt --identity "$IDEANEST_BACKUP_AGE_IDENTITY_FILE" "$_src" ;;
    gpg) gpg --batch --quiet --decrypt "$_src" ;;
  esac
}

# The suffix an encrypted object carries. Part of the contract between
# archive-wal.sh and restore-wal.sh, which are the two scripts that have to
# agree on a filename without ever talking to each other.
crypt_suffix() {
  case "$(crypt_backend)" in
    age) printf '.age' ;;
    gpg) printf '.gpg' ;;
  esac
}
