/**
 * The record of every privileged action — AD-14, #107.
 *
 * <p>CLAUDE.md §3 requires it of the backend in one line: "every privileged action
 * is audited". This package is where that line is kept, and
 * {@link az.ideanest.audit.AuditLog} is the whole of the writing side.
 *
 * <p><strong>Flat, like {@code shared.outbox} and unlike a feature module.</strong>
 * Auditing is cross-cutting by definition — {@code ModuleBoundaryTests} names it
 * among the things everything may depend on — and splitting it into
 * {@code domain}, {@code application} and {@code infrastructure} would mean every
 * caller importing from a package the module boundary calls internal, or the
 * vocabulary being duplicated on both sides of the line. What callers touch is
 * {@link az.ideanest.audit.AuditLog}, {@link az.ideanest.audit.AuditAction},
 * {@link az.ideanest.audit.AuditActor} and
 * {@link az.ideanest.audit.AuditOutcome}, and nothing else here is meant for them.
 *
 * <h2>Three decisions worth reading before calling any of it</h2>
 *
 * <p><strong>The record is written by the transaction that made the change.</strong>
 * {@link az.ideanest.audit.AuditLog#record} is {@code MANDATORY}, so the row and
 * the change commit together or neither does. "The action succeeded and nobody
 * recorded it" is the failure this issue exists to prevent, and the only way to
 * remove it is to stop having two commits. The second entry point,
 * {@link az.ideanest.audit.AuditLog#recordIndependently}, exists for the two cases
 * where there is nothing to be atomic with — a refusal about to roll back, and an
 * action that is not a database write — and it says what it costs.
 *
 * <p><strong>The database refuses to change a row.</strong> Not the application:
 * {@code V21} puts a trigger on {@code audit_logs} that raises on UPDATE, DELETE
 * and TRUNCATE, so the guarantee survives a support session, a migration, and a
 * bug. Rows leave a month at a time by detaching a partition, which V21's header
 * explains in full.
 *
 * <p><strong>What arrives here is redacted first.</strong> §17.4's
 * {@link az.ideanest.shared.observability.Redaction} is applied to the free text
 * and to the user agent on the way in. An audit trail is not a licence to retain
 * personal data — a table nothing may prune row by row is the worst possible place
 * to keep an address by accident.
 */
package az.ideanest.audit;
