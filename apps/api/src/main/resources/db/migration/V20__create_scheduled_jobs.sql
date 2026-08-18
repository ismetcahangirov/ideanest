-- §8.4's durable scheduler (#134): every replica keeps a timer, and exactly one
-- of them does the work.
--
-- ---------------------------------------------------------------------------
-- WHAT THIS TABLE IS FOR
-- ---------------------------------------------------------------------------
--
-- Until now every job in §8.4 ran on Spring's in-process `@Scheduled`. That is
-- one timer per replica with no record of what it did and no retry of the tick
-- itself, and the four jobs already built are safe on it only because each of
-- them claims its own rows -- a conditional update on a pledge, on a reminder,
-- on an account, or `FOR UPDATE SKIP LOCKED` on an outbox row. The sweep being
-- run three times was survivable because the *work* could not be.
--
-- That is not a property the remaining twelve jobs have. `campaign-finalizer`
-- decides whether a campaign succeeded, `charge-processor` moves money, and
-- neither is something to run once per replica and hope. This table is where a
-- scheduled trigger stops meaning "this process will do it" and starts meaning
-- "one process will do it".
--
-- What it holds, per job:
--
--   * **Who is running it now, and until when.** `lock_holder` with
--     `lock_expires_at` is a lease, and the lease is the lock.
--   * **How many consecutive attempts have failed, and when the next one may
--     be.** The same bounded, exponentially backed off, dead-lettering policy
--     `outbox_events` already carries, expressed in the same columns, because
--     an operator should not have to learn a second vocabulary at three in the
--     morning.
--   * **When it last started.** An old `last_run_at` with a live lease is the
--     shape of a hung job, and there is nowhere else to read that from.
--
-- ---------------------------------------------------------------------------
-- WHY A LEASE IN A ROW, AND NOT AN ADVISORY LOCK
-- ---------------------------------------------------------------------------
--
-- PostgreSQL offers `pg_advisory_lock`, and it was rejected for three reasons
-- rather than one.
--
--   * **Its lifetime is a session's, not a run's.** The application holds a
--     Hikari pool, so the session is a pooled connection that outlives the job
--     by design: a run that returns the connection without unlocking leaves the
--     lock held until `max-lifetime` retires the connection twenty minutes
--     later, and a run that unlocks on a connection it did not lock on fails
--     silently. Tying a lock to a resource the application deliberately reuses
--     is a bug waiting for the first `finally` somebody forgets.
--   * **Its key is a bigint.** Sixteen job names would have to be hashed into
--     one, and two jobs colliding would not fail -- they would take turns, and
--     nothing anywhere would say so.
--   * **It records nothing.** No `attempts`, no `next_attempt_at`, no
--     `last_error`, and no answer to "which replica is running the finalizer
--     right now". This table has to exist for the retry policy in any case, so
--     the advisory lock would be a second mechanism holding half the state.
--
-- **What happens when the holder dies mid-run** is the question a lease has to
-- answer, and it is the reason it is a lease rather than a flag. A boolean
-- `running` column would be correct until the first process killed between
-- setting it and clearing it, after which the job never runs again and the only
-- symptom is an absence -- reservations not released, notices not sent. The
-- expiry is what makes the recovery automatic: another replica takes the job
-- once `lock_expires_at` has passed, and the cost of the crash is bounded by
-- `ideanest.jobs.lock-lease` rather than by how long it takes somebody to
-- notice.
--
-- The other direction has a cost too, and it is the reason the lease is
-- configurable. A lease shorter than a run means a second replica starts the
-- same job while the first is still in it. **That is a lease that is too short,
-- not a correctness failure**, and the distinction is worth being precise
-- about: this lock is what stops redundant work and what stops sixteen timers
-- becoming sixteen concurrent sweeps. What stops a pledge being expired twice
-- or a notice being sent twice is the row-level claim inside each job, and that
-- has not moved. A lease can be missed under a long stop-the-world pause on any
-- system that does not carry a fencing token into the write, so the jobs are
-- built not to need one.
--
-- ---------------------------------------------------------------------------
-- ONE ROW PER JOB, NOT PER EXECUTION
-- ---------------------------------------------------------------------------
--
-- This is the scheduler's state, not a history. `outbox-relay` runs every
-- second: a row per execution would be 86,400 rows a day for one job, to record
-- that nothing happened 86,000 times. What ran and what it did is in the log
-- stream, correlated per run by `CorrelationTaskDecorator`, and what an
-- operator needs from the database is the current answer -- is it stuck, is it
-- backing off, has it given up.
--
-- The rows are written by the application at start-up
-- (`INSERT ... ON CONFLICT DO NOTHING`), not seeded here. A new job is then a
-- class and not a migration, and a rolling deploy in which only half the
-- replicas know about a job still ends with exactly one row for it.
--
-- ---------------------------------------------------------------------------
-- Rolling deployment
-- ---------------------------------------------------------------------------
--
-- One new table and nothing else touched. No column is dropped, no constraint
-- is added to an existing table, and the previous release does not know this
-- table exists -- it keeps its own in-process timers, which stay safe on their
-- own row-level claims while both versions are running. Both halves of a
-- rolling deploy are safe in either order, and a mixed fleet means some
-- replicas take the lease and some do not, which is the state this table exists
-- to make harmless. This is an EXPAND with no contract half.
--
-- Reverse:
--   DROP TABLE IF EXISTS scheduled_jobs;
--
--   Cheap, and worth saying why: nothing here is a record of something that
--   happened. Dropping it loses the attempt counters and the lease, and the
--   previous build reverts to firing every job on every replica -- which is
--   what it did before this release and which its own claims already survive.
--   Do it with the new build stopped, so that nothing is holding a lease
--   against a table that is about to disappear.
--
-- ---------------------------------------------------------------------------
-- Recovering a job that gave up
-- ---------------------------------------------------------------------------
--
--   UPDATE scheduled_jobs SET state = 'READY', attempts = 0 WHERE name = '...';
--
--   A DEAD row is deliberate and deliberately manual: the job has failed its
--   configured number of consecutive attempts, each one further apart than the
--   last, which means it is waiting for a person rather than for the network.
--   Resetting it without reading `last_error` first only starts the same clock
--   again.

