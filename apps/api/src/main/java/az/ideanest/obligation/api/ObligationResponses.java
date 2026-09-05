package az.ideanest.obligation.api;

import az.ideanest.obligation.application.UpdateObligations;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the obligation endpoints answer with — issue #437.
 *
 * <p><strong>The state is a name and the dates are facts, and the response carries both.</strong>
 * §22.3 asks for the creator's history to be visible; #437 asks for it to be stated neutrally,
 * with the date of the last update, and never by colour alone. A response carrying only
 * {@code LAPSED} would leave a client with nothing to write except a word, and the word on its
 * own is a verdict.
 */
public final class ObligationResponses {

    private ObligationResponses() {
    }

    /**
     * One campaign's obligation.
     *
     * @param state worked out on the server against one instant. A client given four timestamps
     *     and asked to derive it is a client that gets the boundary wrong
     * @param lastUpdateAt null when nothing has been published since the campaign closed, which is
     *     a different fact from a stale date and is what {@code NEVER_UPDATED} says
     * @param lapsedAt when the escalation was raised, or null. Present on a public response
     *     because it is what makes the state checkable rather than asserted
     */
    public record Obligation(
            UUID projectId,
            String state,
            Instant openedAt,
            Instant lastUpdateAt,
            Instant dueAt,
            Instant lapsedAt,
            Instant closedAt) {

        public static Obligation of(UpdateObligations.ObligationView view) {
            return new Obligation(
                    view.projectId(),
                    view.state().name(),
                    view.openedAt(),
                    view.lastUpdateAt(),
                    view.dueAt(),
                    view.lapsedAt(),
                    view.closedAt());
        }
    }

    /**
     * A creator's campaigns and how each is doing.
     *
     * @param lapsedCount how many are late now. On the envelope rather than counted by the client,
     *     because it is the number a profile leads with and the one §22.3's transparency is
     *     actually about
     */
    public record CreatorHistory(UUID creatorId, List<Obligation> obligations, int lapsedCount) {

        /**
         * Nothing to disclose, and no identifier to name.
         *
         * <p>What a slug nobody holds answers. Deliberately the same shape a creator with no
         * closed campaigns gets — see {@code ObligationController.forCreatorSlug} on why the two
         * must not be distinguishable from outside.
         */
        public static CreatorHistory empty() {
            return new CreatorHistory(null, List.of(), 0);
        }

        public static CreatorHistory of(UUID creatorId, List<UpdateObligations.ObligationView> views) {
            List<Obligation> rendered = views.stream().map(Obligation::of).toList();
            int lapsed = (int) views.stream()
                    .filter(view -> switch (view.state()) {
                        case LAPSED, NEVER_UPDATED -> true;
                        case CURRENT, DUE_SOON, COMPLETE -> false;
                    })
                    .count();
            return new CreatorHistory(creatorId, rendered, lapsed);
        }
    }

    /**
     * One escalation, as the console's queue draws it.
     *
     * <p>Carries the creator, which the public responses do not: the queue's first question is
     * "who is this" and answering it from a campaign identifier would be a second request per row.
     */
    public record Escalation(
            UUID projectId,
            UUID creatorId,
            String state,
            Instant lastUpdateAt,
            Instant dueAt,
            Instant lapsedAt,
            Instant resolvedAt,
            String resolutionNote) {

        public static Escalation of(UpdateObligations.ObligationView view) {
            return new Escalation(
                    view.projectId(),
                    view.creatorId(),
                    view.state().name(),
                    view.lastUpdateAt(),
                    view.dueAt(),
                    view.lapsedAt(),
                    view.resolvedAt(),
                    view.resolutionNote());
        }
    }

    /** The queue. */
    public record Queue(List<Escalation> escalations) {

        public static Queue of(List<UpdateObligations.ObligationView> views) {
            return new Queue(views.stream().map(Escalation::of).toList());
        }
    }
}
