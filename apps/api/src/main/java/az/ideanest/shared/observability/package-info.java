/**
 * Structured logging: a correlation identifier on every line, and personal data
 * on none.
 *
 * <p>Two rules, and both of them are enforced by the pipeline rather than by the
 * call site.
 *
 * <p><strong>Correlation.</strong> {@link az.ideanest.shared.observability.CorrelationFilter}
 * accepts the caller's {@code X-Request-Id} and {@code traceparent} when they are
 * well formed, mints them when they are not, and publishes them to the MDC for
 * the duration of the request. Every appender therefore writes them, whether or
 * not the code that logged remembered they exist.
 * {@link az.ideanest.shared.observability.CorrelationTaskDecorator} carries the
 * same values onto pooled worker threads, and mints a fresh set for work that
 * began on a timer rather than in a request.
 *
 * <p><strong>Redaction.</strong>
 * {@link az.ideanest.shared.observability.RedactingEncoder} wraps whichever
 * encoder an appender uses and masks the bytes it produced, so the message, the
 * MDC, the exception message and the stack trace all pass through
 * {@link az.ideanest.shared.observability.Redaction} on their way out. A call
 * site that logs a whole request object, or an exception whose message quotes
 * one, is covered by construction; nothing has to be remembered.
 * {@link az.ideanest.shared.observability.LogFields} exists for the other
 * direction — building a line out of values that could not have been personal
 * data in the first place.
 *
 * <p>See {@code docs/architecture.md} §18.1 for the fields every line carries and
 * §17.4 for what may never be written.
 */
package az.ideanest.shared.observability;
