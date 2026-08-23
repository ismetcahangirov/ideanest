package az.ideanest.community.application;

import java.util.UUID;

/**
 * No such FAQ entry — and also: an entry on a campaign this caller has no part in.
 *
 * <p><strong>Deliberately the same answer for both</strong>, exactly as
 * {@code ItemNotFoundException} and {@code RewardNotFoundException} are. Without the
 * translation, a stranger would get {@code PROJECT_NOT_FOUND} for a real entry and
 * {@code FAQ_NOT_FOUND} for an invented one, which is an oracle for whether an
 * identifier is real — asked from a flat path that names no campaign, so the caller does
 * not even have to know whose campaign they are guessing at.
 *
 * <p>Answered as 404. A collaborator who <em>is</em> party to the campaign and lacks
 * {@code MANAGE_FAQ} gets 403 instead: they were invited, they can already see the
 * campaign, and there is nothing left to hide from them.
 */
public class FaqNotFoundException extends RuntimeException {

    public FaqNotFoundException(UUID faqId) {
        super("No FAQ entry " + faqId);
    }
}
