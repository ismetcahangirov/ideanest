package az.ideanest.compliance.api;

import az.ideanest.compliance.application.CreatorLegalSubjects;
import az.ideanest.compliance.domain.CampaignLegalSubject;
import az.ideanest.shared.compliance.LegalSubject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** What the legal-subject endpoints return — issue #430. */
public final class LegalSubjectResponses {

    private LegalSubjectResponses() {
    }

    /**
     * A creator's own subject.
     *
     * <p>{@code recorded} is false rather than the whole body being absent, so that the
     * settings screen has one shape to render and does not have to distinguish "no subject" from
     * "endpoint failed". {@code complete} is computed here rather than in the client, for
     * {@code LegalSubject.isComplete()}'s reason: a second copy of the rule is the copy that
     * gets a case wrong.
     */
    public record Mine(
            boolean recorded,
            String subjectKind,
            String legalName,
            String taxId,
            String registeredAddress,
            String registrationNumber,
            boolean complete,
            Instant updatedAt) {

        public static Mine none() {
            return new Mine(false, null, null, null, null, null, false, null);
        }

        public static Mine of(LegalSubject subject, Instant updatedAt) {
            return new Mine(
                    true,
                    subject.subjectKind().name(),
                    subject.legalName(),
                    subject.taxId(),
                    subject.registeredAddress(),
                    subject.registrationNumber(),
                    subject.isComplete(),
                    updatedAt);
        }
    }

    /**
     * What the console draws on the account screen: the live subject and every campaign frozen
     * against it.
     */
    public record ForStaff(UUID accountId, Mine recorded, List<FrozenCampaign> campaigns) {

        public static ForStaff of(UUID accountId, CreatorLegalSubjects.StaffView view) {
            Mine recorded = view.subject()
                    .map(row -> Mine.of(row.asLegalSubject(), row.getUpdatedAt()))
                    .orElseGet(Mine::none);
            return new ForStaff(
                    accountId,
                    recorded,
                    view.campaigns().stream().map(FrozenCampaign::of).toList());
        }
    }

    /**
     * One campaign's frozen subject.
     *
     * <p>Drawn beside the live one, because the comparison is the reason the screen exists: a
     * funded campaign submitted by an individual whose account now says company is not an
     * error, and it is the thing a finance operator must see before approving a payout.
     *
     * <p>The address and the registration number are not returned. The kind, the name and the
     * VOEN are what a payout decision turns on; an address on a list screen is three lines of
     * somebody's company details rendered for every row, which 17.4's minimisation asks the
     * screen not to do when nothing reads them.
     */
    public record FrozenCampaign(
            UUID projectId,
            String subjectKind,
            String legalName,
            String taxId,
            Instant frozenAt) {

        public static FrozenCampaign of(CampaignLegalSubject snapshot) {
            LegalSubject subject = snapshot.asLegalSubject();
            return new FrozenCampaign(
                    snapshot.getProjectId(),
                    subject.subjectKind().name(),
                    subject.legalName(),
                    subject.taxId(),
                    snapshot.getFrozenAt());
        }
    }
}
