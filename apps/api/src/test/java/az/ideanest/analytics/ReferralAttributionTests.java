package az.ideanest.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.analytics.application.CaptureReferralVisit;
import az.ideanest.analytics.application.PledgeConfirmed;
import az.ideanest.analytics.application.ReferralReport;
import az.ideanest.analytics.application.ReferralReportService;
import az.ideanest.analytics.application.ReferralVisitService;
import az.ideanest.analytics.domain.ReferralChannel;
import az.ideanest.analytics.domain.ReferralSource;
import az.ideanest.shared.money.Money;
import az.ideanest.shared.outbox.Outbox;
import az.ideanest.shared.outbox.OutboxRelay;
import az.ideanest.support.AbstractIntegrationTest;
import az.ideanest.support.AdjustableClock;
import az.ideanest.support.Campaigns;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Attribution end to end: a visit, a confirmed pledge, and the line it becomes.
 *
 * <p><strong>Driven through the real outbox rather than by calling the service.</strong>
 * The event is recorded with {@code Outbox} inside a transaction, exactly as the pledge
 * module will record it, and {@code OutboxRelay} is then run once by hand — its timer is
 * {@code -} in the test profile for the reason that file gives. So what these tests
 * exercise is the whole path: a committed row, a claim, a dispatch,
 * {@code ApplicationEventOutboxDispatcher}, the listener, and the write. A test that
 * called {@code ReferralAttributionService} directly would prove the rule and nothing
 * about whether anything reaches it.
 *
 * <p><strong>Nothing in production records this event yet.</strong>
 * {@code PledgeConfirmed} says so and says what is missing; these tests stand in for
 * the producer, which is why they build the payload the way a producer would rather
 * than handing the service a Java object.
 *
 * <p>The ones that carry the design:
 *
 * <ul>
 *   <li>{@link #aRedeliveredEventDoesNotAttributeTwice()} — {@code OutboxMessage}'s
 *       contract is at-least-once, in those words, and this is how it is honoured.
 *   <li>{@link #aVisitPastItsWindowIsNoLongerEvidence()} — the boundary, applied by
 *       the query and not only by the rule.
 *   <li>{@link #anonymousBrowsingIsClaimedWhenTheVisitorSignsIn()} — without it,
 *       attribution would only ever see people who arrived already signed in.
 *   <li>{@link #theAmountSurvivesTheEventExactly()} — money crossing a JSON boundary
 *       as a string, which CLAUDE.md §3 makes not optional to test.
 * </ul>
 */
class ReferralAttributionTests extends AbstractIntegrationTest {

    /**
     * Distinguishes the accounts and campaigns these tests create. A counter rather
     * than a slice of an identifier: UUID version 7 starts with a millisecond, so two
     * made in the same millisecond would collide on a unique index for a reason that
     * has nothing to do with what is being checked.
     */
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static final String AGGREGATE = "pledge";

    @Autowired
    private ReferralVisitService visits;

    @Autowired
    private ReferralReportService reports;

    @Autowired
    private Outbox outbox;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private AnalyticsProperties properties;

    @Autowired
    private AdjustableClock clock;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private DataSource dataSource;

    private UUID creatorId;
    private UUID projectId;

    @BeforeEach
    void liveCampaign() {
        // Frozen, so that "thirty days later" is a step this test takes rather than a
        // duration it waits for, and so the window boundaries below are exact.
        clock.freeze();

        String handle = "referral-" + SEQUENCE.incrementAndGet();
        creatorId = Campaigns.creator(dataSource, handle);
        projectId = Campaigns.seed(dataSource, creatorId, handle).state("LIVE").insert();
    }

    @AfterEach
    void clear() {
        clock.reset();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM outbox_events");
        // Attributions and touches cascade from the campaign; the campaign is removed
        // so that a suite reading `projects` after this one starts from what it seeded.
        jdbc.update("DELETE FROM projects WHERE id = ?", projectId);
    }

    // ------------------------------------------------------------------
    // The rule, against the database
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a pledge is attributed to the last non-direct visit the backer made")
    void aPledgeIsAttributedToTheLastNonDirectVisit() {
        UUID backer = UUID.randomUUID();

        visit(backer, social("twitter"));
        clock.advance(Duration.ofDays(2));
        visit(backer, email("august-newsletter"));
        clock.advance(Duration.ofHours(1));
        visit(backer, ReferralSource.direct());

        confirm(backer, azn("120.00"), null);

        assertThat(attributedChannelOf(backer)).isEqualTo("EMAIL");
        assertThat(attributedSourceOf(backer)).isEqualTo("newsletter");
        assertThat(attributedCampaignOf(backer)).isEqualTo("august-newsletter");
    }

    @Test
    @DisplayName("a visit past its window is no longer evidence, and the pledge is direct")
    void aVisitPastItsWindowIsNoLongerEvidence() {
        UUID backer = UUID.randomUUID();
        visit(backer, social("twitter"));

        // One moment past the window this visit was recorded under. The rule and the
        // query both have to agree that the row has stopped counting; asserting through
        // the database is what proves the second one does.
        clock.advance(properties.referral().attributionWindow().plusSeconds(1));
        confirm(backer, azn("50.00"), null);

        assertThat(attributedChannelOf(backer)).isEqualTo("DIRECT");
        assertThat(attributedSourceOf(backer)).isNull();
        assertThat(touchIdOf(backer)).isNull();
    }

    @Test
    @DisplayName("a visit one moment inside its window still decides the pledge")
    void aVisitJustInsideItsWindowStillDecides() {
        UUID backer = UUID.randomUUID();
        visit(backer, social("twitter"));

        clock.advance(properties.referral().attributionWindow().minusSeconds(1));
        confirm(backer, azn("50.00"), null);

        assertThat(attributedChannelOf(backer)).isEqualTo("SOCIAL");
        assertThat(touchIdOf(backer)).isNotNull();
    }

    @Test
    @DisplayName("anonymous browsing is claimed when the visitor signs in")
    void anonymousBrowsingIsClaimedWhenTheVisitorSignsIn() {
        UUID backer = UUID.randomUUID();

        // The journey that actually happens: read about it, come back, register at
        // checkout. Everything before the last step is anonymous.
        String token = visits.record(new CaptureReferralVisit(projectId, null, social("podcast"), null))
                .visitorToken();
        clock.advance(Duration.ofDays(1));
        visits.record(new CaptureReferralVisit(projectId, token, ReferralSource.direct(), backer));

        confirm(backer, azn("75.00"), null);

        // The direct visit made while signed in loses to the podcast, which is only
        // visible to the rule because signing in claimed it.
        assertThat(attributedChannelOf(backer)).isEqualTo("SOCIAL");
        assertThat(attributedSourceOf(backer)).isEqualTo("podcast");
    }

    @Test
    @DisplayName("another account's visits are never claimed by presenting the same token")
    void anotherAccountsVisitsAreNotClaimed() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        String token = visits.record(new CaptureReferralVisit(projectId, null, social("podcast"), first))
                .visitorToken();
        // A shared device, or a link forwarded with the token still in it. The touch is
        // already somebody's, and it stays theirs.
        visits.record(new CaptureReferralVisit(projectId, token, ReferralSource.direct(), second));

        confirm(second, azn("30.00"), null);

        assertThat(attributedChannelOf(second)).isEqualTo("DIRECT");
    }

    @Test
    @DisplayName("a pledge carrying a referrer code and no visits credits the link")
    void aPledgeCarryingAReferrerCodeCreditsTheLink() {
        // The traffic that never reached the capture endpoint: a share link followed
        // straight into checkout. Reporting these as direct would tell a creator their
        // links do not work.
        UUID backer = UUID.randomUUID();

        confirm(backer, azn("200.00"), "aB7-xY9_Qz");

        assertThat(attributedChannelOf(backer)).isEqualTo("REFERRAL_LINK");
        assertThat(referrerCodeOf(backer)).isEqualTo("aB7-xY9_Qz");
    }

    @Test
    @DisplayName("a recorded visit beats a code that survived to checkout")
    void aRecordedVisitBeatsACodeInTheCheckoutUrl() {
        UUID backer = UUID.randomUUID();
        visit(backer, email("august-newsletter"));

        confirm(backer, azn("200.00"), "aB7-xY9_Qz");

        // A visit is dated evidence about this backer; a code on a pledge is a
        // parameter that survived. The fallback is a fallback.
        assertThat(attributedChannelOf(backer)).isEqualTo("EMAIL");
    }

    // ------------------------------------------------------------------
    // Redelivery and deduplication
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a redelivered event does not attribute the same pledge twice")
    void aRedeliveredEventDoesNotAttributeTwice() {
        UUID backer = UUID.randomUUID();
        visit(backer, social("twitter"));

        UUID pledgeId = UUID.randomUUID();
        record(pledgeId, backer, azn("40.00"), null);
        relay.run();
        // The same event again, which is what a crash between the transport accepting
        // a message and the relay committing produces. OutboxMessage calls this the
        // contract rather than an edge case.
        replay(pledgeId);
        relay.run();

        assertThat(attributionCountFor(pledgeId)).isEqualTo(1);
    }

    @Test
    @DisplayName("two confirmed pledges are two lines, not one")
    void twoPledgesAreTwoLines() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        visit(first, social("twitter"));
        visit(second, social("twitter"));

        confirm(first, azn("10.00"), null);
        confirm(second, azn("15.00"), null);

        ReferralReport report = reports.of(projectId, creatorId);
        assertThat(report.pledgeCount()).isEqualTo(2);
        assertThat(report.value()).isEqualTo(azn("25.00"));
    }

    @Test
    @DisplayName("the same source visited again inside the session interval is one visit")
    void aRepeatVisitIsNotRecordedTwice() {
        UUID backer = UUID.randomUUID();

        String token = visits.record(new CaptureReferralVisit(projectId, null, social("twitter"), backer))
                .visitorToken();
        clock.advance(Duration.ofMinutes(1));
        visits.record(new CaptureReferralVisit(projectId, token, social("twitter"), backer));
        visits.record(new CaptureReferralVisit(projectId, token, social("twitter"), backer));

        assertThat(touchCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a different source inside the session interval is a second visit")
    void aDifferentSourceInsideTheIntervalIsASecondVisit() {
        UUID backer = UUID.randomUUID();

        String token = visits.record(new CaptureReferralVisit(projectId, null, social("twitter"), backer))
                .visitorToken();
        visits.record(new CaptureReferralVisit(projectId, token, email("august-newsletter"), backer));

        // Two genuinely different places. Collapsing them would attribute the pledge to
        // whichever arrived first, which is the opposite of the rule.
        assertThat(touchCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("the same source after the session interval is a second visit")
    void theSameSourceAfterTheIntervalIsASecondVisit() {
        UUID backer = UUID.randomUUID();

        String token = visits.record(new CaptureReferralVisit(projectId, null, social("twitter"), backer))
                .visitorToken();
        clock.advance(properties.referral().repeatVisitInterval().plusSeconds(1));
        visits.record(new CaptureReferralVisit(projectId, token, social("twitter"), backer));

        assertThat(touchCount()).isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // Money
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the amount survives the event exactly, as a string and never a number")
    void theAmountSurvivesTheEventExactly() {
        // The payload is written as a producer would write it: a literal JSON body with
        // the amount as a string, per §10.3. A test that handed the service a Money
        // object would prove nothing about the boundary the value actually crosses.
        UUID backer = UUID.randomUUID();
        UUID pledgeId = UUID.randomUUID();
        Instant confirmedAt = now();

        inTransaction().executeWithoutResult(status -> outbox.record(
                AGGREGATE,
                pledgeId,
                PledgeConfirmed.EVENT_TYPE,
                Map.of(
                        "pledgeId", pledgeId,
                        "projectId", projectId,
                        "backerId", backer,
                        "total", Map.of("amount", "12345678.91", "currency", "AZN"),
                        "confirmedAt", confirmedAt)));
        relay.run();

        assertThat(amountOf(pledgeId)).isEqualByComparingTo(new BigDecimal("12345678.91"));
    }

    @Test
    @DisplayName("the smallest pledge the platform can take is not rounded away")
    void theSmallestPledgeIsNotRoundedAway() {
        UUID backer = UUID.randomUUID();
        visit(backer, social("twitter"));

        confirm(backer, azn("0.01"), null);

        assertThat(reports.of(projectId, creatorId).value()).isEqualTo(azn("0.01"));
    }

    @Test
    @DisplayName("the report's shares add up to exactly one hundred")
    void theReportSharesAddUpToOneHundred() {
        // Three sources whose values do not divide evenly. The arithmetic is checked in
        // ReferralAttributionRuleTests; this is the same guarantee surviving the query,
        // the grouping, and the SUM.
        attribute(social("twitter"), "10.00");
        attribute(social("podcast"), "10.00");
        attribute(email("august-newsletter"), "10.00");

        ReferralReport report = reports.of(projectId, creatorId);

        assertThat(report.sources()).hasSize(3);
        assertThat(report.sources().stream()
                        .map(ReferralReport.AttributedSource::share)
                        .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(report.value()).isEqualTo(azn("30.00"));
    }

    @Test
    @DisplayName("sources are ordered by the money they brought")
    void sourcesAreOrderedByValue() {
        attribute(social("twitter"), "10.00");
        attribute(email("august-newsletter"), "90.00");

        ReferralReport report = reports.of(projectId, creatorId);

        assertThat(report.sources().get(0).channel()).isEqualTo(ReferralChannel.EMAIL);
        assertThat(report.sources().get(0).share()).isEqualByComparingTo(new BigDecimal("90.00"));
        assertThat(report.sources().get(1).share()).isEqualByComparingTo(new BigDecimal("10.00"));
    }

    @Test
    @DisplayName("a campaign with nothing attributed reports nothing, not zero")
    void aCampaignWithNothingAttributedReportsNothing() {
        ReferralReport report = reports.of(projectId, creatorId);

        assertThat(report.pledgeCount()).isZero();
        assertThat(report.currency()).isNull();
        assertThat(report.value()).isNull();
        assertThat(report.sources()).isEmpty();
        assertThat(report.remainder()).isNull();
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** One visit by a known account, at the current instant. */
    private void visit(UUID backerId, ReferralSource source) {
        visits.record(new CaptureReferralVisit(projectId, null, source, backerId));
    }

    /** One attributed pledge from one source, for the report tests. */
    private void attribute(ReferralSource source, String amount) {
        UUID backer = UUID.randomUUID();
        visit(backer, source);
        confirm(backer, azn(amount), null);
    }

    /** Records a {@code pledge.confirmed} and drains the outbox, as production would. */
    private void confirm(UUID backerId, Money total, String referrerCode) {
        record(UUID.randomUUID(), backerId, total, referrerCode);
        relay.run();
    }

    private void record(UUID pledgeId, UUID backerId, Money total, String referrerCode) {
        PledgeConfirmed event =
                new PledgeConfirmed(pledgeId, projectId, backerId, total, referrerCode, now());
        inTransaction()
                .executeWithoutResult(status ->
                        outbox.record(AGGREGATE, pledgeId, PledgeConfirmed.EVENT_TYPE, event));
    }

    /**
     * Puts a published event back in the queue, which is what a crash between the
     * transport accepting a message and the relay committing produces.
     *
     * <p>The row is reset rather than a second event recorded, so the identifier the
     * listener deduplicates on is the same one — a new event would be a different
     * delivery and would prove nothing about redelivery.
     */
    private void replay(UUID pledgeId) {
        new JdbcTemplate(dataSource)
                .update(
                        // published_at goes back to null with the state:
                        // outbox_events_published_when_it_says_so holds the two
                        // together, which is exactly the invariant a replay has to
                        // respect in order to look like one.
                        "UPDATE outbox_events SET state = 'PENDING', published_at = NULL,"
                                + " next_attempt_at = now() WHERE aggregate_id = ?",
                        pledgeId);
    }

    private static ReferralSource social(String source) {
        return ReferralSource.of(ReferralChannel.SOCIAL, source, null, null);
    }

    private static ReferralSource email(String campaign) {
        return ReferralSource.of(ReferralChannel.EMAIL, "newsletter", campaign, null);
    }

    private static Money azn(String amount) {
        return Money.of(new BigDecimal(amount), "AZN");
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private TransactionTemplate inTransaction() {
        return new TransactionTemplate(transactions);
    }

    // ------------------------------------------------------------------
    // Reading the rows
    // ------------------------------------------------------------------

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private String attributedChannelOf(UUID backerId) {
        return columnFor(backerId, "channel", String.class);
    }

    private String attributedSourceOf(UUID backerId) {
        return columnFor(backerId, "source", String.class);
    }

    private String attributedCampaignOf(UUID backerId) {
        return columnFor(backerId, "campaign", String.class);
    }

    private String referrerCodeOf(UUID backerId) {
        return columnFor(backerId, "referrer_code", String.class);
    }

    private UUID touchIdOf(UUID backerId) {
        return columnFor(backerId, "touch_id", UUID.class);
    }

    /**
     * One column of the one attribution written for this backer's pledge.
     *
     * <p>Found by joining nothing: {@code referral_attributions} carries no backer, on
     * purpose, so the test knows which row is which because it made exactly one pledge
     * per backer. That constraint on the fixtures is the schema's guarantee showing
     * through, and it is worth the inconvenience.
     */
    private <T> T columnFor(UUID backerId, String column, Class<T> type) {
        List<T> values = jdbc().query(
                "SELECT " + column + " FROM referral_attributions a"
                        + " WHERE a.project_id = ? ORDER BY a.attributed_at DESC, a.id DESC",
                (row, index) -> row.getObject(1, type),
                projectId);
        assertThat(values).as("one attribution for backer %s", backerId).isNotEmpty();
        return values.get(0);
    }

    private BigDecimal amountOf(UUID pledgeId) {
        return jdbc().queryForObject(
                "SELECT amount FROM referral_attributions WHERE pledge_id = ?", BigDecimal.class, pledgeId);
    }

    private long attributionCountFor(UUID pledgeId) {
        Long count = jdbc().queryForObject(
                "SELECT count(*) FROM referral_attributions WHERE pledge_id = ?", Long.class, pledgeId);
        return count == null ? 0 : count;
    }

    private long touchCount() {
        Long count = jdbc().queryForObject(
                "SELECT count(*) FROM referral_touches WHERE project_id = ?", Long.class, projectId);
        return count == null ? 0 : count;
    }
}
