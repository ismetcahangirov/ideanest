package az.ideanest.shared.project;

import java.util.UUID;

/**
 * The few facts about a campaign that a message about it has to be able to state.
 *
 * <p><strong>Five fields, and the bound is deliberate.</strong> This is a published contract
 * between the project module and everything that describes a campaign without owning one, so
 * every field added here is a field every implementation has to produce and every reader may
 * come to depend on. What a notification needs is the campaign's name and somewhere to send
 * the reader; a caller that needs the goal, the state or the cover image is asking for a
 * projection rather than a summary, and that is a different question with a different answer.
 *
 * <p><strong>{@code creatorId} was the fifth, and #90 is why.</strong> It is the one fact here
 * that is not about wording, and it earned its place by being the only way to ask a
 * project-scoped question about the person behind the campaign: {@code ProjectAudiences} is
 * keyed on a campaign, {@code follows} is keyed on an account, and the community module owns
 * the second and may not read the first. Without it, {@code FOLLOWERS} is not expressible from
 * the module that holds the rows. It stays inside the boundary the paragraph above draws —
 * whose campaign it is, not how it is doing.
 *
 * <p><strong>It is a snapshot, not a handle.</strong> A caller that stores these values —
 * {@code NotificationEventListener} stores all four in {@code notifications.params} — is
 * storing what was true when the thing happened, which is what a notification is supposed to
 * say. A title edited afterwards does not rewrite the message that went out, and should not.
 *
 * @param id the campaign, echoed back so that a caller holding only this record still knows
 *     which one it is about
 * @param title the campaign's name, as the creator last saved it. Never blank: {@code title}
 *     is {@code NOT NULL} and a campaign cannot be created without one
 * @param slug the campaign's half of its public URL
 * @param creatorSlug the creator's half of it. Both halves, because §10.2's public campaign
 *     page is {@code /projects/{creatorSlug}/{projectSlug}} and one of them alone addresses
 *     nothing — a link built from the identifier instead resolves to no route at all, which
 *     is what the emails were doing before this record existed
 * @param creatorId the account the campaign belongs to. <strong>Never rendered</strong>: it is
 *     here to be joined on, and a message that named an account by its identifier would be
 *     naming somebody by a number. Null only when the creator row could not be joined, which
 *     is the same invariant violation {@link #hasPublicPath()} defends against
 */
public record ProjectSummary(UUID id, String title, String slug, String creatorSlug, UUID creatorId) {

    public ProjectSummary {
        if (id == null) {
            throw new IllegalArgumentException("A campaign summary is about some campaign");
        }
    }

    /**
     * Whether this summary can address the campaign's public page.
     *
     * <p>Both slugs or neither: half a path is not a shorter path, it is a different page or
     * no page.
     *
     * <p>Both columns are {@code NOT NULL}, so this is false only when a campaign's creator
     * row could not be joined — an invariant violation rather than an ordinary state. It is
     * checked all the same, because the cost of assuming is a link with the word
     * {@code null} in it going out to somebody.
     */
    public boolean hasPublicPath() {
        return slug != null && !slug.isBlank() && creatorSlug != null && !creatorSlug.isBlank();
    }
}
