package az.ideanest.shared.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Work handed to another thread keeps its correlation, or is given one.
 *
 * <p>Both halves matter. An {@code @Async} call started inside a request belongs
 * to that request and has to say so; a {@code @Scheduled} job has no request at
 * all, and a line with no identifier is a line nobody can join to anything.
 */
class CorrelationTaskDecoratorTests {

    private final CorrelationTaskDecorator decorator = new CorrelationTaskDecorator();

    private ExecutorService pool;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void startPoolAndCaptureAppender() {
        // One thread, reused, which is what makes leaking across tasks visible.
        pool = Executors.newSingleThreadExecutor();
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logger = context.getLogger("az.ideanest.shared.observability.CorrelationTaskDecoratorTests");
        appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void stopPoolAndReleaseAppender() throws InterruptedException {
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        logger.detachAppender(appender);
        appender.stop();
        MDC.clear();
    }

    @Test
    @DisplayName("the submitting thread's correlation reaches the worker thread")
    void propagatesTheSubmittersCorrelation() throws Exception {
        MDC.put(Correlation.REQUEST_ID, "0192f0c1-8f3a-7c2b-9d4e-1a2b3c4d5e6f");
        MDC.put(Correlation.TRACE_ID, "4bf92f3577b34da6a3ce929d0e0e4736");

        run(() -> logger.info("sending the receipt"));

        Map<String, String> mdc = emitted(0).getMDCPropertyMap();
        assertThat(mdc)
                .containsEntry(Correlation.REQUEST_ID, "0192f0c1-8f3a-7c2b-9d4e-1a2b3c4d5e6f")
                .containsEntry(Correlation.TRACE_ID, "4bf92f3577b34da6a3ce929d0e0e4736");
    }

    @Test
    @DisplayName("a task submitted with no correlation is given one")
    void mintsACorrelationForScheduledWork() throws Exception {
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();

        run(() -> logger.info("sweeping lapsed reservations"));

        Map<String, String> mdc = emitted(0).getMDCPropertyMap();
        assertThat(mdc.get(Correlation.REQUEST_ID)).isNotBlank();
        assertThat(mdc.get(Correlation.TRACE_ID)).matches("[0-9a-f]{32}");
        assertThat(mdc.get(Correlation.SPAN_ID)).matches("[0-9a-f]{16}");
    }

    @Test
    @DisplayName("one task's correlation does not leak into the next task on the same thread")
    void doesNotLeakBetweenTasks() throws Exception {
        MDC.put(Correlation.REQUEST_ID, "0192f0c1-8f3a-7c2b-9d4e-1a2b3c4d5e6f");
        run(() -> logger.info("first task"));
        MDC.clear();

        run(() -> logger.info("second task"));

        assertThat(emitted(1).getMDCPropertyMap().get(Correlation.REQUEST_ID))
                .isNotEqualTo("0192f0c1-8f3a-7c2b-9d4e-1a2b3c4d5e6f");
    }

    private void run(Runnable work) throws Exception {
        pool.submit(decorator.decorate(work)).get(5, TimeUnit.SECONDS);
    }

    private ILoggingEvent emitted(int index) {
        assertThat(appender.list)
                .withFailMessage("Nothing was logged, so nothing below proves anything")
                .hasSizeGreaterThan(index);
        return appender.list.get(index);
    }
}
