package az.ideanest.legal.application;

import az.ideanest.legal.domain.DocumentKind;
import java.time.Instant;

/**
 * A version was to start governing before it was published — issue #425.
 *
 * <p>Backdating is the one direction that is refused. A version dated in the future is
 * useful and is the reason {@code effective_from} is a column at all: a change to the
 * creator agreement that everybody should be told about a fortnight before it bites is a
 * publication now with a later date. A version dated in the past would claim to have bound
 * people at a time when the platform could not have shown it to them, which is precisely
 * what this epic exists to prevent.
 */
public class EffectiveDateInThePastException extends RuntimeException {

    private final DocumentKind kind;
    private final Instant effectiveFrom;
    private final Instant now;

    public EffectiveDateInThePastException(DocumentKind kind, Instant effectiveFrom, Instant now) {
        super("%s cannot start governing at %s, which has already passed".formatted(kind, effectiveFrom));
        this.kind = kind;
        this.effectiveFrom = effectiveFrom;
        this.now = now;
    }

    public DocumentKind kind() {
        return kind;
    }

    public Instant effectiveFrom() {
        return effectiveFrom;
    }

    public Instant now() {
        return now;
    }
}
