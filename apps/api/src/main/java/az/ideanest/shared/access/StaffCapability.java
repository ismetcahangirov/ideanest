package az.ideanest.shared.access;

/**
 * What a member of platform staff may do, named one authority at a time — #295.
 *
 * <p><strong>This is {@link ProjectCapability}'s argument applied to the other kind of
 * authority.</strong> That enum exists because four modules wanted a fine-grained
 * permission check, could not name the project module's own enum, and each settled for
 * the coarsest question the project module happened to publish. Staff authorisation was
 * in a worse position: there was no fine-grained question to ask at all, only
 * {@link PlatformStaff#requireStaff}, so every console endpoint asked "is this person
 * staff" and got the same answer whether it was about a comment or about a refund.
 *
 * <p><strong>Why the vocabulary is here and the roles are not.</strong> A capability is
 * named by the module that enforces it — the refund console says
 * {@code ISSUE_REFUND} — so it has to live somewhere every module may name, which is
 * {@code shared}. Which roles confer which capabilities is a policy the staff module
 * owns and nothing else needs to know; a caller that named a role would be re-deciding
 * that policy at the call site, and the fifth one to do it would get it wrong.
 *
 * <p><strong>They are deliberately coarser than the endpoint list.</strong> Sixteen
 * console modules do not need sixteen capabilities: reading the payment log and reading
 * the ledger are the same authority over the same facts, and splitting them would
 * produce a grant screen nobody can reason about. The line drawn here is "could a
 * mistake with this be undone", which is why reading finance and moving money are two
 * and not one.
 */
public enum StaffCapability {

    /**
     * Work the queues: the submission queue, content reports, and the decisions that
     * end them. AD-01, AD-02, AD-09.
     *
     * <p>Includes suspending a campaign, which is terminal and is still here rather
     * than behind its own capability: a moderator who may reject a campaign before
     * launch and may not stop one after it is a moderator who has to escalate the
     * urgent half of the job.
     */
    MODERATE_CONTENT,

    /**
     * Search, inspect, ban and reinstate an account. AD-04.
     *
     * <p>Separate from {@link #MODERATE_CONTENT} because it is the one authority on
     * the platform that hands somebody else's email address to an account with no
     * relationship to them — {@code AdminUserController} audits every read for exactly
     * that reason — and a person hired to clear comments does not need it.
     */
    ADMINISTER_ACCOUNTS,

    /**
     * Editorial collections, badges, open calls, placement, and the taxonomy behind
     * them. AD-03, AD-08.
     *
     * <p>Curation and taxonomy are one capability because they are one job: the person
     * who decides that a collection exists is the person who decides what category it
     * draws from.
     */
    CURATE,

    /**
     * Read the money: the payment log, the ledger, the platform's own figures.
     * AD-05, AD-13.
     *
     * <p>Reading is separated from moving because the mistakes are not comparable. A
     * wrong read is a wrong answer in a meeting; a wrong refund is somebody's money.
     */
    VIEW_FINANCE,

    /** Issue a full or partial refund against a captured charge. AD-06. */
    ISSUE_REFUND,

    /**
     * Record evidence and an outcome against a dispute a provider raised. AD-07.
     *
     * <p>Not folded into {@link #ISSUE_REFUND}: a chargeback is answered rather than
     * granted, and the person who assembles the evidence is not necessarily the person
     * trusted to hand money back on request.
     */
    MANAGE_DISPUTES,

    /**
     * Approve a payout to a creator. AD-05.
     *
     * <p>Its own capability because §4.11 requires dual approval above a threshold, and
     * dual approval means two accounts that each hold <em>this</em> — a rule that
     * cannot be stated if approving is a side effect of being in finance.
     */
    APPROVE_PAYOUT,

    /** Answer support tickets and read the account context behind one. AD-10. */
    HANDLE_SUPPORT,

    /**
     * Change what the platform charges, what is switched on, and what it writes to
     * people. AD-11, AD-12, AD-15.
     *
     * <p>One capability over three screens, and the narrowest grant on the list: each
     * of them changes the behaviour of the running platform for everybody at once, and
     * none of them is part of anybody's daily work.
     */
    CONFIGURE_PLATFORM,

    /**
     * Read the audit trail. AD-14.
     *
     * <p>Held widely rather than narrowly, deliberately. A trail only the people it
     * would incriminate can read is a trail; a trail every member of staff can read is
     * a control.
     */
    VIEW_AUDIT,

    /** Read queue depth, failed jobs and provider status. AD-16. */
    VIEW_HEALTH,

    /**
     * Grant and withdraw the roles above. #295 itself.
     *
     * <p>Held by {@code ADMINISTRATOR} alone. Anybody who can grant themselves a
     * capability effectively holds every capability, so this is the one that decides
     * what the rest of the enum is worth.
     */
    ADMINISTER_STAFF
}
