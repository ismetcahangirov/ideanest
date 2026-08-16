package az.ideanest.shared.idempotency;

/**
 * What an idempotent request answers with, first time and every time after.
 *
 * <p><strong>The body is serialised JSON and not an object</strong>, and that is
 * the whole reason this type exists. §10.3's promise is that a replay returns
 * <em>the original response</em>; a promise kept by re-serialising a freshly loaded
 * entity is only as good as the two serialisations agreeing, which they stop doing
 * the first time a field is added, a null becomes absent, or a row is touched by
 * something else in between. The bytes are produced once, stored, and handed back.
 *
 * @param replayed false for the request that did the work, true for every retry of
 *     it. Not sent to the client — the contract does not carry it and a client that
 *     branched on it would be treating a retry as a different outcome, which is
 *     what idempotency exists to deny — but it is what makes "this was a replay"
 *     assertable in a test and visible in a log
 */
public record RecordedResponse(int status, String body, boolean replayed) {
}
