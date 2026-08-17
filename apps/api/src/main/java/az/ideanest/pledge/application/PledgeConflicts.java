package az.ideanest.pledge.application;

import az.ideanest.pledge.domain.Pledge;
import az.ideanest.pledge.domain.PledgeState;
import az.ideanest.pledge.infrastructure.PledgeRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the pledge that refused somebody else's insert, from outside the
 * transaction that failed because of it.
 *
 * <p><strong>This exists for one moment.</strong> When two checkouts by one backer
 * race past {@link ReservationService}'s read, the loser's insert is refused by
 * {@code pledges_project_backer_active_key}, and §10.4's answer to that carries the
 * pledge the backer already has — the client's next move is to open it rather than
 * to start a third checkout. But a transaction that has hit a constraint violation
 * is doomed: PostgreSQL will accept no further statement on it, so the refusal
 * cannot be answered from inside the transaction the refusal happened in.
 *
 * <p><strong>{@code REQUIRES_NEW}, therefore</strong>, which suspends the doomed
 * transaction and reads on a connection of its own. What it finds is guaranteed to
 * be there: the row that refused the insert is, by definition, one another
 * transaction had already committed — under READ COMMITTED the insert blocks until
 * that commit and only then discovers the conflict.
 *
 * <p>A separate bean rather than a method on {@link ReservationService} because
 * Spring's {@code @Transactional} is a proxy and a self-call carries no transaction
 * at all — which here would mean the read joining the very transaction it exists to
 * avoid, and failing.
 */
@Component
public class PledgeConflicts {

    private final PledgeRepository pledges;

    public PledgeConflicts(PledgeRepository pledges) {
        this.pledges = pledges;
    }

    /**
     * The live pledge this backer already has on this campaign, if it is still live.
     *
     * <p>The same question {@code ReservationService} asks before it starts, and
     * deliberately the same query: the two answers must agree, because the refusal
     * a backer sees must not depend on which of the two paths refused them.
     *
     * @return empty when the pledge that won the race has stopped being active in
     *     the moment since — a state in which "you already have a pledge" would no
     *     longer be true. The caller does not invent an answer for that
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<Pledge> activePledgeOf(UUID projectId, UUID backerId) {
        return pledges.findActive(projectId, backerId, PledgeState.ACTIVE);
    }
}
