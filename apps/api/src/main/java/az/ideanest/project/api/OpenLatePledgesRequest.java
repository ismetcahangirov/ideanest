package az.ideanest.project.api;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * How long a campaign will go on taking pledges after it closed — §4.5's PL-16.
 *
 * <p>The end is <strong>required</strong>. §6.1 has no edge that closes a late-pledge
 * window on a timer, so a window with no end is a campaign that takes money until
 * somebody remembers to stop it — and the people it takes money from are being promised
 * a reward that was costed for a smaller number of them.
 *
 * <p>An instant rather than a number of days: the creator is announcing a date to their
 * backers, and a duration would be resolved against a clock the client and the server
 * do not share.
 */
public record OpenLatePledgesRequest(
        @NotNull(message = "A late-pledge window has to say when it ends") Instant endsAt) {
}
