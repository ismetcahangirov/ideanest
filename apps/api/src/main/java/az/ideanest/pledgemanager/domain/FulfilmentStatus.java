package az.ideanest.pledgemanager.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * Where a parcel is — §4.8's PM-22.
 *
 * <p>Four values, and each of them is something a backer does differently about.
 * {@link #PREPARING} is "wait"; {@link #SHIPPED} is "watch the tracking";
 * {@link #DELIVERED} is "it should be with you"; {@link #RETURNED} is the only one
 * that asks them to act. Folding the last into the third — a delivery that failed
 * reported as a delivery — is the failure this vocabulary exists to prevent, and it
 * is the one a creator otherwise answers four hundred emails about.
 *
 * <p><strong>There is no transition table, deliberately.</strong> §6.1 and §6.2 have
 * one each because a campaign and a pledge move through their states once and the
 * order is the rule. A fulfilment status is not that: it is a <em>claim about the
 * physical world</em>, imported in bulk from a carrier's file, and the claim is
 * frequently wrong. A creator who scanned the wrong box has to be able to put a
 * pledge back from {@code DELIVERED} to {@code SHIPPED}, and a state machine that
 * refused it would leave them with one row they cannot correct and a backer who is
 * told their parcel arrived. What makes the correction safe rather than silent is
 * that every import is audited and that V38's two timestamps are constrained to
 * agree with the value here.
 *
 * <p>The names are the wire format and the stored value, checked by
 * {@code fulfilments_status_is_known}. Renaming one is a migration.
 */
public enum FulfilmentStatus {

    /** Nothing has left the creator yet. The state every fulfilment starts in. */
    PREPARING,

    /** It is with a carrier. {@code shipped_at} is set; the tracking number usually is. */
    SHIPPED,

    /** The carrier says it arrived. */
    DELIVERED,

    /**
     * It came back.
     *
     * <p>A backer whose parcel is here has to do something — usually confirm an
     * address the creator will ship to a second time — and a status that did not
     * distinguish it would leave them waiting for a delivery that has already failed.
     */
    RETURNED;

    /**
     * Whether a parcel in this status has left the creator.
     *
     * <p>Read by {@link Fulfilment} to decide {@code shipped_at}, which V38 constrains
     * to be present for exactly these. A returned parcel counts: it was shipped, and
     * that is what makes the return possible.
     */
    public boolean hasShipped() {
        return this != PREPARING;
    }

    /**
     * The status this text names, if it names one.
     *
     * <p>Case-insensitive and trimmed, because the text comes out of a spreadsheet
     * cell a person typed. Empty rather than an exception: the caller is a bulk
     * import, and one unreadable cell means one row reported back to the creator
     * rather than a file that would not load.
     */
    public static Optional<FulfilmentStatus> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(text.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }
}
