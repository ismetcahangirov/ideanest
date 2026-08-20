package az.ideanest.shared.project;

import java.util.UUID;

/**
 * The few facts about a campaign that a message about it has to be able to state.
 *
 * <p><strong>Four fields, and the bound is deliberate.</strong> This is a published contract
 * between the project module and everything that describes a campaign without owning one, so
 * every field added here is a field every implementation has to produce and every reader may
 * come to depend on. What a notification needs is the campaign's name and somewhere to send
 * the reader; a caller that needs the goal, the state or the cover image is asking for a
 * projection rather than a summary, and that is a different question with a different answer.
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
 */
public record ProjectSummary(UUID id, String title, String slug, String creatorSlug) {

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
