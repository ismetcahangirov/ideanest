package az.ideanest.user.domain;

/**
 * Whether an account has a public profile page — §4.2's P-07.
 *
 * <p><strong>Two values and not three.</strong> A middle setting — "visible to
 * people I have backed", "visible to signed-in accounts" — was considered and
 * refused: every one of them is a rule the platform would then have to enforce
 * identically on the profile page, the creator tab of every campaign, the follow
 * button, and the backed-projects archive, and the first surface that forgot it
 * would publish what the setting promised to hide.
 *
 * <p>What {@link #PRIVATE} does <strong>not</strong> hide is worth stating,
 * because a setting that overpromises is worse than none: a creator's name and
 * avatar are on every campaign page they publish, and V45 says why that cannot
 * change. Choosing PRIVATE withdraws the profile page, its about tab and its
 * archives. It does not retract a campaign somebody has already launched.
 */
public enum ProfileVisibility {

    /** The default, and what every account already was before V45. */
    PUBLIC,

    /** No profile page. {@code GET /v1/users/{slug}} answers 404, not 403 — see the controller. */
    PRIVATE
}
