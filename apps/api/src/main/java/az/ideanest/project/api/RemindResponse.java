package az.ideanest.project.api;

import az.ideanest.project.application.PrelaunchService.ReminderRegistration;

/**
 * What the caller is told after asking to be reminded.
 *
 * <p><strong>It never says whether they were already on the list.</strong>
 * {@code ReminderRegistration#created} is known and is deliberately not published:
 * this endpoint takes an arbitrary address and needs no credential, so a response
 * that distinguished "added" from "already there" would answer the question "does
 * this address follow this campaign" for anybody who cared to ask. That is exactly
 * the fact §17.4 keeps private, and a boolean is a cheap way to give it away.
 *
 * <p>The consequence is that a repeat request looks identical to a first one,
 * which is also the correct thing for a client: both mean "you are on the list".
 *
 * @param following always true. Present so the client has something to key its
 *     success state on rather than inferring it from a 200 with an opaque body
 * @param followerCount after this call, so the page can update the number without
 *     a second request. It moves on a first registration and not on a repeat,
 *     which is a far weaker signal than a boolean would be — the count moves for
 *     everybody else's registrations too
 */
public record RemindResponse(boolean following, long followerCount) {

    public static RemindResponse of(ReminderRegistration registration) {
        return new RemindResponse(true, registration.followerCount());
    }
}
