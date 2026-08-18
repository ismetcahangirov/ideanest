package az.ideanest.notification;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.notification.NotificationProperties;
import az.ideanest.notification.application.ChannelSender;
import az.ideanest.notification.application.NotificationEvents.PledgeConfirmed;
import az.ideanest.notification.application.NotificationMessage;
import az.ideanest.notification.application.NotificationSender;
import az.ideanest.notification.domain.NotificationChannel;
import az.ideanest.shared.money.Money;
import az.ideanest.shared.outbox.Outbox;
import az.ideanest.shared.outbox.OutboxRelay;
import az.ideanest.support.AbstractIntegrationTest;
import az.ideanest.support.Campaigns;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
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
 * The outbound half: that the queue drains, and that nothing in it is sent twice.
 *
 * <p>{@code NotificationDispatch} argues the design at length — the claim is the lock,
 * send-then-commit is at-least-once and never at-most-once, one row per transaction — and
 * until this suite existed none of it was checked. An argument about duplicates that
 * nothing tests is an argument, not a guarantee.
 *
 * <p>Every pass is driven with the instant it should be judged against rather than by
 * waiting, for {@code JobSchedulerTests}' reason: waiting for a five second backoff to
 * elapse would make the suite slow, and make it flaky on the machine that is busiest.
 * {@code ideanest.notification.delivery.send-schedule} is {@code -} in the test profile so
 * that the real timer is not doing the same work on another thread underneath.
 *
 * <p>The ones that carry the design:
 *
 * <ul>
 *   <li>{@link #aSentNotificationIsNeverSentAgain()} — the property the state column
 *       exists for.
 *   <li>{@link #aRefusedNotificationWaitsForItsBackoffAndThenGoesExactlyOnce()} — a retry
 *       is the next pass finding the row eligible, and it sends one message, not two.
 *   <li>{@link #everyAttemptCarriesTheSameIdempotencyKey()} — the duplicate a crash
 *       between the send and the commit produces cannot be prevented on this side, so
 *       what makes it harmless is that a provider can collapse it. That requires the key
 *       to be stable, which is the whole of this side's obligation.
 *   <li>{@link #aChannelThatKeepsRefusingBecomesADeadLetterCarryingTheLastError()} — the
 *       bound, and the one failure §4.10 says somebody has to be paged about.
 * </ul>
 */
class NotificationDeliveryTests extends AbstractIntegrationTest {

    /** Distinguishes the accounts these tests create. See {@code ReferralAttributionTests}. */
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static final String AGGREGATE = "pledge";

    /** §4.10 gives {@code PLEDGE_CONFIRMED} email, push and in-app. */
    private static final int PLEDGE_CONFIRMED_CHANNELS = 3;

    @Autowired
    private NotificationSender sender;

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
    private Instant now;

    @BeforeEach
    void aQueueWithSomethingInIt() {
        now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String handle = "delivery-" + SEQUENCE.incrementAndGet();
        backerId = Campaigns.creator(dataSource, handle);
        projectId = Campaigns.seed(dataSource, backerId, handle).state("LIVE").insert();
    }

    @AfterEach
    void clear() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM projects WHERE id = ?", projectId);
    }

    // ------------------------------------------------------------------
    // Draining
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a pass sends what is pending and marks it sent")
    void aPassDrainsTheQueue() {
        confirm();
        Channels channels = Channels.accepting();

        int sent = drain(channels);

        assertThat(sent).isEqualTo(PLEDGE_CONFIRMED_CHANNELS);
        assertThat(channels.sent()).hasSize(PLEDGE_CONFIRMED_CHANNELS);
        assertThat(statesOf()).containsOnly("SENT");
    }

    /**
     * The bound, which is only observable because the test profile sets it to two.
     *
     * <p>A backlog must not become one pass that overlaps its own next tick, and the
     * remainder being one tick away is what makes that safe rather than merely bounded.
     */
    @Test
    @DisplayName("a pass sends no more than its batch size, and the next pass takes the rest")
    void aPassIsBounded() {
        confirm();
        Channels channels = Channels.accepting();
        int batchSize = properties.delivery().batchSize();

        assertThat(sender.sendPending(now, channels.map()))
                .as("PLEDGE_CONFIRMED writes three rows and the pass is bounded at %s", batchSize)
                .isEqualTo(batchSize);
        assertThat(sender.sendPending(now, channels.map()))
                .as("the remainder, on the next tick")
                .isEqualTo(PLEDGE_CONFIRMED_CHANNELS - batchSize);
        assertThat(statesOf()).containsOnly("SENT");
    }

    // ------------------------------------------------------------------
    // Not twice
    // ------------------------------------------------------------------

    /**
     * The property {@code notifications.state} exists for.
     *
     * <p>{@code claimNext} only considers {@code PENDING}, so a row that has been sent is
     * not a candidate however many times the sender runs. Checked by running it again
     * rather than by reading the query, because the query is the thing that could be
     * wrong.
     */
    @Test
    @DisplayName("a sent notification is never sent again, however often the sender runs")
    void aSentNotificationIsNeverSentAgain() {
        confirm();
        Channels channels = Channels.accepting();
        drain(channels);

        drain(channels);
        drain(channels);

        assertThat(channels.sent())
                .as("three notifications, one send each, after three passes")
                .hasSize(PLEDGE_CONFIRMED_CHANNELS)
                .doesNotHaveDuplicates();
    }

    /**
     * A retry is the next pass, and it sends one message rather than two.
     *
     * <p>The failure this guards against is subtle: a retry that re-sent everything from
     * the event, or that sent the refused row twice because the first attempt had already
     * reached the channel, would be invisible in the table — every row would say
     * {@code SENT} exactly once — and visible only in somebody's inbox.
     */
    @Test
    @DisplayName("a refused notification waits for its backoff and then goes exactly once")
    void aRefusedNotificationWaitsForItsBackoffAndThenGoesExactlyOnce() {
        confirm();
        Channels channels = Channels.refusing(NotificationChannel.EMAIL);

        drain(channels);

        assertThat(channels.sent()).as("the two channels that accepted").hasSize(2);
        assertThat(stateOf(NotificationChannel.EMAIL)).isEqualTo("PENDING");
        assertThat(attemptsOf(NotificationChannel.EMAIL)).isEqualTo(1);

        // Before the backoff has elapsed, the row is not a candidate at all.
        assertThat(sender.sendPending(now, channels.map()))
                .as("nothing is eligible yet")
                .isZero();

        channels.accept();
        Instant afterBackoff = now.plus(properties.delivery().backoffAfter(1)).plus(Duration.ofSeconds(1));
        assertThat(sender.sendPending(afterBackoff, channels.map())).isEqualTo(1);

        assertThat(channels.sent())
                .as("the refused one went once, on its retry, and the other two were not sent again")
                .hasSize(PLEDGE_CONFIRMED_CHANNELS)
                .doesNotHaveDuplicates();
        assertThat(statesOf()).containsOnly("SENT");
    }

    /**
     * What makes the unavoidable duplicate harmless.
     *
     * <p>A crash between the channel accepting the message and this transaction
     * committing sends the message again — deliberately, because the other order loses
     * notifications silently. This side cannot prevent that duplicate. What it owes is a
     * key stable across every attempt, so that a provider given one can collapse the two.
     * If the key were generated per attempt, the design's central claim would be false and
     * nothing else in the suite would notice.
     */
    @Test
    @DisplayName("every attempt at a notification carries the same idempotency key")
    void everyAttemptCarriesTheSameIdempotencyKey() {
        confirm();
        Channels channels = Channels.refusing(NotificationChannel.EMAIL);

        drain(channels);
        UUID rowId = idOf(NotificationChannel.EMAIL);

        Instant at = now;
        for (int attempt = 1; attempt < properties.delivery().maxAttempts(); attempt++) {
            at = at.plus(properties.delivery().backoffAfter(attempt)).plus(Duration.ofSeconds(1));
            sender.sendPending(at, channels.map());
        }

        assertThat(channels.attemptedIds(NotificationChannel.EMAIL))
                .as("every attempt at the same notification is the same message")
                .hasSize(properties.delivery().maxAttempts())
                .containsOnly(rowId);
    }

    /**
     * The bound on retrying, and the one dead letter that should page somebody.
     *
     * <p>§4.10 puts "payment failed" in bold; this is the path on which such a message is
     * lost, so it has to end somewhere a person can find rather than in a retry loop that
     * spends a request per tick for ever.
     */
    @Test
    @DisplayName("a channel that keeps refusing becomes a dead letter carrying the last error")
    void aChannelThatKeepsRefusingBecomesADeadLetterCarryingTheLastError() {
        confirm();
        Channels channels = Channels.refusing(NotificationChannel.EMAIL);

        Instant at = now;
        for (int attempt = 1; attempt <= properties.delivery().maxAttempts(); attempt++) {
            sender.sendPending(at, channels.map());
            at = at.plus(properties.delivery().backoffAfter(attempt)).plus(Duration.ofSeconds(1));
        }

        assertThat(stateOf(NotificationChannel.EMAIL)).isEqualTo("DEAD");
        assertThat(attemptsOf(NotificationChannel.EMAIL)).isEqualTo(properties.delivery().maxAttempts());
        assertThat(lastErrorOf(NotificationChannel.EMAIL))
                .as("the dead letter says which channel said no and what it said")
                .contains(Channels.REFUSAL);

        // And it stops being work. A dead letter that were still claimable would be the
        // retry loop the bound exists to end.
        assertThat(sender.sendPending(at, channels.map())).isZero();
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** Records a {@code pledge.confirmed} and fans it out, leaving three PENDING rows. */
    private void confirm() {
        UUID pledgeId = UUID.randomUUID();
        PledgeConfirmed event = new PledgeConfirmed(
                pledgeId, projectId, backerId, Money.of(new BigDecimal("50.00"), "AZN"), now);
        new TransactionTemplate(transactions)
                .executeWithoutResult(status ->
                        outbox.record(AGGREGATE, pledgeId, PledgeConfirmed.EVENT_TYPE, event));
        relay.run();
        // After the fan-out, not before it. A row's first next_attempt_at is the instant
        // it was written, so a pass judged against an instant from before the write finds
        // nothing eligible and every assertion below reads zero for the wrong reason.
        now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    /** Passes until the queue stops yielding, so that a bounded pass is not a limit here. */
    private int drain(Channels channels) {
        int sent = 0;
        for (int pass = 0; pass < PLEDGE_CONFIRMED_CHANNELS; pass++) {
            sent += sender.sendPending(now, channels.map());
        }
        return sent;
    }

    private List<String> statesOf() {
        return jdbc().queryForList("SELECT state FROM notifications ORDER BY channel", String.class);
    }

    private String stateOf(NotificationChannel channel) {
        return columnFor(channel, "state", String.class);
    }

    private String lastErrorOf(NotificationChannel channel) {
        return columnFor(channel, "last_error", String.class);
    }

    private int attemptsOf(NotificationChannel channel) {
        return columnFor(channel, "attempts", Integer.class);
    }

    private UUID idOf(NotificationChannel channel) {
        return columnFor(channel, "id", UUID.class);
    }

    private <T> T columnFor(NotificationChannel channel, String column, Class<T> type) {
        return jdbc().queryForObject(
                "SELECT " + column + " FROM notifications WHERE channel = ?", type, channel.name());
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    /**
     * The channels a pass sends to, as a test controls them.
     *
     * <p>Passed to {@link NotificationSender#sendPending(Instant, Map)} rather than
     * replacing the {@code ChannelSender} beans, for the reason
     * {@code NotificationDispatch} gives: replacing a bean replaces it for the whole
     * suite and splits the context cache, which here would mean a second PostgreSQL
     * container to prove something about backoff.
     */
    private static final class Channels {

        /** What a refusing channel says, so that the dead letter can be checked for it. */
        static final String REFUSAL = "the transport is unreachable";

        private final Map<NotificationChannel, ChannelSender> map =
                new EnumMap<>(NotificationChannel.class);
        private final List<UUID> sent = new ArrayList<>();
        private final List<Attempt> attempts = new ArrayList<>();
        private NotificationChannel refused;

        private Channels(NotificationChannel refused) {
            this.refused = refused;
            for (NotificationChannel channel : NotificationChannel.values()) {
                map.put(channel, new Recording(channel));
            }
        }

        static Channels accepting() {
            return new Channels(null);
        }

        static Channels refusing(NotificationChannel channel) {
            return new Channels(channel);
        }

        /** Stops refusing, which is what a transport coming back up looks like. */
        void accept() {
            refused = null;
        }

        Map<NotificationChannel, ChannelSender> map() {
            return map;
        }

        /** The notifications a channel accepted, in order. */
        List<UUID> sent() {
            return List.copyOf(sent);
        }

        /** Every message handed to a channel, accepted or not. */
        List<UUID> attemptedIds(NotificationChannel channel) {
            return attempts.stream()
                    .filter(attempt -> attempt.channel() == channel)
                    .map(Attempt::id)
                    .toList();
        }

        private record Attempt(NotificationChannel channel, UUID id) {
        }

        private final class Recording implements ChannelSender {

            private final NotificationChannel channel;

            private Recording(NotificationChannel channel) {
                this.channel = channel;
            }

            @Override
            public NotificationChannel channel() {
                return channel;
            }

            @Override
            public void send(NotificationMessage message) {
                attempts.add(new Attempt(channel, message.id()));
                if (channel == refused) {
                    throw new IllegalStateException(REFUSAL);
                }
                sent.add(message.id());
            }
        }
    }
}
