# `ops/backup`

Point-in-time recovery for the PostgreSQL primary: the scripts that write the
archive, the script that recovers from it, and the drill that proves both.

**The procedure is in [`docs/runbooks/backup-and-recovery.md`](../../docs/runbooks/backup-and-recovery.md).**
Read that during an incident. This file is a map of the directory.

| File | Runs where | Shell |
|---|---|---|
| `archive-wal.sh` | On the primary, as `archive_command` | POSIX `sh` |
| `restore-wal.sh` | On the recovery host, as `restore_command` | POSIX `sh` |
| `base-backup.sh` | On the primary or a backup host, daily | `bash` |
| `prune-archive.sh` | On the archive host, daily | `bash` |
| `restore.sh` | On the recovery host, during a recovery | `bash` |
| `verify-restore.sh` | Against a recovered cluster, after every restore | `bash` |
| `drill/run-drill.sh` | CI and workstations, quarterly | `bash` + Docker |
| `lib/`, `sql/` | Sourced and `\i`-ed by the above | — |

The two scripts PostgreSQL invokes unattended are POSIX `sh` and source nothing.
They run on the database host, as children of the postmaster, on every segment
for ever; a library that moved is an archiver that stops, and an archiver that
stops is a full disk on the primary. Everything an operator runs by hand is
`bash`, which is what the Ubuntu runner and every deployment image already have.

## Configuration

Environment only. Nothing here reads a secret from the repository.

| Variable | Meaning |
|---|---|
| `IDEANEST_BACKUP_ARCHIVE_DIR` | Archive root; `wal/` and `base/` beneath it |
| `IDEANEST_BACKUP_WAL_DIR`, `IDEANEST_BACKUP_BASE_DIR` | Override either half |
| `IDEANEST_BACKUP_ENCRYPTION` | `age` (default) or `gpg` |
| `IDEANEST_BACKUP_AGE_RECIPIENT` | Public key. **The primary gets this and nothing else** |
| `IDEANEST_BACKUP_AGE_IDENTITY_FILE` | Private identity. Only on the host performing a restore |
| `IDEANEST_BACKUP_RETENTION_DAYS` | Default 35. See the runbook §10 for why that number |
| `IDEANEST_BACKUP_MIN_BASE_BACKUPS` | Default 2, kept whatever their age |

Connections come from libpq's own variables (`PGHOST`, `PGUSER`, `PGPASSWORD`,
`~/.pgpass`).

## The drill

```sh
./ops/backup/drill/run-drill.sh                          # the rehearsal
./ops/backup/drill/run-drill.sh --negative late-target   # a restore that must fail
```

Docker is the only requirement. It builds a cluster from
`apps/api/src/main/resources/db/migration` with Flyway, archives through
`archive-wal.sh`, takes a base backup, destroys the source cluster, and recovers
from the archive alone. `--keep` leaves the containers behind for inspection.
