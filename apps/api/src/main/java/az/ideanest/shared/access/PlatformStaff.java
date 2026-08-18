package az.ideanest.shared.access;

import java.util.UUID;

/**
 * Who counts as platform staff, asked from outside the module that currently knows.
 *
 * <p><strong>A second question, not a second interface for the same one.</strong>
 * {@link ProjectAuthorisation} answers "what has this campaign's creator granted this
 * account"; this answers "does this account work for the platform". They are different
 * kinds of authority — one is delegated by a user, the other is conferred by the
 * operator — and a moderator holds no capability on the campaign they are deciding
 * about, which is the point.
 *
 * <p><strong>The implementation is deployment configuration standing in for a role
 * model.</strong> It is a configured list of addresses today
 * ({@code project.application.ModeratorDirectory}), and it fails closed: an unset list
 * is nobody, so a deployment that forgets to configure it has a queue nobody can clear
 * rather than a queue anybody can. Epic #100 replaces what is behind this interface and
 * changes nothing in front of it — which is the reason the callers ask through an
 * interface in {@code shared} rather than naming the directory.
 *
 * <p>{@link #requireStaff} refuses with
 * {@code project.application.NotAModeratorException} — a 403. Described rather than
 * declared for {@link ProjectAuthorisation}'s reason: {@code shared} may not depend on
 * a module.
 */
public interface PlatformStaff {

    /**
     * Whether this account may take a platform decision.
     *
     * <p>For a caller that has to branch rather than refuse. A caller that means to
     * refuse uses {@link #requireStaff} instead, so that the refusal is one sentence
     * and cannot be forgotten after the {@code if}.
     */
    boolean isStaff(UUID accountId);

    /**
     * Refuses unless this account is platform staff.
     *
     * @param accountId the authenticated caller, taken from the access token's subject
     *     and never from the request
     */
    void requireStaff(UUID accountId);
}
