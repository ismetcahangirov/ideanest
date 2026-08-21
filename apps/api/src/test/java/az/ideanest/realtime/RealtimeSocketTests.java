package az.ideanest.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.realtime.application.RealtimeBroadcaster;
import az.ideanest.realtime.application.RealtimeFlusher;
import az.ideanest.shared.money.Money;
import az.ideanest.shared.outbox.Outbox;
import az.ideanest.shared.outbox.OutboxRelay;
import az.ideanest.support.AbstractIntegrationTest;
import az.ideanest.support.Campaigns;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * §12.1 end to end: a real socket, a real event, and the window in between.
 *
 * <p>{@code RealtimeWindowTests} asserts the aggregation on its own, without a container, because
 * that is where the logic is. This suite asserts the parts that only exist when the application
 * is running — that the endpoint is reachable without a credential, that an unserved channel is
 * closed rather than registered, and that a pledge recorded through the outbox reaches a browser
 * as one message after the window closes.
 *
 * <p>The flush is driven directly rather than waited for: the test profile sets
 * {@code flush-schedule: "-"} so the timer never fires, which is what makes these deterministic
 * rather than a suite that sleeps for a second and fails on a loaded runner.
 */
class RealtimeSocketTests extends AbstractIntegrationTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static final String PLEDGE_CONFIRMED = "pledge.confirmed";

    @LocalServerPort
    private int port;

    @Autowired
    private RealtimeFlusher flusher;

    @Autowired
    private RealtimeBroadcaster broadcaster;

    @Autowired
    private Outbox outbox;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private DataSource dataSource;

    private UUID creatorId;
    private UUID projectId;

    @BeforeEach
    void aLiveCampaign() {
        String handle = "socket-" + SEQUENCE.incrementAndGet();
        creatorId = Campaigns.creator(dataSource, handle);
        projectId = Campaigns.seed(dataSource, creatorId, handle).state("LIVE").insert();
    }

    @AfterEach
    void clear() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM project_state_transitions WHERE project_id = ?", projectId);
        jdbc.update("DELETE FROM projects WHERE id = ?", projectId);
        // The window is process state and the next test starts from empty.
        flusher.flush();
    }

    @Test
    @DisplayName("a reader with no credential can watch a public campaign's counter")
    void aReaderNeedsNoCredential() throws Exception {
        Recorder recorder = new Recorder(1);

        try (WebSocketSession session = connect("project:" + projectId, recorder)) {
            assertThat(session.isOpen()).isTrue();
            assertThat(broadcaster.subscribers("project:" + projectId)).isEqualTo(1);
        }
    }

    /**
     * The feature: several pledges, one message, after the window closes.
     *
     * <p>Two pledges are recorded through the real path — the outbox, the relay, the listener —
     * and nothing reaches the socket until {@link RealtimeFlusher#flush()} runs. That ordering is
     * the point of the module: the listener runs inside the dispatch transaction, and a
     * broadcast from there would be telling a reader about a pledge that could still roll back.
     */
    @Test
    @DisplayName("several pledges in a window arrive as one message when it closes")
    void pledgesArriveAsOneMessage() throws Exception {
        Recorder recorder = new Recorder(1);

        try (WebSocketSession session = connect("project:" + projectId, recorder)) {
            confirmPledge("25.00");
            confirmPledge("15.50");

            assertThat(recorder.received())
                    .as("nothing is pushed from inside the dispatch transaction")
                    .isEmpty();

            flusher.flush();

            assertThat(recorder.awaitOne()).as("the window closed and spoke once").isTrue();
            assertThat(recorder.received()).hasSize(1);
            assertThat(recorder.received().get(0))
                    .contains("\"pledges\":2")
                    // §10.3: money crosses as a string, never a JSON number. Asserted on the wire
                    // because this value is added to a running total in a browser.
                    .contains("\"amount\":{\"amount\":\"40.50\"")
                    .contains("\"currency\":\"AZN\"")
                    .contains("project:" + projectId);
            assertThat(session.isOpen()).isTrue();
        }
    }

    @Test
    @DisplayName("a reader watching one campaign is not told about another")
    void aReaderIsNotToldAboutAnotherCampaign() throws Exception {
        UUID other = Campaigns.seed(dataSource, creatorId, "socket-other-" + SEQUENCE.incrementAndGet())
                .state("LIVE")
                .insert();
        Recorder recorder = new Recorder(1);

        try (WebSocketSession watching = connect("project:" + projectId, recorder)) {
            confirmPledge(other, "25.00");
            flusher.flush();

            assertThat(watching.isOpen()).isTrue();
            assertThat(recorder.received()).isEmpty();
        } finally {
            new JdbcTemplate(dataSource).update("DELETE FROM projects WHERE id = ?", other);
        }
    }

    /**
     * A channel this server does not serve is closed, not registered.
     *
     * <p>{@code user:{id}} is the one that matters: §12.1 gives it a person's own notifications,
     * and this endpoint has no credential to check. {@code RealtimeChannel} refuses it by having
     * no constant for it, and this asserts the refusal reaches the wire.
     */
    @Test
    @DisplayName("an unserved channel is closed rather than registered")
    void anUnservedChannelIsClosed() throws Exception {
        Recorder recorder = new Recorder(1);

        WebSocketSession session = connect("user:" + creatorId, recorder);
        assertThat(recorder.awaitClose()).as("the server closed it").isTrue();
        assertThat(broadcaster.subscribers("user:" + creatorId)).isZero();
        session.close();
    }

    @Test
    @DisplayName("a closed socket stops being a subscriber")
    void aClosedSocketIsForgotten() throws Exception {
        String channel = "project:" + projectId;
        Recorder recorder = new Recorder(1);

        WebSocketSession session = connect(channel, recorder);
        assertThat(broadcaster.subscribers(channel)).isEqualTo(1);
        session.close();

        // The close is asynchronous on the server side, so this waits for the deregistration
        // rather than asserting immediately -- a sleep would be the version of this that fails
        // once a fortnight on a loaded runner.
        for (int attempt = 0; attempt < 100 && broadcaster.subscribers(channel) > 0; attempt++) {
            Thread.sleep(20);
        }
        assertThat(broadcaster.subscribers(channel)).isZero();
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private WebSocketSession connect(String channel, Recorder recorder) throws Exception {
        URI uri = URI.create("ws://localhost:" + port + "/v1/realtime?channel="
                + java.net.URLEncoder.encode(channel, java.nio.charset.StandardCharsets.UTF_8));
        return new StandardWebSocketClient()
                .execute(recorder, new WebSocketHttpHeaders(), uri)
                .get(10, TimeUnit.SECONDS);
    }

    private void confirmPledge(String amount) {
        confirmPledge(projectId, amount);
    }

    /** Records a {@code pledge.confirmed} through the outbox and lets the relay dispatch it. */
    private void confirmPledge(UUID campaign, String amount) {
        PledgeConfirmedPayload event = new PledgeConfirmedPayload(
                UUID.randomUUID(),
                creatorId,
                campaign,
                Money.of(new BigDecimal(amount), "AZN"),
                Instant.now().truncatedTo(ChronoUnit.MICROS));
        new TransactionTemplate(transactions)
                .executeWithoutResult(status -> outbox.record("pledge", campaign, PLEDGE_CONFIRMED, event));
        relay.run();
    }

    /** The producer's field names, which are the contract this module reads. */
    private record PledgeConfirmedPayload(
            UUID pledgeId, UUID backerId, UUID projectId, Money total, Instant confirmedAt) {
    }

    /** A client that keeps what it was sent, and can be waited on. */
    private static final class Recorder extends TextWebSocketHandler {

        private final List<String> messages = new CopyOnWriteArrayList<>();
        private final CountDownLatch received;
        private final CountDownLatch closed = new CountDownLatch(1);

        private Recorder(int expected) {
            this.received = new CountDownLatch(expected);
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            messages.add(message.getPayload());
            received.countDown();
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closed.countDown();
        }

        List<String> received() {
            return List.copyOf(messages);
        }

        boolean awaitOne() throws InterruptedException {
            return received.await(10, TimeUnit.SECONDS);
        }

        boolean awaitClose() throws InterruptedException {
            return closed.await(10, TimeUnit.SECONDS);
        }
    }
}
