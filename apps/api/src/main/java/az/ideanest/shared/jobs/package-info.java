/**
 * §8.4's durable scheduler (#134): a job fires on every replica and runs on one.
 *
 * <p>Every job in §8.4 used to carry Spring's {@code @Scheduled}. That is a timer per
 * replica which keeps no record of what it did and retries nothing, and the four jobs
 * built so far are safe on it only because each of them claims its own rows — a
 * conditional update on a pledge, a reminder or an account, and
 * {@code FOR UPDATE SKIP LOCKED} on an outbox row. Running the sweep three times was
 * survivable because the work could not be done three times. That is not a property
 * the twelve remaining jobs have.
 *
 * <p>Three pieces, and nothing else:
 *
 * <ul>
 *   <li>{@link az.ideanest.shared.jobs.ScheduledJob} — what a module implements. A
 *       name, a cron expression, and one pass of the work. The dependency points this
 *       way so that {@code shared} never has to know about {@code pledge} or
 *       {@code project}.
 *   <li>{@link az.ideanest.shared.jobs.JobScheduler} — registers each job's row and
 *       puts its trigger on Spring's {@code TaskScheduler}. The timer was never the
 *       missing part.
 *   <li>{@link az.ideanest.shared.jobs.JobLease} and
 *       {@link az.ideanest.shared.jobs.JobRunner} — the claim, the run, and the
 *       outcome. The claim is a conditional {@code UPDATE} on
 *       {@code scheduled_jobs}: whoever writes their name and an expiry into the row
 *       runs the job, and everybody else is told there was nothing to take.
 * </ul>
 *
 * <p><strong>A lease, not a lock held open.</strong> The alternative — {@code SELECT
 * … FOR UPDATE} kept until the run finishes — makes the run's duration a
 * transaction's duration, which pins a connection and holds PostgreSQL's vacuum
 * horizon back for as long as the work takes. The lease is committed immediately and
 * expires by itself, so a replica killed mid-run costs one lease rather than an
 * outage nobody can see. V20 states the trade in full, including why an advisory lock
 * was rejected.
 *
 * <p><strong>What this does and does not guarantee.</strong> It guarantees that the
 * platform's sixteen triggers become sixteen runs rather than sixteen times the
 * number of replicas, and that a job which keeps failing backs off and eventually
 * stops with a reason attached. It does not replace the row-level claims inside the
 * jobs, and is not meant to: a lease can be outlived by a long enough pause on any
 * system that does not carry a fencing token into the write, so what makes a
 * reservation impossible to release twice is still the conditional update inside
 * {@code ReservationExpiry}.
 *
 * <p>See {@code docs/architecture.md} §8.4 for the job table and §8.3 for the outbox,
 * whose retry policy this one deliberately repeats rather than reinvents.
 */
package az.ideanest.shared.jobs;
