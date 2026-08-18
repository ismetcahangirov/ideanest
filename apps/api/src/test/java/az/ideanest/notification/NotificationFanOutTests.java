package az.ideanest.notification;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.notification.application.NotificationEvents.PledgeConfirmed;
import az.ideanest.shared.money.Money;
import az.ideanest.shared.outbox.Outbox;
import az.ideanest.shared.outbox.OutboxRelay;
import az.ideanest.support.AbstractIntegrationTest;
import az.ideanest.support.Campaigns;
import java.math.BigDecimal;
import java.time.Instant;
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
 * Who gets told, when the event names somebody the platform has never heard of.
 *
 * <p>Driven through the real outbox rather than by calling {@code NotificationFanOut},
 * for {@code ReferralAttributionTests}' reason and for one more specific to this suite:
 * the behaviour under test is partly about what happens to <em>the delivery</em>, and a
 * direct call to the fan-out has no delivery to observe. The event is recorded the way a
 * producer will record it, {@code OutboxRelay} is run once by hand, and the assertions
 * are made on the two tables that result.
 *
 * <p><strong>These tests exist because this module broke another one's.</strong> The
 * fan-out took the recipient a translation handed it and let
 * {@code notifications_recipient_id_fkey} discover that no such account existed — inside
 * the dispatch transaction, which meant the analytics module's attributions rolled back
 * with it. {@link #anEventNamingSomebodyWhoIsNotAnAccountDoesNotFailTheDelivery()} is
 * the regression, and it asserts on the outbox row rather than only on the absence of
 * notifications, because "nobody was told" and "the event was destroyed on the way past"
 * are different outcomes and only one of them is acceptable.
 */
class NotificationFanOutTests extends AbstractIntegrationTest {

    /** Distinguishes the accounts these tests create. See {@code ReferralAttributionTests}. */
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static final String AGGREGATE = "pledge";

    /** §4.10 gives {@code PLEDGE_CONFIRMED} email, push and in-app. */
    private static final int PLEDGE_CONFIRMED_CHANNELS = 3;

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

    @BeforeEach
    void anAccountThatExists() {
        String handle = "fanout-" + SEQUENCE.incrementAndGet();
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

    @Test
    @DisplayName("a pledge confirmed for a real account is fanned out to every channel of its type")
    void aKnownRecipientIsTold() {
        confirm(backerId);

        assertThat(notificationsFor(backerId))
                .as("one notification per channel §4.10 gives PLEDGE_CONFIRMED")
                .hasSize(PLEDGE_CONFIRMED_CHANNELS);
    }

    /**
     * The regression.
     *
     * <p>Two assertions, and the second is the one that matters. Writing no notification
     * is the visible half; leaving the delivery intact is the half that broke somebody
     * else. The dispatcher publishes one message to every listener in one transaction, so
     * a fan-out that throws here does not merely fail itself — it rolls back the dispatch
     * and every other consumer's work with it, and after eight attempts dead-letters an
     * event that was never malformed.
     */
    @Test
    @DisplayName("an event naming somebody who is not an account tells nobody and does not fail the delivery")
    void anEventNamingSomebodyWhoIsNotAnAccountDoesNotFailTheDelivery() {
        UUID strangerId = UUID.randomUUID();

        confirm(strangerId);

        assertThat(notificationsFor(strangerId))
                .as("there is nobody to notify, so nothing is written")
                .isEmpty();
        assertThat(outboxStates())
                .as("the delivery still succeeded: this module does not get to veto the event")
                .containsExactly("PUBLISHED");
    }

    /**
     * One unknown recipient does not silence the event for the people who do exist.
     *
     * <p>Recorded as two events rather than one with two recipients, because nothing
     * publishes a multi-recipient event yet — {@code NotificationEventListener} says so
     * and says why. What is being checked is that the skip is per recipient rather than
     * per delivery, which is the same property either way round.
     */
    @Test
    @DisplayName("a stranger on one event does not stop the next event reaching a real account")
    void theSkipIsPerRecipient() {
        confirm(UUID.randomUUID());
        confirm(backerId);

        assertThat(notificationsFor(backerId))
                .as("the account that exists is told, as though the other event never happened")
                .hasSize(PLEDGE_CONFIRMED_CHANNELS);
        assertThat(outboxStates())
                .as("both deliveries succeeded")
                .containsExactly("PUBLISHED", "PUBLISHED");
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** Records a {@code pledge.confirmed} and drains the outbox, as production will. */
    private void confirm(UUID recipientId) {
        UUID pledgeId = UUID.randomUUID();
        PledgeConfirmed event = new PledgeConfirmed(
                pledgeId,
                projectId,
                recipientId,
                Money.of(new BigDecimal("50.00"), "AZN"),
                Instant.now());
        new TransactionTemplate(transactions)
                .executeWithoutResult(status ->
                        outbox.record(AGGREGATE, pledgeId, PledgeConfirmed.EVENT_TYPE, event));
        relay.run();
    }

    private List<String> notificationsFor(UUID recipientId) {
        return new JdbcTemplate(dataSource)
                .queryForList(
                        "SELECT channel FROM notifications WHERE recipient_id = ? ORDER BY channel",
                        String.class,
                        recipientId);
    }

    private List<String> outboxStates() {
        return new JdbcTemplate(dataSource)
                .queryForList("SELECT state FROM outbox_events ORDER BY sequence_no", String.class);
    }
}
