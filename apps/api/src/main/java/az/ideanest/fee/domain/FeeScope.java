package az.ideanest.fee.domain;

/**
 * What a fee schedule applies to — §9, issue #311.
 *
 * <p>§4.11 asks for "platform and processing rates with exceptions", and these are the
 * kinds of exception §9 names. They are ordered from general to specific, and
 * {@code FeeSchedules} resolves the most specific one that matches: a campaign's own
 * schedule beats its category's, which beats the platform's.
 *
 * <p><strong>The order is declared by the enum's ordinal and is deliberately not
 * configurable.</strong> A precedence somebody can edit is one that will eventually say
 * that the platform default beats a campaign's negotiated rate, and the first anybody
 * hears of it is a creator being charged more than their contract says.
 */
public enum FeeScope {

    /** What everybody pays unless something below says otherwise. Exactly one is open. */
    PLATFORM,

    /**
     * A category's rate.
     *
     * <p>§9's example is a lower platform rate on charitable categories. The reference is
     * a {@code categories.id}.
     */
    CATEGORY,

    /**
     * One campaign's rate.
     *
     * <p>The negotiated case, and the reason this enum is not a boolean. The reference is
     * a {@code projects.id}.
     */
    PROJECT;

    /** Whether a schedule of this scope names something. False only for {@link #PLATFORM}. */
    public boolean needsReference() {
        return this != PLATFORM;
    }
}
