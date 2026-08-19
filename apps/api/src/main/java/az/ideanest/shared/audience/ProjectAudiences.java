package az.ideanest.shared.audience;

import java.util.List;
import java.util.UUID;

/**
 * "Who are the people in this group on this campaign", asked from outside the module that knows.
 *
 * <p><strong>One method, on purpose.</strong> {@code ProjectAuthorisation} makes the argument in
 * full for the capability question and every word of it applies: naming the group as a value
 * costs one enum and answers every question at once, where a method per audience is a published
 * surface that grows without bound.
 *
 * <p><strong>It decides nothing.</strong> The implementation lives in the module that owns the
 * rows — the pledge module for {@link ProjectAudience#BACKERS} — and this interface exists so
 * that the notification module depends on the question rather than on {@code pledges}.
 *
 * <h2>The bound is a parameter, and it has to be</h2>
 *
 * <p>An audience is unbounded in the data: a successful campaign has as many backers as it has,
 * and "goal reached" is precisely the event where that number is largest. So a method that
 * answered with all of them would be one that loads an arbitrary list into memory and hands it
 * to a fan-out that writes a row per member per channel, inside the outbox dispatch transaction
 * — which is one transaction whose size is decided by how well a campaign did.
 *
 * <p>The bound is the caller's rather than the implementation's, because only the caller knows
 * what it can do with the answer. The notification module's is
 * {@code ideanest.notification.audience.max-recipients}.
 *
 * <p><strong>Truncation is detectable, and detecting it is the caller's job.</strong> This method
 * returns at most {@code limit} members and says nothing about whether there were more; a caller
 * that needs to know asks for one more than it can use and compares. That is deliberately not
 * hidden behind a flag: an audience silently cut short is a set of people who were not told
 * something, and the module that decides to accept that has to be the one that says so.
 */
public interface ProjectAudiences {

    /**
     * The members of this audience on this campaign.
     *
     * @param projectId the campaign. <strong>A campaign that does not exist is an empty
     *     audience, not an error.</strong> Callers here are consuming events, and an event about
     *     a campaign that has since been removed must not be able to fail a dispatch that other
     *     modules share — {@code NotificationFanOut} makes that argument at length about a
     *     recipient who is not an account
     * @param audience which group
     * @param limit the most the caller can use, at least one. Refused rather than clamped when
     *     it is not positive: a limit of zero asks for an audience of nobody, which is a caller
     *     bug rather than an instruction
     * @return the members, distinct, in a stable order, at most {@code limit} of them. Never
     *     null; possibly empty, which is an ordinary answer — a campaign nobody has backed has
     *     no backers
     */
    List<UUID> membersOf(UUID projectId, ProjectAudience audience, int limit);
}
