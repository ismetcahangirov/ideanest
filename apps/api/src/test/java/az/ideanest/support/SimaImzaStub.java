package az.ideanest.support;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SİMA İmza, as far as a signing session is concerned — issue #429's fixture.
 *
 * <p>{@code OidcProviderStub}'s argument, unchanged. The suite never reaches SİMA: a test that
 * called somebody else's sandbox would fail for reasons that are not ours, on their schedule,
 * and could not produce the cases that matter — a citizen who cancels, a certificate naming
 * somebody else, and a signature over a version that is no longer in force.
 *
 * <p>What is real here is everything between the platform and the wire.
 * {@code SimaImzaSignatureProvider} is the adapter under test in
 * {@code SimaImzaSignatureProviderTests}; here it is the adapter <em>in use</em>, so the whole
 * of #429's path — session row, hash comparison, name match, acceptance — runs against HTTP
 * rather than against a fake provider bean. It also keeps
 * {@code SignatureProviderBoundaryTests}' rule honest: a test double implementing
 * {@code SignatureProvider} would be a second implementation of the interface the rule exists
 * to keep singular.
 *
 * <p>One server for the whole suite, on a port the operating system picks, registered by
 * {@code AbstractIntegrationTest} — a per-class stub would need a per-class property source,
 * which splits the context cache and starts a second PostgreSQL container.
 */
public final class SimaImzaStub {

    /** A real-shaped detached signature. Bounded, because V67's column is. */
    private static final String SIGNATURE_VALUE = "MIIB".repeat(8);

    private static final WireMockServer SERVER;

    private static final AtomicInteger SESSIONS = new AtomicInteger();

    static {
        // HTTP/1.1 only. The application places outbound calls over the JDK client
        // (`HttpClientTransportTests`), which negotiates h2c on a plaintext connection;
        // WireMock's Jetty answers the upgrade and then cancels the stream, and the adapter
        // reports a provider that could not be reached. Turning the upgrade off is the fix
        // rather than changing the application's transport to suit a stub.
        SERVER = new WireMockServer(WireMockConfiguration.options().dynamicPort().http2PlainDisabled(true));
        SERVER.start();
    }

    private SimaImzaStub() {
    }

    public static String baseUrl() {
        return SERVER.baseUrl();
    }

    /**
     * The next {@code begin} answers with a fresh session, and {@code resolve} on it says what
     * this call was told to say.
     *
     * <p>Returns the session identifier, so a test can begin through the API and then decide
     * what the citizen did. The order matters: the platform's session row is written by the
     * {@code begin}, and a stub that answered a resolve for a session nobody began would be
     * testing a path the application cannot reach.
     */
    public static String willBeginSession() {
        String sessionId = "sima-session-" + SESSIONS.incrementAndGet();
        SERVER.stubFor(WireMock.post(WireMock.urlEqualTo("/sign/sessions"))
                .willReturn(WireMock.okJson(
                        """
                        {"sessionId":"%s","verificationCode":"7391","expiresAt":"%s"}
                        """
                                .formatted(sessionId, Instant.now().plusSeconds(300)))));
        return sessionId;
    }

    /** That session comes back signed, over {@code hash}, by a certificate naming {@code name}. */
    public static void willResolveSigned(String sessionId, String hash, String name, String fin) {
        SERVER.stubFor(WireMock.get(WireMock.urlEqualTo("/sign/sessions/" + sessionId))
                .willReturn(WireMock.okJson(
                        """
                        {"status":"SIGNED","signature":"%s","hash":"%s",
                         "certificateSubject":"CN=%s, SERIALNUMBER=%s, C=AZ",
                         "subjectName":"%s","subjectFin":"%s","signedAt":"%s"}
                        """
                                .formatted(
                                        SIGNATURE_VALUE,
                                        hash,
                                        name,
                                        fin,
                                        name,
                                        fin,
                                        Instant.now()))));
    }

    /** The citizen declined. An outcome, not a failure — §9.4's distinction, which #428 keeps. */
    public static void willResolveCancelled(String sessionId) {
        SERVER.stubFor(WireMock.get(WireMock.urlEqualTo("/sign/sessions/" + sessionId))
                .willReturn(WireMock.okJson(
                        """
                        {"status":"USER_CANCELLED","detail":"The citizen declined."}
                        """)));
    }

    /** Nothing has happened yet. The client polls again. */
    public static void willResolvePending(String sessionId) {
        SERVER.stubFor(WireMock.get(WireMock.urlEqualTo("/sign/sessions/" + sessionId))
                .willReturn(WireMock.okJson("""
                        {"status":"PENDING"}
                        """)));
    }

    /** Forgets every stub, so one test's scripted citizen is not the next test's. */
    public static void reset() {
        SERVER.resetAll();
    }
}