CREATE TABLE scheduled_jobs (
    -- **§8.4's name for the job, verbatim**: `outbox-relay`, `reservation-cleaner`.
    -- The primary key, because the job's identity is what the lock is taken on,
    -- and a surrogate key would leave the real identity in a column that two rows
    -- could share -- which is two schedulers each believing they hold the lock.
    name            text        PRIMARY KEY,

    -- READY -> DEAD, and no way back except by hand.
    --
    -- **Running is deliberately not a state.** A job that is running is a READY
    -- row whose lease is held, because "who has it now" and "is this job still
    -- being attempted" are different questions: a RUNNING state left behind by a
    -- process that was killed is indistinguishable from a job that is genuinely
    -- running, and there is no timeout on a word. The lease columns answer the
    -- first question and expire on their own; this one answers the second.
    state           text        NOT NULL DEFAULT 'READY',

    -- **The lease.** Which process is running this job, and the instant after
    -- which the platform stops believing it.
    --
    -- The holder is a diagnostic and not an authority -- it is written by the
    -- process that claimed the job and could say anything. What actually excludes
    -- a second runner is the conditional UPDATE that sets these two columns: the
    -- claim only matches a row whose lease is absent or elapsed, so PostgreSQL
    -- decides which caller wins and the loser is told nothing was updated. It is
    -- read when a run is released, though, so that a replica whose lease expired
    -- while it was working cannot clear the lease of whoever took the job from it.
    lock_holder     text,
    lock_expires_at timestamptz,

    -- **When the current or most recent run started**, not when it finished. An
    -- old instant here beside a live lease is a hung job, which is the one thing
    -- about a scheduler that is invisible from anywhere else; a finished-at column
    -- would say nothing while the run that matters is still going.
    last_run_at     timestamptz,

    -- Why the last failure failed, for whoever has to look at a job that has
    -- given up. Cleared by a successful run, unlike `outbox_events.last_error`,
    -- and the difference is the point: an outbox row is delivered once and its
    -- history ends there, whereas a job runs for ever, and an error left on a row
    -- that has succeeded a thousand times since is a false alarm somebody
    -- eventually learns to ignore.
    last_error      text,

    -- **Consecutive failures, reset by a success.** Not runs attempted: this is
    -- what the retry bound is measured against, and a job that fails once an hour
    -- for a year has never been failing.
    attempts        integer     NOT NULL DEFAULT 0,

    -- **The backoff, written down.** The trigger fires on its cron; this is what
    -- decides whether the tick may claim anything. Equal to the moment of
    -- registration on a new row, so a job is eligible as soon as it exists, and
    -- moved into the future by a failure.
    --
    -- Written by the application from the injected Clock and never defaulted here,
    -- for V17's, V18's and V19's reason: the retry schedule is a rule, a rule needs
    -- one home, and a test has to be able to ask what happens ten minutes later
    -- without waiting ten minutes.
    next_attempt_at timestamptz NOT NULL,

    CONSTRAINT scheduled_jobs_name_length CHECK (length(btrim(name)) BETWEEN 1 AND 64),
    CONSTRAINT scheduled_jobs_state_known CHECK (state IN ('READY', 'DEAD')),
    CONSTRAINT scheduled_jobs_attempts_is_not_negative CHECK (attempts >= 0),
    -- Half a lease is worse than none: a holder with no expiry never expires, and
    -- an expiry with no holder names nobody to refuse a release to.
    CONSTRAINT scheduled_jobs_lease_is_whole CHECK (
        (lock_holder IS NULL) = (lock_expires_at IS NULL)
    ),
    CONSTRAINT scheduled_jobs_holder_length CHECK (
        lock_holder IS NULL OR length(btrim(lock_holder)) BETWEEN 1 AND 128
    ),
    -- The whole value of giving up is that somebody can find out why. A job that
    -- stopped running for a reason nobody wrote down, after a number of attempts
    -- nobody counted, is not a decision -- it is a disappearance, and the symptom
    -- is work that silently stopped happening.
    CONSTRAINT scheduled_jobs_dead_jobs_say_why CHECK (
        state <> 'DEAD' OR (last_error IS NOT NULL AND attempts >= 1)
    )
);

