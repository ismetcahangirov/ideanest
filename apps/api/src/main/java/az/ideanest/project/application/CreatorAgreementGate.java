package az.ideanest.project.application;

import az.ideanest.project.domain.Project;
import az.ideanest.shared.legal.AgreementInForce;
import az.ideanest.shared.legal.AgreementKind;
import az.ideanest.shared.legal.Agreements;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Whether this campaign's creator has agreed to the terms they would be operating under —
 * issue #426.
 *
 * <h2>Why the gate is on submission</h2>
 *
 * <p>The same place §5.6's subscription gate sits, and §5.6's argument transfers exactly:
 *
 * <blockquote>
 * The gate sits on submission [...] and not on creation or on launch. A draft is private and
 * costs the platform nothing [...] Submission is the first moment a campaign costs the
 * platform something and the first moment it stops being private.
 * </blockquote>
 *
 * <p>It is also the first moment the creator takes on an obligation to anybody. Before
 * submission there are no backers to be obliged to, and an agreement demanded at account
 * creation is one accepted by somebody who was only looking around.
 *
 * <h2>The creator's acceptance, not the caller's</h2>
 *
 * <p>{@code PublishingGate} makes this argument for the subscription — #38 lets a
 * collaborator hold {@code SUBMIT_FOR_REVIEW}, and billing the helper would let a creator
 * publish free by asking a friend. Here it is stronger. A collaborator's acceptance would
 * let a creator take on <em>no obligations at all</em> by asking somebody else to press the
 * button, and would produce a campaign whose §5.5 obligations are owed by a person with no
 * control over it and no share of the money.
 *
 * <h2>A new version does not un-submit anything</h2>
 *
 * <p>Publishing a new creator agreement leaves live campaigns alone. It is required at the
 * creator's <em>next</em> submission, and that is a property of where the check is rather
 * than a rule written anywhere: nothing re-reads this for a campaign that is already
 * submitted. It is the shape §5.6 gives a plan limit — "a limit that moved [...] reaches
 * only their next submission" — and the shape V42 gives a retry window. A rule that reached
 * backwards would change what somebody agreed to after they agreed to it, which is the one
 * thing this epic exists to prevent.
 *
 * <h2>It lets everything through when nothing is published</h2>
 *
 * <p>{@code Agreements} carries the argument in full. In short: a legal gate that failed
 * closed would refuse every campaign on the platform with a message telling creators to
 * accept a document that does not exist, and the deployment where that happens is the one
 * where #439's text has not been seeded. So an agreement that exists must be accepted, and
 * an agreement that does not exist is not a requirement.
 */
@Service
public class CreatorAgreementGate {

    private static final Logger log = LoggerFactory.getLogger(CreatorAgreementGate.class);

    private final Agreements agreements;

    public CreatorAgreementGate(Agreements agreements) {
        this.agreements = agreements;
    }

    /**
     * Refuses unless this campaign's creator has accepted the creator agreement in force.
     *
     * <p>Read at the moment of submission rather than snapshotted, so a version published
     * this morning is the one this afternoon's submission is measured against. That is the
     * same "read live" choice V62 makes for a plan's limits and for the same reason: the
     * operator publishing a new version means it to apply, and a cached answer would apply
     * the old one until something evicted it.
     *
     * @throws AgreementRequiredException when a version is in force and the creator has not
     *     accepted it
     */
    @Transactional(readOnly = true)
    public void requireAccepted(Project project) {
        Optional<AgreementInForce> required = agreements.inForce(AgreementKind.CREATOR_AGREEMENT);
        if (required.isEmpty()) {
            // Nothing published, so nothing to accept. See the class comment.
            return;
        }

        AgreementInForce agreement = required.get();
        if (!agreements.hasAccepted(project.getCreatorId(), agreement)) {
            log.info(
                    "Campaign {} refused submission: creator {} has not accepted {} version {}",
                    project.getId(),
                    project.getCreatorId(),
                    agreement.kind(),
                    agreement.version());
            throw new AgreementRequiredException(project.getId(), agreement);
        }
    }
}
