package az.ideanest.project.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * What one account may do on one campaign, and what it may pass on.
 *
 * <p>A pure value with no Spring and no database in it, for the same reason
 * {@link ProjectStateMachine} is one: the rules below decide who can change a
 * campaign that takes money from the public, and a rule that can only be
 * exercised through an HTTP request and a PostgreSQL container is a rule nobody
 * tests the edges of. {@code ProjectAccess} is what decides <em>whether</em> to
 * refuse a caller and what to throw; this is the vocabulary it decides in.
 *
 * <p><strong>The creator is not a collaborator holding everything.</strong>
 * {@link #ofCreator()} is a distinct kind of holder, not a full set of
 * capabilities, because the two differ in what they may confer: only a creator
 * may grant {@link Capability#MANAGE_COLLABORATORS}. Modelling the creator as a
 * complete capability set would make that rule impossible to express — the
 * holder of everything would, by definition, be able to pass on everything.
 */
public final class Grants {

    private static final Grants CREATOR = new Grants(true, EnumSet.allOf(Capability.class));

    private static final Grants NONE = new Grants(false, EnumSet.noneOf(Capability.class));

    private final boolean creator;

    private final Set<Capability> held;

    private Grants(boolean creator, Set<Capability> held) {
        this.creator = creator;
        this.held = held;
    }

    /**
     * The campaign's owner: everything, implicitly, and the only holder who may
     * confer {@link Capability#MANAGE_COLLABORATORS}.
     */
    public static Grants ofCreator() {
        return CREATOR;
    }

    /** Somebody with no relationship to the campaign at all. */
    public static Grants none() {
        return NONE;
    }

    /**
     * What a collaborator row confers.
     *
     * <p>Nothing at all unless the row is active: a pending invitation has not
     * been accepted by anybody yet, and a revoked one is inert. Deciding that
     * here rather than at each call site is what makes "revoke" a single write
     * that takes effect everywhere.
     */
    public static Grants of(Collaborator collaborator) {
        return collaborator.isActive() ? of(collaborator.getCapabilities()) : NONE;
    }

    /**
     * A grant set held by a collaborator, for callers that have no row to hand.
     *
     * <p>An empty set is "nothing", not an error. The service and the entity both
     * refuse to write a grant that confers nothing, so this only arises if the
     * capability rows were removed underneath one — and the safe reading of a grant
     * with no capabilities is that it authorises none, rather than an exception from
     * a class whose job is to answer a question about permissions.
     */
    public static Grants of(Set<Capability> capabilities) {
        return capabilities.isEmpty() ? NONE : new Grants(false, EnumSet.copyOf(capabilities));
    }

    public boolean isCreator() {
        return creator;
    }

    /** Whether this holder is party to the campaign at all. */
    public boolean isEmpty() {
        return !creator && held.isEmpty();
    }

    public boolean holds(Capability capability) {
        return creator || held.contains(capability);
    }

    /** The capabilities themselves, for a response and for the escalation check. */
    public Set<Capability> capabilities() {
        return Collections.unmodifiableSet(held);
    }

    /**
     * Which of {@code requested} this holder may <strong>not</strong> confer on
     * somebody else. Empty means the grant is within their authority.
     *
     * <p>Two rules, and they are the whole of delegation:
     *
     * <ul>
     *   <li><strong>Nobody may grant more than they hold.</strong> Otherwise the
     *       creator's decision to give somebody exactly one capability is
     *       advisory: that person invites an accomplice with all eight, and the
     *       grant the creator issued was a suggestion.
     *   <li><strong>Only the creator may grant
     *       {@link Capability#MANAGE_COLLABORATORS}.</strong> A holder who could
     *       pass it on could grow the team indefinitely, and every additional
     *       manager can do the same — so the bound is not "one level of
     *       delegation", it is none.
     * </ul>
     *
     * <p>Returned as a set rather than as a boolean so that the refusal can name
     * which capability was the problem. "Forbidden" on a form with eight
     * checkboxes tells a creator to try combinations.
     */
    public Set<Capability> ungrantable(Set<Capability> requested) {
        if (creator) {
            return EnumSet.noneOf(Capability.class);
        }
        Set<Capability> beyond = EnumSet.noneOf(Capability.class);
        for (Capability capability : requested) {
            if (!held.contains(capability) || capability == Capability.MANAGE_COLLABORATORS) {
                beyond.add(capability);
            }
        }
        return beyond;
    }

    @Override
    public String toString() {
        return creator ? "Grants[creator]" : "Grants" + held;
    }
}