-- No index beyond the primary key, deliberately. §8.4 names sixteen jobs, so
-- this table has sixteen rows at its largest, and every read of it is a claim or
-- a release of one row by name. An index on `state` or on `next_attempt_at`
-- would describe a query nothing makes and cost a write on the busiest row in
-- the schema -- `outbox-relay`'s, updated twice a second.

COMMENT ON TABLE scheduled_jobs IS
    '§8.4''s durable scheduler (#134): one row per job, holding the lease that makes a trigger fire on exactly one replica and the retry state that bounds how often it is attempted.';
COMMENT ON COLUMN scheduled_jobs.name IS
    '§8.4''s name for the job. The identity the lock is taken on.';
COMMENT ON COLUMN scheduled_jobs.state IS
    'READY -> DEAD once the consecutive attempts are exhausted. Running is not a state; the lease says who holds the job.';
COMMENT ON COLUMN scheduled_jobs.lock_expires_at IS
    'When the platform stops believing the holder. A crashed holder costs at most this long; a run longer than this may be started twice.';
COMMENT ON COLUMN scheduled_jobs.attempts IS
    'Consecutive failures, reset by a success. What the retry bound is measured against.';
COMMENT ON COLUMN scheduled_jobs.next_attempt_at IS
    'The backoff. A trigger that fires before this claims nothing.';
