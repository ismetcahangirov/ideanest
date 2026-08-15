package az.ideanest.shared;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;
import java.io.IOException;

/**
 * Reads a {@link Patched} field, keeping "not mentioned" apart from "set to null".
 *
 * <p>Three hooks, and each of them carries one of the three cases:
 *
 * <ul>
 *   <li>{@link #deserialize} — the property was present with a value.</li>
 *   <li>{@link #getNullValue} — the property was present and null, which under
 *       RFC 7396 means clear the field.</li>
 *   <li>{@link #getAbsentValue} — the property was not in the document at all,
 *       which means leave it alone. Without this override Jackson falls back to
 *       {@code getNullValue}, and every partial update would erase every field it
 *       did not mention.</li>
 * </ul>
 *
 * <p>{@link ContextualDeserializer} is what makes it generic: the wrapper's type
 * parameter is only known from the property being bound, so the inner type is
 * resolved once per property when the deserializer is built rather than guessed
 * per document.
 */
class PatchedDeserializer extends JsonDeserializer<Patched<?>> implements ContextualDeserializer {

    /** Null only in the uncontextualised instance Jackson creates before resolving the property. */
    private final JavaType valueType;

    PatchedDeserializer() {
        this(null);
    }

    private PatchedDeserializer(JavaType valueType) {
        this.valueType = valueType;
    }

    @Override
    public JsonDeserializer<?> createContextual(DeserializationContext context, BeanProperty property) {
        JavaType wrapper = property == null ? context.getContextualType() : property.getType();
        // containedTypeOrUnknown rather than containedType(0): a raw Patched
        // would otherwise resolve to null here and fail later inside a document,
        // which is a long way from the declaration that caused it.
        return new PatchedDeserializer(wrapper.containedTypeOrUnknown(0));
    }

    @Override
    public Patched<?> deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        return Patched.of(context.readValue(parser, valueType));
    }

    @Override
    public Patched<?> getNullValue(DeserializationContext context) {
        return Patched.of(null);
    }

    @Override
    public Object getAbsentValue(DeserializationContext context) {
        return Patched.absent();
    }
}
