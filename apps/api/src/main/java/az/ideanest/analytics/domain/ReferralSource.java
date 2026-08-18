package az.ideanest.analytics.domain;

import java.util.Locale;

/**
 * Where a visit came from: a channel, and up to three labels naming the place inside
 * it.
 *
 * <p>One value rather than four columns passed around together, for the reason
 * {@code Money} gives about an amount and its currency: the normalisation, the
 * bounds, and the "a direct visit names nothing" rule would otherwise live at every
 * call site, and each of them would be free to get it wrong differently. A touch and
 * an attribution both hold one of these, which is also what makes copying the source
 * onto the attribution row a single assignment rather than four.
 *
 * <h2>Labels are folded; codes are not</h2>
 *
 * <p>{@code source} and {@code campaign} are lower-cased and trimmed, because they
 * are labels a person typed into a link and "Twitter", "twitter" and " twitter " are
 * the same place. Left unfolded they would be three rows in §4.7's report, three
 * shares of one stream of traffic, and a top-sources list whose first three entries
 * name the same site.
 *
 * <p>{@code referrerCode} is trimmed and nothing else. It is an opaque token, not a
 * word: the codes this platform issues come from {@code SecureTokens}, which encodes
 * 256 bits URL-safe and is case-sensitive, so folding one would merge two distinct
 * links and lose 26 bits doing it.
 *
 * <h2>The two refusals</h2>
 *
 * <p><strong>A direct visit names nothing.</strong> {@link ReferralChannel#DIRECT}
 * means "nobody sent them", so a source alongside it is a contradiction rather than
 * extra detail — and one that would inflate whatever was named. Refused rather than
 * quietly dropped, because a client sending both has a bug and a silently discarded
 * field is a bug that ships.
 *
 * <p><strong>A non-direct visit names something.</strong> A row saying only
 * {@code SOCIAL} is a row the report cannot label, and an unlabelled share is the
 * easiest possible thing to fill this table with. The mirror of both rules is in V24,
 * so neither depends on this class being the only writer.
 *
 * @param channel what kind of place, from the closed set
 * @param source the place, folded: {@code twitter}, {@code newsletter}. Null for a
 *     direct visit and for a referral link that named only its code
 * @param campaign which push, when the source runs more than one:
 *     {@code launch-week}. Always optional — it qualifies a source rather than
 *     standing in for one
 * @param referrerCode the creator's own share link, when the visit came through one.
 *     Bounded at the same 64 characters as {@code pledges.referrer_code}, because the
 *     same code is what a pledge carries and the two have to be comparable
 */
public record ReferralSource(ReferralChannel channel, String source, String campaign, String referrerCode) {

    /** {@code pledges_referrer_code_length}, and the same bound applied to the labels. */
    public static final int MAX_LENGTH = 64;

    private static final ReferralSource DIRECT = new ReferralSource(ReferralChannel.DIRECT, null, null, null);

    public ReferralSource {
        if (channel == null) {
            throw new IllegalArgumentException("A visit came from some kind of place, even if that place is DIRECT");
        }

        source = folded(source, "source");
        campaign = folded(campaign, "campaign");
        referrerCode = trimmed(referrerCode, "referrer code");

        if (channel == ReferralChannel.DIRECT && (source != null || campaign != null || referrerCode != null)) {
            throw new IllegalArgumentException(
                    "A direct visit is the absence of a source; it cannot also name one");
        }
        if (channel != ReferralChannel.DIRECT && source == null && referrerCode == null) {
            throw new IllegalArgumentException(
                    "A visit from " + channel + " has to say where it came from, by source or by code");
        }
        // Stricter than the schema, and only here: a REFERRAL_LINK is by definition
        // the link, so one with no code is a channel chosen by mistake rather than a
        // link with a missing field. The check constraint cannot say this without
        // making the column conditionally mandatory, which is a rule a future channel
        // would have to be added to.
        if (channel == ReferralChannel.REFERRAL_LINK && referrerCode == null) {
            throw new IllegalArgumentException("A referral link is its code, and this one has none");
        }
    }

    /** Nobody sent them. See {@link ReferralChannel#DIRECT} for why this is recorded at all. */
    public static ReferralSource direct() {
        return DIRECT;
    }

    public static ReferralSource of(
            ReferralChannel channel, String source, String campaign, String referrerCode) {

        return new ReferralSource(channel, source, campaign, referrerCode);
    }

    public boolean isDirect() {
        return channel == ReferralChannel.DIRECT;
    }

    /**
     * Trimmed, lower-cased, and null when there was nothing but space.
     *
     * <p>Blank to null rather than blank to blank: a client sending an empty string
     * means "no campaign", and {@code referral_touches_campaign_length} refuses a
     * blank one rather than storing it — the same decision
     * {@code DraftPledgeRequest} makes about an empty referrer code.
     */
    private static String folded(String value, String field) {
        String trimmed = trimmed(value, field);
        // ROOT rather than the default locale. Turkish folds a dotted capital I to a
        // dotted lower-case one, so a report grouped by a label folded on a machine
        // configured for Istanbul would split from one grouped on a machine in Baku.
        return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    private static String trimmed(String value, String field) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_LENGTH) {
            // Refused rather than truncated. A truncated code names a different link
            // or none, and a truncated label silently merges two sources in a report
            // somebody is about to spend money on the strength of.
            throw new IllegalArgumentException(
                    "A referral " + field + " is at most " + MAX_LENGTH + " characters");
        }
        return trimmed;
    }
}
