package az.ideanest.shared.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.OutputStreamAppender;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/**
 * {@code logback-spring.xml} as Spring Boot really reads it.
 *
 * <p>Asserted through a booted context rather than by inspecting the file,
 * because the failures worth catching are the ones the file cannot show: a class
 * name that no longer resolves, a profile expression that selects neither branch,
 * an encoder that refuses to start. Every one of those ships silently, and is
 * discovered as an absence of logs in production.
 *
 * <p>The context is deliberately almost empty — one configuration class, no web
 * environment, no auto-configuration — so this costs no database and asserts only
 * what it is about.
 *
 * <p>One test method rather than two, and in this order, because each boot
 * reconfigures the process-wide logger context. Ending on {@code test} leaves the
 * rest of the suite with the logging it started with.
 */
class LogbackConfigurationTests {

    private static final String SECRET = "nurlan@example.com";

    private static final String MARKER = "line-that-was-written";

    private static final String REQUEST_ID = "0192f0c1-8f3a-7c2b-9d4e-1a2b3c4d5e6f";

    @Configuration(proxyBeanMethods = false)
    static class NothingButLogging {
    }

    @Test
    @DisplayName("the format follows the profile and the redaction does not")
    void theFormatFollowsTheProfileAndRedactionDoesNot() {
        String deployed = underProfile("staging", "JSON");
        assertThat(deployed)
                .startsWith("{")
                .contains("\"loggerName\"")
                .contains(MARKER)
                .contains("\"" + Correlation.REQUEST_ID + "\"")
                .contains(REQUEST_ID)
                .doesNotContain(SECRET);

        String local = underProfile("test", "CONSOLE");
        assertThat(local)
                .doesNotStartWith("{")
                .contains(MARKER)
                // LOG_CORRELATION_PATTERN, resolved into Spring Boot's own console
                // format rather than replacing it.
                .contains(REQUEST_ID)
                .doesNotContain(SECRET);
    }

    /**
     * Boots the profile, finds the named root appender, and puts one event through
     * the encoder the configuration gave it.
     */
    private static String underProfile(String profile, String appenderName) {
        forgetPreviousInitialisation();
        ConfigurableApplicationContext context = new SpringApplicationBuilder(NothingButLogging.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .profiles(profile)
                .run();
        try {
            LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
            Appender<ILoggingEvent> appender =
                    loggerContext.getLogger(Logger.ROOT_LOGGER_NAME).getAppender(appenderName);

            assertThat(appender)
                    .withFailMessage("The %s profile selected no '%s' appender", profile, appenderName)
                    .isInstanceOf(OutputStreamAppender.class);
            assertThat(appender.isStarted())
                    .withFailMessage("The '%s' appender did not start, so nothing would be logged", appenderName)
                    .isTrue();

            OutputStreamAppender<ILoggingEvent> stream = (OutputStreamAppender<ILoggingEvent>) appender;
            assertThat(stream.getEncoder())
                    .withFailMessage("The '%s' encoder is not wrapped in redaction: it would write personal data",
                            appenderName)
                    .isInstanceOf(RedactingEncoder.class);

            return new String(stream.getEncoder().encode(event(loggerContext)), StandardCharsets.UTF_8);
        } finally {
            context.close();
        }
    }

    /**
     * Lets Spring Boot configure logging again.
     *
     * <p>The logger context belongs to the process, and Boot marks it once it has
     * configured it so that a second application in the same JVM does not fight
     * the first. Spring's test framework keeps every context it built open for the
     * whole run, so by the time this class runs the mark is usually already there
     * and the boot below would otherwise inherit whichever profile happened to go
     * first — which is how this test would pass or fail depending on test order.
     */
    private static void forgetPreviousInitialisation() {
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        loggerContext.removeObject(LoggingSystem.class.getName());
    }

    private static ILoggingEvent event(LoggerContext loggerContext) {
        Logger logger = loggerContext.getLogger("az.ideanest.pledge.application.PledgeService");
        LoggingEvent event =
                new LoggingEvent(Logger.class.getName(), logger, Level.INFO, MARKER + " for " + SECRET, null, null);
        event.setMDCPropertyMap(Map.of(
                Correlation.REQUEST_ID,
                REQUEST_ID,
                Correlation.TRACE_ID,
                "4bf92f3577b34da6a3ce929d0e0e4736",
                Correlation.SPAN_ID,
                "00f067aa0ba902b7"));
        return event;
    }
}
