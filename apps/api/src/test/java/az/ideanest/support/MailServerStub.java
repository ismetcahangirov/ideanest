package az.ideanest.support;

import com.icegreen.greenmail.store.FolderException;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;

/**
 * A real SMTP server, in the test process.
 *
 * <p>Not a mocked {@code JavaMailSender}. A mock proves that the code called
 * {@code send}, which is the one thing about an email transport nobody doubts; what is
 * worth asserting is what came out — that the subject is right, that both parts of the
 * {@code multipart/alternative} are there, that the {@code Message-ID} is the one derived
 * from the notification. Those are facts about bytes on a socket, and only a server can
 * answer them. It is the same argument {@code ContainersConfiguration} makes for running
 * a real PostgreSQL rather than an in-memory substitute.
 *
 * <p><strong>One server for the whole suite, on a port the operating system picks.</strong>
 * {@code OidcProviderStub} is the same shape for the same two reasons: a fixed port is a
 * test that fails on whichever machine already has something listening there, and a
 * per-class server would need a per-class {@code @DynamicPropertySource}, which splits
 * Spring's context cache and starts a second PostgreSQL container. The port is registered
 * once, in {@code AbstractIntegrationTest}.
 *
 * <p>Because it is shared, <strong>a test that reads the mailbox clears it first</strong>.
 * {@link #clear()} is that, and it is the caller's to remember: the alternative is a
 * cleanup hook on the base class, which would run for the hundreds of tests that never
 * send anything.
 */
public final class MailServerStub {

    /**
     * How long to wait for a message to arrive.
     *
     * <p>Generous, because it is a ceiling on a failure rather than a delay in the happy
     * path — GreenMail returns as soon as the message lands, which is milliseconds when
     * the send worked. A short timeout here is how a suite becomes flaky on a loaded CI
     * runner.
     */
    private static final Duration ARRIVAL = Duration.ofSeconds(10);

    private static final GreenMail SERVER = start();

    private MailServerStub() {
    }

    private static GreenMail start() {
        // dynamicPort() rather than ServerSetupTest's fixed 3025, for the reason in the
        // class comment. Read back through getSmtp().getPort() once it is bound.
        GreenMail server = new GreenMail(ServerSetup.SMTP.dynamicPort());
        server.start();
        // The suite does not stop it. The process ends when the suite does, and a shutdown
        // hook racing Spring's context close is a source of failures that only appear in
        // CI.
        return server;
    }

    /** Where {@code spring.mail.port} points. */
    public static int smtpPort() {
        return SERVER.getSmtp().getPort();
    }

    /**
     * Where {@code spring.mail.host} points, and why it is not {@code localhost}.
     *
     * <p>GreenMail binds to the loopback address. On a machine where {@code localhost}
     * resolves to {@code ::1} first — which is the default on Windows — a client that was
     * told "localhost" tries IPv6, finds nothing listening, and only then falls back to
     * IPv4. With the short connect timeout a test profile wants, that fallback does not
     * finish and every send is recorded as a refusal by an unreachable relay.
     *
     * <p>Asking the server what it bound to removes the resolution step entirely.
     */
    public static String smtpHost() {
        return SERVER.getSmtp().getBindTo();
    }

    /**
     * Empties the mailbox. Call it before the act, not after.
     *
     * <p><strong>Not {@code reset()}</strong>, which is the obvious method and is wrong
     * here: it stops and restarts the services, and with a dynamic port that means
     * binding a <em>different</em> one. Spring resolved {@code spring.mail.port} once, at
     * context startup, so after the first reset the application is talking to a port
     * nothing is listening on any more — and every send is recorded as a refusal by an
     * unreachable relay, which is exactly what it looks like when the transport is
     * broken. This purges the messages and leaves the sockets alone.
     */
    public static void clear() {
        try {
            SERVER.purgeEmailFromAllMailboxes();
        } catch (FolderException unreadable) {
            throw new IllegalStateException("The test mailbox could not be emptied", unreadable);
        }
    }

    /**
     * Waits for exactly {@code count} messages and returns them.
     *
     * @throws AssertionError if they do not arrive. Deliberately not "returns what has
     *     arrived so far": a test asserting on an empty array reads as a passing
     *     assertion about a message nobody sent
     */
    public static MimeMessage[] awaitMessages(int count) {
        if (!SERVER.waitForIncomingEmail(ARRIVAL.toMillis(), count)) {
            throw new AssertionError("Expected %d message(s) within %s, and %d arrived"
                    .formatted(count, ARRIVAL, SERVER.getReceivedMessages().length));
        }
        return SERVER.getReceivedMessages();
    }

    /** The one message this test expected. */
    public static MimeMessage awaitOne() {
        return awaitMessages(1)[0];
    }

    /** What is in the mailbox right now, without waiting. */
    public static MimeMessage[] received() {
        return SERVER.getReceivedMessages();
    }
}
