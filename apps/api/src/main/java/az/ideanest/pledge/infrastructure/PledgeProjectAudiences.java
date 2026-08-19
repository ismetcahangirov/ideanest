package az.ideanest.pledge.infrastructure;

import az.ideanest.shared.audience.ProjectAudience;
import az.ideanest.shared.audience.ProjectAudiences;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Who has backed a campaign, published so that other modules need not read {@code pledges}.
 *
 * <p>The pledge module's half of #245. {@code ProjectAudiences} is the question and this is the
 * only class that answers it, because {@code pledges} is this module's table and
 * {@code ModuleBoundaryTests} forbids anybody else from naming it. Before this existed, §4.10's
 * "goal reached" notified the creator and nobody else — the least useful half of that event,
 * since the people who funded the campaign heard nothing.
 *
 * <h2>Which states are backers, and why it is not the active set</h2>
 *
 * <p>V17 defines an <em>active</em> pledge as one of six states, and this is five of them:
 * {@code CONFIRMED}, {@code CHARGE_PENDING}, {@code CHARGE_FAILED}, {@code COLLECTED},
 * {@code FULFILLED}.
 *
 * <p><strong>{@code DRAFT} is in V17's active set and is deliberately not a backer.</strong> The
 * two definitions are answering different questions. V17's exists to stop one person holding two
 * places on a limited tier, so a checkout in progress has to count. This one decides who gets
 * told the campaign reached its goal, and somebody who opened a checkout and did not finish it
 * has not backed anything — telling them would be a message about a commitment they never made,
 * and on a "campaign succeeded" notification it would be a message implying their card is about
 * to be charged.
 *
 * <p>The six states V17 leaves out are left out here for its reason and it is the same one: the
 * commitment has ended. A lapsed reservation, either cancellation, a dropped charge, a refund and
 * a chargeback are all people who are no longer backing this campaign, and a notification about
 * its progress is not theirs.
 *
 * <h2>SQL rather than the repository</h2>
 *
 * <p>{@code PledgeRepository} maps entities and this needs one column from up to thousands of
 * rows. Loading an aggregate per backer to read its {@code backer_id} would be the version of
 * this that is correct and unusable on the campaign where it matters most.
 *
 * <p>Ordered by the identifier so that the answer is <em>stable</em>, which is the property the
 * interface promises and the only one it promises: the caller's bound can cut the list short, and
 * a truncation that returned a different subset on every call would mean a retried event told a
 * different set of people. Note that stable is not the same as ascending in
 * {@code UUID.compareTo}'s ordering — PostgreSQL compares a {@code uuid} by its bytes and Java
 * compares it as two signed longs, so the two disagree on about half of all pairs. Nothing here
 * or above depends on which; a caller that started to would be depending on the storage engine.
 *
 * <h2>{@code @Component} rather than {@code @Repository}</h2>
 *
 * <p>Which is unlike {@code NotificationRecipients} beside it, and the reason is the argument
 * check below. Spring translates exceptions out of a {@code @Repository} bean, and it translates
 * an {@code IllegalArgumentException} into {@code InvalidDataAccessApiUsageException} — so a
 * caller that asked for an audience of nobody would be handed a {@code DataAccessException}
 * saying the database refused something it was never shown. The stereotype buys nothing else
 * here: this class throws no persistence exception worth translating, because it runs one
 * {@code SELECT} that either works or is an outage.
 */
@Component
public class PledgeProjectAudiences implements ProjectAudiences {

    /**
     * §6.2's states in which a pledge is somebody backing this campaign.
     *
     * <p>Inlined into the statement rather than bound, because they are a constant of this class
     * and not an input — and because an {@code IN} list of literals is what lets the planner use
     * {@code pledges_project_backer_active_key}.
     */
    private static final String BACKING_STATES =
            "'CONFIRMED', 'CHARGE_PENDING', 'CHARGE_FAILED', 'COLLECTED', 'FULFILLED'";

    /**
     * Distinct, although the unique index very nearly guarantees it.
     *
     * <p>{@code pledges_project_backer_active_key} is unique on (project, backer) over the active
     * states, and every state above is active, so there is at most one row per backer today.
     * {@code DISTINCT} is here because that index is a decision about checkout rather than a
     * promise to this query: the day a state moves out of the active set, this would start
     * returning somebody twice and the fan-out would meet
     * {@code notifications_event_recipient_channel_key} instead of a duplicate being impossible.
     */
    private static final String BACKERS_OF = "SELECT DISTINCT backer_id FROM pledges"
            + " WHERE project_id = :projectId AND state IN (" + BACKING_STATES + ")"
            + " ORDER BY backer_id LIMIT :limit";

    private final NamedParameterJdbcTemplate jdbc;

    public PledgeProjectAudiences(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<UUID> membersOf(UUID projectId, ProjectAudience audience, int limit) {
        if (projectId == null) {
            // An empty audience rather than a failure, for the reason the interface gives: the
            // caller is consuming an event shared with other modules, and it must not be able
            // to fail their dispatch over a field it cannot use.
            return List.of();
        }
        if (limit < 1) {
            throw new IllegalArgumentException("An audience of at most " + limit + " people is not a question");
        }

        return switch (audience) {
            case BACKERS -> jdbc.queryForList(
                    BACKERS_OF,
                    new MapSqlParameterSource().addValue("projectId", projectId).addValue("limit", limit),
                    UUID.class);
        };
    }
}
