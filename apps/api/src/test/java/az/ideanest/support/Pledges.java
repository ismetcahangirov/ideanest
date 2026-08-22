package az.ideanest.support;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Puts a pledge into the state a test needs to start from, by writing the row.
 *
 * <p>{@code Campaigns}' argument, applied to the other side of the checkout. The honest
 * way to a {@code CONFIRMED} pledge is the two requests a backer makes, and
 * {@code PledgeApiTests} takes it because that path is what it is checking. A suite about
 * §9.6's retry schedule is not: driving forty pledges through the checkout would make
 * every one of its tests depend on the reservation TTL, on the rate limiter, and on forty
 * accounts, for a state that is a precondition rather than a subject.
 *
 * <p>What is written is what confirmation writes — the state, the instant, the amounts —
 * so the row that results is one the application could have produced, and every check
 * constraint on {@code pledges} still applies.
 *
 * <p><strong>There is no teardown helper here, deliberately.</strong> V41 makes
 * {@code transactions} and {@code ledger_entries} append-only in PostgreSQL and both
 * reference the pledge with {@code ON DELETE NO ACTION}, so a suite that has collected
 * anything <em>cannot</em> delete its pledges — the database refuses, which is the
 * property those triggers exist for. Such a suite mints a fresh campaign per test and
 * scopes its assertions to it, the way {@code AuditLogSchemaTests} and the four suites
 * that write audit rows already do.
 *
 * <p><strong>Each pledge needs its own backer.</strong>
 * {@code pledges_project_backer_active_key} allows one active pledge per backer per
 * campaign, so {@link #confirmed} mints an account per call rather than letting a caller
 * discover the index.
 */
public final class Pledges {

    private Pledges() {}

    /**
     * A confirmed pledge on a campaign, with its own backer.
     *
     * <p>The amount goes entirely into {@code base_amount}: the five components exist so
     * that a receipt can be itemised, and every collection charges {@code total_amount},
     * so a test about collection that split them would be asserting on the generated
     * column's arithmetic rather than on anything this feature does.
     *
     * @param handle a distinct prefix for the backer's account, so that a suite creating
     *     several does not collide on {@code users.handle}
     * @return the pledge's identifier
     */
    public static UUID confirmed(DataSource dataSource, UUID projectId, String handle, String amount) {
        UUID backerId = Campaigns.creator(dataSource, handle);
        return confirmedFor(dataSource, projectId, backerId, amount);
    }

    /** The same, for a backer the caller already has. */
    public static UUID confirmedFor(DataSource dataSource, UUID projectId, UUID backerId, String amount) {
        UUID pledgeId = UUID.randomUUID();
        new JdbcTemplate(dataSource)
                .update(
                        """
                        INSERT INTO pledges (id, project_id, backer_id, state, base_amount, currency, confirmed_at)
                        VALUES (?, ?, ?, 'CONFIRMED', ?, 'AZN', now())
                        """,
                        pledgeId,
                        projectId,
                        backerId,
                        new BigDecimal(amount));
        return pledgeId;
    }

    /**
     * A pledge already queued for collection, with a schedule a test chose.
     *
     * <p>For the cases that start part-way through §9.6 — a third attempt, a window about
     * to close — where driving the earlier attempts would make the test about the earlier
     * attempts.
     *
     * @param state {@code CHARGE_PENDING} or {@code CHARGE_FAILED};
     *     {@code pledges_collection_schedule_is_whole} refuses anything else with a
     *     schedule on it
     * @param attempts how many attempts have already been made
     */
    public static UUID queued(
            DataSource dataSource,
            UUID projectId,
            String handle,
            String amount,
            String state,
            int attempts,
            Instant nextAttemptAt,
            Instant windowEndsAt) {
        UUID pledgeId = UUID.randomUUID();
        new JdbcTemplate(dataSource)
                .update(
                        """
                        INSERT INTO pledges (id, project_id, backer_id, state, base_amount, currency,
                                             confirmed_at, charge_attempts, next_charge_attempt_at,
                                             charge_window_ends_at)
                        VALUES (?, ?, ?, ?, ?, 'AZN', now(), ?, ?, ?)
                        """,
                        pledgeId,
                        projectId,
                        Campaigns.creator(dataSource, handle),
                        state,
                        new BigDecimal(amount),
                        attempts,
                        java.sql.Timestamp.from(nextAttemptAt),
                        java.sql.Timestamp.from(windowEndsAt));
        return pledgeId;
    }
}
