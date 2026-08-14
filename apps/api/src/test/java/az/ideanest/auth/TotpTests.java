package az.ideanest.auth;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.auth.domain.Base32;
import az.ideanest.auth.domain.Totp;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one-time password implementation, against the specification it claims to
 * implement.
 *
 * <p>This is the file that earns the decision not to take a library. RFC 6238
 * publishes test vectors; if these pass, an authenticator app and this service
 * compute the same six digits, and if they do not, every enrolment produces a
 * lockout. Asserting "the code we generated verifies against the code we
 * generated" would pass with a completely wrong algorithm.
 *
 * <p>A plain unit test: no database, no container, no Spring.
 */
class TotpTests {

    /** The seed from RFC 6238 Appendix B: the ASCII digits 1 to 0, twice. */
    private static final byte[] RFC_SEED = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    @DisplayName("the RFC 6238 test vectors produce the codes the RFC says they do")
    void matchesTheSpecification() {
        // The RFC prints eight digits; six is the last six of them, because the
        // truncation is a modulo. Anything else here means an authenticator
        // would disagree with us, and the user is locked out with no way to
        // find out why.
        assertThat(codeAtEpochSecond(59)).isEqualTo("287082");
        assertThat(codeAtEpochSecond(1111111109)).isEqualTo("081804");
        assertThat(codeAtEpochSecond(1111111111)).isEqualTo("050471");
        assertThat(codeAtEpochSecond(1234567890)).isEqualTo("005924");
        assertThat(codeAtEpochSecond(2000000000)).isEqualTo("279037");
        assertThat(codeAtEpochSecond(20000000000L)).isEqualTo("353130");
    }

    @Test
    @DisplayName("base32 encodes the way RFC 4648 says, which is what the authenticator reads")
    void base32MatchesTheSpecification() {
        // The other half of the same guarantee: the digits can be right and the
        // secret still arrive in the app wrong.
        assertThat(Base32.encode("f".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MY");
        assertThat(Base32.encode("fo".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXQ");
        assertThat(Base32.encode("foo".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6");
        assertThat(Base32.encode("foobar".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTBOI");
        assertThat(Base32.encode(RFC_SEED)).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
    }

    @Test
    @DisplayName("a code from one step either side is accepted, and the step it came from is returned")
    void skewIsOneStepEachWay() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        long step = Totp.stepAt(now);

        // A phone twenty seconds out is ordinary; so is starting to type at the
        // end of a window. The caller is told which step matched because it has
        // to record it — that is what stops the code being used twice.
        assertThat(Totp.verify(RFC_SEED, Totp.codeAt(RFC_SEED, step - 1), now)).hasValue(step - 1);
        assertThat(Totp.verify(RFC_SEED, Totp.codeAt(RFC_SEED, step), now)).hasValue(step);
        assertThat(Totp.verify(RFC_SEED, Totp.codeAt(RFC_SEED, step + 1), now)).hasValue(step + 1);
    }

    @Test
    @DisplayName("a code from two steps away is refused")
    void skewStopsSomewhere() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        long step = Totp.stepAt(now);

        // A window that keeps widening is a code that keeps working. Two steps
        // is a broken clock rather than drift, and accepting it triples how long
        // a code read over somebody's shoulder is worth reading.
        assertThat(Totp.verify(RFC_SEED, Totp.codeAt(RFC_SEED, step - 2), now)).isEmpty();
        assertThat(Totp.verify(RFC_SEED, Totp.codeAt(RFC_SEED, step + 2), now)).isEmpty();
    }

    @Test
    @DisplayName("a code typed with a space is the same code")
    void spacingIsIgnored() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        String code = Totp.codeAt(RFC_SEED, Totp.stepAt(now));

        // Authenticators display "123 456" and people type what they see.
        assertThat(Totp.verify(RFC_SEED, code.substring(0, 3) + " " + code.substring(3), now))
                .isPresent();
    }

    @Test
    @DisplayName("something that is not six digits is refused rather than padded")
    void malformedCodesAreRefused() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);

        assertThat(Totp.verify(RFC_SEED, "", now)).isEmpty();
        assertThat(Totp.verify(RFC_SEED, "12345", now)).isEmpty();
        assertThat(Totp.verify(RFC_SEED, "1234567", now)).isEmpty();
        // Guessing on the user's behalf — stripping letters and hoping what is
        // left is the code — would make the input rules something nobody knows.
        assertThat(Totp.verify(RFC_SEED, "12a456", now)).isEmpty();
    }

    @Test
    @DisplayName("a generated secret is 160 bits and is not the same twice")
    void secretsAreRandomAndTheRightSize() {
        byte[] first = Totp.newSecret();
        byte[] second = Totp.newSecret();

        assertThat(first).hasSize(Totp.SECRET_BYTES);
        assertThat(first).isNotEqualTo(second);
    }

    private static String codeAtEpochSecond(long epochSecond) {
        return Totp.codeAt(RFC_SEED, Totp.stepAt(Instant.ofEpochSecond(epochSecond)));
    }
}
