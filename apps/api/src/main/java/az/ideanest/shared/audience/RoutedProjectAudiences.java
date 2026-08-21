package az.ideanest.shared.audience;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The one bean behind {@link ProjectAudiences}: it owns no rows and answers no question, it
 * only knows who does.
 *
 * <p>Every audience is claimed by exactly one {@link ProjectAudienceSource}, and this routes to
 * it. That indirection is what lets {@code ProjectAudience} name audiences from more than one
 * module — {@code BACKERS} from {@code pledges}, {@code SAVERS} and {@code FOLLOWERS} from
 * {@code saves} and {@code follows} — without any module reading another's tables and without
 * the notification module knowing there is more than one answerer.
 *
 * <h2>An unclaimed audience is a start-up failure, and that is the feature</h2>
 *
 * <p>{@code ProjectAudience} has always said that a constant nothing can answer is worse than
 * no constant, because a caller writes code against it. Until now that was a rule enforced by
 * whoever read the comment. It is now enforced by the constructor: an audience with no source
 * and an audience with two both refuse to start, by name, at deployment rather than at the
 * first event that asks for one.
 *
 * <p>Refusing the <em>second</em> claimant matters as much as refusing none. Two sources for
 * one audience is two modules that each believe they own it, and the failure it produces
 * without this check is not an error — it is half an audience, delivered quietly, decided by
 * bean ordering.
 *
 * <p>The cost is stated plainly: adding a constant to {@code ProjectAudience} breaks the
 * application until something claims it. That is the intended cost. The enum's own comment
 * calls adding a constant "a decision and a released implementation, in that order", and this
 * is the line that makes the order real.
 *
 * <h2>Why not a {@code Map} injected by Spring</h2>
 *
 * <p>Spring can inject a {@code Map<String, ProjectAudienceSource>} keyed by bean name, which
 * looks like this and is not: the keys would be bean names rather than audiences, nothing would
 * notice a missing one, and the failure for a duplicate would be a silent overwrite. The list
 * is injected and the map is built here so that both failures are checked rather than assumed.
 */
@Component
public class RoutedProjectAudiences implements ProjectAudiences {

    private final Map<ProjectAudience, ProjectAudienceSource> routes;

    public RoutedProjectAudiences(List<ProjectAudienceSource> sources) {
        Map<ProjectAudience, ProjectAudienceSource> routes = new EnumMap<>(ProjectAudience.class);

        for (ProjectAudienceSource source : sources) {
            if (source.answers().isEmpty()) {
                throw new IllegalStateException(
                        source.getClass().getName() + " is registered as an audience source and claims no audience");
            }
            for (ProjectAudience audience : source.answers()) {
                ProjectAudienceSource claimed = routes.putIfAbsent(audience, source);
                if (claimed != null) {
                    throw new IllegalStateException("The audience " + audience + " is claimed by both "
                            + claimed.getClass().getName() + " and " + source.getClass().getName()
                            + "; exactly one module owns the rows behind an audience");
                }
            }
        }

        List<ProjectAudience> unclaimed = new ArrayList<>();
        for (ProjectAudience audience : ProjectAudience.values()) {
            if (!routes.containsKey(audience)) {
                unclaimed.add(audience);
            }
        }
        if (!unclaimed.isEmpty()) {
            throw new IllegalStateException("No module answers the audience " + unclaimed
                    + "; a constant on ProjectAudience is a decision and a released implementation, in that order");
        }

        this.routes = Map.copyOf(routes);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The argument checks the interface specifies are made here, once, rather than in every
     * source: a null campaign is an empty audience and a limit below one is refused. A source
     * that repeats them is not wrong, and {@code PledgeProjectAudiences} still does — it was
     * written when it was the only implementation and the check is cheap — but nothing depends
     * on it any more.
     */
    @Override
    public List<UUID> membersOf(UUID projectId, ProjectAudience audience, int limit) {
        if (audience == null) {
            throw new IllegalArgumentException("An audience of nobody in particular is not a question");
        }
        if (projectId == null) {
            return List.of();
        }
        if (limit < 1) {
            throw new IllegalArgumentException("An audience of at most " + limit + " people is not a question");
        }
        // Never null: the constructor refused to build a router with a gap in it.
        return routes.get(audience).membersOf(projectId, audience, limit);
    }
}
