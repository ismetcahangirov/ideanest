package az.ideanest.legal.api;

import az.ideanest.legal.application.AgreementSigning;
import az.ideanest.shared.legal.AgreementKind;
import az.ideanest.shared.signature.SignatureOnFile;
import java.time.Instant;
import java.util.UUID;

/** What the signing endpoints return — issue #429. */
public final class SignatureResponses {

    private SignatureResponses() {
    }

    /**
     * A session that has been opened.
     *
     * <p>{@code verificationCode} is shown to the citizen so they can compare it with the code
     * on their phone. It is not a secret and its whole job is to be read off two screens at
     * once.
     */
    public record SessionOpened(
            String document,
            int version,
            String sessionId,
            String verificationCode,
            Instant expiresAt) {

        public static SessionOpened of(AgreementKind kind, AgreementSigning.Started started) {
            return new SessionOpened(
                    kind.name(),
                    started.agreement().version(),
                    started.session().sessionId(),
                    started.session().verificationCode(),
                    started.session().expiresAt());
        }
    }

    /**
     * What became of a session.
     *
     * <p>{@code state} is the whole of the client's branch: {@code PENDING} polls again,
     * {@code SIGNED} moves on, and {@code CANCELLED} or {@code EXPIRED} offers to start over.
     * None of the three is an error, which is why they are a field rather than a status code.
     *
     * <p>The certificate's FİN is not returned. The name is, because the creator has to be able
     * to see whose certificate signed — that is the whole of the mismatch story from their side
     * — and the FİN adds nothing to that and is the more sensitive half of §17.4's pair.
     */
    public record SessionProgress(
            String document,
            int version,
            String state,
            boolean signed,
            UUID signatureId,
            String signerName,
            Instant signedAt) {

        public static SessionProgress of(AgreementKind kind, AgreementSigning.Resolution resolution) {
            SignatureOnFile signature = resolution.signature();
            return new SessionProgress(
                    kind.name(),
                    resolution.agreement().version(),
                    resolution.progress().state().name(),
                    resolution.isSigned(),
                    signature == null ? null : signature.signatureId(),
                    signature == null ? null : signature.signerName(),
                    signature == null ? null : signature.signedAt());
        }
    }

    /**
     * What this account has signed, for the agreement in force.
     *
     * <p>{@code documentHash} is returned deliberately. It is what was signed, and a creator who
     * wants to establish years later that the text in front of them is the text they signed can
     * hash it and compare — which is the property V65 stored the body for and the reason #429
     * takes the signature over a hash rather than a title.
     */
    public record MySignature(
            String document,
            boolean signed,
            int version,
            UUID documentId,
            UUID signatureId,
            String provider,
            String documentHash,
            String signerName,
            Instant signedAt,
            Instant acceptedAt) {

        public static MySignature unsigned(AgreementKind kind) {
            return new MySignature(kind.name(), false, 0, null, null, null, null, null, null, null);
        }

        public static MySignature of(AgreementSigning.SignedAgreement signed) {
            SignatureOnFile signature = signed.signature();
            return new MySignature(
                    signed.agreement().kind().name(),
                    true,
                    signed.agreement().version(),
                    signed.agreement().documentId(),
                    signature.signatureId(),
                    signature.provider(),
                    signature.documentHash(),
                    signature.signerName(),
                    signature.signedAt(),
                    signed.acceptedAt());
        }
    }
}
