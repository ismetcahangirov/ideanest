# The image both halves of the restore drill run in.
#
# It is the production database image plus the two things the archive needs, so
# that the drill exercises the same PostgreSQL binaries the platform runs
# (`postgres:16-alpine`, from apps/api/compose.yaml and §14.4) rather than a
# convenient stand-in. A rehearsal against a different major version rehearses a
# different recovery.
#
#   age       the encryption backend. It is here rather than in the application
#             image because archiving is the database host's job: `archive_command`
#             is a child of the postmaster.
#   bash      for the operator-side scripts. archive-wal.sh and restore-wal.sh,
#             the two that run unattended on the database host, are POSIX sh and
#             need none of this.
#
# Build context is ops/backup, so the scripts are baked in and the drill needs no
# bind mount — which is also what makes it behave identically on a Windows
# workstation and on the Ubuntu runner.
FROM postgres:16-alpine

RUN apk add --no-cache age bash

COPY . /opt/ideanest/

RUN chmod 0755 /opt/ideanest/*.sh \
 && mkdir -p /var/lib/ideanest/archive \
 && chown -R postgres:postgres /var/lib/ideanest
