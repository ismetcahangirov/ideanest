package az.ideanest.pledge.domain;

import java.util.Set;

/**
 * §6.2's pledge state machine, as a vocabulary.
 *
 * <p><strong>The transitions are not here.</strong> This issue (#51) owns two of
 * them — nothing to {@link #DRAFT}, and {@link #DRAFT} to {@link #EXPIRED} when
 * the reservation lapses — and the other ten belong to confirmation (#52),
 * collection, and the pledge manager. A half-written transition table that
 * silently permits the states nobody has implemented yet would be worse than none,
 * because it would look like the rule.
 *
 * <p>Stored as text rather than as a PostgreSQL enum type, like every other state
 * column in the schema: adding a value to an enum type is a migration that cannot
 * run inside a transaction on older servers, and the check constraint gives the
 * same refusal without that.
 */
public enum PledgeState {

    /**
     * A reservation. Stock is held, nothing has been charged, and the place goes
     * back when {@code reservation_expires_at} passes.
     */
    DRAFT,

    /** The card was verified and voided (§4.5). The backer is committed; no money has moved. */
    CONFIRMED,

    /** The reservation TTL elapsed before the backer confirmed. The place was released. */
    EXPIRED,

    CANCELED_BY_BACKER,

    CANCELED_BY_PROJECT,

    /** The campaign succeeded and this pledge is queued for collection (§5.1). */
    CHARGE_PENDING,

    COLLECTED,

    CHARGE_FAILED,

    /** The seven-day retry window of §5.1 elapsed without a successful charge. */
    DROPPED,

    REFUNDED,

    CHARGEBACK,

    FULFILLED;

    /**
     * The states in which a backer counts as having pledged to a campaign.
     *
     * <p><strong>The same set as {@code pledges_project_backer_active_key}</strong>,
     * which is the partial unique index enforcing §7.2's "one pledge per backer per
     * project". Duplicated here on purpose and not derived from anything: the
     * database is where the rule is enforced, and this is how the application can
     * say why a request was refused before the index refuses it. V17 carries the
     * argument for which six are in and which six are out.
     *
     * <p>The two lists disagreeing is the failure mode worth naming, and
     * {@code PledgeSchemaTests} is what stops it: it asserts the index against this
     * set rather than against a copy of it.
     */
    public static final Set<PledgeState> ACTIVE =
            Set.of(DRAFT, CONFIRMED, CHARGE_PENDING, CHARGE_FAILED, COLLECTED, FULFILLED);

    /** Whether a pledge in this state still stands between its backer and a second one. */
    public boolean isActive() {
        return ACTIVE.contains(this);
    }
}
