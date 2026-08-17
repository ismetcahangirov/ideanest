package az.ideanest.shared;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

/**
 * Writes a {@link Patched} field so that it can be told apart from every other
 * {@link Patched} field.
 *
 * <p><strong>This is not for responses.</strong> Nothing the platform returns
 * contains a {@code Patched} — it is a request shape — and the reason it has to be
 * serialisable at all is §10.3's idempotency: {@code IdempotentRequests} fingerprints
 * the <em>parsed</em> request by serialising it, deliberately, so that two spellings
 * of one amount are one request. A type that deserialises faithfully and serialises
 * lossily breaks that.
 *
 * <p><strong>What went wrong without it.</strong> {@link Patched} keeps its value in
 * a private field reached through {@code value()}, which is not a getter by Jackson's
 * naming rules, so bean serialisation emitted {@code {"present":true}} and dropped
 * the value entirely. Every edit of one pledge then fingerprinted identically, and
 * one {@code Idempotency-Key} sent twice with two <em>different</em> bodies would
 * have been answered as a replay of the first — handing a client the response to an
 * edit it never made, which is the single failure {@code IDEMPOTENCY_KEY_REUSED}
 * exists to produce instead.
 *
 * <p>All three states are distinguishable in the output, because all three are
 * different requests: absent is {@code {"present":false}}, a cleared field is
 * {@code {"present":true,"value":null}}, and a set field carries its value. "Leave
 * the reward alone" and "make this pledge support-only" must never fingerprint the
 * same.
 */
class PatchedSerializer extends ValueSerializer<Patched<?>> {

    @Override
    public void serialize(Patched<?> patched, JsonGenerator generator, SerializationContext context) {

        generator.writeStartObject();
        generator.writeBooleanProperty("present", patched.isPresent());
        if (patched.isPresent()) {
            // writePOJOProperty rather than writeNullProperty plus a branch: the
            // value may be any type, including one with a serializer of its own —
            // Money is the one that matters here, and its amount is a string.
            generator.writePOJOProperty("value", patched.value());
        }
        generator.writeEndObject();
    }
}
