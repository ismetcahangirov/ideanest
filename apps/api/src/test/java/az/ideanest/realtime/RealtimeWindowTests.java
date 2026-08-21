package az.ideanest.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.realtime.application.ChannelWindow;
import az.ideanest.realtime.application.RealtimeAggregator;
import az.ideanest.realtime.domain.RealtimeChannel;
import az.ideanest.shared.money.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * §12.1's one-second window, which is the whole of what #91 asks for.
 *
 * <p>"On high-traffic projects the pledge counter is aggregated into one-second windows before
 * broadcast, rather than emitting an event per pledge." What that has to mean, and what these
 * tests hold it to:
 *
 * <ul>
 *   <li>{@link #fortyPledgesInOneWindowAreOneMessage()} — the feature. Forty events, one message.
 *   <li>{@link #theAmountsAreAddedWithoutFloatingPoint()} — this is a money path, and the sum of
 *       forty pledges is somebody's total.
 *   <li>{@link #drainingESpursTheWindowRatherThanRepeatingIt()} — a window is taken once, so a
 *       reader is never told about the same pledge twice.
 *   <li>{@link #concurrentRecordsAreNotLost()} — the relay dispatches on more than one thread,
 *       and a counter that lost updates under load would lose them on exactly the campaigns this
 *       exists for.
 *   <li>{@link #campaignsDoNotShareAWindow()} — the buffer is keyed by channel, so a busy
 *       campaign cannot put its pledges into a quiet one's message.
 * </ul>
 *
 * <p>Deliberately a plain unit test: the aggregator holds a map and a clock it does not read, so
 * a container would prove nothing that this does not and would cost the suite a context.
 */
class RealtimeWindowTests {

    private static final String CURRENCY = "AZN";

    private RealtimeAggregator aggregator;
    private UUID projectId;
    private RealtimeChannel counter;
    private RealtimeChannel comments;

    @BeforeEach
    void anEmptyWindow() {
        aggregator = new RealtimeAggregator(RealtimeProperties.defaults());
        projectId = UUID.randomUUID();
        counter = new RealtimeChannel(RealtimeChannel.Kind.PROJECT, projectId);
        comments = new RealtimeChannel(RealtimeChannel.Kind.COMMENTS, projectId);
    }

    @Test
    @DisplayName("forty pledges in one window are one message")
    void fortyPledgesInOneWindowAreOneMessage() {
        for (int pledge = 0; pledge < 40; pledge++) {
            aggregator.record(counter, money("25.00"));
        }

        List<ChannelWindow> windows = aggregator.drain();

        assertThat(windows).as("forty events, one message").hasSize(1);
        assertThat(windows.get(0).pledges()).isEqualTo(40);
        assertThat(windows.get(0).channel()).isEqualTo("project:" + projectId);
    }

    /**
     * Money arithmetic, which on this platform is never floating point.
     *
     * <p>Three amounts whose sum a double gets wrong. {@code Money} is {@code BigDecimal}
     * underneath, so this passes for the right reason — and it is asserted here rather than
     * assumed because this is the one value in the module that a browser adds to a running
     * total.
     */
    @Test
    @DisplayName("the amounts in a window are added without floating point")
    void theAmountsAreAddedWithoutFloatingPoint() {
        aggregator.record(counter, money("0.10"));
        aggregator.record(counter, money("0.20"));
        aggregator.record(counter, money("0.30"));

        ChannelWindow window = aggregator.drain().get(0);

        assertThat(window.pledges()).isEqualTo(3);
        assertThat(window.amount()).isEqualTo(money("0.60"));
        assertThat(window.amount().amount())
                .as("0.1 + 0.2 + 0.3 is 0.6 exactly, which is not true of a double")
                .isEqualByComparingTo(new BigDecimal("0.60"));
    }

    @Test
    @DisplayName("a pledge with no amount still moves the count")
    void aPledgeWithNoAmountStillCounts() {
        aggregator.record(counter, null);

        ChannelWindow window = aggregator.drain().get(0);

        assertThat(window.pledges()).isEqualTo(1);
        assertThat(window.amount()).as("nothing to name a currency in").isNull();
    }

    @Test
    @DisplayName("draining empties the window rather than repeating it")
    void drainingESpursTheWindowRatherThanRepeatingIt() {
        aggregator.record(counter, money("25.00"));

        assertThat(aggregator.drain()).hasSize(1);
        assertThat(aggregator.drain())
                .as("a reader is never told about the same pledge twice")
                .isEmpty();
    }

    @Test
    @DisplayName("a quiet window produces no message at all")
    void aQuietWindowSaysNothing() {
        assertThat(aggregator.drain()).isEmpty();
        assertThat(aggregator.pendingChannels()).isZero();
    }

    @Test
    @DisplayName("two campaigns do not share a window")
    void campaignsDoNotShareAWindow() {
        RealtimeChannel other =
                new RealtimeChannel(RealtimeChannel.Kind.PROJECT, UUID.randomUUID());
        aggregator.record(counter, money("25.00"));
        aggregator.record(other, money("10.00"));

        List<ChannelWindow> windows = aggregator.drain();

        assertThat(windows).hasSize(2);
        assertThat(windows).allSatisfy(window -> assertThat(window.pledges()).isEqualTo(1));
    }

    @Test
    @DisplayName("a campaign's counter and its comments are different channels")
    void theCounterAndTheCommentsAreSeparate() {
        UUID newest = UUID.randomUUID();
        aggregator.record(counter, money("25.00"));
        aggregator.recordComment(comments, UUID.randomUUID());
        aggregator.recordComment(comments, newest);

        List<ChannelWindow> windows = aggregator.drain();

        assertThat(windows).hasSize(2);
        assertThat(windows)
                .filteredOn(window -> window.channel().endsWith(":comments"))
                .singleElement()
                .satisfies(window -> {
                    assertThat(window.comments()).isEqualTo(2);
                    assertThat(window.latestCommentId()).as("the newest, for a client to compare against").isEqualTo(newest);
                    assertThat(window.pledges()).isZero();
                });
    }

    /**
     * The relay dispatches on more than one thread, and the flush runs on another.
     *
     * <p>An aggregator that lost updates under concurrency would lose them on precisely the
     * campaigns this module exists for. Two hundred threads is far past anything real; what it
     * buys is that a lost update is very unlikely to survive the run rather than merely unlikely.
     */
    @Test
    @DisplayName("concurrent records are not lost")
    void concurrentRecordsAreNotLost() throws Exception {
        int writers = 200;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers);

        try (ExecutorService threads = Executors.newFixedThreadPool(16)) {
            for (int writer = 0; writer < writers; writer++) {
                threads.submit(() -> {
                    try {
                        start.await();
                        aggregator.record(counter, money("1.00"));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).as("every writer finished").isTrue();
        }

        ChannelWindow window = aggregator.drain().get(0);
        assertThat(window.pledges()).isEqualTo(writers);
        assertThat(window.amount()).isEqualTo(money("200.00"));
    }

    private static Money money(String amount) {
        return Money.of(new BigDecimal(amount), CURRENCY);
    }
}
