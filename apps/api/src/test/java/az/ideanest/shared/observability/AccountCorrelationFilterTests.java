package az.ideanest.shared.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** §18.1's {@code userId}, once there is one. */
class AccountCorrelationFilterTests {

    private static final String ACCOUNT = "0192f0c1-8f3a-7c2b-9d4e-1a2b3c4d5e6f";

    private final AccountCorrelationFilter filter = new AccountCorrelationFilter();

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureTheAppender() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logger = context.getLogger("az.ideanest.shared.observability.AccountCorrelationFilterTests");
        appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void releaseEverything() {
        logger.detachAppender(appender);
        appender.stop();
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    @DisplayName("an authenticated request names the account on every line")
    void namesTheAuthenticatedAccount() throws Exception {
        authenticate(new TestingAuthenticationToken(ACCOUNT, "n/a", List.of(new SimpleGrantedAuthority("ACTIVE"))));

        run();

        assertThat(emitted().getMDCPropertyMap()).containsEntry(Correlation.USER_ID, ACCOUNT);
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    @DisplayName("an anonymous request names nobody rather than naming anonymousUser")
    void namesNobodyWhenAnonymous() throws Exception {
        authenticate(new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        run();

        assertThat(emitted().getMDCPropertyMap()).doesNotContainKey(Correlation.USER_ID);
    }

    @Test
    @DisplayName("a subject that is not identifier-shaped is not written to the log")
    void refusesASubjectThatIsNotAnIdentifier() throws Exception {
        authenticate(new TestingAuthenticationToken(
                "admin\r\nWARN forged", "n/a", List.of(new SimpleGrantedAuthority("ACTIVE"))));

        run();

        assertThat(emitted().getMDCPropertyMap()).doesNotContainKey(Correlation.USER_ID);
    }

    private static void authenticate(Authentication authentication) {
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private void run() throws Exception {
        FilterChain chain = (request, response) -> logger.info("handling the request");
        filter.doFilter(new MockHttpServletRequest("GET", "/v1/pledges"), new MockHttpServletResponse(), chain);
    }

    private ILoggingEvent emitted() {
        assertThat(appender.list)
                .withFailMessage("Nothing was logged, so nothing below proves anything")
                .isNotEmpty();
        return appender.list.get(0);
    }
}
