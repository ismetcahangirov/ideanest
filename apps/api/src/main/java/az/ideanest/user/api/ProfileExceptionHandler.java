package az.ideanest.user.api;

import az.ideanest.user.application.AccountNotFoundException;
import az.ideanest.user.application.ProfileFieldRejectedException;
import az.ideanest.user.application.ProfileNotFoundException;
import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * What §4.2's profile endpoints refuse, as RFC 9457 problem details (§10.4).
 *
 * <p>Five of them since #324 — the public page, P-07's switch, P-10's language, and the
 * owner's own read and write — and they belong under one advice because they refuse
 * overlapping things: an {@link AccountNotFoundException} means the same thing on four of
 * them, and the language write is here rather than in its own file for exactly that reason.
 * A second advice would be a second spelling of one 404, and two spellings of a body that
 * must not distinguish a deleted account from one that never existed is how they come to
 * differ.
 *
 * <p>Its own advice rather than two types added to {@link UserExceptionHandler}, following
 * {@code PublicBackerExceptionHandler}: that file is scoped to the three controllers that
 * take a password, and its one entry is a 403 about one. Adding a public read's 404 to it
 * would put a refusal that must never distinguish a private account from a missing one
 * behind the same advice as a refusal whose whole job is to say the password was wrong.
 *
 * <p><strong>The 404 here must not escape unhandled, and that is the reason this file
 * exists at all rather than being left to Spring's default.</strong>
 * {@link PublicProfileController} is a {@code permitAll} endpoint: an unhandled exception
 * on one reaches Spring Security's error dispatch and comes back as 401, which tells an
 * anonymous visitor to sign in to see a profile that does not exist — and, worse, gives a
 * different answer for a private profile than for an absent one to anybody who tries it
 * with a token. {@code ProjectExceptionHandler} names the same trap about
 * {@code PublicProjectController}.
 */
@RestControllerAdvice(
        assignableTypes = {
            PublicProfileController.class,
            ProfileVisibilityController.class,
            LocalePreferenceController.class,
            OwnProfileController.class
        })
public class ProfileExceptionHandler {

    /**
     * 404 for a slug nobody holds, for an account §17.4 has anonymised, and for one whose
     * owner chose {@code PRIVATE}.
     *
     * <p>One body for all three — {@code ProfileNotFoundException} argues each pair — and
     * deliberately the same {@code USER_NOT_FOUND} that {@code BackerSignalExceptionHandler}
     * already answers {@code POST /v1/users/{slug}/follow} with. Two codes for one fact
     * would let a client tell "no profile" from "no account", which is the distinction
     * neither endpoint may draw; a client that handles one needs no second branch.
     *
     * <p><strong>Not a 403, under any of the three.</strong> This endpoint takes no
     * credential, so a 403 would be an oracle any stranger could ask, and what it would
     * report on is a person who asked this platform for no page.
     */
    @ExceptionHandler(ProfileNotFoundException.class)
    public ProblemDetail handleProfileNotFound(ProfileNotFoundException exception) {
        return notFound();
    }

    /**
     * 404 for a genuine token whose account is no longer there.
     *
     * <p>Deleted between the token being issued and being used. The token is ours and the
     * account is not, so this is 404 rather than 401 — the same answer {@code GET /v1/me}
     * gives, and a 401 would send a client to sign in again for an account that cannot be
     * signed in to.
     */
    @ExceptionHandler(AccountNotFoundException.class)
    public ProblemDetail handleAccountNotFound(AccountNotFoundException exception) {
        return notFound();
    }

    /**
     * 400 for a field of a profile edit that V2 or V46 would refuse one layer down, and for a
     * {@code locationSlug} naming no place (#276).
     *
     * <p>The field name travels in {@code meta} so that the editor can put the message beside
     * the input that caused it rather than in a banner at the top of a form. Deliberately the
     * same shape and the same {@code meta.field} as {@code PROJECT_FIELD_INVALID} in the
     * project module: a client that already handles one of them needs no second branch, and
     * two shapes for "this field is wrong" would be two for no reason.
     *
     * <p><strong>The detail is the exception's message here, unlike the 404 above.</strong>
     * That one must say the same sentence for three different facts, because any difference
     * between them is an oracle. This one is answered only to the account's own owner about
     * their own request, and the whole value of it is that it says which field and why.
     */
    @ExceptionHandler(ProfileFieldRejectedException.class)
    public ProblemDetail handleFieldRejected(ProfileFieldRejectedException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create("https://ideanest.az/problems/profile-field-invalid"));
        problem.setTitle("Invalid field");
        problem.setDetail(exception.getMessage());
        problem.setProperty("code", "PROFILE_FIELD_INVALID");
        problem.setProperty("meta", Map.of("field", exception.field()));
        return problem;
    }

    /** One body, so that the two above cannot drift apart. */
    private static ProblemDetail notFound() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(URI.create("https://ideanest.az/problems/user-not-found"));
        problem.setTitle("No such account");
        // Deliberately not the exception's message, and deliberately the same sentence for
        // an account that is hidden as for one that never existed.
        problem.setDetail("There is no account at that address.");
        problem.setProperty("code", "USER_NOT_FOUND");
        return problem;
    }
}
