package az.ideanest.compliance.api;

import az.ideanest.compliance.domain.DestinationVerificationMethod;
import az.ideanest.compliance.domain.PayoutDestination;
import az.ideanest.shared.compliance.DestinationStanding;
import az.ideanest.shared.compliance.RejectionReason;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Where a creator is paid, as the two screens that show it need it — part of issue #432. */
public final class PayoutDestinationResponses {

    private PayoutDestinationResponses() {
    }

    /**
     * The creator's own view.
     *
     * <p><strong>The token is not in it.</strong> Not because the creator may not know their
     * own account — they have it in front of them — but because a response carrying it puts it
     * in a browser cache, a proxy log and a bug report, and there is nothing the screen can do
     * with the value that {@code displayHint} does not do better.
     *
     * @param recorded whether anything is on file. A separate boolean rather than a null
     *     {@code standing}, so that the client's empty state is a field it reads rather than a
     *     null check it might forget
     * @param standing where this leaves a payout, override included — the same value the gate
     *     reads, so the screen cannot disagree with it
     * @param rejectionReason set only when a reviewer refused it, from V58's closed set so the
     *     client can translate it into each of §21.1's four languages
     */
    public record Mine(
            boolean recorded,
            DestinationStanding standing,
            String provider,
            String holderName,
            String displayHint,
            RejectionReason rejectionReason,
            Instant verifiedAt,
            Instant updatedAt) {

        public static Mine of(PayoutDestination row, DestinationStanding standing) {
            return new Mine(
                    true,
                    standing,
                    row.getProvider(),
                    row.getHolderName(),
                    row.getDisplayHint(),
                    row.rejection().orElse(null),
                    row.getVerifiedAt(),
                    row.getUpdatedAt());
        }

        /** Nothing on file. {@link DestinationStanding#NONE}, said explicitly. */
        public static Mine none() {
            return new Mine(false, DestinationStanding.NONE, null, null, null, null, null, null);
        }
    }

    /**
     * A reviewer's view of one creator's destination.
     *
     * <p>Carries {@code verifiedBy} and {@code verificationMethod}, which the creator's view
     * does not: the creator needs to know that their account was accepted, and a reviewer needs
     * to know who accepted it and on what evidence — which is the question #432 asks the method
     * column to answer years later.
     *
     * <p>Still no token. A screen that displayed it would make every reviewer's browser a place
     * one had been.
     */
    public record ForStaff(
            UUID creatorId,
            boolean recorded,
            DestinationStanding standing,
            String provider,
            String holderName,
            String displayHint,
            RejectionReason rejectionReason,
            DestinationVerificationMethod verificationMethod,
            UUID verifiedBy,
            Instant verifiedAt,
            Instant updatedAt) {

        public static ForStaff of(UUID creatorId, PayoutDestination row, DestinationStanding standing) {
            return new ForStaff(
                    creatorId,
                    true,
                    standing,
                    row.getProvider(),
                    row.getHolderName(),
                    row.getDisplayHint(),
                    row.rejection().orElse(null),
                    row.verifiedHow().orElse(null),
                    row.getVerifiedBy(),
                    row.getVerifiedAt(),
                    row.getUpdatedAt());
        }

        public static ForStaff none(UUID creatorId) {
            return new ForStaff(
                    creatorId, false, DestinationStanding.NONE, null, null, null, null, null, null, null, null);
        }
    }

    /** COMPLIANCE's queue of destinations waiting on somebody. */
    public record Queue(List<ForStaff> destinations) {
    }
}
