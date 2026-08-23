package az.ideanest.community.api;

import az.ideanest.community.application.FaqPatch;
import az.ideanest.shared.Patched;

/**
 * {@code PATCH /v1/faqs/{id}} — a partial edit, with JSON Merge-Patch semantics
 * (RFC 7396).
 *
 * <p>Both fields are {@link Patched}, so a body carrying only the answer leaves the
 * question alone. The FAQ editor autosaves one input at a time, which is what makes the
 * distinction between "not mentioned" and "sent as null" load-bearing rather than
 * pedantic — bound to an ordinary record, both arrive as null and a creator fixing a typo
 * in an answer would blank the question above it.
 *
 * <p>Bean validation is absent for the reason {@code RewardPatchRequest} gives: the
 * annotations cannot see inside a {@code Patched}, and a rule enforced both here and in
 * {@code FaqContent} would be two rules that can disagree.
 */
public record FaqPatchRequest(Patched<String> question, Patched<String> answer) {

    public FaqPatchRequest {
        // Absence is the neutral value. See Patched: Jackson's absent hook already does
        // this, and this is the belt to its braces.
        question = Patched.orAbsent(question);
        answer = Patched.orAbsent(answer);
    }

    /** The same edit, in the shape the application layer works in. */
    public FaqPatch toPatch() {
        return new FaqPatch(question, answer);
    }
}
