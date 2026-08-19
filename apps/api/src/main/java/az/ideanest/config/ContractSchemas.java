package az.ideanest.config;

import az.ideanest.shared.Patched;
import az.ideanest.shared.money.Money;
import com.fasterxml.jackson.databind.JavaType;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import java.util.Iterator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The two types whose JSON a scanner cannot see — #136.
 *
 * <p>springdoc builds the contract by reflecting over the classes a controller mentions,
 * which is right for the overwhelming majority of them and is wrong for exactly the two
 * below: both carry a Jackson serialiser that produces something other than their fields.
 * Reflection describes the Java; this describes the JSON.
 *
 * <p><strong>Left alone, both were wrong in the document in ways a generated client would
 * inherit silently.</strong> That is the argument for fixing it here rather than treating a
 * slightly-off specification as good enough:
 *
 * <ul>
 *   <li>{@link Money} came out as {@code {amount: number, currency: string, negative:
 *       boolean, positive: boolean, zero: boolean}} — the record's components plus its
 *       derived accessors. Every one of those five facts is wrong. §10.3 makes the amount a
 *       <strong>string</strong> precisely because a JSON number is an IEEE 754 double, so a
 *       client generated from the reflected schema would parse every pledge, every goal and
 *       every payout into a type that cannot hold them. The three booleans do not exist on
 *       the wire at all.
 *   <li>{@link Patched} came out as {@code {present: boolean}}, which is its
 *       <em>serialised</em> form — and nothing responds with one. On the wire in a request a
 *       {@code Patched<T>} is a {@code T}, or the field is absent; the serialiser exists only
 *       so §10.3's idempotency fingerprint can be taken over a parsed body, which
 *       {@code PatchedSerializer} says at length. Left as it was, the contract described
 *       every {@code PATCH} body on the platform as a set of booleans.
 * </ul>
 *
 * <p><strong>Why a converter rather than annotations on the types themselves.</strong>
 * {@code Money} and {@code Patched} live in {@code shared} and are used by services,
 * repositories and jobs that have nothing to do with HTTP. Annotating them with
 * {@code @Schema} would put a documentation dependency into the domain in order to describe
 * a transport, and the next value type with a custom serialiser would have to acquire the
 * same import. The knowledge that the wire form differs from the Java form belongs to the
 * thing that writes the wire format down.
 *
 * <p>Registered by being a bean: springdoc collects every {@link ModelConverter} in the
 * context, so there is nothing else to remember.
 */
@Component
public class ContractSchemas implements ModelConverter {

    /**
     * The shape §10.4's error example and every response on the platform use.
     *
     * <p>{@code numeric(14,2)} throughout the schema, so the pattern is a decimal with at
     * most two places and an optional sign — described rather than merely typed, because a
     * generated client that validates is a client that catches a locale-formatted amount
     * before it reaches the ledger.
     */
    private static final String AMOUNT_PATTERN = "^-?\\d+(\\.\\d{1,2})?$";

    /** ISO 4217, which {@code users_currency_shape} and {@code Money} both already require. */
    private static final String CURRENCY_PATTERN = "^[A-Z]{3}$";

    @Override
    public Schema<?> resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
        JavaType javaType = Json.mapper().constructType(type.getType());

        if (javaType != null) {
            Class<?> raw = javaType.getRawClass();

            if (Money.class.equals(raw)) {
                return money(type, context);
            }
            if (Patched.class.equals(raw)) {
                return unwrapped(javaType, type, context, chain);
            }
        }

        return chain.hasNext() ? chain.next().resolve(type, context, chain) : null;
    }

    /**
     * §10.3's money object, written out.
     *
     * <p>Named and registered in {@code components.schemas} rather than inlined at every
     * use, so a generated client gets one {@code Money} type instead of forty structurally
     * identical anonymous ones — which is the difference between a client that can pass an
     * amount from a pledge to a payout and one that cannot.
     */
    private static Schema<?> money(AnnotatedType type, ModelConverterContext context) {
        // `required` is declared on the raw Schema, so calling it through the fluent chain
        // is an unchecked call and -Werror refuses it. Set afterwards on the typed
        // reference instead: same object, no suppression, and nothing hidden.
        ObjectSchema schema = new ObjectSchema();
        schema.name(Money.class.getSimpleName())
                .description(
                        "An amount of money. §10.3: the amount is a **string**, never a JSON number —"
                                + " a JSON number is an IEEE 754 double and cannot represent a pledge"
                                + " exactly. Parse it with a decimal type.")
                .addProperty("amount", new StringSchema().pattern(AMOUNT_PATTERN).example("599.00"))
                .addProperty("currency", new StringSchema().pattern(CURRENCY_PATTERN).example("AZN"));
        schema.setRequired(List.of("amount", "currency"));

        context.defineModel(Money.class.getSimpleName(), schema);
        return type.isResolveAsRef() || type.isSchemaProperty()
                ? new Schema<>().$ref("#/components/schemas/" + Money.class.getSimpleName())
                : schema;
    }

    /**
     * {@code Patched<T>} is a {@code T} on the wire, or the property is not there at all.
     *
     * <p>The absence is what {@code Patched} exists to express — RFC 7396 merge-patch, where
     * a field nobody mentioned is left alone — and OpenAPI already has a word for it: the
     * property is simply not in {@code required}. Nothing needs to be added to say so,
     * because springdoc marks a record component required only when it can prove it is.
     *
     * <p>A raw {@code Patched} with no type argument resolves to a free-form value rather
     * than throwing: it cannot occur in this codebase — every use names its type — and a
     * documentation converter is the wrong place to fail a start-up over it.
     */
    private static Schema<?> unwrapped(
            JavaType patched, AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {

        if (patched.containedTypeCount() == 0) {
            return chain.hasNext() ? chain.next().resolve(type, context, chain) : null;
        }

        AnnotatedType inner = new AnnotatedType(patched.containedType(0))
                .parent(type.getParent())
                .schemaProperty(type.isSchemaProperty())
                .resolveAsRef(type.isResolveAsRef())
                .propertyName(type.getPropertyName())
                .ctxAnnotations(type.getCtxAnnotations());

        return context.resolve(inner);
    }
}
