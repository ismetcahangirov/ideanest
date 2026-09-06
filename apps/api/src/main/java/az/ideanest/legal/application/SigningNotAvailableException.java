package az.ideanest.legal.application;

import az.ideanest.shared.legal.AgreementKind;

/**
 * Nothing can be signed on this deployment yet — issue #429.
 *
 * <p>The state the platform is deliberately in: {@code SignatureProviders} refuses to point at
 * production SİMA until #423 answers which personal data from a certificate may be kept and for
 * how long, and with no provider configured there is nothing to sign with.
 *
 * <p>Distinct from every other refusal here because nothing the creator does changes it. A
 * screen meeting this says the platform is not ready, not that the creator did something wrong.
 */
public class SigningNotAvailableException extends RuntimeException {

    private final transient AgreementKind kind;

    public SigningNotAvailableException(AgreementKind kind) {
        super("No signature provider is configured, so " + kind + " cannot be signed");
        this.kind = kind;
    }

    public AgreementKind kind() {
        return kind;
    }
}
