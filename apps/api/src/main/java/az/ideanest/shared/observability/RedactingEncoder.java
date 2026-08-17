package az.ideanest.shared.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.encoder.EncoderBase;
import java.nio.charset.StandardCharsets;

/**
 * The choke point. Wraps whichever encoder an appender uses and masks the bytes
 * it produced.
 *
 * <p>Placed here rather than at the call site on purpose. Redaction that depends
 * on every {@code log.info} remembering to call a helper is redaction that holds
 * until somebody logs a request object to find out what is in it — and the whole
 * of §17.4 then rests on a habit. Here there is one place to get right, and it is
 * downstream of everything: the message, its arguments, the MDC, the exception
 * message, and every frame of the stack trace are already one string by the time
 * this sees them.
 *
 * <p>It also means the choice of format is independent of the choice to redact.
 * The console encoder locally and the JSON encoder everywhere else are both
 * wrapped by this, and neither knows.
 *
 * <p>The cost is a regular expression pass per line, which is paid on the
 * appender's thread rather than the caller's. If that ever shows up in a profile
 * the answer is an async appender in front of it, not a shorter list of rules.
 */
public class RedactingEncoder extends EncoderBase<ILoggingEvent> {

    private Encoder<ILoggingEvent> delegate;

    /** The encoder that decides the format. Configured as a nested element. */
    public void setEncoder(Encoder<ILoggingEvent> encoder) {
        this.delegate = encoder;
    }

    @Override
    public void start() {
        if (delegate == null) {
            // Refusing to start is the point: an appender with no encoder writes
            // nothing, which is noticed. An appender that quietly wrote
            // unredacted lines would not be.
            addError("A RedactingEncoder needs a nested <encoder> to wrap. Nothing will be logged by this appender.");
            return;
        }
        if (delegate.getContext() == null) {
            delegate.setContext(getContext());
        }
        if (!delegate.isStarted()) {
            delegate.start();
        }
        super.start();
    }

    @Override
    public void stop() {
        if (delegate != null && delegate.isStarted()) {
            delegate.stop();
        }
        super.stop();
    }

    @Override
    public byte[] headerBytes() {
        return redact(delegate.headerBytes());
    }

    @Override
    public byte[] encode(ILoggingEvent event) {
        return redact(delegate.encode(event));
    }

    @Override
    public byte[] footerBytes() {
        return redact(delegate.footerBytes());
    }

    private static byte[] redact(byte[] encoded) {
        if (encoded == null || encoded.length == 0) {
            return encoded;
        }
        String written = new String(encoded, StandardCharsets.UTF_8);
        String masked = Redaction.redact(written);
        // Identity, not equality: the common case is that nothing matched, and
        // there is no reason to re-encode a string that did not change.
        return masked == written ? encoded : masked.getBytes(StandardCharsets.UTF_8);
    }
}
