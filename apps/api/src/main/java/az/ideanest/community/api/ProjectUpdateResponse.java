package az.ideanest.community.api;

import az.ideanest.community.domain.ProjectUpdate;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

/**
 * One update, as the Updates tab and the creator's own list read it.
 *
 * <p><strong>No identifier.</strong> §10.2 gives an update no endpoint of its own, so a
 * {@code UUID} here would be a handle to a resource that does not exist; what names an
 * update on this platform is its {@link #number}, which is also what a person says out
 * loud and what a link into the tab carries.
 *
 * <p><strong>No {@code scheduled} flag.</strong> {@link #publishedAt} in the future
 * <em>is</em> scheduled, and a second field saying so is a second thing that can
 * disagree with the first — it would have to be computed against a clock, and the
 * client already holds one.
 *
 * @param authorId who published it. Present so that #38's collaborators are
 *     distinguishable from the creator in a list; the name behind it is the profile
 *     endpoint's to serve, because a body that carried names would need a join per row
 *     and a decision about what a deleted account is called
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ProjectUpdateResponse(
        int number, String title, String body, String visibility, Instant publishedAt, UUID authorId) {

    public static ProjectUpdateResponse of(ProjectUpdate update) {
        return new ProjectUpdateResponse(
                update.getNumber(),
                update.getTitle(),
                update.getBody(),
                update.getVisibility().name(),
                update.getPublishedAt(),
                update.getAuthorId());
    }
}
