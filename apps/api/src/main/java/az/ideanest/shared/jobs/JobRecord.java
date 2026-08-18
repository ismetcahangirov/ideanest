package az.ideanest.shared.jobs;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * One job's place in the schedule: who is running it, and how it is getting on.
 *
 * <p><strong>The row is the lock.</strong> A trigger fires on every replica, and the
 * one that manages to write its name and an expiry into this row is the one that
 * runs the work — see {@link JobRecordRepository#claim}, which is where that is
 * actually decided, because a claim written as a read followed by an update cannot
 * be made correct.
 *
 * <p>Everything else here is the retry policy, and it is deliberately the same
 * policy {@code OutboxEvent} carries: consecutive attempts counted, the next one
 * pushed out exponentially, and a terminal state when they run out. An operator
 * looking at a stuck platform should not have to hold two vocabularies at once.
 *
 * <p>One difference from the outbox row, and it is intentional. A published event's
 * {@code last_error} is kept for ever, because "published, after five failures that
 * all said connection refused" is what turns a resolved incident into an
 * explanation. A job runs again tomorrow, so its error is cleared by a success:
 * an error left on a row that has succeeded a thousand times since is a false alarm
 * somebody eventually learns to ignore.
 */
@Entity
@Table(name = "scheduled_jobs")
public class JobRecord {

    @Id
    @Column(name = "name", nullable = false, updatable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private JobState state;

    /**
     * Which process believes it is running this job, and until when.
     *
     * <p>The holder is a diagnostic rather than an authority — it is whatever the
     * claiming process called itself. It is read for one thing only, and that thing
     * matters: a replica whose lease elapsed while it was working must not clear the
     * lease of whoever took the job from it.
     */
    @Column(name = "lock_holder")
    private String lockHolder;

    @Column(name = "lock_expires_at")
    private Instant lockExpiresAt;

    /** When the current or most recent run started. Old, beside a live lease, is a hung job. */
    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Column(name = "last_error")
    private String lastError;

    /** Consecutive failures. Reset by a success, because a job that recovers was never failing. */
    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    protected JobRecord() {
        // JPA.
    }

    /**
     * Whether this row is still held by the process that is about to write to it.
     *
     * <p>The check that makes an expiring lease safe rather than merely convenient.
     * Without it, a replica that was paused past its expiry would come back and
     * release a run that a second replica is in the middle of — and the third replica
     * along would then start the same job while that one is still going.
     */
    public boolean isHeldBy(String holder) {
        return lockHolder != null && lockHolder.equals(holder);
    }

    /**
     * The run finished, and the job is eligible again the moment its next trigger
     * fires.
     *
     * <p>The lease goes back rather than being left to expire: a job that released
     * nothing would run once per lease instead of once per tick, which for a relay
     * that polls every second is a minute of latency bought for nothing.
     */
    public void succeeded(Instant at) {
        this.lockHolder = null;
        this.lockExpiresAt = null;
        this.attempts = 0;
        this.lastError = null;
        this.nextAttemptAt = Objects.requireNonNull(at, "A run finished at a time");
    }

    /**
     * The run threw, and there are attempts left.
     *
     * <p>Stays {@link JobState#READY} and gives the lease back. Holding it would be a
     * second, silent way of preventing the retry — the backoff is a column so that
     * any replica can serve the next attempt, not only the one that failed.
     */
    public void retryAfter(Instant at, Duration backoff, String error) {
        this.attempts += 1;
        this.lastError = requireText(error, "A failure has a reason");
        this.lockHolder = null;
        this.lockExpiresAt = null;
        this.nextAttemptAt = Objects.requireNonNull(at, "A failure happened at a time").plus(backoff);
    }

    /**
     * The attempts ran out.
     *
     * <p>Terminal, and it carries the reason — V20's
     * {@code scheduled_jobs_dead_jobs_say_why} refuses the row without one, because
     * work that silently stopped happening is the hardest kind of outage to notice.
     */
    public void gaveUp(Instant at, String error) {
        this.attempts += 1;
        this.lastError = requireText(error, "A job that gave up says why");
        this.state = JobState.DEAD;
        this.lockHolder = null;
        this.lockExpiresAt = null;
        // Left where it is rather than nulled: the column is NOT NULL, and the last
        // instant this job was eligible is a true statement about the row.
        this.nextAttemptAt = Objects.requireNonNull(at, "A job gave up at a time");
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    public String getName() {
        return name;
    }

    public JobState getState() {
        return state;
    }

    public String getLockHolder() {
        return lockHolder;
    }

    public Instant getLockExpiresAt() {
        return lockExpiresAt;
    }

    public Instant getLastRunAt() {
        return lastRunAt;
    }

    public String getLastError() {
        return lastError;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof JobRecord job && Objects.equals(name, job.name);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(name);
    }

    @Override
    public String toString() {
        // No last error: it is a message from whatever the job was talking to, and
        // this ends up in log lines about a job that is already failing.
        return "JobRecord[name=" + name + ", state=" + state + ", attempts=" + attempts + ", holder=" + lockHolder
                + "]";
    }
}
