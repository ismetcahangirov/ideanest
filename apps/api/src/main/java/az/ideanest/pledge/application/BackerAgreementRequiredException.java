package az.ideanest.pledge.application;

import az.ideanest.shared.legal.AgreementInForce;
import java.util.UUID;

/**
 * A pledge could not be confirmed because the backer agreement was not acknowledged —
 * issue #427.
 *
 * <p><strong>§22.3 requires that "rewards are not guaranteed" is stated within the pledge
 * flow</strong> — not in the terms, not on a linked page. Half of that already existed: the
 * catalogues carry the sentence, and {@code wording.test.ts} pins it. What did not exist was
 * <em>the record that anybody saw it</em>, and this is the refusal that makes the record
 * unavoidable.
 *
 * <p><strong>Why the version is in the request at all.</strong> The client could send
 * nothing and the server could record the version in force, which would be simpler and
 * would record a lie: it would say the backer acknowledged whatever was current at the
 * instant the request arrived, including a version published while the checkout page was
 * open. Sending the version back is the client saying <em>which text it showed</em>, and a
 * mismatch is precisely the case worth refusing — the page is stale, and the answer is to
 * reload it and read the new sentence.
 *
 * <p><strong>409, not 403.</strong> The backer is permitted to confirm this pledge; what is
 * wrong is the state of the page they are confirming from. That is the same distinction
 * {@code RESERVATION_EXPIRED} draws, and the same recovery: reload, look again, confirm.
 *
 * @see az.ideanest.shared.legal.Agreements on why no published agreement means no
 *     requirement, rather than every confirmation on the platform being refused
 */
public class BackerAgreementRequiredException extends RuntimeException {

    private final UUID pledgeId;
    private final AgreementInForce agreement;
    private final Integer offered;

    public BackerAgreementRequiredException(UUID pledgeId, AgreementInForce agreement, Integer offered) {
        super("Pledge %s cannot be confirmed: %s version %d has to be acknowledged, and the request %s"
                .formatted(
                        pledgeId,
                        agreement.kind(),
                        agreement.version(),
                        offered == null ? "acknowledged nothing" : "acknowledged version " + offered));
        this.pledgeId = pledgeId;
        this.agreement = agreement;
        this.offered = offered;
    }

    public UUID pledgeId() {
        return pledgeId;
    }

    public AgreementInForce agreement() {
        return agreement;
    }

    /** What the client said it showed, or null when it said nothing. */
    public Integer offered() {
        return offered;
    }
}
