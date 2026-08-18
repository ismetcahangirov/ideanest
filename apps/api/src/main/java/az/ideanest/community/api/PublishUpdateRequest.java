package az.ideanest.community.api;

import az.ideanest.community.application.NewUpdate;
import az.ideanest.community.domain.UpdateVisibility;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * {@code POST /v1/projects/{id}/updates}.
 *
 * <p><strong>The title and the body carry no bean-validation annotations, and that is
 * deliberate.</strong> Their rules live in {@code UpdateContent}, which the entity
 * calls on the way in, so there is exactly one statement of "what an update may say"
 * and it is the one a second write path would inherit. Annotating them here as well
 * would mean two messages for one rule and a race to see which fired first.
 *
 * <p>{@code visibility} is the exception, because {@code @NotNull} is the only way to
 * refuse an omitted enum before it reaches a constructor that would throw a
 * {@link NullPointerException} — and a required field is not a rule about content.
 *
 * @param visibility CD-12's "public or backers-only". <strong>Required, with no
 *     default.</strong> A default of {@code PUBLIC} would mean a client that forgot the
 *     field published to the whole internet something a creator wrote for their
 *     backers, and no default is worth that; a default of {@code BACKERS_ONLY} would
 *     quietly hide announcements the creator meant everybody to read
 * @param publishAt when the update becomes readable, ISO 8601 in UTC per §10.3. Omitted
 *     means now; a moment in the future is CD-12's scheduling
 */
public record PublishUpdateRequest(
        String title,
        String body,
        @NotNull(message = "Say whether this update is PUBLIC or BACKERS_ONLY") UpdateVisibility visibility,
        Instant publishAt) {

    /** The command the service takes, so that no field the client may not choose can arrive from here. */
    public NewUpdate toCommand() {
        return new NewUpdate(title, body, visibility, publishAt);
    }
}
