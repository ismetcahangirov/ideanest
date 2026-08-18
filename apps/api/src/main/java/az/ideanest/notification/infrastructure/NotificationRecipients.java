package az.ideanest.notification.infrastructure;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Which of the people an event names this module is actually able to notify.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>{@code notifications.recipient_id} is {@code REFERENCES users (id)}, and until this
 * class existed nothing asked whether the identifier on an event satisfied it. The
 * fan-out took the recipient the translation handed it, built rows, and let the insert
 * find out. That is a bad place to find out, for a reason that has nothing to do with
 * notifications: {@code ApplicationEventOutboxDispatcher} publishes one message to every
 * listener inside one transaction, so an integrity error raised here does not fail this
 * module's work — it fails the dispatch, and every other consumer of that event loses
 * its writes along with us. A notification is not entitled to veto an event for the
 * modules that share it.
 *
 * <h2>Reading {@code users} from the notification module</h2>
 *
 * <p>Deliberate, and not a boundary violation. {@code ModuleBoundaryTests} forbids one
 * module's classes from reaching into another module's {@code domain} or
 * {@code infrastructure} packages; this file imports nothing from {@code az.ideanest.identity}
 * and asks a question in SQL. The dependency it expresses is one V26 already declares
 * twice in this module's own migration — {@code notifications.recipient_id} and
 * {@code notification_preferences.user_id} both reference {@code users} — so the table is
 * not somebody else's secret, it is the thing this module's schema is defined against.
 * {@code CollectionRepository.projectExists} reads {@code projects} from the discovery
 * module on the same reasoning.
 *
 * <p>Existence only, and pointedly not {@code deleted_at IS NULL}. A deleted account is
 * soft-deleted — V2 keeps the row so that financial records still refer to somebody —
 * which means it satisfies the foreign key and reaches the fan-out like any other
 * recipient. Whether a closed account should still be told things is a policy question
 * about {@code deleted_at}, {@code deletion_scheduled_at} and {@code anonymised_at} that
 * #85 has not answered and that this class must not answer by implication. It reports
 * what the constraint reports, and nothing further.
 */
@Repository
public class NotificationRecipients {

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationRecipients(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The subset of these identifiers that are accounts.
     *
     * <p>One query for the whole event rather than one per recipient, on the same
     * reasoning {@code NotificationFanOut} caches preferences: an event with several
     * recipients is rare, and paying a round trip each for the common single-recipient
     * case to handle it would be the wrong trade in the other direction.
     *
     * @param candidateIds who the translations named. Empty is allowed and asks nothing
     * @return those that exist, as a set. Never null; possibly empty, which is the
     *     answer the caller acts on rather than an error
     */
    public Set<UUID> existing(Collection<UUID> candidateIds) {
        if (candidateIds.isEmpty()) {
            // An IN () clause is not valid SQL, and the answer is knowable without asking.
            return Set.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("ids", candidateIds);
        return Set.copyOf(jdbc.queryForList("SELECT id FROM users WHERE id IN (:ids)", params, UUID.class));
    }
}
