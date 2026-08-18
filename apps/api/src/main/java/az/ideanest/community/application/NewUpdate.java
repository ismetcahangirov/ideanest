package az.ideanest.community.application;

import az.ideanest.community.domain.UpdateVisibility;
import java.time.Instant;

/**
 * What a creator asked to publish.
 *
 * <p>A command rather than the request body, so that the service is testable without
 * an HTTP layer and so that a field the client may not choose — the author, the number
 * — has nowhere to arrive from. The author is the authenticated caller and is passed
 * separately for exactly that reason.
 *
 * @param title what the Updates tab lists and what §4.10's notification will use as a
 *     subject line
 * @param body the update itself, as prose. See V22 for why this is not a document
 * @param visibility CD-12's "public or backers-only"
 * @param publishAt when it becomes readable, or null for now. A moment in the future
 *     is CD-12's "scheduled", and it is the only thing scheduling consists of
 */
public record NewUpdate(String title, String body, UpdateVisibility visibility, Instant publishAt) {
}
