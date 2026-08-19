package az.ideanest.notification;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.notification.NotificationProperties;
import az.ideanest.notification.application.DigestAssembly;
import az.ideanest.notification.application.DigestAssembly.Outcome;
import az.ideanest.notification.application.NotificationDigest;
import az.ideanest.notification.application.NotificationDigestJob;
import az.ideanest.notification.application.NotificationEvents.PledgeConfirmed;
import az.ideanest.notification.application.NotificationSender;
import az.ideanest.notification.domain.DeliveryMode;
import az.ideanest.notification.domain.NotificationCategory;
import az.ideanest.notification.domain.NotificationChannel;
import az.ideanest.notification.domain.NotificationPreference;
import az.ideanest.notification.infrastructure.NotificationPreferenceRepository;
import az.ideanest.shared.money.Money;
import az.ideanest.shared.outbox.Outbox;
import az.ideanest.shared.outbox.OutboxRelay;
import az.ideanest.support.AbstractIntegrationTest;
import az.ideanest.support.Campaigns;
import az.ideanest.support.RecordingChannels;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
 * The digest: that a held notification is combined and sent, and that it is one message.
 *
 * <p>#244 reported that {@code DIGEST} shipped as a preference the platform could not honour —
 * a held row was written and nothing ever drained it, so choosing digest meant receiving
 * nothing on that channel for ever, with nothing anywhere reporting a failure. This suite is
 * what makes the fix a guarantee rather than a claim.
 *
 * <p>The rows are written through the real path — a preference, an outbox event, the relay,
 * the fan-out — rather than inserted, because what is being asserted is that the preference
 * and the combining job agree. A fixture that built its own {@code HELD} rows could disagree
 * with {@code DeliveryPolicy} and every assertion here would still pass.
 *
 * <p>Every pass is driven with the instant it should be judged against rather than by waiting.
 * {@code ideanest.notification.digest.schedule} is {@code -} in the test profile so the real
 * timer is not doing the same work on another thread, and the zone there is UTC so that an
 * expected digest boundary is computed in the zone these instants are already in.
 *
 * <p>The ones that carry the design:
 *
 * <ul>
 *   <li>{@link #aDigestIsOneMessageCoveringSeveralNotifications()} — the property a digest
 *       <em>is</em>. Four notifications and one send; four sends would be the preference not
 *       being honoured while every row still said {@code SENT}.
 *   <li>{@link #aHeldNotificationIsNotSentByTheImmediateQueue()} — the two queues staying
 *       disjoint. If the sender could claim a held row, digest mode would silently become
 *       immediate mode.
 *   <li>{@link #aNotificationHeldSinceThePeriodOpenedWaitsForItToClose()} — daily meaning
 *       daily. Without it a digest would go out on the first tick after the notification
 *       arrived, which is immediate delivery with extra steps.
 *   <li>{@link #aRefusedDigestChargesEveryMemberAndRetriesAsAGroup()} — a digest is one
 *       message, so it fails as one. A policy that split the group would produce a digest that
 *       silently shrank on each attempt.
 *   <li>{@link #eachRecipientGetsTheirOwnDigest()} — the grouping. One message per (recipient,
 *       channel) and never one message covering two people.
 * </ul>
 */
class NotificationDigestTests extends AbstractIntegrationTest {

    /** Distinguishes the accounts these tests create. See {@code ReferralAttributionTests}. */
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static final String AGGREGATE = "pledge";

    @Autowired
    private NotificationDigestJob digests;

    @Autowired
    private DigestAssembly assembly;

    @Autowired
    private NotificationSender sender;

    @Autowired
    private NotificationPreferenceRepository preferences;

    @Autowired
    private NotificationProperties properties;

    @Autowired
    private Outbox outbox;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private DataSource dataSource;

    private UUID backerId;
    private UUID projectId;
    private String handle;

    /** 08:00 UTC on a day comfortably in the past, which is a closed digest period. */
    private Instant digestHour;

    @BeforeEach
    void anAccountThatDigestsItsPledges() {
        handle = "digest-" + SEQUENCE.incrementAndGet();
        backerId = Campaigns.creator(dataSource, handle);
        projectId = Campaigns.seed(dataSource, backerId, handle).state("LIVE").insert();
        digestHour = properties.digest().window().lastClosedAt(Instant.now());
    }

    @AfterEach
    void clear() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM notification_preferences");
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM projects WHERE id = ?", projectId);
    }

    // ------------------------------------------------------------------
    // It is one message
    // ------------------------------------------------------------------

    /**
     * The property a digest is, and the one #244 says the platform offered and did not provide.
     */
    @Test
    @DisplayName("a digest is one message covering several notifications")
    void aDigestIsOneMessageCoveringSeveralNotifications() {
        digest(NotificationChannel.EMAIL);
        Instant first = beforeTheDigestHour(Duration.ofHours(6));
        Instant last = beforeTheDigestHour(Duration.ofHours(1));
        confirm(first);
        confirm(beforeTheDigestHour(Duration.ofHours(4)));
        confirm(last);

        assertThat(heldCount()).as("three held rows, waiting for the period to close").isEqualTo(3);

        RecordingChannels channels = RecordingChannels.accepting();
        int sent = digests.combineDue(aPassJustNow(), channels.map());

        assertThat(sent).as("one digest").isEqualTo(1);
        assertThat(channels.digests()).hasSize(1);

        NotificationDigest digest = channels.digests().get(0);
        assertThat(digest.size()).as("all three notifications, in one message").isEqualTo(3);
        assertThat(digest.channel()).isEqualTo(NotificationChannel.EMAIL);
        assertThat(digest.recipientId()).isEqualTo(backerId);
        assertThat(digest.from()).as("the period a template can describe, oldest first").isEqualTo(first);
        assertThat(digest.to()).isEqualTo(last);
        assertThat(digest.notifications().stream().map(m -> m.occurredAt()).toList())
                .as("oldest first, so a rendered digest reads in the order things happened")
                .isSorted();

        assertThat(heldCount()).as("and the rows are no longer held").isZero();
        assertThat(statesOf(NotificationChannel.EMAIL)).containsOnly("SENT");
    }

    /**
     * The key that makes the unavoidable duplicate harmless.
     *
     * <p>A digest has no row of its own, so its key is derived from its members. If it were
     * generated per attempt, this module's central claim — that a provider given the key can
     * collapse a duplicate — would be false for digests and nothing else would notice.
     */
    @Test
    @DisplayName("a retried digest carries the same idempotency key")
    void aRetriedDigestCarriesTheSameIdempotencyKey() {
        digest(NotificationChannel.EMAIL);
        confirm(beforeTheDigestHour(Duration.ofHours(3)));
        confirm(beforeTheDigestHour(Duration.ofHours(2)));

        RecordingChannels refusing = RecordingChannels.refusing(NotificationChannel.EMAIL);
        Instant firstAttempt = aPassJustNow();
        digests.combineDue(firstAttempt, refusing.map());

        refusing.accept();
        Instant retry = firstAttempt.plus(properties.delivery().backoffAfter(1)).plusSeconds(1);
        digests.combineDue(retry, refusing.map());

        assertThat(refusing.attemptedIds(NotificationChannel.EMAIL))
                .as("two attempts at one message, under one key")
                .hasSize(2)
                .satisfies(ids -> assertThat(ids.get(0)).isEqualTo(ids.get(1)));
    }

    /**
     * The two queues are disjoint by state, and that is what keeps digest mode meaningful.
     *
     * <p>{@code claimNext} considers only {@code PENDING}. If it ever considered {@code HELD},
     * digest mode would silently become immediate mode and every row would still look correct.
     */
    @Test
    @DisplayName("a held notification is not sent by the immediate queue")
    void aHeldNotificationIsNotSentByTheImmediateQueue() {
        digest(NotificationChannel.EMAIL);
        confirm(beforeTheDigestHour(Duration.ofHours(2)));

        RecordingChannels channels = RecordingChannels.accepting();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        while (sender.sendPending(now, channels.map()) > 0) {
            // Until the immediate queue stops yielding.
        }

        assertThat(stateOf(NotificationChannel.EMAIL))
                .as("the email row digests, so the sender leaves it alone")
                .isEqualTo("HELD");
        assertThat(statesOf(NotificationChannel.IN_APP))
                .as("in-app cannot digest, so it went immediately")
                .containsOnly("SENT");
    }

    // ------------------------------------------------------------------
    // When
    // ------------------------------------------------------------------

    /**
     * Daily meaning daily.
     *
     * <p>Without the bound on the period, a digest would go out on the first tick after a
     * notification arrived — which is immediate delivery with extra steps and a worse latency.
     */
    @Test
    @DisplayName("a notification held since the period opened waits for it to close")
    void aNotificationHeldSinceThePeriodOpenedWaitsForItToClose() {
        digest(NotificationChannel.EMAIL);
        confirm(digestHour.plusSeconds(1));

        RecordingChannels channels = RecordingChannels.accepting();
        assertThat(digests.combineDue(aPassJustNow().plus(Duration.ofHours(2)), channels.map()))
                .as("its period has not closed yet")
                .isZero();
        assertThat(heldCount()).isEqualTo(1);

        assertThat(digests.combineDue(aPassTomorrow(), channels.map()))
                .as("and it goes out when the period does close")
                .isEqualTo(1);
        assertThat(heldCount()).isZero();
    }

    // ------------------------------------------------------------------
    // Grouping
    // ------------------------------------------------------------------

    /** One message per (recipient, channel), and never one message covering two people. */
    @Test
    @DisplayName("each recipient gets their own digest")
    void eachRecipientGetsTheirOwnDigest() {
        UUID otherBacker = Campaigns.creator(dataSource, handle + "-other");
        digest(NotificationChannel.EMAIL);
        digest(otherBacker, NotificationChannel.EMAIL);
        confirm(backerId, beforeTheDigestHour(Duration.ofHours(3)));
        confirm(otherBacker, beforeTheDigestHour(Duration.ofHours(2)));

        RecordingChannels channels = RecordingChannels.accepting();
        int sent = 0;
        for (int pass = 0; pass < 3; pass++) {
            sent += digests.combineDue(aPassJustNow(), channels.map());
        }

        assertThat(sent).as("two people, two digests").isEqualTo(2);
        assertThat(channels.digests()).hasSize(2);
        assertThat(channels.digests().stream().map(NotificationDigest::recipientId).toList())
                .containsExactlyInAnyOrder(backerId, otherBacker);
        assertThat(channels.digests()).allSatisfy(digest -> assertThat(digest.size()).isEqualTo(1));
    }

    /**
     * A person who digests one channel and not another gets both behaviours.
     *
     * <p>§4.10's preferences are per category <em>and</em> per channel, so this is the ordinary
     * case rather than an edge one.
     */
    @Test
    @DisplayName("digesting one channel leaves the others immediate")
    void digestingOneChannelLeavesTheOthersImmediate() {
        digest(NotificationChannel.EMAIL);
        confirm(beforeTheDigestHour(Duration.ofHours(2)));

        assertThat(stateOf(NotificationChannel.EMAIL)).isEqualTo("HELD");
        assertThat(statesOf(NotificationChannel.PUSH))
                .as("nothing was said about push, so §4.10's default applies")
                .containsOnly("PENDING");
        assertThat(statesOf(NotificationChannel.IN_APP)).containsOnly("PENDING");
    }

    /**
     * The bound on one message, and what happens to the remainder.
     *
     * <p>Observable only because the test profile sets it to three. The remainder must stay held
     * <em>and due</em>: a cap that silently deferred it to the next period would be the same
     * class of failure as the one #244 reported.
     *
     * <p>Driven a message at a time through {@link DigestAssembly} rather than a pass at a time
     * through the job, because there are two bounds here and only one of them is the subject: the
     * pass would combine the remainder itself — which it should, and which
     * {@code NotificationProperties.Digest#batchSize} owns — and that would hide whether the
     * message was bounded at all.
     */
    @Test
    @DisplayName("a digest is bounded, and the remainder stays held and still due")
    void aDigestIsBoundedAndTheRemainderIsStillDue() {
        int bound = properties.digest().maxNotificationsPerMessage();
        digest(NotificationChannel.EMAIL);
        for (int held = 0; held < bound + 1; held++) {
            confirm(beforeTheDigestHour(Duration.ofHours(held + 1L)));
        }

        RecordingChannels channels = RecordingChannels.accepting();
        Instant at = aPassJustNow();

        assertThat(assembly.combineNext(at, channels.map())).isEqualTo(Outcome.SENT);
        assertThat(channels.digests().get(0).size()).as("bounded at %s", bound).isEqualTo(bound);
        assertThat(heldCount()).as("the remainder is still held").isEqualTo(1);

        assertThat(assembly.combineNext(at, channels.map()))
                .as("and still due, so the very next message carries it rather than the next period")
                .isEqualTo(Outcome.SENT);
        assertThat(channels.digests()).hasSize(2);
        assertThat(channels.digests().get(1).size()).isEqualTo(1);
        assertThat(heldCount()).isZero();

        assertThat(assembly.combineNext(at, channels.map())).isEqualTo(Outcome.NOTHING_TO_DO);
    }

    // ------------------------------------------------------------------
    // Refusal
    // ------------------------------------------------------------------

    /**
     * A digest is one message, so it fails as one.
     *
     * <p>A policy that dead-lettered some of a group and retried the rest would produce a digest
     * that silently shrank on every attempt, and the notifications that fell out of it would be
     * marked as having gone.
     */
    @Test
    @DisplayName("a refused digest charges every member and retries as a group")
    void aRefusedDigestChargesEveryMemberAndRetriesAsAGroup() {
        digest(NotificationChannel.EMAIL);
        confirm(beforeTheDigestHour(Duration.ofHours(3)));
        confirm(beforeTheDigestHour(Duration.ofHours(2)));

        RecordingChannels channels = RecordingChannels.refusing(NotificationChannel.EMAIL);
        Instant at = aPassJustNow();
        digests.combineDue(at, channels.map());

        assertThat(statesOf(NotificationChannel.EMAIL))
                .as("still held: a refused digest is still the platform's promise")
                .containsOnly("HELD");
        assertThat(attemptsOf(NotificationChannel.EMAIL))
                .as("one attempt each, because there was one attempt")
                .containsOnly(1);
        assertThat(nextAttemptsOf(NotificationChannel.EMAIL))
                .as("one next attempt for the group, because they retry as a group")
                .hasSize(1);

        assertThat(digests.combineDue(at, channels.map()))
                .as("nothing is eligible before the backoff has elapsed")
                .isZero();

        channels.accept();
        Instant afterBackoff = at.plus(properties.delivery().backoffAfter(1)).plusSeconds(1);
        assertThat(digests.combineDue(afterBackoff, channels.map())).isEqualTo(1);
        assertThat(channels.digests()).as("one message on the retry, not two").hasSize(1);
        assertThat(channels.digests().get(0).size()).isEqualTo(2);
        assertThat(statesOf(NotificationChannel.EMAIL)).containsOnly("SENT");
    }

    /** The bound on retrying, and the reason a dead letter has to say why. */
    @Test
    @DisplayName("a digest that keeps being refused dead-letters every member with the reason")
    void aDigestThatKeepsBeingRefusedDeadLettersEveryMember() {
        digest(NotificationChannel.EMAIL);
        confirm(beforeTheDigestHour(Duration.ofHours(3)));
        confirm(beforeTheDigestHour(Duration.ofHours(2)));

        RecordingChannels channels = RecordingChannels.refusing(NotificationChannel.EMAIL);
        Instant at = aPassJustNow();
        for (int attempt = 1; attempt <= properties.delivery().maxAttempts(); attempt++) {
            digests.combineDue(at, channels.map());
            at = at.plus(properties.delivery().backoffAfter(attempt)).plusSeconds(1);
        }

        assertThat(statesOf(NotificationChannel.EMAIL)).containsOnly("DEAD");
        assertThat(attemptsOf(NotificationChannel.EMAIL))
                .containsOnly(properties.delivery().maxAttempts());
        assertThat(lastErrorsOf(NotificationChannel.EMAIL))
                .as("notifications_dead_letters_say_why, on every member")
                .allSatisfy(error -> assertThat(error).contains(RecordingChannels.REFUSAL));

        assertThat(digests.combineDue(at, channels.map()))
                .as("and it stops being work, which is what the bound is for")
                .isZero();
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** Stores "digest my pledges on this channel" for the account under test. */
    private void digest(NotificationChannel channel) {
        digest(backerId, channel);
    }

    private void digest(UUID recipientId, NotificationChannel channel) {
        preferences.save(NotificationPreference.of(
                recipientId,
                NotificationCategory.PLEDGES,
                channel,
                DeliveryMode.DIGEST,
                Instant.now().truncatedTo(ChronoUnit.MICROS)));
    }

    /** Records a {@code pledge.confirmed} that happened at {@code occurredAt} and fans it out. */
    private void confirm(Instant occurredAt) {
        confirm(backerId, occurredAt);
    }

    private void confirm(UUID recipientId, Instant occurredAt) {
        UUID pledgeId = UUID.randomUUID();
        PledgeConfirmed event = new PledgeConfirmed(
                pledgeId, projectId, recipientId, Money.of(new BigDecimal("50.00"), "AZN"), occurredAt);
        new TransactionTemplate(transactions)
                .executeWithoutResult(status ->
                        outbox.record(AGGREGATE, pledgeId, PledgeConfirmed.EVENT_TYPE, event));
        relay.run();
    }

    /**
     * An instant inside the period that has most recently closed.
     *
     * <p>Before the digest hour, so the notification belongs to a period that is over and is
     * therefore due.
     */
    private Instant beforeTheDigestHour(Duration by) {
        return digestHour.minus(by);
    }

    /**
     * The instant a pass is judged against: now, plus a second.
     *
     * <p><strong>Not {@code digestHour} plus a second, which is the version of this that fails
     * once a day.</strong> A held row's {@code next_attempt_at} is the instant the fan-out wrote
     * it — the real clock — so a pass judged against an instant from before that finds the row
     * ineligible for a reason none of these tests is about. Now is always at or after
     * {@code digestHour}, because {@code digestHour} is the period that has most recently closed,
     * so the cutoff this resolves to is the same one.
     */
    private Instant aPassJustNow() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS).plusSeconds(1);
    }

    /** A pass a day later, by which time the period in progress has closed too. */
    private Instant aPassTomorrow() {
        return aPassJustNow().plus(Duration.ofDays(1));
    }

    private long heldCount() {
        return jdbc().queryForObject("SELECT count(*) FROM notifications WHERE state = 'HELD'", Long.class);
    }

    private List<String> statesOf(NotificationChannel channel) {
        return columnFor(channel, "state", String.class);
    }

    private String stateOf(NotificationChannel channel) {
        return statesOf(channel).get(0);
    }

    private List<Integer> attemptsOf(NotificationChannel channel) {
        return columnFor(channel, "attempts", Integer.class);
    }

    private List<String> lastErrorsOf(NotificationChannel channel) {
        return columnFor(channel, "last_error", String.class);
    }

    /** Distinct next attempts, which is how "they retry as a group" is observable. */
    private List<Instant> nextAttemptsOf(NotificationChannel channel) {
        return jdbc().queryForList(
                        "SELECT DISTINCT next_attempt_at FROM notifications WHERE channel = ?",
                        Instant.class,
                        channel.name());
    }

    private <T> List<T> columnFor(NotificationChannel channel, String column, Class<T> type) {
        return jdbc().queryForList(
                        "SELECT " + column + " FROM notifications WHERE channel = ? ORDER BY occurred_at",
                        type,
                        channel.name());
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }
}
