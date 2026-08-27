package az.ideanest.risk.infrastructure;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The four things the fraud signals need to know — issue #108.
 *
 * <h2>Reading four modules' tables from this one</h2>
 *
 * <p>Deliberate, and not a boundary violation, on exactly the argument
 * {@code NotificationRecipients} makes for reading {@code users} from the notification
 * module: {@code ModuleBoundaryTests} forbids one module's classes from reaching into
 * another module's {@code domain} or {@code infrastructure} packages, and this file
 * imports nothing from {@code az.ideanest.pledge}, {@code az.ideanest.auth} or
 * {@code az.ideanest.user}. It asks questions in SQL.
 *
 * <p>It is also what a fraud signal <em>is</em>. Correlating behaviour across the platform
 * is the job; a module that could only see its own table would have nothing to correlate,
 * and routing every one of these through a published port would mean four new interfaces
 * on four modules to answer four counts.
 *
 * <h2>Counts, never rows</h2>
 *
 * <p>Nothing here returns a pledge, an amount, or a session. The scorer needs "how many"
 * and "which addresses", and a method that returned the rows themselves would be the
 * beginning of a screen that shows one person everything another has done. The one
 * exception is {@link #addressesUsedBy}, and it is bounded and never leaves the score.
 */
@Repository
public class RiskFacts {

    /**
     * How many addresses one account is asked about.
     *
     * <p>An account with a hundred sessions is unusual and the hundredth address adds
     * nothing to the question "is this one familiar". The bound is what stops a
     * long-lived account making this query grow without limit.
     */
    private static final int MAX_KNOWN_ADDRESSES = 50;

    private final NamedParameterJdbcTemplate jdbc;

    public RiskFacts(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * When this account registered, or empty when there is no such account.
     *
     * <p>Empty rather than an ancient default. {@code RiskScorer} reports the age signal as
     * unavailable, which is the safe direction: an account with no creation time is a row
     * that should not exist, and treating it as old would silently clear the signal.
     */
    public Optional<Instant> registeredAt(UUID accountId) {
        return Optional.ofNullable(jdbc.query(
                "SELECT created_at FROM users WHERE id = :id",
                new MapSqlParameterSource("id", accountId),
                rows -> rows.next() ? instantOf(rows.getObject("created_at", OffsetDateTime.class)) : null));
    }

    /**
     * Other pledges this account has made inside the window.
     *
     * <p><strong>Other</strong>: the pledge being assessed is excluded, so it is not
     * evidence against itself. Without that a threshold of one would fire on everybody's
     * first pledge.
     *
     * <p>Drafts are excluded. A draft is a checkout somebody opened and did not finish, and
     * counting them would make an indecisive backer look like a card tester.
     */
    public int pledgesByAccountSince(UUID accountId, UUID excludingPledgeId, Instant since) {
        Integer count = jdbc.queryForObject(
                """
                SELECT count(*) FROM pledges
                 WHERE backer_id = :account
                   AND id <> :excluding
                   AND state <> 'DRAFT'
                   AND created_at >= :since
                """,
                new MapSqlParameterSource()
                        .addValue("account", accountId)
                        .addValue("excluding", excludingPledgeId)
                        .addValue("since", at(since)),
                Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * Pledges from one source address inside the window, across every account.
     *
     * <p>The join is through {@code sessions}: a pledge carries no address of its own, so
     * this asks which accounts have held a session from that address and counts their
     * pledges. That is an approximation and it is the honest one available — the alternative
     * is a column on {@code pledges} that nothing writes, and inventing a number is worse
     * than an approximate one whose approximation is written down.
     *
     * <p>It over-counts a shared address, which is why the threshold for this signal is
     * higher than the per-account one: an office or a carrier NAT has a city behind it.
     */
    public int pledgesFromAddressSince(String address, UUID excludingPledgeId, Instant since) {
        Integer count = jdbc.queryForObject(
                """
                SELECT count(*) FROM pledges pledge
                 WHERE pledge.id <> :excluding
                   AND pledge.state <> 'DRAFT'
                   AND pledge.created_at >= :since
                   AND EXISTS (
                       SELECT 1 FROM sessions session
                        WHERE session.user_id = pledge.backer_id
                          AND session.ip_address = cast(:address AS inet))
                """,
                new MapSqlParameterSource()
                        .addValue("excluding", excludingPledgeId)
                        .addValue("since", at(since))
                        .addValue("address", address),
                Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * Every address this account has held a session from, before a given moment.
     *
     * <p>Bounded and ordered by recency, so a long-lived account is asked a cheap question
     * and the addresses kept are the ones a person is actually using. Sessions are
     * deliberately not filtered by revocation or expiry: a revoked session is still an
     * address this person used, which is the whole question.
     */
    public Set<String> addressesUsedBy(UUID accountId, Instant before) {
        Set<String> addresses = new LinkedHashSet<>();
        jdbc.query(
                """
                SELECT DISTINCT ON (ip_address) host(ip_address) AS address, created_at
                  FROM sessions
                 WHERE user_id = :account AND ip_address IS NOT NULL AND created_at < :before
                 ORDER BY ip_address, created_at DESC
                 LIMIT :limit
                """,
                new MapSqlParameterSource()
                        .addValue("account", accountId)
                        .addValue("before", at(before))
                        .addValue("limit", MAX_KNOWN_ADDRESSES),
                rows -> {
                    addresses.add(rows.getString("address"));
                });
        return addresses;
    }

    /**
     * The most recent address this account signed in from, at or before a moment.
     *
     * <p>Stands in for "where the pledge came from". A pledge is assessed from an event
     * rather than from the request that made it — {@code PledgeRiskListener} explains why —
     * so the request's own address is gone by then, and the session that was current is the
     * closest true thing.
     *
     * <p>Empty is an ordinary answer: a pledge made in a session whose row has since been
     * deleted, or an account with none. The signals that need an address report themselves
     * unavailable rather than clear.
     */
    public Optional<String> addressAt(UUID accountId, Instant moment) {
        return Optional.ofNullable(jdbc.query(
                """
                SELECT host(ip_address) AS address
                  FROM sessions
                 WHERE user_id = :account AND ip_address IS NOT NULL AND created_at <= :moment
                 ORDER BY created_at DESC
                 LIMIT 1
                """,
                new MapSqlParameterSource().addValue("account", accountId).addValue("moment", at(moment)),
                rows -> rows.next() ? rows.getString("address") : null));
    }

    /**
     * An instant as something the driver will bind.
     *
     * <p>pgjdbc refuses a {@link Instant} outright — "Can't infer the SQL type to use for
     * an instance of java.time.Instant" — because the type carries no offset and the
     * driver will not guess one. Every other module reaches the database through JPA,
     * whose converters do this quietly, so this is the first place on the platform where
     * it has to be said out loud.
     *
     * <p>UTC, which is what an {@code Instant} means and what {@code timestamptz} stores.
     */
    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * The other direction, and the driver refuses that one too.
     *
     * <p>{@code rows.getObject(column, Instant.class)} answers "conversion to class
     * java.time.Instant from timestamptz not supported": pgjdbc reads a
     * {@code timestamptz} as an {@link OffsetDateTime} and leaves the conversion to the
     * caller. Doing it here rather than at each call site is what stops the third query
     * discovering it again.
     */
    private static Instant instantOf(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    /**
     * Where the reward is going, when the pledge named a destination.
     *
     * <p>{@code pledges.shipping_country} rather than the shipping address itself: that
     * table is one AES-256-GCM ciphertext and the country is the one field V36 kept outside
     * it, precisely so that questions like this one do not need a key.
     */
    public Optional<String> destinationCountryOf(UUID pledgeId) {
        return Optional.ofNullable(jdbc.query(
                "SELECT shipping_country FROM pledges WHERE id = :id",
                new MapSqlParameterSource("id", pledgeId),
                rows -> rows.next() ? rows.getString("shipping_country") : null));
    }
}
