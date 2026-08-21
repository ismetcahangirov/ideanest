package az.ideanest.community.infrastructure;

import az.ideanest.shared.audience.ProjectAudience;
import az.ideanest.shared.audience.ProjectAudienceSource;
import az.ideanest.shared.project.ProjectSummaries;
import az.ideanest.shared.project.ProjectSummary;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * Who saved a campaign and who follows the person running it, published so that other modules
 * need not read {@code saves} or {@code follows}.
 *
 * <p>The community module's half of #245, and the half that could not be written until #90
 * created the rows. Before this existed, §4.10's "followed creator launched" and "saved project
 * ending soon" had copy, had channels, had a preference category — and no audience, so nothing
 * could ever send them. {@code ProjectAudience} said so in its own comment rather than pretending
 * otherwise, which is why the constants were absent rather than unimplemented.
 *
 * <h2>{@code FOLLOWERS} is a creator's audience reached through a campaign</h2>
 *
 * <p>{@code ProjectAudiences} is keyed on a campaign because every audience it serves is one
 * somebody wants to notify <em>about</em> a campaign. A follow is not about a campaign, so this
 * source has to get from one to the other, and it does it by asking
 * {@link ProjectSummaries} who the campaign belongs to — the published port, not
 * {@code projects}, which is the project module's table and not this module's to read.
 *
 * <p><strong>A campaign whose creator cannot be resolved is an empty audience, not a
 * failure.</strong> Same rule as everywhere else on this path, and
 * {@code NotificationFanOut} makes the argument in full: this answer is consumed inside an
 * outbox dispatch that several modules share, so a fault here would roll back their writes over
 * a condition no redelivery can fix.
 *
 * <h2>Repositories rather than SQL</h2>
 *
 * <p>Unlike {@code PledgeProjectAudiences}, which drops to {@code NamedParameterJdbcTemplate}
 * because loading a pledge aggregate per backer would put fifty columns through Hibernate to
 * read one. A {@code Save} is four columns and a {@code Follow} is four, both already have a
 * repository, and both queries below select one column rather than an entity — so the reason
 * that class had to reach past its repository does not apply and inventing a second data-access
 * style for the sake of matching would be the wrong kind of consistency.
 */
@Component
public class CommunityProjectAudiences implements ProjectAudienceSource {

    private final SaveRepository saves;
    private final FollowRepository follows;
    private final ProjectSummaries campaigns;

    public CommunityProjectAudiences(SaveRepository saves, FollowRepository follows, ProjectSummaries campaigns) {
        this.saves = saves;
        this.follows = follows;
        this.campaigns = campaigns;
    }

    @Override
    public Set<ProjectAudience> answers() {
        return Set.of(ProjectAudience.SAVERS, ProjectAudience.FOLLOWERS);
    }

    @Override
    public List<UUID> membersOf(UUID projectId, ProjectAudience audience, int limit) {
        PageRequest bound = PageRequest.ofSize(limit);

        return switch (audience) {
            case SAVERS -> saves.saverIds(projectId, bound);
            case FOLLOWERS -> campaigns.summaryOf(projectId)
                    .map(ProjectSummary::creatorId)
                    // A summary with no creator is the invariant violation `hasPublicPath`
                    // defends against elsewhere; here it means there is nobody to have
                    // followers, which is an empty audience.
                    .map(creatorId -> follows.followerIds(creatorId, bound))
                    .orElseGet(List::of);
            // Unreachable through the router, which only asks a source for what it claimed.
            // It throws rather than answering emptily for `PledgeProjectAudiences`' reason:
            // "nobody saved this" is an answer and "the community module was asked who backed
            // a campaign" is a wiring fault an empty list would hide.
            case BACKERS -> throw new IllegalArgumentException(
                    "The community module does not own the " + audience + " audience");
        };
    }
}
