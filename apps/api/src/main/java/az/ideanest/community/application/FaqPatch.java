package az.ideanest.community.application;

import az.ideanest.shared.Patched;

/**
 * A partial edit of one FAQ entry, with JSON Merge-Patch semantics (RFC 7396).
 *
 * <p>Both fields are {@link Patched}, so a body carrying only the answer leaves the
 * question alone. That distinction is load-bearing rather than pedantic for the reason
 * {@code RewardPatch} gives: the editor autosaves one input at a time, and an ordinary
 * record would make a body that mentioned only the answer read as "and blank the
 * question".
 *
 * <p><strong>Present-and-null is not "clear this", here.</strong> Neither field is
 * nullable — an entry with no question is a row the page cannot render, and one with no
 * answer asks something on a public page and does not answer it — so an explicit
 * {@code null} is refused by {@code FaqContent} with the same message a blank string
 * gets. One rule in one place, rather than a second null check at the edge saying the
 * same thing differently.
 *
 * <p>The position is deliberately not here. Order is a property of the list and moves
 * through {@code PATCH /v1/projects/{id}/faqs/reorder}, which rewrites every entry — a
 * per-entry position would let two requests both claim position three.
 */
public record FaqPatch(Patched<String> question, Patched<String> answer) {

    public FaqPatch {
        // Absence, not null, is the neutral value. See Patched.
        question = Patched.orAbsent(question);
        answer = Patched.orAbsent(answer);
    }
}
